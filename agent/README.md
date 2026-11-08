# PaperDesk agent

A LangChain [DeepAgent](https://github.com/langchain-ai/deepagents) chat service for the PaperDesk app.

- `agentd.py`: FastAPI on `127.0.0.1:8800`, published tailnet-only as `https://paper1.tailbf37ab.ts.net:8448`
  (mapping + keepalive in `~/.claude/loops/review_server_start.sh`). Same bearer token as the PaperDesk server.
- `config.toml`: model endpoint (OpenAI-compatible). Testing runs on ScaDS.AI (`SCADS_API_KEY`), whose daily
  request cap is shared with the paper studies, so keep chats moderate.
- Conversations: LangGraph SQLite checkpointer in `~/.local/state/paperdesk/agent.sqlite`.
- Phase 1 tools = DeepAgent built-ins only (todos, virtual in-state files, sub-agents). No access to the
  box's filesystem or shell.
- Venv: `virtualenv ~/.venvs/deepagent && ~/.venvs/deepagent/bin/pip install -r requirements.txt`.
- Start/restart: `./start.sh [--restart]`. Terminal test: `agentd.py chat "question" [--thread ID]`.

## API (all but health need `Authorization: Bearer <token>`)
- `GET  /api/agent/health` → `{ok, version, model, models}`
- `GET  /api/agent/threads` → `{threads: [{id, title, created, updated, model}]}`
- `POST /api/agent/threads` body `{title?, model?}` → thread
- `GET  /api/agent/threads/<id>` → thread + `messages: [{role: user|assistant|tool_call|tool_result, text, name?}]`
- `DELETE /api/agent/threads/<id>`
- `POST /api/agent/threads/<id>/chat` body `{text}` → NDJSON stream, one event per line:
  `token` (text delta) · `tool_call` · `tool_result` · `done` (full answer) · `error`. 409 while that chat is busy.
