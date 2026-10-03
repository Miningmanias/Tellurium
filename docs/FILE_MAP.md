# Project file map

Generated from the files on disk by `python scripts/update-file-map.py`.

This inventory excludes Git state, build output, dependency caches and development worlds. It does not treat a planned file as implemented. See [IMPLEMENTATION_MAP.md](IMPLEMENTATION_MAP.md) for future files and [STATUS.md](STATUS.md) for observed validation.

Indexed project files: **449**, plus this generated index.

## semantic-core

Immutable semantic language and independent reference evaluation.

| File | Role |
| --- | --- |
| [semantic-core/README.md](../semantic-core/README.md) | Documentation |
| [semantic-core/src/main/java/dev/worldgennext/semantic/CellGeometry.java](../semantic-core/src/main/java/dev/worldgennext/semantic/CellGeometry.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/CompiledDensity.java](../semantic-core/src/main/java/dev/worldgennext/semantic/CompiledDensity.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/DensityEvaluator.java](../semantic-core/src/main/java/dev/worldgennext/semantic/DensityEvaluator.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/DensityExpression.java](../semantic-core/src/main/java/dev/worldgennext/semantic/DensityExpression.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/execution/CapabilityDecision.java](../semantic-core/src/main/java/dev/worldgennext/semantic/execution/CapabilityDecision.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/execution/ExecutionReceipt.java](../semantic-core/src/main/java/dev/worldgennext/semantic/execution/ExecutionReceipt.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/execution/ProgramAbi.java](../semantic-core/src/main/java/dev/worldgennext/semantic/execution/ProgramAbi.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/execution/ResultBufferLease.java](../semantic-core/src/main/java/dev/worldgennext/semantic/execution/ResultBufferLease.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/ExpressionIdentity.java](../semantic-core/src/main/java/dev/worldgennext/semantic/ExpressionIdentity.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/ExpressionValidation.java](../semantic-core/src/main/java/dev/worldgennext/semantic/ExpressionValidation.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/identity/ContextIdentity.java](../semantic-core/src/main/java/dev/worldgennext/semantic/identity/ContextIdentity.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/identity/DynamicInputIdentity.java](../semantic-core/src/main/java/dev/worldgennext/semantic/identity/DynamicInputIdentity.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/identity/ProgramFingerprint.java](../semantic-core/src/main/java/dev/worldgennext/semantic/identity/ProgramFingerprint.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/material/AquiferProgram.java](../semantic-core/src/main/java/dev/worldgennext/semantic/material/AquiferProgram.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/material/MaterialProgram.java](../semantic-core/src/main/java/dev/worldgennext/semantic/material/MaterialProgram.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/material/OreVeinProgram.java](../semantic-core/src/main/java/dev/worldgennext/semantic/material/OreVeinProgram.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/ControlRegion.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/ControlRegion.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/EvaluationDomain.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/EvaluationDomain.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/InterpolationGeometry.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/InterpolationGeometry.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/JavaIntegerSemantics.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/JavaIntegerSemantics.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/MarkerContext.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/MarkerContext.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/NumericProfile.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/NumericProfile.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/NumericSemantics.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/NumericSemantics.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/ProgramNode.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/ProgramNode.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/ValueType.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/ValueType.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/program/WorldgenProgram.java](../semantic-core/src/main/java/dev/worldgennext/semantic/program/WorldgenProgram.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/ReferenceInterpreter.java](../semantic-core/src/main/java/dev/worldgennext/semantic/ReferenceInterpreter.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/SamplePoint.java](../semantic-core/src/main/java/dev/worldgennext/semantic/SamplePoint.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/BeardifierSnapshot.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/BeardifierSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/BlendedNoiseParameters.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/BlendedNoiseParameters.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/BlockStateDescriptor.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/BlockStateDescriptor.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/BlockStateTraits.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/BlockStateTraits.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/EndIslandParameters.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/EndIslandParameters.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/GeneratorSettingsSnapshot.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/GeneratorSettingsSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/NoiseParameters.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/NoiseParameters.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/NoiseRouterSnapshot.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/NoiseRouterSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/PositionalRandomFactorySnapshot.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/PositionalRandomFactorySnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/RandomStateSnapshot.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/RandomStateSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/RegistrySnapshot.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/RegistrySnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/StructureBlendSnapshot.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/StructureBlendSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/WorldgenSnapshot.java](../semantic-core/src/main/java/dev/worldgennext/semantic/snapshot/WorldgenSnapshot.java) | Implementation or explicit public contract |
| [semantic-core/src/main/java/dev/worldgennext/semantic/WorldgenIdentity.java](../semantic-core/src/main/java/dev/worldgennext/semantic/WorldgenIdentity.java) | Implementation or explicit public contract |
| [semantic-core/src/test/java/dev/worldgennext/semantic/SemanticContractTest.java](../semantic-core/src/test/java/dev/worldgennext/semantic/SemanticContractTest.java) | Executable CPU test |
| [semantic-core/src/test/java/dev/worldgennext/semantic/V02ContractsTest.java](../semantic-core/src/test/java/dev/worldgennext/semantic/V02ContractsTest.java) | Executable CPU test |

## compiler-jvm

Prebound executable CPU programs.

| File | Role |
| --- | --- |
| [compiler-jvm/README.md](../compiler-jvm/README.md) | Documentation |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/CpuCompiler.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/CpuCompiler.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/AquiferEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/AquiferEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/BeardifierEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/BeardifierEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/BlendDensityEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/BlendDensityEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/BlendOffsetEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/BlendOffsetEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/CompiledWorldgenProgram.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/CompiledWorldgenProgram.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/DenseNoiseGenerator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/DenseNoiseGenerator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/EndIslandEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/EndIslandEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/EvaluationScratch.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/EvaluationScratch.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/InterpolationCursor.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/InterpolationCursor.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/LegacyRandom.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/LegacyRandom.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/MarkerEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/MarkerEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/NoiseEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/NoiseEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/OreVeinEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/OreVeinEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/PositionalRandom.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/PositionalRandom.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/SplineEvaluator.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/SplineEvaluator.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/WorldgenCpuCompiler.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/WorldgenCpuCompiler.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/WorldgenInterpreter.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/WorldgenInterpreter.java) | Implementation or explicit public contract |
| [compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/XoroshiroRandom.java](../compiler-jvm/src/main/java/dev/worldgennext/compiler/jvm/worldgen/XoroshiroRandom.java) | Implementation or explicit public contract |
| [compiler-jvm/src/test/java/dev/worldgennext/compiler/jvm/CpuCompilerTest.java](../compiler-jvm/src/test/java/dev/worldgennext/compiler/jvm/CpuCompilerTest.java) | Executable CPU test |
| [compiler-jvm/src/test/java/dev/worldgennext/compiler/jvm/V02WorldgenCpuTest.java](../compiler-jvm/src/test/java/dev/worldgennext/compiler/jvm/V02WorldgenCpuTest.java) | Executable CPU test |
| [compiler-jvm/src/test/java/dev/worldgennext/compiler/jvm/worldgen/AquiferEvaluatorTest.java](../compiler-jvm/src/test/java/dev/worldgennext/compiler/jvm/worldgen/AquiferEvaluatorTest.java) | Executable CPU test |

## compiler-vulkan

Vulkan GLSL source generation.

| File | Role |
| --- | --- |
| [compiler-vulkan/README.md](../compiler-vulkan/README.md) | Documentation |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/GlslCompiler.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/GlslCompiler.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/AquiferEmitter.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/AquiferEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/CapturedNoiseEmitter.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/CapturedNoiseEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/ControlFlowEmitter.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/ControlFlowEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/IntegerIeeeEmitter.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/IntegerIeeeEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/MarkerEmitter.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/MarkerEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/MaterialEmitter.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/MaterialEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/NoiseEmitter.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/NoiseEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/NumericHelperRegistry.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/NumericHelperRegistry.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/OreVeinEmitter.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/OreVeinEmitter.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/ShaderCacheKey.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/ShaderCacheKey.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/SpirvFunctionControlPatcher.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/SpirvFunctionControlPatcher.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/SpirvNumericContract.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/SpirvNumericContract.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/StageLayout.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/StageLayout.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/StagePlanner.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/StagePlanner.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/WorldgenShaderCompiler.java](../compiler-vulkan/src/main/java/dev/worldgennext/compiler/vulkan/worldgen/WorldgenShaderCompiler.java) | Implementation or explicit public contract |
| [compiler-vulkan/src/main/resources/shaders/ieee/bits32.glsl](../compiler-vulkan/src/main/resources/shaders/ieee/bits32.glsl) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/ieee/bits64.glsl](../compiler-vulkan/src/main/resources/shaders/ieee/bits64.glsl) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/ieee/integer-java.glsl](../compiler-vulkan/src/main/resources/shaders/ieee/integer-java.glsl) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/worldgen/material.comp](../compiler-vulkan/src/main/resources/shaders/worldgen/material.comp) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/worldgen/metadata.comp](../compiler-vulkan/src/main/resources/shaders/worldgen/metadata.comp) | Packaged metadata/resource |
| [compiler-vulkan/src/main/resources/shaders/worldgen/noise.comp](../compiler-vulkan/src/main/resources/shaders/worldgen/noise.comp) | Packaged metadata/resource |
| [compiler-vulkan/src/test/java/dev/worldgennext/compiler/vulkan/GlslCompilerTest.java](../compiler-vulkan/src/test/java/dev/worldgennext/compiler/vulkan/GlslCompilerTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/worldgennext/compiler/vulkan/SpirvFunctionControlPatcherTest.java](../compiler-vulkan/src/test/java/dev/worldgennext/compiler/vulkan/SpirvFunctionControlPatcherTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/worldgennext/compiler/vulkan/SpirvNumericContractTest.java](../compiler-vulkan/src/test/java/dev/worldgennext/compiler/vulkan/SpirvNumericContractTest.java) | Executable CPU test |
| [compiler-vulkan/src/test/java/dev/worldgennext/compiler/vulkan/V02WorldgenShaderTest.java](../compiler-vulkan/src/test/java/dev/worldgennext/compiler/vulkan/V02WorldgenShaderTest.java) | Executable CPU test |

## material-codec

Immutable final-state encodings and validation.

| File | Role |
| --- | --- |
| [material-codec/README.md](../material-codec/README.md) | Documentation |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/BlockStateTable.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/BlockStateTable.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkLogicalChecksum.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkLogicalChecksum.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkMetadataPayload.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkMetadataPayload.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkNoiseResult.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkNoiseResult.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkResultCodec.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkResultCodec.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkResultHeader.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkResultHeader.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkResultValidator.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/ChunkResultValidator.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/DenseResultEncoder.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/DenseResultEncoder.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/HeightmapPayload.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/HeightmapPayload.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/PostProcessingPayload.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/PostProcessingPayload.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/SectionCounts.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/SectionCounts.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/chunk/SectionDirectory.java](../material-codec/src/main/java/dev/worldgennext/material/chunk/SectionDirectory.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/DenseSection.java](../material-codec/src/main/java/dev/worldgennext/material/DenseSection.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/LogicalChecksum.java](../material-codec/src/main/java/dev/worldgennext/material/LogicalChecksum.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/PaletteSection.java](../material-codec/src/main/java/dev/worldgennext/material/PaletteSection.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/SectionCodec.java](../material-codec/src/main/java/dev/worldgennext/material/SectionCodec.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/SectionData.java](../material-codec/src/main/java/dev/worldgennext/material/SectionData.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/SectionMetadata.java](../material-codec/src/main/java/dev/worldgennext/material/SectionMetadata.java) | Implementation or explicit public contract |
| [material-codec/src/main/java/dev/worldgennext/material/UniformSection.java](../material-codec/src/main/java/dev/worldgennext/material/UniformSection.java) | Implementation or explicit public contract |
| [material-codec/src/test/java/dev/worldgennext/material/chunk/ChunkResultValidatorTest.java](../material-codec/src/test/java/dev/worldgennext/material/chunk/ChunkResultValidatorTest.java) | Executable CPU test |
| [material-codec/src/test/java/dev/worldgennext/material/SectionCodecTest.java](../material-codec/src/test/java/dev/worldgennext/material/SectionCodecTest.java) | Executable CPU test |
| [material-codec/src/test/java/dev/worldgennext/material/V02ChunkResultTest.java](../material-codec/src/test/java/dev/worldgennext/material/V02ChunkResultTest.java) | Executable CPU test |

## spatial-data

Tile identities, bounded bytes and shared ownership.

| File | Role |
| --- | --- |
| [spatial-data/README.md](../spatial-data/README.md) | Documentation |
| [spatial-data/src/main/java/dev/worldgennext/spatial/ByteBudget.java](../spatial-data/src/main/java/dev/worldgennext/spatial/ByteBudget.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/SharedTileCache.java](../spatial-data/src/main/java/dev/worldgennext/spatial/SharedTileCache.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/TileKey.java](../spatial-data/src/main/java/dev/worldgennext/spatial/TileKey.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/AquiferCellAtlas.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/AquiferCellAtlas.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/AsyncSampleProducer.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/AsyncSampleProducer.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/BoundedSampleStore.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/BoundedSampleStore.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/ColumnSamples.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/ColumnSamples.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/LatticeSamples.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/LatticeSamples.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/ResidencyLedger.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/ResidencyLedger.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleDomain.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleDomain.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleExtent.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleExtent.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleKey.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleKey.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleLease.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleLease.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleProducer.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleProducer.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleWindow.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SampleWindow.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SpatialSampleStore.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SpatialSampleStore.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SurfaceColumnAtlas.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/SurfaceColumnAtlas.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/TiledSampleConsumer.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/TiledSampleConsumer.java) | Implementation or explicit public contract |
| [spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/UncachedSampleStore.java](../spatial-data/src/main/java/dev/worldgennext/spatial/worldgen/UncachedSampleStore.java) | Implementation or explicit public contract |
| [spatial-data/src/test/java/dev/worldgennext/spatial/ByteBudgetTest.java](../spatial-data/src/test/java/dev/worldgennext/spatial/ByteBudgetTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/worldgennext/spatial/SharedTileCacheTest.java](../spatial-data/src/test/java/dev/worldgennext/spatial/SharedTileCacheTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/worldgennext/spatial/TileKeyTest.java](../spatial-data/src/test/java/dev/worldgennext/spatial/TileKeyTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/worldgennext/spatial/V02SpatialStoreTest.java](../spatial-data/src/test/java/dev/worldgennext/spatial/V02SpatialStoreTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/worldgennext/spatial/V02TiledConsumerTest.java](../spatial-data/src/test/java/dev/worldgennext/spatial/V02TiledConsumerTest.java) | Executable CPU test |
| [spatial-data/src/test/java/dev/worldgennext/spatial/V02TypedProducerTest.java](../spatial-data/src/test/java/dev/worldgennext/spatial/V02TypedProducerTest.java) | Executable CPU test |

## chunk-engine

Bounded coordinator, routing and commit models.

| File | Role |
| --- | --- |
| [chunk-engine/README.md](../chunk-engine/README.md) | Documentation |
| [chunk-engine/src/main/java/dev/worldgennext/engine/EpochTaskEngine.java](../chunk-engine/src/main/java/dev/worldgennext/engine/EpochTaskEngine.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/ExecutionRoute.java](../chunk-engine/src/main/java/dev/worldgennext/engine/ExecutionRoute.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/GenerationStage.java](../chunk-engine/src/main/java/dev/worldgennext/engine/GenerationStage.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/RoutingPolicy.java](../chunk-engine/src/main/java/dev/worldgennext/engine/RoutingPolicy.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/TaskKey.java](../chunk-engine/src/main/java/dev/worldgennext/engine/TaskKey.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/BackendResult.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/BackendResult.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/ChunkCommitter.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/ChunkCommitter.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/CommitCoordinator.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/CommitCoordinator.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/CommitReceipt.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/CommitReceipt.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/CommitToken.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/CommitToken.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/CoordinatorSnapshot.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/CoordinatorSnapshot.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/DrainController.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/DrainController.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/EndpointCompletion.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/EndpointCompletion.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/FairWorkQueue.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/FairWorkQueue.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/FixedBatchBuilder.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/FixedBatchBuilder.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/GenerationRequest.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/GenerationRequest.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/RequestSubscription.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/RequestSubscription.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/ResourceAdmission.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/ResourceAdmission.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/ResourceEstimate.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/ResourceEstimate.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/ResourceReservation.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/ResourceReservation.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/SpatialWorkProducer.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/SpatialWorkProducer.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/SpatialWorkService.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/SpatialWorkService.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/StageExecutor.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/StageExecutor.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/StaticRoutePolicy.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/StaticRoutePolicy.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorkCounters.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorkCounters.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorkKey.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorkKey.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorkRecord.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorkRecord.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorldEpoch.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorldEpoch.java) | Implementation or explicit public contract |
| [chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorldgenCoordinator.java](../chunk-engine/src/main/java/dev/worldgennext/engine/worldgen/WorldgenCoordinator.java) | Implementation or explicit public contract |
| [chunk-engine/src/test/java/dev/worldgennext/engine/CoordinatorSnapshotTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/CoordinatorSnapshotTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/EpochTaskEngineTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/EpochTaskEngineTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/RoutingPolicyTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/RoutingPolicyTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/CommitCoordinatorTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/CommitCoordinatorTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/CommitReceiptProvenanceTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/CommitReceiptProvenanceTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/ResourceEstimateTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/ResourceEstimateTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/SpatialWorkServiceTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/SpatialWorkServiceTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/WorkCountersTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/WorkCountersTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/WorkRecordTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/worldgen/WorkRecordTest.java) | Executable CPU test |
| [chunk-engine/src/test/java/dev/worldgennext/engine/WorldgenCoordinatorTest.java](../chunk-engine/src/test/java/dev/worldgennext/engine/WorldgenCoordinatorTest.java) | Executable CPU test |

## frontend-mc1211

Typed captured Minecraft lowering boundary.

| File | Role |
| --- | --- |
| [frontend-mc1211/README.md](../frontend-mc1211/README.md) | Documentation |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/DensityNodeLowerer.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/DensityNodeLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/LoweringDiagnostics.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/LoweringDiagnostics.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/LoweringResult.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/LoweringResult.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/MarkerLowerer.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/MarkerLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/MaterialLowerer.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/MaterialLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/Minecraft1211Frontend.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/Minecraft1211Frontend.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/MinecraftDensityLowerer.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/MinecraftDensityLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/MinecraftSnapshotLowerer.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/MinecraftSnapshotLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/NodeCapabilityRegistry.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/NodeCapabilityRegistry.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/NoiseParameterLowerer.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/NoiseParameterLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/NoiseRouterLowerer.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/NoiseRouterLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/SourceNodeSnapshot.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/SourceNodeSnapshot.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/SourceStackFingerprint.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/SourceStackFingerprint.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/SplineLowerer.java](../frontend-mc1211/src/main/java/dev/worldgennext/frontend/mc1211/SplineLowerer.java) | Implementation or explicit public contract |
| [frontend-mc1211/src/test/java/dev/worldgennext/frontend/mc1211/Minecraft1211FrontendTest.java](../frontend-mc1211/src/test/java/dev/worldgennext/frontend/mc1211/Minecraft1211FrontendTest.java) | Executable CPU test |

## runtime-vulkan

Persistent Vulkan lifecycle and opt-in native compute.

| File | Role |
| --- | --- |
| [runtime-vulkan/build.gradle](../runtime-vulkan/build.gradle) | Build configuration/tooling |
| [runtime-vulkan/README.md](../runtime-vulkan/README.md) | Documentation |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/DeviceCapabilities.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/DeviceCapabilities.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/Fp64Profile.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/Fp64Profile.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/GpuSmokeResult.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/GpuSmokeResult.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/Hashes.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/Hashes.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/NativeIntegerIeeeCompile.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/NativeIntegerIeeeCompile.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/NativeIntegerIeeeRunner.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/NativeIntegerIeeeRunner.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/NativeVulkanLifecycleRunner.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/NativeVulkanLifecycleRunner.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/NativeWorldgenSmoke.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/NativeWorldgenSmoke.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/BufferLayout.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/BufferLayout.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/CapabilityQualifier.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/CapabilityQualifier.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/CompletionPump.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/CompletionPump.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/DeviceArena.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/DeviceArena.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/DeviceFailureHandler.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/DeviceFailureHandler.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/DeviceGeneration.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/DeviceGeneration.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/DispatchDescriptor.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/DispatchDescriptor.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/GpuExecutionReceipt.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/GpuExecutionReceipt.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/MemoryVisibility.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/MemoryVisibility.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/NativeBudgetLedger.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/NativeBudgetLedger.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/PipelineCache.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/PipelineCache.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/PipelineCompiler.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/PipelineCompiler.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/QuarantineLedger.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/QuarantineLedger.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/QueueOwner.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/QueueOwner.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/ReadbackLease.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/ReadbackLease.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/RuntimeDiagnostics.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/RuntimeDiagnostics.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/ScratchLease.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/ScratchLease.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/SubmissionRing.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/SubmissionRing.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/UploadLease.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/UploadLease.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/VulkanContext.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/VulkanContext.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/VulkanGenerationService.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/VulkanGenerationService.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/VulkanWorldgenExecutor.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/production/VulkanWorldgenExecutor.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/ShadercCompiler.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/ShadercCompiler.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/SmokeConfig.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/SmokeConfig.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/SmokeWorkBudget.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/SmokeWorkBudget.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/VulkanSmokeRunner.java](../runtime-vulkan/src/main/java/dev/worldgennext/runtime/vulkan/VulkanSmokeRunner.java) | Implementation or explicit public contract |
| [runtime-vulkan/src/test/java/dev/worldgennext/runtime/vulkan/DeviceCapabilitiesTest.java](../runtime-vulkan/src/test/java/dev/worldgennext/runtime/vulkan/DeviceCapabilitiesTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/worldgennext/runtime/vulkan/SmokeWorkBudgetTest.java](../runtime-vulkan/src/test/java/dev/worldgennext/runtime/vulkan/SmokeWorkBudgetTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/worldgennext/runtime/vulkan/V02ProductionTest.java](../runtime-vulkan/src/test/java/dev/worldgennext/runtime/vulkan/V02ProductionTest.java) | Executable CPU test |
| [runtime-vulkan/src/test/java/dev/worldgennext/runtime/vulkan/VulkanWorldgenExecutorTest.java](../runtime-vulkan/src/test/java/dev/worldgennext/runtime/vulkan/VulkanWorldgenExecutorTest.java) | Executable CPU test |

## oracle-and-replay

Corpus comparator, counted replay and result artifacts.

| File | Role |
| --- | --- |
| [oracle-and-replay/build.gradle](../oracle-and-replay/build.gradle) | Build configuration/tooling |
| [oracle-and-replay/README.md](../oracle-and-replay/README.md) | Documentation |
| [oracle-and-replay/src/corpusApi/java/dev/worldgennext/oracle/schema/CorpusApiVersion.java](../oracle-and-replay/src/corpusApi/java/dev/worldgennext/oracle/schema/CorpusApiVersion.java) | Tooling, data or provenance |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/BaselineReport.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/BaselineReport.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/BaselineReportBuilder.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/BaselineReportBuilder.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/BaselineReportJson.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/BaselineReportJson.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/FullEndpoint.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/FullEndpoint.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/NoiseEndpoint.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/NoiseEndpoint.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/ReopenVerifier.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/ReopenVerifier.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/RunManifest.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/RunManifest.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/SavedChunkReceipt.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/SavedChunkReceipt.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/SavedEndpoint.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/SavedEndpoint.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/SavedWorldVerifier.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/SavedWorldVerifier.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/StageTimers.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/benchmark/StageTimers.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/FixtureResult.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/FixtureResult.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/CandidateRun.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/CandidateRun.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ChunkResultSnapshot.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ChunkResultSnapshot.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ChunkSnapshotComparator.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ChunkSnapshotComparator.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ComparisonCoverage.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ComparisonCoverage.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/CorpusValidator.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/CorpusValidator.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/DeterminismPreflight.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/DeterminismPreflight.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/FailureBundle.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/FailureBundle.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ProcessHarness.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ProcessHarness.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ReferenceRun.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/ReferenceRun.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/RunArtifactManifest.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/minecraft/RunArtifactManifest.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReplayCli.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReplayCli.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReplayFixture.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReplayFixture.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReplayReport.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReplayReport.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReplayRunner.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReplayRunner.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReportJson.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/ReportJson.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/CaptureIdentity.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/CaptureIdentity.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/ChunkSnapshot.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/ChunkSnapshot.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/CorpusManifest.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/CorpusManifest.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/SnapshotField.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/SnapshotField.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/SnapshotIo.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/schema/SnapshotIo.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/main/java/dev/worldgennext/oracle/SyntheticCorpus.java](../oracle-and-replay/src/main/java/dev/worldgennext/oracle/SyntheticCorpus.java) | Implementation or explicit public contract |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/BaselineReportTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/BaselineReportTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/EndpointContractTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/EndpointContractTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/minecraft/ProcessHarnessTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/minecraft/ProcessHarnessTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/ReplayCliTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/ReplayCliTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/ReplayRunnerTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/ReplayRunnerTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/ReportJsonTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/ReportJsonTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/SavedChunkReceiptTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/SavedChunkReceiptTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/SavedWorldVerifierTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/SavedWorldVerifierTest.java) | Executable CPU test |
| [oracle-and-replay/src/test/java/dev/worldgennext/oracle/V02OracleTest.java](../oracle-and-replay/src/test/java/dev/worldgennext/oracle/V02OracleTest.java) | Executable CPU test |

## neoforge-1211

Minecraft snapshot/runtime composition and bootstrap tests.

| File | Role |
| --- | --- |
| [neoforge-1211/build.gradle](../neoforge-1211/build.gradle) | Build configuration/tooling |
| [neoforge-1211/README.md](../neoforge-1211/README.md) | Documentation |
| [neoforge-1211/src/gameTest/java/dev/worldgennext/neoforge/gametest/DiagnosticGameTests.java](../neoforge-1211/src/gameTest/java/dev/worldgennext/neoforge/gametest/DiagnosticGameTests.java) | Opt-in game bootstrap test/resource |
| [neoforge-1211/src/gameTest/resources/data/worldgennext_test/structure/empty.nbt](../neoforge-1211/src/gameTest/resources/data/worldgennext_test/structure/empty.nbt) | Opt-in game bootstrap test/resource |
| [neoforge-1211/src/gameTest/resources/META-INF/neoforge.mods.toml](../neoforge-1211/src/gameTest/resources/META-INF/neoforge.mods.toml) | Opt-in game bootstrap test/resource |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/command/WorldgenNextCommands.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/command/WorldgenNextCommands.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/compat/CompatibilityRegistry.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/compat/CompatibilityRegistry.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/config/WorldgenNextConfig.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/config/WorldgenNextConfig.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/config/WorldgenNextConfigLoader.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/config/WorldgenNextConfigLoader.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/DiagnosticSelfTest.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/DiagnosticSelfTest.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/mixin/ChunkStatusTasksMixin.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/mixin/ChunkStatusTasksMixin.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/ChunkMutationJournal.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/ChunkMutationJournal.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/CoordinatedNoiseAttempt.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/CoordinatedNoiseAttempt.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/GenerationInterceptor.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/GenerationInterceptor.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/HeightmapPacking.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/HeightmapPacking.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/HookTelemetry.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/HookTelemetry.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/LiveNoiseBridge.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/LiveNoiseBridge.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftChunkApplier.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftChunkApplier.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftChunkCommitter.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftChunkCommitter.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftCpuCandidate.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftCpuCandidate.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftCpuNoiseProvider.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftCpuNoiseProvider.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftGpuCandidate.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftGpuCandidate.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftGpuNoiseProvider.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftGpuNoiseProvider.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftLogicalSnapshotWriter.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftLogicalSnapshotWriter.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftNoiseChunkLifecycle.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftNoiseChunkLifecycle.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftNoiseOwnership.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftNoiseOwnership.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftNoisePostProcessor.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftNoisePostProcessor.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftOwnershipToken.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftOwnershipToken.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftResultValidator.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftResultValidator.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftStageAdapter.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/MinecraftStageAdapter.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/NativeDependencyBootstrap.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/NativeDependencyBootstrap.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/NoiseChunkLifecycleAdapter.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/NoiseChunkLifecycleAdapter.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualificationEvidenceBundleFile.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualificationEvidenceBundleFile.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualificationEvidenceFile.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualificationEvidenceFile.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualifiedHookAdmission.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualifiedHookAdmission.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualifiedHookEvidence.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualifiedHookEvidence.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualifiedHookEvidenceBundle.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/QualifiedHookEvidenceBundle.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/ReloadCoordinator.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/ReloadCoordinator.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/RuntimeComposition.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/RuntimeComposition.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/SaveBarrier.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/SaveBarrier.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/ServerLifecycle.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/runtime/ServerLifecycle.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/DensityNodeReader.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/DensityNodeReader.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/MinecraftDynamicInputReader.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/MinecraftDynamicInputReader.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/MinecraftSnapshotReader.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/MinecraftSnapshotReader.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/RegistrySnapshotReader.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/RegistrySnapshotReader.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/StructureBlendReader.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/snapshot/StructureBlendReader.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/java/dev/worldgennext/neoforge/WorldgenNextMod.java](../neoforge-1211/src/main/java/dev/worldgennext/neoforge/WorldgenNextMod.java) | Implementation or explicit public contract |
| [neoforge-1211/src/main/resources/META-INF/neoforge.mods.toml](../neoforge-1211/src/main/resources/META-INF/neoforge.mods.toml) | Packaged metadata/resource |
| [neoforge-1211/src/main/resources/worldgennext.mixins.json](../neoforge-1211/src/main/resources/worldgennext.mixins.json) | Packaged metadata/resource |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/command/WorldgenNextCommandsTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/command/WorldgenNextCommandsTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/config/WorldgenNextConfigLoaderTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/config/WorldgenNextConfigLoaderTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/DiagnosticSelfTestTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/DiagnosticSelfTestTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/ChunkMutationJournalTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/ChunkMutationJournalTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/LiveNoiseBridgeTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/LiveNoiseBridgeTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/MinecraftGpuCandidateTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/MinecraftGpuCandidateTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/QualificationEvidenceFileTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/QualificationEvidenceFileTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/QualifiedHookAdmissionTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/QualifiedHookAdmissionTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/QualifiedHookEvidenceBundleTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/QualifiedHookEvidenceBundleTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/QualifiedHookEvidenceTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/QualifiedHookEvidenceTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/RuntimeContractTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/runtime/RuntimeContractTest.java) | Executable CPU test |
| [neoforge-1211/src/test/java/dev/worldgennext/neoforge/snapshot/StructureBlendReaderTest.java](../neoforge-1211/src/test/java/dev/worldgennext/neoforge/snapshot/StructureBlendReaderTest.java) | Executable CPU test |

## fabric-1211

Deferred loader frame.

| File | Role |
| --- | --- |
| [fabric-1211/build.gradle](../fabric-1211/build.gradle) | Build configuration/tooling |
| [fabric-1211/README.md](../fabric-1211/README.md) | Documentation |

## Project tooling, documentation and evidence

| File | Role |
| --- | --- |
| [.gitattributes](../.gitattributes) | Tooling, data or provenance |
| [.github/workflows/ci.yml](../.github/workflows/ci.yml) | Tooling, data or provenance |
| [.gitignore](../.gitignore) | Tooling, data or provenance |
| [AGENTS.md](../AGENTS.md) | Documentation |
| [build.gradle](../build.gradle) | Build configuration/tooling |
| [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) | Documentation |
| [docs/ATTRIBUTION.md](../docs/ATTRIBUTION.md) | Documentation |
| [docs/COMPATIBILITY.md](../docs/COMPATIBILITY.md) | Documentation |
| [docs/CONFIGURATION.md](../docs/CONFIGURATION.md) | Documentation |
| [docs/CONTRACTS.md](../docs/CONTRACTS.md) | Documentation |
| [docs/design/EVIDENCE.md](../docs/design/EVIDENCE.md) | Documentation |
| [docs/design/performance-model.json](../docs/design/performance-model.json) | Tooling, data or provenance |
| [docs/design/PROPOSAL.md](../docs/design/PROPOSAL.md) | Documentation |
| [docs/evidence/v0.1-cpu-replay.json](../docs/evidence/v0.1-cpu-replay.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-cpu-tests.json](../docs/evidence/v0.1-cpu-tests.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-game-bootstrap.txt](../docs/evidence/v0.1-game-bootstrap.txt) | Tooling, data or provenance |
| [docs/evidence/v0.1-gpu-normal-range.json](../docs/evidence/v0.1-gpu-normal-range.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-gpu-strict.json](../docs/evidence/v0.1-gpu-strict.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-validation.json](../docs/evidence/v0.1-validation.json) | Tooling, data or provenance |
| [docs/evidence/v0.1-validation.md](../docs/evidence/v0.1-validation.md) | Documentation |
| [docs/evidence/v0.2-candidate-saved.md](../docs/evidence/v0.2-candidate-saved.md) | Documentation |
| [docs/evidence/v0.2-cpu-candidate.md](../docs/evidence/v0.2-cpu-candidate.md) | Documentation |
| [docs/evidence/v0.2-cpu-live-prototype.md](../docs/evidence/v0.2-cpu-live-prototype.md) | Documentation |
| [docs/evidence/v0.2-gpu-candidate-end.md](../docs/evidence/v0.2-gpu-candidate-end.md) | Documentation |
| [docs/evidence/v0.2-gpu-candidate-nether.md](../docs/evidence/v0.2-gpu-candidate-nether.md) | Documentation |
| [docs/evidence/v0.2-gpu-candidate-overworld-staging.md](../docs/evidence/v0.2-gpu-candidate-overworld-staging.md) | Documentation |
| [docs/evidence/v0.2-gpu-executor.md](../docs/evidence/v0.2-gpu-executor.md) | Documentation |
| [docs/evidence/v0.2-integer-ieee.md](../docs/evidence/v0.2-integer-ieee.md) | Documentation |
| [docs/evidence/v0.2-mapped-capture.md](../docs/evidence/v0.2-mapped-capture.md) | Documentation |
| [docs/evidence/v0.2-minecraft-application.md](../docs/evidence/v0.2-minecraft-application.md) | Documentation |
| [docs/evidence/v0.2-original-full.md](../docs/evidence/v0.2-original-full.md) | Documentation |
| [docs/evidence/v0.2-original-oracle.md](../docs/evidence/v0.2-original-oracle.md) | Documentation |
| [docs/evidence/v0.2-original-saved.md](../docs/evidence/v0.2-original-saved.md) | Documentation |
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
| [net/minecraft/world/level/chunk/ChunkAccess.java](../net/minecraft/world/level/chunk/ChunkAccess.java) | Tooling, data or provenance |
| [net/minecraft/world/level/chunk/ProtoChunk.java](../net/minecraft/world/level/chunk/ProtoChunk.java) | Tooling, data or provenance |
| [net/minecraft/world/level/chunk/UpgradeData.java](../net/minecraft/world/level/chunk/UpgradeData.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/blending/Blender.java](../net/minecraft/world/level/levelgen/blending/Blender.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/blending/BlendingData.java](../net/minecraft/world/level/levelgen/blending/BlendingData.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/DensityFunctions.java](../net/minecraft/world/level/levelgen/DensityFunctions.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/Heightmap.java](../net/minecraft/world/level/levelgen/Heightmap.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/NoiseGeneratorSettings.java](../net/minecraft/world/level/levelgen/NoiseGeneratorSettings.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/NoiseRouter.java](../net/minecraft/world/level/levelgen/NoiseRouter.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/synth/BlendedNoise.java](../net/minecraft/world/level/levelgen/synth/BlendedNoise.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/synth/ImprovedNoise.java](../net/minecraft/world/level/levelgen/synth/ImprovedNoise.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/synth/NormalNoise.java](../net/minecraft/world/level/levelgen/synth/NormalNoise.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/synth/PerlinNoise.java](../net/minecraft/world/level/levelgen/synth/PerlinNoise.java) | Tooling, data or provenance |
| [net/minecraft/world/level/levelgen/synth/SimplexNoise.java](../net/minecraft/world/level/levelgen/synth/SimplexNoise.java) | Tooling, data or provenance |
| [net/minecraft/world/level/LevelHeightAccessor.java](../net/minecraft/world/level/LevelHeightAccessor.java) | Tooling, data or provenance |
| [NOTICE](../NOTICE) | Tooling, data or provenance |
| [oracle-1211/build.gradle](../oracle-1211/build.gradle) | Build configuration/tooling |
| [oracle-1211/README.md](../oracle-1211/README.md) | Documentation |
| [oracle-1211/src/main/java/dev/worldgennext/oracle1211/OracleCaptureMod.java](../oracle-1211/src/main/java/dev/worldgennext/oracle1211/OracleCaptureMod.java) | Implementation or explicit public contract |
| [oracle-1211/src/main/resources/META-INF/neoforge.mods.toml](../oracle-1211/src/main/resources/META-INF/neoforge.mods.toml) | Packaged metadata/resource |
| [oracle-1211/src/main/resources/pack.mcmeta](../oracle-1211/src/main/resources/pack.mcmeta) | Packaged metadata/resource |
| [Plan.md](../Plan.md) | Documentation |
| [README.md](../README.md) | Documentation |
| [scripts/capture-baseline.py](../scripts/capture-baseline.py) | Tooling, data or provenance |
| [scripts/capture-candidate-saved.ps1](../scripts/capture-candidate-saved.ps1) | Tooling, data or provenance |
| [scripts/capture-original-corpus.ps1](../scripts/capture-original-corpus.ps1) | Tooling, data or provenance |
| [scripts/capture-original-full.ps1](../scripts/capture-original-full.ps1) | Tooling, data or provenance |
| [scripts/capture-original-saved.ps1](../scripts/capture-original-saved.ps1) | Tooling, data or provenance |
| [scripts/check.ps1](../scripts/check.ps1) | Tooling, data or provenance |
| [scripts/minecraft-replay.py](../scripts/minecraft-replay.py) | Tooling, data or provenance |
| [scripts/qualify-v02.ps1](../scripts/qualify-v02.ps1) | Tooling, data or provenance |
| [scripts/replay-minecraft-logical.ps1](../scripts/replay-minecraft-logical.ps1) | Tooling, data or provenance |
| [scripts/replay-minecraft-noise-cpu.ps1](../scripts/replay-minecraft-noise-cpu.ps1) | Tooling, data or provenance |
| [scripts/replay-minecraft-noise-gpu.ps1](../scripts/replay-minecraft-noise-gpu.ps1) | Tooling, data or provenance |
| [scripts/run-prototype-cpu-live.ps1](../scripts/run-prototype-cpu-live.ps1) | Tooling, data or provenance |
| [scripts/summarize-tests.py](../scripts/summarize-tests.py) | Tooling, data or provenance |
| [scripts/test-installed-mod.ps1](../scripts/test-installed-mod.ps1) | Tooling, data or provenance |
| [scripts/update-file-map.py](../scripts/update-file-map.py) | Tooling, data or provenance |
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
| [third-party/gradle-LICENSE.txt](../third-party/gradle-LICENSE.txt) | Tooling, data or provenance |
