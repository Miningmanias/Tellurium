# Tellurium, C2ME and C2ME OpenCL: pregeneration speed — 2026-10-05

## Setup

One machine (24 logical cores, RTX 5070 Ti, Windows 11), one server
installation, one driver. Only the chunk-generation mod changes between rows.

- NeoForge 21.1.255 dedicated server for Minecraft 1.21.1, installed with the
  official installer into `build/installed-server-255`, Java 25 (Temurin
  25.0.4), 16 GB heap, default `server.properties` except seed 0
  (`sync-chunk-writes=true`).
- ScalableLux 0.3.0-alpha.0.8 and Chunky 1.4.23 in every configuration.
- Driver: `chunky radius <blocks>` then `chunky start` on a fresh world, from a
  cold start. Time is from Chunky's "Task started" log line to its "Task
  finished" line.
- `scripts/compare-chunk-mods.sh <configuration> <radius>` with
  `SERVER=build/installed-server-255` and `JDK=<Java 25 home>`.

| Configuration | Added mod |
| --- | --- |
| vanilla | none |
| C2ME | Concurrent Chunk Management Engine (NeoForge) 0.4.0-alpha.0.122 |
| C2ME OpenCL | the same plus C2ME OpenCL Acceleration Module 0.4.0-alpha.0.122 (its log shows the OpenCL device as the RTX 5070 Ti) |
| Tellurium | this mod, commit after 9ba45fc, default settings |

NeoForge 21.1.255 and Java 25 were needed for C2ME 0.4.0: on NeoForge 21.1.176
its Java 25 class files cannot be read by the loader, on Java 21 or 25.
Tellurium's other measurements in this repository are on 21.1.176 and
Java 21.

## Results

Chunks per second, each mod with its own defaults:

| Configuration | 35,721 chunks (radius 1500) | 142,129 chunks (radius 3000) |
| --- | --- | --- |
| vanilla | 127 (one run) | not run |
| C2ME | 927, 930 | 1,064 (with Chunky's limit raised by hand) |
| C2ME OpenCL | 2,018, 2,039, 2,059, 2,074 | 2,135, 2,152 |
| Tellurium | 2,960, 2,971 | 3,479 |

On these runs Tellurium generated about 45% more chunks per second than
C2ME OpenCL at the smaller size and about 60% more at the larger one, and
about three times as many as C2ME alone.

## What "its own defaults" hides

Chunky asks for at most 50 chunks at a time unless the system property
`chunky.maxWorkingCount` says otherwise, and that limit decides the result for
a fast generator:

| Configuration | Chunky limit 50 | Chunky limit 1,024 (set by hand) |
| --- | --- | --- |
| C2ME | 927, 930 | 1,032 |
| C2ME OpenCL | n/a: the module sets the limit to 512 itself | 2,017 |
| Tellurium before this comparison | 1,052 | 2,992 (35,721 chunks), 3,339 (142,129) |

The first round therefore showed C2ME OpenCL at about twice Tellurium's
speed with everything left at defaults, purely because one mod raised
Chunky's limit and the other did not: with 50 chunks in flight Tellurium
used about 35% of the CPU. Tellurium now sets the property too when it is
unset (`pregen.tune_chunky`, to `pregen.in_flight` reduced on small heaps),
which is what the Results table measures. Intermediate point: limit 200 gave
2,065.

## Limits of this comparison

- One machine, one seed, vanilla world generation only, Chunky's default
  (concentric) pattern, cold starts. Two to four runs for most cells, one for
  some; the spread within a cell was under 3%.
- Speed only. Whether C2ME or C2ME OpenCL output is identical to vanilla was not
  examined here; Tellurium's is checked elsewhere in this repository, on
  NeoForge 21.1.176 and Java 21, not on this server.
- C2ME was run with its default configuration. I did not look for C2ME settings
  that might raise its throughput.
- The earlier claim in this repository that C2ME OpenCL targets "2,500+" came
  from its project page; the figures above are what it did here.
- Tellurium's built-in pregenerator was not part of this comparison; on the
  NeoForge 21.1.176 server it did 251,001 chunks at 3,378 chunks/s.

## Moving load ("flying") — 2026-10-05

`scripts/compare-flight.sh`, same server, Java and mods as above (no Chunky).
A 15×15-chunk force-loaded square is moved 96 blocks per step across
ungenerated terrain with vanilla console commands, so each step asks for 90
new chunks and releases 90 behind. `/forceload` generates the newly covered
chunks on the server thread, one request at a time, before the command
returns; a server that cannot finish a step in the step's time falls behind
the schedule. This is the neutral stand-in for the in-mod player tour, which
needs this mod and a client: it is not a real player and sends nothing to a
client.

| Configuration | 120 steps, one per 500 ms (60 s schedule) | 300 steps, one per 100 ms (30 s) | 300 steps, one per 50 ms (15 s), two runs |
| --- | --- | --- | --- |
| vanilla | 127.0 s | not run | not run |
| C2ME | 61.9, 62.0 s | 57.0 s | 55.9, 57.5 s |
| C2ME OpenCL | 61.9, 62.0 s | 46.6 s | 46.2, 46.5 s |
| Tellurium | 62.0, 62.1 s | 74.2 s | 74.5, 75.0 s |

At 180 chunks per second all three mods keep to the schedule and vanilla
takes twice as long. Pushed past what any of them can sustain, the route's
27,000 chunks take about 46 s with C2ME OpenCL (about 585 chunks/s), 57 s with
C2ME (about 480) and 75 s with Tellurium (about 365). **For chunks
requested one at a time on the server thread, Tellurium is the slowest of
the three**, about four times vanilla.

Setting `tellurium.fast.maxDelayMicros=0`, turning the scheduling threads
off, or turning the GPU off (85.5 s) did not close the gap, so it is not the
GPU batching delay. With a single request in flight the work available is
whatever one chunk's neighbourhood needs, stage by stage; this mod speeds up
the stages and their hand-offs but keeps vanilla's chunk system, whereas C2ME
replaces its scheduling. I have not profiled C2ME, so that explanation is an
inference.

`/tick query` was also sampled, and is not reported as a result: the server
subtracts time spent waiting for chunks from its tick times, so the figures
(0.7 ms for vanilla while it was falling a minute behind; 17, 20 and 25 ms
for C2ME OpenCL, C2ME and Tellurium) describe server-thread work, not
responsiveness. All saved worlds validated with no problems.

The earlier in-client player tour (asynchronous player tickets, many chunks
requested at once) was run only for Tellurium and for the mod disabled;
no like-for-like tour with C2ME exists.
