// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.snapshot;

import dev.worldgennext.neoforge.loader.Loader;

import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.snapshot.StructureBlendSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.WeakHashMap;

/** Captures reload-sensitive loader inputs before they cross into pure code. */
public final class MinecraftDynamicInputReader {
    private static final int BUFFER_SIZE = 8192;
    private static final List<String> WORLDGEN_RESOURCE_ROOTS = List.of(
            "worldgen", "dimension", "dimension_type", "structures", "tags");
    private static final Map<ResourceManager, String> DATA_PACK_HASHES = new WeakHashMap<>();
    private static volatile String modStackHash;

    private MinecraftDynamicInputReader() {}

    public static DynamicInputIdentity capture(MinecraftServer server, StructureBlendSnapshot structureBlend) {
        if (server == null) throw new IllegalArgumentException("Minecraft server is required");
        if (structureBlend == null) throw new IllegalArgumentException("Structure blend is required");
        return new DynamicInputIdentity(
                dataPackHash(server),
                modStackHash(),
                digest("structures", structureBlend.beardifier()),
                digest("blend", structureBlend),
                0);
    }

    private static String dataPackHash(MinecraftServer server) {
        ResourceManager resources = server.getServerResources().resourceManager();
        synchronized (DATA_PACK_HASHES) {
            String existing = DATA_PACK_HASHES.get(resources);
            if (existing != null) return existing;
            String computed = hashDataPacks(server, resources);
            DATA_PACK_HASHES.put(resources, computed);
            return computed;
        }
    }

    private static String hashDataPacks(MinecraftServer server, ResourceManager resources) {
        MessageDigest digest = sha256();
        text(digest, "selected-packs");
        server.getPackRepository().getSelectedPacks().forEach(pack -> {
            text(digest, pack.getId());
        });
        for (String root : WORLDGEN_RESOURCE_ROOTS) {
            text(digest, "resource-root");
            text(digest, root);
            resources.listResources(root, ignored -> true).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Object::toString)))
                    .forEach(entry -> hashResource(digest, entry.getKey().toString(), entry.getValue()));
        }
        return hex(digest.digest());
    }

    private static void hashResource(MessageDigest digest, String name, Resource resource) {
        text(digest, name);
        text(digest, resource.sourcePackId());
        try (InputStream input = resource.open()) {
            bytes(digest, input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot hash effective server resource " + name, failure);
        }
    }

    private static String modStackHash() {
        String existing = modStackHash;
        if (existing != null) return existing;
        synchronized (MinecraftDynamicInputReader.class) {
            if (modStackHash != null) return modStackHash;
            MessageDigest digest = sha256();
            text(digest, "loaded-mod-files");
            try {
                Loader.modFiles().forEach(path -> hashPath(digest, path));
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Cannot hash loaded mod files", failure);
            }
            modStackHash = hex(digest.digest());
            return modStackHash;
        }
    }

    private static void hashPath(MessageDigest digest, Path root) {
        if (root == null) throw new IllegalStateException("Loaded mod has no file path");
        try {
            if (Files.isRegularFile(root)) {
                text(digest, root.getFileName().toString());
                try (InputStream input = Files.newInputStream(root)) {
                    bytes(digest, input);
                }
                return;
            }
            if (!Files.isDirectory(root)) throw new IOException("Mod path is not a file or directory: " + root);
            try (var files = Files.walk(root)) {
                files.filter(Files::isRegularFile).sorted()
                        .forEach(path -> {
                            text(digest, root.relativize(path).toString());
                            try (InputStream input = Files.newInputStream(path)) {
                                bytes(digest, input);
                            } catch (IOException failure) {
                                throw new IllegalStateException("Cannot hash mod file " + path, failure);
                            }
                        });
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot hash loaded mod path " + root, failure);
        }
    }

    private static String digest(String label, Object value) {
        MessageDigest digest = sha256();
        text(digest, label);
        text(digest, String.valueOf(value));
        return hex(digest.digest());
    }

    private static void bytes(MessageDigest digest, InputStream input) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        for (int count; (count = input.read(buffer)) >= 0;) {
            if (count > 0) digest.update(buffer, 0, count);
        }
    }

    private static void text(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (encoded.length >>> 24));
        digest.update((byte) (encoded.length >>> 16));
        digest.update((byte) (encoded.length >>> 8));
        digest.update((byte) encoded.length);
        digest.update(encoded);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }
}
