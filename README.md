# Sentinel

Android-first anti-theft location sharing starter. The Android client is Kotlin/Jetpack Compose, with a C++ JNI bridge to a Rust native core. The Rust service stores accounts, linked devices, and each device's latest consented location.

## Current capabilities

- Create an account or sign in with email and password, or use Sign in with Google through Android Credential Manager.
- Link the current Android phone as a device.
- Start and stop location reporting through an Android location foreground service with a visible notification.
- View each linked device's latest reported coordinates and timestamp, open them in the phone's map app, and mark a device lost.
- Keep the session token encrypted in Android Keystore-backed preferences. Passwords are Argon2-hashed by the service; session tokens are stored as SHA-256 digests.
- Validate location coordinates in the Rust core before upload.

## Run the API locally

Install Rust and SQLite support, then from this directory run:

```sh
BIND_ADDR=0.0.0.0:8080 cargo run --bin sentinel-server
```

The service creates `sentinel.db` in the working directory. Google sign-in requires a Google Cloud OAuth Web client ID. Set that ID as `GOOGLE_CLIENT_ID` for the API and pass the same value as `-PsentinelGoogleWebClientId=<web-client-id>` when building Android. Configure the Android OAuth client in the same Google project using the app package name and signing certificate fingerprints. Do not put a Google client secret in the Android app. If the ID is not configured, the Google button explains the missing setup and the API rejects Google sign-in.

The Android debug build uses `http://10.0.2.2:8080`, which reaches the host machine from an Android emulator. For a physical phone, set the debug `API_BASE_URL` in `android-app/build.gradle.kts` to the host machine's LAN address and keep the phone and host on the same trusted network.

## Build the Android app

Open this directory in Android Studio with Android SDK 35, CMake 3.22.1, and the Android NDK installed. Install [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) and add Rust targets for `arm64-v8a`, `armeabi-v7a`, and `x86_64`; Gradle invokes it to build the native Rust library before CMake builds the C++ JNI bridge.

For a release build, deploy the API behind HTTPS and pass its origin to Gradle:

```sh
./gradlew :android-app:assembleRelease -PsentinelApiBaseUrl=https://your-api.example -PsentinelGoogleWebClientId=<web-client-id>
```

Release builds reject cleartext HTTP. The HTTP exception is limited to the debug manifest for local development.

To build a debug APK for the Android emulator after installing SDK 36, NDK `27.0.12077973`, CMake `3.22.1`, and `cargo-ndk` with the Rust Android targets, run:

```sh
./gradlew :android-app:assembleDebug -PsentinelGoogleWebClientId=<web-client-id>
```

The APK is written to `android-app/build/outputs/apk/debug/android-app-debug.apk`. For a physical phone on the same trusted LAN as the API, pass `-PsentinelDebugApiBaseUrl=http://<computer-lan-ip>:8080`. A release APK also needs a signing key before it can be distributed or installed as a production update.

## Hosting choices

### VPS or AWS Lightsail/EC2 (works with the included SQLite service)

Use a small Linux VM with Docker and Docker Compose. Point a domain such as `api.example.com` at the VM, allow inbound TCP ports 80 and 443, copy `.env.example` to `.env`, set `SITE_ADDRESS` to the domain, then run:

```sh
docker compose up --build -d
```

The included Caddy container obtains and renews HTTPS certificates. SQLite and Caddy state persist in named Docker volumes. This is the simplest deployment for this MVP; keep it as one API instance and back up the `sentinel-data` volume.

This same Compose deployment can run on an AWS EC2 VM or [AWS Lightsail VM](https://docs.aws.amazon.com/lightsail/latest/userguide/getting-started-with-amazon-lightsail.html), and on VPS providers such as DigitalOcean, Linode/Akamai, Vultr, or Hetzner. Keep one API instance and retain the VM's disk snapshots/backups.

### Render, Railway, or Fly.io (single container with a persistent disk)

`render.yaml` is included for Render; attach its persistent disk at `/data`. Render's local filesystem is otherwise ephemeral, and persistent disks are available on paid web services ([Render disk documentation](https://render.com/docs/disks)). Railway does not run Compose files directly, so create a service from the repository's `Dockerfile` and attach a Railway volume at `/data` ([Railway volume documentation](https://docs.railway.com/volumes)). Fly.io can run the same container with a volume mounted at `/data` ([Fly volume documentation](https://www.fly.io/docs/flyctl/volumes/)).

For any of these, set `DATABASE_URL=sqlite:///data/sentinel.db?mode=rwc` and `BIND_ADDR=0.0.0.0:<the platform port>`. Use the platform's HTTPS domain and build the Android release with that HTTPS API URL. Keep a single service instance because SQLite is file-backed.

### AWS App Runner or ECS Fargate (requires a database migration to scale)

These are better for managed, replaceable container tasks, but this version keeps data in SQLite. Fargate task filesystems are ephemeral by default; AWS supports persistent EFS volumes, but a shared SQLite file is still the wrong fit for multiple API tasks. Before scaling, migrate SQLx storage to PostgreSQL (for example, Amazon RDS or Aurora PostgreSQL), add migrations, and move the database URL/secret into AWS Secrets Manager. See [ECS storage choices](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/using_data_volumes.html). The current SQLite container should not be deployed as a multi-replica service.

The Android APK does not need to run on the hosting provider. It is built separately and calls the service over HTTPS.

## Platform limits

This app does not install code in the Linux kernel, modify the bootloader, or access carrier IMEI-location systems. Android's normal app sandbox cannot prevent a factory reset, app removal, or firmware flash from removing the tracker. Android Enterprise device-owner controls can restrict factory reset from Android settings on a fully managed, enrolled device, but they do not make a normal consumer app unflashable or stop recovery/bootloader reflashing. Real resistance to reflashing depends on the device maker's locked bootloader, verified boot, and provisioning/FRP policies. IMEI is not a location signal, and ordinary apps cannot use it to query a phone's position. Carrier-side IMEI records require carrier or lawful authority access.

Location reports require the app to remain installed, location permission, power, and a network connection. If reports stop, the dashboard shows the last report rather than claiming live location. A device that is offline, powered down, reset, or no longer running the app cannot send a new location.

## Before production deployment

This is an MVP foundation, not a deployed tracking service. Production still needs HTTPS hosting, email verification and account recovery, abuse/rate controls, database backups and migrations, a location retention/deletion policy, operational monitoring, and privacy/legal review for the regions where it is offered. Keep location sharing explicit and visible; use this only on devices you own or are authorized to manage.
