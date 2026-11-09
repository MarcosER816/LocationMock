# Handoff

_Last updated: 2026-11-09 — Stage 0 (skeleton & CI)._

## Done
- Gradle Kotlin DSL multi-module project with `gradle/libs.versions.toml` and the Gradle wrapper (executable bit set
  in git).
- `:core` — pure Kotlin/JVM; kotlinx-serialization, coroutines-core, commons-math3; JUnit 5 + `kotlin.test`;
  placeholder `Core` object + `CoreTest` (2 tests).
- `:app` — `com.mockroute.app`, Compose BOM + Material 3 (dynamic color), minSdk 26, compile/targetSdk 36, Java/Kotlin
  target 17; `MainActivity` shows "MockRoute" + version; depends on `:core`.
- Stable debug signing: `app/debug.keystore` (alias `androiddebugkey`, store/key password `android`, PKCS12, valid
  30 years). Cert SHA-256: `2D:F1:1D:87:1F:E8:61:E9:2C:B7:5B:90:79:2E:07:2F:A5:F1:7D:7B:63:0D:1C:8D:B5:12:C6:21:5B:8C:15:8E`.
- Optional API keys `ORS_API_KEY`, `TOMTOM_API_KEY`, `GRAPHHOPPER_API_KEY`, `GOOGLE_ROUTES_API_KEY` →
  `BuildConfig` (env var / GitHub secret → `local.properties` → `""`). Missing keys never fail the build.
- `.github/workflows/android.yml`: checkout → JDK 17 (Temurin) → setup-gradle → `:core:test :app:assembleDebug` →
  artifact `app-debug` (30 days); `:core` test reports uploaded on failure.
- `README.md` phone-only install guide; `CLAUDE.md` brief; `docs/ROADMAP.md`.

## Versions
| Component | Version | Note |
|---|---|---|
| Gradle (wrapper) | 9.9.0 | |
| Android Gradle Plugin | 9.5.1 | Built-in Kotlin support (no `kotlin-android` plugin) |
| Kotlin (+ Compose & serialization plugins) | 2.4.21 | |
| Compose BOM | 2026.06.01 (Compose 1.11.4, Material 3 1.4.0) | 2026.08.00+ requires compileSdk 37 |
| androidx.core:core-ktx | 1.18.0 | 1.19.x requires compileSdk 37 |
| androidx.activity:activity-compose | 1.13.0 | |
| androidx.lifecycle | 2.10.0 | 2.11.x requires compileSdk 37 |
| kotlinx-serialization-json | 1.12.0 | |
| kotlinx-coroutines-core | 1.11.0 | |
| commons-math3 | 3.6.1 | |
| JUnit (BOM) | 6.1.3 | |
| JDK | 17 (CI, Temurin) | Bytecode target 17; also builds on JDK 21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | |

## Stubbed
- `Core` object is a placeholder (name constant + km/h→m/s helper).
- Launcher icon uses a system drawable; no mock-location code, permissions or services yet (Stage 1).

## Known issues
- Newest AndroidX releases (Compose 1.12, core 1.19, lifecycle 2.11) demand compileSdk 37. We stay on 36 per the
  brief; when moving to 37, bump `compileSdk` and those versions together.
- In the cloud agent sandbox, Maven Central sometimes returns HTTP 429; just re-run Gradle.

## Next steps
- Stage 1 — map + mock service MVP (see `docs/ROADMAP.md`).

## Build steps
- Agent sandbox: `./gradlew :core:test` (no Android SDK needed). For `:app:assembleDebug`, install
  `platforms;android-36` + `build-tools;36.0.0` with sdkmanager and point `sdk.dir` in `local.properties` at it.
- CI (source of truth): every push / PR / manual run builds the `app-debug` artifact.
- Verified 2026-11-09 in the agent sandbox: `:core:test` 2/2 passed; `:app:assembleDebug` succeeded and the APK is
  signed with the committed keystore.

## How to verify on phone
1. GitHub app/website → repo → Actions → latest "Android CI" run is green.
2. Download artifact `app-debug` → unzip in File Manager → install `app-debug.apk` (see `README.md`).
3. Open MockRoute: it shows "MockRoute" and "v0.0.1 · Stage 0".
4. Install a later CI build: it should update in place (same signature), no uninstall needed.
