# Zeno Play

Android music player (Kotlin, Media3) — dark navy + gold-to-cyan neon identity.

## Features (v1.0.4 — 41 features)

- **No ads, free forever, privacy respected** — no account, no tracking, no extra permissions. The splash screen (3 seconds, animated logo) shows: «Gratuit pour toujours · Sans publicité · Vie privée respectée — pour toujours».
- **Interface:** the approved French redesign — tabs CHANSONS / ALBUMS / ARTISTES / PLAYLISTS / DOSSIERS / PARAMÈTRES, rotating circular cover, mini player, notification preview. App name on one line. **Trilingual UI** — French (default), Arabic (full RTL), English — chosen in Settings; song/album/artist names stay as in the files and search finds them in all three languages.
- **Long song titles scroll like a news ticker (marquee)** — one line, no truncation, no wrapping — in the library, albums, artists, playlists, queue, full player, mini player, TV now-playing bar and ringtone screen. Short titles stay still. Mixed Arabic/French titles scroll in the right direction (first-strong). (The system notification cannot scroll — Android renders its text itself.)
- Plays local audio files from device storage (MediaStore, with runtime permission handling) — **all formats**: Media3 is the primary engine; libVLC is the automatic fallback for rare formats Media3 cannot read (APE, DSD, WavPack, MPC, tracker, WMA…). TAK is the only known exception (even VLC cannot read it).
- Library with accent-insensitive search, folders, albums, artists, smart playlists (most played / recently added / recently played), listening statistics.
- Playlists: create, add/remove songs (stored locally as JSON); up to 20 independent saved queues, resumable from where they stopped.
- **Full player:** rotating circular cover showing the song's REAL album cover (without a cover, the Zeno logo alone, no square frame), circular **smoke ring** visualizer around the cover that breathes with the bass, **cover beat-pulse** (the disc gently pulses with the music, driven by bass energy from the real FFT; simulated on VLC tracks), play/pause, next/previous, −10s, seek, shuffle, repeat (off / all / one), play-once-then-next, A–B repeat, volume to **200%** with BOOST badge, speed (0.5–2×), sleep timer (minutes or after N songs, with fade-out), synced lyrics (tap a line to seek), reorderable queue with swipe-to-remove.
- **Live waves in 20 shapes** (Settings > "Forme des ondes", default = circular smoke): the first 10 (circular smoke, circular bars, straight bars, wave, pulsing circle, concentric rings, particles, circular bar, circular wave, sun rays) + 10 elegant shapes (golden spiral, aurora, blooming flower, elegant flame, glowing crystal, spinning galaxy, silk ribbons, golden bubbles, beating heart, water ripples) — all driven by the real FFT (simulated on VLC tracks). The chosen shape applies EVERYWHERE at once — the full player AND the mini player on the main screen (the mini player's animation follows the shape; the luxury on/off switch still gates it). The circular smoke is real drifting fog: 48 puffs orbiting the ring at their own speeds + a denser inner mist + 6 slow wisps. The self-test reports the saved shape and whether the waves are real FFT or simulated.
- **Volume to 200%** with a soft-clip limiter against distortion; ReplayGain (track/album); Gapless playback.
- **Real 5-band equalizer** + 8 presets + bass boost, with settings per output (speaker / headphones / Bluetooth). **Stereo balance + Mono mode** in the sound settings (Mono on VLC tracks applies from the next track). **Per-song audio settings** — each song remembers its own EQ and speed. **Reverb** effects (small room / studio / concert hall / none, Media3 only).
- **Batch tag editing** (title/artist/album/genre/year + cover, via jaudiotagger); **album-cover management** (embedded cover first, fix/replace a wrong cover, never one track's cover for a whole album); **ringtone cutter** (cut a segment and set it as ringtone).
- **Background playback** with a media notification (prev / play-pause / next / close), Android Auto (MediaLibraryService), home-screen widget, audio-focus and headset handling, resume of the last song/position/queue, battery-optimization guidance (MIUI/Samsung/Honor).
- **Local backup** (playlists, favorites, queues, settings as JSON) + M3U export/import.
- **Chromecast** — optional, OFF by default (needs network).
- **AMOLED black** mode; **force full screen** (immersive, edge-to-edge) on every activity; **responsive layouts, no fixed sizes**; works on all Android phones, tablets (portrait + landscape), Box Android TV and Smart Android TV (leanback, banner, full D-pad remote navigation).
- **Swipe left/right on the player** to change the song; **lyrics search** (find a line, jump to it); **UI colours from the cover** (the player gradient/glow follows the current song's cover).
- **22 navigation motions** (Settings > "Type de mouvement"): slide, fade, shared element, zoom, morph, flip 3D, cube, blur, stagger, spring, parallax, circular reveal, bottom sheet, shared axis, explode, Ken Burns, golden particles, golden ripple, shimmer, depth, carousel, page curl. Default = the best two together (shared element + morph). Respects the system reduce-motion setting.
- **12 luxury features** (each with its own options in Settings): blurred cover background (light/medium/strong), cover tilt with the gyroscope (sensitivity), haptics on buttons and with the beats (strength), golden particles around the cover (density), animated waves in the mini player (the chosen waveform shape; on/off), karaoke lyrics (line-by-line or word-by-word), annual/monthly "Wrapped" listening summary, music alarm (chosen song + gradual volume rise over 30s), full lock screen (blurred cover + full controls), custom themes (gold/cyan, pink, automatic from cover), double-tap the cover to like (flying heart), Reverb.
- **Contact us on WhatsApp** from Settings (https://wa.me/21624835518).
- **Self-test (temporary module):** a "Self-test" button in Settings runs an automated check of every feature + the current screen (clipped elements, truncated text, dead buttons), shows the full report in the app and sends it automatically to the crash-report server (type "self-test"). Built as a standalone unit — delete `SelfTest.kt`, `SelfTestActivity.kt`, `activity_self_test.xml`, its manifest entry and the one Settings button to remove it.
- **In-app updates from GitHub:** on launch (and on manual check) the app queries the latest release, downloads the APK for the device's ABI and installs it itself via PackageInstaller — no uninstall, no manual download.
- **Error reporting:** crashes AND all other errors (playback failures with format/codec details, library scan, permissions, update check/download/install, local storage) are reported automatically to the Zeno crash-report server, with no user action. Reports send immediately, or on the next launch if offline.
- Playback fallback: if the custom audio path errors, the app retries with a plain default ExoPlayer.

### Privacy

- No account, no tracking, no ads, no analytics SDK. Internet is used ONLY for the in-app updater, error reports, and the optional LRCLIB lyrics fetch / Chromecast (both opt-in, off by default). No listening data ever leaves the device. Permissions: INTERNET, REQUEST_INSTALL_PACKAGES (updater), REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (opt-in, uninterrupted playback), FOREGROUND_SERVICE + FOREGROUND_SERVICE_MEDIA_PLAYBACK, READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE, POST_NOTIFICATIONS. No location, contacts, nearby devices, camera, microphone or Bluetooth permissions. No Dolby/DTS wording anywhere in the app (those formats play silently via the VLC engine).

### Not in v1.0.4

- **Crossfade** (0–15s gradual transition): not implemented — Media3's single-player model needs a dual-player setup that conflicts with the single MediaSession + spectrum-processor chain. Planned for a later version.

## Status

v1.0.4 — the approved interactive prototype UI applied natively, the 41 features above, release-signed with the permanent key.

IMPORTANT: v1.0.2+ are signed with the permanent release key (v1.0.0/v1.0.1 were debug-signed), so installing over v1.0.1 requires uninstalling the old app once. After that, updates — including in-app updates — install normally over the previous version.

## Build

```bash
./gradlew assembleRelease
```

ABI splits produce one APK per architecture at `app/build/outputs/apk/release/ZenoPlay-v<version>-<abi>.apk` (e.g. `ZenoPlay-v1.0.4-arm64-v8a.apk`).

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds the signed release APKs on every push to `main` and publishes them to the `v1.0.4` release. The APK files are renamed in the workflow (version read from `build.gradle.kts`, so future versions are named automatically). The release keystore is decoded at build time from the `RELEASE_KEYSTORE_BASE64` repository secret (with `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`); it is never committed to the repo. A CI guard fails the build if any `android:angle` in a gradient drawable is not a multiple of 45 (a bad angle crashes the app at launch — see v1.0.2).
