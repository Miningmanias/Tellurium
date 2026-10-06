# NeoForge build for Minecraft 1.21.8

Builds `worldgennext-neoforge-1.21.8-<version>.jar` (NeoForge 21.8.54 or
later) through `gradle/neoforge-port.gradle`: the shared 1.21.1 source in
`neoforge-1211`, the files Minecraft 1.21.8 needs differently from
`fabric-1218` and `fabric-1214` (they are the same on both loaders), and this
module's own `src/main/resources/META-INF/neoforge.mods.toml`.

The module uses ModDevGradle 2.0.148; the development server of NeoForge 21.8
does not start with the 2.0.91 that `neoforge-1211` uses.

Test files: ScalableLux for NeoForge 1.21.8 in
`build/test-mods/neoforge-1218/vanilla`, and a NeoForge server installed in
`build/installed-neoforge-1.21.8` (the installer from maven.neoforged.net run
with `--installServer`). Runs:
`MODULE=neoforge-1218 MODS_ROOT=build/test-mods/neoforge-1218 PREFIX=n1218- scripts/verify-fast-matrix.sh`
and `NEOFORGE_MC=1.21.8 scripts/test-installed-neoforge.sh`. What was verified
and what was not: `docs/evidence/minecraft-versions.md`.
