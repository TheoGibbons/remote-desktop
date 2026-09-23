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

### Windows signing with Azure Artifact Signing

The release workflow signs the self-contained Windows EXE with Azure Artifact
Signing, adds a timestamp, and verifies the Authenticode signature before
publishing. Signing or verification failure stops the release; there is no
unsigned fallback.

The configuration is here `.github/workflows/release.yml`

## Publish a release

Before publishing, commit. The npm package is in `server/`,
not the repository root.

```bash
cd server
npm version patch --no-git-tag-version
cd ..
```

Run the remaining commands in PowerShell from the repository root:

```powershell
  $releaseVersion = node -p "require('./server/package.json').version"
  git add -- server/package.json server/package-lock.json
  git commit -m "Release v$releaseVersion"
  git tag -a "v$releaseVersion" -m "Release v$releaseVersion"
  git push --atomic origin main "v$releaseVersion"
```

Use a new version for each release; do not overwrite an existing release tag.
Pushing `main` alone does not publish app binaries: the `v*` tag triggers the
release workflow. The tag supplies the Windows app version and Android version
name; the GitHub run number supplies the Android version code. This does not
publish the relay package to the npm registry.

GitHub Actions creates the release with these stable asset names:

```text
RemoteDesktop-Windows-x64.exe
RemoteDesktop-Android.apk
SHA256SUMS.txt
```

The marketing site always points to the newest release using GitHub's
`releases/latest/download` URLs.
