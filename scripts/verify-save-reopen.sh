#!/usr/bin/env bash
# Saved-world check of the unload save path.
#
# Run 1 generates a square with tickets released as chunks complete, so chunks unload and are saved while
# generation continues (async section encoding, deferred ticks/structures, precompressed payloads, batched
# region headers, IO mailbox batching).  Every chunk is digested the moment it completes.
# Run 2 reopens the same world in a new process and digests the same chunks as loaded from disk.
# Blocks, heightmaps, post-processing marks and biomes must be equal, and run 2 must not have generated
# terrain again.
#
# Structure starts are compared differently.  A structure piece is one object shared by every chunk it
# crosses, and several piece types move their bounding box when a later chunk places them, so the digest
# taken when the start's own chunk completed can predate the saved state.  Structure starts and references
# are the same in every run of a seed, so runs 3 and 4 repeat the two steps with the original save path
# (every save-path change switched off) and the structure digests of the two reopened worlds must be equal.
#
# Usage: STATUS=FULL scripts/verify-save-reopen.sh [radius] [mods subdirectory] [extra properties]
set -u
cd "$(dirname "$0")/.."
RADIUS="${1:-45}"
MODS="build/test-mods/${2:-vanilla}"
EXTRA="${3:-}"
STATUS="${STATUS:-FULL}"
status_lc=$(echo "$STATUS" | tr '[:upper:]' '[:lower:]')
CACHE="worldgennext.fast.pipelineCacheDir=$(pwd -W 2>/dev/null || pwd)/build/fast-cache"
G="worldgennext.fast.gpu=force;$CACHE"
flat() { tr '\n' ' '; }

out=$(powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label sr-gen -RadiusChunks "$RADIUS" \
    -ModsDir "$MODS" -Properties "$G;worldgennext.bench.completionDigest=true;$EXTRA" 2>&1)
run=$(printf '%s\n' "$out" | grep -m1 '^RUNDIR' | sed 's/^RUNDIR //' | tr -d '\r')
[ -d "$run" ] || { echo "SAVE-REOPEN FAIL: no run directory"; exit 1; }
cp "$run/logs/latest.log" "$run/logs/generate.log"
generated=$(ls -t build/bench/sr-gen-"$status_lc"-*.digest.txt 2>/dev/null | head -1)
grep -E "benchmark PASS|benchmark FAIL" "$run/logs/generate.log" | sed 's/^.*worldgennext-bench\/\]: /generate: /'
grep -E "Fast GPU NOISE stopped" "$run/logs/generate.log" | tail -1 | sed -E 's/.*stopped: /generate: /' | cut -c1-110
echo "generate: region files $(ls "$run"/candidate-world/region/*.mca 2>/dev/null | wc -l), $(du -sm "$run/candidate-world/region" | cut -f1) MB"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label sr-reopen -RadiusChunks "$RADIUS" \
    -ReopenRunDir "build/run/$(basename "$run")" -Properties "$G;worldgennext.bench.release=end;worldgennext.bench.digest=true;$EXTRA" >/dev/null 2>&1
reopened=$(ls -t build/bench/sr-reopen-"$status_lc"-*.digest.txt 2>/dev/null | head -1)
grep -E "benchmark PASS|benchmark FAIL" "$run/logs/latest.log" | sed 's/^.*worldgennext-bench\/\]: /reopen: /'
stopped=$(grep -E "Fast GPU NOISE stopped" "$run/logs/latest.log" | tail -1 | sed -E 's/.*stopped: //')
echo "reopen: $(echo "$stopped" | cut -c1-110)"
regenerated=$(echo "$stopped" | sed -nE 's/^gpu=([0-9]+) fallback=([0-9]+).*/\1+\2/p')

if [ -z "$generated" ] || [ -z "$reopened" ]; then
  echo "SAVE-REOPEN FAIL: digest missing"; exit 1
fi
comparison=$(python scripts/compare-digests.py "$generated" "$reopened" | flat)
echo "completion vs reopened: $comparison"
[ "$regenerated" = "0+0" ] || { echo "SAVE-REOPEN FAIL: reopened run generated terrain again ($regenerated)"; exit 1; }
case "$comparison" in
  *"compared=0 "*|*missing=[1-9]*|*extra=[1-9]*|*blocks:*|*heightmaps:*|*post:*|*biomes:*) echo "SAVE-REOPEN FAIL"; exit 1 ;;
esac

ORIGINAL="worldgennext.asyncChunkSave=false;worldgennext.asyncChunkCompress=false;worldgennext.regionHeaderBatch=false;worldgennext.asyncIoMailboxBatch=1"
out=$(powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label sr-orig -RadiusChunks "$RADIUS" \
    -ModsDir "$MODS" -Properties "$G;$ORIGINAL;$EXTRA" 2>&1)
run2=$(printf '%s\n' "$out" | grep -m1 '^RUNDIR' | sed 's/^RUNDIR //' | tr -d '\r')
[ -d "$run2" ] || { echo "SAVE-REOPEN FAIL: no run directory for the original save path"; exit 1; }
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label sr-orig-reopen -RadiusChunks "$RADIUS" \
    -ReopenRunDir "build/run/$(basename "$run2")" -Properties "$G;$ORIGINAL;worldgennext.bench.release=end;worldgennext.bench.digest=true;$EXTRA" >/dev/null 2>&1
original=$(ls -t build/bench/sr-orig-reopen-"$status_lc"-*.digest.txt 2>/dev/null | head -1)
[ -n "$original" ] || { echo "SAVE-REOPEN FAIL: no digest for the original save path"; exit 1; }
structures=$(python scripts/compare-digests.py "$reopened" "$original" | flat)
echo "reopened vs reopened with the original save path: $structures"
case "$structures" in
  *"compared=0 "*|*missing=[1-9]*|*extra=[1-9]*|*structures:*) echo "SAVE-REOPEN FAIL: structure data differs from the original save path"; exit 1 ;;
esac
echo "SAVE-REOPEN PASS ($STATUS radius $RADIUS, $MODS)"
