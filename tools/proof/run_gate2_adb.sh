#!/usr/bin/env bash
# Runs the Gate 2 probe in THREE separate app processes on a connected device, pulls the raw evidence, and runs the
# independent checker. UNTESTED against a device from the authoring environment (no adb there); read before trusting.
#
#   tools/proof/run_gate2_adb.sh [output-dir]
#
# Requires: adb, a debug build of the app installed, python3.
set -euo pipefail

PKG="com.neuralcamera.app.debug"
ACTIVITY="$PKG/com.neuralcamera.app.proof.Gate2ProbeActivity"
REMOTE="/sdcard/Android/data/$PKG/files/gate2"
OUT="${1:-gate2_evidence_$(date +%Y%m%d_%H%M%S)}"
RUNS=3
TIMEOUT_S=900

mkdir -p "$OUT"
adb shell "rm -rf '$REMOTE'" || true

for i in $(seq 1 "$RUNS"); do
  echo "== run $i/$RUNS: fresh process"
  adb shell am force-stop "$PKG"
  adb shell am start -n "$ACTIVITY" --ez autorun true >/dev/null
  waited=0
  until adb shell "ls '$REMOTE'/*/gate2_suite_summary.json 2>/dev/null | wc -l" | tr -d '\r' | grep -qx "$i"; do
    sleep 5
    waited=$((waited + 5))
    if [ "$waited" -ge "$TIMEOUT_S" ]; then
      echo "timed out waiting for run $i; pulling what exists" >&2
      break
    fi
  done
done

adb shell am force-stop "$PKG"
adb pull "$REMOTE" "$OUT/" >/dev/null
echo "== evidence pulled to $OUT"
python3 "$(dirname "$0")/check_gate2.py" "$OUT"
