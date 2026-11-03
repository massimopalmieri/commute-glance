# Commute Glance

Status: installed and tested on a physical Pixel 7 with a redesigned native interface and adaptive launcher icon. Backend deployed to the Raspberry Pi over private Tailscale HTTPS. See docs/VALIDATION.md for evidence and remaining device tests. Live rail credentials remain outstanding.

## Goal
A minimal native Android departure board, home-screen widget and optional silent lock-screen notification. Target device: Google Pixel 7, kept up to date. Prioritise glanceable information without opening an app; always communicate freshness honestly.

## Agreed requirements
- Kotlin Android app and widget; Go backend suitable for Raspberry Pi.
- Selectable bus stop and route filters.
- Default stop: Haling Park Gardens, stop GW, northbound on Haling Park Road. Routes 166 towards West Croydon and 405 towards Croydon. Verified via live TfL lookup: NaPTAN 490007669N, stop GW.
- Walking time: 3 minutes, editable. Show earlier buses but highlight realistically catchable ones.
- Monday/Thursday mornings: bus departures plus fast East Croydon to London Bridge trains. Morning trains were explicitly requested for Monday; including Thursday is a proposed default.
- Monday/Thursday afternoons: fast London Bridge to East Croydon trains, including platforms, expected times, delays and cancellations.
- Proposed configurable active windows: 07:00–08:30 and 16:00–19:00, Europe/London timezone with daylight-saving handling.
- Fast trains: scheduled journey duration <=20 minutes; include suitable services via Norwood Junction. Keep normally fast services visible when delayed.
- Manual board selection outside scheduled windows.
- Countdown from absolute predicted arrival timestamps between network fetches. Label cached estimates with source fetch time; never present an extrapolated countdown as newly fetched data. Avoid indefinite Due labels or claiming departure based solely on elapsed prediction time.
- Tap opens a minimal board and fetches fresh information; optional widget refresh action.

## Architecture
- android/: Kotlin application, settings, home-screen widget and optional lock-screen notification.
- backend/: Go transport adapters, normalised departures API, in-memory cache and configuration. No database initially.
- docs/: requirements, architecture decisions and setup instructions.
- Future Home Assistant integrations consume the same backend API.
- Transport sources: TfL Unified API and National Rail Darwin via Rail Data Marketplace. Rail credentials/access must be arranged.
- Keep credentials outside version control; provide example configuration only.
- Decide authenticated remote access to the Pi before relying on the service away from home. Do not expose an unauthenticated endpoint publicly.

## Android feasibility work first
Standard widget periodic updates have a 30-minute minimum; periodic WorkManager jobs have a 15-minute minimum and are not punctual guarantees. A server alone does not remove these constraints. Prototype host-rendered countdowns and test actual Pixel behaviour, stale-state transitions, reboot, Doze, notification settings and refresh actions. Do not promise automatic minute-by-minute fetching or refresh on every unlock. Do not misuse exact alarms or high-priority push to evade platform restrictions.

## Implementation sequence
1. Create Android scaffold with clearly labelled sample departures; verify widget, countdowns, refresh and optional silent lock-screen notification on the Pixel.
2. Implement Go TfL adapter, configurable stop/route filtering, cache and freshness contract; meaningful tests for expiry, upstream failures and filtering.
3. Connect Android to live bus data. Provide stop selection and commute settings.
4. Add Darwin adapter and duration-filtered rail boards; test cancellation, missing platform and delay cases.
5. Package Pi deployment and document secure remote access; later add Home Assistant support.

## Laptop handoff
Clone this private repository using your authenticated GitHub account. Install Android Studio and its recommended Android SDK/JDK, plus Go and Git. Exact build-tool versions will be pinned when the scaffold is created.

When code exists, open android/ in Android Studio, enable Developer options and USB debugging on the Pixel, connect it by USB, approve the laptop on the phone, then use Run to build and install the debug app. No Play Store publication is required for this development workflow.

The backend should run locally for development and on the Pi later. Document phone-to-laptop connectivity and development-only network configuration during implementation. Never commit signing keys, API tokens, local.properties or personal credentials.

## Next-session instruction
Read this document first, inspect the current repository, and continue from actual implemented state. Preserve the Go/Kotlin choices and agreed requirements. Clearly distinguish tested behaviour, sample data and unfinished functionality.

