# Yinglong v0.2.1 diagnostics

This patch adds a persistent rotating application log without adding visible UI controls.
Long-press the single START button to open the log viewer. You can copy or clear the log there.

It also makes the current tunnel state explicit: v0.2.1 still has relay discovery/ranking only.
There is no Android VpnService/OpenVPN transport backend in the current repository, so a real VPN connection cannot occur yet.

Logged areas include:
- app/session lifecycle
- relay pool size
- candidate probing and TCP reachability
- chosen relay and parsed OpenVPN endpoint
- Android VPN network detection
- post-connect catalogue maintenance
- VPN Gate refresh/harvest errors
