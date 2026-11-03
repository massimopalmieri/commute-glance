# Validation — 3 November 2026

## Completed

- Go unit tests with the race detector (`go test -race ./...`).
- Go static analysis (`go vet ./...`) and native executable build.
- Tests cover cache reuse and expiry, stale fallback without resetting timestamps, bus route filtering/sorting, bearer authentication, invalid inputs, missing rail configuration, upstream failures, rail endpoint construction, cancellation/platform suppression, midnight rollover and British Summer Time.
- Default bus stop verified against live TfL StopPoint data: Haling Park Gardens, GW, northbound, NaPTAN 490007669N.
- Live stop search verified; stop groups are excluded so users select a directional stop.
- Live Go backend request to `/v1/buses?stop=490007669N&routes=166,405` returned four real predictions across both routes.
- Android debug APK build, Kotlin/JVM tests and lint. Tests cover commute windows, summer time, elapsed predictions, cancelled trains and explicit delay labels.

## Limits

- Physical Pixel 7 on Android 17 tested over wireless ADB; no emulator test.
- Default widget sizing, running countdown/data age, notification posting/expansion and compact lock-screen icon tested on Pixel. Doze, reboot, large font scaling, resizing and real scheduled-window transitions still need longer observation.
- Rail adapter tested with fixtures and a live Rail Data Marketplace subscription. Cancellation cases remain fixture tests; no live cancellation was required or fabricated.
- Pi hosting and private Tailscale HTTPS deployed and verified from the Mac and Pixel on home Wi-Fi. Cellular/away-from-home connectivity and Home Assistant integration remain untested.
- Lint has non-blocking warnings for English text/localisation and Android data extraction rules; no lint errors. Backups are disabled in the application manifest.

The initial APK is a development build. Do not interpret best-effort refresh as a guaranteed live arrival display. Read freshness labels and refresh if needed.

## Local Mac / physical Pixel / Pi session

- Found a clean existing checkout at `~/Code/commute-glance`; no local changes to preserve.
- Mac Go 1.23.2 automatically selected Go 1.24.0 via the module/toolchain setting. Go race tests, vet and native/ARM64 Linux builds passed.
- Android tests, lint and debug build passed with Android Studio Java 21.0.9. Existing lint/deprecation warnings remain; no lint errors.
- Installed repeatedly with `adb install -r`; app data preserved. Final APK includes redesigned graphite/blue native departure boards, grouped settings, and adaptive/themed launcher icon.
- Confirmed real TfL predictions for 166 towards West Croydon and 405 towards Croydon Town Centre at stop 490007669N. Mac loopback backend worked through wireless `adb reverse`.
- Added Pixel home-screen widget. Found clipped rows at its default size; compact rows now fit bus predictions, fetch label and rail configuration status. Countdown and data-age Chronometers advance without network requests.
- Tested connectivity loss by removing reverse forwarding. Cached board retained its timestamp, displayed STALE and an unavailable-refresh message. Restoring forwarding and tapping the widget refresh fetched a newer timestamp.
- Tested expiry on the phone by temporarily aging a real cached board's fetched timestamp beyond 600 seconds, with forwarding removed. The app suppressed departure rows and displayed Expired / Refresh required. This was a controlled cache test, not a ten-minute Doze/outage test. Test settings/cache were replaced with fresh live data.
- Notification permission granted by the user. Verified notification 42 uses LOW importance, no sound or vibration, public visibility and a commute-end timeout. Expanded notification shows real cached departures and missing rail access. Enabled Android's Show silent notifications; bus icon visible in the existing compact lock-screen layout. Full lock-screen expansion still depends on system layout/user interaction.
- Found that reopening an existing activity could retain old data without fetching. Added foreground refresh; verified fresh arrivals after reopening.
- Deployed a cross-compiled ARM64 backend to the Pi as boot-enabled `commute-glance.service`, with loopback binding, root-only random API token and Tailscale Serve HTTPS port 10443. Existing Pi services were preserved. Authenticated bus request succeeded; request without token returned 401.
- Connected Tailscale on the Pixel, configured Pi HTTPS URL and token directly into private app settings, and confirmed fresh real arrivals with ADB reverse removed. Credentials were not printed, embedded in the APK or committed.
- Temporary all-day test windows were restored to Monday/Thursday, 07:00–08:30 and 16:00–19:00, 3-minute walk. Notifications remain opted in; outside windows the notification is removed and widget is inactive. Screen timeout restored after UI testing.
- National Rail subscription and both live directions are connected. Cellular handover, scheduled-window timing, Pi reboot recovery and extended screen-off behaviour remain outstanding.

## Widget redesign follow-up

- Replaced the text block with a branded header, a large estimated countdown and route badge, separate departure rows, rail status, and a fixed data-age footer. The selected estimate is not repeated in the smaller rows.
- Added an off-hours view showing the next configured commute start in Europe/London, including the direction and saved stop/routes. Tests cover both daily windows, selected weekdays and the autumn clock change.
- Widget options changes trigger a re-render. Shorter widgets and larger font settings use fewer departure rows. Verified active and off-hours rendering on the existing Pixel widget at its current size and 1.15 font scale; neither layout clipped.
- Tapped the widget refresh control and confirmed a successful refresh worker, a newer real TfL fetch timestamp from the Pi, and retained home-screen focus. Countdown and fetch-age counters continue ticking without claiming new network data.
- Final Android unit tests, lint and debug assembly passed. Installed with `adb install -r`. Temporary active-window test settings were restored to the saved Monday/Thursday schedule and Pi connection/token were preserved.


## Live rail connection

- User activated the free Rail Delivery Group **Live Departure Board** subscription in Rail Data Marketplace. The pre-existing NationalRail Disruptions API subscription is a different feed.
- Current subscribed specification requires `x-apikey`, not the legacy direct-LDBWS Basic Authorization scheme. Added `RAIL_API_KEY` support with precedence over the optional legacy `RAIL_AUTHORIZATION`. The HTTPS base includes the gateway product prefix and `/LDBWS`.
- Transferred only the consumer/API key into the Pi's root-only environment. No consumer secret is required for this endpoint. Keys were not printed in chat, committed or embedded in the APK; the phone's backend token was unchanged.
- Verified authenticated upstream requests and normalised Pi responses in both directions. At 21:51 on 3 November, the Pi returned 16 ECR→LBG and 17 LBG→ECR services meeting the scheduled-duration limit of 20 minutes. Confirmed a Southern service scheduled at 21:38, expected at 21:51, platform 4, retained with its normal 17-minute journey despite a 13-minute departure delay.
- Sorted normalised rail rows by expected departure, falling back to scheduled time when no prediction exists. Scheduled journey duration still controls eligibility. Added a delayed-fast-service ordering regression test.
- Added tests for API-key endpoint construction, credential precedence, rail keys never being sent to the TfL request, and upstream auth failures not echoing credentials to clients. Legacy direct-LDBWS tests still pass.
- Physical Pixel displayed live morning rail summaries and the London Bridge→East Croydon board, including platforms, missing-platform indicators and explicit delay labels. The widget was checked with real rail data; its short rail line now retains expected time, journey duration, platform and delay without overflowing at the current size.
- Go tests with race detector, vet and ARM64 build passed. Android tests, lint and debug build passed; APK updated without clearing app data. Temporary active-window settings were restored after visual checks.
