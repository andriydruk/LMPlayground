#!/usr/bin/env bash
#
# App Store screenshots: the iOS counterpart of Android's Paparazzi
# StoreScreenshots (ios/plan/11-store-screenshots.md).
#
# Builds the Debug app for the simulator, then for every device, locale and
# scene launches the app with `-storeScene <id>` (LMPlayground/StoreScenes),
# waits for the scene to mark itself ready and captures it with
# `simctl io screenshot`. The PNGs, flattened (App Store Connect rejects an
# alpha channel), land in ios/fastlane/screenshots/<App Store locale>/, where
# `fastlane upload_screenshots` picks them up; deliver tells the device slot
# from the pixel size.
#
# Usage: store-screenshots.sh [--devices "iphone ipad"] [--locales "en fr"]
#                             [--scenes "0_hero 2_chat"] [--skip-build]
#
# Devices: iphone (iPhone 17 Pro, 1206×2622, the required iPhone size),
# iphone-max (iPhone 17 Pro Max, 1320×2868, optional), ipad (iPad Pro 13-inch,
# 2064×2752). Each gets its own simulator, "LMP Store <device>", created on
# first use. Needs ImageMagick (`brew install imagemagick`).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$IOS_DIR/build-screenshots"
OUT_DIR="$IOS_DIR/fastlane/screenshots"
APP="$BUILD_DIR/Build/Products/Debug-iphonesimulator/LMPlayground.app"
BUNDLE_ID="com.druk.lmplayground"

DEVICES="iphone ipad"
SCENES="0_hero 1_choose_model 2_chat 3_documents 4_chat_with_image 5_system_prompts 6_tools 7_generation_params"
LOCALES=""
SKIP_BUILD=0
while [ $# -gt 0 ]; do
    case "$1" in
        --devices) DEVICES="$2"; shift 2 ;;
        --locales) LOCALES="$2"; shift 2 ;;
        --scenes) SCENES="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

# App language, AppleLocale, App Store Connect locale. Filipino has no App
# Store listing, so it gets no screenshots.
LOCALE_TABLE="
en en_US en-US
ar ar_SA ar-SA
bn bn_BD bn-BD
cs cs_CZ cs
da da_DK da
de de_DE de-DE
es es_ES es-ES
fi fi_FI fi
fr fr_FR fr-FR
he he_IL he
hi hi_IN hi
id id_ID id
it it_IT it
ja ja_JP ja
ko ko_KR ko
ms ms_MY ms
nb nb_NO no
nl nl_NL nl-NL
pl pl_PL pl
pt-BR pt_BR pt-BR
ro ro_RO ro
sv sv_SE sv
th th_TH th
tr tr_TR tr
uk uk_UA uk
vi vi_VN vi
zh-Hans zh_CN zh-Hans
"
[ -n "$LOCALES" ] || LOCALES="$(echo "$LOCALE_TABLE" | awk 'NF { print $1 }' | tr '\n' ' ')"

device_type() {
    case "$1" in
        iphone) echo "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro 1206x2622" ;;
        iphone-max) echo "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max 1320x2868" ;;
        ipad) echo "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M5-12GB 2064x2752" ;;
        *) echo "unknown device: $1" >&2; exit 2 ;;
    esac
}

command -v magick >/dev/null || { echo "error: ImageMagick is missing (brew install imagemagick)" >&2; exit 1; }

# Run "$@", killed after $1 seconds: a wedged simctl call must not hang the run.
with_timeout() {
    local seconds=$1; shift
    "$@" &
    local pid=$!
    ( sleep "$seconds"; kill -9 "$pid" 2>/dev/null ) &
    local watcher=$!
    local status=0
    wait "$pid" || status=$?
    kill "$watcher" 2>/dev/null || true
    wait "$watcher" 2>/dev/null || true
    return "$status"
}

# The UDID of "LMP Store <device>", created with the newest iOS runtime that
# supports the device.
simulator() {
    local name="LMP Store $1" type=$2
    xcrun simctl list devices available -j | python3 -I -c '
import json, sys
name = sys.argv[1]
for devices in json.load(sys.stdin)["devices"].values():
    for d in devices:
        if d["name"] == name:
            print(d["udid"]); sys.exit()
sys.exit(1)
' "$name" && return
    local runtime
    runtime="$(xcrun simctl list runtimes available -j | python3 -I -c '
import json, sys
ios = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS"
       and any(t["identifier"] == sys.argv[1] for t in r.get("supportedDeviceTypes", []))]
print(max(ios, key=lambda r: [int(p) for p in r["version"].split(".")])["identifier"])' "$type")"
    xcrun simctl create "$name" "$type" "$runtime"
}

if [ "$SKIP_BUILD" -eq 0 ]; then
    echo "==> Building the Debug app for the simulator"
    xcodebuild build -project "$IOS_DIR/LMPlayground.xcodeproj" -scheme LMPlayground -configuration Debug \
        -destination 'generic/platform=iOS Simulator' -derivedDataPath "$BUILD_DIR" \
        ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO -quiet
fi
[ -d "$APP" ] || { echo "error: $APP is missing; run without --skip-build" >&2; exit 1; }

failures=0
for device in $DEVICES; do
    read -r type expected <<< "$(device_type "$device")"
    udid="$(simulator "$device" "$type")"
    echo "==> $device ($udid)"
    xcrun simctl boot "$udid" 2>/dev/null || true
    xcrun simctl bootstatus "$udid" -b >/dev/null
    xcrun simctl install "$udid" "$APP"
    ready="$(xcrun simctl get_app_container "$udid" "$BUNDLE_ID" data)/Documents/store-scene-ready"

    for locale in $LOCALES; do
        read -r language region store <<< "$(echo "$LOCALE_TABLE" | awk -v l="$locale" '$1 == l')"
        [ -n "${store:-}" ] || { echo "unknown locale: $locale" >&2; exit 2; }
        mkdir -p "$OUT_DIR/$store"
        for scene in $SCENES; do
            out="$OUT_DIR/$store/${scene}_$device.png"
            raw="$(mktemp -t store-scene).png"
            ok=0
            for attempt in 1 2; do
                rm -f "$ready"
                with_timeout 60 xcrun simctl launch --terminate-running-process "$udid" "$BUNDLE_ID" \
                    -storeScene "$scene" -AppleLanguages "($language)" -AppleLocale "$region" >/dev/null || continue
                for _ in $(seq 1 60); do
                    [ -f "$ready" ] && break
                    sleep 0.5
                done
                [ -f "$ready" ] || continue
                with_timeout 30 xcrun simctl io "$udid" screenshot --type=png "$raw" >/dev/null 2>&1 || continue
                ok=1
                break
            done
            if [ "$ok" -eq 1 ]; then
                # Paint out the home indicator, which the app can't reliably
                # hide in time: it sits on a plain strip at the bottom of every
                # scene, so fill that with the strip's own colour.
                read -r width height <<< "$(magick identify -format '%w %h' "$raw")"
                half=$((width / 4))
                band=$((height / 60))
                colour="$(magick "$raw" -format "%[pixel:p{$((width / 2 - half - 10)),$((height - 4))}]" info:)"
                magick "$raw" -fill "$colour" \
                    -draw "rectangle $((width / 2 - half)),$((height - band)) $((width / 2 + half)),$((height - 1))" \
                    -alpha off "$out"
                size="$(magick identify -format '%wx%h' "$out")"
                if [ "$size" = "$expected" ]; then
                    echo "  $store/${scene}_$device.png"
                else
                    echo "  $store/${scene}_$device.png is $size, expected $expected" >&2
                    failures=$((failures + 1))
                fi
            else
                echo "  FAILED $store $scene" >&2
                failures=$((failures + 1))
            fi
            rm -f "$raw"
        done
    done
    xcrun simctl terminate "$udid" "$BUNDLE_ID" 2>/dev/null || true
    xcrun simctl shutdown "$udid" 2>/dev/null || true
done

if [ "$failures" -gt 0 ]; then
    echo "$failures screenshots failed" >&2
    exit 1
fi
echo "Screenshots are in $OUT_DIR"
