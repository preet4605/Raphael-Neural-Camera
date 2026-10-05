#!/usr/bin/env bash
# Runs the Gate 1 RAW burst probe in THREE separate app processes on a connected device, pulls the raw evidence (about
# 800 MB of DNG per run), and runs the independent checker. UNTESTED against a device from the authoring environment
# (no adb there); read before trusting.
#
#   tools/proof/run_gate1_adb.sh [output-dir] [camera-id]
#
# Requires: adb, a debug build of the app installed, python3, ~3 GB free on the device and the host.
set -euo pipefail

PKG="com.neuralcamera.app.debug"
ACTIVITY="$PKG/com.neuralcamera.app.proof.Gate1ProbeActivity"
REMOTE="/sdcard/Android/data/$PKG/files/gate1"
OUT="${1:-gate1_evidence_$(date +%Y%m%d_%H%M%S)}"
CAMERA_ARGS=()
[ -n "${2:-}" ] && CAMERA_ARGS=(--es camera "$2")
RUNS=3
TIMEOUT_S=600

mkdir -p "$OUT"
adb shell pm grant "$PKG" android.permission.CAMERA
adb shell "rm -rf '$REMOTE'" || true

for i in $(seq 1 "$RUNS"); do
  echo "== run $i/$RUNS: fresh process"
  adb shell am force-stop "$PKG"
  adb shell am start -n "$ACTIVITY" --ez autorun true "${CAMERA_ARGS[@]}" >/dev/null
  waited=0
  until adb shell "ls '$REMOTE'/*/gate1_report.json 2>/dev/null | wc -l" | tr -d '\r' | grep -qx "$i"; do
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
python3 "$(dirname "$0")/check_gate1.py" "$OUT"
