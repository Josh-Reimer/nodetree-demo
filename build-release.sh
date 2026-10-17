#!/bin/bash
# Build a release-signed APK. Reuses build.sh to compile/link/dex/align,
# then signs the aligned (unsigned) APK with the release keystore.
#
# The release keystore is kept OUTSIDE the repo. Override with env vars:
#   REL_KS=/path/to/key.jks REL_KS_PASS=... REL_KEY_ALIAS=... REL_KEY_PASS=... ./build-release.sh
set -euo pipefail

export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
BT="$HOME/Library/Android/sdk/build-tools/35.0.0"
PROJ="/Users/josh/coding/rea/nodetree-demo"
B="$PROJ/build"

REL_KS="${REL_KS:-$HOME/.android/nodetree-release.jks}"
REL_KS_PASS="${REL_KS_PASS:-nodetreedemo}"
REL_KEY_ALIAS="${REL_KEY_ALIAS:-nodetree}"
REL_KEY_PASS="${REL_KEY_PASS:-nodetreedemo}"

echo ">> building (via build.sh) to produce aligned.apk"
bash "$PROJ/build.sh" >/dev/null
echo ">> signing release APK with $REL_KS"
"$BT/apksigner" sign \
  --ks "$REL_KS" \
  --ks-pass "pass:$REL_KS_PASS" \
  --ks-key-alias "$REL_KEY_ALIAS" \
  --key-pass "pass:$REL_KEY_PASS" \
  --out "$B/NodeTreeDemo-release.apk" \
  "$B/aligned.apk"

"$BT/apksigner" verify --print-certs "$B/NodeTreeDemo-release.apk" | head -3
echo ">> RELEASE BUILT: $B/NodeTreeDemo-release.apk"
