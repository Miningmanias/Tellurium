# v0.2 file and API plan

This inventory is the v0.2 target. The 2026-09-12 checkpoint implements the shared semantic/snapshot/execution contracts, typed frontend capture representation, a live bound 1.21.1 15-root/direct-density preflight across vanilla Overworld/Nether/End, CPU primitives, registry-aware result ABI, spatial/coordinator/runtime models, oracle comparator, configuration seams and the disabled-by-default live NOISE bridge contract. Entries still absent are deliberate open work; they must not be represented by empty classes or skipped-test claims. The current inventory is [FILE_MAP.md](../FILE_MAP.md). Scope, ownership and numerical decisions come from [V0.2-PLAN.md](../V0.2-PLAN.md).

Unless a different source root is shown, Java paths are relative to `<module>/src/main/java/dev/worldgennext/`. Test names refer to the matching package under `src/test/java`. `Keep` means preserve the v0.1 implementation and regression contract; `Change` means an intentional implementation-time migration; other entries are new.

## Dependency and build boundaries

| Module | Permitted production dependencies and ownership |
| --- | --- |
| semantic-core | JDK only; immutable values, program/snapshot identities and execution contracts |
| frontend-mc1211 | semantic-core; lowers pure captured inputs, without Minecraft or reflection imports |
| compiler-jvm | semantic-core; scalar worldgen interpreter and compiled evaluator |
| compiler-vulkan | semantic-core; generated source, numerical helpers and pure SPIR-V contract inspection |
| material-codec | semantic-core; validates and encodes complete final results |
| spatial-data | semantic-core; typed sample storage, leases and producer interfaces; no compiler/runtime dependency |
| chunk-engine | semantic-core, material-codec, spatial-data; coordinator and injected execution/application interfaces |
| runtime-vulkan | semantic-core, compiler-vulkan, LWJGL; native execution and raw result leases; no Minecraft types |
| oracle-and-replay | Separate pure `corpusApi` source set/artifact, replay application composing the candidate modules, field comparator and failure artifacts |
| oracle-1211 | Standalone original-only NeoForge/Minecraft capture process; no WorldgenNext candidate dependency; generated snapshot output is compared through `oracle-and-replay` |
| neoforge-1211 | Composition root for game adapters and product runtime; a separate test-only original-capture artifact has a restricted dependency graph |
| fabric-1211 | Existing deferred frame; no v0.2 loader implementation or release artifact |

`ExecutionReceipt` and `ResultBufferLease` belong in semantic-core so the runtime and engine do not depend on each other. `ResultBufferLease` may expose a bounded JDK byte buffer view, never a native Vulkan handle. Runtime creates receipts; the engine accepts them only through its registered executor/submission relationship. Minecraft holder access and commit callbacks are injected by the loader. Native decoding uses material-codec at the composition boundary, not a runtime-to-engine cycle.

The original oracle helper must not depend on the replay application's compiler/runtime graph. The checkpoint implements this boundary as the separate `oracle-1211` loader module plus the independently packaged `corpusApi` variant. The planned candidate capture and production-loader source sets remain separate work; this is a build boundary, not a flag that merely disables the production hook after its classes have loaded.

## semantic-core — P00/P02/P03/P07

| Files | Contract and companion tests |
| --- | --- |
| Keep `semantic/DensityExpression.java`, `ReferenceInterpreter.java`, `WorldgenIdentity.java`, `CellGeometry.java` | Preserve synthetic fixtures and their explicit bounds; `SemanticContractTest` remains |
| `semantic/program/WorldgenProgram.java`, `ValueType.java`, `ProgramNode.java`, `ControlRegion.java` | Typed immutable IR with explicit ordered/lazy control and effects; `WorldgenProgramTest`, `ControlRegionTest` |
| `semantic/program/NumericSemantics.java`, `NumericProfile.java`, `JavaIntegerSemantics.java` | FP32/FP64 rounding, conversions, overflow and NaN contract; `NumericSemanticsTest`, `JavaIntegerSemanticsTest` |
| `semantic/program/EvaluationDomain.java`, `MarkerContext.java`, `InterpolationGeometry.java` | Column, lattice, block and mutable-marker evaluation domains; `MarkerContextTest`, `InterpolationGeometryTest` |
| `semantic/snapshot/WorldgenSnapshot.java`, `NoiseRouterSnapshot.java`, `NoiseParameters.java`, `RandomStateSnapshot.java` | All 15 roots and exact seed-expanded immutable tables; `WorldgenSnapshotTest`, `NoiseParameterIdentityTest` |
| `semantic/snapshot/RegistrySnapshot.java`, `BlockStateDescriptor.java`, `GeneratorSettingsSnapshot.java`, `StructureBlendSnapshot.java` | Canonical states/properties, configured fluids/default blocks, storage bounds and dynamic inputs; `RegistrySnapshotTest`, `SnapshotBoundsTest` |
| `semantic/identity/ContextIdentity.java`, `ProgramFingerprint.java`, `DynamicInputIdentity.java` | Stable serialization/hash boundaries and world/device generation separation; `ContextIdentityTest` |
| `semantic/material/MaterialProgram.java`, `AquiferProgram.java`, `OreVeinProgram.java` | Ordered material decision contracts with explicit inputs/effects; `MaterialProgramContractTest` |
| `semantic/execution/ExecutionReceipt.java`, `ResultBufferLease.java`, `ProgramAbi.java`, `CapabilityDecision.java` | Versioned runtime output identity, buffer ownership and explicit unsupported reasons; `ExecutionContractTest`, `ProgramAbiTest` |

The IR inventory must identify every reachable pinned Minecraft operation and its original reference before P02 passes. A generic node carrying an opaque game object is not a completed lowering.

## frontend-mc1211 — P02

| Files | Contract and companion tests |
| --- | --- |
| Change `frontend/mc1211/MinecraftDensityLowerer.java`, `Minecraft1211Frontend.java`, `LoweringResult.java` | Introduce a typed production entry point; retain explicitly named synthetic tests during migration |
| `frontend/mc1211/SourceNodeSnapshot.java`, `MinecraftSnapshotLowerer.java`, `DensityNodeLowerer.java`, `NoiseRouterLowerer.java` | Pure version-specific source representation; shared identities across 15 roots; `MinecraftSnapshotLowererTest`, `RouterCoverageTest` |
| `frontend/mc1211/MarkerLowerer.java`, `SplineLowerer.java`, `NoiseParameterLowerer.java`, `MaterialLowerer.java` | Preserve cache scope, spline arithmetic, holder/noise identity and material ordering; matching unit tests plus literal captured fixtures |
| `frontend/mc1211/NodeCapabilityRegistry.java`, `SourceStackFingerprint.java`, `LoweringDiagnostics.java` | Supported-node registry, deterministic identity and a precise failing node path; `UnsupportedNodeTest`, `ReloadIdentityTest` |
| `src/test/resources/minecraft1211/node-inventory.json`, `captured-programs/` | Hash-pinned captures with root/node coverage, provenance and expected values; never write expected output by running the candidate itself |

Minecraft source readers live in NeoForge. No compiler invokes a live Minecraft `DensityFunction` while claiming its own implementation produced the answer.

## compiler-jvm — P02/P04

| Files | Contract and companion tests |
| --- | --- |
| Keep `compiler/jvm/CpuCompiler.java` | Small prebound synthetic evaluator; `CpuCompilerTest` remains |
| `compiler/jvm/worldgen/WorldgenInterpreter.java`, `WorldgenCpuCompiler.java`, `CompiledWorldgenProgram.java` | Straightforward typed interpreter and prebound executable program; independent game oracle remains decisive; `WorldgenCpuCompilerTest` |
| `compiler/jvm/worldgen/LegacyRandom.java`, `XoroshiroRandom.java`, `PositionalRandom.java`, `NoiseEvaluator.java`, `SplineEvaluator.java`, `EndIslandEvaluator.java` | Exact seed/RNG and sampling behavior, including overflow and FP32 steps; `RandomParityTest`, `NoiseParityTest`, `EndIslandParityTest` |
| `compiler/jvm/worldgen/MarkerEvaluator.java`, `InterpolationCursor.java`, `EvaluationScratch.java` | Explicit mutable iteration context, lazy cache behavior and per-request scratch ownership; `MarkerParityTest`, `InterpolationOrderTest` |
| `compiler/jvm/worldgen/AquiferEvaluator.java`, `OreVeinEvaluator.java`, `DenseNoiseGenerator.java` | Full final-state generation, fluid flags and complete generation/storage geometry; `AquiferParityTest`, `OreVeinParityTest`, `DenseNoiseGeneratorTest` |

Literal comparisons cover all decision branches, not just scalar density error. Bytecode generation, vectorization and numerical simplification are absent from this baseline.

## compiler-vulkan — P03/P06

| Files | Contract and companion tests |
| --- | --- |
| Keep `compiler/vulkan/GlslCompiler.java` | Existing diagnostic emitter and tests remain separate |
| `compiler/vulkan/worldgen/WorldgenShaderCompiler.java`, `ControlFlowEmitter.java`, `StagePlanner.java`, `StageLayout.java`, `ShaderCacheKey.java` | Conservative bounded stages, ordered branches and exact semantic/ABI identities; `StagePlannerTest`, `ShaderCacheKeyTest` |
| `compiler/vulkan/worldgen/IntegerIeeeEmitter.java`, `NumericHelperRegistry.java`, `SpirvNumericContract.java` | Raw-bit software arithmetic; forbidden native floating operations/types checked in executable shader paths; `IntegerIeeeSourceTest`, `SpirvNumericContractTest` |
| `compiler/vulkan/worldgen/NoiseEmitter.java`, `MarkerEmitter.java`, `AquiferEmitter.java`, `OreVeinEmitter.java`, `MaterialEmitter.java` | Emit real sampler/material computations; `WorldgenShaderContractTest`, `MaterialEmitterTest` plus device replay |
| `src/main/resources/shaders/ieee/bits32.glsl`, `bits64.glsl`, `integer-java.glsl` | Qualified helpers for the actual operator closure, with attribution for any reused algorithms |
| `src/main/resources/shaders/worldgen/noise.comp`, `material.comp`, `metadata.comp` | Template entry points with checked output bounds and explicit ABI version; every packaged template compiled in validation |
| `src/test/resources/numeric/`, `src/test/resources/spirv-contract/` | Stratified arithmetic vectors and deliberately rejected executable modules; no dependence on a GPU to run pure parser tests |

No interval-proof skipping, automatic fusion, subgroup reduction, scratch aliasing or tuning search is required. Dense final output is the GPU baseline; uniform/palette transport can initially be selected on the host after complete classification.

## material-codec — P07

| Files | Contract and companion tests |
| --- | --- |
| Keep `material/SectionCodec.java`, `SectionData.java`, `UniformSection.java`, `PaletteSection.java`, `DenseSection.java` and existing tests | Preserve fixture codecs; evolve through explicit formats rather than changing historical bytes silently |
| `material/chunk/ChunkNoiseResult.java`, `ChunkResultHeader.java`, `SectionDirectory.java`, `ChunkResultValidator.java` | Complete result with geometry/context/state-table binding; `ChunkResultValidatorTest`, `MalformedChunkResultTest` |
| `material/chunk/BlockStateTable.java`, `ChunkResultCodec.java`, `DenseResultEncoder.java` | Registry-specific integer mapping and final-state encoding; `RegistryMappingTest`, `DensePaletteUniformParityTest` |
| `material/chunk/HeightmapPayload.java`, `PostProcessingPayload.java`, `SectionCounts.java`, `ChunkLogicalChecksum.java` | Full metadata, signed-height handling and stable checksums; `HeightmapPayloadTest`, `PostProcessingPayloadTest`, `ChunkChecksumTest` |
| `src/test/resources/chunk-result-abi/` | Versioned little-endian fixtures, malformed offsets/lengths and independently specified logical state |

The semantic registry snapshot owns canonical descriptors; the codec table owns encoding lookup. Live `BlockState` objects and section mutations belong only in the loader.

## spatial-data — P08

| Files | Contract and companion tests |
| --- | --- |
| Keep `spatial/ByteBudget.java`, `SharedTileCache.java`, `TileKey.java` | Existing model tests remain; do not silently reuse incomplete fixture keys in production |
| `spatial/worldgen/SampleKey.java`, `SampleDomain.java`, `SampleExtent.java`, `SampleLease.java` | Full XYZ/halo/domain/context/epoch identity and immutable consumer views; `SampleKeyTest`, `SampleLeaseTest` |
| `spatial/worldgen/ColumnSamples.java`, `LatticeSamples.java`, `AquiferCellAtlas.java`, `SurfaceColumnAtlas.java` | Typed real sample data, partial extents and exact candidate ownership; `AtlasBoundaryTest`, `PartialTileTest` |
| `spatial/worldgen/SampleProducer.java`, `AsyncSampleProducer.java`, `SpatialSampleStore.java`, `UncachedSampleStore.java`, `ResidencyLedger.java` | Injected synchronous/asynchronous producers, reference mode and bounded accounting including retained capacity; `ProducerConsumerTest`, `ResidencyLedgerTest`, `CachedUncachedParityTest` |
| `spatial/worldgen/SampleWindow.java`, `TiledSampleConsumer.java` | Demand-driven bounded tile splitting, partial-edge typed consumption, cancellation propagation and explicit aggregate residency; `V02TiledConsumerTest` |

Actual CPU/GPU producer execution is composed by the engine/loader. A sample cache cannot issue Minecraft tickets or synchronously wait for its own queued producer while holding the last resource slot.

## chunk-engine — P08/P09/P10

| Files | Contract and companion tests |
| --- | --- |
| Keep `engine/EpochTaskEngine.java`, `TaskKey.java`, `RoutingPolicy.java` | Model behavior remains regression evidence, with old diagnostic labels explicitly scoped |
| `engine/worldgen/WorldgenCoordinator.java`, `CoordinatorSnapshot.java`, `GenerationRequest.java`, `WorkKey.java`, `RequestSubscription.java`, `WorkRecord.java` | Unique work versus subscribers, exactly one terminal outcome, finite record retirement and immutable queue/admission diagnostics; `CoordinatorLifecycleTest`, `SubscriptionCancellationTest`, `TerminalRetirementTest` |
| `engine/worldgen/ResourceAdmission.java`, `ResourceReservation.java`, `FairWorkQueue.java`, `FixedBatchBuilder.java` | Atomic budget bundles, bounded fairness and partial-batch progress; `AdmissionProgressTest`, `PartialBatchTest`, `SingleWorkerProgressTest` |
| `engine/worldgen/StageExecutor.java`, `BackendResult.java`, `StaticRoutePolicy.java`, `SpatialWorkProducer.java`, `SpatialWorkService.java` | Injected CPU/Vulkan execution and typed real samples; static route selection, bounded tile consumption and fail-closed missing producers; `ExecutionReceiptTest`, `StaticRoutePolicyTest`, `SpatialWorkServiceTest` |
| `engine/worldgen/CommitCoordinator.java`, `CommitToken.java`, `CommitReceipt.java`, `ChunkCommitter.java` | Authoritative loader ownership callback; no competing holder graph; `CommitOnceTest`, `StaleCompletionTest`, `RollbackFailureTest` |
| `engine/worldgen/WorldEpoch.java`, `DrainController.java`, `WorkCounters.java`, `EndpointCompletion.java` | Reload/unload/drain transitions and distinct NOISE/FULL/SAVED outcomes; `ReloadDrainTest`, `CounterConservationTest` |
| `src/test/resources/lifecycle-traces/` | Reproducible randomized traces and minimized deadlock/stale-commit failures |

Do not introduce `ChunkDependencyGraph` as a replacement for Minecraft holders. The coordinator consumes dependency/ownership tokens supplied by Minecraft and controls only WorldgenNext work.

## runtime-vulkan — P03/P05/P06

| Files | Contract and companion tests |
| --- | --- |
| Keep `runtime/vulkan/VulkanSmokeRunner.java`, `DeviceCapabilities.java`, `ShadercCompiler.java` | Diagnostic entry point and capability facts remain; factor reusable code without changing v0.1 evidence |
| `runtime/vulkan/production/VulkanGenerationService.java`, `VulkanContext.java`, `DeviceGeneration.java`, `CapabilityQualifier.java` | Persistent device lifecycle and numerical-profile qualification; `ServiceLifecycleTest`, `CapabilityQualifierTest` |
| `runtime/vulkan/production/BufferLayout.java`, `DeviceArena.java`, `UploadLease.java`, `ScratchLease.java`, `ReadbackLease.java`, `NativeBudgetLedger.java` | Checked non-overlapping ranges; separate lifetimes through apply; `BufferLayoutTest`, `LeaseLifetimeTest`, `NativeBudgetTest` |
| `runtime/vulkan/production/MemoryVisibility.java`, `QueueOwner.java`, `SubmissionRing.java`, `CompletionPump.java` | External queue synchronization, coherency/atom alignment and asynchronous completion; `MemoryVisibilityTest`, `SubmissionStateTest` |
| `runtime/vulkan/production/PipelineCompiler.java`, `PipelineCache.java`, `DispatchDescriptor.java`, `GpuExecutionReceipt.java`, `VulkanWorldgenExecutor.java` | Bounded compilation/admission, persistent device dispatch, full cache identities and actual native proof; `PipelineIdentityTest`, `DispatchBoundsTest`, `VulkanWorldgenExecutorTest`, opt-in `nativeWorldgenSmoke` |
| `runtime/vulkan/production/DeviceFailureHandler.java`, `QuarantineLedger.java`, `RuntimeDiagnostics.java` | No reuse while completion is unproven; explicit disabled/lost state and bounded admission; `DeviceFailureModelTest`, `QuarantineAccountingTest` |
| `src/nativeTest/java/dev/worldgennext/runtime/vulkan/` | Separate `IntegerIeeeDeviceTest`, `DenseWorldgenReplayTest`, `PersistentServiceTest`, `NonCoherentVisibilityTest`, `BusyShutdownTest`; required unsupported hardware paths fail or are explicitly scoped, never silently CPU-pass |

CPU tests exercise injected native interfaces without loading a driver. GPU campaigns execute one at a time. Error injection uses controlled seams; do not deliberately hang or reset the user's GPU to fabricate device-loss evidence.

## oracle-and-replay — P01/P12

| Files / source roots | Contract and companion tests |
| --- | --- |
| Keep `oracle/ReplayCli.java`, `ReplayReport.java`, `SyntheticCorpus.java`, existing replay classes/tests | Preserve synthetic report identity and historical fixture counts |
| `src/corpusApi/java/dev/worldgennext/oracle/schema/CorpusManifest.java`, `ChunkSnapshot.java`, `SnapshotField.java`, `CaptureIdentity.java`, `SnapshotIo.java` | Pure original/candidate interchange artifact; no candidate compiler/runtime or Minecraft dependency |
| `oracle/minecraft/MinecraftReplayCli.java`, `CorpusValidator.java`, `ComparisonCoverage.java`, `ChunkSnapshotComparator.java`, `FailureBundle.java` | Independent field comparison, complete expected sets and reproducible mismatch bundles; `CorpusValidatorTest`, `ComparatorMutationTest`, `CoverageAccountingTest` |
| `oracle/minecraft/ReferenceRun.java`, `CandidateRun.java`, `ProcessHarness.java`, `DeterminismPreflight.java`, `RunArtifactManifest.java` | Separate fresh processes/worlds, original-vs-original gate, exact dependencies and bounded process handling; `RunIsolationTest`, `ArtifactManifestTest` |
| `oracle/benchmark/RunManifest.java`, `NoiseEndpoint.java`, `FullEndpoint.java`, `SavedEndpoint.java`, `ReopenVerifier.java`, `StageTimers.java`, `BaselineReport.java`, `BaselineReportBuilder.java`, `BaselineReportJson.java` | Endpoint-specific timings and logical save/reopen evidence; measured-row aggregation and deterministic baseline serialization; `EndpointContractTest`, `SaveFailureTest`, `BaselineReportTest` |
| `src/test/resources/minecraft-comparator/`, `test-manifest/corpus/` at repository root | Tiny comparator mutation fixtures plus frozen real-game corpus metadata; larger generated captures are stored by hash under build artifacts |

Compare state properties, metadata and declared endpoint fields directly. A checksum is a useful index, not the oracle. Reject accidental original/candidate world-directory overlap before any server process starts.

## oracle-1211 — P01 original-only capture slice

| Files / source roots | Contract and companion evidence |
| --- | --- |
| `build.gradle`, `src/main/resources/META-INF/neoforge.mods.toml`, `pack.mcmeta` | Isolated NeoForge 21.1.176/Minecraft 1.21.1 server run with only the original-capture mod; no candidate classpath |
| `src/main/java/dev/worldgennext/oracle1211/OracleCaptureMod.java` | Captures original `ChunkStatus.NOISE` state through Minecraft APIs and writes canonical ten-field snapshots; palette numbering is decoded/remapped |
| `scripts/capture-original-corpus.ps1` | Five signed seeds × two centers per invocation, optional odd 5×5 core squares, explicit context/stack namespaces, fresh run roots by default, stale-output rejection and serial launches |
| `docs/evidence/v0.2-original-oracle.md` | Executed 10-case original-vs-original result, hashes, command and explicit non-qualification limits |

## neoforge-1211 — P01/P02/P09/P10/P11

| Files / source roots | Contract and companion tests |
| --- | --- |
| Change `neoforge/WorldgenNextMod.java`, `DiagnosticSelfTest.java` | Compose the product and lifecycle only after qualification; diagnostics must report actual mode/capability |
| `neoforge/snapshot/MinecraftSnapshotReader.java`, `DensityNodeReader.java`, `RegistrySnapshotReader.java`, `StructureBlendReader.java` | Only these adapters inspect mapped Minecraft worldgen types; no mutable game object escapes into the pure snapshot |
| `neoforge/runtime/GenerationInterceptor.java`, `LiveNoiseBridge.java`, `QualifiedHookEvidence.java`, `MinecraftStageAdapter.java`, `MinecraftOwnershipToken.java`, `RuntimeComposition.java`, `CoordinatedNoiseAttempt.java` | Fail-closed NOISE interception, evidence-gated provider admission, capture/compute/stale-recheck/commit composition, runtime-owned coordinator submission, original-stage continuation and registered CPU/GPU executor adapters |
| `neoforge/runtime/MinecraftCpuCandidate.java`, `MinecraftCpuNoiseProvider.java`, `MinecraftNoiseOwnership.java` | Immutable-input CPU capture/materialization split, finite exact-context qualification allowlist, evidence-ready version-pinned NOISE provider and holder/status ownership proof |
| `neoforge/runtime/MinecraftGpuCandidate.java`, `MinecraftGpuNoiseProvider.java`, `build/gpu-end-check3-20260912.chunk.gpu-receipt.json` | Captured End candidate lowering, persistent-executor qualified provider and receipt-backed device replay; no production registration until independent oracle evidence admission |
| `neoforge/runtime/MinecraftChunkCommitter.java`, `ChunkMutationJournal.java`, `MinecraftResultValidator.java`, `NoiseChunkLifecycleAdapter.java`, `MinecraftNoiseChunkLifecycle.java`, `MinecraftNoisePostProcessor.java` | Complete conservative mutation journal, preserved version-pinned NoiseChunk cache creation, rollback and the post-noise retrogen tail before status advancement |
| `neoforge/runtime/ReloadCoordinator.java`, `ServerLifecycle.java`, `SaveBarrier.java` | Epoch changes, server/world close, actual persistence policy |
| `neoforge/config/WorldgenNextConfig.java`, `neoforge/command/WorldgenNextCommands.java`, `neoforge/compat/CompatibilityRegistry.java`, `neoforge/runtime/QualificationEvidenceFile.java`, `neoforge/runtime/QualificationEvidenceBundleFile.java`, `neoforge/runtime/QualifiedHookEvidenceBundle.java`, `neoforge/runtime/HookTelemetry.java`, `NativeDependencyBootstrap.java` | Static CPU/GPU modes, tolerant canonical mode parsing, deterministic single- and multi-context hash-checked qualification receipt admission, ABI/compiler identity checks, clear unsupported reasons, lazy native loading, schema-1 operator status and schema-1/2 qualification receipts, plus process-local hook telemetry |
| `neoforge/mixin/ChunkStatusTasksMixin.java`, narrowly required `neoforge/mixin/accessor/` classes | Only version-pinned access actually needed by capture/application; document injection target, ordering and ownership |
| `src/main/resources/worldgennext.mixins.json`, changes to `META-INF/neoforge.mods.toml` | Checked server-only `ChunkStatusTasks.generateNoise` injection/resource declarations and supported dependency versions |
| `src/oracleCapture/java/dev/worldgennext/capture/OriginalCaptureMod.java`, `OriginalChunkCapture.java`, `OriginalSnapshotReader.java`, `CaptureCommands.java` | Separately packaged read-only capture helper; original generation executes untouched and no production mutation hook/compiler loads |
| `src/oracleCapture/resources/META-INF/neoforge.mods.toml`, capture-only mixin configuration if needed | Distinct test artifact identity; never bundled into the release mod |
| `src/gameTest/java/dev/worldgennext/neoforge/gametest/LiveNoiseTests.java`, `ChunkRollbackTests.java`, `ReloadTests.java`, `FullPipelineTests.java`, `SavedWorldTests.java`, `ConfigurationTests.java` | Replace diagnostic-only assertions deliberately; non-flat corpus remains a separate normal-server campaign |

Oracle capture must observe the proper status/paused endpoint without changing generation outputs. Its test-only launch profile must be usable with no WorldgenNext product jar installed. Modded controls use the exact same terrain mods and datapacks as candidates.

## Root tooling, test data and operator documentation — P00/P01/P11/P12

| Planned additions/changes | Purpose |
| --- | --- |
| Change `build.gradle`, module build files, `gradle.properties`, `.github/workflows/ci.yml` | Add explicit CPU/native/oracle/installed-jar task families, test source sets, dependency lock/verification and release artifact checks; bump version only during implementation |
| `gradle/verification-metadata.xml`, dependency lockfiles, `test-manifest/dependencies.lock.json` | Exact Java/Minecraft/mod/native identities and checksums |
| `scripts/minecraft-replay.py`, `scripts/replay-minecraft-noise-cpu.ps1`, `scripts/replay-minecraft-noise-gpu.ps1`, `scripts/replay-minecraft-logical.ps1`, `scripts/write-qualification-evidence.ps1`, `scripts/write-qualification-bundle.ps1`, `scripts/capture-original-full.ps1`, `scripts/capture-original-saved.ps1`, `scripts/qualify-v02.ps1`, `scripts/capture-baseline.py`, `scripts/test-installed-mod.ps1` | Reproducible original/candidate orchestration, recursive context-preserving fail-closed CPU/GPU paired replay with counted reports and per-case GPU receipts, bounded FULL/SAVED candidate replay with optional fresh-process reopen, deterministic single- and multi-context qualification-receipt generation, reference endpoint stability, baseline capture and fresh disposable installed-jar smoke; resolve target directories before any cleanup |
| `verifyTerrainModLock` Gradle task and `test-manifest/dependencies.lock.json` | Opt-in verification of copied terrain-mod jars and embedded dependency bytes against the pinned fixture hashes; it does not download missing inputs or claim release qualification |
| `test-manifest/corpus/index.json`, `numeric-operators.json`, `normalization-policy.json`, `baseline-workloads.json` | Frozen coverage/coordinates, exact operator closure, field-specific normalization and benchmark endpoint definitions |
| `docs/CONFIGURATION.md`, `COMPATIBILITY.md`, `TROUBLESHOOTING.md`, `RELEASING.md` | Installation, supported contexts, failure handling and commands verified against installed artifacts |
| Change `docs/CONTRACTS.md`, `TESTING.md`, `STATUS.md`, `ARCHITECTURE.md`, `ATTRIBUTION.md`, `README.md` | Migrate current contracts/status as behavior lands; preserve v0.1 evidence and link actual v0.2 results |
| `docs/evidence/v0.2-qualification.md` and structured run manifests | Populate only from executed checks; no prewritten green report |

Proposed task names and quantitative acceptance are in [TESTS.md](TESTS.md). Focused contract tasks now exist; native/game qualification tasks remain separate and fail closed until their evidence is executed. The v0.1 evidence and archives remain retained as regression history.
