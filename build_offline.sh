#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?Set JAVA_HOME to JDK 25}"
: "${PAPER_API_JAR:?Set PAPER_API_JAR to the resolved Paper 26.2 API JAR}"
mkdir -p build/offline-classes
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 25 -cp "${PAPER_API_JAR}${PAPER_DEPENDENCY_CLASSPATH:+:$PAPER_DEPENDENCY_CLASSPATH}" -d build/offline-classes src/main/java/dev/deadstreets/core/*.java src/main/java/dev/deadstreets/paper/*.java
cp src/main/resources/*.yml build/offline-classes/
"$JAVA_HOME/bin/jar" --create --file DeadStreets_R06.jar -C build/offline-classes .
echo 'Built DeadStreets_R06.jar'
