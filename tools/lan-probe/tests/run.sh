#!/usr/bin/env bash
set -euo pipefail
probe_root="$(cd "$(dirname "$0")/.." && pwd)"
class_dir="$(mktemp -d)"
trap 'rm -rf "$class_dir"' EXIT
if command -v javac >/dev/null; then
  compiler=(javac)
else
  # Some runtimes include jdk.compiler but omit the javac launcher.
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" --release 8 -encoding UTF-8 -d "$class_dir" \
  "$probe_root"/app/src/main/java/org/doublecore/stt/lanprobe/Probe*.java \
  "$probe_root"/tests/ProbeIntegrationTest.java
java -cp "$class_dir" org.doublecore.stt.lanprobe.ProbeIntegrationTest
