# BusWatch

A standalone **Wear OS** app showing live bus arrivals for the stops nearest you
in Israel — built for the OnePlus Watch 3 (Wear OS 6), works on any Wear OS 4+
watch.

## What it does

- **Nearby mode** — uses GPS to find the 5 nearest bus stops and shows live
  arrivals for each, nearest-first.
- **Search mode** — search any stop by name or number (voice or keyboard);
  results are sorted by distance to you.
- Live real-time arrivals via [curlbus.app](https://curlbus.app) (Israel MoT
  SIRI data); ~29k stops bundled offline for instant nearest-stop lookup,
  refreshed in the background.
- Arrival cards are colour-coded by wait time (green ≤3 min, amber 4–8, red >8),
  full RTL Hebrew, rotary-crown scrolling.
- No accounts, no ads, no analytics — see [Privacy Policy](docs/privacy.html).

## Modules

- `:app` — the Wear OS app (Jetpack Compose for Wear OS).
- `:phone` — "Bus Bridge", an optional companion that mirrors the Nearby Bus
  Android app to the watch over a Wi-Fi socket via an AccessibilityService
  (experimental; the standalone watch app above is the primary path).

## Build

```bash
./gradlew :app:assembleRelease      # signed APK (needs keystore.properties)
./gradlew :app:bundleRelease        # signed .aab for Play
```

Release signing reads from a gitignored `keystore.properties`:

```
storeFile=keystore/watchbus-upload.jks
storePassword=...
keyAlias=watchbus
keyPassword=...
```

Stack: AGP 8.7, Kotlin 2.0.21, Wear Compose 1.4, compileSdk 35, minSdk 30.

## Data sources

- Live arrivals: [curlbus.app](https://curlbus.app)
- Stop list: [Hasadna open-bus stride API](https://open-bus-stride-api.hasadna.org.il)
