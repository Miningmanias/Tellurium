#!/usr/bin/env bash
# Digest-compares the fused GPU NOISE path against serial vanilla for every
# supported context. Each row runs two fresh worlds (vanilla reference, GPU)
# and compares per-chunk digests. Prints one PASS/FAIL line per context.
# Usage: scripts/verify-fast-matrix.sh [radiusChunks]
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-45}"
CACHE="worldgennext.fast.pipelineCacheDir=$(pwd -W 2>/dev/null || pwd)/build/fast-cache"
G="worldgennext.fast.gpu=true;$CACHE"
D="worldgennext.bench.release=end;worldgennext.bench.digest=true"
MODS=build/terrain-mods
overall=0
pair() { # name, extra script args, extra properties
  local name="$1" args="$2" props="$3"
  # shellcheck disable=SC2086
  local v; v=$(powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status NOISE -Label "mv-$name" -RadiusChunks "$RADIUS" $args \
      -Properties "$D;worldgennext.parallelStructureSteps=false;$props" 2>&1 | grep -E "measured phase" | sed -E 's/.*, ([0-9.]+ cps).*/\1/')
  # shellcheck disable=SC2086
  local g; g=$(powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status NOISE -Label "mg-$name" -RadiusChunks "$RADIUS" $args \
      -Properties "$G;$D;$props" 2>&1 | grep -E "measured phase|NOISE stopped" | sed -E 's/.*, ([0-9.]+ cps).*/\1/; s/.*NOISE stopped: //' | tr '\n' ' ')
  local verdict; verdict=$(python scripts/compare-digests.py "$(ls -t build/bench/mv-$name-*.digest.txt | head -1)" "$(ls -t build/bench/mg-$name-*.digest.txt | head -1)" | tr '\n' ' ')
  echo "$name | vanilla $v | gpu $g | $verdict"
  case "$verdict" in *PASS*) ;; *) overall=1 ;; esac
}
pair vanilla-overworld "" ""
pair vanilla-seed12345 "-Seed 12345" ""
pair vanilla-seedneg1 "-Seed -1" ""
pair vanilla-nether "" "worldgennext.bench.dimension=minecraft:the_nether;worldgennext.bench.centerX=300;worldgennext.bench.centerZ=300"
pair vanilla-end "" "worldgennext.bench.dimension=minecraft:the_end;worldgennext.bench.centerX=300;worldgennext.bench.centerZ=300"
pair terralith "-ModsDir $MODS/terralith-2.6.2-lithostitched-1.8.0b6-20260913" ""
pair tectonic "-ModsDir $MODS/tectonic-3.0.26-lithostitched-1.8.0b6-20260913" ""
pair combined "-ModsDir $MODS/combined-terralith-tectonic-lithostitched-20260913" ""
echo "MATRIX $([ $overall -eq 0 ] && echo PASS || echo FAIL)"
exit $overall
