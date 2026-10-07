#!/usr/bin/env bash
# Digest-compares the fused GPU path (NOISE plus the fused SURFACE stage) against
# serial vanilla for every supported context.  Each row runs two fresh worlds
# (vanilla reference, GPU) to STATUS and compares per-chunk digests of blocks,
# heightmaps, post-processing marks, biomes and structures.
# A row passes only when the digests are identical AND the GPU generated the
# chunks (and, at SURFACE status, their surface); every other outcome,
# including a script error, is a FAIL.
# What is compared is what this invocation produced: each run's report, digest and
# log are taken from the paths that run printed, both runs have to exit
# successfully with a report that says PASS, and the two reports have to cover the
# same chunks.  Files of earlier runs are never picked up; EVALUATE_ONLY=1 looks at
# the newest existing files instead and says so in every line it prints, and its
# result is "MATRIX HISTORICAL", never "MATRIX PASS".
# Every run has the ScalableLux companion installed (build/test-mods/*).
# Usage: scripts/verify-fast-matrix.sh [radiusChunks]
#        EVALUATE_ONLY=1 scripts/verify-fast-matrix.sh   (look at the latest existing runs again; not a verification)
#        STATUS=NOISE EXTRA="tellurium.fast.surface=false" scripts/verify-fast-matrix.sh
#        ROWS="terralith combined" scripts/verify-fast-matrix.sh   (subset)
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-45}"
STATUS="${STATUS:-SURFACE}"
EXTRA="${EXTRA:-}"
status_lc=$(echo "$STATUS" | tr '[:upper:]' '[:lower:]')
CACHE="tellurium.fast.pipelineCacheDir=$(pwd -W 2>/dev/null || pwd)/build/fast-cache"
# force: qualify the current kernels regardless of the shipped list (auto would silently use vanilla).
G="tellurium.fast.gpu=force;$CACHE"
D="tellurium.bench.release=end;tellurium.bench.digest=true"
# MODULE=fabric-1211 MODS_ROOT=build/test-mods/fabric PREFIX=fabric- runs the matrix on Fabric, with its own labels.
MODS="${MODS_ROOT:-build/test-mods}"
P="${PREFIX:-}"
passed=0
total=0
cps() { grep -E "measured phase" "$1" 2>/dev/null | tail -1 | sed -E 's/.*, ([0-9.]+) cps.*/\1/'; }
printed() { grep -m1 "^$1 " "$2" 2>/dev/null | sed "s/^$1 //" | tr -d '\r' | tr '\\' '/'; }
wanted() { [ -z "${ROWS:-}" ] && return 0; for r in $ROWS; do [ "$r" = "$1" ] && return 0; done; return 1; }
pair() { # name, mods subdirectory, extra script args, extra properties, "terrain-only" when the surface rules are expected to stay on the CPU
  local name="$1" mods="$2" args="$3" props="$4"
  wanted "$name" || return 0
  total=$((total + 1))
  local vreport="" greport="" vrun="" grun="" launch="" vout gout
  mkdir -p build/bench/matrix-logs
  if [ -z "${EVALUATE_ONLY:-}" ]; then
    vout="build/bench/matrix-logs/${P}mv-$name-$status_lc.out"
    gout="build/bench/matrix-logs/${P}mg-$name-$status_lc.out"
    # shellcheck disable=SC2086
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label "${P}mv-$name" -RadiusChunks "$RADIUS" \
        -ModsDir "$MODS/$mods" $args \
        -Properties "$D;tellurium.fast.gpu=off;tellurium.parallelStructureSteps=false;tellurium.parallelSurfaceCarvers=false;tellurium.parallelFeatures=false;tellurium.asyncChunkSave=false;tellurium.asyncChunkCompress=false;tellurium.biomeColumnCache=false;tellurium.unloadTypeCache=false;tellurium.fast.rtreeStoreSkip=false;tellurium.fast.biomeIndex=false;tellurium.fast.aquiferPrefill=false;tellurium.fast.orePlacement=false;tellurium.fast.lazyNoiseWrap=false;tellurium.fast.uniformBiome=false;tellurium.fast.cavePlans=false;tellurium.fast.heightCache=false;tellurium.asyncIoMailboxBatch=1;tellurium.asyncGroupCommit=false;tellurium.asyncChunkLoad=false;tellurium.parallelMailboxThreads=false;tellurium.fast.regionChunkCache=false;tellurium.regionHeaderBatch=false;tellurium.fast.freshRegionShortcut=false;tellurium.fast.shapeCache=false;tellurium.unloadPacing=false;tellurium.promptTaskRelease=false;tellurium.bench.productionThreadNames=false;$props" >"$vout" 2>&1 \
        || launch="the reference run failed (see $vout)"
    # shellcheck disable=SC2086
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label "${P}mg-$name" -RadiusChunks "$RADIUS" \
        -ModsDir "$MODS/$mods" $args \
        -Properties "$G;$D;$props;$EXTRA" >"$gout" 2>&1 \
        || launch="${launch:+$launch; }the GPU run failed (see $gout)"
    vreport=$(printed REPORT "$vout"); greport=$(printed REPORT "$gout")
    vrun=$(printed RUNDIR "$vout"); grun=$(printed RUNDIR "$gout")
  else
    vreport=$(ls -t build/bench/"$P"mv-"$name"-"$status_lc"-*.json 2>/dev/null | head -1)
    greport=$(ls -t build/bench/"$P"mg-"$name"-"$status_lc"-*.json 2>/dev/null | head -1)
    vrun=$(ls -td build/run/bench-"$P"mv-"$name"-"$status_lc"-* 2>/dev/null | head -1)
    grun=$(ls -td build/run/bench-"$P"mg-"$name"-"$status_lc"-* 2>/dev/null | head -1)
  fi
  local vdigest="${vreport%.json}.digest.txt" gdigest="${greport%.json}.digest.txt"
  local vlog="$vrun/logs/latest.log" glog="$grun/logs/latest.log"
  if [ -z "$launch" ]; then
    # The two reports must be finished runs of this status and radius over the same chunks.
    local bound
    if ! bound=$(python scripts/check-bench-report.py "$vreport" --endpoint "$STATUS" --radius "$RADIUS" 2>&1); then launch="reference report: $bound"
    elif ! bound=$(python scripts/check-bench-report.py "$greport" --endpoint "$STATUS" --radius "$RADIUS" --same-area-as "$vreport" 2>&1); then launch="GPU report: $bound"
    fi
  fi
  local comparison="no digests" identical=""
  if [ -f "$vdigest" ] && [ -f "$gdigest" ]; then
    # The comparison's exit status decides; its text is for the reader.
    if comparison=$(python scripts/compare-digests.py "$vdigest" "$gdigest" | tr '\n' ' '; exit "${PIPESTATUS[0]}"); then identical=yes; fi
  fi
  local stopped gpuChunks bail expected surface surfaceBail
  stopped=$(grep -E "Fast GPU NOISE stopped" "$glog" 2>/dev/null | tail -1 | sed -E 's/.*stopped: //')
  gpuChunks=$(echo "$stopped" | sed -nE 's/.*gpu=([0-9]+).*/\1/p')
  bail=$(echo "$stopped" | sed -nE 's/.* bail=([0-9]+).*/\1/p')
  surface=$(echo "$stopped" | sed -nE 's/.* surface=([0-9]+).*/\1/p')
  surfaceBail=$(echo "$stopped" | sed -nE 's/.*surfaceBail=([0-9]+).*/\1/p')
  expected=$(echo "$comparison" | sed -nE 's/.*expected=([0-9]+).*/\1/p')
  local verdict="FAIL"
  if [ -n "$launch" ]; then
    verdict="FAIL($launch)"
  elif [ -n "$identical" ]; then
      if [ -z "$gpuChunks" ] || [ -z "$expected" ] || [ "$gpuChunks" -lt $((expected * 9 / 10)) ]; then
        verdict="FAIL(gpu generated ${gpuChunks:-0} of ${expected:-?})"
      elif [ "$STATUS" != "NOISE" ] && [ -z "$EXTRA" ] && [ "${5:-}" != "terrain-only" ] && { [ -z "$surface" ] || [ "$surface" -lt $((expected * 8 / 10)) ]; }; then
        verdict="FAIL(gpu surfaced ${surface:-0} of ${expected:-?})"
      else
        verdict="PASS"
      fi
  fi
  [ "$verdict" = "PASS" ] && passed=$((passed + 1))
  [ -n "${EVALUATE_ONLY:-}" ] && verdict="$verdict (historical: newest existing files, not run now)"
  echo "$name | vanilla $(cps "$vlog") cps | gpu $(cps "$glog") cps gpuChunks=${gpuChunks:-0} bail=${bail:-?} surface=${surface:-0} surfaceBail=${surfaceBail:-?} | $comparison| $verdict"
}
NETHER="tellurium.bench.dimension=minecraft:the_nether;tellurium.bench.centerX=300;tellurium.bench.centerZ=300"
END="tellurium.bench.dimension=minecraft:the_end;tellurium.bench.centerX=300;tellurium.bench.centerZ=300"
pair vanilla-overworld vanilla "" ""
pair vanilla-seed12345 vanilla "-Seed 12345" ""
pair vanilla-seedneg1 vanilla "-Seed -1" ""
pair vanilla-nether vanilla "" "$NETHER"
pair vanilla-end vanilla "" "$END"
# Biome-specific surface code: badlands pillars and clay bands, icebergs, steep slopes.
pair vanilla-eroded-badlands vanilla "" "tellurium.bench.centerBiome=minecraft:eroded_badlands"
pair vanilla-frozen-ocean vanilla "" "tellurium.bench.centerBiome=minecraft:frozen_ocean"
pair vanilla-deep-frozen-ocean vanilla "-Seed 12345" "tellurium.bench.centerBiome=minecraft:deep_frozen_ocean"
pair vanilla-frozen-peaks vanilla "" "tellurium.bench.centerBiome=minecraft:frozen_peaks"
pair vanilla-jagged-peaks vanilla "-Seed 12345" "tellurium.bench.centerBiome=minecraft:jagged_peaks"
pair vanilla-windswept-savanna vanilla "-Seed -1" "tellurium.bench.centerBiome=minecraft:windswept_savanna"
pair vanilla-mangrove-swamp vanilla "" "tellurium.bench.centerBiome=minecraft:mangrove_swamp"
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
# A ROWS filter that matches no row has verified nothing.
if [ -n "${EVALUATE_ONLY:-}" ]; then
  # Old files say what earlier runs did, not what the code does now.
  echo "MATRIX HISTORICAL ($passed/$total rows of earlier runs would pass; nothing was run)"
  exit 3
fi
if [ "$total" -gt 0 ] && [ "$passed" -eq "$total" ] && [ "$total" -ge "$EXPECTED_ROWS" ]; then
  echo "MATRIX PASS ($passed/$total)"
  exit 0
fi
echo "MATRIX FAIL ($passed/$total)"
exit 1
