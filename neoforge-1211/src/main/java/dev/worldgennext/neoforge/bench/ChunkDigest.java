// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.bench;

import dev.worldgennext.neoforge.version.Version;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Canonical per-chunk logical digest for cross-process equality checks:
 * block states (global state ids within one pinned stack), worldgen
 * heightmaps, post-processing marks, structure starts and references.
 * Two runs of the same stack, seed and coordinates must produce identical
 * lines; any difference identifies the chunk and the field family.
 */
public final class ChunkDigest {
    private ChunkDigest() {}

    public static String line(ServerLevel level, ChunkAccess chunk) {
        return chunk.getPos().x + " " + chunk.getPos().z
                + " blocks=" + blocks(chunk)
                + " heightmaps=" + heightmaps(chunk)
                + " post=" + postProcessing(chunk)
                + " biomes=" + biomes(level, chunk)
                + " structures=" + structures(level, chunk);
    }

    private static String blocks(ChunkAccess chunk) {
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(4096 * 4);
        for (LevelChunkSection section : chunk.getSections()) {
            buffer.clear();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                buffer.putInt(Block.getId(section.getBlockState(x, y, z)));
            }
            digest.update(buffer.array(), 0, buffer.position());
        }
        return hex(digest);
    }

    private static String biomes(ServerLevel level, ChunkAccess chunk) {
        var registry = Version.registry(level.registryAccess(), Registries.BIOME);
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 4);
        for (LevelChunkSection section : chunk.getSections()) {
            buffer.clear();
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                buffer.putInt(registry.getId(section.getNoiseBiome(x, y, z).value()));
            }
            digest.update(buffer.array(), 0, buffer.position());
        }
        return hex(digest);
    }

    /** Names of the biomes present in the chunk's biome containers. */
    public static java.util.Set<String> biomeNames(ChunkAccess chunk) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (LevelChunkSection section : chunk.getSections()) {
            section.getBiomes().getAll(holder -> names.add(holder.unwrapKey().map(key -> key.location().toString()).orElse("?")));
        }
        return names;
    }

    private static String heightmaps(ChunkAccess chunk) {
        MessageDigest digest = sha256();
        List<Map.Entry<Heightmap.Types, Heightmap>> entries = new ArrayList<>(chunk.getHeightmaps());
        entries.sort(Map.Entry.comparingByKey());
        ByteBuffer buffer = ByteBuffer.allocate(8);
        for (var entry : entries) {
            digest.update(entry.getKey().getSerializationKey().getBytes(StandardCharsets.UTF_8));
            for (long word : entry.getValue().getRawData()) {
                buffer.clear();
                buffer.putLong(word);
                digest.update(buffer.array());
            }
        }
        return hex(digest);
    }

    private static String postProcessing(ChunkAccess chunk) {
        MessageDigest digest = sha256();
        ShortList[] lists = chunk.getPostProcessing();
        for (int i = 0; i < lists.length; i++) {
            ShortList list = lists[i];
            if (list == null || list.isEmpty()) continue;
            short[] values = list.toShortArray();
            java.util.Arrays.sort(values);
            digest.update((byte) i);
            for (short value : values) {
                digest.update((byte) (value >> 8));
                digest.update((byte) value);
            }
        }
        return hex(digest);
    }

    private static String structures(ServerLevel level, ChunkAccess chunk) {
        Registry<Structure> registry = Version.registry(level.registryAccess(), Registries.STRUCTURE);
        StringBuilder text = new StringBuilder();
        List<String> starts = new ArrayList<>();
        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            StringBuilder one = new StringBuilder(key(registry, entry.getKey()));
            one.append(start.isValid() ? ":valid:" : ":invalid:").append(box(start.getBoundingBox()));
            for (StructurePiece piece : start.getPieces()) {
                one.append('|').append(net.minecraft.core.registries.BuiltInRegistries.STRUCTURE_PIECE.getKey(piece.getType())).append('@').append(box(piece.getBoundingBox()));
            }
            starts.add(one.toString());
        }
        starts.sort(null);
        List<String> references = new ArrayList<>();
        for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
            long[] values = entry.getValue().toLongArray();
            java.util.Arrays.sort(values);
            references.add(key(registry, entry.getKey()) + "=" + java.util.Arrays.toString(values));
        }
        references.sort(null);
        text.append(starts).append(';').append(references);
        MessageDigest digest = sha256();
        digest.update(text.toString().getBytes(StandardCharsets.UTF_8));
        return hex(digest) + "/" + starts.size() + "s" + references.size() + "r";
    }

    private static String key(Registry<Structure> registry, Structure structure) {
        ResourceLocation id = registry.getKey(structure);
        return id == null ? "unregistered" : id.toString();
    }

    private static String box(BoundingBox box) {
        return box.minX() + "," + box.minY() + "," + box.minZ() + "," + box.maxX() + "," + box.maxY() + "," + box.maxZ();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest(), 0, 12);
    }
}
