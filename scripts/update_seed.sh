#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/relays_seed.csv"
WORK="${TMPDIR:-$HOME/.cache}/yinglong-seed-$$"
URL="https://www.vpngate.net/api/iphone/"
mkdir -p "$WORK" "$(dirname "$DEST")"
trap 'rm -rf "$WORK"' EXIT

# Keep the previous bundled pool and merge several fresh partial samples into it.
# VPN Gate intentionally exposes only a portion of its live population per list.
COMBINED="$WORK/combined.csv"
: > "$COMBINED"

for i in 1 2 3 4 5 6; do
  echo "[Yinglong] VPN Gate sample $i/6…"
  F="$WORK/sample-$i.csv"
  NONCE="$(date +%s)-$i-$$"
  if curl --fail --location --silent --show-error \
      --retry 2 --retry-delay 1 --connect-timeout 15 --max-time 120 \
      -A 'Yinglong-seed-builder/0.2' \
      "$URL?_yinglong=$NONCE" -o "$F"; then
    if grep -q '^#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort' "$F"; then
      grep -Ev '^(\*vpn_servers|\*|#|[[:space:]]*$)' "$F" >> "$COMBINED" || true
    fi
  fi
  sleep 1
 done

if [ -f "$DEST" ]; then
  grep -Ev '^(\*vpn_servers|\*|#|[[:space:]]*$)' "$DEST" >> "$COMBINED" || true
fi

OUT="$WORK/out.csv"
{
  echo '*vpn_servers'
  echo '#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64'
  # Field 2 is always IP; newest downloaded occurrence wins over the old seed.
  awk -F',' 'NF >= 15 && $2 != "" && !seen[$2]++ { print }' "$COMBINED"
  echo '*'
} > "$OUT"

COUNT="$(grep -Ev '^(\*vpn_servers|\*|#|[[:space:]]*$)' "$OUT" | wc -l | tr -d ' ')"
if [ "$COUNT" -lt 10 ]; then
  echo "[Yinglong] ERROR: accumulated seed has only $COUNT relays" >&2
  exit 1
fi
mv "$OUT" "$DEST"
BYTES="$(wc -c < "$DEST" | tr -d ' ')"
echo "[Yinglong] Accumulated seed: $COUNT unique relay IPs, $BYTES bytes"
