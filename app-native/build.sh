#!/usr/bin/env bash
# Build and test the native module from a mirror outside OneDrive.
#
# Gradle cannot build this repo where it lives. OneDrive turns files into cloud reparse
# points and Gradle refuses to snapshot one - "Cannot snapshot <X>: not a regular file" -
# and it is not limited to build output: source files written seconds earlier are reparse
# points too, and pinning "always keep on this device" does not stop it. So the sources
# live in the repo and the build runs against a mirror in the temp directory.
#
# The mirror keeps its own build/ and .gradle/ between runs, so the second build is
# incremental. Only sources are copied over; nothing is copied back.
#
# JAVA_HOME is pinned here too. The Android Studio JBR on this machine is Java 25, whose
# version string Gradle 8.14.3 cannot parse - it fails with a bare "25.0.2" and no
# explanation, which is a memorable half hour if you have not seen it before.
#
#   ./build.sh              # runs :core:test
#   ./build.sh :core:build  # or any other Gradle task
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DST="/c/Users/shiel/AppData/Local/Temp/hb-native"
JDK="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"

[ -d "$JDK" ] || { echo "No JDK 21 at $JDK - check what is installed before editing this path." >&2; exit 1; }

mkdir -p "$DST"

# Drop the mirror's copies of the sources so a file deleted here disappears there too,
# then copy the tree across minus anything generated.
find "$DST" -maxdepth 3 -type d -name src -prune -exec rm -rf {} + 2>/dev/null || true
tar -cf - -C "$SRC" --exclude=.gradle --exclude=build . | tar -xf - -C "$DST"
chmod +x "$DST/gradlew"

export JAVA_HOME="$JDK"
cd "$DST"
exec ./gradlew --no-daemon "${@:-:core:test}"
