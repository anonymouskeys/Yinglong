#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required and must already be authenticated." >&2
  exit 1
fi
if ! command -v git >/dev/null 2>&1; then
  echo "git is required." >&2
  exit 1
fi

OWNER="$(gh api user --jq .login)"
USER_ID="$(gh api user --jq .id)"
REPO="$OWNER/Yinglong"
gh auth setup-git >/dev/null 2>&1 || true

echo "[1/6] Refreshing seed relay catalogue"
./scripts/update_seed.sh

echo "[2/6] Initializing local Git repository"
if [ ! -d .git ]; then
  git init -b main
fi
git config user.name "$OWNER"
git config user.email "${USER_ID}+${OWNER}@users.noreply.github.com"
git add .
if ! git diff --cached --quiet; then
  git commit -m "Initial Yinglong Android client"
fi
git branch -M main

echo "[3/6] Creating/connecting GitHub repository $REPO"
if ! git remote get-url origin >/dev/null 2>&1; then
  if gh repo view "$REPO" >/dev/null 2>&1; then
    git remote add origin "https://github.com/$REPO.git"
  else
    gh repo create Yinglong --public --source=. --remote=origin \
      --description "Yinglong: resilient Android VPN relay client"
  fi
fi

echo "[4/6] Pushing source to GitHub"
git push -u origin main

echo "[5/6] Waiting for GitHub Actions to build the APK"
RUN_ID=""
for _ in $(seq 1 30); do
  RUN_ID="$(gh run list --repo "$REPO" --workflow android.yml --branch main --limit 1 \
    --json databaseId --jq '.[0].databaseId // empty' 2>/dev/null || true)"
  [ -n "$RUN_ID" ] && break
  sleep 2
done

if [ -z "$RUN_ID" ]; then
  echo "Source pushed, but the workflow run has not appeared yet." >&2
  echo "Open: https://github.com/$REPO/actions" >&2
  exit 2
fi

gh run watch "$RUN_ID" --repo "$REPO" --exit-status

echo "[6/6] Downloading GitHub-built APK to Downloads"
ARTIFACT_DIR="$HOME/.cache/yinglong-artifact-$RUN_ID"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
gh run download "$RUN_ID" --repo "$REPO" --name Yinglong-debug-apk --dir "$ARTIFACT_DIR"

APK_PATH="$(find "$ARTIFACT_DIR" -type f -name 'Yinglong-debug.apk' | head -n 1)"
if [ -z "$APK_PATH" ]; then
  echo "GitHub build succeeded, but Yinglong-debug.apk was not found in the artifact." >&2
  exit 3
fi

if [ -d "$HOME/storage/downloads" ]; then
  cp -f "$APK_PATH" "$HOME/storage/downloads/Yinglong-debug.apk"
  SHA_PATH="$(find "$ARTIFACT_DIR" -type f -name 'Yinglong-debug.apk.sha256' | head -n 1 || true)"
  [ -n "$SHA_PATH" ] && cp -f "$SHA_PATH" "$HOME/storage/downloads/Yinglong-debug.apk.sha256"
  echo "APK: $HOME/storage/downloads/Yinglong-debug.apk"
fi

echo "Repository: https://github.com/$REPO"
echo "Actions:    https://github.com/$REPO/actions"
