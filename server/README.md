# Relay server

WebSocket relay that pairs devices and forwards JSON/binary frames between them.
Devices join using a **pairing id** derived from their shared session key (the
raw key never reaches the server), and all peer content is **end-to-end
encrypted** — the relay only sees ciphertext plus routing metadata and never
inspects payloads. The pairing id is itself stored only as a SHA-256 hash. See
[`../PROTOCOL.md`](../PROTOCOL.md).

## Run

```bash
npm install
npm start
```

Environment variables:

| Var | Default | Meaning |
|-----|---------|---------|
| `PORT` | `8090` | TCP port. WebSocket path is always `/ws`. |
| `TRUST_PROXY` | `0` | Set to `1` **only** behind a trusted reverse proxy so the client IP is read from `X-Forwarded-For` (needed for correct rate limiting). |

Health check: `GET /` returns `remote-desktop relay ok`.

## Behind TLS (recommended for internet use)

Terminate TLS with nginx/Caddy and proxy `/ws` to the relay, then point the apps
at `wss://your.domain/ws`.

Caddy example:

```
your.domain {
    reverse_proxy /ws localhost:8090
    reverse_proxy /  localhost:8090
}
```

Run the relay with `TRUST_PROXY=1` so per-IP rate limiting sees real client IPs.

## systemd

```ini
# /etc/systemd/system/remote-desktop-relay.service
[Unit]
Description=Remote Desktop relay
After=network.target

[Service]
WorkingDirectory=/opt/remote-desktop/server
ExecStart=/usr/bin/node server.js
Environment=PORT=8090
Restart=always
User=www-data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now remote-desktop-relay
```

## Docker

```bash
docker build -t rd-relay .
docker run -p 8090:8090 --restart unless-stopped rd-relay
```

## Tests

```bash
npm test
```

Spins up the relay on a scratch port and exercises pairing, JSON routing, binary
relay, session isolation, the minimum-key-length check, and the brute-force rate
limit (9 assertions).
