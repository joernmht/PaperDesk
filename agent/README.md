# PaperDesk agent

A LangChain [DeepAgent](https://github.com/langchain-ai/deepagents) chat service for the PaperDesk app.

- `agentd.py`: FastAPI on `127.0.0.1:8800`, published tailnet-only as `https://paper1.tailbf37ab.ts.net:8448`
  (mapping + keepalive in `~/.claude/loops/review_server_start.sh`). Same bearer token as the PaperDesk server.
- `config.toml`: model endpoint (OpenAI-compatible). Testing runs on ScaDS.AI (`SCADS_API_KEY`), whose daily
  request cap is shared with the paper studies, so keep chats moderate.
- Conversations: LangGraph SQLite checkpointer in `~/.local/state/paperdesk/agent.sqlite`.
- Tools: DeepAgent built-ins (todos, files, `execute` shell, sub-agents) inside a **per-chat Docker sandbox**
  (`sandbox.py`, image `pda-sandbox:1` from `sandbox/Dockerfile`: uid 1000, caps dropped, 2 GB / 2 CPU / 512 PIDs,
  no secrets; `/workspace` = `/vol/scratch/agent-ws/<chat>/work`; idle containers stop after 30 min, at most 3
  run; deleting a chat removes its sandbox). GitHub read tools on the box's `gh` login (`ghtools.py`).
- Code + PR: `clone_repo` makes a host-side pristine clone and hands it to the sandbox as a git bundle; the agent
  edits, tests in a venv and commits there; `review_changes`/`open_pull_request` bundle the commits back into the
  pristine clone, refuse `.github/` changes, push branch `agent/<chat>-<repo>` over SSH and open a **draft** PR.
  The host never runs git inside the sandbox's workspace (planted hooks/config cannot reach it).
- **Approval-gated**: `open_pull_request`, `github_comment`, `github_create_issue`. Writes only to `write_owners`
  repos; `deny` repos (EBL) are off-limits (`config.toml [github]`).
- Model choice (ScaDS, 2026-11-09): DeepSeek-V4.1-Flash is the default because it completed clone -> venv ->
  479 tests -> edit -> commit -> PR on its own (~5 min); gpt-oss-120b (fast for chat) and GLM-5.3-Flash stalled.
- Venv: `virtualenv ~/.venvs/deepagent && ~/.venvs/deepagent/bin/pip install -r requirements.txt`.
- Start/restart: `./start.sh [--restart]`. Terminal test: `agentd.py chat "question" [--thread ID]`.

## API (all but health need `Authorization: Bearer <token>`)
- `GET  /api/agent/health` → `{ok, version, model, models}`
- `GET  /api/agent/threads` → `{threads: [{id, title, created, updated, model}]}`
- `POST /api/agent/threads` body `{title?, model?}` → thread
- `GET  /api/agent/threads/<id>` → thread + `messages: [{role: user|assistant|tool_call|tool_result, text, name?}]`
- `DELETE /api/agent/threads/<id>`
- `GET  /api/agent/threads/<id>` also returns `pending_approvals: [approval event]`; history rows carry the same
  fields as the events below (`role` = user | assistant | tool_call | tool_result | todos).
- `POST /api/agent/threads/<id>/chat` body `{text}` → NDJSON stream, one event per line. 409 while that chat is
  busy. While an approval is pending, a chat message answers it: "yes/ok/approve/ja" approves, anything else
  rejects with that text as the reason.
- `POST /api/agent/threads/<id>/resume` body `{decision: "approve"|"reject", message?}` → same NDJSON stream.

### Events (render each non-token event as a box)
- `token {text}`: Markdown text delta of the answer. `done {text}`: full answer (Markdown). `error {text}`.
- `tool_call {id, name, kind, title, args, text}` and `tool_result {id, name, kind, ok, text, exit_code?, url?}`:
  pair them by `id` into one box. `kind` = shell | file | todos | subagent | git | github | tool; `title` is a
  one-line label (shell command, file path, repo · number); `ok=false` → error styling; `url` on a PR result.
- `todos {items: [{content, status: pending|in_progress|completed}]}`: the agent's current plan (replace, not append).
- `approval {id, actions: [{name, kind, title, args}], text}`: show args (PR title/body, comment text) with
  Approve / Reject buttons → `/resume`. The stream ends (`done`) after it.
