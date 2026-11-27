# NovaHop Rejoin (Android)

Auto-rejoiner for Bee Swarm Simulator (Roblox) clones on rooted Android emulators/phones, for NovaHop script
users. Owner: Simon (saucierauto1). Discord: https://discord.gg/brfJ2ZtegE. Windows version: saucierauto1/Windows-Rejoiner.
It replaced an older Termux script (menu: game, private server link, live status screen).

## How to work with the owner
- Do exactly what is asked, minimum change for "fix". No refactoring, renaming or "improvements" that weren't asked for.
- No extra error handling, logging, comments or tests unless asked. No new libraries unless asked.
- Short answers. Owner is not a programmer: explain in plain words, no jargon.
- The APK is NOT signed with a fixed key (owner chose that), so users uninstall the old app before installing an update.

## Build / release
- Pushing to `main` runs `.github/workflows/build.yml` (Gradle 8.7, Java 17, `gradle assembleRelease`)
  -> `NovaHopRejoin.apk` released as tag `v1.0.<run number>`. Put `[skip ci]` in commit messages that don't change the app.
- No Android SDK in the Linux sandbox: code can't be compiled there, so be careful with Kotlin and check imports.
  `Settings` clashes with android.provider.Settings - use the app's own `Settings` object, fully qualify Android's.
- `app/main.py` and `app/core.py` in this repo are Windows app files uploaded here by mistake; they don't affect the build.

## Files (app/src/main/java/com/novahop/rejoin/)
- `WatcherService.kt` - foreground service: finds Roblox clones (Mapping.kt), launches them one by one with
  `launchGap` between, reads NovaHop/hb heartbeats, restarts on errors (teleport/full-server errors wait hopWait,
  NovaHop retries those itself), Discord live status message (RAM per app, system stats, screenshot) + events,
  START opens every clone (openAll), clears Roblox cache every cacheHours.
- `Settings.kt` - SharedPreferences: place, private server (linkCode=/share=), launchGap, webhook, ping, statusEvery,
  shots, events, errorWait, hopWait, autoBoot, openAll, cacheHours.
- `MainActivity.kt` - UI + "Discord & settings" dialog. `BootReceiver.kt` - starts on boot if autoBoot or it was running.
- `Shell.kt` (root commands: am force-stop, am start roblox:// links, pidof, dumpsys, screencap), `Discord.kt`
  (multipart webhook via HttpURLConnection; needs the INTERNET permission in the manifest), `WatcherState.kt`.

## Heartbeat
Executor autoexec writes NovaHop/hb/<UserId>.txt `time|ok|msg|name` every 3 s
(gist saucierauto1/4e760d58e39c190bb537f599d9d788a8, NovaHop_Heartbeat.lua; it stops old loops across hops).
