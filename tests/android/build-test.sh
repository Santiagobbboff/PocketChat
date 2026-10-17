#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
bt="$ANDROID_HOME/build-tools/35.0.0"
jar="$ANDROID_HOME/platforms/android-19/android.jar"
mkdir -p build/android-tests/classes build/android-tests/dex
"$bt/aapt2" link -o build/android-tests/base.apk --manifest tests/android/AndroidManifest.xml -I "$jar"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$jar" -cp build/classes -d build/android-tests/classes tests/android/*.java
jar cf build/android-tests/classes.jar -C build/android-tests/classes .
"$bt/d8" --min-api 19 --lib "$jar" --classpath build/classes.jar --output build/android-tests/dex build/android-tests/classes.jar
cp build/android-tests/base.apk build/android-tests/unsigned.apk
(cd build/android-tests/dex && zip -q -u ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 build/android-tests/unsigned.apk build/android-tests/aligned.apk
"$bt/apksigner" sign --ks build/development.jks --ks-key-alias pocketchat --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --out build/android-tests/Tests.apk build/android-tests/aligned.apk
