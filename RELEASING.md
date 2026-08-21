# Release setup

This repository has two independent delivery paths:

- A push to `main` updates the relay and download site on EC2.
- A tag such as `v1.2.3` builds the Windows and Android apps and publishes
  them as GitHub Release assets.

Generated EXE and APK files are deliberately not committed to Git.

The first path is documented in full in [README.md](README.md) — see
**Deploying to LIVE**, **Deploying to LIVE with hobby-traefik** and **Setup
push-to-deploy** there. Everything below covers the second path only.

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
