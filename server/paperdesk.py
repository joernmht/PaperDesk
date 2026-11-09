#!/usr/bin/env python3
"""PaperDesk server — the box side of Joern's PaperDesk Android app (contract: ../API.md).

    paperdesk.py serve [--port 8799]          HTTP API on 127.0.0.1 (tailnet :8447 via tailscale serve)
    paperdesk.py event --kind K --title T [--body B] [--ref R]   append an event (notify.sh mirrors here)

Inbox = the scout loop's paper picks (~/.claude/scout/papers.jsonl + candidates/*.json). Actions run as
queued jobs, one at a time (RAM): corpus intake = headless `claude -p` with the literature-intake skill,
"include in paper" = a cite note queued in that paper session's cockpit INBOX-literature.md,
reMarkable = rmapi + ~/.claude/loops/remarkable_papers.py, handwritten marks → LaTeX = headless
`claude -p` with the remarkable-notes skill. Events (job done, new scout picks, notify.sh mirror) are
polled by the app's background worker and shown as Android notifications.
Stdlib only, plus `requests` and PyMuPDF (system python3) through the reused skill modules.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import datetime as dt
import hmac
import importlib.util
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "0.1"
HOME = Path.home()
STATE_DIR = HOME / ".local/state/paperdesk"
STATE = STATE_DIR / "state.json"
EVENTS = STATE_DIR / "events.jsonl"
JOBS = STATE_DIR / "jobs"
TOKEN_FILE = HOME / ".config/paperdesk/token"
DIST = Path(__file__).resolve().parent.parent / "dist"
SCOUT = HOME / ".claude/scout"
SKILLS = HOME / ".claude/skills"
LOOPS = HOME / ".claude/loops"
RMAPI = str(HOME / ".local/bin/rmapi")
RMPAPERS = LOOPS / "remarkable_papers.py"
ANNOT = HOME / "remarkable/annotated"
LIT = HOME / "LiteratureAssistant"
CLAUDE = str(HOME / ".nvm/versions/node/v24.14.1/bin/claude")
TOREAD_DIR = "/Papers/To read"
# paper -> (label, cockpit of the session that owns its manuscript)
PAPERS = {"p0": ("P0 raiLPminer", "paper1"), "p1": ("P1 LP Mining with LP2Graph", "paper1"),
          "p2": ("P2 raiLParchitect", "paper2"), "p3": ("P3 raiLPoperator", "paper3")}

LOCK = threading.RLock()


def now() -> str:
    return dt.datetime.now().strftime("%Y-%m-%dT%H:%M:%S")


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


lit = _load_module("pd_lit", SKILLS / "literature/scripts/lit.py")
pc = _load_module("pd_pc", SKILLS / "scout/scripts/paper_candidates.py")
rmp = _load_module("pd_rmp", RMPAPERS)
pc.load_secrets()


# ------------------------------------------------------------------ state + events
def load_state() -> dict:
    with LOCK:
        if STATE.exists():
            return json.loads(STATE.read_text())
        return {"items": {}, "extra": {}, "seen_scout": []}


def save_state(st: dict) -> None:
    with LOCK:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        tmp = STATE.with_suffix(".tmp")
        tmp.write_text(json.dumps(st, indent=1, ensure_ascii=False))
        tmp.replace(STATE)


def add_event(kind: str, title: str, body: str = "", ref: str | None = None) -> dict:
    with LOCK:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        seq = 0
        if EVENTS.exists():
            lines = EVENTS.read_text().splitlines()
            if lines:
                seq = json.loads(lines[-1])["seq"]
        ev = {"seq": seq + 1, "ts": now(), "kind": kind, "title": title, "body": body, "ref": ref}
        with EVENTS.open("a") as f:
            f.write(json.dumps(ev, ensure_ascii=False) + "\n")
        return ev


def events_since(since: int) -> dict:
    evs = [json.loads(l) for l in EVENTS.read_text().splitlines()] if EVENTS.exists() else []
    last = evs[-1]["seq"] if evs else 0
    out = [e for e in evs if e["seq"] > since] if since > 0 else evs[-30:]
    return {"events": out[-200:], "last": last}


DECISIONS = STATE_DIR / "decisions.jsonl"


def log_decision(item: dict, action: str, target: str | None = None) -> None:
    """Append-only record of Joern's choices (feeds the app's history and the scout loops' taste)."""
    rec = {"ts": now(), "id": item["id"], "title": item["title"], "action": action, "target": target,
           "source": item.get("source"), "cat": item.get("cat"), "run": item.get("run"), "why": item.get("why"),
           "doi": item.get("doi"), "arxiv": item.get("arxiv")}
    with LOCK, DECISIONS.open("a") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def decisions(n: int = 300) -> dict:
    recs = [json.loads(l) for l in DECISIONS.read_text().splitlines() if l.strip()] if DECISIONS.exists() else []
    stats = {"dismissed": 0, "literature": 0, "remarkable": 0, "later": 0, "include": {k: 0 for k in PAPERS}}
    for r in recs:
        if r["action"] == "include" and r.get("target") in stats["include"]:
            stats["include"][r["target"]] += 1
        elif r["action"] == "dismiss":
            stats["dismissed"] += 1
        elif r["action"] in stats:
            stats[r["action"]] += 1
    return {"decisions": recs[::-1][:n], "stats": stats}


# ------------------------------------------------------------------ papers
def norm_title(t: str) -> str:
    return pc.norm_title(t)


def paper_id(p: dict) -> str:
    if p.get("doi"):
        return "doi:" + p["doi"].lower()
    if p.get("arxiv"):
        return "arxiv:" + p["arxiv"]
    return "t:" + norm_title(p.get("title", ""))


def corpus_index() -> tuple[dict, dict]:
    """doi/arxiv id -> bibkey and normalised title -> bibkey, from the literature corpus."""
    ids, titles = {}, {}
    for e in lit.load_entries():
        if e["doi"]:
            ids["doi:" + e["doi"].lower().replace("https://doi.org/", "")] = e["key"]
        m = re.search(r"(\d{4}\.\d{4,5})", e["raw"])
        if m and ("arxiv" in e["raw"].lower()):
            ids["arxiv:" + m.group(1)] = e["key"]
        titles[norm_title(e["title"].replace("{", "").replace("}", ""))] = e["key"]
    return ids, titles


def enrich(p: dict, st: dict, cidx=None) -> dict:
    ids, titles = cidx or corpus_index()
    pid = p.get("id") or paper_id(p)
    rec = st["items"].get(pid, {})
    key = ids.get(pid) or titles.get(norm_title(p.get("title", ""))) or rec.get("bibkey")
    pdf_url = p.get("pdf_url")
    if not pdf_url and p.get("arxiv"):
        pdf_url = f"https://arxiv.org/pdf/{p['arxiv']}"
    out = {
        "id": pid, "title": p.get("title") or "", "authors": p.get("authors") or [],
        "year": (p.get("year") or (p.get("date") or "")[:4]) or None, "venue": p.get("venue") or None,
        "abstract": p.get("abstract") or None, "url": p.get("url"), "pdf_url": pdf_url,
        "doi": p.get("doi"), "arxiv": p.get("arxiv"), "why": p.get("why"), "cat": p.get("cat"),
        "run": p.get("run"), "source": p.get("source") or "scout",
        "status": rec.get("status", "added" if key else "new"),
        "in_corpus": bool(key), "bibkey": key, "has_pdf": bool(pdf_url) or bool(key),
        "targets": rec.get("targets", []),
    }
    if out["source"] == "library":
        out["status"] = rec.get("status", "added")
    return out


def scout_items() -> list[dict]:
    """Scout picks joined with the candidate metadata of their run."""
    picks = []
    led = SCOUT / "papers.jsonl"
    if led.exists():
        picks = [json.loads(l) for l in led.read_text().splitlines() if l.strip()]
    meta = {}
    for f in sorted((SCOUT / "candidates").glob("papers-*.json")):
        try:
            for c in json.loads(f.read_text()).get("candidates", []):
                meta.setdefault(c.get("id") or paper_id(c), c)
        except (json.JSONDecodeError, OSError):
            continue
    out = []
    for p in picks:
        m = dict(meta.get(p["id"], {}))
        m.update({k: v for k, v in p.items() if v is not None})
        m.setdefault("authors", [])
        if p["id"].startswith("doi:") and not m.get("doi"):
            m["doi"] = p["id"][4:]
        if p["id"].startswith("arxiv:") and not m.get("arxiv"):
            m["arxiv"] = p["id"][6:]
        m["source"] = "scout"
        out.append(m)
    return out


def inbox(status: str) -> list[dict]:
    st = load_state()
    cidx = corpus_index()
    items = [enrich(p, st, cidx) for p in scout_items()]
    seen = {i["id"] for i in items}
    items += [enrich(p, st, cidx) for pid, p in st["extra"].items() if pid not in seen]
    items.sort(key=lambda i: (i["run"] or "", i["title"]), reverse=True)
    if status != "all":
        items = [i for i in items if i["status"] in ("new", "later", "pending")]
    return items


def find_paper(pid: str) -> dict | None:
    st = load_state()
    for p in scout_items():
        if p["id"] == pid:
            return p
    return st["extra"].get(pid)


def set_item(pid: str, paper: dict | None = None, **kw) -> None:
    with LOCK:
        st = load_state()
        rec = st["items"].setdefault(pid, {})
        rec.update({k: v for k, v in kw.items() if v is not None})
        if paper and not any(p["id"] == pid for p in scout_items()):
            st["extra"][pid] = {k: paper.get(k) for k in
                                ("id", "title", "authors", "year", "venue", "abstract", "url", "pdf_url",
                                 "doi", "arxiv", "why", "cat", "run", "source")}
        save_state(st)


# ------------------------------------------------------------------ search
TEXT_CACHE_FILE = STATE_DIR / "pdftext.json"
_TEXT_CACHE: dict[str, list] = {}


def _pdf_text(path: Path) -> str:
    mt = path.stat().st_mtime
    c = _TEXT_CACHE.get(str(path))
    if c and c[0] == mt:
        return c[1]
    try:
        txt = " ".join(lit.pdf_text(path, None).lower().split())
    except (Exception, SystemExit):  # lit.py sys.exit()s when PyMuPDF is missing
        txt = ""
    _TEXT_CACHE[str(path)] = [mt, txt]
    return txt


def _library_docs() -> list[tuple[dict, list, str, str]]:
    docs, seen = [], set()
    for e in lit.load_entries():          # research_2026.bib and 0_literature.bib share keys
        if e["key"] in seen:
            continue
        seen.add(e["key"])
        meta = " ".join([e["key"], e["title"], e["author"], e["keywords"], e["year"]]).lower()
        pdfs = lit.resolve_pdfs(e)
        text = _pdf_text(pdfs[0]) if pdfs and pdfs[0].stat().st_size > 5000 else ""
        docs.append((e, pdfs, meta, text))
    return docs


def warm_text_cache() -> None:
    global _TEXT_CACHE
    try:
        _TEXT_CACHE = json.loads(TEXT_CACHE_FILE.read_text())
    except (OSError, ValueError):
        _TEXT_CACHE = {}
    n = len(_TEXT_CACHE)
    _library_docs()
    if len(_TEXT_CACHE) != n or not TEXT_CACHE_FILE.exists():
        TEXT_CACHE_FILE.write_text(json.dumps(_TEXT_CACHE))


def search_library(q: str, n: int = 25) -> list[dict]:
    import math
    terms = [t for t in re.findall(r"\w[\w-]+", q.lower()) if len(t) > 1]
    if not terms:
        return []
    docs = _library_docs()
    N = len(docs) or 1
    idf = {t: math.log(1 + N / (1 + sum(1 for d in docs if t in d[2] or t in d[3]))) for t in terms}
    st = load_state()
    res = []
    for e, pdfs, meta, text in docs:
        score, hits = 0.0, 0
        for t in terms:
            m, f = meta.count(t), text.count(t)
            if m or f:
                hits += 1
            score += idf[t] * (3 * min(m, 2) + min(f, 40) ** 0.5)
        if score <= 0:
            continue
        score *= (hits / len(terms)) ** 2
        abstract = None
        if text:
            i = min((text.find(t) for t in terms if t in text), default=-1)
            if i >= 0:
                abstract = "… " + text[max(0, i - 160): i + 240] + " …"
        p = {"id": "bib:" + e["key"], "title": e["title"].replace("{", "").replace("}", ""),
             "authors": [a.strip() for a in re.split(r"\s+and\s+", e["author"]) if a.strip()][:6],
             "year": e["year"], "venue": e["keywords"], "abstract": abstract,
             "doi": e["doi"] or None, "url": f"https://doi.org/{e['doi']}" if e["doi"] else None,
             "source": "library"}
        it = enrich(p, st, ({}, {}))
        it.update({"in_corpus": True, "bibkey": e["key"], "has_pdf": bool(pdfs)})
        res.append((score, it))
    res.sort(key=lambda x: -x[0])
    return [r[1] for r in res[:n]]


def _arxiv_q(q: str, n: int) -> list[dict]:
    """arXiv relevance search: all terms first; arXiv's AND is strict, so fall back to any term."""
    terms = re.findall(r'"[^"]+"|\S+', q)
    out = _arxiv_run(" AND ".join(f"all:{t}" for t in terms), n)
    if len(out) < 3 and len(terms) > 1:
        have = {p["arxiv"] for p in out}
        out += [p for p in _arxiv_run(" OR ".join(f"all:{t}" for t in terms), n) if p["arxiv"] not in have]
    return out[:n]


def _arxiv_run(sq: str, n: int) -> list[dict]:
    r = pc.get("https://export.arxiv.org/api/query",
               params={"search_query": sq, "sortBy": "relevance", "max_results": n})
    if r is None or r.status_code != 200:
        return []
    import xml.etree.ElementTree as ET
    A = pc.ATOM
    out = []
    for e in ET.fromstring(r.content).findall("a:entry", A):
        aid = re.sub(r"v\d+$", "", e.findtext("a:id", "", A).rsplit("/abs/", 1)[-1])
        doi = e.findtext("{http://arxiv.org/schemas/atom}doi")
        out.append({"title": " ".join(e.findtext("a:title", "", A).split()),
                    "abstract": " ".join(e.findtext("a:summary", "", A).split()),
                    "authors": [a.findtext("a:name", "", A) for a in e.findall("a:author", A)][:6],
                    "date": e.findtext("a:published", "", A)[:10], "venue": "arXiv", "arxiv": aid,
                    "doi": (doi or "").lower() or None, "url": f"https://arxiv.org/abs/{aid}",
                    "pdf_url": f"https://arxiv.org/pdf/{aid}", "source": "arxiv"})
    return out


def _openalex_q(q: str, n: int) -> list[dict]:
    params = {"search": q, "per-page": n}
    if os.environ.get("OPENALEX_MAILTO"):
        params["mailto"] = os.environ["OPENALEX_MAILTO"]
    r = pc.get("https://api.openalex.org/works", params=params)
    if r is None or r.status_code != 200:
        return []
    out = []
    for w in r.json().get("results", []):
        p = pc._oa_work(w)
        oa = w.get("best_oa_location") or {}
        p["pdf_url"] = oa.get("pdf_url")
        out.append(p)
    return out


def _scopus_q(q: str, n: int) -> list[dict]:
    key = os.environ.get("ELSEVIER_API_KEY") or os.environ.get("SCOPUS_API_KEY")
    if not key:
        return []
    headers = {"X-ELS-APIKey": key, "Accept": "application/json"}
    if os.environ.get("ELSEVIER_INSTTOKEN"):
        headers["X-ELS-Insttoken"] = os.environ["ELSEVIER_INSTTOKEN"]
    r = pc.get("https://api.elsevier.com/content/search/scopus", headers=headers,
               params={"query": f"TITLE-ABS-KEY({q})", "count": n, "sort": "relevancy"})
    if r is None or r.status_code != 200:
        return []
    out = []
    for e in r.json().get("search-results", {}).get("entry", []):
        if "error" in e:
            continue
        doi = (e.get("prism:doi") or "").lower() or None
        out.append({"title": e.get("dc:title", ""), "abstract": "", "authors": [e.get("dc:creator", "")],
                    "date": e.get("prism:coverDate", ""), "venue": e.get("prism:publicationName", ""),
                    "doi": doi, "arxiv": None, "url": f"https://doi.org/{doi}" if doi else None,
                    "source": "scopus"})
    return out


def search_web(q: str) -> list[dict]:
    with cf.ThreadPoolExecutor(3) as ex:
        futs = [ex.submit(f, q, n) for f, n in ((_openalex_q, 15), (_arxiv_q, 10), (_scopus_q, 10))]
        lists = []
        for f in futs:
            try:
                lists.append(f.result(timeout=40))
            except Exception:
                lists.append([])
    # interleave sources, dedupe by id / title
    merged, seen = [], set()
    for row in range(max(map(len, lists), default=0)):
        for l in lists:
            if row < len(l):
                p = l[row]
                keys = {paper_id(p), "t:" + norm_title(p["title"])}
                if keys & seen or not p["title"]:
                    continue
                seen |= keys
                merged.append(p)
    st, cidx = load_state(), corpus_index()
    out = []
    for p in merged:
        p["id"] = paper_id(p)
        it = enrich(p, st, cidx)
        if it["status"] == "added" and not it["in_corpus"]:
            it["status"] = "new"
        out.append(it)
    return out


# ------------------------------------------------------------------ jobs
JOBQ: "queue.Queue[str]" = queue.Queue()


def job_path(jid: str) -> Path:
    return JOBS / f"{jid}.json"


def job_load(jid: str, tail: int = 1500) -> dict:
    j = json.loads(job_path(jid).read_text())
    log = JOBS / f"{jid}.log"
    j["log_tail"] = log.read_text(errors="replace")[-tail:] if log.exists() else None
    for k in ("spec",):
        j.pop(k, None)
    return j


def job_save(j: dict) -> None:
    with LOCK:
        JOBS.mkdir(parents=True, exist_ok=True)
        job_path(j["id"]).write_text(json.dumps(j, indent=1, ensure_ascii=False))


def new_job(kind: str, title: str, spec: dict) -> dict:
    jid = dt.datetime.now().strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:4]
    j = {"id": jid, "kind": kind, "title": title, "status": "queued", "created": now(),
         "started": None, "finished": None, "summary": None, "spec": spec}
    job_save(j)
    JOBQ.put(jid)
    return job_load(jid)


def list_jobs(n: int = 50) -> list[dict]:
    if not JOBS.exists():
        return []
    ids = sorted((p.stem for p in JOBS.glob("*.json")), reverse=True)[:n]
    return [job_load(i, 600) for i in ids]


def _run(cmd: list[str], log: Path, cwd: Path | None = None, timeout: int = 3600) -> tuple[int, str]:
    with log.open("a") as f:
        f.write(f"$ {' '.join(cmd)[:400]}\n")
        f.flush()
        try:
            r = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                               timeout=timeout)
        except subprocess.TimeoutExpired:
            f.write("TIMEOUT\n")
            return 124, "timeout"
        f.write(r.stdout)
        return r.returncode, r.stdout


def _claude(prompt: str, log: Path, cwd: Path, model: str, effort: str, timeout: int = 3600) -> str:
    rc, out = _run([CLAUDE, "-p", prompt, "--model", model, "--effort", effort,
                    "--dangerously-skip-permissions"], log, cwd=cwd, timeout=timeout)
    m = re.findall(r"^RESULT:\s*(.+)$", out, re.M)
    if rc != 0 or not m:
        raise RuntimeError(f"claude exit {rc}; no RESULT line")
    if m[-1].upper().startswith("FAILED"):
        raise RuntimeError(m[-1])
    return m[-1].strip()


def _safe(name: str) -> str:
    return re.sub(r"[^\w.\- ]+", "_", name).strip()[:120] or "doc"


def _fetch_pdf(paper: dict, dest: Path) -> bool:
    """Corpus PDF first, then the open-access URL; True if dest now holds a real PDF."""
    if paper.get("bibkey"):
        e = rmp.entries().get(paper["bibkey"])
        src = rmp.pdf_for(paper["bibkey"], e) if e else None
        if src:
            shutil.copy(src, dest)
            return True
    urls = [paper.get("pdf_url")]
    if paper.get("doi"):
        r = pc.get(f"https://api.openalex.org/works/doi:{paper['doi']}")
        if r is not None and r.status_code == 200:
            urls.append(((r.json().get("best_oa_location") or {}).get("pdf_url")))
    for u in filter(None, urls):
        r = pc.get(u)
        if r is not None and r.status_code == 200 and r.content[:4] == b"%PDF":
            dest.write_bytes(r.content)
            return True
    return False


INTAKE_PROMPT = """Use the `literature-intake` skill to add ONE paper to the corpus at ~/LiteratureAssistant.
Joern asked for it from his PaperDesk phone app. Paper metadata (JSON, from scout/search):
{meta}
Follow the skill exactly: get the PDF (arXiv / open access; a TODO-DOWNLOAD stub if paywalled), verify it is the
right paper, classify, unique bibkey, `intake.py add`, then commit and push ~/LiteratureAssistant.
{toread}
If it is already in the corpus (`lit.py search`), do not add it twice; just report its key.
Your LAST line must be exactly `RESULT: bibkey=<key> pdf=<yes|stub>` or `RESULT: FAILED <short reason>`."""

TRANSFER_PROMPT = """Use the `remarkable-notes` skill: transfer Joern's handwritten reMarkable marks on his draft of
{label} ({pid}{hashnote}) into the LaTeX source, then build, lint, commit and push as the skill says.
Joern started this from his PaperDesk phone app, so there is nobody to ask: decide sensibly and flag doubts "(?)".
Append the one-line journal entry to ~/sessions/admin/LOG.md.
Your LAST line must be exactly `RESULT: <n> marks on <m> pages -> commit <hash>` or `RESULT: FAILED <short reason>`."""


def _queue_cite(target: str, bibkey: str, paper: dict) -> Path:
    label, sess = PAPERS[target]
    f = HOME / "sessions" / sess / "INBOX-literature.md"
    if not f.exists():
        f.write_text("# Literature inbox (from Joern's PaperDesk app)\n\n"
                     "Joern tapped \"Include in paper\" on these. Work each into the named paper (bib entry from\n"
                     "`lit.py cite <key>` into the paper's own .bib, cite it where it fits), then tick it `[x]`.\n\n")
    with f.open("a") as fh:
        fh.write(f"- [ ] {dt.date.today()} **{target.upper()}** cite `{bibkey}`: {paper.get('title')}"
                 + (f" (why: {paper['why']})" if paper.get("why") else "") + "\n")
    return f


def run_job(jid: str) -> None:
    j = json.loads(job_path(jid).read_text())
    spec, log = j["spec"], JOBS / f"{jid}.log"
    j.update(status="running", started=now())
    job_save(j)
    try:
        summary = JOB_KINDS[j["kind"]](spec, log)
        j.update(status="done", summary=summary)
    except Exception as e:  # noqa: BLE001 - every failure must land in the job record
        with log.open("a") as f:
            traceback.print_exc(file=f)
        j.update(status="failed", summary=str(e)[:300])
        if spec.get("pid_item"):
            set_item(spec["pid_item"], status=spec.get("prev_status", "new"))
    j["finished"] = now()
    job_save(j)
    icon = "✅" if j["status"] == "done" else "❌"
    add_event("job", f"{icon} {j['title']}", j["summary"] or "", ref=jid)


def k_literature(spec: dict, log: Path) -> str:
    paper = spec["paper"]
    if paper.get("bibkey"):
        key = paper["bibkey"]
        res = f"already in the corpus as {key}"
    else:
        toread = ("Joern wants to READ it: add `toread` to its keywords (the box then sends it to his reMarkable)."
                  if spec.get("toread") else "Do NOT add the `toread` keyword.")
        meta = json.dumps({k: paper.get(k) for k in ("title", "authors", "year", "venue", "doi", "arxiv",
                                                    "url", "pdf_url", "abstract", "why")}, ensure_ascii=False)
        res = _claude(INTAKE_PROMPT.format(meta=meta, toread=toread), log, LIT, "claude-sonnet-5-5", "medium", 1800)
        key = re.search(r"bibkey=(\S+)", res).group(1)
        res = f"added as {key} ({res.split('pdf=')[-1]} PDF)" if "pdf=" in res else f"added as {key}"
    if spec.get("toread") and paper.get("bibkey"):
        _send_to_remarkable(paper, log)
        res += ", sent to reMarkable"
    status = "added"
    if spec.get("target"):
        f = _queue_cite(spec["target"], key, paper)
        res += f"; cite queued for {spec['target'].upper()} ({f.parent.name} session)"
        status = "included"
    with LOCK:
        st = load_state()
        rec = st["items"].setdefault(spec["pid_item"], {})
        rec.update(status=status, bibkey=key)
        if spec.get("target"):
            rec["targets"] = sorted(set(rec.get("targets", [])) | {spec["target"]})
        save_state(st)
    return res


def _send_to_remarkable(paper: dict, log: Path) -> str:
    first = (paper.get("authors") or ["?"])[0]
    sur = first.split(",")[0] if "," in first else (first.split() or ["?"])[-1]
    name = _safe(f"{sur} {paper.get('year') or ''} - {paper.get('title', '')}")
    with tempfile.TemporaryDirectory() as td:
        pdf = Path(td) / f"{name}.pdf"
        if not _fetch_pdf(paper, pdf):
            raise RuntimeError("no open PDF found (paywalled?) — add it to the corpus with a PDF first")
        rmp.rmapi("mkdir", "/Papers")
        rmp.rmapi("mkdir", TOREAD_DIR)
        rc, out = _run([RMAPI, "put", str(pdf), TOREAD_DIR], log, timeout=300)
        if rc != 0 and "already exists" not in out:
            raise RuntimeError(f"rmapi put failed: {out.strip()[-200:]}")
    # remember it so remarkable_papers.py pull / PaperDesk can fetch the annotated copy later
    st = rmp.load_state()
    key = paper.get("bibkey") or _safe(paper["id"]).replace(" ", "_")
    st.setdefault(key, {"pdf": f"{name}.pdf", "remote": f"{TOREAD_DIR}/{name}", "sent": now(), "via": "paperdesk"})
    rmp.save_state(st)
    return name


def k_remarkable(spec: dict, log: Path) -> str:
    name = _send_to_remarkable(spec["paper"], log)
    set_item(spec["pid_item"], status=spec.get("prev_status") if spec.get("prev_status") not in ("new", None)
             else "later")
    return f"on the tablet: {TOREAD_DIR}/{name}"


def k_rm_draft(spec: dict, log: Path) -> str:
    rc, out = _run([sys.executable, str(RMPAPERS), "draft", spec["pid"]], log, timeout=1800)
    if rc != 0:
        raise RuntimeError(out.strip().splitlines()[-1][:250] if out.strip() else f"exit {rc}")
    return (out.strip().splitlines() or ["sent"])[-1][:250]


def k_rm_draftpull(spec: dict, log: Path) -> str:
    cmd = [sys.executable, str(RMPAPERS), "draftpull", spec["pid"]] + ([spec["hash"]] if spec.get("hash") else [])
    rc, out = _run(cmd, log, timeout=600)
    if rc != 0:
        raise RuntimeError(out.strip()[-250:])
    return "annotated copy fetched: " + Path(out.split(":", 1)[1].split()[0]).name


def k_rm_pull(spec: dict, log: Path) -> str:
    path = spec["path"]
    ANNOT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        rc, out = _run([RMAPI, "geta", path], log, cwd=Path(td), timeout=600)
        pdfs = list(Path(td).glob("*.pdf"))
        if rc != 0 or not pdfs:
            raise RuntimeError(f"no annotated PDF: {out.strip()[-200:]}")
        dest = ANNOT / (_safe(path.rsplit("/", 1)[-1]) + "_annotated.pdf")
        shutil.move(str(pdfs[0]), dest)
    return f"annotated copy fetched: {dest.name}"


def k_rm_transfer(spec: dict, log: Path) -> str:
    label = PAPERS[spec["pid"]][0]
    hashnote = f", version {spec['hash']}" if spec.get("hash") else ", the most recently annotated version"
    return _claude(TRANSFER_PROMPT.format(label=label, pid=spec["pid"], hashnote=hashnote), log, HOME / "sessions/admin",
                   "claude-opus-5-5", "high", 5400)


def k_rm_archive(spec: dict, log: Path) -> str:
    path = spec["path"]
    parent = path.rsplit("/", 1)[0] or "/"
    dest = parent + "/Older" if not parent.endswith("/Older") else parent
    rmp.rmapi("mkdir", dest)
    rc, out = _run([RMAPI, "mv", path, dest], log, timeout=120)
    if rc != 0:
        raise RuntimeError(out.strip()[-200:])
    _rm_state_move(path, f"{dest}/{path.rsplit('/', 1)[-1]}")
    return f"moved to {dest}"


def k_rm_delete(spec: dict, log: Path) -> str:
    rc, out = _run([RMAPI, "rm", spec["path"]], log, timeout=120)
    if rc != 0:
        raise RuntimeError(out.strip()[-200:])
    return f"deleted {spec['path']}"


def _rm_state_move(old: str, new: str) -> None:
    st = rmp.load_state()
    for k, v in list(st.items()) + list(st.get("_drafts", {}).items()):
        if isinstance(v, dict) and v.get("remote") == old:
            v["remote"] = new
    rmp.save_state(st)


JOB_KINDS = {"literature": k_literature, "remarkable": k_remarkable, "rm_draft": k_rm_draft,
             "rm_draftpull": k_rm_draftpull, "rm_pull": k_rm_pull, "rm_transfer": k_rm_transfer,
             "rm_archive": k_rm_archive, "rm_delete": k_rm_delete}


def worker() -> None:
    while True:
        jid = JOBQ.get()
        try:
            run_job(jid)
        except Exception:  # noqa: BLE001
            traceback.print_exc()


def requeue_interrupted() -> None:
    """A restart mid-job leaves 'running'/'queued' records: mark running ones failed, requeue queued ones."""
    if not JOBS.exists():
        return
    for p in sorted(JOBS.glob("*.json")):
        j = json.loads(p.read_text())
        if j["status"] == "running":
            j.update(status="failed", summary="interrupted by a server restart", finished=now())
            job_save(j)
        elif j["status"] == "queued":
            JOBQ.put(j["id"])


def scout_watch() -> None:
    """Event when the scout loop records new picks."""
    while True:
        try:
            ids = [p["id"] for p in scout_items()]
            with LOCK:
                st = load_state()
                seen = set(st.get("seen_scout", []))
                fresh = [i for i in ids if i not in seen]
                if seen and fresh:
                    titles = [p["title"] for p in scout_items() if p["id"] in fresh]
                    add_event("scout", f"📚 {len(fresh)} new paper(s) in your inbox",
                              "\n".join("• " + t[:90] for t in titles[:5]))
                if fresh or not seen:
                    st["seen_scout"] = ids
                    save_state(st)
        except Exception:  # noqa: BLE001
            traceback.print_exc()
        time.sleep(120)


# ------------------------------------------------------------------ actions
def do_action(body: dict) -> dict:
    pid, action = body.get("id"), body.get("action")
    paper = find_paper(pid) or body.get("paper")
    if not paper:
        raise KeyError(f"unknown paper {pid}")
    paper = dict(paper, id=pid)
    st = load_state()
    item = enrich(paper, st)
    prev = item["status"]
    job = None
    if action in ("dismiss", "later", "restore", "literature", "include", "remarkable"):
        log_decision(item, action, body.get("target") if action == "include" else None)
    if action in ("dismiss", "later", "restore"):
        set_item(pid, paper, status={"dismiss": "dismissed", "later": "later", "restore": "new"}[action])
    elif action in ("literature", "include"):
        target = body.get("target")
        if action == "include" and target not in PAPERS:
            raise ValueError("target must be one of p0..p3")
        set_item(pid, paper, status="pending")
        what = f"Include in {target.upper()}" if action == "include" else "Add to literature"
        job = new_job("literature", f"{what}: {item['title'][:70]}",
                      {"paper": item, "target": target if action == "include" else None,
                       "toread": bool(body.get("toread")), "pid_item": pid, "prev_status": prev})
    elif action == "remarkable":
        set_item(pid, paper, status="pending")
        job = new_job("remarkable", f"To reMarkable: {item['title'][:70]}",
                      {"paper": item, "pid_item": pid, "prev_status": prev})
    else:
        raise ValueError(f"unknown action {action}")
    return {"item": enrich(paper, load_state()), "job": job}


def rm_action(body: dict) -> dict:
    op = body.get("op")
    pid, path = body.get("pid"), body.get("path")
    if op in ("draft", "draftpull", "transfer") and pid not in PAPERS:
        raise ValueError("pid must be one of p0..p3")
    if op in ("pull", "archive", "delete") and not (path and path.startswith("/")):
        raise ValueError("path required")
    if op in ("archive", "delete") and path.rstrip("/") in ("/Papers", "/Papers/To read", "/Papers/My drafts", ""):
        raise ValueError("refusing to move/delete a top-level folder")
    titles = {"draft": f"Send fresh draft {str(pid).upper()}", "draftpull": f"Pull annotations {str(pid).upper()}",
              "transfer": f"Marks → LaTeX {str(pid).upper()}", "pull": f"Pull annotated: {path}",
              "archive": f"Archive: {path}", "delete": f"Delete: {path}"}
    if op not in titles:
        raise ValueError(f"unknown op {op}")
    return {"job": new_job("rm_" + op, titles[op], {"pid": pid, "hash": body.get("hash"), "path": path})}


def rm_ls(path: str) -> dict:
    r = rmp.rmapi("ls", path)
    if r.returncode != 0:
        raise ValueError((r.stderr or r.stdout).strip()[-200:])
    entries = []
    for line in r.stdout.splitlines():
        m = re.match(r"\[(d|f)\]\s+(.+)$", line.strip())
        if m:
            entries.append({"name": m.group(2), "type": m.group(1), "path": path.rstrip("/") + "/" + m.group(2)})
    entries.sort(key=lambda e: (e["type"] != "d", e["name"].lower()))
    return {"path": path, "entries": entries}


def rm_drafts() -> dict:
    drafts = rmp.load_state().get("_drafts", {})
    pulled = {p.name for p in (ANNOT / "drafts").glob("*.pdf")} if (ANNOT / "drafts").exists() else set()
    out = []
    for pid, (label, _) in PAPERS.items():
        vs = []
        for k, v in drafts.items():
            if k.startswith(pid + "@"):
                h = k.split("@", 1)[1]
                fn = f"{pid}_{h}_annotated.pdf"
                vs.append({"hash": h, "sent": v.get("sent"), "remote": v.get("remote"),
                           "older": "/Older/" in (v.get("remote") or ""),
                           "annotated": f"drafts/{fn}" if fn in pulled else None})
        vs.sort(key=lambda x: x["sent"] or "", reverse=True)
        out.append({"pid": pid, "label": label, "versions": vs})
    return {"papers": out}


def rm_annotated() -> dict:
    files = []
    if ANNOT.exists():
        for p in sorted(ANNOT.rglob("*.pdf"), key=lambda p: -p.stat().st_mtime):
            files.append({"name": str(p.relative_to(ANNOT)), "size": p.stat().st_size,
                          "mtime": dt.datetime.fromtimestamp(p.stat().st_mtime).strftime("%Y-%m-%dT%H:%M:%S")})
    return {"files": files}


# ------------------------------------------------------------------ HTTP
def apk_version() -> int:
    f = DIST / "apk_version.txt"
    try:
        return int(f.read_text().strip())
    except (OSError, ValueError):
        return 0


class H(BaseHTTPRequestHandler):
    server_version = "PaperDesk/" + VERSION

    def log_message(self, fmt, *a):
        sys.stderr.write(f"{now()} {self.address_string()} {fmt % a}\n")

    def _send(self, code: int, obj=None, body: bytes | None = None, ctype="application/json"):
        data = body if body is not None else json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _authed(self) -> bool:
        tok = TOKEN_FILE.read_text().strip()
        got = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        return bool(got) and hmac.compare_digest(got, tok)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def _route(self, method: str):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        p = u.path
        if p == "/download/paperdesk.apk" and method == "GET":
            apk = DIST / "paperdesk.apk"
            if not apk.exists():
                return self._send(404, {"error": "no APK built yet"})
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="paperdesk.apk"')
            self.send_header("Content-Length", str(apk.stat().st_size))
            self.end_headers()
            with apk.open("rb") as f:
                shutil.copyfileobj(f, self.wfile)
            return
        if not (p.startswith("/api/") or p.startswith("/files/")):
            return self._send(404, {"error": "not found"})
        if not self._authed():
            return self._send(401, {"error": "unauthorized"})
        try:
            if method == "GET":
                if p == "/api/health":
                    return self._send(200, {"ok": True, "version": VERSION, "apk_version": apk_version()})
                if p == "/api/inbox":
                    return self._send(200, {"items": inbox(q.get("status", "open"))})
                if p == "/api/search":
                    text, scope = q.get("q", "").strip(), q.get("scope", "all")
                    if not text:
                        return self._send(400, {"error": "q required"})
                    lib = search_library(text) if scope in ("library", "all") else []
                    web = search_web(text) if scope in ("web", "all") else []
                    return self._send(200, {"library": lib, "web": web})
                if p == "/api/decisions":
                    return self._send(200, decisions())
                if p == "/api/jobs":
                    return self._send(200, {"jobs": list_jobs()})
                if p.startswith("/api/jobs/"):
                    jid = p.rsplit("/", 1)[-1]
                    if not re.fullmatch(r"[\w-]+", jid) or not job_path(jid).exists():
                        return self._send(404, {"error": "no such job"})
                    return self._send(200, job_load(jid, 8000))
                if p == "/api/events":
                    return self._send(200, events_since(int(q.get("since", "0") or 0)))
                if p == "/api/rm/ls":
                    return self._send(200, rm_ls(q.get("path", "/Papers")))
                if p == "/api/rm/drafts":
                    return self._send(200, rm_drafts())
                if p == "/api/rm/annotated":
                    return self._send(200, rm_annotated())
                if p.startswith("/files/annotated/"):
                    rel = urllib.parse.unquote(p[len("/files/annotated/"):])
                    f = (ANNOT / rel).resolve()
                    if not str(f).startswith(str(ANNOT.resolve()) + "/") or not f.is_file():
                        return self._send(404, {"error": "no such file"})
                    return self._send(200, body=f.read_bytes(), ctype="application/pdf")
            elif method == "POST":
                if p == "/api/action":
                    return self._send(200, do_action(self._body()))
                if p == "/api/rm/action":
                    return self._send(200, rm_action(self._body()))
                if p == "/api/crash":
                    trace = str(self._body().get("trace", ""))[:20000]
                    with (STATE_DIR / "crash.log").open("a") as f:
                        f.write(f"===== {now()}\n{trace}\n")
                    add_event("crash", "💥 PaperDesk app crashed", trace.splitlines()[1][:200] if "\n" in trace else trace[:200])
                    return self._send(200, {"ok": True})
            return self._send(404, {"error": "not found"})
        except (KeyError, ValueError) as e:
            return self._send(400, {"error": str(e)})
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            return self._send(500, {"error": f"{type(e).__name__}: {e}"})

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")


def serve(port: int) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    requeue_interrupted()
    threading.Thread(target=worker, daemon=True).start()
    threading.Thread(target=scout_watch, daemon=True).start()
    threading.Thread(target=warm_text_cache, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", port), H)
    print(f"{now()} PaperDesk {VERSION} on 127.0.0.1:{port}", file=sys.stderr, flush=True)
    srv.serve_forever()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--port", type=int, default=8799)
    e = sub.add_parser("event")
    e.add_argument("--kind", default="notify")
    e.add_argument("--title", required=True)
    e.add_argument("--body", default="")
    e.add_argument("--ref")
    a = ap.parse_args()
    if a.cmd == "serve":
        serve(a.port)
    else:
        add_event(a.kind, a.title, a.body, a.ref)
    return 0


if __name__ == "__main__":
    sys.exit(main())
