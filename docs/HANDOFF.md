# Handoff

_Last updated: 2026-11-10 — Stage 3 (distributions + feasibility UI) — 0.3.0 built, CI; awaiting phone test. Stages 1–2 verified on device (2: A/B follow map ✅)._

## Done
### Stage 3 — distributions + feasibility UI (0.3.0)
- **Segment sheet:** a status line ("✓ 3–7 km/h · 0:20–0:35" or "✗ Not feasible — tap to fix") and a
  **Speed & time…** button that opens the Plan screen.
- **Plan screen** (`ui/PlanScreen.kt`):
  - **Feasibility banner.** Green: "Needs ≥ X km/h average to arrive by t_max" plus the fastest physical time.
    Red: each issue explained. For TooSlow/NoSpeed it shows the **coupled sliders** (v_max, t_max) with
    **Apply**. If the route is too long even at (100 km/h, 6:00), it shows "Route too long for the caps" and no
    sliders.
  - **Speed card:** range slider 0–100 km/h (1 km/h steps), Mean/Mode toggle, centre slider, shape k slider
    0.5–30 (0.5 steps), live PDF chart with a dashed mean marker and an x axis in km/h. A warning appears when the
    requested mean/mode can't be reached with that shape.
  - **Time card:** the same, 0:00–6:00 in whole minutes, labels h:mm.
  - **Acceleration card:** preset chips (the current profile's first) with source notes, plus a free numeric a_max
    field (> 0, uncapped). Switching Walk/Car/Bus in the sheet selects that profile's default preset.
  - "Reset to <profile> defaults".
- **Persistence:** `SegmentPlanSettings` JSON in DataStore (`segment_plan_v1`), saved debounced 500 ms. There is
  one "current segment" until the timeline (Stage 8) introduces a list.
- **Chart performance** (`ui/GammaChart.kt`): the density is sampled once per distribution (160 bins, in
  `remember`). Paths/strokes are built in `drawWithCache`, which only re-runs on a size or data change, so the
  per-frame `onDrawBehind` allocates nothing. Axis text is ordinary `Text` (no text measuring in draw). A shape < 1
  spike at lo is capped at 3× the 90th percentile for display.

### Stage 2 — routing & tracing
- **Mode toolbar** (top of map): Explore | A → B | Streets | Free draw.
  - **A → B (point-to-point):** A defaults to the last real location. Tapping the map sets B. A and B are **map
    layers** (`EndpointLayers`) dragged via `Modifier.endpointDrag` (see 0.2.1/0.2.2 notes below; the original
    Compose overlay handles were removed). "Move A" makes the next tap set A, and "A = my location" resets it.
    Requests are debounced 400 ms and the newest wins (`collectLatest`).
  - **Streets (street-by-street):** each tap appends a waypoint, and the pair (previous, new) is routed as one leg.
    Legs are concatenated with the duplicate joint dropped. Undo removes the last waypoint and leg (cancelling its
    request); Clear removes everything. "Start at my location" seeds the first waypoint.
  - **Free draw:** same as Streets, with snapping off (straight legs). The "Free draw (no snapping)" switch can be
    flipped mid-route, and each leg keeps its own snap flag.
- **Segment sheet:** total distance (provider distance), provider + profile (+ provider ETA), fallback notice in
  red, Walk/Car/Bus chips (changing them re-routes), speed slider 1–100 km/h (default 5/40/25) and **Play at constant
  speed**. Play passes the route to the service as `TrajectorySpec.Polyline` (a flat DoubleArray extra) →
  `PolylineTrajectory`.
- **Map layers:** edited route (blue with a white casing; grey when it's a straight-line fallback), street
  waypoints, and the playing path (red). GeoJSON strings are memoized per geometry; display uses `simplify(1 m)`.
- **Settings → Routing:** provider radio (OpenRouteService / GraphHopper / Straight lines), API-key fields (masked,
  Show/Save), a status line saying which key is used, and Clear route cache.

### Stage 1 (verified on device)
- **Map** (`ui/MapScreen.kt`): MapLibre Compose map with the OpenFreeMap `liberty` style. If a style fails to load
  it falls back to OpenFreeMap `bright`, then to MapLibre demotiles (`map/TileStyle.kt`). Pan/zoom, a long-press
  marker (orange), the real location (blue, dimmed while simulating), the mocked location (red) and the walk path.
  A bottom card shows lat/lon, distance, walking ETA and the buttons **Simulate here** / **Walk straight to here**.
  While simulating, a status card has Pause/Resume/Stop and shows speed and remaining distance. Attribution text
  "© OpenStreetMap contributors · OpenFreeMap · OpenMapTiles" sits bottom-right, and MapLibre's (i) attribution
  button is also on.
- **Real location** (`location/RealLocationReader.kt`): uses `FusedLocationProviderClient.getCurrentLocation` (high
  accuracy, 10 s timeout). Falls back to `LocationManager.getCurrentLocation` (API 30+), then `getLastKnownLocation`.
  It refuses to read while a simulation is active and discards fixes flagged `isMock`. The fix is stored in
  DataStore as `lastRealLocation` (lat, lon, accuracy, altitude, time). It is captured on app open, from the
  my-location button, and right before every simulation start.
- **Setup wizard** (`ui/SetupWizardScreen.kt`, `setup/SetupChecks.kt`): shown on first run and from Settings →
  Check setup. Checks re-run on every resume. Steps: 1 Developer options (`Settings.Global.DEVELOPMENT_SETTINGS_ENABLED`,
  deep link to About phone / Developer options); 2 mock app (AppOps `OPSTR_MOCK_LOCATION`, with a throwaway
  `addTestProvider` probe as fallback); 3 location + notification permissions with explanations, and an app-info
  link if permanently denied; 4 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` plus HyperOS steps and an app-info
  button; 5 flicker tip and Location settings link; 6 one-time disclaimer, stored in DataStore and also enforced
  before any start. Every deep link has fallbacks, so no `ActivityNotFoundException` crash.
- **MockLocationService** (`mock/MockLocationService.kt`): foreground service with
  `foregroundServiceType="location"`, `START_NOT_STICKY`, a 1 Hz coroutine tick aligned to whole seconds, and an
  ongoing notification (title = mode, text = "x.x km/h · lat, lon") with Pause/Resume and Stop actions. Pause keeps
  pushing the current point at speed 0. A new START while running swaps the trajectory, and a walk starts from the
  current mocked point. On `onTaskRemoved` (swiped from Recents) it stops and cleans up. `onDestroy` runs
  `StartupCleaner`.
- **MockInjector** (`mock/MockInjector.kt`): registers `gps`, `network`, plus `fused` on API 31+. For each one:
  `removeTestProvider` (try/catch) → `addTestProvider` (`ProviderProperties` on 31+, legacy overload below) →
  `setTestProviderEnabled(true)`. Optional GMS layer (Settings switch, default ON when Play services is present):
  `setMockMode(true)` + `setMockLocation` every tick, and `setMockMode(false)` on stop. Each `Location` carries
  provider, lat/lon, altitude, accuracy, speed, bearing, wall time, fresh `elapsedRealtimeNanos`, and vertical,
  speed and bearing accuracy.
- **Not the mock app** → `SecurityException` becomes `SimError.NotMockApp`, which shows a dialog with the exact path
  and an "Open Developer options" button. The same dialog appears from a pre-check before starting. No crash.
- **StartupCleaner** runs in `Application.onCreate` and in `Service.onDestroy`. It removes all three test providers
  and calls GMS `setMockMode(false)`.
- **:core**: `geo/GeoMath` (haversine, initial bearing, destination, great-circle intermediate point, polyline
  length, point-at-distance, exact WGS84 ENU ↔ geodetic) and `trajectory/Trajectory` (`KinematicState`,
  `Trajectory`, `StaticTrajectory`, `StraightLineTrajectory` at 1.4 m/s), plus `Units`. 10 tests: city-pair
  haversine within 0.5 % of GeographicLib WGS84 geodesics, bearings incl. antimeridian, destination round trip,
  point-at-distance, ENU round trip, static and straight-line trajectories.
- The APK is now arm64-v8a only: ~31 MB instead of ~75 MB.

### Stage 0
Gradle multi-module skeleton, version catalog, wrapper, stable debug keystore, CI artifact `app-debug`,
optional API-key secrets → BuildConfig.

## Stage 3 API (for Stage 4)
All in `:core`, SI units (m, m/s, s, m/s²), pure Kotlin.

**`dist.BoundedGamma`**: X = lo + Y, Y ~ Gamma(k, θ) truncated to [0, W], W = hi − lo. Immutable.
- Builders: `withMean(lo, hi, k, mean)` (θ by bisection on log θ so the **truncated** mean matches; it is clamped
  to the reachable `maxMean = lo + W·k/(k+1)`; check `.mean`), `withMode(lo, hi, k > 1, mode)` (θ = mode/(k−1)),
  `fromMoments(lo, hi, meanY, varY)` and `shapeScaleFromMoments(m, s²) = (m²/s², s²/m)`, `pointMass(x)`. lo == hi
  gives a point mass.
- `pdf(x)` (normalised by F(W) − F(0)), `cdf(x)`, `quantile(p)` (commons-math3 inverse + Newton polish, with a
  bisection fallback in extreme tails), `sample(kotlin.random.Random)` (inverse CDF), `mean` (closed form
  kθ·P(k+1, W/θ)/P(k, W/θ)), `mode`, `shape`, `scale`, `lo`, `hi`, `width`, `isPointMass`.
  `MIN_SHAPE = 0.5`, `MAX_SHAPE = 30`.
- Stage 4: sample **T** with `settings.time.distribution().sample(rng)`. Use `settings.speed.distribution()` for
  the speed marginal (Gaussian copula: u = Φ(z_OU) → `quantile(u)`).

**`plan.Feasibility`**
- `tMinPhys(d, v, a)` = d/v + v/a if d ≥ v²/a, else 2√(d/a) (trapezoid/triangle, rest-to-rest).
- `absoluteMinTime(d, a)` = 2√(d/a).
- `requiredV(d, t, a)` = 2d/(t + √(t² − 4d/a)) (closed-form inverse; +∞ if t < 2√(d/a)).
- `check(d, vMax, tMax, vMin, tMin, aMax): Result(feasible, issues: List<Issue>, tMinPhysS, requiredAverageMps,
  displayRuleOk)`. Issues: TooSlow, TooShort (d < vMin·tMin), TimeRangeInverted, SpeedRangeInverted, NoSpeed.
  `displayRuleOk` = d ≤ v_max·t_max (the user's "max_velocity/max_time" corrected; display only).

**`plan.CoupledSliders(d, aMax, vMax, tMax)`**: immutable.
- `withVMax(v)` / `withTMax(t)`: a decrease pushes the other to exactly requiredT / requiredV; an increase leaves
  it alone. Clamped to the floors `vFloor = requiredV(6 h)` and `tFloor = requiredT(100 km/h)` and to the caps
  `V_CAP = 100 km/h`, `T_CAP = 6 h`.
- `tooLongForCaps` (both setters become no-ops). `isFeasible`.
- `propose(d, aMax, vMax, tMax)`: keep v_max, t_max = requiredT rounded up to the minute.

**`plan.AccelerationPreset`**: Walking 1.0, CarNormal 1.3, CarBrisk 2.2, CarFull 3.5, BusComfort 1.0, BusMax 2.0
(each with a note). `defaultFor(profile)`, `forProfile(profile)`.

**`plan.SegmentPlanSettings(speed: GammaSetting, time: GammaSetting, aMaxMps2, accelPreset?)`**: @Serializable.
`GammaSetting(lo, hi, center, shape, centerKind = Mean|Mode).distribution()` (mode with k ≤ 1 falls back to mean).
Also `withRange()`, `vMin/vMax/tMin/tMax`, `feasibility(d)`, `applyCoupled(c)` and `defaults(profile, d)`:
Walk 3–6.5 km/h (mean 5), Car 10–60 (35), Bus 5–45 (20); time = [0.8×, 1.5×] of d/mean, whole minutes.

## Routing: endpoints, quotas, caching
| Provider | Endpoint | Auth | Profiles (Walk / Car / Bus) | Free quota |
|---|---|---|---|---|
| OpenRouteService (HeiGIT) | `POST https://api.heigit.org/openrouteservice/v2/directions/{profile}/geojson` | header `Authorization: <key>` | `foot-walking` / `driving-car` / `driving-hgv` (bus proxy) | Standard plan ≈ 2,000 directions/day, 40/min (see account.heigit.org dashboard) |
| GraphHopper | `POST https://graphhopper.com/api/1/route?key=<key>` | query `key` | `foot` / `car` / `car` (no bus/truck on free) | 500 credits/day, non-commercial |
| Straight line | — (offline) | — | — | unlimited |

- **ORS host move:** HeiGIT deprecated `api.openrouteservice.org` (28 Apr 2026; 10 % quota from 27 Aug) in favour of
  `api.heigit.org/openrouteservice`. The old host shuts down in November 2026. Same keys and the same
  `Authorization` header. We only use the new host (`OrsCodec.BASE_URL`).
- **Request bodies:** ORS `{"coordinates":[[lon,lat],…],"extra_info":["waytype"],"instructions":false}`.
  GraphHopper `{"points":[[lon,lat],…],"profile":"foot","points_encoded":false,"details":["max_speed","road_class"]}`.
- **Errors → friendly messages** (`RoutingException.userMessage`). Checked live on 2026-11-09: **ORS returns 403
  `{"error":"Access to this API has been disallowed"}` for an invalid key** (not 401), and GraphHopper returns 401.
  Other mappings: 429 → rate limited; ORS codes 2009/2010/2004 or 404 → no route ("try points closer to a road");
  network → offline.
- **Fallback:** no key, an error, or offline → a straight line with a notice (route drawn grey). The app keeps
  working without keys.
- **Keys:** device-only since 0.2.2. They are pasted in Settings → Routing (DataStore) and trimmed. They are not
  in BuildConfig, GitHub secrets or the repo. The `ORS_API_KEY`… secrets are no longer read by CI.
- **Cache** (`net/RouteCache`): `cacheDir/routes/<sha256>.json`, keyed by provider | profile | points rounded to
  1e-6°. 30-day TTL, max 500 files (oldest evicted). Only real (non-fallback) results are stored. Re-adding the
  same leg, undo/redo, or switching profile back costs no request.
- **Debounce:** point-to-point 400 ms. Street legs make one request per tap (a tap is deliberate), cancelled on undo.

## RouteGeometry API (for Stage 3+)
`com.mockroute.core.route.RouteGeometry`: immutable, haversine cumulative distances, kotlinx-serializable as a
list of `LatLon`.
- `RouteGeometry.of(points)`, `EMPTY`, `points`, `size`, `start`, `end`, `lengthM`.
- `cumulativeM(i)`, `cumulativeDistances()`: distance from the start to vertex i.
- `pointAtDistance(d)`: `PolylinePoint(position, bearingDeg, segmentIndex)`, clamped, O(log n).
- `densify(maxSpacingM = 10.0)`: great-circle points inserted, original vertices kept, length preserved.
- `simplify(toleranceM)`: Douglas–Peucker (local equirectangular), for display only.
- `a + b`: concatenation that drops a duplicate joint.

`RouteResult(geometry, distanceM, durationS?, providerId, profile, spans: List<RouteSpan>, isFallback, notice)`.
`RouteSpan(startM, endM, speedLimitKmh?, roadClass?)` is distance-based, so it survives densify. ORS fills
`roadClass` from `waytype`; GraphHopper fills both `speedLimitKmh` (max_speed) and `roadClass`.
`RouteResult.concat(legs, profile)` joins legs and offsets the spans.
`RoutingProvider { id; displayName; suspend fun route(points, profile): RouteResult }` throws `RoutingException`.
`RoutingService.route(points, profile, snap, settings)` never throws (it falls back).
`PolylineTrajectory(geometry, speedMps)` is the temporary playback; Stage 4 replaces it with speed profiles over
`densify()`d geometry.

## Versions
| Component | Version | Note |
|---|---|---|
| Gradle (wrapper) | 9.9.0 | |
| Android Gradle Plugin | 9.5.1 | Built-in Kotlin (no `kotlin-android` plugin) |
| Kotlin (+ Compose & serialization plugins) | 2.4.21 | |
| Compose BOM | 2026.06.01 (Compose 1.11.4, Material 3 1.4.0) | 2026.08.00+ needs compileSdk 37 |
| **MapLibre Compose** `org.maplibre.compose:maplibre-compose` | **0.14.0** | Bundles MapLibre Native Android 13.0.2 (OpenGL). 0.16+ needs compileSdk 37 |
| play-services-location | 21.4.0 | |
| datastore-preferences | 1.3.0 | |
| androidx.core:core-ktx | 1.18.0 | 1.19.x needs compileSdk 37 |
| androidx.activity:activity-compose | 1.13.0 | |
| androidx.lifecycle (runtime/viewmodel compose) | 2.10.0 | 2.11.x needs compileSdk 37 |
| kotlinx-coroutines (core/android/play-services) | 1.11.0 | |
| kotlinx-serialization-json | 1.12.0 | |
| commons-math3 | 3.6.1 | |
| JUnit (BOM) | 6.1.3 | |
| JDK | 17 (CI, Temurin) | Bytecode target 17 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | |

## MapLibre Compose notes (0.14.0)
- Entry point: `MaplibreMap(baseStyle = BaseStyle.Uri(url), cameraState, onMapLongClick = { pos, dpOffset -> ClickResult.Consume }, onMapLoadFailed = { reason -> }) { layers }`.
- `Position(longitude, latitude)` comes from `org.maplibre.spatialk.geojson`. Note the order: **lon first**.
- Camera: `rememberCameraState(CameraPosition(target, zoom))`; `cameraState.animateTo(position, duration)`.
- Data: `rememberGeoJsonSource(GeoJsonData.JsonString(json))` (re-sets the data on recomposition) +
  `CircleLayer` / `LineLayer` with `const(...)` expressions (`org.maplibre.compose.expressions.dsl.const`).
- No separate render-runtime artifact is needed in 0.14. From 0.16 the Android backend moved to
  `maplibre-native-ffi` with separate `maplibre-compose-runtime-opengl-android` / `-vulkan-android` artifacts.
  Re-check this when upgrading.
- Upgrading past 0.14 requires compileSdk 37 (it pulls Compose 1.12 and lifecycle 2.11).

## Stubbed
- Playback is constant speed (no acceleration, no v/t distributions) until Stages 3–4.
- A route segment is not saved or persisted yet (timeline = Stage 8); leaving the mode keeps the edit in memory only.
- Street waypoints aren't draggable (only point-to-point A/B are).
- The disclaimer is shown in the wizard and enforced before start. There is no separate first-launch popup if the
  wizard is skipped (it pops up on the first Simulate tap instead).

## Known issues / HyperOS observations (to confirm on device)
- **Swipe from Recents stops the simulation** by design (`onTaskRemoved` → cleanup) so location is never left
  frozen. To keep simulating while using other apps, just switch apps (don't swipe), or lock MockRoute in Recents.
- If HyperOS **force-kills** the process (no `onTaskRemoved`/`onDestroy`), Android may keep the last mock fix on
  the test providers. Opening MockRoute once fixes it (StartupCleaner runs on app start). Unselecting the mock
  app in Developer options also clears it.
- Google Maps reads Play services' fused location. If it doesn't follow the mock, check that the GMS layer is ON
  (Settings) and that Google Location Accuracy is OFF (wizard step 5).
- **2026-11-09: user ran the full Stage 1 phone test script (steps 1–14 below) on the POCO F7 Ultra
  (HyperOS 3 / Android 16) — all steps OK.** Wizard all green; Google Maps follows "Simulate here" and the
  5 km/h walk; Pause/Resume works; Stop and swipe-from-Recents restore the real location; "not mock app" shows
  the dialog + deep link without crashing. CI build: Android CI run #2.
- Lint: 0 errors; warnings are cosmetic (Timber, `toUri`, targetSdk 37 available).

## Known issues (Stage 2)
- **2026-11-09 phone test, round 1:** routing, streets, undo, free draw and playback in Google Maps all work, but:
  1. **The A/B handles stayed fixed on screen while the map moved.** The first attempt (0.2.0+, computing the
     overlay position in `Modifier.offset {}`) did not fix it on the device. **0.2.1 removes the Compose overlays
     entirely:** A/B are now drawn as **map layers** (`EndpointLayers`: CircleLayer + SymbolLayer label with font
     "Noto Sans Bold" from the liberty style), so they are geographically pinned by MapLibre itself. Dragging is done
     by `Modifier.endpointDrag` on the Box that contains the map. It runs in `PointerEventPass.Initial` (parents
     before children). On each touch-down it computes A/B screen positions *fresh* from the projection; if the touch
     is within 36 dp it consumes the gesture, and Compose's `PointerInteropFilter` then stops dispatching it to the
     MapLibre AndroidView, so the map doesn't pan. Otherwise it doesn't touch the event. Verified against the
     Compose 1.11.4 `PointerInteropFilter` source. **Rule for later stages: never place Compose views over the map
     for geo-anchored things; use map layers.**
  2. **The built-in key (GitHub secret) didn't work; the key had to be pasted in Settings, and after reopening the
     app routing only worked after visiting Settings.** CI shows the secret is present (`ORS_API_KEY: ***`). Likely
     cause: the secret value has surrounding whitespace/quotes (common when pasting on a phone). ORS rejects such
     a key with 403, which gives a straight-line fallback, and nothing retried it. Fixes in 0.2.1:
     - Gradle trims whitespace/quotes from secrets and logs `API key ORS_API_KEY: present, N chars (removed …)`
       (no key content). **Check that line in the CI log.**
     - The app trims keys too. Settings key fields **save as you type** (the old Save button was easy to miss, which
       explains "had to set it again after reopening"). The status line shows which key is used
       (`saved on phone` / `built-in`, length, last 4 chars).
     - **Auto re-route:** when the provider or a key changes (debounced 800 ms), the editor re-routes the P2P
       route and any fallback legs (`RouteEditor.refresh()`). A **Retry** button sits next to every fallback notice.
       Network errors retry once after 1.5 s (`RoutingService.routeWithRetry`).
     - **Settings → Test routing** makes one 400 m walking request with the current key and shows the exact result
       or error.
     - **Build identity** in Settings → About: `0.2.1 (build <CI run #>, <git sha>)` confirms which APK is installed.
- The ORS/GraphHopper fixtures in `core/src/test/resources/fixtures` are hand-built from the documented formats
  (no key in the agent sandbox). Error bodies were checked against the live APIs. If a real response ever fails to
  parse, save it as a new fixture.
- The tall segment sheet covers part of the map on small screens.

## Next steps
- Run the Stage 3 phone test script below (and the 0.2.2 re-check if not done).
- Stage 4: speed profile + gradient plan + playback (sample T from `time.distribution()`; Gaussian-copula OU on
  `speed.distribution()`; accel limiting with `aMaxMps2`; bisection to hit T; colour the route by speed).

## How to verify on phone (Stage 3 test script)
1. Install 0.3.0 (Settings → About). Draw a route (A → B, Walk, ~1–2 km).
2. The sheet shows "✓ 3–7 km/h · h:mm–h:mm". Tap **Speed & time…**.
3. Speed card: move the range, the mean slider and the shape slider. The curve updates smoothly; the dashed line
   is the mean. Switch to **Mode**: the peak follows the slider.
4. Time card: same, labels in h:mm.
5. Acceleration: tap the presets; type 1.75 in the field (it stays as typed).
6. Make it infeasible: set the time range to 0:01–0:02. The banner turns red with an explanation and two linked
   sliders. Drag **v_max down**: t_max rises. Drag **t_max down**: v_max rises. Drag either **up**: the other
   doesn't move. Tap **Apply**: the banner turns green and the cards show the new values.
7. Back to the map → the sheet line updates. Close and reopen the app → the settings are kept.
8. Draw a ~700 km Car route (two distant cities) → "Route too long for the caps", with no sliders.

## Build steps
- Agent sandbox: `./gradlew :core:test` (no Android SDK needed). For `:app:assembleDebug`, install
  `platforms;android-36` + `build-tools;36.0.0` with sdkmanager and point `sdk.dir` in `local.properties` at it.
  Maven Central sometimes returns HTTP 429 in the sandbox; re-run.
- CI (source of truth): every push / PR / manual run builds the `app-debug` artifact.
- Verified 2026-11-10 in sandbox: `:core:test` 48/48 passed (BoundedGamma, Feasibility, CoupledSliders, settings, earlier stages); `:app:lintDebug` 0 errors; `:app:assembleDebug` OK.

## 0.2.2 (2026-11-10)
- **Phone round 2:** the A/B markers now follow the map (map-layer approach confirmed ✅).
- **Bug: B couldn't be placed / no path unless Settings had been opened from A → B.** Cause: **MaplibreMap 0.14
  remembers its `onMapClick`/`onMapLongClick`/`onMapLoadFailed` callbacks from the first composition**
  (`remember(cameraState, styleState, styleComposition)`, not keyed on the lambdas). The map was created in Explore
  mode, so the tap handler kept seeing "not editing" and passed taps through. Leaving for Settings and coming back
  rebuilt the map with fresh lambdas. Fix: the map gets stable forwarding lambdas that call
  `rememberUpdatedState` holders, and the handlers read the mode from `vm.editor.state.value` at call time. **Rule:
  any callback passed to MaplibreMap must go through rememberUpdatedState.**
- **API keys are device-only now (user request):** removed the BuildConfig key fields, the Gradle `apiKey()` and
  the workflow `secrets.*` env. Keys come only from Settings (DataStore), trimmed of spaces/quotes. CLAUDE.md and
  README updated. Settings shows "Saved on this phone: N chars, …abcd" or "No key". **The repo is public:** CI
  artifacts from builds that had the `ORS_API_KEY` secret (runs ~#4–#6, 2026-11-09) contain the key in the APK.
  The user should delete those runs/artifacts and regenerate the ORS key.

### Re-check for 0.2.2 (do first)
1. Settings → About shows **0.2.2 (build N, sha)**.
2. Settings → Routing: paste the ORS key (if it isn't there) → **Test routing** says "OK — ors: …".
3. Close MockRoute from Recents, reopen it, tap **A → B** right away (without visiting Settings) and tap the map:
   **B appears and the street route is drawn.**
4. Explore → Settings → back → A → B → tap the map: B and the route appear.
5. Streets mode, right after opening the app: taps add legs.

## Re-check for 0.2.1
1. Settings → About shows **0.2.1 (build N, sha)**. N should match the latest green Actions run.
2. Settings → Routing: **clear the ORS key field** (to test the built-in key). The status line says
   "Using key built-in (GitHub secret): N chars, …abcd". Tap **Test routing**: it should say "OK — ors: … route".
   If it shows a 403 message, the GitHub secret value is wrong: re-create it (paste only the key) or just paste the
   key in the field (it is saved immediately).
3. Close MockRoute from Recents, reopen it, go to A → B and set B: the route follows the streets straight away.
4. With A and B set, **pan, zoom and rotate**: A and B stay on their streets.
5. **Drag** A and B: the map doesn't pan while you drag a marker, and it re-routes when you lift your finger.
   Dragging anywhere else pans the map as normal.

## How to verify on phone (Stage 2 test script)
Needs the `ORS_API_KEY` GitHub secret (set before this build) or a key pasted in Settings → Routing.
1. Install the new `app-debug`. Settings ⚙ → Routing: OpenRouteService is selected, and the key field says
   "using the key built in from GitHub secrets" (or paste your key and tap Save key).
2. Map → **A → B**. A green "A" handle sits on your real location. Tap the map about 1 km away: a red "B" appears.
   Within about 1 s a **blue line following the streets** appears, and the sheet shows the distance and
   "OpenRouteService · Walk · ETA".
3. Drag **B** to another street, then drag **A**. The route recalculates after you let go.
4. Tap **Car**, then **Bus**. The route changes to roads that allow cars/trucks; the distance updates.
5. **Streets**: tap "Start at my location" (or tap a first point), then tap 5 points along nearby streets.
   Each tap extends one continuous blue line along the streets (no gaps at the joints).
6. Tap **Undo** twice: the last two legs disappear. Tap one more point: it reconnects from the current end.
7. Turn on **Free draw (no snapping)** and tap 2 points: straight segments are added. Turn it off again.
8. Set the speed slider to about 5 km/h → **Play at constant speed**. The red dot follows the route. Open **Google
   Maps**: your location moves along the streets. Stop when done.
9. No-key fallback: Settings → Routing → paste `x` in the ORS key and Save. A → B now draws a **grey straight
   line** with a red notice ("…refused the request (403)… key invalid…"). Clear the field, Save, and routing works
   again (built-in key). Choosing "Straight lines (offline)" always draws straight lines without any notice.
10. Optional: airplane mode → A → B gives a straight line and an "offline" notice. Previously routed A/B pairs
    still come from the cache.

## Stage 1 test script (passed 2026-11-09)
1. Install the new `app-debug` artifact over the old one (it should update in place, with no uninstall).
2. Open MockRoute. The setup wizard appears (first run after the update). Work through each step with its button
   and return to the app. Steps 1, 2, 3, 4 and 6 should turn green (✓) on their own; step 5 is info only.
3. Tap **All set — open the map**. The world map loads (OpenFreeMap style), then flies to your real location
   (blue dot). The attribution text is visible bottom-right.
4. Pinch/pan the map. Tap the ⌖ button: the map re-centres on the blue dot (spinner while reading).
5. Long-press a spot about 500 m away. An orange marker appears and the bottom card shows lat/lon, distance and
   walking ETA.
6. Tap **Simulate here**. A red dot appears at the marker, the status card says "Holding position · 0.0 km/h",
   and an ongoing notification "MockRoute · Holding position" appears.
7. Open Google Maps and tap its location button. It should show you at the marker. Return to MockRoute.
8. Long-press another spot about 200 m away → **Walk straight to here**. The red dot moves about 1.4 m every second
   along the red line; the card shows "5.0 km/h" and the remaining distance. Google Maps' blue dot follows.
9. Pull down the notification → **Pause**. The card shows "Paused", speed 0. Tap **Resume** in the notification.
   Movement continues.
10. Tap **Stop**. The notification disappears. Within ~30 s Google Maps shows your real position again.
11. Start any simulation again, then open Recents and swipe MockRoute away. The notification disappears and
    Google Maps returns to the real location (≤ 30 s).
12. Not-mock-app check: Developer options → Select mock location app → **Nothing**. In MockRoute tap Simulate
    here. A dialog explains the path and "Open Developer options" works. No crash. Re-select MockRoute afterwards.
13. Settings (gear) → Check setup reopens the wizard; the Google Play services switch is present.
14. Note anything odd (flicker back to the real location, the service killed in the background, style fallback,
    etc.) under "Known issues".
