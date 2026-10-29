#!/usr/bin/env bash
# Re-enable every package disabled during the debloat (listed in disabled.log),
# put the stock Google TV home back and reset Private DNS.
# Usage: TV_HOST=192.168.1.50 ./restore.sh
set -u
cd "$(dirname "$0")"
export ANDROID_SERIAL="${TV_HOST:?set TV_HOST to the TV IP, e.g. TV_HOST=192.168.1.50}:5555"
adb connect "$ANDROID_SERIAL" >/dev/null
# setup wizard + stock launcher first, so Home never lands on a missing activity
for p in com.google.android.tungsten.setupwraith com.google.android.apps.tv.launcherx $(grep -v '^#' disabled.log 2>/dev/null); do
  echo "enable $p: $(adb shell pm enable --user 0 "$p" 2>&1)"
done
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb shell settings put global private_dns_mode opportunistic
echo "Done. Optional: adb uninstall the custom launcher package, then reboot."
