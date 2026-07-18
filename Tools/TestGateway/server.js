"use strict";

const net = require("net");

const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 9090;
const MAX_LINE_LENGTH = 256;
const AUTH_TIMEOUT_MS = 10_000;
const IDLE_TIMEOUT_MS = 45_000;

const expectedToken = process.env.TEST_TUNNEL_TOKEN;
if (!expectedToken) {
  console.error("TEST_TUNNEL_TOKEN must be set.");
  process.exit(1);
}

const host = process.env.TEST_GATEWAY_HOST || DEFAULT_HOST;
const port = parsePort(process.env.TEST_GATEWAY_PORT || String(DEFAULT_PORT));

const server = net.createServer((socket) => {
  const remote = `${socket.remoteAddress}:${socket.remotePort}`;
  console.log(`client connected: ${remote}`);

  let buffer = "";
  let authenticated = false;
  socket.setTimeout(AUTH_TIMEOUT_MS);

  socket.on("data", (chunk) => {
    buffer += chunk.toString("utf8");
    if (buffer.length > MAX_LINE_LENGTH * 2) {
      closeWithError(socket, "line too long");
      return;
    }

    let newlineIndex;
    while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
      const rawLine = buffer.slice(0, newlineIndex);
      buffer = buffer.slice(newlineIndex + 1);
      const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;

      if (line.length > MAX_LINE_LENGTH) {
        closeWithError(socket, "line too long");
        return;
      }

      if (!authenticated) {
        handleAuth(socket, line);
        authenticated = true;
        socket.setTimeout(IDLE_TIMEOUT_MS);
        continue;
      }

      if (line === "PING") {
        socket.write("PONG\n");
      } else {
        closeWithError(socket, "unexpected command");
      }
    }
  });

  socket.on("timeout", () => {
    closeWithError(socket, "timeout");
  });

  socket.on("close", () => {
    console.log(`client disconnected: ${remote}`);
  });

  socket.on("error", (error) => {
    console.log(`client socket error: ${remote} ${error.code || "unknown"}`);
  });
});

server.listen(port, host, () => {
  console.log(`test gateway listening on ${host}:${port}`);
});

function handleAuth(socket, line) {
  const prefix = "AUTH ";
  if (!line.startsWith(prefix)) {
    socket.write("ERROR\n");
    socket.end();
    return;
  }

  const suppliedToken = line.slice(prefix.length);
  if (suppliedToken !== expectedToken) {
    socket.write("ERROR\n");
    socket.end();
    return;
  }

  socket.write("OK\n");
}

function closeWithError(socket, reason) {
  console.log(`closing client: ${reason}`);
  socket.end("ERROR\n");
}

function parsePort(value) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
    console.error("TEST_GATEWAY_PORT must be a valid TCP port.");
    process.exit(1);
  }
  return parsed;
}
