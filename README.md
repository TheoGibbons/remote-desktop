# Remote Desktop

Remote Desktop pairs your own devices over one small WebSocket protocol so you can
control a Windows PC from Android, control Android from Windows, control one PC from
another, and move files in any of those directions — configured once with a shared
session key, with no login, no token expiry and no re-authentication after updates.
This repository holds the always-on relay server, the public download site, and both
client apps.

## Deploying to LIVE

Standalone. This stack publishes its own host ports and serves plain HTTP on port 80;
no shared proxy is involved.

```bash
git clone https://github.com/TheoGibbons/remote-desktop.git ~/projects/remote-desktop
cd ~/projects/remote-desktop
cp .env.production.example .env

# Set before continuing:
#   USE_TRAEFIK        0, so deploy.sh takes the standalone path
#   SITE_BIND          0.0.0.0, so the site is reachable from off the host
#   SITE_PORT          80
#   RELAY_BIND         0.0.0.0
#   RELAY_PORT         8090, the port both apps dial as ws://<host>:8090/ws
#   PUBLIC_SITE_URL    absolute URL the site answers on, no trailing slash,
#                      e.g. http://203.0.113.10
#   RELAY_TRUST_PROXY  1 only if you put your own reverse proxy in front of the
#                      relay, otherwise 0
#   SITE_HOST and RELAY_HOST are unused on this path and can be left alone
nano .env

bash scripts/deploy.sh
```

TLS is the operator's job on this path — nothing here terminates HTTPS. The usual
options are Cloudflare in front of port 80, or an nginx or Caddy reverse proxy on the
host that holds the certificate and forwards to these ports. If you do add a proxy, set
`RELAY_TRUST_PROXY=1` so the relay's per-IP rate limiting reads `X-Forwarded-For`
rather than the proxy's own address.

Open these ports in the EC2 security group, and only these:

| TCP port | Allowed source | Purpose |
|---|---|---|
| 22 | Administrator IP | SSH and deployment |
| 80 | Public internet | Download site over HTTP |
| 8090 | Public internet | WebSocket relay, `ws://<host>:8090/ws` |

The security group is the enforcement boundary, not a host firewall. Docker publishes a
port by writing DNAT rules straight into iptables, which bypasses `ufw` — a `ufw deny`
rule does not close a published container port.

## Deploying to LIVE with hobby-traefik

The shared hobby-traefik proxy terminates TLS on 443 with Let's Encrypt and routes to
these containers over the external `traefik-public` Docker network. Deploy hobby-traefik
on the instance first; on this path this stack publishes no host ports of its own. Point
DNS A records for both hostnames at the instance before deploying, or Let's Encrypt
cannot issue certificates.

```bash
git clone https://github.com/TheoGibbons/remote-desktop.git ~/projects/remote-desktop
cd ~/projects/remote-desktop
cp .env.production.example .env

# Set before continuing:
#   USE_TRAEFIK      1, so deploy.sh adds the Traefik and Let's Encrypt overlays
#   SITE_HOST        hostname for the download site, with a DNS A record pointing
#                    at this instance, e.g. www.remote-desktop.co
#   RELAY_HOST       hostname for the relay, its own DNS A record pointing at this
#                    instance, e.g. relay.remote-desktop.co
#   PUBLIC_SITE_URL  https://<SITE_HOST>, no trailing slash
#   SITE_BIND, SITE_PORT, RELAY_BIND and RELAY_PORT are unused on this path
nano .env

bash scripts/deploy.sh
```

Both apps then connect to `wss://<RELAY_HOST>/ws`.

| TCP port | Allowed source | Purpose |
|---|---|---|
| 22 | Administrator IP | SSH and deployment |
| 80, 443 | Public internet | Traefik HTTP/HTTPS |

The security group is the enforcement boundary, not a host firewall. Docker publishes a
port by writing DNAT rules straight into iptables, which bypasses `ufw` — a `ufw deny`
rule does not close a published container port.

## Deploying Locally

Loopback ports on your own machine. No DNS, no shared network, no proxy.

```bash
git clone https://github.com/TheoGibbons/remote-desktop.git ~/projects/remote-desktop
cd ~/projects/remote-desktop
cp .env.example .env

# Nothing here is required — .env.example runs as it ships. Change it only if a
# port is already busy:
#   SITE_PORT        host port for the download site, default 8087
#   RELAY_PORT       host port for the relay, default 8090
#   PUBLIC_SITE_URL  must match SITE_PORT, e.g. http://localhost:8087
nano .env

bash scripts/up-local.sh
```

The site is then on <http://127.0.0.1:8087> and the relay on `ws://127.0.0.1:8090/ws`.
Put that relay URL and one generated session key into both apps.

## Deploying Locally with hobby-traefik

Routed through a locally running hobby-traefik on `*.localhost`, port 8085. Start
hobby-traefik first — it creates the external `traefik-public` network this stack joins.

```bash
git clone https://github.com/TheoGibbons/remote-desktop.git ~/projects/remote-desktop
cd ~/projects/remote-desktop
cp .env.traefik.example .env.traefik

# Nothing here is required — .env.traefik.example runs as it ships. Change it only
# to use different names:
#   SITE_HOST        must end in .localhost, default remote-desktop.localhost
#   RELAY_HOST       must end in .localhost, default relay.remote-desktop.localhost
#   PUBLIC_SITE_URL  http://<SITE_HOST>:8085
nano .env.traefik

bash scripts/up-local-traefik.sh
```

The site is then on <http://remote-desktop.localhost:8085> and the relay on
`ws://relay.remote-desktop.localhost:8085/ws`. Names under `.localhost` resolve to
127.0.0.1 without a hosts-file entry.

## Setup push-to-deploy

A push to `main` runs `.github/workflows/deploy.yml`, which SSHes to the instance and
runs `bash scripts/deploy.sh` in `~/projects/remote-desktop`.

**Part 1 — once per server, not once per project.** The instance needs to read GitHub
so `git pull --ff-only` works. Skip this if another project on the same box has
already done it; skip it entirely if this repository is public.

```bash
# On the EC2 instance. Create a fine-grained PAT with Contents: Read-only,
# scoped to the repositories this box deploys:
#   https://github.com/settings/personal-access-tokens
read -rsp 'PAT: ' PAT && echo

git config --global credential.helper store
printf 'https://x-access-token:%s@github.com\n' "$PAT" > ~/.git-credentials
chmod 600 ~/.git-credentials
```

A deploy key cannot be shared between projects: GitHub binds one to a single
repository and rejects the same key on a second with "Key is already in use". One
PAT covers every repository on the box.

`TheoGibbons/remote-desktop` is public today, so Part 1 is not needed for this project.

**Part 2 — once per repository.** The same EC2 keypair is reused for every project,
so only these four secrets are new. Create a GitHub environment named `production`,
then:

```bash
# Locally, with the gh CLI authenticated:
gh secret set EC2_HOST            --env production --body '<ec2-public-dns>'
gh secret set EC2_USER            --env production --body 'ubuntu'
gh secret set EC2_SSH_PRIVATE_KEY --env production < ~/.ssh/<ec2-deploy-key>
gh secret set EC2_KNOWN_HOSTS     --env production \
  --body "$(ssh-keyscan -H <ec2-public-dns> 2>/dev/null)"
```

The deploy stops rather than overwrite tracked files edited directly on the server.

## What it is

Three components share one small WebSocket protocol ([PROTOCOL.md](PROTOCOL.md)):

| Component | Folder | Tech | Role |
|-----------|--------|------|------|
| Relay server | [`server/`](server/) | Node.js + `ws` | Pairs devices by a shared **session key** and relays traffic. The only always-on, internet-facing piece. |
| Windows app | [`windows/`](windows/) | C# / WPF (.NET 8) | Streams all monitors (stitched) and injects mouse/keyboard; can also **view & control another PC** (full keyboard + mouse); serves & receives files. |
| Android app | [`android/`](android/) | Kotlin | Views & controls the PC (zoom, full keyboard incl. Win/Ctrl/Alt/Shift); shares its own screen so the PC can tap it; serves & receives files. |

A fourth piece, [`site/`](site/), is the static download page the LIVE deployments
publish alongside the relay.

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

## How it works

Pairing is the "set up once" part:

1. Run the relay somewhere both devices can reach — any of the four deployments above,
   including just your LAN.
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
[Security notes](#security-notes).

## Configuration

Every deployment reads a single environment file. Which one, and which script reads it:

| File | Copied from | Read by |
|---|---|---|
| `.env` | `.env.example` locally, `.env.production.example` on LIVE | `scripts/up-local.sh`, `scripts/deploy.sh` |
| `.env.traefik` | `.env.traefik.example` | `scripts/up-local-traefik.sh` |

| Variable | Default | Meaning |
|---|---|---|
| `USE_TRAEFIK` | — | LIVE only. `0` deploys standalone, `1` deploys behind hobby-traefik. `scripts/deploy.sh` refuses to run without one of those two values. |
| `SITE_BIND` | `127.0.0.1` | Host interface the site is published on. Unused behind Traefik. |
| `SITE_PORT` | `8087` | Host port the site is published on. Unused behind Traefik. |
| `RELAY_BIND` | `127.0.0.1` | Host interface the relay is published on. Unused behind Traefik. |
| `RELAY_PORT` | `8090` | Host port the relay is published on. Unused behind Traefik. |
| `SITE_HOST` | — | Traefik only. Hostname routed to the site; the Traefik overlay fails validation without it. |
| `RELAY_HOST` | — | Traefik only. Hostname routed to the relay; the Traefik overlay fails validation without it. |
| `PUBLIC_SITE_URL` | `http://localhost:8087` | Absolute URL the site is served from, no trailing slash. Baked into the page at **build** time, so changing it needs a rebuild. |
| `RELAY_TRUST_PROXY` | `0` | `1` makes the relay read `X-Forwarded-For` for rate limiting. The Traefik overlays set it themselves. |

The Compose layers each deployment combines:

| File | Purpose |
|---|---|
| `docker-compose.yml` | Proxy-independent relay and site services |
| `docker-compose.override.yml` | Published host ports, standalone modes |
| `docker-compose.traefik.yml` | Shared network and HTTP routing labels |
| `docker-compose.prod.yml` | HTTPS and Let's Encrypt labels, LIVE only |

There are no secrets in any of these files. The session key that authenticates devices
never reaches the server or this repository — it lives only in the two apps.

## Operating it

Every script ends with `docker compose up -d --wait` followed by `docker compose ps`, so
a script that exits 0 has left healthy containers behind, and one that exits non-zero
has printed the failing service's logs. There is nothing extra to verify afterwards.

To inspect a running LIVE stack, rebuild the same argument list the deploy used:

```bash
cd ~/projects/remote-desktop

# Standalone (USE_TRAEFIK=0)
docker compose --env-file .env -f docker-compose.yml -f docker-compose.override.yml ps

# Behind hobby-traefik (USE_TRAEFIK=1)
docker compose --env-file .env -f docker-compose.yml -f docker-compose.traefik.yml \
  -f docker-compose.prod.yml logs -f --tail=100 relay
```

A manual redeploy is the same command the workflow runs:

```bash
ssh ubuntu@<ec2-public-dns> 'bash ~/projects/remote-desktop/scripts/deploy.sh'
```

App binaries are released separately from the server. Pushing a tag such as `v1.0.0`
triggers `.github/workflows/release.yml`, which publishes a self-contained Windows EXE,
a signed Android APK and a SHA-256 checksum file to GitHub Releases. The site links to
stable `releases/latest/download/...` URLs, so publishing app binaries never requires
committing them or redeploying the server. [RELEASING.md](RELEASING.md) covers the
one-time signing secrets and the tagging steps.

## Security notes

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
  don't present a valid pairing id within 10s are dropped. Behind any proxy set
  `RELAY_TRUST_PROXY=1`, or every client looks like one IP.
- **Transport:** even though content is E2E encrypted, run the relay behind a TLS
  reverse proxy and use `wss://` over the internet (it protects the metadata and
  the pairing id too). Over a trusted LAN, `ws://` is fine.
- **Out of scope:** replay protection against a *malicious active* relay, and
  metadata privacy. The threat model is a passive/curious relay + eavesdroppers.

## Development

```
remote-desktop/
├── PROTOCOL.md                 # the wire protocol both apps implement
├── server/                     # Node relay (build & run here)
├── site/                       # static download page served on LIVE
├── scripts/                    # deployment entry points
├── windows/RemoteDesktopWin/   # .NET 8 WPF app
└── android/                    # Gradle/Kotlin app (open in Android Studio)
```

To iterate on the relay alone, skip Docker entirely:

```bash
cd server && npm ci && npm start
```

The client apps build from [`windows/README.md`](windows/README.md) and
[`android/README.md`](android/README.md).

This repository is developed on Windows and deployed to Linux, so `.gitattributes` pins
every file to LF. A shell script checked out with CRLF fails on the server with
`bad interpreter: /usr/bin/env bash^M`, a message that never mentions line endings.
Check a clone with:

```bash
git ls-files --eol -- '*.sh'   # want w/lf
git ls-files -s  -- '*.sh'     # want 100755
```

Record the executable bit through Git rather than with `chmod`, which
`core.filemode=false` silently ignores on Windows:

```bash
git update-index --chmod=+x scripts/deploy.sh
```
