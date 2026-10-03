# v0.2 test and acceptance plan

Focused driver-free contract checks, the isolated original-only P01 capture slice,
and the opt-in integer-carrier primitive device campaign are implemented in the
0.2.0 checkpoint. The complete candidate matrix, graph-level GPU replay,
live-loader, FULL, SAVED/reopened and long-run rows below remain open. A task
name never implies a passed gate. [V0.2-PLAN.md](../V0.2-PLAN.md) controls
scope; the [acceptance manifest](../../test-manifest/v0.2-acceptance.json)
records machine-readable expectations.

## Evidence rules

Each run records source/jar hashes, exact dependency/config/datapack hashes, JDK/OS/device/driver, numerical profile, world seed, dimension, coordinates, endpoint and comparison policy. Persist expected and actual case sets, field coverage, full mismatch details, actual GPU receipts, logs and replay commands. `NOT_RUN`, `UNSUPPORTED` and `FAILED` are different outcomes. None satisfies a required gate.

The original capture helper is separate from the candidate product. First run original against original in isolated processes. Then compare the candidate CPU and GPU routes against that independent same-stack result. Never use the candidate interpreter to generate the decisive expected Minecraft output. Gate manifests identify reference/candidate directories before launch and reject overlap.

Every comparison must see a nonempty expected set, exactly one result per expected case, and the full required field set. Missing, duplicate, stale-context and unrecognized-schema records fail. Intentionally mutate each field family and remove a case to prove the comparator fails for the right reason. Hash agreement alone is insufficient.

## Gate sequence and proposed commands

Task names are an implementation specification. The P01 original-only command-line contract is executable; the full G1 task still requires candidate/process isolation evidence and the complete release corpus. Existing `test` remains the fast driver-free regression entry point; heavy/native suites must never silently join it.

The executed P01 slice is reproducible with two clean runs of `scripts/capture-original-corpus.ps1` followed by `:oracle-and-replay:run --args='compare-corpus ...'`. The initial preflight covers 10 vanilla Overworld NOISE cases (five signed seeds at two centers), compares 100 fields, and passed original-vs-original determinism. An expanded repeat control covers 750 vanilla cases across Overworld, Nether and End in 5×5 squares and compares 7,500 fields with zero differences. The generated-capture hashes and limitations are recorded in [v0.2-original-oracle.md](../evidence/v0.2-original-oracle.md). Neither control counts the 1,500-case six-context corpus or candidate parity.

The opt-in `scripts/replay-minecraft-noise-cpu.ps1` replay paired the 750 independent vanilla cases with WorldgenNext CPU candidate artifacts. It compared 7,500 fields and 57,344,000 block states with zero mismatches. The Terralith, Tectonic and combined pinned terrain-mod replays add 750 cases, 7,500 fields and 73,728,000 block states with zero mismatches. The candidate CPU corpus therefore now covers all 1,500 required six-context NOISE cases; the remaining GPU, live, FULL and SAVED/reopened requirements are documented in [v0.2-cpu-candidate.md](../evidence/v0.2-cpu-candidate.md) and [v0.2-terrain-mods.md](../evidence/v0.2-terrain-mods.md).

A separate pinned Terralith 2.6.2 + Lithostitched 1.8.0+beta6 Overworld run
captured and paired 250 `NOISE` cases (5×5 at both required centers across
five signed seeds) with 2,500 fields and 24,576,000 block states equal. The
Tectonic 3.0.26 + Lithostitched expansion then completed the same 250-case
grid, also with 2,500 fields and 24,576,000 block states equal. The combined
Terralith + Tectonic + Lithostitched fixture has a separate completed
250-case Overworld grid. Across the three terrain-mod contexts, the candidate
CPU total is 750 cases, 7,500 fields and 73,728,000 block states with zero
mismatches. Together with vanilla, the six-context CPU NOISE total is now
1,500 cases and 15,000 fields. GPU and downstream gates remain open.

After a bounded CPU hot-path cleanup that reuses the immutable noise/beardifier
evaluators and the request interpreter, one fresh pinned-Tectonic case
(`seed=-1`, chunk `(-30,-30)`) was replayed independently and compared equal:
65,536/65,536 block states and all 10 fields, with zero mismatches. The
follow-up report is `build/cpu-candidates/v02-tectonic-single-20260913/replay-report.json`
(SHA-256
`001F627C068EE6F53299364008149FCFD312AB4D79C5A3D2844EE888985A444E`). This
is a bounded performance/regression witness, not an expansion of the 10-case
smoke or the required 250-case context.

The copied fixture jars can be checked against the pinned terrain and nested
dependency bytes with the opt-in command below. The directory is a build
fixture and is not silently downloaded or created by the ordinary build:

```powershell
.\gradlew.bat `
  -Dworldgennext.terrainModDirectory=build/terrain-mods/combined-terralith-tectonic-lithostitched-20260913 `
  verifyTerrainModLock --no-daemon
```

The reference-only `scripts/capture-original-saved.ps1` smoke exercises the
logical SAVED boundary with two clean `oracle-1211` processes. The first
process reaches FULL, completes the explicit save barrier and captures the
post-barrier state; the second reopens the same world and is compared across
all ten fields. This proves only the original serializer/reopen harness. It
does not qualify a WorldgenNext candidate, live commit, or saved-world parity.

The opt-in `scripts/capture-candidate-saved.ps1` witness then runs the same
logical endpoint through two clean WorldgenNext candidate processes. The
first run requires real candidate NOISE work and saves the FULL result; the
second reopens that candidate world with no new-NOISE requirement. Candidate
expected, candidate reopened and the independent original expected snapshots
were each compared across all ten fields with zero differences. This is one
vanilla Overworld case only; the required 216-case FULL/SAVED matrix, live
commit and release qualification remain open. See
[v0.2 candidate SAVED evidence](../evidence/v0.2-candidate-saved.md).

The reference-only `scripts/capture-original-full.ps1` smoke separately
requests `ChunkStatus.FULL` in two clean `oracle-1211` processes and compares
the same ten fields before any save/reopen claim. The executed seed-0
Overworld case was byte-identical with 10/10 fields equal; see
[v0.2-original-full.md](../evidence/v0.2-original-full.md). This proves only
same-stack FULL endpoint stability and does not qualify a candidate route.

The draft multi-case downstream runner is
`scripts/replay-minecraft-logical.ps1`. It consumes a corpus captured with
`capture-original-corpus.ps1 -Endpoint FULL` or `-Endpoint SAVED`, runs the
candidate in bounded per-seed batches and compares all ten logical fields.
`SAVED -Reopen` reuses each batch's world in a second candidate process and
can compare against a separately captured original reopen corpus. This is a
runner contract and remains verification-only until the required 216-case
matrix has been executed.

The loader-side P02/P09 preflight is separately exercised by `:neoforge-1211:gameSmoke`: a real 1.21.1 `DensityFunctions.rangeChoice` graph and the bound vanilla Overworld, Nether and End `NoiseRouter`s are captured through the mapped adapter, lowered into the pure typed IR, and evaluated without an opaque Minecraft object. The 11/11 GameTest result includes the default-off version-pinned NOISE hook bypass, rejected-provider teardown, all 15 roots in each dimension, nine-point Overworld direct `finalDensity` raw-bit parity, three-point Nether/End parity, real `ProtoChunk` application/rollback, injected failure restoration across every mutation phase, cancellation forwarding and authoritative commit-token checks; it is recorded in [v0.2-mapped-capture.md](../evidence/v0.2-mapped-capture.md). It is not complete candidate chunk/noise/material parity.

| Gate | Owning package | Proposed command/task | Required evidence |
| --- | --- | --- | --- |
| G0 | P00 | `verifyV02Inputs` | Locked source/dependency/corpus schemas, license inventory, clean artifact identities and acyclic module boundaries |
| G1 | P01 | `testMinecraftOracle` | Original-vs-original determinism, comparator mutation detection, process/classpath isolation and complete coverage accounting |
| G2 | P02 | `testWorldgenSemantics` | Typed node/marker/RNG/noise inventory covered against literal pinned Minecraft captures |
| G3 | P03 | `testIntegerIeee` + `nativeIntegerIeee` | Primitive CPU vectors, shaderc/SPIR-V contract and actual-device integer-carrier arithmetic; complete graph qualification remains required |
| G4 | P04 | `replayMinecraftNoiseCpu` | Complete candidate CPU NOISE parity, including metadata and material decisions |
| G5 | P05 | `testVulkanLifecycle` + `nativeVulkanLifecycle` | Persistent service, memory visibility, leases, failure states and actual asynchronous native completion |
| G6 | P06 | `replayMinecraftNoiseGpu` | Captured real-game NOISE parity with GPU-issued execution evidence, before the live hook is enabled |
| G7 | P07 | `testChunkResultContract` | Complete dense/uniform/palette equivalence, real registry mapping and malformed ABI rejection |
| G8 | P08 | `testWorldgenCoordinator` | Single-worker progress, shared subscriptions, atomic budgets, bounded demand-driven typed sample consumers and terminal retirement |
| G9 | P09 | `testLiveNoise` | Live CPU/GPU chunk application, receipt-to-commit linkage, exhaustive journal fault seams and reload/unload safety |
| G10 | P10 | `testFullAndSavedWorlds` | Full original pipeline integration, logical save/close and fresh-process reopen parity |
| G11 | P11 | `checkReleaseJar` + `testInstalledMod` | Installed dedicated/integrated product, CPU without native loading, config modes, native/dependency/license packaging |
| G12 | P12 | `qualifyV02` | All preceding gates on the release snapshot, required matrix, stability, reproducible reports and recorded baseline rates |

The aggregate qualification task returns nonzero on any missing required evidence. GPU tests explicitly request the GPU; the absence of suitable hardware fails required qualification rather than converting the suite to CPU success. Optional platform reports may say `NOT_RUN` but cannot be advertised as qualified.

## Numerical and semantic testing

For **each reachable operation and type**, cover curated boundary vectors and at least 100,000 reproducible stratified/random operand tuples. Before release run at least 1,000,000 additional generated tuples per operation/type on the actual GPU; seeds and expected outputs are archived. These are minimum sampling requirements, not a mathematical proof. Include differential arithmetic-oracle coverage and source/algorithm review for the cases random testing rarely reaches.

Mandatory classes include exponent transitions, rounding ties, halfway products, cancellation to subnormal, smallest/largest normal and subnormal values, signed zero, overflow, infinities, NaNs, conversions, negative floor/truncation and integer overflow. Unary/binary/ternary helpers get appropriate tuple shapes. Conditional operator closure is enumerated in `numeric-operators.json`: unused transcendental functions do not justify a general-purpose library, and newly reachable operations cannot bypass qualification.

Compare finite values and signed zero by raw bits; specify NaN classification/comparison/downstream behavior separately. Preserve intermediate FP32 rounding, including End behavior and climate quantization. Test raw integer input/output carriers, inspect compiled SPIR-V, and reject hidden native float arithmetic in `GPU_IEEE_BITS`. Verify the device actually executes this profile and that compilation has not substituted an unqualified helper.

Semantic fixtures cover:

- All 15 router roots and each reachable node, including holder indirection, lazy range branches, spline extrapolation, shifted/blended/legacy noise and parameter identity.
- Legacy/Xoroshiro/positional RNG vectors, permutation/octave setup, signed overflow, negative division/remainder, End large-coordinate behavior and integer-to-FP conversions.
- Interpolator geometry, sample order, `FlatCache`, `CacheOnce`, `CacheAllInCell`, repeated/context-changing calls and independently shared versus unshared graph nodes.
- Structure beardifier and blending inputs, reload-invalidated inputs, aquifers enabled/disabled, candidate ties, surface/fluid levels, fluid postprocessing and every ore/filler/raw-block branch.
- Same-stack biome population, climate float quantization and nearest-search ties, special surface behavior, mod hooks and vanilla stage prerequisites.

The pure compiler tests retain all 107 v0.1 regressions. Existing synthetic coordinate/finite-value rejection remains tested independently; it must not accidentally reject valid Minecraft intermediate semantics in the new typed program.

## Frozen world corpus

Required configurations are vanilla Overworld, Nether, End; Terralith with its pinned Lithostitched loader fixture; Tectonic with Lithostitched Overworld; and combined Terralith/Tectonic/Lithostitched Overworld. P00 locks exact compatible artifacts and dependency hashes. If no compatible pinned stack can be established, that required matrix entry is unresolved; it is not silently deleted after a failed run.

Seeds are represented as decimal strings to preserve signed 64-bit values: `0`, `12345`, `-1`, `9223372036854775807`, `-9223372036854775808`.

| Corpus | Core regions per context/seed | Compared core chunks per backend | Purpose |
| --- | --- | --- | --- |
| NOISE | Two 5×5 squares centered at chunk (-32,-32) and (32,32), all five seeds | 6 contexts × 5 seeds × 2 regions × 25 = **1,500** | Positive/negative coordinates, chunk/region boundaries, full NOISE output |
| FULL and SAVED/reopened | Two 3×3 squares at the same centers, seeds 0 and 12345 | 6 contexts × 2 seeds × 2 regions × 9 = **216 per endpoint** | Original downstream stages, cross-chunk effects, lighting, conversion and persistent logical state |

Run those sets for both owned CPU and real GPU candidate modes. G4/G6 captured replay and G9 live NOISE must each cover the full NOISE set; a capture replay does not stand in for live commit testing. Required prerequisite chunks/halos are generated through the game's own version-derived neighborhood rules and recorded separately. Halo work cannot inflate compared core counts. Every required supported core NOISE chunk must show the selected candidate backend; CPU-only support in a required GPU entry fails the GPU gate.

Add individually pinned scene fixtures beyond these minimum grids. P00/P01 discover and freeze coordinates from the original generator; do not assume a seed contains a requested biome or ore vein. The corpus manifest records a nonzero reference witness for the behavior each scene claims to cover. Required scenes:

- Copper and iron ore veins, their raw blocks and filler, aquifer water/lava boundaries, fluid postprocessing and dry caves.
- Eroded badlands and frozen ocean; representative cross-chunk structures/features, scheduled ticks and block entities.
- Existing-chunk blending with a captured old/new boundary, structure-density influence, customized storage/generation heights and sea levels.
- Nether and End geometry, negative and valid extreme world coordinates, the large-coordinate End integer-overflow path, and independently generated region seams.
- Data-pack reload changing router/settings/registry identities, unknown custom nodes, unsupported generator types, already-populated targets and conflicting generation hooks.

Additional scenes may intentionally test original routing/rejection; they are classified separately and cannot be counted toward required GPU-supported core coverage. Preserve the required core case list when fixing a failure; changes to a corpus require an explicit version and rerun of affected controls.

## Exact endpoint comparison

| Endpoint | Required comparison |
| --- | --- |
| NOISE | Every storage block's canonical state/properties; biome prerequisite state; section counters and geometry; generation heightmaps; fluid/postprocessing marks; relevant preexisting structure and lifecycle state |
| FULL | NOISE-derived final world plus surface/carver/feature effects; all biomes and heightmaps; scheduled block/fluid ticks; block entities; structures/references; lighting arrays and correctness flags; logical FULL readiness and worldgen spawning behavior |
| SAVED/reopened | Fresh-process logical snapshots after an explicit save/flush/close barrier, including persisted states/biomes/heightmaps/ticks/block entities/structures/light and relevant entities |

Canonicalization resolves registry IDs to names/properties and orders maps/sets only where their order has no semantic effect. Every normalization has a named field, rationale, original-vs-original evidence and a test proving meaningful differences are retained. Do not compare compressed region bytes. Do not broadly omit entities, ticks or lighting because capture timing is inconvenient; implement paused/tick-controlled capture and narrowly specify truly nondeterministic values such as unrelated timestamps or generated identifiers when supported by evidence.

Worldgen entity checks compare semantic entity properties and location/count/behavior under the same controlled endpoint; unstable UUID identity cannot erase missing/extra entities. Verify entity storage separately where required by the pinned game. FULL is not persistence proof, and an enqueued save is not a completed reopen comparison.

## Runtime, coordinator and application failure campaigns

| Area | Required scenarios and invariant |
| --- | --- |
| Subscriptions and terminals | Cancel one of several consumers; duplicate requests; cancel before/after submit; drain 100,000 randomized model requests per fixed seed with at least five seeds; exactly one terminal per unique work and finite within-epoch terminal retention |
| Resource admission | One worker and one legal execution slot; minimal fitting budgets; impossible oversized requests rejected promptly; partial batches; cancelled producer; no hold-and-wait deadlock; all queued/retained memory charged |
| Spatial data | Cached versus uncached literal equality; negative XY/Z and halo edges; incomplete tiles; Y ranges; dynamic/epoch changes; producer exception with multiple consumers; no cached result crosses an identity boundary |
| Native lifetime | Multiple uploads/submissions/readbacks, unaligned logical lengths, non-coherent flush/invalidate if supported, completion reordered relative to application, scratch/readback retention and partial dispatch tails |
| Runtime faults | Compilation/allocation/submit/validation failure, delayed completion, modeled timeout/device loss, disable during work, server close while busy; no premature buffer reuse or unreported quarantine |
| Commit journal | Inject at each actual mutation seam: sections/storage/counts, heightmaps, fluid/postprocessing metadata, dirty/lifecycle state and publication; either exact rollback or explicit unsafe failure, never original recovery over partial state |
| Epoch and ownership | Unload/reload/world change/device generation change while queued, executing, decoding and committing; stale revision/context can never publish |
| Downstream/save | Delayed light completion, cross-chunk feature dependency, FULL conversion failure, write/flush/close error, corrupted/missing reopened capture; only actually completed endpoints receive receipts |
| Routing/config | CPU_ONLY never initializes Vulkan; GPU_REQUIRED fails on missing capability/recovery; AUTO_SUPPORTED logs pre-admission original routes separately from at most one verified post-failure recovery |

Real GPU runs use safe failure-injection seams and ordinary bounded workloads. Do not intentionally reset or wedge hardware. A model device-loss pass is labeled model evidence; native lifecycle tests still need actual successful GPU traffic. An actual device loss, if encountered, retains its incident evidence and invalidates the affected strict run.

Every healthy drain leaves zero outstanding subscriptions, application/readback leases and unaccounted reservations, and a bounded documented set of reusable pools/caches. After final service disposal there are zero live owned native allocations. Failure quarantine is explicitly charged, blocks unsafe reuse and cannot be converted into a healthy shutdown pass. Logical waits have predeclared deadlines and diagnostics; no future is abandoned merely because a test timeout expires.

## Stability and installation

Required Windows x64 release checks use Java21, the locked NeoForge version and the RTX5070Ti. Run a dedicated server from the installed artifact and exercise integrated play on the same frozen build. CPU_ONLY must pass with Vulkan/Shaderc unavailable and show zero native initialization calls. AUTO_SUPPORTED missing-device behavior and GPU_REQUIRED failure behavior are both checked.

Run **30 minutes each of CPU and GPU continuous controlled generation**, plus **five consecutive busy startup/generation/shutdown cycles** in each mode. Include dimension changes, non-overlapping exploration, cancellation/unload and a controlled reload. Record throughput, latency and resource high-water marks; bounded cache retention must not become unbounded growth. No stale/duplicate commits, unexplained stalls, healthy-run recovery or device-error markers are allowed.

P00 defines legal resource ceilings and endpoint deadlines; P03 establishes bounded native slices and P04/G6 calibrate per-case execution bounds before release qualification. Freeze the resulting timeout policy before the final campaign. A failing run remains failed if bounds later need justified revision. There is no minimum chunks/sec target, but completion/progress must remain bounded.

Linux CPU build/tests and a native packaging audit are required automation. Linux real-GPU execution, AMD/Intel GPU and additional drivers need their own evidence before being listed as supported. Optional untested hardware does not block the initial declared Windows release and does not inherit support from it. Fabric remains a later adapter.

## Performance baseline, without optimization

Capture three independent cold launches and three warm measured repetitions for each declared baseline workload/endpoint, using a fixed configuration and frozen corpus. Record CPU-owned and GPU-owned candidate routes and an original same-stack control, complete GPU receipt/commit counts, comparisons, wall time, p50/p95 request latency, stage time, transferred bytes, memory high-water and failures. Define warm-up and measured coordinates in advance; warm runs still generate fresh equivalent worlds/chunks rather than reading already-generated chunks from cache.

Measure NOISE, FULL and SAVED separately. GPU kernel timing is not chunk throughput; save/reopen validation time is distinguished from generation and save service time. A valid slow result passes the performance-recording gate. No old-mod speedup, throughput floor, hardware-wide claim or multiplication of speculative gains is required or implied. Compare against the old mod only with frozen available artifacts and matched endpoints; historical notes alone are not a new benchmark control.

After G12, preserve the v0.2 binary/source/corpus/driver/config baseline. Optimization experiments start as v0.3 with the same correctness gates and explicit matched-control measurement.
