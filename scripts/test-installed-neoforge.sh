#!/usr/bin/env bash
# Runs the built NeoForge jar of a Minecraft version after 1.21.1 on an installed NeoForge server (not the
# development environment).  Pregenerates with the built-in pregenerator from the server console.  The 1.21.1
# jar has its own, fuller console test: scripts/test-installed-commands.sh.
#
# The server lives in build/installed-neoforge-<Minecraft version>: NeoForge's installer run there with
# --installServer.  Mods: this mod and ScalableLux (build/test-mods/neoforge-<digits>/vanilla).
#
# DIM=<dimension id> pregenerates another dimension.  With gpu.mode = "check" in the server's
# config/worldgennext.toml the run passes when chunks were compared with vanilla and none differed.
# Usage: NEOFORGE_MC=1.21.8 scripts/test-installed-neoforge.sh [radius in chunks]
set -u
cd "$(dirname "$0")/.."
radius="${1:-60}"
mc="${NEOFORGE_MC:-1.21.8}"
module="neoforge-$(echo "$mc" | tr -d .)"
server="build/installed-neoforge-$mc"
# MODS=<directory> installs that directory's jars instead of ScalableLux alone (it should contain ScalableLux).
lux="${MODS:-build/test-mods/$module/vanilla}"
export PATH="${JDK:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}/bin:$PATH"
args=$(ls "$server"/libraries/net/neoforged/neoforge/*/win_args.txt 2>/dev/null | head -1)
[ -n "$args" ] || { echo "no NeoForge server installed in $server"; exit 2; }
args="${args#$server/}"
free=$(df -Pk . | awk 'NR==2 {print int($4 / 1048576)}')
[ "$free" -lt 10 ] && { echo "only $free GB free here"; exit 3; }

stash=build/_to_delete/neoforge-worlds
mkdir -p "$stash" "$server/mods"
mv "$server"/mods/*.jar "$stash/" 2>/dev/null
cp "$(ls -t "$module"/build/libs/worldgennext-neoforge-"$mc"-*.jar | grep -v sources | head -1)" "$server/mods/"
cp "$lux"/*.jar "$server/mods/"
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
} | java -Xmx16G "@$args" nogui > console.out 2>&1
cd - >/dev/null

log="$server/logs/latest.log"
grep -E "WorldgenNext [0-9.]+ loaded|Pregeneration finished|Chunks since start|overworld: " "$log" | sed -E 's/^\[[^]]*\] \[[^]]*\]( \[[^]]*\])?: /  /' | tail -5 | cut -c1-200
# Not counted: "Failed to fetch mob spawner entity", which the unmodified game logs for the same dungeons
# (docs/evidence/fabric-port.md).
errors=$(grep -E "/ERROR\]|/FATAL\]|Mixin apply failed|InvalidInjectionException|InvalidMixinException" "$log" 2>/dev/null | grep -vc "Failed to fetch mob spawner entity")
echo "  error lines: ${errors:-no log}"
grep -E "/ERROR\]|/FATAL\]|Mixin apply failed|Invalid(Injection|Mixin)Exception" "$log" 2>/dev/null | grep -v "Failed to fetch mob spawner entity" | head -5 | cut -c1-240
echo "  saved chunks: $(python scripts/check-region-files.py "$server/world/region" | tail -1)"
# A world generator the mod could not read or does not list falls back to vanilla code, which must not count
# as a pass.
# In check mode (gpu.mode = "check" in the server's config) vanilla code generates and the GPU's result is
# compared with it; the status report then carries the tally.
checked=$(grep -E "chunks compared" "$log" 2>/dev/null | tail -1 | grep -oE "[0-9,]+ chunks compared, [0-9,]+ differ")
[ -n "$checked" ] && echo "  check mode: $checked"
gpu=$(grep -E "Chunks since start" "$log" 2>/dev/null | tail -1 | sed -E 's/.*start: ([0-9,]+) on the GPU.*/\1/' | tr -d ',')
if grep -q "Pregeneration finished" "$log" 2>/dev/null && [ "${errors:-1}" = 0 ] && echo "$checked" | grep -qE "^[1-9][0-9,]* chunks compared, 0 differ$"; then
  echo "INSTALLED-NEOFORGE PASS (check mode: $checked)"
elif grep -q "Pregeneration finished" "$log" 2>/dev/null && [ "${errors:-1}" = 0 ] && [ "${gpu:-0}" -gt 0 ] 2>/dev/null; then
  echo "INSTALLED-NEOFORGE PASS (${gpu} chunks' terrain on the GPU)"
else
  echo "INSTALLED-NEOFORGE FAIL (errors ${errors:-?}, GPU chunks ${gpu:-0})"
fi
