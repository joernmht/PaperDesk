# PaperDesk

Joern's personal paper desk: an Android app plus a small server on the research box. It replaces the
Telegram messages for paper updates.

- **Inbox:** the weekly scout picks (new papers for P0–P3 and ARail), each with its "why". One tap to
  **Add to literature** (into `~/LiteratureAssistant` via the `literature-intake` skill), **Include in paper**
  P0–P3 (adds it to the corpus, then queues a cite task in that paper session's `INBOX-literature.md`),
  **Send to reMarkable**, Later, Dismiss.
- **Search:** your own library (BibTeX metadata + full text of the corpus PDFs, ranked) and the wider
  literature (OpenAlex, arXiv, Scopus) with the same buttons on every result.
- **reMarkable:** your drafts P0–P3 (send a fresh draft, pull the annotated copy, transfer the handwritten
  marks into the LaTeX source via the `remarkable-notes` skill), browse/archive/delete on the tablet, open
  annotated PDFs on the phone.
- **Activity + notifications:** job results, new scout picks and everything the box sends through
  `notify.sh` arrive as Android notifications (the app polls every 15 min; faster while jobs run).

## Layout
- `API.md`: the contract between app and server.
- `server/paperdesk.py`: stdlib HTTP server on `127.0.0.1:8799`, published on the tailnet only as
  `https://paper1.tailbf37ab.ts.net:8447` (`tailscale serve`, mapped by `~/.claude/loops/review_server_start.sh`,
  which also keeps `server/start.sh` running). State in `~/.local/state/paperdesk/`, bearer token in
  `~/.config/paperdesk/token` (never committed). Jobs run one at a time; LLM jobs run headless `claude -p`
  (corpus intake: Sonnet 5.5/medium; handwriting → LaTeX: Opus 5.5/high).
- `android/`: the Kotlin/Compose app (see `android/README.md`). The APK is built on the box, served at
  `/download/paperdesk.apk` on the tailnet and never published.

## Security
Reachable from Joern's tailnet only, plus a bearer token. Every write goes through a fixed job type; the
app cannot send free-form commands or prompts.

## AI disclaimer
This repository was written with substantial help from AI coding assistants (Claude Code, Anthropic),
under the direction and review of the author. Treat it like any other code: review before you rely on it.

## License
Apache-2.0, see `LICENSE` and `NOTICE`.
