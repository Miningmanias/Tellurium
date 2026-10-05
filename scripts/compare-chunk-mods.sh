#!/usr/bin/env bash
# Times one Chunky pregeneration on the installed NeoForge server with a given set of chunk-generation mods.
#
# Every configuration runs on the same server installation, seed (0), settings and JVM heap, on a fresh
# world, with ScalableLux and Chunky installed and Chunky as the driver:
#   vanilla        nothing else
#   worldgennext   this mod's release jar
#   c2me           Concurrent Chunk Management Engine (NeoForge)
#   c2me-ocl       C2ME plus its OpenCL acceleration module
# The time is taken from the server log: Chunky's "Task started" line to its "Task finished" line.
#
# Usage: scripts/compare-chunk-mods.sh <configuration> [radius in blocks] [extra JVM arguments]
#   e.g. scripts/compare-chunk-mods.sh c2me 1500 "-Dchunky.maxWorkingCount=1024"
set -u
cd "$(dirname "$0")/.."
config="${1:?configuration}"
radius="${2:-1500}"
jvm="${3:-}"
# SERVER=<dir> selects the server installation; its NeoForge version is read from its libraries folder.
server="${SERVER:-build/installed-server}"
neoforge=$(ls "$server/libraries/net/neoforged/neoforge" | head -1)
stash=build/_to_delete/compare-worlds
mkdir -p "$stash" "$server/mods-stash"
# JDK=<home> selects the Java runtime (C2ME 0.4.0 needs Java 25; default is the project's Java 21).
export PATH="${JDK:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}/bin:$PATH"

mv "$server"/mods/*.jar "$server/mods-stash/" 2>/dev/null
cp build/test-mods/vanilla/ScalableLux-*.jar build/test-mods/chunky/Chunky-*.jar "$server/mods/"
case "$config" in
  vanilla) ;;
  worldgennext) cp "$(ls -t neoforge-1211/build/libs/worldgennext-neoforge-1.21.1-*.jar | grep -v sources | head -1)" "$server/mods/" ;;
  c2me) cp build/test-mods/c2me/c2me-neoforge-mc*.jar "$server/mods/" ;;
  c2me-ocl) cp build/test-mods/c2me-ocl/c2me-neoforge-*.jar "$server/mods/" ;;
  *) echo "unknown configuration $config"; exit 2 ;;
esac

[ -d "$server/world" ] && mv "$server/world" "$stash/world-$(date +%Y%m%d-%H%M%S)"
rm -f "$server/logs/latest.log"
# shellcheck disable=SC2086
printf -- '-Xmx16G\n%s\n' "$(echo $jvm | tr ' ' '\n')" > "$server/user_jvm_args.txt"

cd "$server"
{
  for _ in $(seq 1 600); do sleep 1; grep -q "Done (" logs/latest.log 2>/dev/null && break; grep -qE "Failed to start the minecraft server|FATAL\]" logs/latest.log 2>/dev/null && break; done
  if [ "$config" = worldgennext ]; then
    for _ in $(seq 1 600); do sleep 1; grep -qE "the_end: .*(on the GPU|vanilla code)|GPU terrain generation is unavailable" logs/latest.log && break; done
  fi
  sleep 5
  echo "chunky radius $radius"
  echo "chunky start"
  for _ in $(seq 1 7200); do sleep 1; grep -q "Task finished" logs/latest.log && break; done
  sleep 2
  echo "stop"
} | java @user_jvm_args.txt "@libraries/net/neoforged/neoforge/$neoforge/win_args.txt" nogui > console.out 2>&1
cd - >/dev/null
printf -- '-Xmx16G\n' > "$server/user_jvm_args.txt"

log="$server/logs/latest.log"
python - "$log" "$config" "$radius" "$jvm" <<'PY'
import re, sys
from datetime import datetime
log, config, radius, jvm = sys.argv[1:5]
text = open(log, errors="replace").read()
def stamp(pattern):
    m = re.search(r"^\[(?:\d+\w+\d+ )?(\d\d:\d\d:\d\d\.\d+)\][^\n]*" + pattern, text, re.M)
    return datetime.strptime(m.group(1), "%H:%M:%S.%f") if m else None
start, end = stamp(r"\[Chunky\] Task started"), stamp(r"\[Chunky\] Task finished")
finished = re.search(r"Task finished[^\n]*Processed: (\d+) chunks[^\n]*Total time: ([\d:]+)", text)
errors = sum(1 for line in text.splitlines() if "/ERROR]" in line or "/FATAL]" in line)
if not (start and end and finished):
    print(f"{config}: did not finish (errors in log: {errors})")
    sys.exit(1)
seconds = (end - start).total_seconds()
if seconds < 0:
    seconds += 86400
chunks = int(finished.group(1))
print(f"{config} radius={radius} {jvm}: {chunks} chunks in {seconds:.1f} s = {chunks / seconds:.0f} chunks/s (Chunky's total {finished.group(2)}; error lines {errors})")
PY
mv "$server"/mods/*.jar "$stash/" 2>/dev/null
mv "$server"/mods-stash/*.jar "$server/mods/" 2>/dev/null
