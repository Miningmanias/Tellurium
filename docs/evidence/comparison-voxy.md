# Generating terrain for Voxy — 2026-10-06

## What was built

Voxy draws distant terrain from chunks it has been given; by itself it shows
only where the player has been. When Voxy runs in the same game as the server
(singleplayer, or the host of a LAN world), `compat/VoxyBridge.java` generates
the chunks around each player with the server's own chunk generation and
passes each finished chunk to Voxy's ingest service, the entry Voxy itself
uses for chunks the client receives.

- **Order.** 16×16-chunk tiles, nearest tile to the player first, each tile
  filled before the next is begun, out to `voxy.radius` chunks (default 128).
- **Window.** As many chunks in progress as the pregenerator uses (one per
  12 MB of heap, at most 1,024). Nothing is started while Voxy's ingest queue,
  which has no limit of its own, holds more than 8,192 sections.
- **Memory of what was done.** The positions handed over are kept per
  dimension in `<world>/tellurium-voxy/`, so a later session generates and
  loads nothing twice. `/tellurium voxy forget` clears that for the
  dimension the command is run in, for after Voxy's stored data was deleted.
- **Voxy is not changed.** Two of its methods are looked up by name
  (`VoxelIngestService.tryAutoIngestChunk`, and the queue length). On a
  dedicated server there is no Voxy in the process and nothing happens.
- If the separate Voxy WorldGen mod is installed, this mod leaves the job to
  it and says so in the log and in `/tellurium status`.

## Setup

- Development client of this mod (`runClient`) on NeoForge 21.1.255 for
  Minecraft 1.21.1, Java 21, 8 GB heap, RTX 5070 Ti, 24 logical cores, a fresh
  singleplayer world with seed 0, player standing at the spawn.
- Voxy 0.2.16-beta (the original Fabric jar for Minecraft 1.21.11) loaded
  through Roxy 0.3.3, with Sodium 0.8.12, Forgified Fabric API 0.116.14 and
  ScalableLux 0.3.0-alpha.0.8. Roxy needs NeoForge 21.1.224 or later, which is
  why these runs are not on the 21.1.176 the mod is built against
  (`NEOFORGE=21.1.255` in the script).
- `NEOFORGE=21.1.255 CREATE_MODS=vanilla IDLE=150 scripts/run-client-pregen.sh 1 <mods>`
  with `<mods>` = `voxy`, or `voxy-worldgen` for the comparison (the same plus
  Voxy WorldGen 2.4.2 for NeoForge 1.21.1, Cloth Config and Better ModList).
  The client is closed 150 s after the player joins.

## Results

One run each.

| Configuration | Chunks in the world's region files after 150 s |
| --- | --- |
| Voxy WorldGen, this mod switched off in its config | 15,167 |
| Voxy WorldGen on this mod's chunk generation (`voxy.generate = false`) | 29,588 |
| This mod's generation for Voxy | 86,436 |

About 3,400 of each figure are the chunks around the player that the game
loads anyway. Voxy WorldGen ran with the settings it wrote for itself
(`generationRadius` 128, `maxActiveTasks` 6).

This mod's run was not limited by time: it had handed over the whole area,
73,984 chunks (272×272), about 40 s after the player joined and then had
nothing left to do. From its own log, at ten-second intervals: 22,917,
42,675, 62,075, 73,984 chunks handed over, about 1,950 chunks/s while it was
working. Voxy WorldGen was still generating when its runs were closed, at
roughly 80 and 175 chunks/s.

## Checks

- **Memory.** Heap in use after garbage collection stayed between 2.2 and
  2.8 GB of 8 GB and at most 6,658 chunks were loaded.
- **A second session generates nothing.** The same world was opened again:
  0 chunks handed over, 0 errors.
- **A moving player.** With the player moved 96 blocks every 500 ms for 60
  steps (`TOUR=60`), 55,034 chunks were handed over in the 40 s, the average
  server tick was 10.5 ms (longest recent 87 ms), no errors.
- **Saved world.** 86,436 chunks in 100 region files read back with no
  problems.
- **Logs.** No errors from this mod, Voxy or Roxy in these runs.

## Two defects found on the way

- Asking the chunk system for a chunk from the server thread waits for that
  chunk. The first version did, and generated one chunk at a time; requests
  now come from another thread.
- Filling outward chunk by chunk in rings around the player keeps the whole
  ring's surroundings loaded half-generated. At this radius that was over
  20,000 loaded chunks and the 8 GB client ran out of memory after about
  50,000 chunks. Filling a tile at a time fixed it. The pregenerator, which
  already works tile by tile, did 40,401 chunks at up to 2,710 chunks/s in the
  same client.

## Limits

- **What Voxy shows was not looked at.** These runs count chunks accepted by
  Voxy's ingest service and its queue emptying; nobody looked at the screen.
- Singleplayer and LAN host only. A dedicated server sends nothing to a
  client's Voxy; Voxy WorldGen has networking for that and this mod does not.
- One machine, one seed, vanilla Overworld, one run per figure, Voxy
  0.2.16-beta through Roxy 0.3.3 only. A native NeoForge port of Voxy was not
  tried.
- Voxy WorldGen was run with its own defaults; raising its `maxActiveTasks`
  was not tried.
- The chunks are generated completely and saved, about 10 KB each, as with
  Voxy WorldGen. Stopping before the last steps, as is done for Distant
  Horizons, was not tried here: Voxy takes its light from the chunk, so the
  chunk has to be lit.
- If Voxy's ingest is switched off in its settings every hand-over is
  refused; the mod then waits 30 s before asking again and
  `/tellurium status` shows the count.
