# FetchX

_FetchX by Emtrix_

Open-source Android download manager: aria2c for direct files and torrents, yt-dlp + FFmpeg for video pages.
Kotlin, Jetpack Compose, Material 3 (dynamic color, optional pure-black theme), Room, WorkManager.

## Features

- Link detection: magnet / `.torrent` / direct files go to aria2c (JSON-RPC, up to 32 splits, pause/resume);
  video pages and HLS/DASH go to yt-dlp, with a resolution picker and FFmpeg merging.
- Share sheet (`ACTION_SEND`), `magnet:` links and clipboard detection.
- Refresh an expired URL on a paused or failed task and keep the downloaded bytes.
- Custom User-Agent, Referer, extra headers and `cookies.txt` import.
- In-app yt-dlp updater (Settings > Engines).

## Build

CI: push to `main` or tag `vX.Y.Z`. The APK is attached to the workflow run, and to a GitHub Release for tags.
Without signing secrets the APK is signed with the debug key (installable, not for the Play Store).
For a stable release signature add the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Local: `gradle assembleRelease` (Gradle 8.9+, JDK 17), or open in Android Studio. Add a Gradle wrapper with `gradle wrapper`.

## Notes

- Targets `arm64-v8a`, Android 10+ (minSdk 29).
- aria2 allows at most 16 connections per server; higher thread counts only help with mirrors and torrents.
- Licensed GPL-3.0, as the bundled youtubedl-android/yt-dlp/FFmpeg/aria2 components are GPL.
- Use it only for content you have the right to download.

## Credits

- Wordmark font: [Outfit](https://github.com/Outfitio/Outfit-Fonts), SIL Open Font License 1.1 (see `licenses/OFL-Outfit.txt`).
