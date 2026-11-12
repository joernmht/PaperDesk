# PaperDesk

A paper desk in your pocket: an Android app plus your own Linux machine. It finds new papers on the topics
you follow, keeps your library (one BibTeX file + PDFs, readable by Zotero, JabRef and LaTeX), lets you edit
and comment your LaTeX papers (Overleaf or GitHub) on the phone and build their PDFs, runs a chat agent that
works on your GitHub repos in a sandbox and, if you have one, talks to your reMarkable tablet.

<p align="center"><img src="docs/icon.svg" width="96" alt="PaperDesk icon: blue circle, red sheet, yellow folded corner"></p>

## Setup
**Start with the app: it walks you through every step, from an empty VM to a working machine.** You need an
Android phone (13 or newer), a Linux machine that stays on (a cloud VM is enough: Ubuntu 24.04; 2 vCPU and
4 GB RAM with the chat agent, 1 vCPU / 2 GB without; ~10 GB disk), and a free
[Tailscale](https://tailscale.com) account. Optional: an LLM API key (chat agent), a GitHub token, an
Overleaf git token, a reMarkable.

1. **Install the app** from the [latest release](https://github.com/joernmht/PaperDesk/releases/latest)
   (`paperdesk.apk`). Android asks whether your browser may install apps; allow it once.
2. **Tailscale on the phone.** Install Tailscale from the Play Store and sign in. It is a private network
   between your phone and your machine; PaperDesk is never on the public internet.
3. **Install PaperDesk on the machine.** Log in with SSH and run
   ```bash
   curl -fsSL https://raw.githubusercontent.com/joernmht/PaperDesk/main/install.sh | bash
   ```
   It installs Docker, LaTeX (TinyTeX), Tailscale, the PaperDesk server and the chat agent (about 10
   minutes). Tailscale prints a sign-in link and QR code: sign in with the **same** Tailscale account as on
   the phone. If Tailscale asks you to enable HTTPS for your tailnet, open its link and enable it. At the end
   you see a QR code, an address and a pairing code. (`--no-agent`, `--no-latex` make it smaller.)
4. **Connect.** In the app tap *Scan the QR code* (or type the address and code). Codes are valid for 30
   minutes; `paperdesk pair` on the machine makes a new one.
5. **Keys.** The app asks for the keys you want to use (LLM API key, GitHub token, Overleaf git token,
   optional Scopus/OpenAlex). They go once over your private Tailscale link to your own machine and are
   stored there in `~/.config/paperdesk/secrets.env` (readable only by you); the app never shows them again.
6. **Chat agent.** Pick a model provider (EU-hosted ones are marked) and a model.
7. **Papers.** Add your paper repos by their git URL (Overleaf: *Menu > Git*; GitHub: the https URL).
8. **reMarkable (optional).** In the app: open my.remarkable.com/device/browser/connect, type the 8-letter
   code into PaperDesk. The machine downloads [rmapi](https://github.com/ddvk/rmapi) and connects itself.
9. **Topics.** Add what you want to follow (a name and a few search words). The machine checks arXiv and
   OpenAlex once a day and puts new papers in your Inbox.

Running it again updates PaperDesk and keeps your data. `paperdesk status` shows what the desk is doing;
`./install.sh --uninstall` removes the service (your library stays). Settings live in
`~/.config/paperdesk/config.toml`, for example projects for *Include in…*:
```toml
[[targets]]
id = "thesis"
label = "My thesis"
bib = "/home/you/thesis/references.bib"
```
Optional API keys (`OPENALEX_MAILTO`, `ELSEVIER_API_KEY` for Scopus search) go into
`~/.config/paperdesk/secrets.env`.

## What the app does
- **Inbox:** new papers from your topics, each with the topic that found it. *Add to literature*,
  *Include in* a project, *Send to reMarkable*, Later, Dismiss.
- **Search:** your library (metadata + full text of the PDFs) and the wider literature (OpenAlex, arXiv,
  Scopus) with the same buttons.
- **Paper:** your LaTeX papers. Edit any `.tex`/`.bib` file, comment, build the PDF, see all comments in one
  list. Saving commits the file and pushes it (Overleaf git bridge or GitHub); if someone changed the file in
  the meantime, the app shows it instead of overwriting. The machine edits its own clone of each paper.
  Comments are LaTeX, so they show in the PDF and in Overleaf too:
  `\pdmark{marked text}{comment}` (select text, tap *Comment*; tap the marked text to read it) and
  `\pdnote{comment}` (a comment at the cursor). The first comment adds `pdnotes.tex` to the paper;
  `\pdnotesfalse` in it hides all comments for a clean PDF. reMarkable marks (`\rmnote`, `\rmhl`, `\rmdel`)
  show the same way. The tablet browser and annotated PDFs live here too.
- **Chat:** an agent (LangChain DeepAgents, your model provider) that reads and changes your GitHub repos in a
  Docker sandbox, tests, and opens draft pull requests. The repos you attach to a chat are its whole scope.
- **Activity + notifications:** finished jobs and new papers arrive as Android notifications.

The design is Bauhaus: three colours, three forms, and the form always says what something means
(blue circle = in your library / done, red square = new / stop / failed, yellow triangle = on its way).

## Two profiles
`profile = "portable"` (the default, set up by `install.sh`) uses only this repository.
`profile = "box"` is the author's research machine: there the inbox comes from a weekly scout loop,
intake runs Claude Code skills, and P0–P3 are paper drafts sent to and pulled from the reMarkable.
## Layout
- `API.md`: the contract between app and server.
- `install.sh`: the installer above. `server/pdlib.py`: the portable profile (config, library, intake, topic
  watch, rmapi, pairing). `server/tests/`: `python3 -m unittest discover -s server/tests`.
- `server/paperdesk.py`: stdlib HTTP server on `127.0.0.1:8799`, published on the tailnet only as
  `https://<box>.<tailnet>.ts.net:8447` (`tailscale serve`, mapped by `~/.claude/loops/review_server_start.sh`,
  which also keeps `server/start.sh` running). State in `~/.local/state/paperdesk/`, bearer token in
  `~/.config/paperdesk/token` (never committed). Jobs run one at a time; LLM jobs run headless `claude -p`
  (corpus intake: Sonnet 5.5/medium; handwriting → LaTeX: Opus 5.5/high).
- `android/`: the Kotlin/Compose app (see `android/README.md`). The APK is built on the box, served at
  `/download/paperdesk.apk` on the tailnet and never published.

## Security
Reachable from your tailnet only, plus a bearer token (`~/.config/paperdesk/token`, mode 600). Pairing codes
are 8 characters, valid 30 minutes, and ten wrong guesses cancel every open code. Every write goes through a
fixed job type; the app cannot send free-form commands or prompts. The pairing page and link contain the
token: do not share them.

## AI disclaimer
This repository was written with substantial help from AI coding assistants (Claude Code, Anthropic),
under the direction and review of the author. Treat it like any other code: review before you rely on it.

## License
Apache-2.0, see `LICENSE` and `NOTICE`.
