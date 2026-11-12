"""Papers for the PaperDesk app: LaTeX repos (Overleaf git bridge or GitHub) edited from the phone.

Every paper gets its OWN clone under ~/.local/state/paperdesk/papers/<id>, so app edits never touch a working
copy someone else (a person, a Claude session) is busy in. Save = pull, check that the file is still the one
the phone loaded (blob sha), write, commit that one file, push. A race ends in a 409 with the current text.

Comments are LaTeX macros, defined in `pdnotes.tex` (added to the paper on first use):
    \\pdmark{marked text}{comment}   a comment on a passage
    \\pdnote{comment}                a comment at a point
plus the reMarkable marks \\rmnote[p.N]{..}, \\rmhl{..}, \\rmdel{..} from the remarkable-notes skill.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import threading
import time
import urllib.parse
from pathlib import Path

import pdlib

TEXT_EXT = {".tex", ".bib", ".sty", ".cls", ".bst", ".md", ".txt", ".cfg", ".bbx", ".cbx"}
MAX_FILE = 2_000_000
CLONES = pdlib.STATE_DIR / "papers"
BUILDS = pdlib.STATE_DIR / "builds"
REGISTRY = pdlib.STATE_DIR / "papers.json"     # portable desks: papers added from the app
LOCKS: dict[str, threading.Lock] = {}
_FETCHED: dict[str, float] = {}
NEVER = {"69c1ccc574a3b46e16cc3427"}            # box: the frozen arXiv copy of P0 is never edited

PDNOTES = r"""%% PaperDesk comments (added from the PaperDesk app).
%%   \pdmark{marked text}{comment}   a comment on a passage (blue text, red [comment])
%%   \pdnote{comment}                a comment at this point (red [comment])
%% Hide all of them for a clean PDF: change \pdnotestrue to \pdnotesfalse below.
%% Find them all: grep -rn '\\pd\(mark\|note\)' .
\newif\ifpdnotes \pdnotestrue
\providecolor{pdmarkcol}{HTML}{0047BB}
\providecolor{pdnotecol}{HTML}{E4002B}
\NewDocumentCommand{\pdnote}{m}{\ifpdnotes{\color{pdnotecol}\sffamily\footnotesize\,[#1]\,}\fi}
\NewDocumentCommand{\pdmark}{m m}{\ifpdnotes{\color{pdmarkcol}#1}{\color{pdnotecol}\sffamily\footnotesize\,[#2]\,}\else#1\fi}
"""


def _lock(pid: str) -> threading.Lock:
    return LOCKS.setdefault(pid, threading.Lock())


# ------------------------------------------------------------------ registry
def registry(cfg: dict, box: bool) -> dict[str, dict]:
    """id -> {id, label, url, main, live?}. Box: [[papers]] in config.toml; portable: papers.json."""
    out = {}
    for p in cfg.get("papers", []):
        out[str(p["id"])] = {"id": str(p["id"]), "label": str(p.get("label") or p["id"]),
                             "url": str(p.get("url") or ""), "live": str(p.get("dir") or ""),
                             "main": str(p.get("main") or ""), "rm": bool(p.get("rm", box))}
    if not box:
        try:
            for p in json.loads(REGISTRY.read_text()):
                out.setdefault(p["id"], p)
        except (OSError, ValueError):
            pass
    for k in list(out):
        if any(n in (out[k]["url"] + out[k]["live"]) for n in NEVER):
            out.pop(k)
    return out


def _save_registry(items: list[dict]) -> None:
    pdlib.STATE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = REGISTRY.with_suffix(".tmp")
    tmp.write_text(json.dumps(items, indent=1))
    tmp.replace(REGISTRY)


def slug(label: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-")[:30]
    return s or "paper"


# ------------------------------------------------------------------ git
def _askpass() -> str:
    """A tiny GIT_ASKPASS helper: answers git's password prompt with $PD_GIT_PASS (never on the command line)."""
    f = pdlib.STATE_DIR / "git-askpass.sh"
    if not f.exists():
        pdlib.STATE_DIR.mkdir(parents=True, exist_ok=True)
        f.write_text('#!/bin/sh\ncase "$1" in Username*) echo "${PD_GIT_USER:-git}";; *) echo "$PD_GIT_PASS";; esac\n')
        f.chmod(stat.S_IRWXU)
    return str(f)


def _auth_env(url: str) -> dict:
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    host = urllib.parse.urlparse(url).hostname or ""
    if "@" in (urllib.parse.urlparse(url).netloc or ""):
        return env                                   # credentials already in the URL (box checkouts)
    tok = os.environ.get("GITHUB_TOKEN") if host == "github.com" else os.environ.get("OVERLEAF_TOKEN")
    if tok:
        env.update(GIT_ASKPASS=_askpass(), PD_GIT_PASS=tok,
                   PD_GIT_USER="x-access-token" if host == "github.com" else "git")
    return env


def git(d: Path, *args: str, url: str = "", timeout: int = 120, check: bool = True) -> str:
    r = subprocess.run(["git", "-C", str(d), *args], capture_output=True, text=True, timeout=timeout,
                       env=_auth_env(url))
    if check and r.returncode:
        msg = (r.stderr or r.stdout).strip()
        msg = re.sub(r"https://[^@\s]+@", "https://***@", msg)     # never echo a token
        raise RuntimeError(f"git {args[0]}: {msg[-400:]}")
    return r.stdout


def remote_url(p: dict) -> str:
    if p.get("url"):
        return p["url"]
    if p.get("live"):
        return git(Path(p["live"]).expanduser(), "remote", "get-url", "origin").strip()
    raise RuntimeError("paper has neither url nor dir")


def clone_dir(pid: str) -> Path:
    return CLONES / pid


def ensure_clone(p: dict) -> Path:
    d, url = clone_dir(p["id"]), remote_url(p)
    if not (d / ".git").exists():
        CLONES.mkdir(parents=True, exist_ok=True)
        tmp = Path(tempfile.mkdtemp(prefix=f".{p['id']}-", dir=CLONES))
        try:
            r = subprocess.run(["git", "clone", "-q", url, str(tmp / "c")], capture_output=True, text=True,
                               timeout=600, env=_auth_env(url))
            if r.returncode:
                err = re.sub(r"https://[^@\s]+@", "https://***@", r.stderr.strip())
                if re.search(r"could not read Username|Authentication failed|not found|403|401", err):
                    raise RuntimeError("cannot open this repo: check the URL, and that the matching token "
                                       "(GitHub or Overleaf) is saved under Keys")
                raise RuntimeError("clone failed: " + err[-300:])
            (tmp / "c").rename(d)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        name = _git_identity()
        git(d, "config", "user.name", name[0])
        git(d, "config", "user.email", name[1])
    return d


def _git_identity() -> tuple[str, str]:
    def g(k):
        r = subprocess.run(["git", "config", "--global", k], capture_output=True, text=True)
        return r.stdout.strip()
    return g("user.name") or "PaperDesk", g("user.email") or "paperdesk@localhost"


def sync(p: dict, force: bool = False) -> Path:
    """Fast-forward the clone to the remote (at most every 30 s unless forced). A diverged clone is reset:
    every app edit is pushed at once, so local-only commits are failed pushes that were reported."""
    d = ensure_clone(p)
    if force or time.time() - _FETCHED.get(p["id"], 0) > 30:
        url = remote_url(p)
        git(d, "fetch", "-q", "origin", url=url, timeout=180)
        branch = git(d, "rev-parse", "--abbrev-ref", "HEAD").strip()
        git(d, "reset", "-q", "--hard", f"origin/{branch}")
        _FETCHED[p["id"]] = time.time()
    return d


def head(d: Path) -> dict:
    h, date, subj = git(d, "log", "-1", "--format=%h%x00%cI%x00%s").strip().split("\x00")
    return {"hash": h, "date": date, "subject": subj}


def main_file(d: Path, p: dict) -> str:
    if p.get("main") and (d / p["main"]).exists():
        return p["main"]
    for cand in ("Main.tex", "main.tex", "paper.tex"):
        if (d / cand).exists():
            return cand
    for f in sorted(git(d, "ls-files", "*.tex").split()):
        if re.search(r"^\s*\\documentclass", (d / f).read_text(errors="replace"), re.M):
            return f
    return ""


def _safe_path(d: Path, rel: str) -> Path:
    f = (d / rel).resolve()
    if not str(f).startswith(str(d.resolve()) + os.sep) or "/.git/" in f"/{rel}/":
        raise ValueError("bad path")
    if f.suffix.lower() not in TEXT_EXT:
        raise ValueError("only text files (.tex, .bib, ...) can be edited here")
    return f


def blob_sha(text: str) -> str:
    r = subprocess.run(["git", "hash-object", "--stdin"], input=text.encode(), capture_output=True)
    return r.stdout.decode().strip()


# ------------------------------------------------------------------ API helpers
def list_papers(cfg: dict, box: bool) -> list[dict]:
    out = []
    for pid, p in registry(cfg, box).items():
        item = {"id": pid, "label": p["label"], "main": p.get("main") or "", "branch": None, "head": None,
                "comments": 0, "rm": bool(p.get("rm")), "error": None}
        try:
            d = ensure_clone(p)
            item.update(head=head(d), branch=git(d, "rev-parse", "--abbrev-ref", "HEAD").strip(),
                        main=main_file(d, p), comments=len(comments(d)))
        except Exception as e:  # noqa: BLE001 - one broken paper must not hide the others
            item["error"] = str(e)[:200]
        out.append(item)
    return out


def files(p: dict) -> list[dict]:
    d = sync(p)
    out = []
    for f in git(d, "ls-files", "-z").split("\0"):
        if f and Path(f).suffix.lower() in TEXT_EXT and (d / f).is_file():
            out.append({"path": f, "size": (d / f).stat().st_size})
    return sorted(out, key=lambda x: (x["path"].count("/"), x["path"].lower()))


def read(p: dict, rel: str) -> dict:
    d = sync(p)
    f = _safe_path(d, rel)
    if not f.is_file():
        raise KeyError(f"no such file {rel}")
    text = f.read_text(encoding="utf-8", errors="replace")
    return {"path": rel, "text": text, "sha": blob_sha(text), "head": head(d)["hash"]}


class Conflict(Exception):
    def __init__(self, text: str, sha: str):
        super().__init__("changed")
        self.text, self.sha = text, sha


def write(p: dict, rel: str, text: str, base_sha: str, message: str | None = None) -> dict:
    if len(text.encode()) > MAX_FILE:
        raise ValueError("file too large")
    with _lock(p["id"]):
        d = sync(p, force=True)
        f = _safe_path(d, rel)
        cur = f.read_text(encoding="utf-8", errors="replace") if f.exists() else ""
        if f.exists() and blob_sha(cur) != base_sha:
            raise Conflict(cur, blob_sha(cur))
        if cur == text:
            return {"sha": base_sha, "commit": head(d)["hash"], "pushed": True, "note": "no changes"}
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")
        touched, note = [rel], None
        if re.search(r"\\pd(mark|note)\b", text):
            added = ensure_pdnotes(d, p)
            if added:
                touched += added
                note = "comment macros added to the paper (pdnotes.tex)"
        git(d, "add", "--", *touched)
        msg = message or f"PaperDesk: edit {rel}"
        git(d, "commit", "-q", "-m", msg, "--", *touched)
        url = remote_url(p)
        r = subprocess.run(["git", "-C", str(d), "push", "-q", "origin", "HEAD"], capture_output=True, text=True,
                           timeout=180, env=_auth_env(url))
        if r.returncode:                      # someone pushed meanwhile: rebase once if it is another file
            try:
                git(d, "pull", "-q", "--rebase", url=url, timeout=180)
                git(d, "push", "-q", "origin", "HEAD", url=url, timeout=180)
            except RuntimeError:
                subprocess.run(["git", "-C", str(d), "rebase", "--abort"], capture_output=True)
                sync(p, force=True)
                now_text = f.read_text(encoding="utf-8", errors="replace") if f.exists() else ""
                raise Conflict(now_text, blob_sha(now_text))
        _FETCHED[p["id"]] = time.time()
        return {"sha": blob_sha(text), "commit": head(d)["hash"], "pushed": True, "note": note}


def ensure_pdnotes(d: Path, p: dict) -> list[str]:
    """Define \\pdmark/\\pdnote in the paper if nothing defines them yet. Returns the files it changed."""
    for f in git(d, "ls-files", "*.tex", "*.sty").split():
        if re.search(r"\\(NewDocumentCommand|newcommand|providecommand|DeclareRobustCommand)\s*\{?\\pdmark",
                     (d / f).read_text(errors="replace")):
            return []
    main = main_file(d, p)
    if not main:
        raise RuntimeError("no main .tex file found to load the comment macros")
    (d / "pdnotes.tex").write_text(PDNOTES, encoding="utf-8")
    m = d / main
    src = m.read_text(encoding="utf-8")
    if "\\input{pdnotes}" not in src:
        i = src.find("\\begin{document}")
        if i < 0:
            raise RuntimeError(f"{main} has no \\begin{{document}}")
        src = src[:i] + "\\input{pdnotes} % PaperDesk comments; switch off inside pdnotes.tex\n" + src[i:]
        m.write_text(src, encoding="utf-8")
    return ["pdnotes.tex", main]


# ------------------------------------------------------------------ comments
MACROS = {"pdmark": ("mark", 2), "pdnote": ("note", 1), "rmnote": ("rmnote", 1), "rmhl": ("rmhl", 1),
          "rmdel": ("rmdel", 1)}
MACRO_RE = re.compile(r"\\(pdmark|pdnote|rmnote|rmhl|rmdel)(?![A-Za-z@])")


def _group(s: str, i: int, open_: str = "{", close: str = "}") -> tuple[str, int] | None:
    """The balanced group starting at s[i] == open_ -> (inner text, index after it)."""
    if i >= len(s) or s[i] != open_:
        return None
    depth, j = 0, i
    while j < len(s):
        c = s[j]
        if c == "\\":
            j += 2
            continue
        if c == open_:
            depth += 1
        elif c == close:
            depth -= 1
            if depth == 0:
                return s[i + 1:j], j + 1
        j += 1
    return None


def parse_comments(text: str, path: str) -> list[dict]:
    out = []
    # mask comment lines/parts so commented-out macros and definitions are not listed
    masked = re.sub(r"(?<!\\)%[^\n]*", lambda m: " " * len(m.group()), text)
    for m in MACRO_RE.finditer(masked):
        line_start = masked.rfind("\n", 0, m.start()) + 1
        if re.match(r"\s*\\(NewDocumentCommand|newcommand|renewcommand|providecommand|DeclareRobustCommand)",
                    masked[line_start:m.start()] + "\\"):
            continue
        kind, nargs = MACROS[m.group(1)]
        j = m.end()
        while j < len(masked) and masked[j] in " \t":
            j += 1
        opt = None
        if masked.startswith("[", j):
            g = _group(text, j, "[", "]")
            if g:
                opt, j = g
        args = []
        for _ in range(nargs):
            while j < len(text) and text[j] in " \t\n":
                j += 1
            g = _group(text, j)
            if not g:
                break
            args.append(g[0])
            j = g[1]
        if len(args) < nargs:
            continue
        line = text.count("\n", 0, m.start()) + 1
        col = m.start() - line_start + 1
        if kind == "mark":
            marked, comment = args
        elif kind in ("note", "rmnote"):
            marked, comment = None, args[0] + (f" ({opt})" if opt else "")
        else:
            marked, comment = args[0], None
        out.append({"id": f"{path}:{m.start()}", "kind": kind, "path": path, "line": line, "col": col,
                    "start": m.start(), "end": j, "marked": marked, "comment": comment})
    return out


def comments(d: Path) -> list[dict]:
    out = []
    for f in git(d, "ls-files", "*.tex").split():
        if Path(f).name in ("pdnotes.tex", "rmnotes.tex"):
            continue
        try:
            out += parse_comments((d / f).read_text(encoding="utf-8", errors="replace"), f)
        except OSError:
            continue
    return out


# ------------------------------------------------------------------ build
def latexmk() -> str | None:
    for c in (shutil.which("latexmk"), str(Path.home() / ".TinyTeX/bin/x86_64-linux/latexmk"),
              str(Path.home() / ".TinyTeX/bin/aarch64-linux/latexmk")):
        if c and Path(c).exists():
            return c
    return None


def build(p: dict, log) -> str:
    """latexmk into ~/.local/state/paperdesk/builds/<id>; missing packages are installed with tlmgr (TinyTeX)."""
    mk = latexmk()
    if not mk:
        raise RuntimeError("LaTeX is not installed on this machine (run install.sh again)")
    with _lock(p["id"]):
        d = sync(p, force=True)
        main = main_file(d, p)
        if not main:
            raise RuntimeError("no main .tex file")
        src = BUILDS / p["id"] / "src"
        out = BUILDS / p["id"] / "out"
        shutil.rmtree(src, ignore_errors=True)
        src.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(d, src, ignore=shutil.ignore_patterns(".git"))
    out.mkdir(parents=True, exist_ok=True)
    tlmgr = str(Path(mk).with_name("tlmgr"))
    env = dict(os.environ, PATH=f"{Path(mk).parent}:{os.environ.get('PATH', '')}")
    for attempt in range(4):
        r = subprocess.run([mk, "-pdf", "-bibtex", "-interaction=nonstopmode", "-halt-on-error", "-f",
                            f"-outdir={out}", main], cwd=src / Path(main).parent, capture_output=True, text=True,
                           timeout=900, env=env)
        log.write(r.stdout[-6000:] + r.stderr[-2000:])
        logf = out / (Path(main).stem + ".log")
        ltx = logf.read_text(errors="replace") if logf.exists() else r.stdout
        missing = sorted(set(re.findall(r"File `([^']+)' not found", ltx)))
        if missing and Path(tlmgr).exists() and attempt < 3:
            pkgs = set()
            for f in missing:
                s = subprocess.run([tlmgr, "search", "--global", "--file", "/" + f], capture_output=True, text=True,
                                   timeout=300, env=env).stdout
                m = re.search(r"^(\S+):\s*$", s, re.M)
                if m:
                    pkgs.add(m.group(1))
            if pkgs:
                log.write(f"\ninstalling {sorted(pkgs)}\n")
                subprocess.run([tlmgr, "install", *sorted(pkgs)], capture_output=True, timeout=900, env=env)
                continue
        pdf = out / (Path(main).stem + ".pdf")
        if r.returncode == 0 and pdf.exists():
            pages = re.search(r"Output written on .*?\((\d+) pages?", ltx)
            return f"PDF built ({pages.group(1)} pages)" if pages else "PDF built"
        err = re.search(r"^! (.+)$(?:[\s\S]*?^(l\.\d+.*)$)?", ltx, re.M)
        raise RuntimeError("LaTeX error: " + (f"{err.group(1)} {err.group(2) or ''}".strip() if err else
                                               (r.stdout.strip().splitlines() or ["unknown"])[-1])[:280])
    raise RuntimeError("missing LaTeX packages could not be installed")


def pdf_path(p: dict) -> Path | None:
    d = clone_dir(p["id"])
    main = main_file(d, p) if d.exists() else ""
    f = BUILDS / p["id"] / "out" / (Path(main).stem + ".pdf") if main else None
    return f if f and f.exists() else None


# ------------------------------------------------------------------ add / remove (portable)
def add(label: str, url: str, main: str = "") -> dict:
    label = " ".join((label or "").split())[:60]
    u = urllib.parse.urlparse((url or "").strip())
    if u.scheme != "https" or not u.hostname or "@" in u.netloc:
        raise ValueError("use the https git URL without credentials (tokens go into Keys)")
    items = []
    try:
        items = json.loads(REGISTRY.read_text())
    except (OSError, ValueError):
        pass
    if any(i["url"].rstrip("/") == url.strip().rstrip("/") for i in items):
        raise ValueError("this paper is already on the desk")
    pid, n = slug(label or Path(u.path).name), 1
    while any(i["id"] == pid for i in items):
        n += 1
        pid = f"{slug(label)}-{n}"
    p = {"id": pid, "label": label or pid, "url": url.strip(), "main": main.strip(), "live": "", "rm": False}
    items.append(p)
    _save_registry(items)
    return p


def remove(pid: str) -> None:
    try:
        items = json.loads(REGISTRY.read_text())
    except (OSError, ValueError):
        items = []
    _save_registry([i for i in items if i["id"] != pid])
    shutil.rmtree(clone_dir(pid), ignore_errors=True)
    shutil.rmtree(BUILDS / pid, ignore_errors=True)
