#!/usr/bin/env bash
# Types the mod's commands into the console of the installed NeoForge server (build/installed-server) running
# the release jar, on a fresh world, and prints what the server answered.
#
# Covers what the unattended start-up properties do not: the command tree itself, pause, resume, stop and
# status while a pregeneration is running.
# Usage: scripts/test-installed-commands.sh [radius in chunks]
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-100}"
server=build/installed-server
jar=$(ls -t mod/targets/neoforge-1211/build/libs/worldgennext-neoforge-1.21.1-*.jar | grep -v sources | head -1)
[ -f "$jar" ] || { echo "COMMANDS FAIL: no release jar; run gradlew build"; exit 1; }
rm -f "$server"/mods/worldgennext-neoforge-*.jar
cp "$jar" "$server/mods/"
[ -d "$server/world" ] && mv "$server/world" "$server/world-prev-$(date +%Y%m%d-%H%M%S)"
rm -f "$server/config/worldgennext.toml"
# A log left by an earlier run would satisfy the "server is up" wait below at once.
rm -f "$server/logs/latest.log"
printf -- '-Xmx16G\n' > "$server/user_jvm_args.txt"
export PATH="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin:$PATH"

cd "$server"
{
  # Wait for the server to be up and the kernels to be ready before typing.
  for _ in $(seq 1 120); do sleep 1; grep -q "the_end: .* on the GPU\|generates with vanilla code\|GPU terrain generation is unavailable" logs/latest.log 2>/dev/null && grep -q "Done (" logs/latest.log && break; done
  sleep 2
  echo "worldgennext status"
  echo "worldgennext pregen status"
  echo "worldgennext pregen start $RADIUS"
  sleep 5
  echo "worldgennext pregen status"
  echo "worldgennext pregen pause"
  sleep 3
  echo "worldgennext pregen status"
  echo "worldgennext pregen start 5"
  echo "worldgennext pregen resume"
  sleep 4
  echo "worldgennext pregen stop"
  sleep 1
  echo "worldgennext pregen status"
  echo "worldgennext pregen start 20 0 0"
  for _ in $(seq 1 60); do sleep 1; grep -q "Pregeneration finished: 1,681" logs/latest.log && break; done
  echo "execute in minecraft:the_nether run worldgennext pregen start 8"
  for _ in $(seq 1 60); do sleep 1; grep -q "Pregeneration finished: 289" logs/latest.log && break; done
  echo "worldgennext pregen start worldborder"
  echo "worldborder set 400"
  sleep 1
  echo "worldgennext pregen start worldborder"
  for _ in $(seq 1 60); do sleep 1; grep -q "Pregeneration finished: 729" logs/latest.log && break; done
  echo "worldgennext status"
  echo "worldgennext dev selftest"
  sleep 1
  echo "stop"
} | java @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.176/win_args.txt nogui > console.out 2>&1
cd - >/dev/null

log="$server/logs/latest.log"
grep -E "\[worldgennext(-fast)?/\]|MinecraftServer/\]: " "$log" | grep -vE "Starting|Loading|Default game|Generating keypair|Preparing|Stopping|Saving|ThreadedAnvil|Done \("   | sed -E 's/^\[[^]]*\] \[[^]]*\] \[[^]]*\]: //' | cut -c1-210
errors=$(grep -E "/ERROR\]|/FATAL\]" "$log" 2>/dev/null | grep -vc "Failed to fetch mob spawner entity")
unknown=$(grep -ciE "unexpected error|Unknown or incomplete command|Incorrect argument" "$log" 2>/dev/null)
echo "errors: ${errors:-no log}; unexpected or unknown commands: ${unknown:-no log}"
# The commands have to have been understood, the three pregenerations they start have to have finished, and
# the server must not have logged an error of any kind meanwhile.
finished=$(grep -cE "Pregeneration finished: (1,681|289|729) " "$log" 2>/dev/null)
if [ "${errors:-1}" = 0 ] && [ "${unknown:-1}" = 0 ] && [ "${finished:-0}" -ge 3 ]; then
  echo "COMMANDS PASS"
else
  echo "COMMANDS FAIL (errors ${errors:-?}, unknown commands ${unknown:-?}, pregenerations finished ${finished:-0} of 3)"
  exit 1
fi
