# Minecraft versions after 1.21.1 — 2026-10-06

## What exists

| Minecraft | Loader | Module | Jar | State |
| --- | --- | --- | --- | --- |
| 1.21.1 | NeoForge | `neoforge-1211` | `worldgennext-neoforge-1.21.1-<version>.jar` | released line; everything in `docs/STATUS.md` refers to it |
| 1.21.1 | Fabric | `fabric-1211` | `worldgennext-fabric-1.21.1-<version>.jar` | `docs/evidence/fabric-port.md` |
| 1.21.4 | Fabric | `fabric-1214` | `worldgennext-fabric-1.21.4-<version>.jar` | verified as below |
| 1.21.8 | Fabric | `fabric-1218` | `worldgennext-fabric-1.21.8-<version>.jar` | verified as below |
| 1.21.8 | NeoForge 21.8.54+ | `neoforge-1218` | `worldgennext-neoforge-1.21.8-<version>.jar` | verified as below |

Not ported: NeoForge for 1.21.4 (the module compiled against NeoForge
21.4.158 and was set aside unrun: ScalableLux has no NeoForge build for
1.21.4, and every test run here includes ScalableLux); Minecraft 1.21.5 to
1.21.7 (not built or run; the 1.21.8 jars declare 1.21.8 only); 1.21.9 and
later (see the end).

## How a version is built

The mod's code is the 1.21.1 source in `neoforge-1211` plus the Fabric glue in
`fabric-1211`. A later version's module holds only what differs, and
`gradle/fabric-port.gradle` assembles the rest:

- `src/main/java`: files that replace the shared file of the same path;
- `removed.txt`: shared files with no counterpart in that version;
- `src/main/resources`: its mixin list and its list of tested generators;
- `portParents`: earlier versions' modules whose files it also uses.

Code that differs between versions but is otherwise shared calls
`version/Version` (one class per version, same methods). `fabric-1214` holds a
`Version`, four mixins and a mixin list; `fabric-1218` holds a `Version`, a
list of tested generators and nothing else.

What a Minecraft version needs differently is the same on both loaders, so the
NeoForge build of a later version (`gradle/neoforge-port.gradle`) takes those
files from that version's Fabric module and adds only its loader metadata:
`neoforge-1218` is a build script and a `neoforge.mods.toml`. The NeoForge
`Loader` and entry point of 1.21.1 compile and run unchanged on NeoForge
21.8. The module uses ModDevGradle 2.0.148; with 2.0.91, which the 1.21.1
module uses, the development server of 21.8 does not start.

What changed in Minecraft and what the mod does about it:

| Since | Change | Handling |
| --- | --- | --- |
| 1.21.2 | Which aquifer blocks are marked for a fluid update: only where the aquifers concerned differ in level or fluid, looking at the fourth nearest aquifer as well | The GPU kernel takes the rule as an option (`FusedNoiseCompiler.FluidUpdates`). The later rule is put in by rewriting the kernel text, so the 1.21.1 kernels are character for character what they were, and so are their fingerprints |
| 1.21.2 | `ChunkSerializer` became `SerializableChunkData`; the game serialises chunks off the server thread itself | The mod's own off-thread save is left out; the mark of a chunk saved with its surface already built moves to `SerializableChunkDataSurfaceMixin` |
| 1.21.2 | `ProcessorMailbox` is gone; `processUnloads` was restructured | The mailbox dispatch mixin is left out; the unload pacing mixin has a 1.21.4 copy |
| 1.21.2 | Registry, height and chunk-flag methods renamed | `Version` |
| 1.21.5 | Chunk tickets: a ticket kind is a plain value without a type parameter, and a chunk holds one ticket per kind and level, with no key | `Version.Ticket` counts the keys itself. The mod's kinds are not registered: they are never saved, and a registered kind would have to exist on clients too |
| 1.21.5 | NBT getters return `Optional` | `Version` |

Left out after 1.21.1 (each is an optimisation; none changes what is
generated): reading chunks off the server thread (`ChunkMapAsyncLoadMixin`,
`ChunkSerializerAsyncLoadMixin`) and the mailbox dispatch. Everything else in
the 1.21.1 mixin list applies on 1.21.4 and 1.21.8.

## The list of tested generators is per version

The 1.21.2 aquifer change does not show in a kernel's fingerprint: the same
router gives the same kernel text on 1.21.1 and 1.21.4 apart from that rule,
and on 1.21.4 and 1.21.8 the fingerprints are equal. A list produced on one
version therefore says nothing about another. `fused-qualified.properties` now
names its Minecraft version (`minecraft=`), and the mod ignores a list whose
version is not the running one: a build without its own list generates with
vanilla code. This was seen working: the 1.21.8 build, before its matrix had
run, reported every dimension as not on the tested list and put 0 chunks on
the GPU.

## Verification

Reference host (RTX 5070 Ti, 24 logical cores), Java 21, ScalableLux for the
version and loader in every run (0.1.2 on 1.21.4, 0.1.5.1 on 1.21.8), Fabric
loader 0.19.5, Fabric API 0.119.4+1.21.4 and 0.136.1+1.21.8, NeoForge 21.8.54.
One run per figure.

| Check | Fabric 1.21.4 | Fabric 1.21.8 | NeoForge 1.21.8 |
| --- | --- | --- | --- |
| SURFACE digests against serial vanilla, 8,281 chunks per context, the 12 vanilla contexts (`MODULE=<module> MODS_ROOT=build/test-mods/<module> PREFIX=<label>- scripts/verify-fast-matrix.sh`), development environment | 12 of 12 identical | 12 of 12 identical | 12 of 12 identical |
| Built jar on a real server, pregeneration to FULL, 40,401 chunks (`FABRIC_MC=<version> scripts/test-installed-fabric.sh 100`, `NEOFORGE_MC=1.21.8 scripts/test-installed-neoforge.sh 100`) | 3,096 chunks/s; 41,895 chunks' terrain on the GPU, 9 on the CPU; no errors; 49,729 chunks read back with no problems | 2,620 chunks/s; 41,895 on the GPU, 9 on the CPU; no errors; 49,729 read back with no problems | 1,953 chunks/s; 41,895 on the GPU, 9 on the CPU; no errors; 49,729 read back with no problems |
| The same server with `gpu.mode = "check"` (vanilla code generates, the GPU result is compared block by block) | Overworld 7,104, Nether 7,225, End 7,225 chunks compared, 0 differ | the same counts, 0 differ | the same counts, 0 differ |
| Save and reopen, 8,281 chunks to FULL (`MODULE=<module> scripts/verify-save-reopen.sh 45 <module>/vanilla`) | PASS: blocks, heightmaps, marks and biomes equal after reopening, nothing generated again | PASS, the same | PASS, the same |
| Singleplayer in the development client, 8,281 chunks pregenerated (`MODULE=<module> scripts/run-client-pregen.sh 45 <module>/vanilla`) | 1,811 chunks/s, terrain on the GPU, no errors | 2,083 chunks/s, terrain on the GPU; one error line from the game ("Negative index in crash report handler") at the moment the test stops the integrated server under the client, not looked into | not run |

The list of tested generators that the NeoForge 1.21.8 build uses is the
Fabric 1.21.8 module's file; the NeoForge row of the matrix above is what
entitles it to that list (the fingerprints are the same on both loaders).

Before the fix for the aquifer rule the 1.21.4 matrix failed on exactly that:
blocks, heightmaps, biomes and structures were identical and the fluid-update
marks differed in 4,422 to 6,096 chunks of 8,281 per context.

After the changes to shared code, on 1.21.1: NeoForge vanilla Overworld,
Nether, End, Terralith and Tectonic rows identical (5 of 5), then Overworld,
Nether and Terralith with Tectonic again after the ticket change (3 of 3);
Fabric vanilla Overworld, End and Tectonic identical (3 of 3); the Fabric
1.21.1 jar on a real server generates on the GPU and recognises the Overworld
as the tested vanilla generator, which shows the 1.21.1 kernel text is
unchanged.

Fabric 1.21.1, which had not had it: save and reopen (`MODULE=fabric-1211
scripts/verify-save-reopen.sh 45 fabric/vanilla`) PASS. NeoForge 1.21.1 after
all of the above: `scripts/release-check.sh` PASS (build, exactness 3 of 3,
carvers, save and reopen, kill-recovery, console commands on the installed
server).

## Not verified on 1.21.4 and 1.21.8

- No datapack or mod world generator (Terralith, Tectonic and the rest): only
  vanilla is on their tested lists. Others generate with vanilla code unless
  `gpu.mode` is set to `"check"` or `"force"`.
- Singleplayer was run only as above, in the development client: no released
  client, no play session. Distant Horizons and Voxy are untested there. The
  Distant Horizons bridge holds tickets of one position under several keys;
  on 1.21.8 that goes through the mod's own counting, which only the
  pregenerator and the benchmark have exercised.
- Kill-recovery and the console command walk-through were not repeated.
- Throughput is one run each and is not a comparison between versions or
  loaders (the ScalableLux and Fabric API versions differ, and the runs were
  not repeated).

## 1.21.9 and later: not ported, and why

A compile against 1.21.10 shows a change in world generation itself, not only
in names: the noise router no longer has `initialDensityWithoutJaggedness`;
the preliminary surface level that aquifers use is now a density function of
its own (`preliminary_surface_level`, built with the new `find_top_surface`).
The mod computes that level on the GPU from the old search, so this needs a
new density-function node in the frontend, a new kernel path and its own
exactness matrix. `PalettedContainer.Strategy`, the spawn position and the
singleplayer-owner check also changed (small). Minecraft 1.21.11 and 26.x
additionally need a newer Fabric Loom than Gradle 8.14 can run (Loom 1.11.8 is
the newest that works here; 1.14 needs Gradle 9.2).
