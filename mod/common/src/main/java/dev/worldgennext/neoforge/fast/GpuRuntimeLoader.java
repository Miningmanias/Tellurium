// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import dev.worldgennext.compiler.vulkan.fused.FusedGpuBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the native GPU runtime from the jars nested in this mod, in a class
 * loader of its own.
 *
 * <p>A dedicated server has no LWJGL at all and a client already carries its
 * own LWJGL core, so the Vulkan/shaderc bindings cannot be ordinary mod
 * dependencies.  The nested jars (runtime classes, LWJGL core, Vulkan,
 * shaderc and their natives) are unpacked once into the game directory and
 * loaded child-first for {@code org.lwjgl.} and the runtime package; every
 * other class, including the {@link FusedGpuBackend} contract, comes from the
 * mod's own loader.  That LWJGL copy extracts its natives into a private
 * directory.</p>
 */
final class GpuRuntimeLoader {
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext-fast");
    private static final String RESOURCE_ROOT = "/META-INF/worldgennext-gpu/";
    private static final String ENTRY_CLASS = "dev.worldgennext.runtime.vulkan.fused.FusedNoiseDevice";

    private GpuRuntimeLoader() {}

    /** The runtime's entry class and natives directory, loaded once per JVM. */
    private static Class<?> ENTRY;
    private static Path NATIVES;

    static synchronized FusedGpuBackend open(Path gameDirectory) throws Exception {
        if (ENTRY == null) load(gameDirectory);
        Object backend;
        try {
            backend = ENTRY.getMethod("openIsolated", String.class).invoke(null, NATIVES.toString());
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        }
        return (FusedGpuBackend) backend;
    }

    private static void load(Path gameDirectory) throws Exception {
        List<String> names = index();
        if (names.isEmpty()) throw new IllegalStateException("GPU runtime jars are not bundled in this build");
        String fingerprint = Integer.toHexString(String.join("|", names).hashCode());
        Path directory = gameDirectory.resolve("worldgennext-cache").resolve("gpu-runtime-" + fingerprint).toAbsolutePath();
        Files.createDirectories(directory);
        List<URL> urls = new ArrayList<>();
        for (String name : names) {
            Path target = directory.resolve(name);
            try (InputStream in = GpuRuntimeLoader.class.getResourceAsStream(RESOURCE_ROOT + name)) {
                if (in == null) throw new IllegalStateException("Bundled GPU runtime jar missing: " + name);
                byte[] bytes = in.readAllBytes();
                if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                    Path temporary = Files.createTempFile(directory, name, ".tmp");
                    Files.write(temporary, bytes);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            urls.add(target.toUri().toURL());
        }
        Path natives = directory.resolve("natives");
        Files.createDirectories(natives);
        ClassLoader loader = new ChildFirstLoader(urls.toArray(URL[]::new), GpuRuntimeLoader.class.getClassLoader());
        Class<?> entry = Class.forName(ENTRY_CLASS, true, loader);
        LOG.debug("Fast GPU NOISE runtime loaded from {} bundled jars in {}", names.size(), directory);
        NATIVES = natives;
        ENTRY = entry;
    }

    private static List<String> index() throws IOException {
        try (InputStream in = GpuRuntimeLoader.class.getResourceAsStream(RESOURCE_ROOT + "index.txt")) {
            if (in == null) return List.of();
            List<String> names = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) names.add(line.trim());
            }
            return names;
        }
    }

    /** Child-first for the native bindings and the runtime that uses them; parent-first for everything else. */
    private static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(URL[] urls, ClassLoader parent) { super("worldgennext-gpu", urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.lwjgl.") || name.startsWith("dev.worldgennext.runtime.vulkan.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
