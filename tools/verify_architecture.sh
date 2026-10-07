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
    "docs/PROOF_GATES.md"
    "docs/TEMPORAL_PIPELINE.md"
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

echo "Checking proof flags (code never holds flag state; docs say TRUE only with committed checker output)..."
FLAGS=(RAW_BURST_PROVEN HTP_INFERENCE_PROVEN CLASSICAL_MERGE_ADVANTAGE_PROVEN NEURAL_ISP_PROVEN PRODUCT_ADVANTAGE_PROVEN FULL_PIPELINE_PROVEN)
for flag in "${FLAGS[@]}"; do
    if grep -rlw --include='*.kt' --include='*.kts' --include='*.java' "$flag" "$PROJECT_ROOT" --exclude-dir=build --exclude-dir=.git >/dev/null 2>&1; then
        echo "ERROR: $flag appears in source code; proof flags come only from the gate checkers"
        exit 1
    fi
    if grep -Eq "^\| \`$flag\` \| \`TRUE\` \|" "$PROJECT_ROOT/docs/PROOF_GATES.md"; then
        evidence="$PROJECT_ROOT/proof/$(echo "$flag" | tr '[:upper:]' '[:lower:]')/checker_output.txt"
        if ! grep -q "^$flag=TRUE" "$evidence" 2>/dev/null; then
            echo "ERROR: docs/PROOF_GATES.md marks $flag TRUE but $evidence does not contain the checker's $flag=TRUE line"
            exit 1
        fi
    fi
    echo "  [OK] $flag"
done

echo "=== All Architecture Checks Passed ==="
