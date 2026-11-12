"""Portable building blocks of the PaperDesk server: no dependency on anything outside this repo.

Used by paperdesk.py when the config says `profile = "portable"` (the default on a fresh install):
config loading, a small HTTP client (stdlib urllib), a BibTeX library in one folder, deterministic paper
intake (BibTeX from doi.org or built from the metadata, open-access PDF), the topic watch that fills the
inbox, a thin rmapi wrapper for the optional reMarkable, and the pairing codes for the phone app.
The class shapes (`Library`, `Remarkable`) mirror the box modules paperdesk.py uses in the "box" profile
(lit.py, paper_candidates.py, remarkable_papers.py), so the server code is the same for both.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import tomllib
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HOME = Path.home()
CONFIG_DIR = Path(os.environ.get("PAPERDESK_CONFIG_DIR") or HOME / ".config/paperdesk")
STATE_DIR = Path(os.environ.get("PAPERDESK_STATE_DIR") or HOME / ".local/state/paperdesk")
UA = {"User-Agent": "PaperDesk/0.2 (personal paper desk; https://github.com/joernmht/PaperDesk)"}
ATOM = {"a": "http://www.w3.org/2005/Atom"}
RMAPI_VERSION = "v0.0.35"           # github.com/ddvk/rmapi (AGPL-3.0, separate program, downloaded on demand)

DEFAULTS = {
    "profile": "portable",          # "portable" | "box" (Joern's research box: scout loop, Claude skills)
    "name": "PaperDesk",
    "port": 8799,
    "public_url": "",               # e.g. https://myvm.tailnet-name.ts.net:8447 (install.sh fills it in)
    "agent_url": "",                # optional chat agent service
    "library": {"dir": str(HOME / "PaperDesk-library")},
    "remarkable": {"enabled": "auto", "rmapi": "", "folder": "/Papers/To read"},
    "watch": {"enabled": True, "per_topic": 6, "days": 45, "every_hours": 24},
    "targets": [],                  # [{id, label, bib}] : "Include in ..." copies the BibTeX entry there
}


def load_config() -> dict:
    cfg = json.loads(json.dumps(DEFAULTS))
    f = CONFIG_DIR / "config.toml"
    if f.exists():
        user = tomllib.loads(f.read_text(encoding="utf-8"))
        for k, v in user.items():
            if isinstance(v, dict) and isinstance(cfg.get(k), dict):
                cfg[k].update(v)
            else:
                cfg[k] = v
    return cfg


def load_secrets() -> None:
    """Optional API keys (OPENALEX_MAILTO, ELSEVIER_API_KEY, ...) from ~/.config/paperdesk/secrets.env."""
    f = CONFIG_DIR / "secrets.env"
    if not f.exists():
        return
    for line in f.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            v = v.strip().strip('"').strip("'")
            if v and not os.environ.get(k.strip()):
                os.environ[k.strip()] = v


# ------------------------------------------------------------------ HTTP
class Resp:
    def __init__(self, status: int, content: bytes):
        self.status_code, self.content = status, content

    @property
    def text(self) -> str:
        return self.content.decode("utf-8", "replace")

    def json(self):
        return json.loads(self.content)


def get(url: str, params: dict | None = None, headers: dict | None = None, timeout: int = 60) -> Resp | None:
    """GET with three tries; None when the host cannot be reached (same contract as the box helper)."""
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={**UA, **(headers or {})})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return Resp(r.status, r.read())
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                time.sleep(3 * (attempt + 1))
                continue
            return Resp(e.code, e.read() or b"")
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            print(f"  ! {url[:80]}: {e}", file=sys.stderr)
            time.sleep(3 * (attempt + 1))
    return None


def norm_title(t: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (t or "").lower())[:120]


def _oa_abstract(inv: dict | None) -> str:
    if not inv:
        return ""
    pos = sorted((p, w) for w, ps in inv.items() for p in ps)
    return " ".join(w for _, w in pos)


def _oa_work(w: dict) -> dict:
    doi = (w.get("doi") or "").replace("https://doi.org/", "").lower() or None
    loc = (w.get("primary_location") or {}).get("source") or {}
    arx = None
    for l in w.get("locations") or []:
        m = re.search(r"arxiv\.org/(?:abs|pdf)/([\d.]+)", (l.get("landing_page_url") or ""))
        if m:
            arx = m.group(1)
    return {"title": w.get("title") or "", "abstract": _oa_abstract(w.get("abstract_inverted_index")),
            "authors": [a["author"]["display_name"] for a in (w.get("authorships") or [])[:6]],
            "date": w.get("publication_date") or "", "venue": loc.get("display_name") or "",
            "doi": doi, "arxiv": arx, "type": w.get("type"),
            "url": f"https://doi.org/{doi}" if doi else w.get("id"), "source": "openalex"}


# ------------------------------------------------------------------ BibTeX library
def _field(body: str, name: str) -> str:
    m = re.search(rf"(?<![\w-]){name}\s*=\s*", body, re.I)
    if not m:
        return ""
    i = m.end()
    if i >= len(body):
        return ""
    if body[i] in "{\"":
        opener, depth, j = body[i], 0, i
        while j < len(body):
            c = body[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0 and opener == "{":
                    break
            elif c == "\"" and opener == "\"" and j > i and depth == 0:
                break
            j += 1
        val = body[i + 1:j]
    else:
        j = i
        while j < len(body) and body[j] not in ",\n":
            j += 1
        val = body[i:j]
    return re.sub(r"\s+", " ", val).strip()


def parse_bib(text: str, src: str = "") -> list[dict]:
    out = []
    for m in re.finditer(r"@(\w+)\s*\{", text):
        typ = m.group(1)
        if typ.lower() in ("comment", "string", "preamble"):
            continue
        start = text.index("{", m.start())
        depth, j = 0, start
        while j < len(text):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        body = text[start + 1:j]
        out.append({"type": typ, "key": body.split(",", 1)[0].strip(), "raw": text[m.start():j + 1], "src": src,
                    "author": _field(body, "author"), "title": _field(body, "title"),
                    "year": _field(body, "year"), "keywords": _field(body, "keywords"),
                    "doi": _field(body, "doi")})
    return out


def _ascii(s: str) -> str:
    return unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()


def surname(author: str) -> str:
    author = author.strip()
    if not author:
        return ""
    return author.split(",")[0].strip() if "," in author else author.split()[-1]


STOP = {"a", "an", "the", "on", "of", "for", "and", "in", "to", "with", "towards", "toward", "using", "via", "from"}


class Library:
    """One folder: library.bib + pdfs/<bibkey>.pdf. Plain files, so Zotero/JabRef/LaTeX can read them too."""

    def __init__(self, root: Path):
        self.root = Path(root).expanduser()
        self.bib = self.root / "library.bib"
        self.pdfdir = self.root / "pdfs"

    def ensure(self) -> None:
        self.pdfdir.mkdir(parents=True, exist_ok=True)
        if not self.bib.exists():
            self.bib.write_text("% PaperDesk library. One BibTeX entry per paper; PDFs in pdfs/<key>.pdf\n\n",
                                encoding="utf-8")

    # --- the interface paperdesk.py expects from the box's lit.py
    def load_entries(self) -> list[dict]:
        if not self.root.exists():
            return []
        out = []
        for f in sorted(self.root.glob("*.bib")):
            out += parse_bib(f.read_text(encoding="utf-8", errors="replace"), f.name)
        return out

    def resolve_pdfs(self, e: dict) -> list[Path]:
        p = self.pdfdir / f"{e['key']}.pdf"
        return [p] if p.exists() else []

    @staticmethod
    def pdf_text(path: Path, pages=None) -> str:
        try:
            import fitz  # PyMuPDF, optional: without it the search covers titles/authors/keywords only
        except ImportError:
            return ""
        doc = fitz.open(path)
        idx = pages if pages is not None else range(len(doc))
        return "\n".join(doc[i].get_text() for i in idx if 0 <= i < len(doc))

    # --- intake
    def new_key(self, paper: dict) -> str:
        first = (paper.get("authors") or [""])[0]
        sur = re.sub(r"[^a-z]", "", _ascii(surname(first)).lower()) or "anon"
        year = str(paper.get("year") or (paper.get("date") or "")[:4] or "nd")
        words = [w for w in re.findall(r"[a-z]+", _ascii(paper.get("title") or "").lower()) if w not in STOP]
        base = f"{sur}{year}{words[0] if words else ''}"
        have = {e["key"] for e in self.load_entries()}
        key, n = base, 1
        while key in have:
            n += 1
            key = f"{base}{chr(96 + n)}" if n <= 26 else f"{base}{n}"
        return key

    def add(self, paper: dict, toread: bool = False, fetch=get) -> tuple[str, bool]:
        """Add one paper: returns (bibkey, has_pdf). Never adds the same DOI/arXiv id/title twice."""
        self.ensure()
        for e in self.load_entries():
            same_doi = paper.get("doi") and e["doi"].lower().endswith(paper["doi"].lower())
            if same_doi or norm_title(re.sub(r"[{}]", "", e["title"])) == norm_title(paper.get("title") or "-"):
                return e["key"], bool(self.resolve_pdfs(e))
        key = self.new_key(paper)
        entry = None
        if paper.get("doi"):                       # the publisher's own BibTeX, re-keyed
            r = fetch(f"https://doi.org/{urllib.parse.quote(paper['doi'])}",
                      headers={"Accept": "application/x-bibtex"}, timeout=30)
            if r is not None and r.status_code == 200 and r.text.lstrip().startswith("@"):
                got = parse_bib(r.text)
                if got:
                    entry = re.sub(r"(@\w+\s*\{)\s*[^,]*,", lambda m: m.group(1) + key + ",", got[0]["raw"], count=1)
        if entry is None:
            entry = build_entry(key, paper)
        if toread:
            entry = add_keyword(entry, "toread")
        with self.bib.open("a", encoding="utf-8") as f:
            f.write("\n" + entry.strip() + "\n")
        return key, fetch_pdf(paper, self.pdfdir / f"{key}.pdf", fetch)

    def raw(self, key: str) -> str | None:
        return next((e["raw"] for e in self.load_entries() if e["key"] == key), None)


def _tex(s: str) -> str:
    return re.sub(r"([&%#_$])", r"\\\1", " ".join((s or "").split()))


def build_entry(key: str, p: dict) -> str:
    arx = p.get("arxiv")
    year = str(p.get("year") or (p.get("date") or "")[:4] or "")
    venue = p.get("venue") or ""
    typ = "misc" if (arx and (not venue or venue.lower() == "arxiv")) else "article"
    fields = [("title", "{" + _tex(p.get("title") or "") + "}"),
              ("author", " and ".join(_tex(a) for a in (p.get("authors") or []) if a)),
              ("year", year)]
    if typ == "article" and venue:
        fields.append(("journal", _tex(venue)))
    if p.get("doi"):
        fields.append(("doi", p["doi"]))
    if arx:
        fields += [("eprint", arx), ("archiveprefix", "arXiv")]
    url = p.get("url") or (f"https://arxiv.org/abs/{arx}" if arx else "")
    if url:
        fields.append(("url", url))
    body = ",\n".join(f"  {k} = {{{v}}}" for k, v in fields if v)
    return f"@{typ}{{{key},\n{body}\n}}"


def add_keyword(entry: str, kw: str) -> str:
    m = re.search(r"(keywords\s*=\s*\{)([^}]*)\}", entry, re.I)
    if m:
        have = [k.strip() for k in m.group(2).split(",") if k.strip()]
        if kw not in have:
            have.append(kw)
        return entry[:m.start()] + m.group(1) + ", ".join(have) + "}" + entry[m.end():]
    i = entry.rstrip().rfind("}")
    return entry[:i].rstrip().rstrip(",") + f",\n  keywords = {{{kw}}}\n}}"


def fetch_pdf(paper: dict, dest: Path, fetch=get) -> bool:
    urls = [paper.get("pdf_url")]
    if paper.get("arxiv"):
        urls.append(f"https://arxiv.org/pdf/{paper['arxiv']}")
    if paper.get("doi"):
        r = fetch(f"https://api.openalex.org/works/doi:{paper['doi']}")
        if r is not None and r.status_code == 200:
            try:
                urls.append((r.json().get("best_oa_location") or {}).get("pdf_url"))
            except ValueError:
                pass
    for u in dict.fromkeys(filter(None, urls)):
        r = fetch(u)
        if r is not None and r.status_code == 200 and r.content[:4] == b"%PDF":
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(r.content)
            return True
    return False


def copy_to_bib(raw: str, key: str, bibfile: Path) -> bool:
    """Append a BibTeX entry to a project's .bib unless that key is already there. True if written."""
    bibfile = Path(bibfile).expanduser()
    text = bibfile.read_text(encoding="utf-8", errors="replace") if bibfile.exists() else ""
    if any(e["key"] == key for e in parse_bib(text)):
        return False
    bibfile.parent.mkdir(parents=True, exist_ok=True)
    with bibfile.open("a", encoding="utf-8") as f:
        f.write(("\n" if text and not text.endswith("\n") else "") + "\n" + raw.strip() + "\n")
    return True


# ------------------------------------------------------------------ topic watch
TOPICS_FILE = STATE_DIR / "topics.json"


def topics_load() -> list[dict]:
    try:
        return json.loads(TOPICS_FILE.read_text())
    except (OSError, ValueError):
        return []


def topics_save(ts: list[dict]) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = TOPICS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(ts, indent=1, ensure_ascii=False))
    tmp.replace(TOPICS_FILE)


def topic_add(name: str, query: str) -> list[dict]:
    name, query = " ".join(name.split())[:80], " ".join(query.split())[:300]
    if not query:
        raise ValueError("a topic needs a search query")
    ts = topics_load()
    if len(ts) >= 20:
        raise ValueError("20 topics is the limit")
    ts.append({"id": "t" + secrets.token_hex(3), "name": name or query[:40], "query": query, "last_run": None})
    topics_save(ts)
    return ts


def topic_remove(tid: str) -> list[dict]:
    ts = [t for t in topics_load() if t["id"] != tid]
    topics_save(ts)
    return ts


def watch_search(query: str, days: int, n: int, fetch=get) -> list[dict]:
    """Recent work for one topic: OpenAlex (relevance, publication window) and arXiv (newest first)."""
    since = (dt.date.today() - dt.timedelta(days=days)).isoformat()
    out = []
    params = {"search": query, "per-page": n * 3, "filter": f"from_publication_date:{since},type:article|preprint|review"}
    if os.environ.get("OPENALEX_MAILTO"):
        params["mailto"] = os.environ["OPENALEX_MAILTO"]
    r = fetch("https://api.openalex.org/works", params=params)
    if r is not None and r.status_code == 200:
        for w in r.json().get("results", []):
            p = _oa_work(w)
            p["pdf_url"] = (w.get("best_oa_location") or {}).get("pdf_url")
            out.append(p)
    terms = re.findall(r'"[^"]+"|\S+', query)
    r = fetch("https://export.arxiv.org/api/query",
              params={"search_query": " AND ".join(f"all:{t}" for t in terms), "sortBy": "submittedDate",
                      "sortOrder": "descending", "max_results": n * 2})
    if r is not None and r.status_code == 200:
        import xml.etree.ElementTree as ET
        try:
            root = ET.fromstring(r.content)
        except ET.ParseError:
            root = None
        for e in (root.findall("a:entry", ATOM) if root is not None else []):
            date = e.findtext("a:published", "", ATOM)[:10]
            if date < since:
                continue
            aid = re.sub(r"v\d+$", "", e.findtext("a:id", "", ATOM).rsplit("/abs/", 1)[-1])
            doi = e.findtext("{http://arxiv.org/schemas/atom}doi")
            out.append({"title": " ".join(e.findtext("a:title", "", ATOM).split()),
                        "abstract": " ".join(e.findtext("a:summary", "", ATOM).split()),
                        "authors": [a.findtext("a:name", "", ATOM) for a in e.findall("a:author", ATOM)][:6],
                        "date": date, "venue": "arXiv", "arxiv": aid, "doi": (doi or "").lower() or None,
                        "url": f"https://arxiv.org/abs/{aid}", "pdf_url": f"https://arxiv.org/pdf/{aid}",
                        "source": "arxiv"})
    return out


# ------------------------------------------------------------------ reMarkable (optional)
class Remarkable:
    """Thin wrapper around the rmapi command-line client; same surface as the box's remarkable_papers.py."""

    def __init__(self, cfg: dict, library: Library):
        self.cfg, self.library = cfg, library
        self.bin = cfg.get("rmapi") or shutil.which("rmapi") or str(HOME / ".local/bin/rmapi")
        self.state = STATE_DIR / "remarkable.json"

    def available(self) -> bool:
        if str(self.cfg.get("enabled", "auto")).lower() in ("false", "no", "off", "0"):
            return False
        conf = Path(os.environ.get("RMAPI_CONFIG") or HOME / ".config/rmapi/rmapi.conf")
        return Path(self.bin).exists() and (conf.exists() or (HOME / ".rmapi").exists())

    def install(self, log) -> str:
        """Download the rmapi release for this machine into ~/.local/bin (only if missing)."""
        if Path(self.bin).exists():
            return self.bin
        import platform
        import tarfile
        arch = {"x86_64": "amd64", "amd64": "amd64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine().lower())
        if sys.platform != "linux" or not arch:
            raise RuntimeError(f"no rmapi build for {sys.platform}/{platform.machine()}; install rmapi by hand")
        url = f"https://github.com/ddvk/rmapi/releases/download/{RMAPI_VERSION}/rmapi-linux-{arch}.tar.gz"
        r = get(url, timeout=120)
        if r is None or r.status_code != 200:
            raise RuntimeError(f"could not download rmapi ({url})")
        import io
        with tarfile.open(fileobj=io.BytesIO(r.content)) as tf:
            m = next((m for m in tf.getmembers() if m.isfile() and Path(m.name).name == "rmapi"), None)
            if m is None:
                raise RuntimeError("rmapi archive has no rmapi binary")
            data = tf.extractfile(m).read()
        dest = HOME / ".local/bin/rmapi"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        dest.chmod(0o755)
        self.bin = str(dest)
        log.write(f"installed rmapi {RMAPI_VERSION} -> {dest}\n")
        return self.bin

    def connect(self, code: str, log) -> None:
        """Register this machine with the reMarkable cloud using the 8-letter one-time code."""
        code = re.sub(r"\s+", "", code or "")
        if not re.fullmatch(r"[A-Za-z0-9]{8}", code):
            raise ValueError("the one-time code has 8 letters")   # rmapi would loop forever on a bad length
        self.install(log)
        try:
            r = subprocess.run([self.bin, "ls", "/"], input=code + "\n", capture_output=True, text=True, timeout=120)
        except subprocess.TimeoutExpired:
            raise RuntimeError("the reMarkable cloud did not answer")
        log.write(r.stdout[-2000:] + r.stderr[-2000:])
        if r.returncode != 0:
            raise RuntimeError("code not accepted (they expire after a few minutes; get a new one)")

    def rmapi(self, *args):
        return subprocess.run([self.bin, *args], capture_output=True, text=True, timeout=180)

    def entries(self) -> dict:
        return {e["key"]: e for e in self.library.load_entries()}

    def pdf_for(self, key: str, e: dict):
        p = self.library.resolve_pdfs(e)
        return p[0] if p else None

    def load_state(self) -> dict:
        try:
            return json.loads(self.state.read_text())
        except (OSError, ValueError):
            return {}

    def save_state(self, st: dict) -> None:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        self.state.write_text(json.dumps(st, indent=1, ensure_ascii=False))


# ------------------------------------------------------------------ pairing
PAIR_FILE = STATE_DIR / "pair.json"
PAIR_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"      # no 0/O, 1/I/L
PAIR_TTL = 30 * 60
PAIR_MAX_FAILS = 10


def _pair_load() -> dict:
    try:
        return json.loads(PAIR_FILE.read_text())
    except (OSError, ValueError):
        return {"codes": {}, "fails": 0}


def _pair_save(d: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = PAIR_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(d))
    os.chmod(tmp, 0o600)
    tmp.replace(PAIR_FILE)


def pair_new() -> str:
    """A fresh pairing code (valid 30 minutes). Whoever opens /pair/<code> gets the desk's token."""
    d = _pair_load()
    now = time.time()
    d["codes"] = {c: t for c, t in d["codes"].items() if t > now}
    code = "".join(secrets.choice(PAIR_ALPHABET) for _ in range(8))
    d["codes"][code] = now + PAIR_TTL
    d["fails"] = 0
    _pair_save(d)
    return code[:4] + "-" + code[4:]


def pair_check(code: str) -> bool:
    code = re.sub(r"[^A-Z0-9]", "", (code or "").upper())
    d = _pair_load()
    ok = d["codes"].get(code, 0) > time.time()
    if not ok and d["codes"]:
        d["fails"] = d.get("fails", 0) + 1
        if d["fails"] >= PAIR_MAX_FAILS:           # guessing: drop every open code
            d["codes"] = {}
        _pair_save(d)
    return ok


def pair_link(base: str, token: str, name: str, agent: str = "") -> str:
    q = {"url": base, "token": token, "name": name}
    if agent:
        q["agent"] = agent
    return "paperdesk://pair?" + urllib.parse.urlencode(q)


def qr_terminal(text: str) -> str:
    """The text as a QR code drawn with half-block characters; '' when the optional `segno` is missing."""
    try:
        import segno
    except ImportError:
        return ""
    m = [list(row) for row in segno.make(text, error="m").matrix]
    q = 2
    w = len(m[0]) + 2 * q
    rows = [[0] * w for _ in range(q)] + [[0] * q + [int(bool(c)) for c in r] + [0] * q for r in m] + [[0] * w for _ in range(q)]
    if len(rows) % 2:
        rows.append([0] * w)
    out = []
    for i in range(0, len(rows), 2):               # dark-on-light, drawn inverted for dark terminals
        out.append("".join({(0, 0): "█", (0, 1): "▀", (1, 0): "▄", (1, 1): " "}[(a, b)]
                           for a, b in zip(rows[i], rows[i + 1])))
    return "\n".join(out)
