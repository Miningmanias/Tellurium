# Test layers and honest outcomes

The v0.1 commands and evidence remain regression coverage. The 0.2.0 checkpoint also provides focused driver-free contract tasks; real Minecraft/native qualification remains separate in [v0.2/TESTS.md](v0.2/TESTS.md).

```powershell
.\gradlew.bat verifyV02Inputs testWorldgenSemantics testIntegerIeee testMinecraftOracle testChunkResultContract testWorldgenCoordinator checkReleaseJar --no-daemon
```

`qualifyV02` intentionally fails while the acceptance manifest is not a completed execution report.

## Commands

Run Gradle on JDK21. The repository wrapper pins Gradle8.14.2.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
.\gradlew.bat test build verifyArchitecture testReport --no-daemon
python scripts/summarize-tests.py
```

The Windows helper `scripts/check.ps1` finds a Java21 installation or accepts `-JavaHome`. It changes only its process environment.

`test` and `testFast` run CPU tests. They do not launch Minecraft or exercise a physical GPU. Standard reports live under each module's `build/reports/tests/test`; the aggregate is `build/reports/tests/all`. The summary script fails if no tests exist or any test failed.

The installed-jar smoke is opt-in and requires a disposable NeoForge server
template containing `server.jar`, `eula.txt` with `eula=true`, and no existing
worlds or WorldgenNext jar. It copies that template into fresh per-mode run
directories, stages the release jar, starts each requested mode, waits for the
Minecraft `Done` line and sends `stop`; logs and the JSON report are retained.
It does not qualify terrain generation or enable the live hook:

```powershell
.\gradlew.bat testInstalledMod `
  '-Dworldgennext.installed.serverDir=C:/path/to/empty-neoforge-server-template' `
  '-Dworldgennext.installed.runRoot=C:/path/to/fresh-installed-test-output' `
  --no-daemon
```

The task refuses to reuse a run root or overwrite an existing staged
WorldgenNext jar. `CPU_ONLY` must report `native=DISABLED`; `GPU_REQUIRED` and
`AUTO_SUPPORTED` are boot/configuration checks with the qualified hook disabled,
so they do not claim a physical-device result.

The original-only P01 capture is an explicit opt-in server run and remains separate from `test`/`build`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1 -CaptureRoot .\build\oracle-captures\original
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1 -CaptureRoot .\build\oracle-captures\original-repeat
.\gradlew.bat :oracle-and-replay:run --args='compare-corpus build/oracle-captures/original build/oracle-captures/original-repeat --failure-dir=build/oracle-failures' --no-daemon
```

The clean server loads only the independent `oracle-1211` mod. The observed 10-case PASS is recorded in [v0.2 original oracle evidence](evidence/v0.2-original-oracle.md); it is not the full 1,500-case or candidate parity gate.

Corpus capture is dimension-namespaced below `CaptureRoot` (for example,
`minecraft_overworld/seed-0-x--32-z--32-NOISE.snap`) so repeated coordinates
from different required contexts cannot overwrite one another. The CPU and
GPU replay scripts walk those subdirectories and mirror the relative path in
their candidate and run roots; existing flat preflight captures remain valid.
The default invocation intentionally captures the small 10-case preflight.
For a required 5×5 core square, pass `-CoreSquareSideChunks 5`; use a distinct
`-ContextId` for each frozen stack/modpack context and pass its exact
`-StackFingerprint`. The capture driver remains serial and the oracle must
actually load that stack before its artifacts can count as evidence.

The opt-in CPU candidate preflight is separate from the original capture and uses a distinct output/run root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\replay-minecraft-noise-cpu.ps1 -ExpectedRoot .\build\oracle-captures\v02-vanilla-core -OutputRoot .\build\cpu-candidates\v02-vanilla-core-750-20260913 -RunRoot .\build\run\cpu-candidates-v02-vanilla-core-750-20260913
```

Its observed 750-case three-dimension vanilla result is recorded in [v0.2 CPU candidate evidence](evidence/v0.2-cpu-candidate.md); it remains a partial G4 witness and does not enable the production hook.

The opt-in GPU candidate replay uses the same independent expected directory,
but requires a real physical device and records one receipt sidecar per
candidate artifact. It never substitutes CPU output and stops on the first
unsupported context, compilation failure, device failure or comparison
mismatch:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\replay-minecraft-noise-gpu.ps1 `
  -ExpectedRoot .\build\oracle-captures\original `
  -OutputRoot .\build\gpu-candidates-v02 `
  -RunRoot .\build\run\gpu-candidates-v02
```

The GPU script is an orchestration boundary, not qualification evidence by
itself. Each `*.chunk.gpu-receipt.json` must show `GPU_IEEE_BITS`, a positive
device-produced block count, zero device-reported mismatches, and nonempty
shader/SPIR-V hashes before the paired oracle comparison is considered.

Successful isolated GPU receipts also contain an explicit
`telemetryScope=executor_lifetime_since_batch_start` plus dispatch, element,
byte-transfer and wall-time counters. These are diagnostic counters for the
persistent replay executor, not a throughput or speedup claim.

The loader-side `LiveNoiseBridgeTest` covers the P09 sequencing seam: capture
and compute run before the stale-generation recheck, commit is delegated only
after that recheck, and original downstream stages run only after a committed
result. Reload during compute prevents publication; a failed qualified route
without an explicit restoration proof is classified unsafe. This is a runtime
contract test, not live hook or Minecraft matrix evidence; the hook remains
disabled until the independent CPU/GPU gates qualify.

The qualified provider adapters now use the runtime-owned `WorldgenCoordinator`
for request admission, host resource reservation, backend provenance
validation, authoritative commit and terminal completion. The one-case
`build/coordinator-smoke/noise.snap` server run is a wiring smoke for that path;
it does not replace the independent oracle, GPU, FULL/SAVED or stability gates.
The coordinator also exposes an immutable diagnostic snapshot. The live
`/worldgennext dev status-json` command reports its queue/admission dimensions and
all distinct work counters, but that report is operational telemetry rather
than qualification evidence.

The reference-only saved/reopen boundary can be exercised with two clean
oracle processes over one disposable world:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-saved.ps1 `
  -OutputRoot .\build\oracle-saved-check `
  -RunRoot .\build\oracle-runs\saved-check `
  -Seed 0 -Dimension minecraft:overworld -ChunkX 0 -ChunkZ 0
```

The first process reaches `FULL`, completes the logical save barrier and
captures `SAVED`; the second reopens the same world and is compared across all
ten fields. This is reference-vs-reference evidence only and does not qualify
candidate application or live saved-world parity.

The FULL endpoint has a separate reference-stability smoke:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-full.ps1 `
  -OutputRoot .\build\oracle-full-check `
  -RunRoot .\build\oracle-runs\full-check `
  -Seed 0 -Dimension minecraft:overworld -ChunkX 0 -ChunkZ 0
```

It requests `ChunkStatus.FULL` in two fresh processes and compares all ten
canonical fields. This establishes only the original same-stack FULL boundary;
candidate downstream parity remains a separate gate.

For a bounded candidate endpoint corpus, first capture an independent
`NOISE`, `FULL` or `SAVED` corpus with `scripts/capture-original-corpus.ps1 -Endpoint`
and then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\replay-minecraft-logical.ps1 `
  -Endpoint FULL `
  -ExpectedRoot .\build\oracle-captures\original-full `
  -OutputRoot .\build\candidate-full-check `
  -RunRoot .\build\oracle-runs\candidate-full-check
```

Use `-Endpoint NOISE` to exercise the isolated version-pinned live hook and
commit path, or `-Endpoint SAVED -Reopen` for a fresh candidate-process
reopen. The runner refuses mixed endpoints, overlapping roots, stale output
roots and missing per-case PASS sidecars; its result is verification-only.

Both capture paths accept `-Dimension minecraft:overworld`, `minecraft:the_nether` or
`minecraft:the_end` (and pass the selected dimension through to the server). The
existing evidence above is still the vanilla Overworld-only run; selecting another
dimension expands the artifact path but does not by itself qualify the required
multi-context matrix.

## Coverage expected at v0.1

| Layer | Assertions | What it does not establish |
| --- | --- | --- |
| Semantic/interpreter/compiler | Lazy branch behavior, boundary order, negative cells, finite supported domain, independent analytic and seeded differential cases | Complete Minecraft noise/material semantics |
| GLSL emission | ABI, bounds, control regions, constants and valid shader structure | Physical GPU execution unless the opt-in smoke passes |
| Section codec | Immutable ownership, arbitrary fixture state IDs, uniform/dense equivalence, metadata/checksum, malformed rejection | Correct choice of Minecraft block materials |
| Spatial/engine | Budget accounting, leases, tile keys, epoch/cancellation/commit-once invariants | Installed production chunk scheduler or feature ordering |
| Replay/report | Actual compared counts, mismatch/zero-coverage/failure verdicts, deterministic artifacts | Vanilla/same-stack parity |
| Loader build/smoke | Mod metadata, self-contained pure classes, game registration and diagnostic startup | GPU acceleration of Minecraft generation |
| GPU smoke | Native submission and result comparison on named device, no CPU substitute | Cross-vendor qualification, terrain parity or chunk throughput |

## Opt-in validation

The replay application's CPU and GPU commands are documented in oracle-and-replay/README.md. GPU-required smoke must return failure on a missing device, unsupported capability, timeout, compiler error, mismatch or incomplete comparison. Serialize physical GPU runs; do not co-run them with long benchmarks in the reference checkout.

Convenience tasks are `:oracle-and-replay:replay`, `:oracle-and-replay:gpuSmoke`, `:runtime-vulkan:nativeWorldgenSmoke`, `:neoforge-1211:gameSmoke`, `:oracle-1211:oracleSmoke` and the heavy root `replayMinecraftNoiseGpu` candidate task (the oracle task requires capture properties). The current `gameSmoke` run has 11/11 passing tests, including the default-off version-pinned `ChunkStatusTasks.generateNoise` hook bypass, rejected-provider teardown, live bound 1.21.1 `NoiseRouter` capture of all 15 roots in Overworld, Nether and End, nine-point Overworld raw `finalDensity` parity, three-point parity in each other vanilla dimension through the pure typed interpreter, real `ProtoChunk` application/rollback, injected mutation-failure restoration and authoritative commit-token checks. The opt-in native/game/oracle runs are deliberately excluded from `test` and `build`. Use `:oracle-and-replay:run --args='replay --output=build/replay/custom.json'` for an explicit report path; relative paths resolve inside the application module.

`:runtime-vulkan:nativeWorldgenSmoke` exercises the persistent worldgen
 executor with a real GPU and deliberately split density, captured-noise,
 captured-cell aquifer status/nearest-three-pressure, the two-word state/fluid-mark output ABI,
 and six-material legacy/xoroshiro ore batches. It proves
 device-produced output for the emitted classifier, captured
 permutation/octave noise, aquifer precedence and synthetic ore rules, not
captured Minecraft aquifer/material parity; see the [v0.2 GPU executor
evidence](evidence/v0.2-gpu-executor.md).

`:oracle-and-replay:gpuSmokeNormal` is also opt-in: seven fixed normal-range fixtures under a different corpus/backend identity. It does not satisfy the default strict GPU check or full FP64 qualification. Tests enforce profile separation and reject attempts to promote a limited native result into a strict report.

Device capability flags describe supported floating-point execution modes; they do not prove those modes are enabled in a shader. The current smoke compares raw bits for its finite fixture corpus and records the compiled shader hash. Explicit SPIR-V float-control mode qualification and cross-vendor coverage remain future work. See the [Khronos float-control properties](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceFloatControlsProperties.html).

Game bootstrap is a separate task of the neoforge-1211 build (mod/neoforge-diagnostics.gradle). Use its dedicated run directory. Do not reuse a valuable world for development smoke. A started process is not a passed boot; capture the readiness/diagnostic output and clean shutdown.

## Deferred gates

`:runtime-vulkan:nativeBlendedReductionSmoke` compares main sums, staged
main/ten quotients and final ordered reductions against independent Java
division formulas. Both compute policies, 150/37 slices, clamp-adjacent values,
subnormals/signed zero, poisoned unused branches and nonfinite used samples are
covered. Its 600 final plus 1,200 intermediate checks are primitive regression
evidence, not Minecraft qualification or the release numeric campaign. See
[the retained native-compilation timeout and bounded fix](evidence/v0.2-shared-blended-reduction.md).

`:runtime-vulkan:nativeBeardifierSmoke
'-Pworldgennext.beardifierInlineHelpers=true'` checks 112 points through eight
physical structure/combine/policy/batch runs against independent Java formulas.
It covers all terrain adjustments, junctions, negative/boundary points and
four-/six-word row layouts. Its 896 raw-bit matches do not establish nonempty
Minecraft structure parity or release numeric qualification. The broad
out-of-line control failed with device loss and is retained as negative
evidence; see [captured structure evidence](evidence/v0.2-beardifier-gpu.md).

The same smoke with `'-Pworldgennext.beardifierInputKernel=true'` checks owned
shared kernel metadata. The additional captured-piece switch
`'-Pworldgennext.beardifierTerralithPiece=true'` passes 63,488 raw-bit comparisons
over 7,936 points, four-/six-word layouts, both compute policies and full/127
slices. The ordinary all-adjustment suffix control separately passes 896
comparisons. Neither implies successful complete Minecraft ore/material output;
the first real nonempty chunk completed GPU stages but failed 56 states.

The bounded ore continuation corrects that same captured chunk: 98,304 states
and ten independent fields match with zero differences on its new frozen jar.
The intermediate input-only ore trial instead lost the device; both are retained
in [the structure/ore evidence](evidence/v0.2-beardifier-gpu.md). The 50-case
extreme-seed expansion remains separate. Neither unit source checks nor a
single successful chunk closes the required six-context release matrix.

All 50 extreme-seed Terralith captures now independently match 4,915,200 states
and 500 fields after recovery of the last three cases from an owned timeout.
This is a selected seed subset, not all 250 cases or a release gate. The newer
copy/fingerprint checkpoint separately passes 330 focused tests, the wide
physical kernel control and four real chunks; see
[its checks and scope](evidence/v0.2-host-copy-fingerprint.md).

The isolated GPU logical verifier, request/computation shutdown tracker and
cancellation-forwarding observation now pass 21 driver-free contracts within
the rebuilt 327-test compiler/runtime/engine/NeoForge checkpoint. Actual complete
NOISE/FULL/SAVED qualification remains pending. Matrix contracts pass 88 synthetic
PowerShell assertions; real-input PlanOnly sees all 1,500 original captures
without starting a child. Owned-process checks pass seven assertions on tiny
PowerShell workers, including timeout cleanup; baseline input checks pass 23
Python tests. None is GPU, live-commit or measured performance evidence. See
[verification scopes and the retained failed Terralith campaign](evidence/v0.2-live-gpu-verifier.md).

The October 1 isolated INLINE_REFERENCE dispatcher passes 126 NeoForge tests
and root `checkReleaseJar`/`verifyArchitecture`. CPU FULL, GPU FULL, and GPU
SAVED/fresh reopen each independently match ten fields for vanilla seed 0 at
`(32,32)` on the same frozen main inputs. This is one-core reference coverage,
not the complete downstream/live/production gate. Each initial GPU endpoint
run has 25 linked NOISE publications, not 25 compared FULL/SAVED cores.

Original endpoint capture supports `-Seeds @('0','12345')
-CoreSquareSideChunks 3` (36 cores/context, 216 over all six contexts),
`-TimeoutMinutesPerSeed` and read-only `-PlanOnly`. Its five-seed NOISE default
is unchanged. Seventeen synthetic planning assertions and eleven compiled
replay-input assertions pass; they do not execute Minecraft. Separate original
repeat/reopen captures and full endpoint campaigns are still required.

`:runtime-vulkan:nativeIntegerFloorSmoke` explicitly runs physical floorDiv/
floorMod helpers, including negative exact multiples and zero-divisor failure
flags; 1,684 pairs at three batch sizes pass 20,208 output-word checks. The
shared helper fix also reproduces the previously failing full Terralith chunk
and its independent oracle. Neither is a full numeric or six-context gate;
see [failure/correction evidence](evidence/v0.2-aquifer-integer-floor.md).

`WorldgenCoordinatorCampaign` is a standalone Java21 test-source main, not a
JUnit test. The archived five-seed run checks 500,000 randomized subscriber
requests, one manual worker, tiny budgets and final zero tracked resources.
It is a MODEL campaign only; see [commands and scope](evidence/v0.2-coordinator-model-campaign.md).

`:runtime-vulkan:nativeSharedFp64DivisionSmoke` is a physical-device regression,
separate from the release numeric campaign. It checks Java division raw bits
(NaN classification only), BigInteger intermediate quotient/remainder carriers,
both actual compute policies, partial batches and reversed operand slots.
Source/unit checks do not imply GPU execution. The first complete pinned
Tectonic GPU witness and bounded Java module-cache continuation are in
[shared divider evidence](evidence/v0.2-shared-fp64-divider.md); their filtered
coverage is not the complete G6 matrix or live generation qualification.

Captured parity and the isolated live/FULL/SAVED witnesses described above
are partial execution evidence. Complete candidate matrices, production
threading/admission, sustained performance and release qualification remain
open; do not relabel those requirements as passed from focused controls. The
original-only 10-case P01 preflight is evidence of the capture path, not a
completed G1 or release qualification. These are documented work items, not
disabled tests counted as success.

The isolated End GPU candidate parity witness is recorded in [v0.2 End GPU candidate evidence](evidence/v0.2-gpu-candidate-end.md). It is a real-device CPU-vs-GPU comparison plus an independent same-stack original comparison for one vanilla End case. It does not satisfy the six-context corpus or qualified GPU terrain. Production promotion requires at least five paired, compile-free warm runs and sustained workloads after correctness is qualified. Do not subtract overlapping compile timers from wall time, count queued work as completed, report synthetic samples as chunks/sec or treat an absent comparison as zero mismatches.
