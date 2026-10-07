# Changelog

## 0.2.0-alpha.1

First public alpha.

**Builds**: Minecraft 1.21.1 and 1.21.11, for NeoForge and for Fabric.

**What is in it**

- Terrain and surface generation on the GPU (Vulkan) for tested world generators: vanilla Overworld,
  Nether and End, Terralith, Tectonic, both together, Incendium; terrain for Amplified Nether and
  Nullscape. On 1.21.11: vanilla, Terralith, Tectonic and both together. Everything else is built by
  vanilla code.
- Structure, carver and feature steps of different chunks in parallel.
- Background chunk saving, with a choice of compression level.
- A pregenerator: `/tellurium pregen start <radius>`, pause, resume (also after a restart), stop.
- `/tellurium status`, and `config/tellurium.toml`; `gpu.mode = "check"` compares the GPU with vanilla
  on any world.
- Distant Horizons and Voxy are fed from Tellurium's generation, paced to keep the game smooth.
- Chunky's limit on chunks in progress is raised when Chunky is installed.

**Known limits**

- Run on one machine only (Windows 11, RTX 5070 Ti). Linux and other graphics cards are untested.
- On 1.21.11, Distant Horizons, Voxy and a released client have not been run.
- Saved worlds are about 12% larger by default (`saving.compression_level = 6` for vanilla's size).
- Chunk save listeners of other NeoForge 1.21.1 mods may see chunk data before it is complete;
  `saving.async = false` avoids that.
- The mod id is `tellurium`. Another mod on Modrinth uses the same id; the two cannot be installed
  together.

The mod was called WorldgenNext during development; `config/worldgennext.toml` is copied to
`config/tellurium.toml` on first start.
