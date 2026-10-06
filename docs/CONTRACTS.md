# v0.1 contracts and ownership

These are current implemented contracts. The planned production migrations are in [V0.2-PLAN.md](V0.2-PLAN.md) and [v0.2/FILES.md](v0.2/FILES.md); they do not silently replace the synthetic API or its historical evidence.

The public files below are pinned before parallel implementation. Additive helpers are allowed; changing shared method signatures requires coordinator agreement.

## Semantic interfaces
Package dev.worldgennext.semantic:
- SamplePoint(int x,int y,int z)
- CellGeometry(int width,int height): positive widths/heights; width divides16. floorDiv/floorMod coordinates.
- WorldgenIdentity(long seed,String dimension,String graphHash,long epoch)
- DensityExpression: nested Constant(double value), Coordinate(Axis axis), Add(left,right), Multiply(left,right), RangeChoice(input,double minInclusive,double maxExclusive,whenIn,whenOut), Interpolated(child,CellGeometry geometry). Axis X/Y/Z. Interpolated is a semantic boundary: child evaluated at lattice corners, interpolation in explicit vanilla Y then X then Z order; surrounding operations remain at block coordinate.
- DensityEvaluator.evaluate(DensityExpression,SamplePoint): independent reference interpreter.
- CompiledDensity.sample(SamplePoint).
compiler-jvm package dev.worldgennext.compiler.jvm: new CpuCompiler().compile(DensityExpression) -> CompiledDensity, preserve lazy branches. Compilation means prebound executable program for v0.1; do not claim JVM bytecode JIT emission unless actually implemented.
compiler-vulkan package dev.worldgennext.compiler.vulkan: new GlslCompiler().emit(DensityExpression) -> String complete GLSL450 compute shader. local_size_x64, binding0 readonly std430 ivec4 points[], binding1 writeonly std430 double values[], push_constant uint sampleCount; bounds-check dispatch tail. Preserve branch laziness and exact ordered arithmetic. No noise primitives or Minecraft graph support implied.
frontend-mc1211 owns adapter/capability contracts; v0.1 Minecraft graph lowering unsupported with explicit reason, no fake terrain.

## Material contract
Package dev.worldgennext.material:
SectionData.BLOCK_COUNT =4096; int blockStateId(int index), int nonAirCount(), long logicalChecksum().
SectionCodec.encode(int[] states) [static] -> SectionData, SectionCodec.decode(SectionData) [static]->int[].
Fixture-only block-state IDs are nonnegative integers, air0; not Minecraft global registry IDs.
Immutable validated uniform and dense palette encodings; no aliasing of caller arrays. Logical checksum is representation-independent and NOT evidence of vanilla parity.
Spatial package dev.worldgennext.spatial. Engine package dev.worldgennext.engine.
Engine commits are unique, epoch-aware, validate before mutation, budgeted and cancellable. This is the MC-free model, not an installed chunk-system rewrite.

### GPU replay versus device generation

`MinecraftGpuCandidate.generate(Inputs,VulkanWorldgenExecutor)` is the isolated
replay API: it computes the owned CPU candidate, executes the device program,
and requires zero state/fluid differences before returning `Replay`. Its
compared count is the complete storage-height ABI, including a deterministic
air tail above the logical generation height. Independent Minecraft parity
still requires a separate original-only capture comparison.

`generateDevice(Inputs,VulkanWorldgenExecutor)` returns `DeviceGeneration`
without CPU terrain regeneration. It rejects native-draft, CPU-fallback and
comparison/point-diagnostic options. `logicalGpuElements` is actual logical
device output coverage; storage size is derived separately from the result.
NOISE heightmaps are derived from the returned device states; prerequisite
biomes/structures remain captured metadata. This API returns no comparison
counts or parity/qualification verdict. Isolated device-only receipts use
`DEVICE_EXECUTION_ONLY`, zero compared blocks and a null mismatch count, and
cannot satisfy strict replay/qualification admission. The live GPU provider
uses it only after the existing exact-context evidence admission boundary.

`VulkanWorldgenExecutor` owns one serialized reusable raw/chain submission slot. Only
a successfully completed fence permits resetting command/descriptor objects,
fence, or mapped storage. A failed/unproven submission quarantines the retained
capacities and makes the session terminal. Shader descriptor ranges use actual
request bytes, not pooled capacity, including shared suffixes and partial tails.
Idle high-water capacities obey the configured buffer budget, including unused
chain slots during ordinary raw requests. If combined capacities would exceed
the available budget, changing/unused buffers are freed before replacements
are allocated. Raw dispatches and chains share mapped buffers, command objects,
descriptor pool and fence; request ranges never expose retained capacity. The
historical target-driver device-idle guard remains before freeing storage used
by a completed chain, but not before reuse without a capacity change. Loss
quarantines all actual retained slots, not merely this request's geometry.
Close/device recreation release idle resources after device-idle proof. Storage telemetry reports
allocation/reuse/retention observations and supplies no qualification verdict.
`reusedDispatches` counts reusable fenced submissions, not kernels within a chain.
The hard per-kernel row ceiling is 16,384; ordinary `executeBatched(Request)`
still defaults to 4,096. Larger caller-selected slices are prototype settings,
remain budget/fence bounded and confer no qualification or TPS verdict.

Resident `RawStage` may own an immutable `uniformInputWords` suffix. Each
bounded slice places it after `count * inputWordsPerElement` words in that
stage's input buffer. Prior stages cannot overwrite it: their output descriptor
range contains only output rows. Total admission/quarantine accounting includes
every suffix once per native batch, never once per row. Chain identities include
ordered suffix contents. Telemetry counts all kernel dispatches/covered rows,
but host bytes count only initial rows, uploaded suffixes and final readback;
device-only intermediates are not reported as host traffic. Chain elapsed/max
times are whole submitted-chain timings, not individual kernel durations.

`RawStage.withExportedOutput(offset,words)` declares a bounded intermediate
output slice. After the same completion fence, each result row packs requested
exports in stage order, followed by the complete final-stage output. Exports
do not change kernel strides, descriptor bounds or native allocation budget;
they do increase host result/readback size and are included in chain identity
and byte counters. The old no-export ABI is unchanged.

The opt-in shared End neighbor stage owns exactly 256 permutation words after
its four-word coordinate rows. Every slice rebases table access to
`dispatch.count * 4`; descriptors expose actual rows plus suffix, not pooled
capacity. Metadata must be a complete owned byte permutation. Missing,
ambiguous or changed source ABI fails closed. Java signed coordinate remainders
are formed from truncating quotients, never negative-operand GLSL `%`.

Native pipeline optimization policy is set on compute-pipeline creation flags;
shader-stage creation flags remain zero. Cache identity includes actual compute
flags and the corrected `worldgennext-captured-v3-compute-flags` ABI.
`CompilationTelemetry` records successful shaderc/native pipeline creation
counts/times and their cache hits for the executor lifetime. `resetTelemetry`
resets dispatch counters only. These phase timers may overlap dispatch wall
time and are not device kernel time, per-case counters or qualification evidence.

The compiler-owned `SharedFp64DivisionStageEmitter` consumes an eight-word row
with distinct FP64 operand slots 4 and 6, preserves a sixteen-word division
carrier across 27 four-bit chunks, then writes two result words. Its integer
helper closure comes only from `IntegerIeeeEmitter`, with unknown helpers
rejected and original declaration/arithmetic order preserved. It does not
inherit arbitrary captured GLSL or seed CPU-computed results. Local sizes and
operand/chunk layouts are validated. The host-mediated route remains default;
the long resident chain is still an unqualified, disabled experiment.

The Java `SpirvModuleCache` is an owned LRU with hard bounds of 2,048 entries
and 64 MiB of retained binary bytes, separate from native buffers/pipelines.
Both insert and hit return owned copies. Invalid requests cannot change cache
storage; oversized binaries execute uncached. Byte/count eviction and clear
maintain exact retained-byte accounting. `spirvCacheTelemetry` exposes current
storage and lifetime evictions/rejections without resetting compilation
counters. This is not a total JVM heap bound: compiler temporaries and captured
graph caches remain separately accountable. Native fence/quarantine and
pipeline-retention contracts are unchanged.

## Runtime/platform

`RawRequest` is an immutable final class, not a Java record. Public construction
and input access remain defensive. Policy-only derivatives may share the
request's owned immutable array; fresh slice arrays are adopted privately once.
No public unsafe/owned-input constructor exists. Geometry, suffix rebasing,
flags and native lifetime/numeric contracts are unchanged. Record reflection
is not a supported prototype compatibility boundary.

Program/node fingerprints stream the same canonical UTF-8 byte sequence used
by the previous recursive serializer, including ordered child braces and
control-region formatting. Header caching is identity-keyed and local to one
computation; it does not replace semantic data with hashes or alter the format.

The compiler-owned blended reduction consumes 84 words: coordinate/reserved4,
MAIN8/MIN16/MAX16 FP64 carriers in octave order. Prepare writes eight words
with mainSum/ten operands at 4/6; the proven staged divider produces its GPU
quotient. Finish consumes the same sample row plus quotient at 84/85 and writes
two words. Java only repacks device carriers; malformed/duplicate groups fail
closed. Power-of-two divisions use exact integer-IEEE scaling; division by ten
retains restoring arithmetic. Lazy branch reads and accumulation/lerp order
are preserved. This ABI and primitive evidence do not admit live generation.

Captured beardifier graph markers use the same request-local structure snapshot
as the outer density-combine stage. Empty captures return exact positive zero;
nonempty captures preserve integer IEEE arithmetic and original accumulation
order. Standalone rows use four coordinate/reserved words; combined rows use
six words with FP64 density at 4/5. Reached captured kernels bind an owned
13,824-word raw table suffix, not a private shader literal array. Lookup admits
only the canonical captured index and requires the actual descriptor length to
equal `dispatch.count * rowWords + 13824`. Existing raw slicing rebases the
suffix per partial batch; resident stages own it as `uniformInputWords`.
Isolated candidate structure stages use inline helpers. This is not production admission. Every
captured old-world blend channel, including nonempty height lists without a
presence flag, remains fail-closed until independently supported.

Exact isolated ore stages now default to ten-word input rows: coordinate and
reserved words 0..3, then GPU-produced toggle/ridged/gap FP64 carriers at 4/6/8.
Each graph root uses the existing staged interpolation/noise path on the same
coordinate domain and device. Java only repacks carriers; the captured ore rule,
lazy decision reads and positional RNG remain on Vulkan. Unknown root shapes,
nonfinite carriers and inconsistent geometry fail closed. The diagnostic
resident material chain rejects this new route until its row ABI is extended;
the old monolithic ore route remains an explicit unqualified control.
Two GPU preparation stages preserve the captured edge/richness numerator and
denominator expressions. Each writes the existing eight-word divider input;
the shared exact divider supplies fractions at finish words 10/11 and 12/13.
The fourteen-word final row has no restoring divide or captured noise graph.
GPU quotient nonfinites are rejected before the final decision stage. The
small final kernel is fully inline; these are isolated containment defaults,
not a promoted production or numeric/performance gate.

runtime-vulkan package dev.worldgennext.runtime.vulkan owns LWJGL capabilities, shader compilation, tiny bounded native replay and GPU-smoke report APIs. CPU tests must run without loading Vulkan/native libraries; GPU tests explicitly opt in and strict GPU mode fails on missing capability.
oracle-and-replay package dev.worldgennext.oracle consumes pinned APIs; synthetic replay must be clearly identified. Record actual compared counts; malformed/failed/mismatched/zero-coverage data cannot pass. CPU replay must not count GPU work.
neoforge-1211 package dev.worldgennext.neoforge is a loadable diagnostic mod, ID worldgennext, version0.1.0, NeoForge21.1.176, Java21, ModDevGradle2.0.91. No chunk generation interception in v0.1. Include own pure modules in jar and dev runs; do not depend on old mod. Diagnostic commands/status are useful.
fabric-1211 builds the Fabric jar from neoforge-1211's source plus its own loader package (Loader, entry point, one mixin); loader-specific code stays in dev.worldgennext.neoforge.loader, and Minecraft members read by reflection go through loader.Names. What was verified on Fabric is in docs/evidence/fabric-port.md.

## Worker ownership
Compiler worker: semantic-core, compiler-jvm, compiler-vulkan, frontend-mc1211.
Engine worker: material-codec, spatial-data, chunk-engine.
Platform worker: runtime-vulkan, oracle-and-replay, neoforge-1211, fabric-1211.
Coordinator: root build/wrapper, docs, scripts, CI, licensing, integration. No worker edits another module or root files; request contract/build changes by message.

## v0.2 checkpoint contracts

The isolated logical verifier has an explicit CPU_OWNED/GPU_IEEE_BITS selector.
Its GPU factory returns an empty production allowlist and refuses CPU_ONLY
before native initialization. It uses device generation without a concurrent
CPU material oracle. BACKEND_VALIDATED receipts have zero committed counts;
the separate commit artifact links execution/context/coordinates/epoch only
after successful actual publication. New debug artifacts are create-new.
The verification-only publication executor and private-output source checks
do not qualify the production threading, installed jar or endpoint gates.
The isolated blocking logical verifier uses INLINE_REFERENCE coordinator
dispatch as well as inline compute/publication in the Minecraft NOISE task.
The ordinary runtime still owns a separate coordinator worker; this reference
does not qualify its scheduling. Frozen logical replay binds main bytecode
and resources through initial, independent comparison and fresh reopen runs.

Provider disposal tracks logical requests and physical computations separately:
cancellation is not native completion. Closing rejects new work and only disposes
after both counters drain; an unfinished/failed disposal cannot be reported as
healthy closure. Debug observation forwards cancellation to the actual request.
The isolated GPU mode rejection is a pure preflight before provider construction.

Shared integer floor helpers use unsigned magnitudes and wrapping carriers to
preserve Java floorDiv/floorMod for every divisor sign, MIN_VALUE/-1 and explicit
zero-divisor failure. Negative signed GLSL remainder is not a legal substitute.
Interpolation cell helpers share this floor boundary. A native helper smoke
is separate from full numeric/Minecraft qualification.

The serial GPU matrix runner requires exactly six canonical 250-case original
corpora and a shared unchanged compiled-input fingerprint. Receipt counts and
independently compared fields remain separate; PlanOnly has no execution
coverage. Complete captured replay still does not imply live commit or G12.

The checkpoint adds immutable typed programs/snapshots and explicit `GPU_IEEE_BITS`/ABI identities in `semantic-core`; snapshot/router/effect fingerprints are canonical across non-semantic map insertion order; canonical registry-aware dense chunk results and little-endian validation in `material-codec`; typed sample keys/leases and uncached reference production in `spatial-data`; unique work/subscriber/resource/commit/lifecycle models in `chunk-engine`; injected persistent Vulkan lifecycle and quarantine seams in `runtime-vulkan`; and a field-by-field corpus comparator in `oracle-and-replay`.

The NeoForge composition layer exposes static `CPU_ONLY`, `GPU_REQUIRED` and `AUTO_SUPPORTED` configuration plus lazy native initialization and a conservative mutation journal. `canInterceptGeneration()` remains false until an independent original-vs-original corpus, isolated CPU/GPU replay and actual loader commit qualification exist. These open gates are recorded as `NOT_RUN`, not counted as success.
No commits by workers. Root source tree is newly created; concurrent incomplete modules may temporarily fail compilation. Validate focused owned modules, then coordinator runs full suite.
