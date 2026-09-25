# Yinglong v0.3.1 repair

This patch focuses on two failures seen on-device: no useful diagnostics and an OpenVPN start that did not produce a verified Android VPN transport.

Changes:
- persistent file log + always-available RAM ring log;
- uncaught crash capture with full stack traces;
- engine self-check for merged OpenVPNService and `libovpnexec.so`;
- fixes stop/failover wait logic (stop no longer burns the full timeout every attempt);
- initializes ProfileManager before installing the temporary VPN profile;
- uses the same `startOpenVpn(..., replace=false)` mode as the reference backend;
- ignores a short stale `NOTCONNECTED` race from a previous service instance;
- verifies Android actually reports `TRANSPORT_VPN` after OpenVPN says CONNECTED;
- prefers reachable TCP/443 relays before other TCP and UDP fallbacks;
- CI verifies the VPN service/permissions and native executable are really packaged into the APK.

Long-press START/STOP to open diagnostics. The dialog now shows file status and a RAM ring even if file logging fails.
