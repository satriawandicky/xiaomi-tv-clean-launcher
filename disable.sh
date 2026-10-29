#!/usr/bin/env bash
# Disable packages one pm call each, log them for restore.sh, then screenshot.
# Usage: ./disable.sh <batch-name> pkg1 pkg2 ...
set -u
cd "$(dirname "$0")"
export ANDROID_SERIAL="${TV_HOST:?set TV_HOST to the TV IP, e.g. TV_HOST=192.168.1.50}:5555"
batch=$1; shift
for p in "$@"; do
  out=$(adb shell pm disable-user --user 0 "$p" 2>&1)
  echo "$p: $out"
  [[ $out == *"new state: disabled"* ]] && { grep -qx "$p" disabled.log || echo "$p" >> disabled.log; }
done
sleep 3
adb shell input keyevent KEYCODE_HOME
sleep 4
mkdir -p shots && adb exec-out screencap -p > "shots/$batch.png"
echo "screenshot: shots/$batch.png ($(stat -c%s "shots/$batch.png") bytes)"
