# Project file map

Generated from the files on disk by `python scripts/update-file-map.py`.

This inventory excludes Git state, build output, dependency caches and development worlds. It does not treat a planned file as implemented. See [IMPLEMENTATION_MAP.md](IMPLEMENTATION_MAP.md) for future files and [STATUS.md](STATUS.md) for observed validation.

Indexed project files: **682**, plus this generated index.

## semantic-core

Immutable semantic language and independent reference evaluation.

| File | Role |
| --- | --- |
| [semantic-core/README.md](../semantic-core/README.md) | Documentation |
| [semantic-core/src/main/java/dev/tellurium/semantic/CellGeometry.java](../semantic-core/src/main/java/dev/tellurium/semantic/CellGeometry.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/CompiledDensity.java](../semantic-core/src/main/java/dev/tellurium/semantic/CompiledDensity.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/DensityEvaluator.java](../semantic-core/src/main/java/dev/tellurium/semantic/DensityEvaluator.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/DensityExpression.java](../semantic-core/src/main/java/dev/tellurium/semantic/DensityExpression.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/execution/CapabilityDecision.java](../semantic-core/src/main/java/dev/tellurium/semantic/execution/CapabilityDecision.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/execution/ExecutionReceipt.java](../semantic-core/src/main/java/dev/tellurium/semantic/execution/ExecutionReceipt.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/execution/ProgramAbi.java](../semantic-core/src/main/java/dev/tellurium/semantic/execution/ProgramAbi.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/execution/ResultBufferLease.java](../semantic-core/src/main/java/dev/tellurium/semantic/execution/ResultBufferLease.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/ExpressionIdentity.java](../semantic-core/src/main/java/dev/tellurium/semantic/ExpressionIdentity.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/ExpressionValidation.java](../semantic-core/src/main/java/dev/tellurium/semantic/ExpressionValidation.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/identity/ContextIdentity.java](../semantic-core/src/main/java/dev/tellurium/semantic/identity/ContextIdentity.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/identity/DynamicInputIdentity.java](../semantic-core/src/main/java/dev/tellurium/semantic/identity/DynamicInputIdentity.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/identity/ProgramFingerprint.java](../semantic-core/src/main/java/dev/tellurium/semantic/identity/ProgramFingerprint.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/material/AquiferProgram.java](../semantic-core/src/main/java/dev/tellurium/semantic/material/AquiferProgram.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/material/MaterialProgram.java](../semantic-core/src/main/java/dev/tellurium/semantic/material/MaterialProgram.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/material/OreVeinProgram.java](../semantic-core/src/main/java/dev/tellurium/semantic/material/OreVeinProgram.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/ControlRegion.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/ControlRegion.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/EvaluationDomain.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/EvaluationDomain.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/InterpolationGeometry.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/InterpolationGeometry.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/JavaIntegerSemantics.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/JavaIntegerSemantics.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/MarkerContext.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/MarkerContext.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/NumericProfile.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/NumericProfile.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/NumericSemantics.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/NumericSemantics.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/ProgramNode.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/ProgramNode.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/ValueType.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/ValueType.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/program/WorldgenProgram.java](../semantic-core/src/main/java/dev/tellurium/semantic/program/WorldgenProgram.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/ReferenceInterpreter.java](../semantic-core/src/main/java/dev/tellurium/semantic/ReferenceInterpreter.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/SamplePoint.java](../semantic-core/src/main/java/dev/tellurium/semantic/SamplePoint.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/BeardifierSnapshot.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/BeardifierSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/BlendedNoiseParameters.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/BlendedNoiseParameters.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/BlockStateDescriptor.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/BlockStateDescriptor.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/BlockStateTraits.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/BlockStateTraits.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/EndIslandParameters.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/EndIslandParameters.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/GeneratorSettingsSnapshot.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/GeneratorSettingsSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/NoiseParameters.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/NoiseParameters.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/NoiseRouterSnapshot.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/NoiseRouterSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/PositionalRandomFactorySnapshot.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/PositionalRandomFactorySnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/RandomStateSnapshot.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/RandomStateSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/RegistrySnapshot.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/RegistrySnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/StructureBlendSnapshot.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/StructureBlendSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/snapshot/WorldgenSnapshot.java](../semantic-core/src/main/java/dev/tellurium/semantic/snapshot/WorldgenSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/tellurium/semantic/WorldgenIdentity.java](../semantic-core/src/main/java/dev/tellurium/semantic/WorldgenIdentity.java) | Implementation or explicit public contract |
| [semantic-core/src/test/java/dev/tellurium/semantic/SemanticContractTest.java](../semantic-core/src/test/java/dev/tellurium/semantic/SemanticContractTest.java) | Executable CPU test |
| [semantic-core/src/test/java/dev/tellurium/semantic/V02ContractsTest.java](../semantic-core/src/test/java/dev/tellurium/semantic/V02ContractsTest.java) | Executable CPU test |
| [semantic-core/src/test/java/dev/tellurium/semantic/WorldgenProgramFingerprintTest.java](../semantic-core/src/test/java/dev/tellurium/semantic/WorldgenProgramFingerprintTest.java) | Executable CPU test |

## compiler-jvm

Prebound executable CPU programs.

| File | Role |
| --- | --- |
| [compiler-jvm/README.md](../compiler-jvm/README.md) | Documentation |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/CpuCompiler.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/CpuCompiler.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/AquiferEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/AquiferEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/BeardifierEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/BeardifierEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/BlendDensityEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/BlendDensityEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/BlendOffsetEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/BlendOffsetEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/CompiledWorldgenProgram.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/CompiledWorldgenProgram.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/DenseNoiseGenerator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/DenseNoiseGenerator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/EndIslandEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/EndIslandEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/EvaluationScratch.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/EvaluationScratch.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/InterpolationCursor.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/InterpolationCursor.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/LegacyRandom.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/LegacyRandom.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/MarkerEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/MarkerEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/NoiseEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/NoiseEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/OreVeinEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/OreVeinEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/PositionalRandom.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/PositionalRandom.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/SplineEvaluator.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/SplineEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/WorldgenCpuCompiler.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/WorldgenCpuCompiler.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/WorldgenInterpreter.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/WorldgenInterpreter.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/XoroshiroRandom.java](../compiler-jvm/src/main/java/dev/tellurium/compiler/jvm/worldgen/XoroshiroRandom.java) | Implementation or explicit public contract |
| [compiler-jvm/src/test/java/dev/tellurium/compiler/jvm/CpuCompilerTest.java](../compiler-jvm/src/test/java/dev/tellurium/compiler/jvm/CpuCompilerTest.java) | Executable CPU test |
| [compiler-jvm/src/test/java/dev/tellurium/compiler/jvm/V02WorldgenCpuTest.java](../compiler-jvm/src/test/java/dev/tellurium/compiler/jvm/V02WorldgenCpuTest.java) | Executable CPU test |
| [compiler-jvm/src/test/java/dev/tellurium/compiler/jvm/worldgen/AquiferEvaluatorTest.java](../compiler-jvm/src/test/java/dev/tellurium/compiler/jvm/worldgen/AquiferEvaluatorTest.java) | Executable CPU test |

## compiler-vulkan

Vulkan GLSL source generation.

| File | Role |
| --- | --- |
| [compiler-vulkan/README.md](../compiler-vulkan/README.md) | Documentation |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedGpuBackend.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedGpuBackend.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedKernels.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedKernels.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedNoiseCompiler.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedNoiseCompiler.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedSurfaceKernels.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedSurfaceKernels.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedTables.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/FusedTables.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/SurfaceProgram.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/fused/SurfaceProgram.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/GlslCompiler.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/GlslCompiler.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/AquiferEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/AquiferEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/BeardifierEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/BeardifierEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/BeardifierKernelInputStage.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/BeardifierKernelInputStage.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/CapturedNoiseEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/CapturedNoiseEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/ControlFlowEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/ControlFlowEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/IntegerIeeeEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/IntegerIeeeEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/MarkerEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/MarkerEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/MaterialEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/MaterialEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/NativeDraftMath.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/NativeDraftMath.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/NoiseEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/NoiseEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/NumericHelperRegistry.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/NumericHelperRegistry.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/OreVeinEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/OreVeinEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/ShaderCacheKey.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/ShaderCacheKey.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SharedBlendedReductionStageEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SharedBlendedReductionStageEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SharedFp64DivisionStageEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SharedFp64DivisionStageEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SharedSplineStageEmitter.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SharedSplineStageEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SpirvFunctionControlPatcher.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SpirvFunctionControlPatcher.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SpirvNumericContract.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/SpirvNumericContract.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/StageLayout.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/StageLayout.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/StagePlanner.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/StagePlanner.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/WorldgenShaderCompiler.java](../compiler-vulkan/src/main/java/dev/tellurium/compiler/vulkan/worldgen/WorldgenShaderCompiler.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/resources/dev/tellurium/compiler/vulkan/fused/fused_lib.glsl](../compiler-vulkan/src/main/resources/dev/tellurium/compiler/vulkan/fused/fused_lib.glsl) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/ieee/bits32.glsl](../compiler-vulkan/src/main/resources/shaders/ieee/bits32.glsl) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/ieee/bits64.glsl](../compiler-vulkan/src/main/resources/shaders/ieee/bits64.glsl) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/ieee/integer-java.glsl](../compiler-vulkan/src/main/resources/shaders/ieee/integer-java.glsl) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/worldgen/material.comp](../compiler-vulkan/src/main/resources/shaders/worldgen/material.comp) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/worldgen/metadata.comp](../compiler-vulkan/src/main/resources/shaders/worldgen/metadata.comp) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/worldgen/noise.comp](../compiler-vulkan/src/main/resources/shaders/worldgen/noise.comp) | Packaged metadata/resource |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/fused/FusedKernelsFluidUpdatesTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/fused/FusedKernelsFluidUpdatesTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/fused/KernelTextIdentityTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/fused/KernelTextIdentityTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/GlslCompilerTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/GlslCompilerTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/IntegerIeeeLimbOrderTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/IntegerIeeeLimbOrderTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/NativeDraftMathTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/NativeDraftMathTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/OreVeinEmitterTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/OreVeinEmitterTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/SpirvFunctionControlPatcherTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/SpirvFunctionControlPatcherTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/SpirvNumericContractTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/SpirvNumericContractTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/V02WorldgenShaderTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/V02WorldgenShaderTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/worldgen/BeardifierKernelInputStageTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/worldgen/BeardifierKernelInputStageTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/worldgen/SharedBlendedReductionStageEmitterTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/worldgen/SharedBlendedReductionStageEmitterTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/worldgen/SharedFp64DivisionStageEmitterTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/worldgen/SharedFp64DivisionStageEmitterTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/worldgen/SharedSplineStageEmitterTest.java](../compiler-vulkan/src/test/java/dev/tellurium/compiler/vulkan/worldgen/SharedSplineStageEmitterTest.java) | Executable CPU test |

## material-codec

Immutable final-state encodings and validation.

| File | Role |
| --- | --- |
| [material-codec/README.md](../material-codec/README.md) | Documentation |
| [material-codec/src/main/java/dev/tellurium/material/chunk/BlockStateTable.java](../material-codec/src/main/java/dev/tellurium/material/chunk/BlockStateTable.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/ChunkLogicalChecksum.java](../material-codec/src/main/java/dev/tellurium/material/chunk/ChunkLogicalChecksum.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/ChunkMetadataPayload.java](../material-codec/src/main/java/dev/tellurium/material/chunk/ChunkMetadataPayload.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/ChunkNoiseResult.java](../material-codec/src/main/java/dev/tellurium/material/chunk/ChunkNoiseResult.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/ChunkResultCodec.java](../material-codec/src/main/java/dev/tellurium/material/chunk/ChunkResultCodec.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/ChunkResultHeader.java](../material-codec/src/main/java/dev/tellurium/material/chunk/ChunkResultHeader.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/ChunkResultValidator.java](../material-codec/src/main/java/dev/tellurium/material/chunk/ChunkResultValidator.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/DenseResultEncoder.java](../material-codec/src/main/java/dev/tellurium/material/chunk/DenseResultEncoder.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/HeightmapPayload.java](../material-codec/src/main/java/dev/tellurium/material/chunk/HeightmapPayload.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/PostProcessingPayload.java](../material-codec/src/main/java/dev/tellurium/material/chunk/PostProcessingPayload.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/SectionCounts.java](../material-codec/src/main/java/dev/tellurium/material/chunk/SectionCounts.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/chunk/SectionDirectory.java](../material-codec/src/main/java/dev/tellurium/material/chunk/SectionDirectory.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/DenseSection.java](../material-codec/src/main/java/dev/tellurium/material/DenseSection.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/LogicalChecksum.java](../material-codec/src/main/java/dev/tellurium/material/LogicalChecksum.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/PaletteSection.java](../material-codec/src/main/java/dev/tellurium/material/PaletteSection.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/SectionCodec.java](../material-codec/src/main/java/dev/tellurium/material/SectionCodec.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/SectionData.java](../material-codec/src/main/java/dev/tellurium/material/SectionData.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/SectionMetadata.java](../material-codec/src/main/java/dev/tellurium/material/SectionMetadata.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/tellurium/material/UniformSection.java](../material-codec/src/main/java/dev/tellurium/material/UniformSection.java) | Implementation or explicit public contract |
| [material-codec/src/test/java/dev/tellurium/material/chunk/ChunkResultValidatorTest.java](../material-codec/src/test/java/dev/tellurium/material/chunk/ChunkResultValidatorTest.java) | Executable CPU test |
| [material-codec/src/test/java/dev/tellurium/material/SectionCodecTest.java](../material-codec/src/test/java/dev/tellurium/material/SectionCodecTest.java) | Executable CPU test |
| [material-codec/src/test/java/dev/tellurium/material/V02ChunkResultTest.java](../material-codec/src/test/java/dev/tellurium/material/V02ChunkResultTest.java) | Executable CPU test |

## spatial-data

Tile identities, bounded bytes and shared ownership.

| File | Role |
| --- | --- |
| [spatial-data/README.md](../spatial-data/README.md) | Documentation |
| [spatial-data/src/main/java/dev/tellurium/spatial/ByteBudget.java](../spatial-data/src/main/java/dev/tellurium/spatial/ByteBudget.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/SharedTileCache.java](../spatial-data/src/main/java/dev/tellurium/spatial/SharedTileCache.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/TileKey.java](../spatial-data/src/main/java/dev/tellurium/spatial/TileKey.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/AquiferCellAtlas.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/AquiferCellAtlas.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/AsyncSampleProducer.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/AsyncSampleProducer.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/BoundedSampleStore.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/BoundedSampleStore.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/ColumnSamples.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/ColumnSamples.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/LatticeSamples.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/LatticeSamples.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/ResidencyLedger.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/ResidencyLedger.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleDomain.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleDomain.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleExtent.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleExtent.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleKey.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleKey.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleLease.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleLease.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleProducer.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleProducer.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleWindow.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SampleWindow.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SpatialSampleStore.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SpatialSampleStore.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SurfaceColumnAtlas.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/SurfaceColumnAtlas.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/TiledSampleConsumer.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/TiledSampleConsumer.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/tellurium/spatial/worldgen/UncachedSampleStore.java](../spatial-data/src/main/java/dev/tellurium/spatial/worldgen/UncachedSampleStore.java) | Implementation or explicit public contract |
| [spatial-data/src/test/java/dev/tellurium/spatial/ByteBudgetTest.java](../spatial-data/src/test/java/dev/tellurium/spatial/ByteBudgetTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/tellurium/spatial/SharedTileCacheTest.java](../spatial-data/src/test/java/dev/tellurium/spatial/SharedTileCacheTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/tellurium/spatial/TiledConsumerOwnershipTest.java](../spatial-data/src/test/java/dev/tellurium/spatial/TiledConsumerOwnershipTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/tellurium/spatial/TileKeyTest.java](../spatial-data/src/test/java/dev/tellurium/spatial/TileKeyTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/tellurium/spatial/V02SpatialStoreTest.java](../spatial-data/src/test/java/dev/tellurium/spatial/V02SpatialStoreTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/tellurium/spatial/V02TiledConsumerTest.java](../spatial-data/src/test/java/dev/tellurium/spatial/V02TiledConsumerTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/tellurium/spatial/V02TypedProducerTest.java](../spatial-data/src/test/java/dev/tellurium/spatial/V02TypedProducerTest.java) | Executable CPU test |

## chunk-engine

Bounded coordinator, routing and commit models.

| File | Role |
| --- | --- |
| [chunk-engine/README.md](../chunk-engine/README.md) | Documentation |
| [chunk-engine/src/main/java/dev/tellurium/engine/EpochTaskEngine.java](../chunk-engine/src/main/java/dev/tellurium/engine/EpochTaskEngine.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/ExecutionRoute.java](../chunk-engine/src/main/java/dev/tellurium/engine/ExecutionRoute.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/GenerationStage.java](../chunk-engine/src/main/java/dev/tellurium/engine/GenerationStage.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/RoutingPolicy.java](../chunk-engine/src/main/java/dev/tellurium/engine/RoutingPolicy.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/TaskKey.java](../chunk-engine/src/main/java/dev/tellurium/engine/TaskKey.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/BackendResult.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/BackendResult.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/ChunkCommitter.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/ChunkCommitter.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/CommitCoordinator.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/CommitCoordinator.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/CommitReceipt.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/CommitReceipt.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/CommitToken.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/CommitToken.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/CoordinatorSnapshot.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/CoordinatorSnapshot.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/DrainController.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/DrainController.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/EndpointCompletion.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/EndpointCompletion.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/FairWorkQueue.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/FairWorkQueue.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/FixedBatchBuilder.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/FixedBatchBuilder.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/GenerationRequest.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/GenerationRequest.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/RequestSubscription.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/RequestSubscription.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/ResourceAdmission.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/ResourceAdmission.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/ResourceEstimate.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/ResourceEstimate.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/ResourceReservation.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/ResourceReservation.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/SpatialWorkProducer.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/SpatialWorkProducer.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/SpatialWorkService.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/SpatialWorkService.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/StageExecutor.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/StageExecutor.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/StaticRoutePolicy.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/StaticRoutePolicy.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorkCounters.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorkCounters.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorkKey.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorkKey.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorkRecord.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorkRecord.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorldEpoch.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorldEpoch.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorldgenCoordinator.java](../chunk-engine/src/main/java/dev/tellurium/engine/worldgen/WorldgenCoordinator.java) | Implementation or explicit public contract |
| [chunk-engine/src/test/java/dev/tellurium/engine/CoordinatorSnapshotTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/CoordinatorSnapshotTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/EpochTaskEngineTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/EpochTaskEngineTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/RoutingPolicyTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/RoutingPolicyTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/worldgen/CommitCoordinatorTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/worldgen/CommitCoordinatorTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/worldgen/CommitReceiptProvenanceTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/worldgen/CommitReceiptProvenanceTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/worldgen/FixedBatchBuilderTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/worldgen/FixedBatchBuilderTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/worldgen/ResourceEstimateTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/worldgen/ResourceEstimateTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/worldgen/SpatialWorkServiceTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/worldgen/SpatialWorkServiceTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/worldgen/WorkCountersTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/worldgen/WorkCountersTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/worldgen/WorkRecordTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/worldgen/WorkRecordTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/WorldgenCoordinatorCampaign.java](../chunk-engine/src/test/java/dev/tellurium/engine/WorldgenCoordinatorCampaign.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/tellurium/engine/WorldgenCoordinatorTest.java](../chunk-engine/src/test/java/dev/tellurium/engine/WorldgenCoordinatorTest.java) | Executable CPU test |

## frontend-mc1211

Typed captured Minecraft lowering boundary.

| File | Role |
| --- | --- |
| [frontend-mc1211/README.md](../frontend-mc1211/README.md) | Documentation |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/DensityNodeLowerer.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/DensityNodeLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/LoweringDiagnostics.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/LoweringDiagnostics.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/LoweringResult.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/LoweringResult.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/MarkerLowerer.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/MarkerLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/MaterialLowerer.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/MaterialLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/Minecraft1211Frontend.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/Minecraft1211Frontend.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/MinecraftDensityLowerer.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/MinecraftDensityLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/MinecraftSnapshotLowerer.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/MinecraftSnapshotLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/NodeCapabilityRegistry.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/NodeCapabilityRegistry.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/NoiseParameterLowerer.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/NoiseParameterLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/NoiseRouterLowerer.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/NoiseRouterLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/SourceNodeSnapshot.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/SourceNodeSnapshot.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/SourceStackFingerprint.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/SourceStackFingerprint.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/SplineLowerer.java](../frontend-mc1211/src/main/java/dev/tellurium/frontend/mc1211/SplineLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/test/java/dev/tellurium/frontend/mc1211/Minecraft1211FrontendTest.java](../frontend-mc1211/src/test/java/dev/tellurium/frontend/mc1211/Minecraft1211FrontendTest.java) | Executable CPU test |

## runtime-vulkan

Persistent Vulkan lifecycle and opt-in native compute.

| File | Role |
| --- | --- |
| [runtime-vulkan/build.gradle](../runtime-vulkan/build.gradle) | Build configuration/tooling |
| [runtime-vulkan/README.md](../runtime-vulkan/README.md) | Documentation |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/DeviceCapabilities.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/DeviceCapabilities.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/Fp64Profile.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/Fp64Profile.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/fused/FusedCompileProbe.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/fused/FusedCompileProbe.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/fused/FusedNoiseDevice.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/fused/FusedNoiseDevice.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/GpuSmokeResult.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/GpuSmokeResult.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/Hashes.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/Hashes.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeBeardifierSmoke.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeBeardifierSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeBlendedReductionSmoke.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeBlendedReductionSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeIntegerFloorSmoke.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeIntegerFloorSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeIntegerIeeeCompile.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeIntegerIeeeCompile.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeIntegerIeeeRunner.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeIntegerIeeeRunner.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeRangeChoiceSmoke.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeRangeChoiceSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeRawChainSuffixSmoke.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeRawChainSuffixSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeSharedFp64DivisionSmoke.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeSharedFp64DivisionSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeSingleKnotSplineSmoke.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeSingleKnotSplineSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeVulkanLifecycleRunner.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeVulkanLifecycleRunner.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeWorldgenSmoke.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/NativeWorldgenSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/BufferLayout.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/BufferLayout.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/CapabilityQualifier.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/CapabilityQualifier.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/ChainStoragePlan.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/ChainStoragePlan.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/CompletionPump.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/CompletionPump.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/DeviceArena.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/DeviceArena.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/DeviceFailureHandler.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/DeviceFailureHandler.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/DeviceGeneration.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/DeviceGeneration.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/DispatchDescriptor.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/DispatchDescriptor.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/GpuExecutionReceipt.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/GpuExecutionReceipt.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/MemoryVisibility.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/MemoryVisibility.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/NativeBudgetLedger.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/NativeBudgetLedger.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/PipelineCache.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/PipelineCache.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/PipelineCacheLimits.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/PipelineCacheLimits.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/PipelineCompiler.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/PipelineCompiler.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/QuarantineLedger.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/QuarantineLedger.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/QueueOwner.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/QueueOwner.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/ReadbackLease.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/ReadbackLease.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/RuntimeDiagnostics.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/RuntimeDiagnostics.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/ScratchLease.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/ScratchLease.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/ScratchStoragePlan.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/ScratchStoragePlan.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/SpirvModuleCache.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/SpirvModuleCache.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/SubmissionRing.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/SubmissionRing.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/UploadLease.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/UploadLease.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/VulkanContext.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/VulkanContext.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/VulkanGenerationService.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/VulkanGenerationService.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/VulkanWorldgenExecutor.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/production/VulkanWorldgenExecutor.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/ShadercCompiler.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/ShadercCompiler.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/SmokeConfig.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/SmokeConfig.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/SmokeWorkBudget.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/SmokeWorkBudget.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/VulkanSmokeRunner.java](../runtime-vulkan/src/main/java/dev/tellurium/runtime/vulkan/VulkanSmokeRunner.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/DeviceCapabilitiesTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/DeviceCapabilitiesTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/NativeRangeChoiceSmokeTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/NativeRangeChoiceSmokeTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/ChainStoragePlanTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/ChainStoragePlanTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/PipelineCacheLimitsTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/PipelineCacheLimitsTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/RawRequestOwnershipTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/RawRequestOwnershipTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/ScratchStoragePlanTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/ScratchStoragePlanTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/SpirvModuleCacheTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/production/SpirvModuleCacheTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/SmokeWorkBudgetTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/SmokeWorkBudgetTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/V02ProductionTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/V02ProductionTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/VulkanWorldgenExecutorTest.java](../runtime-vulkan/src/test/java/dev/tellurium/runtime/vulkan/VulkanWorldgenExecutorTest.java) | Executable CPU test |

## oracle-and-replay

Corpus comparator, counted replay and result artifacts.

| File | Role |
| --- | --- |
| [oracle-and-replay/build.gradle](../oracle-and-replay/build.gradle) | Build configuration/tooling |
| [oracle-and-replay/README.md](../oracle-and-replay/README.md) | Documentation |
| [oracle-and-replay/src/corpusApi/java/dev/tellurium/oracle/schema/CorpusApiVersion.java](../oracle-and-replay/src/corpusApi/java/dev/tellurium/oracle/schema/CorpusApiVersion.java) | Tooling, data or provenance |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/BaselineReport.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/BaselineReport.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/BaselineReportBuilder.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/BaselineReportBuilder.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/BaselineReportJson.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/BaselineReportJson.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/FullEndpoint.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/FullEndpoint.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/NoiseEndpoint.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/NoiseEndpoint.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/ReopenVerifier.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/ReopenVerifier.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/RunManifest.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/RunManifest.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/SavedChunkReceipt.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/SavedChunkReceipt.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/SavedEndpoint.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/SavedEndpoint.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/SavedWorldVerifier.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/SavedWorldVerifier.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/StageTimers.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/benchmark/StageTimers.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/FixtureResult.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/FixtureResult.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/CandidateRun.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/CandidateRun.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ChunkResultSnapshot.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ChunkResultSnapshot.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ChunkSnapshotComparator.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ChunkSnapshotComparator.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ComparisonCoverage.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ComparisonCoverage.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/CorpusValidator.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/CorpusValidator.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/DeterminismPreflight.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/DeterminismPreflight.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/FailureBundle.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/FailureBundle.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ProcessHarness.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ProcessHarness.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ReferenceRun.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/ReferenceRun.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/RunArtifactManifest.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/minecraft/RunArtifactManifest.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/ReplayCli.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/ReplayCli.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/ReplayFixture.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/ReplayFixture.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/ReplayReport.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/ReplayReport.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/ReplayRunner.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/ReplayRunner.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/ReportJson.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/ReportJson.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/CaptureIdentity.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/CaptureIdentity.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/ChunkSnapshot.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/ChunkSnapshot.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/CorpusManifest.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/CorpusManifest.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/SnapshotField.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/SnapshotField.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/SnapshotIo.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/schema/SnapshotIo.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/tellurium/oracle/SyntheticCorpus.java](../oracle-and-replay/src/main/java/dev/tellurium/oracle/SyntheticCorpus.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/BaselineReportTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/BaselineReportTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/EndpointContractTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/EndpointContractTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/minecraft/ProcessHarnessTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/minecraft/ProcessHarnessTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/ReplayCliTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/ReplayCliTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/ReplayRunnerTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/ReplayRunnerTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/ReportJsonTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/ReportJsonTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/SavedChunkReceiptTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/SavedChunkReceiptTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/SavedWorldVerifierTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/SavedWorldVerifierTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/tellurium/oracle/V02OracleTest.java](../oracle-and-replay/src/test/java/dev/tellurium/oracle/V02OracleTest.java) | Executable CPU test |

## mod

The mod: common source, per-loader and per-Minecraft-version files, and the builds of each.

| File | Role |
| --- | --- |
| [mod/common/src/gameTest/java/dev/tellurium/neoforge/gametest/DiagnosticGameTests.java](../mod/common/src/gameTest/java/dev/tellurium/neoforge/gametest/DiagnosticGameTests.java) | Opt-in game bootstrap test/resource |
| [mod/common/src/gameTest/resources/data/tellurium_test/structure/empty.nbt](../mod/common/src/gameTest/resources/data/tellurium_test/structure/empty.nbt) | Opt-in game bootstrap test/resource |
| [mod/common/src/gameTest/resources/META-INF/neoforge.mods.toml](../mod/common/src/gameTest/resources/META-INF/neoforge.mods.toml) | Opt-in game bootstrap test/resource |
| [mod/common/src/main/java/dev/tellurium/neoforge/bench/ChunkDigest.java](../mod/common/src/main/java/dev/tellurium/neoforge/bench/ChunkDigest.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/bench/ChunkThroughputBenchmark.java](../mod/common/src/main/java/dev/tellurium/neoforge/bench/ChunkThroughputBenchmark.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/bench/PlayerTour.java](../mod/common/src/main/java/dev/tellurium/neoforge/bench/PlayerTour.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/bench/StutterProbe.java](../mod/common/src/main/java/dev/tellurium/neoforge/bench/StutterProbe.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/command/StatusReport.java](../mod/common/src/main/java/dev/tellurium/neoforge/command/StatusReport.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/command/TelluriumCommands.java](../mod/common/src/main/java/dev/tellurium/neoforge/command/TelluriumCommands.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/ChunkSetFile.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/ChunkSetFile.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/CompatibilityRegistry.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/CompatibilityRegistry.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsBridge.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsBridge.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsColumns.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsColumns.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsConverter.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsConverter.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsHandover.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsHandover.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsRoughSpeedup.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/DistantHorizonsRoughSpeedup.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/LodPace.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/LodPace.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/TileRings.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/TileRings.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/compat/VoxyBridge.java](../mod/common/src/main/java/dev/tellurium/neoforge/compat/VoxyBridge.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/config/TelluriumConfig.java](../mod/common/src/main/java/dev/tellurium/neoforge/config/TelluriumConfig.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/config/TelluriumConfigLoader.java](../mod/common/src/main/java/dev/tellurium/neoforge/config/TelluriumConfigLoader.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/config/UserSettings.java](../mod/common/src/main/java/dev/tellurium/neoforge/config/UserSettings.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/DiagnosticSelfTest.java](../mod/common/src/main/java/dev/tellurium/neoforge/DiagnosticSelfTest.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/AquiferPrefill.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/AquiferPrefill.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/BaseHeightCache.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/BaseHeightCache.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/CavePlans.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/CavePlans.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/ClimateColumnCache.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/ClimateColumnCache.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/ColumnBiomeIndex.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/ColumnBiomeIndex.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/FastChunkApplier.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/FastChunkApplier.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/FastNoiseEngine.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/FastNoiseEngine.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/FastOrePlacement.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/FastOrePlacement.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/FastRouterCapture.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/FastRouterCapture.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/FastSurfaceCapture.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/FastSurfaceCapture.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/FastSurfaceState.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/FastSurfaceState.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/ForeignBeardifier.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/ForeignBeardifier.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/GpuRuntimeLoader.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/GpuRuntimeLoader.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/GraphDump.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/GraphDump.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/GraphStatistics.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/GraphStatistics.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/LazyMappedDensity.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/LazyMappedDensity.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/OrePlacementVerifier.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/OrePlacementVerifier.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/fast/UniformBiomeLookup.java](../mod/common/src/main/java/dev/tellurium/neoforge/fast/UniformBiomeLookup.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/legacy/StagedRoute.java](../mod/common/src/main/java/dev/tellurium/neoforge/legacy/StagedRoute.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/loader/Names.java](../mod/common/src/main/java/dev/tellurium/neoforge/loader/Names.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/BiomeManagerUniformMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/BiomeManagerUniformMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/BlockableEventLoopAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/BlockableEventLoopAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/BlockShapeCacheMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/BlockShapeCacheMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/CarvingContextAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/CarvingContextAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/CaveCarverConfigurationAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/CaveCarverConfigurationAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/CaveWorldCarverPlanMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/CaveWorldCarverPlanMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapAsyncLoadMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapAsyncLoadMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapAsyncSaveMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapAsyncSaveMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapUnloadTypeMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapUnloadTypeMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkSerializerAsyncLoadMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkSerializerAsyncLoadMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkSerializerAsyncMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkSerializerAsyncMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkSerializerSurfaceMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkSerializerSurfaceMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkStatusTasksMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkStatusTasksMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkStatusTasksStructureMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkStatusTasksStructureMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkStorageAsyncMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkStorageAsyncMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkStorageInfoAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkStorageInfoAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkTaskPriorityQueueMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ChunkTaskPriorityQueueMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ClimateParameterListMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ClimateParameterListMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ClimateRTreeMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ClimateRTreeMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerGroupCommitMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerGroupCommitMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerHeaderFlushMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerHeaderFlushMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerOldChunkMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerOldChunkMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/LegacyRandomSourceAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/LegacyRandomSourceAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/LevelChunkSectionAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/LevelChunkSectionAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/LevelChunkSectionUniformBiomeMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/LevelChunkSectionUniformBiomeMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/MinecraftServerIdleUnloadMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/MinecraftServerIdleUnloadMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/NoiseBasedChunkGeneratorFastMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/NoiseBasedChunkGeneratorFastMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/NoiseBasedChunkGeneratorHeightCacheMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/NoiseBasedChunkGeneratorHeightCacheMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/NoiseBasedChunkGeneratorSurfaceOnceMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/NoiseBasedChunkGeneratorSurfaceOnceMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/NoiseChunkLazyWrapMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/NoiseChunkLazyWrapMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/OreFeatureMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/OreFeatureMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/ProcessorMailboxDispatchMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/ProcessorMailboxDispatchMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileGroupCommitMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileGroupCommitMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileHeaderMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileHeaderMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileStorageAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileStorageAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileStorageCompressMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/RegionFileStorageCompressMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/TelluriumMixinPlugin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/TelluriumMixinPlugin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/WorldgenRandomAccessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/WorldgenRandomAccessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/mixin/WorldGenRegionChunkCacheMixin.java](../mod/common/src/main/java/dev/tellurium/neoforge/mixin/WorldGenRegionChunkCacheMixin.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/pregen/InFlightWindow.java](../mod/common/src/main/java/dev/tellurium/neoforge/pregen/InFlightWindow.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/pregen/Pregenerator.java](../mod/common/src/main/java/dev/tellurium/neoforge/pregen/Pregenerator.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/pregen/TileOrder.java](../mod/common/src/main/java/dev/tellurium/neoforge/pregen/TileOrder.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/ChunkMutationJournal.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/ChunkMutationJournal.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/CompletionObservation.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/CompletionObservation.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/CoordinatedNoiseAttempt.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/CoordinatedNoiseAttempt.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/GenerationInterceptor.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/GenerationInterceptor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/HeightmapPacking.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/HeightmapPacking.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/HookTelemetry.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/HookTelemetry.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/IsolatedGpuEvidence.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/IsolatedGpuEvidence.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/LiveNoiseBridge.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/LiveNoiseBridge.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/LogicalCandidateBackend.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/LogicalCandidateBackend.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftChunkApplier.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftChunkApplier.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftChunkCommitter.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftChunkCommitter.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftCpuCandidate.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftCpuCandidate.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftCpuNoiseProvider.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftCpuNoiseProvider.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftGpuCandidate.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftGpuCandidate.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftGpuNoiseProvider.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftGpuNoiseProvider.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftLogicalSnapshotWriter.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftLogicalSnapshotWriter.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftNoiseChunkLifecycle.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftNoiseChunkLifecycle.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftNoiseOwnership.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftNoiseOwnership.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftNoisePostProcessor.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftNoisePostProcessor.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftOwnershipToken.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftOwnershipToken.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftResultValidator.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftResultValidator.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftStageAdapter.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/MinecraftStageAdapter.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/NativeDependencyBootstrap.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/NativeDependencyBootstrap.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/NoiseChunkLifecycleAdapter.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/NoiseChunkLifecycleAdapter.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/ProviderActivity.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/ProviderActivity.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualificationEvidenceBundleFile.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualificationEvidenceBundleFile.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualificationEvidenceFile.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualificationEvidenceFile.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualifiedHookAdmission.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualifiedHookAdmission.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualifiedHookEvidence.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualifiedHookEvidence.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualifiedHookEvidenceBundle.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/QualifiedHookEvidenceBundle.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/ReloadCoordinator.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/ReloadCoordinator.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/RuntimeComposition.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/RuntimeComposition.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/SaveBarrier.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/SaveBarrier.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/runtime/ServerLifecycle.java](../mod/common/src/main/java/dev/tellurium/neoforge/runtime/ServerLifecycle.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/snapshot/DensityNodeReader.java](../mod/common/src/main/java/dev/tellurium/neoforge/snapshot/DensityNodeReader.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/snapshot/MinecraftDynamicInputReader.java](../mod/common/src/main/java/dev/tellurium/neoforge/snapshot/MinecraftDynamicInputReader.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/snapshot/MinecraftSnapshotReader.java](../mod/common/src/main/java/dev/tellurium/neoforge/snapshot/MinecraftSnapshotReader.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/snapshot/RegistrySnapshotReader.java](../mod/common/src/main/java/dev/tellurium/neoforge/snapshot/RegistrySnapshotReader.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/snapshot/StructureBlendReader.java](../mod/common/src/main/java/dev/tellurium/neoforge/snapshot/StructureBlendReader.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/TelluriumMod.java](../mod/common/src/main/java/dev/tellurium/neoforge/TelluriumMod.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/AsyncChunkLoad.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/AsyncChunkLoad.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/AsyncSectionEncoding.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/AsyncSectionEncoding.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/DeferredRegionHeaders.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/DeferredRegionHeaders.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/FeatureRegionScheduler.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/FeatureRegionScheduler.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/GroupCommit.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/GroupCommit.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/IdleUnloads.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/IdleUnloads.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/MailboxThreads.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/MailboxThreads.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/ParallelWorldgenSteps.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/ParallelWorldgenSteps.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/PendingChunkSaves.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/PendingChunkSaves.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/PrecompressedChunks.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/PrecompressedChunks.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/threading/ShapeFullBlockCache.java](../mod/common/src/main/java/dev/tellurium/neoforge/threading/ShapeFullBlockCache.java) | Implementation or explicit public contract |
| [mod/common/src/main/java/dev/tellurium/neoforge/version/Version.java](../mod/common/src/main/java/dev/tellurium/neoforge/version/Version.java) | Implementation or explicit public contract |
| [mod/common/src/main/resources/tellurium/fused-qualified.properties](../mod/common/src/main/resources/tellurium/fused-qualified.properties) | Packaged metadata/resource |
| [mod/common/src/main/resources/tellurium.mixins.json](../mod/common/src/main/resources/tellurium.mixins.json) | Packaged metadata/resource |
| [mod/common/src/test/java/dev/tellurium/neoforge/command/TelluriumCommandsTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/command/TelluriumCommandsTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/compat/ChunkSetFileTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/compat/ChunkSetFileTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/compat/TileRingsTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/compat/TileRingsTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/config/TelluriumConfigLoaderTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/config/TelluriumConfigLoaderTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/config/UserSettingsTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/config/UserSettingsTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/DiagnosticSelfTestTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/DiagnosticSelfTestTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/pregen/InFlightWindowTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/pregen/InFlightWindowTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/pregen/TileOrderTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/pregen/TileOrderTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/ChunkMutationJournalTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/ChunkMutationJournalTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/CompletionObservationTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/CompletionObservationTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/IsolatedGpuEvidenceTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/IsolatedGpuEvidenceTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/LiveNoiseBridgeTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/LiveNoiseBridgeTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/MinecraftGpuCandidateTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/MinecraftGpuCandidateTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/ProviderActivityTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/ProviderActivityTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/QualificationEvidenceFileTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/QualificationEvidenceFileTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/QualifiedHookAdmissionTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/QualifiedHookAdmissionTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/QualifiedHookEvidenceBundleTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/QualifiedHookEvidenceBundleTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/QualifiedHookEvidenceTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/QualifiedHookEvidenceTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/runtime/RuntimeContractTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/runtime/RuntimeContractTest.java) | Executable CPU test |
| [mod/common/src/test/java/dev/tellurium/neoforge/snapshot/StructureBlendReaderTest.java](../mod/common/src/test/java/dev/tellurium/neoforge/snapshot/StructureBlendReaderTest.java) | Executable CPU test |
| [mod/fabric.gradle](../mod/fabric.gradle) | Build configuration/tooling |
| [mod/gpu-runtime.gradle](../mod/gpu-runtime.gradle) | Build configuration/tooling |
| [mod/loader/fabric/src/main/java/dev/tellurium/neoforge/loader/FabricEntry.java](../mod/loader/fabric/src/main/java/dev/tellurium/neoforge/loader/FabricEntry.java) | Implementation or explicit public contract |
| [mod/loader/fabric/src/main/java/dev/tellurium/neoforge/loader/Loader.java](../mod/loader/fabric/src/main/java/dev/tellurium/neoforge/loader/Loader.java) | Implementation or explicit public contract |
| [mod/loader/fabric/src/main/java/dev/tellurium/neoforge/loader/mixin/StructurePaletteCacheMixin.java](../mod/loader/fabric/src/main/java/dev/tellurium/neoforge/loader/mixin/StructurePaletteCacheMixin.java) | Implementation or explicit public contract |
| [mod/loader/fabric/src/main/resources/fabric.mod.json](../mod/loader/fabric/src/main/resources/fabric.mod.json) | Packaged metadata/resource |
| [mod/loader/fabric/src/main/resources/tellurium.accesswidener](../mod/loader/fabric/src/main/resources/tellurium.accesswidener) | Packaged metadata/resource |
| [mod/loader/fabric/src/main/resources/tellurium.fabric.mixins.json](../mod/loader/fabric/src/main/resources/tellurium.fabric.mixins.json) | Packaged metadata/resource |
| [mod/loader/neoforge/src/main/java/dev/tellurium/neoforge/loader/Loader.java](../mod/loader/neoforge/src/main/java/dev/tellurium/neoforge/loader/Loader.java) | Implementation or explicit public contract |
| [mod/loader/neoforge/src/main/java/dev/tellurium/neoforge/loader/NeoForgeEntry.java](../mod/loader/neoforge/src/main/java/dev/tellurium/neoforge/loader/NeoForgeEntry.java) | Implementation or explicit public contract |
| [mod/loader/neoforge/src/main/resources/META-INF/neoforge.mods.toml](../mod/loader/neoforge/src/main/resources/META-INF/neoforge.mods.toml) | Packaged metadata/resource |
| [mod/neoforge-diagnostics.gradle](../mod/neoforge-diagnostics.gradle) | Build configuration/tooling |
| [mod/neoforge.gradle](../mod/neoforge.gradle) | Build configuration/tooling |
| [mod/README.md](../mod/README.md) | Documentation |
| [mod/sources.gradle](../mod/sources.gradle) | Build configuration/tooling |
| [mod/targets.gradle](../mod/targets.gradle) | Build configuration/tooling |
| [mod/versions/1.21.11/removed.txt](../mod/versions/1.21.11/removed.txt) | Tooling, data or provenance |
| [mod/versions/1.21.11/renames.gradle](../mod/versions/1.21.11/renames.gradle) | Build configuration/tooling |
| [mod/versions/1.21.11/src/main/java/dev/tellurium/neoforge/mixin/SerializableChunkDataSurfaceMixin.java](../mod/versions/1.21.11/src/main/java/dev/tellurium/neoforge/mixin/SerializableChunkDataSurfaceMixin.java) | Implementation or explicit public contract |
| [mod/versions/1.21.11/src/main/java/dev/tellurium/neoforge/version/Version.java](../mod/versions/1.21.11/src/main/java/dev/tellurium/neoforge/version/Version.java) | Implementation or explicit public contract |
| [mod/versions/1.21.11/src/main/resources/tellurium/fused-qualified.properties](../mod/versions/1.21.11/src/main/resources/tellurium/fused-qualified.properties) | Packaged metadata/resource |
| [mod/versions/1.21.11/src/main/resources/tellurium.mixins.json](../mod/versions/1.21.11/src/main/resources/tellurium.mixins.json) | Packaged metadata/resource |
| [mod/versions/1.21.4/removed.txt](../mod/versions/1.21.4/removed.txt) | Tooling, data or provenance |
| [mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapSavePipelineMixin.java](../mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapSavePipelineMixin.java) | Implementation or explicit public contract |
| [mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapUnloadTypeMixin.java](../mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/ChunkMapUnloadTypeMixin.java) | Implementation or explicit public contract |
| [mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/ConsecutiveExecutorBatchMixin.java](../mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/ConsecutiveExecutorBatchMixin.java) | Implementation or explicit public contract |
| [mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerGroupCommitMixin.java](../mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerGroupCommitMixin.java) | Implementation or explicit public contract |
| [mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerHeaderFlushMixin.java](../mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/IOWorkerHeaderFlushMixin.java) | Implementation or explicit public contract |
| [mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/SerializableChunkDataSurfaceMixin.java](../mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/mixin/SerializableChunkDataSurfaceMixin.java) | Implementation or explicit public contract |
| [mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/threading/SavePipeline.java](../mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/threading/SavePipeline.java) | Implementation or explicit public contract |
| [mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/version/Version.java](../mod/versions/1.21.4/src/main/java/dev/tellurium/neoforge/version/Version.java) | Implementation or explicit public contract |
| [mod/versions/1.21.4/src/main/resources/tellurium/fused-qualified.properties](../mod/versions/1.21.4/src/main/resources/tellurium/fused-qualified.properties) | Packaged metadata/resource |
| [mod/versions/1.21.4/src/main/resources/tellurium.mixins.json](../mod/versions/1.21.4/src/main/resources/tellurium.mixins.json) | Packaged metadata/resource |
| [mod/versions/1.21.8/src/main/java/dev/tellurium/neoforge/version/Version.java](../mod/versions/1.21.8/src/main/java/dev/tellurium/neoforge/version/Version.java) | Implementation or explicit public contract |
| [mod/versions/1.21.8/src/main/resources/tellurium/fused-qualified.properties](../mod/versions/1.21.8/src/main/resources/tellurium/fused-qualified.properties) | Packaged metadata/resource |

## Project tooling, documentation and evidence

| File | Role |
| --- | --- |
| [.gitattributes](../.gitattributes) | Tooling, data or provenance |
| [.github/workflows/ci.yml](../.github/workflows/ci.yml) | Tooling, data or provenance |
| [.gitignore](../.gitignore) | Tooling, data or provenance |
| [AGENTS.md](../AGENTS.md) | Documentation |
| [build.gradle](../build.gradle) | Build configuration/tooling |
| [CODE_REVIEW.md](../CODE_REVIEW.md) | Documentation |
| [CODE_REVIEW_2026-10-07.md](../CODE_REVIEW_2026-10-07.md) | Documentation |
| [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) | Documentation |
| [docs/ATTRIBUTION.md](../docs/ATTRIBUTION.md) | Documentation |
| [docs/branding/launch-video.md](../docs/branding/launch-video.md) | Documentation |
| [docs/branding/modrinth-page.md](../docs/branding/modrinth-page.md) | Documentation |
| [docs/branding/tellurium-logo-256.png](../docs/branding/tellurium-logo-256.png) | Tooling, data or provenance |
| [docs/branding/tellurium-logo-chunk.png](../docs/branding/tellurium-logo-chunk.png) | Tooling, data or provenance |
| [docs/branding/tellurium-logo-chunk.svg](../docs/branding/tellurium-logo-chunk.svg) | Tooling, data or provenance |
| [docs/branding/tellurium-logo-pixel.svg](../docs/branding/tellurium-logo-pixel.svg) | Tooling, data or provenance |
| [docs/branding/tellurium-logo.png](../docs/branding/tellurium-logo.png) | Tooling, data or provenance |
| [docs/branding/tellurium-logo.svg](../docs/branding/tellurium-logo.svg) | Tooling, data or provenance |
| [docs/COMPATIBILITY.md](../docs/COMPATIBILITY.md) | Documentation |
| [docs/CONFIGURATION.md](../docs/CONFIGURATION.md) | Documentation |
| [docs/CONTRACTS.md](../docs/CONTRACTS.md) | Documentation |
| [docs/design/EVIDENCE.md](../docs/design/EVIDENCE.md) | Documentation |
| [docs/design/performance-model.json](../docs/design/performance-model.json) | Tooling, data or provenance |
| [docs/design/PROPOSAL.md](../docs/design/PROPOSAL.md) | Documentation |
| [docs/evidence/code-review-20261006.md](../docs/evidence/code-review-20261006.md) | Documentation |
| [docs/evidence/code-review-20261007.md](../docs/evidence/code-review-20261007.md) | Documentation |
| [docs/evidence/comparison-c2me.md](../docs/evidence/comparison-c2me.md) | Documentation |
| [docs/evidence/comparison-distant-horizons.md](../docs/evidence/comparison-distant-horizons.md) | Documentation |
| [docs/evidence/comparison-voxy.md](../docs/evidence/comparison-voxy.md) | Documentation |
| [docs/evidence/fabric-port.md](../docs/evidence/fabric-port.md) | Documentation |
| [docs/evidence/minecraft-1.21.11.md](../docs/evidence/minecraft-1.21.11.md) | Documentation |
| [docs/evidence/minecraft-versions.md](../docs/evidence/minecraft-versions.md) | Documentation |
| [docs/evidence/smoothness.md](../docs/evidence/smoothness.md) | Documentation |
| [docs/evidence/throughput-fused-gpu.md](../docs/evidence/throughput-fused-gpu.md) | Documentation |
| [docs/evidence/v0.1-cpu-replay.json](../docs/evidence/v0.1-cpu-replay.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-cpu-tests.json](../docs/evidence/v0.1-cpu-tests.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-game-bootstrap.txt](../docs/evidence/v0.1-game-bootstrap.txt) | Tooling, data or provenance |
| [docs/evidence/v0.1-gpu-normal-range.json](../docs/evidence/v0.1-gpu-normal-range.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-gpu-strict.json](../docs/evidence/v0.1-gpu-strict.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-validation.json](../docs/evidence/v0.1-validation.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-validation.md](../docs/evidence/v0.1-validation.md) | Documentation |
| [docs/evidence/v0.2-aquifer-integer-floor.md](../docs/evidence/v0.2-aquifer-integer-floor.md) | Documentation |
| [docs/evidence/v0.2-beardifier-gpu.md](../docs/evidence/v0.2-beardifier-gpu.md) | Documentation |
| [docs/evidence/v0.2-candidate-saved.md](../docs/evidence/v0.2-candidate-saved.md) | Documentation |
| [docs/evidence/v0.2-coordinator-model-campaign.md](../docs/evidence/v0.2-coordinator-model-campaign.md) | Documentation |
| [docs/evidence/v0.2-cpu-candidate.md](../docs/evidence/v0.2-cpu-candidate.md) | Documentation |
| [docs/evidence/v0.2-cpu-live-prototype.md](../docs/evidence/v0.2-cpu-live-prototype.md) | Documentation |
| [docs/evidence/v0.2-gpu-candidate-end.md](../docs/evidence/v0.2-gpu-candidate-end.md) | Documentation |
| [docs/evidence/v0.2-gpu-candidate-nether.md](../docs/evidence/v0.2-gpu-candidate-nether.md) | Documentation |
| [docs/evidence/v0.2-gpu-candidate-overworld-staging.md](../docs/evidence/v0.2-gpu-candidate-overworld-staging.md) | Documentation |
| [docs/evidence/v0.2-gpu-exact-overworld.md](../docs/evidence/v0.2-gpu-exact-overworld.md) | Documentation |
| [docs/evidence/v0.2-gpu-executor.md](../docs/evidence/v0.2-gpu-executor.md) | Documentation |
| [docs/evidence/v0.2-gpu-seed1-fix.md](../docs/evidence/v0.2-gpu-seed1-fix.md) | Documentation |
| [docs/evidence/v0.2-host-copy-fingerprint.md](../docs/evidence/v0.2-host-copy-fingerprint.md) | Documentation |
| [docs/evidence/v0.2-integer-ieee.md](../docs/evidence/v0.2-integer-ieee.md) | Documentation |
| [docs/evidence/v0.2-live-gpu-verifier.md](../docs/evidence/v0.2-live-gpu-verifier.md) | Documentation |
| [docs/evidence/v0.2-mapped-capture.md](../docs/evidence/v0.2-mapped-capture.md) | Documentation |
| [docs/evidence/v0.2-minecraft-application.md](../docs/evidence/v0.2-minecraft-application.md) | Documentation |
| [docs/evidence/v0.2-original-full.md](../docs/evidence/v0.2-original-full.md) | Documentation |
| [docs/evidence/v0.2-original-oracle.md](../docs/evidence/v0.2-original-oracle.md) | Documentation |
| [docs/evidence/v0.2-original-saved.md](../docs/evidence/v0.2-original-saved.md) | Documentation |
| [docs/evidence/v0.2-shared-blended-reduction.md](../docs/evidence/v0.2-shared-blended-reduction.md) | Documentation |
| [docs/evidence/v0.2-shared-end-metadata.md](../docs/evidence/v0.2-shared-end-metadata.md) | Documentation |
| [docs/evidence/v0.2-shared-fp64-divider.md](../docs/evidence/v0.2-shared-fp64-divider.md) | Documentation |
| [docs/evidence/v0.2-terrain-mods.md](../docs/evidence/v0.2-terrain-mods.md) | Documentation |
| [docs/IMPLEMENTATION_MAP.md](../docs/IMPLEMENTATION_MAP.md) | Documentation |
| [docs/RELEASING.md](../docs/RELEASING.md) | Documentation |
| [docs/ROADMAP.md](../docs/ROADMAP.md) | Documentation |
| [docs/STATUS.md](../docs/STATUS.md) | Documentation |
| [docs/TEST_PLAN.md](../docs/TEST_PLAN.md) | Documentation |
| [docs/TESTING.md](../docs/TESTING.md) | Documentation |
| [docs/TROUBLESHOOTING.md](../docs/TROUBLESHOOTING.md) | Documentation |
| [docs/v0.2/DRAFT-CLEANUP.md](../docs/v0.2/DRAFT-CLEANUP.md) | Documentation |
| [docs/v0.2/FILES.md](../docs/v0.2/FILES.md) | Documentation |
| [docs/v0.2/TESTS.md](../docs/v0.2/TESTS.md) | Documentation |
| [docs/v0.2/WORK_PACKAGES.json](../docs/v0.2/WORK_PACKAGES.json) | Tooling, data or provenance |
| [docs/V0.2-PLAN.md](../docs/V0.2-PLAN.md) | Documentation |
| [gradle/wrapper/gradle-wrapper.jar](../gradle/wrapper/gradle-wrapper.jar) | Tooling, data or provenance |
| [gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) | Build configuration/tooling |
| [gradle.properties](../gradle.properties) | Build configuration/tooling |
| [gradlew](../gradlew) | Build configuration/tooling |
| [gradlew.bat](../gradlew.bat) | Build configuration/tooling |
| [LICENSE](../LICENSE) | Tooling, data or provenance |
| [NOTICE](../NOTICE) | Tooling, data or provenance |
| [oracle-1211/build.gradle](../oracle-1211/build.gradle) | Build configuration/tooling |
| [oracle-1211/README.md](../oracle-1211/README.md) | Documentation |
| [oracle-1211/src/main/java/dev/tellurium/oracle1211/OracleCaptureMod.java](../oracle-1211/src/main/java/dev/tellurium/oracle1211/OracleCaptureMod.java) | Implementation or explicit public contract |
| [oracle-1211/src/main/resources/META-INF/neoforge.mods.toml](../oracle-1211/src/main/resources/META-INF/neoforge.mods.toml) | Packaged metadata/resource |
| [oracle-1211/src/main/resources/pack.mcmeta](../oracle-1211/src/main/resources/pack.mcmeta) | Packaged metadata/resource |
| [Plan.md](../Plan.md) | Documentation |
| [README.md](../README.md) | Documentation |
| [scripts/bench-cps.ps1](../scripts/bench-cps.ps1) | Tooling, data or provenance |
| [scripts/bench-full.sh](../scripts/bench-full.sh) | Tooling, data or provenance |
| [scripts/capture-baseline.py](../scripts/capture-baseline.py) | Tooling, data or provenance |
| [scripts/capture-candidate-saved.ps1](../scripts/capture-candidate-saved.ps1) | Tooling, data or provenance |
| [scripts/capture-original-corpus.ps1](../scripts/capture-original-corpus.ps1) | Tooling, data or provenance |
| [scripts/capture-original-endpoint-matrix.ps1](../scripts/capture-original-endpoint-matrix.ps1) | Tooling, data or provenance |
| [scripts/capture-original-full.ps1](../scripts/capture-original-full.ps1) | Tooling, data or provenance |
| [scripts/capture-original-saved.ps1](../scripts/capture-original-saved.ps1) | Tooling, data or provenance |
| [scripts/check-bench-report.py](../scripts/check-bench-report.py) | Tooling, data or provenance |
| [scripts/check-region-files.py](../scripts/check-region-files.py) | Tooling, data or provenance |
| [scripts/check.ps1](../scripts/check.ps1) | Tooling, data or provenance |
| [scripts/compare-chunk-mods.sh](../scripts/compare-chunk-mods.sh) | Tooling, data or provenance |
| [scripts/compare-dh.sh](../scripts/compare-dh.sh) | Tooling, data or provenance |
| [scripts/compare-digests.py](../scripts/compare-digests.py) | Tooling, data or provenance |
| [scripts/compare-flight.sh](../scripts/compare-flight.sh) | Tooling, data or provenance |
| [scripts/CompiledReplayInputs.ps1](../scripts/CompiledReplayInputs.ps1) | Tooling, data or provenance |
| [scripts/dh-database-steps.py](../scripts/dh-database-steps.py) | Tooling, data or provenance |
| [scripts/fetch-fabric-test-files.py](../scripts/fetch-fabric-test-files.py) | Tooling, data or provenance |
| [scripts/fetch-terrain-test-mods.py](../scripts/fetch-terrain-test-mods.py) | Tooling, data or provenance |
| [scripts/Invoke-ReplayProcess.ps1](../scripts/Invoke-ReplayProcess.ps1) | Tooling, data or provenance |
| [scripts/jfr-stage-profile.py](../scripts/jfr-stage-profile.py) | Tooling, data or provenance |
| [scripts/LogicalEndpointMatrix.ps1](../scripts/LogicalEndpointMatrix.ps1) | Tooling, data or provenance |
| [scripts/merge-frozen-gpu-replay.ps1](../scripts/merge-frozen-gpu-replay.ps1) | Tooling, data or provenance |
| [scripts/minecraft-replay.py](../scripts/minecraft-replay.py) | Tooling, data or provenance |
| [scripts/probe-fused-kernels.ps1](../scripts/probe-fused-kernels.ps1) | Tooling, data or provenance |
| [scripts/probe-gpu-density.ps1](../scripts/probe-gpu-density.ps1) | Tooling, data or provenance |
| [scripts/qualify-v02.ps1](../scripts/qualify-v02.ps1) | Tooling, data or provenance |
| [scripts/release-check.sh](../scripts/release-check.sh) | Tooling, data or provenance |
| [scripts/replay-minecraft-logical-matrix.ps1](../scripts/replay-minecraft-logical-matrix.ps1) | Tooling, data or provenance |
| [scripts/replay-minecraft-logical.ps1](../scripts/replay-minecraft-logical.ps1) | Tooling, data or provenance |
| [scripts/replay-minecraft-noise-cpu.ps1](../scripts/replay-minecraft-noise-cpu.ps1) | Tooling, data or provenance |
| [scripts/replay-minecraft-noise-gpu-matrix.ps1](../scripts/replay-minecraft-noise-gpu-matrix.ps1) | Tooling, data or provenance |
| [scripts/replay-minecraft-noise-gpu.ps1](../scripts/replay-minecraft-noise-gpu.ps1) | Tooling, data or provenance |
| [scripts/run-client-pregen.sh](../scripts/run-client-pregen.sh) | Tooling, data or provenance |
| [scripts/run-pregen.sh](../scripts/run-pregen.sh) | Tooling, data or provenance |
| [scripts/run-prototype-cpu-live.ps1](../scripts/run-prototype-cpu-live.ps1) | Tooling, data or provenance |
| [scripts/summarize-tests.py](../scripts/summarize-tests.py) | Tooling, data or provenance |
| [scripts/test-installed-commands.sh](../scripts/test-installed-commands.sh) | Tooling, data or provenance |
| [scripts/test-installed-fabric.sh](../scripts/test-installed-fabric.sh) | Tooling, data or provenance |
| [scripts/test-installed-mod.ps1](../scripts/test-installed-mod.ps1) | Tooling, data or provenance |
| [scripts/test-installed-neoforge.sh](../scripts/test-installed-neoforge.sh) | Tooling, data or provenance |
| [scripts/test-worldgen-mods.py](../scripts/test-worldgen-mods.py) | Tooling, data or provenance |
| [scripts/tests/FocusedJUnitRunner.java](../scripts/tests/FocusedJUnitRunner.java) | Tooling, data or provenance |
| [scripts/tests/test-compiled-replay-inputs.ps1](../scripts/tests/test-compiled-replay-inputs.ps1) | Tooling, data or provenance |
| [scripts/tests/test-gpu-matrix-contract.ps1](../scripts/tests/test-gpu-matrix-contract.ps1) | Tooling, data or provenance |
| [scripts/tests/test-logical-endpoint-matrix.ps1](../scripts/tests/test-logical-endpoint-matrix.ps1) | Tooling, data or provenance |
| [scripts/tests/test-original-capture-plan.ps1](../scripts/tests/test-original-capture-plan.ps1) | Tooling, data or provenance |
| [scripts/tests/test-original-endpoint-matrix-plan.ps1](../scripts/tests/test-original-endpoint-matrix-plan.ps1) | Tooling, data or provenance |
| [scripts/tests/test-replay-process.ps1](../scripts/tests/test-replay-process.ps1) | Tooling, data or provenance |
| [scripts/tests/test_capture_baseline.py](../scripts/tests/test_capture_baseline.py) | Tooling, data or provenance |
| [scripts/tests/test_verification_scripts.py](../scripts/tests/test_verification_scripts.py) | Tooling, data or provenance |
| [scripts/update-file-map.py](../scripts/update-file-map.py) | Tooling, data or provenance |
| [scripts/verify-fast-matrix.sh](../scripts/verify-fast-matrix.sh) | Tooling, data or provenance |
| [scripts/verify-kill-recovery.sh](../scripts/verify-kill-recovery.sh) | Tooling, data or provenance |
| [scripts/verify-save-reopen.sh](../scripts/verify-save-reopen.sh) | Tooling, data or provenance |
| [scripts/write-qualification-bundle.ps1](../scripts/write-qualification-bundle.ps1) | Tooling, data or provenance |
| [scripts/write-qualification-evidence.ps1](../scripts/write-qualification-evidence.ps1) | Tooling, data or provenance |
| [settings.gradle](../settings.gradle) | Build configuration/tooling |
| [test-manifest/baseline-workloads.json](../test-manifest/baseline-workloads.json) | Tooling, data or provenance |
| [test-manifest/corpus/index.json](../test-manifest/corpus/index.json) | Tooling, data or provenance |
| [test-manifest/dependencies.lock.json](../test-manifest/dependencies.lock.json) | Tooling, data or provenance |
| [test-manifest/minecraft-parity.json](../test-manifest/minecraft-parity.json) | Tooling, data or provenance |
| [test-manifest/normalization-policy.json](../test-manifest/normalization-policy.json) | Tooling, data or provenance |
| [test-manifest/numeric-operators.json](../test-manifest/numeric-operators.json) | Tooling, data or provenance |
| [test-manifest/v0.2-acceptance.json](../test-manifest/v0.2-acceptance.json) | Tooling, data or provenance |
| [test-manifest/v0.2-gpu-matrix-local.json](../test-manifest/v0.2-gpu-matrix-local.json) | Tooling, data or provenance |
| [third-party/gradle-LICENSE.txt](../third-party/gradle-LICENSE.txt) | Tooling, data or provenance |
