// Remote desktop relay server.
// Pairs devices that present the same session key and relays JSON/binary
// frames between them. See ../PROTOCOL.md.

import http from "http";
import crypto from "crypto";
import fs from "fs";
import { WebSocketServer } from "ws";

const PORT = Number(process.env.PORT || 8090);
// How often each device is pinged; one missed pong drops it. Tests shorten it.
const KEEPALIVE_MS = Number(process.env.KEEPALIVE_MS || 30_000);
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

// The apps learn of new releases from the relay rather than each asking
// GitHub. Pushing main deploys the relay minutes before the tag build has
// published the apps, and a failed build publishes nothing, so the relay
// announces what GitHub has released, never its own version.
// RELEASES_URL="" turns the check off.
const RELEASES_URL = process.env.RELEASES_URL ??
  "https://api.github.com/repos/TheoGibbons/remote-desktop/releases/latest";
const RELEASE_CHECK_MS = Number(process.env.RELEASE_CHECK_MS || 10 * 60_000);
// A release exists before its files have finished uploading.
const RELEASE_ASSETS = ["RemoteDesktop-Windows-x64.exe", "RemoteDesktop-Android.apk"];
const VERSION_RE = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?$/;
const RELAY_VERSION = (() => {
  try { return JSON.parse(fs.readFileSync(new URL("./package.json", import.meta.url), "utf8")).version; }
  catch { return "unknown"; }
})();

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
  res.end(`remote-desktop relay ok\nrelay v${RELAY_VERSION}\nlatest apps ${latestVersion ? "v" + latestVersion : "unknown"}\n`);
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
    version: null,
    sessionHash: null,
    alive: true,
    joinedAt: 0,
    // Why the relay dropped this device, when it was the relay's decision.
    dropReason: null,
    socketError: null,
  };
  ws.on("error", (e) => (client.socketError = e.message));

  const helloTimer = setTimeout(() => {
    if (!client.sessionHash) closeWithError(ws, "hello-timeout", "no hello received");
  }, HELLO_TIMEOUT_MS);

  ws.on("pong", () => (client.alive = true));

  ws.on("message", (data, isBinary) => {
    // An error that escapes this listener ends the process, and with it every
    // session on the relay. Whatever a device sends, the most it may cost is
    // that device's own connection.
    try {
      handleMessage(client, data, isBinary, helloTimer);
    } catch (e) {
      const what = e instanceof Error ? e.message.slice(0, 120) : "error";
      console.log(`[bad-message] from ${client.ip}: ${what}`);
      client.dropReason = "sent a message the relay could not handle";
      ws.terminate();
    }
  });

  ws.on("close", (code, reason) => {
    clearTimeout(helloTimer);
    ipS.conns = Math.max(0, ipS.conns - 1);
    leaveSession(client, client.dropReason ?? describeClose(client, code, reason));
  });
});

function handleMessage(client, data, isBinary, helloTimer) {
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
  // `null`, a number, a string and a list are all valid JSON and none is a
  // message. Reading `type` off null threw, which took the relay down with
  // every session on it, for one frame from anyone who could connect.
  if (msg === null || typeof msg !== "object" || Array.isArray(msg)) return;
  if (!client.sessionHash) {
    if (msg.type !== "hello") return;
    handleHello(client, msg, helloTimer);
  } else {
    relayJson(client, msg);
  }
}

// A field of a device's message is used only when it is the kind of value
// expected. Turning one into text with String() throws for an object such as
// {"toString":0}, and anyone who can connect can send that.
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
  client.name = (typeof msg.name === "string" && msg.name ? msg.name : client.device).slice(0, 64);
  client.uid = typeof msg.uid === "string" && msg.uid ? msg.uid.slice(0, 64) : null;
  // The app's version, so each device can tell when another is behind.
  client.version = typeof msg.version === "string" && VERSION_RE.test(msg.version) ? msg.version : null;
  client.id = String(nextPeerId++);
  client.joinedAt = Date.now();

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
        leaveSession(p, "replaced by its new connection");
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
    peers: [...peers.values()].map(peerInfo),
    // Clients address their binary frames only once they see this; an older
    // relay would broadcast an addressed frame that no receiver can read.
    binaryTo: true,
    // Left out until GitHub has answered.
    latest: latestVersion ?? undefined,
  });

  for (const p of peers.values()) {
    send(p, { type: "peer-joined", peer: peerInfo(client) });
  }
  peers.set(client.id, client);
  console.log(`[join] ${client.name} (${client.device} ${client.version ? "v" + client.version : "unknown version"}) ` +
    `from ${client.ip}, session peers=${peers.size}`);
}

/** A device as other devices' peer lists show it. Apps from before the
 *  version field send none, and then it is left out. */
function peerInfo(p) {
  return { id: p.id, device: p.device, name: p.name, version: p.version ?? undefined };
}

/**
 * Logged with every leave, so a device that keeps dropping says which end gave
 * up: the relay (missed pings), the device (a close frame), or the network.
 */
function describeClose(client, code, reason) {
  const why = reason?.length ? `: ${reason}` : "";
  if (code === 1006) {
    // No close frame: the socket ended without either side saying goodbye.
    return `connection lost (1006${client.socketError ? `: ${client.socketError}` : ""})`;
  }
  return `closed by the device (${code}${why})`;
}

function leaveSession(client, reason) {
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
  const secs = Math.round((Date.now() - client.joinedAt) / 1000);
  console.log(`[leave] ${client.name} (${client.device}) after ${secs}s: ${reason}, session peers=${peers.size}`);
}

function relayJson(client, msg) {
  const peers = sessions.get(client.sessionHash);
  if (!peers) return;
  // Peer ids are text. An address that is anything else names nobody, and is
  // not sent to everybody in its place.
  let to = msg.to;
  if (typeof to === "number" && to) to = String(to);
  if (to && typeof to !== "string") return;
  msg.from = client.id;
  const payload = JSON.stringify(msg);
  if (to) {
    const target = peers.get(to);
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
        c.dropReason = `missed a ping (no pong within ${KEEPALIVE_MS / 1000}s)`;
        c.ws.terminate();
        continue;
      }
      c.alive = false;
      try {
        c.ws.ping();
      } catch {}
    }
  }
}, KEEPALIVE_MS);

// The newest published app release, or null until GitHub has answered.
let latestVersion = null;
let lastReleaseProblem = null;

async function checkLatestRelease() {
  let problem = null;
  try {
    const res = await fetch(RELEASES_URL, {
      headers: { accept: "application/vnd.github+json", "user-agent": "remote-desktop-relay" },
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const release = await res.json();
    const version = String(release.tag_name ?? "").replace(/^v/, "");
    const uploaded = new Set((release.assets ?? []).filter((a) => a.state === "uploaded").map((a) => a.name));
    if (!VERSION_RE.test(version) || release.draft || release.prerelease) {
      problem = `ignored release ${JSON.stringify(release.tag_name)}`;
    } else if (!RELEASE_ASSETS.every((name) => uploaded.has(name))) {
      problem = `v${version} is still uploading`;
    } else if (version !== latestVersion) {
      latestVersion = version;
      console.log(`[release] latest apps are v${version}`);
      // Devices stay connected for days, so tell them now, not on their next join.
      for (const peers of sessions.values()) {
        for (const c of peers.values()) send(c, { type: "latest", version });
      }
    }
  } catch (e) {
    problem = `check failed: ${e.message}`;
  }
  // Logged once per change, so a long GitHub outage is one line, not one every check.
  if (problem && problem !== lastReleaseProblem) console.log(`[release] ${problem}`);
  lastReleaseProblem = problem;
}

if (RELEASES_URL) {
  checkLatestRelease();
  setInterval(checkLatestRelease, RELEASE_CHECK_MS);
}

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
