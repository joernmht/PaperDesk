# PaperDesk Android app

Native Kotlin + Jetpack Compose (Material 3) client for a PaperDesk server. It talks only to the server it was
paired with; the API is specified in `../API.md`.

Tabs: **Inbox** (new papers with *Include in…* / *Add to literature* / *Send to reMarkable* / *Dismiss*, plus
*Topics* to follow), **Search** (library + web, same buttons), **reMarkable** (drafts, tablet browser, annotated
PDFs), **Chat** (the agent service), **Activity** (jobs, decisions, update feed). Tabs and actions the server does
not offer are hidden (see *Feature gating*).

## Design: Bauhaus (`ui/Bauhaus.kt`)
Three colours, three forms, no ornament. Fixed palette (no dynamic colour): red `#D8232A`, yellow `#F2C200`,
blue `#1E4FA3`, ink `#141414`, paper `#F5F1E8`; in dark mode the ground is ink, text is paper, blue and red are
lightened. All corners are square, nothing casts a shadow, cards and buttons carry a 1.5 dp ink border, chrome is
separated from content by a 2 dp ink rule. Titles and labels are set in **Jost** (variable font, SIL OFL 1.1,
`res/font/jost.ttf`, licence in `licenses/Jost-OFL.txt`); body text stays the system sans so abstracts read easily.

The forms carry meaning by shape, so colour is never the only signal:

| Form | Means | Used for |
|---|---|---|
| ● blue circle | keep / known | in the library, added, done, *Include in…*, *Add to literature*, *Approve*, *Connect* |
| ■ red square | new / stop | new inbox item, dismiss, delete, failed, every error message |
| ▲ yellow triangle | forward / in motion | *Send to reMarkable*, pending and running jobs, later, update available, *Next* |

`Form(kind, size)` draws one (always with a thin outline in the content colour, so yellow stays legible on
paper); `statusForm()` / `actionForm()` map states and decisions to forms. Use `BCard`, `BFilledButton`,
`BOutlinedButton`, `BTag`, `ErrorLine`, `InkRule` instead of the stock Material components, and keep spacing on
the 8 dp grid.

## Setup wizard (`ui/Setup.kt`)
A new user is handed only the APK. While no server is configured the app shows a wizard instead of the tabs; the
current step is kept in Prefs (`setup_step`) and Back walks backwards. The step indicator uses the forms:
● done, ▲ current, □ not reached.

0. **Welcome**: what it is, what you need (phone, an always-on Linux machine, a free Tailscale account).
1. **Tailscale on this phone**: Play Store link; live check for an active VPN network (`TRANSPORT_VPN`).
2. **Your machine**: pointer to the guide (`https://github.com/joernmht/PaperDesk#setup`).
3. **Install**: the one-line installer command with Copy / Share.
4. **Connect**: scan the installer's QR code (Google code scanner, no camera permission), or type machine
   address + pairing code, or paste a pairing link. The pairing is saved, confirmed with `/api/health`, and undone
   if that check fails.
5. **reMarkable** (only if the server reports `features.rm_connect`): one-time code from
   `my.remarkable.com/device/browser/connect` → `POST /api/rm/connect`, job polled until done.
6. **What to follow** (only if `features.topics`): add topics; the first search starts when you leave the step.
7. **Done**: enters the tabs, starts polling, asks for the notification permission.

Until the wizard is finished the app is silent: no polling, no crash reports, no start-up ping.
Settings (gear) has *Run setup again* (reopens step 4; the current desk stays until a new pairing works),
*reMarkable: connect* (step 5 on its own, when available), *Paste pairing link* and *Disconnect*.

### Pairing formats
- **QR code / manual entry**: QR text `https://<host>[:port]/pair/<CODE>`; by hand a machine address
  (`myvm.tail1234.ts.net`, optional `https://`, port defaults to `8447`, https only) and the code `XXXX-XXXX`
  (case-insensitive, dash optional). The app calls `GET <origin>/api/pair?code=<CODE>` without auth and expects
  `{"url","token","name","agent"}`; `403` means the code is wrong or expired.
- **Pairing link** (deep link, also accepted as QR text or pasted):
  `paperdesk://pair?url=<server base URL>&token=<token>[&agent=<agent base URL>][&name=<desk name>]`.
  A link opened from outside the app always asks "Connect to …?" first, because any web page can open one.

Prefs (`SharedPreferences "paperdesk"`): `url`, `token`, `agent_url`, `name`, `server_info` (last health JSON),
`last_seq`, `setup_step`, `setup_solo`.

## Feature gating
`GET /api/health` → `features {remarkable, drafts, transfer, topics, agent, rm_connect}` and
`targets [{id,label}]`. `remarkable=false` hides the reMarkable tab and every *Send to reMarkable*; `drafts=false`
hides the drafts section; `transfer=false` hides *Transfer marks into LaTeX*; no targets turns *Include in…* into
a plain *Add to literature*; `agent=false` or no agent URL hides Chat; `topics` shows *Topics* in the inbox.
A server that sends no `features` (older than 0.2) gets everything it always had, the classic P0–P3 targets, and
no topics.

## Build (on the box)
```bash
source /vol/home/android/env.sh          # JDK 21, SDK 36, gradle cache on /vol
cd ~/PaperDesk/android
./gradlew --no-daemon assembleRelease                          # private build: URL + token baked in
./gradlew --no-daemon assembleRelease -Ppaperdesk.public=true  # public build: nothing baked in, starts in the wizard
```
Both land in `app/build/outputs/apk/release/app-release.apk` (the second build overwrites the first), R8-shrunk
and signed with the debug key. Bump `versionCode`/`versionName` in `app/build.gradle.kts` for every APK you hand
out; the app shows an "Update available" banner when the server's `apk_version` is higher, linking to
`/download/paperdesk.apk`.

- **Private build**: `android/local.properties` (gitignored, never committed) holds
  ```
  sdk.dir=/vol/home/android/sdk
  paperdesk.url=https://<your tailnet host>:8447
  paperdesk.token=<contents of ~/.config/paperdesk/token>
  paperdesk.agent=<optional agent base URL>
  ```
  which become `BuildConfig.DEFAULT_URL` / `DEFAULT_TOKEN` / `DEFAULT_AGENT_URL`. That APK contains the token:
  **never publish it**. It is paired from the first start and never shows the wizard.
- **Public build** (`-Ppaperdesk.public=true`): ignores those three properties; the APK holds no URL and no
  token and is the one to hand to other people. Check before publishing:
  `unzip -p app-release.apk 'classes*.dex' | strings | grep -c ts.net` must print 0.

## Notifications
No Firebase. WorkManager polls `/api/events?since=<last seq>` every 15 min (network required), plus a
short burst of one-off polls at ~1/3/6/10 min after you start a job. The first poll after pairing only
records the current sequence number, so history is not replayed. Tapping a notification opens
*Activity* (or *Inbox* for `scout` / `watch` events). Android may delay periodic work in Doze; exempting
PaperDesk from battery optimisation makes it punctual.

## Code map
`Models.kt` (API types, `ServerInfo`, `Topic`) · `Api.kt` (`Prefs`, `Pairing`, `PairClient`, HttpURLConnection +
org.json clients) · `PollWorker.kt` (polling + notifications) · `ChatWatch.kt` ("Chat answered") ·
`MainActivity.kt` (app frame: top bar, bottom bar, deep link, settings, update banner) · `ui/Bauhaus.kt` (theme,
forms, flat components) · `ui/Setup.kt` (wizard) · `ui/PaperScreens.kt` (paper card, Inbox, Topics, Search) ·
`ui/RemarkableScreens.kt` (reMarkable, Activity) · `ui/ChatScreen.kt` (agent chat).
