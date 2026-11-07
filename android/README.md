# PaperDesk Android app

Native Kotlin + Jetpack Compose (Material 3, dynamic colour) client for the PaperDesk server on the box.
Talks only to the tailnet URL; the API is specified in `../API.md`.

Tabs: **Inbox** (scout papers with *Add to literature* / *Include in paper* / *Send to reMarkable*),
**Search** (library + web, same buttons), **reMarkable** (drafts P0–P3, tablet browser, annotated PDFs),
**Activity** (jobs + update feed).

## Build (on the box)
```bash
source /vol/home/android/env.sh          # JDK 21, SDK 36, gradle cache on /vol
cd ~/PaperDesk/android
./gradlew --no-daemon assembleRelease   # R8-shrunk (~2 MB), signed with the debug key
cp app/build/outputs/apk/release/app-release.apk ../dist/paperdesk.apk
echo <versionCode> > ../dist/apk_version.txt
```
Bump `versionCode`/`versionName` in `app/build.gradle.kts` for every APK you hand out; the app shows
an "Update available" banner when the server's `apk_version` is higher, linking to `/download/paperdesk.apk`.

## Token + URL
`android/local.properties` (gitignored, never committed) holds
```
sdk.dir=/vol/home/android/sdk
paperdesk.url=https://paper1.tailbf37ab.ts.net:8447
paperdesk.token=<contents of ~/.config/paperdesk/token>
```
These become `BuildConfig.DEFAULT_URL` / `DEFAULT_TOKEN`. The APK therefore contains the token:
**never publish it** (tailnet download only). Settings (gear icon) can override both on the phone.

## Notifications
No Firebase. WorkManager polls `/api/events?since=<last seq>` every 15 min (network required), plus a
short burst of one-off polls at ~1/3/6/10 min after you start a job. The first poll after install only
records the current sequence number, so history is not replayed. Tapping a notification opens
*Activity* (or *Inbox* for `scout` events). Android may delay periodic work in Doze; exempting
PaperDesk from battery optimisation makes it punctual.

## Code map
`Models.kt` (API types) · `Api.kt` (HttpURLConnection + org.json, Prefs) · `PollWorker.kt`
(polling + notifications) · `MainActivity.kt` (theme, nav, settings, update banner) ·
`ui/PaperScreens.kt` (paper card, Inbox, Search) · `ui/RemarkableScreens.kt` (reMarkable, Activity).
