#!/usr/bin/env bash
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

COMMAND="${1:-help}"
ADB_BIN="${ADB_BIN:-$(which adb 2>/dev/null || echo "/root/android-sdk/platform-tools/adb")}"
PACKAGE_NAME="com.neuralcamera.app.debug"
MAIN_ACTIVITY="com.neuralcamera.app.MainActivity"

case "$COMMAND" in
    build)
        echo "=== Building Neural Camera Debug APK ==="
        ./gradlew assembleDebug
        cp -f app/build/outputs/apk/debug/app-debug.apk neural-camera-debug.apk
        ls -lh neural-camera-debug.apk
        ;;
    install)
        echo "=== Installing APK via ADB ==="
        "$ADB_BIN" install -r neural-camera-debug.apk
        ;;
    launch)
        echo "=== Launching Neural Camera ==="
        "$ADB_BIN" shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY"
        ;;
    clear-logs)
        echo "=== Clearing Logcat ==="
        "$ADB_BIN" logcat -c
        ;;
    collect-logcat)
        echo "=== Collecting Camera Logcat ==="
        "$ADB_BIN" logcat -d -s "NeuralCamera:V" "CameraCore:V" "Camera2:V" "AndroidRuntime:E" > camera_logcat.txt
        echo "Saved to camera_logcat.txt"
        ;;
    collect-profiles)
        echo "=== Collecting Runtime Capability Profiles ==="
        mkdir -p profiles/runtime/pulled
        "$ADB_BIN" pull /data/data/com.neuralcamera.app/files/profiles profiles/runtime/pulled || true
        ;;
    pull-test-images)
        echo "=== Pulling Captured Test Images ==="
        mkdir -p captures/pulled
        "$ADB_BIN" pull /data/data/com.neuralcamera.app/files/captures captures/pulled || true
        ;;
    pull-diagnostics)
        echo "=== Pulling Telemetry and Benchmark Data ==="
        mkdir -p benchmarks/pulled
        "$ADB_BIN" pull /data/data/com.neuralcamera.app/files/benchmarks benchmarks/pulled || true
        ;;
    *)
        echo "Usage: $0 {build|install|launch|clear-logs|collect-logcat|collect-profiles|pull-test-images|pull-diagnostics}"
        exit 1
        ;;
esac
