// End-to-end-encryption tests: crypto known-answer vectors + a live check that
// the relay only ever forwards ciphertext (the plaintext never appears on the
// wire the server sees). Run via `npm test`.
import { spawn } from "child_process";
import { once } from "events";
import WebSocket from "ws";
import {
  deriveKeys, encrypt, decrypt, encryptWithNonce,
  encryptEnvelope, decryptEnvelope, CH_JSON, CH_VIDEO,
} from "./crypto.mjs";

const results = [];
const ok = (name, cond) => results.push([name, !!cond]);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- 1. Known-answer vectors (must match the C#/Kotlin self-tests) ----
const VEC = {
  sessionKey: "correct horse battery staple",
  encKeyHex: "198b7e020fa0c8fdfeb3514e6b402eb3ea5af30addadc19153965eeec0e8dabf",
  pairId: "9a81be09e574b5a3078107db507c21722e8ea1f9f0a7bf762d6894a5dd371626",
  nonceHex: "000102030405060708090a0b",
  plaintext: '{"type":"mouse","x":0.5}',
  blobHex:
    "000102030405060708090a0b11316fc47766f9c1dec528cf275cf3fa7d82e9c7f09830195d76f8782e7999f9150b6d97be135331",
};

const { encKey, pairId } = deriveKeys(VEC.sessionKey);
ok("HKDF encKey matches vector", encKey.toString("hex") === VEC.encKeyHex);
ok("HKDF pairId matches vector", pairId === VEC.pairId);

const blob = encryptWithNonce(encKey, Buffer.from(VEC.plaintext), CH_JSON, Buffer.from(VEC.nonceHex, "hex"));
ok("GCM KAT blob matches vector", blob.toString("hex") === VEC.blobHex);
ok("decrypt round-trips", decrypt(encKey, blob, CH_JSON)?.toString() === VEC.plaintext);
ok("decrypt wrong channel fails (AAD bound)", decrypt(encKey, blob, CH_VIDEO) === null);

const tampered = Buffer.from(blob);
tampered[20] ^= 0x01;
ok("decrypt tampered fails (integrity)", decrypt(encKey, tampered, CH_JSON) === null);

const rand = encrypt(encKey, Buffer.from("hello"), CH_JSON);
ok("random-nonce round-trips", decrypt(encKey, rand, CH_JSON)?.toString() === "hello");
ok("two encryptions differ (fresh nonce)", !encrypt(encKey, Buffer.from("x"), CH_JSON).equals(encrypt(encKey, Buffer.from("x"), CH_JSON)));

// ---- 2. Live relay: only ciphertext crosses the server ----
const PORT = 8600 + Math.floor(Math.random() * 300);
const URL = `ws://127.0.0.1:${PORT}/ws`;
const server = spawn(process.execPath, ["server.js"], {
  env: { ...process.env, PORT: String(PORT) },
  stdio: ["ignore", "ignore", "inherit"],
});

function connect(device, name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    const client = { ws, id: null, texts: [], bins: [] };
    // Pairing uses the derived pairId, NEVER the raw session key.
    ws.on("open", () => ws.send(JSON.stringify({ type: "hello", session: pairId, device, name })));
    ws.on("message", (data, isBinary) => {
      if (isBinary) return void client.bins.push(Buffer.from(data));
      const raw = data.toString();
      client.texts.push(raw);
      const m = JSON.parse(raw);
      if (m.type === "welcome") { client.id = m.id; resolve(client); }
    });
    ws.on("error", reject);
    setTimeout(() => reject(new Error("welcome timeout")), 3000);
  });
}

async function live() {
  for (let i = 0; i < 50; i++) {
    try { const w = new WebSocket(URL); await once(w, "open"); w.close(); break; }
    catch { await sleep(100); }
  }
  const MARKER = "TOP_SECRET_KEYSTROKE_ABC123";

  const a = await connect("windows", "pc");
  const b = await connect("android", "phone");
  await sleep(150);

  // A sends an encrypted control message addressed to B.
  const env = encryptEnvelope(encKey, { type: "text", text: MARKER, to: b.id });
  a.ws.send(JSON.stringify(env));

  // A sends an encrypted "video" binary frame whose plaintext contains MARKER.
  const vblob = encrypt(encKey, Buffer.from("JPEGDATA-" + MARKER), CH_VIDEO);
  a.ws.send(Buffer.concat([Buffer.from([CH_VIDEO]), vblob]));
  await sleep(200);

  // B received exactly what the relay forwarded.
  const gotText = b.texts.find((t) => JSON.parse(t).type === "enc");
  ok("B received an enc envelope", !!gotText);
  ok("relayed text is ciphertext (no plaintext marker)", gotText != null && !gotText.includes(MARKER));
  const innerDec = decryptEnvelope(encKey, JSON.parse(gotText));
  ok("B decrypts the control message", innerDec?.text === MARKER);
  ok("decrypted message carries server-added from", innerDec?.from === a.id);

  ok("B received a binary frame", b.bins.length === 1);
  const raw = b.bins[0];
  ok("relayed binary is ciphertext (no plaintext marker)", !raw.toString("latin1").includes(MARKER));
  ok("binary frame type byte is cleartext", raw[0] === CH_VIDEO);
  const vdec = decrypt(encKey, raw.subarray(1), CH_VIDEO);
  ok("B decrypts the video frame", vdec?.toString() === "JPEGDATA-" + MARKER);

  a.ws.close(); b.ws.close();
}

live()
  .then(() => {
    let pass = 0;
    for (const [name, p] of results) { console.log((p ? "PASS" : "FAIL") + "  " + name); if (p) pass++; }
    console.log(`${pass}/${results.length} passed`);
    server.kill();
    process.exit(pass === results.length ? 0 : 1);
  })
  .catch((e) => { console.error(e); server.kill(); process.exit(1); });
