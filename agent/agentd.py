"""PaperDesk agent: a LangChain DeepAgent chat service for the PaperDesk Android app.

Runs on 127.0.0.1:8800 (published on the tailnet as https://paper1.<tailnet>.ts.net:8448 by
~/.claude/loops/review_server_start.sh). Same bearer token as the PaperDesk server
(~/.config/paperdesk/token). Conversations persist in a LangGraph SQLite checkpointer.

Model: any OpenAI-compatible endpoint, configured in agent/config.toml (default: ScaDS.AI).
Phase 1 tools: the DeepAgent built-ins only (todo list, virtual in-state files, sub-agents).
The agent has NO access to the box's real filesystem or shell.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import threading
import time
import tomllib
import uuid
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

HOME = Path.home()
DIR = Path(__file__).resolve().parent
STATE = HOME / ".local/state/paperdesk"
DB_PATH = STATE / "agent.sqlite"
TOKEN_FILE = HOME / ".config/paperdesk/token"
VERSION = "0.1.0"

CONFIG = tomllib.loads((DIR / "config.toml").read_text())

SYSTEM_PROMPT = """You are Joern's research assistant inside his PaperDesk phone app.
Joern is a PhD researcher at TU Dresden (Chair of Railway Operations) working on LLMs and
mixed-integer linear programming (MILP) for railway rescheduling.
Answer concisely: he reads you on a phone. Use Markdown sparingly (short lists, `code`).
For multi-step work, plan with the todo tool and keep notes in your virtual files.
If you do not know something, say so instead of guessing."""


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


# ---------------------------------------------------------------- persistence
def _conn() -> sqlite3.Connection:
    STATE.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(DB_PATH, check_same_thread=False)
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("""CREATE TABLE IF NOT EXISTS pd_threads(
        id TEXT PRIMARY KEY, title TEXT, created TEXT, updated TEXT, model TEXT)""")
    c.commit()
    return c


DB = _conn()
DB_LOCK = threading.Lock()
THREAD_LOCKS: dict[str, threading.Lock] = {}


def build_agent(checkpointer, model_name: str | None = None):
    from deepagents import create_deep_agent
    from langchain_openai import ChatOpenAI

    m = CONFIG["model"]
    llm = ChatOpenAI(
        model=model_name or m["name"],
        base_url=m["base_url"],
        api_key=os.environ[m["api_key_env"]],
        temperature=m.get("temperature", 0.3),
        timeout=m.get("timeout", 120),
        max_retries=2,
    )
    return create_deep_agent(model=llm, tools=[], system_prompt=SYSTEM_PROMPT,
                             checkpointer=checkpointer)


def make_checkpointer():
    from langgraph.checkpoint.sqlite import SqliteSaver
    return SqliteSaver(DB)


CHECKPOINTER = None
AGENTS: dict[str, object] = {}


def agent_for(model_name: str):
    global CHECKPOINTER
    with DB_LOCK:
        if CHECKPOINTER is None:
            CHECKPOINTER = make_checkpointer()
        if model_name not in AGENTS:
            AGENTS[model_name] = build_agent(CHECKPOINTER, model_name)
        return AGENTS[model_name]


# ---------------------------------------------------------------- helpers
def _text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):  # content blocks
        return "".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
    return str(content or "")


def history(thread_id: str, model_name: str) -> list[dict]:
    st = agent_for(model_name).get_state({"configurable": {"thread_id": thread_id}})
    out = []
    for msg in (st.values or {}).get("messages", []):
        kind = msg.type  # human | ai | tool | system
        if kind == "human":
            out.append({"role": "user", "text": _text(msg.content)})
        elif kind == "ai":
            for tc in getattr(msg, "tool_calls", None) or []:
                out.append({"role": "tool_call", "name": tc["name"], "text": json.dumps(tc["args"])[:400]})
            t = _text(msg.content)
            if t.strip():
                out.append({"role": "assistant", "text": t})
        elif kind == "tool":
            out.append({"role": "tool_result", "name": getattr(msg, "name", ""), "text": _text(msg.content)[:400]})
    return out


def thread_row(tid: str) -> dict | None:
    r = DB.execute("SELECT id,title,created,updated,model FROM pd_threads WHERE id=?", (tid,)).fetchone()
    return dict(zip(("id", "title", "created", "updated", "model"), r)) if r else None


# ---------------------------------------------------------------- API
app = FastAPI(title="PaperDesk agent", version=VERSION)


def authed(request: Request) -> None:
    want = TOKEN_FILE.read_text().strip()
    got = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    if not want or got != want:
        raise HTTPException(401, "unauthorized")


class NewThread(BaseModel):
    title: str | None = None
    model: str | None = None


class ChatIn(BaseModel):
    text: str


@app.get("/api/agent/health")
def health():
    return {"ok": True, "version": VERSION, "model": CONFIG["model"]["name"],
            "models": CONFIG["model"].get("choices", [CONFIG["model"]["name"]])}


@app.get("/api/agent/threads", dependencies=[Depends(authed)])
def list_threads():
    rows = DB.execute("SELECT id,title,created,updated,model FROM pd_threads ORDER BY updated DESC LIMIT 100").fetchall()
    return {"threads": [dict(zip(("id", "title", "created", "updated", "model"), r)) for r in rows]}


@app.post("/api/agent/threads", dependencies=[Depends(authed)])
def new_thread(body: NewThread):
    model = body.model or CONFIG["model"]["name"]
    if model not in CONFIG["model"].get("choices", [CONFIG["model"]["name"]]):
        raise HTTPException(400, f"unknown model {model}")
    tid = uuid.uuid4().hex[:12]
    with DB_LOCK:
        DB.execute("INSERT INTO pd_threads VALUES(?,?,?,?,?)", (tid, body.title or "New chat", now(), now(), model))
        DB.commit()
    return thread_row(tid)


@app.delete("/api/agent/threads/{tid}", dependencies=[Depends(authed)])
def delete_thread(tid: str):
    with DB_LOCK:
        DB.execute("DELETE FROM pd_threads WHERE id=?", (tid,))
        DB.commit()
    if CHECKPOINTER is not None:
        CHECKPOINTER.delete_thread(tid)
    return {"ok": True}


@app.get("/api/agent/threads/{tid}", dependencies=[Depends(authed)])
def get_thread(tid: str):
    row = thread_row(tid)
    if not row:
        raise HTTPException(404, "no such thread")
    return {**row, "messages": history(tid, row["model"])}


@app.post("/api/agent/threads/{tid}/chat", dependencies=[Depends(authed)])
def chat(tid: str, body: ChatIn):
    """Stream the answer as NDJSON lines:
    {"type":"token","text"} | {"type":"tool_call","name","text"} | {"type":"tool_result","name","text"}
    | {"type":"done","text"} | {"type":"error","text"}"""
    row = thread_row(tid)
    if not row:
        raise HTTPException(404, "no such thread")
    if not body.text.strip():
        raise HTTPException(400, "empty message")
    lock = THREAD_LOCKS.setdefault(tid, threading.Lock())
    if not lock.acquire(blocking=False):
        raise HTTPException(409, "this chat is still answering")
    if row["title"] == "New chat":
        with DB_LOCK:
            DB.execute("UPDATE pd_threads SET title=? WHERE id=?", (body.text.strip()[:60], tid))
            DB.commit()

    return StreamingResponse(_stream(tid, row, body.text, lock), media_type="application/x-ndjson")


def _stream(tid: str, row: dict, text: str, lock: threading.Lock):
    if True:
        agent = agent_for(row["model"])
        cfg = {"configurable": {"thread_id": tid}, "recursion_limit": 60}
        final = []
        try:
            for mode, data in agent.stream({"messages": [{"role": "user", "content": text}]},
                                           cfg, stream_mode=["messages", "updates"]):
                if mode == "messages":
                    chunk, meta = data
                    # only the main agent's model tokens, not sub-agents or tool output
                    if chunk.type in ("AIMessageChunk", "ai") and meta.get("langgraph_node") == "model" \
                            and not meta.get("langgraph_checkpoint_ns", "").count("|"):
                        t = _text(chunk.content)
                        if t:
                            final.append(t)
                            yield json.dumps({"type": "token", "text": t}) + "\n"
                elif mode == "updates":
                    for node, upd in (data or {}).items():
                        for msg in (upd or {}).get("messages", []) if isinstance(upd, dict) else []:
                            if getattr(msg, "type", "") == "ai":
                                for tc in getattr(msg, "tool_calls", None) or []:
                                    yield json.dumps({"type": "tool_call", "name": tc["name"],
                                                      "text": json.dumps(tc["args"])[:400]}) + "\n"
                            elif getattr(msg, "type", "") == "tool":
                                yield json.dumps({"type": "tool_result", "name": getattr(msg, "name", ""),
                                                  "text": _text(msg.content)[:400]}) + "\n"
            yield json.dumps({"type": "done", "text": "".join(final)}) + "\n"
        except Exception as e:  # surface model/API errors to the app instead of a dead stream
            yield json.dumps({"type": "error", "text": f"{type(e).__name__}: {e}"[:500]}) + "\n"
        finally:
            with DB_LOCK:
                DB.execute("UPDATE pd_threads SET updated=? WHERE id=?", (now(), tid))
                DB.commit()
            lock.release()


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--port", type=int, default=8800)
    c = sub.add_parser("chat", help="one-shot terminal chat for testing")
    c.add_argument("text")
    c.add_argument("--thread", default=None)
    a = ap.parse_args()
    if a.cmd == "serve":
        import uvicorn
        uvicorn.run(app, host="127.0.0.1", port=a.port, log_level="info")
    else:
        tid = a.thread or new_thread(NewThread())["id"]
        t0 = time.time()
        row = thread_row(tid)
        lock = THREAD_LOCKS.setdefault(tid, threading.Lock())
        lock.acquire()
        for line in _stream(tid, row, a.text, lock):
            ev = json.loads(line)
            if ev["type"] == "token":
                print(ev["text"], end="", flush=True)
            elif ev["type"] != "done":
                print(f"\n[{ev['type']}] {ev.get('name', '')} {ev['text']}")
        print(f"\n-- thread {tid}, {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
