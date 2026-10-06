# Fabric port for Minecraft 1.21.1 — 2026-10-06

## What it is

`fabric-1211` builds `worldgennext-fabric-1.21.1-<version>.jar` from the same
source as the NeoForge mod. The mod's code lives in `neoforge-1211`; two
classes per loader differ (`loader/Loader`, which answers what the mod needs
from the loader, and the entry point), plus the loader's metadata. Fabric
needs Fabric API (lifecycle events, commands, chunk unload).

Three things on Fabric are not as on NeoForge, and each has its own handling:

- **A vanilla cache that is not thread-safe.** A structure template's
  per-block cache is a plain map (MC-271899). NeoForge patches it to a
  concurrent map; on Fabric the first run crashed in the parallel structure
  step within seconds. `loader/mixin/StructurePaletteCacheMixin` makes the
  same change on Fabric. NeoForge's sources contain no other thread-safety
  patch in world generation (searched its `net/minecraft/world/level`,
  `server/level` and `util`).
- **Fabric API's seed on the climate sampler.** Fabric API's biome module
  stores the world seed on the sampler and its End biome source fails without
  it. The mod builds a caching sampler from the original;
  `Loader.carryOver` copies the seed, as Fabric API itself does wherever
  vanilla builds one sampler from another. Found by the End row of the matrix.
- **Names in a released game.** The mod reads parts of the world generator
  by reflection, by the names its source uses. Those are the names at run
  time on NeoForge and in any development environment, but a released Fabric
  game runs with "intermediary" names (`class_6916`, `field_1099`). The build
  writes `worldgennext/fabric-names.tsv` into the Fabric jar: the pairs for the
  names that occur as string literals in the mod's source, and no others
  (4,099 lines, 49 classes), taken from the mappings the build uses.
  `loader/Names` translates through it; on NeoForge and in development it does
  nothing. Three NeoForge-only calls in shared code (a block state's
  "empty" test, a biome's modified climate settings, the server's resource
  manager) now go through `Loader` or a vanilla method; on Fabric the climate
  settings are reached with an access widener.

## Verification

Reference host (RTX 5070 Ti, 24 logical cores), Java 21, ScalableLux for
Fabric 0.3.0-alpha.0.7 in every run.

**Development environment** (Loom's `runServer`, names as in the source):

| Check | Result |
| --- | --- |
| SURFACE digests against serial vanilla, 15 contexts of 8,281 chunks (`MODULE=fabric-1211 MODS_ROOT=build/test-mods/fabric PREFIX=fabric- scripts/verify-fast-matrix.sh`), with the Fabric builds of Terralith 2.6.2, Tectonic 3.0.26 and Lithostitched 1.8.0 | 15 of 15 identical. 14 passed in the full run; the End failed on the sampler seed and passed after the fix |
| Pregeneration to FULL, 14,641 chunks, cold | 2,055 chunks/s, no errors, region files valid |

**Released jar on a real Fabric server** (`scripts/test-installed-fabric.sh`:
Fabric's server launcher, loader 0.19.5, Fabric API 0.116.17, intermediary
names; this is what a player runs and what development runs do not exercise):

| Check | Result |
| --- | --- |
| Pregeneration to FULL, 40,401 chunks, default settings | 2,479 chunks/s; the Overworld is recognised as the tested vanilla generator; 41,900 chunks' terrain on the GPU, 4 on the CPU; no errors; 49,729 chunks read back with no problems |
| `gpu.mode = "check"`, Overworld (vanilla code generates, the GPU result is compared block by block) | 7,102 chunks compared, 0 differ |
| The same in the Nether | 2,809 compared, 0 differ |
| The same in the End | 2,809 compared, 0 differ |

A Fabric client in the development environment (`MODULE=fabric-1211
scripts/run-client-pregen.sh 45 fabric/vanilla`): a singleplayer world opened,
8,281 chunks pregenerated at 1,172 chunks/s, no errors. A released client was
not run.

Re-run after the name layer went in, because it changes shared code (SURFACE
digests, 8,281 chunks per context, development environment): NeoForge vanilla
Overworld, Nether, End, Terralith and Terralith with Tectonic, 5 of 5
identical; Fabric vanilla Overworld, frozen ocean and Tectonic, 3 of 3; Fabric
vanilla Overworld at CARVERS, identical.

## Not done on Fabric

- Save/reopen, kill-recovery and the console command walk-through were not
  repeated on Fabric; they exercise shared code that passed on NeoForge.
- Distant Horizons and Voxy on Fabric: the bridges are loader-neutral
  reflection and were not run there. On Fabric no "chunk saved" notice exists,
  so the Distant Horizons hand-over releases a chunk when it unloads.
- Other mods' compatibility on Fabric beyond Terralith, Tectonic and
  Lithostitched; in particular no Fabric mod that itself adds a field to
  `StructureTemplate.Palette`.
- Throughput was measured once per figure; no comparison with other Fabric
  chunk-generation mods.
- The name table covers the names in the source at build time. A name read by
  reflection that is missing from it fails the lookup, which the mod treats
  as "this world generator is not supported" and generates with vanilla code;
  the installed-server test fails in that case because it requires chunks on
  the GPU.
