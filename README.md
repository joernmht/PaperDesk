# PaperDesk

A paper desk in your pocket: an Android app plus a small server on a Linux machine you own. It finds new
papers on the topics you follow, keeps your library (one BibTeX file + PDFs, readable by Zotero, JabRef and
LaTeX), copies the entries you cite into your projects' `.bib` files and, if you have one, sends PDFs to
your reMarkable tablet.

<p align="center"><img src="docs/icon.svg" width="96" alt="PaperDesk icon: blue circle, red sheet, yellow folded corner"></p>

## Setup
**Start with the app: it walks you through every step.** You need an Android phone (13 or newer), a
Linux machine that stays on (a small cloud VM is enough: Ubuntu 24.04, 1 vCPU, 1–2 GB RAM), and a free
[Tailscale](https://tailscale.com) account. A reMarkable is optional.

1. **Install the app** from the [latest release](https://github.com/joernmht/PaperDesk/releases/latest)
   (`paperdesk.apk`). Android asks whether your browser may install apps; allow it once.
2. **Tailscale on the phone.** Install Tailscale from the Play Store and sign in. It is a private network
   between your phone and your machine; PaperDesk is never on the public internet.
3. **Install PaperDesk on the machine.** Log in with SSH and run
   ```bash
   curl -fsSL https://raw.githubusercontent.com/joernmht/PaperDesk/main/install.sh | bash
   ```
   It installs Tailscale on the machine and prints a sign-in link and QR code: sign in with the **same**
   Tailscale account as on the phone. If Tailscale asks you to enable HTTPS for your tailnet, open its link
   and enable it. At the end you see a QR code, an address and a pairing code.
4. **Connect.** In the app tap *Scan the QR code* (or type the address and code). Codes are valid for 30
   minutes; `paperdesk pair` on the machine makes a new one.
5. **reMarkable (optional).** In the app: open my.remarkable.com/device/browser/connect, type the 8-letter
   code into PaperDesk. The machine downloads [rmapi](https://github.com/ddvk/rmapi) and connects itself.
6. **Topics.** Add what you want to follow (a name and a few search words). The machine checks arXiv and
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
- **reMarkable:** browse the tablet, fetch annotated PDFs to the phone.
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
