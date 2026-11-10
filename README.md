# MockRoute

Personal Android app that simulates the device's GPS location (routes, zones, timelines) through Android's
mock-location framework. For testing and privacy use only — other apps can detect mock locations.

Built entirely in GitHub Actions; installed from the phone. See `docs/ROADMAP.md` for progress.

## Install from your phone (no computer needed)

### 1. Get the APK
1. Open the repo on GitHub (app or browser) → **Actions** → **Android CI**.
2. Open the latest run with a green check ✅.
3. Under **Artifacts**, tap **app-debug** to download `app-debug.zip`
   (in the GitHub mobile app you may need "Open in browser"; you must be signed in).

### 2. Unzip and install
1. Open **File Manager** → **Downloads** → tap `app-debug.zip` → **Extract**.
2. Tap `app-debug.apk` → **Install**. If asked, allow "Install unknown apps" for File Manager/your browser.
3. Later builds install over the old one (same debug signature) — no need to uninstall.

### 3. Turn on Developer options (once)
**Settings → About phone →** tap **OS version** 7 times (enter your lock screen PIN if asked) until
"You are now a developer" appears.

### 4. Select MockRoute as mock location app
**Settings → Additional settings → Developer options → Select mock location app → MockRoute**.

### 5. HyperOS: keep it running in the background
- **Settings → Apps → Manage apps → MockRoute → Autostart: ON**.
- **Battery saver** (same app page) → **No restrictions**.
- Open **Recents**, long-press (or swipe down on) the MockRoute card → **Lock** 🔒 so it is not killed.

## Using it (Stage 1)
1. On first launch, follow the setup wizard until every step is green (Settings ⚙ → **Check setup** reopens it).
2. The map shows your real location (blue). **Long-press** anywhere → **Simulate here** (stay at that point) or
   **Walk straight to here** (≈ 5 km/h from where you are now).
3. Control it from the card at the bottom or the notification: **Pause / Resume / Stop**. Stop (or swiping MockRoute
   out of Recents) gives you your real location back within ~30 s.

## Routes (Stage 2)
Use the toolbar at the top of the map:
- **A → B**: A starts at your location; tap the map to set B, and drag A/B to adjust. The route follows streets
  for Walk / Car / Bus.
- **Streets**: tap point after point; each step is routed along the streets. **Undo** / **Clear**.
- **Free draw**: same as Streets, but with straight lines (no snapping).
Then **Play at constant speed** moves your mocked location along the route.

## Plans (Stage 4)
Once the speed & time settings are feasible, the route is coloured by speed: green = slow, yellow = average,
red = fast. A label shows the total time. Tap the line to see the speed and ETA at that spot. **Re-roll**
generates a different plan; **Lock time** keeps the duration fixed. **Play plan** moves your mocked location along
the route with that speed profile.

## Indoor zone (Stage 6)
Toolbar → **Indoor**:
1. **Shape:** tap the corners of a room or building and tap **Close shape**.
2. **Entry A / Exit B:** tap near the walls; A and B always sit on the edge.
3. **Hotspots:** mark where you tend to be (drag circles, adjust their size and rotation). **3D preview** shows
   the result as a 3D surface.
4. **Play zone:** you enter at A, wander around (pausing sometimes) and leave at B after the planned time.

## Traffic (Stage 5, Car/Bus)
With a free TomTom key (Settings → Traffic), Car and Bus routes get typical traffic for your departure time. Tap
**Depart …** to choose it. The app predicts the trip duration, marks the worst congestion points in red
("≈ 08:14, 14 km/h, +4 min") and fills in the speed & time settings automatically; you can still edit them. One
prediction uses at most 12 of the 2,500 free daily TomTom requests, and is reused for 7 days.

## Speed & time (Stage 3)
In the route sheet tap **Speed & time…**. Set the speed range (km/h) and the time range (h:mm), and shape each one
with a mean/mode and a "shape" (higher = narrower), shown as live curves. Pick an acceleration preset or type
a value. A green banner means the segment is possible. A red one explains why it isn't and offers two linked
sliders (top speed ↔ max time) plus **Apply**.

## Optional API keys (stored only on your phone)
Street routing needs a free key, pasted in **MockRoute → Settings ⚙ → Routing** (saved as you type, kept only on
the phone; never in this repository or the APK):
- **OpenRouteService:** sign up at openrouteservice.org (HeiGIT) and copy your key. Use **Test routing** to check it.
- **GraphHopper** (alternative): graphhopper.com → API key (free: 500 credits/day, non-commercial).

Without a key, the app still works with straight-line routes.

## Developing
- `./gradlew :core:test` — pure-Kotlin logic tests (no Android SDK needed).
- `./gradlew :app:assembleDebug` — needs an Android SDK (platform 36); CI does this on every push.
- Agents: read `CLAUDE.md`, `docs/ROADMAP.md` and `docs/HANDOFF.md` first.
