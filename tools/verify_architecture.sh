#!/usr/bin/env bash
set -e

echo "=== Neural Camera Architecture Rule Verification ==="

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo "Checking required module directories..."
REQUIRED_MODULES=(
    "app"
    "camera-core"
    "capture-intelligence"
    "neural-runtime"
    "neural-isp"
    "quality-engine"
    "video-engine"
    "device-profiles"
    "models"
    "benchmarks"
    "gallery"
    "ui"
    "data-lab"
    "tools"
    "docs"
)

for mod in "${REQUIRED_MODULES[@]}"; do
    if [ ! -d "$PROJECT_ROOT/$mod" ]; then
        echo "ERROR: Missing required directory: $mod"
        exit 1
    fi
    echo "  [OK] $mod exists"
done

echo "Checking required documentation files..."
REQUIRED_DOCS=(
    "docs/ARCHITECTURE.md"
    "docs/CAMERA_PIPELINE.md"
    "docs/UI_SYSTEM.md"
    "docs/AI_RUNTIME.md"
    "docs/DEVICE_PROFILES.md"
    "docs/MODEL_REGISTRY.md"
    "docs/BENCHMARKING.md"
    "docs/DECISIONS.md"
)

for doc in "${REQUIRED_DOCS[@]}"; do
    if [ ! -f "$PROJECT_ROOT/$doc" ]; then
        echo "ERROR: Missing documentation file: $doc"
        exit 1
    fi
    echo "  [OK] $doc exists"
done

echo "Checking dependency direction (UI must not import neural-runtime internals directly)..."
# Verify UI doesn't depend directly on neural-runtime
if grep -q 'project(":neural-runtime")' "$PROJECT_ROOT/ui/build.gradle.kts" 2>/dev/null; then
    echo "ERROR: Dependency violation: :ui must not directly depend on :neural-runtime"
    exit 1
fi
echo "  [OK] UI dependency boundary clean"

echo "=== All Architecture Checks Passed ==="
