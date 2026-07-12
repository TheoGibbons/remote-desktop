// Remote desktop relay server.
// Pairs devices that present the same session key and relays JSON/binary
// frames between them. See ../PROTOCOL.md.

import http from "http";
import crypto from "crypto";
import { WebSocketServer } from "ws";

const PORT = Number(process.env.PORT || 8090);
const HELLO_TIMEOUT_MS = 10_000;
const MAX_CONNS_PER_IP = 16;
const HELLO_WINDOW_MS = 10 * 60_000;
const HELLO_MAX_PER_WINDOW = 20;
const BAN_MS = 60 * 60_000;
const MIN_SESSION_KEY_LEN = 16;

/** sessionKeyHash -> Map<peerId, client> */
const sessions = new Map();
/** ip -> { attempts: number[], bannedUntil: number, conns: number } */
const ipState = new Map();

function ipOf(req) {
  // Honor X-Forwarded-For only if you put this behind a trusted reverse proxy.
  if (process.env.TRUST_PROXY === "1") {
    const xff = req.headers["x-forwarded-for"];
    if (xff) return String(xff).split(",")[0].trim();
  }
  return req.socket.remoteAddress || "unknown";
}

function getIpState(ip) {
  let s = ipState.get(ip);
  if (!s) {
    s = { attempts: [], bannedUntil: 0, conns: 0 };
    ipState.set(ip, s);
  }
  return s;
}

// Session keys are never stored in plain text server-side.
function hashKey(key) {
  return crypto.createHash("sha256").update(key).digest("hex");
}

function send(client, obj) {
  if (client.ws.readyState === client.ws.OPEN) {
    client.ws.send(JSON.stringify(obj));
  }
}

function closeWithError(ws, code, message) {
  try {
    ws.send(JSON.stringify({ type: "error", code, message }));
  } catch {}
  ws.close(4000, code);
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { "content-type": "text/plain" });
  res.end("remote-desktop relay ok\n");
});

const wss = new WebSocketServer({ server, path: "/ws", maxPayload: 16 * 1024 * 1024 });

let nextPeerId = 1;

wss.on("connection", (ws, req) => {
  const ip = ipOf(req);
  const ipS = getIpState(ip);
  const now = Date.now();

  if (ipS.bannedUntil > now) {
    ws.close(4001, "banned");
    return;
  }
  if (ipS.conns >= MAX_CONNS_PER_IP) {
    ws.close(4002, "too-many-connections");
    return;
  }
  ipS.conns++;

  const client = {
    ws,
    ip,
    id: null,
    uid: null,
    name: null,
    device: null,
    sessionHash: null,
    alive: true,
  };

  const helloTimer = setTimeout(() => {
    if (!client.sessionHash) closeWithError(ws, "hello-timeout", "no hello received");
  }, HELLO_TIMEOUT_MS);

  ws.on("pong", () => (client.alive = true));

  ws.on("message", (data, isBinary) => {
    if (isBinary) {
      relayBinary(client, data);
      return;
    }
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      return;
    }
    if (!client.sessionHash) {
      if (msg.type !== "hello") return;
      handleHello(client, msg, helloTimer);
    } else {
      relayJson(client, msg);
    }
  });

  ws.on("close", () => {
    clearTimeout(helloTimer);
    ipS.conns = Math.max(0, ipS.conns - 1);
    leaveSession(client);
  });

  ws.on("error", () => {});
});

function handleHello(client, msg, helloTimer) {
  const ipS = getIpState(client.ip);
  const now = Date.now();

  // Rate-limit join attempts so session keys can't be brute-forced.
  ipS.attempts = ipS.attempts.filter((t) => now - t < HELLO_WINDOW_MS);
  ipS.attempts.push(now);
  if (ipS.attempts.length > HELLO_MAX_PER_WINDOW) {
    ipS.bannedUntil = now + BAN_MS;
    console.log(`[ban] ${client.ip} exceeded hello rate limit`);
    closeWithError(client.ws, "rate-limited", "too many join attempts; banned temporarily");
    return;
  }

  const key = typeof msg.session === "string" ? msg.session.trim() : "";
  if (key.length < MIN_SESSION_KEY_LEN) {
    closeWithError(client.ws, "bad-hello", `session key must be at least ${MIN_SESSION_KEY_LEN} characters`);
    return;
  }

  clearTimeout(helloTimer);
  client.sessionHash = hashKey(key);
  client.device = msg.device === "windows" ? "windows" : "android";
  client.name = String(msg.name || client.device).slice(0, 64);
  client.uid = typeof msg.uid === "string" && msg.uid ? msg.uid.slice(0, 64) : null;
  client.id = String(nextPeerId++);

  let peers = sessions.get(client.sessionHash);
  if (!peers) {
    peers = new Map();
    sessions.set(client.sessionHash, peers);
  }

  // A device that reconnects (new socket, same install uid) replaces its old
  // entry immediately instead of showing up as a duplicate peer until the
  // stale connection times out.
  if (client.uid) {
    for (const p of [...peers.values()]) {
      if (p.uid === client.uid) {
        console.log(`[replace] ${p.name} superseded by new connection`);
        leaveSession(p);
        p.ws.terminate();
      }
    }
  }

  send(client, {
    type: "welcome",
    id: client.id,
    peers: [...peers.values()].map((p) => ({ id: p.id, device: p.device, name: p.name })),
  });

  for (const p of peers.values()) {
    send(p, { type: "peer-joined", peer: { id: client.id, device: client.device, name: client.name } });
  }
  peers.set(client.id, client);
  console.log(`[join] ${client.name} (${client.device}) from ${client.ip}, session peers=${peers.size}`);
}

function leaveSession(client) {
  if (!client.sessionHash) return;
  const hash = client.sessionHash;
  client.sessionHash = null; // idempotent: close after replacement won't re-broadcast
  const peers = sessions.get(hash);
  if (!peers) return;
  peers.delete(client.id);
  for (const p of peers.values()) {
    send(p, { type: "peer-left", id: client.id });
  }
  if (peers.size === 0) sessions.delete(hash);
  console.log(`[leave] ${client.name}, session peers=${peers.size}`);
}

function relayJson(client, msg) {
  const peers = sessions.get(client.sessionHash);
  if (!peers) return;
  msg.from = client.id;
  const payload = JSON.stringify(msg);
  if (msg.to) {
    const target = peers.get(String(msg.to));
    if (target && target.ws.readyState === target.ws.OPEN) target.ws.send(payload);
  } else {
    for (const p of peers.values()) {
      if (p !== client && p.ws.readyState === p.ws.OPEN) p.ws.send(payload);
    }
  }
}

function relayBinary(client, data) {
  const peers = sessions.get(client.sessionHash);
  if (!peers) return;
  for (const p of peers.values()) {
    // Skip peers with a large backlog so a slow viewer doesn't balloon memory.
    if (p !== client && p.ws.readyState === p.ws.OPEN && p.ws.bufferedAmount < 8 * 1024 * 1024) {
      p.ws.send(data);
    }
  }
}

// Keepalive: drop dead connections.
setInterval(() => {
  for (const peers of sessions.values()) {
    for (const c of peers.values()) {
      if (!c.alive) {
        c.ws.terminate();
        continue;
      }
      c.alive = false;
      try {
        c.ws.ping();
      } catch {}
    }
  }
}, 30_000);

// Periodic cleanup of stale IP state.
setInterval(() => {
  const now = Date.now();
  for (const [ip, s] of ipState) {
    s.attempts = s.attempts.filter((t) => now - t < HELLO_WINDOW_MS);
    if (s.attempts.length === 0 && s.conns === 0 && s.bannedUntil < now) ipState.delete(ip);
  }
}, 60_000);

server.listen(PORT, () => {
  console.log(`remote-desktop relay listening on :${PORT} (ws path /ws)`);
});
