// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.runtime.vulkan.production.*;
import dev.tellurium.semantic.execution.ExecutionReceipt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V02ProductionTest {
    @Test void pipelineCacheIsBoundedLruAndReturnsDetachedDisposals() {
        var cache = new PipelineCache<String>(2);
        assertEquals(java.util.List.of(), cache.putAndCollectEvicted("a", "A"));
        cache.put("b", "B");
        assertEquals("A", cache.get("a"));
        assertEquals(java.util.List.of("B"), cache.putAndCollectEvicted("c", "C"));
        assertEquals(java.util.List.of("A"), cache.putAndCollectEvicted("a", "A2"));
        assertEquals(2, cache.size());
        assertEquals(java.util.List.of("C", "A2"), cache.clear());
        assertEquals(0, cache.size());
    }

    @Test void layoutRejectsOverlapAndVisibilityAligns() {
        var layout = new BufferLayout(128);
        assertEquals(0, layout.allocate("a", 7, 4).offset());
        assertEquals(8, layout.allocate("b", 8, 8).offset());
        var visibility = new MemoryVisibility(false, 16);
        assertEquals(16, visibility.alignedLength(7, 1));
    }

    @Test void failedArenaLayoutDoesNotLeakTheBudgetReservation() {
        try (var arena = new DeviceArena(16)) {
            try (var first = arena.allocate("first", 8, 1)) {
                assertEquals(8, arena.usedBytes());
                assertThrows(IllegalStateException.class, () -> arena.allocate("second", 8, 16));
                assertEquals(8, arena.usedBytes());
            }
            assertEquals(0, arena.usedBytes());
        }
    }

    @Test void dispatchCeilingUsesCheckedWideningAtIntegerLimits() {
        assertEquals(2_097_152, new DispatchDescriptor("pipeline", Integer.MAX_VALUE, 1024, 0, 0).workgroups());
        assertEquals(1, new DispatchDescriptor("pipeline", Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0).workgroups());
    }

    @Test void serviceLifecycleUsesInjectedDispatcherWithoutNativeLoading() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0, false, false, false, false);
        var service = new VulkanGenerationService(caps, 1024, descriptor -> java.util.concurrent.CompletableFuture.completedFuture(new dev.tellurium.semantic.execution.ResultBufferLease(java.nio.ByteBuffer.allocate(8), "test", () -> {})));
        service.start();
        assertEquals(VulkanGenerationService.State.READY, service.state());
        service.beginDrain();
        assertEquals(VulkanGenerationService.State.DRAINING, service.state());
        service.close();
    }

    @Test void synchronouslyCompletedDispatchIsRetiredBeforeItCanLeakInFlightTracking() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0, false, false, false, false);
        var service = new VulkanGenerationService(caps, 32, descriptor ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        new dev.tellurium.semantic.execution.ResultBufferLease(
                                java.nio.ByteBuffer.allocate(4), "sync", () -> {})));
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(3, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);
        var submission = service.submit(context, new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi")
                .toCompletableFuture().join();
        assertEquals(0, service.diagnostics().inFlight());
        assertEquals(8, service.diagnostics().reservedBytes());
        submission.close();
        assertEquals(0, service.diagnostics().reservedBytes());
        service.close();
    }

    @Test void cancelledCompletionClosesLateResultAndReleasesNativeBudget() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0, false, false, false, false);
        var pending = new java.util.concurrent.CompletableFuture<dev.tellurium.semantic.execution.ResultBufferLease>();
        var service = new VulkanGenerationService(caps, 32, descriptor -> pending);
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(8, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);
        var result = service.submit(context, new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi")
                .toCompletableFuture();
        assertTrue(result.cancel(false));
        var late = new dev.tellurium.semantic.execution.ResultBufferLease(
                java.nio.ByteBuffer.allocate(4), "cancelled", () -> {});
        pending.complete(late);
        assertTrue(late.isClosed());
        assertEquals(0, service.diagnostics().inFlight());
        assertEquals(0, service.diagnostics().reservedBytes());
        service.close();
    }

    @Test void failedCompletionClosesAnUnexpectedSuppliedResultBuffer() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0, false, false, false, false);
        var supplied = new dev.tellurium.semantic.execution.ResultBufferLease(
                java.nio.ByteBuffer.allocate(4), "failed", () -> {});
        var failure = new IllegalStateException("native failure");
        var stage = new java.util.concurrent.CompletableFuture<dev.tellurium.semantic.execution.ResultBufferLease>() {
            @Override public java.util.concurrent.CompletableFuture<dev.tellurium.semantic.execution.ResultBufferLease> whenComplete(
                    java.util.function.BiConsumer<? super dev.tellurium.semantic.execution.ResultBufferLease, ? super Throwable> action) {
                action.accept(supplied, failure);
                return this;
            }
        };
        var service = new VulkanGenerationService(caps, 32, descriptor -> stage);
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(9, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);
        var result = service.submit(context, new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi")
                .toCompletableFuture();
        assertThrows(java.util.concurrent.CompletionException.class, result::join);
        assertTrue(supplied.isClosed());
        assertEquals(0, service.diagnostics().inFlight());
        // A completion that failed has not shown its memory was given back: the bytes are quarantined and stay
        // counted, so a second dispatch of the whole budget is not admitted on top of them.
        assertEquals(8, service.diagnostics().reservedBytes());
        var second = new VulkanGenerationService(caps, 8, descriptor -> stage);
        second.start();
        assertThrows(java.util.concurrent.CompletionException.class, () -> second.submit(context, new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi").toCompletableFuture().join());
        assertThrows(java.util.concurrent.CompletionException.class, () -> second.submit(context, new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi").toCompletableFuture().join());
        assertEquals(8, second.diagnostics().reservedBytes(), "the refused second dispatch added nothing");
        second.close();
        service.close();
    }

    @Test void dispatcherFailureIsDiagnosedAndReleasesItsNativeBudget() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0, false, false, false, false);
        var service = new VulkanGenerationService(caps, 32, descriptor -> {
            throw new IllegalStateException("injected submit failure");
        });
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(7, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);

        var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> service.submit(context,
                new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi")
                .toCompletableFuture().join());
        assertTrue(failure.getCause().getMessage().contains("injected submit failure"));
        assertEquals(0, service.diagnostics().reservedBytes());
        assertEquals(0, service.diagnostics().inFlight());
        assertTrue(service.diagnostics().failures().stream().anyMatch(value -> value.contains("dispatch threw")));
        service.close();
    }

    @Test void deviceLossAdvancesGenerationAndRejectsLateResults() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0, false, false, false, false);
        var pending = new java.util.concurrent.CompletableFuture<dev.tellurium.semantic.execution.ResultBufferLease>();
        var service = new VulkanGenerationService(caps, 1024, descriptor -> pending);
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(1, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);
        var result = service.submit(context, new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi");
        assertEquals(0, service.deviceGeneration());
        service.deviceLost("test loss");
        assertEquals(1, service.deviceGeneration());
        assertEquals(VulkanGenerationService.State.LOST, service.state());
        pending.complete(new dev.tellurium.semantic.execution.ResultBufferLease(java.nio.ByteBuffer.allocate(4), "late", () -> {}));
        assertThrows(java.util.concurrent.CompletionException.class, () -> result.toCompletableFuture().join());
        assertTrue(service.diagnostics().quarantinedBytes() > 0);
        service.close();
    }

    @Test void serviceReceiptCarriesTheRequestedNumericProfile() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0, false, false, false, false);
        var service = new VulkanGenerationService(caps, 32, descriptor ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        new dev.tellurium.semantic.execution.ResultBufferLease(java.nio.ByteBuffer.allocate(4), "done", () -> {})));
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(2, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);
        var submission = service.submit(context, new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi")
                .toCompletableFuture().join();
        assertEquals("GPU_IEEE_BITS", submission.receipt().backend());
        submission.close();
        service.close();
    }

    @Test void serviceCanIssueReceiptWithExplicitCompiledArtifactIdentity() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0, false, false, false, false);
        var service = new VulkanGenerationService(caps, 32, descriptor ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        new dev.tellurium.semantic.execution.ResultBufferLease(
                                java.nio.ByteBuffer.allocate(4), "done", () -> {})));
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(13, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);
        var submission = service.submit(context, new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi",
                "shader-sha", "spirv-sha").toCompletableFuture().join();

        assertEquals("shader-sha", submission.receipt().shaderHash());
        assertEquals("spirv-sha", submission.receipt().spirvHash());
        submission.close();
        service.close();
    }

    @Test void gpuReceiptBindsCompiledArtifactsToTheRuntimeReceipt() {
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(12, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);
        ExecutionReceipt execution = ExecutionReceipt.issued("gpu-1", "GPU_IEEE_BITS", context,
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi",
                "shader-sha", "spirv-sha", 0, 1).completed(1).validated(1);
        var dispatch = new DispatchDescriptor("pipeline", 1, 1, 4, 4);

        var receipt = new GpuExecutionReceipt(execution, dispatch, "shader-sha", "spirv-sha");

        assertEquals("shader-sha", receipt.execution().shaderHash());
        assertEquals("spirv-sha", receipt.execution().spirvHash());
        assertTrue(receipt.execution().hasCompiledArtifactProvenance());
        assertThrows(IllegalArgumentException.class,
                () -> new GpuExecutionReceipt(execution, dispatch, "other-shader", "spirv-sha"));
    }

    @Test void serviceRejectsAnUnqualifiedNativeProfileBeforeDispatch() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0,
                true, true, false, true);
        var dispatches = new java.util.concurrent.atomic.AtomicInteger();
        var service = new VulkanGenerationService(caps, 32, descriptor -> {
            dispatches.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new dev.tellurium.semantic.execution.ResultBufferLease(
                            java.nio.ByteBuffer.allocate(4), "unexpected", () -> {}));
        });
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(4, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.NATIVE_QUALIFIED, "abi", "compiler", 0, 0);
        var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> service.submit(context,
                new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.NATIVE_QUALIFIED, context.programHash(), "abi")
                .toCompletableFuture().join());
        assertTrue(failure.getCause().getMessage().contains("not qualified"));
        assertEquals(0, dispatches.get());
        assertEquals(0, service.diagnostics().reservedBytes());
        service.close();
    }

    @Test void serviceRejectsContextProfileMismatchBeforeDispatch() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0,
                false, false, false, false);
        var dispatches = new java.util.concurrent.atomic.AtomicInteger();
        var service = new VulkanGenerationService(caps, 32, descriptor -> {
            dispatches.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new dev.tellurium.semantic.execution.ResultBufferLease(
                            java.nio.ByteBuffer.allocate(4), "unexpected", () -> {}));
        });
        service.start();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(
                dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(5, "minecraft:overworld").build(),
                dev.tellurium.semantic.program.WorldgenProgram.builder().build(),
                dev.tellurium.semantic.program.NumericProfile.JAVA_REFERENCE, "abi", "compiler", 0, 0);
        var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> service.submit(context,
                new DispatchDescriptor("pipeline", 1, 1, 4, 4),
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi")
                .toCompletableFuture().join());
        assertTrue(failure.getCause().getMessage().contains("does not match"));
        assertEquals(0, dispatches.get());
        service.close();
    }

    @Test void serviceRejectsStaleProgramAbiAndDeviceIdentityBeforeDispatch() {
        var caps = new DeviceCapabilities("test", (1 << 22) | (2 << 12), 1, 2, 0,
                false, false, false, false);
        var dispatches = new java.util.concurrent.atomic.AtomicInteger();
        var service = new VulkanGenerationService(caps, 32, descriptor -> {
            dispatches.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new dev.tellurium.semantic.execution.ResultBufferLease(
                            java.nio.ByteBuffer.allocate(4), "unexpected", () -> {}));
        });
        service.start();
        var snapshot = dev.tellurium.semantic.snapshot.WorldgenSnapshot.builder(6, "minecraft:overworld").build();
        var program = dev.tellurium.semantic.program.WorldgenProgram.builder().build();
        var context = dev.tellurium.semantic.identity.ContextIdentity.of(snapshot, program,
                dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "abi", "compiler", 0, 0);
        var descriptor = new DispatchDescriptor("pipeline", 1, 1, 4, 4);
        var wrongProgram = assertThrows(java.util.concurrent.CompletionException.class, () -> service.submit(context,
                descriptor, dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, "wrong-program", "abi")
                .toCompletableFuture().join());
        assertTrue(wrongProgram.getCause().getMessage().contains("Program identity"));
        var wrongAbi = assertThrows(java.util.concurrent.CompletionException.class, () -> service.submit(context,
                descriptor, dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), "wrong-abi")
                .toCompletableFuture().join());
        assertTrue(wrongAbi.getCause().getMessage().contains("ABI identity"));
        var staleContext = new dev.tellurium.semantic.identity.ContextIdentity(context.snapshotHash(),
                context.registryHash(), context.programHash(), context.numericProfile(), context.abiVersion(),
                context.compilerVersion(), context.worldEpoch(), context.dynamicInputs(), 1);
        var stale = assertThrows(java.util.concurrent.CompletionException.class, () -> service.submit(staleContext,
                descriptor, dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS, context.programHash(), context.abiVersion())
                .toCompletableFuture().join());
        assertTrue(stale.getCause().getMessage().contains("device generation"));
        assertEquals(0, dispatches.get());
        assertEquals(0, service.diagnostics().reservedBytes());
        service.close();
    }
}
