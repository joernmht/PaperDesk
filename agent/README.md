# PaperDesk agent

A LangChain [DeepAgent](https://github.com/langchain-ai/deepagents) chat service for the PaperDesk app.

- `agentd.py`: FastAPI on `127.0.0.1:8800`, published tailnet-only as `https://paper1.tailbf37ab.ts.net:8448`
  (mapping + keepalive in `~/.claude/loops/review_server_start.sh`). Same bearer token as the PaperDesk server.
- `config.toml`: model endpoint (OpenAI-compatible). **EU-hosted LLMs only** (Joern 2026-11-09): `agentd.py`
  refuses to start unless the endpoint host is on its in-code `EU_ENDPOINTS` allowlist and `region = "EU"`.
  Allowed: ScaDS.AI (`llm.scads.ai`, TU Dresden/Leipzig, `SCADS_API_KEY`; daily cap shared with the paper studies)
  and OpenRouter's EU in-region routing (`eu.openrouter.ai` only, needs a Business plan; plain `openrouter.ai` is
  refused because the request passes a US company). No repo is deny-listed (EBL is fine on EU models).
- Conversations: LangGraph SQLite checkpointer in `~/.local/state/paperdesk/agent.sqlite`.
- Tools: DeepAgent built-ins (todos, files, `execute` shell, sub-agents) inside a **per-chat Docker sandbox**
  (`sandbox.py`, image `pda-sandbox:1` from `sandbox/Dockerfile`: uid 1000, caps dropped, 2 GB / 2 CPU / 512 PIDs,
  no secrets; `/workspace` = `/vol/scratch/agent-ws/<chat>/work`; idle containers stop after 30 min, at most 3
  run; deleting a chat removes its sandbox). GitHub read tools on the box's `gh` login (`ghtools.py`).
- **Repos per chat, read vs write** (0.3): any number of repos. READ-ONLY ones are mounted `:ro` at
  `/workspace/ro/<name>` (the agent cannot change them; no PR possible). **No double clones** (0.4): a repo with a
  checkout on the box (`~/*`, `/vol/home/*` with a GitHub origin) is bind-mounted read-only from there (live
  state incl. uncommitted work; secret-looking files such as `.env`, `*.pem`, `local.properties` are covered with
  `/dev/null`); others are shallow host clones in `<ws>/ro/<name>`. Writable copies of box repos borrow its git
  objects (`--reference`), so only missing objects come from GitHub. Live checkouts are never written. The agent
  may attach reference repos itself (`clone_repo_readonly`). WRITABLE ones (`/workspace/<name>`, work branch) only
  when Joern picks them at chat setup / via the attach endpoint, or approves the agent's `clone_repo_writable`.
- Code + PR: a writable attach makes a host-side pristine clone and hands it to the sandbox as a git bundle; the agent
  edits, tests in a venv and commits there; `review_changes`/`open_pull_request` bundle the commits back into the
  pristine clone, refuse `.github/` changes, push branch `agent/<chat>-<repo>` over SSH and open a **draft** PR.
  The host never runs git inside the sandbox's workspace (planted hooks/config cannot reach it).
- **Writes**: `open_pull_request` (draft), `clone_repo_writable`, `github_comment`, `github_create_issue`.
  Approvals default to **auto** (`[approvals] default`, Joern 2026-11-09): they run directly and their
  `tool_call` carries `auto_approved: true`. A chat set to `ask` gates them behind approve/reject. Writes only to `write_owners`
  repos; `deny` repos (EBL) are off-limits (`config.toml [github]`).
- Model choice (ScaDS, 2026-11-09): DeepSeek-V4.1-Flash is the default because it completed clone -> venv ->
  479 tests -> edit -> commit -> PR on its own (~5 min); gpt-oss-120b (fast for chat) and GLM-5.3-Flash stalled.
- Venv: `virtualenv ~/.venvs/deepagent && ~/.venvs/deepagent/bin/pip install -r requirements.txt`.
- Start/restart: `./start.sh [--restart [--force]]`. Terminal test: `agentd.py chat "question" [--thread ID] [--no-subagents] [--origin X]`.

## API (all but health need `Authorization: Bearer <token>`)
- `GET  /api/agent/health` → `{ok, version, model, models, limits: {model: {max_input_tokens, max_output_tokens,
  effective_input_tokens, summarize_at}}, context_budget, summarize_fraction, hosting, region, approvals_default,
  running, ping_s, features}`. Context: deepagents summarises old messages at 85 % of
  `min(window, context_budget)` and keeps the newest 10 % (`[model.limits]` + `context_budget` in config.toml).
- `GET  /api/agent/threads` → `{threads: [thread + last_seq]}`. A thread is `{id, title, created, updated, model,
  repos, approvals: auto|ask, subagents: bool, origin: str|null, running: bool}`.
- `POST /api/agent/threads` body `{title?, model?, repos?: [{repo, mode: "read"|"write"}], approvals?,
  subagents?: bool (default true; false = no `task` tool), origin?: "loop:agent-scout" (1-40 chars A-Za-z0-9:._-)}`
  → thread (repos are cloned when the first message runs and show up as `attach_repo` boxes)
- `GET  /api/agent/repos` → `{repos: [{repo, description, on_box, private, pushed, writable}]}` for the picker
  (deny-listed repos left out; `writable` = owner allowed for write mode)
- `POST /api/agent/threads/<id>/repos` body `{repo, mode}` → `{attached, workspace}`: attach now (400 on refusal)
- `DELETE /api/agent/threads/<id>/repos/<name>` → `{workspace}`: detach (a writable copy's unpushed work is lost)
- `PATCH /api/agent/threads/<id>` body `{title?, approvals?: "auto"|"ask", subagents?: bool}` (a running turn
  keeps its settings; the next one uses the new ones)
- workspace entries: `{repo, dir, mode, branch, source: box|github|box+github, masked?}`
- `DELETE /api/agent/threads/<id>` (409 while it runs: cancel first)
- `GET  /api/agent/threads/<id>` → thread + `messages` (flat) + `turns: [{user, steps: [rows], answer}]` (steps =
  tool boxes, todos and `narration` text, for the collapsed "steps" dropdown; `answer` = final Markdown or null)
  + `workspace` + `pending_approvals: [approval event]` + `last_seq` + `run` (null, or while running
  `{id, kind, started, first_seq, elapsed, activity, cancelling}`). Rows carry the event fields below
  (`role` = user | assistant | narration | tool_call | tool_result | todos). While running, `turns[-1]` is the
  live turn as far as the checkpointer has it: replace it with the `/events` replay.

### Runs are detached (0.5)
- `POST /api/agent/threads/<id>/chat` body `{text}` starts a run in a background thread and streams its events
  as NDJSON until its `done`/`error` event. A dropped connection (screen lock, Tailscale reconnect) never stops
  the run. 409 while the chat is running. While an approval is pending, a chat message answers it:
  "yes/ok/approve/ja" approves, anything else rejects with that text as the reason.
- `POST /api/agent/threads/<id>/resume` body `{decision: "approve"|"reject", message?}` → same, as a run.
- `GET  /api/agent/threads/<id>/events?since=<seq>` → reconnect: NDJSON replay of the events with seq > since, then
  the live run until its `done`/`error`; the stream ends at once when nothing runs. No `since` = from the start
  of the latest run (the usual reconnect). Read to EOF: with an old `since` the replay can hold several runs.
- `POST /api/agent/threads/<id>/cancel` → `{ok, run}`; 409 when nothing runs. The model stream is dropped at its
  next chunk and the sandbox's running commands are killed; the run ends with `done {cancelled: true}`.
  `running` can stay true a few seconds after that, until an in-flight model call returns (/chat → 409
  meanwhile). A reply the model was already writing is still kept in the history.
- Every event except `ping` carries `seq` (per chat, contiguous, from 1). Events live in `pd_events`
  (last 20 000 per chat). If the service restarts during a run, that run gets a closing
  `error {text: "The agent service restarted …"}` at the next start.
- `start.sh --restart` refuses while runs are live (exit 2); `--restart --force` restarts anyway.

### Events (render each non-token event as a box)
- `start {run, kind: chat|resume, text, model}`: first event of a run; `text` = the user message (or
  "approve" / "reject: reason" for a resume), so a replay can draw the user bubble.
- `token {text, seg}`: Markdown text delta; `seg` = id of the model message it belongs to.
- `segment {seg, final: false}`: the text streamed under `seg` was narration before tool calls → move it into the
  steps dropdown. Text of a `seg` that never gets this event is the answer.
- `done {run, text, cancelled?}`: the final answer only (Markdown). `error {run, text}`. Exactly one of them ends
  every run, and it is sent only once the chat is unlocked (the next message can go out at once).
- `ping {run, activity, elapsed, idle}` (no seq, not stored): sent after 15 s without events, e.g. while a shell
  command runs or the model writes long tool arguments. `activity` = "running execute: pytest -q",
  "preparing write_file (12,345 chars)", "writing", "thinking", "sub-agent working", "cancelling".
- UI pattern Joern asked for: while running, show the steps collapsed as one dropdown whose header is the latest
  running step (the newest `tool_call` without a `tool_result` yet); at the end only the answer stays expanded.
- `tool_call {id, name, kind, title, args, text}` and `tool_result {id, name, kind, ok, text, exit_code?, url?}`:
  pair them by `id` into one box. `kind` = shell | file | todos | subagent | git | github | tool; `title` is a
  one-line label (shell command, file path, repo · number); `ok=false` → error styling; `url` on a PR result.
- `todos {items: [{content, status: pending|in_progress|completed}]}`: the agent's current plan (replace, not append).
- `approval {id, actions: [{name, kind, title, args}], text}`: show args (PR title/body, comment text) with
  Approve / Reject buttons → `/resume`. The stream ends (`done`) after it.
