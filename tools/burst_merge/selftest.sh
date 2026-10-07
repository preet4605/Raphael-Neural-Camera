#!/usr/bin/env bash
# End-to-end check on a synthetic burst: generate DNGs, run the tool through Gradle, compare against ground truth.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
GRADLE="${GRADLE:-$HERE/../../gradlew}"
python3 "$HERE/make_synthetic_burst.py" "$TMP/burst" 512 384 6
"$GRADLE" -p "$HERE" --no-daemon -q run --args="--input $TMP/burst --output $TMP/out --reference 0 --display-p3 --exposure 0.4"
python3 "$HERE/check_selftest.py" "$TMP/burst" "$TMP/out"
