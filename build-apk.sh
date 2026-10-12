#!/usr/bin/env bash
# Minimal reproducible build without Gradle. Requires JDK 17 + SDK platform/build-tools 35.
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p build/classes build/dex build/generated
"$BUILD_TOOLS/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$BUILD_TOOLS/aapt2" link -o build/base.apk --manifest app/src/main/AndroidManifest.xml -I "$ANDROID_JAR" --java build/generated build/resources.zip
find app/src/main/java build/generated -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$BUILD_TOOLS/d8" --min-api 19 --lib "$ANDROID_JAR" --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# Local development signature. Keep your keystore private to retain upgrade compatibility.
if [ ! -f build/development.jks ]; then
    keytool -genkeypair -keystore build/development.jks -storepass android -keypass android -alias pocketchat -dname 'CN=Pocket Chat Development' -keyalg RSA -keysize 2048 -validity 10000
fi
"$BUILD_TOOLS/apksigner" sign --ks build/development.jks --ks-key-alias pocketchat --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --out build/PocketChat.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose --min-sdk-version 19 build/PocketChat.apk
