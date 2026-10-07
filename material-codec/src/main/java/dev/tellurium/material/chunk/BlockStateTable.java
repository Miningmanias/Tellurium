// SPDX-License-Identifier: MIT
package dev.tellurium.material.chunk;

import dev.tellurium.semantic.snapshot.BlockStateDescriptor;
import dev.tellurium.semantic.snapshot.RegistrySnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Result-local canonical state mapping; IDs are never assumed to be Minecraft global IDs. */
public final class BlockStateTable {
    private final List<BlockStateDescriptor> states;
    private final Map<String, Integer> ids;
    private final String fingerprint;
    public BlockStateTable(List<BlockStateDescriptor> states) {
        this(states, fingerprint(states));
    }

    private BlockStateTable(List<BlockStateDescriptor> states, String fingerprint) {
        var copy = new ArrayList<>(Objects.requireNonNull(states, "states"));
        if (copy.isEmpty() || copy.stream().anyMatch(Objects::isNull) || copy.stream().map(BlockStateDescriptor::canonical).distinct().count() != copy.size()) throw new IllegalArgumentException("State table must contain unique states");
        this.states = List.copyOf(copy); var map = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < copy.size(); i++) map.put(copy.get(i).canonical(), i);
        ids = Map.copyOf(map);
        if (fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("State table fingerprint is blank");
        this.fingerprint = fingerprint;
    }
    public static BlockStateTable fromRegistry(RegistrySnapshot registry) {
        Objects.requireNonNull(registry, "registry");
        return new BlockStateTable(registry.states(), registry.fingerprint());
    }
    static BlockStateTable fromEncoded(List<BlockStateDescriptor> states, String fingerprint) {
        return new BlockStateTable(states, fingerprint);
    }
    public List<BlockStateDescriptor> states() { return states; }
    public Map<String, Integer> ids() { return ids; }
    public int id(String canonical) { return ids.getOrDefault(canonical, -1); }
    public int id(BlockStateDescriptor state) { return id(state.canonical()); }
    public BlockStateDescriptor state(int id) { if (id < 0 || id >= states.size()) throw new IndexOutOfBoundsException(id); return states.get(id); }
    public String fingerprint() { return fingerprint; }
    private static String fingerprint(List<BlockStateDescriptor> values) {
        try { var digest = java.security.MessageDigest.getInstance("SHA-256"); values.forEach(value -> digest.update((value.canonical() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))); return java.util.HexFormat.of().formatHex(digest.digest()); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
