# Evidence ledger

Read-only investigation performed 8 September 2026 for the separate [WorldgenNext plan](PROPOSAL.md). This ledger separates observed source behavior, archived performance, and proposed engineering targets. No Minecraft benchmark or test was launched during this investigation.

## Checkout and host

- Reference checkout: `D:\Projects\GPUWorldGen`.
- Observed HEAD: `ed361f93b5669b9689a1025c3606fcafa3e6424c`.
- Existing modifications at entry: `docs/evidence/2026-09-03-uniform-regression.md`, `harness/__pycache__/normal-server.cpython-313.pyc`, `runtime/VulkanGpuDispatcher.java`, `runtime/StripSkipEmissionTest.java`, plus untracked `.worktrees/`. These are not changes made by this task.
- Current read-only host probe: Intel Core Ultra 7 270K Plus, 24 cores / 24 logical processors; 33,675,702,272 bytes visible physical RAM; NVIDIA GeForce RTX 5070 Ti, driver 610.88, reported 16,303 MiB.
- Archived game runs identify Windows amd64, RTX 5070 Ti, Temurin 25.0.4.1. Historical CPU/RAM provenance is incomplete; do not silently assume all host variables match today's probe. The project's Gradle build toolchain is JDK 21.
- Existing `Plan.md` was read before source review. Its design contracts are useful references, while many dated status paragraphs are superseded by later evidence. They are not restrictions on the new mod's design.

## Benchmark anchors

Available run IDs link to their summaries; unavailable historical artifacts are listed at the end of this ledger. Profile files are adjacent `rcon-profile-stop.txt`; generated settings are under `server/config/`. All rows below are exact-math configuration, host-aquifer routing, full benchmark-scoped GPU completion and no recorded fallback/bypass/silent/hard failures. These counters do not establish independent full-state parity.

| Run | Requested workload | Warm applied NOISE cps | GPU bracket ms/chunk | Interpretation |
| --- | --- | ---: | ---: | --- |
| r44 (archived) | Vanilla, radius720 / 8,281 | 2,007.83 | Unprofiled | Historical best; approximately 4.12-second measured interval |
| r05 (archived) | Vanilla, radius720 / 8,281 | 1,676.69 | 0.250 | Historical timing anchor, a different run from r44 |
| r36 (archived) | Combined, radius720 / 8,281 | 597.95 | 0.424 | Historical combined best |
| r125 (archived) | Vanilla, radius720 / 8,281 | 1,429.51 | 0.523 | Recent matched-shape reference |
| r126 (archived) | Vanilla, radius1648 / 42,849 | 1,555.73 | 0.512 | Larger shape; separate scaling evidence |
| Sep-5 push (archived) | Vanilla, radius720 / 8,281 | 1,332.71 | 0.515 | Current production-path reference; raw equals warm |
| Sep-5 reboot (archived) | Vanilla, radius720 / 8,281 | 1,389.12 | 0.503 | Reboot did not recover old throughput |
| r114 (archived) | Combined, radius720 / 8,281 | 389.09 | Unprofiled | Recent combined reference |
| r115 (archived) | Combined, radius720 / 8,281 | 377.69 | 1.856 | Recent combined profile |
| [Sep-8 proxy](D:/Projects/GPUWorldGen/build/harness-runs/vanilla-r256-20260908-proxy/summary.json) | Vanilla, radius256 / 1,089 | 807.75 | 0.497 | Latest small diagnostic control found; not a radius720 replacement |

Historical r44/r36 manifests name base commit `5adb4a5` and `dirtyTree=true`. Checking out that base does not reproduce the fast uncommitted tree. This limitation is also recorded in the [regression evidence](D:/Projects/GPUWorldGen/docs/evidence/2026-09-03-uniform-regression.md:127).

The small September 8 experiments ranged from 698.09 to 852.81 cps and approximately 0.497–0.565 ms/chunk. They do not establish a recovered 2,000-cps workload or a new large speedup.

### Endpoint, arithmetic and correctness caveats

- [ServerChunkSink.java](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/bench/ServerChunkSink.java:32) requests NOISE futures. FULL/lighting/saved completion is not the endpoint.
- [ChunkSetGenerator.java](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/bench/ChunkSetGenerator.java:9) converts a radius in blocks to a square of chunks. Radius720 gives 91×91=8,281.
- [VulkanWorldgenCommands.java](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/commands/VulkanWorldgenCommands.java:326) uses a separate 100-chunk warmup region. Measured chunks are not warmup chunks.
- [BenchmarkHarness.java](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/bench/BenchmarkHarness.java:270) computes warm cps by subtracting recorded compilation duration. Summed overlapping compile times are not generally subtractable from wall time.
- [Regression evidence](D:/Projects/GPUWorldGen/docs/evidence/2026-09-03-uniform-regression.md:133) rejects an inflated ~10,431 warm-cps figure whose raw rate was 774.35. Historical r44 had equal raw/warm rates, so this caveat does not invalidate its 2,007.83 figure.
- [ServerHarnessReport.java](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/bench/ServerHarnessReport.java:107) emits a literal zero for `parityMismatchCount`, also at line132. There is no independently compared-chunk count behind that field.
- r44 settings (archived) disable debug parity, sampling and valid-readback comparison. The current push profile uses the same disabled diagnostics.
- [BatchTimeline.java](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/profiling/BatchTimeline.java:221) calculates a compute timestamp bracket. It includes barriers/inter-dispatch gaps, not just executed ALU instructions. Queue waits overlap preceding work and must not be added as independent GPU service.

## Source findings behind the redesign

| Finding | Evidence | Design implication |
| --- | --- | --- |
| NOISE-only integration | [Mixin](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/mixin/NoiseBasedChunkGeneratorMixin.java:17), [Plan non-goals](D:/Projects/GPUWorldGen/Plan.md:176) | FULL generation and saving need their own scheduler/integration and benchmark |
| Semantic phases and boundaries already exist | [Plan compiler contracts](D:/Projects/GPUWorldGen/Plan.md:265) | Preserve interpolation semantics; do not claim staging itself as a new invention |
| Ore material behavior is missing from the inspected path | [Compiler](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/integration/WorldgenContextCompiler.java:440), [classifier](D:/Projects/GPUWorldGen/src/main/resources/shaders/terrain.comp:2132), [explicit test caveat](D:/Projects/GPUWorldGen/src/test/java/dev/gpuworldgen/vulkanworldgen/terrain/DecodedBlockParityTest.java:410) | New exact output and uniform-solid proofs must cover ore rules; no runtime mismatch was executed in this audit |
| Literal parity fixture is Nether-focused | [VanillaFillParityGameTest](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/gametest/VanillaFillParityGameTest.java:183) | Add literal non-flat Overworld comparisons with ore-bearing fixtures |
| Region-service default is noop; production installation/consumer not found | [Coordinator](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/scheduler/VulkanBatchCoordinator.java:333), [setter](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/scheduler/VulkanBatchCoordinator.java:624), [wire](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/scheduler/RegionCacheWire.java:118) | Model real producers/consumers rather than assuming a configured cache is already eliminating work |
| Output skip still calls dense emission | [worldgen.comp](D:/Projects/GPUWorldGen/src/main/resources/shaders/worldgen.comp:1421), [terrain.comp](D:/Projects/GPUWorldGen/src/main/resources/shaders/terrain.comp:2219) | Sparse ABI must eliminate emit/metadata/transfer/application costs |
| RangeChoice references previously named values | [Emitter assignments](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/compiler/StagedGlslEmitter.java:1477), [RangeChoice](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/compiler/StagedGlslEmitter.java:2526) | Branch-local compiler is a candidate; profile/ISA must establish actual wasted execution |
| Full-domain intermediate storage accumulates live-outs | [IntermediateLayout](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/compiler/IntermediateLayout.java:125), [capacity comment](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/runtime/VulkanGpuDispatcher.java:221) | Lifetime aliasing and tiled arenas can reduce ~32.2M doubles / 257.6MB per combined slot |
| Liveness estimation is present but schedule budgets are largely compiler-complexity limits | [Planner](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/compiler/DensityIrStagePlanner.java:1380), [StageBudgets](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/compiler/StageBudgets.java:114) | Add measured device costs; do not assume spills without evidence |
| Host router calls remain ordinary Minecraft evaluators | [MinecraftRouterNoiseSampler](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/integration/MinecraftRouterNoiseSampler.java:115) | Compiled CPU preparation is a distinct candidate from the already implemented GPU shared DAG |
| Readback consumers retain slot resources | [GpuResultHandler](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/integration/GpuResultHandler.java:328) | Separate scratch, readback and apply leases without early reuse |
| Direct palette application and rollback already exist | [MinecraftChunkMutator](D:/Projects/GPUWorldGen/src/main/java/dev/gpuworldgen/vulkanworldgen/apply/MinecraftChunkMutator.java:74) | Preserve the mechanism; no new multiplier for introducing it again |
| Ordered parallel checksums already exist | [terrain.comp](D:/Projects/GPUWorldGen/src/main/resources/shaders/terrain.comp:2821) | Sparse checksum handling is new; ordinary parallel reduction is not |

Vanilla material-rule ordering was inspected directly from `build/moddev/artifacts/neoforge-21.1.176-sources.jar`: `NoiseChunk.java:148–158` adds aquifer then optional `OreVeinifier`; `OreVeinifier.java:27–49` evaluates vein roots and RNG to select ore/raw ore/filler. This archive is a local reference, not material to redistribute as a new mod's source. The old temporary decompiled-source location in Plan.md was missing.

Rejected directions and counter-evidence are in the [throughput campaign](D:/Projects/GPUWorldGen/docs/evidence/2026-09-03-throughput-push.md). In particular, prior stone skip regressed, the uniform-air census did not translate to a speedup, and adding shared boundary cache worsened the complex graph. Larger batches, additional in-flight depth, delay sweeps and generic prefetch also failed to establish a new architecture-level win. Latest [regression notes](D:/Projects/GPUWorldGen/docs/evidence/2026-09-03-uniform-regression.md) supersede their earlier confident root-cause language.

## Primary upstream research

Verified on 8 September 2026. These pins establish the researched code/license identity. A current default branch is not a drop-in Minecraft1.21.1 dependency.

| Project | Verified primary source | Reuse decision |
| --- | --- | --- |
| C2ME base | [MIT with explicit exclusions, commit8138acfb](https://github.com/RelativityMC/C2ME-fabric/blob/8138acfb32daf5d1b264780b6436eb8ff958c1b9/LICENSE.md) | Candidate scheduler/threading/compiler foundation; independently inspect selected files and nested dependencies |
| C2ME OpenCL | [ARR text at the same pin](https://github.com/RelativityMC/C2ME-fabric/blob/8138acfb32daf5d1b264780b6436eb8ff958c1b9/licenses/LICENSE-ARR.txt) | Do not copy/translate/bundle implementation without permission; using the OpenCL API independently is a separate matter |
| FlowSched | [MIT](https://github.com/RelativityMC/FlowSched/blob/fae2126839607b68d4059e3933a492886fe8b8f5/LICENSE), [priority/multiple-resource scheduling](https://github.com/RelativityMC/FlowSched/blob/fae2126839607b68d4059e3933a492886fe8b8f5/README.md) | Useful scheduling primitives, not a full Minecraft integration by itself |
| Moonrise | [GPLv3 at e805bb42](https://github.com/Tuinity/Moonrise/blob/e805bb427c513ec485cf4eb98d4d8eda0120be84/LICENSE.md), [README and C2ME incompatibility](https://github.com/Tuinity/Moonrise/blob/e805bb427c513ec485cf4eb98d4d8eda0120be84/README.md) | Alternative full chunk-system lineage; do not stack schedulers; license must govern any reused material |
| ScalableLux | [LGPLv3 at77c290c4](https://github.com/RelativityMC/ScalableLux/blob/77c290c4c84be1c87d006bc8221e5361d98e0c9a/LICENSE), [upstream description](https://github.com/RelativityMC/ScalableLux) | Candidate parallel lighting module; validate at chosen Minecraft version |
| Noisium | [LGPLv3-or-later atc819d73e](https://github.com/Steveplays28/noisium/blob/c819d73e24a1a13248af14cf02b9c0d0b1824af7/LICENSE), [upstream techniques](https://github.com/Steveplays28/noisium) | Archived; useful reference, direct-write benefits overlap existing mod |
| VMP | [MIT at7e89476b](https://github.com/RelativityMC/VMP-fabric/blob/7e89476b51267d7ce64dad75cf001177b6188f1b/LICENSE), [upstream description](https://github.com/RelativityMC/VMP-fabric) | Admission/tickets/delivery ideas, not a density backend |
| Voxy | [Upstream](https://github.com/MCRcortex/voxy) | LOD rendering; do not count distant representations as exact completed chunks |

Version-matched candidates to audit before implementation:

- [C2ME NeoForge1.21.1 at26adcb8c](https://github.com/RelativityMC/C2ME-neoforge/tree/26adcb8c2be2054e1c2c600ac617d51e961af182), August14,2026. [Wrapper license](https://github.com/RelativityMC/C2ME-neoforge/blob/26adcb8c2be2054e1c2c600ac617d51e961af182/LICENSE) is MIT; recursively audit nested backport/submodule content. Prior local notes call this port alpha; compatibility and stability remain to be tested.
- [Moonrise1.21.1 at2eae1b1f](https://github.com/Tuinity/Moonrise/tree/2eae1b1f7318907cd9b6552e59156f6dc619b6cc), October10,2025, [GPLv3](https://github.com/Tuinity/Moonrise/blob/2eae1b1f7318907cd9b6552e59156f6dc619b6cc/LICENSE.md).
- [ScalableLux NeoForge1.21.1 at9ca40b0a](https://github.com/RelativityMC/ScalableLux/tree/9ca40b0a3002c8156547bb031539928ff8f34035), August16,2026, [LGPLv3](https://github.com/RelativityMC/ScalableLux/blob/9ca40b0a3002c8156547bb031539928ff8f34035/LICENSE).

Base C2ME's MIT CPU density compiler is separately inspectable: [AstEmitter](https://github.com/RelativityMC/C2ME-fabric/blob/8138acfb32daf5d1b264780b6436eb8ff958c1b9/c2me-opts-dfc/src/main/java/com/ishland/c2me/opts/dfc/common/ast/AstEmitter.java) and [McToAst](https://github.com/RelativityMC/C2ME-fabric/blob/8138acfb32daf5d1b264780b6436eb8ff958c1b9/c2me-opts-dfc/src/main/java/com/ishland/c2me/opts/dfc/common/ast/McToAst.java). Reusing it does not require copying the ARR OpenCL module. Present bindings on this default branch must be rechecked for the selected backport.

## Primary numerical/API sources

- [Vulkan feature query](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceFeatures.html): FP64 is a feature to query, not a guarantee attached to every Vulkan device.
- [Vulkan floating-point controls](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceFloatControlsProperties.html): preservation and rounding capabilities are distinct properties.
- [Vulkan SPIR-V numerical environment](https://docs.vulkan.org/spec/latest/appendices/spirvenv.html) and [SPIR-V specification](https://registry.khronos.org/SPIR-V/specs/unified1/SPIRV.html): operation ordering, contraction and accuracy rules need explicit treatment; a double datatype alone does not establish Java parity.
- [Vulkan subgroup guide](https://docs.vulkan.org/guide/latest/subgroups.html): query widths and operation support and retain a portable fallback.
- [OpenCL C specification](https://registry.khronos.org/OpenCL/specs/unified/html/OpenCL_C.html) and [double-capability query](https://registry.khronos.org/OpenCL/specs/unified/refpages/man/html/clGetDeviceInfo.html): optional FP64 and numerical configuration need qualification.
- [SYCL specification](https://registry.khronos.org/SYCL/specs/sycl-2020/pdf/sycl-2020.pdf) and [Intel NVIDIA-target instructions](https://www.intel.com/content/www/us/en/docs/dpcpp-cpp-compiler/developer-guide-reference/2025-1/ahead-of-time-compilation.html): CUDA-based targets are explicitly excluded from this plan, even when hidden beneath a programming abstraction.
- [D3D12 hardware feature levels](https://learn.microsoft.com/en-us/windows/win32/direct3d12/hardware-feature-levels); [Microsoft double-support caveat](https://learn.microsoft.com/en-us/windows/win32/api/d3d11/ns-d3d11-d3d11_feature_data_doubles): do not assume full arithmetic semantics from a double-support flag. These sources establish capabilities, not a performance comparison.
- [Apple Metal Shading Language4.1 specification](https://developer.apple.com/metal/Metal-Shading-Language-Specification.pdf#page=25), dated June4,2026, page25 §2.1: `double` unsupported. The research worker downloaded and extracted the PDF in memory after the web fetch exceeded its size limit.
- [ISPC documentation](https://ispc.github.io/ispc.html) and [performance guide](https://ispc.github.io/perfguide.html): fast-math and FMA options affect numerical behavior; vectorizing independent samples does not authorize reassociation.
- [Adaptive precision predicates](https://www.cs.cmu.edu/~quake/robust.html): primary research motivating a possible certified fast filter. It provides no proof or speedup guarantee for Minecraft's density/material program.

## How the estimates should be used

The 4,000/1,200 design points are conditional service-budget calculations, not extrapolated measured benchmarks. The [JSON model](performance-model.json) is deliberately explicit about assumed CPU/GPU capacities and utilization. Changing these inputs changes the rate; no distribution or statistical confidence is implied.

Approximately 0.20ms GPU service at80% useful duty is required for4,000 cps. Approximately0.667ms at80% is required for1,200 cps. These budgets must include missing material correctness, barriers and actual host feeding. FULL/saved-rate multipliers use hypothetical Amdahl fractions solely to illustrate sensitivity; they are not CPU profile measurements.

The estimate gives no assured gain for API replacement, no additive credit for already-present palette/readback/checksum work, and no full-chunk credit for LOD or altered terrain. A new benchmark campaign must establish the final outcome.

## Historical artifact availability at v0.1 packaging

On 9 September 2026 the paths below were no longer present in the reference checkout. Their prior audit findings are retained as historical evidence, not revalidated measurements. Source links above still resolve locally; this archive does not bundle the original benchmark runs.

- r44: `D:/Projects/GPUWorldGen/build/harness-runs/fast-host-aquifer-gclog-r720-20260903-r44/summary.json`
- r05: `D:/Projects/GPUWorldGen/build/harness-runs/fast-host-aquifer-r720-profile-20260903-r05/summary.json`
- r36: `D:/Projects/GPUWorldGen/build/harness-runs/combined-merge-pri-r720-20260903-r36/summary.json`
- r125: `D:/Projects/GPUWorldGen/build/harness-runs/vanilla-r720-20260904-r125/summary.json`
- r126: `D:/Projects/GPUWorldGen/build/harness-runs/vanilla-r1648-20260904-r126/summary.json`
- Sep-5 push: `D:/Projects/GPUWorldGen/build/harness-runs/vanilla-r720-20260905-push/summary.json`
- Sep-5 reboot: `D:/Projects/GPUWorldGen/build/harness-runs/vanilla-r720-20260905-reboot/summary.json`
- r114: `D:/Projects/GPUWorldGen/build/harness-runs/combined-r720-20260904-r114/summary.json`
- r115: `D:/Projects/GPUWorldGen/build/harness-runs/combined-r720-20260904-r115/summary.json`
- r44 settings: `D:/Projects/GPUWorldGen/build/harness-runs/fast-host-aquifer-gclog-r720-20260903-r44/server/config/vulkan_worldgen-common.toml:22`
