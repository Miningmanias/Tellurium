# Throughput path: fused GPU NOISE and parallel worldgen steps — 2026-10-03

This records the throughput work that the project owner pulled forward from the
v0.3 plan on 2026-10-02. It is separate from the `GPU_IEEE_BITS` staged route
and its G0–G12 gates, none of which this document closes.

Host: Windows 11, 24 logical cores, RTX 5070 Ti, JDK 21.0.12, NeoForge 21.1.176,
Minecraft 1.21.1, 16 GB heap, dev server (`:neoforge-1211:runServer`).

## 2026-10-03 FULL throughput (latest; supersedes FULL figures below)

All runs: dev server, ScalableLux installed, chunks generated to FULL with
tickets released as chunks complete (unloading and saving are inside the
interval), vanilla Overworld seed 0 unless stated. "Whole run" is chunks over
wall time from first request to last completion; "steady" is the rate between
20% and 85% of completions. `scripts/bench-full.sh <label> "" <mods> 90 <warm-up radius>`.

| Run | Whole run | Steady |
| --- | --- | --- |
| Vanilla reference, every switch off, 8,281 chunks (one run) | 124 | 131 |
| Mod, 32,761 chunks, 121-chunk warm-up (two runs) | 2,465–2,475 | 2,886–2,914 |
| Mod, 32,761 chunks, 6,561-chunk warm-up (five runs) | 2,675–2,802 | 2,908–3,049 |
| Mod, 90,601 chunks, region order, `sync-chunk-writes=false`, 6,561-chunk warm-up (one run) | 2,734 | 2,863 |
| Tectonic, 32,761 chunks, 6,561-chunk warm-up (one run) | 2,593 | 2,841 |
| Terralith, same (one run) | 1,824 | 1,944 |
| Terralith + Tectonic, same (one run) | 1,811 | 1,909 |

The warm-up square is generated at a separate location, so every measured
chunk is new; the larger warm-up only removes JIT compilation from the
interval. No vanilla FULL reference was measured for the three packs.

What was added for FULL (each has a switch; see `docs/CONFIGURATION.md`):
SURFACE on the GPU; aquifer results reused by carvers; exact per-column
replacements for the biome R-tree search and column-only climate functions;
lazy NoiseChunk router mapping; a row-mask ore scan; a uniform-section biome
shortcut; background section encoding and compression with batched region
headers; removal of serial stalls in unloading and task release; IO mailboxes
that take many messages per dispatch; own threads for the chunk system's
"worldgen" and "sorter" mailboxes. The last one was the largest single step
after SURFACE: with the worker pool saturated by generation steps, scheduling
messages had been waiting for a free worker.

Tried and measured as no gain: replaying or memoizing NoiseChunk wrap hashing
(slower), removing the chunk-holder map copy on the server thread, ParallelGC,
a larger G1 young generation, tile request order at radius 90, batching ticket
changes.

**Correctness checks on this code** (GPU forced, 8,281 chunks per context):

- `scripts/verify-fast-matrix.sh 45` at SURFACE: 15/15 contexts identical to
  serial vanilla (three Overworld seeds, Nether, End, seven biome-centred
  Overworld areas, Terralith, Tectonic, combined), run on the commit that added
  the mailbox threads. A three-context subset passed again after the code
  cleanup.
- CARVERS: vanilla Overworld, End and Terralith identical on that commit;
  the CPU noise path with the lazy router mapping identical to the reference.
- Ore scan: `worldgennext.fast.oreVerify` replays vanilla's random draws
  against the replacement and compares the vein's box of blocks: 871,121
  veins (vanilla) and 893,472 (Terralith), 0 differences.
- Biome shortcut: `worldgennext.fast.uniformBiomeVerify`, 6,806,077 shortcut
  answers equal to vanilla's. The first version tested palette entries rather
  than stored cells and never fired; the verify counter showed 0 and it was
  corrected.
- Saved world: `scripts/verify-save-reopen.sh 45` at FULL for vanilla and the
  combined pack. Digests taken as each chunk completed equal the digests of
  the same chunks loaded from disk in a new process for blocks, heightmaps,
  post-processing marks and biomes; the reopened run generated no terrain.
  Structure digests differ in 14 (vanilla) and 24 (combined) chunks between
  completion and reload with the mod's save path and with the original save
  path alike, and the two reopened worlds have identical structure digests:
  structure pieces are shared objects that later chunks move, so the
  completion-time digest can predate the saved state.

FULL output itself is not compared with vanilla: two runs of one seed differ
from FEATURES on, with or without the mod's save path.

**Installed server, user path.** The release jar in the installer-made NeoForge
21.1.176 dedicated server (`build/installed-server`), with ScalableLux, no
WorldgenNext settings (the default `config/worldgennext.toml` was written on
that start) and `sync-chunk-writes=true`: the built-in pregenerator
(`-Dworldgennext.pregen.autostart=90`) generated 32,761 chunks in 16 s from a
cold start (1,958 chunks/s by its own clock, which includes JIT warm-up);
33,760 chunks' terrain came from the GPU and 4 from the CPU. One run.

**Singleplayer (dev client, integrated server).** `scripts/run-client-pregen.sh`
opens a world with `--quickPlaySingleplayer`. `DIGEST=... scripts/run-client-pregen.sh 45`:
8,281 vanilla Overworld chunks at SURFACE, GPU forced, identical to the
dedicated-server vanilla reference digest (3,029 chunks/s in digest mode).
`scripts/run-client-pregen.sh 60`: 14,641 chunks to FULL in 13 s from a cold
start with an 8 GB heap (682 in flight), 15,501 chunks' terrain from the GPU,
no errors in the log. One run each. The shutdown-during-compile crash and the
per-start runtime class loader were found here and fixed; the fix was
exercised with `-Dworldgennext.fast.restartCheck=true` (engine started,
stopped mid-compile and started again in one process) on the dedicated dev
server, not by opening two worlds in a client.

**Group commit for synchronous writes (added after the measurements under
"Limits found").** With `sync-chunk-writes=true`, region files are opened
without DSYNC and the IO worker forces the files of a batch once before
completing that batch's saves. 90,601 chunks, region order, 6,561-chunk
warm-up, setting on: 2,701 and 2,659 chunks/s with group commit (pending
writes peaked near 300), 2,389 without (one run). Checks:
`scripts/verify-save-reopen.sh 45` passes with it; `scripts/verify-kill-recovery.sh 120`
killed the server process at 17.7% and at 40.5% of a 58,081-chunk
pregeneration, after which every chunk referenced by every region, poi and
entities file header was complete and decompressed (21,426 and 33,214 chunks),
the resumed job finished with no chunk read errors in the log, and the files
validated again. A process kill keeps the operating system's file cache, so
this shows the files are consistent at an arbitrary instant; power loss was
not simulated.

**Off-thread chunk loading (added after the measurements under "Limits
found").** `ChunkSerializer.read` runs on a worker; the server thread runs the
recorded point-of-interest checks and takes the chunk. 90,601 chunks FULL in
ring order, 1,024 in flight, 6,561-chunk warm-up: 2,367 chunks/s with it and
1,524 without (one run each; the 200 chunks/s collapse below was measured
before the scheduling-thread and IO changes). Reopening 8,281 saved FULL
chunks: 3,519 chunks/s against 1,990 before. `scripts/verify-save-reopen.sh 45`
passes for vanilla and the combined pack with it; that run loads every chunk
through the new path and compares blocks, heightmaps, biomes and
post-processing marks with the chunks as generated, and structure data with a
run that has the change off. Stored light is not part of the digest and was
not compared.

**Cave plans.** The tunnels of a cave system depend only on its starting
chunk, yet every chunk within eight chunks walks them again. They are now
recorded once per starting chunk and replayed. Carving fell from 10.8% to 9.3%
of worker CPU samples (one profiled run each); FULL throughput is unchanged
within noise (2,767-2,860 chunks/s with, 2,775-2,837 without, three runs
each). `STATUS=CARVERS scripts/verify-fast-matrix.sh 45`: 15/15 contexts
identical to serial vanilla with it on.

**Commands typed into a server console.** `scripts/test-installed-commands.sh`
pipes the mod's commands into the installed server running the release jar on
a fresh world: `status`, `pregen start/status/pause/resume/stop`, a second
`start` while one is paused (refused), `start` with a centre, and
`dev selftest`, a pregeneration in the Nether through `/execute in`, and
`pregen start worldborder` with the default and with a 400-block border. One
defect was found this way: a `pregen start` typed while the server was still
starting answered with Minecraft's "An unexpected error occurred", because a
console command queued before the world is loaded runs with a source that has
no level. It now means the Overworld; typing the commands during start-up was
re-tested, as was the whole sequence after start-up.

**Soak run, 2026-10-05.** Installed server, release jar, ScalableLux, default
settings (`sync-chunk-writes=true`, 16 GB heap), built-in pregenerator from a
cold start: 251,001 chunks to FULL in 74 s, 3,378 chunks/s by the
pregenerator's own clock; progress lines read 2,082 chunks/s at 8%, 3,345 at
51% and 3,678 at 93%. No full collections, longest pause 220 ms, 3.0 GB of
region files. Afterwards `scripts/check-region-files.py` read 273,529 chunks
from 324 region files with no problems; the only error in the log was
vanilla's "Failed to fetch mob spawner entity". One run. The rate is higher
than the 32,761-chunk benchmark because warm-up and the partly generated
border around the area are a smaller share of a larger job.

**Limits found.** With `sync-chunk-writes=true` (the dedicated-server default)
each chunk write is synchronous; the IO thread then wrote about 2,700 chunks/s
while about 3,300 were produced, pending writes held their chunk data, and a
90,601-chunk run saw full collections (1,768–2,127 chunks/s over five runs,
before the mailbox-thread change). With ring-by-ring request order, once a
ring is longer than the in-flight window (radius above 128 at 1,024 in
flight) every completed chunk needs about 24 chunk loads on the server thread
and throughput fell to about 200 chunks/s. The built-in pregenerator uses
region order for that reason.

## 2026-10-03 update (supersedes the numbers below where they differ)

Two kernel optimizations were added and every context was requalified:

- **K_XZ**: expensive Y-independent subgraphs the block kernel needs (2D noise
  and splines outside interpolation) are evaluated once per block column.
- **K_PRELIM**: each unique preliminary-surface quart column of a batch is
  scanned once and looked up by the aquifer kernel. The compiler rejects a
  router unless every FlatCache reachable from
  `initialDensityWithoutJaggedness` has a Y-independent child; that is the
  condition under which the value does not depend on the asking chunk.

Requalification (`scripts/verify-fast-matrix.sh 45`, GPU forced, run
2026-10-03): 8/8 contexts digest-identical to serial vanilla on 8,281 chunks
each, 8,400+ GPU-generated chunks per run, no bail-outs. The script now fails
closed; an earlier revision printed `MATRIX PASS` after an evaluation error
without comparing anything, and that output was discarded.

Timed NOISE, default mode, 8,281 chunks, structures on:

| Context | Vanilla | Mod | Ratio |
| --- | --- | --- | --- |
| Vanilla Overworld | 702–753 | 3,334–3,476 | about 4.6x |
| Terralith | 666–692 (digest mode) | 2,839–2,916 | about 4.2x |
| Tectonic | 592 (digest mode) | 2,536–2,650 | about 4.4x |
| Combined | 487–494 | 2,338–2,476 | about 4.9x |

Digest-mode NOISE (tickets held to the end): Nether 5,936 vs 4,047; End 6,406
vs 3,352.

FULL, 3,721 chunks, timed (tickets released as chunks complete, so saving is
inside the interval): vanilla Overworld 657–671 vs 110; combined pack 506 vs 64.

Kernel cost per chunk (profiled): vanilla Overworld 0.21 ms total; combined
pack 0.35 ms total. Cold kernel compilation for the combined pack fell to about
2.6 minutes.

**Installed server.** The release jar was run in a NeoForge 21.1.176 dedicated
server installed with the official installer (`build/installed-server`), with
default settings and no WorldgenNext flags other than the benchmark. The nested
GPU runtime loaded through `GpuRuntimeLoader`, the GPU generated 8,401 chunks,
and the digest equals the dev-server vanilla reference on all 8,281 chunks
(3,563 chunks/s in digest mode). The first attempt fell back to vanilla with
LWJGL "Out of stack space"; the isolated runtime now sets its own LWJGL stack
size. Not run: a Minecraft client, Linux, other GPUs.

## What was built

- **Parallel worldgen steps.** Vanilla 1.21.1 runs STRUCTURE_STARTS,
  STRUCTURE_REFERENCES, SURFACE, CARVERS and FEATURES synchronously on the
  single "worldgen" mailbox. The first four now run their unmodified vanilla
  bodies on the worldgen worker pool. FEATURES runs through
  `FeatureRegionScheduler`, which only overlaps steps whose 3×3 chunk
  neighbourhoods are disjoint. `OctahedralGroup` rotation tables are prebuilt
  first; vanilla builds them lazily without synchronization and a parallel
  jigsaw placement crashed on a half-built table.
- **Fused GPU NOISE.** `FusedNoiseCompiler` turns the captured router and the
  NoiseChunk material rule into five batched native-FP64 compute kernels
  (FlatCache columns, interpolation corners, aquifer statuses, per-block
  material, heightmaps). Division and square root are corrected to round-to-
  nearest-even with FMA residual checks; splines are table-driven and use
  `Mth.binarySearch` probe for probe; any arithmetic outside the guarded domain
  sets a per-chunk flag and that chunk is regenerated by the original generator.
  Chunks with a non-empty blender or below-zero retrogen always use the
  original generator.
- **Admission.** `worldgennext.fast.gpu=auto` (default) enables the GPU path
  only for a router whose seed-independent kernel-structure fingerprint is in
  `worldgennext/fused-qualified.properties`. Other routers stay on the original
  generator with a logged reason. `force` and `off` override.

## Method

`scripts/bench-cps.ps1` starts a fresh world, generates a separate 121-chunk
warmup square, then requests a 91×91 square (8,281 chunks) at the target status
and reports completed chunks divided by wall-clock time from the first request
to the last completion. Nothing is subtracted. Kernel pipelines were already in
the driver cache for timed runs; cold compilation is reported separately.

Correctness uses a per-chunk digest (`ChunkDigest`): every block state, the two
worldgen heightmaps, post-processing marks, and structure starts and references.
`scripts/compare-digests.py` requires an identical chunk set and identical
digests between a serial vanilla process and a candidate process.
`scripts/verify-fast-matrix.sh` does this for every context.

## Correctness results

| Context (8,281 chunks, NOISE status) | Digest vs serial vanilla | Bail-outs |
| --- | --- | --- |
| Vanilla Overworld, seed 0 | identical | 0 |
| Vanilla Overworld, seed 12345 | identical | 0 |
| Vanilla Overworld, seed -1 | identical | 0 |
| Vanilla Nether, seed 0 | identical | 0 |
| Vanilla End, seed 0 | identical | 0 |
| Terralith 2.6.2 + Lithostitched | identical | 0 |
| Tectonic 3.0.26 + Lithostitched | identical | 0 |
| Terralith + Tectonic + Lithostitched | identical | 0 |

SURFACE and CARVERS offload: digest at CARVERS status identical to serial
vanilla on 3,721 vanilla Overworld chunks.

Limits of this evidence:

- One region per context (centre chunk 4000,4000; Nether and End at 300,300),
  one hardware and driver configuration. It is a same-stack comparison against
  the original generator in a separate process, not the project's
  `oracle-1211` corpus.
- Old-world blending, retrogen and non-empty-blender chunks are not covered;
  they are routed to the original generator.
- Vanilla FULL output is not reproducible against itself: two serial vanilla
  runs differed in block content in 2,416 of 3,721 chunks, because decoration
  order across chunk borders depends on scheduling. No exactness claim is made
  past CARVERS. Parallel features differ from a vanilla run in the same way.
- The qualified list is tied to the exact compiler and GLSL library source;
  any change to either requires re-running the matrix.

## Throughput results

NOISE status, 8,281 chunks, structures on, timed protocol:

| Configuration | NOISE chunks/s |
| --- | --- |
| Vanilla (serial structure steps) | 702–753 |
| Vanilla + parallel structure steps | 1,036–1,053 |
| GPU NOISE, vanilla Overworld seed 0 (default mode) | 2,690–2,753 |
| GPU NOISE, vanilla Overworld seed 777 (default mode) | 2,933 |
| Combined pack, vanilla | 487–490 |
| Combined pack, GPU NOISE | 796–805 |

The owner's reference targets (historical GPUWorldGen bests, used as a stand-in
because no C2ME OpenCL measurement exists in this repository) were 2,008 for
vanilla and 598 for the combined pack. No C2ME build was run.

Digest-mode runs (tickets held until the end, so not directly comparable):
Terralith 1,780 vs 666 vanilla; Tectonic 860 vs 592; Nether 5,634 vs 3,831;
End 5,599 vs 3,419.

Other endpoints, vanilla Overworld, 3,721 chunks:

| Endpoint | Vanilla | Mod |
| --- | --- | --- |
| CARVERS | 184 | 1,472 |
| FULL, serial features | 118–120 | 346 |
| FULL, parallel features | 118–120 | 896 |

Kernel cost per chunk (profiled, serialized): column 0.06 ms, corner 0.06 ms,
aquifer 0.29 ms, block 0.14 ms, height 0.01 ms.

Cold kernel compilation in the driver: about 46 s for the vanilla Overworld,
66 s for Terralith, 3.7 min for Tectonic and about 7.5 min for the combined
pack. It runs on a background thread and is cached by the driver and by a
`VkPipelineCache` file afterwards.

## Not done

- No run in a Minecraft client, on Linux or on another GPU; the installed
  dedicated server run above is the only non-dev execution.
- Lighting is not changed by this mod; FULL figures from 2026-10-03 on are
  measured with ScalableLux installed.
- No sustained or multi-run statistics beyond the ranges above; no measurement
  on other hardware.
