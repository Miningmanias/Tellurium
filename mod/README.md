# The mod

One source tree, built for every supported Minecraft version and loader.

```
mod/
  common/src/main/        the mod, written for Minecraft 1.21.1 (package dev.tellurium.neoforge on every loader)
  common/src/test/        unit tests (run by the NeoForge 1.21.1 build)
  common/src/gameTest/    NeoForge GameTests (the NeoForge 1.21.1 build)
  loader/neoforge/        Loader, NeoForgeEntry, neoforge.mods.toml
  loader/fabric/          Loader, FabricEntry, one mixin, fabric.mod.json, access widener
  versions/1.21.4/        what Minecraft 1.21.4 needs differently
  versions/1.21.8/        ... 1.21.8, on top of 1.21.4's
  versions/1.21.11/       ... 1.21.11, on top of both
  targets.gradle          the table of builds
  sources.gradle          how a build's sources are put together
  neoforge.gradle         the build script of every NeoForge build
  fabric.gradle           the build script of every Fabric build
  neoforge-diagnostics.gradle   GameTests and the early replay tasks (NeoForge 1.21.1 only)
  gpu-runtime.gradle      nests the Vulkan runtime in the jar
  targets/<name>/build/   build output of each build (nothing to edit there)
```

## Where a change goes

| Change | Place |
| --- | --- |
| Anything that is the same everywhere (almost everything) | `common/src/main` |
| Something that depends on the mod loader | a method of `loader/Loader`, in both `loader/neoforge` and `loader/fabric` |
| A Minecraft call that differs between versions | a method of `version/Version`: the 1.21.1 form in `common`, the others in `versions/<version>/src/main/java/.../version/Version.java`. Every `Version` has the same methods |
| A mixin whose target changed in a version | a copy of the same path under `versions/<version>/src/main/java`; it replaces the common file from that version on |
| A file with no counterpart in a version | a line in `versions/<version>/removed.txt`, and the version's own `tellurium.mixins.json` if it was a mixin |
| A name Minecraft merely changed | `versions/<version>/renames.gradle` (never logic) |
| A new Minecraft version or loader build | a line in `targets.gradle`, a folder under `versions/` if it needs files |

A version folder applies to that version and every later one that lists it in `targets.gradle`, until a later
folder has the same path.

## Building and running

`./gradlew build` builds everything and copies the release jars to `build/release/`. One build:
`./gradlew :fabric-12111:build`. The Gradle project names are the keys of `targets.gradle`
(`neoforge-1211`, `fabric-1211`, `fabric-1214`, `fabric-1218`, `neoforge-1218`, `fabric-12111`,
`neoforge-12111`), and the scripts take them as `MODULE=`:

- `MODULE=<name> scripts/run-pregen.sh 60 <mods folder>`: development server with a pregeneration
- `MODULE=<name> scripts/run-client-pregen.sh 45 <mods folder>`: development client, singleplayer
- `MODULE=<name> MODS_ROOT=build/test-mods/<name> PREFIX=<label>- scripts/verify-fast-matrix.sh`: exactness
  against serial vanilla
- `FABRIC_MC=<version> scripts/test-installed-fabric.sh`, `NEOFORGE_MC=<version>
  scripts/test-installed-neoforge.sh`: the built jar on a real server (`scripts/test-installed-commands.sh` for
  NeoForge 1.21.1)

A change in `common` is in every build, so it is checked on more than one: at least
`scripts/release-check.sh` (NeoForge 1.21.1) and the installed-server test of a 1.21.11 build. A change that
touches kernel text needs the exactness matrix of every version whose list of tested generators it affects
(`docs/CONTRACTS.md`).

What was verified on which build: `docs/evidence/fabric-port.md`, `docs/evidence/minecraft-versions.md`,
`docs/evidence/minecraft-1.21.11.md`.
