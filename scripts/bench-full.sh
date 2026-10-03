#!/usr/bin/env bash
# FULL throughput run with the GPU path and ScalableLux; prints the whole-run and steady-state lines.
# Usage: scripts/bench-full.sh <label> [extra properties, semicolon-separated] [mods dir] [radius] [warmup radius]
set -u
cd "$(dirname "$0")/.."
label="${1:-full}"
extra="${2:-}"
mods="${3:-build/test-mods/vanilla}"
radius="${4:-90}"
warmup="${5:-5}"
cache="worldgennext.fast.pipelineCacheDir=$(pwd -W 2>/dev/null || pwd)/build/fast-cache"
out=$(powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status FULL -RadiusChunks "$radius" -WarmupRadiusChunks "$warmup" \
    -ModsDir "$mods" -Label "$label" ${JFR:+-Jfr "$JFR"} -Properties "worldgennext.fast.gpu=force;$cache;$extra" 2>&1)
run=$(printf '%s\n' "$out" | grep -m1 '^RUNDIR' | sed 's/^RUNDIR //' | tr -d '\r')
log="$run/logs/latest.log"
grep -E "measured steady-state|benchmark PASS|benchmark FAIL|OutOfMemory|Mixin apply failed|InvalidInjection" "$log" | sed 's/^.*worldgennext-bench\/\]: //' | head -8
grep -E "bail|Bail" "$log" | tail -2 | cut -c1-200
printf '%s\n' "$out" | grep -F "[worldgennext]" | tr -d '\r'
