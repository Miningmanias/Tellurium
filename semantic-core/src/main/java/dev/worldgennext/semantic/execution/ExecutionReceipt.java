// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.execution;

import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import java.util.Objects;

/** Runtime-issued execution provenance. A caller cannot label a CPU result as GPU by enum alone. */
public record ExecutionReceipt(String executionId, String backend, ContextIdentity context,
                               NumericProfile numericProfile, String programHash, String abiVersion,
                               String shaderHash, String spirvHash,
                               long deviceGeneration, long submitted, long completed,
                               long validated, long committed) {
    /** Marker used by CPU and non-compiling lifecycle seams where shader artifacts do not apply. */
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    public ExecutionReceipt {
        requireText(executionId, "executionId"); requireText(backend, "backend"); Objects.requireNonNull(context, "context");
        Objects.requireNonNull(numericProfile, "numericProfile"); requireText(programHash, "programHash"); requireText(abiVersion, "abiVersion");
        requireText(shaderHash, "shaderHash"); requireText(spirvHash, "spirvHash");
        if (deviceGeneration < 0 || submitted < 0 || completed < 0 || validated < 0 || committed < 0) throw new IllegalArgumentException("Negative receipt count");
        if (completed > submitted || validated > completed || committed > validated) throw new IllegalArgumentException("Receipt phase order violated");
    }
    public static ExecutionReceipt issued(String id, String backend, ContextIdentity context,
                                          NumericProfile profile, String programHash, String abiVersion,
                                          long deviceGeneration, long submitted) {
        return issued(id, backend, context, profile, programHash, abiVersion,
                NOT_APPLICABLE, NOT_APPLICABLE, deviceGeneration, submitted);
    }
    /** Issues a receipt with the exact compiled artifacts used by a GPU dispatch. */
    public static ExecutionReceipt issued(String id, String backend, ContextIdentity context,
                                          NumericProfile profile, String programHash, String abiVersion,
                                          String shaderHash, String spirvHash,
                                          long deviceGeneration, long submitted) {
        return new ExecutionReceipt(id, backend, context, profile, programHash, abiVersion,
                shaderHash, spirvHash, deviceGeneration, submitted, 0, 0, 0);
    }
    /** Adds immutable GPU artifact provenance after compilation, without changing execution identity. */
    public ExecutionReceipt withShaderProvenance(String shaderHash, String spirvHash) {
        return new ExecutionReceipt(executionId, backend, context, numericProfile, programHash, abiVersion,
                shaderHash, spirvHash, deviceGeneration, submitted, completed, validated, committed);
    }
    /** True only when this receipt carries both artifacts needed to audit a GPU dispatch. */
    public boolean hasCompiledArtifactProvenance() {
        return !NOT_APPLICABLE.equals(shaderHash) && !NOT_APPLICABLE.equals(spirvHash);
    }
    public ExecutionReceipt completed(long count) { return new ExecutionReceipt(executionId, backend, context, numericProfile, programHash, abiVersion, shaderHash, spirvHash, deviceGeneration, submitted, count, 0, 0); }
    public ExecutionReceipt validated(long count) { return new ExecutionReceipt(executionId, backend, context, numericProfile, programHash, abiVersion, shaderHash, spirvHash, deviceGeneration, submitted, completed, count, 0); }
    public ExecutionReceipt committed(long count) { return new ExecutionReceipt(executionId, backend, context, numericProfile, programHash, abiVersion, shaderHash, spirvHash, deviceGeneration, submitted, completed, validated, count); }
    private static void requireText(String value, String name) { if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank"); }
}
