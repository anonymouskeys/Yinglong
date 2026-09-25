#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
MSG="${*:-Update Yinglong}"
OWNER="$(gh api user --jq .login)"
REPO="$OWNER/Yinglong"

git add .
if ! git diff --cached --quiet; then
  git commit -m "$MSG"
fi
git push origin main
SHA="$(git rev-parse HEAD)"

echo "[Yinglong] Waiting for GitHub Actions for $SHA…"
RUN_ID=""
for _ in $(seq 1 45); do
  RUN_ID="$(gh run list --repo "$REPO" --workflow android.yml --branch main --limit 10 \
    --json databaseId,headSha --jq ".[] | select(.headSha == \"$SHA\") | .databaseId" 2>/dev/null | head -n1 || true)"
  [ -n "$RUN_ID" ] && break
  sleep 2
done
if [ -z "$RUN_ID" ]; then
  echo "Workflow run for this commit did not appear." >&2
  exit 2
fi

gh run watch "$RUN_ID" --repo "$REPO" --exit-status

ART="$HOME/.cache/yinglong-artifact-$RUN_ID"
rm -rf "$ART" && mkdir -p "$ART"
gh run download "$RUN_ID" --repo "$REPO" --name Yinglong-debug-apk --dir "$ART"
APK="$(find "$ART" -type f -name Yinglong-debug.apk | head -n1)"
[ -n "$APK" ] || { echo "APK artifact missing" >&2; exit 3; }
mkdir -p "$HOME/storage/downloads"
cp -f "$APK" "$HOME/storage/downloads/Yinglong-debug.apk"
SHA_FILE="$(find "$ART" -type f -name Yinglong-debug.apk.sha256 | head -n1 || true)"
[ -n "$SHA_FILE" ] && cp -f "$SHA_FILE" "$HOME/storage/downloads/Yinglong-debug.apk.sha256"
echo "[Yinglong] APK -> $HOME/storage/downloads/Yinglong-debug.apk"
