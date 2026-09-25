# Yinglong v0.3 — real OpenVPN tunnel

This patch replaces the v0.2 relay-only placeholder with a real Android VPN tunnel backed by
ics-openvpn.

Flow:

START -> Android VPN consent -> rank local VPN Gate relays -> parse .ovpn -> force direct relay IP
-> try candidates sequentially -> wait for actual CONNECTED -> run one post-connect relay refresh
/cleanup -> keep tunnel alive -> automatic failover on terminal disconnect -> STOP tears it down.

Long-press START/STOP still opens Yinglong's persistent log; OpenVPN engine logs and state
transitions are mirrored there.

The OpenVPN AAR is downloaded only in GitHub Actions from a pinned immutable commit and verified
against the upstream-published SHA-256. It is not committed to the Yinglong repository.
