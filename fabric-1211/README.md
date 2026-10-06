# Fabric build for Minecraft 1.21.1

Builds `worldgennext-fabric-1.21.1-<version>.jar`. The mod's code is the same
as on NeoForge and lives in `neoforge-1211/src/main/java`; this module adds
only what differs on Fabric:

| File | Purpose |
| --- | --- |
| `src/main/java/.../loader/Loader.java` | What the mod needs from the loader: config directory, loaded mods, tick and chunk-unload callbacks, and the table of Minecraft names for a released game |
| `src/main/java/.../loader/FabricEntry.java` | Entry point; forwards Fabric API's lifecycle and command events |
| `src/main/java/.../loader/mixin/StructurePaletteCacheMixin.java` | Makes a vanilla structure cache thread-safe (NeoForge patches it itself) |
| `src/main/resources/fabric.mod.json`, `worldgennext.fabric.mixins.json`, `worldgennext.accesswidener` | Loader metadata |

`./gradlew :fabric-1211:build` needs Fabric Loom 1.11 (Gradle 8.14), fetched
from `maven.fabricmc.net`. The build writes `worldgennext/fabric-names.tsv`
into the jar: see `docs/evidence/fabric-port.md` for why, and for what was
verified on Fabric and what was not.

Runs: `MODULE=fabric-1211 scripts/run-pregen.sh 60 fabric-vanilla` (development
server), `MODULE=fabric-1211 MODS_ROOT=build/test-mods/fabric PREFIX=fabric- scripts/verify-fast-matrix.sh`
(exactness matrix), `scripts/test-installed-fabric.sh` (the built jar on a real
Fabric server).
