# Minecraft 1.21.1 frontend capability frame

`Minecraft1211Frontend` still reports **unsupported** for opaque or unqualified game objects and keeps `canInterceptGeneration() == false`. The pure portion now accepts immutable `SourceNodeSnapshot` captures and lowers a typed subset (constants, arithmetic, ranges, clamps, gradients, interpolation/cache markers and explicit logical operators) with case-insensitive capability checks, arity/type validation and actionable diagnostics. A valid synthetic `DensityExpression` is never relabeled as a lowered Minecraft graph.

The pure module does not depend on a Minecraft jar. The NeoForge loader module owns the version-pinned mapped reader and converts `DensityFunction` records to immutable snapshots before this module runs. The typed `lowerTyped` result shape remains separate from the legacy opaque `lower` seam.

Before enabling generation, implement actual density/noise lowering, cache/interpolation semantics, aquifer status/pressure, the ore-vein material rule and coordinate-scoped RNG, then complete biome/surface adapters as their stages are owned. Include structure/beardifier and blending inputs in dependencies. Unknown nodes must keep an explicit original CPU route or fail with a reason, never produce invented terrain.

Qualification requires independent literal vanilla fixtures for vanilla and the same unaccelerated pinned stack for modded terrain, including ore-bearing non-flat Overworld, Nether/End, negative/extreme coordinates, fluid/postprocessing/heightmap state, reload epochs and error paths. The v0.1 tests only establish truthful capability rejection and validated result contracts.
