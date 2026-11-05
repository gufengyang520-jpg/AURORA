#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/android-sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
OUT="$ROOT/out"
CLASSES="$OUT/classes"
VERSION="2.0.1"
APK="AURORA-LSP-$VERSION.apk"

mkdir -p "$CLASSES" "$OUT"
rm -rf "$CLASSES" "$OUT/dex"
mkdir -p "$CLASSES" "$OUT/dex"

javac -source 8 -target 8 -encoding UTF-8 \
  -classpath "$PLATFORM:$ROOT/xposed-api.jar" \
  -d "$CLASSES" \
  $(find "$ROOT/app/src/main/java" -name '*.java' -print)

mapfile -t CLASS_FILES < <(find "$CLASSES" -name '*.class' -print)
"$BT/d8" --min-api 26 --lib "$PLATFORM" --classpath "$ROOT/xposed-api.jar" \
  --output "$OUT/dex" "${CLASS_FILES[@]}"

"$BT/aapt" package -f \
  -M "$ROOT/app/src/main/AndroidManifest.xml" \
  -S "$ROOT/app/src/main/res" \
  -I "$PLATFORM" \
  -A "$ROOT/app/src/main/assets" \
  -F "$OUT/base.apk"
(cd "$OUT/dex" && "$BT/aapt" add "$OUT/base.apk" classes.dex)
mv "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/zipalign" -f 4 "$OUT/aligned.apk" "$OUT/unsigned.apk"

KEY="$OUT/aurora-observer.jks"
if [[ ! -f "$KEY" ]]; then
  keytool -genkeypair -keystore "$KEY" -storepass android -keypass android \
    -alias aurora-observer -dname "CN=AURORA Observer" -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/$APK" "$OUT/unsigned.apk"
"$BT/apksigner" verify --verbose "$OUT/$APK" | head -6
"$BT/aapt" dump badging "$OUT/$APK" | head -6
sha256sum "$OUT/$APK"