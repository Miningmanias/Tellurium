// SPDX-License-Identifier: MIT
package dev.tellurium.semantic;

import dev.tellurium.semantic.execution.ProgramAbi;
import dev.tellurium.semantic.identity.DynamicInputIdentity;
import dev.tellurium.semantic.program.*;
import dev.tellurium.semantic.snapshot.*;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class V02ContractsTest {
    @Test void typedProgramIsImmutableAndFingerprintIncludesProfile() {
        var root = new ProgramNode.Binary("add", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK),
                new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD));
        var javaProgram = WorldgenProgram.builder().root("finalDensity", root).build();
        var gpuProgram = WorldgenProgram.builder().numericProfile(NumericProfile.GPU_IEEE_BITS).root("finalDensity", root).build();
        assertNotEquals(javaProgram.fingerprint(), gpuProgram.fingerprint());
        assertThrows(UnsupportedOperationException.class, () -> javaProgram.roots().put("x", root));
    }

    @Test void typedConstantsStoreTheCarrierRequiredByTheirValueType() {
        var floatConstant = new ProgramNode.Constant(ValueType.FP32, Math.PI, EvaluationDomain.WORLD);
        var intConstant = new ProgramNode.Constant(ValueType.INT32, 4_294_967_297L, EvaluationDomain.WORLD);
        assertInstanceOf(Float.class, floatConstant.value());
        assertEquals(Float.floatToRawIntBits((float) Math.PI),
                Float.floatToRawIntBits((Float) floatConstant.value()));
        assertInstanceOf(Integer.class, intConstant.value());
        assertEquals(1, intConstant.value());
    }

    @Test void snapshotIdentitySeparatesReloadAndRegistry() {
        var registry = RegistrySnapshot.minimal();
        var first = WorldgenSnapshot.builder(123, "minecraft:overworld").registry(registry).dynamicInputs(DynamicInputIdentity.empty()).sourceStackFingerprint("stack-a").build();
        var second = WorldgenSnapshot.builder(123, "minecraft:overworld").registry(registry).dynamicInputs(new DynamicInputIdentity("pack-b", "mods", "structures", "blend", 1)).sourceStackFingerprint("stack-a").build();
        assertNotEquals(first.fingerprint(), second.fingerprint());
        assertEquals(1, registry.id("minecraft:stone"));
    }

    @Test void snapshotAndProgramIdentitiesIgnoreNonSemanticMapInsertionOrder() {
        var effectsA = new LinkedHashMap<String, String>();
        effectsA.put("zeta", "2");
        effectsA.put("alpha", "1");
        var effectsB = new LinkedHashMap<String, String>();
        effectsB.put("alpha", "1");
        effectsB.put("zeta", "2");
        var markerA = new ProgramNode.Marker("marker", "NONE", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.BLOCK), effectsA);
        var markerB = new ProgramNode.Marker("marker", "NONE", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.BLOCK), effectsB);

        var rootsA = new LinkedHashMap<String, ProgramNode>();
        rootsA.put("zeta", markerA);
        rootsA.put("alpha", markerA);
        var rootsB = new LinkedHashMap<String, ProgramNode>();
        rootsB.put("alpha", markerB);
        rootsB.put("zeta", markerB);
        var first = WorldgenSnapshot.builder(123, "minecraft:overworld")
                .router(new NoiseRouterSnapshot(rootsA)).build();
        var second = WorldgenSnapshot.builder(123, "minecraft:overworld")
                .router(new NoiseRouterSnapshot(rootsB)).build();
        assertEquals(first.fingerprint(), second.fingerprint());
        var programA = WorldgenProgram.builder().root("zeta", markerA).root("alpha", markerA).build();
        var programB = WorldgenProgram.builder().root("alpha", markerB).root("zeta", markerB).build();
        assertEquals(programA.fingerprint(), programB.fingerprint());
    }

    @Test void markerContextIsRequestOwned() {
        var context = new MarkerContext(2);
        context.put("density", 3.0);
        assertEquals(3.0, context.get("density"));
        assertThrows(IllegalArgumentException.class, () -> new ProgramAbi(1, 0, 1, java.nio.ByteOrder.BIG_ENDIAN, 4));
    }

    @Test void interpolationTokensAreDistinctAndNestedScopesRestoreTheirParent() {
        var context = new MarkerContext(0);
        assertFalse(context.interpolating());
        context.beginInterpolation();
        long outer = context.interpolationToken();
        context.beginInterpolation();
        long inner = context.interpolationToken();
        assertNotEquals(outer, inner);
        context.endInterpolation();
        assertEquals(outer, context.interpolationToken());
        context.endInterpolation();
        assertFalse(context.interpolating());
        assertThrows(IllegalStateException.class, context::interpolationToken);
        assertThrows(IllegalStateException.class, context::endInterpolation);
    }

    @Test void materialOrderKeepsAquiferFluidBeforeOreAndDefault() {
        var program = new dev.tellurium.semantic.material.MaterialProgram(0.0);
        var fluid = program.decide(new dev.tellurium.semantic.material.MaterialProgram.Inputs(
                -1.0, true, "minecraft:water", false, "minecraft:iron_ore", "minecraft:stone"));
        var defaultBlock = program.decide(new dev.tellurium.semantic.material.MaterialProgram.Inputs(
                -1.0, false, "minecraft:water", false, "minecraft:iron_ore", "minecraft:stone"));
        var ore = program.decide(new dev.tellurium.semantic.material.MaterialProgram.Inputs(
                -1.0, false, "minecraft:water", true, "minecraft:iron_ore", "minecraft:stone"));
        var dryAquifer = program.decide(new dev.tellurium.semantic.material.MaterialProgram.Inputs(
                -1.0, true, "minecraft:air", true, "minecraft:iron_ore", "minecraft:stone"));
        assertEquals(dev.tellurium.semantic.material.MaterialProgram.Source.AQUIFER, fluid.source());
        assertEquals("minecraft:water", fluid.state());
        assertEquals(dev.tellurium.semantic.material.MaterialProgram.Source.DEFAULT, defaultBlock.source());
        assertEquals("minecraft:stone", defaultBlock.state());
        assertEquals(dev.tellurium.semantic.material.MaterialProgram.Source.ORE, ore.source());
        assertEquals(dev.tellurium.semantic.material.MaterialProgram.Source.AQUIFER, dryAquifer.source());
        assertEquals("minecraft:air", dryAquifer.state());
    }
}
