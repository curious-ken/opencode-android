#!/usr/bin/env bash
#
# Builds OpenCode Mobile without Gradle. This is the path used on the development
# machine (aarch64 Linux) where the Android SDK's x86_64 build tools cannot run.
# The resulting APK is debug-signed and ready to sideload.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

ANDROID_JAR="${ANDROID_JAR:-$HERE/toolchain/android-34.jar}"
if [ ! -f "$ANDROID_JAR" ]; then
  ANDROID_JAR="$HERE/../toolchain/android-34.jar"
fi
AAPT2="${AAPT2:-/usr/bin/aapt2}"
APKSIGNER="${APKSIGNER:-/usr/bin/apksigner}"
ZIPALIGN="${ZIPALIGN:-/usr/bin/zipalign}"
R8_JAR="${R8_JAR:-$HERE/toolchain/r8.jar}"
if [ ! -f "$R8_JAR" ]; then
  R8_JAR="$HERE/../toolchain/r8.jar"
fi

APP=app/src/main
BUILD="$HERE/build"
OUT="$BUILD/opencode-mobile.apk"
# Kept outside build/ so "Cleaning" does not rotate the signing key: a new key would force users
# to uninstall before every update.
KEYSTORE="${KEYSTORE:-$HERE/debug.keystore}"

for tool in "$AAPT2" "$APKSIGNER" "$ZIPALIGN"; do
  if [ ! -x "$tool" ]; then
    echo "Missing build tool: $tool" >&2
    exit 1
  fi
done
if [ ! -f "$ANDROID_JAR" ]; then
  echo "Missing android.jar: $ANDROID_JAR" >&2
  exit 1
fi
if [ ! -f "$R8_JAR" ]; then
  echo "Missing r8.jar: $R8_JAR" >&2
  exit 1
fi

echo "==> Cleaning"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/lib/arm64-v8a"

echo "==> Compiling resources"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "==> Linking resources"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  -A "$APP/assets" \
  --min-sdk-version 26 \
  --target-sdk-version 28 \
  --version-code 4 \
  --version-name 1.0.3 \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "==> Compiling Java"
find "$APP/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -source 17 -target 17 -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "==> Dexing"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  "$BUILD/classes.jar"

echo "==> Packaging"
cp "$BUILD/base.apk" "$BUILD/unaligned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unaligned.apk" classes.dex)
cp "$APP/jniLibs/arm64-v8a/"*.so "$BUILD/lib/arm64-v8a/"
(cd "$BUILD" && zip -q -r unaligned.apk lib)

"$ZIPALIGN" -f 4 "$BUILD/unaligned.apk" "$BUILD/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "==> Generating debug keystore"
  keytool -genkeypair -keystore "$KEYSTORE" \
    -storepass android -keypass android \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "==> Signing"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT" \
  "$BUILD/aligned.apk"

"$APKSIGNER" verify --verbose "$OUT" | head -20

echo
echo "APK: $OUT"
ls -lh "$OUT"
