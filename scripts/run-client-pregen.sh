#!/usr/bin/env bash
# Singleplayer check: opens a world in the Minecraft client (integrated server), pregenerates around spawn
# with the built-in pregenerator, prints the mod's log lines and closes the client.
#
# The world is created first by a short dedicated-server run and copied into the client's saves folder, so
# the client can go straight into it with --quickPlaySingleplayer.  A game window opens while this runs.
# IDLE=<seconds> stands in the world instead; DIGEST= and TOUR= are described below.
# Usage: scripts/run-client-pregen.sh [radius in chunks] [mods subdirectory] [extra properties, semicolon-separated]
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-60}"
MODS="${2:-vanilla}"
EXTRA="${3:-}"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot}"
root="$(pwd -W 2>/dev/null || pwd)"

# CREATE_MODS=<mods subdirectory>: create the world on the dedicated server with those mods instead (client-only
# mods such as Sodium or Voxy cannot be on it), then put the client's mods in place.
# REUSE=<run directory of an earlier run>: open that run's world again instead of creating one.
if [ -n "${REUSE:-}" ]; then
  run="$REUSE"
  mv "$run/logs/latest.log" "$run/logs/session-$(date +%H%M%S).log" 2>/dev/null
else
run=$(bash scripts/run-pregen.sh 1 "${CREATE_MODS:-$MODS}" | grep -m1 '^RUNDIR' | sed 's/^RUNDIR //')
if [ -n "${CREATE_MODS:-}" ]; then
  mkdir -p "$run/mods-create" && mv "$run"/mods/*.jar "$run/mods-create/" && cp "build/test-mods/$MODS"/*.jar "$run/mods/"
fi
fi
if [ -z "${REUSE:-}" ]; then
[ -d "$run/candidate-world" ] || { echo "CLIENT FAIL: the world was not created"; exit 1; }
mkdir -p "$run/saves"
cp -r "$run/candidate-world" "$run/saves/sp-world"
mv "$run/logs/latest.log" "$run/logs/server-create.log"
fi
# A first launch otherwise stops at the accessibility onboarding screen instead of entering the world.
printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\ntutorialStep:none\npauseOnLostFocus:false\n' > "$run/options.txt"
# OPTIONS=<file>: lines appended to the client's options.txt (a window size, a render distance).
[ -n "${OPTIONS:-}" ] && cat "$OPTIONS" >> "$run/options.txt"

# CONFIG_TOML=<file>: use that as config/tellurium.toml in the client (for example one with enabled = false).
[ -n "${CONFIG_TOML:-}" ] && cp "$CONFIG_TOML" "$run/config/tellurium.toml"
# NEOFORGE=<version>: run the client on that NeoForge 21.1.x instead of the one the mod is built against.
args=(${WINDOW:+"-Dtellurium.client.window=$WINDOW"} ":${MODULE:-neoforge-1211}:runClient" "--no-daemon" "--console=plain" "-Dtellurium.candidate.runDir=$run" "-Dtellurium.prototype.resume=true"
      "-Dtellurium.client.quickPlay=sp-world" "-Dtellurium.run.maxHeap=${HEAP:-8G}" "-Dtellurium.statusOnStop=true"
      "-Dtellurium.fast.pipelineCacheDir=$root/build/fast-cache")
# DIGEST=<file.json>: instead of pregenerating, run the benchmark driver at SURFACE status over the matrix's
# square and write per-chunk digests next to that file, for comparison with a dedicated-server reference.
if [ -n "${DIGEST:-}" ]; then
  args+=("-Dtellurium.bench.autorun=true" "-Dtellurium.bench.status=SURFACE" "-Dtellurium.bench.radiusChunks=$RADIUS"
         "-Dtellurium.bench.release=end" "-Dtellurium.bench.digest=true" "-Dtellurium.bench.output=$root/$DIGEST"
         "-Dtellurium.fast.gpu=force")
elif [ -n "${IDLE:-}" ]; then
  # IDLE=<seconds>: just stand in the world for that long (for mods that work around the player, such as
  # Distant Horizons), then report.
  [ -f "$run/config/DistantHorizons.toml" ] && sed -i 's/enableAutoUpdater = true/enableAutoUpdater = false/' "$run/config/DistantHorizons.toml"
elif [ -n "${TOUR:-}" ]; then
  # TOUR=<steps>: instead of pregenerating, fly the player across fresh terrain (ordinary play path).
  args+=("-Dtellurium.bench.tour=$TOUR")
else
  args+=("-Dtellurium.pregen.autostart=$RADIUS" "-Dtellurium.pregen.stopServerWhenDone=true")
fi
[ -n "${NEOFORGE:-}" ] && args+=("-Ptellurium.neoforge=$NEOFORGE")
# RUN_JAVA=<version>: run the client on that Java (for a comparison with a mod that needs a newer one).
[ -n "${RUN_JAVA:-}" ] && args+=("-Dtellurium.run.java=$RUN_JAVA")
IFS=';' read -ra extra <<< "$EXTRA"
for p in "${extra[@]}"; do [ -n "$p" ] && args+=("-D$p"); done
./gradlew.bat "${args[@]}" > "$run/gradle-client.out" 2>&1 &

# The client keeps running after its integrated server stops; close it once the mod reports the stop.
for tick in $(seq 1 180); do
  sleep 5
  if [ -n "${IDLE:-}" ]; then
    grep -q "joined the game" "$run/logs/latest.log" 2>/dev/null || continue
    joined=${joined:-$tick}
    [ $(( (tick - joined) * 5 )) -ge "$IDLE" ] && break
    continue
  fi
  grep -qE "Fast GPU NOISE stopped|Stopping singleplayer server|Stopping server|benchmark PASS|benchmark FAIL|player tour (PASS|FAIL)" "$run/logs/latest.log" 2>/dev/null && break
  jobs -r | grep -q . || break
done
sleep 5
powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$(basename "$run")*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
wait 2>/dev/null

echo "RUNDIR $run"
grep -E "\[tellurium(-fast|-bench)?/\]" "$run/logs/latest.log" | sed -E 's/^\[[^]]*\] \[[^]]*\] \[[^]]*\]: //' | cut -c1-260
[ -n "${IDLE:-}" ] && grep -E "DH is |Distant Horizons will|joined the game" "$run/logs/latest.log" | sed -E 's/^\[[^ ]* ([^]]*)\] \[[^]]*\] \[[^]]*\]: /\1 /' | cut -c1-200 | tail -40
echo "errors: $(grep -cE "/ERROR\]|/FATAL\]" "$run/logs/latest.log")"
grep -E "/ERROR\]|/FATAL\]|Mixin apply failed|UnsatisfiedLinkError" "$run/logs/latest.log" | grep -v "MonsterRoomFeature" | head -8 | cut -c1-260
