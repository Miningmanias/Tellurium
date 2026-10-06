# Tellurium

Faster chunk generation for Minecraft that **produces the same world**. For
NeoForge and Fabric on Minecraft 1.21.1 and 1.21.11, with builds for 1.21.4
and 1.21.8. No CUDA: the GPU part runs on Vulkan.

*The mod is called WorldgenNext in game: its jars, commands
(`/worldgennext`) and config file (`config/worldgennext.toml`) use that name.*

## What it does

- **Terrain and surface on the GPU** for the world generators on the tested
  list: vanilla Overworld, Nether and End, Terralith, Tectonic, Terralith with
  Tectonic, and Incendium; terrain for Amplified Nether and Nullscape. Any
  other generator, and any chunk the GPU flags as unsafe, uses vanilla code.
- **Structure, carver and feature steps in parallel**, with cheaper but
  equivalent versions of the hottest vanilla loops (ore veins, biome lookups).
- **Background saving**: chunks are encoded and compressed off the server
  thread.
- **Built-in pregenerator**: `/worldgennext pregen start <radius>`.
- Works with **Chunky**, **Distant Horizons** and **Voxy**.

## Throughput

Reference host: 24 logical cores, RTX 5070 Ti, 16 GB heap, ScalableLux,
chunks generated to FULL and saved. Chunks per second:

| World generator | Vanilla | Tellurium |
| --- | --- | --- |
| Vanilla Overworld | 124 | 2,620–2,860 |
| Tectonic | 90–105 | 2,702–2,790 |
| Terralith | 41–48 | 1,750–1,810 |
| Terralith + Tectonic | 56–62 | 2,019–2,042 |

Same server, same Chunky pregeneration of a vanilla world (35,721 and
142,129 chunks): vanilla 127 chunks/s; C2ME 930 and 1,064; C2ME with its
OpenCL module 2,020–2,070 and 2,140; Tellurium 2,960–2,970 and 3,479.

With Distant Horizons, a dedicated server built full-detail distant terrain
about 4.6 times as fast as Distant Horizons alone (2,040–2,120 chunks/s
against 449). With Voxy, 73,984 chunks around the player were filled in about
40 s.

## What "the same world" means

Through the SURFACE step, per-chunk digests (blocks, heightmaps, biomes,
structure starts and references) are identical to vanilla generating one
chunk at a time, in 15 contexts: three vanilla Overworld seeds, Nether, End,
seven biome-specific Overworld areas, Terralith, Tectonic and both together.
Later steps are checked piece by piece (ore veins, biome lookups, saved and
reopened chunks), with no differences found. Method and limits are in the
repository's README.

## Install

1. Minecraft 1.21.1 on Java 21, with NeoForge 21.1.176+ or with Fabric
   (loader 0.16+ and Fabric API). Minecraft 1.21.11 (NeoForge 21.11.45+ or
   Fabric), 1.21.8 (NeoForge 21.8.54+ or Fabric) and 1.21.4 (Fabric) are
   supported too; their tested lists are shorter.
2. A GPU with Vulkan and 64-bit float support for the GPU part. Without one,
   the mod logs why and keeps the CPU-side improvements.
3. Put the jar for your loader and version in `mods/`. On a multiplayer server
   only the server needs it; in singleplayer, install it on the client.
4. Recommended: **ScalableLux**. Lighting is not part of this mod, and every
   figure above was measured with it installed.

Start the server (or open a world) and run `/worldgennext status`. It names
the GPU, says what each dimension is generated with (and why, if that is
vanilla code), and lists anything worth changing.

## Commands

| Command | What it does |
| --- | --- |
| `/worldgennext status` | What is active, chunk counts, tips |
| `/worldgennext pregen start <radius>` | Generate a square, `radius` chunks in each direction from where you stand |
| `/worldgennext pregen start <radius> <x> <z>` | Same, centred on block coordinates |
| `/worldgennext pregen start worldborder` | Generate everything inside the world border |
| `/worldgennext pregen pause` / `resume` / `stop` / `status` | `resume` also continues a job a restart interrupted |

## Other world generators

A world generator that is not on the tested list generates with vanilla code
by default. To find out whether the GPU reproduces it, set
`gpu.mode = "check"`, pregenerate a few thousand chunks and look at
`/worldgennext status`: it counts the chunks compared with vanilla and the
chunks that differ. With no differences, `gpu.mode = "force"` turns the GPU on
for that world.

Run with this mod, all working: Biomes O' Plenty, Oh The Biomes We've Gone,
Nature's Spirit, Geophilic, William Wythers' Overhauled Overworld, nine YUNG's
structure mods, Dungeons and Taverns, When Dungeons Arise, Structory, Regions
Unexplored, Incendium, Amplified Nether, Nullscape, Lithium and Chunky.

## Not checked

Linux, GPUs other than an RTX 5070 Ti, world generators outside the tested
list, other mods that change chunk generation or saving, LAN play.

## Links

Source, full documentation and the compatibility table:
[github.com/Miningmanias/Tellurium](https://github.com/Miningmanias/Tellurium).
MIT licensed.
