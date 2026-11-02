# Validation — 2 November 2026

## Completed

- Go unit tests with the race detector (`go test -race ./...`).
- Go static analysis (`go vet ./...`) and native executable build.
- Tests cover cache reuse and expiry, stale fallback without resetting timestamps, bus route filtering/sorting, bearer authentication, invalid inputs, missing rail configuration, upstream failures, rail endpoint construction, cancellation/platform suppression, midnight rollover and British Summer Time.
- Default bus stop verified against live TfL StopPoint data: Haling Park Gardens, GW, northbound, NaPTAN 490007669N.
- Live stop search verified; stop groups are excluded so users select a directional stop.
- Live Go backend request to `/v1/buses?stop=490007669N&routes=166,405` returned four real predictions across both routes.
- Android debug APK build, Kotlin/JVM tests and lint. Tests cover commute windows, summer time, elapsed predictions, cancelled trains and explicit delay labels.

## Limits

- No emulator or physical Pixel UI execution yet; compile and lint are not device validation.
- Lock-screen notification presentation, widget sizing, countdown rendering, Doze, reboot and delayed background scheduling need testing on the Pixel.
- Rail adapter tested with fixtures against the published National Rail LDBWS JSON schema; live subscription credentials are not available here.
- Pi hosting, private HTTPS connectivity away from home and Home Assistant integration are not deployed.
- Lint has non-blocking warnings for English text/localisation and Android data extraction rules; no lint errors. Backups are disabled in the application manifest.

The initial APK is a development build. Do not interpret best-effort refresh as a guaranteed live arrival display. Read freshness labels and refresh if needed.
