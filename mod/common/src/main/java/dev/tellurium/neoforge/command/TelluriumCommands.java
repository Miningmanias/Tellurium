// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.command;

import dev.tellurium.neoforge.config.TelluriumConfig;
import dev.tellurium.neoforge.runtime.NativeDependencyBootstrap;
import dev.tellurium.engine.worldgen.CoordinatorSnapshot;
import dev.tellurium.engine.worldgen.WorldgenCoordinator;
import dev.tellurium.neoforge.runtime.HookTelemetry;

/** Command-facing diagnostics kept separate from the loader event registration. */
public final class TelluriumCommands {
    private TelluriumCommands() {}

    /** Provider/evidence identity included in machine-readable operator status. */
    public record HookStatus(String status, String evidenceFile, String providerRoute,
                             String providerResultAbi, String providerCompilerVersion,
                             long qualifiedContexts, long qualifiedCases, long qualifiedFields,
                             boolean prototypeCpuLive, String prototypeProviderRoute) {
        public HookStatus(String status, String evidenceFile, String providerRoute,
                          String providerResultAbi, String providerCompilerVersion) {
            this(status, evidenceFile, providerRoute, providerResultAbi, providerCompilerVersion,
                    0, 0, 0, false, "NONE");
        }

        public HookStatus(String status, String evidenceFile, String providerRoute,
                          String providerResultAbi, String providerCompilerVersion,
                          long qualifiedContexts, long qualifiedCases, long qualifiedFields) {
            this(status, evidenceFile, providerRoute, providerResultAbi, providerCompilerVersion,
                    qualifiedContexts, qualifiedCases, qualifiedFields, false, "NONE");
        }

        public HookStatus {
            requireText(status, "status");
            requireText(evidenceFile, "evidenceFile");
            requireText(providerRoute, "providerRoute");
            requireText(providerResultAbi, "providerResultAbi");
            requireText(providerCompilerVersion, "providerCompilerVersion");
            requireText(prototypeProviderRoute, "prototypeProviderRoute");
            if (qualifiedContexts < 0 || qualifiedCases < 0 || qualifiedFields < 0) {
                throw new IllegalArgumentException("Qualification counters cannot be negative");
            }
        }

        public static HookStatus unavailable() {
            return new HookStatus("NOT_CONFIGURED", "", "NONE", "NONE", "NONE", 0, 0, 0,
                    false, "NONE");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(name + " must be a single-line value");
            }
        }
    }

    public static String status(TelluriumConfig config, NativeDependencyBootstrap nativeBootstrap, boolean qualifiedContext) {
        if (config == null || nativeBootstrap == null) throw new NullPointerException("config/nativeBootstrap");
        String failure = nativeBootstrap.failure();
        return "mode=" + config.mode() + "; hook=" + (config.enableQualifiedHook() && qualifiedContext ? "eligible" : "disabled")
                + "; native=" + nativeBootstrap.state() + "; initCalls=" + nativeBootstrap.initializationCalls()
                + "; reason=" + (qualifiedContext ? "qualified-context-required" : "no-qualified-context")
                + (failure.isBlank() ? "" : "; nativeFailure=" + singleLine(failure));
    }

    /**
     * Stable text view with the coordinator's live queue and reservation
     * dimensions.  These values are diagnostics, not qualification evidence;
     * a caller must not use a sampled snapshot as an admission decision.
     */
    public static String status(TelluriumConfig config, NativeDependencyBootstrap nativeBootstrap,
                                boolean qualifiedContext, WorldgenCoordinator coordinator) {
        return status(config, nativeBootstrap, qualifiedContext, coordinator, null);
    }

    public static String status(TelluriumConfig config, NativeDependencyBootstrap nativeBootstrap,
                                boolean qualifiedContext, WorldgenCoordinator coordinator,
                                HookTelemetry telemetry) {
        if (coordinator == null) throw new NullPointerException("coordinator");
        CoordinatorSnapshot snapshot = coordinator.snapshot();
        String result = status(config, nativeBootstrap, qualifiedContext)
                + "; coordinatorLifecycle=" + snapshot.lifecycle()
                + "; coordinatorAdmitting=" + snapshot.admitting()
                + "; coordinatorQueue=" + snapshot.queueDepth() + "/" + snapshot.queueCapacity()
                + "; coordinatorActive=" + snapshot.activeRecords()
                + "; coordinatorTerminal=" + snapshot.retainedTerminalRecords()
                + "; coordinatorReservedBytes=" + snapshot.reservedBytes() + "/" + snapshot.resourceBudgetBytes();
        if (telemetry != null) {
            HookTelemetry.Snapshot hooks = telemetry.snapshot();
            result += "; hookCalls=" + hooks.calls()
                    + "; hookBypass=" + hooks.bypasses()
                    + "; hookReplace=" + hooks.replacements()
                    + "; hookFail=" + hooks.failures()
                    + "; hookCompleted=" + hooks.completed()
                    + "; hookFutureFail=" + hooks.futureFailures()
                    + "; hookCancelled=" + hooks.cancellations();
        }
        return result;
    }

    /**
     * Machine-readable operator status.  The schema deliberately includes all
     * work-counter identities instead of deriving them from one another.
     */
    public static String statusJson(TelluriumConfig config, NativeDependencyBootstrap nativeBootstrap,
                                    boolean qualifiedContext, WorldgenCoordinator coordinator) {
        return statusJson(config, nativeBootstrap, qualifiedContext, coordinator, null, HookStatus.unavailable());
    }

    public static String statusJson(TelluriumConfig config, NativeDependencyBootstrap nativeBootstrap,
                                    boolean qualifiedContext, WorldgenCoordinator coordinator,
                                    HookTelemetry telemetry) {
        return statusJson(config, nativeBootstrap, qualifiedContext, coordinator, telemetry,
                HookStatus.unavailable());
    }

    public static String statusJson(TelluriumConfig config, NativeDependencyBootstrap nativeBootstrap,
                                    boolean qualifiedContext, WorldgenCoordinator coordinator,
                                    HookTelemetry telemetry, HookStatus hookStatus) {
        if (config == null || nativeBootstrap == null || coordinator == null) {
            throw new NullPointerException("config/nativeBootstrap/coordinator");
        }
        if (hookStatus == null) throw new NullPointerException("hookStatus");
        CoordinatorSnapshot snapshot = coordinator.snapshot();
        var counters = snapshot.counters();
        String failure = nativeBootstrap.failure();
        StringBuilder json = new StringBuilder("{\n")
                .append("  \"schemaVersion\":1,\n")
                .append("  \"mode\":").append(jsonQuote(config.mode().name())).append(",\n")
                .append("  \"qualifiedHookEnabled\":").append(config.enableQualifiedHook()).append(",\n")
                .append("  \"qualifiedEvidenceFile\":").append(jsonQuote(config.qualifiedEvidenceFile())).append(",\n")
                .append("  \"qualificationStatus\":").append(jsonQuote(hookStatus.status())).append(",\n")
                .append("  \"providerRoute\":").append(jsonQuote(hookStatus.providerRoute())).append(",\n")
                .append("  \"providerResultAbi\":").append(jsonQuote(hookStatus.providerResultAbi())).append(",\n")
                .append("  \"providerCompilerVersion\":").append(jsonQuote(hookStatus.providerCompilerVersion())).append(",\n")
                .append("  \"qualificationFile\":").append(jsonQuote(hookStatus.evidenceFile())).append(",\n")
                .append("  \"qualifiedContexts\":").append(hookStatus.qualifiedContexts()).append(",\n")
                .append("  \"qualifiedCases\":").append(hookStatus.qualifiedCases()).append(",\n")
                .append("  \"qualifiedFields\":").append(hookStatus.qualifiedFields()).append(",\n")
                .append("  \"prototypeCpuLive\":").append(hookStatus.prototypeCpuLive()).append(",\n")
                .append("  \"prototypeProviderRoute\":").append(jsonQuote(hookStatus.prototypeProviderRoute())).append(",\n")
                .append("  \"qualifiedContext\":").append(qualifiedContext).append(",\n")
                .append("  \"nativeState\":").append(jsonQuote(nativeBootstrap.state().name())).append(",\n")
                .append("  \"nativeInitializationCalls\":").append(nativeBootstrap.initializationCalls()).append(",\n")
                .append("  \"nativeFailure\":").append(jsonQuote(singleLine(failure))).append(",\n")
                .append("  \"coordinator\":{\n")
                .append("    \"lifecycle\":").append(jsonQuote(snapshot.lifecycle().name())).append(",\n")
                .append("    \"admitting\":").append(snapshot.admitting()).append(",\n")
                .append("    \"queueDepth\":").append(snapshot.queueDepth()).append(",\n")
                .append("    \"queueCapacity\":").append(snapshot.queueCapacity()).append(",\n")
                .append("    \"activeRecords\":").append(snapshot.activeRecords()).append(",\n")
                .append("    \"retainedTerminalRecords\":").append(snapshot.retainedTerminalRecords()).append(",\n")
                .append("    \"reservedBytes\":").append(snapshot.reservedBytes()).append(",\n")
                .append("    \"resourceBudgetBytes\":").append(snapshot.resourceBudgetBytes()).append(",\n")
                .append("    \"counters\":{");
        appendCounter(json, "requested", counters.requested());
        appendCounter(json, "rejected", counters.rejected());
        appendCounter(json, "cancelled", counters.cancelled());
        appendCounter(json, "failed", counters.failed());
        appendCounter(json, "stale", counters.stale());
        appendCounter(json, "uniqueWork", counters.uniqueWork());
        appendCounter(json, "backendAttempts", counters.backendAttempts());
        appendCounter(json, "gpuSubmitted", counters.gpuSubmitted());
        appendCounter(json, "gpuCompleted", counters.gpuCompleted());
        appendCounter(json, "validated", counters.validated());
        appendCounter(json, "committed", counters.committed());
        appendCounter(json, "cpuOwned", counters.cpuOwned());
        appendCounter(json, "originalStages", counters.originalStages());
        appendCounter(json, "recovery", counters.recovery());
        appendCounter(json, "comparedFields", counters.comparedFields());
        appendCounter(json, "mismatchedFields", counters.mismatchedFields());
        appendCounter(json, "fullCompleted", counters.fullCompleted());
        appendCounter(json, "saveBarrierCompleted", counters.saveBarrierCompleted());
        appendCounter(json, "reopenedVerified", counters.reopenedVerified());
        if (json.charAt(json.length() - 1) == ',') json.setLength(json.length() - 1);
        json.append("}\n  }");
        if (telemetry != null) {
            HookTelemetry.Snapshot hooks = telemetry.snapshot();
            json.append(",\n  \"telemetry\":{\n")
                    .append("    \"calls\":").append(hooks.calls()).append(",\n")
                    .append("    \"bypasses\":").append(hooks.bypasses()).append(",\n")
                    .append("    \"replacements\":").append(hooks.replacements()).append(",\n")
                    .append("    \"failures\":").append(hooks.failures()).append(",\n")
                    .append("    \"completed\":").append(hooks.completed()).append(",\n")
                    .append("    \"futureFailures\":").append(hooks.futureFailures()).append(",\n")
                    .append("    \"cancellations\":").append(hooks.cancellations()).append(",\n")
                    .append("    \"terminalFutureOutcomes\":").append(hooks.terminalFutureOutcomes()).append("\n  }");
        }
        return json.append("\n}\n").toString();
    }

    private static void appendCounter(StringBuilder json, String name, long value) {
        json.append('\n').append("      ").append(jsonQuote(name)).append(':').append(value).append(',');
    }

    private static String jsonQuote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace(';', ',');
    }
}
