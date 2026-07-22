# Proxy Tunnel Phase 2 - Reverse SOCKS

Phase 2 adds a reverse SOCKS5 prototype on top of the existing consent-based Android
foreground service.

## Architecture

```text
SOCKS client anywhere allowed by firewall
  -> authenticated SOCKS5 / HTTP CONNECT listener on gateway, default 127.0.0.1:1080
  -> Node gateway stream multiplexer
  -> authenticated outbound Android tunnel, default 127.0.0.1:9090 for local tests
  -> Android foreground service
  -> destination TCP socket opened by Android
```

The Android device does not open a listening socket. It only makes an outbound,
user-started foreground-service tunnel connection to the gateway.

## Authentication

There is no token authentication in Phase 2.

Two username/password checks exist:

1. Android tunnel authentication using `TUNNEL_USERNAME` and `TUNNEL_PASSWORD`.
2. SOCKS5 username/password authentication using `SOCKS_USERNAME` and `SOCKS_PASSWORD`.
   If SOCKS credentials are omitted, the gateway reuses the tunnel username/password.

The Android tunnel sends:

```text
AUTH2 base64(username) base64(password)\n
```

The gateway replies:

```text
OK\n
```

Credentials are not logged.

## Scope

Implemented:

- SOCKS5 username/password CONNECT support on the gateway side.
- HTTP CONNECT proxy support on the same gateway listener for clients/apps that do not
  support SOCKS5.
- One authenticated Android tunnel carrying multiple framed streams.
- Gateway-to-Android `OPEN`, `DATA`, and `CLOSE` frames.
- Android-side destination TCP connect and bidirectional relay.
- Foreground notification and explicit Stop action remain the tunnel lifetime controls.
- Optional TLS server for the Android tunnel when `TLS_CERT_PATH` and `TLS_KEY_PATH` are set.

Not implemented:

- UDP ASSOCIATE.
- Android `VpnService`.
- Android local/public listening port.
- Traffic interception.
- Hidden background execution.
- Root functionality.

## Frame protocol

After `OK`, the tunnel switches to binary frames:

```text
1 byte  frame type
4 bytes stream id, signed big endian
4 bytes payload length, signed big endian
N bytes payload
```

Frame types:

```text
1 PING
2 PONG
3 OPEN        payload: host + "\n" + port
4 OPEN_OK
5 OPEN_ERROR  payload: error message
6 DATA
7 CLOSE
```

Maximum frame payload is 64 KiB. Android splits destination reads into 16 KiB DATA
frames.

## Local run

```bash
cd Tools/TestGateway
TUNNEL_USERNAME="user1" TUNNEL_PASSWORD="strong-password" npm start
```

Defaults:

```text
tunnel listener: 127.0.0.1:9090
proxy listener:  127.0.0.1:1080
```

## Global run from any network

Run the gateway on a VPS/public server:

```bash
TUNNEL_USERNAME="user1" \
TUNNEL_PASSWORD="strong-password" \
TEST_GATEWAY_HOST=0.0.0.0 \
SOCKS_HOST=0.0.0.0 \
npm start
```

Use firewall rules to restrict access where possible. For the Android tunnel, TLS is
recommended globally:

```bash
TUNNEL_USERNAME="user1" \
TUNNEL_PASSWORD="strong-password" \
TEST_GATEWAY_HOST=0.0.0.0 \
TLS_CERT_PATH=/etc/letsencrypt/live/example.com/fullchain.pem \
TLS_KEY_PATH=/etc/letsencrypt/live/example.com/privkey.pem \
npm start
```

In Unity, set:

```text
Gateway Host: your public domain or IP
Gateway Port: 9090
Username: user1
Password: strong-password
Use TLS: on when TLS cert/key are configured
```

For TLS, the Gateway Host must match the certificate hostname.

## 24/7 connectivity notes

The gateway and Android tunnel are tuned for long-lived connections:

- Gateway tunnel idle timeout is 5 minutes.
- Android tunnel read timeout is 5 minutes.
- TCP keepalive is enabled on the gateway and Android tunnel sockets.
- Android foreground service holds a partial wake lock while running.

Android still may stop long-running work if the user or OEM battery manager restricts the
app. For 24/7 testing on the Mumbai phone:

1. Keep the foreground notification visible.
2. Disable battery optimization for the Unity app.
3. Allow background activity/autostart for the Unity app if the device vendor provides
   those settings.
4. Keep mobile data/Wi-Fi stable.

For your static IP `144.45.29.130`, a plain TCP test command is:

```bash
TUNNEL_USERNAME="user1" \
TUNNEL_PASSWORD="strong-password" \
TEST_GATEWAY_HOST=0.0.0.0 \
SOCKS_HOST=0.0.0.0 \
npm start
```

Android app settings:

```text
Gateway Host: 144.45.29.130
Gateway Port: 9090
Username: user1
Password: strong-password
Use TLS: off for this plain TCP test
```

## SOCKS client commands

Local gateway machine:

```bash
curl -x socks5h://user1:strong-password@127.0.0.1:1080 https://example.com/
```

Global gateway:

```bash
curl -x socks5h://user1:strong-password@your-server-domain:1080 https://example.com/
```

If a Delhi phone/app only supports HTTP proxy mode, configure:

```text
HTTP Proxy Host: your-server-domain-or-ip
HTTP Proxy Port: 1080
Username: user1
Password: strong-password
```

The destination connection is opened from the Android device/network.

## Acceptance tests

1. Android app starts only after explicit button press.
2. Foreground notification appears immediately.
3. Gateway logs tunnel connected without logging credentials.
4. Wrong tunnel username/password terminates the service and does not retry aggressively.
5. SOCKS client without username/password is rejected.
6. SOCKS client with correct username/password can CONNECT through the Android tunnel.
7. HTTP CONNECT client with correct username/password can CONNECT through the Android
   tunnel.
8. `curl -x socks5h://user:pass@127.0.0.1:1080 https://example.com/` succeeds while
   Android tunnel is connected.
9. Multiple CONNECT streams can run concurrently.
10. Notification Stop closes all streams and the tunnel.
11. Wi-Fi loss causes Reconnecting and closes active streams.
12. Restored network reconnects the tunnel.
13. No Android `VpnService` is declared.
14. No Android listening socket is opened.
