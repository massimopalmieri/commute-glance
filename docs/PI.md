# Raspberry Pi deployment

Deployed 3 November 2026 on `massimopalmieri@raspberrypi5`, Linux ARM64.

- Backend executable: `/opt/commute-glance/backend`.
- Boot-enabled system service: `commute-glance.service`, running with a temporary unprivileged system account.
- Listener: `127.0.0.1:8080` on the Pi.
- Private HTTPS URL: `https://raspberrypi5.tailc9e937.ts.net:10443`.
- Tailscale Serve forwards HTTPS port 10443 to the loopback backend. Existing services on 443, 8443 and 9443 were preserved. No Funnel or public exposure was enabled.
- Root-only configuration: `/etc/commute-glance.env`, mode 0600. Contains `API_TOKEN` and `LISTEN_ADDR`. The random token was transferred directly to the app's private settings without printing it or writing it into the checkout/APK.

The Pixel uses the Pi URL and token. Tailscale must be connected on the Pixel; Mac sleep and ADB disconnection no longer affect this connection. The Mac development backend was left running as requested and can still be selected using the local setup in [MAC_PIXEL.md](MAC_PIXEL.md).

## Operations

```sh
ssh massimopalmieri@raspberrypi5 'systemctl status commute-glance --no-pager'
ssh massimopalmieri@raspberrypi5 'sudo systemctl restart commute-glance'
ssh massimopalmieri@raspberrypi5 'sudo journalctl -u commute-glance -n 50 --no-pager'
curl -f https://raspberrypi5.tailc9e937.ts.net:10443/healthz
```

Health is public within the tailnet. Departures require the bearer token and return HTTP 401 without it. Do not put that token in shared terminal output or Git. To add rail access, edit the root-only environment file locally on the Pi with `sudoedit /etc/commute-glance.env`, add the subscribed `RAIL_BASE_URL` and `RAIL_AUTHORIZATION`, then restart the service. Keep `API_TOKEN` unchanged unless also updating the app.

## Update the backend from the Mac

From the repository root, after running backend tests:

```sh
cd backend
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -o /tmp/commute-glance-backend .
scp /tmp/commute-glance-backend massimopalmieri@raspberrypi5:/tmp/commute-glance-backend.new
ssh massimopalmieri@raspberrypi5 'sudo systemctl stop commute-glance && sudo install -m 755 /tmp/commute-glance-backend.new /opt/commute-glance/backend && sudo systemctl start commute-glance'
```

The service definition is tracked in `deploy/commute-glance.service`; configuration and secrets are not. Cross-compiling on the Mac avoids relying on the Pi's old Go 1.19 installation. The current service is enabled at boot, but a physical reboot has not been tested.

## Phone presentation

Settings retain the requested Monday/Thursday commute windows, stop GW, routes 166/405 and 3-minute walk. The silent notification is opt-in and was granted notification permission. Android's “Show silent notifications” lock-screen setting was enabled so it can appear there; this system option also affects other silent notifications. The existing compact lock-screen layout was retained. Expand the notification for the detailed snapshot.

Rail needs a National Rail LDBWS Public JSON subscription. It is shown as unavailable until configured. No train fixtures are presented as live data.
