# Roadmap after the v0.1 foundation

The root [Plan.md](../Plan.md) controls scope. The [detailed v0.2 plan](V0.2-PLAN.md) supersedes the old functional split across v0.2–v0.5. Runtime code and frozen delivery artifacts remain v0.1 until implementation begins.

## v0.2 — complete functionality, before optimization

1. Pin original/candidate inputs and build the independent real-Minecraft oracle.
2. Complete typed semantics, exact CPU generation and the qualified integer-based GPU arithmetic route.
3. Implement real GPU density/aquifer/ore generation, full output metadata, persistent Vulkan resources and bounded sample producers/coordinator.
4. Integrate live NOISE with transactional application and lifecycle safety; preserve original downstream stages through FULL and save/reopen.
5. Qualify the installed product, required vanilla/terrain-mod matrix, failure/stability campaigns and reproducible baseline measurements.

The [13 work packages](v0.2/WORK_PACKAGES.json), [file map](v0.2/FILES.md) and [test gates](v0.2/TESTS.md) define the work. Minecraft retains holder/ticket/status authority; adapting a second chunk-system scheduler is not required for this functional baseline. Real GPU NOISE is mandatory; original CPU execution of the remaining stages is an explicit supported design.

There is no v0.2 chunks/sec floor or old-mod speedup claim. A correct CPU-only interim build does not complete the GPU release. Missing required matrix evidence remains an open gate.

## v0.3 — measured optimization

Freeze v0.2 source/binary/corpus/configuration first. Profile actual NOISE, FULL and saved endpoints, then test one justified change at a time. Candidate work includes native arithmetic with proved numerical domains, CPU specialization, shared-work reduction, shader fusion, sparse proofs, memory/copy reductions, batching, placement and bounded locality improvements.

A later holder-scheduler replacement must choose one audited lineage and requalify ownership, neighborhood ordering and mod hooks. It cannot be stacked beside an independent competing scheduler. Compare all performance claims on matched artifacts, endpoints, fresh workloads, coverage and hardware. Preserve exact parity and bounded failure behavior throughout.

## Later adapters and support expansion

Fabric, Linux GPU execution, additional NVIDIA/AMD/Intel devices and drivers, more mod stacks and optional backend/stage expansion are separately qualified milestones. API availability, CPU fallback or one working GPU does not establish support elsewhere. No CUDA API or CUDA-backed dependency is permitted.
