# Zeno Play

Android MP3 music player (Kotlin, Media3) — dark navy + gold-to-cyan neon identity.

## Features

- Plays local MP3/audio files from device storage (MediaStore, with runtime permission handling)
- Library with search and categories (تونسي / شرقي / أجنبي / مفضلة / من جهازي)
- Playlists grid: create, add/remove songs (stored locally as JSON)
- Now-playing card with rotating disc, live sound waves, seek, shuffle, repeat, volume, favorite
- Full player: play/pause, next/previous, seek bar, shuffle, repeat (off / all / one), volume, "Up next" queue
- Mini player bar above the library
- Background playback with a media notification (play / pause / next / previous)
- Real audio visualizer: animated gradient spectrum bars driven by the actual audio (an in-app Media3 AudioProcessor analyses the real PCM stream with a 1024-sample FFT; the system `android.media.audiofx.Visualizer` is kept only as a backup) — waves animate while playing and fall flat when paused; the visualizer area is always visible (minimum bars + baseline) with an animated fallback if no real data arrives
- Playback fallback: if the custom audio path errors, the app retries with a plain default ExoPlayer
- In-app updates from GitHub: on launch (and on manual check) the app queries the latest release, downloads the APK and installs it itself via PackageInstaller — no uninstall, no manual download
- Portrait phone UI, no fixed sizes

## Status

v1.0.2 — the approved interactive prototype UI is applied natively, rebranded to Zeno Play, playback regression fixed, in-app updates added, release-signed.

IMPORTANT: v1.0.2 is signed with the permanent release key (v1.0.0/v1.0.1 were debug-signed), so uninstall the old app once and install v1.0.2 fresh. After that, updates — including in-app updates — install normally.

## Build

```bash
./gradlew assembleRelease
```

The APK is produced at `app/build/outputs/apk/release/app-release.apk`.

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds the signed release APK on every push to `main` and publishes it to the `v1.0.2` release. The release keystore is decoded at build time from the `RELEASE_KEYSTORE_BASE64` repository secret (with `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`); it is never committed to the repo.
