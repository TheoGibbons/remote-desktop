// Self-contained integration test: spawns the relay on a scratch port,
// exercises the protocol, then tears everything down. Run with `npm test`.
import { spawn } from "child_process";
import { once } from "events";
import WebSocket from "ws";

const PORT = 8099 + Math.floor(Math.random() * 300);
const URL = `ws://127.0.0.1:${PORT}/ws`;
const KEY = "test-session-key-1234567890";

const server = spawn(process.execPath, ["server.js"], {
  env: { ...process.env, PORT: String(PORT) },
  stdio: ["ignore", "ignore", "inherit"],
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const ok = (name, cond) => results.push([name, !!cond]);

function connect(device, name, key = KEY) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    const client = { ws, msgs: [], bins: [], welcome: null };
    ws.on("open", () => ws.send(JSON.stringify({ type: "hello", session: key, device, name })));
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

  // 3. Binary relay (video frame)
  pc.ws.send(Buffer.concat([Buffer.from([1]), Buffer.from("fakejpeg")]));
  await sleep(150);
  ok("phone got binary frame", phone.bins.length === 1 && phone.bins[0][0] === 1);

  // 4. Session isolation
  const other = await connect("android", "x", "a-totally-different-key-9999");
  ok("different key is isolated", other.welcome.peers.length === 0);

  // 5. Min key length rejected
  let shortErr = false;
  try { await connect("android", "x", "short"); } catch (e) { shortErr = e.message === "bad-hello"; }
  ok("short key rejected", shortErr);

  // 6. Brute-force rate limit (disconnect after each guess)
  let banned = false;
  for (let i = 0; i < 25 && !banned; i++) {
    await new Promise((res) => {
      const w = new WebSocket(URL);
      w.on("open", () => w.send(JSON.stringify({ type: "hello", session: "guess-" + i + "-aaaaaaaa", device: "android", name: "evil" })));
      w.on("message", (d, b) => { if (!b) { const m = JSON.parse(d); if (m.type === "error" && m.code === "rate-limited") banned = true; w.close(); } });
      w.on("close", res); w.on("error", res);
      setTimeout(res, 400);
    });
  }
  ok("rate limit bans brute force", banned);

  let pass = 0;
  for (const [name, p] of results) { console.log((p ? "PASS" : "FAIL") + "  " + name); if (p) pass++; }
  console.log(`${pass}/${results.length} passed`);
  return pass === results.length;
}

main()
  .then((allPass) => { server.kill(); process.exit(allPass ? 0 : 1); })
  .catch((e) => { console.error(e); server.kill(); process.exit(1); });
