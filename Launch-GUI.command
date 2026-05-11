#!/bin/bash
# Double-click this file in Finder to open the Semi-0 dev menu (Swing GUI).
# Requires a built jar: run `clojure -T:build uber` once from Terminal if needed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/target/semi-dev-menu.jar"
if [[ ! -f "$JAR" ]]; then
  osascript -e 'display dialog "Build the jar first. In Terminal run:\n\ncd '"$ROOT"'\nclojure -T:build uber" buttons {"OK"} default button "OK" with title "Semi-0 dev menu"'
  exit 1
fi
exec java -jar "$JAR" --gui
