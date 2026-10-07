# Tellurium

Faster chunk generation for Minecraft that produces the same world, for
NeoForge and Fabric on Minecraft 1.21.1 and 1.21.11 (builds for 1.21.4 and
1.21.8 exist as well). No CUDA: the GPU part runs on Vulkan.

- **Terrain and surface on the GPU** for world generators on the tested list:
  vanilla Overworld, Nether and End, Terralith, Tectonic, Terralith with
  Tectonic, and Incendium; terrain for Amplified Nether and Nullscape. Any other generator, and any chunk the GPU flags as unsafe,
  uses vanilla code.
- **Structure, carver and feature steps in parallel**, with cheaper but
  equivalent versions of the hottest vanilla loops (ore veins, biome lookups).
- **Background saving**: chunks are encoded and compressed off the server
  thread.
- **Built-in pregenerator**: `/tellurium pregen start <radius>`.

Tellurium was called WorldgenNext until 0.2.0. Settings in
`config/worldgennext.toml` are copied to `config/tellurium.toml` the first
time the renamed mod starts; the command is `/tellurium`.

## Install

1. Minecraft 1.21.1 on Java 21, with NeoForge 21.1.176+ or with Fabric
   (loader 0.16+ and Fabric API): a dedicated server, or the client for
   singleplayer worlds. On a multiplayer server only the server needs the mod.
2. A GPU with Vulkan and 64-bit float support for the GPU part. Without one
   the mod logs why and keeps the CPU-side improvements.
3. Put `tellurium-neoforge-1.21.1-0.2.0.jar` (NeoForge) or
   `tellurium-fabric-1.21.1-0.2.0.jar` (Fabric) in `mods/`.
   For Minecraft 1.21.11 the jars are
   `tellurium-neoforge-1.21.11-0.2.0.jar` (NeoForge 21.11.45+) and
   `tellurium-fabric-1.21.11-0.2.0.jar`; the tested list there holds the
   vanilla Overworld, Nether and End, Terralith, Tectonic and the two
   together, and Distant Horizons and Voxy have not been run on it
   ([docs/evidence/minecraft-1.21.11.md](docs/evidence/minecraft-1.21.11.md)).
   For Minecraft 1.21.4 or 1.21.8 on Fabric the jar is
   `tellurium-fabric-1.21.4-0.2.0.jar` or
   `tellurium-fabric-1.21.8-0.2.0.jar`; for 1.21.8 on NeoForge (21.8.54+)
   it is `tellurium-neoforge-1.21.8-0.2.0.jar`. On those two versions the
   tested list holds the vanilla Overworld, Nether and End and Terralith
   (Tectonic generates with vanilla code there), and
   everything below about other mods, Distant Horizons, Voxy and singleplayer
   was measured on 1.21.1
   ([docs/evidence/minecraft-versions.md](docs/evidence/minecraft-versions.md)).
4. Recommended: also install ScalableLux.
   Lighting is not part of this mod, and every throughput figure below was
   measured with ScalableLux installed.

Start the server (or open a world) and run `/tellurium status`. In
singleplayer the world's owner can use the commands without cheats enabled. It names the GPU, says what
each dimension is generated with (and why, if that is vanilla code), and lists
anything worth changing.

## Use

| Command | What it does |
| --- | --- |
| `/tellurium status` | What is active, chunk counts, tips |
| `/tellurium pregen start <radius>` | Generate a square, `radius` chunks in each direction from where you stand (from the console: the world spawn) |
| `/tellurium pregen start <radius> <x> <z>` | Same, centred on block coordinates |
| `/tellurium pregen start worldborder` | Generate everything inside the world border |
| `/tellurium pregen pause` / `resume` / `stop` / `status` | `resume` also continues a job that a restart interrupted |

Chunky works too. By itself it asks for 50 chunks at a time, which holds any
fast generator to about 1,000 chunks/s, so this mod raises that limit when
Chunky is installed (`pregen.tune_chunky = false` leaves it alone).

Distant Horizons works too. It keeps its rough surface pass for far terrain,
and the chunks it then generates to refine that come from this mod's chunk
generation instead of its own. On the reference host a dedicated server built
full-detail distant terrain about 4.6 times as fast as Distant Horizons alone
with its default settings (2,040-2,120 chunks/s against 449, 66,049 chunks),
and a singleplayer world had about four times as many chunks refined after
two minutes. `distant_horizons.generator = "direct"` drops the rough pass and
builds everything from finished chunks; `"off"` leaves Distant Horizons to
itself. Those chunks are saved in the world, about 10 KB each, so the world's
files grow as far as Distant Horizons refines; by default they are saved
unfinished (no lighting yet) and the game completes them when a player gets
there, `distant_horizons.full_chunks = true` generates them completely. Details:
[docs/evidence/comparison-distant-horizons.md](docs/evidence/comparison-distant-horizons.md).

Voxy (on NeoForge through the Roxy layer) is fed too: in singleplayer, or on
the host of a LAN world, this mod generates the terrain within 128 chunks of
each player and hands it to Voxy, so the distance fills in without exploring.
On the reference host that was 73,984 chunks in about 40 s, where the Voxy
WorldGen mod had about 12,000 after 150 s. `voxy.radius` sets the distance,
`voxy.generate = false` turns it off. Details:
[docs/evidence/comparison-voxy.md](docs/evidence/comparison-voxy.md).

The pregenerator works in the dimension the command is run in. From the
console, or for another dimension, use
`/execute in minecraft:the_nether run tellurium pregen start 100`.

If the graphics driver resets or the GPU otherwise stops responding, the mod
says so once in the log and in `/tellurium status` and generates on the CPU
until the next restart; chunks already generated are unaffected.

Settings live in `config/tellurium.toml`, written on first start:

```toml
enabled = true            # false: behave exactly as without the mod

[gpu]
mode = "auto"             # "auto" | "check" | "force" (untested generators too) | "off"

[generation]
parallel_steps = true

[saving]
async = true
compression_level = 1     # 1 fastest (files ~12% larger) ... 6 = vanilla size ... 9

[pregen]
in_flight = 1024
progress_seconds = 10
```

**A world generator that is not on the tested list** (a datapack, another
terrain mod) generates with vanilla code by default. To find out whether the
GPU reproduces it, set `gpu.mode = "check"`, generate a few thousand chunks
across different biomes (`/tellurium pregen start 40`) and look at
`/tellurium status`: it counts the chunks compared with vanilla and the
chunks that differ. Nothing the GPU computes is kept in that mode, and it is
slower than vanilla. With no differences, `gpu.mode = "force"` turns the GPU
on for that world. A check on the chunks you generated is evidence, not
proof, for the ones you did not.

Things that affect sustained pregeneration speed more than anything in that
file:

- `sync-chunk-writes=true` in `server.properties` (the default; in singleplayer
  `syncChunkWrites:true` in `options.txt`) makes chunk writes wait for the
  disk. The mod keeps that guarantee and shares one disk flush between a batch
  of saves; turning the setting off is still slightly faster (2,734 against
  2,660–2,700 chunks/s over 90,601 chunks).
- Memory. The pregenerator works on fewer chunks at a time on a small heap
  (one per 12 MB, so 1,024 needs about 12 GB); it says so when it does.
- Generation order. The built-in pregenerator works one region file at a time,
  which needs the fewest chunk reloads. A pregenerator that walks outward ring
  by ring reloads its neighbours from disk on every lap; the mod deserializes
  those chunks off the server thread, and a 90,601-chunk ring-order benchmark
  reached 2,367 chunks/s (1,524 with that switched off) against 2,660-2,700
  in region order.

All options, commands and developer switches: [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## What "the same world" means here

Checked on the reference host (2026-10-03), each on 8,281 chunks per context
against vanilla generating one chunk at a time with every optimization off:

- Through the SURFACE step, per-chunk digests (blocks, heightmaps, biomes,
  structure starts and references, post-processing marks) are identical in 15
  contexts: three vanilla Overworld seeds, Nether, End, seven biome-specific
  Overworld areas, Terralith, Tectonic and both together.
- Through CARVERS, the same holds for the contexts re-run after each change
  (vanilla Overworld, Nether, End, Terralith).
- From FEATURES on, chunk contents are not reproducible from run to run (feature
  placement depends on the order neighbouring chunks are generated in), so
  later steps are checked piece by piece instead: the ore-vein scan against vanilla's on the same
  random draws (871,121 veins in vanilla, 893,472 with Terralith, no
  difference), the biome shortcut against vanilla's answer (6.8 million
  lookups, no difference), and saved chunks against the chunks as generated
  after reopening the world (blocks, heightmaps, biomes, post-processing marks
  identical; structure data identical to what vanilla's save path writes).

Singleplayer was checked in the client on the same host: 8,281 vanilla
Overworld chunks through SURFACE are identical to the dedicated-server vanilla
reference, and a 14,641-chunk pregeneration ran with the GPU alongside
rendering without errors. Opening a second world in the same game session was
only exercised by restarting the GPU engine inside one server process.

Other mods: Biomes O' Plenty, Oh The Biomes We've Gone, Nature's Spirit,
Geophilic, William Wythers' Overhauled Overworld, nine YUNG's structure mods,
three structure packs, Regions Unexplored, Incendium, Amplified Nether,
Nullscape, Lithium and Chunky were each run with this mod. All of them work.
Where the GPU can be used for a mod's terrain it matched vanilla on every
chunk compared; where it cannot, that world generates with vanilla code.
Details and limits: [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

Not checked: Linux, GPUs other than an RTX 5070 Ti, world generators outside
the tested list, other mods that change chunk generation or saving, LAN play.

## Throughput

Reference host: 24 logical cores, RTX 5070 Ti, 16 GB heap, ScalableLux, dev
server, chunks generated to FULL and saved, measured after a 6,561-chunk
warm-up. Whole-run figures; ranges are the lowest and highest of two or more
runs. "Vanilla" is the same server with every Tellurium switch off.

| World generator | Vanilla | Tellurium |
| --- | --- | --- |
| Vanilla Overworld | 124 (8,281 chunks) | 2,620–2,860 (32,761 chunks) |
| Tectonic | 90–105 (3,721 chunks) | 2,702–2,790 |
| Terralith | 41–48 (3,721 chunks) | 1,750–1,810 |
| Terralith + Tectonic | 56–62 (3,721 chunks) | 2,019–2,042 |

On the installed server with default settings, the built-in pregenerator did
251,001 chunks in 74 s from a cold start (3,378 chunks/s; one run), and every
saved chunk read back cleanly.

Against other chunk-generation mods, same server, same Chunky pregeneration
(NeoForge 21.1.255, Java 25, vanilla world, 35,721 and 142,129 chunks):
vanilla 127 chunks/s; C2ME 930 and 1,064; C2ME with its OpenCL module 2,020-2,070
and 2,140; Tellurium 2,960-2,970 and 3,479. Setup and caveats:
[docs/evidence/comparison-c2me.md](docs/evidence/comparison-c2me.md).

A cold start (121-chunk warm-up) gives 2,465–2,475 on vanilla Overworld. A
90,601-chunk run in region order sustained 2,660–2,700 with the default
`sync-chunk-writes=true` and 2,734 with it off. Method, history and limits:
[docs/evidence/throughput-fused-gpu.md](docs/evidence/throughput-fused-gpu.md).

```powershell
# One FULL benchmark run (label, extra -D properties, mods folder, radius, warm-up radius)
bash scripts/bench-full.sh myrun "" build/test-mods/vanilla 90 40
# Digest matrix against serial vanilla (15 contexts, about 45 minutes)
bash scripts/verify-fast-matrix.sh 45
# Save, reopen and compare
bash scripts/verify-save-reopen.sh 45 vanilla
# The user path end to end: default config, pregenerate, status report
bash scripts/run-pregen.sh 60 vanilla
```

## Repository

This repository also contains the earlier, stricter qualification route (typed
capture/program contracts, an independent Minecraft oracle, a bit-exact GPU
route behind evidence gates). It ships in the jar as developer tooling under
`/tellurium dev` and generates nothing unless an operator supplies
qualification evidence. Start with [Plan.md](Plan.md),
[architecture](docs/ARCHITECTURE.md), [file map](docs/FILE_MAP.md) and
[validation status](docs/STATUS.md).

## Build and test

Use JDK21, including for the Gradle launcher. The wrapper downloads pinned Gradle8.14.2 with checksum verification; initial dependency downloads require internet access.

```powershell
# Windows: discover an installed JDK21, then build and test
.\scripts\check.ps1

# Or select it explicitly
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
.\gradlew.bat test build verifyArchitecture testReport --no-daemon
python scripts/summarize-tests.py
```

```sh
# Linux, with JAVA_HOME pointing to JDK21
bash ./gradlew test build verifyArchitecture testReport --no-daemon
python3 scripts/summarize-tests.py
```

The default suite does not require a GPU or launch Minecraft. Aggregate HTML: `build/reports/tests/all/index.html`. Every module retains its own JUnit XML and HTML reports. Python is needed only for the convenience summary script.

## Run the staged-route diagnostics

With JDK21 selected, run these from the repository root:

```powershell
# Deterministic CPU replay with a counted JSON report
.\gradlew.bat :oracle-and-replay:replay --no-daemon

# Explicit native GPU check; fails if a qualifying GPU is unavailable
.\gradlew.bat :oracle-and-replay:gpuSmoke --no-daemon

# Separate, limited diagnostic for GPUs without FP64 subnormal preservation
.\gradlew.bat :oracle-and-replay:gpuSmokeNormal --no-daemon

# Isolated NeoForge startup and mapped-capture diagnostics
.\gradlew.bat :neoforge-1211:gameSmoke --no-daemon

# Original-only Minecraft NOISE corpus and clean-process determinism preflight
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1
.\gradlew.bat :oracle-and-replay:run --args='compare-corpus build/oracle-captures/original build/oracle-captures/original-repeat --failure-dir=build/oracle-failures' --no-daemon

# The default is the 10-case preflight. A release-sized core square is 5x5;
# use a distinct context ID and frozen stack fingerprint for each real stack.
# Captures are namespaced below CaptureRoot so equal coordinates do not collide.
# powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1 `
#   -ContextId vanilla-overworld -CoreSquareSideChunks 5

# Reference-only FULL endpoint stability smoke (two fresh processes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-full.ps1 `
  -OutputRoot .\build\oracle-full-check `
  -RunRoot .\build\oracle-runs\full-check `
  -Seed 0 -Dimension minecraft:overworld -ChunkX 0 -ChunkZ 0

# Reference-only SAVED/reopened smoke (two fresh processes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-saved.ps1 `
  -OutputRoot .\build\oracle-saved-check `
  -RunRoot .\build\oracle-runs\saved-check `
  -Seed 0 -Dimension minecraft:overworld -ChunkX 0 -ChunkZ 0

# Rebuild the portable source archive
.\gradlew.bat sourceBundle --no-daemon
```

Replay reports are in `oracle-and-replay/build/replay/`. The dedicated game run is under `mod/targets/neoforge-1211/build/run/gametest/`. GPU checks are bounded smoke workloads, not terrain benchmarks. Run them one at a time.

On the initial RTX5070Ti host, the normal-range diagnostic passed 917 comparisons. The strict GPU check failed its capability gate because the driver does not advertise FP64 subnormal preservation. The two results have different corpus IDs and neither establishes full floating-point or Minecraft qualification. See [validation status](docs/STATUS.md).

The mod jars of every build are in `build/release/` after `gradlew build` (for NeoForge 1.21.1, `tellurium-neoforge-1.21.1-0.2.0.jar`). The staged route's diagnostics are under `/tellurium dev` (`status`, `status-json`, `write-default-config`, `selftest`); `selftest` compares synthetic CPU expressions and fixture material states and validates no world generation. Fabric packaging is deferred.

## Modules

| Module | 0.2 checkpoint responsibility |
| --- | --- |
| semantic-core | Typed programs, immutable snapshots/identities, material/execution contracts and v0.1 language |
| compiler-jvm | Scalar typed interpreter, deterministic RNG/noise/aquifer/ore primitives and dense CPU baseline |
| compiler-vulkan | Synthetic emitter plus integer-carrier helper/source and dense worldgen shader contracts |
| material-codec | Registry-aware complete dense results, metadata, little-endian ABI and fixture encodings |
| spatial-data | Full spatial identities, typed sample leases, cached/uncached stores and bounded accounting |
| chunk-engine | Unique work/subscriptions, atomic resource admission, backend receipts and authoritative commit seam |
| frontend-mc1211 | Pure captured-node/router/snapshot lowering with explicit unsupported diagnostics |
| runtime-vulkan | Persistent service lifecycle, disjoint leases, visibility, pipeline and quarantine models |
| oracle-and-replay | Isolated corpus API, field-by-field comparator, failure bundles and endpoint baseline contracts |
| oracle-1211 | Independent original-only NeoForge/Minecraft capture process; no Tellurium candidate dependency |
| mod | The mod itself, one source tree for every Minecraft version and loader: `common` (written for 1.21.1), `loader/neoforge` and `loader/fabric`, `versions/<version>` for what later versions need differently, and the table of builds in `targets.gradle`. Each build is a Gradle project named after its loader and version (`neoforge-1211`, `fabric-1211`, `fabric-1214`, `fabric-1218`, `neoforge-1218`, `fabric-12111`, `neoforge-12111`). See [mod/README.md](mod/README.md) |

See the module READMEs for runnable diagnostics and detailed API limits. [docs/CONTRACTS.md](docs/CONTRACTS.md) fixes the shared boundaries for contributors.

## Validation and future work

CPU tests, native GPU replay, game startup, Minecraft parity and performance are separate gates. A successful synthetic replay is not vanilla parity, and synthetic samples are not chunks/sec. A strict GPU check cannot substitute CPU execution for missing hardware.

The original-only Minecraft oracle slice is now runnable and has a 10-case vanilla Overworld NOISE determinism preflight plus separate reference-only FULL and SAVED/reopened boundary smokes; the opt-in CPU candidate also passes those ten independent NOISE cases. The required six-context corpus, GPU lowering/execution, and the remaining same-stack, live, FULL and saved-world candidate gates are still open. See [roadmap](docs/ROADMAP.md), [oracle evidence](docs/evidence/v0.2-original-oracle.md) and [test layers](docs/TESTING.md). The new scheduler, sparse GPU terrain production, lighting and saving arrive only after their correctness gates exist.

## Licensing

New code is MIT. Build/runtime dependencies retain their own terms. Standard Gradle wrapper files are Apache2.0; their origin and hashes are recorded in [attribution](docs/ATTRIBUTION.md). No C2ME/OpenCL-module implementation or decompiled Minecraft source is bundled.
