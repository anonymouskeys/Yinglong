# Yinglong v0.2 relay-core patch

This patch deliberately separates **relay intelligence** from the **OpenVPN tunnel engine**.
It does not pretend that a tunnel exists before the embedded OpenVPN core is integrated.

Changes:

- Main screen contains only one START/STOP control. Long-press reveals the hidden local pool count for debugging.
- No catalogue update runs when the app merely launches.
- START performs bounded parallel TCP reachability checks against top candidates.
- VPN Gate profiles are rewritten to direct IP by `OpenVpnProfileUtil` for the future tunnel backend.
- After a real Android VPN transport appears during a user-started session, maintenance runs exactly once:
  - merge 4 fresh VPN Gate CSV samples;
  - harvest up to 16 additional IP-based OpenVPN profiles from VPN Gate's official HTML list;
  - health-check stale TCP relays;
  - prune a relay only after 3 failed post-connect probes and >=7 days without being seen in a fresh official sample.
- `relays.csv` is now an accumulated pool instead of being replaced by each ~100-entry sample.
- `update_seed.sh` also accumulates six official CSV samples with the previous bundled seed.
- `push.sh` waits for the GitHub Actions run belonging to the exact pushed commit and downloads the resulting APK into Termux Downloads.

## Apply

From the existing `~/Yinglong` repository:

```bash
unzip -o ~/storage/downloads/Yinglong-v0.2-relay-core-patch.zip -d ~/Yinglong
cd ~/Yinglong
bash scripts/apply_v02.sh
./scripts/update_seed.sh
./scripts/push.sh "Yinglong v0.2 relay pool and one-button UI"
```

## Next transport step

The repository still needs a real embedded OpenVPN implementation before START can truthfully become CONNECTED/STOP. The recommended path is to embed `ics-openvpn` and keep the existing `RelayProbe`, `OpenVpnProfileUtil`, and one-shot post-connect maintenance around it.
