# Sentinel Design System

## Product
Sentinel is an Android-first device recovery app. An account holder links phones, explicitly enables visible location sharing, sees each phone's most recently reported location, opens it in a map app, and can mark a phone lost. Never imply live tracking when a device has stopped reporting. Never imply protection from factory reset or firmware changes.

## Platform and architecture
- Native Android app built with Jetpack Compose and Material 3.
- One screen with signed-out account creation/sign-in and signed-in device management states.
- Location sharing runs through an Android foreground service with an ongoing notification.
- Device locations refresh periodically; stale devices show their last report.

## Visual foundations
- Brand primary: deep teal `#126B62`.
- Secondary accent: warm clay `#B36A3C`.
- App background: soft gray-green `#F5F7F6`.
- Surfaces: white `#FFFFFF`.
- Use Material 3 typography, accessible contrast, an 8 dp spacing rhythm, and generous touch targets.
- Tone: calm, clear, trustworthy, and practical. Use plain language and restrained status colors.

## Important UI states
- Signed out: email, password, create account/sign-in action, and mode toggle.
- Signed in: account/device controls, linked-device list, explicit link-phone action, and location-sharing start/stop controls when this phone is linked.
- A device can be reporting, offline/stale, marked lost, or waiting for its first location. Show the report timestamp and map action only when coordinates exist.
- Avoid invented location names, device models, or live-status claims when those values are not supplied by the app.
