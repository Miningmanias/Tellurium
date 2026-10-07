#!/usr/bin/env bash
# Runs the built Fabric jar on a real Fabric server (not the development environment): the jar there is
# remapped to the names a production Fabric server uses, which is what players run and what the development
# runs do not exercise.  Pregenerates with the built-in pregenerator from the server console.
#
# The server lives in build/installed-fabric: Fabric's server launcher jar (from meta.fabricmc.net), which
# fetches the Minecraft server and its libraries on first start.  Mods: this mod, Fabric API, ScalableLux.
#
# DIM=<dimension id> pregenerates another dimension.  With gpu.mode = "check" in build/installed-fabric/config/
# worldgennext.toml the run passes when chunks were compared with vanilla and none differed.
# Usage: scripts/test-installed-fabric.sh [radius in chunks] [extra JVM arguments]
set -u
cd "$(dirname "$0")/.."
radius="${1:-60}"
jvm="${2:-}"
# FABRIC_MC=<Minecraft version> tests that version's build (module fabric-<version without dots>) on its own server.
mc="${FABRIC_MC:-1.21.1}"
module="fabric-$(echo "$mc" | tr -d .)"
if [ "$mc" = 1.21.1 ]; then server=build/installed-fabric; api=build/test-mods/fabric-api; lux=build/test-mods/fabric-vanilla
else server="build/installed-fabric-$mc"; api="build/test-mods/fabric-api-$mc"; lux="build/test-mods/$module/vanilla"; fi
# MODS=<directory> installs that directory's jars instead of ScalableLux alone (it should contain ScalableLux).
lux="${MODS:-$lux}"
export PATH="${JDK:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}/bin:$PATH"
[ -f "$server/fabric-server-launch.jar" ] || { echo "no Fabric server launcher in $server"; exit 2; }
free=$(df -Pk . | awk 'NR==2 {print int($4 / 1048576)}')
[ "$free" -lt 10 ] && { echo "only $free GB free here"; exit 3; }

stash=build/_to_delete/fabric-worlds
mkdir -p "$stash" "$server/mods"
mv "$server"/mods/*.jar "$stash/" 2>/dev/null
cp "$(ls -t mod/targets/"$module"/build/libs/worldgennext-fabric-"$mc"-*.jar | grep -v sources | head -1)" "$server/mods/"
cp "$api"/*.jar "$lux"/*.jar "$server/mods/"
[ -d "$server/world" ] && mv "$server/world" "$stash/world-$(date +%Y%m%d-%H%M%S)"
rm -f "$server/logs/latest.log"
printf 'eula=true\n' > "$server/eula.txt"
printf 'level-seed=0\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=25595\nspawn-protection=0\nmax-tick-time=-1\n' > "$server/server.properties"

cd "$server"
{
  for _ in $(seq 1 900); do sleep 1; grep -q "Done (" logs/latest.log 2>/dev/null && break; grep -qE "Failed to start the minecraft server|Exception in thread \"main\"" logs/latest.log console.out 2>/dev/null && { echo stop; exit; }; done
  for _ in $(seq 1 600); do sleep 1; grep -qE "the_end:? .*(on the GPU|vanilla code|check mode)|GPU terrain generation is unavailable" logs/latest.log && break; done
  sleep 3
  echo "worldgennext status"
  echo "execute in ${DIM:-minecraft:overworld} run worldgennext pregen start $radius 0 0"
  for _ in $(seq 1 3600); do sleep 1; grep -q "Pregeneration finished" logs/latest.log && break; done
  echo "save-all flush"
  sleep 8
  echo "worldgennext status"
  sleep 1
  echo "stop"
} | java -Xmx16G $jvm -jar fabric-server-launch.jar nogui > console.out 2>&1
cd - >/dev/null

log="$server/logs/latest.log"
grep -E "WorldgenNext [0-9.]+ loaded|Pregeneration finished|Chunks since start|overworld: " "$log" | sed -E 's/^\[[^]]*\] \[[^]]*\]: /  /' | tail -5 | cut -c1-200
# Not counted: "Failed to fetch mob spawner entity", which the unmodified game logs for the same dungeons
# (docs/evidence/fabric-port.md).
errors=$(grep -E "/ERROR\]|/FATAL\]|Mixin apply failed|InvalidInjectionException|InvalidMixinException" "$log" 2>/dev/null | grep -vc "Failed to fetch mob spawner entity")
echo "  error lines: ${errors:-no log}"
grep -E "/ERROR\]|/FATAL\]|Mixin apply failed|Invalid(Injection|Mixin)Exception" "$log" 2>/dev/null | grep -v "Failed to fetch mob spawner entity" | head -5 | cut -c1-240
# Every chunk of the square asked for has to be on disk and readable ("0 problems" alone would also be
# true of a world with nothing in it).  Another dimension's chunks are in its own folder.
case "${DIM:-minecraft:overworld}" in
  minecraft:overworld) regions="$server/world/region" ;;
  minecraft:the_nether) regions="$server/world/DIM-1/region" ;;
  minecraft:the_end) regions="$server/world/DIM1/region" ;;
  *) regions="$server/world/dimensions/$(echo "$DIM" | tr ':' '/')/region" ;;
esac
side=$((2 * radius + 1))
saved=$(python scripts/check-region-files.py --min-chunks $((side * side)) "$regions" | tail -1; exit "${PIPESTATUS[0]}") && savedOk=yes || savedOk=
echo "  saved chunks: $saved"
# The names a released Fabric game uses differ from the development environment's; if the mod could not read
# the world generator under them it falls back to vanilla code, which must not count as a pass.
# In check mode (gpu.mode = "check" in the server's config) vanilla code generates and the GPU's result is
# compared with it; the status report then carries the tally.
checked=$(grep -E "chunks compared" "$log" 2>/dev/null | tail -1 | grep -oE "[0-9,]+ chunks compared, [0-9,]+ differ")
[ -n "$checked" ] && echo "  check mode: $checked"
gpu=$(grep -E "Chunks since start" "$log" 2>/dev/null | tail -1 | sed -E 's/.*start: ([0-9,]+) on the GPU.*/\1/' | tr -d ',')
if [ -z "$savedOk" ]; then
  echo "INSTALLED-FABRIC FAIL (saved chunks: $saved)"
  exit 1
elif grep -q "Pregeneration finished" "$log" 2>/dev/null && [ "${errors:-1}" = 0 ] && echo "$checked" | grep -qE "^[1-9][0-9,]* chunks compared, 0 differ$"; then
  echo "INSTALLED-FABRIC PASS (check mode: $checked)"
elif grep -q "Pregeneration finished" "$log" 2>/dev/null && [ "${errors:-1}" = 0 ] && [ "${gpu:-0}" -gt 0 ] 2>/dev/null; then
  echo "INSTALLED-FABRIC PASS (${gpu} chunks' terrain on the GPU)"
else
  echo "INSTALLED-FABRIC FAIL (errors ${errors:-?}, GPU chunks ${gpu:-0})"
  exit 1
fi
