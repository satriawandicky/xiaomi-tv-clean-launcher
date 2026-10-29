#!/usr/bin/env bash
# Build the launcher (debug APK) and install it on the TV over ADB, then make it Home.
# Usage: TV_HOST=192.168.1.50 ./build-and-install.sh
set -euo pipefail
cd "$(dirname "$0")/launcher"
export JAVA_HOME="${JAVA_HOME:-$(ls -d ~/.local/opt/jdk-17* | head -1)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/opt/android-sdk}"
export ANDROID_SERIAL="${TV_HOST:?set TV_HOST to the TV IP, e.g. TV_HOST=192.168.1.50}:5555"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
APK=app/build/outputs/apk/debug/app-debug.apk
adb connect "$ANDROID_SERIAL" >/dev/null
# push + pm install: plain `adb install` can hang on Android 14 waiting for an on-screen confirmation
adb push "$APK" /data/local/tmp/tvhome.apk >/dev/null
adb shell pm install -r /data/local/tmp/tvhome.apk
adb shell rm /data/local/tmp/tvhome.apk
adb shell cmd package set-home-activity com.gathin.tvhome/.MainActivity
