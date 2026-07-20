# Proxy Tunnel Test Gateway

Small local TCP gateway for Phase 1 Android foreground tunnel testing.

## Run

```bash
cd Tools/TestGateway
TEST_TUNNEL_TOKEN="replace-with-test-token" npm start
```

Defaults:

```text
host: 127.0.0.1
port: 9090
```

Override the port:

```bash
TEST_TUNNEL_TOKEN="replace-with-test-token" TEST_GATEWAY_PORT=9091 npm start
```

For controlled physical-device LAN testing only, bind to all interfaces:

```bash
TEST_TUNNEL_TOKEN="replace-with-test-token" TEST_GATEWAY_HOST=0.0.0.0 npm start
```

When binding to `0.0.0.0`, use a private test network, firewall the host, and enter the
computer's LAN IP address in the Unity debug UI. Do not expose this test gateway to the
public internet.

## Protocol

The client sends:

```text
AUTH <token>
```

The server replies:

```text
OK
```

Then the client sends:

```text
PING
```

The server replies:

```text
PONG
```

The server does not log token values. It enforces maximum line lengths and socket
timeouts so malformed clients cannot leave unbounded input in memory.
