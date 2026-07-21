# Proxy Tunnel Phase 2 - Reverse SOCKS

Phase 2 adds a reverse SOCKS5 prototype on top of the existing consent-based Android
foreground service.

## Architecture

```text
SOCKS client on gateway machine
  -> local SOCKS5 listener, default 127.0.0.1:1080
  -> Node test gateway stream multiplexer
  -> authenticated outbound Android tunnel, default 127.0.0.1:9090 for local tests
  -> Android foreground service
  -> destination TCP socket opened by Android
```

The Android device does not open a listening socket. It only makes an outbound,
user-started foreground-service tunnel connection to the gateway.

## Scope

Implemented:

- SOCKS5 no-auth CONNECT support on the gateway side.
- One authenticated Android tunnel carrying multiple framed streams.
- Gateway-to-Android `OPEN`, `DATA`, and `CLOSE` frames.
- Android-side destination TCP connect and bidirectional relay.
- Loopback-only SOCKS listener by default.
- Foreground notification and explicit Stop action remain the tunnel lifetime controls.

Not implemented:

- UDP ASSOCIATE.
- SOCKS username/password authentication.
- Android `VpnService`.
- Android local/public listening port.
- Traffic interception.
- Hidden background execution.
- Root functionality.

## Protocol

Authentication is still line based:

```text
AUTH <token>\n
OK\n
```

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

## Running the gateway

```bash
cd Tools/TestGateway
TEST_TUNNEL_TOKEN="test-token" npm start
```

Defaults:

```text
tunnel listener: 127.0.0.1:9090
SOCKS listener:  127.0.0.1:1080
```

For physical Android device testing on a controlled LAN:

```bash
TEST_TUNNEL_TOKEN="test-token" TEST_GATEWAY_HOST=0.0.0.0 npm start
```

Keep `SOCKS_HOST` as `127.0.0.1` unless you intentionally need LAN clients to use the
SOCKS listener. If you bind SOCKS to `0.0.0.0`, firewall it and use only on a trusted test
network:

```bash
TEST_TUNNEL_TOKEN="test-token" TEST_GATEWAY_HOST=0.0.0.0 SOCKS_HOST=0.0.0.0 npm start
```

## Unity / Android steps

1. Build and install the Unity Android app.
2. Start the Node gateway.
3. In the Unity debug panel, enter the gateway host, tunnel port, token, and TLS setting.
4. Press `Start Reverse SOCKS Tunnel`.
5. Confirm Android foreground notification remains visible and status becomes Connected.
6. On the gateway machine, configure a client to use SOCKS5 at `127.0.0.1:1080`.

Example curl command on the gateway machine:

```bash
curl --socks5-hostname 127.0.0.1:1080 https://example.com/
```

The destination connection is opened from the Android device/network.

## Acceptance tests

1. Android app starts only after explicit button press.
2. Foreground notification appears immediately.
3. Gateway logs tunnel connected without logging token.
4. SOCKS listener binds to `127.0.0.1` by default.
5. `curl --socks5-hostname 127.0.0.1:1080 https://example.com/` succeeds while Android
   tunnel is connected.
6. Multiple SOCKS CONNECT streams can run concurrently.
7. Notification Stop closes all streams and the tunnel.
8. Wi-Fi loss causes Reconnecting and closes active streams.
9. Restored network reconnects the tunnel.
10. Wrong token terminates the service and does not retry aggressively.
11. No Android `VpnService` is declared.
12. No Android listening socket is opened.
