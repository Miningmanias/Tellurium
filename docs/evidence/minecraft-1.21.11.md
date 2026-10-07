# Minecraft 1.21.11 on Fabric and NeoForge — 2026-10-06

> Layout since the consolidation of 2026-10-06: the sources this document places in `neoforge-1211`,
> `fabric-1211`, `fabric-1214`, `fabric-1218`, `fabric-12111` and the two port scripts under `gradle/` are now
> `mod/common`, `mod/loader/<loader>`, `mod/versions/<version>`, `mod/sources.gradle`, `mod/fabric.gradle` and
> `mod/neoforge.gradle`; the names remain as the Gradle projects of the builds. See `mod/README.md`.

`fabric-12111` builds `tellurium-fabric-1.21.11-<version>.jar` and
`neoforge-12111` builds `tellurium-neoforge-1.21.11-<version>.jar`
(NeoForge 21.11.45 or later). Both come from the shared 1.21.1 source, the
files of the 1.21.4 and 1.21.8 modules, and what `fabric-12111` holds; how
that works is in `docs/evidence/minecraft-versions.md`.

## What changed in Minecraft, and what the mod does about it

World generation itself changed in 1.21.9:

| Change | Handling |
| --- | --- |
| The noise router has no `initialDensityWithoutJaggedness` any more. Its place is taken by `preliminary_surface_level`, a function that is the level itself; the game takes the floor of its value at y 0. Before, the game searched the density from the top of the world for the first value above 0.390625 | `Version.preliminarySurface` gives the function of either kind, in the same router root; `FusedNoiseCompiler.PreliminarySurface` tells the compiler which it is |
| In the vanilla Overworld that function is the new `find_top_surface`: a search with its own density, from an upper bound that is itself a function, rounded down to a step of 8, down to a lower bound of -64, for the first value above 0; the lower bound if there is none (before: the highest int) | The reader captures it; the lowering keeps it as three nested binary nodes under names of their own; the compiler turns it into the preliminary-surface kernel when it is the router's root and refuses it anywhere else. In the Nether and the End the function is the constant 0 |
| A new mapped operation, `invert` (1 / x), used in that upper bound | Lowered to the division node the frontend already had |
| Aquifers: above a level taken from the highest preliminary surface over the chunk's aquifer grid, a block is the global fluid and no aquifer is looked at | The aquifer kernel works the level out once per chunk and leaves it in a spare int of the chunk's first aquifer cell; the block kernel checks it. Put in by rewriting the kernel text, so the kernels of 1.21.1, 1.21.4 and 1.21.8 are character for character what they were |
| `Beardifier` keeps lists and a bounding box instead of two iterators; outside the box its value is 0 | The values are the same; `Version` names the fields and gives their entries |

Not generation:

| Change | Handling |
| --- | --- |
| `ResourceLocation` is `Identifier` (and `ResourceKey.location()` is `identifier()`); `Util` moved to `net.minecraft.util` | A port may name plain renames (`portRenames`, here in `fabric-12111/renames.gradle`). The build applies them line by line to a copy of every source file that is not the port's own and compiles the copy; line numbers stay |
| `ChunkStorage` is gone (`ChunkMap` extends `SimpleRegionStorage`) | The two mixins on it only served the 1.21.1 save and load paths and are left out |
| `PalettedContainer` has no public constructor from a palette and packed indices | `Version.blockStates` goes through the game's own `unpack` |
| A ticket kind has flags instead of a use | `Version.Ticket`: loading, simulation and "keep the dimension active" |
| Permissions are a set, not a level; the spawn position moved; the singleplayer-owner check takes a `NameAndId` | `Version` |
| `SerializableChunkData.parse` takes the container factory | A 1.21.11 copy of `SerializableChunkDataSurfaceMixin` |

Tooling: Fabric API for 1.21.11 needs Fabric Loom 1.13.3 or later; all Fabric
modules now use 1.13.6, which runs on the Gradle 8.14 the project has.
NeoForge 21.11 has no "additional runtime classpath" for development runs; the
mod's nested copy of the Vulkan runtime is what such a run uses, as a released
jar does.

## Verification

Reference host (RTX 5070 Ti, 24 logical cores), Java 21. Fabric: loader
0.19.5, Fabric API 0.141.6+1.21.11, ScalableLux 0.3.0-alpha.0.3 (the first
version Modrinth returns for Fabric 1.21.11; it is an alpha). NeoForge:
21.11.45, ScalableLux 0.1.6. One run per figure.

| Check | Fabric 1.21.11 | NeoForge 1.21.11 |
| --- | --- | --- |
| SURFACE digests against serial vanilla, 8,281 chunks per context, the 12 vanilla contexts (`MODULE=<module> MODS_ROOT=build/test-mods/<module> PREFIX=<label>- scripts/verify-fast-matrix.sh`), development environment | 12 of 12 identical | 12 of 12 identical |
| Built jar on a real server, pregeneration to FULL, 160,801 chunks, 16 GB heap (`FABRIC_MC=1.21.11 scripts/test-installed-fabric.sh 200`, `NEOFORGE_MC=1.21.11 scripts/test-installed-neoforge.sh 200`) | 57 s, 2,817 chunks/s; 163,977 chunks' terrain on the GPU, 23 on the CPU; no errors; 178,929 chunks read back with no problems | 58 s, 2,768 chunks/s; the same counts; no errors |
| The same, 90,601 chunks on a 4 GB heap | passes; no out-of-memory, no "not keeping up" warning; 104,329 chunks read back | not run |
| The same server with `gpu.mode = "check"` (vanilla code generates, the GPU result is compared block by block) | Overworld 7,200, Nether 7,225, End 7,225 chunks compared, 0 differ | the same counts, 0 differ |
| Save and reopen, 8,281 chunks to FULL (`MODULE=<module> scripts/verify-save-reopen.sh 45 <module>/vanilla`) | PASS | PASS |

The first run of the matrix on Fabric 1.21.11, three contexts, passed as
written; nothing in the kernels had to be corrected afterwards.

The released Fabric jar runs with the names a released game has, so the check
mode run there also shows that `find_top_surface` and its parts are found
under those names.

After the changes to shared code:

- NeoForge 1.21.1: `scripts/release-check.sh` passes (build, exactness 3 of 3,
  carvers, save and reopen, kill-recovery, console commands on the installed
  server).
- Fabric 1.21.1: vanilla Overworld, End and Tectonic rows identical (3 of 3);
  the released jar on a real server recognises the Overworld as the tested
  vanilla generator (41,900 chunks' terrain on the GPU), which shows the
  1.21.1 kernel text is unchanged.
- Fabric 1.21.4, Fabric 1.21.8 and NeoForge 1.21.8: each released jar on its
  server recognises its tested generator and generates on the GPU with no
  errors (15,502 chunks). Nothing else was repeated there.

## Terralith and Tectonic

Fetched by `scripts/fetch-terrain-test-mods.py` (the first version Modrinth
returns for 1.21.11 and the loader): Terralith 2.6.1, Tectonic 3.0.19,
Lithostitched 1.7.2. Rows `terralith`, `tectonic` and `combined` of the
matrix, 8,281 chunks at SURFACE against serial vanilla with the same mods.

| Row | Fabric 1.21.11 | NeoForge 1.21.11 |
| --- | --- | --- |
| Terralith | identical; all chunks on the GPU, 2,214 chunks/s against 99 | identical; 2,261 against 98 |
| Tectonic | identical; all chunks on the GPU, 863 chunks/s against 130 | identical; 822 against 128 |
| Terralith with Tectonic | identical; all chunks on the GPU, 802 chunks/s against 91 | identical; 880 against 91 |

All three are on the 1.21.11 tested list. The released jars with both mods
on real servers (`MODS=build/test-mods/<module>/combined ...
scripts/test-installed-*.sh 60`): the Overworld is recognised as the tested
combination and 15,597 chunks' terrain is generated on the GPU with no errors,
on both loaders. The rates are the matrix's own (to
SURFACE status, serial vanilla as the reference), one run each. With Tectonic
the GPU rate is about a quarter of what the Tectonic row gives on 1.21.1
(3,273 chunks/s there); why was not looked into.

## Singleplayer

In the development client of each loader (`MODULE=<module>
scripts/run-client-pregen.sh 45 <module>/vanilla`): a singleplayer world
opened and 8,281 chunks were pregenerated with terrain on the GPU, at 1,761
chunks/s on Fabric and 2,144 on NeoForge. No errors from the mod; the Fabric
client logged two of the game's own (it could not reach the account and
Realms services from a development login).

## Not verified on 1.21.11

- No released client, no play session, no Distant Horizons, no Voxy.
- Kill-recovery and the console command walk-through were not repeated.
- No world generator mod beyond the rows above.
- Throughput is one run each and not a comparison with other versions.
- Minecraft 1.21.9 and 1.21.10 were not built or run; the jars declare 1.21.11
  only.
