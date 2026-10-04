#!/usr/bin/env bash
# Kills a server in the middle of a pregeneration and checks what is left on disk.
#
# 1. Start a pregeneration (default settings: sync-chunk-writes=true, so group commit is active).
# 2. Once it reports progress, kill the Java process without warning.
# 3. Validate every chunk the region headers reference (scripts/check-region-files.py).
# 4. Start again with autoresume, let the job finish, validate again, and look for read errors in the log.
#
# A process kill leaves the operating system's file cache intact, so this checks that the files are
# consistent at an arbitrary instant; it does not simulate power loss.
# Usage: scripts/verify-kill-recovery.sh [radius in chunks] [seconds of progress before the kill] [extra properties]
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-120}"
AFTER="${2:-6}"
EXTRA="${3:-}"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot}"
root="$(pwd -W 2>/dev/null || pwd)"
run="$root/build/run/kill-$(date -u +%Y%m%d-%H%M%S)"
mkdir -p "$run/mods"
cp build/test-mods/vanilla/*.jar "$run/mods/"
common=("--no-daemon" "--console=plain" "-Dworldgennext.candidate.runDir=$run" "-Dworldgennext.candidate.seed=0" "-Dworldgennext.run.maxHeap=16G"
        "-Dworldgennext.fast.pipelineCacheDir=$root/build/fast-cache")
IFS=';' read -ra extra <<< "$EXTRA"
for p in "${extra[@]}"; do [ -n "$p" ] && common+=("-D$p"); done

printf '[pregen]\nprogress_seconds = 2\n' > "$run/worldgennext.toml.seed"
mkdir -p "$run/config" && cp "$run/worldgennext.toml.seed" "$run/config/worldgennext.toml"
./gradlew.bat :neoforge-1211:runServer "${common[@]}" "-Dworldgennext.pregen.autostart=$RADIUS" > "$run/gradle-1.out" 2>&1 &
for _ in $(seq 1 120); do
  sleep 1
  grep -q "Pregeneration: " "$run/logs/latest.log" 2>/dev/null && break
done
sleep "$AFTER"
powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$(basename "$run")*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
wait 2>/dev/null
cp "$run/logs/latest.log" "$run/logs/killed.log"
echo "killed at: $(grep "Pregeneration: " "$run/logs/killed.log" | tail -1 | sed 's/^.*Pregeneration: //' | cut -c1-80)"
echo "after kill:  $(python scripts/check-region-files.py "$run/candidate-world/region" "$run/candidate-world/poi" "$run/candidate-world/entities" | tail -3 | tr '\n' ' ')"

ls "$run/candidate-world/worldgennext-pregen.properties" >/dev/null 2>&1 || { echo "KILL-RECOVERY FAIL: no saved pregeneration progress"; exit 1; }

./gradlew.bat :neoforge-1211:runServer "${common[@]}" "-Dworldgennext.prototype.resume=true" "-Dworldgennext.pregen.autoresume=true" \
    "-Dworldgennext.pregen.stopServerWhenDone=true" > "$run/gradle-2.out" 2>&1
grep -E "continuing at|Pregeneration finished" "$run/logs/latest.log" | sed -E 's/^\[[^]]*\] \[[^]]*\] \[[^]]*\]: /resume: /' | cut -c1-170
readErrors=$(grep -ciE "wrong location|Failed to read|corrupt|Couldn't load chunk|Chunk file at|Invalid chunk|Region file .* (is truncated|has)" "$run/logs/latest.log")
echo "resume: read errors in the log: $readErrors"
final=$(python scripts/check-region-files.py "$run/candidate-world/region" "$run/candidate-world/poi" "$run/candidate-world/entities" | tail -3 | tr '\n' ' ')
echo "after resume: $final"
side=$((2 * RADIUS + 1))
case "$final" in *" 0 problems"*) ;; *) echo "KILL-RECOVERY FAIL: invalid chunks after the resume"; exit 1 ;; esac
grep -q "Pregeneration finished" "$run/logs/latest.log" || { echo "KILL-RECOVERY FAIL: the resumed job did not finish"; exit 1; }
[ "$readErrors" = "0" ] || { echo "KILL-RECOVERY FAIL: chunk read errors after the kill"; exit 1; }
python scripts/check-region-files.py "$run/candidate-world/region" >/dev/null || exit 1
echo "KILL-RECOVERY PASS (radius $RADIUS, $((side * side)) chunks requested, $run)"
