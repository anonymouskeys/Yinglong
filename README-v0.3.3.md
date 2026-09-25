# Yinglong v0.3.3 — adaptive handshake + larger seed

Key fixes:

- Replaces the 9.5s hard OpenVPN cutoff with an adaptive watchdog. A relay can use up to 45s overall, while stalled stages are still abandoned quickly.
- Treats TLS server reply / certificate verification / PUSH progress as real progress, so working VPN Gate relays are not killed mid-handshake.
- Suppresses the giant OpenVPN parameter dump while retaining connection/TLS/PUSH/errors in the persistent log.
- Fast scan checks up to 80 relays; extended scan can use up to 320 accumulated relays.
- UI wording says "candidates" rather than claiming unprobed UDP endpoints are reachable.
- The APK's bundled seed is merged into an existing installation on startup instead of being ignored after the first install.
- `scripts/update_seed.sh` now best-effort harvests extra profiles from VPN Gate's official HTML list in addition to the CSV slice.
- `scripts/push.sh` refreshes the bundled seed before committing when possible.
- Session state is persisted for diagnostics so the next launch can report that Android killed the previous process while a VPN attempt was active.

Install over the project, then push normally:

```bash
cd ~/Yinglong
unzip -o ~/storage/downloads/Yinglong-v0.3.3-adaptive-handshake.zip -d ~/Yinglong
chmod +x scripts/*.sh scripts/*.py
./scripts/push.sh "Yinglong v0.3.3 adaptive handshake and larger relay seed"
```
