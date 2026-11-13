# NovaHop Rejoin

Android app that reopens your game when a Roblox app gets stuck (error screen, crash, freeze, ad),
so NovaHop keeps farming. Works with one Delta app or several Delta clones.

## Download

Get `NovaHopRejoin.apk` from the **Releases** page of this repo.

## Setup

1. Turn on root on the device (MuMu: Settings > Other > Root permission, then restart).
2. Install the APK and open it.
3. Tap **Setup** and tap Allow on the root prompt.
4. Optional: tap **Game / private server** to pick another game or paste a private server link.
5. Tap **Start**, then join the game once on every Roblox app.
6. With clones: if an account says NOT MATCHED, tap it and pick its app.

Stop it with the **Stop** button. It starts again by itself after a reboot if it was running.

## Building

Every push to `main` builds the APK with GitHub Actions and publishes it as a release.
