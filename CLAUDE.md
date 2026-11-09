# CLAUDE.md — MockRoute project brief (read at the start of every session; keep updated)

## Product
Personal, sideloaded Android app that simulates the device location via Android's mock-location framework on the
user's POCO F7 Ultra (HyperOS 3, Android 16). The user works ONLY from a phone: no Android Studio, no adb.
Builds run in GitHub Actions; the user installs the debug APK artifact.

## Features (built in stages, see docs/ROADMAP.md)
1. World street map (MapLibre + OpenFreeMap vector tiles) with the real location.
2. ROUTE segments: point-to-point best route OR street-by-street tracing (snapped to roads).
   Per segment: v_min, v_max ∈ [0,100] km/h + speed gamma inside them; t_min, t_max ∈ [0h00,6h00] + time gamma;
   a_max (uncapped) with presets: Walking ≈1.0 m/s² (peaks 2–3), Car ≈1.3 normal (2.2 brisk, 3–4 full throttle),
   City bus ≈1.0 comfort (1.5–2.5 observed max). Mode: Walk / Car / Bus (Car & Bus enable traffic auto-settings).
3. Feasibility: d ≤ v_max·(t_max − v_max/a_max) (start/end at rest), display rule d ≤ v_max·t_max, and d ≥ v_min·t_min.
   NOTE: the user's text said "max_velocity/max_time" — a typo; we use v·t.
   If infeasible: coupled double slider (v_max, t_max), default = raise t_max. Lowering one raises the other just
   enough; raising one never changes the other. Caps: v_max ≤ 100 km/h, t_max ≤ 6 h.
4. Plan: sample T from time gamma; continuous speed profile from speed gamma (Gaussian-copula OU + accel limiting +
   bisection to hit T). Route drawn green (slowest) → yellow (mean) → red (fastest); midpoint label = duration.
5. INDOOR ZONE: polygon; A and B on the boundary (never inside); free wandering inside, biased by a user-edited
   mixture of 2D Gaussians (3D preview); same settings, a_max default = walking.
6. STREET ZONE: larger polygon; wander only on OSM streets inside it (Overpass graph); A/B inside or on edge.
7. TIMELINE: chain segments; B of segment i == A of segment i+1 (enforced); first A = real location captured BEFORE
   mocking (editable).
8. Noise: time-correlated Gauss–Markov error, varying accuracy (open sky ≈3–8 m, urban worse, indoor much worse),
   occasional multipath jumps, consistent speed/bearing/altitude, 1 Hz.

## Architecture
- Gradle multi-module, Kotlin, Compose, minSdk 26, targetSdk/compileSdk 36, JDK 17 toolchain.
- :core = PURE Kotlin/JVM (no Android imports): geo, truncated gamma, feasibility, sliders, speed profiles, zone
  walkers, road graph, noise, timeline, kotlinx.serialization. Must pass `./gradlew :core:test` without Android SDK.
- :app = Compose UI, MapLibre Compose, ViewModels, network clients, DataStore/Room, MockLocationService, setup wizard.
- Interfaces: RoutingProvider (ORS default, GraphHopper alt), TrafficProvider (TomTom default, Google optional),
  RoadGraphProvider (Overpass), TileStyle (OpenFreeMap liberty + fallback).
- API keys never committed: GitHub secrets / local.properties → BuildConfig, or pasted in app Settings (DataStore).
  The app must work with no keys (walk mode, straight-line/hand-drawn routes).

## Mock-location rules (hard requirements)
- Manifest: ACCESS_MOCK_LOCATION (tools:ignore="MockLocation,ProtectedPermissions"), ACCESS_FINE/COARSE_LOCATION,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS, INTERNET.
- Test providers "gps", "network", + "fused" on API 31+: removeTestProvider (try/catch) → addTestProvider →
  setTestProviderEnabled(true) → setTestProviderLocation each tick. Optional GMS setMockMode(true)+setMockLocation;
  ALWAYS setMockMode(false) on stop.
- Every Location: provider == test provider name, lat, lon, accuracy, time, fresh elapsedRealtimeNanos, altitude,
  speed (m/s), bearing, vertical/speed/bearing accuracy.
- Startup cleaner removes all test providers on app start and service stop.
- Read the REAL location before mocking. Start the service only from a visible UI action.
- Not selected as mock app (SecurityException) → explain + deep link to Developer options.

## Agent workflow rules
- Start: read CLAUDE.md, docs/ROADMAP.md, docs/HANDOFF.md. End: update HANDOFF.md (done, stubbed, issues, next steps)
  and tick ROADMAP.md. Stay within the current stage; small clear commits.
- Always run `./gradlew :core:test`. Try `:app:assembleDebug` only if the Android SDK is available; else rely on CI
  and say so in HANDOFF.md.
- Never commit secrets or use tile.openstreetmap.org. Cache Overpass (≤100 automated queries/day) and
  ORS/GraphHopper/TomTom responses; debounce requests.
- Internally SI units; UI in km/h, h:mm, m/s².
- Show a one-time disclaimer that other apps can detect mock locations; testing/privacy use only.
