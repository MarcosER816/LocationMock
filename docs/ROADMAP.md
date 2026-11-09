# MockRoute roadmap

Tick a stage (`[x]`) only when its acceptance checks pass and CI is green. Details live in `CLAUDE.md`.

- [x] **Stage 0 — Skeleton & CI.** Gradle multi-module (`:core` pure Kotlin, `:app` Compose), version catalog,
  wrapper, stable debug keystore, GitHub Actions debug APK artifact, optional API-key secrets → BuildConfig, docs.
- [x] **Stage 1 — Map + mock service MVP.** _(CI green; phone test script passed on POCO F7 Ultra 2026-11-09)_ MapLibre + OpenFreeMap map showing the real location; manifest
  permissions; `MockLocationService` (foreground, test providers gps/network/fused, startup cleaner); start/stop from
  UI; mock a fixed point; "not selected as mock app" explanation + deep link; one-time disclaimer.
- [ ] **Stage 2 — Routing & tracing.** `RoutingProvider` (ORS default, GraphHopper alt), point-to-point best route,
  street-by-street tracing snapped to roads, straight-line/hand-drawn fallback with no keys; response caching.
- [ ] **Stage 3 — Distributions + feasibility UI.** Truncated gamma for speed/time; v_min/v_max, t_min/t_max, a_max
  presets (Walk/Car/Bus); feasibility rules; coupled (v_max, t_max) double slider.
- [ ] **Stage 4 — Speed profile + gradient plan + playback.** Sample T; Gaussian-copula OU speed profile + accel
  limiting + bisection to hit T; green→yellow→red route rendering with duration label; play the plan through the
  mock service at 1 Hz.
- [ ] **Stage 5 — Traffic-aware car/bus.** `TrafficProvider` (TomTom default, Google optional); Car/Bus auto-settings.
- [ ] **Stage 6 — Indoor zone + Gaussian-mixture 3D editor.** Polygon zone, A/B on boundary, biased wandering by a
  user-edited 2D Gaussian mixture with 3D preview.
- [ ] **Stage 7 — Street zone.** Overpass `RoadGraphProvider` (cached, ≤100 queries/day); wander on streets inside
  the polygon.
- [ ] **Stage 8 — Timeline.** Chain segments; enforce B(i) == A(i+1); first A = real location captured before mocking.
- [ ] **Stage 9 — GPS noise.** Gauss–Markov error, environment-dependent accuracy, multipath jumps, consistent
  speed/bearing/altitude.
- [ ] **Stage 10 — Polish + device testing.** Setup wizard, settings (keys in DataStore), HyperOS reliability,
  end-to-end testing on the POCO F7 Ultra.
