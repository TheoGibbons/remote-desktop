# Release setup

This repository has two independent delivery paths:

- A push to `main` updates the relay and download site on EC2.
- A tag such as `v1.2.3` builds the Windows and Android apps and publishes
  them as GitHub Release assets.

Generated EXE, APK and AAB files are deliberately not committed to Git.

The first path is documented in full in [README.md](README.md) — see
**Deploying to LIVE**, **Deploying to LIVE with hobby-traefik** and **Setup
push-to-deploy** there. Everything below covers the second path only.

## Release build configuration

`wss://relay.remote-desktop.co/ws` is compiled into both public app builds as
their first-run default. It is public configuration, not a secret, so no GitHub
variable is required. Users who already have saved settings keep their existing
server URL.

### Android signing

Reuse the existing Android release keystore and back it up outside Git. The same
key signs both the direct-download APK and Google Play AAB. Encode the binary
keystore as Base64 and provide these repository secrets (already configured for
the published APK):

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

The release workflow builds the APK and AAB together, runs Android lint and unit
tests, and verifies both signatures before creating the GitHub Release. Both
artifacts compile and target API 36, with Android 8.0 (API 26) as the minimum.

For local builds, use JDK 21 and Android SDK Platform 36. Set
`ANDROID_KEYSTORE_FILE` to the absolute path of the existing keystore, and set
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` in
the build process environment. Then run from the repository root:

```powershell
.\android\gradlew.bat -p android :app:assembleRelease :app:bundleRelease
```

The outputs are `android/app/build/outputs/apk/release/app-release.apk` and
`android/app/build/outputs/bundle/release/app-release.aab`. The same signing key
and version values apply to both. Android Studio builds default to
version `1.0.7` / version code `9`; `RELEASE_VERSION_NAME` and
`RELEASE_VERSION_CODE` override those values. For a build matching a tagged
release, use its tag version and GitHub release workflow run number. Version
codes must increase for new releases; the `v1.0.6` public APK used code `8`.

When setting up Google Play, use **Protected with Play → Play Store protection
→ Manage Play app signing → Change key → Export and upload a key from Java
keystore**. Follow the Console's PEPK instructions to import this existing app
signing key, and use the same key for uploads. Verify Play's app signing
certificate against the published APK before rollout. The GitHub workflow
provides the AAB as a release asset; upload it to Play Console separately.

### Windows signing with Azure Artifact Signing

The release workflow signs the self-contained Windows EXE with Azure Artifact
Signing, adds a timestamp, and verifies the Authenticode signature before
publishing. Signing or verification failure stops the release; there is no
unsigned fallback.

The workflow uses the `windows-app-signing-v3` certificate profile in the
`theo-gibbons` signing account at `https://eus.codesigning.azure.net/`.
If the active profile is replaced, update `certificate-profile-name` in
`.github/workflows/release.yml` to match its exact name before tagging a release.

The configuration is here `.github/workflows/release.yml`

## Publish a release

Run from the repository root on `main`. Commit release changes first. Paste the
entire block together: it stops before the version bump if tracked files have
staged or unstaged changes. Untracked files are ignored.

```powershell
& {
    if (git status --porcelain --untracked-files=no) { throw "Working tree is dirty. Commit first." }
    cd server
    npm version patch --no-git-tag-version
    cd ..
    $release_version = node -p "require('./server/package.json').version"
    git add -- server/package.json server/package-lock.json
    git commit -m "Release v$release_version"
    git tag -a "v$release_version" -m "Release v$release_version"
    git push --atomic origin main "v$release_version"
}
```

The npm package is in `server/`, not the repository root.

Use a new version for each release; do not overwrite an existing release tag.
Pushing `main` alone does not publish app binaries: the `v*` tag triggers the
release workflow. The tag supplies the Windows app version and Android version
name; the GitHub run number supplies the Android version code. This does not
publish the relay package to the npm registry.

GitHub Actions creates the release with these stable asset names:

```text
RemoteDesktop-Windows-x64.exe
RemoteDesktop-Android.apk
RemoteDesktop-Android.aab
SHA256SUMS.txt
```

The marketing site always points to the newest release using GitHub's
`releases/latest/download` URLs.
