#!/usr/bin/env bash
# Digest-compares the fused GPU NOISE path against serial vanilla for every
# supported context. Each row runs two fresh worlds (vanilla reference, GPU)
# and compares per-chunk digests. Prints one PASS/FAIL line per context.
# A row passes only when the digests are identical AND the GPU generated the
# chunks; every other outcome, including a script error, is a FAIL.
# Usage: scripts/verify-fast-matrix.sh [radiusChunks]
#        EVALUATE_ONLY=1 scripts/verify-fast-matrix.sh   (re-evaluate the latest existing runs)
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-45}"
CACHE="worldgennext.fast.pipelineCacheDir=$(pwd -W 2>/dev/null || pwd)/build/fast-cache"
# force: qualify the current kernels regardless of the shipped list (auto would silently use vanilla).
G="worldgennext.fast.gpu=force;$CACHE"
D="worldgennext.bench.release=end;worldgennext.bench.digest=true"
MODS=build/terrain-mods
passed=0
total=0
cps() { grep -E "measured phase" "$1" 2>/dev/null | tail -1 | sed -E 's/.*, ([0-9.]+) cps.*/\1/'; }
pair() { # name, extra script args, extra properties
  local name="$1" args="$2" props="$3"
  total=$((total + 1))
  if [ -z "${EVALUATE_ONLY:-}" ]; then
    # shellcheck disable=SC2086
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status NOISE -Label "mv-$name" -RadiusChunks "$RADIUS" $args \
        -Properties "$D;worldgennext.fast.gpu=off;worldgennext.parallelStructureSteps=false;$props" >/dev/null 2>&1
    # shellcheck disable=SC2086
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status NOISE -Label "mg-$name" -RadiusChunks "$RADIUS" $args \
        -Properties "$G;$D;$props" >/dev/null 2>&1
  fi
  local vdigest gdigest vlog glog
  vdigest=$(ls -t build/bench/mv-"$name"-noise-*.digest.txt 2>/dev/null | head -1)
  gdigest=$(ls -t build/bench/mg-"$name"-noise-*.digest.txt 2>/dev/null | head -1)
  vlog=$(ls -td build/run/bench-mv-"$name"-noise-* 2>/dev/null | head -1)/logs/latest.log
  glog=$(ls -td build/run/bench-mg-"$name"-noise-* 2>/dev/null | head -1)/logs/latest.log
  local comparison="no digests"
  if [ -n "$vdigest" ] && [ -n "$gdigest" ]; then
    comparison=$(python scripts/compare-digests.py "$vdigest" "$gdigest" | tr '\n' ' ')
  fi
  local stopped gpuChunks bail expected
  stopped=$(grep -E "Fast GPU NOISE stopped" "$glog" 2>/dev/null | tail -1 | sed -E 's/.*stopped: //')
  gpuChunks=$(echo "$stopped" | sed -nE 's/.*gpu=([0-9]+).*/\1/p')
  bail=$(echo "$stopped" | sed -nE 's/.*bail=([0-9]+).*/\1/p')
  expected=$(echo "$comparison" | sed -nE 's/.*expected=([0-9]+).*/\1/p')
  local verdict="FAIL"
  case "$comparison" in
    *PASS*)
      if [ -n "$gpuChunks" ] && [ -n "$expected" ] && [ "$gpuChunks" -ge $((expected * 9 / 10)) ]; then
        verdict="PASS"
      else
        verdict="FAIL(gpu generated ${gpuChunks:-0} of ${expected:-?})"
      fi ;;
  esac
  [ "$verdict" = "PASS" ] && passed=$((passed + 1))
  echo "$name | vanilla $(cps "$vlog") cps | gpu $(cps "$glog") cps gpuChunks=${gpuChunks:-0} bail=${bail:-?} | $comparison| $verdict"
}
pair vanilla-overworld "" ""
pair vanilla-seed12345 "-Seed 12345" ""
pair vanilla-seedneg1 "-Seed -1" ""
pair vanilla-nether "" "worldgennext.bench.dimension=minecraft:the_nether;worldgennext.bench.centerX=300;worldgennext.bench.centerZ=300"
pair vanilla-end "" "worldgennext.bench.dimension=minecraft:the_end;worldgennext.bench.centerX=300;worldgennext.bench.centerZ=300"
pair terralith "-ModsDir $MODS/terralith-2.6.2-lithostitched-1.8.0b6-20260913" ""
pair tectonic "-ModsDir $MODS/tectonic-3.0.26-lithostitched-1.8.0b6-20260913" ""
pair combined "-ModsDir $MODS/combined-terralith-tectonic-lithostitched-20260913" ""
if [ "$passed" -eq "$total" ] && [ "$total" -eq 8 ]; then
  echo "MATRIX PASS ($passed/$total)"
  exit 0
fi
echo "MATRIX FAIL ($passed/$total)"
exit 1
