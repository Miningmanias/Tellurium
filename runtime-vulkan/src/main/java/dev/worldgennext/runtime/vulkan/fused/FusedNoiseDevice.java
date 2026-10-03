// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.fused;

import dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Persistent Vulkan device for the fused NOISE kernels.  One device, one
 * compute queue, one compiled program per captured router, and a fixed ring
 * of batch slots.  Each slot owns all of its buffers; a slot is reused only
 * after its fence signalled.  A failed or timed-out fence marks the device
 * lost: no buffer of an unproven submission is ever reused or freed.
 */
public final class FusedNoiseDevice implements AutoCloseable {
    private static final int BINDINGS = 10;
    private static final String[] KERNELS = {"K_COLUMN", "K_CORNER", "K_AQUIFER", "K_BLOCK", "K_HEIGHT"};
    public static final boolean PROFILE = Boolean.getBoolean("worldgennext.fast.profile");
    private static final java.util.concurrent.atomic.AtomicLong[] PROFILE_NANOS = new java.util.concurrent.atomic.AtomicLong[5];
    private static final java.util.concurrent.atomic.AtomicLong PROFILE_CHUNKS = new java.util.concurrent.atomic.AtomicLong();
    static { for (int i = 0; i < 5; i++) PROFILE_NANOS[i] = new java.util.concurrent.atomic.AtomicLong(); }

    public static String profileSummary() {
        long chunks = Math.max(1, PROFILE_CHUNKS.get());
        StringBuilder out = new StringBuilder("chunks=" + PROFILE_CHUNKS.get());
        for (int i = 0; i < 5; i++) out.append(' ').append(KERNELS[i]).append('=')
                .append(String.format(java.util.Locale.ROOT, "%.3f", PROFILE_NANOS[i].get() / 1e6 / chunks)).append("ms/chunk");
        return out.toString();
    }

    private final VkInstance instance;
    private final VkPhysicalDevice physical;
    private final VkDevice device;
    private final VkQueue queue;
    private final int queueFamily;
    private final String deviceName;
    private final long commandPool;
    private final VkPhysicalDeviceMemoryProperties memoryProperties;
    private volatile boolean lost;
    private volatile String lostReason = "";
    private final List<Program> programs = new ArrayList<>();

    private FusedNoiseDevice(VkInstance instance, VkPhysicalDevice physical, VkDevice device, VkQueue queue,
                             int queueFamily, String deviceName, long commandPool,
                             VkPhysicalDeviceMemoryProperties memoryProperties) {
        this.instance = instance;
        this.physical = physical;
        this.device = device;
        this.queue = queue;
        this.queueFamily = queueFamily;
        this.deviceName = deviceName;
        this.commandPool = commandPool;
        this.memoryProperties = memoryProperties;
    }

    public String deviceName() { return deviceName; }
    /** Raw device handle for developer probes in this package. */
    VkDevice vkDevice() { return device; }
    public boolean lost() { return lost; }
    public String lostReason() { return lostReason; }

    public static FusedNoiseDevice open() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("WorldgenNext")).apiVersion(VK12.VK_API_VERSION_1_2);
            var instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkCreateInstance(instanceInfo, null, pointer), "vkCreateInstance");
            VkInstance instance = new VkInstance(pointer.get(0), instanceInfo);
            try {
                IntBuffer count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
                VkPhysicalDevice best = null;
                int bestScore = -1, bestFamily = -1;
                String bestName = "";
                for (int i = 0; i < devices.capacity(); i++) {
                    VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                    var props = VkPhysicalDeviceProperties.calloc(stack);
                    vkGetPhysicalDeviceProperties(candidate, props);
                    var features = VkPhysicalDeviceFeatures.calloc(stack);
                    vkGetPhysicalDeviceFeatures(candidate, features);
                    if (!features.shaderFloat64() || !features.shaderInt64()) continue;
                    int family = computeFamily(candidate, stack);
                    if (family < 0) continue;
                    int score = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 3
                            : props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2 : 0;
                    if (score > bestScore) {
                        best = candidate;
                        bestScore = score;
                        bestFamily = family;
                        bestName = props.deviceNameString();
                    }
                }
                if (best == null || bestScore <= 0) throw new IllegalStateException("No Vulkan GPU with shaderFloat64 and shaderInt64");
                var queues = VkDeviceQueueCreateInfo.calloc(1, stack);
                queues.get(0).sType$Default().queueFamilyIndex(bestFamily).pQueuePriorities(stack.floats(1.0f));
                var enabled = VkPhysicalDeviceFeatures.calloc(stack).shaderFloat64(true).shaderInt64(true);
                var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues).pEnabledFeatures(enabled);
                check(vkCreateDevice(best, deviceInfo, null, pointer), "vkCreateDevice");
                VkDevice device = new VkDevice(pointer.get(0), best, deviceInfo);
                vkGetDeviceQueue(device, bestFamily, 0, pointer);
                VkQueue queue = new VkQueue(pointer.get(0), device);
                var poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(bestFamily);
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateCommandPool(device, poolInfo, null, handle), "vkCreateCommandPool");
                var memory = VkPhysicalDeviceMemoryProperties.malloc();
                vkGetPhysicalDeviceMemoryProperties(best, memory);
                return new FusedNoiseDevice(instance, best, device, queue, bestFamily, bestName, handle.get(0), memory);
            } catch (RuntimeException failure) {
                vkDestroyInstance(instance, null);
                throw failure;
            }
        }
    }

    private static int computeFamily(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
        var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0 && families.get(i).queueCount() > 0) return i;
        }
        return -1;
    }

    /** Compiles all kernels of one fused program and allocates its batch slots. */
    public synchronized Program load(FusedNoiseCompiler.Compiled compiled, int maxBatchChunks, int slotCount) {
        if (lost) throw new IllegalStateException("Fused NOISE device is lost: " + lostReason);
        Program program = new Program(compiled, maxBatchChunks, slotCount);
        programs.add(program);
        return program;
    }

    // ---------------------------------------------------------------- buffers
    final class Buffer {
        final long buffer;
        final long memory;
        final long size;
        final ByteBuffer mapped;

        Buffer(long size, boolean hostVisible) {
            this.size = Math.max(16, (size + 15) & ~15L);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(this.size)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateBuffer(device, info, null, handle), "vkCreateBuffer");
                buffer = handle.get(0);
                var requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, buffer, requirements);
                int type = hostVisible
                        ? memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        VK_MEMORY_PROPERTY_HOST_CACHED_BIT)
                        : memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
                var allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(requirements.size()).memoryTypeIndex(type);
                check(vkAllocateMemory(device, allocate, null, handle), "vkAllocateMemory(" + this.size + ")");
                memory = handle.get(0);
                check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
                if (hostVisible) {
                    PointerBuffer data = stack.mallocPointer(1);
                    check(vkMapMemory(device, memory, 0, this.size, 0, data), "vkMapMemory");
                    mapped = MemoryUtil.memByteBuffer(data.get(0), (int) this.size).order(ByteOrder.LITTLE_ENDIAN);
                } else {
                    mapped = null;
                }
            }
        }

        void destroy() {
            if (mapped != null) vkUnmapMemory(device, memory);
            vkDestroyBuffer(device, buffer, null);
            vkFreeMemory(device, memory, null);
        }
    }

    private int memoryType(int typeBits, int required, int preferred) {
        int fallback = -1;
        for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) == 0) continue;
            int flags = memoryProperties.memoryTypes(i).propertyFlags();
            if ((flags & required) != required) continue;
            if ((flags & preferred) == preferred) return i;
            if (fallback < 0) fallback = i;
        }
        if (fallback < 0) throw new IllegalStateException("No Vulkan memory type for flags " + required);
        return fallback;
    }

    // ---------------------------------------------------------------- program
    public final class Program implements AutoCloseable {
        public final FusedNoiseCompiler.Compiled compiled;
        public final int maxBatchChunks;
        private final long descriptorSetLayout;
        private final long pipelineLayout;
        private final long[] pipelines = new long[KERNELS.length];
        private final long[] modules = new long[KERNELS.length];
        private final long descriptorPool;
        private final Buffer permBuffer;
        private final Buffer dtabBuffer;
        private final Slot[] slots;
        public final long compileNanos;

        Program(FusedNoiseCompiler.Compiled compiled, int maxBatchChunks, int slotCount) {
            this.compiled = compiled;
            this.maxBatchChunks = maxBatchChunks;
            long start = System.nanoTime();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var bindings = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
                for (int i = 0; i < BINDINGS; i++) {
                    bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                }
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                        .pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
                descriptorSetLayout = handle.get(0);
                var push = VkPushConstantRange.calloc(1, stack);
                push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);
                check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(push), null, handle), "vkCreatePipelineLayout");
                pipelineLayout = handle.get(0);
                long pipelineCache = createPipelineCache(stack, compiled.fingerprint());
                for (int k = 0; k < KERNELS.length; k++) {
                    long kernelStart = System.nanoTime();
                    byte[] spirv = compileGlsl(compiled.source(), KERNELS[k]);
                    long shadercNanos = System.nanoTime() - kernelStart;
                    ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
                    try {
                        code.put(spirv).flip();
                        check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle),
                                "vkCreateShaderModule(" + KERNELS[k] + ")");
                    } finally {
                        MemoryUtil.memFree(code);
                    }
                    modules[k] = handle.get(0);
                    var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(modules[k]).pName(stack.UTF8("main"));
                    var info = VkComputePipelineCreateInfo.calloc(1, stack);
                    info.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                    check(vkCreateComputePipelines(device, pipelineCache, info, null, handle), "vkCreateComputePipelines(" + KERNELS[k] + ")");
                    pipelines[k] = handle.get(0);
                    System.out.println("[worldgennext-fast] kernel " + KERNELS[k] + ": spirv " + spirv.length + " bytes, shaderc "
                            + shadercNanos / 1_000_000 + " ms, pipeline " + (System.nanoTime() - kernelStart - shadercNanos) / 1_000_000 + " ms");
                }
                savePipelineCache(pipelineCache, compiled.fingerprint());
                vkDestroyPipelineCache(device, pipelineCache, null);
                var sizes = VkDescriptorPoolSize.calloc(1, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(BINDINGS * slotCount);
                check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                        .maxSets(slotCount).pPoolSizes(sizes), null, handle), "vkCreateDescriptorPool");
                descriptorPool = handle.get(0);
            }
            permBuffer = new Buffer((long) compiled.permTable().length * 4, true);
            permBuffer.mapped.asIntBuffer().put(compiled.permTable());
            dtabBuffer = new Buffer((long) compiled.doubleTable().length * 8, true);
            dtabBuffer.mapped.asDoubleBuffer().put(compiled.doubleTable());
            slots = new Slot[slotCount];
            for (int i = 0; i < slotCount; i++) slots[i] = new Slot();
            compileNanos = System.nanoTime() - start;
        }

        public int slotCount() { return slots.length; }
        public Slot slot(int index) { return slots[index]; }

        @Override
        public synchronized void close() {
            for (Slot slot : slots) slot.destroy();
            permBuffer.destroy();
            dtabBuffer.destroy();
            vkDestroyDescriptorPool(device, descriptorPool, null);
            for (int k = 0; k < KERNELS.length; k++) {
                vkDestroyPipeline(device, pipelines[k], null);
                vkDestroyShaderModule(device, modules[k], null);
            }
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        }

        /** One in-flight batch: all buffers are owned until its fence signals. */
        public final class Slot {
            public static final int CHUNK_INFO_INTS = 8;
            private final Buffer chunks, columns, corners, aquifer, blocks, beard, flags, heights;
            private final long descriptorSet;
            private final VkCommandBuffer commands;
            private final long fence;
            private boolean pending;
            private int pendingChunks;
            public final int beardCapacityInts;

            Slot() {
                var g = compiled.geometry();
                int n = maxBatchChunks;
                chunks = new Buffer((long) n * CHUNK_INFO_INTS * 4, true);
                columns = new Buffer((long) n * Math.max(1, compiled.flatChannels()) * g.columnCount() * 8, false);
                corners = new Buffer((long) n * Math.max(1, compiled.interpolatedChannels()) * g.cornerCount() * 8, false);
                aquifer = new Buffer((long) n * g.aquiferCellCount() * 8 * 4, false);
                blocks = new Buffer((long) n * g.blockCount(), true);
                beardCapacityInts = n * 2048;
                beard = new Buffer((long) beardCapacityInts * 4, true);
                flags = new Buffer((long) n * 4, true);
                heights = new Buffer((long) n * 2 * 256 * 4, true);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    LongBuffer handle = stack.mallocLong(1);
                    check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout)), handle), "vkAllocateDescriptorSets");
                    descriptorSet = handle.get(0);
                    Buffer[] bound = {chunks, permBuffer, dtabBuffer, columns, corners, aquifer, blocks, beard, flags, heights};
                    var infos = VkDescriptorBufferInfo.calloc(BINDINGS, stack);
                    var writes = VkWriteDescriptorSet.calloc(BINDINGS, stack);
                    for (int i = 0; i < BINDINGS; i++) {
                        infos.get(i).buffer(bound[i].buffer).offset(0).range(VK_WHOLE_SIZE);
                        writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorCount(1)
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .pBufferInfo(VkDescriptorBufferInfo.create(infos.get(i).address(), 1));
                    }
                    vkUpdateDescriptorSets(device, writes, null);
                    PointerBuffer pointer = stack.mallocPointer(1);
                    check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                            .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer),
                            "vkAllocateCommandBuffers");
                    commands = new VkCommandBuffer(pointer.get(0), device);
                    check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateFence");
                    fence = handle.get(0);
                }
            }

            public boolean pending() { return pending; }
            public int pendingChunks() { return pendingChunks; }
            public ByteBuffer chunkInfo() { return chunks.mapped; }
            public ByteBuffer beardData() { return beard.mapped; }

            /** Records and submits all kernels for {@code count} chunks already written to the mapped inputs. */
            public void submit(int count) {
                if (pending) throw new IllegalStateException("Slot is still in flight");
                if (lost) throw new IllegalStateException("Fused NOISE device is lost: " + lostReason);
                if (count <= 0 || count > maxBatchChunks) throw new IllegalArgumentException("Invalid batch size " + count);
                var g = compiled.geometry();
                for (int i = 0; i < count; i++) flags.mapped.putInt(i * 4, 0);
                long[] threads = {(long) count * g.columnCount(), (long) count * g.cornerCount(),
                        compiledAquifers() ? (long) count * g.aquiferCellCount() : 0L,
                        (long) count * 64 * g.storageHeight(), (long) count * 256};
                if (PROFILE) {
                    // Diagnostic: one fenced submission per kernel so each kernel's wall time is visible.
                    for (int k = 0; k < KERNELS.length; k++) {
                        if (threads[k] == 0) continue;
                        long start = System.nanoTime();
                        record(count, threads, k, k + 1);
                        int status = vkWaitForFences(device, new long[]{fence}, true, 60_000_000_000L);
                        if (status != VK_SUCCESS) { lost = true; lostReason = "profile fence VkResult=" + status; throw new IllegalStateException(lostReason); }
                        PROFILE_NANOS[k].addAndGet(System.nanoTime() - start);
                    }
                    PROFILE_CHUNKS.addAndGet(count);
                    pending = true; // fence already signalled; poll() completes immediately
                    pendingChunks = count;
                    return;
                }
                record(count, threads, 0, KERNELS.length);
                pending = true;
                pendingChunks = count;
            }

            private void record(int count, long[] threads, int from, int to) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    vkResetCommandBuffer(commands, 0);
                    check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
                    vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                            stack.longs(descriptorSet), null);
                    vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(count));
                    for (int k = from; k < to; k++) if (threads[k] > 0) dispatch(stack, k, threads[k]);
                    var hostBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                            hostBarrier, null, null);
                    check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
                    check(vkResetFences(device, stack.longs(fence)), "vkResetFences");
                    var submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commands));
                    synchronized (FusedNoiseDevice.this) {
                        check(vkQueueSubmit(queue, submit, fence), "vkQueueSubmit");
                    }
                }
            }

            private void dispatch(MemoryStack stack, int kernel, long threads) {
                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[kernel]);
                long groups = (threads + 63) / 64;
                if (groups > 65535L * 65535L) throw new IllegalStateException("Dispatch too large");
                vkCmdDispatch(commands, (int) groups, 1, 1);
                var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                        barrier, null, null);
            }

            /** Waits for the fence; returns false on timeout (slot then stays owned and the device is lost). */
            public boolean await(long timeoutNanos) {
                if (!pending) return true;
                int status = vkWaitForFences(device, new long[]{fence}, true, timeoutNanos);
                if (status == VK_SUCCESS) {
                    pending = false;
                    return true;
                }
                lost = true;
                lostReason = "Fence wait returned VkResult=" + status;
                return false;
            }

            public boolean poll() {
                if (!pending) return true;
                int status = vkGetFenceStatus(device, fence);
                if (status == VK_SUCCESS) {
                    pending = false;
                    return true;
                }
                if (status != VK_NOT_READY) {
                    lost = true;
                    lostReason = "Fence status VkResult=" + status;
                }
                return false;
            }

            public ByteBuffer blocks() { return blocks.mapped; }
            public ByteBuffer heights() { return heights.mapped; }
            public int flags(int chunk) { return flags.mapped.getInt(chunk * 4); }

            void destroy() {
                if (pending) return; // never free storage of an unproven submission
                vkDestroyFence(device, fence, null);
                for (Buffer b : new Buffer[]{chunks, columns, corners, aquifer, blocks, beard, flags, heights}) b.destroy();
            }
        }

        private boolean compiledAquifers() {
            return compiled.source().contains("const bool AQUIFERS = true;");
        }

    }

    private static java.nio.file.Path cacheFile(String fingerprint) {
        String dir = System.getProperty("worldgennext.fast.pipelineCacheDir", "worldgennext-cache").trim();
        if (dir.isEmpty() || dir.equals("NONE")) return null;
        return java.nio.file.Path.of(dir, "fused-" + fingerprint.substring(0, 32) + ".vkcache");
    }

    private long createPipelineCache(MemoryStack stack, String fingerprint) {
        java.nio.file.Path file = cacheFile(fingerprint);
        byte[] initial = null;
        if (file != null && java.nio.file.Files.isRegularFile(file)) {
            try { initial = java.nio.file.Files.readAllBytes(file); } catch (java.io.IOException ignored) { initial = null; }
        }
        var info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
        ByteBuffer data = null;
        if (initial != null && initial.length > 0) {
            data = MemoryUtil.memAlloc(initial.length);
            data.put(initial).flip();
            info.pInitialData(data);
        }
        try {
            LongBuffer handle = stack.mallocLong(1);
            int result = vkCreatePipelineCache(device, info, null, handle);
            if (result != VK_SUCCESS && data != null) {
                // Stale or foreign cache data: start empty.
                info.pInitialData(null);
                result = vkCreatePipelineCache(device, info, null, handle);
            }
            check(result, "vkCreatePipelineCache");
            return handle.get(0);
        } finally {
            if (data != null) MemoryUtil.memFree(data);
        }
    }

    private void savePipelineCache(long cache, String fingerprint) {
        java.nio.file.Path file = cacheFile(fingerprint);
        if (file == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer size = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(device, cache, size, null) != VK_SUCCESS) return;
            ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
            try {
                if (vkGetPipelineCacheData(device, cache, size, data) != VK_SUCCESS) return;
                byte[] bytes = new byte[(int) size.get(0)];
                data.get(0, bytes);
                java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
                java.nio.file.Files.write(file, bytes);
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (java.io.IOException ignored) {
            // cache is an optimization only
        }
    }

    static byte[] compileGlsl(String source, String kernel) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc initialization failed");
        long options = shaderc_compile_options_initialize();
        long result = 0;
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
            shaderc_compile_options_add_macro_definition(options, kernel, "1");
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, "worldgennext-fused.comp", "main", options);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                String message = result == 0 ? "no result" : shaderc_result_get_error_message(result);
                throw new IllegalStateException("Fused kernel " + kernel + " failed to compile: " + message);
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            byte[] out = new byte[bytes.remaining()];
            bytes.get(out);
            String dontInline = System.getProperty("worldgennext.fast.dontInline", "wgs_").trim();
            if (!dontInline.isEmpty() && !dontInline.equals("NONE")) {
                out = dev.worldgennext.compiler.vulkan.worldgen.SpirvFunctionControlPatcher.addDontInlineToNamedFunctions(out, dontInline);
            }
            return out;
        } finally {
            if (result != 0) shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    @Override
    public synchronized void close() {
        if (!lost) vkDeviceWaitIdle(device);
        for (Program program : programs) {
            try {
                program.close();
            } catch (RuntimeException ignored) {
                // keep tearing down the remaining handles
            }
        }
        programs.clear();
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
        memoryProperties.free();
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult=" + result);
    }
}
