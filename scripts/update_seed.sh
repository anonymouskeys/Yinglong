#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/relays_seed.csv"
TMP="$DEST.tmp"
URL="https://www.vpngate.net/api/iphone/"

mkdir -p "$(dirname "$DEST")"
trap 'rm -f "$TMP"' EXIT

echo "[Yinglong] Downloading current VPN Gate relay snapshot…"
curl --fail --location --silent --show-error \
  --retry 3 --retry-delay 2 --connect-timeout 15 --max-time 180 \
  -A 'Yinglong-seed-builder/0.1' \
  "$URL" -o "$TMP"

if ! grep -q '^#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort' "$TMP"; then
  echo "[Yinglong] ERROR: unexpected VPN Gate CSV format" >&2
  exit 1
fi

COUNT="$(grep -Ev '^(\*vpn_servers|\*|#|[[:space:]]*$)' "$TMP" | wc -l | tr -d ' ')"
if [ "$COUNT" -lt 10 ]; then
  echo "[Yinglong] ERROR: snapshot contains only $COUNT relays; refusing to replace seed" >&2
  exit 1
fi

mv "$TMP" "$DEST"
trap - EXIT
BYTES="$(wc -c < "$DEST" | tr -d ' ')"
echo "[Yinglong] Seed updated: $COUNT relays, $BYTES bytes"
