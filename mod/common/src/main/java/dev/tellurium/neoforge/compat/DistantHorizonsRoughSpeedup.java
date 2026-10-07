// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.compat;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Makes Distant Horizons' rough surface generator cheaper without changing what it produces.
 *
 * <p>For terrain far from the player Distant Horizons does not generate chunks: its rough generator finds the
 * surface of every other column by evaluating the world generator's final density down the column, a step at
 * a time and then by halving, some fifty evaluations a column.  It evaluates the plain function, in which the
 * parts that depend only on x and z (continents, erosion, ridges and the splines over them: most of the cost)
 * are worked out again at every height.  In a client that was the largest single use of the processor while
 * Distant Horizons filled its view, more than chunk generation itself.</p>
 *
 * <p>The game marks those parts in the function ({@code cache_2d}, {@code flat_cache}); inside chunk generation
 * it caches them per column, outside it the marks do nothing.  Here the function the rough generator holds is
 * replaced by the same function with each such mark remembering its last column per thread.  A marked part is
 * only treated so if it gives the same value at different heights on a set of test columns; anything else is
 * left exactly as it was.  The values, and so the surface found, are the same.</p>
 */
final class DistantHorizonsRoughSpeedup {
    private static final Logger LOG = LoggerFactory.getLogger("tellurium");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.dh.roughColumnCache", "true"));

    private DistantHorizonsRoughSpeedup() {}

    /** Applies it to the rough generator of one of Distant Horizons' world generators; does nothing if anything is not as expected. */
    static void apply(Object dhWorldGenerator, String dimension) {
        if (!ENABLED) return;
        try {
            Object rough = dhWorldGenerator.getClass().getField("roughGenerator").get(dhWorldGenerator);
            if (rough == null) return;
            Field parametersField = field(rough.getClass(), "genParams");
            Object parameters = parametersField.get(rough);
            Field densityField = field(parameters.getClass(), "density");
            if (!(densityField.get(parameters) instanceof DensityFunction density) || density instanceof ColumnMemo) return;
            int[] wrapped = new int[2];
            DensityFunction faster = density.mapAll(new DensityFunction.Visitor() {
                @Override
                public DensityFunction apply(DensityFunction function) {
                    if (!(function instanceof DensityFunctions.MarkerOrMarked marker) || function instanceof ColumnMemo) return function;
                    String type = String.valueOf((Object) marker.type());
                    if (!type.equals("Cache2D") && !type.equals("FlatCache")) return function;
                    if (!independentOfHeight(marker.wrapped()) || !sameAtEveryHeight(marker.wrapped())) {
                        wrapped[1]++;
                        return function;
                    }
                    wrapped[0]++;
                    return new ColumnMemo(marker.wrapped());
                }
            });
            if (wrapped[0] == 0) return;
            densityField.set(parameters, faster);
            LOG.info("Distant Horizons' rough surface generator for {}: {} per-column parts of the density function are worked out once a column"
                    + " instead of at every height{}", dimension, wrapped[0], wrapped[1] == 0 ? "" : " (" + wrapped[1] + " left alone: they vary with height)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unexpected) {
            LOG.debug("Distant Horizons' rough surface generator left as it is", unexpected);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException next) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Node kinds whose value follows from their children's alone, and leaves that do not read the height. */
    private static final java.util.Set<String> HEIGHT_FREE = java.util.Set.of("Constant", "BlendAlpha", "BlendOffset", "ShiftA", "ShiftB",
            "Clamp", "Mapped", "MulOrAdd", "Ap2", "RangeChoice", "Spline", "HolderHolder", "Marker");

    /**
     * Whether nothing in a part can read the height: every node is of a kind known not to, and every noise in
     * it is sampled with a vertical scale of zero.  This is what allows the memo; a function of a kind not
     * listed here (a mod's own, a height gradient, a 3D noise) keeps its part as it was, however equal its
     * values look at the heights {@link #sameAtEveryHeight} tries.
     */
    private static boolean independentOfHeight(DensityFunction part) {
        boolean[] free = {true};
        try {
            part.mapAll(new DensityFunction.Visitor() {
                @Override
                public DensityFunction apply(DensityFunction node) {
                    if (!free[0]) return node;
                    String kind = dev.tellurium.neoforge.loader.Names.simpleName(node.getClass());
                    if (kind.equals("Noise") || kind.equals("ShiftedNoise")) {
                        try {
                            var scale = dev.tellurium.neoforge.loader.Names.publicMethod(node.getClass(), "yScale");
                            if (!scale.canAccess(node)) scale.setAccessible(true);
                            if (((Number) scale.invoke(node)).doubleValue() != 0.0) free[0] = false;
                        } catch (ReflectiveOperationException | RuntimeException unknown) {
                            free[0] = false;
                        }
                    } else if (!HEIGHT_FREE.contains(kind)) {
                        free[0] = false;
                    }
                    return node;
                }
            });
        } catch (RuntimeException unexpected) {
            return false;
        }
        return free[0];
    }

    /** A second look, by trying: whether a part gives one value down each of a set of columns spread over a wide area. */
    private static boolean sameAtEveryHeight(DensityFunction part) {
        long seed = 0x9E3779B97F4A7C15L;
        for (int column = 0; column < 24; column++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int x = (int) (seed >> 40), z = (int) (seed << 24 >> 40);
            double first = part.compute(new DensityFunction.SinglePointContext(x, -64, z));
            for (int y : new int[]{-17, 31, 63, 64, 100, 197, 319}) {
                double other = part.compute(new DensityFunction.SinglePointContext(x, y, z));
                if (Double.doubleToRawLongBits(other) != Double.doubleToRawLongBits(first)) return false;
            }
        }
        return true;
    }

    /** A part of the density function that depends only on x and z, remembering its last column for each thread. */
    private static final class ColumnMemo implements DensityFunction {
        private static final class Last {
            int x, z;
            boolean set;
            double value;
        }

        private final DensityFunction part;
        private final ThreadLocal<Last> last = ThreadLocal.withInitial(Last::new);

        ColumnMemo(DensityFunction part) {
            this.part = part;
        }

        @Override
        public double compute(FunctionContext context) {
            Last last = this.last.get();
            int x = context.blockX(), z = context.blockZ();
            if (last.set && last.x == x && last.z == z) return last.value;
            double value = part.compute(context);
            last.x = x;
            last.z = z;
            last.value = value;
            last.set = true;
            return value;
        }

        @Override
        public void fillArray(double[] values, ContextProvider provider) {
            provider.fillAllDirectly(values, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new ColumnMemo(part.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return part.minValue();
        }

        @Override
        public double maxValue() {
            return part.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return part.codec();
        }
    }
}
