# Handoff

_Last updated: 2026-11-09 — Stage 2 (routing & tracing) — built, CI; awaiting phone test. Stage 1 verified on device._

## Done
### Stage 2 — routing & tracing
- **Mode toolbar** (top of map): Explore | A → B | Streets | Free draw.
  - **A → B (point-to-point):** A defaults to the last real location. Tapping the map sets B. A and B are
    draggable Compose overlay handles (`EndpointHandles`/`DragHandle`): the drop point is converted with
    `CameraProjection.positionFromScreenLocation`. "Move A" makes the next tap set A, and "A = my location" resets it.
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
- **Keys:** a key saved in Settings (DataStore) wins. Otherwise the build-time `BuildConfig` key from the GitHub
  secret is used (`ORS_API_KEY`, `GRAPHHOPPER_API_KEY`), which is baked into the debug APK (fine for a personal
  sideloaded app).
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
- The ORS/GraphHopper fixtures in `core/src/test/resources/fixtures` are hand-built from the documented formats
  (no key in the agent sandbox). Error bodies were checked against the live APIs. If a real response ever fails to
  parse, save it as a new fixture.
- The tall segment sheet covers part of the map on small screens.

## Next steps
- Run the Stage 2 phone test script below.
- Stage 3: distributions + feasibility UI (use `RouteResult.distanceM`, `RouteGeometry.densify()`).

## Build steps
- Agent sandbox: `./gradlew :core:test` (no Android SDK needed). For `:app:assembleDebug`, install
  `platforms;android-36` + `build-tools;36.0.0` with sdkmanager and point `sdk.dir` in `local.properties` at it.
  Maven Central sometimes returns HTTP 429 in the sandbox; re-run.
- CI (source of truth): every push / PR / manual run builds the `app-debug` artifact.
- Verified 2026-11-09 in sandbox: `:core:test` 24/24 passed; `:app:lintDebug` 0 errors; `:app:assembleDebug` OK.

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
