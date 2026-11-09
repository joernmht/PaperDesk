"""PaperDesk agent: a LangChain DeepAgent chat service for the PaperDesk Android app.

Runs on 127.0.0.1:8800 (published on the tailnet as https://paper1.<tailnet>.ts.net:8448 by
~/.claude/loops/review_server_start.sh). Same bearer token as the PaperDesk server
(~/.config/paperdesk/token). Conversations persist in a LangGraph SQLite checkpointer.

Model: any OpenAI-compatible endpoint, configured in agent/config.toml (default: ScaDS.AI).
Tools: DeepAgent built-ins (todos, files, shell, sub-agents) running inside a per-chat Docker
sandbox (see sandbox.py), GitHub read tools, and approval-gated writes (comment, issue, PR).
The agent never touches the box's own filesystem or shell.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import sys
import threading
import time
import tomllib
import uuid
from collections import OrderedDict
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, StreamingResponse
from pydantic import BaseModel

sys.path.insert(0, str(Path(__file__).resolve().parent))  # run with python -I: add our own dir only
import ghtools  # noqa: E402
import sandbox  # noqa: E402

HOME = Path.home()
DIR = Path(__file__).resolve().parent
STATE = HOME / ".local/state/paperdesk"
DB_PATH = STATE / "agent.sqlite"
TOKEN_FILE = HOME / ".config/paperdesk/token"
VERSION = "0.4.0"

CONFIG = tomllib.loads((DIR / "config.toml").read_text())
POLICY = ghtools.Policy(CONFIG.get("github", {}))
APPROVALS_DEFAULT = CONFIG.get("approvals", {}).get("default", "auto")

# EU-ONLY model hosting (Joern 2026-11-09). Chat content (incl. private repos, EBL) may only reach LLM
# endpoints hosted in Europe. Hosts are allow-listed here in code ON PURPOSE, so a config edit alone cannot
# add a non-EU endpoint. Not allowed: OpenRouter (US company in the request path), US/CN model APIs.
EU_ENDPOINTS = {
    "llm.scads.ai": "ScaDS.AI, TU Dresden / Leipzig University (Germany)",
    # OpenRouter's EU in-region routing ONLY (never openrouter.ai): payloads are decrypted in Europe and go
    # only to EU/EEA/UK/CH provider endpoints, failing rather than leaving the region. Needs a Business plan
    # (2026-11-09: Joern's key gets 403). Billing/account data may still be processed in the US.
    "eu.openrouter.ai": "OpenRouter EU in-region routing (European providers only)",
}


def _check_eu(m: dict) -> str:
    from urllib.parse import urlparse
    host = (urlparse(m["base_url"]).hostname or "").lower()
    if m.get("region") != "EU" or host not in EU_ENDPOINTS:
        raise SystemExit(f"refusing to start: model endpoint {host!r} is not on the EU-hosted allowlist")
    return EU_ENDPOINTS[host]


HOSTING = _check_eu(CONFIG["model"])

SYSTEM_PROMPT = """You are Joern's research and coding assistant inside his PaperDesk phone app.
Joern is a PhD researcher at TU Dresden (Chair of Railway Operations) working on LLMs and
mixed-integer linear programming (MILP) for railway rescheduling. His GitHub account is joernmht.

Style: he reads you on a phone. Be concise. Use Markdown (short lists, `code`, small code blocks).
Say so when you do not know something instead of guessing.

Your computer: a private Linux sandbox (Debian, Python 3.12, git, make, internet for pip; no sudo).
Your file tools and the `execute` shell run there. Work under /workspace with absolute paths.
For multi-step work, plan with write_todos and keep it updated.

GitHub: use the github_* tools to read repos, issues, PRs, files and CI runs.

Repos in this chat: WRITABLE ones live at /workspace/<name> on a work branch (only these can become a PR);
READ-ONLY ones at /workspace/ro/<name> (reference material; you cannot change them; repos that have a checkout
on Joern's box show its live state, incl. uncommitted work, with secret files blanked). Attach more
reference repos yourself with clone_repo_readonly. Need to change a repo that is not writable yet?
Call clone_repo_writable (Joern approves). list_workspace shows what is attached.

Coding workflow (when asked to change code):
1. Make sure the repo is attached writable (see the list below, or clone_repo_writable).
2. Read the code, make focused changes, follow the repo's style and its CLAUDE.md/README if present.
3. Test in a virtual environment OUTSIDE the repo dir, e.g.
   python -m venv /workspace/.venvs/<name> && . /workspace/.venvs/<name>/bin/activate && pip install -e '.[test]'
   (or the repo's documented install), then run its test suite (e.g. pytest -q). Fix until green.
4. git add/commit with clear messages (never commit venvs, caches or secrets).
5. review_changes("<name>"), then open_pull_request("<name>", title, body) as a draft. The body says
   what changed and how it was tested. Joern approves the push on his phone.
Be efficient: set up and run the tests early, use one targeted grep instead of many reads, and stop
exploring once you know enough. Batch related shell commands into one execute call.
Never edit .github/ (CI) files; they are refused. Comments and new issues also need his approval.
If he rejects an action, read his reason and adapt."""


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


# ---------------------------------------------------------------- persistence
def _conn() -> sqlite3.Connection:
    STATE.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(DB_PATH, check_same_thread=False)
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("""CREATE TABLE IF NOT EXISTS pd_threads(
        id TEXT PRIMARY KEY, title TEXT, created TEXT, updated TEXT, model TEXT)""")
    if "repos" not in [r[1] for r in c.execute("PRAGMA table_info(pd_threads)")]:
        c.execute("ALTER TABLE pd_threads ADD COLUMN repos TEXT DEFAULT '[]'")  # chat setup: [{repo, mode}]
    if "approvals" not in [r[1] for r in c.execute("PRAGMA table_info(pd_threads)")]:
        c.execute("ALTER TABLE pd_threads ADD COLUMN approvals TEXT")  # auto | ask | NULL = config default
    c.commit()
    return c


DB = _conn()
DB_LOCK = threading.Lock()
THREAD_LOCKS: dict[str, threading.Lock] = {}
CHECKPOINTER = None
AGENTS: "OrderedDict[tuple[str, str], object]" = OrderedDict()


def build_agent(tid: str, model_name: str, approvals: str):
    from deepagents import create_deep_agent
    from deepagents.backends import CompositeBackend, StateBackend
    from langchain_openai import ChatOpenAI

    m = CONFIG["model"]
    _check_eu(m)  # every agent build re-checks the endpoint
    llm = ChatOpenAI(model=model_name, base_url=m["base_url"], api_key=os.environ[m["api_key_env"]],
                     temperature=m.get("temperature", 0.3), timeout=m.get("timeout", 120), max_retries=2)
    tools, interrupt_on = ghtools.make_tools(tid, POLICY)
    # files + shell in the chat's container; offloaded context stays in the checkpointed state
    backend = CompositeBackend(default=sandbox.DockerSandbox(tid), routes={"/_agent/": StateBackend()},
                               artifacts_root="/_agent")
    ws = sandbox.workspace(tid)
    prompt = SYSTEM_PROMPT + "\n\nAttached now:\n" + ("\n".join(
        f"- {r['repo']} at {r['dir']} ({'WRITABLE, branch ' + r['branch'] if r['mode'] == 'write' else 'read-only'})"
        for r in ws) or "- nothing yet")
    prompt += ("\n\nApprovals: OFF. Writes (writable clone, PR, comment, issue) run directly, so be sure before you call them."
               if approvals == "auto" else
               "\n\nApprovals: ON. Writes (writable clone, PR, comment, issue) wait for Joern's approve/reject.")
    return create_deep_agent(model=llm, tools=tools, system_prompt=prompt, backend=backend,
                             interrupt_on=interrupt_on if approvals == "ask" else None,
                             checkpointer=CHECKPOINTER)


def forget_agent(tid: str) -> None:
    """Drop cached agents of a chat (its system prompt lists the attached repos)."""
    with DB_LOCK:
        for key in [k for k in AGENTS if k[0] == tid]:
            AGENTS.pop(key)


def agent_for(tid: str, model_name: str, approvals: str | None = None):
    global CHECKPOINTER
    if approvals is None:
        row = thread_row(tid)
        approvals = (row or {}).get("approvals") or APPROVALS_DEFAULT
    with DB_LOCK:
        if CHECKPOINTER is None:
            from langgraph.checkpoint.sqlite import SqliteSaver
            CHECKPOINTER = SqliteSaver(DB)
        key = (tid, model_name, approvals)
        if key not in AGENTS:
            AGENTS[key] = build_agent(tid, model_name, approvals)
            while len(AGENTS) > 16:
                AGENTS.popitem(last=False)
        AGENTS.move_to_end(key)
        return AGENTS[key]


# ---------------------------------------------------------------- event shaping (app boxes)
FILE_TOOLS = {"ls", "read_file", "write_file", "edit_file", "glob", "grep", "delete_file"}
GIT_TOOLS = {"clone_repo_readonly", "clone_repo_writable", "list_workspace", "review_changes",
             "open_pull_request", "attach_repo"}


def _text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):  # content blocks
        return "".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
    return str(content or "")


def _kind(name: str) -> str:
    if name == "execute":
        return "shell"
    if name in FILE_TOOLS:
        return "file"
    if name == "write_todos":
        return "todos"
    if name == "task":
        return "subagent"
    if name in GIT_TOOLS:
        return "git"
    if name.startswith("github_"):
        return "github"
    return "tool"


def _title(name: str, args: dict) -> str:
    a = args or {}
    if name == "execute":
        return a.get("command", "")[:160]
    if name in FILE_TOOLS:
        return str(a.get("file_path") or a.get("path") or a.get("pattern") or "")[:160]
    if name == "task":
        return str(a.get("description", ""))[:160]
    bits = [str(a[k]) for k in ("repo", "repo_dir_name", "number", "title", "path") if a.get(k)]
    return " · ".join(bits)[:160]


def _short_args(args: dict) -> dict:
    return {k: (v[:2000] + "…" if isinstance(v, str) and len(v) > 2000 else v) for k, v in (args or {}).items()}


def call_event(tc: dict) -> dict:
    return {"type": "tool_call", "id": tc.get("id"), "name": tc["name"], "kind": _kind(tc["name"]),
            "title": _title(tc["name"], tc.get("args")), "args": _short_args(tc.get("args")),
            "text": json.dumps(tc.get("args"))[:400]}


def result_event(msg) -> dict:
    name = getattr(msg, "name", "") or ""
    text = _text(msg.content)
    art = getattr(msg, "artifact", None)
    code = art.get("exit_code") if isinstance(art, dict) else None
    ok = getattr(msg, "status", "success") != "error" and not text.startswith(("ERROR:", "Error:")) \
        and (code in (None, 0))
    ev = {"type": "tool_result", "id": getattr(msg, "tool_call_id", None), "name": name, "kind": _kind(name),
          "ok": ok, "text": text[:2000]}
    if code is not None:
        ev["exit_code"] = code
    if name == "open_pull_request" and ok:
        mm = re.search(r"https://github\.com/\S+/pull/\d+", text)
        if mm:
            ev["url"] = mm.group(0)
    return ev


def todos_event(args: dict) -> dict:
    return {"type": "todos", "items": [{"content": t.get("content", ""), "status": t.get("status", "pending")}
                                       for t in (args or {}).get("todos", [])]}


def pending_approvals(agent, cfg) -> list[dict]:
    st = agent.get_state(cfg)
    out = []
    for it in st.interrupts or ():
        v = it.value if isinstance(it.value, dict) else {}
        acts = [{"name": a["name"], "kind": _kind(a["name"]), "title": _title(a["name"], a.get("args")),
                 "args": _short_args(a.get("args"))} for a in v.get("action_requests", [])]
        out.append({"type": "approval", "id": it.id, "actions": acts,
                    "text": "Approve: " + "; ".join(f"{a['name']} ({a['title']})" for a in acts)})
    return out


# ---------------------------------------------------------------- history
def turns(rows: list[dict]) -> list[dict]:
    """Group history rows per user message: steps (tool boxes + in-between narration) and the final answer."""
    out: list[dict] = []
    for r in rows:
        if r["role"] == "user":
            out.append({"user": r["text"], "steps": [], "answer": None})
            continue
        if not out:
            out.append({"user": None, "steps": [], "answer": None})
        t = out[-1]
        if r["role"] == "assistant":
            if t["answer"] is not None:  # an earlier text was narration before more tool calls
                t["steps"].append({"role": "narration", "text": t["answer"]})
            t["answer"] = r["text"]
        else:
            if t["answer"] is not None:
                t["steps"].append({"role": "narration", "text": t["answer"]})
                t["answer"] = None
            t["steps"].append(r)
    return out


def history(tid: str, model_name: str) -> list[dict]:
    st = agent_for(tid, model_name).get_state({"configurable": {"thread_id": tid}})
    out = []
    for msg in (st.values or {}).get("messages", []):
        kind = msg.type
        if kind == "human":
            out.append({"role": "user", "text": _text(msg.content)})
        elif kind == "ai":
            t = _text(msg.content)
            if t.strip():
                out.append({"role": "assistant", "text": t})
            for tc in getattr(msg, "tool_calls", None) or []:
                ev = call_event(tc)
                out.append({"role": "tool_call", **{k: v for k, v in ev.items() if k != "type"}})
                if tc["name"] == "write_todos":
                    out.append({"role": "todos", "items": todos_event(tc.get("args"))["items"]})
        elif kind == "tool":
            ev = result_event(msg)
            out.append({"role": "tool_result", **{k: v for k, v in ev.items() if k != "type"}})
    return out


COLS = ("id", "title", "created", "updated", "model", "repos", "approvals")


def _row(r) -> dict:
    d = dict(zip(COLS, r))
    d["repos"] = json.loads(d.get("repos") or "[]")
    d["approvals"] = d.get("approvals") or APPROVALS_DEFAULT
    return d


def thread_row(tid: str) -> dict | None:
    r = DB.execute(f"SELECT {','.join(COLS)} FROM pd_threads WHERE id=?", (tid,)).fetchone()
    return _row(r) if r else None


def attach(tid: str, repo: str, mode: str) -> dict:
    """Joern-initiated attach (chat setup or the app's repo picker): his choice is the approval."""
    if mode == "write":
        info = sandbox.clone_into_sandbox(tid, POLICY.check(repo, write=True), None, POLICY.identity)
    else:
        info = sandbox.clone_readonly(tid, POLICY.check(repo), None)
    forget_agent(tid)
    return info


def attach_pending(tid: str, row: dict):
    """Clone the repos chosen at chat setup that are not attached yet; yield box events."""
    have = {r["repo"].lower() for r in sandbox.workspace(tid)}
    for i, spec in enumerate(row.get("repos") or []):
        if spec["repo"].lower() in have:
            continue
        cid = f"attach-{i}"
        args = {"repo": spec["repo"], "mode": spec["mode"]}
        yield {"type": "tool_call", "id": cid, "name": "attach_repo", "kind": "git",
               "title": f"{spec['repo']} ({spec['mode']})", "args": args, "text": json.dumps(args)}
        try:
            info = attach(tid, spec["repo"], spec["mode"])
            yield {"type": "tool_result", "id": cid, "name": "attach_repo", "kind": "git", "ok": True,
                   "text": json.dumps(info)}
        except Exception as e:
            yield {"type": "tool_result", "id": cid, "name": "attach_repo", "kind": "git", "ok": False,
                   "text": f"ERROR: {e}"[:500]}


# ---------------------------------------------------------------- streaming
APPROVE_WORDS = {"approve", "approved", "yes", "y", "ok", "okay", "go", "ja", "do it", "👍"}


def _stream(tid: str, row: dict, inp, lock: threading.Lock):
    """Run one agent turn; yield NDJSON events. `inp` is a user text or a langgraph Command."""
    from langgraph.types import Command

    cfg = {"configurable": {"thread_id": tid}, "recursion_limit": 300}
    final: list[str] = []
    seg = None  # id of the model message whose tokens are streaming
    try:
        for ev in attach_pending(tid, row):
            yield json.dumps(ev) + "\n"
        agent = agent_for(tid, row["model"])
        # middleware nodes may re-send the whole message list as an "update": emit each message once
        seen = {getattr(m, "id", None) for m in (agent.get_state(cfg).values or {}).get("messages", [])}
        if isinstance(inp, str):
            pend = pending_approvals(agent, cfg)
            if pend:  # plain-chat fallback: a typed "yes" approves, anything else rejects with that reason
                ok = inp.strip().lower().rstrip("!.") in APPROVE_WORDS
                inp = _decision_command(pend, "approve" if ok else "reject", None if ok else inp)
            else:
                inp = {"messages": [{"role": "user", "content": inp}]}
        for mode, data in agent.stream(inp, cfg, stream_mode=["messages", "updates"]):
            if mode == "messages":
                chunk, meta = data
                if chunk.type in ("AIMessageChunk", "ai") and meta.get("langgraph_node") == "model" \
                        and "|" not in meta.get("langgraph_checkpoint_ns", ""):
                    t = _text(chunk.content)
                    if t:
                        if chunk.id != seg:
                            seg, final = chunk.id, []
                        final.append(t)
                        yield json.dumps({"type": "token", "text": t, "seg": seg}) + "\n"
            elif mode == "updates":
                for node, upd in (data or {}).items():
                    if not isinstance(upd, dict):
                        continue
                    msgs = upd.get("messages", []) or []
                    for msg in msgs if isinstance(msgs, list) else [msgs]:
                        mid = getattr(msg, "id", None)
                        if mid is not None and mid in seen:
                            continue
                        seen.add(mid)
                        if getattr(msg, "type", "") == "ai":
                            if getattr(msg, "tool_calls", None):
                                # text streamed before these tool calls was narration, not the answer
                                if _text(msg.content).strip():
                                    yield json.dumps({"type": "segment", "seg": mid, "final": False}) + "\n"
                                if mid == seg:
                                    final = []
                            for tc in getattr(msg, "tool_calls", None) or []:
                                ev = call_event(tc)
                                if tc["name"] in ghtools.GATED and row.get("approvals") == "auto":
                                    ev["auto_approved"] = True
                                yield json.dumps(ev) + "\n"
                                if tc["name"] == "write_todos":
                                    yield json.dumps(todos_event(tc.get("args"))) + "\n"
                        elif getattr(msg, "type", "") == "tool":
                            yield json.dumps(result_event(msg)) + "\n"
        for ev in pending_approvals(agent, cfg):
            yield json.dumps(ev) + "\n"
        yield json.dumps({"type": "done", "text": "".join(final)}) + "\n"
    except Exception as e:  # surface model/API errors to the app instead of a dead stream
        yield json.dumps({"type": "error", "text": f"{type(e).__name__}: {e}"[:500]}) + "\n"
    finally:
        with DB_LOCK:
            DB.execute("UPDATE pd_threads SET updated=? WHERE id=?", (now(), tid))
            DB.commit()
        lock.release()


def _decision_command(pend: list[dict], decision: str, message: str | None):
    from langgraph.types import Command
    resume = {}
    for p in pend:
        d = {"type": decision}
        if decision == "reject":
            d["message"] = message or "Joern rejected this action."
        resume[p["id"]] = {"decisions": [dict(d) for _ in p["actions"]]}
    return Command(resume=resume)


def _begin(tid: str) -> tuple[dict, threading.Lock]:
    row = thread_row(tid)
    if not row:
        raise HTTPException(404, "no such thread")
    lock = THREAD_LOCKS.setdefault(tid, threading.Lock())
    if not lock.acquire(blocking=False):
        raise HTTPException(409, "this chat is still answering")
    return row, lock


# ---------------------------------------------------------------- API
@asynccontextmanager
async def _lifespan(_app):
    threading.Thread(target=sandbox.reaper_loop, daemon=True).start()  # stops idle sandboxes
    yield


app = FastAPI(title="PaperDesk agent", version=VERSION, lifespan=_lifespan)


def authed(request: Request) -> None:
    want = TOKEN_FILE.read_text().strip()
    got = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    if not want or got != want:
        raise HTTPException(401, "unauthorized")


class RepoSpec(BaseModel):
    repo: str
    mode: str = "read"  # read | write


class NewThread(BaseModel):
    title: str | None = None
    model: str | None = None
    repos: list[RepoSpec] = []
    approvals: str | None = None  # auto | ask (default from config)


class ThreadPatch(BaseModel):
    title: str | None = None
    approvals: str | None = None


class ChatIn(BaseModel):
    text: str


class ResumeIn(BaseModel):
    decision: str  # approve | reject
    message: str | None = None


@app.get("/", response_class=HTMLResponse)
def index():
    m = CONFIG["model"]
    return f"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>PaperDesk agent</title><body style="font-family:sans-serif;padding:16px">
<h2>PaperDesk agent is running</h2><p>Version {VERSION} &middot; model <code>{m['name']}</code>
on {HOSTING} (EU-hosted only)</p><p>Chat from the PaperDesk app. API: <code>/api/agent/health</code>.</p>"""


@app.get("/api/agent/health")
def health():
    return {"ok": True, "version": VERSION, "model": CONFIG["model"]["name"],
            "models": CONFIG["model"].get("choices", [CONFIG["model"]["name"]]),
            "hosting": HOSTING, "region": "EU", "approvals_default": APPROVALS_DEFAULT,
            "features": ["github_read", "sandbox", "pull_requests", "approvals", "repos"]}


@app.get("/api/agent/threads", dependencies=[Depends(authed)])
def list_threads():
    rows = DB.execute(f"SELECT {','.join(COLS)} FROM pd_threads ORDER BY updated DESC LIMIT 100").fetchall()
    return {"threads": [_row(r) for r in rows]}


_REPO_CACHE: dict = {"t": 0.0, "repos": []}


@app.get("/api/agent/repos", dependencies=[Depends(authed)])
def repo_choices():
    """Repos for the app's picker: Joern's GitHub repos minus the deny list (cached 10 min)."""
    if time.time() - _REPO_CACHE["t"] > 600:
        raw = json.loads(ghtools._gh("repo", "list", POLICY.default_owner, "--limit", "200", "--json",
                                     "nameWithOwner,description,visibility,pushedAt,isArchived"))
        _REPO_CACHE.update(t=time.time(), repos=sorted(
            ({"repo": r["nameWithOwner"], "description": r.get("description") or "",
              "on_box": r["nameWithOwner"].lower() in sandbox.local_checkouts(),
              "private": r["visibility"] != "PUBLIC", "pushed": r["pushedAt"],
              "writable": r["nameWithOwner"].split("/")[0].lower() in POLICY.write_owners}
             for r in raw if r["nameWithOwner"].lower() not in POLICY.deny and not r.get("isArchived")),
            key=lambda r: r["pushed"], reverse=True))
    return {"repos": _REPO_CACHE["repos"]}


@app.delete("/api/agent/threads/{tid}/repos/{name}", dependencies=[Depends(authed)])
def remove_repo(tid: str, name: str):
    """Detach a repo from a chat (a writable copy's unpushed sandbox work is dropped)."""
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
        raise HTTPException(400, "bad repo name")
    row, lock = _begin(tid)
    try:
        sandbox.detach(tid, name)
        with DB_LOCK:
            specs = [r for r in row["repos"] if r["repo"].split("/")[1] != name]
            DB.execute("UPDATE pd_threads SET repos=? WHERE id=?", (json.dumps(specs), tid))
            DB.commit()
        forget_agent(tid)
        return {"workspace": sandbox.workspace(tid)}
    except RuntimeError as e:
        raise HTTPException(400, str(e))
    finally:
        lock.release()


@app.post("/api/agent/threads/{tid}/repos", dependencies=[Depends(authed)])
def add_repo(tid: str, body: RepoSpec):
    """Attach a repo to an existing chat now (read-only, or writable = Joern's own approval)."""
    if body.mode not in ("read", "write"):
        raise HTTPException(400, "mode must be read or write")
    row, lock = _begin(tid)
    try:
        info = attach(tid, body.repo, body.mode)
        with DB_LOCK:
            specs = row["repos"] + [{"repo": info["repo"], "mode": body.mode}]
            DB.execute("UPDATE pd_threads SET repos=? WHERE id=?", (json.dumps(specs), tid))
            DB.commit()
        return {"attached": info, "workspace": sandbox.workspace(tid)}
    except (ValueError, PermissionError, RuntimeError) as e:
        raise HTTPException(400, str(e))
    finally:
        lock.release()


@app.post("/api/agent/threads", dependencies=[Depends(authed)])
def new_thread(body: NewThread):
    model = body.model or CONFIG["model"]["name"]
    if model not in CONFIG["model"].get("choices", [CONFIG["model"]["name"]]):
        raise HTTPException(400, f"unknown model {model}")
    specs = []
    for r in body.repos:
        if r.mode not in ("read", "write"):
            raise HTTPException(400, f"mode must be read or write: {r.repo}")
        try:
            specs.append({"repo": POLICY.check(r.repo, write=r.mode == "write"), "mode": r.mode})
        except (ValueError, PermissionError) as e:
            raise HTTPException(400, str(e))
    if body.approvals not in (None, "auto", "ask"):
        raise HTTPException(400, "approvals must be auto or ask")
    tid = uuid.uuid4().hex[:12]
    with DB_LOCK:
        DB.execute(f"INSERT INTO pd_threads({','.join(COLS)}) VALUES(?,?,?,?,?,?,?)",
                   (tid, body.title or "New chat", now(), now(), model, json.dumps(specs),
                    body.approvals or APPROVALS_DEFAULT))
        DB.commit()
    return thread_row(tid)  # repos are cloned when the first message runs (shown as attach_repo boxes)


@app.patch("/api/agent/threads/{tid}", dependencies=[Depends(authed)])
def patch_thread(tid: str, body: ThreadPatch):
    if not thread_row(tid):
        raise HTTPException(404, "no such thread")
    if body.approvals not in (None, "auto", "ask"):
        raise HTTPException(400, "approvals must be auto or ask")
    with DB_LOCK:
        if body.title:
            DB.execute("UPDATE pd_threads SET title=? WHERE id=?", (body.title[:80], tid))
        if body.approvals:
            DB.execute("UPDATE pd_threads SET approvals=? WHERE id=?", (body.approvals, tid))
        DB.commit()
    forget_agent(tid)
    return thread_row(tid)


@app.delete("/api/agent/threads/{tid}", dependencies=[Depends(authed)])
def delete_thread(tid: str):
    if not re.fullmatch(r"[0-9a-f]{12}", tid):
        raise HTTPException(400, "bad id")
    with DB_LOCK:
        DB.execute("DELETE FROM pd_threads WHERE id=?", (tid,))
        DB.commit()
    forget_agent(tid)
    if CHECKPOINTER is not None:
        CHECKPOINTER.delete_thread(tid)
    sandbox.destroy(tid)
    return {"ok": True}


@app.get("/api/agent/threads/{tid}", dependencies=[Depends(authed)])
def get_thread(tid: str):
    row = thread_row(tid)
    if not row:
        raise HTTPException(404, "no such thread")
    agent = agent_for(tid, row["model"])
    msgs = history(tid, row["model"])
    return {**row, "messages": msgs, "turns": turns(msgs), "workspace": sandbox.workspace(tid),
            "pending_approvals": pending_approvals(agent, {"configurable": {"thread_id": tid}})}


@app.post("/api/agent/threads/{tid}/chat", dependencies=[Depends(authed)])
def chat(tid: str, body: ChatIn):
    """NDJSON stream; event types: token, tool_call, tool_result, todos, approval, done, error.
    While an approval is pending, a chat message answers it ("yes" approves, anything else rejects)."""
    if not body.text.strip():
        raise HTTPException(400, "empty message")
    row, lock = _begin(tid)
    if row["title"] == "New chat":
        with DB_LOCK:
            DB.execute("UPDATE pd_threads SET title=? WHERE id=?", (body.text.strip()[:60], tid))
            DB.commit()
    return StreamingResponse(_stream(tid, row, body.text, lock), media_type="application/x-ndjson")


@app.post("/api/agent/threads/{tid}/resume", dependencies=[Depends(authed)])
def resume(tid: str, body: ResumeIn):
    """Answer the pending approval(s) of a chat; streams the continuation like /chat."""
    if body.decision not in ("approve", "reject"):
        raise HTTPException(400, "decision must be approve or reject")
    row, lock = _begin(tid)
    try:
        pend = pending_approvals(agent_for(tid, row["model"]), {"configurable": {"thread_id": tid}})
    except Exception:
        lock.release()
        raise
    if not pend:
        lock.release()
        raise HTTPException(409, "nothing to approve")
    cmd = _decision_command(pend, body.decision, body.message)
    return StreamingResponse(_stream(tid, row, cmd, lock), media_type="application/x-ndjson")




def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--port", type=int, default=8800)
    c = sub.add_parser("chat", help="one-shot terminal chat for testing (no auth)")
    c.add_argument("text")
    c.add_argument("--thread", default=None)
    c.add_argument("--model", default=None)
    c.add_argument("--repo", action="append", default=[], help="owner/name[:write] (repeatable)")
    c.add_argument("--ask", action="store_true", help="this chat asks before writes")
    a = ap.parse_args()
    if a.cmd == "serve":
        import uvicorn
        uvicorn.run(app, host="127.0.0.1", port=a.port, log_level="info")
        return
    specs = [RepoSpec(repo=r.split(":")[0], mode="write" if r.endswith(":write") else "read") for r in a.repo]
    tid = a.thread or new_thread(NewThread(model=a.model, repos=specs, approvals="ask" if a.ask else None))["id"]
    row, lock = _begin(tid)
    t0 = time.time()
    for line in _stream(tid, row, a.text, lock):
        ev = json.loads(line)
        if ev["type"] == "token":
            print(ev["text"], end="", flush=True)
        elif ev["type"] == "tool_call":
            print(f"\n[{ev['kind']}] {ev['name']}: {ev['title']}")
        elif ev["type"] == "tool_result":
            print(f"  -> {'ok' if ev['ok'] else 'FAIL'} {ev['text'][:200]!r}")
        elif ev["type"] == "todos":
            print("  todos: " + " | ".join(f"[{i['status']}] {i['content']}" for i in ev["items"]))
        elif ev["type"] in ("approval", "error"):
            print(f"\n[{ev['type'].upper()}] {ev['text']}")
    print(f"\n-- thread {tid}, {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
