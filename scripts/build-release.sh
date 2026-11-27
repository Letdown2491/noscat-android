#!/bin/sh
# Builds the release APK: the optimized native bridge, then a signed
# assembleRelease. Signing needs keystore.properties at the repo root (see
# app/build.gradle.kts); without it the APK is unsigned.
#
# Usage:
#   scripts/build-release.sh [version|local]   # as build-native.sh; default 0.7.1
set -eu
cd "$(dirname "$0")/.."
PROFILE=release scripts/build-native.sh "${1:-0.7.1}"
JAVA_HOME="${JAVA_HOME:-$(echo "$HOME"/opt/jdk-21*)}" ./gradlew :app:assembleRelease --no-daemon -q
ls -l app/build/outputs/apk/release/
