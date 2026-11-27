# NovaHop Rejoin (Android)

Auto-rejoiner for Bee Swarm Simulator (Roblox) clones on rooted Android emulators/phones, for NovaHop script
users. Owner: Simon (saucierauto1). Discord: https://discord.gg/brfJ2ZtegE. Windows version: saucierauto1/Windows-Rejoiner.
It replaced an older Termux script (menu: game, private server link, live status screen).

## How to work with the owner
- Do exactly what is asked, minimum change for "fix". No refactoring, renaming or "improvements" that weren't asked for.
- No extra error handling, logging, comments or tests unless asked. No new libraries unless asked.
- Never put Co-Authored-By, Claude-Session or "Generated with Claude" lines in commits or pull requests:
  the owner doesn't want Claude listed as a contributor.
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
  `launchGap` between, reads NovaHop/hb heartbeats (a half-written file keeps the last good line; an account leaves
  "loading" as soon as a fresh ok heartbeat newer than its launch arrives), restarts on errors
  (teleport/full-server errors incl. codes 769-773 wait hopWait, and the timer starts again when NovaHop moves to a
  different hop error, but one error spell never lasts more than 5 x hopWait), RAM limit (3 quick tries, then an alert and once per 15 min), FPS cap via
  `novahop_fps.lua` in every autoexec folder (`applyFps`; capped 5 s after the game loaded, uncapped to 240 while
  teleporting so hops load at full speed; one line with no single quotes because it's written with `echo '...'`), each clone's own private server (`Settings.link(uid)`),
  closes a clone running twice (every 60 s, keeps the oldest), remembers the last restart reason per account,
  Discord live status message (RAM per app + total, last restart, system stats, screenshot) + events with the game
  name, START opens every clone (openAll), clears Roblox cache every cacheHours. Each pass runs in `tick()` inside
  a try/catch, so one bad pass never stops the watcher.
- `Settings.kt` - SharedPreferences: place, private server (linkCode=/share=), launchGap, webhook, ping, statusEvery,
  shots, events, errorWait, hopWait, autoBoot, openAll, cacheHours, ramLimit, fpsCap, and per account
  `place_<uid>` / `ps_<uid>` / `psRaw_<uid>` (empty place = uses the main one).
- `MainActivity.kt` - UI + "Discord & settings" dialog; tapping an account offers "Pick its Roblox app" or "Its own
  game / private server"; Start asks for the battery-optimisation exemption. `BootReceiver.kt` - starts on boot if
  autoBoot or it was running.
- `Shell.kt` (root commands: am force-stop, am start roblox:// links, pidof, dumpsys, screencap; output is read on
  the side and a command is killed after 30 s, so nothing can hang the watcher), `Discord.kt`
  (multipart webhook via HttpURLConnection; needs the INTERNET permission in the manifest), `WatcherState.kt`.

## Untested on a real emulator
Everything since the Discord update was only checked by reading the Kotlin carefully and by the GitHub build
(it compiles; to check a build without releasing, push to a temporary branch whose build.yml has no release step):
RAM limit, FPS cap file, per-clone servers, duplicate-clone kill, the battery prompt, the Discord status layout.

## Heartbeat
Executor autoexec writes NovaHop/hb/<UserId>.txt `time|ok|msg|name` every 3 s
(gist saucierauto1/4e760d58e39c190bb537f599d9d788a8, NovaHop_Heartbeat.lua; it stops old loops across hops).
