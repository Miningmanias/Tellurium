# NeoForge build for Minecraft 1.21.11

Builds `worldgennext-neoforge-1.21.11-<version>.jar` (NeoForge 21.11.45 or
later) through `gradle/neoforge-port.gradle`: the shared 1.21.1 source, the
files Minecraft 1.21.11 needs differently from `fabric-12111`, `fabric-1218`
and `fabric-1214` (they are the same on both loaders), the renames in
`fabric-12111/renames.gradle`, and this module's own
`src/main/resources/META-INF/neoforge.mods.toml`.

NeoForge 21.11 has no additional runtime classpath for development runs
(`portNoAdditionalClasspath`); the mod's nested copy of the Vulkan runtime is
used there, as in a released jar.

Test files: ScalableLux for NeoForge 1.21.11 in
`build/test-mods/neoforge-12111/vanilla`, Terralith and Tectonic from
`python scripts/fetch-terrain-test-mods.py 1.21.11 neoforge build/test-mods/neoforge-12111`,
and a NeoForge server installed in `build/installed-neoforge-1.21.11`. Runs:
`MODULE=neoforge-12111 MODS_ROOT=build/test-mods/neoforge-12111 PREFIX=n12111- scripts/verify-fast-matrix.sh`
and `NEOFORGE_MC=1.21.11 scripts/test-installed-neoforge.sh`. What was
verified and what was not: `docs/evidence/minecraft-1.21.11.md`.
