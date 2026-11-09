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

## Optional API keys
Routing/traffic providers can use keys stored as GitHub repository secrets
(**Settings → Secrets and variables → Actions**): `ORS_API_KEY`, `TOMTOM_API_KEY`, `GRAPHHOPPER_API_KEY`,
`GOOGLE_ROUTES_API_KEY`. All are optional; the build succeeds and the app works (walk mode, straight-line routes)
without them. Keys can also be pasted into the app's Settings later.

## Developing
- `./gradlew :core:test` — pure-Kotlin logic tests (no Android SDK needed).
- `./gradlew :app:assembleDebug` — needs an Android SDK (platform 36); CI does this on every push.
- Agents: read `CLAUDE.md`, `docs/ROADMAP.md` and `docs/HANDOFF.md` first.
