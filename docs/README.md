# Tellurium documentation

## Using the mod

| Page | What is in it |
| --- | --- |
| [README](../README.md) | What the mod does, downloads, commands, settings, speed |
| [Configuration](CONFIGURATION.md) | Every option of `config/tellurium.toml`, the commands, and the developer switches |
| [Compatibility](COMPATIBILITY.md) | Other mods it was run with, and what happened |
| [Troubleshooting](TROUBLESHOOTING.md) | The GPU is not used, a world generator is not recognised, crashes, turning things off |
| [Changelog](../CHANGELOG.md) | What changed in each release |

## What was measured

| Page | What is in it |
| --- | --- |
| [Throughput](evidence/throughput-fused-gpu.md) | Chunks per second, how they were measured, history |
| [Smoothness](evidence/smoothness.md) | Stutter with Distant Horizons, Voxy and fast flight |
| [Distant Horizons](evidence/comparison-distant-horizons.md), [Voxy](evidence/comparison-voxy.md), [C2ME](evidence/comparison-c2me.md) | Comparisons |
| [Minecraft versions](evidence/minecraft-versions.md), [1.21.11](evidence/minecraft-1.21.11.md), [Fabric](evidence/fabric-port.md) | What was run on which build |
| [Status](STATUS.md) | A dated log of what was done and what it was checked with |

## Working on the mod

| Page | What is in it |
| --- | --- |
| [Development](DEVELOPMENT.md) | Building, the checks to run for a change, the modules |
| [mod/README](../mod/README.md) | One source tree for every Minecraft version and loader |
| [Contracts](CONTRACTS.md), [Testing](TESTING.md), [Architecture](ARCHITECTURE.md) | The rules generation code is held to |
| [Releasing](RELEASING.md) | What a release has to pass |
| [File map](FILE_MAP.md) | Every file and what it is for |
| Code reviews: [2026-10-06](evidence/code-review-20261006.md), [2026-10-07](evidence/code-review-20261007.md) | Findings and what was done about each |

## History

The repository also holds an earlier, stricter qualification route (an independent Minecraft oracle and
a bit-exact GPU route behind evidence gates). It ships as developer tooling under `/tellurium dev` and
generates nothing by itself. Its plan and records: [Plan.md](../Plan.md), [V0.2-PLAN.md](V0.2-PLAN.md),
[v0.2/](v0.2), [design/](design), and the `v0.1-*` and `v0.2-*` pages under [evidence/](evidence).
