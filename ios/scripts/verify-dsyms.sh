#!/usr/bin/env bash
#
# Prove an archive's symbols are complete (requirement R2) before or after
# uploading it:
#
#   1. every Mach-O in the app has a dSYM with a matching UUID;
#   2. LlamaKit's dSYM has DWARF for the engine, llama.cpp, ggml-metal and
#      parakeet;
#   3. atos resolves an engine function back to its source file.
#
#   verify-dsyms.sh <path to .xcarchive>
#
set -euo pipefail

ARCHIVE="${1:?usage: verify-dsyms.sh <path to .xcarchive>}"
APP="$ARCHIVE/Products/Applications/LMPlayground.app"
DSYMS="$ARCHIVE/dSYMs"
failures=0

fail() {
    echo "FAIL: $1" >&2
    failures=$((failures + 1))
}

[ -d "$APP" ] || { echo "error: no LMPlayground.app in $ARCHIVE" >&2; exit 1; }
[ -d "$DSYMS" ] || { echo "error: no dSYMs folder in $ARCHIVE" >&2; exit 1; }

# UUIDs of every dSYM in the archive, one per line.
dsym_uuids="$(for d in "$DSYMS"/*.dSYM; do dwarfdump --uuid "$d"; done)"

# 1. Each binary's UUID appears among the dSYMs.
binaries=("$APP/LMPlayground")
for framework in "$APP"/Frameworks/*.framework; do
    [ -d "$framework" ] || continue
    binaries+=("$framework/$(basename "$framework" .framework)")
done
for binary in "${binaries[@]}"; do
    while read -r _ uuid _; do
        [ -n "$uuid" ] || continue
        if [[ "$dsym_uuids" == *"$uuid"* ]]; then
            echo "ok   $(basename "$binary") $uuid"
        else
            fail "$(basename "$binary") $uuid has no dSYM"
        fi
    done < <(dwarfdump --uuid "$binary")
done

# 2. The engine and the libraries it links carry DWARF in LlamaKit's dSYM.
LLAMAKIT_DSYM="$DSYMS/LlamaKit.framework.dSYM"
if [ -d "$LLAMAKIT_DSYM" ]; then
    # Captured, then searched: grep -q in a pipeline trips pipefail.
    units="$(dwarfdump --debug-info "$LLAMAKIT_DSYM" | grep DW_AT_name || true)"
    for source in LlamaGenerationSession.cpp ggml-metal-context.m llama-model.cpp parakeet.cpp; do
        if [[ "$units" == *"$source"* ]]; then
            echo "ok   DWARF for $source"
        else
            fail "LlamaKit dSYM has no DWARF for $source"
        fi
    done

    # 3. atos maps an engine function to its source line.
    LLAMAKIT="$APP/Frameworks/LlamaKit.framework/LlamaKit"
    symbol="$(nm -arch arm64 "$LLAMAKIT" | grep -E ' T __ZN22LlamaGenerationSession8generate' | head -1 || true)"
    if [ -n "$symbol" ]; then
        address="0x${symbol%% *}"
        resolved="$(atos -arch arm64 -o "$LLAMAKIT_DSYM/Contents/Resources/DWARF/LlamaKit" -l 0x0 "$address")"
        if [[ "$resolved" == *"LlamaGenerationSession.cpp:"* ]]; then
            echo "ok   atos: $resolved"
        else
            fail "atos didn't resolve $address to a source line: $resolved"
        fi
    else
        fail "LlamaGenerationSession::generate not found in LlamaKit (stripped of exports?)"
    fi
else
    fail "no LlamaKit.framework.dSYM"
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures problem(s): this archive's crashes won't symbolicate fully." >&2
    exit 1
fi
echo "All symbols present."
