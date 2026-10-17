#!/usr/bin/env bash
#
# Write ios/Config/Version.xcconfig: MARKETING_VERSION from the repo-root
# version.properties (shared with Android, D12) and the next build number.
#
#   bump-version.sh            next build number
#   bump-version.sh --build N  set the build number explicitly
#   bump-version.sh --check    fail if MARKETING_VERSION is out of date
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$IOS_DIR")"
PROPS="$REPO_ROOT/version.properties"
XCCONFIG="$IOS_DIR/Config/Version.xcconfig"

prop() {
    local value
    value="$(sed -n "s/^$1=//p" "$PROPS")"
    [ -n "$value" ] || { echo "error: $1 missing from $PROPS" >&2; exit 1; }
    echo "$value"
}

marketing="$(prop major).$(prop minor).$(prop patch)"
current_marketing="$(sed -n 's/^MARKETING_VERSION = //p' "$XCCONFIG")"
current_build="$(sed -n 's/^CURRENT_PROJECT_VERSION = //p' "$XCCONFIG")"

if [ "${1:-}" = "--check" ]; then
    if [ "$current_marketing" != "$marketing" ]; then
        echo "error: Version.xcconfig has $current_marketing, version.properties says $marketing. Run ios/scripts/bump-version.sh" >&2
        exit 1
    fi
    echo "Version $current_marketing ($current_build)"
    exit 0
fi

if [ "${1:-}" = "--build" ]; then
    build="${2:?usage: bump-version.sh --build N}"
else
    build=$(( ${current_build:-0} + 1 ))
fi

cat > "$XCCONFIG" <<EOF
// Written by scripts/bump-version.sh from the repo-root version.properties.

MARKETING_VERSION = $marketing
CURRENT_PROJECT_VERSION = $build
EOF
echo "Version $marketing ($build)"
