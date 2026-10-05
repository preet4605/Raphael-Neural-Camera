#!/usr/bin/env bash
set -e

echo "=== Neural Camera Build & Verification Suite ==="
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo "1. Verifying Architecture Boundaries..."
bash tools/verify_architecture.sh

echo "2. Running Unit Tests across all modules..."
./gradlew test

echo "3. Assembling Debug APK..."
./gradlew assembleDebug

echo "4. Copying APK to project root..."
cp -f app/build/outputs/apk/debug/app-debug.apk "$PROJECT_ROOT/neural-camera-debug.apk"

echo "5. Verifying APK File Details..."
ls -lh "$PROJECT_ROOT/neural-camera-debug.apk"
md5sum "$PROJECT_ROOT/neural-camera-debug.apk"

echo "=== Build & Verification Complete ==="
