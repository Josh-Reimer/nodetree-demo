#!/bin/bash
set -euo pipefail

export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
PROJ="/Users/josh/coding/rea/nodetree-demo"
B="$PROJ/build"

rm -rf "$B"
mkdir -p "$B/compiled" "$B/gen" "$B/obj" "$B/dex"

echo ">> aapt2 compile resources"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$B/compiled/res.zip"

echo ">> aapt2 link (generates R.java + base apk with resources/manifest)"
"$BT/aapt2" link \
  -o "$B/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$B/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 35 \
  "$B/compiled/res.zip"

echo ">> javac"
find "$PROJ/src" "$B/gen" -name '*.java' > "$B/sources.txt"
"$JAVA_HOME/bin/javac" \
  -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -d "$B/obj" \
  @"$B/sources.txt"

echo ">> d8 -> classes.dex"
CLASSES=$(find "$B/obj" -name '*.class')
"$BT/d8" $CLASSES --min-api 24 --lib "$PLATFORM" --output "$B/dex"

echo ">> add classes.dex into apk"
cp "$B/base.apk" "$B/unsigned.apk"
( cd "$B/dex" && "$BT/../../build-tools/35.0.0/../../platform-tools/.." >/dev/null 2>&1 || true )
cd "$B/dex"
"$JAVA_HOME/bin/jar" uf "$B/unsigned.apk" classes.dex 2>/dev/null || \
  zip -j "$B/unsigned.apk" "$B/dex/classes.dex"
cd "$PROJ"

echo ">> zipalign"
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"

echo ">> apksigner (debug keystore)"
"$BT/apksigner" sign \
  --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out "$B/NodeTreeDemo.apk" \
  "$B/aligned.apk"

"$BT/apksigner" verify --verbose "$B/NodeTreeDemo.apk" | head -5
echo ">> BUILT: $B/NodeTreeDemo.apk"
