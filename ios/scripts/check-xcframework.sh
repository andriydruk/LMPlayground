#!/usr/bin/env bash
#
# Build phase: refuse to build against a missing or stale llama.xcframework
# (built from another llama.cpp revision or another version of the build
# scripts). Rebuild with ios/scripts/build-xcframework.sh.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$IOS_DIR")"
STAMP="$IOS_DIR/build-apple/llama.xcframework.stamp"

fail() {
    echo "error: $1 Run ios/scripts/build-xcframework.sh" >&2
    exit 1
}

[ -d "$IOS_DIR/build-apple/llama.xcframework" ] || fail "llama.xcframework is missing."
[ -f "$STAMP" ] || fail "llama.xcframework has no build stamp."

expected="$(git -C "$REPO_ROOT/core/llama.cpp" rev-parse HEAD) $(cat "$SCRIPT_DIR/build-xcframework.sh" "$IOS_DIR/native/CMakeLists.txt" "$REPO_ROOT/core/CMakeLists.txt" | shasum | cut -d' ' -f1)"
[ "$(cat "$STAMP")" = "$expected" ] || fail "llama.xcframework is stale (llama.cpp or its build scripts changed)."
