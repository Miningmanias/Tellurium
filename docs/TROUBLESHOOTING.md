# Troubleshooting

Start with `/tellurium status`. It names the graphics card in use, says what each dimension is generated
with and why, counts chunks made on the GPU and on the processor, and repeats any problem found in
`config/tellurium.toml`.

## The GPU is not used

| What the status or the log says | What it means and what to do |
| --- | --- |
| `GPU terrain generation is unavailable ... Reason: ...` | No usable graphics card was found. It needs Vulkan 1.2 with 64-bit floats and 64-bit integers in shaders; update the graphics driver. Integrated graphics on older processors often lack 64-bit floats. The processor-side improvements stay on. |
| `... is not on the tested list` | This world generator (a datapack or a terrain mod) has not been compared with vanilla, so vanilla code builds it. Set `gpu.mode = "check"` to compare on your world; see the README. |
| `... the GPU kernels do not support this world generator (...)` | The generator uses something the GPU code cannot do. Vanilla code builds that dimension; nothing to change. |
| `vanilla generation: not a noise-based generator` | Flat and debug worlds, and mods with their own generator, are not touched. |
| `the GPU stopped responding ...` | The driver reset or the card was lost. Terrain is built on the processor until the next restart; chunks already made are fine. |
| Chunk counts show many "on the CPU" | Chunks the GPU flags as unsafe are rebuilt by vanilla code. A few are normal; a large share is worth reporting with the world seed. |

## Turning things off

- Everything: `enabled = false` in `config/tellurium.toml`. The game then generates and saves exactly as
  without the mod.
- Only the GPU: `gpu.mode = "off"`.
- Only parallel generation steps: `generation.parallel_steps = false`.
- Only background saving: `saving.async = false`.
- Distant Horizons or Voxy feeding: `distant_horizons.generator = "off"`, `voxy.generate = false`.

Changes take effect on the next start. If a problem goes away with one of these, say which in the report.

## The game stutters or runs out of memory

- The pregenerator works on 1,024 chunks at a time by default and reduces that on a small heap. On less
  than about 12 GB, lower `pregen.in_flight` or give the game more memory.
- With Distant Horizons or Voxy in singleplayer, far terrain shares the processor and memory with the
  game. `voxy.radius` and Distant Horizons' own render distance decide how much is generated.
- [What was measured](evidence/smoothness.md).

## Worlds and saving

- Saved worlds are about 12% larger by default; `saving.compression_level = 6` gives vanilla's size.
- Chunks generated for Distant Horizons are saved unfinished (no lighting) and completed when a player
  gets there; `distant_horizons.full_chunks = true` generates them completely.
- Removing the mod is safe: worlds are ordinary Minecraft worlds.
- A pregeneration interrupted by a crash or a restart continues with `/tellurium pregen resume`.

## Reporting a problem

Open an issue at <https://github.com/Miningmanias/Tellurium/issues> with:

- the Minecraft version, the loader and its version, and the Tellurium file name;
- the graphics card and driver version, and the operating system;
- the output of `/tellurium status`;
- `logs/latest.log` (and the crash report, if there is one);
- other mods and datapacks, and the world seed if the problem is in the terrain.
