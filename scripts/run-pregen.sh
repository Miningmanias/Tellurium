#!/usr/bin/env bash
# Starts a fresh dev server, pregenerates a square around spawn with the built-in pregenerator, prints the
# status report and stops.  Exercises the user-facing path: config file, default (auto) GPU mode, pregen.
# Usage: scripts/run-pregen.sh [radius in chunks] [mods subdirectory] [extra properties, semicolon-separated]
#   CONFIG=<file>  copied to config/worldgennext.toml before the start
#   RUN=<dir>      reuse an existing run directory (and its world) instead of a fresh one
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-60}"
MODS="build/test-mods/${2:-vanilla}"
EXTRA="${3:-}"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot}"
root="$(pwd -W 2>/dev/null || pwd)"
run="${RUN:-$root/build/run/pregen-$(date -u +%Y%m%d-%H%M%S)}"
mkdir -p "$run/mods" "$run/config"
cp "$MODS"/*.jar "$run/mods/" 2>/dev/null
[ -n "${CONFIG:-}" ] && cp "$CONFIG" "$run/config/worldgennext.toml"
resume=()
[ -n "${RUN:-}" ] && resume=("-Dworldgennext.prototype.resume=true")
args=("${resume[@]}" ":${MODULE:-neoforge-1211}:runServer" "--no-daemon" "--console=plain" "-Dworldgennext.candidate.runDir=$run" "-Dworldgennext.candidate.seed=0"
      "-Dworldgennext.run.maxHeap=16G" "-Dworldgennext.pregen.autostart=$RADIUS" "-Dworldgennext.pregen.stopServerWhenDone=true"
      "-Dworldgennext.statusOnStop=true" "-Dworldgennext.fast.pipelineCacheDir=$root/build/fast-cache")
[ -n "${NEOFORGE:-}" ] && args+=("-Pworldgennext.neoforge=$NEOFORGE")
IFS=';' read -ra extra <<< "$EXTRA"
for p in "${extra[@]}"; do [ -n "$p" ] && args+=("-D$p"); done
./gradlew.bat "${args[@]}" > "$run/gradle.out" 2>&1
echo "RUNDIR $run"
grep -E "\[worldgennext(-fast)?/\]" "$run/logs/latest.log" | sed -E 's/^\[[^]]*\] \[[^]]*\] \[[^]]*\]: //' | cut -c1-260
ls "$run/config/worldgennext.toml" "$run"/candidate-world/worldgennext-pregen.properties 2>&1 | sed 's|.*/build/run/||'
