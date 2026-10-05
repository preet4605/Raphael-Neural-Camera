#!/usr/bin/env bash
set -e

echo "=== Running Neural Camera Test Suite across all 13 modules ==="
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

./gradlew test --continue

echo "=== All Unit Tests Passed Successfully ==="
