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
proxy listener:  127.0.0.1:2227
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

For static IP `144.45.29.130`, Android should connect to:

```text
Gateway Host: 144.45.29.130
Gateway Port: 9090
Use TLS: off unless you configure a valid TLS certificate/domain
```

Open/firewall only the ports you need:

```text
9090/tcp tunnel from Android app
2227/tcp SOCKS5 clients, username/password protected
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

If the Delhi client app has only an `HTTPS proxy` option and no `SOCKS5` or plain `HTTP
proxy` option, run the proxy listener itself over TLS too:

```bash
TUNNEL_USERNAME="user1" \
TUNNEL_PASSWORD="strong-password" \
TEST_GATEWAY_HOST=0.0.0.0 \
SOCKS_HOST=0.0.0.0 \
PROXY_TLS_CERT_PATH=/etc/letsencrypt/live/example.com/fullchain.pem \
PROXY_TLS_KEY_PATH=/etc/letsencrypt/live/example.com/privkey.pem \
npm start
```

Then configure the Delhi client as an HTTPS proxy using the same host and port `2227`.

## Test with curl

After the Android app connects to the tunnel:

```bash
curl -x socks5h://user1:strong-password@127.0.0.1:2227 https://example.com/
```

For global SOCKS:

```bash
curl -x socks5h://user1:strong-password@your-server-domain:2227 https://example.com/
```

If your Delhi phone/app only supports HTTP proxy, use the same host/port/credentials in
HTTP proxy mode. The gateway accepts HTTP CONNECT on the same `2227` port:

```text
HTTP Proxy Host: your-server-domain-or-ip
HTTP Proxy Port: 2227
Username: user1
Password: strong-password
```

Do not select `HTTPS proxy` unless `PROXY_TLS_CERT_PATH` and `PROXY_TLS_KEY_PATH` are set
on the gateway. If HTTPS proxy mode hits a plain listener, the server logs a clear
diagnostic and closes that client connection.

Some Android SOCKS apps open a TCP connection and wait for the server to speak first during
their connection test. That is not standard SOCKS5 behavior, but the gateway includes a
compatibility fallback: if no first byte arrives quickly, it sends the SOCKS5
username/password method selection and continues the normal auth flow. The server logs:

```text
SOCKS compatibility greeting sent to <client-ip>:<port>
```

If that app still closes immediately, enable server-first mode explicitly:

```bash
SOCKS_SERVER_FIRST=1 \
TUNNEL_USERNAME="user1" \
TUNNEL_PASSWORD="strong-password" \
TEST_GATEWAY_HOST=0.0.0.0 \
SOCKS_HOST=0.0.0.0 \
npm start
```

The server then sends the SOCKS5 username/password method selection as soon as a client
connects and logs:

```text
SOCKS server-first compatibility greeting sent to <client-ip>:<port>
```

The gateway accepts SOCKS5 username/password CONNECT requests and HTTP CONNECT proxy
requests, then forwards each CONNECT over the single authenticated Android tunnel as a
framed stream. The Android device opens the final outbound destination TCP connection.

## Security notes

- Credentials are required and are never logged.
- SOCKS binds to loopback by default.
- Binding SOCKS to `0.0.0.0` is for controlled global testing; use strong passwords and
  firewall rules.
- The Android device does not open a listening socket.
- This test gateway is a development prototype, not a hardened production proxy service.

## 24/7 notes

- The gateway keeps authenticated tunnel sockets alive for up to 5 idle minutes and enables
  TCP keepalive.
- The Android service uses a foreground notification and partial wake lock.
- On the Android phone, disable battery optimization for the Unity app and allow background
  activity/autostart where the vendor settings require it.
