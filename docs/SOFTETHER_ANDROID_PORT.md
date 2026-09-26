# SoftEther Android port

## v0.9.2 milestone

SoftEther is enabled as Yinglong's first VPN Gate transport. OpenVPN remains
a fallback while the Android port of the desktop SoftEther client is completed.

VPN Gate connection parameters used by Yinglong:

- virtual hub: `VPNGATE`
- username: `vpn`
- password: `vpn`
- relay address: IP address
- native TCP candidates: 443, 992, 5555

## Desktop SoftEther engine boundary

The currently fetched `hoang-rio/SoftEther-Android-Module` supplies the active
Android native protocol engine. It is not the complete desktop Cedar/Mayaqua
VPN Client.

The full port should retain the desktop SoftEther client/session core
(`NewClientSession()` and `PACKET_ADAPTER`) and replace the desktop TAP edge
with an Android adapter.

Android `VpnService` provides an L3 TUN interface, while the desktop SoftEther
client packet adapter exchanges L2 Ethernet frames. The Android edge therefore
needs:

1. a queue-backed `PACKET_ADAPTER`;
2. DHCP inside the SoftEther L2 session;
3. IPv4/IPv6 TUN packet to Ethernet frame conversion and reverse conversion;
4. ARP/gateway-MAC handling;
5. `VpnService.protect()` for every SoftEther transport socket;
6. cancellation/teardown synchronization across JNI and the VPN service.

That is the next engine-porting stage after v0.9.2 proves the currently packaged
SoftEther transport is actually being attempted on Android.
