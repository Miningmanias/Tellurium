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
  the rough surface and the plan, except a request for a full-detail tile that
  it would answer by generating chunks itself. For those the mod gets the
  chunks from the chunk system and runs DH's own chunk-to-LOD conversion on
  threads of its own (`compat/DistantHorizonsConverter.java`): the same
  methods, in the order DH's `INTERNAL_SERVER` generator calls them, writing
  into the data source DH supplied. DH does everything it does inside one
  thread budget (12 by default) and that conversion was most of it.
  If the conversion methods cannot be found, the request is passed on in DH's
  `INTERNAL_SERVER` mode instead, in which DH asks the server for the chunks
  and converts them itself (the "converter off" row below).
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
override itself. `hybrid` constructs DH's generator class and calls its
conversion classes, none of which is part of the API; all are found by name.
If the generator class is missing the mod logs it and registers nothing; if
the conversion methods are missing it falls back as described. The ignore set
is not part of the API either; if it is missing the mod logs it and DH does
the extra work. Nothing in DH is changed or patched. The conversion threads
are named with DH's thread prefix (`DH-WorldgenNext Convert Thread`), because
DH warns when its conversion runs on a thread without it.

## Later the same day: own column writer and unfinished chunks

Two further changes to `hybrid`, both on by default. The tables in the
sections below were measured before them and are kept as they were; the
figures with the changes are in this section.

- **Own column writer** (`compat/DistantHorizonsColumns.java`). DH's builder
  turns each chunk into a tile-sized data source and merges it into the tile,
  re-processing the whole tile once per chunk, sixteen times per tile; that
  merge was about 38% of the conversion. The mod now scans the chunk's
  columns itself and writes each into the tile, then removes hidden data
  points once. The rules for a column are DH's, the code is this mod's; light
  is still DH's own baked light, and its data-point encoding and id mapping
  are called. DH's builder fires an event per data point that lets other mods
  replace blocks or biomes; the writer does not, so while any mod listens to
  that event DH's builder is used instead. `-Dworldgennext.dh.columns=false`
  turns the writer off.
- **Unfinished chunks** (`distant_horizons.full_chunks = false`, the default).
  Chunks made for DH stop at the FEATURES step: no vanilla lighting, mob
  spawning or promotion to a full chunk, none of which DH uses. A tile is
  converted only when the ring of chunks around it has reached FEATURES too,
  because neighbours write trees and water across the border. The chunks are
  saved unfinished, as the game saves any partly generated chunk, and the
  game finishes them when a player gets there. They are not dropped: that
  would cut features at the border with real chunks later.

Server, `dh pregen`, 66,049 chunks, DH's defaults (same setup as below):

| Configuration | Chunks/s |
| --- | --- |
| DH alone | 449 |
| `hybrid` as in the sections below (DH's builder, full chunks) | 1,652–1,742 (four runs) |
| Own column writer, full chunks (`full_chunks = true`) | 1,893, 1,898, 1,915 |
| Own column writer, unfinished chunks (the default) | 2,041, 2,045, 2,080, 2,097, 2,119 |

About 4.6 times DH alone. With the writer, about 2 threads were converting
instead of about 6, the processors were 98% busy, and DH's own database and
lower-detail work kept about 9 of its 12 threads busy.

DH's storage settings were tried on top of the column writer with full
chunks (one run each): `dataCompression = "LZ4"` 1,977 (database 390 MB
instead of 268 MB), `"UNCOMPRESSED"` 1,968 (751 MB), LZ4 with
`databaseSyncMode = "OFF"` 1,945. Together with unfinished chunks LZ4 gave
2,080, no different from without. The mod does not change DH's settings.

Singleplayer, two minutes standing (as below): 77,216, 78,560 and 97,456
chunks refined in three runs (the last two with the check on), against 19,280
for DH alone and 75,488 before these changes. The run-to-run spread is as
large as the change, so in the game this is not shown to be faster than
before.

### Check against DH's builder

`-Dworldgennext.dh.columnsCheck=true` builds every tile with DH's builder as
well and compares the two column by column through DH's public getter:
heights, block, biome, block light and sky light of every data point, after
hidden points are removed. A tile that differs is built both ways again, up
to twice, because chunks that tick change between the two builds.

| Run | Columns compared | Differ |
| --- | --- | --- |
| Overworld, 16,384 chunks, then finished by the pregenerator | 4,194,304 | 0 |
| Nether, 9,232 chunks | 2,363,392 | 0 |
| End, 9,232 chunks | 2,363,392 | 0 |
| Overworld already generated by the pregenerator, 8,208 chunks | 2,101,248 | 0 |
| Overworld, 9,216 chunks (two earlier runs, one with full chunks) | 2,359,296 each | 0 and 4 |
| Singleplayer, two minutes | 18,906,865 | 706 |

The 4 and the 706 are in tiles at the world spawn and around the player, in
flowing water and lava and in the `distance` state of leaves: chunks that
were ticking while they were compared. Six more tiles in the singleplayer run
differed at first and matched when built again. That explanation fits where
and what the differences are; it was not proven for each column.

The check found a real defect while this was being built: stopping chunks one
step after FEATURES without waiting for the ring around the tile left 1,824
to 2,777 of 2,359,296 columns different between two reads, because
neighbours' trees and water were still arriving.

After the Overworld run the same area was given to this mod's pregenerator,
which finished the unfinished chunks; 27,956 chunks then read back with no
problems, and a repeat `dh pregen` asked for nothing.

Not covered: unit tests of the column rules (the comparison is the test);
Terralith and Tectonic; DH compression setting `MERGE_SAME_BLOCKS`; a mod that
listens to the chunk-processing event (the fallback was only seen to engage
by mistake, when DH's "nothing bound" answer was misread).

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
| `SURFACE_THEN_CHUNKS` (DH's default), 12 threads | 449 | 1,652, 1,667, 1,705 | 1,598–1,686 (eight runs) |
| `CHUNKS_ONLY`, 12 threads | 441 | not run | 1,644 |
| `CHUNKS_ONLY`, 24 threads | 731 | 1,754 | 1,822, 1,862 |
| `SURFACE_ONLY` (rough surface only), 12 threads | 373 | 368 (DH's rough generator, no chunks) | 1,667 (finished chunks) |
| `SURFACE_ONLY`, 24 threads | 716 | not run | not run |

One run per cell unless more figures are given. At DH's defaults both modes
produced full-detail LODs about 3.7 times as fast as DH alone. `direct`
ignores the plan; `hybrid` follows it, so under `SURFACE_ONLY` it generates no
chunks at all (0 provided, checked).

During a `hybrid` run the machine's processors were 90–93% busy. About as
many threads were running DH's conversion as were generating chunks, so on
this machine the rate is now limited by processor time, and converting a
chunk for DH costs about as much of it as generating the chunk. Doubling DH's
threads no longer makes much difference (1,754).

Other configurations of the same test (12 DH threads):

| Configuration | Chunks/s |
| --- | --- |
| DH alone, its `INTERNAL_SERVER` mode (16,641 chunks) | 143 |
| This mod loaded, `off`, DH's own generator (16,641 chunks) | 640, 654, 670 |
| This mod loaded, `off`, DH set to `INTERNAL_SERVER` (16,641 chunks) | 878 |
| `hybrid`, converter off (DH converts on its own threads) | 1,220, 1,262, 1,314, 1,317 |
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
| This mod, `hybrid`, converter off | all of it | 36,896 |
| This mod, `hybrid` | 251,824 chunks | 75,488 |
| This mod, `direct` | none | 72,480 by this mod's count; DH's database was not read after that run |

In every run with rough data DH spent roughly the first 40 s on it before
refining much. `hybrid` refined about 3.9 times as many chunks as DH alone in
the two minutes. Its rough data covered about three quarters of the area by
then instead of all of it: the rough pass is DH's own code and shares the
processors with the chunk generation and conversion. `direct` refined about
3.8 times as many, with nothing shown beyond what was finished.

## Checks

- **Nothing left out, by DH's own account.** The `dh pregen` command was
  given a second time in the same session (`REPEAT=1`). DH skips tiles it
  holds complete data for: the second pass took 0.8 s in both modes and asked
  for no chunks.
- **What DH stored.** After the `hybrid` run all 4,097 full-detail tiles in
  DH's database were marked as built from lit chunks.
- **No chunk left marked.** After `save-all flush` the count of chunks still
  in DH's ignore set was 0 (`/worldgennext status` reports it). Chunks that
  are already loaded, near a player or force-loaded are never marked.
- **Saved world.** 82,980 (`hybrid`) and 83,116 (`direct`) chunks in 100
  region files read back with no problems (`scripts/check-region-files.py`).
  About 820 MB of region files at the default compression level, next to a
  268 MB DH database.
- **Logs.** No errors from this mod or DH in the timed runs. One server
  process out of about seventy runs that day ended right after start-up with
  nothing in its log and no crash file; it did not happen again and was not
  explained. One vanilla
  message ("Failed to fetch mob spawner entity") appears in some runs with and
  without the mod. Stopping the server seconds after a pregen sometimes made
  DH log "database connection closed" from threads still writing.
- **Exactness of generation is unaffected.** Four rows of the digest matrix
  (vanilla Overworld, frozen ocean, eroded badlands, Terralith; 8,281 chunks
  each) passed after these changes.

## What changes besides speed

- **With the default `full_chunks = false` the saved chunks are unfinished**
  (see above); with `true` they are complete, as described next.
- **The chunks are real and are saved.** DH's default generator keeps nothing
  but LOD data. In `hybrid` and `direct` the world's region files grow as far
  as DH refines, about 10 KB per chunk here, as they do with DH's own
  `INTERNAL_SERVER` mode. A world explored afterwards finds those chunks
  already generated.
- **The rough pass can take longer to cover everything** in `hybrid`, as in
  the singleplayer run above.
- **DH's C2ME warning.** Only when the converter is unavailable does `hybrid`
  run DH's `INTERNAL_SERVER` code, which logs and shows in chat "C2ME missing
  … slow world gen speeds expected".
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
  with DH's own code, `hybrid` by calling the same methods in the same order
  on other threads. FULL generation is not reproducible run to run.
- `hybrid`, its converter and the ignore set rely on DH internals as they are
  in 3.3.3. A
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
- Read-ahead that follows DH's ring order around its centre, and converting
  tiles before DH asks for them, were both tried once the processors were
  saturated and were no faster (1,478–1,554 and 1,640–1,683); the simpler
  neighbour read-ahead stayed.
- Replacing DH's rough surface generator (for example with heights sampled on
  the GPU) was not attempted.
