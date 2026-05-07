# Android 4.0.4 Legacy Client Implementation Plan

**Goal:** Add a full native Android client for Android 4.0.4 / API 15 without weakening the existing Expo/React Native app.

**Architecture:** Keep the current app as the modern Android 5+ client. Add `legacy-android-ics/` as a separate Java Android application that reuses the same HTTP API contract, but implements UI, storage, playback, remote input, and release packaging natively for old TV firmware.

**Tech Stack:** Java, Android SDK min API 15, compile SDK 25, Android Gradle Plugin 2.3.3, Gradle 3.3, OkHttp 3.12.13, Gson 2.8.9, Picasso 2.5.2, system `MediaPlayer` + `SurfaceView`.

---

## Scope

The legacy app must include these complete product flows:

- First-run setup and API base URL configuration.
- Server config and login flow.
- Home recommendations and recent play records.
- Search, search history, and remote text input.
- Detail page with multiple playback sources, episode list, source filtering, and favorites.
- Fullscreen player with remote control, progress save, episode switching, fallback source selection, and basic intro/outro marks.
- Live TV from M3U URL with grouped channel list.
- Favorites page and play history page.
- Settings page for API URL, live M3U URL, video source filtering, remote input, and local data cleanup.
- Android 4.0.4 compatible APK build and GitHub Actions release artifact.

## Non-Goals

- Do not migrate the existing Expo/RN app.
- Do not use React Native, Expo modules, AndroidX, Compose, Kotlin, or modern Media3.
- Do not promise universal playback for every HLS stream. Unsupported encodings must fail gracefully and attempt another source.

## File Structure

Create:

```text
legacy-android-ics/
  settings.gradle
  build.gradle
  gradle/wrapper/gradle-wrapper.properties
  app/build.gradle
  app/proguard-rules.pro
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/oriontv/legacy/App.java
  app/src/main/java/com/oriontv/legacy/MainActivity.java
  app/src/main/java/com/oriontv/legacy/SearchActivity.java
  app/src/main/java/com/oriontv/legacy/DetailActivity.java
  app/src/main/java/com/oriontv/legacy/PlayerActivity.java
  app/src/main/java/com/oriontv/legacy/LiveActivity.java
  app/src/main/java/com/oriontv/legacy/FavoritesActivity.java
  app/src/main/java/com/oriontv/legacy/SettingsActivity.java
  app/src/main/java/com/oriontv/legacy/LoginActivity.java
  app/src/main/java/com/oriontv/legacy/api/ApiCallback.java
  app/src/main/java/com/oriontv/legacy/api/ApiException.java
  app/src/main/java/com/oriontv/legacy/api/CookieStore.java
  app/src/main/java/com/oriontv/legacy/api/OrionApiClient.java
  app/src/main/java/com/oriontv/legacy/api/models/*.java
  app/src/main/java/com/oriontv/legacy/data/LocalRepository.java
  app/src/main/java/com/oriontv/legacy/data/PreferencesStore.java
  app/src/main/java/com/oriontv/legacy/media/LegacyPlayerController.java
  app/src/main/java/com/oriontv/legacy/media/M3uParser.java
  app/src/main/java/com/oriontv/legacy/media/M3u8Inspector.java
  app/src/main/java/com/oriontv/legacy/media/PlaybackSourceSelector.java
  app/src/main/java/com/oriontv/legacy/remote/LegacyHttpInputServer.java
  app/src/main/java/com/oriontv/legacy/ui/*.java
  app/src/main/res/drawable/*.xml
  app/src/main/res/layout/*.xml
  app/src/main/res/mipmap-hdpi/ic_launcher.png
  app/src/main/res/values/colors.xml
  app/src/main/res/values/strings.xml
  app/src/main/res/values/styles.xml
```

Modify:

```text
.github/workflows/android-ics.yml
.gitignore
README.md
```

Only update `README.md` if we need a user-facing build note after implementation. Otherwise leave it untouched.

## API Contract

Implement `OrionApiClient` with the same endpoints as `services/api.ts`:

- `POST /api/login`
- `POST /api/logout`
- `GET /api/server-config`
- `GET /api/favorites`
- `POST /api/favorites`
- `DELETE /api/favorites`
- `GET /api/playrecords`
- `POST /api/playrecords`
- `DELETE /api/playrecords`
- `GET /api/searchhistory`
- `POST /api/searchhistory`
- `DELETE /api/searchhistory`
- `GET /api/image-proxy?url=...`
- `GET /api/douban?type=...&tag=...&pageSize=...&pageStart=...`
- `GET /api/search?q=...`
- `GET /api/search/one?q=...&resourceId=...`
- `GET /api/search/resources`
- `GET /api/detail?source=...&id=...`

All requests must:

- Reject when API base URL is empty.
- Attach saved cookies when present.
- Save `Set-Cookie` on login.
- Report 401 as login-required.
- Use timeouts: connect 10s, read 20s, write 20s.
- Normalize API base URL by trimming trailing `/` and adding `http://` for IP or host:port input.

## Data Model

Create Java models matching the TypeScript interfaces:

- `ServerConfig`
- `DoubanItem`
- `DoubanResponse`
- `ApiSite`
- `SearchResult`
- `VideoDetail`
- `Favorite`
- `PlayRecord`
- `PlayerSettings`
- `AppSettings`
- `Channel`

Local storage keys must match current app where practical:

- `mytv_settings`
- `mytv_player_settings`
- `mytv_favorites`
- `mytv_play_records`
- `mytv_search_history`
- `mytv_login_credentials`
- `authCookies`

## Tasks

### Task 1: Native Project Skeleton

Create the Gradle project and a launchable empty app.

Acceptance:

- `legacy-android-ics/gradlew assembleDebug` creates an APK.
- Manifest has `minSdkVersion 15`.
- App launches into `MainActivity`.
- Uses no AndroidX classes.

### Task 2: Shared Base UI and Focus System

Create a TV-first UI foundation:

- Dark theme.
- Focusable buttons and poster cards.
- Focus ring drawable.
- Grid adapter for old `GridView`.
- Loading, empty, and error views.
- Key handling for DPAD, ENTER, BACK, MENU, MEDIA_PLAY_PAUSE, MEDIA_FAST_FORWARD, MEDIA_REWIND.

Acceptance:

- Remote direction keys move focus predictably.
- Focused item is visually obvious on old low-resolution TVs.

### Task 3: Preferences and Local Repository

Implement settings, favorites, play records, player settings, search history, and login cookies.

Acceptance:

- Data survives app restart.
- JSON corruption falls back safely to empty defaults.
- Storage type switches between local and remote based on `ServerConfig.StorageType`.

### Task 4: API Client

Implement all API endpoints and callbacks.

Acceptance:

- Empty API URL shows a settings-required error.
- 401 opens login.
- Server config updates storage mode.
- Search, detail, favorites, and play records match the current app contract.

### Task 5: Settings and Login

Implement `SettingsActivity` and `LoginActivity`.

Settings fields:

- API base URL.
- M3U URL.
- Remote input enabled.
- Enable all video sources.
- Per-source enable/disable list after resources are fetched.
- Clear local favorites/history/settings.

Acceptance:

- API URL can be entered with or without scheme.
- IP/host:port defaults to HTTP.
- Domain defaults to HTTPS.
- Login supports anonymous localstorage mode and username/password mode.

### Task 6: Home

Implement `MainActivity`.

Sections:

- Recent play records.
- Hot TV.
- TV category groups.
- Movie category groups.
- Variety.
- Douban Top250.

Acceptance:

- Recommendation pages load through `/api/douban`.
- Recent records load from local or server storage.
- Pressing a card opens detail.
- Settings, search, live, favorites, and history are reachable by remote.

### Task 7: Search and Remote Input

Implement search page and `LegacyHttpInputServer`.

Remote input:

- Serve a simple page at `http://device-ip:12346/`.
- `POST /handshake` marks connection alive.
- `POST /message` injects text into the active search field and triggers search when target page is search.

Acceptance:

- Search calls `/api/search`.
- Search history stores latest 20 unique keywords.
- Remote browser input works from phone on same LAN.

### Task 8: Detail and Source Loading

Implement detail page.

Flow:

- For direct result, show basic detail immediately.
- Fetch resources.
- If source filter is enabled, only search enabled sources.
- Call `/api/search/one` per source.
- Keep only exact title matches.
- Inspect first episode M3U8 for highest resolution label.
- Allow manual source switch.

Acceptance:

- First available source can start playback before all sources finish.
- Failed source calls do not fail the entire page.
- Favorite toggling works.

### Task 9: Player

Implement `PlayerActivity` and `LegacyPlayerController`.

Capabilities:

- Fullscreen `SurfaceView`.
- Prepare/play/pause/resume/stop/release lifecycle.
- Seek left/right by 15 seconds.
- Episode previous/next.
- Source fallback on error.
- Progress bar overlay.
- Control overlay auto-hide.
- Save play record every 10 seconds.
- Save final record on pause/stop.

Acceptance:

- MP4 plays.
- Supported M3U8 plays.
- Unsupported stream shows error and attempts fallback source.
- Back exits player without leaking MediaPlayer.

### Task 10: Live TV

Implement `LiveActivity` and `M3uParser`.

Capabilities:

- Fetch M3U URL from settings.
- Parse `#EXTINF`, `tvg-logo`, `group-title`.
- Show grouped channel list.
- Left/right changes channel.
- Down opens channel list.

Acceptance:

- Empty M3U URL shows settings prompt.
- Invalid M3U shows recoverable error.
- Channel selection starts playback.

### Task 11: Favorites and History

Implement favorites and recent records pages.

Acceptance:

- Favorites are sorted by saved time when available.
- Play records show title, source, episode, progress.
- Entries open detail/player with the correct source and id.
- Delete one and clear all both work.

### Task 12: Release Build

Add release signing placeholders and CI build.

Acceptance:

- Local debug build works.
- CI workflow builds `orionTV.<version>-android-ics.apk`.
- The legacy APK package name is distinct if needed, for example `com.oriontv.legacy`, to avoid replacing the modern app.

## Verification Plan

Automated:

- Run `legacy-android-ics/gradlew assembleDebug`.
- Run `legacy-android-ics/gradlew assembleRelease` when signing config is available.
- Run Java unit tests for URL normalization, M3U parser, M3U8 inspector, and version comparison if test setup is available.

Manual on Android 4.0.4 / API 15 TV:

- Install APK.
- Launch app.
- Configure API URL.
- Fetch server config.
- Login.
- Load home recommendations.
- Search keyword.
- Open detail.
- Toggle favorite.
- Play MP4.
- Play M3U8.
- Trigger unsupported stream fallback.
- Save and reopen play record.
- Load M3U live channels.
- Use remote web input.
- Run 30-minute playback soak test.

## Risk Handling

- HTTPS/TLS failure: prefer HTTP API for legacy devices or route through server proxy.
- M3U8 incompatibility: try alternate source, then show unsupported-stream message.
- Low memory: cap image size, keep small poster cache, avoid large bitmaps.
- Vendor key mismatch: log unknown key codes and add mapping table.
- Install/update failure: expose downloaded APK path and show manual install instructions.

## Execution Order

1. Create skeleton and build system.
2. Add models, storage, API client.
3. Add shared UI/focus components.
4. Add settings/login.
5. Add home/search/detail.
6. Add player and fallback.
7. Add live TV.
8. Add favorites/history.
9. Add remote input.
10. Add CI release workflow.
11. Build and verify.
