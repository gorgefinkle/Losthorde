#!/usr/bin/env bash
set -euo pipefail

# Keep Dead Streets out of shared HUD channels so companion plugins can own
# titles, action bars, and scoreboards without last-writer-wins interference.
if grep -R -nE '\.sendTitle\(|\.sendActionBar\(|\.setScoreboard\(|getNewScoreboard\(' src/main/java/dev/deadstreets/paper; then
  echo "Shared HUD API use is prohibited; use chat, a dedicated BossBar, or tagged world-space displays."
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
