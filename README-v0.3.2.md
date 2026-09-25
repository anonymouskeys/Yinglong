# Yinglong v0.3.2

Connection/debug repair:

- live status under the only START/STOP control;
- tapping the status or long-pressing START/STOP opens the full persistent journal;
- relay probing reports live `checked/accepted/rejected` progress;
- first fast scan is bounded to 64 candidates, then expands to 180 only if needed;
- TCP/443 reachable relays are prioritized;
- OpenVPN states are surfaced live: PROFILE, ENGINE_START, TCP_CONNECT, AUTH, GET_CONFIG, ASSIGN_IP, ADD_ROUTES, CONNECTED, etc.;
- every new attempt cleanly stops a stale OpenVPN service/TUN first;
- `persist-tun` is disabled during relay failover so a dead TUN cannot poison the next attempt;
- new profiles are allowed to replace stale ics-openvpn sessions during failover;
- pre-progress stale `NOTCONNECTED` callbacks are ignored and the watchdog timeout decides a true no-start.

Apply from Termux and build through the existing GitHub Actions workflow.
