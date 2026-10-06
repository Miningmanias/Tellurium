// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs Distant Horizons' own chunk-to-LOD conversion on this mod's threads.
 *
 * <p>Distant Horizons runs everything it does inside one thread budget (12 threads by default), and converting
 * the chunks it is given takes most of it: baking its block light, building the data columns, merging them
 * into the tile.  The conversion itself is a handful of public methods on Distant Horizons classes, and this
 * class calls exactly those, in the order Distant Horizons' own INTERNAL_SERVER generator does, so the data is
 * what Distant Horizons would have produced; it only happens on other threads, before the tile is handed
 * back.</p>
 *
 * <p>None of these classes is part of Distant Horizons' API.  They are looked up by name once; if one is
 * missing {@link #available()} is false and the bridge lets Distant Horizons convert the chunks itself.</p>
 */
final class DistantHorizonsConverter {
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.dh.convert", "true"));
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext");

    private static volatile boolean available;
    private static Object factory;
    private static Object lightingEngine;
    private static Method createChunkWrapper;
    private static Method createHeightMaps;
    private static Method isBlockLightCorrect;
    private static Method bakeBlockLight;
    private static Method updateBeaconBeams;
    private static Method createFromChunk;
    private static Method updateFromDataSource;
    private static ExecutorService pool;

    private DistantHorizonsConverter() {}

    static boolean available() {
        return available;
    }

    /** Looks the classes up once.  Called from the bridge's thread before any level is registered. */
    static synchronized void prepare() {
        if (!ENABLED || available) return;
        try {
            String core = "com.seibel.distanthorizons.core.";
            Class<?> wrapperFactory = Class.forName(core + "wrapperInterfaces.IWrapperFactory");
            Class<?> chunkWrapper = Class.forName(core + "wrapperInterfaces.chunk.IChunkWrapper");
            Class<?> levelWrapper = Class.forName(core + "wrapperInterfaces.world.ILevelWrapper");
            Class<?> dataSource = Class.forName(core + "dataObjects.fullData.sources.FullDataSourceV2");
            Class<?> injector = Class.forName(core + "dependencyInjection.SingletonInjector");
            Object injectorInstance = injector.getField("INSTANCE").get(null);
            factory = injectorInstance.getClass().getMethod("get", Class.class).invoke(injectorInstance, wrapperFactory);
            if (factory == null) throw new IllegalStateException("no wrapper factory is bound");
            createChunkWrapper = wrapperFactory.getMethod("createChunkWrapper", Object[].class);
            createHeightMaps = chunkWrapper.getMethod("createDhHeightMaps");
            isBlockLightCorrect = chunkWrapper.getMethod("isDhBlockLightingCorrect");
            Class<?> lighting = Class.forName(core + "generation.DhLightingEngine");
            lightingEngine = lighting.getField("INSTANCE").get(null);
            bakeBlockLight = lighting.getMethod("bakeChunkBlockLighting", chunkWrapper, ArrayList.class, int.class);
            updateBeaconBeams = Class.forName(core + "level.IDhLevel").getMethod("updateBeaconBeamsForChunk", chunkWrapper, ArrayList.class);
            createFromChunk = Class.forName(core + "dataObjects.transformers.LodDataBuilder").getMethod("createFromChunk", levelWrapper, chunkWrapper);
            updateFromDataSource = dataSource.getMethod("updateFromDataSource", dataSource);
            if (!AutoCloseable.class.isAssignableFrom(dataSource)) throw new IllegalStateException("data sources cannot be closed");
            int threads = Math.max(2, Integer.getInteger("worldgennext.dh.convertThreads", Runtime.getRuntime().availableProcessors() / 2));
            // Distant Horizons tells its own background threads from the game's by this prefix, and warns when its
            // conversion runs on a thread without it (meaning the server or render thread).  These are background
            // threads doing its conversion, so they carry the prefix, and this mod's name.
            String prefix = (String) Class.forName("com.seibel.distanthorizons.coreapi.ModInfo").getField("THREAD_NAME_PREFIX").get(null);
            AtomicInteger number = new AtomicInteger();
            pool = Executors.newFixedThreadPool(threads, task -> {
                Thread thread = new Thread(task, prefix + "WorldgenNext Convert Thread[" + number.getAndIncrement() + "]");
                thread.setDaemon(true);
                return thread;
            });
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
            LOG.info("This version of Distant Horizons does not have the conversion methods this mod calls ({});"
                    + " it will convert the chunks on its own threads", missing.toString());
        }
    }

    static ExecutorService pool() {
        return pool;
    }

    /**
     * Converts the chunks of one tile into the tile's data source, which Distant Horizons supplied.  The steps
     * and their order are those of Distant Horizons' INTERNAL_SERVER generator followed by its own merge.
     */
    static void convert(ServerLevel level, Object dhLevel, Object levelWrapper, ChunkAccess[] chunks, Object tileDataSource) throws Exception {
        try {
            ArrayList<Object> wrappers = new ArrayList<>(chunks.length);
            for (ChunkAccess chunk : chunks) {
                Object wrapper = createChunkWrapper.invoke(factory, (Object) new Object[]{chunk, level});
                createHeightMaps.invoke(wrapper);
                wrappers.add(wrapper);
            }
            int maxSkyLight = level.dimensionType().hasSkyLight() ? 15 : 0;
            for (Object wrapper : wrappers) {
                if (!(boolean) isBlockLightCorrect.invoke(wrapper)) bakeBlockLight.invoke(lightingEngine, wrapper, wrappers, maxSkyLight);
                updateBeaconBeams.invoke(dhLevel, wrapper, wrappers);
            }
            for (Object wrapper : wrappers) {
                Object chunkData = createFromChunk.invoke(null, levelWrapper, wrapper);
                if (chunkData == null) continue;
                try {
                    updateFromDataSource.invoke(tileDataSource, chunkData);
                } finally {
                    ((AutoCloseable) chunkData).close();
                }
            }
        } catch (InvocationTargetException thrown) {
            if (thrown.getCause() instanceof Exception cause) throw cause;
            throw thrown;
        }
    }
}
