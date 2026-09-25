#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
rm -f app/src/main/java/org/yinglong/client/net/VpnCatalogRefreshMonitor.java
chmod +x scripts/*.sh
echo "[Yinglong] v0.2 patch applied. Old app-start VPN catalogue monitor removed."
