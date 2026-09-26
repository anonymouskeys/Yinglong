# Yinglong v1.0.0 — Official SoftEther core on Android

This migration stops patching the independent `softether-core` protocol implementation.

Pinned official engine:
- `SoftEtherVPN/SoftEtherVPN_Stable`
- commit `ed17437af9719ac66acab30faa29e375d613c35f`
- v4.44-9807-rtm

Verified byte-for-byte unchanged:
- `src/Cedar/Protocol.c`
- `src/Cedar/Connection.c`
- `src/Cedar/Session.c`
- `src/Mayaqua/Network.c`
- `src/Mayaqua/Encrypt.c`
- `src/Mayaqua/Pack.c`

Only `Mayaqua.c` gets an Android environment adaptation that skips the `/tmp`
self-test in Mayaqua minimal mode.

`android_official_jni.c` is only an Android platform adapter. It calls
`NewClientSession()`, supplies a Cedar `PACKET_ADAPTER`, performs DHCP/ARP over
the resulting Ethernet session, bridges Android TUN L3 packets to Cedar L2
frames, and exposes the physical underlay fd(s) to `VpnService.protect()`.

TCP/TLS/PACK/password authentication/NAT-T/R-UDP are performed by the official
SoftEther Cedar/Mayaqua sources, not reimplemented here.
