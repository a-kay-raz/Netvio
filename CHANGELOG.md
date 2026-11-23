# Netvio v1.3.1-Stable

> ⚡ **Urgent Performance & Cache Hotfix:** Fixes the severe poster, content row, and metadata loading slowdown introduced in `v1.3.0-Stable` and makes cold launches and page transitions dramatically faster.

---

## 🛠️ What's Fixed

- **Fixed Live Disk Cache Corruption (`image_cache` & `http_cache`):** Automatic cache maintenance and manual cache clearing previously deleted individual files/folders directly inside Coil's `image_cache/` and OkHttp's `http_cache/` while their `DiskLruCache` journals were open in memory — causing cache misses and full network re-downloads on every page. Both stores are now exclusively managed through Coil 3 and OkHttp's official LRU & clear APIs, and Android/Room system artifacts (`euthopiar_database.lck`, `oat_primary/`) are strictly protected.
- **Eliminated Cold-Start Disk & Sync Contention:** Deferred background cache maintenance (20s post-launch, 12-hour interval with O(1) fast-path skip) and cloud/tracker reconciliation until after Home catalog & posters finish hydrating, so startup I/O and network bandwidth go 100% to loading content.
- **Preserved In-Memory Poster Cache Across Pages:** Removed aggressive `TRIM_MEMORY_RUNNING_LOW` bitmap cache wiping in `EuthopiarApp.onTrimMemory()`, keeping hot posters and backdrops cached in RAM when switching between Home, Details, Search, and Library.
- **Single-Flight Zero-Reflection DNS-over-HTTPS (`DohDns`) & HTTP Cache Freshness Fix:** Added single-flight lookup coalescing (eliminating cold-start DNS thundering herds when dozens of posters & API calls resolve simultaneously), replaced Jackson reflection parsing with zero-reflection `kotlinx.serialization.json` (100% R8-safe), parallelized host prefetching at boot, and stripped upstream CloudFront `Age` / `Expires` headers in `ForceCacheInterceptor` so newly cached TMDB responses are genuinely fresh for 30 minutes instead of being treated as expired on arrival.
- **Faster Home First-Paint:** Initial Home load now fetches core feeds + first 6 curated discovery rails in parallel with Stremio catalogs (bounded by a 5s startup ceiling so a slow third-party addon cannot stall launch), loading remaining rails smoothly as you scroll.

---

## 📦 Build Info

- **Version:** `1.3.1-Stable` (`versionCode 14`)
- **Optimization:** R8 full code minification + resource shrinking enabled
- **Upgrade Path:** Direct in-place OTA/APK upgrade over `v1.3.0-Stable` and earlier versions (same signing key, zero data loss)
---

# Netvio v1.3.0-Stable

> 📺 **Euthopiar Android TV is now LIVE!** Pair your phone with the new 10-foot Android TV edition at **[TheARCreactorGUY/EuthopiarTV](https://github.com/TheARCreactorGUY/EuthopiarTV)** for instant playback handoff, live remote control, phone keyboard typing, and 1-tap addon/Debrid sync.

---

## ✨ New Features

- **📺 Euthopiar TV Companion & Smart Hybrid Playback Handoff:**
  - Auto-discover **Euthopiar Android TV** ([TheARCreactorGUY/EuthopiarTV](https://github.com/TheARCreactorGUY/EuthopiarTV)) over Wi-Fi via `_euthopiar._tcp.` mDNS or pair manually via IP + 6-digit PIN.
  - **"Play on TV"** directly from the Video Player Cast sheet or the Media Details action bar — hands off the active stream URL, HTTP headers, subtitle track, playback timestamp, and canonical media metadata while automatically stripping phone-local loopback streams so the TV resolves natively.
  - **Live TV Remote & Phone Keyboard:** Control playback (`-10s`, `Play/Pause`, `+10s`, `Prev/Next Episode`, `Stop`, `Volume`), navigate with a tactile D-pad, type into TV search/input fields from your phone keyboard, and push your Repositories, Stremio Addons, and Debrid API keys to your TV in one tap.
- **📡 Direct DLNA / UPnP Smart TV Casting & Android 11+ External Players:**
  - Cast direct HTTP/HLS/DASH streams to any standard DLNA / UPnP `AVTransport` Smart TV or media renderer on your local network.
  - Added Android 11+ package visibility queries so installed external players (**VLC**, **MX Player**, **Just Player**, **MPV**, **Nova Video Player**) are detected reliably.
- **☁️ Google Cloud Sync Tombstone & Server-Watermark Overhaul:**
  - Soft-delete tombstones (`is_deleted = true`, `client_updated_at_ms`) across **Watchlist**, **Continue Watching / Playback History**, **Playlists**, **Profiles**, and **Addons** so deletions propagate cleanly across all devices without resurrecting on pull.
  - Server-authoritative pull watermarks (`max(updated_at)`) eliminate device clock-skew sync misses, paired with atomic `sync_user_playlists_v2` RPC execution.
- **🔄 Trakt.tv Out of Maintenance & 3-Way Tracker Sync Hardening:**
  - Restored full **Trakt.tv** PKCE OAuth 2.0 authentication with automatic `401` refresh-token rotation and two-way Watchlist + Episode/Movie History sync.
  - Redesigned **Trakt.tv** & **Simkl** integration cards and connection sheets with monochrome glass surfaces, live status pills, and tactile spring physics.
  - Hardened 3-way sync overlap between **Google Cloud**, **Trakt.tv**, and **Simkl** with mutex serialization, tombstone preservation, monotonic watch-progress merging, and loop prevention.
- **🧹 Thoughtful Smart Auto-Cache System:**
  - New `AppCacheManager` with user-first eviction priority (**1.** Stale OTA update APKs & orphaned stream buffers → **2.** Idle P2P torrent cache → **3.** Video playback buffers → **4.** HTTP metadata cache → **5.** Oldest untouched thumbnails), keeping recent Home/Details artwork fast (14-day minimum image window) and **strictly protecting offline downloads**.
  - Configurable **Automatic cache cleanup**, **Max cache limit** (`250 MB` / `500 MB` / `1 GB` / `2 GB`), **Stale cache retention** (`3` / `7` / `14` / `30 Days`), **Auto-clear P2P cache on player exit**, low-storage emergency guard (<512 MB free), and a 4-category **Cache Breakdown** with individual **Clear** actions.
- **🛡️ Option to Disable P2P Torrent Streams:**
  - Added **"Allow P2P torrent streams"** toggle directly in **Settings → Plugins & addons** (on both the **Repositories** and **Addons** tabs) and **Settings → Playback**.
  - Turning P2P OFF immediately shuts down the native BitTorrent engine, filters out P2P/magnet streams across the player and downloader (hiding the `P2P` download tab when Debrid is off), and prevents Debrid-to-P2P swarm fallback while still allowing HTTPS Debrid streams when configured.

---

## 🐛 Bug Fixes & Stability

- **Fixed Cast Refresh Crash (v1.2.0):** Declared `CHANGE_WIFI_MULTICAST_STATE` in `AndroidManifest.xml`, wrapped `WifiManager.MulticastLock` acquire/release in safe `try/catch` blocks, and added a concurrency guard (`scanGuard`) so tapping Refresh in the Cast sheet never crashes.
- **Fixed HTTP Metadata Disk Cache Clear:** `Storage & cache` now properly evicts `DohDns.metadataClient` disk cache instead of the uncached base client, and clearing P2P cache when idle no longer spins up the native torrent engine.
- **Protected Active Sequential Stream Sessions:** `SequentialStreamManager.cleanOrphanedCache` now preserves any currently active playback session directory.
- **R8 Release Hardening:** Added explicit keep rules for all `@Serializable` sync, TV companion, and Room models in the R8-minified release build.
---

# Netvio v1.2.0-Stable — Tablet & Landscape Overhaul, Persistent Batch Downloads & Sync Efficiency
This release brings full horizontal, tablet, and foldable UI compatibility across every screen, persistent multi-episode batch downloading with redesigned frosted glass controls, tighter Supabase bandwidth optimization, and add-on/stream stability fixes.

---

## ✨ What's New

### 📱 Horizontal, Tablet & Landscape Layout Support
* **Adaptive Window & Cutout Geometry**: Added `WindowDimensions` (`WindowWidthClass` / `WindowHeightClass`) and shared navigation clearance (`NavigationLayoutState`) across Home, Search, Library, Profile, Settings, Player, Onboarding, Walkthrough, and modal sheets.
* **Persistent Side Navigation Rail**: Introduced `FrostedNavigationRail` on wide and landscape screens with dedicated start clearance, ensuring navigation stays visible in Library, Search, and across keyboard/scroll interactions.
* **Responsive Media Details Split View**: Organized wide and landscape screens into a balanced two-column layout with scrollable `AppTabRow`, non-overlapping quick action tiles, adaptive season/batch controls, and responsive episode rows.

### ⬇️ Persistent Batch Episode Downloads
* **Application-Scoped Coordinator**: Moved multi-episode resolution and queueing into `BatchDownloadCoordinator`, preserving live progress and download states when leaving Media Details and returning later.
* **Redesigned Frosted Download Sheet**: Rebuilt `MultiEpisodeDownloadSheet` with attached frosted glass styling, smoother dropdown transitions, season switcher, episode selection filter, preferred quality selector, and background preflight checks.

### ⚡ Supabase Sync Efficiency & Performance
* **Traffic & Egress Reduction**: Added `SyncEfficiencyPolicy` with a 5-minute auto-pull cooldown and per-domain content hash caching (`profiles`, `settings`, `addons`, `playlists`) to prevent redundant network requests and Free-Tier egress spikes (paired with schema update `004_sync_efficiency_and_addons_rpc.sql`).
* **UI Blur Performance Toggle**: Added a clear **UI Blur** toggle in Appearance Settings so users on lower-end devices can control backdrop blur overhead cleanly.

---

## 🐛 Bug Fixes & Stability
* **Media Details Landscape Episode Stack**: Fixed `AnimatedContent` layout stacking in the wide split Episodes view where the season selector, episode range buttons, episode rows, and pagination controls could overlap.
* **CNCVerse & Stremio Add-on Parsing**: Hardened add-on metadata parsing (`AddonMetaParser`, `HomeCatalogParser`, `MatchScorer`) and improved stream resolution reliability in `StreamsRepository`.
* **Download & Playback Networking**: Improved retry, pause/resume state tracking, and sequential/download error handling across `DownloadEngine`, `DownloadRepository`, and `PlayerPlaybackNetworking`.

---

## 📦 APK Installation & Verification

| Property | Value |
| :--- | :--- |
| **Package (`applicationId`)** | `com.euthopiar` |
| **File Name** | `Euthopiar-v1.2.0-Stable.apk` |
| **File Size** | `30.57 MB` |
| **SHA-256 Checksum** | `8CA7A85300DC6C05EE791A1BCE00490203B82C706A52A5BFE8BC06867227014F` |

---

## 📥 How to Install
1. Download **`Euthopiar-v1.2.0-Stable.apk`** below.
2. Tap the downloaded file and select **Install** (allow *Install unknown apps* if prompted).
3. Existing users on `v1.1.0-Stable` can install directly over the previous version—all local settings, watch history, playlists, and plugins are preserved.
---

# Changelog
