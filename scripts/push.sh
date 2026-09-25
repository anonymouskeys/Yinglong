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

echo "Pushed. GitHub Actions will build the APK: https://github.com/$REPO/actions"
