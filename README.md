# Yinglong

Yinglong is an experimental Android client built around a resilient local catalogue of public VPN Gate relays.

## Build model

**Termux does not build the Android APK.** Termux refreshes the bundled relay seed, commits the source, creates/pushes the GitHub repository, and then waits for GitHub Actions. GitHub Actions installs Java/Gradle/Android SDK, builds `Yinglong-debug.apk`, uploads it as an Actions artifact, and the Termux bootstrap script downloads that GitHub-built APK back into `~/storage/downloads/`.

## Relay catalogue design

1. Before the first push, `scripts/update_seed.sh` downloads the current relay subset returned by the official VPN Gate CSV feed into `app/src/main/assets/relays_seed.csv`.
2. On first launch the read-only APK asset is copied to the app's writable internal storage as `relays.csv`.
3. When Android reports that the default network has `TRANSPORT_VPN`, Yinglong attempts to fetch a fresh VPN Gate catalogue through that working VPN path.
4. Downloads go to `relays.csv.tmp`, are parsed and sanity checked, fsynced, then atomically replace `relays.csv`.
5. A failed/censored/partial download never destroys the last known-good catalogue.

## First setup in Termux

Keep the Git repository in Termux private storage, not shared Downloads storage:

```bash
cd ~
rm -rf Yinglong
unzip -q ~/storage/downloads/Yinglong-source.zip
cd ~/Yinglong
chmod +x scripts/*.sh
bash scripts/termux_bootstrap.sh
```

The bootstrap sequence is:

```text
refresh seed
→ git init/commit
→ create public GitHub repo Yinglong
→ push main
→ GitHub Actions builds APK
→ download Actions artifact to ~/storage/downloads/Yinglong-debug.apk
```

No local Android/Gradle build is required in Termux.

## Later changes

```bash
cd ~/Yinglong
./scripts/push.sh "Describe the change"
```

Every push to `main` automatically starts the GitHub APK build.

To refresh the relay seed embedded in the next APK:

```bash
./scripts/update_seed.sh
./scripts/push.sh "Refresh bundled VPN Gate relay seed"
```

## Current scope

v0.1 implements the seed/live relay catalogue and automatic post-VPN refresh. The actual in-app OpenVPN tunnel engine is the next module.
