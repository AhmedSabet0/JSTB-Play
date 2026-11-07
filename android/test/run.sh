#!/bin/bash
# Compiles the engine for the JVM and runs EngineTest against the LinkedIn mocks. Usage: test/run.sh [classic|sdui|real|ask|all]
set -e
cd "$(dirname "$0")/.."
unset JAVA_TOOL_OPTIONS
SCR=/tmp/claude-0/-home-claude/99a23c9d-4f01-5bbc-bad9-8c8923bdd13c/scratchpad
rm -rf out/jvm && mkdir -p out/jvm
javac --release 8 -Xlint:-options -nowarn -d out/jvm src/com/autoapply/bot/core/*.java flavors/github/com/autoapply/bot/core/*.java
javac --release 21 -nowarn -cp out/jvm -d out/jvm test/*.java
java -Dscratch=$SCR -Dserver=/home/claude/jstb/server -Dassets=$PWD/assets ${ECHO:+-Decho=true} -cp out/jvm EngineTest "${1:-all}"
