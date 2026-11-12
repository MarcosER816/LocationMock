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
6. STREET ZONE: larger polygon; wander only on OSM streets inside it (Overpass graph); A/B inside or on edge,
   NEVER outside (editors refuse shapes that leave a fixed point outside; planning refuses too).
   In a timeline the indoor zone's fixed entry is its first corner; a street zone's corners are all free and the
   shape only closes with the fixed entry inside or on the edge.
7. TIMELINE: chain segments; B of segment i == A of segment i+1 (enforced); first A = real location captured BEFORE
   mocking (editable). Points are labelled A, B, C, D… Points shared by two steps are FIXED (no editor may move them;
   to change one, delete the steps after it); only the first and last point move. Steps are added/removed only at
   the start or the end; no reordering. While editing a step, the other steps show in grey (routes as lines, zones
   as a grey heatmap). Default speed & time per route step are data-driven (TomTom traffic at that hour → router
   ETA × hour congestion → standard speed; walking adds street-crossing waits) until the user sets them.
8. Noise: time-correlated Gauss–Markov error, varying accuracy (open sky ≈3–8 m, urban worse, indoor much worse),
   occasional multipath jumps, consistent speed/bearing/altitude, 1 Hz.

## Architecture
- Gradle multi-module, Kotlin, Compose, minSdk 26, targetSdk/compileSdk 36, JDK 17 toolchain.
- :core = PURE Kotlin/JVM (no Android imports): geo, truncated gamma, feasibility, sliders, speed profiles, zone
  walkers, road graph, noise, timeline, kotlinx.serialization. Must pass `./gradlew :core:test` without Android SDK.
- :app = Compose UI, MapLibre Compose, ViewModels, network clients, DataStore/Room, MockLocationService, setup wizard.
- Interfaces: RoutingProvider (ORS default, GraphHopper alt), TrafficProvider (TomTom default, Google optional),
  RoadGraphProvider (Overpass), TileStyle (OpenFreeMap liberty + fallback).
- API keys live ONLY on the device: pasted in app Settings (DataStore). Never committed, never in GitHub secrets,
  never built into the APK (the repo is public and CI artifacts are downloadable). (Changed 2026-11-10 at the
  user's request; previously GitHub secrets → BuildConfig.) The app must work with no keys (walk mode,
  straight-line/hand-drawn routes).

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
- Internally SI units; UI in km/h, m/s², and durations spelled out ("25 min", "1 h 05 min"; a bare "6:00" was
  misread as 6 minutes).
- Show a one-time disclaimer that other apps can detect mock locations; testing/privacy use only.

## UI rules (user preferences, 2026-11-10)
- Always dark theme with vibrant colours, one per kind of thing (Route blue, Trace cyan, Draw amber, Area purple,
  Trip green, errors pink). No dynamic colour, no light theme. Dark map by default.
- Map (bundled `assets/map_dark.json`, built by `tools/make_map_style.py` from OpenFreeMap liberty): show as many
  buildings and details as possible (like Google Maps): buildings from z13, 3-D from z14, lighter than the streets;
  POIs + house numbers; green areas dark green, water blue. Overture Maps buildings (PMTiles, release looked up
  daily by `map/OvertureBuildings.kt`) fill places where OpenStreetMap has no buildings.
- Map overlays and panels: use `MapPanel` / `MapControlStack` / `Tag` from `ui/Widgets.kt`; overlays use the app
  palette with dark halos (never white casings).
- Default speed & time of a route = the provider's values as given; the first plan takes exactly the provider's
  time AND follows the provider's speed at every point (ORS step / GraphHopper average_speed, scaled to its ETA;
  no random variation). Shuffle adds variation around that profile; the gammas reshape it.
- Buttons are smooth squares (use the wrappers in `ui/Widgets.kt`; never Material's pill buttons).
- Animations: ease-OUT only, never ease-in, at most 0.1 s (`AppMotion.spec()`).
- The map always keeps at least 50 % of the screen; bottom panels scroll inside ≤ 45 %.
- Plain names in the UI (Map / Route / Trace / Draw / Area, Building / Streets, Start, Shuffle, Trip, Prepare
  trip…); prefer doing things automatically over asking (e.g. Start trip prepares first).
- New fixed labels go into the Spanish dictionary in `ui/I18n.kt`.
