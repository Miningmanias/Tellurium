# WorldgenNext configuration

## Settings file: `config/worldgennext.toml`

Written with these defaults on first start; read once at startup, before any
of the mod's code runs. Unknown options and invalid values are reported in the
log and by `/worldgennext status`, and the default is used.

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch. `false` turns off everything the mod changes (every switch in the developer table below is set to its original-behaviour value). |
| `gpu.mode` | `"auto"` | `"auto"`: GPU terrain for world generators on the tested list, vanilla code for the rest. `"check"`: generate with vanilla code, run the GPU as well for any generator its kernels build for, and compare block by block (after SURFACE where surface rules are on the GPU, after NOISE otherwise); `/worldgennext status` reports chunks compared and chunks that differ, and the log lists the first differences. `"force"`: GPU for any generator the kernels build for; untested generators are not guaranteed identical. `"off"`: no GPU, CPU-side optimizations stay on. |
| `generation.parallel_steps` | `true` | Structure, surface, carver and feature steps of different chunks run at the same time. |
| `saving.async` | `true` | Chunks are encoded and compressed on background threads when they unload. |
| `saving.compression_level` | `1` | Deflate level for those chunks, 1–9. Level 1 files are about 12% larger than vanilla's; 6 matches vanilla's size. |
| `pregen.in_flight` | `1024` | Chunks `/worldgennext pregen` works on at once (16–16384). Reduced to one per 12 MB of heap (not below 32) on small heaps. |
| `pregen.progress_seconds` | `10` | Seconds between progress messages. |
| `pregen.tune_chunky` | `true` | If Chunky is installed and `chunky.maxWorkingCount` is not set, set it to `pregen.in_flight` (with the same small-heap reduction). Chunky's own default of 50 limits throughput to about 1,000 chunks/s. |
| `voxy.generate` | `true` | Only if Voxy is installed and this is singleplayer or the host of a LAN world: generate the terrain around each player and hand it to Voxy. Left to Voxy WorldGen when that mod is installed. Measurements: `docs/evidence/comparison-voxy.md`. |
| `voxy.radius` | `128` | How far around each player, in chunks (16–1024). The chunks are generated completely and saved. |
| `distant_horizons.generator` | `"hybrid"` | Only if Distant Horizons is installed. `"hybrid"`: Distant Horizons keeps its own generator and plan (rough surface for far terrain first); the chunks it generates to refine that come from the server's chunk system. `"direct"`: all of its distant terrain is built from finished chunks; fastest to full detail, but nothing is shown for an area until its chunks are done. `"off"`: Distant Horizons is left alone. With `hybrid` and `direct` those chunks are real and are saved in the world. Measurements: `docs/evidence/comparison-distant-horizons.md`. |
| `distant_horizons.full_chunks` | `false` | `hybrid` only. `false`: chunks made for Distant Horizons stop before lighting and are saved unfinished; the game finishes them when a player gets there. `true`: they are generated completely, so the area is fully pregenerated too (about 8% slower in the server test). |

Voxy fine-tuning (system properties only): `worldgennext.voxy.inFlight` (chunks
in progress at once, default the pregenerator's rule), `worldgennext.voxy.queueLimit`
(sections in Voxy's ingest queue above which nothing is started, default 8,192),
`worldgennext.voxy.loadedLimit` (loaded chunks above which nothing is started)
and `worldgennext.voxy.log` (`true` writes progress and memory to the log every
ten seconds).

Distant Horizons fine-tuning (system properties only): `worldgennext.dh.columns`
(default `true`; `false` uses Distant Horizons' own chunk-to-LOD builder instead
of this mod's column writer), `worldgennext.dh.columnsCheck` (`true` builds every
tile both ways and compares them; `/worldgennext status` reports the result), `worldgennext.dh.readAhead`
(chunks started before Distant Horizons asks for them, default one per 4 MB of
heap up to 4,096, `0` for none), `worldgennext.dh.readAheadReach` (how far from
a request, in chunks, default 32), `worldgennext.dh.skipSecondBuild` (default
`true`; `false` lets Distant Horizons also rebuild each of those chunks from
its load event), `worldgennext.dh.convert` (default `true`; `false` leaves the
chunk-to-LOD conversion on Distant Horizons' own threads),
`worldgennext.dh.convertThreads` (default half the processors) and
`worldgennext.dh.log` (`true` writes the bridge's counters to the log every
ten seconds). `worldgennext.dh.mode` is the property behind
the option above.

Each option stands for one or more of the system properties below. A property
given on the command line (`-Dworldgennext...`) wins over the file.

## Commands (permission level 2, or the owner of a singleplayer world)

| Command | Effect |
| --- | --- |
| `/worldgennext status` | GPU in use, what each dimension generates with and why, chunk counts, settings problems, tips. |
| `/worldgennext pregen start <radius> [<centerX> <centerZ>]` | Generates a square of `(2·radius+1)²` chunks in the caller's dimension. Radius in chunks (max 5000); centre in block coordinates, default the caller's position (the world spawn from the console). |
| `/worldgennext pregen start worldborder` | Generates the square that covers the dimension's world border (refused while the border is wider than 160,016 blocks). |
| `/worldgennext voxy forget` | Forgets which chunks of the caller's dimension were handed to Voxy, so they are handed over again (for after Voxy's stored data was deleted). |
| `/worldgennext pregen pause` / `resume` / `stop` / `status` | Pause keeps progress; `resume` also continues a job a restart cut short; `stop` discards it (generated chunks stay). |
| `/worldgennext dev status`, `dev status-json`, `dev write-default-config`, `dev selftest` | Staged-route developer diagnostics (formerly directly under `/worldgennext`). |

The pregenerator walks one region file (32×32 chunks) at a time, outward from
the centre. It works in the caller's dimension; `/execute in <dimension> run
worldgennext pregen ...` selects another. Progress is kept in `worldgennext-pregen.properties` in the world
folder. Unattended use: `-Dworldgennext.pregen.autostart=<radius>` starts a
job around the Overworld spawn when the server is up,
`-Dworldgennext.pregen.autostartDimension=<id>` picks another dimension (centred
on 0, 0), `-Dworldgennext.pregen.autoresume=true` continues an unfinished one instead,
`-Dworldgennext.pregen.stopServerWhenDone=true` stops the server at the end,
and `-Dworldgennext.statusOnStop=true` writes the status report to the log at
shutdown.

`sync-chunk-writes=true` in `server.properties` (the dedicated-server default)
makes every chunk write wait for the disk. The mod keeps that guarantee but
shares one disk flush between the saves of a batch (group commit): a save is
reported complete only after its data and the header pointing at it have been
forced. A 90,601-chunk run with the setting on reached 2,660–2,700 chunks/s
with group commit and 2,389 without (2026-10-03); with the setting off it
reached 2,734.

## Throughput path settings (JVM system properties)

Developer switches. Every one defaults to the optimized behaviour; the value
in the last column of the second table restores the original code path.

| Property | Default | Meaning |
| --- | --- | --- |
| `worldgennext.fast.gpu` | `auto` | `auto`: GPU NOISE only for routers on the qualified list; `force`: any router that compiles (unqualified); `off`: never. |
| `worldgennext.fast.batch` | `64` | Maximum chunks per GPU batch. |
| `worldgennext.fast.slots` | `4` | Batches that may be in flight. |
| `worldgennext.fast.maxDelayMicros` | `1500` | Longest a request waits for a batch to fill. |
| `worldgennext.fast.pipelineCacheDir` | `worldgennext-cache` | Directory for the Vulkan pipeline cache; `NONE` disables it. |
| `worldgennext.parallelStructureSteps` | `true` | Run structure starts/references on the worker pool. |
| `worldgennext.parallelSurfaceCarvers` | `true` | Run surface and carvers on the worker pool. |
| `worldgennext.parallelFeatures` | `true` | Run features in parallel where 3×3 neighbourhoods are disjoint. |

| Property | Default | Original behaviour | Meaning |
| --- | --- | --- | --- |
| `worldgennext.fast.surface` | `true` | `false` | Surface rules on the GPU for qualified surface programs. |
| `worldgennext.fast.aquiferPrefill` | `true` | `false` | Carvers reuse aquifer cell results the GPU already computed. |
| `worldgennext.fast.lazyNoiseWrap` | `true` | `false` | NoiseChunk maps its router on first use instead of in its constructor. |
| `worldgennext.fast.orePlacement` | `true` | `false` | Ore veins use the row-mask scan (`worldgennext.fast.oreRows=false` keeps the hoisted terms but the original visited index). |
| `worldgennext.fast.cavePlans` | `true` | `false` | The cave systems of a starting chunk are walked once and replayed for each chunk they can reach, skipping tunnels whose bounding box misses the chunk. |
| `worldgennext.fast.heightCache` | `true` | `false` | Terrain-height answers (`getBaseHeight`) are remembered per thread, keyed by generator, random state, position, heightmap type and level heights. |
| `worldgennext.fast.uniformBiome` | `true` | `false` | Biome lookups skip the seeded cell choice when all eight candidate cells hold one biome. |
| `worldgennext.fast.biomeIndex` | `true` | `false` | Exact per-column replacement for the climate R-tree search. |
| `worldgennext.fast.rtreeStoreSkip` | `true` | `false` | The climate R-tree does not re-store a lookup result that is already the stored one. |
| `worldgennext.biomeColumnCache` | `true` | `false` | Column-only climate functions are evaluated once per column (`worldgennext.biomeUnwrappedSampler=false` keeps the NoiseChunk for BIOMES). |
| `worldgennext.fast.regionChunkCache` | `true` | `false` | A WorldGenRegion remembers the chunks it resolved, with the status each may be read at. |
| `worldgennext.fast.shapeCache` | `true` | `false` | `Block.isShapeFullBlock` is memoized per thread instead of in a shared, lock-taking cache. |
| `worldgennext.fast.freshRegionShortcut` | `true` | `false` | Region files created this session are not scanned for pre-1.18 chunks. |
| `worldgennext.asyncChunkSave` | `true` | `false` | Section encoding of unloading chunks runs on a save pool (`asyncChunkSaveThreads`, `asyncChunkSaveQueue`). |
| `worldgennext.asyncChunkCompress` | `true` | `false` | The save pool also compresses the chunk; the IO thread only writes. |
| `worldgennext.asyncChunkCompressLevel` | `1` | — | Deflate level for precompressed chunks. |
| `worldgennext.regionHeaderBatch` | `true` | `false` | Region headers are written when the IO worker runs dry instead of after every chunk. |
| `worldgennext.asyncIoMailboxBatch` | `256` | `1` | Messages an IO worker handles per dispatch. |
| `worldgennext.asyncChunkLoad` | `true` | `false` | Chunks read from disk are deserialized on the worker pool; the server thread only runs the point-of-interest consistency check and takes the finished chunk. |
| `worldgennext.asyncGroupCommit` | `true` | `false` | With synchronous chunk writes on, region files are opened without DSYNC; the IO worker forces the files of a batch once (at most `worldgennext.asyncGroupCommitWrites` = 256 saves or 50 ms, or when it runs dry) and only then completes those saves. No effect when synchronous writes are off. |
| `worldgennext.parallelMailboxThreads` | `true` | `false` | The chunk system's "worldgen" and "sorter" mailboxes run on their own threads (`worldgennext.parallelMailboxBatch`, default 64 messages per dispatch). |
| `worldgennext.unloadTypeCache` | `true` | `false` | Remembers chunk types so unload saves do not re-read the region file. |
| `worldgennext.unloadPacing` | `true` | `false` | Spreads unload saves over ticks and idle time. |
| `worldgennext.promptTaskRelease` | `true` | `false` | Cancelled generation tasks release their chunk references at top priority. |

Fault injection: `worldgennext.fast.loseDeviceAfterBatches=<n>` makes the engine
treat the GPU as lost after n batches, to exercise the fall-back to CPU
generation and its reporting.

Verification modes compare an optimized result with the original while the
server runs: `worldgennext.fast.verify` and `worldgennext.fast.surfaceVerify`
log and count differences (the original result is kept);
`worldgennext.fast.biomeIndexVerify` counts them;
`worldgennext.fast.oreVerify` and `worldgennext.fast.uniformBiomeVerify` fail
the chunk on a difference and print totals at shutdown.

Diagnostics: `worldgennext.fast.verify=true` generates each chunk with both the
original generator and the GPU and logs block mismatches (the original result
is kept); `worldgennext.fast.profile=true` reports per-kernel GPU time;
`worldgennext.fast.debugBuffers=true` with verify compares GPU intermediates
with the CPU interpreter; `worldgennext.fast.dumpSourceDir=<dir>` writes the
generated GLSL. The GPU runtime and its LWJGL bindings are nested in the mod
jar and unpacked to `worldgennext-cache/gpu-runtime-*` in the game directory.

The v0.2 checkpoint exposes three explicit modes:

- `CPU_ONLY` never initializes the Vulkan or Shaderc boundary.
- `GPU_REQUIRED` rejects admission when the qualified `GPU_IEEE_BITS` route is unavailable; it does not silently recover to CPU.
- `AUTO_SUPPORTED` uses the fixed pre-admission policy and reports unsupported contexts as `CPU_ORIGINAL_PLANNED`.
- If an admitted qualified route fails, `AUTO_SUPPORTED` reports `CPU_RECOVERY` only after an explicit target-restoration proof; without that proof it returns the terminal `QUALIFIED_ROUTE_FAILED_UNSAFE` decision and will not run the original path over potentially mutated state.

The file parser accepts `AUTO`, `AUTO_SUPPORTED`, or the hyphenated
`AUTO-SUPPORTED` spelling as aliases for the same fixed `AUTO_SUPPORTED`
policy. It writes only the canonical enum name when serializing a config.

Resource budgets and request deadlines are bounded in `WorldgenNextConfig`. They are capacity and completion-safety controls, not optimization knobs. The configured `nativeBudgetBytes` reaches the provider-owned Vulkan executor and covers active/idle reusable input/output capacities plus bytes retained in quarantine after an unproven completion. A serialized slot reuses mapped storage, command objects, descriptors and fence only after the previous fence completes; descriptor ranges remain request-sized. Raw chains release idle scratch before their own budget admission. The version-pinned generation boundary remains vanilla by default: setting `enableQualifiedHook` alone cannot activate it, because provider registration also requires a complete independent qualification receipt (`QualifiedHookEvidence`).

For a playable draft before the release qualification gates are closed, the
development launcher supports an explicit CPU prototype hook:

```powershell
.\gradlew.bat :neoforge-1211:runServer `
  -Dworldgennext.prototype.cpuLive=true `
  -Dworldgennext.prototype.cpuWorkers=2
```

This installs the captured CPU candidate into the real NOISE boundary for the
running server, then lets the original SURFACE-through-FULL stages continue.
It is disabled by default, rejects `GPU_REQUIRED`, is not evidence-backed
qualification, and is not safe to advertise as exact for arbitrary worlds or
mod stacks. Use `worldgennext.candidate.live=true` with the candidate capture
scripts for the bounded FULL/SAVED verifier instead. The prototype hook is a
temporary integration aid and is recorded in `docs/v0.2/DRAFT-CLEANUP.md`.
During startup fan-out it uses the larger prototype-only
`worldgennext.prototype.cpuLiveTimeoutMillis` deadline (default `120000` ms,
never lower than configured `requestTimeoutMillis`); set it explicitly when a
machine's CPU worker pool needs more time.

For a disposable playtest with unique run and result directories, use the
wrapper instead:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-prototype-cpu-live.ps1
```

The wrapper keeps the server interactive and writes optional captured CPU
NOISE result artifacts under the generated `build\prototype-cpu-live-*`
directory. Pass `-RunRoot`, `-NoiseResultRoot`, `-Workers`, or
`-TimeoutMillis` when a different location, bounded worker count, or startup
deadline is needed. After shutdown it writes `cpu-live-report.json`; the
report only marks the prototype wiring witness successful when the server log
reached readiness and at least one live artifact exists. Stop the server
normally before reusing either directory.

Once a route has actually passed the required corpus, an operator can point
`qualifiedEvidenceFile` at a UTF-8 properties receipt (relative paths resolve
against the server `config` directory):

```properties
mode=CPU_ONLY
enableQualifiedHook=true
qualifiedEvidenceFile=qualification.properties
```

The generated single-context receipt is schema `1` and must contain `contextKey`, `route`,
`resultAbi`, `compilerVersion`, `comparedCases`, `comparedFields`,
`mismatches`, `completeCoverage`, `independentOracle`, and
`sourceArtifactSha256`, plus `sourceArtifactFile`. The
`QualificationEvidenceFile.write(...)` helper emits a deterministic UTF-8
properties file with no timestamp, requires complete admissible evidence, and
refuses to overwrite an existing receipt. The artifact path is relative to the
receipt file and must remain inside the receipt directory. WorldgenNext hashes
that regular file and rejects the receipt if the digest differs before
constructing the provider. It also requires the receipt's result ABI and
compiler identity to match the provider that is about to run. `AUTO_SUPPORTED`
and `CPU_ONLY` continue with the explicit original route when the file is
absent or rejected; `GPU_REQUIRED` refuses startup instead of silently
bypassing the strict route. This file format is only an admission pointer—the
referenced comparison artifact remains the actual qualification evidence.

For the required multi-seed/terrain matrix, `qualifiedEvidenceFile` may instead
point to a schema-`2` `QualificationEvidenceBundleFile`. Its entries are sorted
exact context keys, each with its own comparison counts and source artifact;
the aggregate must still contain at least 1,500 cases, nonzero complete fields,
zero mismatches and independent-oracle markers. A request is admitted only if
its freshly captured context key appears in the bundle.

The replay scripts add the counted comparison fields to their generated
`replay-report.json`. After a complete qualifying corpus has passed, generate
the receipt from that report (one receipt per exact `contextKey`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\write-qualification-evidence.ps1 `
  -ComparisonReport .\build\cpu-candidates\qualified\replay-report.json `
  -ContextKey '<captured ContextIdentity.worldKey()>' `
  -Route CPU_OWNED -IndependentOracle
```

The command refuses partial reports, mismatches, incomplete coverage,
overwrites and source artifacts outside the receipt directory. The context key
must come from the frozen candidate identity; it is not inferred from a route
label.

For the complete multi-context bar, prepare a UTF-8 JSON manifest containing
one `contextKey`, `comparisonReport`, and optional `sourceArtifact` per entry,
then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\write-qualification-bundle.ps1 `
  -InputManifest .\build\qualification\contexts.json `
  -OutputFile .\build\qualification\qualification-bundle.properties `
  -Route CPU_OWNED -IndependentOracle
```

The bundle writer sorts exact contexts, checks each report and artifact, and
requires at least 1,500 aggregate cases before writing schema 2. It refuses
overwrites and source artifacts outside the receipt directory. This remains
unsigned draft provenance; it does not manufacture missing GPU or live-hook
evidence.

The isolated GPU candidate is enabled with
`worldgennext.gpuCandidate.capture=true`; that flag also enables the
candidate-capture event and selects the GPU branch, so a separate
`worldgennext.candidate.capture=true` flag is no longer required. The isolated
GPU candidate exposes draft-only density compiler probes:
`worldgennext.gpuCandidate.densityDontInlinePrefix` overrides the ordinary
graph/noise-wrapper/integer-helper prefix list (use `NONE` to disable it), and
`worldgennext.gpuCandidate.stopAfterDensityStage` stops after a zero-based
stage for driver diagnostics. The direct second-branch path has its own
`worldgennext.gpuCandidate.directBranchDontInlinePrefix` policy; its tested
default is `wg_node_,wg_spline_` because marking captured normal-noise wrappers
`DontInline` returned incorrect values on the target driver. `NONE` disables
that direct policy for experiments.

`worldgennext.gpuCandidate.directBranchProbeElements` limits the direct
branch diagnostic to a prefix of the chunk and requires
`debugDensityBranchOnly=true`. `debugDensityBranchOnly=true` runs the exact
direct-branch parity diagnostic and stops before ordinary density/material
output. `debugDensityParity=true` additionally checks density carriers during
the ordinary candidate path. `debugDensityBranchComponents=true` and
`debugStages=true` write bounded diagnostic values/logs. These switches are
draft probes only; none can create qualification evidence.

`worldgennext.gpuCandidate.debugMaterialProbeCpuDensity=true` together with
`cpuDensityMaterialFallback=true`, `debugDensityParity=true`, and a
`debugDensityProbePoint` skips the expensive full density planner for one
diagnostic point. It feeds that point's independently captured CPU density to
the GPU barrier/aquifer/ore/material stages and reports their result. This is
downstream GPU execution evidence only; it is fail-closed from qualification
and cannot produce a parity receipt.

`worldgennext.gpuCandidate.debugDensityEmbeddedNormalNoiseRoot` selects a
fail-closed, diagnostic-only embedded normal-noise root such as
`wg_node_2139`. It runs the GPU coordinate, Perlin-carrier and parent-combine
prototype with zeroed child carriers, then reports the finite device value and
stops. It is a compiler/dispatch probe, not CPU parity or generation: it
cannot create GPU, FULL, SAVED, terrain-mod or live-hook qualification
evidence.

`worldgennext.gpuCandidate.debugDensityInterpolationRoot` selects a captured
`wg_node_*` interpolation root for a one-point staged carrier diagnostic and
requires `debugDensityProbePoint`. It reports the GPU interpolation carrier
and stops before material execution; it is intended for per-stage isolation,
not parity or qualification.

`worldgennext.gpuCandidate.debugDensityInterpolationComponents=true` adds a
bounded eight-corner dump for the selected interpolation probe. It reports the
GPU child carriers before the parent interpolation and stops with the other
draft diagnostic; it is an isolation aid only and does not create a receipt.

`worldgennext.gpuCandidate.debugDensityNodeExact=true` makes a selected
`debugDensityNode=wg_node_<id>` probe use the retained integer-carrier shader
instead of the native-draft rewrite. It is a diagnostic A/B switch only; it
does not change the full route or qualify a hybrid profile.

`worldgennext.gpuCandidate.debugDensityStageRoot=wg_node_<id>` runs one
selected root through the ordinary staged dependency planner on the single
`debugDensityProbePoint` coordinate. It also accepts
`semantic:<64-hex-node-fingerprint>`, where the fingerprint comes from the
compiler-owned `ProgramNode` metadata and is stable when transient
`wg_node_<id>` allocation changes between captures. Unlike
`debugDensityNode`, it preserves the staged child-carrier boundary and
therefore is the preferred compact probe for an exact-island root. It stops
after the selected root and creates no candidate receipt.

`worldgennext.gpuCandidate.debugDensityStageRootSeedCpu=true` derives the
selected direct root's child carriers from the compiler-owned semantic nodes
and uses them only as explicit diagnostic inputs. It is a faster way to test a
GPU parent without replaying the full dependency closure; it is never a
production fallback and the diagnostic status records the seeded boundary.

`worldgennext.gpuCandidate.debugDensityStageRootGpuChildren=true` evaluates
the selected root's direct children one at a time on Vulkan, then evaluates
the parent from those GPU-produced rows. This is a diagnostic containment route
for semantic subtrees whose transitive stage plan exceeds the ordinary limit;
it remains opt-in, may take substantially longer, and does not turn a partial
subtree result into a complete GPU replay or qualification receipt. The route
is bounded by
`worldgennext.gpuCandidate.debugDensityStageRootGpuChildrenMaxStages` (default
`4096`) and calls the existing Vulkan pipeline-reclaim boundary every
`worldgennext.gpuCandidate.debugDensityStageRootGpuChildrenReclaimEvery`
completed stages (default `1`). Lower the stage limit for a quick envelope
probe. The wall-clock guard
`worldgennext.gpuCandidate.debugDensityStageRootGpuChildrenMaxMillis` defaults
to `600000` and is checked at stage boundaries. Do not raise the ordinary
`maxDensityStages` cap to work around this diagnostic.

`worldgennext.gpuCandidate.debugDensityStageRootGpuChildrenRecreateDeviceEvery`
defaults to `0`. A positive value recreates the Vulkan device after that many
completed recursive child stages, after host-carrier handoff. This is a slow,
diagnostic-only escape hatch for drivers that retain compiler state after
pipeline reclamation; it is never enabled by the ordinary route.

`worldgennext.gpuCandidate.debugDensityStageRootGpuChildrenNativeNoise=true`
is a second, explicitly unqualified escape hatch for that route. It keeps
captured normal-noise leaves in the native-draft arithmetic profile instead of
using the full exact integer helper closure for every leaf. All resulting
samples still execute on Vulkan, but this can introduce native-draft numeric
drift; it is for compiler-envelope exploration only and is not a parity or
qualification mode.

`worldgennext.gpuCandidate.debugDensityStageRootGpuChildrenSemanticLeaves=true`
enables the additional diagnostic leaf experiment. Captured `Shift` leaves
are emitted as standalone exact semantic shaders before their two-word rows
feed a minimal parent carrier shader. Captured `Noise` leaves use the existing
two-half exact Perlin containment route because a generic standalone Noise
probe returned an incorrect zero on the target driver. It is enabled by
default inside the opt-in GPU-child route and remains diagnostic-only. Set it
to `false` to retain the older bounded Perlin leaf decomposition.

`worldgennext.gpuCandidate.debugDensityStageRootGpuChildrenNativeCarrier=true`
lowers only the opt-in semantic leaf/parent carrier stages to hardware FP32/
FP64 arithmetic while retaining the raw two-word carrier ABI and real Vulkan
dispatch. It is an explicitly unqualified compiler-envelope experiment; it
does not change the exact `GPU_IEEE_BITS` route or permit a production hook.

`worldgennext.gpuCandidate.debugDensitySemanticNodes=wg_node_<id>,...` adds
CPU-only values, compiler-owned fingerprints and operations for selected
semantic nodes to a `cpuDensityOnly=true` diagnostic. Entries may also be
`semantic:<64-hex-node-fingerprint>`. It is intended to align stage-log values
with the generated shader's node map; use the reported fingerprint with the
`semantic:` stage-root selector instead of persisting a transient numeric
function ID. It does not execute or qualify a GPU route.

`worldgennext.gpuCandidate.debugDensityStageRootChildValues=child=value;...`
replaces the dependency replay for that direct root with explicit diagnostic
child carriers. The parent still executes on Vulkan, but the supplied values
are test inputs rather than production output. All direct children must be
listed as finite decimal FP64 values; interpolation roots are rejected.
This is useful for separating a parent operator from a divergent child
subgraph and never qualifies a GPU route. A CPU-seeded stage diagnostic also
reports the CPU value of the selected semantic root, so the GPU parent result
and CPU root value can be compared at the same probe coordinate.

`worldgennext.gpuCandidate.nativeDraftExactInterpolation` is an explicit
native-draft experiment. When `true`, captured interpolation parents and
captured FP32 (`uint`) child stages use the exact integer-carrier shader while
surrounding FP64/other graph stages remain native and the route remains
GPU-dispatched. It defaults to `false`: even this selective mode has not yet
produced an Overworld parity receipt within the target driver's bounded
compiler envelope. This switch is not a fallback, performance mode, or
qualification claim.

When that switch is enabled, `worldgennext.gpuCandidate.nativeDraftExactInterpolationRoots`
optionally limits the exact child-stage island to a comma-separated list of
function roots, with a trailing `*` meaning prefix match (for example,
`wg_spline_*`). An empty value preserves the broader exact-`uint` experiment;
the selector exists to keep one-child compiler probes bounded.

`worldgennext.gpuCandidate.nativeDraftExactInterpolationDontInline=true` is a
second diagnostic escape hatch for that experiment. It removes the broad
density `DontInline` prefix from exact-island stages to reduce driver compiler
state. It is disabled by default and is not a numerical or qualification fix.

`worldgennext.gpuCandidate.recreateDeviceInterval` optionally recreates the
Vulkan device after the requested number of density stages. It is intended
only to contain development-driver shader compiler state for very large
captured graphs; the default is `-1` (disabled). Device recreation adds
substantial setup cost and has no qualification or performance meaning.

`worldgennext.gpuCandidate.normalNoiseDeviceResidentChain` selects the
experimental one-command-buffer Perlin carrier chain. It defaults to `false`:
the correctness-first draft uses separate GPU dispatches with host-visible
intermediate carriers because the target NVIDIA driver crashed while updating
the resident chain's descriptor set. The fallback is still GPU noise
execution, but it is not a production resource policy or a performance claim.

`worldgennext.gpuCandidate.normalNoiseSharedShader` selects the experimental
single-dispatch captured-Perlin sampler and defaults to `false`. The
correctness-first default uses bounded host-staged GPU Perlin groups. An older
static experiment kept a complete captured Perlin function and its immutable
tables in one shader; the current opt-in implementation instead uses one
cached generic sampler per capture and carries captured octave factors,
offsets, y-axis controls, smear flags and packed permutation words in each
row. Its 82-word-per-level metadata ABI is a draft compiler boundary and was
recently corrected after a full-planner root mismatch; it still needs focused
GPU parity validation. Set this property to `true` only for a driver
experiment. Both routes are prototype diagnostics; neither setting creates
GPU, FULL, SAVED or live-generation qualification evidence.
The generic sampler now loops through the highest populated octave level in
the row rather than all 16 possible slots; sparse levels remain represented.
`worldgennext.gpuCandidate.normalNoisePerlinGroupSize` bounds each fallback or
resident group to `1..4` octave calls. It defaults to `1` for the native-draft
profile and `2` for the exact profile because native-draft leaves retain the
exact carrier helper closure during staged Perlin compilation. Larger groups
remain available only as explicit experiments and have no exact-parity or
qualification claim.
`worldgennext.gpuCandidate.normalNoiseSharedGenericShader` defaults to `true`
for the shared route and selects the cached dynamic metadata sampler. Set it
to `false` to use the older unrolled metadata shader when a driver cannot
compile the generic level loop/table-indexing form. Both are diagnostic-only;
the fallback still needs exact parity and a qualification artifact.
`worldgennext.gpuCandidate.normalNoisePairShader` is an opt-in experiment that
evaluates the two transformed Perlin roots of one normal-noise leaf in one
module. It defaults to `false`: the current RTX driver made the paired
38.5k-character module spend more than a minute in native compilation before
dispatch, so the split host-staged path remains the draft default.

`worldgennext.gpuCandidate.recreateDeviceBetweenNormalNoiseHalves` is an
opt-in compiler-envelope escape hatch. When `true`, the split route recreates
the Vulkan device between host-staged octave groups and after the first Perlin
half before the second; it is
disabled by default because it trades persistent-device reuse for driver-state
reclamation and has no parity or qualification meaning by itself.

`worldgennext.gpuCandidate.materialDontInlinePrefix` applies the equivalent
request-local function-control policy to the aquifer, ore, and final material
dispatches. It defaults to `wg_,noise_`; use `NONE` only for a driver
experiment. This is a draft compile-envelope control, not a numeric or
performance qualification.

`worldgennext.gpuCandidate.deviceResidentMaterial=true` selects the draft
fixed-stride device-resident post-density row chain. It copies coordinates,
density, aquifer, ore, and final state/mark fields through one raw-stage chain
and remains disabled by default pending broader exact parity and saved-world
validation. The
long-divider experiment
`worldgennext.gpuCandidate.deviceResidentFp64DivisionChain=true` is also
disabled by default after a target-driver queue-submit crash; neither switch
creates qualification evidence.

`worldgennext.gpuCandidate.exactAquiferStage=true` replaces only the ordinary
host-staged aquifer consumer with the captured `GPU_IEEE_BITS` aquifer shader
while the native-draft density carrier, ore stage, and final material stage
remain in use. It is a focused A/B diagnostic: a target-point probe corrected
the observed aquifer decision, but a full seed-0 Overworld replay increased
the state mismatch count from `62` to `282` of `98,304`. The default is
`false`; this switch is not a fallback, parity result, or qualification claim.
The current draft exact consumer compares the binary64 density against a
bounded pressure rational using integer four-limb (base-2^16) arithmetic; the
consumer avoids FP64 multiply/divide in that comparison. Barrier-noise
contribution and the complete threshold behavior are still not qualified.
Full exact-stage attempts also exceeded the target native compiler envelope
before dispatch, so this switch remains diagnostic only.

`worldgennext.gpuCandidate.exactAquiferStageNative=true` is a second opt-in
compiler-envelope experiment. When the complete shader is already using the
`GPU_NATIVE_DRAFT` profile, it keeps that native shader while selecting the
exact aquifer consumer; otherwise the normal exact aquifer shader is used.
This is a containment seam, not a numerical fix, fallback, or qualification
mode, and it remains disabled by default.

`worldgennext.gpuCandidate.exactAquiferBarrierInput=true` enables the draft
external barrier carrier for the exact aquifer stage. The barrier root is
evaluated in a separate GPU stage and supplied as two FP64 carrier words,
keeping the compact aquifer module independent of the full barrier graph. It
is disabled by default, supports both host-staged and opt-in device-resident
material rows, and applies the external barrier through an integer-carrier
similarity scale. Threshold parity across the CPU oracle is still required
before qualification. The resident form adds the carrier to a fifteen-word
row and evaluates it as the first chain stage; it remains diagnostic-only.

For this exact external-barrier consumer only,
`worldgennext.gpuCandidate.aquiferDontInlinePrefix` defaults to `NONE`.
Forcing every `wg_` helper DontInline miscompiled the dynamic barrier/density
sum on the target driver. This stage-specific default leaves the ore and
final-material compiler policies unchanged. An explicit prefix can restore
the old experiment; it does not make the route qualified. With the opt-in
shared Perlin route and this policy, one seed-0 Overworld chunk completed
`98,304/98,304` state parity and wrote a `DRAFT_PARITY_PASS` native-draft
receipt. A one-ULP pressure boundary and the release matrix remain open.

`worldgennext.gpuCandidate.debugAquiferMultiply=true` emits a tiny diagnostic
FP64 multiply at the aquifer root. It exists only to reproduce the target
driver compiler boundary and must remain disabled for ordinary probes and
all qualification runs.

`worldgennext.gpuCandidate.maxShaderSourceChars` fails the candidate closed
before shaderc/Vulkan compilation when the generated source exceeds the
configured character count. The default is `900000`, chosen to leave the
current bounded native-draft baseline below the observed multi-gigabyte
compiler envelope. Set it to `-1` only for a deliberately bounded compiler
experiment; an over-budget source is not a GPU or parity result.

`worldgennext.gpuCandidate.deviceResidentDensityScratch=true` enables the
experimental fixed-stride dependency-row chain for ordinary graph stages.
`worldgennext.gpuCandidate.deviceResidentDensityMaxStages` bounds each linear
chain (default `16`, legal range `1..64`). Interpolation, blended-noise fan-out,
and the FP64 divider retain their explicit stage ABIs. Resident stages default
to the safer `wg_node_,wg_spline_` no-inline prefix through
`worldgennext.gpuCandidate.residentDensityDontInlinePrefix`; `NONE` or a custom
comma-separated prefix is available for driver experiments. The scratch route
is disabled by default and is not a qualification or performance claim.

The ordinary captured blended-noise draft path uses
`worldgennext.gpuCandidate.blendedNoiseDontInlinePrefix`, which defaults to
`NONE`. Each of the 40 captured octaves is emitted as a fixed literal-call
shader and the arithmetic is allowed to inline because the target driver
returned incorrect signed-zero or oversized carriers when `wg_fp64_` was
marked `DontInline`. A comma-separated prefix list is available for bounded
driver experiments, but the resulting path is not qualified by default.
`worldgennext.gpuCandidate.blendedNoiseStaticGroupSize` bounds the number of
fixed calls per shader (default `4` for `GPU_NATIVE_DRAFT`, `1` for
`GPU_IEEE_BITS`, legal range `1..8`); smaller groups trade more dispatches for
a smaller native compiler unit. The native-draft default is a bounded compiler
containment experiment, not exact-parity or throughput qualification.
`worldgennext.gpuCandidate.blendedNoisePipelineReclaimInterval` fences the
device and destroys cached pipelines after that many groups (default `2`, or
`-1` to disable). This bounds driver-side pipeline retention during the draft
fan-out. `worldgennext.gpuCandidate.blendedNoiseDeviceRecreateInterval` is an
explicit heavier escape hatch for drivers that retain compiler state past
pipeline destruction (default `-1`, disabled); it recreates the Vulkan device
after the selected number of full groups and must remain a diagnostic policy,
not a production default or a performance claim.

`worldgennext.gpuCandidate.rewriteCapturedNoiseSampler` and
`worldgennext.gpuCandidate.rewriteFp64DivisionLoop` default to `true` and
select candidate-local source rewrites that keep captured permutation tables
packed and the integer divider statically bounded. They do not alter the
canonical compiler or its conformance shaders. The temporary
`debugDensityBlendedNoiseSampleLimit` property runs only the requested prefix
of octave dispatches and stops before fan-in; it is a trace diagnostic and
cannot create a candidate receipt or qualification evidence.
`debugDensityBlendedNoiseGroupLimit` provides the equivalent bounded stop for
the static-group path (for example, `1` validates only the first configured
group); it cannot be combined with the sample limit.

The isolated CPU candidate artifact producer captures Minecraft inputs on the
server thread and pipelines immutable pure materialization through a bounded
worker pool. `-Dworldgennext.candidate.cpuWorkers=2` controls that draft-only
pool (valid range `1..8`); it does not change the live provider's executor or
the semantic result contract.

The CPU replay script also accepts `-BatchSize N` to bound the number of cases
handled by one server process. Use `-Resume` with the same `-OutputRoot` and
`-RunRoot` after an interruption: atomically published non-empty `.chunk`
artifacts are retained, failed sidecars remain fatal, and
`replay-checkpoint.json` is updated after each completed batch.

When a qualified provider is admitted, each captured NOISE request is submitted
to the runtime-owned bounded coordinator. The coordinator reserves host
snapshot, staging, output, readback and application capacity before invoking the
provider's request-local backend handler, validates the backend receipt, and
publishes through the request-local authoritative Minecraft committer. Dispatch
runs through a dedicated coordinator mailbox rather than the Minecraft caller
thread. A stale, cancelled or over-budget request completes with an explicit
terminal outcome; this wiring is draft integration and is not itself
qualification evidence.

At runtime, a GPU execution receipt also carries the exact source and compiled
SPIR-V hashes returned by the dispatch. Commit publication preserves and
rechecks those hashes; CPU-only and lifecycle test seams explicitly record
`NOT_APPLICABLE` because they do not compile a shader.

Successful isolated GPU candidate receipts additionally include an explicit
`telemetryScope` and structured dispatch counters. In the current draft the
scope is `executor_lifetime_since_batch_start`, covering successful dispatches,
elements, input/output bytes and wall-clock nanoseconds for the worker's
persistent executor; it is diagnostic evidence, not a throughput claim.

`/worldgennext dev status` reports `qualificationStatus=HOOK_DISABLED`,
`NO_RECEIPT_CONFIGURED`, `REJECTED:<reason>`, or `ADMITTED:<route>` so an
operator can distinguish configuration from qualification state.
`/worldgennext dev status-json` exposes the same native state plus a schema-1
coordinator object containing lifecycle, queue depth/capacity, active and
retained-terminal records, reserved/budget bytes, and every independent work
counter. The live mod also exposes effective hook/config identity and a
process-local `telemetry` object. Its counters distinguish hook calls,
bypasses, replacement decisions, immediate failures, successful async target
completions, async failures, and cancellations. They are diagnostic operator
counters, not parity evidence. `/worldgennext dev write-default-config` creates a
deterministic default `config/worldgennext.properties` only when the file does
not already exist; it never overwrites a file and requires a restart to apply
the new values. Machine-readable status also carries `qualificationStatus`,
provider route/ABI/compiler identity, and the resolved qualification file so
an automated operator can distinguish an absent receipt from a rejected or
admitted route without parsing human log text.

The draft downstream runner is `scripts/replay-minecraft-logical.ps1`. It
pairs an independent `FULL` or `SAVED` snapshot corpus with candidate logical
endpoint snapshots in bounded per-seed server batches. `-Reopen` is available
for `SAVED` and reuses each batch's disposable world in a second candidate
process; `-ReopenedExpectedRoot` can point at a separately captured fresh
original process. The script compares all ten logical fields and leaves the
candidate route explicitly verification-only.

For an installed-server smoke, the `testInstalledMod` Gradle task writes an
explicit `config/worldgennext.properties` into each fresh disposable run. The
smoke checks that all three mode names load from the installed jar and that
`CPU_ONLY` starts with native state `DISABLED`. It intentionally sets
`enableQualifiedHook=false`; this verifies product configuration and lazy
startup, not terrain qualification.

## Current isolated GPU draft replay

```powershell
.\scripts\probe-gpu-density.ps1 -FullChunk -SharedSpline -Seed 1
```

This generates one isolated Overworld `(32,32)` NOISE artifact in fresh
disposable directories, using native draft math, shared captured Perlin,
shared spline kernels, exact aquifer stages and a default batch of 4,096.
`-BatchElements`, `-Seed`, `-Dimension`, `-ChunkX` and `-ChunkZ` are configurable.
Explicit `-BatchElements` may now select 1--16,384 rows; the default remains
4,096. The 16,383 setting exercises a partial tail while reducing submissions
and repeated metadata upload. Wider slices are diagnostic/prototype controls,
not an automatically selected or qualified device policy. Native admission and
the ten-second fence deadline remain enforced.
Omit `-FullChunk` for a bounded point/stage diagnostic. Full replay rejects CPU
child seeding and semantic/stage-stop options. It verifies the GPU-vs-CPU
receipt and actual shared-stage log, not an independent Minecraft oracle.
The generic Perlin route uploads captured tables once per batch; the JSON
report includes actual `sharedPerlinSuffixStages`, `sharedSplineStages`, wall
time and executor transfer counters. `-BatchElements 4095` exercises a partial
tail on a full Overworld chunk. These are diagnostic counters, not TPS.
`-StageOracleTolerance` only filters point/stage diagnostic differences;
it never relaxes the zero-state-mismatch requirement for a full artifact.
The status remains draft; this command does not install a live GPU hook,
qualify numeric parity, or measure TPS. Cleanup and open gates are logged in
[DRAFT-CLEANUP.md](v0.2/DRAFT-CLEANUP.md).

The same bounded launcher accepts `-ExactProfile` to request `GPU_IEEE_BITS`
without native-draft math. Its success label is `EXACT_PROFILE_PARITY_PASS`,
still only an isolated GPU-vs-owned-CPU comparison. Pair the resulting artifact
with an independent original snapshot before making any Minecraft parity claim.
`-SharedBlended` additionally selects two reusable GPU coordinate kernels and
one shared ImprovedNoise sampler, supplying only captured offsets/permutations
as immutable metadata; it never supplies CPU-computed noise values. The report
records `sharedBlendedSamples`, and a full replay fails if that requested route
was not observed. This new containment route is opt-in pending broader evidence:

```powershell
.\scripts\probe-gpu-density.ps1 -ExactProfile -FullChunk -SharedSpline -SharedBlended -InlineIeeeHelpers -Seed -1 -BatchElements 4095 -TimeoutMinutes 12
```

The launcher accepts all three vanilla dimensions. Selection is not a guarantee
of support or parity. `:runtime-vulkan:nativeRangeChoiceSmoke` is a separate,
bounded physical-device regression for a staged FP64 range parent, not terrain
qualification. Add `-Pworldgennext.rangeDraftControl=true` to compare the explicitly
unqualified native-draft control as well as the default integer-only profile.

The independent corpus runner `scripts/replay-minecraft-noise-gpu.ps1` accepts
`-SharedStages -BatchElements 4095` to select the same exact shared kernels in
serial, seed/context-grouped batches. Its receipt and independent comparison
requirements remain unchanged; the flag does not admit the production hook.
That runner also accepts an explicit `-BatchElements 16383`; its conservative
default is unchanged. Do not infer corpus qualification from a larger batch.
For a bounded subset, `-CaseFilter` is a regex against normalized relative
capture paths, including context folder and `.snap` filename. The runner copies
only selected original bytes into disposable run directories, verifies hashes
and records `oracle-selection.tsv`; empty selection fails before GPU work.
A filtered report explicitly sets `completeCoverage=false` and
`completeSelectedCoverage=true`. It is preflight evidence, never the full GPU
corpus. The authoritative capture tree remains unchanged.
`-TimeoutMinutesPerGroup` bounds the corpus runner's owned process tree
(default 12 minutes). Each serial group retains `stdout.log`, `stderr.log`,
an explicit worker exit record and `group-run-status.json`. Deadline or launcher
failure attempts to stop only that worker's descendants; unknown completion
stops the runner. Process completion alone is never artifact/parity success.

`-SharedEndIsland` selects the opt-in shared captured permutation suffix in
both the probe and corpus runners, forwarding
`worldgennext.gpuCandidate.endIslandSharedShader=true`. The probe reports
completed shared End stages and requires one in a full End replay; other
dimensions need not contain an End leaf. The outer-island positive/negative
regression and limitations are in
[shared End evidence](evidence/v0.2-shared-end-metadata.md).

`-EnablePipelineOptimization` forwards the existing experimental
`worldgennext.gpuCandidate.enablePipelineOptimization=true` selection to both
runners, including End neighbor/reduction stages. Without it, their default
requests disable compute-pipeline optimization. Corrected compute flags are
part of pipeline cache identity; shader-stage flags stay zero. Successful
receipts expose `compilationTelemetry` with executor-lifetime shaderc/native
pipeline counters. These are not per-case or GPU kernel timers; old receipts
predate the corrected flag binding and cannot establish current policy speed.

`-MaxShaderSourceChars` supplies a finite emitted-graph source guard to both
launchers (default 900,000; allowed 1..4,000,000). A larger explicit value is
only a bounded prototype experiment, not support or parity admission. It does
not remove per-stage planner limits, native storage/fence bounds or the owned
process deadline. Tectonic's seed-0 preflight emits 1,120,997 characters; its
initial 900,000-character rejection is preserved without a passing artifact.

`:runtime-vulkan:nativeSingleKnotSplineSmoke` is an opt-in physical-device
regression for one-knot endpoint extension. It compares 96 vectors at slices
96/7/1 with compute optimization disabled/enabled, including signed-zero
derivatives and coordinate conversion edges. It is not a release numeric gate
or full mod-stack Minecraft comparison.

`:runtime-vulkan:nativeSharedFp64DivisionSmoke` checks the graph-independent
host-mediated integer FP64 divider against Java division and intermediate
BigInteger carriers. It runs both compute policies, partial slices and swapped
operand slots; it is not the release numeric gate or Minecraft qualification.
The shared divider uses the existing conservative 29-dispatch route; the
experimental resident chain remains disabled by default.

`:runtime-vulkan:nativeBlendedReductionSmoke` checks ordered main/limit
reductions, shared main/ten division and lazy branch behavior on the physical
device (600 final and 1,200 intermediate checks, including NaN classification).
It is not the release numeric or Minecraft gate. Exact isolated candidates use
the shared reduction by default; `worldgennext.gpuCandidate.sharedBlendedReduction=false`
retains the old monolithic diagnostic, whose combined-fixture trial timed out.
Native-draft routes are unchanged. The production hook remains disabled.

`:runtime-vulkan:nativeBeardifierSmoke
'-Pworldgennext.beardifierInlineHelpers=true'` selects the passing inline-helper
physical structure regression (896 raw-bit comparisons). Without that property,
the broad out-of-line negative control currently fails with device loss on the
test host. Neither policy is a Minecraft/full-corpus or release qualification.
The isolated candidate uses inline helpers for standalone/resident structure
stages; captured old-world blending remains unsupported.

Add `'-Pworldgennext.beardifierInputKernel=true'` to exercise the owned shared
13,824-word input suffix. Add `'-Pworldgennext.beardifierTerralithPiece=true'`
for the captured nonempty BEARD_THIN piece and its 7,936-point chunk envelope.
The suffix/inline fixture passes 63,488 raw-bit comparisons across both layouts,
both compute policies and full/127-element batches. This is primitive evidence;
the first actual chunk completed but exposed 56 ore-state mismatches.

`worldgennext.gpuCandidate.stagedOreInputs=true` selects independent staged GPU
toggle/ridged/gap carriers, two shared-divider fractions and a fourteen-word
ore decision row. The graph-input/prepare row remains ten words. It defaults on
for exact isolated candidates and off for native-draft diagnostics. `false`
retains the old monolithic noise/ore module as a control. The diagnostic
resident material chain rejects staged ore input mode explicitly; it is not
silently downgraded. This does not change production admission.

The executor's Java SPIR-V LRU now has two hard bounds: 2,048 modules and 64 MiB
of retained binaries. Oversized modules execute uncached. Successful receipts
include `spirvCacheTelemetry` (current entries/bytes, both maxima, lifetime
evictions and oversized rejections). This is separate from native storage;
native pipeline retention limits and buffer/quarantine ceilings are unchanged.
The byte bound excludes transient compilation and unrelated host graph caches.

`-FreezeCompiledInputs` on `replay-minecraft-noise-gpu.ps1` requires PowerShell 7
and existing compiled main classes. It skips shipping source/resource compilation,
records `frozen-compiled-inputs.tsv`, and verifies exact byte hashes around GPU
execution/comparison. Use it for a controlled recovery without silently rebuilding
onto a different checkpoint; ordinary development runs still compile normally.

`merge-frozen-gpu-replay.ps1` takes a selected independent `ExpectedRoot`, one or
more `CandidateRoots`, a fresh `OutputRoot` and `FrozenCompiledManifest`. It
admits only complete exact-profile artifact/receipt/PASS-sidecar triples,
rejects duplicates/extra/missing cases and performs the independent comparison.
It preserves inputs and explicitly reports `releaseQualification=false` and
incomplete generation-start fingerprint binding. Its manifest proves the
recovery/comparator classes, not retroactive source provenance for every group.

`-TraceStages` on the corpus replay runner enables stage diagnostics and retains
`stages.log` plus submitted divider GLSL in the owned group's `shaders` directory.
It forwards `debugStages`, `debugStagesFile` and `stagedShaderDir`. Diagnostics
are off by default and add host/file overhead; do not compare trace-enabled
wall times with quiet runs as a throughput claim. Failed groups preserve their
diagnostics without creating a successful candidate receipt.

`-InlineIeeeHelpers` keeps only graph/spline functions out of line, rather than
patching every software IEEE helper with `DontInline`; it changes compilation
policy, not the integer-only numerical profile. Shared corpus stages select
that same bounded policy. This remains a driver-containment experiment until
the exact matrix passes.

`-MaterialPipelineProbe -ExactProfile -SharedSpline -SharedBlended
-InlineIeeeHelpers -Point 512,-49,512` checks one point through the actual
GPU density/material path and compares its state/fluid mark before a
diagnostic stop. It does not publish a full-chunk receipt.

`-DeviceOnly -ExactProfile -FullChunk -SharedSpline -SharedBlended
-InlineIeeeHelpers` runs the device-generation API without regenerating CPU
terrain. Its artifact/receipt is **DEVICE_EXECUTION_ONLY**, with zero compared
blocks and a null mismatch count; the strict GPU corpus runner refuses it.
Use the independent comparator separately to assess that artifact. This mode
never admits a live hook or turns unmeasured execution into a parity/TPS claim.
The independently admitted GPU provider uses this API so production requests
will not run a complete CPU oracle alongside every device generation.

`:runtime-vulkan:nativeIntegerIeee -Pworldgennext.ieeeArithmeticOnly=true`
selects the bounded 19-operation arithmetic/RNG campaign. It explicitly omits
captured-noise, End-island and weird-scaled campaigns and is not the release
numeric gate.

`-ResidentNormalNoise` (isolated probe) or `-SharedStages -ResidentNormalNoise`
(independent corpus replay) selects opt-in integer-carrier coordinate-to-sampler
chains for both halves, then the existing parent combine. The first half
exports only the six GPU coordinate words the later stages need; the second
half reads back only its sample. Metadata suffixes are uploaded per sampler,
without reuploading coordinate rows. It requires
generic shared sampling and rejects legacy/pair chains. This is a draft route,
not qualification or a live TPS claim. Nether/End captures without normal-noise leaves
report no such resident stages. The ordinary passing host-staged route remains
the default. `worldgennext.gpuCandidate.residentNormalNoiseMode=halves` is this
containment mode. `row` retains the experimental full five-stage resident row;
both full-row trials hit their process deadlines before a leaf completed.

For the full-row experiment only,
`worldgennext.gpuCandidate.residentNormalNoiseSamplerDontInlinePrefix=wg_shared_`
retains sampler call boundaries without forcing every IEEE helper out of line.
`NONE` is the fully inlined diagnostic control. The initial fully inlined,
dynamic preserve-row loop exceeded the target driver envelope and hit its
six-minute process deadline before output; it has no parity artifact. The
current preserve-row stores are explicit and bounded. Broader compile-envelope,
numeric, context and performance controls are still required.

The logical endpoint runner accepts `-Backend CPU_OWNED` (default) or
`-Backend GPU_IEEE_BITS`. The latter is an isolated verification provider, not
production admission. It selects the contained exact shared stages, defaults
to `-GpuBatchElements 16383`, and requires linked backend/commit artifacts for
each core chunk. `-TimeoutMinutesPerBatch 25` bounds both game processes and
independent comparisons. `-Endpoint SAVED -Reopen` reopens the same saved world
in a new process. The new GPU endpoint is implemented but not yet run live;
wait for the current frozen replay to end and build the new sources first.

The serial complete-corpus runner can be preflighted without executing:

```powershell
.\scripts\replay-minecraft-noise-gpu-matrix.ps1 `
  -MatrixManifest test-manifest/v0.2-gpu-matrix-local.json `
  -OutputRoot build/gpu-matrix-new -RunRoot build/run/gpu-matrix-new `
  -FreezeCompiledInputs -SharedStages -SharedEndIsland `
  -MaxShaderSourceChars 1500000 -BatchElements 16383 `
  -TimeoutMinutesPerGroup 25 -PlanOnly
```

It requires PowerShell7, fresh distinct roots and exactly six complete original
250-case corpora. Removing PlanOnly starts all contexts serially; do not do so
while another GPU/game campaign is running. There is no retry/fallback/resume
promotion, and any incomplete child prevents a full matrix pass. Existing
compiled classes are mandatory for execution. The local manifest references
unpushed build fixtures; copy and verify the pinned inputs on another host.

`python -B scripts/capture-baseline.py measurements.json [--partial]` now
requires schema v2 (documented in the script). Full validation requires three
cold and three warm independent runs for each declared workload, endpoint and
backend. Partial mode preserves row invariants but cannot satisfy the matrix.
Both outputs deny release qualification; the command validates supplied
measurements rather than producing them.

`:runtime-vulkan:nativeRawChainSuffixSmoke` checks actual-device suffix
visibility, request-sized descriptors, partial slices (37/7/1), exact buffer
budget and device recreation. Its 37-element integer fixtures are ABI/lifecycle
evidence only, not Minecraft or release numerical qualification.
