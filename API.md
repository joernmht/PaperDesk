# PaperDesk API (contract between `server/` and `android/`)

Base URL (tailnet only): `https://<machine>.<tailnet>.ts.net:8447` → `127.0.0.1:8799` (`tailscale serve`).
Every `/api/*` and `/files/*` request carries `Authorization: Bearer <token>` (token in
`~/.config/paperdesk/token` on the box, baked into the APK at build time). JSON in and out, UTF-8.
Errors: HTTP 4xx/5xx with `{"error": "..."}`.

## Types
```
Paper = { id: str,                      # "doi:10.x/y" | "arxiv:2501.01234" | "bib:<bibkey>" | "t:<normalised title>"
          title: str, authors: [str], year: str|null, venue: str|null, abstract: str|null,
          url: str|null, pdf_url: str|null, doi: str|null, arxiv: str|null,
          why: str|null,                # scout's reason ("P0/P2 related work: ...")
          cat: str|null,                # "research" | "arail" | null
          run: str|null,                # scout run date YYYY-MM-DD
          source: str,                  # "scout" | "library" | "arxiv" | "openalex" | "scopus"
          status: str,                  # "new" | "later" | "dismissed" | "added" | "included" | "pending"
          in_corpus: bool, bibkey: str|null, has_pdf: bool,
          targets: [str] }              # papers it was queued for, e.g. ["p1"]
Job   = { id: str, kind: str, title: str, status: "queued"|"running"|"done"|"failed",
          created: str, started: str|null, finished: str|null, summary: str|null, log_tail: str|null }
Event = { seq: int, ts: str, kind: str, title: str, body: str, ref: str|null }
        # kind: "scout" | "job" | "remarkable" | "notify" (mirror of notify.sh) ...
```

## Endpoints
- `GET  /api/health` → `{ok, version, apk_version, name, profile: "portable"|"box",
     features: {remarkable, drafts, transfer, topics, agent, rm_connect}: bool, targets: [{id, label}]}`
  (servers before 0.2 send only the first three: the app then assumes every feature and targets P0–P3)
- Pairing (no token): `GET /pair/<CODE>` = HTML page with the app download and a `paperdesk://pair?url=…&token=…&name=…[&agent=…]`
  link (the QR code from `paperdesk pair` encodes this URL); `GET /api/pair?code=<CODE>` → `{url, token, name, agent}`
  or 403. Codes: 8 characters `XXXX-XXXX`, case-insensitive, 30 minutes, 10 misses cancel all open codes.
- Topics: `GET /api/topics` → `{topics: [{id, name, query, last_run}]}`; `POST /api/topics` body
  `{op: "add", name, query}` | `{op: "remove", id}` | `{op: "run"}` → `{topics, job: Job|null}`. Their papers
  arrive in the inbox with `source: "watch"`, `cat: "watch"`, `why: "Topic: <name>"`.
- `POST /api/rm/connect` body `{code}` (8-letter one-time code from my.remarkable.com/device/browser/connect)
  → `{job}`; when it is done `features.remarkable` turns true. Only when `features.rm_connect`.
- `GET  /api/inbox?status=open|all` → `{items: [Paper]}`  (open = new + later + pending, newest run first)
- `POST /api/action` body `{id, action, paper?, target?, toread?}` → `{item: Paper, job: Job|null}`
  - `action`: `"literature"` (add to corpus; `toread: true` also sends it to the reMarkable),
    `"include"` (`target` = one of health's `targets`: add to the library if needed, then copy the BibTeX
    entry into that project's .bib (portable) or queue a cite note for that paper's session (box)), `"remarkable"` (send PDF to `/Papers/To read`), `"dismiss"`, `"later"`, `"restore"`.
  - `paper` = the full Paper object when the id is not in the inbox (search results).
- `GET  /api/search?q=<text>&scope=library|web|all` → `{library: [Paper], web: [Paper]}`
- `GET  /api/decisions` → `{decisions: [{ts, id, title, action, target, source, cat, run, why, doi, arxiv}], stats: {dismissed, literature, remarkable, later, include: {p0..p3}}}` (newest first; append-only log `~/.local/state/paperdesk/decisions.jsonl`, also read by the scout loops)
- `GET  /api/jobs` → `{jobs: [Job]}` (newest first, last 50); `GET /api/jobs/<id>` → `Job` with longer `log_tail`
- `GET  /api/events?since=<seq>` → `{events: [Event], last: int}` (since=0 → last 30 only)
- reMarkable
  - `GET  /api/rm/ls?path=/Papers` → `{path, entries: [{name, type: "d"|"f", path}]}`
  - `GET  /api/rm/drafts` → `{papers: [{pid, label, versions: [{hash, sent, remote, older: bool,
       annotated: str|null}]}]}` (`annotated` = file name under /files/annotated/ if pulled)
  - `GET  /api/rm/annotated` → `{files: [{name, size, mtime}]}`
  - `POST /api/rm/action` body `{op, pid?, hash?, path?}` → `{job: Job}`
    - `op`: `"draft"` (build + send pid now), `"pull"` (`path` → fetch annotated PDF),
      `"draftpull"` (pid, hash?), `"transfer"` (pid, hash? → handwritten marks into the LaTeX source via
      the remarkable-notes skill; commits + pushes the paper), `"archive"` (`path` → move into
      `<its folder>/Older`), `"delete"` (`path`; moves to the tablet's trash).
- `GET  /files/annotated/<name>` → `application/pdf`
- `GET  /download/paperdesk.apk` (no token needed; tailnet only) — the newest APK

## Papers, keys, chat agent (0.2)
- `GET /api/papers` → `{papers: [{id, label, main, branch, head: {hash, date, subject}, comments: int, rm: bool, error}]}`
- `POST /api/papers` (portable only) `{op: "add", label, git_url, main?}` | `{op: "remove", id}` → `{papers, job}`
- `GET /api/papers/<id>/files` → `{files: [{path, size}]}` (text files only)
- `GET /api/papers/<id>/file?path=` → `{path, text, sha, head}` (sha = git blob sha of the text)
- `POST /api/papers/<id>/file` `{path, text, base_sha, message?}` → `{sha, commit, pushed, note}` or
  409 `{error: "changed", text, sha}` (the file changed since `base_sha`; resend with the new sha to keep yours)
- `GET /api/papers/<id>/comments` → `{comments: [{id, kind: mark|note|rmnote|rmhl|rmdel, path, line, col, marked, comment}]}`
- `POST /api/papers/<id>/build` → `{job}`; `GET /api/papers/<id>/pdf` → `application/pdf`; `POST /api/papers/<id>/sync` → `{head}`
- `GET /api/secrets` → `{secrets: [{name, label, help, url, optional, set}]}` (never values);
  `POST /api/secrets {name, value}` → same list (portable only)
- `GET /api/agent-setup` → `{base_url, model, configured, providers: [...], running}`;
  `POST /api/agent-setup {base_url, model}` → `{job}` (writes `~/.config/paperdesk/agent.toml`; the agent re-reads it)
- health `features` adds `papers`, `papers_edit`, `latex`, `secrets`, `agent_setup`.
