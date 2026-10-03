# WorldgenNext status — 2026-10-03

## 2026-10-03 — FULL throughput and user-facing surface

Supersedes the FULL figures further down.

- **FULL throughput** (vanilla Overworld, ScalableLux, 32,761 chunks): 2,675–2,802
  chunks/s after a JIT warm-up, 2,465–2,475 from a cold start, against 124 for
  vanilla with every switch off. Tectonic 2,593, Terralith 1,824, combined 1,811
  (one run each). Details, checks and limits:
  [evidence](evidence/throughput-fused-gpu.md).
- **Checks**: 15/15 contexts identical to serial vanilla at SURFACE; CARVERS
  subset identical; ore scan and biome shortcut compared in place with
  vanilla's results; FULL save/reopen passes for vanilla and combined.
- **User-facing**: `config/worldgennext.toml`, `/worldgennext status`,
  `/worldgennext pregen ...` (region-ordered, resumable). The staged route's
  commands moved to `/worldgennext dev ...`, and its code from
  `WorldgenNextMod` to `legacy/StagedRoute`; behaviour unchanged.
- **Open**: client/singleplayer (mixins are registered for dedicated servers
  only), Linux, other GPUs; group commit for `sync-chunk-writes=true`;
  off-thread chunk loading; vanilla FULL references for the terrain packs. The
  staged `GPU_IEEE_BITS` gates below are unchanged by any of this.

## 2026-10-02 — throughput path and frozen-matrix outcome

Two updates supersede the paragraphs below where they conflict.

**Frozen `GPU_IEEE_BITS` matrix.** The six-context replay at
`build/gpu-six-context-matrix-frozen-20261001a` is no longer running. It ended
`FAIL`: vanilla Overworld, Nether, End and Terralith each passed 250/250 cases
with independent comparison; Tectonic (34 receipts) and combined (31 receipts)
hit the 25-minute seed-group deadline in their first group, with no mismatch
reported and no independent comparison run. G6 remains open.

**Throughput path.** At the owner's direction, optimization was pulled forward.
A separate fused native-FP64 GPU NOISE path and parallel worldgen steps now
exist and are on by default for qualified routers. Per-chunk digests are
identical to serial vanilla on 8,281 chunks in each of eight contexts (three
vanilla Overworld seeds, Nether, End, Terralith, Tectonic, combined). Timed
NOISE throughput (2026-10-03, after requalification): about 3,330–3,480
chunks/s on vanilla Overworld against about 720 for vanilla, and about
2,340–2,480 on the combined pack against about 490. FULL: about 660 against
about 110 on vanilla, 506 against 64 on the combined pack. The release jar was
also run in an installed NeoForge dedicated server with a matching digest. Scope, method and limits are in
[the throughput evidence](evidence/throughput-fused-gpu.md). This does not
close any G0–G12 gate: it has not run in a client, it is
not `oracle-1211` corpus evidence, and nothing is claimed exact past CARVERS.

The frozen 250-case Terralith run failed after 237 passing device/CPU receipts:
`Long.MAX_VALUE (32,32)` differed by one water/stone state. A retained-shader
probe identified undefined negative signed remainder in the aquifer grid.
The corrected shared floor helpers now pass 20,208 physical output-word checks,
and the failing chunk passes all 98,304 states and ten independent Minecraft
fields. The rebuilt checkpoint passes 327 focused tests, packaging and
architecture checks; the complete new GPU matrix remains open. See
[counterexample and correction](evidence/v0.2-aquifer-integer-floor.md).

The coordinator MODEL campaign passes exactly 500,000 randomized subscriber
requests with one worker/tiny budgets and all final resource counts zero. It
does not stand in for native or Minecraft lifecycle evidence; see
[campaign boundaries and metrics](evidence/v0.2-coordinator-model-campaign.md).
The logical GPU verifier and cancellation-safe provider shutdown changes are
now packaged. Its first actual one-case Minecraft GPU NOISE publication passes
all ten independent fields, with separate validated/committed receipts. The
first vanilla GPU FULL continuation completed but failed on 80 block states,
with the other nine fields matching. All 25 backend NOISE halo results match
2,457,600 original states/250 fields. Older CPU coordinator FULL artifacts
have the same 80 differences; the pre-coordinator inline CPU artifact matches.
The isolated verifier now also dispatches inline; its new one-case CPU FULL
control passes all ten independent fields. GPU FULL and GPU SAVED/fresh-process
reopen now also pass all ten independent fields each on the same frozen inputs,
zero differences, with 25 linked GPU publications per initial run. This is
one vanilla core per endpoint, not the required six-context/216-core campaign.
The current build passes 126 NeoForge tests and root jar/architecture checks.
No production scheduling or TPS is claimed.
The new frozen six-context/1,500-case GPU captured replay is now running
serially at `build/gpu-six-context-matrix-frozen-20261001a`. The first context
has emitted device/CPU receipts; no completed independent context comparison
or full-matrix result is claimed yet. See
[logical verifier](evidence/v0.2-live-gpu-verifier.md).

Structure/ore continuation: guarded shared kernel metadata and bounded ore
graph/division stages correct the nonempty Terralith counterexample. All **50
extreme-seed chunks now match 4,915,200 states and 500 independent fields**,
zero differences. The original owned group timed out after 47 receipts; the
last three were recovered without recompiling, then all fifty were compared
together. This is one seed subset, not the full 250-case context or G6. See
[retained failures, recovery and evidence](evidence/v0.2-beardifier-gpu.md).

The newer host-throughput checkpoint removes redundant raw-request copies and
streams unchanged program-fingerprint bytes instead of building expanded graph
strings. Five focused modules pass 330 tests, none failed/skipped; packaging and
architecture checks pass. Its wide physical structure fixture passes 63,488
raw-bit comparisons again, and four real Terralith chunks match 393,216 states
and 40 independent fields. The combined-mod pair separately passes 196,608
states and 20 independent fields on this same new checkpoint, zero differences.
No measured live TPS or full matrix is promoted; see
[throughput work and remaining debt](evidence/v0.2-host-copy-fingerprint.md).

Bounded blended-noise reduction continuation: the initial combined-mod pair
timed out in native final-fan-in compilation, with no pass artifact. The new
graph-independent reduction keeps main/10 on the staged divider and uses exact
power-of-two scaling for other divisions. It passes 600 physical result checks
and 1,200 intermediate checks, including lazy poisoned branches and partial
tails, with no differences. The new focused checkpoint has 284 passing tests;
package and architecture checks pass. The combined fixture now passes its
seed-0 negative/positive pair: 196,608 states and 20 independent fields, zero
differences, on that new frozen jar. The complete matrix remains open; see
[reduction evidence](evidence/v0.2-shared-blended-reduction.md).

Captured beardifier continuation: the previously rejected Terralith graph
marker now has an integer-carrier emitter. Corrected structure arithmetic and
six-word coordinate loading pass **896 physical raw-bit comparisons** with
inline helpers; the broad out-of-line control instead lost the device and is
not admitted. Isolated candidate stages select the passing policy; old-world
blending still fails closed and the production hook remains disabled. Focused
compiler/runtime/engine/NeoForge checks pass 278 tests and release packaging.
The Terralith seed-0 negative/positive full-chunk pair now passes 196,608 states
and 20 independent fields, zero differences. The subsequent combined-mod
timeout and passing reduction fix are recorded above on their separate jars.
Full-corpus and live TPS qualification remain open; see
[structure evidence and remaining work](evidence/v0.2-beardifier-gpu.md).

Latest continuation: shared End permutation metadata now has a stable shader
ABI. An outer-island negative-coordinate counterexample exposed signed GLSL
remainder drift (887 states); both static/shared End paths are corrected.
The full seed-0 `(-128,-128)`/`(128,128)` pair now passes **131,072 states and
20 independent fields**, with partial batches. Vulkan compute/stage flag
binding is corrected and compilation phase counters are exposed. The pinned
Tectonic GPU preflight additionally exposed missing one-knot spline support;
both emitters now implement it, with modded replay qualification still open.
That preceding three-module checkpoint passed 209 focused tests, none failed/skipped. No full GPU matrix or
live TPS/production-hook claim is added. See
[the counterexample and evidence](evidence/v0.2-shared-end-metadata.md).
The preceding packaged checkpoint additionally passes seed -1 `(-32,-32)` in all three
vanilla dimensions: 229,376 storage states and 30 independent fields, zero
differences. The new graph-independent exact divider passes 7,540 native
comparisons, including independent BigInteger carrier checks. It also produces
the first complete pinned Tectonic GPU witness: seed-0 `(32,32)`, 98,304 states
and ten independent fields with zero differences. The full stack matrix remains
unqualified. A strict 64-MiB/2,048-entry Java module cache now contains compile
reuse without expanding native pipeline retention; negative/positive modded
replay on that newer artifact passes 196,608 states and 20 independent fields,
zero differences; its second case adds one shaderc compilation and 4,450 hits
with about 17 MiB retained and no evictions. This is two-case integration/reuse
evidence, not the complete corpus or live TPS. See
[shared divider evidence](evidence/v0.2-shared-fp64-divider.md).

Latest GPU draft: the previously failing seed-1 Overworld `(32,32)` now matches
all **98,304 blocks** and the independent original-only Minecraft capture
(**10 fields, zero differences**). Marker identity aliasing and an unstaged
spline-coordinate sampler were corrected; shared spline/coordinate DAGs are
retained. This is `GPU_NATIVE_DRAFT`, not exact-profile or live TPS evidence.
The reusable spline kernel also passes the independent comparison; bounded
parser caches reduce the observed fresh-process replay from 2m37s to 2m09s
(single-run timing, not live TPS). Seed-0 also passes all 98,304 states and all
ten independent fields with a 4,095-element partial-tail batch. Shared-table
upload and linear host packing then pass both seeds again in 40–41s fresh
Gradle runs; recorded input is about 130 MB instead of 1.50 GB. These timings
are not a matched benchmark or TPS claim.
The additional seed `-1` counterexample (38 air/stone differences) is now
corrected by draft FP64 comparison containment and passes all 98,304 states
and ten independent fields with a partial-tail batch. General-seed, exact
profile, FULL/SAVED and live GPU qualification remain incomplete. See
[the current witness and limitations](evidence/v0.2-gpu-seed1-fix.md).

Exact-profile continuation: the bounded range-parent regression passes 192
actual-device vectors in both profiles at three batch sizes. The new opt-in
shared blended-noise route, corrected FP32 multiply limb order and generic
root staging now pass seeds -1, 0 and 1 Overworld `(32,32)`:
**98,304/98,304 states each, ten independent fields each, zero differences**.
This supersedes the 133-state failure in the former split reconstruction.
Current arithmetic-only conformance
passes 1,829,120 device comparisons; it is not the release numeric gate.
Neither the completed stage path nor the native-draft witnesses enable the
production hook. See
[exact GPU containment evidence](evidence/v0.2-gpu-exact-overworld.md).

The provider now has a device-only generation API that avoids full CPU terrain
regeneration on each GPU request. Its isolated seed -1 artifact independently
matches all ten Minecraft fields and 98,304 states, while its own execution
receipt correctly records zero CPU comparisons and no parity verdict. Focused
compiler/runtime/NeoForge coverage at that checkpoint was 192 tests, none
failed/skipped; release packaging passed. Broad qualification and live TPS
remain open.

The exact Nether seed-0 `(32,32)` control now passes 65,536 storage states
(32,768 GPU rows plus air tail) and ten independent fields. The new staged
End neighbor/reduction kernel also passes 65,536 storage states and ten
independent fields, superseding the former 14,716-state failure. A far-island
point matches the CPU root bit-for-bit. These are exact one-context witnesses,
not the complete GPU matrix or live qualification.

The persistent executor now reuses fence-completed mapped storage, command
objects, descriptors and fence instead of recreating them per dispatch. A
fresh exact-profile replay remains GPU-vs-CPU exact in all three dimensions:
Overworld uses 10 buffer allocations across 1,388 dispatches, Nether 8 across
150, and End 10 across 218, including partial batches. Two-profile range smoke
uses two allocations across 66 dispatches with zero mismatches. These are
allocation and correctness observations, not a measured live TPS improvement.
The virtual-spline/End-detector integration regression is corrected and logged.

2026-09-30: resident chains now support immutable per-stage metadata suffixes
and bounded intermediate exports with exact descriptor/budget/identity/traffic
accounting. Native suffix/export controls pass 37/7/1 slices and device
recreation. Opt-in normal-noise half chains pass Overworld seed -1 `(32,32)`:
98,304 states and ten independent fields, with 27 completed resident leaves.
The same jar passes Nether/End state and independent controls; those captures
contain no normal-noise leaves and do not count as resident-path evidence.
Full-row attempts timed out before output. The half-chain cold run transferred
fewer bytes but was slower and allocated more buffers, so it remains opt-in.
The original host-staged path is still the default; no live TPS claim or
production-hook admission is added.

The subsequent shared raw/chain pool removes the allocation regression:
411 -> 12 native buffers in the full half-chain Overworld run, 33.58 -> 13.65 s
recorded executor time, still 98,304 states and ten independent fields exact.
The same-size host control remains faster at 12.92 s, so residency stays opt-in.
Native controls additionally pass metadata changes, raw/chain switches,
tight-budget shrink/regrowth and recreation. An explicit larger batch now
accepts up to 16,384 rows; 16,383-row raw/chain partial-tail ABI controls pass.
Default standalone batching remains 4,096 and broad GPU/TPS gates remain open.

The current jar also passes new seed -1 `(-32,-32)` controls in all three
vanilla dimensions at batch 16,383: 229,376 storage states, 30 independent
fields, zero mismatches. The filtered corpus report explicitly denies complete
input coverage. End's new call records 170.21 s including compilation versus
Nether 1.01 s; compiler/pipeline containment is still a real throughput limit,
not a hidden successful TPS gate.

## v0.2 checkpoint

The repository now builds `0.2.0` and contains executable correctness-first contracts across the semantic, CPU, Vulkan, material, spatial, coordinator, oracle and NeoForge configuration/runtime layers. The release jar packages those layers and native initialization remains lazy. Qualified provider attempts now pass through the runtime-owned bounded coordinator, and engine-side spatial requests now select a fixed route before consuming bounded typed sample tiles; the live Minecraft generation hook is still disabled by default. An explicit `worldgennext.prototype.cpuLive=true` launcher flag now exposes the existing CPU candidate through the ordinary NOISE hook for playable draft integration, without treating that path as qualification evidence.

Open mandatory evidence: the independent candidate-paired same-stack oracle qualification beyond the now-complete 1,500-case CPU NOISE corpus, complete Minecraft density/noise/aquifer/ore semantics on every supported route, full qualified integer-carrier terrain execution, real GPU replay, qualified live commit/rollback, the required downstream FULL and SAVED/reopened matrices beyond the one-case witness, terrain-mod GPU matrix and stability campaigns. These are not inferred from focused tests, the 121-artifact CPU-live prototype smoke, or synthetic replay.

Planning update: [v0.2 complete functionality](V0.2-PLAN.md) is in progress. Focused driver-free contracts are executable; the observed v0.1 runtime evidence below is retained and the real release gates remain separate.

**The independent development foundation is built and validated. The opt-in draft CPU-live hook now executes ordinary Minecraft startup and downstream stages, but no qualified accelerated route is enabled by default.** The diagnostic NeoForge mod loads; the supported synthetic compiler/codec/engine contracts are executable and tested. The original GPUWorldGen checkout was not modified by this work.

| Gate | Observed result | Scope |
| --- | --- | --- |
| Fresh Java21 build and CPU suite | **422 tests, 0 failures, 0 errors, 0 skipped** | Ten executable test modules plus the independent oracle module's no-test capture task; includes deterministic schema-1/2 qualification receipt, multi-context admission coverage, the fixed-call blended-noise carrier-control-flow regression, exact compiler-to-oracle blended-noise metadata mapping, strict typed snapshot/root identity validation and the static per-table sampler rewrite regression; Fabric is an explicit deferred frame |
| Architecture and release-jar checks | **PASS** | Pure-module imports, declared dependency scan, packaged core classes, no bundled Minecraft/NeoForge/JUnit/LWJGL/old-mod implementation |
| CPU replay | **1,048/1,048 comparisons, 0 mismatches** | Eight synthetic graphs; zero GPU work |
| Native Vulkan normal-range diagnostic | **917/917 comparisons, 0 mismatches** | Seven graphs on RTX5070Ti; finite fixture raw-bit comparisons only |
| Persistent worldgen Vulkan executor smoke | **PASS: 23 device-produced state IDs across 12 bounded dispatches** | RTX5070Ti; emitted `GPU_IEEE_BITS` density classifier, captured permutation/octave noise, device-side nearest-three aquifer pressure over immutable statuses, a two-word state/fluid-mark ABI, and explicit six-material legacy- and xoroshiro-RNG ore fixtures, each with bounded partial-batch execution; not Minecraft terrain qualification |
| Native integer-carrier conformance | **PASS: 1,829,120 comparisons, 0 mismatches** | RTX5070Ti; shaderc/SPIR-V float-type inspection plus raw `GPU_IEEE_BITS` integer/IEEE/RNG vectors; primitive evidence only |
| Default strict Vulkan check | **FAILED: unsupported capability; 0 comparisons** | RTX driver does not advertise FP64 subnormal preservation; integrated GPU lacks FP64 |
| NeoForge development bootstrap | **11/11 required GameTests passed**, clean shutdown | Main mod registration, core self-test, explicit live-hook rejection, default-off version-pinned hook bypass, rejected-provider teardown, mapped range capture, bound 1.21.1 Overworld/Nether/End 15-root/direct-density smoke, real ProtoChunk rollback, injected mutation-failure restoration, cancellation forwarding and authoritative commit-token preflight |
| Standalone replay launcher | **PASS** | Packaged launcher executes CPU/limited GPU and returns failure for strict unsupported hardware |
| Original-only Minecraft oracle preflight | **PASS: 10/10 cases, 100/100 fields, 0 differences** | Vanilla Overworld NOISE; five signed seeds × two centers; clean original-vs-original processes; not candidate parity |
| CPU candidate NOISE corpus | **PASS: 1,500/1,500 cases, 15,000/15,000 fields, 131,072,000/131,072,000 block states, 0 mismatches** | Opt-in WorldgenNext NeoForge candidate artifacts paired with independent original corpora; 750 vanilla cases plus 250 each for Terralith/Lithostitched, Tectonic/Lithostitched and the combined fixture; CPU-only G4 corpus evidence, not GPU or live qualification |
| Coordinator-backed isolated NOISE wiring smoke | **PASS: one real candidate task, artifact 221,640 bytes, clean shutdown** | Fresh vanilla Overworld server at chunk (32,32); exercises bounded coordinator admission, request-local CPU backend, authoritative commit and endpoint publication; wiring witness only, not same-stack parity or live qualification |
| Demand-driven spatial consumer/service | **PASS: bounded tile split, cache sharing, cancellation and typed partial surface edges** | `TiledSampleConsumer` and `SpatialWorkService` cover exact XYZ/halo windows with bounded in-flight tiles and static CPU/GPU selection; engine contract evidence only, not Minecraft regional sampling |
| Coordinator/operator snapshot | **PASS: schema-1 text/JSON diagnostics** | Queue depth/capacity, active/retained records, reserved/budget bytes, independent work counters, effective hook/config identity and process-local hook decision/completion counters are exposed through `/worldgennext status-json`; telemetry only, not qualification evidence |
| Draft CPU-live integration switch | **PASS: 121 live NOISE artifacts, receipts and committed sidecars; server reached `Done (95.755s)`** | `worldgennext.prototype.cpuLive=true` installs the isolated CPU candidate for an ordinary running server and preserves original downstream stages; disposable process was stopped after readiness; explicit prototype aid only, rejects `GPU_REQUIRED`, and is not G9/G12 evidence; see [prototype evidence](evidence/v0.2-cpu-live-prototype.md) |
| Multi-context qualification admission seam | **PASS: schema-2 aggregate receipt model and exact context allowlist** | Aggregates version-pinned route/ABI/compiler evidence across distinct captured contexts while rejecting duplicates, mixed identities, partial entries and missing request contexts; no corpus is claimed by the model itself |
| Terralith + Lithostitched CPU NOISE slice | **PASS: 250/250 cases, 2,500/2,500 fields, 24,576,000/24,576,000 block states, 0 mismatches** | Terralith 2.6.2 + Lithostitched 1.8.0+beta6, Overworld, five signed seeds × 5×5 squares at both centers; CPU-only partial terrain-mod evidence, not the required six-context/GPU gate; see [evidence](evidence/v0.2-terrain-mods.md) |
| Tectonic + Lithostitched CPU NOISE corpus | **PASS: 250/250 cases, 2,500/2,500 fields, 24,576,000/24,576,000 block states, 0 mismatches** | Tectonic 3.0.26 + Lithostitched 1.8.0+beta6, Overworld, five signed seeds × 5×5 squares at both centers; CPU-only context evidence, not GPU/live qualification; see [evidence](evidence/v0.2-terrain-mods.md) |
| Combined Terralith + Tectonic + Lithostitched CPU NOISE corpus | **PASS: 250/250 cases, 2,500/2,500 fields, 24,576,000/24,576,000 block states, 0 mismatches** | Combined pinned terrain-mod fixture, Overworld, five signed seeds × 5×5 squares at both centers; CPU-only partial terrain-mod evidence, not the required six-context/GPU gate; see [evidence](evidence/v0.2-terrain-mods.md) |
| Dynamic-input candidate capture smoke | **PASS: one real 1.21.1 capture, 5,237-node router, artifact written and clean shutdown** | Worldgen-relevant effective server resources, loaded mod files and captured structure/blend inputs now form the dynamic identity; capture-only, not candidate parity or a live-hook qualification |
| Isolated End GPU candidate parity witness | **PASS: 10/10 fields, 65,536/65,536 block states, 0 differences; 0 CPU-vs-GPU mismatches** | Seed 0, End chunk (-32,-32), RTX 5070 Ti; `GPU_IEEE_BITS` receipt with shader/SPIR-V provenance; one vanilla End case, not the six-context G6 corpus |
| Isolated Nether GPU native-draft parity witness | **DRAFT_PARITY_PASS: 65,536/65,536 blocks, 0 CPU-vs-GPU mismatches** | Seed 0, Nether chunk (32,32), RTX 5070 Ti; real density/aquifer/ore/material device execution via `GPU_NATIVE_DRAFT`; explicitly unqualified because the device reports `preserveDenorm64=false`; see [staging evidence](evidence/v0.2-gpu-candidate-nether.md) |
| Isolated Nether GPU exact candidate parity witness | **PASS: 65,536/65,536 blocks, 0 CPU-vs-GPU mismatches** | Seed 0, Nether chunk (32,32), RTX 5070 Ti; `GPU_IEEE_BITS` one-case receipt using one-octave blended-noise containment; 323 dispatches; not the required multi-context GPU qualification matrix; see [staging evidence](evidence/v0.2-gpu-candidate-nether.md) |
| Overworld GPU direct-density branch diagnostic | **PARTIAL: targeted exact points pass; historical full-chunk result is not promoted** | RTX 5070 Ti, seed 0, Overworld chunk (32,32); the corrected normal-noise wrapper now passes the previously failing targeted points, but the current full branch/material route has not reproduced a receipt under the current emitter/runtime. The retained historical 98,304-value diagnostic remains in the staging record and is intentionally not treated as current qualification evidence; see [staging evidence](evidence/v0.2-gpu-candidate-overworld-staging.md) |
| Overworld staged compiler-containment probe | **PARTIAL: bundled stages executed; no candidate artifact** | RTX 5070 Ti; native-draft point probe executed bundled direct-density stages and later bounded compiler-envelope probes; intentional stops/stall produced no parity receipt and do not close exact Overworld material, FULL/SAVED or live gates |
| Overworld embedded normal-noise GPU containment | **PARTIAL: real upstream one-element branch reached stage 43; no parity or artifact** | RTX 5070 Ti; a fail-closed `wg_node_2139` probe compiled the GPU coordinate, two Perlin-carrier and combine stages, while a real first-branch replay reached the embedded parent and returned `0.25578899322829773` at `(512,-64,512)` before the intentional stop. The zero-child probe returned `0.3602672866443762` on the exact profile; the native-draft group-4 smoke returned finite `0.10744060995659291`, including a GPU-flag-only launch after the capture-gate fix. Removing the stop re-entered the broader native compile envelope; no receipt was produced, so GPU, FULL/SAVED, terrain-mod and live gates remain open; see [staging evidence](evidence/v0.2-gpu-candidate-overworld-staging.md) |
| Overworld full exact GPU retry | **PARTIAL: prior shared route reached the full envelope but split-carrier parity failed; host-staged default remains bounded** | RTX 5070 Ti; the prior shared metadata-backed Perlin shader crossed all 410 planned density stages and reached the material-input boundary in about 4m30 with stable memory around 3.6 GiB, then failed 61,158/98,304 density values with 17,071 sign mismatches. Post-review host-staged retries with the corrected combine/guard and reusable pipeline cache remained in native compilation and were cleanly stopped without parity output or an artifact; one retry still inherited the harness's legacy group-2 value before profile-aware forwarding was fixed, and the correctly forwarded native-draft group-4 replay also remained in the compiler/device envelope. A later static captured-Perlin experiment exceeded the target driver's compile envelope and an explicit no-inline variant lost the device. FULL/SAVED/live gates remain open; see [staging evidence](evidence/v0.2-gpu-candidate-overworld-staging.md) |
| Overworld native-draft group-two full GPU route | **PARTIAL: 394/394 real GPU stages completed; 121/98,304 final state mismatches** | Historical RTX 5070 Ti run; host-staged two-octave Perlin groups plus opt-in device recreation completed the full `wg_node_5` child and GPU aquifer/ore/material stages. It failed closed on `minecraft:stone -> minecraft:water[level=0]` near `(516,45,512)`; no artifact or receipt was written. |
| Overworld native-draft group-one full GPU route | **PARTIAL: 394/394 real GPU stages completed; 24,055/98,304 density mismatches** | Current RTX 5070 Ti run; one-octave host-staged Perlin groups completed the full child and all GPU material stages, then failed the independent density carrier gate with `1,016` sign mismatches, first at `(512,-63,512)`. No artifact or receipt was written; see [staging evidence](evidence/v0.2-gpu-candidate-overworld-staging.md) |
| Device-resident staged GPU chain | **PARTIAL: normal-noise chain reached device execution; resident Nether material row PASS; density scratch FAILED** | RTX 5070 Ti; one command-buffer raw-stage chain carries Perlin intermediates and an opt-in 13-word resident post-density row completed one exact Nether case (`65,536/65,536`, `0` mismatches). The separate opt-in density dependency-row scratch path reached Overworld dispatch but failed `11,634/65,536` on an exact Nether run; it remains disabled. The long FP64-divider chain remains opt-in after a driver queue-submit crash, and the full Overworld material attempt stopped at stage 79 without an artifact; see [Overworld staging](evidence/v0.2-gpu-candidate-overworld-staging.md) and [Nether staging](evidence/v0.2-gpu-candidate-nether.md) |
| Exploratory Overworld/Nether GPU staging | **PARTIAL: exact Nether one-case pass; Overworld remains open** | The direct Overworld branch is full-chunk exact in isolation. The exact one-octave Nether route completed one real 65,536-block density/aquifer/ore/material case with 0 mismatches; the native-draft witness remains unqualified. The first native Overworld attempt hit the 2,048-stage planner cap; a later 4,096-stage diagnostic planned 2,204 stages but stalled in compiler/driver construction without a result. FULL/SAVED, terrain-mod and live-hook gates remain open; see [Overworld staging evidence](evidence/v0.2-gpu-candidate-overworld-staging.md) and [Nether staging evidence](evidence/v0.2-gpu-candidate-nether.md) |
| Original-only FULL endpoint stability | **PASS: 10/10 fields, 0 differences in two fresh processes** | Seed 0, vanilla Overworld chunk (0,0); byte-identical `FULL` snapshots; reference-only boundary smoke, not candidate downstream parity; see [evidence](evidence/v0.2-original-full.md) |
| Original-only FULL/SAVED oracle boundary | **PASS: one FULL-backed SAVED capture reopened in a fresh process; 10/10 fields, 0 differences** | Independent reference-vs-reference smoke with an explicit save barrier; candidate FULL/SAVED parity and the live hook remain open; see [evidence](evidence/v0.2-original-saved.md) |
| CPU candidate FULL/SAVED/reopen witness | **PASS: one far Overworld case; three 10-field comparisons, 0 differences** | Candidate FULL-backed SAVED capture used 25 real candidate NOISE tasks, then reopened in a fresh candidate process; required 216-case matrix, live commit and release gates remain open; see [evidence](evidence/v0.2-candidate-saved.md) |
| Vanilla dimension candidate routing smoke | **PASS: Nether 1/1 and End 1/1, all 10 logical fields, 65,536/65,536 block states per case** | Separate original/candidate server worlds; validates dimension selection plus storage/logical-height handling; not the required six-context corpus |
| Mapped 1.21.1 capture/lowering preflight | **PASS: 15/15 roots in Overworld/Nether/End; raw-bit final-density parity** | One clean runtime seed; Overworld nine points plus Nether and End three points each; candidate chunk/material parity, full context inputs and production lowering remain open |
| Minecraft same-stack parity and generation interception | **QUALIFIED HOOK DISABLED; DRAFT CPU-LIVE SMOKE PASS** | The unqualified prototype route has exercised 121 real startup NOISE requests and original downstream stages; independent live parity, receipt-to-commit linkage, rollback campaign and qualified production admission remain open |
| NOISE / FULL / saved-world performance | **NOT MEASURED** | No v0.1 speedup or chunks/sec claim |
| Installed-jar external launcher, other platforms and remote CI | **NOT RUN** | The executable `testInstalledMod` smoke now requires an explicitly supplied disposable NeoForge template; Windows/Linux CI is configured, but only this Windows host was executed locally |

Current GPU gate update (2026-09-22): the native-draft Overworld candidate now
reaches real GPU density, aquifer, ore and final-material dispatches for the
full `98,304`-block seed-0 chunk `(32,32)`. The best restored baseline differs
from the CPU state result at `62/98,304` blocks: `31` stone-to-air, `29`
stone-to-water and `2` water-to-stone mismatches. The first mismatch is
`(516,8,525)`, where a focused probe identifies an aquifer decision boundary.
This is real GPU prototype evidence, not a parity pass: no candidate artifact,
GPU receipt or qualification admission was produced, and the live hook remains
disabled. The exact barrier-leaf hybrid was tested once, worsened the count to
`101/98,304`, and was reverted. The staged CPU graph oracle is separately
passing; the native-draft material route is still fail-closed.

Exact-aquifer A/B update: the opt-in exact consumer corrected the focused
`(516,8,525)` probe (`[1,80,0,0]` aquifer carrier and water material), but the
full replay regressed to `282/98,304` mismatches (`209` stone-to-water,
`73` stone-to-air). The native density carrier is therefore still the
limiting unqualified input; `exactAquiferStage` remains disabled by default.

Pressure-draft follow-up (2026-09-22): a real RTX 5070 Ti scalar probe at
`(512,-1,526)` reached the pressure branch, returning the expected pressure
base carrier (`7.5`) for CPU density `-0.001301293401027703`. The old coarse
signed carrier was only an exploratory intermediate. The current
`exactAquiferStage=true` source path uses integer similarity numerators and a
four-limb rational comparison against binary64 density, avoiding FP64
multiply/divide in that consumer. Barrier noise and full threshold parity are
still unqualified; the default native route retains its legacy FP64 pressure
consumer.

Two fresh full exact-aquifer attempts were stopped at the target native shader
compiler boundary before dispatch (about eight minutes and six minutes;
roughly 3.7--5.6 GiB resident). They produced no artifact or receipt. The
previous completed exact A/B (`282/98,304`) and best default baseline
(`62/98,304`) remain the valid evidence. Rational/native full retries stopped
before dispatch in the target driver's multi-gigabyte compiler envelope and
produced no new artifact or receipt. The exact route is still diagnostic, the
GPU/FULL/SAVED gates remain open, and live generation remains disabled.

The candidate now fails closed before shaderc/Vulkan compilation when generated
source exceeds `maxShaderSourceChars` (default `900000`; `-1` is reserved for
bounded compiler experiments). This prevents another oversized source from
silently consuming the driver compiler envelope, but it is only containment
evidence and does not create a GPU receipt or parity result.

External barrier-carrier update (2026-09-22): the opt-in
`exactAquiferBarrierInput=true` path now evaluates the captured barrier root in
its own GPU stage and supplies a seven-word aquifer input row. A fresh real-GPU
probe at `(512,-1,526)` compiled the carrier-enabled aquifer module and
returned `[0,0,1,0]`, matching the CPU probe's no-fluid/default-stone decision.
The same carrier was then run through a focused downstream probe with the CPU
density supplied explicitly: barrier, aquifer, ore and final-material stages
all dispatched on the RTX 5070 Ti and returned aquifer `[0,0,1,0]`, ore
`[0,0]`, and final material `[1,0]`. This is downstream GPU ABI evidence, not
qualification: the density was CPU-supplied, the current rational consumer
uses an integer-carrier similarity-scaled barrier contribution, and the full Overworld
density/material route remains open. The same carrier now also runs through
the opt-in device-resident fifteen-word material row as a four-stage chain
(barrier, aquifer, ore, material) on the RTX 5070 Ti. The default route and
live hook are unchanged.

Focused aquifer follow-up (2026-09-22): the exact rational consumer now skips
adding an ineligible zero barrier and divides its bounded mantissa product
before shifting in rounding bits. Real-GPU CPU-oracle sweeps passed `8/8`
densities at `(512,-1,526)` and `5/5` at `(516,8,525)`; the latter exercises
an eligible nonzero barrier. These are diagnostic point tests, not GPU chunk
parity. A full Overworld replay spent `8m33s` inside Vulkan Perlin pipeline
creation before any comparison and was stopped; it produced no artifact or
new mismatch count. Exact GPU NOISE/material and all release gates remain open.

Latest draft GPU witness (2026-09-22): the opt-in shared metadata Perlin
sampler now bounds its loop to populated octave levels. One captured node
matched its CPU semantic value exactly. A full seed-0 vanilla Overworld
`(32,32)` run then narrowed the state gap to `1/98,304`; isolating that point
revealed a forced-`DontInline` pressure-add miscompile. With the compact exact
aquifer stage using its own no-inline policy, a fresh RTX 5070 Ti replay
matched **98,304/98,304 block states with 0 mismatches** and wrote a
`DRAFT_PARITY_PASS` receipt on route `GPU_NATIVE_DRAFT`. This is a real GPU
NOISE/material prototype, not G6 qualification: its native numeric profile
is unqualified, one neighboring pressure threshold still differs by one ULP,
and changed-seed, partial-batch, terrain-mod, FULL/SAVED and live gates remain
open. The production hook stays disabled.

Changed-seed check: seed `1` at the same Overworld chunk with batch `127`
completed device execution but failed `1,424/98,304` state comparisons
(CPU air versus GPU stone). Investigation resumed on 2026-09-29 using
per-stage CPU comparisons at the first mismatch `(512,67,523)`. Diagnostics
now compare every stage coordinate; scalar subtree probes and interpolation
corner probes must keep their different evaluation domains explicit. The
seed-0 receipt remains a one-case witness. See the [cleanup log](v0.2/DRAFT-CLEANUP.md).

Latest GPU prototype update (2026-09-22): native-draft interpolation children
use the wider stage envelope by default. The captured `wg_node_5` plan was
reduced from `2,204` aggressive stages to `394` wide-envelope stages over
`1,225` corners. A one-octave native-draft replay completed all `394/394`
child stages and GPU aquifer/ore/material dispatches for `98,304` blocks, then
failed the density carrier gate at `24,055` values with `1,016` sign
mismatches. The first mismatch is `(512,-63,512)`; the saved child-corner
diagnostic identifies the fallback `wg_node_2150` subtree as divergent while
the `wg_node_2101` branch agrees. No artifact or receipt was produced. This
is complete real-GPU containment evidence, not G6 qualification.

The strict GPU failure remains visible in its own report. The normal-range diagnostic has a distinct corpus/backend and cannot promote a limited result into the strict gate. Explicit SPIR-V floating-point mode qualification also remains open. The next compiler/runtime phase must prove intermediate numeric domains or provide an exact supported route for hardware that lacks a required mode.

Later GPU update (2026-09-22): the group-two native-draft route completed the
full `394`-stage Overworld child and all downstream GPU material stages over
`98,304` blocks. The independent comparison found `121` water-vs-stone state
mismatches, so the run is a real GPU execution witness but not a parity pass;
the failure sidecar and stage trace are retained. Native-draft group size now
defaults to `2` because group four stalled in the target driver's compiler
envelope. No Overworld GPU receipt, FULL/SAVED result, terrain-mod result or
live qualification is claimed.

A focused follow-up traced the first mismatch `(516,45,512)` to density:
the CPU carrier is `+0.08588803299314993` while native-draft GPU produced
`-4.785197563648143E-4` (`0x3fb5fcc214d2c6ee` versus
`0xbf3f5c3ab43e5ff9`). The aquifer-only hypothesis is rejected. An exact
IEEE one-point retry remained in the native compiler envelope for roughly
thirteen minutes and was stopped without a result; the next required step is
per-stage carrier isolation, not another uninstrumented full replay.

Latest GPU isolation (2026-09-22): at `(512,-63,512)`, native staged
`wg_node_4` returned `0.11428212498167178` while the CPU interpolation oracle
returned `0.1139361094199007`. The new bounded child-corner dump reports GPU
`wg_node_5` values
`[0.1171875,0.1171875,0.09394449985337432,0.10355379072090688,0.1171875,0.1171875,0.09275941305005912,0.0961675267444693]`
versus the CPU
`[0.1171875,0.1171875,0.0911763753592057,0.10465722094930609,0.1171875,0.1171875,0.09313349376198948,0.09732175390361061]`.
At `(512,-56,512)`, `wg_node_2101` agrees while `wg_node_2150` differs
(`0.047458499560122935` GPU versus `0.03915412607761706` CPU). Exact-parent
only interpolation did not change the value, and the exact `wg_node_215*`
island again expanded into the stopped exact compiler envelope. The native
default remains one-octave normal-noise modules and containment-only; no
Overworld artifact or receipt is promoted.

The staged exact fallback-root follow-up used
`debugDensityStageRoot=wg_node_2150` at `(512,-56,512)` with an exact selector
and no-inline escape. It remained CPU-bound in per-stage shader compaction for
about 15 minutes, peaked around 5.6 GiB, and was stopped before reaching the
selected root. No value, artifact, receipt, or parity result was produced;
this is compiler-envelope evidence only and does not change any gate.

The new seeded parent diagnostic then executed `wg_node_2150` on the RTX 5070
Ti with explicit CPU child carriers and returned
`0.03915412607761706`, exactly matching the CPU parent value. This confirms
the parent max/threshold operator on the GPU but intentionally does not count
as child generation, density parity, a candidate artifact, or a qualification
receipt.

The follow-up diagnostic now uses compiler-owned semantic fingerprints because
the numeric `wg_node_<id>` names are capture-local. A fresh native-draft probe
selected
`semantic:e0023b082cc7c7b494ebf6f62a0df74f9cdfcbdb17f7c219c935382ff55cb060`,
resolved it to the current run's parent, seeded its CPU-derived children, and
reported identical GPU and CPU semantic-root values
(`0.03915412607761706`). This improves repeatability of parent isolation only;
the child closure, full density parity, artifact, receipt and live/GPU gates
remain open.

The opt-in recursive GPU-child containment route now has a 4,096-stage limit,
per-stage pipeline reclaim, and no large-capture compaction cache. A fresh
one-point probe of the unresolved `30e1…` semantic child still reached about
7.1 GiB resident before the first child completed and was stopped without a
value or artifact. This is compiler/graph-envelope evidence only; subgraph
extraction is the next cleanup and no GPU gate is promoted.

An explicitly unqualified `debugDensityStageRootGpuChildrenNativeNoise=true`
escape hatch now keeps recursive normal-noise leaves on Vulkan in the native
profile, avoiding the exact integer helper closure during compiler-envelope
exploration. It does not change the ordinary route or promote parity.

A native-leaf retry held about 3.3–3.4 GiB resident for roughly nine minutes
without a completed stage or value before being stopped. This is improved
compiler-envelope containment only; Overworld GPU parity and all release
gates remain open.

The recursive diagnostic now has a compiler-owned semantic carrier extractor:
pure parent nodes are emitted as minimal exact shaders over typed two-word
child rows, with FP32 conversion and explicit fallback for interpolation,
spline and structure-dependent nodes. A real one-point probe reached Shaderc
through this new path, but the recursive exact closure still stalled in the
driver compiler at about 5.3 GiB resident / 6.8 GiB private. The separate
semantic captured-noise leaf experiment reached about 4.4 GiB resident /
8.2 GiB private without a value. Both runs were stopped without artifacts or
receipts; this is containment evidence only and does not promote any GPU gate.

The standalone semantic Noise probe was then corrected out of the default
containment path after it returned an incorrect finite `0.0` for
`minecraft:cave_layer`. Captured Noise leaves now use the existing exact
two-half Perlin route; a fresh one-point RTX 5070 Ti probe returned
`-0.02575933967591109`, exactly matching the CPU semantic leaf value. The
recursive path also recognizes embedded normal-noise parents and routes them
through staged coordinate/Perlin/combine modules. That change advanced the
same parent probe into repeated embedded modules, but it still reached about
5.5 GiB resident in the driver compiler before a selected-root value, so no
Overworld density/material artifact or GPU qualification gate is promoted.

The follow-up carrier retry enabled device recreation every four completed
stages, with a 512-stage cap and 180-second route budget. It still stalled
inside source compaction/driver compilation before a selected-root value,
reaching roughly 5.9 GiB resident in about five minutes. The elapsed guard and
recreation cadence run only between completed stages, so they do not bound a
single native compile; no GPU gate, artifact or receipt is promoted.

A second retry also enabled device recreation between the two normal-noise
halves and after every completed recursive root. Resident memory oscillated
between roughly 4.0 and 5.3 GiB, but the run still stayed in the embedded
parent sequence for about five minutes without a selected-root value. This is
an improved containment signal only; it does not close the Overworld GPU gate.

The metadata-driven shared-Perlin prototype subsequently replaced per-leaf
captured shader compilation with one cached generic sampler. Recursive probes
advanced to `wg_node_343` before a 180-second budget and `wg_node_1321` before
a 1,024-stage ceiling, holding roughly 3.4–4.5 GiB resident. The ordinary
planner then reached the selected semantic root (`wg_node_2150`) in 11m31s,
but returned GPU `0.045047703014439194` versus CPU semantic-root
`0.03915412607761706` and CPU full `0.029168161157509254`. It failed closed
without a candidate artifact or receipt. This is the first bounded full-planner
progress signal for the recursive route, not GPU parity. The generic row ABI
has since been widened from 77 to 82 words per Perlin level to preserve
`yScale`, `yMax` and `smear`; Java 21 compilation passed, but the corrected
route still has no focused GPU parity result. The first rerun found captured
`uvec2(0u)` scalar constructors; the parser now applies GLSL scalar
replication. A fresh probe reached the 34,475-character generic sampler and
entered the driver compiler at about 4.8 GiB resident before being stopped
without a value. GPU/FULL/SAVED/live gates remain open.
The explicit unrolled fallback reached a comparable 34,800-character sampler
and about 5.1 GiB before the same compiler envelope, so it remains selectable
for future driver work but is not a GPU result.

The P05 native lifecycle follow-ups now have bounded lost-device quarantine,
failure diagnostics and a bounded persistent native-pipeline LRU. The native
smoke and lifecycle checks prove those focused contracts, but they do not
close the still-open full Minecraft terrain/GPU qualification gates.

## What is implemented

The semantic language has constants, coordinates, ordered addition/multiplication, lazy ranges and explicit interpolation boundaries. The CPU compiler prebinds callbacks; it does not emit JVM bytecode yet. The independent interpreter, analytic formulas and raw-bit golden tests challenge the compiler. Captured marker evaluation now preserves Minecraft's point-boundary transparency, interpolation-cell CacheOnce scope, last-column Cache2D lifecycle and finite FlatCache fallback outside the owning NoiseChunk range, while the CPU Beardifier reproduces Minecraft's precomputed float kernel and exact deep-dark boundary constants. The strict captured CPU entry point rejects incomplete registry/RNG/noise identities instead of silently switching to fixture sampling. Vanilla aquifer pressure comparisons, lazy ore-gap evaluation, raw-ore chance ordering, zero-state Xoroshiro canonicalization, signed Perlin octave scaling and Minecraft floor behavior are covered on both CPU and shader paths. Immutable interpreter/noise/beardifier evaluators are reused across captured requests without sharing request caches, and a bounded pinned-Tectonic replay guards that allocation cleanup. Blender height samples now preserve direct source-section precedence, and BlendAlpha/BlendOffset are explicit typed nodes with scalar CPU evaluation and an integer-carrier shader table; unsupported GPU production admission for blended worlds remains fail-closed. The candidate material scope exposes both NOISE-owned heightmap families with bounded column results and uses loader-captured block-state traits for heightmap predicates instead of name-based non-air shortcuts. Vulkan emits full GLSL450, including opt-in captured-cell aquifer statuses with device-side nearest-three pressure, a disabled-aquifer global-fluid picker, a two-word state/fluid-mark dense output, and captured six-material legacy- and xoroshiro-ore branches. Captured Perlin permutation resources now use a packed four-byte-per-uint `[64]` ABI at the shared on-device sampler boundary, reducing staged source/resource duplication without moving samples to the CPU. Vulkan performs bounded native dispatch through a separate replay application. The loader capture boundary now fingerprints worldgen-relevant effective server resources and loaded mod files alongside captured structure/blend inputs; failures reject capture instead of producing an empty dynamic identity. Full Minecraft aquifer/material parity remains open.

The material codec supports immutable uniform, palette and dense fixture sections, validates binary input and checks logical metadata. Spatial and engine modules model budgets, shared producer leases, asynchronous sample handoff, demand-driven bounded tile windows, static route selection, epoch changes, cancellation, stale-work invalidation and a single atomic commit decision. Runtime composition now owns one bounded coordinator and invalidates it with server/world/device lifecycle events; qualified CPU/GPU providers bind request-local backend and commit handlers through that queue, while the coordinator retains active reservations until backend callbacks release them. The disabled-by-default loader integration still composes capture, backend computation, stale-generation recheck, authoritative commit and downstream continuation without holding the runtime monitor during computation. CPU candidate capture is split from pure materialization, and the version-pinned ownership adapter checks the exact BIOMES holder target before commit. A provider cannot be registered without a complete independent qualification receipt; generated receipts are deterministic and hash-checked, and live admission also matches the result ABI/compiler identity to the provider. Isolated GPU candidate receipts carry request-result shader/SPIR-V provenance plus executor-lifetime dispatch telemetry, and candidate artifacts publish through temporary files before their final paths become visible. The integration still does not enable a production provider by default or prove that caller-labelled GPU work actually ran on a GPU. Payload budgets exclude temporary JVM copies/object overhead, and the model retains bounded task tombstones until epoch advance. Endpoint contracts now require positive comparison/timing evidence, unique coverage keys, requested cold/warm run coverage, per-case GPU receipts, exact SAVED identities and explicit saved/reopen completion; compatibility registration rejects conflicting route replacement. A post-attempt qualified-route failure cannot fall through to original generation without a restoration proof.

The 0.2.0 NeoForge jar packages the pure core modules and persistent-runtime classes, and provides operator status/self-test/default-config commands. Native initialization is lazy. The version-pinned server Mixin boundary is now packaged and proven to bypass vanilla when no independently qualified provider is registered; an explicitly configured provider now also requires a deterministic schema-versioned receipt whose source artifact hash and ABI/compiler identity match on disk and in the provider. Config parsing accepts the operator aliases `AUTO` and `AUTO-SUPPORTED` while serializing the canonical `AUTO_SUPPORTED` value. The new baseline accumulator/JSON writer preserves measured endpoint identity and does not generate measurements. There is still no enabled production generation provider. Fabric jar tasks are disabled.

## Artifacts and next steps

Build outputs:

- `neoforge-1211/build/libs/worldgennext-neoforge-1.21.1-0.2.0.jar` — correctness-first checkpoint mod.
- `oracle-and-replay/build/libs/worldgennext-corpus-api-0.2.0.jar` — isolated pure corpus interchange artifact.
- `oracle-1211/build/libs/worldgennext-oracle-1.21.1-0.2.0.jar` — independent original-only capture module; it is not the candidate product.
- `oracle-and-replay/build/distributions/oracle-and-replay-0.2.0.zip` — replay application and its runtime dependencies.
- `build/distributions/worldgennext-0.2.0-sources.zip` — source/configuration/documentation archive.
- `build/reports/tests/all/index.html` — aggregate CPU report.

Artifact identity checkpoint (2026-09-14, SHA-256): NeoForge jar
`82E70A9BB357005B10B9376BA97AD27C8EA295995ECF157196295F596365AEA6`,
corpus API jar
`537BDE06F86287D3E51CA41857900D51E02A5A17D92FD12470386C4C5AA009B7`,
oracle jar
`2595ACDEB060B280CD67E6307B2F9FC69200DEFE2EBD0B3D1199DCBC45309D4D`,
and replay distribution
`C4F1FA0F511EF3C9D9687EE2727E40F6A57192114E1911E1A8728FAF17C2E439`.


See [v0.2 original oracle evidence](evidence/v0.2-original-oracle.md), [validation evidence](evidence/v0.1-validation.md), [file inventory](FILE_MAP.md), [planned implementation files](IMPLEMENTATION_MAP.md), [test expansion plan](TEST_PLAN.md) and [roadmap](ROADMAP.md). The old performance evidence and conditional targets are retained in [the design proposal](design/PROPOSAL.md), not relabelled as measurements of this foundation.

This is a new local Git repository with no remote and no initial commit; Git author identity was not configured. The current source archive is the portable v0.2 checkpoint, with historical v0.1 evidence retained separately.
