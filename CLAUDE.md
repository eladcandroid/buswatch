# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**BusWatch** — a standalone **Wear OS** app (OnePlus Watch 4, Wear OS 6) showing live Israeli bus arrivals + a live map, with no phone required. Published to Google Play (Wear track). Hebrew RTL throughout, rotary-crown input.

## Build & run

Stack: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, Wear Compose 1.4.0, compileSdk 35, minSdk 30, Java 17, Gradle 8.10.2.

```bash
./gradlew :app:assembleDebug     # debug APK — iterate with this
./gradlew :app:assembleRelease   # signed release APK (R8) — see "smooth build" below
./gradlew :app:bundleRelease     # signed .aab for Play
```

Deploy to the watch (the `adb` transport_id **rotates** between reconnects — never hardcode it; target by serial from `adb devices -l`):

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.eladcohen.buswatch/.presentation.MainActivity
adb -s <serial> shell pm grant com.eladcohen.buswatch android.permission.ACCESS_FINE_LOCATION
```

Debug and release are signed with **different keys** — switching between them on the watch needs an uninstall first (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

There is **no automated test suite**. Verification is on-device: drive the UI with `adb shell input tap/swipe`, capture with `adb exec-out screencap -p > out.png`, and read the PNG.

## Modules

- **`:app`** — the Wear app. This is the product; almost all work happens here.
- **`:phone`** — "Bus Bridge", an experimental companion that mirrors the phone's Nearby Bus app to the watch over a Wi-Fi socket via an `AccessibilityService`. Superseded by the standalone watch app; effectively dormant. Don't touch unless explicitly asked.

## `:app` architecture (the big picture)

Fully standalone — no phone, no GMS (runs on this microG watch), no paid APIs. Four free data sources:

- **curlbus.app** (`https://curlbus.app/<stopCode>`) — **primary** live MoT SIRI arrivals. **REQUIRES header `Accept: application/json`** or it serves ASCII art. Each visit may carry a live GPS `location` (`{lat,lon}` as strings) + `vehicle_ref`; many arrivals are schedule-only with no `location`. ⚠️ It's one person's free server (Elad Alfassa) and has gone fully **502 for extended periods** — that outage is why the fallback below exists.
- **kavnav.com** (`/api/realtime?stopCode=` + `/api/stopSchedule?stopCode=&date=`) — **fallback** when curlbus throws. Same MoT SIRI data, same author. `net/KavNavClient.kt` merges the two endpoints to reproduce curlbus's board: live vehicles (onward-call ETAs + GPS) **plus** timetable backfill (next scheduled departure per line, cached once/stop/day, so a stop in a lull isn't empty). Schedule-only line numbers resolve via the bundled `assets/routes.tsv` (`RoutesDb`, routeId→line, ~58KB). The failover + a 2-min curlbus circuit-breaker live in **`net/ArrivalsSource.kt`** — the single entry point both consumers call (so never call `CurlbusClient`/`KavNavClient` directly). Good-citizen use of a personal project: contactable User-Agent, 30s poll floor. Endpoints are open/no-auth but undocumented — the durable fix is still self-hosting curlbus with a MoT key.
- **OpenStreetMap raster tiles** (`https://tile.openstreetmap.org/{z}/{x}/{y}.png`) — the map. Free, no key, but **REQUIRES a descriptive `User-Agent`**.
- **Hasadna open-bus stride API** — source for the bundled stops dataset (build-time, not runtime).

Runtime flow (`presentation/NearbyBusController.kt` orchestrates):
`LocationProvider` (platform `LocationManager`, no GMS) → `StopsDb.nearestN` → `ArrivalsSource.fetchStop` (curlbus→KavNav failover) → `boards: StateFlow<List<StopBoard>>` → `BusBoardScreen`. `MainActivity` hosts `controller.run()` under **`repeatOnLifecycle(STARTED)`** so GPS + polling stop when backgrounded (battery). Two modes in `BusMode`: NEARBY (5 closest stops, GPS-driven, hysteresis to avoid flip-flop) and FIXED (one searched stop, ignores GPS); persisted in `StopStore` (SharedPreferences).

`StopsDb` holds ~29k stops from `app/src/main/assets/stops.tsv` in **parallel primitive arrays** (IntArray/FloatArray + one concatenated names String) to stay memory-lean — not 29k `Stop` objects. Refreshed copy in `filesDir` wins over the bundled asset (`StopsUpdater`, >7 days stale + unmetered).

Tapping an arrival card opens the **live map** (`LineMapScreen` + `OsmMap`):
- `OsmMap` is a custom slippy map on a Compose `Canvas` — Web-Mercator projection, `LruCache<ImageBitmap>`, **fractional zoom** (tiles drawn at `floor(zoom)` inside `scale(zoomScale, pivot=center)`, markers positioned by scaled coords but fixed dp size). Drag to pan (divide drag by scale), crown to zoom (`onRotaryScrollEvent`, needs `focusRequester.requestFocus()`), tap a stop marker → `controller.selectFixed`.
- `LineMapScreen` polls `fetchStop` (board + positions in one request): board arrivals drive the **remaining-time pill** (works even with no live GPS); live `location`s drive **bus markers**, which **glide** prev→new via `Animatable` lerp keyed by `vehicle_ref`; travel-direction arrows from `bearing(prev→new)`; user-heading arrow from `HeadingProvider` (compass `TYPE_ROTATION_VECTOR`).
- A line with no live GPS shows time but no marker — surfaced by a status dot (● green = tracked, ○ gray = schedule-only), set from `Arrival.hasGps`.

## Non-obvious gotchas

- **`assets/stops.tsv` must be plain uncompressed TSV.** AGP silently gz-decompresses + renames `.gz` assets; ship plain text and read without `GZIPInputStream`.
- **Scroll/render smoothness requires a RELEASE build.** Debug is genuinely janky (R8 off); always hand the user `app-release.apk`, iterate on debug. Don't chase "lag" reports on a debug build.
- **Signing**: release reads `keystore.properties` (gitignored) → `keystore/watchbus-upload.jks` (alias `watchbus`). Losing the key needs a Play upload-key reset. Never commit the keystore, `keystore.properties`, or the password.
- **Kotlin**: `break`/`continue` inside an inline lambda (e.g. `x.ifBlank { continue }`) is gated experimental and fails to compile here — use an explicit `if (...) continue`.
- **Watch dev loop**: off-wrist the watch forces keyguard → `screencap` returns a ~1866-byte black PNG (can't screenshot/interact). Needs the watch on-wrist or on charger with "Stay awake". The **crown rotary cannot be injected via adb** — zoom feel must be tested by hand.
- **Testing GPS**: `adb shell appops set com.android.shell android:mock_location allow`, then `cmd location providers add-test-provider gps` / `set-test-provider-enabled gps true` / `set-test-provider-location gps --location <lat>,<lon>`; keep re-asserting the location (test fixes expire). Remove the test provider when done.
- **Screenshots are 466×466 device px** but tooling often previews at half size — double eyeballed coordinates before `adb shell input tap`. Round-screen corners are clipped; keep overlay controls mid-edge, not in corners.
