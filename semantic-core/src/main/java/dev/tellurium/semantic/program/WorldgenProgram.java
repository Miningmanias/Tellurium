// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.program;

import dev.tellurium.semantic.ExpressionIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable typed program with stable root/control-region identity. */
public final class WorldgenProgram {
    public static final List<String> ROUTER_ROOTS = List.of(
            "barrierNoise", "fluidLevelFloodedness", "fluidLevelSpread", "lava",
            "temperature", "vegetation", "continents", "erosion", "depth",
            "ridges", "initialDensityWithoutJaggedness", "finalDensity",
            "veinToggle", "veinRidged", "veinGap");

    private final String version;
    private final Map<String, ProgramNode> roots;
    private final List<ControlRegion> controlRegions;
    private final NumericProfile numericProfile;
    private final String fingerprint;

    public WorldgenProgram(String version, Map<String, ProgramNode> roots,
                           List<ControlRegion> controlRegions, NumericProfile numericProfile) {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Program version is required");
        this.version = version;
        Objects.requireNonNull(roots, "roots");
        var copied = new LinkedHashMap<String, ProgramNode>();
        roots.forEach((name, node) -> {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Root name is required");
            copied.put(name, Objects.requireNonNull(node, "root node"));
        });
        this.roots = Collections.unmodifiableMap(copied);
        this.controlRegions = List.copyOf(controlRegions == null ? List.of() : controlRegions);
        this.numericProfile = Objects.requireNonNull(numericProfile, "numericProfile");
        this.fingerprint = computeFingerprint();
    }

    public static Builder builder() { return new Builder(); }
    public String version() { return version; }
    public Map<String, ProgramNode> roots() { return roots; }
    public List<ControlRegion> controlRegions() { return controlRegions; }
    public NumericProfile numericProfile() { return numericProfile; }
    public String fingerprint() { return fingerprint; }
    public boolean hasAllRouterRoots() { return roots.keySet().containsAll(ROUTER_ROOTS); }
    /** True only when this program contains exactly the version-pinned router root set. */
    public boolean hasExactRouterRoots() { return roots.keySet().equals(java.util.Set.copyOf(ROUTER_ROOTS)); }
    public ProgramNode root(String name) { return roots.get(Objects.requireNonNull(name, "name")); }

    /**
     * Stable identity for one semantic node, independent of the generated
     * GLSL function name assigned by a compiler traversal.
     *
     * <p>This is intentionally the same canonical representation used by the
     * enclosing program fingerprint.  Diagnostic tooling can therefore select
     * a node across captures even when emitter-local {@code wg_node_<id>}
     * allocation order changes.</p>
     */
    public static String nodeFingerprint(ProgramNode node) {
        Objects.requireNonNull(node, "node");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateNodeIdentity(digest, node, new IdentityHashMap<>());
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private String computeFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((version + "\0" + numericProfile.name() + "\0").getBytes(StandardCharsets.UTF_8));
            var headers = new IdentityHashMap<ProgramNode, byte[]>();
            roots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                updateNodeIdentity(digest, entry.getValue(), headers);
            });
            controlRegions.forEach(region -> digest.update(region.toString().getBytes(StandardCharsets.UTF_8)));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void updateNodeIdentity(MessageDigest digest, ProgramNode node,
                                           IdentityHashMap<ProgramNode, byte[]> headers) {
        // Cache only local headers: every occurrence still contributes its full ordered subtree.
        digest.update(headers.computeIfAbsent(node, WorldgenProgram::nodeIdentityHeader));
        for (ProgramNode child : node.children()) {
            digest.update((byte) '{');
            updateNodeIdentity(digest, child, headers);
            digest.update((byte) '}');
        }
    }

    private static byte[] nodeIdentityHeader(ProgramNode node) {
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
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private String version = "tellurium-program-v1";
        private final Map<String, ProgramNode> roots = new LinkedHashMap<>();
        private final List<ControlRegion> regions = new ArrayList<>();
        private NumericProfile numericProfile = NumericProfile.JAVA_REFERENCE;
        public Builder version(String value) { version = Objects.requireNonNull(value, "version"); return this; }
        public Builder numericProfile(NumericProfile value) { numericProfile = Objects.requireNonNull(value, "numericProfile"); return this; }
        public Builder root(String name, ProgramNode node) { roots.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(node, "node")); return this; }
        public Builder roots(Map<String, ProgramNode> values) { values.forEach(this::root); return this; }
        public Builder controlRegion(ControlRegion region) { regions.add(Objects.requireNonNull(region, "region")); return this; }
        public WorldgenProgram build() { return new WorldgenProgram(version, roots, regions, numericProfile); }
    }
}
