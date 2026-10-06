# Fabric build for Minecraft 1.21.11

Builds `worldgennext-fabric-1.21.11-<version>.jar` from the shared 1.21.1
source, the files of `fabric-1218` and `fabric-1214`, and its own:

| File | Purpose |
| --- | --- |
| `renames.gradle` | Names Minecraft changed by 1.21.11 without anything else changing (ResourceLocation to Identifier, Util's package); the build applies them to a copy of the shared source. The NeoForge module of this version applies the same file |
| `src/main/java/.../version/Version.java` | The 1.21.11 form of every call that differs between Minecraft versions, including where the preliminary surface comes from since 1.21.9 |
| `src/main/java/.../mixin/SerializableChunkDataSurfaceMixin.java` | 1.21.11 copy: the parse method takes the container factory |
| `removed.txt`, `src/main/resources/worldgennext.mixins.json` | Two mixins on `ChunkStorage`, which is gone |
| `src/main/resources/worldgennext/fused-qualified.properties` | The generators tested on 1.21.11 (on both loaders) |

Test files: `python scripts/fetch-fabric-test-files.py 1.21.11` and
`python scripts/fetch-terrain-test-mods.py 1.21.11 fabric build/test-mods/fabric-12111`.
Runs: `MODULE=fabric-12111 MODS_ROOT=build/test-mods/fabric-12111 PREFIX=f12111- scripts/verify-fast-matrix.sh`
and `FABRIC_MC=1.21.11 scripts/test-installed-fabric.sh`. What changed in
Minecraft, what was verified and what was not: `docs/evidence/minecraft-1.21.11.md`.
