#!/usr/bin/env bash
set -euo pipefail

./gradlew classes

classpath="build/classes/java/main:build/resources/main"
java -Djava.awt.headless=true -cp "$classpath" \
  com.tndmadman.rts.RenderPerformanceValidator \
  --output=build/reports/render-performance.csv "$@"
