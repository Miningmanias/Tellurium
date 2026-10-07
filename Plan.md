# Tellurium implementation plan

2026-10-02 owner decision: throughput work was pulled forward ahead of the
remaining v0.2 gates. The mod now ships a fused native-FP64 GPU NOISE path
(default-on only for routers on a qualified list, with per-chunk fallback to
the original generator) and runs the vanilla structure, surface, carver and
feature steps off the single worldgen mailbox. Measured results, the digest
method and what is not yet qualified are in
[docs/evidence/throughput-fused-gpu.md](docs/evidence/throughput-fused-gpu.md).
The `GPU_IEEE_BITS` route and gates G0–G12 below are unchanged and still open;
the frozen six-context matrix ended FAIL on Tectonic/combined time budgets.

Current runtime: **0.2.0 correctness-first checkpoint**. The v0.2 plan is implemented in focused pure/runtime slices; real same-stack Minecraft/GPU/FULL/SAVED qualification remains explicitly open. Independent repository; no CUDA.

2026-10-01 continuation: the first GPU FULL verifier failed on 80 blocks
despite 25 independently matching backend NOISE chunks. The isolated verifier
now dispatches inline as well as computing/committing inline. A new one-case
CPU FULL control passes all ten original fields. GPU FULL and GPU SAVED/fresh
reopen now also pass all ten fields each for the same one-core vanilla case
on the same frozen inputs. Root jar/architecture checks and 126 NeoForge tests
pass. Production scheduling, full matrices and TPS remain unqualified; see
[verifier evidence](docs/evidence/v0.2-live-gpu-verifier.md).

Latest 2026-09-30: the frozen Terralith campaign stopped after 237 receipts on
a one-state `Long.MAX_VALUE (32,32)` aquifer mismatch. Corrected shared signed
floor helpers now reproduce that full chunk and all ten independent fields;
20,208 physical helper output checks pass. The newly rebuilt checkpoint passes
327 focused tests and packages the isolated GPU verifier/shutdown fixes.
The coordinator MODEL campaign also passes exactly 500,000 requests with all
tracked resources drained. Neither closes the full GPU/live/downstream gates.
See [aquifer correction](docs/evidence/v0.2-aquifer-integer-floor.md) and
[model campaign](docs/evidence/v0.2-coordinator-model-campaign.md).

Nonempty structure/ore continuation: guarded shared kernel metadata and bounded
ore graph/division stages now pass the Terralith `Long.MIN_VALUE` `(30,30)`
counterexample (98,304 states, ten independent fields, zero differences).
That checkpoint's 50-case seed subset now independently matches 4,915,200
states and 500 fields after bounded recovery, with zero differences. The newer
copy/fingerprint checkpoint passes 330 focused tests and four actual chunks;
neither establishes full context/live/TPS qualification. Retained failures,
partial coverage and cleanup are in [the evidence](docs/evidence/v0.2-beardifier-gpu.md).

Bounded blended reduction now removes the combined fixture's monolithic
native-compilation timeout. Its new frozen jar passes the seed-0 combined-mod
negative/positive pair (196,608 states, 20 independent fields, zero differences),
plus 600 physical final and 1,200 intermediate primitive checks. Full scope and
live-hook boundaries are unchanged; see [reduction evidence](docs/evidence/v0.2-shared-blended-reduction.md).

Captured beardifier continuation: 896 exact physical primitive comparisons
pass with inline helpers after fixing inverse-square-root order and combined
row stride. The new packaged snapshot independently passes the Terralith
seed-0 negative/positive pair (196,608 states, 20 fields, zero differences).
Combined-mod replay and nonempty-structure coverage remain open; no live hook
or TPS gate is promoted. See [structure evidence](docs/evidence/v0.2-beardifier-gpu.md).

2026-09-30 continuation: shared End metadata and corrected Java signed
remainders now pass the independent outer-island positive/negative pair
(131,072 states, 20 fields). Vulkan compute flags and compilation counters are
corrected. Pinned Tectonic exposes one-knot splines; regular/shared emitters
now support their linear extension. The shared exact divider now passes the
first full Tectonic `(32,32)` GPU witness (98,304 states, ten independent fields,
zero differences), plus 7,540 primitive device comparisons. Module caching is
now bounded by both entries and 64 MiB; the full modded corpus remains open.
The new-artifact Tectonic negative/positive pair additionally passes 196,608
states and 20 independent fields; its second case needs one new shaderc
compilation. These are isolated witnesses/reuse observations, not live TPS.
See [new evidence](docs/evidence/v0.2-shared-end-metadata.md). The complete
mandatory scope and disabled-hook qualification boundary remain unchanged.

Exact GPU baseline (2026-09-29): vanilla Overworld seeds -1, 0 and 1 at
`(32,32)` pass all 98,304 GPU-produced states and ten independent Minecraft
NOISE fields on `GPU_IEEE_BITS`. Seed-0 Nether and End pass 65,536 storage
states and ten fields each, including partial batches and explicit air tails.
GPU generation no longer duplicates complete CPU terrain; replay still compares
it. Bounded fence-safe dispatch reuse removes per-dispatch allocation churn.
These are isolated exact witnesses, not the GPU corpus, live TPS or release.
See [current exact evidence](docs/evidence/v0.2-gpu-exact-overworld.md); earlier
failures below are preserved history, not the latest baseline.

The 2026-09-30 resident-chain draft adds immutable metadata suffixes and bounded
intermediate exports. Coordinate-to-sampler half chains pass one complete
Overworld chunk and the independent ten-field comparison. The full resident
row still exceeds the driver compile envelope; the half-chain cold run is
initially slower than the prior host-staged witness. Shared raw/chain resource
pooling subsequently cuts that experiment from 411 buffer allocations to 12,
preserving the independent comparison. It stays opt-in: the same-size host
control is still faster. Explicit batches up to 16,384 rows are now supported,
with the existing 4,096 standalone default retained. Qualification and
matched throughput remain open, and performance experiments do not redefine
the complete v0.2 scope below.

This file is authoritative for milestone scope and implemented status. The detailed target is [docs/V0.2-PLAN.md](docs/V0.2-PLAN.md), with its [file/API map](docs/v0.2/FILES.md), [tests](docs/v0.2/TESTS.md), [work packages](docs/v0.2/WORK_PACKAGES.json) and [acceptance manifest](test-manifest/v0.2-acceptance.json). These documents are implementation specifications, not execution evidence.

The [historical proposal](docs/design/PROPOSAL.md) remains design provenance. Its earlier v0.2–v0.5 functional split and early scheduler-fork sequence are superseded by this plan. Its performance estimates remain hypotheses.

## v0.2 scope and decisions

- Deliver the full usable NeoForge 1.21.1 product: independent same-stack oracle, complete typed frontend, owned CPU and real Vulkan NOISE generation, exact material/metadata output, bounded coordination/resources, live application, original downstream stages, FULL and saved/reopened worlds, configuration and installed-jar qualification.
- Retain Minecraft's holder/ticket/status machinery as the sole chunk-state authority. Tellurium coordinates only its subordinate work. No second holder scheduler or required C2ME integration.
- Implement the `GPU_IEEE_BITS` numerical route and qualify its integer-based FP32/FP64 helpers before real GPU worldgen. The v0.1 normal-range diagnostic does not qualify arbitrary floating-point arithmetic.
- Require vanilla Overworld/Nether/End and pinned Terralith, Tectonic/Lithostitched and combined contexts. Required GPU NOISE cannot be replaced by CPU fallback and still pass. Original CPU surface/carvers/features/light/spawning/save are planned stage owners.
- Target Windows x64/Java 21/RTX 5070 Ti first, with CPU operation and Linux build/test/native-packaging automation. Other GPUs/platforms need separate evidence. Fabric remains a later adapter, following the existing NeoForge-first scope.
- Defer optimization to v0.3: no autotuning, sparse proof skipping, fusion/workgroup search, native-math substitution without qualification, scheduler replacement or performance-floor gate in v0.2.

The checkpoint implements the shared P00/P02/P03/P05/P07/P08 contracts, typed CPU primitives, result ABI, oracle comparator, persistent-runtime model, configuration and focused Gradle tasks. P01 now has an independent original-only capture process, canonical snapshots, a 10-case NOISE preflight and a separate two-process FULL endpoint stability smoke; the opt-in CPU candidate now passes the complete 1,500-case vanilla/terrain-mod six-context NOISE corpus across the required 5×5 squares, comparing 15,000 fields and 131,072,000 block states with zero mismatches. Complete P06/P09/P10/P11/P12 evidence, qualified device traffic and loader mutation qualification remain open. The draft GPU path now has static fixed-call blended-noise groups, per-table captured-noise sampler specialization and a corrected carrier-write branch: one diagnostic point completed all 20 fan-out groups and device fan-in with finite carriers, and the corrected 40-sample follow-up matched the CPU blended-noise oracle exactly at `-0.2805788967238936`; its compact same-stack oracle still returned `1.0`, so full ordinary parity remains open. An earlier split-normal-noise ordinary attempt stalled in native compilation; the default graph-stage compiler unit now completes the one-point stage-2 dispatch, but the complete material path remains unverified. Nether's one-branch density root is now admitted by the same staged planner, but the RTX 5070 Ti native compiler did not complete its captured 64-octave workload in bounded dense or staged probes. P09 now has a version-pinned default-off Mixin seam, an opt-in unqualified CPU-live prototype that reached server readiness with 121 committed startup artifacts, evidence-gated qualified-provider registration, result-target validation, authoritative server-mailbox completion and commit composition. The qualified production hook stays disabled until isolated CPU/GPU output qualification. Frozen v0.1 evidence is retained unchanged.

## v0.1 acceptance

- A reproducible Java21 multi-module Gradle build, CPU suite and packaged artifacts.
- Real tested implementations of a small semantic expression language, independent interpreter, prebound CPU compiler, and lazy GLSL emitter. Explicit interpolation boundaries and negative coordinates are covered. These are synthetic primitives, not Minecraft's complete density/noise stack.
- Immutable uniform/dense section codecs, logical checksums and malformed-input rejection; budget, tile ownership, epoch/cancellation/commit-once engine models.
- A runnable replay application with explicit synthetic identity, actual comparison counts and failed verdicts on mismatch/incomplete coverage.
- A headless Vulkan capability probe, shader compilation and bounded native compute smoke when the host supports it. GPU-required checks must fail rather than silently pass through CPU.
- A loadable NeoForge1.21.1 diagnostics jar with the project's ID and status. Generation interception remains absent until an independent Minecraft oracle qualifies the frontend.
- An explicit Fabric adapter frame and file/module roadmap. Future implementations are documented contracts, not success-returning stubs or skipped-test claims.
- Source inventory, test layers, CI, licensing/provenance, startup commands and measured validation results on disk.

## Implemented module dependencies

semantic-core is independent. compiler-jvm/compiler-vulkan/frontend depend on semantic-core. material-codec/spatial-data depend on semantic-core. chunk-engine uses semantic/material/spatial. runtime-vulkan uses semantic/compiler-vulkan and LWJGL. oracle-and-replay composes these modules. oracle-1211 is a separate original-only Minecraft capture process with no candidate dependency. NeoForge packages the pure modules it uses; Fabric packaging is deferred. No dependency on GPUWorldGen or its directories.

The production dependency boundary remains planned; the original-only oracle classpath boundary is now implemented by `oracle-1211` and the pure `corpusApi` artifact. The mapped 1.21.1 reader now captures all 15 bound vanilla Overworld, Nether and End router roots and directly replays `finalDensity` with raw-bit parity in each dimension. An opt-in candidate artifact producer now selects those vanilla and pinned terrain-mod contexts, preserves storage height separately from the logical noise range, and has a complete CPU NOISE corpus witness; qualified GPU execution, production live mutation and downstream FULL/SAVED qualification remain open.

## Following milestones

v0.2: complete supported product and correctness/lifecycle/packaging qualification, with baseline measurements and no speedup requirement.
v0.3: optimize against the frozen v0.2 baseline while retaining all correctness gates; choose changes by measured endpoint costs and matched controls.
Later: Fabric and additional hardware/platforms, wider mod compatibility, optional GPU stage expansion or alternate APIs. Each needs its own acceptance evidence.

## Current execution status

The frozen v0.1 foundation remains regression evidence. The current build also packages the v0.2 contract/runtime checkpoint and focused tests, but does not claim complete Minecraft parity, qualified GPU terrain, qualified live interception, FULL completion, the required FULL/SAVED matrix or release qualification. The unqualified CPU-live prototype has a separate wiring witness; one candidate SAVED/reopen witness exists and is documented separately; neither is the complete gate. See [docs/STATUS.md](docs/STATUS.md), [docs/CONFIGURATION.md](docs/CONFIGURATION.md) and [validation evidence](docs/evidence/v0.1-validation.md).
