# Fabric build for Minecraft 1.21.8

Builds `worldgennext-fabric-1.21.8-<version>.jar` from the shared 1.21.1
source, the files of `fabric-1214`, and two of its own:

| File | Purpose |
| --- | --- |
| `src/main/java/.../version/Version.java` | The 1.21.8 form of every call that differs between Minecraft versions: chunk tickets without keys (the mod counts them itself), NBT getters that return `Optional`, renamed methods |
| `src/main/resources/worldgennext/fused-qualified.properties` | The generators tested on 1.21.8 (vanilla only). A list holds for one Minecraft version; this one comes from this version's own matrix run |

Test files: `python scripts/fetch-fabric-test-files.py 1.21.8`. Runs:
`MODULE=fabric-1218 MODS_ROOT=build/test-mods/fabric-1218 PREFIX=f1218- scripts/verify-fast-matrix.sh`
and `FABRIC_MC=1.21.8 scripts/test-installed-fabric.sh`. What was verified and
what was not: `docs/evidence/minecraft-versions.md`.
