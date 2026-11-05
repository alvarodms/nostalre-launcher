#!/usr/bin/env bash
# Builds the Android app: rAthena's servers, the bridge and nostalRE for Android, a fresh database (the app creates
# the account), staged into out/build/apk and packed by Gradle. Game files are not bundled, the app reads them from
# storage.
# Usage: scripts/build-apk.sh [--skip-native] [--release] [--side]
#   --skip-native: reuse out/android/bin and the client's last Android build
#   --release: the release build (not debuggable) for publishing, signed with the release key; it needs the key in
#   ~/.gradle/gradle.properties (NOSTALRE_STORE_FILE, NOSTALRE_STORE_PASSWORD, NOSTALRE_KEY_ALIAS,
#   NOSTALRE_KEY_PASSWORD). Without --release a debug build, signed with the release key too when it is there.
#   --side: a test build that installs beside the app, not over it: its own package (.test) and name (nostalRE Test),
#   so its own characters and settings. Output: out/nostalre-test.apk
# NOSTALRE_DIR=<checkout> builds another nostalRE checkout than client/.
# Output: out/nostalre.apk
set -euo pipefail
source "$(dirname "$0")/common.sh"
skip_native=false release=false side=false
for arg in "$@"; do
	case "$arg" in
		--skip-native) skip_native=true ;;
		--release) release=true ;;
		--side) side=true ;;
		*) echo "unknown option: $arg" >&2; exit 1 ;;
	esac
done
client_info="$(native_client_info "$CLIENT")"
mapfile -t client_parts <<< "$client_info"
client_package="${client_parts[0]}"
client_library="${client_parts[1]}"

if ! $skip_native; then
	"$ROOT/scripts/build-server.sh" android
	"$ROOT/scripts/build-bridge.sh" android
	( cd "$CLIENT" && ANDROID_NDK_HOME="$NDK" cargo ndk -t "$ANDROID_ABI" -P "$ANDROID_API" \
		build --release -p "$client_package" --lib )
fi

if [ ! -f "$client_library" ]; then
	echo "nostalRE library missing: $client_library (build without --skip-native first)" >&2
	exit 1
fi

stage="$OUT/build/apk"
libs="$stage/jniLibs/$ANDROID_ABI"
rm -rf "$stage" && mkdir -p "$libs" "$stage/assets"

# executables ship as lib*.so: only the native library dir may hold files an app can run
for bin in login-server char-server map-server grf-server; do
	cp "$OUT/android/bin/$bin" "$libs/lib${bin//-/}.so"
done
"$NDK_BIN/llvm-strip" -o "$libs/$(basename "$client_library")" "$client_library"

# the licenses and notices of what the APK carries: this repository (the app, the bridge, rAthena's GPL) and
# nostalRE's own
notices="$stage/assets/licenses"
mkdir -p "$notices/nostalre/rust-ro" "$notices/nostalre/winit" "$notices/nostalre/fonts"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$notices/"
cp "$CLIENT/LICENSE.md" "$CLIENT/NOTICE" "$notices/nostalre/"
cp -r "$CLIENT/LICENSES" "$notices/nostalre/"
cp "$CLIENT/rust-ro/LICENSE.md" "$notices/nostalre/rust-ro/"
cp "$CLIENT/vendor/winit/LICENSE" "$notices/nostalre/winit/"
cp "$CLIENT/lib/renderer/src/fonts/"*.txt "$notices/nostalre/fonts/"

# tracked files only, import/ fresh from import-tmpl: local server tweaks (git ignored) stay out of the app
server="$stage/server"
mkdir -p "$server"
( cd "$RATHENA" && git ls-files -z conf db npc | tar --null -T - -cf - ) | tar -xf - -C "$server"
server_imports "$server"
( cd "$server" && python3 -I -m zipfile -c "$stage/assets/server.zip" conf db npc )
RA_DB="$stage/assets/ragnarok.db" "$ROOT/scripts/make-db.sh" --force

gradle_args=( --quiet )
name=nostalre
if $side; then
	gradle_args+=( -PnostalreSide )
	name=nostalre-test
fi
if $release; then
	( cd "$ROOT/android" && ./gradlew "${gradle_args[@]}" assembleRelease )
	apk="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
	[ -f "$apk" ] || { echo "no release key: the release build is unsigned (see --release above)" >&2; exit 1; }
	"$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* | sort -V | tail -1)/apksigner" verify "$apk"
else
	( cd "$ROOT/android" && ./gradlew "${gradle_args[@]}" assembleDebug )
	apk="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
cp "$apk" "$OUT/$name.apk"
echo "Built: $OUT/$name.apk   (install: adb install -r out/$name.apk)"
