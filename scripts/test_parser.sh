#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.parser-test"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/app/src/main/java/org/yinglong/client/catalog/Relay.java" \
  "$ROOT/app/src/main/java/org/yinglong/client/catalog/RelayCsv.java" \
  "$ROOT/tests/RelayCsvSmokeTest.java"
java -cp "$OUT" RelayCsvSmokeTest
rm -rf "$OUT"
