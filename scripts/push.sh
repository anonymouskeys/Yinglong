#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
MSG="${*:-Update Yinglong}"

OWNER="$(gh api user --jq .login)"
REPO="$OWNER/Yinglong"

# Best-effort seed refresh before every build. If censorship blocks VPN Gate right now,
# keep the previous bundled pool rather than preventing source pushes.
echo "[Yinglong] Refreshing bundled relay seed (best effort)…"
if ! bash scripts/update_seed.sh; then
  echo "[Yinglong] Seed refresh failed; keeping existing relays_seed.csv" >&2
fi

git add .
if ! git diff --cached --quiet; then
  git commit -m "$MSG"
fi
SHA="$(git rev-parse HEAD)"
git push origin main

echo "[Yinglong] Waiting for GitHub Actions for commit $SHA"
RUN_ID=""
for _ in $(seq 1 45); do
  RUN_ID="$(gh run list --repo "$REPO" --workflow android.yml --branch main --commit "$SHA" --limit 1 \
    --json databaseId --jq '.[0].databaseId // empty' 2>/dev/null || true)"
  [ -n "$RUN_ID" ] && break
  sleep 2
done

if [ -z "$RUN_ID" ]; then
  echo "Push succeeded, but no GitHub Actions run appeared for $SHA." >&2
  echo "https://github.com/$REPO/actions" >&2
  exit 2
fi

gh run watch "$RUN_ID" --repo "$REPO" --exit-status

ARTIFACT_DIR="$HOME/.cache/yinglong-artifact-$RUN_ID"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
gh run download "$RUN_ID" --repo "$REPO" --name Yinglong-debug-apk --dir "$ARTIFACT_DIR"

APK_PATH="$(find "$ARTIFACT_DIR" -type f -name 'Yinglong-debug.apk' | head -n 1)"
if [ -z "$APK_PATH" ]; then
  echo "Build succeeded, but Yinglong-debug.apk was not found." >&2
  exit 3
fi

if [ -d "$HOME/storage/downloads" ]; then
  cp -f "$APK_PATH" "$HOME/storage/downloads/Yinglong-debug.apk"
  SHA_PATH="$(find "$ARTIFACT_DIR" -type f -name 'Yinglong-debug.apk.sha256' | head -n 1 || true)"
  [ -n "$SHA_PATH" ] && cp -f "$SHA_PATH" "$HOME/storage/downloads/Yinglong-debug.apk.sha256"
  echo "APK: $HOME/storage/downloads/Yinglong-debug.apk"
fi

echo "Repository: https://github.com/$REPO"
