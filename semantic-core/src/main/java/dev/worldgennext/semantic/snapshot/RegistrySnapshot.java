// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable registry identity; process-local numeric IDs never escape this table. */
public final class RegistrySnapshot {
    private final List<BlockStateDescriptor> states;
    private final Map<String, Integer> ids;
    private final Map<String, BlockStateTraits> stateTraits;
    private final BlockStateDescriptor defaultBlock;
    private final BlockStateDescriptor defaultFluid;
    private final String fingerprint;

    public RegistrySnapshot(List<BlockStateDescriptor> states, BlockStateDescriptor defaultBlock,
                            BlockStateDescriptor defaultFluid) {
        this(states, defaultBlock, defaultFluid, Map.of());
    }

    public RegistrySnapshot(List<BlockStateDescriptor> states, BlockStateDescriptor defaultBlock,
                            BlockStateDescriptor defaultFluid, Map<String, BlockStateTraits> stateTraits) {
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(stateTraits, "stateTraits");
        var copied = new ArrayList<>(states);
        if (copied.isEmpty()) throw new IllegalArgumentException("Registry snapshot cannot be empty");
        if (copied.stream().anyMatch(Objects::isNull) || copied.stream().map(BlockStateDescriptor::canonical).distinct().count() != copied.size()) {
            throw new IllegalArgumentException("Registry states must be unique");
        }
        this.states = List.copyOf(copied);
        var map = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < copied.size(); i++) map.put(copied.get(i).canonical(), i);
        this.ids = Collections.unmodifiableMap(map);
        this.defaultBlock = Objects.requireNonNull(defaultBlock, "defaultBlock");
        this.defaultFluid = Objects.requireNonNull(defaultFluid, "defaultFluid");
        if (!ids.containsKey(defaultBlock.canonical()) || !ids.containsKey(defaultFluid.canonical())) {
            throw new IllegalArgumentException("Defaults must be present in registry");
        }
        var traits = new LinkedHashMap<String, BlockStateTraits>();
        for (var state : copied) {
            traits.put(state.canonical(), Objects.requireNonNull(
                    stateTraits.getOrDefault(state.canonical(), BlockStateTraits.infer(state)),
                    "stateTraits entry"));
        }
        if (stateTraits.keySet().stream().anyMatch(key -> key == null || !ids.containsKey(key))) {
            throw new IllegalArgumentException("State traits must reference known registry states");
        }
        this.stateTraits = Collections.unmodifiableMap(traits);
        this.fingerprint = hash();
    }

    public List<BlockStateDescriptor> states() { return states; }
    public Map<String, Integer> ids() { return ids; }
    public Map<String, BlockStateTraits> stateTraits() { return stateTraits; }
    public BlockStateTraits stateTraits(String canonical) {
        return stateTraits.get(canonical);
    }
    public BlockStateDescriptor defaultBlock() { return defaultBlock; }
    public BlockStateDescriptor defaultFluid() { return defaultFluid; }
    public String fingerprint() { return fingerprint; }
    public int id(BlockStateDescriptor state) { return id(Objects.requireNonNull(state).canonical()); }
    public int id(String canonical) { return ids.getOrDefault(canonical, -1); }
    public BlockStateDescriptor state(int id) {
        if (id < 0 || id >= states.size()) throw new IndexOutOfBoundsException(id);
        return states.get(id);
    }
    public static RegistrySnapshot minimal() {
        var air = BlockStateDescriptor.of("minecraft:air");
        var stone = BlockStateDescriptor.of("minecraft:stone");
        var water = BlockStateDescriptor.of("minecraft:water");
        return new RegistrySnapshot(List.of(air, stone, water), stone, water);
    }
    private String hash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            states.forEach(state -> digest.update((state.canonical() + "\0" + stateTraits.get(state.canonical()) + "\n")
                    .getBytes(StandardCharsets.UTF_8)));
            digest.update(defaultBlock.canonical().getBytes(StandardCharsets.UTF_8));
            digest.update(defaultFluid.canonical().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
