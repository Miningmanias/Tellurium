#!/usr/bin/env bash
# Singleplayer check: opens a world in the Minecraft client (integrated server), pregenerates around spawn
# with the built-in pregenerator, prints the mod's log lines and closes the client.
#
# The world is created first by a short dedicated-server run and copied into the client's saves folder, so
# the client can go straight into it with --quickPlaySingleplayer.  A game window opens while this runs.
# Usage: scripts/run-client-pregen.sh [radius in chunks] [mods subdirectory] [extra properties, semicolon-separated]
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-60}"
MODS="${2:-vanilla}"
EXTRA="${3:-}"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot}"
root="$(pwd -W 2>/dev/null || pwd)"

run=$(bash scripts/run-pregen.sh 1 "$MODS" | grep -m1 '^RUNDIR' | sed 's/^RUNDIR //')
[ -d "$run/candidate-world" ] || { echo "CLIENT FAIL: the world was not created"; exit 1; }
mkdir -p "$run/saves"
cp -r "$run/candidate-world" "$run/saves/sp-world"
mv "$run/logs/latest.log" "$run/logs/server-create.log"
# A first launch otherwise stops at the accessibility onboarding screen instead of entering the world.
printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\ntutorialStep:none\npauseOnLostFocus:false\n' > "$run/options.txt"

args=(":neoforge-1211:runClient" "--no-daemon" "--console=plain" "-Dworldgennext.candidate.runDir=$run" "-Dworldgennext.prototype.resume=true"
      "-Dworldgennext.client.quickPlay=sp-world" "-Dworldgennext.run.maxHeap=${HEAP:-8G}" "-Dworldgennext.statusOnStop=true"
      "-Dworldgennext.fast.pipelineCacheDir=$root/build/fast-cache")
# DIGEST=<file.json>: instead of pregenerating, run the benchmark driver at SURFACE status over the matrix's
# square and write per-chunk digests next to that file, for comparison with a dedicated-server reference.
if [ -n "${DIGEST:-}" ]; then
  args+=("-Dworldgennext.bench.autorun=true" "-Dworldgennext.bench.status=SURFACE" "-Dworldgennext.bench.radiusChunks=$RADIUS"
         "-Dworldgennext.bench.release=end" "-Dworldgennext.bench.digest=true" "-Dworldgennext.bench.output=$root/$DIGEST"
         "-Dworldgennext.fast.gpu=force")
else
  args+=("-Dworldgennext.pregen.autostart=$RADIUS" "-Dworldgennext.pregen.stopServerWhenDone=true")
fi
IFS=';' read -ra extra <<< "$EXTRA"
for p in "${extra[@]}"; do [ -n "$p" ] && args+=("-D$p"); done
./gradlew.bat "${args[@]}" > "$run/gradle-client.out" 2>&1 &

# The client keeps running after its integrated server stops; close it once the mod reports the stop.
for _ in $(seq 1 180); do
  sleep 5
  grep -qE "Fast GPU NOISE stopped|Stopping singleplayer server|Stopping server|benchmark PASS|benchmark FAIL" "$run/logs/latest.log" 2>/dev/null && break
  jobs -r | grep -q . || break
done
sleep 5
powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$(basename "$run")*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
wait 2>/dev/null

echo "RUNDIR $run"
grep -E "\[worldgennext(-fast|-bench)?/\]" "$run/logs/latest.log" | sed -E 's/^\[[^]]*\] \[[^]]*\] \[[^]]*\]: //' | cut -c1-260
echo "errors: $(grep -cE "/ERROR\]|/FATAL\]" "$run/logs/latest.log")"
grep -E "/ERROR\]|/FATAL\]|Mixin apply failed|UnsatisfiedLinkError" "$run/logs/latest.log" | grep -v "MonsterRoomFeature" | head -8 | cut -c1-260
