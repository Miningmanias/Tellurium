// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.compat;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This mod's own chunk-to-LOD column writer for Distant Horizons.
 *
 * <p>Distant Horizons turns a chunk into a tile-sized data source of its own and then merges that into the
 * tile, re-processing the whole tile (64x64 columns) once per chunk: sixteen times for a tile.  This class
 * scans the chunk's columns itself and writes each one straight into the tile's data source, then removes
 * hidden data points once.  The rules for a column are Distant Horizons' (start above the height maps, walk
 * down, a new data point wherever the block or the biome changes, and one more after a block that only tints
 * what is under it); the code is this mod's.  Block and sky light are still read from Distant Horizons' chunk
 * wrapper, after its own light baking, and its data-point encoding and id mapping are called, not copied.</p>
 *
 * <p>Distant Horizons' builder fires a DhApiChunkProcessingEvent for every data point, which lets another mod
 * replace blocks or biomes while a chunk is converted.  This writer does not fire it; while any mod listens to
 * that event {@link #usable()} is false and Distant Horizons' builder is used instead.</p>
 *
 * <p>With {@code -Dworldgennext.dh.columnsCheck=true} every tile is also built by Distant Horizons' own
 * builder and the two are compared column by column.</p>
 */
final class DistantHorizonsColumns {
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.dh.columns", "true"));
    static final boolean CHECK = Boolean.getBoolean("worldgennext.dh.columnsCheck");
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext");

    private static volatile boolean available;
    private static volatile boolean listenerReported;
    private static volatile boolean failureReported;
    private static MethodHandle minNonEmptyHeight, exclusiveMaxHeight, inclusiveMinHeight;   // (Object)int
    private static MethodHandle lightBlockingHeight, solidHeight;                             // (Object,int,int)int
    private static MethodHandle blockLight, skyLight;                                         // (Object,int,int,int)int
    private static MethodHandle blockWrapper, biomeWrapper;                                   // (Object,int,int,int)Object
    private static MethodHandle isAir, isSolid, isLiquid;                                     // (Object)boolean
    private static MethodHandle opacity;                                                      // (Object)int
    private static MethodHandle mappingOf;                                                    // (Object)Object
    private static MethodHandle idFor;                                                        // (Object,Object,Object)int
    private static MethodHandle encode;                                                       // (int,int,int,byte,byte)long
    private static MethodHandle setColumn;                                                    // (Object,LongArrayList,int,int,Object,Object)void
    private static MethodHandle columnAt;                                                     // (Object,int,int)LongArrayList
    private static MethodHandle setNotEmpty;                                                  // (Object,boolean)void
    private static MethodHandle cull;                                                         // (Object,int,int)void
    private static MethodHandle compressionMode;                                              // ()Object
    private static MethodHandle listeners;                                                    // ()List
    private static MethodHandle apiColumn;                                                    // (Object,int,int)List
    private static Object air;
    private static Object lightStep;
    private static int fullyOpaque;

    static final AtomicLong COLUMNS_CHECKED = new AtomicLong();
    static final AtomicLong COLUMNS_DIFFERENT = new AtomicLong();
    static final AtomicLong POINTS_CHECKED = new AtomicLong();
    /** Tiles whose chunks changed between the two builds, found by building both again. */
    static final AtomicLong TILES_CHANGED = new AtomicLong();
    private static final AtomicInteger REPORTS = new AtomicInteger();

    private DistantHorizonsColumns() {}

    static boolean available() {
        return available;
    }

    /** Whether this tile may be written here: the writer was found, and no mod listens to the event it does not fire. */
    static boolean usable() {
        if (!available) return false;
        try {
            // With nothing bound, Distant Horizons answers with a list holding one null.
            List<?> bound = ((List<?>) listeners.invokeExact()).stream().filter(Objects::nonNull).toList();
            if (!bound.isEmpty() && !listenerReported) {
                listenerReported = true;
                LOG.info("A mod listens to Distant Horizons' chunk processing event ({}); Distant Horizons' own builder is used"
                        + " so that it is heard", String.valueOf(bound));
            }
            return bound.isEmpty();
        } catch (Throwable unknown) {
            if (!failureReported) {
                failureReported = true;
                LOG.warn("Could not ask Distant Horizons who listens to its chunk processing event ({}); its own builder is used",
                        unknown.toString());
            }
            return false;
        }
    }

    /** Called once after {@link DistantHorizonsConverter#prepare} found its classes. */
    static synchronized void prepare(Object wrapperFactory) {
        if (!ENABLED || available) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            String core = "com.seibel.distanthorizons.core.";
            Class<?> chunk = Class.forName(core + "wrapperInterfaces.chunk.IChunkWrapper");
            Class<?> block = Class.forName(core + "wrapperInterfaces.block.IBlockStateWrapper");
            Class<?> biome = Class.forName(core + "wrapperInterfaces.world.IBiomeWrapper");
            Class<?> source = Class.forName(core + "dataObjects.fullData.sources.FullDataSourceV2");
            Class<?> mapping = Class.forName(core + "dataObjects.fullData.FullDataPointIdMap");
            Class<?> step = Class.forName("com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep");
            Class<?> compression = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode");
            MethodType height = MethodType.methodType(int.class, Object.class);
            minNonEmptyHeight = lookup.findVirtual(chunk, "getMinNonEmptyHeight", MethodType.methodType(int.class)).asType(height);
            exclusiveMaxHeight = lookup.findVirtual(chunk, "getExclusiveMaxBuildHeight", MethodType.methodType(int.class)).asType(height);
            inclusiveMinHeight = lookup.findVirtual(chunk, "getInclusiveMinBuildHeight", MethodType.methodType(int.class)).asType(height);
            MethodType map = MethodType.methodType(int.class, Object.class, int.class, int.class);
            lightBlockingHeight = lookup.findVirtual(chunk, "getLightBlockingHeightMapValue", MethodType.methodType(int.class, int.class, int.class)).asType(map);
            solidHeight = lookup.findVirtual(chunk, "getSolidHeightMapValue", MethodType.methodType(int.class, int.class, int.class)).asType(map);
            MethodType light = MethodType.methodType(int.class, Object.class, int.class, int.class, int.class);
            MethodType at = MethodType.methodType(int.class, int.class, int.class, int.class);
            blockLight = lookup.findVirtual(chunk, "getDhBlockLight", at).asType(light);
            skyLight = lookup.findVirtual(chunk, "getDhSkyLight", at).asType(light);
            MethodType wrapper = MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class);
            blockWrapper = lookup.findVirtual(chunk, "getBlockState", MethodType.methodType(block, int.class, int.class, int.class)).asType(wrapper);
            biomeWrapper = lookup.findVirtual(chunk, "getBiome", MethodType.methodType(biome, int.class, int.class, int.class)).asType(wrapper);
            MethodType flag = MethodType.methodType(boolean.class, Object.class);
            isAir = lookup.findVirtual(block, "isAir", MethodType.methodType(boolean.class)).asType(flag);
            isSolid = lookup.findVirtual(block, "isSolid", MethodType.methodType(boolean.class)).asType(flag);
            isLiquid = lookup.findVirtual(block, "isLiquid", MethodType.methodType(boolean.class)).asType(flag);
            opacity = lookup.findVirtual(block, "getOpacity", MethodType.methodType(int.class)).asType(height);
            mappingOf = lookup.findGetter(source, "mapping", mapping).asType(MethodType.methodType(Object.class, Object.class));
            idFor = lookup.findVirtual(mapping, "addIfNotPresentAndGetId", MethodType.methodType(int.class, biome, block))
                    .asType(MethodType.methodType(int.class, Object.class, Object.class, Object.class));
            encode = lookup.findStatic(Class.forName(core + "util.FullDataPointUtil"), "encode",
                    MethodType.methodType(long.class, int.class, int.class, int.class, byte.class, byte.class));
            setColumn = lookup.findVirtual(source, "setSingleColumn",
                            MethodType.methodType(void.class, LongArrayList.class, int.class, int.class, step, compression))
                    .asType(MethodType.methodType(void.class, Object.class, LongArrayList.class, int.class, int.class, Object.class, Object.class));
            columnAt = lookup.findVirtual(source, "getColumnAtRelPos", MethodType.methodType(LongArrayList.class, int.class, int.class))
                    .asType(MethodType.methodType(LongArrayList.class, Object.class, int.class, int.class));
            setNotEmpty = lookup.findSetter(source, "isEmpty", boolean.class).asType(MethodType.methodType(void.class, Object.class, boolean.class));
            cull = lookup.findStatic(Class.forName(core + "dataObjects.transformers.FullDataOcclusionCuller"), "cullHiddenDatapointsInColumn",
                    MethodType.methodType(void.class, source, int.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class, int.class));
            Object setting = Class.forName(core + "config.Config$Common$LodBuilding").getField("worldCompression").get(null);
            compressionMode = lookup.unreflect(setting.getClass().getMethod("get")).bindTo(setting).asType(MethodType.methodType(Object.class));
            apiColumn = lookup.findVirtual(source, "getApiDataPointColumn", MethodType.methodType(List.class, int.class, int.class))
                    .asType(MethodType.methodType(List.class, Object.class, int.class, int.class));
            Class<?> events = Class.forName("com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector");
            Class<?> event = Class.forName("com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkProcessingEvent");
            listeners = MethodHandles.insertArguments(
                    lookup.findVirtual(events, "getAll", MethodType.methodType(java.util.ArrayList.class, Class.class))
                            .bindTo(events.getField("INSTANCE").get(null)), 0, event)
                    .asType(MethodType.methodType(List.class));
            air = wrapperFactory.getClass().getMethod("getAirBlockStateWrapper").invoke(wrapperFactory);
            lightStep = step.getField("LIGHT").get(null);
            fullyOpaque = Class.forName(core + "util.LodUtil").getField("BLOCK_FULLY_OPAQUE").getInt(null);
            available = true;
            if (CHECK) LOG.info("Every Distant Horizons tile this mod's column writer fills is also built by Distant Horizons' builder and compared");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
            LOG.info("This version of Distant Horizons lacks what this mod's column writer needs ({}); its own builder is used",
                    missing.toString());
        }
    }

    /** What is remembered about a block state for one tile: Distant Horizons' wrapper and two of its answers. */
    private record Block(Object wrapper, boolean air, boolean tintsBelow) {}

    /**
     * Writes the columns of one tile's chunks into the tile's data source and removes hidden data points.
     * The chunk wrappers are Distant Horizons', with its height maps made and its light baked.
     */
    static void fill(ChunkAccess[] chunks, List<Object> wrappers, Object tile) throws Throwable {
        IdentityHashMap<BlockState, Block> blocks = new IdentityHashMap<>();
        IdentityHashMap<Holder<Biome>, Object> biomes = new IdentityHashMap<>();
        Object mapping = (Object) mappingOf.invokeExact(tile);
        Object compression = (Object) compressionMode.invokeExact();
        boolean anything = false;
        for (int i = 0; i < chunks.length; i++) {
            anything |= fill(chunks[i], wrappers.get(i), tile, mapping, compression, blocks, biomes);
        }
        if (anything) setNotEmpty.invokeExact(tile, false);
        // Distant Horizons does this after every merge unless it is told to keep everything.
        if (!((Enum<?>) compression).name().equals("MERGE_SAME_BLOCKS")) {
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 64; z++) {
                    LongArrayList column = (LongArrayList) columnAt.invokeExact(tile, x, z);
                    if (column != null && column.size() > 1) cull.invokeExact(tile, x, z);
                }
            }
        }
    }

    private static boolean fill(ChunkAccess chunk, Object wrapper, Object tile, Object mapping, Object compression,
                                IdentityHashMap<BlockState, Block> blocks, IdentityHashMap<Holder<Biome>, Object> biomes) throws Throwable {
        int offsetX = (chunk.getPos().x & 3) << 4, offsetZ = (chunk.getPos().z & 3) << 4;
        int lowest = (int) minNonEmptyHeight.invokeExact(wrapper);
        int top = (int) exclusiveMaxHeight.invokeExact(wrapper);
        int bottom = (int) inclusiveMinHeight.invokeExact(wrapper);
        LevelChunkSection[] sections = chunk.getSections();
        int sectionBase = chunk.getMinBuildHeight();
        Block airBlock = new Block(air, true, false);
        boolean anything = false;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                LongArrayList points = (LongArrayList) columnAt.invokeExact(tile, x + offsetX, z + offsetZ);
                if (points == null) points = new LongArrayList((top - bottom) / 4);
                else points.clear();

                int lastY = top;
                Holder<Biome> biomeHere = chunk.getNoiseBiome(x >> 2, lastY >> 2, z >> 2);
                Object biome = biome(biomes, biomeHere, wrapper, x, lastY, z);
                Block block = airBlock;
                int id = (int) idFor.invokeExact(mapping, biome, block.wrapper);
                byte light = 0, sky = 15;

                int y = Math.max((int) lightBlockingHeight.invokeExact(wrapper, x, z), (int) solidHeight.invokeExact(wrapper, x, z));
                y = Math.max(bottom, Math.min(top - 1, y));
                // Up to open air: snow layers and the like block light without being in the height maps.
                BlockState state = state(sections, sectionBase, x, y, z);
                while (!block(blocks, state, wrapper, x, y, z).air && y < top) {
                    if (y + 1 >= top) break;
                    y++;
                    state = state(sections, sectionBase, x, y, z);
                }

                boolean single = false;
                BlockState lastState = null;
                Holder<Biome> lastBiome = null;
                Block next = block;
                Object nextBiome = biome;
                for (; y >= lowest; y--) {
                    state = state(sections, sectionBase, x, y, z);
                    if (state != lastState) {
                        next = block(blocks, state, wrapper, x, y, z);
                        lastState = state;
                    }
                    biomeHere = chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2);
                    if (biomeHere != lastBiome) {
                        nextBiome = biome(biomes, biomeHere, wrapper, x, y, z);
                        lastBiome = biomeHere;
                    }
                    if (single || !same(next.wrapper, block.wrapper) || !same(nextBiome, biome)) {
                        // A block that only tints what is under it (a flower, fire, a snow layer) gets a data point
                        // of its own, and so does the block right under it even if more of the same follows.
                        single = block.tintsBelow;
                        byte nextLight = (byte) (int) blockLight.invokeExact(wrapper, x, y + 1, z);
                        byte nextSky = (byte) (int) skyLight.invokeExact(wrapper, x, y + 1, z);
                        points.add((long) encode.invokeExact(id, lastY - y, y + 1 - bottom, light, sky));
                        block = next;
                        biome = nextBiome;
                        id = (int) idFor.invokeExact(mapping, biome, block.wrapper);
                        light = nextLight;
                        sky = nextSky;
                        lastY = y;
                        anything |= !block.air;
                    }
                }
                points.add((long) encode.invokeExact(id, lastY - y, y + 1 - bottom, light, sky));
                setColumn.invokeExact(tile, points, x + offsetX, z + offsetZ, lightStep, compression);
            }
        }
        return anything;
    }

    private static boolean same(Object a, Object b) {
        return a == b || a.equals(b);
    }

    private static BlockState state(LevelChunkSection[] sections, int base, int x, int y, int z) {
        return sections[(y - base) >> 4].getBlockState(x, y & 15, z);
    }

    private static Block block(IdentityHashMap<BlockState, Block> known, BlockState state, Object chunkWrapper, int x, int y, int z) throws Throwable {
        Block block = known.get(state);
        if (block == null) {
            Object wrapper = (Object) blockWrapper.invokeExact(chunkWrapper, x, y, z);
            boolean air = (boolean) isAir.invokeExact(wrapper);
            boolean tints = !air && !(boolean) isSolid.invokeExact(wrapper) && !(boolean) isLiquid.invokeExact(wrapper)
                    && (int) opacity.invokeExact(wrapper) != fullyOpaque;
            block = new Block(wrapper, air, tints);
            known.put(state, block);
        }
        return block;
    }

    private static Object biome(IdentityHashMap<Holder<Biome>, Object> known, Holder<Biome> biome, Object chunkWrapper, int x, int y, int z) throws Throwable {
        Object wrapper = known.get(biome);
        if (wrapper == null) {
            wrapper = (Object) biomeWrapper.invokeExact(chunkWrapper, x, y, z);
            known.put(biome, wrapper);
        }
        return wrapper;
    }

    /**
     * Compares a tile filled here with the same tile built by Distant Horizons, through its public column getter,
     * and returns how many columns differ.  With {@code count} false nothing is counted or logged: a chunk that
     * ticks (near a player, at the spawn) can change between the two builds, so a tile that differs is built
     * both ways again before the difference is believed.
     */
    static int compare(Object mine, Object reference, int tileX, int tileZ, boolean count) throws Throwable {
        int different = 0;
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                List<?> a = (List<?>) apiColumn.invokeExact(mine, x, z);
                List<?> b = (List<?>) apiColumn.invokeExact(reference, x, z);
                String difference = difference(a, b);
                if (difference != null) different++;
                if (!count) continue;
                COLUMNS_CHECKED.incrementAndGet();
                POINTS_CHECKED.addAndGet(b.size());
                if (difference != null) {
                    COLUMNS_DIFFERENT.incrementAndGet();
                    if (REPORTS.incrementAndGet() <= 20) {
                        LOG.warn("column writer differs from Distant Horizons in tile {},{} column {},{}: {}", tileX, tileZ, x, z, difference);
                    }
                }
            }
        }
        return different;
    }

    private static String difference(List<?> a, List<?> b) throws ReflectiveOperationException {
        if (a.size() != b.size()) {
            int first = 0;
            while (first < Math.min(a.size(), b.size()) && describe(a.subList(first, first + 1)).equals(describe(b.subList(first, first + 1)))) first++;
            return a.size() + " data points against " + b.size() + ", first difference at " + first + ": "
                    + describe(a.subList(Math.max(0, first - 1), Math.min(a.size(), first + 3))) + " | "
                    + describe(b.subList(Math.max(0, first - 1), Math.min(b.size(), first + 3)));
        }
        for (int i = 0; i < a.size(); i++) {
            Object p = a.get(i), q = b.get(i);
            for (String field : new String[]{"bottomYBlockPos", "topYBlockPos", "blockLightLevel", "skyLightLevel"}) {
                if (p.getClass().getField(field).getInt(p) != q.getClass().getField(field).getInt(q)) {
                    return "data point " + i + " " + field + ": " + describe(a) + " | " + describe(b);
                }
            }
            for (String field : new String[]{"blockStateWrapper", "biomeWrapper"}) {
                if (!Objects.equals(p.getClass().getField(field).get(p), q.getClass().getField(field).get(q))) {
                    return "data point " + i + " " + field + ": " + describe(a) + " | " + describe(b);
                }
            }
        }
        return null;
    }

    private static String describe(List<?> column) throws ReflectiveOperationException {
        StringBuilder text = new StringBuilder();
        for (Object p : column) {
            if (text.length() > 300) return text.append("...").toString();
            text.append('[').append(p.getClass().getField("bottomYBlockPos").getInt(p)).append("..")
                    .append(p.getClass().getField("topYBlockPos").getInt(p)).append(' ')
                    .append(p.getClass().getField("blockStateWrapper").get(p)).append(" l")
                    .append(p.getClass().getField("blockLightLevel").getInt(p)).append(" s")
                    .append(p.getClass().getField("skyLightLevel").getInt(p)).append(']');
        }
        return text.toString();
    }
}
