// SPDX-License-Identifier: MIT
package dev.tellurium.semantic;

import dev.tellurium.semantic.program.*;
import dev.tellurium.semantic.snapshot.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldgenProgramFingerprintTest {
    private static final ValueType TYPE = ValueType.FP64;
    private static final EvaluationDomain DOMAIN = EvaluationDomain.BLOCK;

    @Test void sharedDagMatchesExpandedLegacySerialization() {
        ProgramNode shared = capturedNodes().get(0);
        for (int i = 0; i < 7; i++) shared = new ProgramNode.Binary("add", TYPE, DOMAIN, shared, shared);
        assertEquals(legacyNodeFingerprint(shared), WorldgenProgram.nodeFingerprint(shared));
        var program = WorldgenProgram.builder().root("zeta", shared).root("alpha", shared).build();
        assertEquals(legacyProgramFingerprint(program), program.fingerprint());

        var a = new ProgramNode.Input("equal", TYPE, DOMAIN);
        var b = new ProgramNode.Input("equal", TYPE, DOMAIN);
        assertNotSame(a, b);
        assertEquals(a, b);
        var distinct = new ProgramNode.Binary("subtract", TYPE, DOMAIN, a, b);
        assertEquals(legacyNodeFingerprint(distinct), WorldgenProgram.nodeFingerprint(distinct));
    }

    @Test void capturedNoisePermutationsAndNestedSplinesMatchLegacy() {
        var nodes = capturedNodes();
        for (ProgramNode node : nodes) {
            assertEquals(legacyNodeFingerprint(node), WorldgenProgram.nodeFingerprint(node), node.operation());
            var program = WorldgenProgram.builder().root("finalDensity", node).build();
            assertEquals(legacyProgramFingerprint(program), program.fingerprint(), node.operation());
        }
    }

    @Test void rootSortingProfilesAndControlRegionEncodingMatchLegacy() {
        var nodes = capturedNodes();
        var roots = new LinkedHashMap<String, ProgramNode>();
        roots.put("zeta", nodes.get(0));
        roots.put("alpha", nodes.get(6));
        roots.put("雪\ud83c\udf0d", nodes.get(0));
        var reversed = new LinkedHashMap<String, ProgramNode>();
        var entries = new ArrayList<>(roots.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) reversed.put(entries.get(i).getKey(), entries.get(i).getValue());
        var regions = List.of(
                new ControlRegion("lazy-雪", DOMAIN, List.of(nodes.get(0), nodes.get(6)), true,
                        Map.of("zeta", "{a}|b", "alpha", "\ud83c\udf0d")),
                new ControlRegion("tail", EvaluationDomain.WORLD, List.of(nodes.get(1)), false, Map.of()));
        for (NumericProfile profile : NumericProfile.values()) {
            var first = new WorldgenProgram("v1\0雪", roots, regions, profile);
            var second = new WorldgenProgram("v1\0雪", reversed, regions, profile);
            assertEquals(legacyProgramFingerprint(first), first.fingerprint());
            assertEquals(legacyProgramFingerprint(second), second.fingerprint());
            assertEquals(first.fingerprint(), second.fingerprint());
            var reordered = new WorldgenProgram("v1\0雪", roots, List.of(regions.get(1), regions.get(0)), profile);
            assertEquals(legacyProgramFingerprint(reordered), reordered.fingerprint());
            assertNotEquals(first.fingerprint(), reordered.fingerprint());
        }
        var empty = WorldgenProgram.builder().build();
        assertEquals(legacyProgramFingerprint(empty), empty.fingerprint());
    }

    @Test void childOrderAndAllAdditionalHeadersMatchLegacy() {
        var input = new ProgramNode.Input("x|{雪}\ud83c\udf0d", TYPE, DOMAIN);
        var constant = new ProgramNode.Constant(TYPE, -0.0, EvaluationDomain.WORLD);
        var range = new ProgramNode.RangeChoice(input, -0.125, 3.5, constant, input, TYPE, DOMAIN);
        var ap2 = new ProgramNode.Ap2("max", TYPE, DOMAIN, input, range,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        var marker = new ProgramNode.Marker("cache", "NONE", TYPE, DOMAIN, ap2,
                Map.of("zeta", "雪", "alpha", "{x}|y"));
        var nodes = List.of(constant, input, range, ap2, marker,
                new ProgramNode.Interpolated(marker, new InterpolationGeometry(4, 8), TYPE),
                new ProgramNode.Unary("abs", TYPE, DOMAIN, marker),
                new ProgramNode.Select("select", TYPE, DOMAIN, input, marker, constant),
                new ProgramNode.BlendDensity(marker, TYPE, DOMAIN),
                new ProgramNode.BlendAlpha(TYPE, DOMAIN), new ProgramNode.BlendOffset(TYPE, DOMAIN),
                new ProgramNode.Beardifier(TYPE, DOMAIN));
        for (ProgramNode node : nodes) {
            assertEquals(legacyNodeFingerprint(node), WorldgenProgram.nodeFingerprint(node), node.operation());
            var program = WorldgenProgram.builder().root("root", node).build();
            assertEquals(legacyProgramFingerprint(program), program.fingerprint());
        }
        var forward = new ProgramNode.Binary("subtract", TYPE, DOMAIN, input, constant);
        var reverse = new ProgramNode.Binary("subtract", TYPE, DOMAIN, constant, input);
        assertEquals(legacyNodeFingerprint(forward), WorldgenProgram.nodeFingerprint(forward));
        assertEquals(legacyNodeFingerprint(reverse), WorldgenProgram.nodeFingerprint(reverse));
        assertNotEquals(WorldgenProgram.nodeFingerprint(forward), WorldgenProgram.nodeFingerprint(reverse));
        assertThrows(NullPointerException.class, () -> WorldgenProgram.nodeFingerprint(null));
    }

    private static List<ProgramNode> capturedNodes() {
        var permutation = new ArrayList<Integer>();
        for (int i = 0; i < 256; i++) permutation.add((i * 73 + 19) & 255);
        var level = new NoiseParameters.ImprovedNoiseSnapshot(-12.25, 0.0, 87.5, permutation);
        var other = new NoiseParameters.ImprovedNoiseSnapshot(1.125, -0.0, -23.75, permutation);
        var first = new NoiseParameters.PerlinNoiseSnapshot(-3, List.of(1.0, 0.0, -0.5),
                Arrays.asList(level, null, other));
        var second = new NoiseParameters.PerlinNoiseSnapshot(-1, List.of(0.25, 1.0), List.of(other, level));
        var parameters = new NoiseParameters("minecraft:雪", -3, List.of(1.0, 0.0, -0.5), -987654321L,
                new NoiseParameters.CapturedNoise(Long.MIN_VALUE + 19, 1.75, first, second));
        var noise = new ProgramNode.Noise("terrain", parameters, 0.125, -0.0, TYPE, DOMAIN);
        var input = new ProgramNode.Input("x", TYPE, DOMAIN);
        var shift = new ProgramNode.Shift("shift", parameters, "ZX0", 4.0, TYPE, DOMAIN);
        var shifted = new ProgramNode.ShiftedNoise("shifted", parameters, 0.25, 0.5,
                shift, input, shift, TYPE, DOMAIN);
        var blended = new ProgramNode.BlendedNoise(new BlendedNoiseParameters(42, first, second, first,
                0.25, 0.5, 80.0, 160.0, 8.0), TYPE, DOMAIN);
        var end = new ProgramNode.EndIsland(new EndIslandParameters(-1.25, 4.5, permutation), TYPE, DOMAIN);
        var weird = new ProgramNode.WeirdScaledSampler(shifted, parameters, "TYPE2", TYPE, DOMAIN);
        var nested = new ProgramNode.SplineMultipoint(noise, List.of(-1.0f, 2.0f),
                List.of(new ProgramNode.SplineConstant(-0.0f), new ProgramNode.SplineConstant(3.5f)),
                List.of(0.25f, -0.75f));
        var spline = new ProgramNode.Spline(new ProgramNode.SplineMultipoint(shifted,
                List.of(-2.0f, 0.0f, 4.0f), List.of(nested, new ProgramNode.SplineConstant(1.0f), nested),
                List.of(-1.0f, 0.0f, 0.5f)), TYPE, DOMAIN);
        return List.of(noise, shift, shifted, blended, end, weird, spline);
    }

    private static String legacyNodeFingerprint(ProgramNode node) {
        return sha256(legacyNodeIdentity(node));
    }

    private static String legacyProgramFingerprint(WorldgenProgram program) {
        var value = new StringBuilder(program.version()).append('\0').append(program.numericProfile().name()).append('\0');
        program.roots().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> value.append(entry.getKey()).append(legacyNodeIdentity(entry.getValue())));
        program.controlRegions().forEach(region -> value.append(region.toString()));
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    // Frozen pre-streaming serializer; independent of the production header/digest helpers.
    private static String legacyNodeIdentity(ProgramNode node) {
        StringBuilder value = new StringBuilder(node.operation()).append('|').append(node.type()).append('|').append(node.domain());
        if (node instanceof ProgramNode.Constant constant) value.append('|').append(String.valueOf(constant.value()));
        if (node instanceof ProgramNode.Input input) value.append('|').append(input.name());
        if (node instanceof ProgramNode.Noise noise) value.append('|').append(noise.name()).append('|').append(noise.parameters()).append('|').append(noise.xzScale()).append('|').append(noise.yScale());
        if (node instanceof ProgramNode.ShiftedNoise noise) value.append('|').append(noise.name()).append('|').append(noise.parameters()).append('|').append(noise.xzScale()).append('|').append(noise.yScale());
        if (node instanceof ProgramNode.Shift shift) value.append('|').append(shift.name()).append('|').append(shift.parameters()).append('|').append(shift.axis()).append('|').append(shift.scale());
        if (node instanceof ProgramNode.BlendedNoise noise) value.append('|').append(noise.parameters());
        if (node instanceof ProgramNode.EndIsland endIsland) value.append('|').append(endIsland.parameters());
        if (node instanceof ProgramNode.WeirdScaledSampler sampler) value.append('|').append(sampler.parameters()).append('|').append(sampler.rarityMapper());
        if (node instanceof ProgramNode.Spline spline) value.append('|').append(spline.spline());
        if (node instanceof ProgramNode.Interpolated interpolated) value.append('|').append(interpolated.geometry());
        if (node instanceof ProgramNode.RangeChoice range) value.append('|').append(Double.toHexString(range.minInclusive())).append('|').append(Double.toHexString(range.maxExclusive()));
        if (node instanceof ProgramNode.Ap2 ap2) value.append('|').append(ap2.operation())
                .append('|').append(Double.toHexString(ap2.rightMinValue()))
                .append('|').append(Double.toHexString(ap2.rightMaxValue()));
        if (node instanceof ProgramNode.Marker marker) value.append('|').append(marker.marker()).append('|').append(marker.effects());
        for (ProgramNode child : node.children()) value.append('{').append(legacyNodeIdentity(child)).append('}');
        return value.toString();
    }
}
