// Reference implementation of the end-to-end encryption used by both apps.
// The relay never runs this — it only routes ciphertext. This module exists to
// (a) generate authoritative cross-language test vectors and (b) let the test
// harness and any browser viewer speak the same encrypted protocol.
//
// Derivation (HKDF-SHA256, RFC 5869), from the shared session key:
//   encKey = HKDF(ikm=key, salt=SALT, info="e2ee-key", 32)   // AES-256 key
//   pairId = HKDF(ikm=key, salt=SALT, info="pair-id", 32)     // sent to server
// Frame = nonce(12) || ciphertext || tag(16), AES-256-GCM, AAD = [channel byte].
//   channel 0 = JSON control, 1 = video, 2 = file chunk.
import crypto from "crypto";

export const SALT = Buffer.from("remote-desktop/v1", "utf8");
const INFO_ENC = Buffer.from("e2ee-key", "utf8");
const INFO_PAIR = Buffer.from("pair-id", "utf8");

export const CH_JSON = 0;
export const CH_VIDEO = 1;
export const CH_FILE = 2;

export function deriveKeys(sessionKey) {
  const ikm = Buffer.from(sessionKey, "utf8");
  const encKey = Buffer.from(crypto.hkdfSync("sha256", ikm, SALT, INFO_ENC, 32));
  const pairId = Buffer.from(crypto.hkdfSync("sha256", ikm, SALT, INFO_PAIR, 32)).toString("hex");
  return { encKey, pairId };
}

export function encryptWithNonce(key, plaintext, channel, nonce) {
  const cipher = crypto.createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from([channel]));
  const ct = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([nonce, ct, tag]);
}

export function encrypt(key, plaintext, channel) {
  return encryptWithNonce(key, plaintext, channel, crypto.randomBytes(12));
}

export function decrypt(key, blob, channel) {
  if (blob.length < 28) return null;
  const nonce = blob.subarray(0, 12);
  const tag = blob.subarray(blob.length - 16);
  const ct = blob.subarray(12, blob.length - 16);
  try {
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, nonce);
    decipher.setAAD(Buffer.from([channel]));
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ct), decipher.final()]);
  } catch {
    return null;
  }
}

// ---- JSON envelope helpers ----

/** Wrap a control message object as a cleartext routing envelope with an
 *  encrypted body. `to` (if present on the inner message) is lifted into the
 *  envelope so the relay can still route it. */
export function encryptEnvelope(encKey, innerObj) {
  const blob = encrypt(encKey, Buffer.from(JSON.stringify(innerObj), "utf8"), CH_JSON);
  const env = { type: "enc", d: blob.toString("base64") };
  if (innerObj.to != null) env.to = innerObj.to;
  return env;
}

export function decryptEnvelope(encKey, env) {
  const pt = decrypt(encKey, Buffer.from(env.d, "base64"), CH_JSON);
  if (!pt) return null;
  const inner = JSON.parse(pt.toString("utf8"));
  if (env.from != null) inner.from = env.from;
  return inner;
}

// ---- vector generation: `node crypto.mjs --vectors` ----

if (process.argv[2] === "--vectors") {
  const sessionKey = "correct horse battery staple";
  const { encKey, pairId } = deriveKeys(sessionKey);
  const nonce = Buffer.from("000102030405060708090a0b", "hex");
  const plaintext = Buffer.from('{"type":"mouse","x":0.5}', "utf8");
  const blob = encryptWithNonce(encKey, plaintext, CH_JSON, nonce);
  const vectors = {
    sessionKey,
    encKeyHex: encKey.toString("hex"),
    pairId,
    kat: {
      keyHex: encKey.toString("hex"),
      nonceHex: nonce.toString("hex"),
      channel: CH_JSON,
      plaintextUtf8: plaintext.toString("utf8"),
      blobHex: blob.toString("hex"),
    },
  };
  console.log(JSON.stringify(vectors, null, 2));
}
