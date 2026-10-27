// Self-contained integration test: spawns the relay on a scratch port,
// exercises the protocol, then tears everything down. Run with `npm test`.
import { spawn } from "child_process";
import { once } from "events";
import net from "net";
import WebSocket from "ws";

const PORT = 8099 + Math.floor(Math.random() * 300);
const URL = `ws://127.0.0.1:${PORT}/ws`;
const KEY = "test-session-key-1234567890";

const server = spawn(process.execPath, ["server.js"], {
  env: { ...process.env, PORT: String(PORT) },
  stdio: ["ignore", "pipe", "inherit"],
});
let relayLog = "";
server.stdout.on("data", (d) => (relayLog += d));
const leaveLine = (name) => relayLog.split(/\r?\n/).find((l) => l.startsWith(`[leave] ${name} `)) ?? "";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const ok = (name, cond) => results.push([name, !!cond]);

function connect(device, name, key = KEY, uid = undefined) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    const client = { ws, msgs: [], bins: [], welcome: null, closed: false };
    ws.on("close", () => (client.closed = true));
    ws.on("open", () => ws.send(JSON.stringify({ type: "hello", session: key, device, name, uid })));
    ws.on("message", (data, isBinary) => {
      if (isBinary) return void client.bins.push(data);
      const m = JSON.parse(data.toString());
      client.msgs.push(m);
      if (m.type === "welcome") { client.welcome = m; resolve(client); }
      if (m.type === "error") reject(new Error(m.code));
    });
    ws.on("error", reject);
    setTimeout(() => reject(new Error("welcome timeout")), 3000);
  });
}

async function main() {
  // Wait for the server to be listening.
  for (let i = 0; i < 50; i++) {
    try { const w = new WebSocket(URL); await once(w, "open"); w.close(); break; }
    catch { await sleep(100); }
  }

  // 1. Pairing
  const pc = await connect("windows", "my-pc");
  const phone = await connect("android", "my-phone");
  ok("pc welcome has empty peers", pc.welcome.peers.length === 0);
  ok("phone welcome lists pc", phone.welcome.peers.some((p) => p.device === "windows"));
  await sleep(150);
  ok("pc notified of phone join", pc.msgs.some((m) => m.type === "peer-joined" && m.peer.device === "android"));

  // 2. JSON routing + broadcast
  phone.ws.send(JSON.stringify({ type: "start-view", to: pc.welcome.id }));
  phone.ws.send(JSON.stringify({ type: "mouse", action: "down", button: "left", x: 0.5, y: 0.5 }));
  await sleep(150);
  ok("pc got routed start-view w/ from", pc.msgs.some((m) => m.type === "start-view" && m.from === phone.welcome.id));
  ok("pc got broadcast mouse", pc.msgs.some((m) => m.type === "mouse" && m.x === 0.5));

  // 3. Binary relay (video frame + dirty-rect patch)
  pc.ws.send(Buffer.concat([Buffer.from([1]), Buffer.from("fakejpeg")]));
  pc.ws.send(Buffer.concat([Buffer.from([3]), Buffer.from("fakepatch")]));
  await sleep(150);
  ok("phone got binary frame", phone.bins.some((b) => b[0] === 1));
  ok("phone got patch frame", phone.bins.some((b) => b[0] === 3));

  // 3b. Addressed binary frames reach only the peers they name, unwrapped to
  // the plain [type][payload] a receiver always gets. With a third device in
  // the session, a viewer otherwise receives every host's stream.
  ok("welcome says binary frames can be addressed", pc.welcome.binaryTo === true);
  const phone2 = await connect("android", "my-other-phone");
  await sleep(150);
  phone.bins.length = 0;
  const addressed = (type, ids, payload) => Buffer.concat([
    Buffer.from([0x80 | type, ids.length]),
    ...ids.map((id) => Buffer.concat([Buffer.from([Buffer.byteLength(id)]), Buffer.from(id)])),
    Buffer.from(payload),
  ]);
  pc.ws.send(addressed(3, [phone.welcome.id], "for-phone"));
  phone2.ws.send(addressed(1, [pc.welcome.id], "for-pc"));
  pc.ws.send(addressed(2, [phone.welcome.id, phone2.welcome.id, phone.welcome.id], "chunk"));
  await sleep(150);
  ok("addressed frame reaches its recipient unwrapped",
    phone.bins.some((b) => b.equals(Buffer.from("\x03for-phone"))));
  ok("addressed frame skips other peers", !phone2.bins.some((b) => b.includes("for-phone")));
  ok("phone's frame reaches only the pc",
    pc.bins.some((b) => b.equals(Buffer.from("\x01for-pc"))) && !phone.bins.some((b) => b.includes("for-pc")));
  ok("frame addressed to several peers reaches each once",
    phone.bins.filter((b) => b.includes("chunk")).length === 1 &&
    phone2.bins.filter((b) => b.includes("chunk")).length === 1);
  const before = phone.bins.length + phone2.bins.length;
  pc.ws.send(Buffer.from([0x83, 2, 5, 0x31])); // recipient list runs past the end
  pc.ws.send(Buffer.from([0x83, 0, 1, 2, 3])); // addressed to nobody
  await sleep(150);
  ok("malformed addressed frames are dropped", phone.bins.length + phone2.bins.length === before);
  phone2.ws.close(1000, "bye");
  await sleep(150);
  ok("leave log says the device closed, and how",
    /^\[leave\] my-other-phone \(android\) after \d+s: closed by the device \(1000: bye\), session peers=2$/
      .test(leaveLine("my-other-phone")));

  // A socket that just ends, with no close frame, is the network's doing.
  const vanished = await connect("android", "vanishing-phone");
  vanished.ws.terminate();
  await sleep(150);
  ok("leave log tells a lost connection from a closed one",
    leaveLine("vanishing-phone").includes(": connection lost (1006"));

  // 4. Reconnect with the same device uid replaces the stale peer entry
  const tab1 = await connect("android", "my-tab", KEY, "uid-tablet-1");
  await sleep(150);
  const tab2 = await connect("android", "my-tab", KEY, "uid-tablet-1");
  await sleep(150);
  ok("old connection is kicked on uid rejoin", tab1.closed);
  ok("rejoin welcome has no duplicate of itself",
    !tab2.welcome.peers.some((p) => p.id === tab1.welcome.id));
  ok("peers notified stale entry left",
    pc.msgs.some((m) => m.type === "peer-left" && m.id === tab1.welcome.id));
  ok("leave log says the stale entry was replaced",
    leaveLine("my-tab").includes(": replaced by its new connection,"));
  tab2.ws.close();
  await sleep(150);

  // Replacing a session's only peer must keep the replacement registered.
  // The old socket may still be alive when a desktop reconnects after a
  // network change; a later phone must discover and communicate with it.
  const soloKey = "solo-reconnect-session-1234567890";
  const solo1 = await connect("windows", "solo-pc", soloKey, "uid-solo-pc");
  const solo2 = await connect("windows", "solo-pc", soloKey, "uid-solo-pc");
  const soloPhone = await connect("android", "solo-phone", soloKey);
  await sleep(150);
  ok("sole peer replacement closes old socket", solo1.closed);
  ok("phone discovers reconnected sole desktop",
    soloPhone.welcome.peers.some((p) => p.id === solo2.welcome.id));
  ok("reconnected sole desktop notified of phone join",
    solo2.msgs.some((m) => m.type === "peer-joined" && m.peer.id === soloPhone.welcome.id));
  soloPhone.ws.send(JSON.stringify({ type: "start-view", to: solo2.welcome.id }));
  solo2.ws.send(JSON.stringify({ type: "desktop-ready", to: soloPhone.welcome.id }));
  solo2.ws.send(Buffer.from([1, 42]));
  await sleep(150);
  ok("reconnected sole desktop receives routed view request",
    solo2.msgs.some((m) => m.type === "start-view" && m.from === soloPhone.welcome.id));
  ok("reconnected sole desktop can send control messages",
    soloPhone.msgs.some((m) => m.type === "desktop-ready" && m.from === solo2.welcome.id));
  ok("reconnected sole desktop can send video",
    soloPhone.bins.some((b) => b.equals(Buffer.from([1, 42]))));
  solo2.ws.close();
  soloPhone.ws.close();
  await sleep(150);

  // 5. Session isolation
  const other = await connect("android", "x", "a-totally-different-key-9999");
  ok("different key is isolated", other.welcome.peers.length === 0);

  // 6. Min key length rejected
  let shortErr = false;
  try { await connect("android", "x", "short"); } catch (e) { shortErr = e.message === "bad-hello"; }
  ok("short key rejected", shortErr);

  // 7. A device that keeps reconnecting with its key is not guessing, however
  // often a flaky network drops it (here, more often than the limit allows).
  const flakyKey = "flaky-network-session-1234567890";
  let rejoins = 0;
  for (let i = 0; i < 25; i++) {
    try {
      const c = await connect("android", "flaky-phone", flakyKey, "uid-flaky-phone");
      rejoins++;
      c.ws.close();
      await once(c.ws, "close");
    } catch { break; }
  }
  ok("same-key reconnects are not rate limited", rejoins === 25);

  // 8. Past the per-IP connection limit, the relay says why it refuses.
  const crowd = Array.from({ length: 17 }, () => {
    const c = { ws: new WebSocket(URL), error: null, code: null };
    c.ws.on("message", (d, b) => { if (!b) c.error = JSON.parse(d); });
    c.ws.on("close", (code) => (c.code = code));
    c.ws.on("error", () => {});
    return c;
  });
  await sleep(500);
  ok("connection limit refusal is explained",
    crowd.some((c) => c.error?.code === "too-many-connections" && c.code === 4002));
  for (const c of crowd) c.ws.terminate();
  await sleep(150);

  // 9. Brute-force rate limit (disconnect after each guess)
  let banned = null;
  for (let i = 0; i < 25 && !banned; i++) {
    await new Promise((res) => {
      const w = new WebSocket(URL);
      w.on("open", () => w.send(JSON.stringify({ type: "hello", session: "guess-" + i + "-aaaaaaaa", device: "android", name: "evil" })));
      w.on("message", (d, b) => { if (!b) { const m = JSON.parse(d); if (m.type === "error" && m.code === "rate-limited") banned = m; w.close(); } });
      w.on("close", res); w.on("error", res);
      setTimeout(res, 400);
    });
  }
  ok("rate limit bans brute force", banned);
  ok("ban reports its length", banned?.retryAfter === 3600);

  // 10. While banned, every connection is refused with the time left.
  const refusal = await new Promise((res) => {
    const w = new WebSocket(URL);
    let error = null;
    w.on("message", (d, b) => { if (!b) error = JSON.parse(d); });
    w.on("close", (code) => res({ error, code }));
    w.on("error", () => res({ error, code: null }));
    setTimeout(() => res({ error, code: null }), 2000);
  });
  ok("banned connection is told how long is left",
    refusal.error?.code === "banned" && refusal.error.retryAfter > 3500 && refusal.code === 4001);

  // 11. A malformed frame on a refused connection must not crash the relay.
  const raw = net.connect(PORT, "127.0.0.1");
  raw.on("error", () => {});
  raw.write("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
  await once(raw, "data");
  raw.write(Buffer.from([0xc1, 0x81, 1, 2, 3, 4, 0x41 ^ 1])); // RSV1 set: invalid without compression
  await sleep(300);
  raw.destroy();
  ok("malformed frame on a refused connection keeps the relay up", server.exitCode === null);

  // 12. A device that stops answering pings is dropped, and the log says so.
  // Run on its own relay with a short keepalive so the test needn't wait 60 s.
  const pingPort = PORT + 400;
  const pingRelay = spawn(process.execPath, ["server.js"], {
    env: { ...process.env, PORT: String(pingPort), KEEPALIVE_MS: "200" },
    stdio: ["ignore", "pipe", "inherit"],
  });
  let pingLog = "";
  pingRelay.stdout.on("data", (d) => (pingLog += d));
  for (let i = 0; i < 50 && !pingLog.includes("listening"); i++) await sleep(100);
  const frozen = new WebSocket(`ws://127.0.0.1:${pingPort}/ws`, { autoPong: false });
  frozen.on("error", () => {});
  await once(frozen, "open");
  frozen.send(JSON.stringify({ type: "hello", session: KEY, device: "windows", name: "frozen-pc" }));
  await sleep(800);
  ok("a device that stops answering pings is dropped for it",
    /\[leave\] frozen-pc \(windows\) after \d+s: missed a ping \(no pong within 0\.2s\)/.test(pingLog));
  pingRelay.kill();

  let pass = 0;
  for (const [name, p] of results) { console.log((p ? "PASS" : "FAIL") + "  " + name); if (p) pass++; }
  console.log(`${pass}/${results.length} passed`);
  return pass === results.length;
}

main()
  .then((allPass) => { server.kill(); process.exit(allPass ? 0 : 1); })
  .catch((e) => { console.error(e); server.kill(); process.exit(1); });
