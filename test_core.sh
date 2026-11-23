#!/usr/bin/env bash
set -euo pipefail

# Shared HUD ownership rules:
# - Full-screen titles are reserved for the established horde/Blood Moon notice controller.
# - Blood Moon may temporarily own a private scoreboard only in BloodMoonManager.
# - Action bars are not used by Dead Streets.
if grep -R -nE '\.sendTitle\(' src/main/java/dev/deadstreets/paper | grep -v '/DeadStreetsPlugin.java' | grep -v '/BloodMoonManager.java'; then
  echo "Title notifications must go through the Dead Streets title controller."
  exit 1
fi
if grep -R -nE '\.sendActionBar\(' src/main/java/dev/deadstreets/paper; then
  echo "Dead Streets must not write to the shared action bar."
  exit 1
fi
if grep -R -nE '\.setScoreboard\(' src/main/java/dev/deadstreets/paper | grep -v '/BloodMoonManager.java'; then
  echo "Only the Blood Moon display controller may temporarily assign its private scoreboard."
  exit 1
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p build/core
"$JAVAC" --release 25 -d build/core src/main/java/dev/deadstreets/core/*.java src/test/java/dev/deadstreets/core/*.java
"$JAVA" -ea -cp build/core dev.deadstreets.core.CoreHarness

"$JAVA" -ea -cp build/core dev.deadstreets.core.HordeStateHarness

"$JAVA" -ea -cp build/core dev.deadstreets.core.ObservationGuardHarness

"$JAVA" -ea -cp build/core dev.deadstreets.core.HordeArrivalHarness
