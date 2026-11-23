#!/bin/sh
# Cross-compiles the UniFFI bridge for Android (arm64) in the podman container
# and drops the .so into the app's jniLibs. Run from the repo root.
#
# The Android NDK lives on the host (installed via the SDK's sdkmanager); it is
# mounted into the container because the host has no Rust toolchain.
set -eu

NDK="$HOME/Android/Sdk/ndk/27.2.12479018"
[ -d "$NDK" ] || { echo "NDK not found at $NDK" >&2; exit 1; }

API=26

podman run --rm \
  -v noscat-cargo-home:/usr/local/cargo \
  -v "$PWD/rust:/work:Z" \
  -v "$NDK:/ndk:Z" \
  -e NDK_HOME=/ndk \
  -e CC_aarch64_linux_android=/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang \
  -e AR_aarch64_linux_android=/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar \
  -e CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang \
  -w /work localhost/noscat-dev \
  sh -c "rustup target add aarch64-linux-android && cargo build --target aarch64-linux-android"

mkdir -p app/src/main/jniLibs/arm64-v8a
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  -o app/src/main/jniLibs/arm64-v8a/libnoscat_android.so \
  rust/target/aarch64-linux-android/debug/libnoscat_android.so
echo "installed app/src/main/jniLibs/arm64-v8a/libnoscat_android.so"
