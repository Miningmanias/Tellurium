// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.threading;

import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Block.isShapeFullBlock memoizes a pure shape test in a size-bounded Guava
 * cache.  Every read of that cache records an access and periodically takes a
 * segment lock, which contends when many worldgen threads place features that
 * test face shapes (glow lichen, sculk veins, snow).  The same pure function
 * is memoized here per thread in a small identity table instead.
 */
public final class ShapeFullBlockCache {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.shapeCache", "true"));
    private static final int SIZE = 1024;

    private static final class Table {
        final VoxelShape[] shapes = new VoxelShape[SIZE];
        final boolean[] full = new boolean[SIZE];
    }

    private static final ThreadLocal<Table> TABLE = ThreadLocal.withInitial(Table::new);

    private ShapeFullBlockCache() {}

    public static boolean isFullBlock(VoxelShape shape) {
        Table table = TABLE.get();
        int slot = (System.identityHashCode(shape) * 0x9E3779B9) >>> 22;
        if (table.shapes[slot] != shape) {
            table.full[slot] = !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME);
            table.shapes[slot] = shape;
        }
        return table.full[slot];
    }
}
