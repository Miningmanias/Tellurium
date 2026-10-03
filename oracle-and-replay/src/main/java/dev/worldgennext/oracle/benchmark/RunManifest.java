// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.benchmark;

import java.util.List;
import java.util.HashSet;

public record RunManifest(String workloadId, String backend, String endpoint, List<String> chunks,
                          int coldLaunches, int warmRuns) {
    public RunManifest {
        if (workloadId == null || workloadId.isBlank() || backend == null || backend.isBlank()
                || endpoint == null || endpoint.isBlank() || coldLaunches < 1 || warmRuns < 1) {
            throw new IllegalArgumentException("Invalid benchmark manifest");
        }
        chunks = List.copyOf(chunks == null ? List.of() : chunks);
        if (chunks.isEmpty() || chunks.stream().anyMatch(chunk -> chunk == null || chunk.isBlank())) {
            throw new IllegalArgumentException("Benchmark chunks must be nonempty names");
        }
        if (new HashSet<>(chunks).size() != chunks.size()) {
            throw new IllegalArgumentException("Benchmark chunks must be unique");
        }
    }
}
