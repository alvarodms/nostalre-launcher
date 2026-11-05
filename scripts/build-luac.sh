#!/usr/bin/env bash
# Builds luac 5.1 (luac/: Lua 5.1.5's compiler) for the host or Android. The app compiles the English translation's
# itemInfo.lua with it; on the host it is the luac5.1 scripts/import-english.py looks for, and the English import's
# test compares against it.
# Usage: scripts/build-luac.sh host|android
set -euo pipefail
source "$(dirname "$0")/common.sh"

target="${1:?usage: build-luac.sh host|android}"
build="$OUT/build/luac-$target"
args=( -S "$ROOT/luac" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release
	-DCMAKE_RUNTIME_OUTPUT_DIRECTORY="$OUT/$target/bin" )

# shellcheck disable=SC2207
[ "$target" = android ] && args+=( $(android_cmake_args) )

cmake "${args[@]}"
ninja -C "$build"
[ "$target" = android ] && "$NDK_BIN/llvm-strip" "$OUT/android/bin/luac"
echo "Built: $OUT/$target/bin/luac"
