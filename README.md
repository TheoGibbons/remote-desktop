# Remote Desktop

Remote control across your devices — **Windows ↔ Android** in both directions and
**Windows ↔ Windows** (computer-to-computer) — plus file transfer between any of
them. Designed to be **set up once and then just work**, with no re-authentication
on every use or every update.

Three components share one small WebSocket protocol ([PROTOCOL.md](PROTOCOL.md)):

| Component | Folder | Tech | Role |
|-----------|--------|------|------|
| Relay server | [`server/`](server/) | Node.js + `ws` | Pairs devices by a shared **session key** and relays traffic. The only always-on, internet-facing piece. |
| Windows app | [`windows/`](windows/) | C# / WPF (.NET 8) | Streams all monitors (stitched) and injects mouse/keyboard; can also **view & control another PC** (full keyboard + mouse); serves & receives files. |
| Android app | [`android/`](android/) | Kotlin | Views & controls the PC (zoom, full keyboard incl. Win/Ctrl/Alt/Shift); shares its own screen so the PC can tap it; serves & receives files. |

## How pairing works (the "set up once" part)

1. Run the relay somewhere both devices can reach (a small VPS, a home server,
   or just your LAN).
2. In **each** app's settings, enter the **same three things once**:
   - the server URL (`ws://host:8090/ws`, or `wss://…` behind TLS),
   - the **same long session key** (tap *Generate* on one device, copy it to the other —
     or click **QR code** on the PC and tap **Scan QR** on the phone to copy the
     server URL + key in one scan),
   - a device name.
3. Press **Save & Connect**. That's it — forever. Both apps auto-reconnect with
   backoff and re-send the key on their own. There is **no login, no token
   expiry, no re-auth after updates**. Any devices holding the same key are in
   one session and can start/stop controlling each other at will.

The session key **is** the authentication, so make it long and secret (the apps
enforce ≥16 chars and the *Generate* button creates a 32-char random one). See
[Security model](#security-model).

## Feature checklist (from the original brief)

**Phone → PC**
- ✅ Always-visible mouse pointer (touchpad-style): drag to move it, tap = left
  click, 2-finger tap = right click, press & hold then drag = drag, 3-finger drag = scroll
- ✅ Full on-screen keyboard including **Win, Ctrl, Alt, Shift**, function keys, arrows, Esc/Tab/etc., plus native-IME typing for long text
- ✅ Multiple monitors **stitched** into one canvas
- ✅ Pinch to **zoom in/out** and 2-finger drag to **pan**

**PC → Phone**
- ✅ Tap / swipe / Back / Home / Recents on the phone screen
- ⚠️ Unlocking a *secure* lockscreen (PIN/pattern/biometric) is **not possible** — Android forbids apps from doing it. A phone with no secure lock (swipe-only) can be unlocked, since the tap lands on the lockscreen surface.

**PC ↔ PC (computer-to-computer)**
- ✅ View and control another Windows PC: the same dirty-rect screen stream, full
  mouse (move / click / scroll), and your **physical keyboard** (shortcuts,
  modifiers, arrows, typing) while the view window is focused, plus a
  **Ctrl+Alt+Del** button. Uses the existing protocol unchanged.

**Both directions**
- ✅ File explorer (separate screen from the remote view) to browse the other device's filesystem and **send files back and forth** (phone *or* PC)
- ✅ Per-IP **rate limiting** on the session-key check so a leaked key can't be brute-forced/spammed

## Quick start

```bash
# Relay + public site, without Traefik
cp .env.example .env
docker compose up -d --build
```

Then build and configure the two apps — see [`windows/README.md`](windows/README.md)
and [`android/README.md`](android/README.md). Point both at your server, paste the
same generated session key into both, and connect.

The root Compose stack runs the relay on `ws://127.0.0.1:8090/ws` and the site
on `http://127.0.0.1:8087`. To run only the relay directly, use
`cd server && npm ci && npm start`.

## Public deployment and releases

The EC2 deployment is designed for the shared
[`hobby-traefik`](https://github.com/TheoGibbons/hobby-traefik) proxy. Traefik
routes `www.remote-desktop.co` to the marketing/download site and
`relay.remote-desktop.co` to the WebSocket relay. Both DNS records point to the
same EC2 instance, and only Traefik publishes ports 80 and 443; the containers'
ports remain private inside Docker.

| File | Purpose |
|---|---|
| `docker-compose.yml` | Proxy-independent relay and site services |
| `docker-compose.override.yml` | Standalone loopback ports |
| `docker-compose.traefik.yml` | Shared-network and HTTP routing labels |
| `docker-compose.prod.yml` | HTTPS and Let's Encrypt labels |
| `scripts/up-local.sh` | Local shared-Traefik startup |
| `scripts/deploy.sh` | Safe EC2 pull, rebuild, and health check |

For local Traefik, start `hobby-traefik`, copy `.env.traefik.example` to
`.env.traefik`, and run `bash scripts/up-local.sh`.

For EC2, clone this repository to `~/projects/remote-desktop`, copy
`.env.production.example` to `.env.production`, point both public DNS records at
the instance, and run `bash scripts/deploy.sh`.
Subsequent pushes to `main` can deploy automatically through
`.github/workflows/deploy.yml`.

Tagged releases such as `v1.0.0` trigger `.github/workflows/release.yml`. The
workflow publishes a self-contained Windows EXE, a signed Android APK, and a
SHA-256 checksum file to GitHub Releases. The public site links to stable
`releases/latest/download/...` assets, so publishing app binaries does not
require committing them or redeploying EC2. See [RELEASING.md](RELEASING.md) for
the one-time GitHub secret and variable setup.

## Security model

- **The session key pairs; devices approve.** The key is the rendezvous +
  encryption secret — treat it like a password (generate it; don't type a weak
  one). But knowing the key is **not enough to control anything**: every install
  has a persistent device identity (EC P-256 keypair), and the first time a
  device connects, the other side shows an approve/deny prompt with a short
  device code to compare. Approved devices reconnect silently forever; each app
  has a devices list to **revoke** one (it must be re-approved) or disconnect it.
  Changing the session key resets all approvals. While an *unapproved* device is
  in the session, hosts also stop streaming/serving files entirely, since those
  frames are broadcast. See [PROTOCOL.md](PROTOCOL.md#device-authentication).
- **End-to-end encrypted.** The raw session key **never leaves your devices**.
  Each device derives (HKDF-SHA256) a *pairing id* it sends to the server and a
  separate *encryption key* it keeps. All screen frames, input, file names and
  file data are encrypted with AES-256-GCM between the two devices, so **the
  relay only ever sees ciphertext.** A wrong key simply fails to pair. The relay
  can still see connection metadata (IP, timing/size, each device's type and
  name, routing ids) — see [PROTOCOL.md](PROTOCOL.md#end-to-end-encryption).
- **Rate limiting:** max 20 join attempts per IP per 10 minutes; exceeding it
  bans the IP for 1 hour. Max 16 concurrent connections per IP. Connections that
  don't present a valid pairing id within 10s are dropped.
- **Transport:** even though content is E2E encrypted, run the relay behind a TLS
  reverse proxy and use `wss://` over the internet (it protects the metadata and
  the pairing id too). Over a trusted LAN, `ws://` is fine.
- **Out of scope:** replay protection against a *malicious active* relay, and
  metadata privacy. The threat model is a passive/curious relay + eavesdroppers.

## Repo layout

```
remote-desktop/
├── PROTOCOL.md                 # the wire protocol both apps implement
├── server/                     # Node relay (build & run here)
├── windows/RemoteDesktopWin/   # .NET 8 WPF app
└── android/                    # Gradle/Kotlin app (open in Android Studio)
```
