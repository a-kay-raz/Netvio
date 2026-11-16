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
