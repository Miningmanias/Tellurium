// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Static fail-closed product configuration. AUTO is a fixed policy, not a learned estimator. */
public record TelluriumConfig(Mode mode, long hostBudgetBytes, long nativeBudgetBytes,
                                 int queueCapacity, Duration requestTimeout, boolean enableQualifiedHook,
                                 String qualifiedEvidenceFile) {
    /** Backwards-compatible constructor for callers that do not provide an evidence file. */
    public TelluriumConfig(Mode mode, long hostBudgetBytes, long nativeBudgetBytes,
                              int queueCapacity, Duration requestTimeout, boolean enableQualifiedHook) {
        this(mode, hostBudgetBytes, nativeBudgetBytes, queueCapacity, requestTimeout,
                enableQualifiedHook, "");
    }

    public enum Mode {
        CPU_ONLY, GPU_REQUIRED, AUTO_SUPPORTED;

        /** Parses operator-facing values while retaining one canonical enum. */
        public static Mode fromExternal(String value) {
            Objects.requireNonNull(value, "value");
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (normalized.equals("AUTO")) normalized = AUTO_SUPPORTED.name();
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Unknown Tellurium mode: " + value, failure);
            }
        }
    }
    public TelluriumConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(qualifiedEvidenceFile, "qualifiedEvidenceFile");
        qualifiedEvidenceFile = qualifiedEvidenceFile.trim();
        if (hostBudgetBytes <= 0 || nativeBudgetBytes <= 0 || queueCapacity <= 0 || requestTimeout.isNegative() || requestTimeout.isZero()) throw new IllegalArgumentException("Invalid Tellurium resource limits");
        try {
            // The properties format is millisecond-based. Preserve a usable
            // positive deadline for finer-grained callers and reject a
            // duration that cannot be represented before it can reach a
            // runtime lifecycle operation.
            requestTimeout = Duration.ofMillis(Math.max(1L, requestTimeout.toMillis()));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Request timeout is too large", overflow);
        }
    }
    public static TelluriumConfig defaults() { return new TelluriumConfig(Mode.AUTO_SUPPORTED, 256L * 1024 * 1024, 256L * 1024 * 1024, 64, Duration.ofSeconds(30), false, ""); }
    public static TelluriumConfig from(Properties properties) {
        Properties values = properties == null ? new Properties() : properties;
        Mode mode = Mode.fromExternal(values.getProperty("mode", defaults().mode().name()));
        return new TelluriumConfig(mode, longValue(values, "hostBudgetBytes", defaults().hostBudgetBytes()), longValue(values, "nativeBudgetBytes", defaults().nativeBudgetBytes()), intValue(values, "queueCapacity", defaults().queueCapacity()), Duration.ofMillis(longValue(values, "requestTimeoutMillis", defaults().requestTimeout().toMillis())), booleanValue(values, "enableQualifiedHook", defaults().enableQualifiedHook()), values.getProperty("qualifiedEvidenceFile", defaults().qualifiedEvidenceFile()));
    }
    public Properties toProperties() { var values = new Properties(); values.setProperty("mode", mode.name()); values.setProperty("hostBudgetBytes", Long.toString(hostBudgetBytes)); values.setProperty("nativeBudgetBytes", Long.toString(nativeBudgetBytes)); values.setProperty("queueCapacity", Integer.toString(queueCapacity)); values.setProperty("requestTimeoutMillis", Long.toString(requestTimeout.toMillis())); values.setProperty("enableQualifiedHook", Boolean.toString(enableQualifiedHook)); values.setProperty("qualifiedEvidenceFile", qualifiedEvidenceFile); return values; }
    private static long longValue(Properties values, String key, long fallback) { try { return Long.parseLong(values.getProperty(key, Long.toString(fallback))); } catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid " + key, failure); } }
    private static int intValue(Properties values, String key, int fallback) { try { return Integer.parseInt(values.getProperty(key, Integer.toString(fallback))); } catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid " + key, failure); } }
    private static boolean booleanValue(Properties values, String key, boolean fallback) {
        String value = values.getProperty(key, Boolean.toString(fallback));
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Invalid " + key + ": expected true or false");
    }
}
