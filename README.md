# Music Player

A simple Android MP3 music player (Kotlin, Media3).

## Features

- Plays local MP3/audio files from device storage (MediaStore, with runtime permission handling)
- Library view: songs with title / artist / album, search
- Playlists: create, add/remove songs (stored locally as JSON)
- Full player: play/pause, next/previous, seek bar, shuffle, repeat (off / all / one)
- Background playback with a media notification (play / pause / next / previous)
- Portrait phone UI, no fixed sizes

## Status

The current UI is a **functional placeholder**. The final UI design will be
applied after the user approves a prototype design.

## Build

```bash
./gradlew assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds the debug
APK on every push to `main` and publishes it to the `v1.0.0` release.
