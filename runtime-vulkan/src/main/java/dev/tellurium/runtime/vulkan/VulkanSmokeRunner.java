// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.GlslCompiler;
import dev.tellurium.semantic.DensityExpression;
import dev.tellurium.semantic.SamplePoint;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

/** Bounded synthetic dispatch. No Minecraft integration and no CPU fallback. */
public final class VulkanSmokeRunner {
    public List<DeviceCapabilities> probe() {
        try (Session session = new Session()) { return List.copyOf(session.capabilities); }
    }

    public GpuSmokeResult run(DensityExpression expression, List<SamplePoint> points, double[] expected) {
        return run(expression, points, expected, Fp64Profile.STRICT);
    }

    public GpuSmokeResult run(DensityExpression expression, List<SamplePoint> points, double[] expected, Fp64Profile profile) {
        java.util.Objects.requireNonNull(profile, "profile");
        if (expression == null || points == null || expected == null || points.size() != expected.length)
            throw new IllegalArgumentException("Expression, points and equal-length oracle values required");
        SmokeConfig config = SmokeConfig.of(points.size());
        SmokeWorkBudget.validate(expression, config);
        for (SamplePoint point : points) if (point == null) throw new IllegalArgumentException("Null point");
        for (double value : expected) if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite oracle values required");
        if (profile == Fp64Profile.NORMAL_RANGE_DIAGNOSTIC) {
            for (double value : expected) if (value != 0 && Math.abs(value) < Double.MIN_NORMAL)
                throw new IllegalArgumentException("Subnormal expected value is outside normal-range diagnostic scope");
        }
        String source = new GlslCompiler().emit(expression);
        byte[] spirv = new ShadercCompiler().compile(source);
        try (Session session = new Session()) {
            int selected = DeviceCapabilities.select(session.capabilities, profile);
            session.openDevice(selected);
            double[] actual = session.dispatch(spirv, points, config);
            int mismatches = 0;
            for (int i = 0; i < actual.length; i++) {
                if (!Double.isFinite(actual[i]) || Double.doubleToRawLongBits(expected[i]) != Double.doubleToRawLongBits(actual[i])) mismatches++;
            }
            return new GpuSmokeResult(session.capabilities.get(selected), points.size(), actual.length, mismatches,
                    Hashes.sha256(source.getBytes(StandardCharsets.UTF_8)), Hashes.sha256(spirv), profile);
        }
    }

    private static void check(int status, String operation) {
        if (status != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult=" + status);
    }

    private static final class Session implements AutoCloseable {
        private VkInstance instance;
        private VkDevice device;
        private VkPhysicalDevice physical;
        private VkQueue queue;
        private int queueFamily;
        private final List<VkPhysicalDevice> devices = new ArrayList<>();
        private final List<DeviceCapabilities> capabilities = new ArrayList<>();
        private final List<Allocation> allocations = new ArrayList<>();
        private long shader, descriptorLayout, pipelineLayout, pipeline, descriptorPool, commandPool, fence;
        private boolean pending;

        Session() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var application = VkApplicationInfo.calloc(stack).sType$Default()
                        .pApplicationName(stack.UTF8("Tellurium synthetic replay"))
                        .applicationVersion(VK_MAKE_VERSION(0, 1, 0)).apiVersion(VK_MAKE_VERSION(1, 2, 0));
                var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application);
                PointerBuffer out = stack.mallocPointer(1);
                check(vkCreateInstance(create, null, out), "vkCreateInstance");
                instance = new VkInstance(out.get(0), create);
                var count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices/count");
                if (count.get(0) == 0) throw new IllegalStateException("No Vulkan physical devices");
                PointerBuffer physicalDevices = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, physicalDevices), "vkEnumeratePhysicalDevices");
                for (int i = 0; i < count.get(0); i++) {
                    VkPhysicalDevice candidate = new VkPhysicalDevice(physicalDevices.get(i), instance);
                    devices.add(candidate);
                    capabilities.add(inspect(candidate, stack));
                }
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }

        private static DeviceCapabilities inspect(VkPhysicalDevice candidate, MemoryStack stack) {
            var controls = VkPhysicalDeviceFloatControlsProperties.calloc(stack).sType$Default();
            var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(controls.address());
            vkGetPhysicalDeviceProperties2(candidate, props);
            var features = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(candidate, features);
            var count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
            int family = -1;
            for (int i = 0; i < families.capacity(); i++) {
                if (families.get(i).queueCount() > 0 && (families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) { family = i; break; }
            }
            return new DeviceCapabilities(props.properties().deviceNameString(), props.properties().apiVersion(),
                    props.properties().driverVersion(), props.properties().deviceType(), family, features.shaderFloat64(),
                    controls.shaderSignedZeroInfNanPreserveFloat64(), controls.shaderDenormPreserveFloat64(), controls.shaderRoundingModeRTEFloat64());
        }

        void openDevice(int index) {
            physical = devices.get(index);
            queueFamily = capabilities.get(index).computeQueueFamily();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var queues = VkDeviceQueueCreateInfo.calloc(1, stack);
                queues.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1));
                var features = VkPhysicalDeviceFeatures.calloc(stack).shaderFloat64(true);
                var create = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues).pEnabledFeatures(features);
                PointerBuffer out = stack.mallocPointer(1);
                check(vkCreateDevice(physical, create, null, out), "vkCreateDevice");
                device = new VkDevice(out.get(0), physical, create);
                vkGetDeviceQueue(device, queueFamily, 0, out);
                queue = new VkQueue(out.get(0), device);
            }
        }

        private Allocation allocate(long bytes) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var create = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateBuffer(device, create, null, out), "vkCreateBuffer");
                long buffer = out.get(0), memory = 0;
                try {
                    var requirements = VkMemoryRequirements.calloc(stack);
                    vkGetBufferMemoryRequirements(device, buffer, requirements);
                    var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
                    vkGetPhysicalDeviceMemoryProperties(physical, properties);
                    int memoryType = -1;
                    int required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                    for (int i = 0; i < properties.memoryTypeCount(); i++) {
                        if ((requirements.memoryTypeBits() & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & required) == required) { memoryType = i; break; }
                    }
                    if (memoryType < 0) throw new IllegalStateException("Smoke requires host-visible coherent storage buffer memory");
                    var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(memoryType);
                    check(vkAllocateMemory(device, allocation, null, out), "vkAllocateMemory");
                    memory = out.get(0);
                    check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
                    PointerBuffer mapped = stack.mallocPointer(1);
                    check(vkMapMemory(device, memory, 0, bytes, 0, mapped), "vkMapMemory");
                    Allocation result = new Allocation(buffer, memory, MemoryUtil.memByteBuffer(mapped.get(0), Math.toIntExact(bytes)).order(ByteOrder.nativeOrder()));
                    allocations.add(result);
                    return result;
                } catch (Throwable failure) {
                    vkDestroyBuffer(device, buffer, null);
                    if (memory != 0) vkFreeMemory(device, memory, null);
                    throw failure;
                }
            }
        }

        double[] dispatch(byte[] spirv, List<SamplePoint> points, SmokeConfig config) {
            Allocation input = allocate(config.pointBytes()), output = allocate(config.valueBytes());
            for (int i = 0; i < points.size(); i++) {
                var point = points.get(i);
                input.mapped.putInt(i * 16, point.x()).putInt(i * 16 + 4, point.y()).putInt(i * 16 + 8, point.z()).putInt(i * 16 + 12, 0);
                output.mapped.putDouble(i * 8, Double.NaN);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                ByteBuffer code = MemoryUtil.memAlloc(spirv.length).order(ByteOrder.nativeOrder());
                try {
                    code.put(spirv).flip();
                    check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle), "vkCreateShaderModule");
                    shader = handle.get(0);
                } finally { MemoryUtil.memFree(code); }
                var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
                for (int i = 0; i < 2; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
                descriptorLayout = handle.get(0);
                var push = VkPushConstantRange.calloc(1, stack);
                push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);
                check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push), null, handle), "vkCreatePipelineLayout");
                pipelineLayout = handle.get(0);
                var pipelines = VkComputePipelineCreateInfo.calloc(1, stack);
                pipelines.get(0).sType$Default().layout(pipelineLayout);
                pipelines.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(device, 0, pipelines, null, handle), "vkCreateComputePipelines");
                pipeline = handle.get(0);
                var sizes = VkDescriptorPoolSize.calloc(1, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
                check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, handle), "vkCreateDescriptorPool");
                descriptorPool = handle.get(0);
                check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle), "vkAllocateDescriptorSets");
                long descriptor = handle.get(0);
                var writes = VkWriteDescriptorSet.calloc(2, stack);
                Allocation[] buffers = {input, output};
                for (int i = 0; i < 2; i++) {
                    var info = VkDescriptorBufferInfo.calloc(1, stack);
                    info.get(0).buffer(buffers[i].buffer).offset(0).range(buffers[i].mapped.capacity());
                    writes.get(i).sType$Default().dstSet(descriptor).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
                }
                vkUpdateDescriptorSets(device, writes, null);
                check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(queueFamily), null, handle), "vkCreateCommandPool");
                commandPool = handle.get(0);
                PointerBuffer commandOut = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), commandOut), "vkAllocateCommandBuffers");
                var command = new VkCommandBuffer(commandOut.get(0), device);
                check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptor), null);
                vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(config.sampleCount()));
                vkCmdDispatch(command, config.workgroups(), 1, 1);
                var barriers = VkMemoryBarrier.calloc(1, stack);
                barriers.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barriers, null, null);
                check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateFence");
                fence = handle.get(0);
                var submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command.address()));
                check(vkQueueSubmit(queue, submit, fence), "vkQueueSubmit");
                pending = true;
                int waitResult = vkWaitForFences(device, fence, true, config.fenceTimeoutNanos());
                if (waitResult == VK_SUCCESS) pending = false;
                check(waitResult, "vkWaitForFences (GPU-required smoke; stop this process on timeout/device loss)");
                double[] values = new double[config.sampleCount()];
                for (int i = 0; i < values.length; i++) values[i] = output.mapped.getDouble(i * 8);
                return values;
            }
        }

        @Override public void close() {
            // A failed fence cannot prove buffers idle. Quarantine rather than freeing in-use memory.
            // This API is intended for a dedicated smoke process; caller must exit nonzero on failure.
            if (pending) return;
            if (device != null) {
                if (fence != 0) vkDestroyFence(device, fence, null);
                if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
                if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
                if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
                if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
                if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
                if (shader != 0) vkDestroyShaderModule(device, shader, null);
                for (Allocation allocation : allocations) {
                    vkUnmapMemory(device, allocation.memory);
                    vkDestroyBuffer(device, allocation.buffer, null);
                    vkFreeMemory(device, allocation.memory, null);
                }
                vkDestroyDevice(device, null);
                device = null;
            }
            if (instance != null) { vkDestroyInstance(instance, null); instance = null; }
        }
    }

    private record Allocation(long buffer, long memory, ByteBuffer mapped) {}
}
