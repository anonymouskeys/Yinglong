# SoftEther Windows -> Android desktop-parity architecture

Pinned engine: `SoftEtherVPN/SoftEtherVPN_Stable`
commit `ed17437af9719ac66acab30faa29e375d613c35f` (Cedar 4.44 build 9807).

## Official engine boundary

The SoftEther protocol/session engine stays official. The build preserves
`Cedar/Protocol.c`, `Cedar/Connection.c`, `Cedar/Session.c`,
`Mayaqua/Network.c`, `Mayaqua/Encrypt.c` and `Mayaqua/Pack.c`.

These files own TLS, SoftEther PACK negotiation, authentication, session
establishment, TCP transport and the official NAT-T/R-UDP fallback.

## Windows client architecture

The Windows product is split into the background VPN Client service and the
Win32 VPN Client Manager. `vpnclient/vpncsvc.c` starts the client service;
`vpncmgr/vpncmgr.c` runs the GUI. The service-side path is based on the official
CLIENT/account subsystem and eventually calls `NewClientSessionEx()` with a
packet adapter.

The relevant connection path is:
`CtConnect -> VLanGetPacketAdapter -> NewClientSessionEx -> ClientThread ->
SessionConnect -> ClientConnect -> ClientConnectToServer`.

## Android replacement boundary

Win32 GUI and the Windows NDIS adapter cannot be copied as binaries to Android.
Android supplies `VpnService`, which exposes an L3 TUN rather than an NDIS L2
adapter. The Android port therefore replaces only the OS boundary:

- Android UI replaces Win32 windows/tray code.
- A custom official Cedar `PACKET_ADAPTER` replaces the platform VLAN adapter.
- DHCP/ARP bridge the remote Ethernet segment to Android's L3 TUN.
- The VPN provider package is excluded from its own VPN route.
- Transport descriptors are also passed through `VpnService.protect()`.

The JNI owns an official `CLIENT` created by `CiNewClient()` and uses that
client's `CEDAR`. A bare `NewCedar()` is insufficient because `ClientThread`
uses client-side globals initialized by the VPN Client subsystem.

## Correctness rules

Never derive a native SoftEther listener port from an OpenVPN `.ovpn` profile.
Never fabricate an IPv4 address when DHCP failed. Never fabricate IPv6 ULA/DNS
and `::/0` without implementing RA/NDP. Never reimplement the SoftEther
TLS/PACK/authentication protocol when the official Cedar path is available.

For complete L2 parity the Android packet adapter still needs an ARP cache with
on-link next-hop selection and a complete IPv6 RA/NDP implementation. Until
then, the supported data path is IPv4 DHCP plus gateway ARP.
