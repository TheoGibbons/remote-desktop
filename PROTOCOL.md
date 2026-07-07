# Remote Desktop Protocol

All communication runs over a single WebSocket connection per device to the relay
server (`/ws`). Messages are either **JSON text frames** (control) or **binary
frames** (video / file data). The server relays messages between devices that
share the same session and never inspects payloads beyond routing. All peer
content is **end-to-end encrypted** (see [End-to-end encryption](#end-to-end-encryption));
the relay only ever sees ciphertext plus minimal routing metadata.

## Pairing model

- Every device is configured once with `{ server URL, session key, device name }`.
- The session key is a long random string (recommend 32+ chars) and is the only
  secret. The **raw key is never sent to the server.** Each device derives, via
  HKDF-SHA256:
  - a **pairing id** — sent to the server (as `hello.session`) to join a session;
  - an **encryption key** — never leaves the device.
  Any devices deriving the same pairing id are joined into one session and may
  control each other; only devices holding the same raw key can decrypt the
  traffic. A mismatched key therefore simply fails to pair.
- The server rate-limits join attempts per IP so the pairing id cannot be
  brute-forced by spamming (see below).

## JSON messages

Every JSON message has a `type`. Messages forwarded between peers get a `from`
field (peer id) added by the server. A message may include `to: <peerId>` to
route to one peer; otherwise it is broadcast to all other peers in the session.

### Client → Server (handled by server)

| type    | fields                                   | notes |
|---------|------------------------------------------|-------|
| `hello` | `session` (the derived **pairing id**, not the raw key), `device` (`windows`\|`android`), `name`, `uid`? | First message after connect. Cleartext. `uid` is a stable random per-install id; when a device reconnects with the same `uid`, the server kicks its stale connection from the session (and broadcasts `peer-left`) so the device is never listed twice. |

### Server → Client

| type          | fields                                | notes |
|---------------|----------------------------------------|-------|
| `welcome`     | `id` (your peer id), `peers` (array of `{id, device, name}`) | Reply to `hello`. |
| `peer-joined` | `peer` `{id, device, name}`            | |
| `peer-left`   | `id`                                   | |
| `error`       | `code`, `message`                      | e.g. `rate-limited`, `bad-hello`. Connection is closed after. |

### Peer ↔ Peer (end-to-end encrypted)

The messages below are the **inner, decrypted** messages. On the wire each one is
carried inside an `enc` envelope (see [End-to-end encryption](#end-to-end-encryption)):
the sender encrypts the inner JSON and lifts its `to` (if any) into the cleartext
envelope so the relay can still route it; the receiver decrypts and sees the
server-added `from`. Broadcast (no `to`) still goes to all other peers.

**Viewing**

| type          | fields | notes |
|---------------|--------|-------|
| `start-view`  | `to`   | Ask peer to start streaming its screen to me. |
| `stop-view`   | `to`   | Stop streaming. |
| `screen-info` | `width`, `height` | Pixel size of the streamed (stitched) surface. Sent by the host when streaming starts and whenever it changes. |
| `request-keyframe` | `to` | Viewer asks the streaming host for a full frame (sent when a sequence gap is detected in dirty-rect patches, throttled to one per ~2 s). |

**Input — controlling Windows** (coordinates normalized 0..1 over the stitched
virtual desktop). The controller may be a phone or another PC — the host injects
these identically either way, so Windows↔Windows control needs no new messages;
the viewer simply decodes the type-3 stream (below) and sends these.

| type     | fields | notes |
|----------|--------|-------|
| `mouse`  | `action` (`move`\|`down`\|`up`), `button` (`left`\|`right`\|`middle`), `x`, `y` | |
| `scroll` | `dx`, `dy` | Wheel notches; positive `dy` scrolls up. |
| `key`    | `action` (`down`\|`up`\|`press`), `code` | Key names: `A`-`Z`, `0`-`9`, `F1`-`F12`, `ENTER`, `ESC`, `TAB`, `SPACE`, `BACKSPACE`, `DELETE`, `INSERT`, `HOME`, `END`, `PGUP`, `PGDN`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `WIN`, `CTRL`, `ALT`, `SHIFT`, `CAPS`, `PRINTSCREEN`, or a single character. |
| `text`   | `text` | Type a unicode string (used for normal typing without modifiers). |
| `cad`    | – | Request Ctrl+Alt+Del. The real secure attention sequence can't be injected by normal apps, so the host calls `SendSAS` where policy allows it and opens Task Manager otherwise. |

**Input — controlling Android** (coordinates normalized 0..1 over the phone screen)

| type    | fields | notes |
|---------|--------|-------|
| `tap`   | `x`, `y` | |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `ms` | Drag gesture over `ms` milliseconds. |
| `touch` | `action` (`down`\|`move`\|`up`), `x`, `y` | Streamed pointer for real-time press / long-press / drag. The phone plays it as one continued accessibility stroke: the finger stays down from `down` until `up`. Senders should throttle `move` to ~30 ms. |
| `pinch` | `x`, `y`, `dir` (`in`\|`out`) | Two-finger pinch step centered at `x`,`y` (e.g. one mouse-wheel notch). |
| `back` / `homebtn` / `recents` | – | Global navigation actions. |

**File system** (`path` uses the native separators of the target device)

| type             | fields | notes |
|------------------|--------|-------|
| `fs-list`        | `to`, `path`, `reqId` | Empty `path` = roots (drives on Windows, storage root on Android). |
| `fs-list-result` | `reqId`, `path`, `entries` `[{name, dir, size, mtime}]`, `error?` | |
| `fs-get`         | `to`, `path`, `xferId` | Ask peer to send me a file. |
| `fs-begin`       | `to`, `xferId`, `name`, `size` | Sender announces an incoming file (used for both push-upload and get-response). |
| `fs-end`         | `to`, `xferId`, `ok`, `error?` | |

## Binary frames

The **first byte is the cleartext frame type**; the rest is the encrypted
payload (`nonce(12) || ciphertext || tag(16)`, AES-256-GCM, AAD = the frame-type
byte). After decryption the payload is:

| byte | meaning     | decrypted payload |
|------|-------------|-------------------|
| `1`  | video frame | `JPEG bytes` — one full JPEG image of the streamed screen. (Still used by the Android host; the Windows host streams type `3`.) |
| `2`  | file chunk  | `[xferId: uint32 BE][data bytes]` — sequential chunks (≤ 256 KiB) for the transfer announced by `fs-begin`. |
| `3`  | screen patch | Dirty-rect update, all integers big-endian: `[seq u32][flags u8][surfW u16][surfH u16][rectCount u16]` followed by `rectCount` × `[x u16][y u16][w u16][h u16][jpegLen u32][JPEG bytes]`. `flags` bit 0 = keyframe (a single rect covering the whole surface). |

**Dirty-rect streaming (type 3).** The host compares each captured frame to the
previous one on a 64 px block grid and sends only the changed regions as JPEG
tiles, so a static screen costs (near) zero bandwidth. Tiles are absolute pixel
content: the viewer blits them into a persistent `surfW × surfH` canvas, and a
*lost* patch (they may be dropped under backpressure) only leaves those regions
stale — there is no codec-style corruption. Recovery rules:

- `seq` increments per patch **including dropped ones**, so a viewer detects
  loss as a gap and sends `request-keyframe`.
- The host sends a keyframe on stream start / `request-keyframe` / surface
  resize, and every ~10 s while deltas are being sent (safety net).
- A viewer that has no canvas yet ignores deltas and asks for a keyframe.

So the wire layout is `[frameType(1)][nonce(12)][ciphertext][tag(16)]`. Binary
frames are broadcast by the server to all other peers in the session (sessions
are expected to hold 2 devices; the design tolerates more).

## End-to-end encryption

Everything except the `hello` and the server's own control messages
(`welcome` / `peer-*` / `error`) is encrypted so the relay cannot read it.

**Key derivation** (HKDF-SHA256, RFC 5869), from the UTF-8 session key:

```
salt   = "remote-desktop/v1"
encKey = HKDF(ikm=sessionKey, salt, info="e2ee-key", 32)   # AES-256 key, stays on-device
pairId = HKDF(ikm=sessionKey, salt, info="pair-id", 32)    # lowercase hex, sent as hello.session
```

Because `pairId` and `encKey` come from independent HKDF `info` labels, learning
the `pairId` (which the server necessarily sees) reveals nothing about `encKey`.

**AEAD:** AES-256-GCM, fresh random 12-byte nonce per message, 16-byte tag. The
associated data is a single **channel** byte, binding each ciphertext to its
context: `0` = JSON control, `1` = video, `2` = file chunk. Frame =
`nonce(12) || ciphertext || tag(16)`.

**Control envelope** (text frame): the inner control message is encrypted on
channel 0 and wrapped as

```json
{ "type": "enc", "to": "<peerId>"?, "d": "<base64(nonce||ct||tag)>" }
```

The `to` is copied from the inner message so the relay routes it unchanged; the
relay adds `from` to the envelope, and the receiver copies it onto the decrypted
inner message.

**What the relay can still see (metadata):** connection timing/size, IP (for
rate limiting), the `pairId`, each peer's `device` type and chosen `name`, and
`to`/`from` routing ids. It cannot see screen contents, input, file names or file
data.

**Cross-language test vectors** (verified by `server/test-e2ee.mjs`, `Crypto.SelfTest()`
in C#, and `Crypto.selfTest()` in Kotlin):

```
sessionKey = "correct horse battery staple"
encKey     = 198b7e020fa0c8fdfeb3514e6b402eb3ea5af30addadc19153965eeec0e8dabf
pairId     = 9a81be09e574b5a3078107db507c21722e8ea1f9f0a7bf762d6894a5dd371626
channel 0, nonce = 000102030405060708090a0b, plaintext = {"type":"mouse","x":0.5}
  -> 000102030405060708090a0b11316fc47766f9c1dec528cf275cf3fa7d82e9c7f09830195d76f8782e7999f9150b6d97be135331
```

**Not covered:** replay protection (a malicious *active* relay could reorder or
replay ciphertext; GCM guarantees confidentiality + integrity, not freshness)
and metadata privacy. The threat model is a passive/curious relay and network
eavesdroppers.

## Server rate limiting

- Per IP: max **20 `hello` attempts per 10 minutes**. Exceeding it closes the
  connection with `error: rate-limited` and bans the IP for 1 hour.
- Connections that don't send a valid `hello` within 10 seconds are dropped.
- Max 16 concurrent connections per IP.

## Reconnection

Clients auto-reconnect with backoff (2s → 30s max) and re-send `hello`. The
session key never expires; there is no re-authentication, ever.
