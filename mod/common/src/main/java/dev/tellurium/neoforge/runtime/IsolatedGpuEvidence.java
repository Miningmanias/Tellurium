// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.material.chunk.ChunkResultCodec;
import dev.tellurium.semantic.execution.ExecutionReceipt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Verification artifacts only: a device completion is never labelled a Minecraft commit. */
final class IsolatedGpuEvidence {
    private IsolatedGpuEvidence() { }

    static void writeBackend(int x, int z, ChunkNoiseResult result, ExecutionReceipt receipt,
                             MinecraftGpuCandidate.DeviceGeneration generation) {
        Path root = root();
        if (root == null) return;
        try {
            Files.createDirectories(root);
            String stem = "chunk-" + x + "-" + z;
            Files.write(root.resolve(stem + ".chunk"), ChunkResultCodec.encode(result), StandardOpenOption.CREATE_NEW);
            Files.writeString(root.resolve(stem + ".gpu-receipt.json"),
                    backendJson(x, z, result.logicalChecksum(), receipt, generation), StandardOpenOption.CREATE_NEW);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write isolated GPU backend evidence for " + x + "," + z, failure);
        }
    }

    static void writeTerminal(int x, int z, ExecutionReceipt receipt, Throwable failure) {
        Path root = root();
        if (root == null) return;
        if (failure == null && receipt == null) {
            throw new IllegalStateException("GPU endpoint completed without a backend execution receipt");
        }
        try {
            Files.createDirectories(root);
            String stem = "chunk-" + x + "-" + z;
            Files.writeString(root.resolve(stem + ".gpu-commit.json"),
                    terminalJson(x, z, receipt, failure), StandardOpenOption.CREATE_NEW);
            Files.writeString(root.resolve(stem + ".candidate-status"),
                    failure == null ? "PASS\nCOMMITTED\n" : "FAIL\n" + detail(failure) + "\n",
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException statusFailure) {
            throw new IllegalStateException("Cannot write isolated GPU terminal evidence for " + x + "," + z, statusFailure);
        }
    }

    static String backendJson(int x, int z, String checksum, ExecutionReceipt receipt,
                              MinecraftGpuCandidate.DeviceGeneration generation) {
        if (receipt.committed() != 0 || receipt.submitted() <= 0
                || receipt.completed() != receipt.submitted() || receipt.validated() != receipt.completed()
                || !receipt.hasCompiledArtifactProvenance()
                || !receipt.backend().equals("gpu")
                || !receipt.abiVersion().equals(MinecraftGpuNoiseProvider.RESULT_ABI)
                || !receipt.shaderHash().matches("[0-9a-fA-F]{64}")
                || !receipt.spirvHash().matches("[0-9a-fA-F]{64}")
                || receipt.numericProfile() != dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS
                || generation.numericProfile() != receipt.numericProfile()
                || !generation.shaderHash().equals(receipt.shaderHash())
                || !generation.spirvHash().equals(receipt.spirvHash())
                || generation.logicalGpuElements() <= 0 || generation.logicalGpuElements() > receipt.submitted()) {
            throw new IllegalArgumentException("Incomplete or prematurely committed GPU backend evidence");
        }
        var device = generation.device();
        return "{\n  \"schemaVersion\":1,\n  \"kind\":\"tellurium_gpu_live_receipt\",\n"
                + "  \"status\":\"BACKEND_VALIDATED\",\n  \"route\":\"GPU_IEEE_BITS\",\n"
                + identityJson(x, z, receipt)
                + "  \"compilerVersion\":" + quote(MinecraftGpuNoiseProvider.COMPILER_VERSION) + ",\n"
                + "  \"submitted\":" + receipt.submitted() + ",\n  \"completed\":" + receipt.completed() + ",\n"
                + "  \"validated\":" + receipt.validated() + ",\n  \"committed\":0,\n"
                + "  \"logicalGpuElements\":" + generation.logicalGpuElements() + ",\n"
                + "  \"storageBlocks\":" + receipt.submitted() + ",\n"
                + "  \"resultChecksum\":" + quote(checksum) + ",\n"
                + "  \"shaderHash\":" + quote(receipt.shaderHash()) + ",\n"
                + "  \"spirvHash\":" + quote(receipt.spirvHash()) + ",\n"
                + "  \"device\":{\"name\":" + quote(device.name())
                + ",\"apiVersion\":" + quote(Integer.toUnsignedString(device.apiVersion()))
                + ",\"driverVersion\":" + quote(Integer.toUnsignedString(device.driverVersion())) + "},\n"
                + "  \"independentParityCompared\":false,\n  \"releaseQualification\":false\n}\n";
    }

    static String terminalJson(int x, int z, ExecutionReceipt receipt, Throwable failure) {
        if (failure == null && (receipt == null || receipt.validated() <= 0 || receipt.committed() != 0
                || receipt.validated() != receipt.submitted() || !receipt.backend().equals("gpu")
                || receipt.numericProfile() != dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS)) {
            throw new IllegalArgumentException("Commit requires a complete, not prematurely committed GPU backend receipt");
        }
        return "{\n  \"schemaVersion\":1,\n  \"kind\":\"tellurium_gpu_live_commit\",\n"
                + "  \"status\":" + quote(failure == null ? "COMMITTED" : "FAILED") + ",\n"
                + "  \"route\":\"GPU_IEEE_BITS\",\n"
                + (receipt == null ? "  \"chunkX\":" + x + ",\n  \"chunkZ\":" + z + ",\n" : identityJson(x, z, receipt))
                + "  \"committed\":" + (failure == null ? receipt.validated() : 0) + ",\n"
                + "  \"failure\":" + quote(failure == null ? "" : detail(failure)) + ",\n"
                + "  \"releaseQualification\":false\n}\n";
    }

    private static String identityJson(int x, int z, ExecutionReceipt receipt) {
        return "  \"chunkX\":" + x + ",\n  \"chunkZ\":" + z + ",\n"
                + "  \"executionId\":" + quote(receipt.executionId()) + ",\n"
                + "  \"backend\":" + quote(receipt.backend()) + ",\n"
                + "  \"resultAbi\":" + quote(receipt.abiVersion()) + ",\n"
                + "  \"contextKey\":" + quote(receipt.context().worldKey()) + ",\n"
                + "  \"snapshotHash\":" + quote(receipt.context().snapshotHash()) + ",\n"
                + "  \"programHash\":" + quote(receipt.programHash()) + ",\n"
                + "  \"worldEpoch\":" + receipt.context().worldEpoch() + ",\n"
                + "  \"deviceGeneration\":" + receipt.deviceGeneration() + ",\n";
    }

    private static Path root() {
        String directory = System.getProperty("tellurium.candidate.liveNoiseResultDir", "").trim();
        return directory.isEmpty() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    private static String detail(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                default -> { if (c < 32) result.append(String.format("\\u%04x", (int) c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
}
