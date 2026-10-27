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
const MAX_KEYS_PER_WINDOW = 20;
const BAN_MS = 60 * 60_000;
const MIN_SESSION_KEY_LEN = 16;

// Per-peer send buffering, by frame type (byte 0 is cleartext — see PROTOCOL.md).
// Video is droppable and latency-critical: buffering it is actively harmful,
// because every queued byte delays the input and control messages sharing the
// same TCP connection. A few hundred KB is roughly one frame in flight; past
// that the newest frame is worth more than the queue, and the sender resends
// the affected regions anyway. File chunks cannot be dropped without silently
// corrupting the transfer, so they keep a generous ceiling.
const MAX_VIDEO_BUFFER = 256 * 1024;
const MAX_FILE_BUFFER = 8 * 1024 * 1024;
const CH_FILE = 2;
// Set on byte 0 of an addressed binary frame, which carries its recipients
// in cleartext ahead of the encrypted payload. See PROTOCOL.md.
const ADDRESSED = 0x80;

/** sessionKeyHash -> Map<peerId, client> */
const sessions = new Map();
/** ip -> { keys: Map<sessionHash, lastTriedMs>, bannedUntil: number, conns: number } */
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
    s = { keys: new Map(), bannedUntil: 0, conns: 0 };
    ipState.set(ip, s);
  }
  return s;
}

function forgetOldKeys(s, now) {
  for (const [hash, t] of s.keys) {
    if (now - t >= HELLO_WINDOW_MS) s.keys.delete(hash);
  }
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

// Rejections with their own close code; every other error closes with 4000.
const CLOSE_CODES = { banned: 4001, "too-many-connections": 4002 };

function closeWithError(ws, code, message, extra = {}) {
  try {
    ws.send(JSON.stringify({ type: "error", code, message, ...extra }));
  } catch {}
  ws.close(CLOSE_CODES[code] ?? 4000, code);
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { "content-type": "text/plain" });
  res.end("remote-desktop relay ok\n");
});

const wss = new WebSocketServer({ server, path: "/ws", maxPayload: 16 * 1024 * 1024 });

let nextPeerId = 1;

wss.on("connection", (ws, req) => {
  // Before any early return: a malformed frame on a rejected connection emits
  // "error", and with no listener that throws and takes down the relay.
  ws.on("error", () => {});

  const ip = ipOf(req);
  const ipS = getIpState(ip);
  const now = Date.now();

  // Clients show these, so a refusal never looks like a network fault.
  if (ipS.bannedUntil > now) {
    closeWithError(ws, "banned", "too many join attempts from this network",
      { retryAfter: Math.ceil((ipS.bannedUntil - now) / 1000) });
    return;
  }
  if (ipS.conns >= MAX_CONNS_PER_IP) {
    closeWithError(ws, "too-many-connections", "too many connections from this network");
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
});

function handleHello(client, msg, helloTimer) {
  const ipS = getIpState(client.ip);
  const now = Date.now();
  const key = typeof msg.session === "string" ? msg.session.trim() : "";
  const sessionHash = hashKey(key);

  // Rate-limit join attempts so session keys can't be brute-forced. Count the
  // distinct keys an IP tries, not its hellos: every guess needs a new key,
  // while a device that keeps reconnecting presents the same one. Counting
  // hellos once banned two phones for an hour because a VPN they shared kept
  // dropping their connections.
  forgetOldKeys(ipS, now);
  ipS.keys.set(sessionHash, now);
  if (ipS.keys.size > MAX_KEYS_PER_WINDOW) {
    ipS.bannedUntil = now + BAN_MS;
    console.log(`[ban] ${client.ip} tried more than ${MAX_KEYS_PER_WINDOW} session keys`);
    closeWithError(client.ws, "rate-limited", "too many join attempts; banned temporarily",
      { retryAfter: BAN_MS / 1000 });
    return;
  }

  if (key.length < MIN_SESSION_KEY_LEN) {
    closeWithError(client.ws, "bad-hello", `session key must be at least ${MIN_SESSION_KEY_LEN} characters`);
    return;
  }

  clearTimeout(helloTimer);
  client.sessionHash = sessionHash;
  client.device = msg.device === "windows" ? "windows" : "android";
  client.name = String(msg.name || client.device).slice(0, 64);
  client.uid = typeof msg.uid === "string" && msg.uid ? msg.uid.slice(0, 64) : null;
  client.id = String(nextPeerId++);

  let peers = sessions.get(client.sessionHash);
  if (!peers) {
    peers = new Map();
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

  // Removing the last stale peer deletes the session from the registry.
  // Re-register this map before admitting the replacement, or it gets a
  // welcome but later peers join a separate map and cannot discover it.
  sessions.set(client.sessionHash, peers);

  send(client, {
    type: "welcome",
    id: client.id,
    peers: [...peers.values()].map((p) => ({ id: p.id, device: p.device, name: p.name })),
    // Clients address their binary frames only once they see this; an older
    // relay would broadcast an addressed frame that no receiver can read.
    binaryTo: true,
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
  if (!peers || data.length === 0) return;
  // Broadcast reaches every device in the session, which with three or more
  // showed one viewer two hosts' streams alternating. An addressed frame goes
  // only to the peers it names.
  let recipients = peers.values();
  if (data[0] & ADDRESSED) {
    const addressed = unwrapAddressed(data);
    if (!addressed) return;
    data = addressed.frame;
    recipients = addressed.to.map((id) => peers.get(id)).filter(Boolean);
  }
  const limit = data[0] === CH_FILE ? MAX_FILE_BUFFER : MAX_VIDEO_BUFFER;
  for (const p of recipients) {
    // Skip peers already backed up: for video that keeps the relay from
    // becoming the buffer that makes the link feel slow, and it bounds memory
    // for a slow viewer either way.
    if (p !== client && p.ws.readyState === p.ws.OPEN && p.ws.bufferedAmount < limit) {
      p.ws.send(data);
    }
  }
}

/**
 * `[0x80 | type][count u8]` then `count` x `[idLen u8][id]`, then the
 * encrypted payload. Receivers get the plain `[type][payload]` they always
 * have, so only senders need to know this format. Null when malformed.
 */
function unwrapAddressed(data) {
  const count = data.length > 1 ? data[1] : 0;
  const to = new Set();
  let off = 2;
  for (let i = 0; i < count; i++) {
    if (off >= data.length) return null;
    const len = data[off++];
    if (off + len > data.length) return null;
    to.add(data.toString("utf8", off, off + len));
    off += len;
  }
  if (to.size === 0) return null;
  const frame = Buffer.allocUnsafe(1 + data.length - off);
  frame[0] = data[0] & ~ADDRESSED;
  data.copy(frame, 1, off);
  return { to: [...to], frame };
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
    forgetOldKeys(s, now);
    if (s.keys.size === 0 && s.conns === 0 && s.bannedUntil < now) ipState.delete(ip);
  }
}, 60_000);

server.listen(PORT, () => {
  console.log(`remote-desktop relay listening on :${PORT} (ws path /ws)`);
});
