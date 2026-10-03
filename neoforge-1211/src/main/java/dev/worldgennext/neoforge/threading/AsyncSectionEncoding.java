// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.threading;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Deferred palette encoding for chunks that are being unloaded.
 *
 * <p>ChunkSerializer.write runs on the server thread and is dominated by
 * PalettedContainer packing.  While a collector is active on the calling
 * thread, each section's block-state and biome encoding is replaced by a
 * placeholder tag; {@link #resolve} later encodes them on a worker and swaps
 * the real tags in before the chunk tag is handed to the IO worker.
 * Everything else (block entities, light, ticks, structures) is still
 * captured on the server thread.</p>
 */
public final class AsyncSectionEncoding {
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("worldgennext.asyncChunkSave", "true"));
    private static final ThreadLocal<Map<Tag, Supplier<Tag>>> COLLECTOR = new ThreadLocal<>();

    private AsyncSectionEncoding() {}

    public static Map<Tag, Supplier<Tag>> begin() {
        Map<Tag, Supplier<Tag>> collector = new IdentityHashMap<>();
        COLLECTOR.set(collector);
        return collector;
    }

    public static void end() { COLLECTOR.remove(); }

    /** Non-null only between begin() and end() on this thread. */
    public static Map<Tag, Supplier<Tag>> collector() { return COLLECTOR.get(); }

    /** Registers a deferred encoding and returns the placeholder that stands in for it. */
    public static Tag defer(Map<Tag, Supplier<Tag>> collector, Supplier<Tag> encoder) {
        CompoundTag placeholder = new CompoundTag();
        collector.put(placeholder, encoder);
        return placeholder;
    }

    /** Replaces every placeholder in the chunk tag's sections; throws if any encoding fails. */
    public static void resolve(CompoundTag chunkTag, Map<Tag, Supplier<Tag>> collector) {
        ListTag sections = chunkTag.getList("sections", Tag.TAG_COMPOUND);
        int resolved = 0;
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            for (String key : new String[]{"block_states", "biomes"}) {
                Tag current = section.get(key);
                Supplier<Tag> encoder = current == null ? null : collector.get(current);
                if (encoder != null) {
                    section.put(key, encoder.get());
                    resolved++;
                }
            }
        }
        if (resolved != collector.size()) {
            throw new IllegalStateException("Deferred section encodings were not all located in the chunk tag: "
                    + resolved + " of " + collector.size());
        }
    }
}
