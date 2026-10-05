# WorldgenNext, C2ME and C2ME OpenCL: pregeneration speed — 2026-10-05

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
| WorldgenNext | this mod, commit after 9ba45fc, default settings |

NeoForge 21.1.255 and Java 25 were needed for C2ME 0.4.0: on NeoForge 21.1.176
its Java 25 class files cannot be read by the loader, on Java 21 or 25.
WorldgenNext's other measurements in this repository are on 21.1.176 and
Java 21.

## Results

Chunks per second, each mod with its own defaults:

| Configuration | 35,721 chunks (radius 1500) | 142,129 chunks (radius 3000) |
| --- | --- | --- |
| vanilla | 127 (one run) | not run |
| C2ME | 927, 930 | 1,064 (with Chunky's limit raised by hand) |
| C2ME OpenCL | 2,018, 2,039, 2,059, 2,074 | 2,135, 2,152 |
| WorldgenNext | 2,960, 2,971 | 3,479 |

On these runs WorldgenNext generated about 45% more chunks per second than
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
| WorldgenNext before this comparison | 1,052 | 2,992 (35,721 chunks), 3,339 (142,129) |

The first round therefore showed C2ME OpenCL at about twice WorldgenNext's
speed with everything left at defaults, purely because one mod raised
Chunky's limit and the other did not: with 50 chunks in flight WorldgenNext
used about 35% of the CPU. WorldgenNext now sets the property too when it is
unset (`pregen.tune_chunky`, to `pregen.in_flight` reduced on small heaps),
which is what the Results table measures. Intermediate point: limit 200 gave
2,065.

## Limits of this comparison

- One machine, one seed, vanilla world generation only, Chunky's default
  (concentric) pattern, cold starts. Two to four runs for most cells, one for
  some; the spread within a cell was under 3%.
- Speed only. Whether C2ME or C2ME OpenCL output is identical to vanilla was not
  examined here; WorldgenNext's is checked elsewhere in this repository, on
  NeoForge 21.1.176 and Java 21, not on this server.
- C2ME was run with its default configuration. I did not look for C2ME settings
  that might raise its throughput.
- The earlier claim in this repository that C2ME OpenCL targets "2,500+" came
  from its project page; the figures above are what it did here.
- WorldgenNext's built-in pregenerator was not part of this comparison; on the
  NeoForge 21.1.176 server it did 251,001 chunks at 3,378 chunks/s.
