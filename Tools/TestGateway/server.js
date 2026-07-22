"use strict";

const fs = require("fs");
const net = require("net");
const tls = require("tls");

const DEFAULT_TUNNEL_HOST = "127.0.0.1";
const DEFAULT_TUNNEL_PORT = 9090;
const DEFAULT_SOCKS_HOST = "127.0.0.1";
const DEFAULT_SOCKS_PORT = 1080;
const MAX_LINE_LENGTH = 512;
const MAX_FRAME_LENGTH = 64 * 1024;
const AUTH_TIMEOUT_MS = 10_000;
const TUNNEL_IDLE_TIMEOUT_MS = 5 * 60_000;
const SOCKS_TIMEOUT_MS = 30_000;
const OPEN_TIMEOUT_MS = 15_000;

const FRAME_PING = 1;
const FRAME_PONG = 2;
const FRAME_OPEN = 3;
const FRAME_OPEN_OK = 4;
const FRAME_OPEN_ERROR = 5;
const FRAME_DATA = 6;
const FRAME_CLOSE = 7;

const tunnelUsername = requireSecret("TUNNEL_USERNAME");
const tunnelPassword = requireSecret("TUNNEL_PASSWORD");
const socksUsername = process.env.SOCKS_USERNAME || tunnelUsername;
const socksPassword = process.env.SOCKS_PASSWORD || tunnelPassword;
const tunnelHost = process.env.TEST_GATEWAY_HOST || DEFAULT_TUNNEL_HOST;
const tunnelPort = parsePort(process.env.TEST_GATEWAY_PORT || String(DEFAULT_TUNNEL_PORT), "TEST_GATEWAY_PORT");
const socksHost = process.env.SOCKS_HOST || DEFAULT_SOCKS_HOST;
const socksPort = parsePort(process.env.SOCKS_PORT || String(DEFAULT_SOCKS_PORT), "SOCKS_PORT");

let activeTunnel = null;
let nextStreamId = 1;

const tunnelServer = createTunnelServer();
const socksServer = createProxyServer();

socksServer.on("error", (error) => {
  console.error(`proxy server error: ${error.message}`);
});

tunnelServer.listen(tunnelPort, tunnelHost, () => {
  const mode = isTlsEnabled() ? "TLS" : "plain TCP";
  console.log(`tunnel gateway listening on ${tunnelHost}:${tunnelPort} (${mode})`);
});

socksServer.listen(socksPort, socksHost, () => {
  const mode = isProxyTlsEnabled() ? "TLS" : "plain TCP";
  console.log(`authenticated SOCKS5/HTTP CONNECT listener on ${socksHost}:${socksPort} (${mode})`);
  if (socksHost !== "127.0.0.1" && socksHost !== "::1") {
    console.log("WARNING: SOCKS listener is not loopback-only. Use only with strong credentials and firewall rules.");
  }
});

function createProxyServer() {
  if (!isProxyTlsEnabled()) {
    return net.createServer(handleProxySocket);
  }
  const options = {
    cert: fs.readFileSync(process.env.PROXY_TLS_CERT_PATH),
    key: fs.readFileSync(process.env.PROXY_TLS_KEY_PATH),
  };
  return tls.createServer(options, handleProxySocket);
}

function handleProxySocket(client) {
  const remote = `${client.remoteAddress}:${client.remotePort}`;
  client.setNoDelay(true);
  client.setTimeout(SOCKS_TIMEOUT_MS);
  handleProxyClient(client, remote).catch((error) => {
    console.log(`socks client closed: ${remote} ${error.message}`);
    client.destroy();
  });
}

function createTunnelServer() {
  if (!isTlsEnabled()) {
    return net.createServer(handleTunnelSocket);
  }
  const options = {
    cert: fs.readFileSync(process.env.TLS_CERT_PATH),
    key: fs.readFileSync(process.env.TLS_KEY_PATH),
  };
  return tls.createServer(options, handleTunnelSocket);
}

function isTlsEnabled() {
  return Boolean(process.env.TLS_CERT_PATH && process.env.TLS_KEY_PATH);
}

function isProxyTlsEnabled() {
  return Boolean(process.env.PROXY_TLS_CERT_PATH && process.env.PROXY_TLS_KEY_PATH);
}

function handleTunnelSocket(socket) {
  const remote = `${socket.remoteAddress}:${socket.remotePort}`;
  console.log(`tunnel connected: ${remote}`);
  if (activeTunnel) {
    console.log("closing previous tunnel connection");
    activeTunnel.close();
  }

  socket.setNoDelay(true);
  socket.setKeepAlive(true, 30_000);
  socket.setTimeout(AUTH_TIMEOUT_MS);
  let authBuffer = Buffer.alloc(0);

  socket.on("data", function onAuthData(chunk) {
    authBuffer = Buffer.concat([authBuffer, chunk]);
    if (authBuffer.length > MAX_LINE_LENGTH * 2) {
      socket.end("ERROR\n");
      return;
    }
    const newlineIndex = authBuffer.indexOf(0x0a);
    if (newlineIndex < 0) {
      return;
    }

    socket.removeListener("data", onAuthData);
    const rawLine = authBuffer.subarray(0, newlineIndex).toString("utf8");
    const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
    const remaining = authBuffer.subarray(newlineIndex + 1);
    const authResult = validateTunnelAuth(line);
    if (!authResult.ok) {
      console.log(`tunnel authentication failed: ${remote} (${authResult.reason})`);
      socket.write("ERROR\n");
      socket.end();
      return;
    }

    socket.write("OK\n");
    socket.setTimeout(TUNNEL_IDLE_TIMEOUT_MS);
    socket.setKeepAlive(true, 30_000);
    activeTunnel = new TunnelSession(socket, remote);
    console.log(`reverse SOCKS tunnel ready: ${remote}`);
    if (remaining.length > 0) {
      activeTunnel.receive(remaining);
    }
  });

  socket.on("timeout", () => {
    console.log(`tunnel timeout: ${remote}`);
    socket.destroy();
  });

  socket.on("close", () => {
    console.log(`tunnel disconnected: ${remote}`);
    if (activeTunnel && activeTunnel.socket === socket) {
      activeTunnel.closeStreams();
      activeTunnel = null;
    }
  });

  socket.on("error", (error) => {
    console.log(`tunnel socket error: ${remote} ${error.code || "unknown"}`);
  });
}

class TunnelSession {
  constructor(socket, remote) {
    this.socket = socket;
    this.remote = remote;
    this.buffer = Buffer.alloc(0);
    this.streams = new Map();
    socket.on("data", (chunk) => this.receive(chunk));
  }

  receive(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 9) {
      const type = this.buffer.readUInt8(0);
      const streamId = this.buffer.readInt32BE(1);
      const length = this.buffer.readInt32BE(5);
      if (length < 0 || length > MAX_FRAME_LENGTH) {
        this.close();
        return;
      }
      if (this.buffer.length < 9 + length) {
        return;
      }
      const payload = this.buffer.subarray(9, 9 + length);
      this.buffer = this.buffer.subarray(9 + length);
      this.handleFrame(type, streamId, payload);
    }
  }

  handleFrame(type, streamId, payload) {
    if (type === FRAME_PING) {
      this.sendFrame(FRAME_PONG, 0, Buffer.alloc(0));
      return;
    }
    if (type === FRAME_PONG) {
      return;
    }

    const stream = this.streams.get(streamId);
    if (!stream) {
      return;
    }

    if (type === FRAME_OPEN_OK) {
      stream.opened = true;
      stream.resolveOpen();
      return;
    }
    if (type === FRAME_OPEN_ERROR) {
      stream.rejectOpen(new Error(payload.toString("utf8") || "open failed"));
      this.closeStream(streamId, false);
      return;
    }
    if (type === FRAME_DATA) {
      if (!stream.client.destroyed) {
        stream.client.write(payload);
      }
      return;
    }
    if (type === FRAME_CLOSE) {
      this.closeStream(streamId, false);
    }
  }

  openStream(client, targetHost, targetPort) {
    const streamId = nextStreamId;
    nextStreamId += 2;
    if (nextStreamId > 0x7fffffff) {
      nextStreamId = 1;
    }

    let resolveOpen;
    let rejectOpen;
    const openPromise = new Promise((resolve, reject) => {
      resolveOpen = resolve;
      rejectOpen = reject;
    });
    const stream = { streamId, client, opened: false, resolveOpen, rejectOpen };
    this.streams.set(streamId, stream);
    this.sendFrame(FRAME_OPEN, streamId, Buffer.from(`${targetHost}\n${targetPort}`, "utf8"));

    const timer = setTimeout(() => {
      if (!stream.opened) {
        rejectOpen(new Error("open timeout"));
        this.closeStream(streamId, true);
      }
    }, OPEN_TIMEOUT_MS);

    return openPromise.finally(() => clearTimeout(timer)).then(() => streamId);
  }

  sendData(streamId, chunk) {
    let offset = 0;
    while (offset < chunk.length) {
      const end = Math.min(offset + MAX_FRAME_LENGTH, chunk.length);
      this.sendFrame(FRAME_DATA, streamId, chunk.subarray(offset, end));
      offset = end;
    }
  }

  sendFrame(type, streamId, payload) {
    if (!payload) {
      payload = Buffer.alloc(0);
    }
    if (payload.length > MAX_FRAME_LENGTH) {
      throw new Error("frame too large");
    }
    const header = Buffer.alloc(9);
    header.writeUInt8(type, 0);
    header.writeInt32BE(streamId, 1);
    header.writeInt32BE(payload.length, 5);
    this.socket.write(Buffer.concat([header, payload]));
  }

  closeStream(streamId, notifyAndroid) {
    const stream = this.streams.get(streamId);
    if (!stream) {
      return;
    }
    this.streams.delete(streamId);
    if (notifyAndroid) {
      this.sendFrame(FRAME_CLOSE, streamId, Buffer.alloc(0));
    }
    if (!stream.client.destroyed) {
      stream.client.end();
    }
  }

  closeStreams() {
    for (const streamId of Array.from(this.streams.keys())) {
      this.closeStream(streamId, false);
    }
  }

  close() {
    this.closeStreams();
    this.socket.destroy();
  }
}

async function handleProxyClient(client, remote) {
  const firstByte = await readExactly(client, 1);
  if (firstByte[0] === 0x05) {
    return handleSocksClient(client, remote, firstByte[0]);
  }
  if (firstByte[0] === 0x16 && !isProxyTlsEnabled()) {
    throw new Error("client appears to be using HTTPS/TLS proxy mode on a plain proxy listener; select SOCKS5/HTTP proxy or configure PROXY_TLS_CERT_PATH and PROXY_TLS_KEY_PATH");
  }
  client.unshift(firstByte);
  return handleHttpConnectClient(client, remote);
}

async function handleSocksClient(client, remote, version) {
  if (version !== 0x05) {
    throw new Error("unsupported SOCKS version");
  }
  const methodCount = await readExactly(client, 1);
  const methods = await readExactly(client, methodCount[0]);
  if (!methods.includes(0x02)) {
    client.write(Buffer.from([0x05, 0xff]));
    throw new Error("SOCKS username/password method required; client may be using no-auth or HTTP proxy mode");
  }
  client.write(Buffer.from([0x05, 0x02]));
  await authenticateSocksClient(client);

  const header = await readExactly(client, 4);
  if (header[0] !== 0x05 || header[1] !== 0x01) {
    writeSocksReply(client, 0x07);
    throw new Error("only SOCKS CONNECT is supported");
  }

  if (!activeTunnel) {
    writeSocksReply(client, 0x01);
    throw new Error("no active Android tunnel");
  }

  const address = await readSocksAddress(client, header[3]);
  const portBuffer = await readExactly(client, 2);
  const targetPort = portBuffer.readUInt16BE(0);
  console.log(`SOCKS CONNECT ${address.host}:${targetPort} from ${remote}`);

  const tunnel = activeTunnel;
  const streamId = await tunnel.openStream(client, address.host, targetPort);
  writeSocksReply(client, 0x00);

  client.on("data", (chunk) => {
    try {
      tunnel.sendData(streamId, chunk);
    } catch (error) {
      client.destroy();
    }
  });
  client.on("close", () => tunnel.closeStream(streamId, true));
  client.on("error", () => tunnel.closeStream(streamId, true));
  client.on("timeout", () => tunnel.closeStream(streamId, true));
}

async function handleHttpConnectClient(client, remote) {
  const request = await readHttpHeader(client, 8192);
  const lines = request.header.toString("utf8").split(/\r?\n/);
  const requestLine = lines.shift() || "";
  const match = /^CONNECT\s+([^\s:]+):(\d+)\s+HTTP\/1\.[01]$/i.exec(requestLine);
  if (!match) {
    client.write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n");
    throw new Error("HTTP proxy client did not send CONNECT; use SOCKS5 or HTTP CONNECT proxy mode");
  }

  const headers = parseHttpHeaders(lines);
  if (!isValidHttpProxyAuth(headers["proxy-authorization"])) {
    client.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"ProxyTunnel\"\r\nConnection: close\r\n\r\n");
    throw new Error("HTTP CONNECT authentication failed");
  }
  if (!activeTunnel) {
    client.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n");
    throw new Error("no active Android tunnel");
  }

  const targetHost = match[1];
  const targetPort = Number.parseInt(match[2], 10);
  if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) {
    client.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
    throw new Error("invalid HTTP CONNECT target port");
  }

  console.log(`HTTP CONNECT ${targetHost}:${targetPort} from ${remote}`);
  const tunnel = activeTunnel;
  const streamId = await tunnel.openStream(client, targetHost, targetPort);
  client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
  if (request.remaining.length > 0) {
    tunnel.sendData(streamId, request.remaining);
  }

  client.on("data", (chunk) => {
    try {
      tunnel.sendData(streamId, chunk);
    } catch (error) {
      client.destroy();
    }
  });
  client.on("close", () => tunnel.closeStream(streamId, true));
  client.on("error", () => tunnel.closeStream(streamId, true));
  client.on("timeout", () => tunnel.closeStream(streamId, true));
}

function parseHttpHeaders(lines) {
  const headers = {};
  for (const line of lines) {
    const separator = line.indexOf(":");
    if (separator <= 0) {
      continue;
    }
    headers[line.slice(0, separator).trim().toLowerCase()] = line.slice(separator + 1).trim();
  }
  return headers;
}

function isValidHttpProxyAuth(value) {
  if (!value) {
    return false;
  }
  const match = /^Basic\s+(.+)$/i.exec(value);
  if (!match) {
    return false;
  }
  let decoded;
  try {
    decoded = Buffer.from(match[1], "base64").toString("utf8");
  } catch (error) {
    return false;
  }
  const separator = decoded.indexOf(":");
  if (separator < 0) {
    return false;
  }
  return decoded.slice(0, separator) === socksUsername && decoded.slice(separator + 1) === socksPassword;
}

function readHttpHeader(socket, maxLength) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    function cleanup() {
      socket.off("data", onData);
      socket.off("close", onClose);
      socket.off("error", onError);
      socket.off("timeout", onTimeout);
    }
    function onData(chunk) {
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length > maxLength) {
        cleanup();
        reject(new Error("HTTP header too large"));
        return;
      }
      const marker = buffer.indexOf("\r\n\r\n");
      const alternateMarker = marker < 0 ? buffer.indexOf("\n\n") : -1;
      const end = marker >= 0 ? marker + 4 : (alternateMarker >= 0 ? alternateMarker + 2 : -1);
      if (end >= 0) {
        cleanup();
        resolve({ header: buffer.subarray(0, end), remaining: buffer.subarray(end) });
      }
    }
    function onClose() {
      cleanup();
      reject(new Error("socket closed"));
    }
    function onError(error) {
      cleanup();
      reject(error);
    }
    function onTimeout() {
      cleanup();
      reject(new Error("socket timeout waiting for SOCKS5 or HTTP CONNECT handshake"));
    }
    socket.on("data", onData);
    socket.on("close", onClose);
    socket.on("error", onError);
    socket.on("timeout", onTimeout);
  });
}

async function authenticateSocksClient(client) {
  const version = await readExactly(client, 1);
  if (version[0] !== 0x01) {
    client.write(Buffer.from([0x01, 0x01]));
    throw new Error("invalid SOCKS auth version");
  }
  const usernameLength = (await readExactly(client, 1))[0];
  const username = (await readExactly(client, usernameLength)).toString("utf8");
  const passwordLength = (await readExactly(client, 1))[0];
  const password = (await readExactly(client, passwordLength)).toString("utf8");
  if (username !== socksUsername || password !== socksPassword) {
    client.write(Buffer.from([0x01, 0x01]));
    throw new Error("SOCKS authentication failed");
  }
  client.write(Buffer.from([0x01, 0x00]));
}

function readSocksAddress(client, atyp) {
  if (atyp === 0x01) {
    return readExactly(client, 4).then((buffer) => ({ host: Array.from(buffer).join(".") }));
  }
  if (atyp === 0x03) {
    return readExactly(client, 1).then((lengthBuffer) => readExactly(client, lengthBuffer[0]))
      .then((buffer) => ({ host: buffer.toString("utf8") }));
  }
  if (atyp === 0x04) {
    return readExactly(client, 16).then((buffer) => {
      const groups = [];
      for (let i = 0; i < 16; i += 2) {
        groups.push(buffer.readUInt16BE(i).toString(16));
      }
      return { host: groups.join(":") };
    });
  }
  writeSocksReply(client, 0x08);
  throw new Error("unsupported address type");
}

function readExactly(socket, length) {
  if (length === 0) {
    return Promise.resolve(Buffer.alloc(0));
  }
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    function cleanup() {
      socket.off("data", onData);
      socket.off("close", onClose);
      socket.off("error", onError);
      socket.off("timeout", onTimeout);
    }
    function onData(chunk) {
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length >= length) {
        cleanup();
        const needed = buffer.subarray(0, length);
        const rest = buffer.subarray(length);
        if (rest.length > 0) {
          socket.unshift(rest);
        }
        resolve(needed);
      }
    }
    function onClose() {
      cleanup();
      reject(new Error("socket closed"));
    }
    function onError(error) {
      cleanup();
      reject(error);
    }
    function onTimeout() {
      cleanup();
      reject(new Error("socket timeout"));
    }
    socket.on("data", onData);
    socket.on("close", onClose);
    socket.on("error", onError);
    socket.on("timeout", onTimeout);
  });
}

function writeSocksReply(client, code) {
  client.write(Buffer.from([0x05, code, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
}

function validateTunnelAuth(line) {
  const parts = line.split(" ");
  if (parts.length !== 3 || parts[0] !== "AUTH2") {
    return { ok: false, reason: "expected AUTH2 username/password handshake" };
  }
  let username;
  let password;
  try {
    username = Buffer.from(parts[1], "base64").toString("utf8");
    password = Buffer.from(parts[2], "base64").toString("utf8");
  } catch (error) {
    return { ok: false, reason: "invalid base64 credentials" };
  }
  if (username !== tunnelUsername || password !== tunnelPassword) {
    return { ok: false, reason: "wrong username/password" };
  }
  return { ok: true, reason: "ok" };
}

function requireSecret(name) {
  const value = process.env[name];
  if (!value) {
    console.error(`${name} must be set.`);
    process.exit(1);
  }
  return value;
}

function parsePort(value, name) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
    console.error(`${name} must be a valid TCP port.`);
    process.exit(1);
  }
  return parsed;
}
