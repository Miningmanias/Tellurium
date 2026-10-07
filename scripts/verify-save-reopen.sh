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
printed() { printf '%s\n' "$2" | grep -m1 "^$1 " | sed "s/^$1 //" | tr -d '\r' | tr '\\' '/'; }
fail() { echo "SAVE-REOPEN FAIL: $1"; exit 1; }
# Every run's digest is the one beside the report that run printed, and every run has to exit successfully
# with a report that says so; nothing is taken from earlier runs.
bench() { # label, then bench-cps.ps1 arguments; sets $out
  local label="$1"; shift
  out=$(powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/bench-cps.ps1 -Status "$STATUS" -Label "$label" -RadiusChunks "$RADIUS" "$@" 2>&1) \
      || fail "the $label run failed: $(printf '%s\n' "$out" | grep -E "BENCH FAIL|FAILED|Exception" | tail -1 | cut -c1-200)"
  local report; report=$(printed REPORT "$out")
  local checked; checked=$(python scripts/check-bench-report.py "$report" --endpoint "$STATUS" --radius "$RADIUS" 2>&1) || fail "the $label run's report: $checked"
  digest="${report%.json}.digest.txt"
}

bench sr-gen -ModsDir "$MODS" -Properties "$G;worldgennext.bench.completionDigest=true;$EXTRA"
run=$(printed RUNDIR "$out")
[ -d "$run" ] || fail "no run directory"
cp "$run/logs/latest.log" "$run/logs/generate.log"
generated="$digest"
grep -E "benchmark PASS|benchmark FAIL" "$run/logs/generate.log" | sed 's/^.*worldgennext-bench\/\]: /generate: /'
grep -E "Fast GPU NOISE stopped" "$run/logs/generate.log" | tail -1 | sed -E 's/.*stopped: /generate: /' | cut -c1-110
echo "generate: region files $(ls "$run"/candidate-world/region/*.mca 2>/dev/null | wc -l), $(du -sm "$run/candidate-world/region" | cut -f1) MB"

bench sr-reopen -ReopenRunDir "build/run/$(basename "$run")" -Properties "$G;worldgennext.bench.release=end;worldgennext.bench.digest=true;$EXTRA"
reopened="$digest"
grep -E "benchmark PASS|benchmark FAIL" "$run/logs/latest.log" | sed 's/^.*worldgennext-bench\/\]: /reopen: /'
stopped=$(grep -E "Fast GPU NOISE stopped" "$run/logs/latest.log" | tail -1 | sed -E 's/.*stopped: //')
echo "reopen: $(echo "$stopped" | cut -c1-110)"
regenerated=$(echo "$stopped" | sed -nE 's/^gpu=([0-9]+) fallback=([0-9]+).*/\1+\2/p')

[ -f "$generated" ] && [ -f "$reopened" ] || fail "digest missing"
# Structure starts are compared in the second half (see the top of this file); here their differences are
# shown and not counted.  A chunk missing from either side, a malformed digest or a comparison that could
# not be made fails whatever else is equal: the comparison's exit status decides, not its wording.
comparison=$(python scripts/compare-digests.py --ignore structures "$generated" "$reopened" | flat; exit "${PIPESTATUS[0]}") && same=yes || same=
echo "completion vs reopened: $comparison"
[ "$regenerated" = "0+0" ] || fail "reopened run generated terrain again ($regenerated)"
[ -n "$same" ] || fail "the reopened world differs from what was generated"

ORIGINAL="worldgennext.asyncChunkSave=false;worldgennext.asyncChunkCompress=false;worldgennext.regionHeaderBatch=false;worldgennext.asyncIoMailboxBatch=1;worldgennext.asyncGroupCommit=false;worldgennext.asyncChunkLoad=false;worldgennext.parallelMailboxThreads=false"
bench sr-orig -ModsDir "$MODS" -Properties "$G;$ORIGINAL;$EXTRA"
run2=$(printed RUNDIR "$out")
[ -d "$run2" ] || fail "no run directory for the original save path"
bench sr-orig-reopen -ReopenRunDir "build/run/$(basename "$run2")" -Properties "$G;$ORIGINAL;worldgennext.bench.release=end;worldgennext.bench.digest=true;$EXTRA"
original="$digest"
[ -f "$original" ] || fail "no digest for the original save path"
structures=$(python scripts/compare-digests.py --only structures "$reopened" "$original" | flat; exit "${PIPESTATUS[0]}") && same=yes || same=
echo "reopened vs reopened with the original save path: $structures"
[ -n "$same" ] || fail "structure data differs from the original save path"
echo "SAVE-REOPEN PASS ($STATUS radius $RADIUS, $MODS)"
