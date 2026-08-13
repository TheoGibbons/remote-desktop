# Deployment and release setup

This repository has two independent delivery paths:

- A push to `main` updates the relay and marketing site on EC2.
- A tag such as `v1.2.3` builds the Windows and Android apps and publishes them
  as GitHub Release assets.

Generated EXE and APK files are deliberately not committed to Git.

## EC2 application setup

Run this from your workstation to connect to EC2:

```bash
ssh ubuntu@www.remote-desktop.co
```

Run these commands on EC2 for the first deployment:

```bash
docker network inspect traefik-public
mkdir -p ~/projects
git ls-remote git@github.com:TheoGibbons/remote-desktop.git HEAD
git clone git@github.com:TheoGibbons/remote-desktop.git ~/projects/remote-desktop
cd ~/projects/remote-desktop
cp .env.production.example .env.production
./scripts/deploy.sh
```

Only do this if the scripts need to be made executable:
```bash
cd ./scripts
chmod +x *.sh
cd ..
git add scripts/deploy.sh scripts/up-local.sh
git commit -m "Make shell scripts executable"
git push
./scripts/deploy.sh
```

Verify the deployment from EC2:

```bash
curl --fail --show-error https://www.remote-desktop.co/ >/dev/null
curl --fail --show-error https://relay.remote-desktop.co/
docker compose \
  --env-file .env.production \
  -f docker-compose.yml \
  -f docker-compose.traefik.yml \
  -f docker-compose.prod.yml \
  ps
```

Run these commands for later manual deployments:

```bash
ssh ubuntu@www.remote-desktop.co \
  'cd ~/projects/remote-desktop && bash scripts/deploy.sh'
```

## Automatic EC2 deployment

Run these commands from a workstation with GitHub CLI installed and signed in:

```bash
gh auth status
gh api \
  --method PUT \
  repos/TheoGibbons/remote-desktop/environments/production

gh secret set EC2_HOST \
  --env production \
  --body 'www.remote-desktop.co'

gh secret set EC2_USER \
  --env production \
  --body 'ubuntu'

gh secret set EC2_SSH_PRIVATE_KEY \
  --env production \
  < /path/to/ec2-ssh-private-key

ssh-keyscan -H www.remote-desktop.co > remote-desktop-known-hosts
ssh-keygen -lf remote-desktop-known-hosts

gh secret set EC2_KNOWN_HOSTS \
  --env production \
  < remote-desktop-known-hosts
```

After verifying the displayed EC2 host-key fingerprint, trigger and watch a
deployment with:

```bash
gh workflow run deploy.yml --ref main
sleep 3
gh run watch "$(gh run list --workflow deploy.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

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
