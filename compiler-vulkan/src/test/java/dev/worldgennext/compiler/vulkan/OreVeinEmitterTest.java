// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan;

import dev.worldgennext.compiler.vulkan.worldgen.OreVeinEmitter;
import dev.worldgennext.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.worldgennext.semantic.material.OreVeinProgram;
import dev.worldgennext.semantic.program.EvaluationDomain;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OreVeinEmitterTest {
    @Test void positionalXorCanRequireNormalizationEvenWithNonzeroFactoryState() {
        // Minecraft Mth.getSeed: x multiplication wraps at int width before
        // promotion; final shift is signed. Use a valid copper-height point.
        int x = 480, y = 25, z = 480;
        long position = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        position = (position * position * 42317861L + position * 11L) >> 16;
        assertNotEquals(0L, position);
        var factory = PositionalRandomFactorySnapshot.xoroshiro(position, 0L);
        assertEquals(position, factory.seedLo());
        assertEquals(0L, position ^ factory.seedLo());
        assertEquals(0L, factory.seedHi());

        String source = emit(factory);
        String guard = "if (all(equal(randomStateLo, uvec2(0u)))) {";
        // Xoroshiro128PlusPlus's constructor fallback, stored low word first.
        String lo = "randomStateLo = uvec2(0x7f4a7c15u, 0x9e3779b9u);";
        String hi = "randomStateHi = uvec2(0xf3bcc909u, 0x6a09e667u);";
        assertTrue(source.contains(guard + "\n        " + lo + "\n        " + hi + "\n    }"));
        int init = source.indexOf("uvec2 randomStateLo =");
        int normalize = source.indexOf(guard);
        int firstDraw = source.indexOf("uint firstRandom =");
        assertTrue(init < normalize && normalize < firstDraw);
        assertTrue(source.contains("wg_fp32_less(0x3f333333u, firstRandom)"));
    }

    @Test void normalizationIsUnnecessaryWithNonzeroHighWordOrLegacyRandom() {
        for (var factory : List.of(PositionalRandomFactorySnapshot.xoroshiro(123L, 456L),
                PositionalRandomFactorySnapshot.legacy(123L))) {
            String source = emit(factory);
            assertFalse(source.contains("all(equal(randomStateLo"));
            assertFalse(source.contains("0x9e3779b9u"));
        }
    }

    private static String emit(PositionalRandomFactorySnapshot factory) {
        var program = WorldgenProgram.builder()
                .root("veinToggle", new ProgramNode.Constant(ValueType.FP64, 0.5d, EvaluationDomain.WORLD))
                .root("veinRidged", new ProgramNode.Constant(ValueType.FP64, -1.0d, EvaluationDomain.WORLD))
                .root("veinGap", new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.WORLD))
                .build();
        var material = new WorldgenShaderCompiler.MaterialOptions(OreVeinProgram.vanilla(99L),
                factory, List.of(10, 11, 12, 13, 14, 15));
        var ids = new AtomicInteger();
        return new OreVeinEmitter().emit(program, material,
                (router, root) -> root + "(point)", ids::getAndIncrement).source();
    }
}
