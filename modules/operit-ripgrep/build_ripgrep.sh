#!/usr/bin/env bash
# Build liboperit_ripgrep.so for the 4 Android ABIs and copy into src/main/jniLibs.
#
# Mirrors upstream tools/native_ripgrep/build_native_ripgrep.ps1 (which targeted arm64 only).
# Prereqs: rustup targets aarch64-linux-android / armv7-linux-androideabi / i686-linux-android /
#          x86_64-linux-android, and the Android NDK (default below; override via ANDROID_NDK).
#
# Usage:  bash build_ripgrep.sh
set -euo pipefail

NDK="${ANDROID_NDK:-/d/develop/Android/sdk/android-sdk_r24.4.1-windows/android-sdk-windows/ndk/26.3.11579264}"
API="${ANDROID_API:-26}"
TB="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/rust"

declare -A TARGETS=(
  [aarch64-linux-android]="arm64-v8a:aarch64-linux-android${API}-clang.cmd"
  [armv7-linux-androideabi]="armeabi-v7a:armv7a-linux-androideabi${API}-clang.cmd"
  [i686-linux-android]="x86:i686-linux-android${API}-clang.cmd"
  [x86_64-linux-android]="x86_64:x86_64-linux-android${API}-clang.cmd"
)

for target in "${!TARGETS[@]}"; do
  IFS=':' read -r abi linker <<< "${TARGETS[$target]}"
  env_name="CARGO_TARGET_$(echo "$target" | tr 'a-z-' 'A-Z_')_LINKER"
  export "$env_name"="$TB/$linker"
  echo "=== $target ($abi) ==="
  cargo build --release --target "$target"
  mkdir -p "$HERE/src/main/jniLibs/$abi"
  cp "target/$target/release/liboperit_ripgrep.so" "$HERE/src/main/jniLibs/$abi/"
done

echo "Done. .so files:"
find "$HERE/src/main/jniLibs" -name '*.so' -printf '%s %p\n'
