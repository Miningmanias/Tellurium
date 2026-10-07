// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.fast;

import dev.tellurium.neoforge.loader.Names;

import net.minecraft.world.level.biome.Climate;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exact replacement for Climate.RTree.search that exploits how BIOMES asks:
 * 96 lookups per quart column that differ only in the depth parameter.
 *
 * <p>The original search returns the leaf with the smallest squared distance
 * to the target; among equally near leaves it keeps the calling thread's
 * previous result if that is one of them, otherwise the first one in the
 * tree's depth-first order.  Squared distance is a sum over seven parameters,
 * so for a fixed column (all parameters but depth fixed) the leaves that share
 * one depth interval are ordered by the same six-parameter partial sum at every
 * Y.  Per column this index finds, for each distinct depth interval, the
 * smallest partial sum and the first leaf (depth-first) that attains it; a
 * lookup then compares one candidate per depth interval and applies the same
 * tie rule.  A column's first lookup is answered by a direct port of the
 * original search, so callers that do not repeat columns pay nothing extra.</p>
 */
public final class ColumnBiomeIndex {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.fast.biomeIndex", "true"));
    /** Diagnostic: answer every lookup with the ported original search as well and count differences. */
    public static final boolean VERIFY = Boolean.getBoolean("tellurium.fast.biomeIndexVerify");
    public static final AtomicLong lookups = new AtomicLong(), fastLookups = new AtomicLong(), mismatches = new AtomicLong();
    private static final int DEPTH = 4, DIMS = 7, SLOTS = 64, MAX_CLASSES = 32;

    // Flattened tree, nodes in depth-first order; node 0 is the root.
    private final long[] nodeMin, nodeMax;       // DIMS per node
    private final int[] childStart, childCount;   // into children[]; childCount 0 = leaf
    private final int[] children;
    private final int[] leafOf;                   // node -> leaf index or -1
    private final long[] nodeClassMask;           // depth classes present below the node
    private final int[] leafNode;                 // leaf -> node
    private final int[] leafClass;
    private final Object[] leafValue;
    private final long[] classMin, classMax;
    private final int classes;

    private final ThreadLocal<State> state = ThreadLocal.withInitial(State::new);

    private final class State {
        int last = -1;
        final long[] keys = new long[SLOTS * 5];
        final int[] uses = new int[SLOTS];
        final long[] bestPartial = new long[SLOTS * classes];
        final int[] bestLeaf = new int[SLOTS * classes];
        final long[] target = new long[DIMS];
        /** Best leaf per depth class of the column prepared last; neighbouring columns usually share them. */
        final int[] previousLeaf = new int[classes];
        boolean havePrevious;
        /** Lookups answered for the column used last; a caller that repeats columns is expected to do so again. */
        int currentSlot = -1, currentUses;
        long batchedLookups, batchedFast;
    }

    private ColumnBiomeIndex(List<long[]> mins, List<long[]> maxs, List<int[]> kids, List<Object> values) {
        int n = mins.size();
        nodeMin = new long[n * DIMS];
        nodeMax = new long[n * DIMS];
        childStart = new int[n];
        childCount = new int[n];
        leafOf = new int[n];
        int totalChildren = 0, leaves = 0;
        for (int i = 0; i < n; i++) {
            System.arraycopy(mins.get(i), 0, nodeMin, i * DIMS, DIMS);
            System.arraycopy(maxs.get(i), 0, nodeMax, i * DIMS, DIMS);
            totalChildren += kids.get(i).length;
            if (kids.get(i).length == 0) leaves++;
        }
        children = new int[totalChildren];
        leafNode = new int[leaves];
        leafClass = new int[leaves];
        leafValue = new Object[leaves];
        Map<Long, Map<Long, Integer>> classIds = new HashMap<>();
        List<long[]> classBounds = new ArrayList<>();
        int at = 0, leaf = 0;
        for (int i = 0; i < n; i++) {
            int[] k = kids.get(i);
            childStart[i] = at;
            childCount[i] = k.length;
            System.arraycopy(k, 0, children, at, k.length);
            at += k.length;
            if (k.length == 0) {
                long lo = nodeMin[i * DIMS + DEPTH], hi = nodeMax[i * DIMS + DEPTH];
                Integer id = classIds.computeIfAbsent(lo, ignored -> new HashMap<>()).get(hi);
                if (id == null) {
                    id = classBounds.size();
                    classBounds.add(new long[]{lo, hi});
                    classIds.get(lo).put(hi, id);
                }
                leafOf[i] = leaf;
                leafNode[leaf] = i;
                leafClass[leaf] = id;
                leafValue[leaf] = values.get(i);
                leaf++;
            } else {
                leafOf[i] = -1;
            }
        }
        classes = classBounds.size();
        if (classes > MAX_CLASSES) throw new IllegalStateException("too many distinct depth intervals: " + classes);
        classMin = new long[classes];
        classMax = new long[classes];
        for (int c = 0; c < classes; c++) {
            classMin[c] = classBounds.get(c)[0];
            classMax[c] = classBounds.get(c)[1];
        }
        nodeClassMask = new long[n];
        for (int i = n - 1; i >= 0; i--) { // children follow their parent in depth-first order
            if (childCount[i] == 0) {
                nodeClassMask[i] = 1L << leafClass[leafOf[i]];
            } else {
                long mask = 0;
                for (int c = 0; c < childCount[i]; c++) mask |= nodeClassMask[children[childStart[i] + c]];
                nodeClassMask[i] = mask;
            }
        }
    }

    // ------------------------------------------------------------------ construction by reflection
    /** Flattens the parameter list's search tree; returns null when it does not have the expected shape. */
    public static ColumnBiomeIndex build(Climate.ParameterList<?> list) {
        try {
            Object tree = field(Climate.ParameterList.class, "index").get(list);
            Object root = field(tree.getClass(), "root").get(tree);
            List<long[]> mins = new ArrayList<>(), maxs = new ArrayList<>();
            List<int[]> kids = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            flatten(root, mins, maxs, kids, values);
            return new ColumnBiomeIndex(mins, maxs, kids, values);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static int flatten(Object node, List<long[]> mins, List<long[]> maxs, List<int[]> kids, List<Object> values)
            throws ReflectiveOperationException {
        int index = mins.size();
        Climate.Parameter[] space = (Climate.Parameter[]) field(node.getClass().getSuperclass(), "parameterSpace").get(node);
        if (space.length != DIMS) throw new IllegalStateException("unexpected parameter count");
        long[] min = new long[DIMS], max = new long[DIMS];
        for (int d = 0; d < DIMS; d++) {
            min[d] = space[d].min();
            max[d] = space[d].max();
        }
        mins.add(min);
        maxs.add(max);
        kids.add(null);
        values.add(null);
        String kind = Names.simpleName(node.getClass());
        if (kind.equals("Leaf")) {
            kids.set(index, new int[0]);
            values.set(index, field(node.getClass(), "value").get(node));
        } else if (kind.equals("SubTree")) {
            Object array = field(node.getClass(), "children").get(node);
            int[] mine = new int[Array.getLength(array)];
            for (int i = 0; i < mine.length; i++) mine[i] = flatten(Array.get(array, i), mins, maxs, kids, values);
            if (mine.length == 0) throw new IllegalStateException("empty subtree");
            kids.set(index, mine);
        } else {
            throw new IllegalStateException("unknown node " + node.getClass().getName());
        }
        return index;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = Names.declaredField(owner, name);
        field.setAccessible(true);
        return field;
    }

    // ------------------------------------------------------------------ distances
    private static long axis(long value, long min, long max) {
        long above = value - max;
        long below = min - value;
        return above > 0 ? above : Math.max(below, 0L);
    }

    private long distance(int node, long[] target) {
        long sum = 0;
        int base = node * DIMS;
        for (int d = 0; d < DIMS; d++) {
            long a = axis(target[d], nodeMin[base + d], nodeMax[base + d]);
            sum += a * a;
        }
        return sum;
    }

    private long partial(int node, long[] target) {
        long sum = 0;
        int base = node * DIMS;
        for (int d = 0; d < DIMS; d++) {
            if (d == DEPTH) continue;
            long a = axis(target[d], nodeMin[base + d], nodeMax[base + d]);
            sum += a * a;
        }
        return sum;
    }

    // ------------------------------------------------------------------ the original search, ported
    /** Climate.RTree.SubTree.search over the flattened tree; {@code last} is a leaf index or -1. */
    private int originalSearch(long[] target, int last) {
        if (childCount[0] == 0) return leafOf[0];
        long best = last < 0 ? Long.MAX_VALUE : distance(leafNode[last], target);
        return descend(0, target, last, best);
    }

    private int descend(int node, long[] target, int leaf, long best) {
        // Callers pass the current best leaf and its distance, which is what the original recomputes on entry.
        int start = childStart[node], count = childCount[node];
        for (int c = 0; c < count; c++) {
            int child = children[start + c];
            long bound = distance(child, target);
            if (best > bound) {
                int found;
                long foundDistance;
                if (childCount[child] == 0) {
                    found = leafOf[child];
                    foundDistance = bound;
                } else {
                    found = descend(child, target, leaf, best);
                    foundDistance = found == leaf ? best : distance(leafNode[found], target);
                    if (found < 0) continue;
                }
                if (best > foundDistance) {
                    best = foundDistance;
                    leaf = found;
                }
            }
        }
        return leaf;
    }

    // ------------------------------------------------------------------ per-column candidates
    /** For every depth class: the smallest six-parameter partial sum and the first leaf in depth-first order attaining it. */
    private void columnCandidates(long[] target, long[] bestPartial, int[] bestLeaf, int offset, State s) {
        if (childCount[0] == 0) {
            for (int c = 0; c < classes; c++) {
                bestPartial[offset + c] = Long.MAX_VALUE;
                bestLeaf[offset + c] = -1;
            }
            bestPartial[offset + leafClass[0]] = partial(0, target);
            bestLeaf[offset + leafClass[0]] = 0;
            return;
        }
        for (int c = 0; c < classes; c++) {
            // A leaf of the class bounds the minimum.  Starting one above it keeps "first leaf in
            // depth-first order that attains the minimum": the traversal still has to find a leaf.
            int seed = s.havePrevious ? s.previousLeaf[c] : -1;
            bestPartial[offset + c] = seed < 0 ? Long.MAX_VALUE : partial(leafNode[seed], target) + 1;
            bestLeaf[offset + c] = -1;
        }
        collect(0, target, bestPartial, bestLeaf, offset);
        System.arraycopy(bestLeaf, offset, s.previousLeaf, 0, classes);
        s.havePrevious = true;
    }

    private void collect(int node, long[] target, long[] bestPartial, int[] bestLeaf, int offset) {
        int start = childStart[node], count = childCount[node];
        for (int c = 0; c < count; c++) {
            int child = children[start + c];
            long bound = partial(child, target);
            if (childCount[child] == 0) {
                int leaf = leafOf[child];
                int cls = offset + leafClass[leaf];
                if (bestPartial[cls] > bound) {
                    bestPartial[cls] = bound;
                    bestLeaf[cls] = leaf;
                }
            } else {
                // Worth descending only if the subtree holds a class whose best could still improve.
                long mask = nodeClassMask[child];
                boolean useful = false;
                while (mask != 0) {
                    int cls = Long.numberOfTrailingZeros(mask);
                    if (bestPartial[offset + cls] > bound) { useful = true; break; }
                    mask &= mask - 1;
                }
                if (useful) collect(child, target, bestPartial, bestLeaf, offset);
            }
        }
    }

    // ------------------------------------------------------------------ lookup
    public Object find(Climate.TargetPoint point) {
        State s = state.get();
        long[] target = s.target;
        target[0] = point.temperature();
        target[1] = point.humidity();
        target[2] = point.continentalness();
        target[3] = point.erosion();
        target[4] = point.depth();
        target[5] = point.weirdness();
        target[6] = 0L;
        long hash = target[0] * 0x9E3779B97F4A7C15L + target[1];
        hash = hash * 0x9E3779B97F4A7C15L + target[2];
        hash = hash * 0x9E3779B97F4A7C15L + target[3];
        hash = hash * 0x9E3779B97F4A7C15L + target[5];
        int slot = (int) (hash >>> 58) & (SLOTS - 1);
        int key = slot * 5;
        long[] keys = s.keys;
        boolean same = s.uses[slot] != 0 && keys[key] == target[0] && keys[key + 1] == target[1] && keys[key + 2] == target[2]
                && keys[key + 3] == target[3] && keys[key + 4] == target[5];
        int result;
        boolean direct = false;
        if (!same) {
            keys[key] = target[0];
            keys[key + 1] = target[1];
            keys[key + 2] = target[2];
            keys[key + 3] = target[3];
            keys[key + 4] = target[5];
            // If the previous column was asked more than once, expect this one to be as well and prepare it now.
            boolean prepare = s.currentUses >= 2;
            s.uses[slot] = prepare ? 2 : 1;
            s.currentSlot = slot;
            s.currentUses = 1;
            if (prepare) columnCandidates(target, s.bestPartial, s.bestLeaf, slot * classes, s);
            else direct = true;
        } else if (s.currentSlot != slot) {
            s.currentSlot = slot;
            s.currentUses = 2;
        } else {
            s.currentUses++;
        }
        if (direct) {
            result = originalSearch(target, s.last);
        } else {
            int offset = slot * classes;
            if (s.uses[slot] == 1) {
                s.uses[slot] = 2;
                columnCandidates(target, s.bestPartial, s.bestLeaf, offset, s);
            }
            long minimum = Long.MAX_VALUE;
            int first = -1;
            long depth = target[DEPTH];
            for (int c = 0; c < classes; c++) {
                int leaf = s.bestLeaf[offset + c];
                if (leaf < 0) continue;
                long a = axis(depth, classMin[c], classMax[c]);
                long total = s.bestPartial[offset + c] + a * a;
                if (total < minimum || (total == minimum && leaf < first)) {
                    minimum = total;
                    first = leaf;
                }
            }
            // The original keeps the previous result when it is as near as the nearest leaf.
            int last = s.last;
            result = last >= 0 && distance(leafNode[last], target) == minimum ? last : first;
            s.batchedFast++;
            if (VERIFY && result != originalSearch(target, s.last)) mismatches.incrementAndGet();
        }
        if (++s.batchedLookups >= 4096) {
            lookups.addAndGet(s.batchedLookups);
            fastLookups.addAndGet(s.batchedFast);
            s.batchedLookups = 0;
            s.batchedFast = 0;
        }
        s.last = result;
        return leafValue[result];
    }
}
