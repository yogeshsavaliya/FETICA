# Proxy Tunnel Test Gateway

Node.js gateway for Phase 2 reverse SOCKS testing.

## Required credentials

The gateway no longer uses a token. Set username/password credentials:

```bash
export TUNNEL_USERNAME="user1"
export TUNNEL_PASSWORD="strong-password"
```

By default, the SOCKS5 username/password are the same as the tunnel credentials. Override
only if you want separate SOCKS client credentials:

```bash
export SOCKS_USERNAME="socks-user"
export SOCKS_PASSWORD="socks-password"
```

## Local run

```bash
cd Tools/TestGateway
TUNNEL_USERNAME="user1" TUNNEL_PASSWORD="strong-password" npm start
```

Defaults:

```text
tunnel listener: 127.0.0.1:9090
SOCKS5 listener: 127.0.0.1:1080
```

## Global / VPS run

Run this on a public VPS or server with a public IP/domain:

```bash
TUNNEL_USERNAME="user1" \
TUNNEL_PASSWORD="strong-password" \
TEST_GATEWAY_HOST=0.0.0.0 \
SOCKS_HOST=0.0.0.0 \
npm start
```

Open/firewall only the ports you need:

```text
9090/tcp tunnel from Android app
1080/tcp SOCKS5 clients, username/password protected
```

For production-like global testing, use TLS for the Android tunnel. Provide a certificate
valid for the gateway domain:

```bash
TUNNEL_USERNAME="user1" \
TUNNEL_PASSWORD="strong-password" \
TEST_GATEWAY_HOST=0.0.0.0 \
TLS_CERT_PATH=/etc/letsencrypt/live/example.com/fullchain.pem \
TLS_KEY_PATH=/etc/letsencrypt/live/example.com/privkey.pem \
npm start
```

Then enable `Use TLS` in the Unity debug UI and use the certificate hostname as Gateway
Host. Do not use trust-all certificates.

## Test with curl

After the Android app connects to the tunnel:

```bash
curl -x socks5h://user1:strong-password@127.0.0.1:1080 https://example.com/
```

For global SOCKS:

```bash
curl -x socks5h://user1:strong-password@your-server-domain:1080 https://example.com/
```

The gateway accepts SOCKS5 username/password CONNECT requests and forwards each CONNECT
over the single authenticated Android tunnel as a framed stream. The Android device opens
the final outbound destination TCP connection.

## Security notes

- Credentials are required and are never logged.
- SOCKS binds to loopback by default.
- Binding SOCKS to `0.0.0.0` is for controlled global testing; use strong passwords and
  firewall rules.
- The Android device does not open a listening socket.
- This test gateway is a development prototype, not a hardened production proxy service.
