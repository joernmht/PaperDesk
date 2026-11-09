# PaperDesk API (contract between `server/` and `android/`)

Base URL (tailnet only): `https://paper1.tailbf37ab.ts.net:8447` → box `127.0.0.1:8799`.
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
- `GET  /api/health` → `{ok: true, version: str, apk_version: int}`
- `GET  /api/inbox?status=open|all` → `{items: [Paper]}`  (open = new + later + pending, newest run first)
- `POST /api/action` body `{id, action, paper?, target?, toread?}` → `{item: Paper, job: Job|null}`
  - `action`: `"literature"` (add to corpus; `toread: true` also sends it to the reMarkable),
    `"include"` (`target` = `p0|p1|p2|p3`: add to corpus if needed, then queue a cite note for that
    paper's session), `"remarkable"` (send PDF to `/Papers/To read`), `"dismiss"`, `"later"`, `"restore"`.
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
