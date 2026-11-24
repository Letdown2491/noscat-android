#!/bin/sh
# Cross-compiles noscat-ffi for Android arm64 in the podman container and
# installs the stripped .so plus regenerated Kotlin bindings into the app.
# Run from the repo root.
#
# The Android NDK lives on the host (installed via the SDK's sdkmanager); it is
# mounted into the container because the host has no Rust toolchain.
#
# Usage:
#   scripts/build-native.sh [version]   # build the published crate; default 0.4.2
#   scripts/build-native.sh local       # build from a noscat checkout
#                                       # (NOSCAT_DIR overrides, default ~/Documents/noscat)
set -eu

NDK="$HOME/Android/Sdk/ndk/27.2.12479018"
[ -d "$NDK" ] || { echo "NDK not found at $NDK" >&2; exit 1; }

API=26
COMMON_ENV="-v noscat-cargo-home:/usr/local/cargo:Z -v \"$NDK:/ndk:Z\" \
  -e NDK_HOME=/ndk \
  -e CC_aarch64_linux_android=/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang \
  -e AR_aarch64_linux_android=/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar \
  -e CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"

if [ "${1:-}" = "local" ]; then
  NOSCAT_DIR="${NOSCAT_DIR:-$HOME/Documents/noscat}"
  [ -d "$NOSCAT_DIR/crates/noscat-ffi" ] || {
    echo "noscat-ffi not found under $NOSCAT_DIR" >&2
    exit 1
  }
  podman run --rm \
    $(eval echo $COMMON_ENV) \
    -v "$NOSCAT_DIR:/noscat:Z" \
    -v "$PWD/app/src/main:/out:Z" \
    -w /noscat localhost/noscat-dev \
    sh -c "set -eu
      rustup target add aarch64-linux-android
      cargo build -p noscat-ffi --target aarch64-linux-android
      cargo run -q -p noscat-ffi --bin uniffi-bindgen -- generate \
        --library ./target/aarch64-linux-android/debug/libnoscat_ffi.so \
        --language kotlin --out-dir /tmp/bindings
      mkdir -p /out/jniLibs/arm64-v8a /out/java/uniffi/noscat
      /ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
        -o /out/jniLibs/arm64-v8a/libnoscat_ffi.so \
        ./target/aarch64-linux-android/debug/libnoscat_ffi.so
      cp /tmp/bindings/uniffi/noscat/noscat_ffi.kt /out/java/uniffi/noscat/noscat_android.kt"
  echo "installed jniLibs and regenerated bindings (local checkout: $NOSCAT_DIR)"
  exit 0
fi

NFFI_VERSION="${1:-0.4.2}"

podman run --rm \
  $(eval echo $COMMON_ENV) \
  -v "$PWD/app/src/main:/out:Z" \
  -w /tmp localhost/noscat-dev \
  sh -c "set -eu
    mkdir -p /tmp/pull/src && cd /tmp/pull
    printf '[package]\\nname=\\\"pull\\\"\\nversion=\\\"0.0.0\\\"\\nedition=\\\"2021\\\"\\n[dependencies]\\nnoscat-ffi=\\\"=${NFFI_VERSION}\\\"\\n' > Cargo.toml
    echo 'fn main() {}' > src/main.rs
    cargo fetch
    cp -r \$(ls -d /usr/local/cargo/registry/src/*/noscat-ffi-${NFFI_VERSION}) /tmp/ffi
    cd /tmp/ffi
    rustup target add aarch64-linux-android
    cargo build --target aarch64-linux-android
    cargo run -q --bin uniffi-bindgen -- generate \
      --library ./target/aarch64-linux-android/debug/libnoscat_ffi.so \
      --language kotlin --out-dir /tmp/bindings
    mkdir -p /out/jniLibs/arm64-v8a /out/java/uniffi/noscat
    /ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
      -o /out/jniLibs/arm64-v8a/libnoscat_ffi.so \
      ./target/aarch64-linux-android/debug/libnoscat_ffi.so
    cp /tmp/bindings/uniffi/noscat/noscat_ffi.kt /out/java/uniffi/noscat/noscat_android.kt"

echo "installed jniLibs and regenerated bindings (noscat-ffi ${NFFI_VERSION})"
