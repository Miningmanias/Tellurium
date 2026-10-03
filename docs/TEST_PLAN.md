# Test suite expansion plan

The current milestone specification is [v0.2/TESTS.md](v0.2/TESTS.md), with [v0.2 acceptance criteria](../test-manifest/v0.2-acceptance.json). It supersedes earlier milestone sequencing and the initial corpus sketch. The families below remain background; their planned filenames are not evidence that tests exist.

The executable v0.1 tests are inventoried in FILE_MAP.md and their observed results in STATUS.md. This document frames the remaining test files and independent evidence before the production generator is enabled.

## Implemented foundation families

Semantic validation and interpreter tests exercise finite constants, graph limits, coordinates, interpolation boundaries, lazy range branches and exact ordered arithmetic. Compiler tests compare against independent formulas and reference evaluation. Codec tests cover caller mutation, alternate representations, padding/length/ID corruption and checksums. Spatial/engine tests cover keys, byte ownership, shared producer lifetime, cancellation and epoch/commit races. Runtime tests validate capabilities and bounded input before any native call. Report tests require actual nonzero comparison coverage and failure verdicts.

These test families use synthetic data. They must not be renamed Minecraft parity or chunk throughput.

## Next files to implement with the Minecraft frontend

| Planned test file | Independent reference and required failure cases |
| --- | --- |
| VanillaNoisePrimitiveParityTest | Literal game RNG/noise samplers, long seed boundaries and negative/large positions |
| InterpolationMarkerParityTest | Real NoiseChunk markers; nonlinear outer operations, all cell shapes and cache domains |
| OreVeinMaterialParityTest | Literal aquifer-then-ore material chain; copper, iron, raw ores, filler and RNG ordering |
| AquiferMaterialParityTest | Nearest candidate tie order, fluid barriers, lava and post-processing |
| StructureBlendParityTest | Identical structure/blender snapshots and epoch fingerprints |
| SameStackNoiseCommitTest | Original same-stack generator; every output field, actual coverage and unique commit |
| ReloadCancellationIntegrationTest | World/registry epoch changes with queued, native-pending and ready-to-commit work |
| DeviceFailureRecoveryTest | Failed submission/wait/readback; no stale output, double execution or unfinished future |
| SparseDenseLogicalParityTest | Proven descriptors compared to independently generated dense material output |
| FullStatusParityTest | Surface, carvers, features, light, structures, ticks and block entities after full generation |
| SavedWorldReopenTest | Actual saved/reopened world state under an explicit flush/durability policy |
| LongRunBudgetStabilityTest | Virgin-world work, cache eviction, shutdown, bounded memory and no leaked leases |

The matrix at ../test-manifest/minecraft-parity.json fixes seeds, fixtures, endpoint fields and verdict rules. Its NOT_IMPLEMENTED status is deliberate. Add the real runner and fixtures before changing it.

## Benchmark files after parity qualification

The draft now has `BaselineReportBuilder` and `BaselineReportJson`: measured
NOISE/FULL/SAVED rows aggregate only explicit workload identities, requested
run counts, comparison/mismatch counts, GPU receipts and positive wall times,
then serialize deterministically. Still add the real manifest writer,
readiness barrier, separate-area warmup, endpoint sinks and source/jar/shader/
dependency/driver identity capture. Paired performance runs must use
identical artifact settings; no synthetic or fallback work may inflate GPU
counters.

Every missing supported-matrix fixture remains a reported open gate. A skipped test or a zero mismatch default is not correctness evidence.
