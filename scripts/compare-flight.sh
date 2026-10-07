#!/usr/bin/env bash
# Moving-load test for a set of chunk-generation mods, using only vanilla console commands.
#
# A 15x15-chunk square of force-loaded chunks is moved across ungenerated terrain in steps, the way a
# fast-flying player's surroundings move: chunks ahead are generated and become ticking, chunks behind are
# released and unload.  Every ten steps `tick query` is asked for the server's tick times.  At the end the
# script reports the worst percentiles seen and how many chunks were fully generated and saved.
#
# This is not a real player (nothing is sent to a client) and not the in-mod PlayerTour; it is the closest
# load that can be applied identically to servers with and without this mod.
#
# Configurations and environment (SERVER, JDK) are those of scripts/compare-chunk-mods.sh.
# Usage: scripts/compare-flight.sh <configuration> [steps] [stride in blocks] [milliseconds per step]
set -u
cd "$(dirname "$0")/.."
config="${1:?configuration}"
steps="${2:-120}"
stride="${3:-96}"
millis="${4:-500}"
server="${SERVER:-build/installed-server}"
neoforge=$(ls "$server/libraries/net/neoforged/neoforge" | head -1)
stash=build/_to_delete/compare-worlds
mkdir -p "$stash" "$server/mods-stash"
export PATH="${JDK:-/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}/bin:$PATH"

mv "$server"/mods/*.jar "$server/mods-stash/" 2>/dev/null
cp build/test-mods/vanilla/ScalableLux-*.jar "$server/mods/"
case "$config" in
  vanilla) ;;
  tellurium) cp "$(ls -t mod/targets/neoforge-1211/build/libs/tellurium-neoforge-1.21.1-*.jar | grep -v sources | head -1)" "$server/mods/" ;;
  c2me) cp build/test-mods/c2me/c2me-neoforge-mc*.jar "$server/mods/" ;;
  c2me-ocl) cp build/test-mods/c2me-ocl/c2me-neoforge-*.jar "$server/mods/" ;;
  *) echo "unknown configuration $config"; exit 2 ;;
esac
[ -d "$server/world" ] && mv "$server/world" "$stash/world-$(date +%Y%m%d-%H%M%S)"
rm -f "$server/logs/latest.log"
printf -- '-Xmx16G\n' > "$server/user_jvm_args.txt"

pause=$(python -c "print($millis / 1000.0)")
half=$((7 * 16))
cd "$server"
{
  for _ in $(seq 1 600); do sleep 1; grep -q "Done (" logs/latest.log 2>/dev/null && break; grep -qE "Failed to start the minecraft server" logs/latest.log 2>/dev/null && break; done
  if [ "$config" = tellurium ]; then
    for _ in $(seq 1 600); do sleep 1; grep -qE "the_end: .*(on the GPU|vanilla code)|GPU terrain generation is unavailable" logs/latest.log && break; done
  fi
  sleep 5
  # Start well away from the spawn area, which is already generated.
  x0=4000; z=4000
  previous=""
  for step in $(seq 0 "$steps"); do
    x=$((x0 + step * stride))
    echo "forceload add $((x - half)) $((z - half)) $((x + half)) $((z + half))"
    [ -n "$previous" ] && echo "forceload remove $previous"
    # Remove only the strip that the new square no longer covers next time.
    previous="$((x - half)) $((z - half)) $((x - half + stride - 1)) $((z + half))"
    [ $((step % 10)) = 9 ] && echo "tick query"
    sleep "$pause"
  done
  echo "tick query"
  echo "forceload remove all"
  sleep 10
  echo "tick query"
  echo "stop"
} | java @user_jvm_args.txt "@libraries/net/neoforged/neoforge/$neoforge/win_args.txt" nogui > console.out 2>&1
cd - >/dev/null

python - "$server/logs/latest.log" "$config" "$steps" "$stride" "$millis" <<'PY'
import re, sys
log, config, steps, stride, millis = sys.argv[1:6]
text = open(log, errors="replace").read()
averages = [float(v) for v in re.findall(r"Average time per tick: ([\d.]+)ms", text)]
p50 = [float(v) for v in re.findall(r"P50: ([\d.]+)ms", text)]
p95 = [float(v) for v in re.findall(r"P95: ([\d.]+)ms", text)]
p99 = [float(v) for v in re.findall(r"P99: ([\d.]+)ms", text)]
errors = sum(1 for line in text.splitlines() if "/ERROR]" in line or "/FATAL]" in line)
behind = len(re.findall(r"Can't keep up", text))
if not averages:
    print(f"{config}: no tick data (errors in log: {errors})")
    sys.exit(1)
during = averages[:-1] or averages
# Force-loading generates the newly covered chunks on the server thread before the command returns, so a
# server that cannot generate a step's 90 chunks in the step's time falls behind the schedule.
from datetime import datetime
marks = [m.group(1) for m in re.finditer(r"^\[(?:\d+\w+\d+ )?(\d\d:\d\d:\d\d\.\d+)\].*Marked \d+ chunks", text, re.M)]
took = (datetime.strptime(marks[-1], "%H:%M:%S.%f") - datetime.strptime(marks[0], "%H:%M:%S.%f")).total_seconds() if len(marks) > 1 else 0.0
schedule = int(steps) * int(millis) / 1000.0
print(f"{config}: route took {took:.1f} s for a {schedule:.0f} s schedule ({max(0.0, took - schedule):.1f} s behind)")
print(f"{config} steps={steps} stride={stride} every {millis} ms: average tick {sum(during) / len(during):.1f} ms "
      f"(worst sample window {max(during):.1f}), P50 up to {max(p50):.1f}, P95 up to {max(p95):.1f}, P99 up to {max(p99):.1f} ms; "
      f"'can't keep up' warnings {behind}; error lines {errors}")
PY
echo "  saved chunks: $(python scripts/check-region-files.py "$server/world/region" | tail -1)"
mv "$server"/mods/*.jar "$stash/" 2>/dev/null
mv "$server"/mods-stash/*.jar "$server/mods/" 2>/dev/null
