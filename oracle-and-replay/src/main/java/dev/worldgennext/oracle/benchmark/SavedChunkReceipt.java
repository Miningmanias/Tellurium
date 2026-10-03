// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.benchmark;

import java.util.Objects;

/**
 * Evidence for the SAVED endpoint.  A save request, an in-memory completion,
 * process close and a fresh-process reopen are separate facts; none is
 * inferred from the others.
 */
public record SavedChunkReceipt(String requestKey, String savePolicy,
                                boolean saveRequested, boolean saveCompleted,
                                boolean processClosed, boolean reopened,
                                long comparedFields, long mismatches, String detail) {
    public SavedChunkReceipt {
        requireText(requestKey, "requestKey");
        requireText(savePolicy, "savePolicy");
        if (comparedFields < 0 || mismatches < 0 || mismatches > comparedFields) {
            throw new IllegalArgumentException("Invalid saved comparison counts");
        }
        requireText(detail, "detail");
        if (saveCompleted && !saveRequested) throw new IllegalArgumentException("Save completion without a request");
        if (reopened && !processClosed) throw new IllegalArgumentException("Reopen evidence requires process close");
        if (comparedFields > 0 && !reopened) throw new IllegalArgumentException("Comparison evidence requires reopen");
    }

    public static SavedChunkReceipt pending(String requestKey, String savePolicy) {
        return new SavedChunkReceipt(requestKey, savePolicy, false, false, false, false, 0, 0,
                "save not requested");
    }

    public boolean verified() {
        return saveRequested && saveCompleted && processClosed && reopened
                && comparedFields > 0 && mismatches == 0;
    }

    public SavedChunkReceipt withComparison(boolean reopened, long comparedFields, long mismatches, String detail) {
        return new SavedChunkReceipt(requestKey, savePolicy, saveRequested, saveCompleted,
                processClosed, reopened, comparedFields, mismatches, detail);
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank");
    }
}
