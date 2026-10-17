#!/usr/bin/env bash
#
# Builds ios/build-apple/llama.xcframework: llama.cpp, common, ggml (CPU,
# BLAS, Metal with the shader library embedded), mtmd and parakeet as one
# static library per slice:
#
#   ios-arm64            iPhone / iPad
#   ios-arm64-simulator  iOS simulator (CPU only at runtime)
#   macos-arm64          LlamaKit tests on the Mac, "Designed for iPad"
#
# Everything is compiled with -g so the app's dSYM covers the engine.
#
# LMP_REUSE_BUILD=1 keeps the CMake build directories between runs.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$IOS_DIR")"
LLAMA_DIR="$REPO_ROOT/core/llama.cpp"
SOURCE_DIR="$IOS_DIR/native"
OUT_DIR="$IOS_DIR/build-apple"
WORK_DIR="$OUT_DIR/cmake"
FRAMEWORK="$OUT_DIR/llama.xcframework"

IOS_MIN=26.0
MACOS_MIN=26.0

if [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
    echo "error: llama.cpp submodule missing. Run: git submodule update --init core/llama.cpp" >&2
    exit 1
fi

FLAGS="-Wno-macro-redefined -Wno-shorten-64-to-32 -Wno-unused-command-line-argument -g"
COMMON_ARGS=(
    -G Xcode
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY=""
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO
    -DCMAKE_XCODE_ATTRIBUTE_GCC_GENERATE_DEBUGGING_SYMBOLS=YES
    -DCMAKE_XCODE_ATTRIBUTE_COPY_PHASE_STRIP=NO
    -DCMAKE_XCODE_ATTRIBUTE_STRIP_INSTALLED_PRODUCT=NO
    -DCMAKE_C_FLAGS="$FLAGS"
    -DCMAKE_CXX_FLAGS="$FLAGS"
    -S "$SOURCE_DIR"
)

# slice name : config dir : extra cmake args
build_slice() {
    local slice=$1 config_dir=$2; shift 2
    local dir="$WORK_DIR/$slice"
    if [ "${LMP_REUSE_BUILD:-0}" != "1" ]; then
        rm -rf "$dir"
    fi
    echo "==> Configuring $slice"
    cmake -B "$dir" "${COMMON_ARGS[@]}" "$@" > "$dir.configure.log" 2>&1 || {
        tail -40 "$dir.configure.log" >&2; exit 1; }
    echo "==> Building $slice"
    cmake --build "$dir" --config Release -j "$(sysctl -n hw.logicalcpu)" -- -quiet > "$dir.build.log" 2>&1 || {
        grep -E "error:" "$dir.build.log" | head -40 >&2; tail -20 "$dir.build.log" >&2; exit 1; }

    # Every static archive the build produced for this configuration. Taking
    # them all (rather than a fixed list) means a library upstream adds is
    # never silently left out; the required list below catches renames.
    local archives=()
    while IFS= read -r a; do archives+=("$a"); done < <(find "$dir" -path "*/$config_dir/*.a" -type f | sort)
    local names=""
    for a in "${archives[@]}"; do names+=" $(basename "$a")"; done
    for required in libllama.a libllama-common.a libllama-common-base.a libcpp-httplib.a \
                    libggml.a libggml-base.a libggml-cpu.a libggml-metal.a libggml-blas.a \
                    libmtmd.a libparakeet.a; do
        case " $names " in
            *" $required "*) ;;
            *) echo "error: $required was not built for $slice (have:$names)" >&2; exit 1 ;;
        esac
    done

    # Merge into one archive with every member renamed <library>__<object>.o.
    # Different libraries reuse object names (conformer.o is in both mtmd and
    # parakeet, unicode.o in llama and common), and dsymutil drops the debug
    # info of all but one member per name — the dSYM would silently miss part
    # of the engine.
    local objs="$OUT_DIR/objs/$slice"
    rm -rf "$objs"
    for a in "${archives[@]}"; do
        local lib
        lib="$(basename "$a" .a)"
        local dupes
        dupes="$(ar -t "$a" | grep -v SYMDEF | sort | uniq -d)"
        if [ -n "$dupes" ]; then
            echo "error: $lib.a has duplicate members ($dupes); extracting would lose objects" >&2
            exit 1
        fi
        mkdir -p "$objs/$lib"
        (cd "$objs/$lib" && ar -x "$a" && rm -f __.SYMDEF*)
        for o in "$objs/$lib"/*.o; do
            mv "$o" "$objs/$lib/${lib}__$(basename "$o")"
        done
    done
    mkdir -p "$OUT_DIR/lib/$slice"
    rm -f "$OUT_DIR/lib/$slice/libllamacpp-apple.a"
    # libtool's stderr stays visible: a bad input must fail loudly, not
    # produce a broken archive.
    xcrun libtool -static -no_warning_for_no_symbols -o "$OUT_DIR/lib/$slice/libllamacpp-apple.a" "$objs"/*/*.o
    local merged_dupes
    merged_dupes="$(ar -t "$OUT_DIR/lib/$slice/libllamacpp-apple.a" | grep -v SYMDEF | sort | uniq -d)"
    if [ -n "$merged_dupes" ]; then
        echo "error: merged archive still has duplicate members: $merged_dupes" >&2
        exit 1
    fi
    rm -rf "$objs"
    echo "    merged:$names"
}

mkdir -p "$WORK_DIR"

build_slice ios-arm64 Release-iphoneos \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=$IOS_MIN -DGGML_METAL_TARGET_OS=ios \
    -DCMAKE_XCODE_ATTRIBUTE_SUPPORTED_PLATFORMS=iphoneos

build_slice ios-arm64-simulator Release-iphonesimulator \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphonesimulator -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=$IOS_MIN -DGGML_METAL_TARGET_OS=ios -DIOS=ON \
    -DCMAKE_XCODE_ATTRIBUTE_SUPPORTED_PLATFORMS=iphonesimulator

build_slice macos-arm64 Release \
    -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=$MACOS_MIN

echo "==> Creating $FRAMEWORK"
rm -rf "$FRAMEWORK"
xcrun xcodebuild -create-xcframework \
    -library "$OUT_DIR/lib/ios-arm64/libllamacpp-apple.a" \
    -library "$OUT_DIR/lib/ios-arm64-simulator/libllamacpp-apple.a" \
    -library "$OUT_DIR/lib/macos-arm64/libllamacpp-apple.a" \
    -output "$FRAMEWORK" > /dev/null

echo "==> Verifying"
device_lib="$FRAMEWORK/ios-arm64/libllamacpp-apple.a"
# Capture once: never pipe nm into `grep -q` under pipefail (grep exits on the
# first match, nm dies of SIGPIPE, and the pipeline reports a failure).
symbols="$(nm -gU "$device_lib" 2>/dev/null || true)"
for sym in common_chat_templates_apply common_sampler_sample mtmd_init_from_file \
           parakeet_capi_stream_begin llama_model_load_from_file ggml_backend_metal_init \
           gguf_init_from_file; do
    case "$symbols" in
        *"$sym"*) ;;
        *) echo "error: $sym missing from $device_lib" >&2; exit 1 ;;
    esac
done

# Stamp: the Xcode build refuses a framework built from another llama.cpp or
# with another version of this script / the Apple CMake.
stamp="$(git -C "$LLAMA_DIR" rev-parse HEAD) $(cat "$0" "$SOURCE_DIR/CMakeLists.txt" "$REPO_ROOT/core/CMakeLists.txt" | shasum | cut -d' ' -f1)"
echo "$stamp" > "$OUT_DIR/llama.xcframework.stamp"

echo "    slices: $(ls "$FRAMEWORK" | grep -v Info.plist | tr '\n' ' ')"
echo "    size:   $(du -sh "$FRAMEWORK" | cut -f1)"
echo "==> Done"
