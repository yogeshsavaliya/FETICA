# Proxy Tunnel Phase 1

Phase 1 adds a consent-based Android foreground service and a persistent authenticated
connection from the Unity app to a configured gateway. It does not proxy traffic.

## Architecture

```text
Unity Debug UI / game UI
  -> ProxyTunnelBootstrap
  -> ProxyTunnelClient
  -> AndroidJavaClass com.secureinfotech.proxytunnel.ProxyTunnelBridge
  -> ProxyTunnelService foreground service
  -> TunnelConnectionManager worker thread
  -> TCP or TLS socket to gateway
```

Android files live in:

```text
Assets/Plugins/Android/ProxyTunnel.androidlib/
```

Unity runtime files live in:

```text
Assets/Scripts/ProxyTunnel/
```

## Permissions

Declared in `Assets/Plugins/Android/ProxyTunnel.androidlib/AndroidManifest.xml`:

- `android.permission.INTERNET`: opens the outbound gateway socket.
- `android.permission.ACCESS_NETWORK_STATE`: checks network availability and listens for
  reconnect triggers.
- `android.permission.FOREGROUND_SERVICE`: required for foreground services on Android 9+.
- `android.permission.FOREGROUND_SERVICE_DATA_SYNC`: foreground-service-specific
  permission required for the selected `dataSync` type on Android 14+ target SDKs.
- `android.permission.POST_NOTIFICATIONS`: requested at runtime on Android 13+ before
  starting the foreground service, because the persistent status notification must be
  visible.

## Foreground service type

The service uses:

```xml
android:foregroundServiceType="dataSync"
```

This is the most defensible prototype type because Phase 1 maintains a user-requested,
authenticated network synchronization/control connection and exposes continuous transfer
status. It deliberately does not use `connectedDevice`, because the service does not
communicate with Bluetooth, USB, NFC, or similar external devices.

Limitations:

- Android foreground-service policy still expects user-visible, user-benefiting work.
- Platform and Play policy may impose additional limits on long-running `dataSync`
  foreground services on newer Android versions.
- If later phases implement broad traffic relay or VPN behavior, this service type and
  architecture must be reassessed.

## Unity-to-Java call flow

`ProxyTunnelDebugUI` provides a prototype UI. Pressing Start calls:

```text
ProxyTunnelBootstrap.StartTunnel(...)
ProxyTunnelClient.StartTunnel(...)
ProxyTunnelBridge.startTunnel(Activity, host, port, username, password, useTls)
```

The Java bridge validates host, port, and username/password shape. On Android 13+ it requests
notification permission through the Unity activity and asks the user to press Start again
after granting permission. It then starts `ProxyTunnelService`.

The tunnel is never started automatically by Unity startup, pause, resume, focus, or quit
callbacks.

## Service lifecycle

- `ProxyTunnelService` calls `startForeground(...)` immediately in `onCreate`.
- Start intents create a `TunnelConnectionManager` running on a single background worker.
- Notification Stop action sends an explicit service stop intent.
- Stop closes the socket, unregisters network callbacks, stops the worker, clears the
  user-enabled flag, removes the foreground notification, and stops the service.
- `stopWithTask=false` lets Android keep the service when the Unity activity is removed
  from recents if the OS permits continued foreground execution.
- `START_NOT_STICKY` prevents silent restarts without in-memory credentials.
- Invalid start input, missing credentials, unsupported actions, and permanent
  authentication failures are terminal configuration/authentication errors. The service
  clears the persisted user-enabled flag, preserves the final error for Unity, removes the
  foreground notification, and stops itself instead of leaving an idle foreground service.

## Authentication and heartbeat flow

For `useTls=false`, the client opens a normal TCP socket.

For `useTls=true`, the client first opens a plain socket with the configured connect
timeout, then wraps it with
`SSLSocketFactory.createSocket(plainSocket, host, port, true)`. Passing the configured host
to the platform TLS stack associates the connection with that hostname and lets the
platform send SNI on supported stacks. On Android API 24+ the client also explicitly sets
SNI for DNS hostnames and enables HTTPS endpoint identification through `SSLParameters`.
For API 23 compatibility, and as a defense in depth on newer versions, the client starts
the TLS handshake and then explicitly verifies the `SSLSession` with Android's default
HTTPS hostname verifier. This means a certificate issued for a different hostname must
fail. The client uses the Android platform trust store, does not install a permissive
trust manager, and does not bypass certificate verification.

Protocol:

```text
client: AUTH2 base64(username) base64(password)\n
server: OK\n
client: PING\n every 15 seconds
server: PONG\n
```

Rules implemented:

- Authentication timeout: 10 seconds.
- Heartbeat read timeout: 20 seconds.
- Maximum protocol line length: 256 bytes.
- Failed authentication closes the socket and does not aggressively retry.
- Reconnect backoff: 2s, 4s, 8s, 16s, max 30s.
- Networking never runs on Android's main thread.
- Server commands are not accepted in Phase 1.

The username/password are passed only in memory to the service start intent and are not stored in
`SharedPreferences`. Host, port, TLS setting, and whether the user enabled the service are
persisted. If Android kills the process and credentials are unavailable, the tunnel must be
started again by the user.

## Build instructions

Open the project with Unity `6000.0.33f1`, switch platform to Android, and build with the
existing IL2CPP Android scripting backend.

Expected unchanged settings:

```text
Android minimum SDK: 23
Android target SDK: Automatic
Android scripting backend: IL2CPP
Custom Gradle templates: disabled
Package name: unchanged
```

## adb install

After producing an APK:

```bash
adb install -r path/to/app.apk
```

For Android App Bundles, use your normal bundletool/internal testing flow.

## adb logcat

The implementation avoids logging credentials. Useful filters:

```bash
adb logcat Unity:D ProxyTunnelConnection:D AndroidRuntime:E '*:S'
```

If you need full service diagnostics:

```bash
adb logcat | grep -E 'ProxyTunnel|Unity|AndroidRuntime'
```

## Test gateway commands

Run on the development computer:

```bash
cd Tools/TestGateway
TUNNEL_USERNAME="user1" TUNNEL_PASSWORD="strong-password" npm start
```

For controlled LAN testing with a physical device:

```bash
TUNNEL_USERNAME="user1" TUNNEL_PASSWORD="strong-password" TEST_GATEWAY_HOST=0.0.0.0 npm start
```

Use the computer's LAN IP address as the gateway host in the Unity debug UI. Do not expose
the test gateway to the public internet.

## Physical Android device testing steps

1. Put the Android device and test gateway computer on the same trusted LAN.
2. Start the test gateway with `TEST_GATEWAY_HOST=0.0.0.0`.
3. Build and install the Unity Android app.
4. Launch the app.
5. Enter gateway host, port, username, password, and TLS setting. The provided test gateway is plain TCP,
   so use TLS off for this test.
6. Press Start. On Android 13+, grant notification permission, then press Start again.
7. Confirm the foreground notification appears and moves to Connected.
8. Minimize the app and verify the notification remains.
9. Toggle Wi-Fi off and verify Reconnecting.
10. Toggle Wi-Fi on and verify it reconnects.
11. Press Stop in the notification and verify the service stops.

## Expected notification behavior

The persistent foreground notification shows:

- Title: `Network sharing active`
- Status: Connecting, Connected, Reconnecting, or Disconnected
- Uploaded byte count
- Downloaded byte count
- Stop action

The Stop action fully terminates the socket and service.

## Known limitations

- No SOCKS5 forwarding.
- No arbitrary third-party traffic relay.
- No public listening port.
- No Android `VpnService`.
- No hidden background execution.
- No notification bypass.
- No root functionality.
- No traffic interception.
- Token persistence is intentionally omitted for Phase 1.
- The debug UI is a prototype OnGUI panel, not production UI.

## Security decisions

- Host must be non-empty.
- Port must be 1-65535.
- Usernames/passwords containing newlines are rejected.
- Credentials are never intentionally logged.
- Authentication and protocol response line lengths are bounded.
- TLS uses Android platform certificate verification.
- No permissive trust manager is installed.
- No server commands are accepted.
- No Android listening sockets are opened.

## Acceptance tests

1. Unity project compiles.
2. Android build succeeds with IL2CPP.
3. Pressing Start creates a visible foreground-service notification.
4. Service authenticates with the test gateway.
5. Status changes to Connected.
6. PING/PONG continues every 15 seconds.
7. Minimizing the Unity game does not stop the service.
8. Removing the Unity activity from recent apps does not immediately stop the service when
   Android permits continued execution.
9. Wi-Fi disconnect causes Reconnecting status.
10. Restoring the network reconnects automatically.
11. Notification Stop action terminates the socket and service.
12. Incorrect username/password does not continuously retry authentication aggressively.
13. Credentials never appear in logcat.
14. No `VpnService` is declared.
15. No listening socket is opened on the Android device.
16. No SOCKS5 or traffic-forwarding code exists.

## Blocking-fix validation coverage

These cases should be run on a physical Android device or Android CI environment with the
Unity Android toolchain available:

1. Valid TLS hostname succeeds:
   - Run a TLS gateway with a certificate whose SAN matches the hostname entered in the
     Unity UI.
   - Start with `Use TLS` enabled.
   - Expected: TLS handshake succeeds, AUTH succeeds, status becomes Connected.
2. Wrong-host certificate fails:
   - Run the TLS gateway with a certificate valid for a different hostname.
   - Start with `Use TLS` enabled and the unmatched hostname in the Unity UI.
   - Expected: TLS handshake/session verification fails, no AUTH retry storm occurs, and
     credentials are not logged.
3. Authentication failure terminates the service:
   - Start the tunnel with incorrect username/password.
   - Expected: status becomes Disconnected, final error remains readable from Unity,
     user-enabled persistence is cleared, foreground notification is removed, and the
     service stops.
4. Temporary disconnect reconnects:
   - Start with correct username/password and then interrupt Wi-Fi or stop the gateway after a
     successful connection.
   - Expected: status becomes Reconnecting, reconnect uses bounded backoff, and restoring
     network/gateway connectivity returns status to Connected.
5. Notification Stop terminates immediately:
   - Start a connected tunnel and press Stop from the persistent notification.
   - Expected: socket closes, worker/callbacks are released, notification is removed, and
     service stops without waiting for heartbeat timeout.
