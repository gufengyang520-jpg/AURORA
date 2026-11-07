#!/usr/bin/env bash
# AURORA build entry point.
#
# Requires: WSL Ubuntu, JDK 17, Android SDK with platforms/android-37 (see README),
# and Gradle 9.3.1 at ~/gradle-9.3.1/bin/gradle.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE="${GRADLE:-$HOME/gradle-9.3.1/bin/gradle}"

cd "$ROOT"
"$GRADLE" :app:assembleRelease --no-daemon
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
mkdir -p "$ROOT/out"
cp -f "$APK" "$ROOT/out/AURORA-1.2.3.apk"
ls -lh "$ROOT/out/AURORA-1.2.3.apk"
sha256sum "$ROOT/out/AURORA-1.2.3.apk"