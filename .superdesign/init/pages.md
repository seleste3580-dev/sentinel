# Screens and Dependencies

## Signed-out / Signed-in (MainActivity)
Entry: `android-app/src/main/java/dev/sentinel/app/MainActivity.kt`
Dependencies:
- `android-app/src/main/java/dev/sentinel/app/TrackerApi.kt` — network results and device fields
  - `android-app/src/main/java/dev/sentinel/app/SentinelNative.kt` — Rust coordinate validation
- `android-app/src/main/java/dev/sentinel/app/SecureSession.kt` — encrypted session state
- `android-app/src/main/java/dev/sentinel/app/LocationReporterService.kt` — foreground location reporting
  - `android-app/src/main/java/dev/sentinel/app/TrackerApi.kt`
  - `android-app/src/main/java/dev/sentinel/app/SecureSession.kt`
- AndroidX Compose Material 3 primitives
