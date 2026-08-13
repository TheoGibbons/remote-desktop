# Deployment and release setup

This repository has two independent delivery paths:

- A push to `main` updates the relay and marketing site on EC2.
- A tag such as `v1.2.3` builds the Windows and Android apps and publishes them
  as GitHub Release assets.

Generated EXE and APK files are deliberately not committed to Git.

## EC2 application setup

The shared Traefik proxy must already be running from
`~/projects/hobby-traefik`, with the external `traefik-public` Docker network in
place. The EC2 security group needs inbound 22, 80, and 443; port 8090 should
not be public.

Point both `www.remote-desktop.co` and `relay.remote-desktop.co` at the EC2
instance, then run:

```bash
git clone git@github.com:TheoGibbons/remote-desktop.git ~/projects/remote-desktop
cd ~/projects/remote-desktop
cp .env.production.example .env.production
```

The example already contains the production hostnames. Review it, then run:

```bash
bash scripts/deploy.sh
```

The resulting public URLs are:

```text
https://www.remote-desktop.co/
wss://relay.remote-desktop.co/ws
```

The relay has no database or durable server-side application state. A deploy
briefly disconnects active clients; the apps reconnect automatically. Keep the
relay at one replica because live sessions are held in process memory.

## Automatic EC2 deployment

Create a GitHub environment named `production`. Add these environment secrets:

| Secret | Value |
|---|---|
| `EC2_HOST` | EC2 public hostname or IP |
| `EC2_USER` | Deployment user, normally `ubuntu` |
| `EC2_SSH_PRIVATE_KEY` | Private key whose public key is authorized on EC2 |
| `EC2_KNOWN_HOSTS` | Verified `known_hosts` line for the EC2 host |

The EC2 checkout also needs read access to this GitHub repository so
`git pull --ff-only` can succeed. A repository deploy key is recommended for a
private repository.

## Release build configuration

`wss://relay.remote-desktop.co/ws` is compiled into both public app builds as
their first-run default. It is public configuration, not a secret, so no GitHub
variable is required. Users who already have saved settings keep their existing
server URL.

### Android signing

Create an Android upload/release keystore once and back it up outside Git. The
same key must sign every future update. Encode the binary keystore as Base64 and
add these repository secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded `.jks` contents |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

On PowerShell, encode a keystore without line breaks with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('remote-desktop-release.jks'))
```

The release workflow refuses to publish an unsigned APK and verifies the APK
signature before creating the GitHub Release.

### Optional Windows signing

The workflow can publish the self-contained Windows EXE unsigned. To add
Authenticode signing, configure both optional secrets:

| Secret | Value |
|---|---|
| `WINDOWS_SIGNING_CERTIFICATE_BASE64` | Base64-encoded code-signing `.pfx` |
| `WINDOWS_SIGNING_CERTIFICATE_PASSWORD` | PFX password |

When present, the workflow signs and verifies the EXE before release. Without
them, Windows may show an unknown-publisher or SmartScreen warning.

## Publish a release

Before tagging, update release notes as needed and ensure `main` is green. Then
create and push a semantic version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions creates the release with these stable asset names:

```text
RemoteDesktop-Windows-x64.exe
RemoteDesktop-Android.apk
SHA256SUMS.txt
```

The marketing site always points to the newest release using GitHub's
`releases/latest/download` URLs.
