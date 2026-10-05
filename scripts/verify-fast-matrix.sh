#!/usr/bin/env bash
# Digest-compares the fused GPU path (NOISE plus the fused SURFACE stage) against
# serial vanilla for every supported context.  Each row runs two fresh worlds
# (vanilla reference, GPU) to STATUS and compares per-chunk digests of blocks,
# heightmaps, post-processing marks, biomes and structures.
# A row passes only when the digests are identical AND the GPU generated the
# chunks (and, at SURFACE status, their surface); every other outcome,
# including a script error, is a FAIL.
# Every run has the ScalableLux companion installed (build/test-mods/*).
# Usage: scripts/verify-fast-matrix.sh [radiusChunks]
#        EVALUATE_ONLY=1 scripts/verify-fast-matrix.sh   (re-evaluate the latest existing runs)
#        STATUS=NOISE EXTRA="worldgennext.fast.surface=false" scripts/verify-fast-matrix.sh
#        ROWS="terralith combined" scripts/verify-fast-matrix.sh   (subset)
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-45}"
STATUS="${STATUS:-SURFACE}"
EXTRA="${EXTRA:-}"
status_lc=$(echo "$STATUS" | tr '[:upper:]' '[:lower:]')
CACHE="worldgennext.fast.pipelineCacheDir=$(pwd -W 2>/dev/null || pwd)/build/fast-cache"
# force: qualify the current kernels regardless of the shipped list (auto would silently use vanilla).
G="worldgennext.fast.gpu=force;$CACHE"
D="worldgennext.bench.release=end;worldgennext.bench.digest=true"
MODS=build/test-mods
passed=0
total=0
cps() { grep -E "measured phase" "$1" 2>/dev/null | tail -1 | sed -E 's/.*, ([0-9.]+) cps.*/\1/'; }
wanted() { [ -z "${ROWS:-}" ] && return 0; for r in $ROWS; do [ "$r" = "$1" ] && return 0; done; return 1; }
pair() { # name, mods subdirectory, extra script args, extra properties, "terrain-only" when the surface rules are expected to stay on the CPU
  local name="$1" mods="$2" args="$3" props="$4"
  wanted "$name" || return 0
  total=$((total + 1))
  if [ -z "${EVALUATE_ONLY:-}" ]; then
    # shellcheck disable=SC2086
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label "mv-$name" -RadiusChunks "$RADIUS" \
        -ModsDir "$MODS/$mods" $args \
        -Properties "$D;worldgennext.fast.gpu=off;worldgennext.parallelStructureSteps=false;worldgennext.parallelSurfaceCarvers=false;worldgennext.parallelFeatures=false;worldgennext.asyncChunkSave=false;worldgennext.asyncChunkCompress=false;worldgennext.biomeColumnCache=false;worldgennext.unloadTypeCache=false;worldgennext.fast.rtreeStoreSkip=false;worldgennext.fast.biomeIndex=false;worldgennext.fast.aquiferPrefill=false;worldgennext.fast.orePlacement=false;worldgennext.fast.lazyNoiseWrap=false;worldgennext.fast.uniformBiome=false;worldgennext.fast.cavePlans=false;worldgennext.fast.heightCache=false;worldgennext.asyncIoMailboxBatch=1;worldgennext.asyncGroupCommit=false;worldgennext.asyncChunkLoad=false;worldgennext.parallelMailboxThreads=false;worldgennext.fast.regionChunkCache=false;worldgennext.regionHeaderBatch=false;worldgennext.fast.freshRegionShortcut=false;worldgennext.fast.shapeCache=false;worldgennext.unloadPacing=false;worldgennext.promptTaskRelease=false;worldgennext.bench.productionThreadNames=false;$props" >/dev/null 2>&1
    # shellcheck disable=SC2086
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label "mg-$name" -RadiusChunks "$RADIUS" \
        -ModsDir "$MODS/$mods" $args \
        -Properties "$G;$D;$props;$EXTRA" >/dev/null 2>&1
  fi
  local vdigest gdigest vlog glog
  vdigest=$(ls -t build/bench/mv-"$name"-"$status_lc"-*.digest.txt 2>/dev/null | head -1)
  gdigest=$(ls -t build/bench/mg-"$name"-"$status_lc"-*.digest.txt 2>/dev/null | head -1)
  vlog=$(ls -td build/run/bench-mv-"$name"-"$status_lc"-* 2>/dev/null | head -1)/logs/latest.log
  glog=$(ls -td build/run/bench-mg-"$name"-"$status_lc"-* 2>/dev/null | head -1)/logs/latest.log
  local comparison="no digests"
  if [ -n "$vdigest" ] && [ -n "$gdigest" ]; then
    comparison=$(python scripts/compare-digests.py "$vdigest" "$gdigest" | tr '\n' ' ')
  fi
  local stopped gpuChunks bail expected surface surfaceBail
  stopped=$(grep -E "Fast GPU NOISE stopped" "$glog" 2>/dev/null | tail -1 | sed -E 's/.*stopped: //')
  gpuChunks=$(echo "$stopped" | sed -nE 's/.*gpu=([0-9]+).*/\1/p')
  bail=$(echo "$stopped" | sed -nE 's/.* bail=([0-9]+).*/\1/p')
  surface=$(echo "$stopped" | sed -nE 's/.* surface=([0-9]+).*/\1/p')
  surfaceBail=$(echo "$stopped" | sed -nE 's/.*surfaceBail=([0-9]+).*/\1/p')
  expected=$(echo "$comparison" | sed -nE 's/.*expected=([0-9]+).*/\1/p')
  local verdict="FAIL"
  case "$comparison" in
    *PASS*)
      if [ -z "$gpuChunks" ] || [ -z "$expected" ] || [ "$gpuChunks" -lt $((expected * 9 / 10)) ]; then
        verdict="FAIL(gpu generated ${gpuChunks:-0} of ${expected:-?})"
      elif [ "$STATUS" != "NOISE" ] && [ -z "$EXTRA" ] && [ "${5:-}" != "terrain-only" ] && { [ -z "$surface" ] || [ "$surface" -lt $((expected * 8 / 10)) ]; }; then
        verdict="FAIL(gpu surfaced ${surface:-0} of ${expected:-?})"
      else
        verdict="PASS"
      fi ;;
  esac
  [ "$verdict" = "PASS" ] && passed=$((passed + 1))
  echo "$name | vanilla $(cps "$vlog") cps | gpu $(cps "$glog") cps gpuChunks=${gpuChunks:-0} bail=${bail:-?} surface=${surface:-0} surfaceBail=${surfaceBail:-?} | $comparison| $verdict"
}
NETHER="worldgennext.bench.dimension=minecraft:the_nether;worldgennext.bench.centerX=300;worldgennext.bench.centerZ=300"
END="worldgennext.bench.dimension=minecraft:the_end;worldgennext.bench.centerX=300;worldgennext.bench.centerZ=300"
pair vanilla-overworld vanilla "" ""
pair vanilla-seed12345 vanilla "-Seed 12345" ""
pair vanilla-seedneg1 vanilla "-Seed -1" ""
pair vanilla-nether vanilla "" "$NETHER"
pair vanilla-end vanilla "" "$END"
# Biome-specific surface code: badlands pillars and clay bands, icebergs, steep slopes.
pair vanilla-eroded-badlands vanilla "" "worldgennext.bench.centerBiome=minecraft:eroded_badlands"
pair vanilla-frozen-ocean vanilla "" "worldgennext.bench.centerBiome=minecraft:frozen_ocean"
pair vanilla-deep-frozen-ocean vanilla "-Seed 12345" "worldgennext.bench.centerBiome=minecraft:deep_frozen_ocean"
pair vanilla-frozen-peaks vanilla "" "worldgennext.bench.centerBiome=minecraft:frozen_peaks"
pair vanilla-jagged-peaks vanilla "-Seed 12345" "worldgennext.bench.centerBiome=minecraft:jagged_peaks"
pair vanilla-windswept-savanna vanilla "-Seed -1" "worldgennext.bench.centerBiome=minecraft:windswept_savanna"
pair vanilla-mangrove-swamp vanilla "" "worldgennext.bench.centerBiome=minecraft:mangrove_swamp"
pair terralith terralith "" ""
pair tectonic tectonic "" ""
pair combined combined "" ""
# Dimension packs (optional rows: the mods are fetched by scripts/test-worldgen-mods.py into build/test-mods/compat).
if [ -d "$MODS/compat/incendium" ]; then pair incendium-nether compat/incendium "" "$NETHER"; fi
if [ -d "$MODS/compat/amplified-nether" ]; then pair amplified-nether compat/amplified-nether "" "$NETHER" terrain-only; fi
if [ -d "$MODS/compat/nullscape" ]; then pair nullscape-end compat/nullscape "" "$END" terrain-only; fi
EXPECTED_ROWS=15
[ -n "${ROWS:-}" ] && EXPECTED_ROWS=$total
# The dimension-pack rows are optional, so a full run has at least the 15 core rows.
if [ "$passed" -eq "$total" ] && [ "$total" -ge "$EXPECTED_ROWS" ]; then
  echo "MATRIX PASS ($passed/$total)"
  exit 0
fi
echo "MATRIX FAIL ($passed/$total)"
exit 1
