// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

import dev.worldgennext.semantic.*;
import dev.worldgennext.semantic.program.EvaluationDomain;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.NoiseRouterSnapshot;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class Minecraft1211FrontendTest {
    private final Minecraft1211Frontend frontend = new Minecraft1211Frontend();
    private final WorldgenIdentity identity = new WorldgenIdentity(42, "minecraft:overworld", "unknown", 1);

    @Test void realOrSyntheticObjectsCannotClaimImplementedMinecraftLowering() {
        LoweringResult.Unsupported result = assertInstanceOf(LoweringResult.Unsupported.class, frontend.lower(identity, new DensityExpression.Constant(1)));
        assertEquals(LoweringResult.Reason.MINECRAFT_FRONTEND_NOT_IMPLEMENTED, result.reason());
        assertEquals(identity, result.identity());
        assertTrue(result.explanation().contains("ore"));
        assertFalse(frontend.canInterceptGeneration());
    }

    @Test void missingSourceIsExplicitAndNoSourceMethodsAreInvoked() {
        LoweringResult.Unsupported missing = assertInstanceOf(LoweringResult.Unsupported.class, frontend.lower(identity, null));
        assertEquals(LoweringResult.Reason.MISSING_SOURCE, missing.reason());
        Object hostileSource = new Object() { @Override public String toString() { throw new AssertionError("Must not execute untrusted graph"); } };
        assertInstanceOf(LoweringResult.Unsupported.class, frontend.lower(identity, hostileSource));
    }

    @Test void completeCapturedSnapshotUsesTypedFrontendBoundary() {
        var roots = new java.util.LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            roots.put(name, new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.BLOCK));
        }
        var snapshot = WorldgenSnapshot.builder(42L, "minecraft:overworld")
                .router(new NoiseRouterSnapshot(roots)).build();
        LoweringResult.TypedLowered lowered = assertInstanceOf(LoweringResult.TypedLowered.class,
                frontend.lower(identity, snapshot));
        assertSame(snapshot, lowered.snapshot());
        assertTrue(lowered.program().hasAllRouterRoots());
        assertEquals(WorldgenProgram.ROUTER_ROOTS.size(), lowered.program().roots().size());
    }

    @Test void resultsValidateIdentityAndDoNotFabricateSuccessOnBadInput() {
        assertThrows(NullPointerException.class, () -> frontend.lower(null, new Object()));
        assertThrows(NullPointerException.class, () -> new LoweringResult.Lowered(identity, null));
        assertThrows(IllegalArgumentException.class, () -> new LoweringResult.Unsupported(identity, LoweringResult.Reason.MISSING_SOURCE, "null", " "));
    }

    @Test void typedBoundaryRejectsMismatchedRequestIdentityAndProgramRoots() {
        var roots = new java.util.LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            roots.put(name, new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.BLOCK));
        }
        var snapshot = WorldgenSnapshot.builder(42L, "minecraft:overworld")
                .router(new NoiseRouterSnapshot(roots)).build();
        var program = WorldgenProgram.builder().roots(roots).build();
        LoweringResult.Unsupported wrongSeed = assertInstanceOf(LoweringResult.Unsupported.class,
                frontend.lowerTyped(new WorldgenIdentity(43L, "minecraft:overworld", "unknown", 1), snapshot, program));
        assertTrue(wrongSeed.explanation().contains("seed"));

        var partial = WorldgenProgram.builder().root("finalDensity",
                new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.BLOCK)).build();
        LoweringResult.Unsupported wrongRoots = assertInstanceOf(LoweringResult.Unsupported.class,
                frontend.lowerTyped(identity, snapshot, partial));
        assertTrue(wrongRoots.explanation().contains("exactly"));
    }

    @Test void typedLowererIsCaseInsensitiveAndPreservesOperatorTypes() {
        var source = new SourceNodeSnapshot("SuBtRaCt", "root/subtract", ValueType.INT32, EvaluationDomain.BLOCK, Map.of(), List.of(
                new SourceNodeSnapshot("INPUT", "root/x", ValueType.INT32, EvaluationDomain.BLOCK, Map.of("name", "x"), List.of()),
                SourceNodeSnapshot.constant("root/one", ValueType.INT32, "1")));
        var lowered = new DensityNodeLowerer().lower(source);
        assertTrue(lowered.supported(), lowered.diagnostics().summary());
        assertEquals("subtract", lowered.node().operation());
        assertEquals(ValueType.INT32, lowered.node().type());
    }

    @Test void typedLowererRejectsArityAndUnknownCapturedNodesWithActionableDiagnostics() {
        var badArity = new SourceNodeSnapshot("add", "root/add", ValueType.FP64, EvaluationDomain.BLOCK, Map.of(), List.of(SourceNodeSnapshot.constant("root/a", ValueType.FP64, "1")));
        var result = new DensityNodeLowerer().lower(badArity);
        assertFalse(result.supported());
        assertTrue(result.diagnostics().summary().contains("requires 2 children"));

        var unknown = new SourceNodeSnapshot("spline", "root/spline", ValueType.FP64, EvaluationDomain.BLOCK, Map.of(), List.of());
        var rejected = new DensityNodeLowerer().lower(unknown);
        assertFalse(rejected.supported());
        assertTrue(rejected.diagnostics().summary().contains("control points"));
    }

    @Test void warningDoesNotTurnAValidLoweringIntoAnError() {
        var diagnostics = new LoweringDiagnostics();
        diagnostics.warning("root", "OPTIONAL_METADATA", "metadata was not present");
        assertFalse(diagnostics.hasErrors());
        assertEquals(1, diagnostics.warningCount());
    }

    @Test void snapshotLowererCopiesRouterDiagnosticsIntoMutableDiagnosticOwner() {
        var result = new MinecraftSnapshotLowerer().lower(0L, "minecraft:overworld", "test", Map.of(),
                RegistrySnapshot.minimal(), GeneratorSettingsSnapshot.overworld(), DynamicInputIdentity.empty(), "test-stack");
        assertFalse(result.supported());
        assertTrue(result.diagnostics().hasErrors());
        assertEquals(15, result.diagnostics().entries().size());
    }

    @Test void capturedNoiseLowersToImmutableParametersInsteadOfAnOpaqueInput() {
        var source = new SourceNodeSnapshot("noise", "root/noise", ValueType.FP64, EvaluationDomain.WORLD,
                Map.of("name", "test/noise", "xzScale", "0.5", "yScale", "2.0",
                        "noise", "firstOctave=-1;amplitudes=0x1.0p0,0x1.0p-1"), List.of());
        var result = new DensityNodeLowerer().lower(source);
        assertTrue(result.supported(), result.diagnostics().summary());
        var noise = assertInstanceOf(dev.worldgennext.semantic.program.ProgramNode.Noise.class, result.node());
        assertEquals("test/noise", noise.name());
        assertEquals(new NoiseParameters("test/noise", -1, List.of(1.0, 0.5), 0L), noise.parameters());
        assertEquals(0.5, noise.xzScale());
        assertEquals(2.0, noise.yScale());
    }

    @Test void shiftedNoiseKeepsAllThreeCoordinateEffects() {
        var shift = SourceNodeSnapshot.constant("root/shift", ValueType.FP64, "1.0");
        var source = new SourceNodeSnapshot("shifted_noise", "root/shifted", ValueType.FP64, EvaluationDomain.WORLD,
                Map.of("name", "test/noise", "xzScale", "1.0", "yScale", "1.0",
                        "noise", "firstOctave=0;amplitudes=0x1.0p0"), List.of(shift, shift, shift));
        var result = new DensityNodeLowerer().lower(source);
        assertTrue(result.supported(), result.diagnostics().summary());
        var shifted = assertInstanceOf(dev.worldgennext.semantic.program.ProgramNode.ShiftedNoise.class, result.node());
        assertEquals(3, shifted.children().size());
        assertEquals("shifted_noise:test/noise", shifted.operation());
    }

    @Test void mappedNegativeTransformsAreCapturedAsTypedUnaryOperations() {
        var source = new SourceNodeSnapshot("squeeze", "root/squeeze", ValueType.FP64, EvaluationDomain.WORLD, Map.of(),
                List.of(SourceNodeSnapshot.constant("root/value", ValueType.FP64, "2.0")));
        var result = new DensityNodeLowerer().lower(source);
        assertTrue(result.supported(), result.diagnostics().summary());
        assertEquals("squeeze", result.node().operation());
    }

    @Test void yGradientPreservesMinecraftReversedAndZeroWidthBounds() {
        var lowerer = new DensityNodeLowerer();
        var reversed = new SourceNodeSnapshot("y_gradient", "root/reversed", ValueType.FP64,
                EvaluationDomain.BLOCK,
                Map.of("fromY", "8", "toY", "-8", "fromValue", "1.0", "toValue", "2.0"), List.of());
        var zeroWidth = new SourceNodeSnapshot("y_gradient", "root/zero-width", ValueType.FP64,
                EvaluationDomain.BLOCK,
                Map.of("fromY", "8", "toY", "8", "fromValue", "1.0", "toValue", "2.0"), List.of());

        var reversedResult = lowerer.lower(reversed);
        var zeroWidthResult = lowerer.lower(zeroWidth);
        assertTrue(reversedResult.supported(), reversedResult.diagnostics().summary());
        assertTrue(zeroWidthResult.supported(), zeroWidthResult.diagnostics().summary());
        assertEquals("add", reversedResult.node().operation());
        assertEquals("add", zeroWidthResult.node().operation());
    }

    @Test void ap2LoweringPreservesOperationAndRightArgumentBounds() {
        var source = new SourceNodeSnapshot("AP2", "root/ap2", ValueType.FP64, EvaluationDomain.BLOCK,
                Map.of("operation", "multiply",
                        "rightMinValue", Double.toHexString(-3.0),
                        "rightMaxValue", Double.toHexString(7.0)),
                List.of(SourceNodeSnapshot.constant("root/left", ValueType.FP64, "0.0"),
                        SourceNodeSnapshot.constant("root/right", ValueType.FP64, "2.0")));
        var result = new DensityNodeLowerer().lower(source);
        assertTrue(result.supported(), result.diagnostics().summary());
        var ap2 = assertInstanceOf(ProgramNode.Ap2.class, result.node());
        assertEquals("multiply", ap2.operation());
        assertEquals(-3.0, ap2.rightMinValue());
        assertEquals(7.0, ap2.rightMaxValue());
    }

    @Test void blendMarkersLowerToDynamicTypedNodes() {
        var alpha = new SourceNodeSnapshot("blend_alpha", "root/alpha", ValueType.FP64,
                EvaluationDomain.BLOCK, Map.of(), List.of());
        var offset = new SourceNodeSnapshot("blend_offset", "root/offset", ValueType.FP64,
                EvaluationDomain.BLOCK, Map.of(), List.of());
        var lowerer = new DensityNodeLowerer();
        assertInstanceOf(ProgramNode.BlendAlpha.class, lowerer.lower(alpha).node());
        assertInstanceOf(ProgramNode.BlendOffset.class, lowerer.lower(offset).node());
    }

    @Test void beardifierMarkerLowersToARequestLocalStructureNode() {
        var source = new SourceNodeSnapshot("beardifier", "root/beardifier", ValueType.FP64,
                EvaluationDomain.BLOCK, Map.of(), List.of());
        var result = new DensityNodeLowerer().lower(source);
        assertTrue(result.supported(), result.diagnostics().summary());
        assertInstanceOf(ProgramNode.Beardifier.class, result.node());
    }
}
