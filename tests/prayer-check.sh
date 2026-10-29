#!/usr/bin/env bash
# Compile PrayerTimes.java with the check above (plain JDK, no Android) and run it.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-$(ls -d ~/.local/opt/jdk-17* | head -1)}"
out=$(mktemp -d)
"$JAVA_HOME/bin/javac" -d "$out" launcher/app/src/main/java/com/gathin/tvhome/PrayerTimes.java tests/PrayerCheck.java
"$JAVA_HOME/bin/java" -cp "$out" com.gathin.tvhome.PrayerCheck
