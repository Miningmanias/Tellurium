// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import java.util.Objects;
import java.util.function.Function;

/** Entry bounds, not a byte budget for opaque driver allocations. */
public record PipelineCacheLimits(int ordinaryEntries, int reusableEntries) {
    public static final String ORDINARY_PROPERTY = "tellurium.vulkan.pipelineCacheEntries";
    public static final String REUSABLE_PROPERTY = "tellurium.vulkan.reusablePipelineCacheEntries";
    public static final PipelineCacheLimits DEFAULT = new PipelineCacheLimits(8, 32);
    public static final int MAX_ORDINARY_ENTRIES = 256;
    public static final int MAX_REUSABLE_ENTRIES = 128;

    public PipelineCacheLimits {
        if (ordinaryEntries < 1 || ordinaryEntries > MAX_ORDINARY_ENTRIES
                || reusableEntries < 1 || reusableEntries > MAX_REUSABLE_ENTRIES) {
            throw new IllegalArgumentException("Native pipeline cache entries must be ordinary=1..256, reusable=1..128");
        }
    }

    /** Invalid experimental options fail before device initialization. Defaults are unchanged. */
    public static PipelineCacheLimits configured() {
        return fromProperties(System::getProperty);
    }

    public static PipelineCacheLimits fromProperties(Function<String, String> properties) {
        Objects.requireNonNull(properties, "properties");
        return new PipelineCacheLimits(read(properties, ORDINARY_PROPERTY, DEFAULT.ordinaryEntries),
                read(properties, REUSABLE_PROPERTY, DEFAULT.reusableEntries));
    }

    private static int read(Function<String, String> properties, String key, int fallback) {
        String value = properties.apply(key);
        if (value == null) return fallback;
        if (!value.matches("[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException(key + " requires a canonical positive entry count");
        }
        return Integer.parseInt(value);
    }
}
