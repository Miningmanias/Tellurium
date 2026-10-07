#!/usr/bin/env bash
# Runs the checks a release candidate should pass, one after another, and prints one line per check.
#
#   build      gradlew test build verifyArchitecture
#   exactness  SURFACE digests of three contexts against serial vanilla (ROWS overrides; FULL_MATRIX=1 runs all 15)
#   carvers    the same at CARVERS status for vanilla Overworld
#   reopen     FULL save, reopen in a new process, compare
#   kill       kill the server mid-pregeneration, validate region files, resume
#   console    type every command into the installed server running the release jar
#   client     singleplayer SURFACE digest against the dedicated reference (CLIENT=1 only; opens a game window)
#   tour       a player flown across fresh terrain in singleplayer, no errors (CLIENT=1 only)
#
# GPU checks run one at a time; nothing else should be using the GPU.  Logs go to build/release-check/.
# Usage: scripts/release-check.sh            (about 25 minutes; FULL_MATRIX=1 adds about 40)
set -u
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot}"
out=build/release-check
mkdir -p "$out"
failed=0
results=()

busy=$(nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>/dev/null | head -1)
if [ -n "$busy" ] && [ "$busy" -gt 30 ]; then
  echo "The GPU is ${busy}% busy with something else; timings and GPU checks would not be clean. Stop it or rerun later."
  exit 2
fi

check() { # name, pass pattern, command...
  local name="$1" pattern="$2"; shift 2
  local start=$SECONDS
  "$@" > "$out/$name.log" 2>&1
  local status=$?
  # A check passes when its command succeeded AND printed its own verdict: output that reads like a pass
  # from a command that then failed is a failure, and so is a clean exit without the verdict.
  local verdict="FAIL"
  if [ $status -eq 0 ] && grep -qE "$pattern" "$out/$name.log" \
      && ! grep -qE "MATRIX FAIL|SAVE-REOPEN FAIL|KILL-RECOVERY FAIL|COMMANDS FAIL|BUILD FAILED" "$out/$name.log"; then verdict="PASS"; else failed=1; fi
  [ $status -ne 0 ] && verdict="$verdict(exit $status)"
  results+=("$(printf '%-10s %s  (%d s)  %s' "$name" "$verdict" $((SECONDS - start)) "$(grep -E "$pattern|FAIL" "$out/$name.log" | tail -1 | cut -c1-110)")")
  echo "${results[-1]}"
}

check build "BUILD SUCCESSFUL" ./gradlew.bat test build verifyArchitecture --no-daemon --console=plain
if [ -n "${FULL_MATRIX:-}" ]; then
  # 15 core rows, plus the dimension-pack rows when those packs are present.
  check exactness "MATRIX PASS \((1[5-9]|[2-9][0-9])/" bash scripts/verify-fast-matrix.sh 45
else
  check exactness "MATRIX PASS" env ROWS="${ROWS:-vanilla-overworld vanilla-nether combined}" bash scripts/verify-fast-matrix.sh 45
fi
check carvers "MATRIX PASS" env STATUS=CARVERS ROWS="vanilla-overworld" bash scripts/verify-fast-matrix.sh 45
check reopen "SAVE-REOPEN PASS" bash scripts/verify-save-reopen.sh 45 vanilla
check kill "KILL-RECOVERY PASS" bash scripts/verify-kill-recovery.sh 120 8
check console "COMMANDS PASS" bash scripts/test-installed-commands.sh 60
if [ -n "${CLIENT:-}" ]; then
  # The reference is the vanilla run of the exactness check above, by the path that run printed.
  reference=$(grep -m1 "^REPORT " build/bench/matrix-logs/mv-vanilla-overworld-surface.out 2>/dev/null | sed 's/^REPORT //' | tr -d '\r' | tr '\\' '/')
  reference="${reference%.json}.digest.txt"
  client() {
    [ -f "$reference" ] || { echo "no reference digest from this run's exactness check (ROWS must include vanilla-overworld)"; return 1; }
    rm -f build/bench/release-client.digest.txt
    DIGEST=build/bench/release-client.json bash scripts/run-client-pregen.sh 45 vanilla
    python scripts/compare-digests.py "$reference" build/bench/release-client.digest.txt | tr -d '\r' | tr '\n' ' '
    local compared=${PIPESTATUS[0]}
    echo
    return "$compared"
  }
  check client "missing=0 extra=0 +PASS" client
  tour() {
    TOUR=120 bash scripts/run-client-pregen.sh 1 vanilla | tee "$out/tour.out"
    # The client is closed by the script once the tour has reported; the tour's own line is the result.
    grep -q "player tour PASS" "$out/tour.out" && ! grep -q "player tour FAIL" "$out/tour.out"
  }
  check tour "player tour PASS" tour
fi

echo
echo "release check: $([ $failed = 0 ] && echo PASS || echo FAIL)  ($(git rev-parse --short HEAD)$(git status --short -- mod compiler-vulkan runtime-vulkan scripts | grep -q . && echo ', uncommitted changes'))"
printf '  %s\n' "${results[@]}"
exit $failed
