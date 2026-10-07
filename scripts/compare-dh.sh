#!/usr/bin/env bash
# Times a Distant Horizons LOD pregeneration on the installed NeoForge server, with or without this mod.
#
#   dh             ScalableLux + Distant Horizons
#   tellurium   the same plus this mod's release jar
#
# Driver: Distant Horizons' own command, `dh pregen start <dimension> 0 0 <chunk radius>`, on a fresh world
# from a cold start.  Time is from the command to "Pregen is complete" in the server log.
# DH_CONFIG=<file> is copied to config/DistantHorizons.toml first (to pick a generator plan); without it the
# server's existing Distant Horizons settings are used.
# REPEAT=1 gives the same command a second time afterwards (Distant Horizons skips what it holds complete data
# for, so a pass that asks for nothing shows the first left nothing out), flushes saves and validates the
# region files.  PRE=1 runs this mod's own pregenerator over the area first.
# POST=1 runs it afterwards instead, to finish what the pass left unfinished.  DIM=<dimension id> picks the dimension.
# This mod's mode is chosen with an extra JVM argument: -Dtellurium.dh.mode=hybrid|direct|off.
#
# Usage: scripts/compare-dh.sh <dh|tellurium> [chunk radius] [extra JVM arguments]
set -u
cd "$(dirname "$0")/.."
config="${1:?configuration}"
radius="${2:-64}"
jvm="${3:-}"
# LOADER=fabric runs on the Fabric server (build/installed-fabric, mods from build/test-mods/dh-fabric) with the
# Fabric jar instead; with MC=<Minecraft version> on that version's Fabric server (build/installed-fabric-<version>,
# mods from build/test-mods/dh-fabric-<version>) with that version's jar.
loader="${LOADER:-neoforge}"
mc="${MC:-1.21.1}"
suffix=""; [ "$mc" = 1.21.1 ] || suffix="-$mc"
if [ "$loader" = fabric ]; then server="${SERVER:-build/installed-fabric$suffix}"; mods="build/test-mods/dh-fabric$suffix"
else server="${SERVER:-build/installed-server}"; mods=build/test-mods/dh; fi
dimension="${DIM:-minecraft:overworld}"
[ "$loader" = fabric ] || neoforge=$(ls "$server/libraries/net/neoforged/neoforge" | head -1)
stash=build/_to_delete/compare-worlds
# Every run keeps its world (about 1 GB) under build/_to_delete; a full disk truncates the jars copied below.
free=$(df -Pk . | awk 'NR==2 {print int($4 / 1048576)}')
[ "$free" -lt 10 ] && { echo "only $free GB free here; delete build/_to_delete first"; exit 3; }
mkdir -p "$stash" "$server/mods-stash"
export PATH="${JDK:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}/bin:$PATH"

mv "$server"/mods/*.jar "$server/mods-stash/" 2>/dev/null
cp "$mods"/*.jar "$server/mods/"
case "$config" in
  dh) ;;
  tellurium) cp "$(ls -t mod/targets/"$loader-$(echo "$mc" | tr -d .)"/build/libs/tellurium-"$loader"-"$mc"-*.jar | grep -v sources | head -1)" "$server/mods/" ;;
  *) echo "unknown configuration $config"; exit 2 ;;
esac
[ -d "$server/world" ] && mv "$server/world" "$stash/world-$(date +%Y%m%d-%H%M%S)"
rm -f "$server/logs/latest.log"
[ -n "${DH_CONFIG:-}" ] && cp "$DH_CONFIG" "$server/config/DistantHorizons.toml"
# shellcheck disable=SC2086
printf -- '-Xmx16G\n%s\n' "$(echo $jvm | tr ' ' '\n')" > "$server/user_jvm_args.txt"

cd "$server"
{
  for _ in $(seq 1 600); do sleep 1; grep -q "Done (" logs/latest.log 2>/dev/null && break; grep -qE "Failed to start the minecraft server" logs/latest.log 2>/dev/null && break; grep -q 'Exception in thread "main"' console.out 2>/dev/null && { echo stop; exit; }; done
  if [ "$config" = tellurium ]; then
    for _ in $(seq 1 600); do sleep 1; grep -qE "the_end: .*(on the GPU|vanilla code)|GPU terrain generation is unavailable" logs/latest.log && break; done
  fi
  sleep 5
  if [ -n "${PRE:-}" ]; then
    # This mod's pregenerator first; Distant Horizons builds LODs from the chunks as the server handles them.
    echo "execute in $dimension run tellurium pregen start $((radius + 4)) 0 0"
    for _ in $(seq 1 7200); do sleep 1; grep -q "Pregeneration finished" logs/latest.log && break; done
    sleep "${PRE_SETTLE:-0}"
  fi
  echo "dh pregen start $dimension 0 0 $radius"
  for _ in $(seq 1 7200); do sleep 1; grep -qE "Pregen is complete|Pregen failed|Pregen is cancelled" logs/latest.log && break; done
  sleep 2
  [ "$config" = tellurium ] && echo "tellurium status"
  sleep 1
  if [ -n "${POST:-}" ]; then
    # Finish the area with this mod's pregenerator afterwards: chunks Distant Horizons' pass left unfinished
    # are completed the way they would be when a player gets there.
    echo "execute in $dimension run tellurium pregen start $((radius - 2)) 0 0"
    for _ in $(seq 1 7200); do sleep 1; grep -q "Pregeneration finished" logs/latest.log && break; done
    echo "save-all flush"
    sleep 15
    [ "$config" = tellurium ] && echo "tellurium status"
    sleep 1
  fi
  if [ -n "${REPEAT:-}" ]; then
    # Ask again for the same area: Distant Horizons skips every section it holds complete data for, so a
    # second pass that finishes at once without asking for chunks shows the first one left nothing out.
    echo "dh pregen start $dimension 0 0 $radius"
    for _ in $(seq 1 7200); do sleep 1; [ "$(grep -c "Pregen is complete" logs/latest.log)" -ge 2 ] && break; done
    echo "save-all flush"
    sleep 25
    [ "$config" = tellurium ] && echo "tellurium status"
    sleep 1
  fi
  echo "stop"
} | if [ "$loader" = fabric ]; then java @user_jvm_args.txt -jar fabric-server-launch.jar nogui > console.out 2>&1
  else java @user_jvm_args.txt "@libraries/net/neoforged/neoforge/$neoforge/win_args.txt" nogui > console.out 2>&1; fi
cd - >/dev/null
printf -- '-Xmx16G\n' > "$server/user_jvm_args.txt"

python - "$server/logs/latest.log" "$config" "$radius" <<'PY'
import re, sys
from datetime import datetime
log, config, radius = sys.argv[1:4]
text = open(log, errors="replace").read()
def stamp(pattern):
    m = re.search(r"^\[(?:\d+\w+\d+ )?(\d\d:\d\d:\d\d(?:\.\d+)?)\].*" + pattern, text, re.M)
    # A Fabric server's log has whole seconds only.
    return datetime.strptime(m.group(1), "%H:%M:%S.%f" if "." in m.group(1) else "%H:%M:%S") if m else None
start, end = stamp(r"Starting pregen"), stamp(r"Pregen is complete")
first = stamp(r"Pregenerating ")
errors = sum(1 for line in text.splitlines() if "/ERROR]" in line or "/FATAL]" in line)
side = 2 * int(radius) + 1
if not (start and end):
    print(f"{config}: pregen did not complete (errors in log: {errors})")
    sys.exit(1)
seconds = (end - start).total_seconds()
if first and first < start:
    whole = (end - first).total_seconds()
    print(f"{config} radius={radius}: this mod's pregenerator, then Distant Horizons' pass ({seconds:.1f} s): "
          f"{side * side} chunks of LOD in {whole:.1f} s = {side * side / whole:.0f} chunks/s")
print(f"{config} radius={radius}: about {side * side} chunks of LOD in {seconds:.1f} s = {side * side / seconds:.0f} chunks/s (error lines {errors})")
PY
grep -E "Chunks since start|overworld:|Distant Horizons [(]|Pregeneration finished|column writer check" "$server/logs/latest.log" | sed -E 's/^\[[^]]*\] \[[^]]*\] \[[^]]*\]: /  /' | tail -6 | cut -c1-260
if [ -n "${REPEAT:-}" ]; then
  grep -E "Starting pregen|Pregen is complete" "$server/logs/latest.log" | cut -c12-23 | tr '
' ' '; echo
  echo "  saved chunks: $(python scripts/check-region-files.py "$server/world/region" | tail -1)"
fi
mv "$server"/mods/*.jar "$stash/" 2>/dev/null
mv "$server"/mods-stash/*.jar "$server/mods/" 2>/dev/null
