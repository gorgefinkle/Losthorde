#!/usr/bin/env bash
set -euo pipefail
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p build/core
"$JAVAC" --release 25 -d build/core src/main/java/dev/deadstreets/core/*.java src/test/java/dev/deadstreets/core/*.java
"$JAVA" -ea -cp build/core dev.deadstreets.core.CoreHarness

"$JAVA" -ea -cp build/core dev.deadstreets.core.HordeStateHarness

"$JAVA" -ea -cp build/core dev.deadstreets.core.ObservationGuardHarness
