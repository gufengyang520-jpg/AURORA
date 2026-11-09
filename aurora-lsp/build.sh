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

# The artifact name comes from the build file, not from this script. Keeping a copy of the version
# here meant renaming the output by hand on every release, and the release that was missed shipped
# an APK whose filename disagreed with the versionName inside it.
VERSION_NAME="$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)"
if [ -z "$VERSION_NAME" ]; then
    echo "build.sh: could not read versionName from app/build.gradle.kts" >&2
    exit 1
fi

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
OUT="$ROOT/out/AURORA-$VERSION_NAME.apk"
mkdir -p "$ROOT/out"
cp -f "$APK" "$OUT"
ls -lh "$OUT"
sha256sum "$OUT"