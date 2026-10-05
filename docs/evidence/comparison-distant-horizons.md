# Distant Horizons LOD generation with and without WorldgenNext — 2026-10-05

## How Distant Horizons generates, and what was changed

Distant Horizons (DH) 3.3 fills the world beyond the loaded chunks in two
ways. Its **rough surface generator** samples the terrain height at about a
thousand points per tile, whatever the tile's size, and writes one surface
block, water and a biome per column; that is how far terrain appears within
seconds. Its **chunk generator** runs the game's generation steps on real
chunk data outside the server's chunk system and converts the result; under
the default plan (`SURFACE_THEN_CHUNKS`) it refines the rough data tile by
tile, 4×4 chunks at a time, one tile per worker thread.

With this mod the server's own chunk system is the fastest source of chunks,
so the mod redirects DH's chunk generation to it
(`compat/DistantHorizonsBridge.java`). Setting `distant_horizons.generator`:

- **`"hybrid"` (default).** The mod registers a generator override that wraps
  DH's own generator. Everything goes to DH's generator unchanged, including
  the rough surface and the plan, except that a request it would answer by
  generating chunks itself is passed on in DH's `INTERNAL_SERVER` mode, in
  which DH asks the server for finished chunks. The mod starts those chunks,
  and the tiles around them, before DH asks.
- **`"direct"`.** The override answers every request with finished chunks
  itself. DH's rough surface generator is not used: nothing is shown for an
  area until its chunks are done.
- **`"off"`.** DH is left alone.

In both modes:

- **Starting ahead.** DH keeps about 200 chunks in progress. The mod also
  starts the tiles around each request, up to 4,096 chunks (one per 4 MB of
  heap), and holds them until DH asks or 30 s pass.
- **No second build.** A real chunk also reaches DH through its ordinary
  chunk-load event, and DH then builds the same chunk's data a second time.
  DH's own `INTERNAL_SERVER` generator avoids that with an "ignore" set of
  chunk positions; the mod puts the chunks it starts into the same set until
  they have been saved once or unloaded
  (`compat/DistantHorizonsHandover.java`).

What is and is not DH's public API: `direct` uses only the API for the
override itself. `hybrid` constructs DH's generator class, which is not part
of the API; if that class is missing the mod logs it and registers nothing.
The ignore set is not part of the API either; if it is missing the mod logs
it and DH does the extra work.

## Server: every chunk at full detail (`dh pregen`)

- NeoForge 21.1.176 dedicated server for Minecraft 1.21.1 in
  `build/installed-server`, Java 21, 16 GB heap, 24 logical cores, RTX 5070 Ti,
  default `server.properties` except seed 0. Vanilla world generation.
- Distant Horizons 3.3.3 and ScalableLux 0.3.0-alpha.0.8 in every
  configuration; "with WorldgenNext" adds this mod's jar, default settings
  except the mode shown.
- Driver: DH's command `dh pregen start minecraft:overworld 0 0 128` on a
  fresh world from a cold start. Time is from the command to DH's "Pregen is
  complete" log line. The area is 257×257 chunks nominally (66,049, used for
  every rate below); DH asked for 65,536. This command asks for every tile at
  full detail, so the rough generator plays no part unless the plan is
  `SURFACE_ONLY`.
- `scripts/compare-dh.sh <dh|worldgennext> 128`, `DH_CONFIG=<file>` for DH's
  plan and thread count (its default here is 12).

Chunks of LOD per second:

| DH settings | DH alone | WorldgenNext `hybrid` | WorldgenNext `direct` |
| --- | --- | --- | --- |
| `SURFACE_THEN_CHUNKS` (DH's default), 12 threads | 449 | 1,262, 1,314, 1,317 | 1,598–1,686 (seven runs) |
| `CHUNKS_ONLY`, 12 threads | 441 | not run | 1,644 |
| `CHUNKS_ONLY`, 24 threads | 731 | 1,625 | 1,822, 1,862 |
| `SURFACE_ONLY` (rough surface only), 12 threads | 373 | 368 (DH's rough generator, no chunks) | 1,667 (finished chunks) |
| `SURFACE_ONLY`, 24 threads | 716 | not run | not run |

One run per cell unless more figures are given. At DH's defaults `hybrid`
produced full-detail LODs about 2.9 times as fast as DH alone and `direct`
about 3.7 times. `direct` ignores the plan; `hybrid` follows it, so under
`SURFACE_ONLY` it generates no chunks at all (0 provided, checked).

Other configurations of the same test (12 DH threads):

| Configuration | Chunks/s |
| --- | --- |
| DH alone, its `INTERNAL_SERVER` mode (16,641 chunks) | 143 |
| This mod loaded, `off`, DH's own generator (16,641 chunks) | 640, 654, 670 |
| This mod loaded, `off`, DH set to `INTERNAL_SERVER` (16,641 chunks) | 878 |
| `direct` without starting ahead and with the second build | 881, 903 |
| `direct` without starting ahead, no second build | 1,203, 1,221, 1,240 |
| `direct` with starting ahead and with the second build | 924 |

Thread dumps explain the last three rows. While DH rebuilt every chunk a
second time its thread budget was full, about half of it on that, and
starting chunks earlier changed nothing. Without the second build its
generation threads mostly waited for chunks, and starting ahead removed the
wait.

## Singleplayer: standing still for two minutes

`IDLE=120 scripts/run-client-pregen.sh 1 dh` opens a fresh world in the
development client (this mod's dev run with DH and ScalableLux, 8 GB heap, DH's
default settings: `SURFACE_THEN_CHUNKS`, 12 threads, 256-chunk LOD radius) and
closes it 120 s after the player joins. Afterwards
`scripts/dh-database-steps.py` counts DH's full-detail tiles by how they were
produced. One run each.

| Configuration | Area with rough data | Chunks refined to finished terrain |
| --- | --- | --- |
| DH alone (this mod switched off in its config) | 331,776 chunks (all of it) | 19,280 |
| This mod, `off` (DH's own generator on the faster engine) | all of it | 30,928 |
| This mod, `hybrid` | all of it | 36,896 |
| This mod, `direct` | none | 72,480 by this mod's count; DH's database could not be read after that run |

In every run with rough data DH spent roughly the first 40 s on it before
refining anything. In the game `hybrid` refined about 1.9 times as many chunks
as DH alone in the two minutes and `direct` about 3.8 times as many, with
nothing shown beyond what was finished. The rough fill itself is DH's code in
every case and was not made faster.

## Checks

- **Nothing left out, by DH's own account.** The `dh pregen` command was
  given a second time in the same session (`REPEAT=1`). DH skips tiles it
  holds complete data for: the second pass took 0.9 s (`hybrid`) and 0.8 s
  (`direct`) and asked for no chunks.
- **What DH stored.** After the `hybrid` run all 4,097 full-detail tiles in
  DH's database were marked as built from lit chunks.
- **No chunk left marked.** After `save-all flush` the count of chunks still
  in DH's ignore set was 0 (`/worldgennext status` reports it). Chunks that
  are already loaded, near a player or force-loaded are never marked.
- **Saved world.** 83,076 (`hybrid`) and 83,116 (`direct`) chunks in 100
  region files read back with no problems (`scripts/check-region-files.py`).
  About 820 MB of region files at the default compression level, next to a
  268 MB DH database.
- **Logs.** No errors from this mod or DH in the timed runs. One vanilla
  message ("Failed to fetch mob spawner entity") appears in some runs with and
  without the mod. Stopping the server seconds after a pregen sometimes made
  DH log "database connection closed" from threads still writing.
- **Exactness of generation is unaffected.** Four rows of the digest matrix
  (vanilla Overworld, frozen ocean, eroded badlands, Terralith; 8,281 chunks
  each) passed after these changes.

## What changes besides speed

- **The chunks are real and are saved.** DH's default generator keeps nothing
  but LOD data. In `hybrid` and `direct` the world's region files grow as far
  as DH refines, about 10 KB per chunk here, as they do with DH's own
  `INTERNAL_SERVER` mode. A world explored afterwards finds those chunks
  already generated.
- **DH warns about C2ME once.** In `hybrid` DH runs its `INTERNAL_SERVER` code,
  which logs and shows in chat "C2ME missing … slow world gen speeds expected".
  The figures above are with that warning; DH's
  `showSlowWorldGenSettingWarnings` setting hides it.
- **`direct` shows no rough terrain**, as described above.
- **DH lights the chunks itself** in both modes, as it does for any chunk it is
  given. That is DH's code and was not changed.

## Limits

- One machine, one seed, vanilla world generation, the Overworld. Terralith,
  Tectonic, the Nether and the End were not timed with DH.
- The singleplayer figures are one two-minute run each in a development
  client with the player standing still. Flying, a dedicated server with
  remote players, frame rate and how the terrain looks were not measured.
- LOD content was not compared between the routes. All of them convert chunks
  with DH's own code. FULL generation is not reproducible run to run.
- `hybrid` and the ignore set rely on DH internals as they are in 3.3.3. A
  chunk changed by something other than a player or a force-loaded area
  between being started for DH and its first save would have that change
  reach DH only at its next save.
- Larger tiles than 4×4 chunks are not offered to DH. With 16×16 tiles in
  `direct` DH stopped asking after 154 requests in the singleplayer test; the
  cause was not found.
- DH's `INTERNAL_SERVER` mode alone: one such run did not shut down cleanly;
  not investigated.
- Running this mod's pregenerator first and letting DH pick the chunks up
  from its chunk events is not a shortcut: DH's event queue dropped most of
  them, and its later pass still asked for 55,712 of 66,049.
- Replacing DH's rough surface generator (for example with heights sampled on
  the GPU) was not attempted.
