"""PaperDesk agent: a LangChain DeepAgent chat service for the PaperDesk Android app.

Runs on 127.0.0.1:8800 (published on the tailnet as https://paper1.<tailnet>.ts.net:8448 by
~/.claude/loops/review_server_start.sh). Same bearer token as the PaperDesk server
(~/.config/paperdesk/token). Conversations persist in a LangGraph SQLite checkpointer.

Model: any OpenAI-compatible endpoint, configured in agent/config.toml (default: ScaDS.AI).
Tools: DeepAgent built-ins (todos, files, shell, sub-agents) running inside a per-chat Docker
sandbox (see sandbox.py), GitHub read tools, and approval-gated writes (comment, issue, PR).
The agent never touches the box's own filesystem or shell.

Runs are detached from the HTTP request: /chat and /resume start the turn in a background thread, and a
client disconnect never stops it. Every event is numbered per chat (`seq`) and logged in pd_events, so the
app can reconnect with GET /threads/{tid}/events?since=<seq>.
"""
from __future__ import annotations

import argparse
import functools
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
VERSION = "0.5.0"

CONFIG = tomllib.loads((DIR / "config.toml").read_text())
POLICY = ghtools.Policy(CONFIG.get("github", {}))
APPROVALS_DEFAULT = CONFIG.get("approvals", {}).get("default", "auto")
LIMITS = CONFIG["model"].get("limits", {})  # per model: max_input_tokens, max_output_tokens
CONTEXT_BUDGET = int(CONFIG["model"].get("context_budget", 200_000))
SUMMARIZE_AT = 0.85  # deepagents summarises at this fraction of a model's (profile) window
PING_S = 15  # a tail sends {"type":"ping"} after this many silent seconds
TERMINAL = ("done", "error")  # every run ends with exactly one of these
KEEP_EVENTS = 20_000  # per chat; older events are pruned when a run starts
ORIGIN_RE = re.compile(r"[A-Za-z0-9:._-]{1,40}")
SOLO = "paperdesk-solo"  # harness-profile key for chats without sub-agents (no `task` tool)

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
on Joern's box show its live state, incl. uncommitted work, with secret files blanked). list_workspace
shows what is attached.
SCOPE: when Joern attached repos, they ARE the scope of his request. Do not look at, list or clone other
repos unless his message names them (the tools refuse otherwise). Typical roles: the read-only repo(s) are
what the work is about or reference material (style, templates); the writable one is where results go.
"Changes of the last day" means the commits/PRs/issues of the attached subject repo(s), nothing else.
With no repos attached you may use clone_repo_readonly / clone_repo_writable freely.

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
    c = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=30)
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("""CREATE TABLE IF NOT EXISTS pd_threads(
        id TEXT PRIMARY KEY, title TEXT, created TEXT, updated TEXT, model TEXT)""")
    have = [r[1] for r in c.execute("PRAGMA table_info(pd_threads)")]
    for col, decl in (("repos", "TEXT DEFAULT '[]'"),  # chat setup: [{repo, mode}]
                      ("approvals", "TEXT"),            # auto | ask | NULL = config default
                      ("subagents", "INTEGER"),         # 0 = no `task` tool | NULL = on
                      ("origin", "TEXT")):              # who opened the chat, e.g. "loop:agent-scout"
        if col not in have:
            c.execute(f"ALTER TABLE pd_threads ADD COLUMN {col} {decl}")
    # detached runs: every event of every run, numbered per chat, so a client can reconnect and replay
    c.execute("""CREATE TABLE IF NOT EXISTS pd_events(
        tid TEXT, seq INTEGER, run TEXT, ev TEXT, PRIMARY KEY(tid, seq))""")
    c.execute("""CREATE TABLE IF NOT EXISTS pd_runs(id TEXT PRIMARY KEY, tid TEXT, kind TEXT, started TEXT,
        ended TEXT, status TEXT, first_seq INTEGER, last_seq INTEGER, pid INTEGER)""")
    c.execute("CREATE INDEX IF NOT EXISTS pd_runs_tid ON pd_runs(tid, first_seq)")
    c.commit()
    return c


DB = _conn()
DB_LOCK = threading.Lock()
EV_DB = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=30)  # own connection = own transactions
EV_LOCK = threading.Lock()
THREAD_LOCKS: dict[str, threading.Lock] = {}
CHECKPOINTER = None
AGENTS: "OrderedDict[tuple, object]" = OrderedDict()


def model_limits(name: str) -> dict:
    """Window from config + what the agent actually uses (capped by context_budget)."""
    lim = dict(LIMITS.get(name, {}))
    if "max_input_tokens" in lim:
        eff = min(int(lim["max_input_tokens"]), CONTEXT_BUDGET)
        lim.update(effective_input_tokens=eff, summarize_at=int(eff * SUMMARIZE_AT))
    return lim


@functools.cache
def _llm_class():
    """ChatOpenAI whose provider tag picks the deepagents harness profile: SOLO disables the general-purpose
    sub-agent, which removes the `task` tool and its prompt section."""
    from deepagents import GeneralPurposeSubagentProfile, HarnessProfile, register_harness_profile
    from langchain_openai import ChatOpenAI

    register_harness_profile(SOLO, HarnessProfile(
        general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False)))

    class PaperDeskLLM(ChatOpenAI):
        pd_provider: str = "openai"

        def _get_ls_params(self, stop=None, **kwargs):
            params = super()._get_ls_params(stop=stop, **kwargs)
            params["ls_provider"] = self.pd_provider
            return params

    return PaperDeskLLM


def build_agent(row: dict):
    from deepagents import create_deep_agent
    from deepagents.backends import CompositeBackend, StateBackend

    tid, model_name, approvals = row["id"], row["model"], row["approvals"]
    m = CONFIG["model"]
    _check_eu(m)  # every agent build re-checks the endpoint
    extra = {}
    lim = model_limits(model_name)
    if "effective_input_tokens" in lim:  # a known window makes deepagents summarise at a fraction of it
        extra["profile"] = {"max_input_tokens": lim["effective_input_tokens"]}
    llm = _llm_class()(model=model_name, base_url=m["base_url"], api_key=os.environ[m["api_key_env"]],
                       temperature=m.get("temperature", 0.3), timeout=m.get("timeout", 120), max_retries=2,
                       pd_provider="openai" if row["subagents"] else SOLO, **extra)
    tools, interrupt_on = ghtools.make_tools(tid, POLICY, scope=lambda: chat_scope(tid))
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


def agent_for(row: dict):
    """Cached compiled agent for a chat row (its settings are part of the key)."""
    global CHECKPOINTER
    with DB_LOCK:
        if CHECKPOINTER is None:
            from langgraph.checkpoint.sqlite import SqliteSaver
            CHECKPOINTER = SqliteSaver(DB)
        key = (row["id"], row["model"], row["approvals"], row["subagents"])
        if key not in AGENTS:
            AGENTS[key] = build_agent(row)
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


def history(row: dict) -> list[dict]:
    st = agent_for(row).get_state({"configurable": {"thread_id": row["id"]}})
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


COLS = ("id", "title", "created", "updated", "model", "repos", "approvals", "subagents", "origin")


def _row(r) -> dict:
    d = dict(zip(COLS, r))
    d["repos"] = json.loads(d.get("repos") or "[]")
    d["approvals"] = d.get("approvals") or APPROVALS_DEFAULT
    d["subagents"] = d.get("subagents") is None or bool(d["subagents"])
    d["running"] = d["id"] in RUNS
    return d


def thread_row(tid: str) -> dict | None:
    r = DB.execute(f"SELECT {','.join(COLS)} FROM pd_threads WHERE id=?", (tid,)).fetchone()
    return _row(r) if r else None


CURRENT_TEXT: dict[str, str] = {}  # tid -> the user message of the running turn (not flushed to pd_events yet)


def chat_scope(tid: str) -> tuple[set[str], str]:
    """(repos Joern attached to this chat, lowercased text of his messages in it) for ghtools' scope check."""
    row = thread_row(tid) or {}
    allowed = {r["repo"].lower() for r in row.get("repos") or []}
    with EV_LOCK:
        evs = EV_DB.execute("SELECT ev FROM pd_events WHERE tid=? AND ev LIKE '%\"type\": \"start\"%'",
                            (tid,)).fetchall()
    texts = [json.loads(e[0]).get("text") or "" for e in evs]
    return allowed, " ".join(texts + [CURRENT_TEXT.get(tid, "")]).lower()


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


# ---------------------------------------------------------------- detached runs
APPROVE_WORDS = {"approve", "approved", "yes", "y", "ok", "okay", "go", "ja", "do it", "👍"}


class Run:
    """One agent turn in its own thread. It holds the chat's lock until it has fully ended; its events stay in
    memory while it lives (live tails read them there) and are flushed to pd_events in small batches."""

    def __init__(self, row: dict, kind: str, first_seq: int, lock: threading.Lock):
        self.id = uuid.uuid4().hex[:12]
        self.row, self.tid, self.kind, self.lock = row, row["id"], kind, lock
        self.first_seq = self.next_seq = first_seq
        self.events: list[dict] = []
        self.cond = threading.Condition()
        self.cancel = threading.Event()
        self._terminal = False
        self.finished = False  # terminal event emitted and stored
        self.started = now()
        self.t0 = self.t_last = time.time()
        self.activity = "starting"
        self._unflushed: list[dict] = []
        self._t_flush = time.time()

    def emit(self, ev: dict) -> None:
        if ev["type"] in TERMINAL or ev["type"] == "start":
            ev = {**ev, "run": self.id}
        terminal = ev["type"] in TERMINAL
        with self.cond:
            if self._terminal:
                return  # exactly one terminal event per run
            self._terminal = terminal
            ev = {"seq": self.next_seq, **ev}
            self.next_seq += 1
            self.events.append(ev)
            self._unflushed.append(ev)
            self.t_last = time.time()
            if not terminal:
                self.cond.notify_all()
        if terminal or len(self._unflushed) >= 50 or time.time() - self._t_flush > 1:
            self.flush()
        if terminal:  # tails see it only once it is in the DB
            with self.cond:
                self.finished = True
                self.cond.notify_all()

    def flush(self) -> None:
        with self.cond:
            batch, self._unflushed = self._unflushed, []
        self._t_flush = time.time()
        if batch:
            with EV_LOCK:
                EV_DB.executemany("INSERT OR REPLACE INTO pd_events(tid,seq,run,ev) VALUES(?,?,?,?)",
                                  [(self.tid, e["seq"], self.id, json.dumps(e)) for e in batch])
                EV_DB.commit()

    def status(self) -> dict:
        return {"id": self.id, "kind": self.kind, "started": self.started, "first_seq": self.first_seq,
                "elapsed": int(time.time() - self.t0), "activity": self.activity,
                "cancelling": self.cancel.is_set()}


RUNS: dict[str, Run] = {}  # tid -> live run (removed once it has fully ended)


def _max_seq(tid: str) -> int:
    with EV_LOCK:
        return EV_DB.execute("SELECT MAX(seq) FROM pd_events WHERE tid=?", (tid,)).fetchone()[0] or 0


def last_seq(tid: str) -> int:
    run = RUNS.get(tid)
    return run.next_seq - 1 if run else _max_seq(tid)


def start_run(row: dict, inp, kind: str, lock: threading.Lock, label: str) -> Run:
    """Start one agent turn in a background thread. The caller holds the chat's lock; the run releases it."""
    tid = row["id"]
    try:
        first = _max_seq(tid) + 1
        run = Run(row, kind, first, lock)
        with EV_LOCK:
            EV_DB.execute("DELETE FROM pd_events WHERE tid=? AND seq<=?", (tid, first - KEEP_EVENTS))
            EV_DB.execute("INSERT INTO pd_runs(id,tid,kind,started,status,first_seq,pid) VALUES(?,?,?,?,?,?,?)",
                          (run.id, tid, kind, run.started, "running", first, os.getpid()))
            EV_DB.commit()
        RUNS[tid] = run
        threading.Thread(target=_run_main, args=(run, inp, label), name=f"run-{tid}", daemon=True).start()
        return run
    except BaseException:
        RUNS.pop(tid, None)
        lock.release()
        raise


def _run_main(run: Run, inp, label: str) -> None:
    """The terminal event goes out LAST, right before the chat unlocks, so a client that sees `done` can
    send its next message or approval at once. Only a cancel sends done early (closing may take a while)."""
    term = None
    try:
        CURRENT_TEXT[run.tid] = label or ""
        run.emit({"type": "start", "kind": run.kind, "text": label, "model": run.row["model"]})
        for ev in _turn_events(run, inp):
            if ev["type"] in TERMINAL and not ev.get("cancelled"):
                term = ev
                continue
            run.emit(ev)
            if ev.get("cancelled"):
                term = ev
    except Exception as e:  # surface model/API errors to the app instead of a dead stream
        term = term or {"type": "error", "text": f"{type(e).__name__}: {e}"[:500]}
    finally:
        term = term or {"type": "error", "text": "the run ended without an answer"}
        status = "cancelled" if term.get("cancelled") else term["type"]
        try:
            run.flush()
            with DB_LOCK:
                DB.execute("UPDATE pd_threads SET updated=? WHERE id=?", (now(), run.tid))
                DB.commit()
        finally:
            run.emit(term)  # no-op if the cancel already sent it
            if RUNS.get(run.tid) is run:
                RUNS.pop(run.tid)
            run.lock.release()
        with EV_LOCK:
            EV_DB.execute("UPDATE pd_runs SET ended=?, status=?, last_seq=? WHERE id=?",
                          (now(), status, run.next_seq - 1, run.id))
            EV_DB.commit()


def _turn_events(run: Run, inp):
    """One agent turn as event dicts (seq is added by Run.emit). `inp` is a user text or a langgraph Command."""
    row, tid = run.row, run.tid
    cfg = {"configurable": {"thread_id": tid}, "recursion_limit": 300}
    final: list[str] = []
    seg = None  # id of the model message whose tokens are streaming
    writing: dict = {}  # tool-call args the model is still generating: index -> [name, chars]
    run.activity = "attaching repos"
    yield from attach_pending(tid, row)
    agent = agent_for(row)
    # middleware nodes may re-send the whole message list as an "update": emit each message once
    seen = {getattr(m, "id", None) for m in (agent.get_state(cfg).values or {}).get("messages", [])}
    if isinstance(inp, str):
        pend = pending_approvals(agent, cfg)
        if pend:  # plain-chat fallback: a typed "yes" approves, anything else rejects with that reason
            ok = inp.strip().lower().rstrip("!.") in APPROVE_WORDS
            inp = _decision_command(pend, "approve" if ok else "reject", None if ok else inp)
        else:
            inp = {"messages": [{"role": "user", "content": inp}]}
    run.activity = "thinking"
    stream = agent.stream(inp, cfg, stream_mode=["messages", "updates"])
    try:
        for mode, data in stream:
            if run.cancel.is_set():
                break
            if mode == "messages":
                chunk, meta = data
                if chunk.type not in ("AIMessageChunk", "ai") or meta.get("langgraph_node") != "model":
                    continue
                if "|" in meta.get("langgraph_checkpoint_ns", ""):
                    run.activity = "sub-agent working"
                    continue
                t = _text(chunk.content)
                if t:
                    if chunk.id != seg:
                        seg, final = chunk.id, []
                    final.append(t)
                    run.activity = "writing"
                    yield {"type": "token", "text": t, "seg": seg}
                for tcc in getattr(chunk, "tool_call_chunks", None) or []:
                    w = writing.setdefault(tcc.get("index") or 0, ["", 0])
                    w[0] = tcc.get("name") or w[0]
                    w[1] += len(tcc.get("args") or "")
                    run.activity = f"preparing {w[0] or 'a tool call'} ({w[1]:,} chars)"
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
                            writing.clear()
                            if getattr(msg, "tool_calls", None):
                                # text streamed before these tool calls was narration, not the answer
                                if _text(msg.content).strip():
                                    yield {"type": "segment", "seg": mid, "final": False}
                                if mid == seg:
                                    final = []
                            for tc in getattr(msg, "tool_calls", None) or []:
                                ev = call_event(tc)
                                if tc["name"] in ghtools.GATED and row.get("approvals") == "auto":
                                    ev["auto_approved"] = True
                                run.activity = f"running {tc['name']}: {ev['title']}"[:200]
                                yield ev
                                if tc["name"] == "write_todos":
                                    yield todos_event(tc.get("args"))
                        elif getattr(msg, "type", "") == "tool":
                            run.activity = "thinking"
                            yield result_event(msg)
        if run.cancel.is_set():
            yield {"type": "done", "text": "", "cancelled": True}
            return  # finally: closing the graph stream may wait for an in-flight model call or tool
        yield from pending_approvals(agent, cfg)
        yield {"type": "done", "text": "".join(final)}
    finally:
        stream.close()


def tail(tid: str, since: int | None):
    """Events of a chat with seq > since, then the live run until its terminal event; ends at once when
    nothing runs. since=None: from the start of the latest run. Pings (no seq) every PING_S silent seconds."""
    run = RUNS.get(tid)
    if since is None:
        if run:
            since = run.first_seq - 1
        else:
            with EV_LOCK:
                r = EV_DB.execute("SELECT first_seq FROM pd_runs WHERE tid=? ORDER BY first_seq DESC LIMIT 1",
                                  (tid,)).fetchone()
            since = r[0] - 1 if r else 0
    with EV_LOCK:
        if run:
            rows = EV_DB.execute("SELECT ev FROM pd_events WHERE tid=? AND seq>? AND seq<? ORDER BY seq",
                                 (tid, since, run.first_seq)).fetchall()
        else:
            rows = EV_DB.execute("SELECT ev FROM pd_events WHERE tid=? AND seq>? ORDER BY seq",
                                 (tid, since)).fetchall()
    for (ev,) in rows:
        yield json.loads(ev)
    if run is None:
        return
    i = max(0, since + 1 - run.first_seq)
    t_sent = time.time()
    while True:
        with run.cond:
            while len(run.events) <= i and not run.finished:
                left = PING_S - (time.time() - t_sent)
                if left <= 0:
                    break
                run.cond.wait(timeout=left)
            new = run.events[i:]
            i += len(new)
            over = run.finished and i >= len(run.events)
        for ev in new:
            yield ev
        if over:
            return
        if not new:
            yield {"type": "ping", "run": run.id, "activity": run.activity, "elapsed": int(time.time() - run.t0),
                   "idle": int(time.time() - run.t_last)}
        t_sent = time.time()


def _ndjson(events):
    for ev in events:
        yield json.dumps(ev) + "\n"


def _alive_agentd(pid: int) -> bool:
    try:
        return b"agentd.py" in Path(f"/proc/{pid}/cmdline").read_bytes()
    except OSError:
        return False


def recover_runs() -> None:
    """Runs that were live when the service stopped can never finish: close each with an error event."""
    with EV_LOCK:
        rows = EV_DB.execute("SELECT id, tid, pid FROM pd_runs WHERE status='running'").fetchall()
    for rid, tid, pid in rows:
        if pid and pid != os.getpid() and _alive_agentd(pid):
            continue  # e.g. a terminal chat in another process
        seq = _max_seq(tid) + 1
        ev = {"seq": seq, "type": "error", "run": rid,
              "text": "The agent service restarted during this run, so it stopped. Send a message to continue."}
        with EV_LOCK:
            EV_DB.execute("INSERT OR REPLACE INTO pd_events(tid,seq,run,ev) VALUES(?,?,?,?)",
                          (tid, seq, rid, json.dumps(ev)))
            EV_DB.execute("UPDATE pd_runs SET status='interrupted', ended=?, last_seq=? WHERE id=?",
                          (now(), seq, rid))
            EV_DB.commit()
        threading.Thread(target=sandbox.interrupt, args=(tid,), daemon=True).start()  # orphaned commands


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
    recover_runs()  # close runs a previous service process left open
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
    subagents: bool = True  # False = no `task` tool
    origin: str | None = None  # who opens the chat, e.g. "loop:agent-scout" (app labels it)


class ThreadPatch(BaseModel):
    title: str | None = None
    approvals: str | None = None
    subagents: bool | None = None


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
    models = CONFIG["model"].get("choices", [CONFIG["model"]["name"]])
    return {"ok": True, "version": VERSION, "model": CONFIG["model"]["name"], "models": models,
            "limits": {n: model_limits(n) for n in models}, "context_budget": CONTEXT_BUDGET,
            "summarize_fraction": SUMMARIZE_AT, "hosting": HOSTING, "region": "EU",
            "approvals_default": APPROVALS_DEFAULT, "running": len(RUNS), "ping_s": PING_S,
            "features": ["github_read", "sandbox", "pull_requests", "approvals", "repos", "detached_runs",
                         "events", "cancel", "subagents_toggle", "origin", "limits"]}


@app.get("/api/agent/threads", dependencies=[Depends(authed)])
def list_threads():
    rows = DB.execute(f"SELECT {','.join(COLS)} FROM pd_threads ORDER BY updated DESC LIMIT 100").fetchall()
    with EV_LOCK:
        seqs = dict(EV_DB.execute("SELECT tid, MAX(seq) FROM pd_events GROUP BY tid").fetchall())
    return {"threads": [{**_row(r), "last_seq": last_seq(r[0]) if r[0] in RUNS else seqs.get(r[0], 0)}
                        for r in rows]}


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
    if body.origin is not None and not ORIGIN_RE.fullmatch(body.origin):
        raise HTTPException(400, "origin: 1-40 chars of A-Z a-z 0-9 : . _ -")
    tid = uuid.uuid4().hex[:12]
    with DB_LOCK:
        DB.execute(f"INSERT INTO pd_threads({','.join(COLS)}) VALUES(?,?,?,?,?,?,?,?,?)",
                   (tid, body.title or "New chat", now(), now(), model, json.dumps(specs),
                    body.approvals or APPROVALS_DEFAULT, int(body.subagents), body.origin))
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
        if body.subagents is not None:
            DB.execute("UPDATE pd_threads SET subagents=? WHERE id=?", (int(body.subagents), tid))
        DB.commit()
    forget_agent(tid)  # a running turn keeps its settings; the next one uses the new ones
    return thread_row(tid)


@app.delete("/api/agent/threads/{tid}", dependencies=[Depends(authed)])
def delete_thread(tid: str):
    if not re.fullmatch(r"[0-9a-f]{12}", tid):
        raise HTTPException(400, "bad id")
    if tid in RUNS:
        raise HTTPException(409, "this chat is running; cancel it first")
    with DB_LOCK:
        DB.execute("DELETE FROM pd_threads WHERE id=?", (tid,))
        DB.commit()
    with EV_LOCK:
        EV_DB.execute("DELETE FROM pd_events WHERE tid=?", (tid,))
        EV_DB.execute("DELETE FROM pd_runs WHERE tid=?", (tid,))
        EV_DB.commit()
    forget_agent(tid)
    if CHECKPOINTER is not None:
        CHECKPOINTER.delete_thread(tid)
    sandbox.destroy(tid)
    return {"ok": True}


@app.get("/api/agent/threads/{tid}", dependencies=[Depends(authed)])
def get_thread(tid: str):
    """Chat with history. While `running`, turns[-1] is the live turn as far as the checkpointer has it;
    replay it in full with GET .../events (no `since`) and follow it there."""
    row = thread_row(tid)
    if not row:
        raise HTTPException(404, "no such thread")
    agent = agent_for(row)
    msgs = history(row)
    run = RUNS.get(tid)
    return {**row, "messages": msgs, "turns": turns(msgs), "workspace": sandbox.workspace(tid),
            "pending_approvals": pending_approvals(agent, {"configurable": {"thread_id": tid}}),
            "last_seq": last_seq(tid), "run": run.status() if run else None}


def _set_title(tid: str, row: dict, text: str) -> None:
    if row["title"] == "New chat":
        with DB_LOCK:
            DB.execute("UPDATE pd_threads SET title=? WHERE id=?", (text.strip()[:60], tid))
            DB.commit()


@app.post("/api/agent/threads/{tid}/chat", dependencies=[Depends(authed)])
def chat(tid: str, body: ChatIn):
    """Start a detached run (an app disconnect never stops it) and stream its events as NDJSON until its
    done/error event. Event types: start, token, segment, tool_call, tool_result, todos, approval, done,
    error, ping. While an approval is pending, a chat message answers it ("yes" approves, anything else
    rejects). 409 while the chat is still running."""
    if not body.text.strip():
        raise HTTPException(400, "empty message")
    row, lock = _begin(tid)
    try:
        _set_title(tid, row, body.text)
    except Exception:
        lock.release()
        raise
    run = start_run(row, body.text, "chat", lock, body.text)
    return StreamingResponse(_ndjson(tail(tid, run.first_seq - 1)), media_type="application/x-ndjson")


@app.post("/api/agent/threads/{tid}/resume", dependencies=[Depends(authed)])
def resume(tid: str, body: ResumeIn):
    """Answer the pending approval(s) of a chat; runs and streams the continuation like /chat."""
    if body.decision not in ("approve", "reject"):
        raise HTTPException(400, "decision must be approve or reject")
    row, lock = _begin(tid)
    try:
        pend = pending_approvals(agent_for(row), {"configurable": {"thread_id": tid}})
    except Exception:
        lock.release()
        raise
    if not pend:
        lock.release()
        raise HTTPException(409, "nothing to approve")
    cmd = _decision_command(pend, body.decision, body.message)
    label = body.decision + (f": {body.message}" if body.message else "")
    run = start_run(row, cmd, "resume", lock, label)
    return StreamingResponse(_ndjson(tail(tid, run.first_seq - 1)), media_type="application/x-ndjson")


@app.get("/api/agent/threads/{tid}/events", dependencies=[Depends(authed)])
def events(tid: str, since: int | None = None):
    """Reconnect: NDJSON replay of the chat's events with seq > since (no since = from the start of the
    latest run), then the live run until its done/error event. Ends at once when nothing is running."""
    if not thread_row(tid):
        raise HTTPException(404, "no such thread")
    return StreamingResponse(_ndjson(tail(tid, since)), media_type="application/x-ndjson")


@app.post("/api/agent/threads/{tid}/cancel", dependencies=[Depends(authed)])
def cancel(tid: str):
    """Stop the running turn: the model stream is dropped at its next chunk and running sandbox commands
    are killed. The run then ends with done {cancelled: true}. 409 when nothing is running."""
    run = RUNS.get(tid)
    if not run:
        raise HTTPException(409, "nothing is running")
    run.cancel.set()
    run.activity = "cancelling"
    threading.Thread(target=sandbox.interrupt, args=(tid,), daemon=True).start()
    return {"ok": True, "run": run.status()}


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
    c.add_argument("--no-subagents", action="store_true", help="no `task` tool in this chat")
    c.add_argument("--origin", default=None, help='label, e.g. "loop:agent-scout"')
    a = ap.parse_args()
    if a.cmd == "serve":
        import uvicorn
        uvicorn.run(app, host="127.0.0.1", port=a.port, log_level="info", timeout_graceful_shutdown=3)
        return
    specs = [RepoSpec(repo=r.split(":")[0], mode="write" if r.endswith(":write") else "read") for r in a.repo]
    tid = a.thread or new_thread(NewThread(model=a.model, repos=specs, approvals="ask" if a.ask else None,
                                           subagents=not a.no_subagents, origin=a.origin))["id"]
    row, lock = _begin(tid)
    _set_title(tid, row, a.text)
    t0 = time.time()
    run = start_run(row, a.text, "chat", lock, a.text)
    for ev in tail(tid, run.first_seq - 1):
        if ev["type"] == "ping":
            print(f"\n  .. {ev['activity']} ({ev['elapsed']}s)", end="", flush=True)
        elif ev["type"] == "token":
            print(ev["text"], end="", flush=True)
        elif ev["type"] == "tool_call":
            print(f"\n[{ev['kind']}] {ev['name']}: {ev['title']}")
        elif ev["type"] == "tool_result":
            print(f"  -> {'ok' if ev['ok'] else 'FAIL'} {ev['text'][:200]!r}")
        elif ev["type"] == "todos":
            print("  todos: " + " | ".join(f"[{i['status']}] {i['content']}" for i in ev["items"]))
        elif ev["type"] in ("approval", "error"):
            print(f"\n[{ev['type'].upper()}] {ev['text']}")
    while tid in RUNS:  # let the run thread finish its bookkeeping before the process exits
        time.sleep(0.1)
    print(f"\n-- thread {tid}, {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
