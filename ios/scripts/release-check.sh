#!/usr/bin/env bash
#
# Run before every archive (ios/plan/06-build-and-release.md, "Release from
# Xcode"). Fails on any problem:
#
#   1. the llama.xcframework matches the submodule and build scripts;
#   2. the catalog and the string catalogs match Android;
#   3. the LlamaKit macOS tests and the app unit tests pass;
#   4. the version comes from version.properties, and the Info.plist and
#      privacy manifest are in place.
#
# The app unit tests run on LMP_TEST_DESTINATION (default: the iPhone 17 Pro
# simulator), e.g. LMP_TEST_DESTINATION='id=<device udid>' for a device.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT="$IOS_DIR/LMPlayground.xcodeproj"
DESTINATION="${LMP_TEST_DESTINATION:-platform=iOS Simulator,name=iPhone 17 Pro}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

step() { echo; echo "==> $1"; }

step "llama.xcframework"
"$SCRIPT_DIR/check-xcframework.sh"
echo "up to date"

step "Version and plists"
"$SCRIPT_DIR/bump-version.sh" --check
plist="$IOS_DIR/LMPlayground/Info.plist"
capabilities="$(/usr/libexec/PlistBuddy -c 'Print :UIRequiredDeviceCapabilities' "$plist")"
[[ "$capabilities" == *"iphone-performance-gaming-tier"* ]] || {
    echo "error: Info.plist lacks iphone-performance-gaming-tier (D11)" >&2; exit 1; }
plutil -lint "$IOS_DIR/LMPlayground/PrivacyInfo.xcprivacy" >/dev/null
echo "gaming tier and privacy manifest present"

step "Catalog"
python3 -I "$SCRIPT_DIR/generate-catalog.py" --check

step "Build (Release, generic iOS) for the string check"
xcodebuild build -project "$PROJECT" -scheme LMPlayground -configuration Release \
    -destination 'generic/platform=iOS' -derivedDataPath "$WORK/dd" -quiet
stringsdata="$(find "$WORK/dd/Build/Intermediates.noindex" -path '*LMPlayground.build/Objects-normal/arm64' -type d | head -1)"
python3 -I "$SCRIPT_DIR/generate-strings.py" --stringsdata "$stringsdata" --check

step "LlamaKit tests (macOS, Metal)"
xcodebuild test -project "$PROJECT" -scheme LlamaKit -destination 'platform=macOS' \
    -derivedDataPath "$WORK/dd-mac" -quiet

step "App unit tests ($DESTINATION)"
xcodebuild test -project "$PROJECT" -scheme LMPlayground -destination "$DESTINATION" \
    -only-testing:LMPlaygroundTests -derivedDataPath "$WORK/dd-test" -quiet

echo
echo "Release check passed. Archive in Xcode, upload with symbols, then run"
echo "  ios/scripts/verify-dsyms.sh <archive>.xcarchive"
