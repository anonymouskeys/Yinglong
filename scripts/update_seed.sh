#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/relays_seed.csv"
WORK="${TMPDIR:-$HOME/.cache}/yinglong-seed-$$"
URL="https://www.vpngate.net/api/iphone/"
mkdir -p "$WORK" "$(dirname "$DEST")"
trap 'rm -rf "$WORK"' EXIT

COMBINED="$WORK/combined.csv"
: > "$COMBINED"

# VPN Gate intentionally returns only a partial list. Keep the previous seed and merge
# several live samples instead of replacing the pool with ~100 entries every build.
for i in 1 2 3 4 5 6; do
  echo "[Yinglong] VPN Gate CSV sample $i/6…"
  F="$WORK/sample-$i.csv"
  NONCE="$(date +%s)-$i-$$"
  if curl --fail --location --silent --show-error \
      --retry 2 --retry-delay 1 --connect-timeout 15 --max-time 120 \
      -H 'Cache-Control: no-cache' \
      -A 'Yinglong-seed-builder/0.9.1' \
      "$URL?_yinglong=$NONCE" -o "$F"; then
    if grep -q '^#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort' "$F"; then
      grep -Ev '^(\*vpn_servers|\*|#|[[:space:]]*$)' "$F" >> "$COMBINED" || true
    fi
  fi
  sleep 1
done

# The official HTML page can expose profiles that are absent from the current CSV slice.
# This is optional so a Termux installation without Python still builds normally.
if command -v python >/dev/null 2>&1; then
  HTML_EXTRA="$WORK/html-extra.csv"
  if python "$ROOT/scripts/harvest_official_html.py" "$COMBINED" "$HTML_EXTRA" 48; then
    cat "$HTML_EXTRA" >> "$COMBINED" || true
  else
    echo "[Yinglong] HTML harvest failed; continuing with CSV pool" >&2
  fi
else
  echo "[Yinglong] python not found; skipping official HTML harvest" >&2
fi

# Keep historical candidates when an API response is only a partial slice.
# Fresh rows precede saved rows, so the newest profile wins for a duplicate IP.
if [ -f "$DEST" ]; then
  grep -Ev '^(\*vpn_servers|\*|#|[[:space:]]*$)' "$DEST" >> "$COMBINED" || true
fi

OUT="$WORK/out.csv"
{
  echo '*vpn_servers'
  echo '#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64'
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
