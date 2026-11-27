# Music Player

A simple Android MP3 music player (Kotlin, Media3).

## Features

- Plays local MP3/audio files from device storage (MediaStore, with runtime permission handling)
- Library view: songs with title / artist / album, search
- Playlists: create, add/remove songs (stored locally as JSON)
- Full player: play/pause, next/previous, seek bar, shuffle, repeat (off / all / one)
- Background playback with a media notification (play / pause / next / previous)
- Real audio visualizer on the player screen: animated spectrum bars driven by
  the actual audio (an in-app Media3 AudioProcessor analyses the real PCM
  stream with a 1024-sample FFT; the system `android.media.audiofx.Visualizer`
  is kept only as a backup) — waves animate while playing and fall flat when
  paused; the visualizer area is always visible (minimum bars + baseline) with
  an animated fallback if no real data arrives
- Portrait phone UI, no fixed sizes

## Status

The current UI is a **functional placeholder**. The final UI design will be
applied after the user approves a prototype design. The approved prototype
design must include the audio visualizer on the player screen.

## Build

```bash
./gradlew assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds the debug
APK on every push to `main` and publishes it to the `v1.0.1` release.
