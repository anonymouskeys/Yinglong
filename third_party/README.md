# OpenVPN Android backend

The APK build uses the headless `ics-openvpn` Android backend packaged by the
`mysteriumnetwork/openvpn_dart` project as `icsopenvpn-0.7.55-myst.aar`.

The binary AAR is intentionally **not committed** here. GitHub Actions downloads it from the
immutable upstream commit `7ac4d91f5e401f2802c6942dcb868a476236cdcd` and verifies SHA-256:

`1da7a6068d0bda22fef4b8ec7a887c07ab456e65c1246ea9a7533c4676891454`

Upstream engine: `schwabe/ics-openvpn` v0.7.55. See the upstream provenance document in
`mysteriumnetwork/openvpn_dart/android/localmaven/PROVENANCE.md` for build inputs and source SHAs.

ics-openvpn is GPLv2 with additional terms. Preserve the upstream license/source obligations when
distributing APKs that contain this backend.
