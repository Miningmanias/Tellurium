// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.snapshot;

import dev.tellurium.neoforge.loader.Names;

import dev.tellurium.semantic.snapshot.BeardifierSnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class StructureBlendReader {
    public StructureBlendSnapshot requireCaptured(Object value) {
        if (value == null) return StructureBlendSnapshot.empty();
        if (value instanceof StructureBlendSnapshot snapshot) return snapshot;
        throw new IllegalArgumentException("Blend input must be captured before pure execution");
    }

    /**
     * Captures the immutable density samples owned by a live 1.21.1 Blender.
     * The adapter intentionally uses reflection only here because
     * {@code Blender.densityBlendingData} and
     * {@code BlendingData.iterateDensities} are private/protected in the
     * mapped game sources.  The resulting object contains no loader types.
     */
    public StructureBlendSnapshot capture(Object blender) {
        return capture(blender, BeardifierSnapshot.empty());
    }

    /** Capture blending samples and the already captured structure beardifier. */
    public StructureBlendSnapshot capture(Object blender, BeardifierSnapshot beardifier) {
        if (blender == null) return new StructureBlendSnapshot("none", Map.of(), Map.of(), beardifier, List.of());
        if (blender instanceof StructureBlendSnapshot snapshot) return snapshot;
        Object densityMap = field(blender, "densityBlendingData");
        if (!(densityMap instanceof Map<?, ?> entries)) {
            throw new IllegalArgumentException("Blender density data is not a map: "
                    + (densityMap == null ? "null" : densityMap.getClass().getName()));
        }
        var samples = new ArrayList<StructureBlendSnapshot.DensitySample>();
        var directSamples = new LinkedHashSet<StructureBlendSnapshot.DirectDensitySample>();
        var heightSamples = new ArrayList<StructureBlendSnapshot.HeightSample>();
        var directHeightSamples = new LinkedHashSet<StructureBlendSnapshot.DirectHeightSample>();
        for (var entry : entries.entrySet()) {
            if (!(entry.getKey() instanceof Number key) || entry.getValue() == null) {
                throw new IllegalArgumentException("Malformed Blender density entry");
            }
            // ChunkPos.asLong stores the signed X/Z section coordinates in
            // the low/high 32-bit halves.  Decode the stable wire layout
            // here so the pure unit tests do not need a game runtime.
            int sectionX = (int) (key.longValue() & 0xffff_ffffL);
            int sectionZ = (int) (key.longValue() >>> 32);
            captureDensities(entry.getValue(), Math.multiplyExact(sectionX, 4),
                    Math.multiplyExact(sectionZ, 4), samples);
            captureDirectDensities(entry.getValue(), sectionX, sectionZ, directSamples);
        }
        Object heightMap = optionalField(blender, "heightAndBiomeBlendingData");
        boolean hasHeightAndBiomeData;
        if (heightMap == null) {
            hasHeightAndBiomeData = false;
        } else if (heightMap instanceof Map<?, ?> heightEntries) {
            hasHeightAndBiomeData = !heightEntries.isEmpty();
            // Blender's direct getBlendingDataValue lookup intentionally uses
            // the broad height/biome map for all cell getters, including
            // density.  Capture those source-local values as well; the
            // density map is narrower and cannot stand in for this precedence
            // when the requested quart boundary is outside its radius.
            for (var entry : heightEntries.entrySet()) {
                if (!(entry.getKey() instanceof Number key) || entry.getValue() == null) {
                    throw new IllegalArgumentException("Malformed Blender height/biome entry");
                }
                int sectionX = (int) (key.longValue() & 0xffff_ffffL);
                int sectionZ = (int) (key.longValue() >>> 32);
                captureHeights(entry.getValue(), Math.multiplyExact(sectionX, 4),
                        Math.multiplyExact(sectionZ, 4), heightSamples);
                captureDirectDensities(entry.getValue(), sectionX, sectionZ, directSamples);
                captureDirectHeights(entry.getValue(), sectionX, sectionZ, directHeightSamples);
            }
        } else {
            throw new IllegalArgumentException("Blender height/biome data is not a map: "
                    + heightMap.getClass().getName());
        }
        return new StructureBlendSnapshot(
                "blender:" + blender.getClass().getName(), Map.of(), Map.of(), beardifier, samples,
                List.copyOf(directSamples), heightSamples, List.copyOf(directHeightSamples),
                hasHeightAndBiomeData);
    }

    private static void captureHeights(Object blendingData, int baseQuartX, int baseQuartZ,
                                       List<StructureBlendSnapshot.HeightSample> samples) {
        Method iterate = method(blendingData.getClass(), "iterateHeights", 3);
        Class<?> consumerType = iterate.getParameterTypes()[2];
        Object consumer = Proxy.newProxyInstance(
                consumerType.getClassLoader() == null ? StructureBlendReader.class.getClassLoader() : consumerType.getClassLoader(),
                new Class<?>[]{consumerType}, (proxy, called, arguments) -> {
                    if (Names.isNamed(called, consumerType, "consume") && arguments != null && arguments.length == 3) {
                        samples.add(new StructureBlendSnapshot.HeightSample(
                                ((Number) arguments[0]).intValue(), ((Number) arguments[1]).intValue(),
                                ((Number) arguments[2]).doubleValue()));
                    }
                    return null;
                });
        try {
            iterate.invoke(blendingData, baseQuartX, baseQuartZ, consumer);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot capture Blender height samples", failure);
        }
    }

    private static void captureDensities(Object blendingData, int baseQuartX, int baseQuartZ,
                                         List<StructureBlendSnapshot.DensitySample> samples) {
        Method iterate = method(blendingData.getClass(), "iterateDensities", 5);
        Class<?> consumerType = iterate.getParameterTypes()[4];
        Object consumer = Proxy.newProxyInstance(
                consumerType.getClassLoader() == null ? StructureBlendReader.class.getClassLoader() : consumerType.getClassLoader(),
                new Class<?>[]{consumerType}, (proxy, called, arguments) -> {
                    if (Names.isNamed(called, consumerType, "consume") && arguments != null && arguments.length == 4) {
                        samples.add(new StructureBlendSnapshot.DensitySample(
                                ((Number) arguments[0]).intValue(), ((Number) arguments[1]).intValue(),
                                ((Number) arguments[2]).intValue(), ((Number) arguments[3]).doubleValue()));
                    }
                    return null;
                });
        try {
            iterate.invoke(blendingData, baseQuartX, baseQuartZ, -1_000_000_000, 1_000_000_000, consumer);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot capture Blender density samples", failure);
        }
    }

    /** Capture the source-local direct lookup table used before weighted blending. */
    private static void captureDirectDensities(Object blendingData, int sectionX, int sectionZ,
                                               java.util.Set<StructureBlendSnapshot.DirectDensitySample> samples) {
        Method getDensity;
        Method minYMethod;
        Method countMethod;
        try {
            getDensity = method(blendingData.getClass(), "getDensity", 3);
            minYMethod = method(blendingData.getClass(), "getMinY", 0);
            countMethod = method(blendingData.getClass(), "cellCountPerColumn", 0);
        } catch (IllegalArgumentException unavailable) {
            // A test double or an older mapped object may expose only the
            // iterator.  Weighted capture remains useful, but the caller must
            // not mistake it for complete direct lookup coverage.
            return;
        }
        try {
            int minY = ((Number) minYMethod.invoke(blendingData)).intValue();
            int cellCount = ((Number) countMethod.invoke(blendingData)).intValue();
            if (cellCount < 0 || cellCount > 4096) {
                throw new IllegalArgumentException("Invalid Blender cell count: " + cellCount);
            }
            final int maxY;
            try {
                maxY = Math.addExact(minY, cellCount);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Invalid Blender Y range: minY=" + minY
                        + ", cellCount=" + cellCount, overflow);
            }
            for (int localX = 0; localX <= 4; localX++) {
                for (int localZ = 0; localZ <= 4; localZ++) {
                    for (int cellY = minY; ; cellY++) {
                        double value = ((Number) getDensity.invoke(blendingData, localX, cellY, localZ)).doubleValue();
                        if (value != Double.MAX_VALUE) {
                            samples.add(new StructureBlendSnapshot.DirectDensitySample(
                                    sectionX, sectionZ, localX, cellY, localZ, value));
                        }
                        if (cellY == maxY) break;
                    }
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot capture direct Blender density samples", failure);
        }
    }

    /** Capture source-local height values used by Blender's direct lookup. */
    private static void captureDirectHeights(Object blendingData, int sectionX, int sectionZ,
                                             java.util.Set<StructureBlendSnapshot.DirectHeightSample> samples) {
        Method getHeight;
        try {
            getHeight = method(blendingData.getClass(), "getHeight", 3);
        } catch (IllegalArgumentException unavailable) {
            throw new IllegalArgumentException("Cannot capture direct Blender height lookup", unavailable);
        }
        try {
            for (int localX = 0; localX <= 4; localX++) {
                for (int localZ = 0; localZ <= 4; localZ++) {
                    double value = ((Number) getHeight.invoke(blendingData, localX, 0, localZ)).doubleValue();
                    if (value != Double.MAX_VALUE) {
                        samples.add(new StructureBlendSnapshot.DirectHeightSample(
                                sectionX, sectionZ, localX, localZ, value));
                    }
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot capture direct Blender height lookup", failure);
        }
    }

    private static Method method(Class<?> start, String name, int parameterCount) {
        Class<?> type = start;
        while (type != null) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (Names.isNamed(candidate, type, name) && candidate.getParameterCount() == parameterCount) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
            type = type.getSuperclass();
        }
        throw new IllegalArgumentException("Cannot find " + name + " on " + start.getName());
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = Names.declaredField(type, name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("Cannot read " + name + " from " + target.getClass().getName(), failure);
            }
        }
        throw new IllegalArgumentException("Cannot find " + name + " on " + target.getClass().getName());
    }

    private static Object optionalField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var field = Names.declaredField(type, name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("Cannot read " + name + " from " + target.getClass().getName(), failure);
            }
        }
        return null;
    }
}
