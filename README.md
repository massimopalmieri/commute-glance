# Commute Glance

A private Android commute companion: live London buses, fast trains, a home-screen widget and an optional silent lock-screen notification. Kotlin Android app + Go service suitable for a Raspberry Pi.

## What's implemented

- TfL live bus arrivals, stop search and route filters. Default: Haling Park Gardens **GW**, NaPTAN **490007669N**, routes **166 / 405**.
- Configurable walking time (3 minutes), weekdays, morning and afternoon windows, backend URL and token.
- Morning bus + East Croydon → London Bridge boards; afternoon London Bridge → East Croydon board. Manual switching anytime.
- National Rail LDBWS JSON adapter, platforms, cancellations, predictions and scheduled-duration filtering (20 minutes or less). Delays do not turn a normally fast train into an excluded slow service.
- Home widget with a host-rendered estimated countdown for the next catchable service, fixed predicted clock times and a running age-of-data counter. Negative countdowns explicitly mean the prediction has elapsed, not that departure is confirmed.
- Silent opt-in lock-screen notification during configured hours; tap to open and refresh. Android settings determine visibility and expansion.
- 15-minute best-effort background jobs; 30-second server cache; cached fallback on provider errors for at most 10 minutes. No synthetic departure data is presented as live.
- Backend bearer authentication, bounded upstream reads, timeouts and no secrets in the APK.

## First run on your laptop

Install Git, Go 1.24+ and Android Studio with JDK 17, Android SDK 35 and Build Tools 35.0.0. Gradle 8.11.1 is pinned and included via the wrapper.

```sh
git clone git@github.com:massimopalmieri/commute-glance.git
cd commute-glance/backend
go test ./...
go run .
```

The server binds to `127.0.0.1:8080`. TfL buses work without rail credentials. A TfL app key is optional (`TFL_APP_KEY`) and may be needed for your usage quota. Requests should respect the providers' current terms.

Connect your Pixel by USB, enable USB debugging and approve your laptop on the phone. In another terminal, using Android SDK platform-tools:

```sh
adb reverse tcp:8080 tcp:8080
cd commute-glance/android
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. Alternatively open `android/` in Android Studio and use Run. In the app's Settings enter **http://127.0.0.1:8080** as the backend URL (USB reverse forwarding), leave the token blank for this loopback-only development server and save. Use **Add home-screen widget**. Enable the notification if wanted and grant permission. HTTP is enabled only in debug builds; release builds require HTTPS.

The app opens with live configuration, not a demo mode. It needs a running reachable backend. `adb reverse` lasts only while connected; it is not the away-from-home deployment solution.

## Rail setup

Subscribe to National Rail's **LDBWS Public JSON** feed through Rail Data Marketplace. The adapter implements the published 20220120 LDBWS API:

- `RAIL_BASE_URL`: HTTPS base before `/api/20220120/GetDepBoardWithDetails/{crs}`.
- `RAIL_AUTHORIZATION`: complete Authorization header specified by that subscription (the published direct API describes Basic authentication).

Different RDM products/gateways can require different authentication or paths. Check the subscription documentation; this is not an adapter for arbitrary Darwin SOAP, Staff or push feeds. Rail has fixture tests but has not been verified using a live subscription. Until configured, the app clearly says rail is unavailable while buses continue to work.

## Raspberry Pi deployment

```sh
cp .env.example .env
# Edit .env: replace API_TOKEN with a random value (openssl rand -hex 32).
docker compose up -d --build
```

The container is non-root; the published port is loopback-only. Use a trusted HTTPS reverse proxy over your private network/VPN to reach it from the phone, and enter the same `API_TOKEN` in the app. Do not publish the port directly to the internet. The app needs reachability when away from home; configure that separately. Secrets belong in `.env`, which is ignored by Git. Go also runs directly under systemd if preferred; set `LISTEN_ADDR` and a strong `API_TOKEN` for non-default bindings.

## API (also suitable for future Home Assistant clients)

Send `Authorization: Bearer <API_TOKEN>` if a server token is configured.

- `GET /healthz`
- `GET /v1/stops?q=Haling%20Park`
- `GET /v1/buses?stop=490007669N&routes=166,405`
- `GET /v1/trains?from=ECR&to=LBG&maxMinutes=20`

Boards expose `fetchedAt`, optional `sourceAt`, `stale`, `warning` and `departures`. Each departure has absolute expected/scheduled timestamps where known. Missing predictions stay missing; cancelled trains never have an expected countdown. Re-requesting within 30 seconds returns the existing timestamp, not fake freshness.

## Android limitations and physical-device checks

Android can delay periodic work. Commute windows are evaluated when work executes, not with exact alarms, so automatic start/switch timing is approximate. Opening the app or tapping Refresh requests fresh data immediately, subject to connectivity and the short backend cache. No permanent foreground service or battery-exemption workaround is used.

A host Chronometer can tick without fetching data, but cannot execute arbitrary application logic when it reaches zero. The widget therefore explicitly labels a negative countdown as an elapsed prediction, shows fixed expected clock times and displays a continuously advancing age counter. Rows and expired-data messages are recalculated at the next app/widget update. Data older than 90 seconds is stale; over 10 minutes it is suppressed when rendered. The age counter remains the authoritative indicator between updates. Lock-screen content is a dated snapshot and refreshes on the same best-effort schedule.

Before relying on it, test on the Pixel: font scaling/widget resizing, notification permission and lock-screen settings, a real morning/afternoon window, screen-off/Doze, reboot, an upstream outage, clock/timezone changes and more than ten minutes without connectivity. The app uses Europe/London for scheduling and supports BST. Physical Pixel 7 checks were completed on 3 November 2026. See docs/VALIDATION.md for the tested cases and remaining limits.

CI tests Go and builds/lints/tests Android, uploading a debug APK as `commute-glance-debug`. Debug signing keys differ between machines/CI; switching installation sources may require uninstalling the previous build, which clears app settings. Use the same laptop for ongoing development.

See [Pi deployment](docs/PI.md), [Mac development fallback](docs/MAC_PIXEL.md), [project requirements](docs/PROJECT.md) and [validation notes](docs/VALIDATION.md).
