# Handoff

_Last updated: 2026-11-09 — Stage 1 (map + mock service MVP)._

## Done
### Stage 1
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
- Only Static and StraightLine trajectories; no routing, distributions or noise yet (Stages 2–9).
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
- Not verified on a device yet (no emulator in the agent sandbox: no KVM). Lint: 0 errors; warnings are cosmetic
  (Timber, `toUri`, targetSdk 37 available).

## Next steps
- Run the phone test script below and record the results here.
- Stage 2 — routing & tracing (`RoutingProvider`: ORS default, GraphHopper alt; snapped street tracing; cache).

## Build steps
- Agent sandbox: `./gradlew :core:test` (no Android SDK needed). For `:app:assembleDebug`, install
  `platforms;android-36` + `build-tools;36.0.0` with sdkmanager and point `sdk.dir` in `local.properties` at it.
  Maven Central sometimes returns HTTP 429 in the sandbox; re-run.
- CI (source of truth): every push / PR / manual run builds the `app-debug` artifact.
- Verified 2026-11-09 in sandbox: `:core:test` 10/10 passed; `:app:lintDebug` 0 errors; `:app:assembleDebug` OK.

## How to verify on phone (Stage 1 test script)
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
