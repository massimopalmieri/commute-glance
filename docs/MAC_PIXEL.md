# Mac and Pixel development setup

The phone now uses the [Pi backend](PI.md). These instructions retain the working Mac development fallback.

Run the backend from the checkout in a terminal:

```sh
cd ~/Code/commute-glance/backend
go run .
```

Go's default `GOTOOLCHAIN=auto` selects the module's Go 1.24 toolchain even when the Mac's installed Go is 1.23.2. The development server binds to `127.0.0.1:8080`. Leave `API_TOKEN` and `LISTEN_ADDR` unset for this configuration. Keep the Mac awake and connected while testing. Check `curl -f http://127.0.0.1:8080/healthz`. If the port is already in use, reuse the running server or stop its process before starting another.

In another terminal:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices -l
adb mdns services
```

The paired Pixel normally reconnects automatically on the same Wi-Fi network. If absent, enable Wireless debugging on the phone and use the current **IP address & port** shown on that screen:

```sh
adb connect PHONE_IP:CURRENT_DEBUGGING_PORT
adb devices -l
adb reverse tcp:8080 tcp:8080
adb reverse --list
adb shell am start -n uk.commuteglance/.MainActivity
```

Use the connection port, not the separate pairing port. `adb mdns services` can also discover the `_adb-tls-connect._tcp` address and port. If multiple devices are attached, add `-s CURRENT_DEVICE_SERIAL` to each ADB command. Only pair again if the phone has forgotten the Mac; enter the pairing code locally with `adb pair PHONE_IP:PAIRING_PORT`. Restore reverse forwarding after reconnects or reboots.

The app's backend URL is `http://127.0.0.1:8080`, with a blank token for the default server. Saved settings are stop `490007669N`, routes `166,405`, walk 3 minutes, Monday/Thursday, 07:00–08:30 and 16:00–19:00, Europe/London. Outside these windows the widget shows an inactive message and the notification is removed. The app still lets you open either board manually. The optional silent notification needs Android notification permission and suitable system lock-screen visibility settings.

To rebuild and update without clearing app data:

```sh
cd ~/Code/commute-glance/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

Rail remains unavailable until a National Rail **LDBWS Public JSON** subscription is active. Configure its HTTPS base URL as `RAIL_BASE_URL` and the subscription's complete Authorization header as `RAIL_AUTHORIZATION` in the backend's local environment. Obtain these from the subscription documentation and store them locally, outside Git. Do not put rail credentials in the Android app or chat. Restart the backend after setting them. The app requests ECR→LBG or LBG→ECR with `maxMinutes=20`; tests cover keeping delayed services whose scheduled duration qualifies.

ADB forwarding is a development connection. Mac sleep, Wi-Fi changes or a lost ADB connection interrupt fetching. Android background work remains best effort. Read the age indicator and stale/expired labels, then refresh after restoring connectivity. Pi hosting and secure remote access are separate future work.
