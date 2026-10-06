# Fabric build for Minecraft 1.21.4

Builds `worldgennext-fabric-1.21.4-<version>.jar` from the shared 1.21.1 source
(`neoforge-1211`, `fabric-1211`) through `gradle/fabric-port.gradle`. This
module holds only what 1.21.4 needs differently:

| File | Purpose |
| --- | --- |
| `src/main/java/.../version/Version.java` | The 1.21.4 form of every call that differs between Minecraft versions, including which aquifer fluid-update rule the GPU kernels use |
| `src/main/java/.../mixin/SerializableChunkDataSurfaceMixin.java` | Keeps the mark of a chunk saved with its surface already built (1.21.1: `ChunkSerializerSurfaceMixin`) |
| `src/main/java/.../mixin/ChunkMapUnloadTypeMixin.java`, `IOWorkerGroupCommitMixin.java`, `IOWorkerHeaderFlushMixin.java` | 1.21.4 copies of mixins whose targets changed shape |
| `removed.txt` | Shared files with no counterpart on 1.21.4 |
| `src/main/resources/worldgennext.mixins.json` | The mixins that apply on 1.21.4 |
| `src/main/resources/worldgennext/fused-qualified.properties` | The generators tested on 1.21.4 (vanilla only) |
| `src/main/resources/fabric.mod.json` | Loader metadata; the Minecraft version is filled in by the build |

Test files (ScalableLux, Fabric API, the server launcher) come from
`python scripts/fetch-fabric-test-files.py 1.21.4`. Runs:
`MODULE=fabric-1214 MODS_ROOT=build/test-mods/fabric-1214 PREFIX=f1214- scripts/verify-fast-matrix.sh`
and `FABRIC_MC=1.21.4 scripts/test-installed-fabric.sh`. What was verified and
what was not: `docs/evidence/minecraft-versions.md`.
