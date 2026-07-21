# Proxy Tunnel Test Gateway

Node.js gateway for Phase 1 heartbeat testing and Phase 2 reverse SOCKS testing.

## Run

```bash
cd Tools/TestGateway
TEST_TUNNEL_TOKEN="replace-with-test-token" npm start
```

Defaults:

```text
tunnel listener: 127.0.0.1:9090
SOCKS5 listener: 127.0.0.1:1080
```

Override ports:

```bash
TEST_TUNNEL_TOKEN="replace-with-test-token" TEST_GATEWAY_PORT=9091 SOCKS_PORT=1081 npm start
```

For controlled physical-device LAN testing, bind the tunnel listener to all interfaces:

```bash
TEST_TUNNEL_TOKEN="replace-with-test-token" TEST_GATEWAY_HOST=0.0.0.0 npm start
```

Keep the SOCKS listener on `127.0.0.1` by default. Bind SOCKS to `0.0.0.0` only on a
trusted, firewalled test network:

```bash
TEST_TUNNEL_TOKEN="replace-with-test-token" TEST_GATEWAY_HOST=0.0.0.0 SOCKS_HOST=0.0.0.0 npm start
```

## Test with curl

After the Android app connects to the tunnel:

```bash
curl --socks5-hostname 127.0.0.1:1080 https://example.com/
```

The gateway accepts SOCKS5 no-auth CONNECT requests and forwards each CONNECT over the
single authenticated Android tunnel as a framed stream. The Android device opens the final
outbound destination TCP connection.

## Security notes

- The token is never logged.
- SOCKS binds to loopback by default.
- The Android device does not open a listening socket.
- This test gateway is for controlled development testing, not public internet exposure.
