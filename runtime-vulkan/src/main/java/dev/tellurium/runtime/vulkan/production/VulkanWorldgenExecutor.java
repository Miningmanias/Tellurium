// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.runtime.vulkan.DeviceCapabilities;
import dev.tellurium.runtime.vulkan.Hashes;
import dev.tellurium.runtime.vulkan.ShadercCompiler;
import dev.tellurium.semantic.program.NumericProfile;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFloatControlsProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST;
import static org.lwjgl.vulkan.VK10.VK_TIMEOUT;
import static org.lwjgl.vulkan.VK10.VK_TRUE;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetCommandPool;
import static org.lwjgl.vulkan.VK10.vkResetDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * Persistent in-process Vulkan execution for the emitted dense worldgen ABI.
 *
 * <p>This class owns one instance/device/queue for its lifetime. Each request
 * borrows a bounded storage/command slot only after its previous fence proved
 * completion. A failed submission quarantines that slot and makes the session
 * terminal, so an active buffer is never reused. The executor deliberately exposes
 * only the state-ID output emitted by {@link WorldgenShaderCompiler}; it is not
 * a claim that aquifer, ore, downstream stages, or Minecraft commit semantics
 * have already been qualified.</p>
 *
 * <p>Construction is the explicit native boundary. CPU-only callers should
 * never construct this class.</p>
 */
public final class VulkanWorldgenExecutor implements AutoCloseable {
    public enum State { READY, LOST, CLOSED }
    private static final long FENCE_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int ABI_WORDS_PER_POINT = 4;
    private static final int OUTPUT_WORDS_PER_POINT = 1;
    /** Maximum number of elements submitted in one driver-visible dispatch. */
    public static final int MAX_DISPATCH_ELEMENTS = 16_384;
    /** Conservative default; wider batches are an explicit prototype setting. */
    public static final int DEFAULT_DISPATCH_ELEMENTS = 4_096;
    /** Default native buffer budget used by standalone diagnostics. */
    public static final long DEFAULT_NATIVE_BUDGET_BYTES = 256L * 1024L * 1024L;

    public record Request(WorldgenShaderCompiler.Shader shader, int[] coordinates,
                          int defaultStateId, int airStateId, int invalidStateId,
                          int[] allowedStateIds) {
        public Request(WorldgenShaderCompiler.Shader shader, int[] coordinates,
                       int defaultStateId, int airStateId, int invalidStateId) {
            this(shader, coordinates, defaultStateId, airStateId, invalidStateId,
                    new int[]{defaultStateId, airStateId, invalidStateId});
        }

        public Request {
            Objects.requireNonNull(shader, "shader");
            Objects.requireNonNull(coordinates, "coordinates");
            Objects.requireNonNull(allowedStateIds, "allowedStateIds");
            if (shader.profile() != NumericProfile.GPU_IEEE_BITS && shader.profile() != NumericProfile.GPU_NATIVE_DRAFT) {
                throw new IllegalArgumentException("Vulkan worldgen executor requires GPU_IEEE_BITS");
            }
            if (coordinates.length == 0 || coordinates.length % 3 != 0) {
                throw new IllegalArgumentException("Coordinates must contain one x/y/z triple per element");
            }
            coordinates = coordinates.clone();
            if (defaultStateId < 0 || airStateId < 0 || invalidStateId < 0) {
                throw new IllegalArgumentException("State IDs must be non-negative");
            }
            allowedStateIds = allowedStateIds.clone();
            if (allowedStateIds.length == 0) {
                throw new IllegalArgumentException("At least one allowed state ID is required");
            }
            for (int stateId : allowedStateIds) {
                if (stateId < 0) throw new IllegalArgumentException("Allowed state IDs must be non-negative");
            }
            if (!containsStateId(allowedStateIds, defaultStateId)
                    || !containsStateId(allowedStateIds, airStateId)
                    || !containsStateId(allowedStateIds, invalidStateId)) {
                throw new IllegalArgumentException("Allowed state IDs must include default, air and invalid IDs");
            }
        }

        public int elementCount() { return coordinates.length / 3; }

        public int[] coordinates() { return coordinates.clone(); }

        public int[] allowedStateIds() { return allowedStateIds.clone(); }
    }

    /**
     * Raw bounded storage-buffer request used by explicit multi-stage GPU
     * programs. The input and output words are opaque to this executor; the
     * shader owns their layout while the executor still owns checked sizing,
     * push-constant identity and device lifetime.
     */
    public static final class RawRequest {
        private final WorldgenShaderCompiler.Shader shader;
        private final int[] inputWords;
        private final int inputWordsPerElement, outputWordsPerElement, elementCount;
        private final int defaultStateId, airStateId, invalidStateId;
        private final boolean disablePipelineOptimization, dontInlineFunctions;
        private final String dontInlinePrefix;

        public RawRequest(WorldgenShaderCompiler.Shader shader, int[] inputWords,
                          int inputWordsPerElement, int outputWordsPerElement,
                          int elementCount) {
            this(shader, inputWords, inputWordsPerElement, outputWordsPerElement,
                    elementCount, 0, 0, 0, configuredPipelineOptimizationDisabled(),
                    configuredDontInlineFunctions(), configuredDontInlinePrefix());
        }

        public RawRequest(WorldgenShaderCompiler.Shader shader, int[] inputWords,
                          int inputWordsPerElement, int outputWordsPerElement,
                          int elementCount, int defaultStateId, int airStateId,
                          int invalidStateId) {
            this(shader, inputWords, inputWordsPerElement, outputWordsPerElement,
                    elementCount, defaultStateId, airStateId, invalidStateId,
                    configuredPipelineOptimizationDisabled(), configuredDontInlineFunctions(),
                    configuredDontInlinePrefix());
        }

        public RawRequest(WorldgenShaderCompiler.Shader shader, int[] inputWords,
                          int inputWordsPerElement, int outputWordsPerElement,
                          int elementCount, int defaultStateId, int airStateId,
                          int invalidStateId, boolean disablePipelineOptimization,
                          boolean dontInlineFunctions, String dontInlinePrefix) {
            this(shader, inputWords, inputWordsPerElement, outputWordsPerElement,
                    elementCount, defaultStateId, airStateId, invalidStateId,
                    disablePipelineOptimization, dontInlineFunctions, dontInlinePrefix, false);
        }

        /** Owned inputs come only from this request or freshly allocated slices. */
        private RawRequest(WorldgenShaderCompiler.Shader shader, int[] inputWords,
                           int inputWordsPerElement, int outputWordsPerElement,
                           int elementCount, int defaultStateId, int airStateId,
                           int invalidStateId, boolean disablePipelineOptimization,
                           boolean dontInlineFunctions, String dontInlinePrefix,
                           boolean inputAlreadyOwned) {
            Objects.requireNonNull(shader, "shader");
            Objects.requireNonNull(inputWords, "inputWords");
            if (shader.profile() != NumericProfile.GPU_IEEE_BITS && shader.profile() != NumericProfile.GPU_NATIVE_DRAFT) {
                throw new IllegalArgumentException("Vulkan worldgen executor requires GPU_IEEE_BITS");
            }
            if (inputWordsPerElement <= 0 || outputWordsPerElement <= 0 || elementCount <= 0
                    || defaultStateId < 0 || airStateId < 0 || invalidStateId < 0) {
                throw new IllegalArgumentException("Invalid raw worldgen buffer geometry");
            }
            long expectedInputWords;
            try {
                expectedInputWords = Math.multiplyExact((long) elementCount, inputWordsPerElement);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Raw worldgen input word count overflow", overflow);
            }
            if (expectedInputWords > inputWords.length) {
                throw new IllegalArgumentException("Raw input words do not cover the element stride");
            }
            if (dontInlineFunctions && (dontInlinePrefix == null || dontInlinePrefix.isBlank())) {
                throw new IllegalArgumentException("Raw DontInline policy requires a nonblank function prefix");
            }
            if (dontInlinePrefix == null) dontInlinePrefix = "";
            if (!dontInlineFunctions) dontInlinePrefix = "";
            this.shader = shader;
            this.inputWords = inputAlreadyOwned ? inputWords : inputWords.clone();
            this.inputWordsPerElement = inputWordsPerElement;
            this.outputWordsPerElement = outputWordsPerElement;
            this.elementCount = elementCount;
            this.defaultStateId = defaultStateId;
            this.airStateId = airStateId;
            this.invalidStateId = invalidStateId;
            this.disablePipelineOptimization = disablePipelineOptimization;
            this.dontInlineFunctions = dontInlineFunctions;
            this.dontInlinePrefix = dontInlinePrefix;
        }

        public WorldgenShaderCompiler.Shader shader() { return shader; }
        public int inputWordsPerElement() { return inputWordsPerElement; }
        public int outputWordsPerElement() { return outputWordsPerElement; }
        public int elementCount() { return elementCount; }
        public int defaultStateId() { return defaultStateId; }
        public int airStateId() { return airStateId; }
        public int invalidStateId() { return invalidStateId; }
        public boolean disablePipelineOptimization() { return disablePipelineOptimization; }
        public boolean dontInlineFunctions() { return dontInlineFunctions; }
        public String dontInlinePrefix() { return dontInlinePrefix; }

        // Preserve the record's component equality, including array identity.
        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RawRequest that)) return false;
            return shader.equals(that.shader) && inputWords == that.inputWords
                    && inputWordsPerElement == that.inputWordsPerElement
                    && outputWordsPerElement == that.outputWordsPerElement
                    && elementCount == that.elementCount && defaultStateId == that.defaultStateId
                    && airStateId == that.airStateId && invalidStateId == that.invalidStateId
                    && disablePipelineOptimization == that.disablePipelineOptimization
                    && dontInlineFunctions == that.dontInlineFunctions
                    && dontInlinePrefix.equals(that.dontInlinePrefix);
        }

        @Override
        public int hashCode() {
            int hash = shader.hashCode();
            hash = 31 * hash + inputWords.hashCode();
            hash = 31 * hash + inputWordsPerElement;
            hash = 31 * hash + outputWordsPerElement;
            hash = 31 * hash + elementCount;
            hash = 31 * hash + defaultStateId;
            hash = 31 * hash + airStateId;
            hash = 31 * hash + invalidStateId;
            hash = 31 * hash + Boolean.hashCode(disablePipelineOptimization);
            hash = 31 * hash + Boolean.hashCode(dontInlineFunctions);
            return 31 * hash + dontInlinePrefix.hashCode();
        }

        @Override
        public String toString() {
            return "RawRequest[shader=" + shader + ", inputWords=" + inputWords
                    + ", inputWordsPerElement=" + inputWordsPerElement
                    + ", outputWordsPerElement=" + outputWordsPerElement
                    + ", elementCount=" + elementCount + ", defaultStateId=" + defaultStateId
                    + ", airStateId=" + airStateId + ", invalidStateId=" + invalidStateId
                    + ", disablePipelineOptimization=" + disablePipelineOptimization
                    + ", dontInlineFunctions=" + dontInlineFunctions
                    + ", dontInlinePrefix=" + dontInlinePrefix + "]";
        }

        public int[] inputWords() { return inputWords.clone(); }

        /** Number of input words, without exposing or copying the backing ABI array. */
        public int inputWordCount() { return inputWords.length; }

        /** Internal dispatch view; callers must not mutate the returned array. */
        int[] inputWordsUnsafe() { return inputWords; }

        /**
         * Returns a copy whose pipeline is compiled with Vulkan's explicit
         * disable-optimization flag.  Large staged worldgen modules use this
         * as a driver-envelope control: it affects pipeline construction only,
         * never shader source, SPIR-V numeric semantics, or dispatch geometry.
         */
        public RawRequest withPipelineOptimizationDisabled(boolean disabled) {
            return new RawRequest(shader, inputWords, inputWordsPerElement, outputWordsPerElement,
                    elementCount, defaultStateId, airStateId, invalidStateId, disabled,
                    dontInlineFunctions, dontInlinePrefix, true);
        }

        /** Returns a copy with an explicit SPIR-V function-control policy. */
        public RawRequest withDontInlineFunctions(boolean enabled, String prefix) {
            return new RawRequest(shader, inputWords, inputWordsPerElement, outputWordsPerElement,
                    elementCount, defaultStateId, airStateId, invalidStateId,
                    disablePipelineOptimization, enabled, prefix, true);
        }

        public RawRequest withDontInlineFunctions(String prefix) {
            return withDontInlineFunctions(true, prefix);
        }

        /**
         * Creates one bounded dispatch view.  Words after the required
         * element prefix are shared stage data and are copied to every view;
         * the raw shader ABI remains responsible for making any pointer words
         * batch-relative.  Keeping this rule here prevents a bounded raw
         * request from silently dropping intermediate GPU stage data.
         */
        public RawRequest slice(int offset, int elements) {
            if (offset < 0 || elements <= 0 || (long) offset + elements > elementCount) {
                throw new IllegalArgumentException("Raw slice is outside the request element range");
            }
            int prefixStart;
            int prefixLength;
            int suffixStart;
            try {
                prefixStart = Math.multiplyExact(offset, inputWordsPerElement);
                prefixLength = Math.multiplyExact(elements, inputWordsPerElement);
                suffixStart = Math.multiplyExact(elementCount, inputWordsPerElement);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Raw slice word count overflow", overflow);
            }
            int suffixLength = inputWords.length - suffixStart;
            int[] sliced = new int[Math.addExact(prefixLength, suffixLength)];
            System.arraycopy(inputWords, prefixStart, sliced, 0, prefixLength);
            if (suffixLength > 0) System.arraycopy(inputWords, suffixStart, sliced, prefixLength, suffixLength);
            return new RawRequest(shader, sliced, inputWordsPerElement, outputWordsPerElement,
                    elements, defaultStateId, airStateId, invalidStateId, disablePipelineOptimization,
                    dontInlineFunctions, dontInlinePrefix, true);
        }
    }

    /** One device-resident stage in a chained raw dispatch. */
    public record RawStage(WorldgenShaderCompiler.Shader shader, int inputWordsPerElement,
                           int outputWordsPerElement, boolean disablePipelineOptimization,
                           boolean dontInlineFunctions, String dontInlinePrefix, int[] uniformInputWords,
                           int exportOffset, int exportWords) {
        public RawStage(WorldgenShaderCompiler.Shader shader, int inputWordsPerElement,
                        int outputWordsPerElement, boolean disablePipelineOptimization,
                        boolean dontInlineFunctions, String dontInlinePrefix) {
            this(shader, inputWordsPerElement, outputWordsPerElement, disablePipelineOptimization,
                    dontInlineFunctions, dontInlinePrefix, new int[0], 0, 0);
        }

        public RawStage(WorldgenShaderCompiler.Shader shader, int inputWordsPerElement,
                        int outputWordsPerElement, boolean disablePipelineOptimization,
                        boolean dontInlineFunctions, String dontInlinePrefix, int[] uniformInputWords) {
            this(shader, inputWordsPerElement, outputWordsPerElement, disablePipelineOptimization,
                    dontInlineFunctions, dontInlinePrefix, uniformInputWords, 0, 0);
        }

        public RawStage(WorldgenShaderCompiler.Shader shader, int inputWordsPerElement,
                        int outputWordsPerElement) {
            this(shader, inputWordsPerElement, outputWordsPerElement,
                    configuredPipelineOptimizationDisabled(), configuredDontInlineFunctions(),
                    configuredDontInlinePrefix());
        }

        public RawStage {
            Objects.requireNonNull(shader, "shader");
            Objects.requireNonNull(uniformInputWords, "uniformInputWords");
            uniformInputWords = uniformInputWords.clone();
            if (shader.profile() != NumericProfile.GPU_IEEE_BITS
                    && shader.profile() != NumericProfile.GPU_NATIVE_DRAFT) {
                throw new IllegalArgumentException("Vulkan worldgen chain requires GPU_IEEE_BITS or GPU_NATIVE_DRAFT");
            }
            if (inputWordsPerElement <= 0 || outputWordsPerElement <= 0) {
                throw new IllegalArgumentException("Invalid chained raw stage geometry");
            }
            if (exportOffset < 0 || exportWords < 0 || exportWords > outputWordsPerElement
                    || exportOffset > outputWordsPerElement - exportWords
                    || (exportWords == 0 && exportOffset != 0)) {
                throw new IllegalArgumentException("Invalid resident intermediate export slice");
            }
            if (dontInlineFunctions && (dontInlinePrefix == null || dontInlinePrefix.isBlank())) {
                throw new IllegalArgumentException("Raw DontInline policy requires a nonblank function prefix");
            }
            if (dontInlinePrefix == null || !dontInlineFunctions) dontInlinePrefix = "";
        }

        @Override public int[] uniformInputWords() { return uniformInputWords.clone(); }

        public RawStage withUniformInputWords(int[] words) {
            return new RawStage(shader, inputWordsPerElement, outputWordsPerElement,
                    disablePipelineOptimization, dontInlineFunctions, dontInlinePrefix, words, exportOffset, exportWords);
        }

        /** Export only this output slice after the chain's fence, before final words. */
        public RawStage withExportedOutput(int offset, int words) {
            return new RawStage(shader, inputWordsPerElement, outputWordsPerElement,
                    disablePipelineOptimization, dontInlineFunctions, dontInlinePrefix,
                    uniformInputWords, offset, words);
        }

        public int inputBufferWordCount(int elements) {
            if (elements <= 0) throw new IllegalArgumentException("Chain elements must be positive");
            return Math.addExact(Math.multiplyExact(elements, inputWordsPerElement), uniformInputWords.length);
        }
    }

    /** Includes each stage's immutable suffix exactly once, not once per row. */
    public static long rawChainBufferBytes(int elements, List<RawStage> stages) {
        if (elements <= 0 || stages.isEmpty()) throw new IllegalArgumentException("Empty chain geometry");
        long words = 0;
        int previousOutput = stages.get(0).inputWordsPerElement();
        for (RawStage stage : stages) {
            if (stage.inputWordsPerElement() != previousOutput) {
                throw new IllegalArgumentException("Incompatible chained stage strides");
            }
            words = Math.addExact(words, stage.inputBufferWordCount(elements));
            previousOutput = stage.outputWordsPerElement();
        }
        words = Math.addExact(words, Math.multiplyExact((long) elements, previousOutput));
        return Math.multiplyExact(words, Integer.BYTES);
    }

    public static int rawChainResultWords(List<RawStage> stages) {
        if (stages.isEmpty()) throw new IllegalArgumentException("Empty raw chain");
        int words = stages.get(stages.size() - 1).outputWordsPerElement();
        for (RawStage stage : stages) words = Math.addExact(words, stage.exportWords());
        return words;
    }

    private static boolean configuredPipelineOptimizationDisabled() {
        return Boolean.getBoolean("tellurium.vulkan.disablePipelineOptimization");
    }

    private static boolean configuredDontInlineFunctions() {
        return Boolean.getBoolean("tellurium.shaderc.dontInline");
    }

    private static String configuredDontInlinePrefix() {
        return configuredDontInlineFunctions()
                ? System.getProperty("tellurium.shaderc.dontInlinePrefix", "wg_") : "";
    }

    /** Device-produced raw words from one bounded dispatch or batch. */
    public record RawResult(int[] outputWords, DeviceCapabilities device, String shaderHash,
                            String spirvHash, int elementCount, int outputWordsPerElement) {
        public RawResult {
            Objects.requireNonNull(outputWords, "outputWords");
            Objects.requireNonNull(device, "device");
            if (shaderHash == null || shaderHash.isBlank() || spirvHash == null || spirvHash.isBlank()
                    || elementCount <= 0 || outputWordsPerElement <= 0) {
                throw new IllegalArgumentException("Invalid Vulkan raw worldgen result");
            }
            try {
                if (outputWords.length != Math.multiplyExact(elementCount, outputWordsPerElement)) {
                    throw new IllegalArgumentException("Raw result words do not match element stride");
                }
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Raw worldgen output word count overflow", overflow);
            }
            outputWords = outputWords.clone();
        }

        public int[] outputWords() { return outputWords.clone(); }

        /** Internal batched-copy view; callers must not mutate the returned array. */
        int[] outputWordsUnsafe() { return outputWords; }
    }

    public record Result(int[] stateIds, DeviceCapabilities device, String shaderHash,
                         String spirvHash, int elementCount) {
        public Result {
            Objects.requireNonNull(stateIds, "stateIds");
            Objects.requireNonNull(device, "device");
            if (shaderHash == null || shaderHash.isBlank() || spirvHash == null || spirvHash.isBlank()
                    || elementCount <= 0 || stateIds.length != elementCount) {
                throw new IllegalArgumentException("Invalid Vulkan worldgen result");
            }
            stateIds = stateIds.clone();
        }

        public int[] stateIds() { return stateIds.clone(); }
    }

    /**
     * Successful raw-dispatch measurements for one executor lifetime. These
     * are observability counters only: they include compilation and readback
     * in wall time and make no throughput or speedup claim.
     */
    public record Telemetry(long dispatches, long elements, long inputBytes,
                            long outputBytes, long elapsedNanos, long maxElapsedNanos) {
        public Telemetry {
            if (dispatches < 0 || elements < 0 || inputBytes < 0 || outputBytes < 0
                    || elapsedNanos < 0 || maxElapsedNanos < 0) {
                throw new IllegalArgumentException("Negative Vulkan telemetry");
            }
            if (dispatches == 0 && (elements != 0 || inputBytes != 0 || outputBytes != 0
                    || elapsedNanos != 0 || maxElapsedNanos != 0)) {
                throw new IllegalArgumentException("Telemetry has values without dispatches");
            }
            if (maxElapsedNanos > elapsedNanos) {
                throw new IllegalArgumentException("Telemetry maximum exceeds total");
            }
        }
    }

    /** Allocation/reuse observations, not throughput or qualification evidence. */
    public record StorageTelemetry(long bufferAllocations, long reusedDispatches, long retainedBytes) { }

    /** Successful compilation observations; lifetime scope, never device-kernel time. */
    public record CompilationTelemetry(long shadercCacheHits, long shadercCompilations, long shadercNanos,
                                       long pipelineCacheHits, long pipelineCreations, long pipelineNanos) {
        public CompilationTelemetry {
            if (shadercCacheHits < 0 || shadercCompilations < 0 || shadercNanos < 0
                    || pipelineCacheHits < 0 || pipelineCreations < 0 || pipelineNanos < 0) {
                throw new IllegalArgumentException("Negative Vulkan compilation telemetry");
            }
        }
    }

    private final Session session;
    private final ExecutorService submissionExecutor;
    /*
     * Captured graph stages deliberately use distinct provenance labels, but
     * their source identity is still stable.  Keep enough SPIR-V entries for
     * the repeated normal-noise modules to survive a graph walk; this is Java
     * memory only and is cleared with the executor.
     */
    public static final int DEFAULT_SPIRV_CACHE_ENTRIES = 2048;
    public static final long DEFAULT_SPIRV_CACHE_BYTES = 64L * 1024 * 1024;
    private final SpirvModuleCache spirvCache = new SpirvModuleCache(
            DEFAULT_SPIRV_CACHE_ENTRIES, DEFAULT_SPIRV_CACHE_BYTES);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong telemetryDispatches = new AtomicLong();
    private final AtomicLong telemetryElements = new AtomicLong();
    private final AtomicLong telemetryInputBytes = new AtomicLong();
    private final AtomicLong telemetryOutputBytes = new AtomicLong();
    private final AtomicLong telemetryElapsedNanos = new AtomicLong();
    private final AtomicLong telemetryMaxElapsedNanos = new AtomicLong();
    private final AtomicLong shadercCacheHits = new AtomicLong();
    private final AtomicLong shadercCompilations = new AtomicLong();
    private final AtomicLong shadercNanos = new AtomicLong();

    public VulkanWorldgenExecutor() {
        this(DEFAULT_NATIVE_BUDGET_BYTES);
    }

    /**
     * Creates an executor with an explicit native buffer/quarantine ceiling.
     * The queue owner is serialized, so the budget covers the two active
     * dispatch buffers plus bytes retained after an unproven completion.
     */
    public VulkanWorldgenExecutor(long nativeBudgetBytes) {
        if (nativeBudgetBytes <= 0) {
            throw new IllegalArgumentException("Native Vulkan budget must be positive");
        }
        session = new Session(nativeBudgetBytes);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tellurium-vulkan-submit");
            thread.setDaemon(true);
            return thread;
        };
        submissionExecutor = Executors.newSingleThreadExecutor(factory);
    }

    public DeviceCapabilities device() { return session.capabilities(); }

    /** Retention limits selected at construction; no driver-memory or TPS guarantee. */
    public PipelineCacheLimits pipelineCacheLimits() { return session.pipelineCacheLimits; }

    /** Current native lifecycle state.  LOST is terminal for this executor. */
    public State state() { return session.state(); }

    /** Bytes retained because native completion was not proven. */
    public long quarantinedBytes() { return session.quarantinedBytes(); }

    /** Configured ceiling for active and quarantined native buffer bytes. */
    public long nativeBudgetBytes() { return session.nativeBudgetBytes(); }

    /** Number of reusable native pipeline objects held by the persistent session. */
    public int cachedPipelineCount() { return session.cachedPipelineCount(); }

    public StorageTelemetry storageTelemetry() { return session.storageTelemetry(); }

    public SpirvModuleCache.Telemetry spirvCacheTelemetry() { return spirvCache.telemetry(); }

    public CompilationTelemetry compilationTelemetry() {
        return new CompilationTelemetry(shadercCacheHits.get(), shadercCompilations.get(), shadercNanos.get(),
                session.pipelineCacheHits.get(), session.pipelineCreations.get(), session.pipelineNanos.get());
    }

    /**
     * Reclaims compiled pipeline objects at an explicit stage boundary.
     *
     * <p>Some drivers retain compiler/JIT working sets after individual
     * pipeline destruction until the device reaches an idle point. Large
     * captured graphs can legitimately compile hundreds of short-lived
     * modules, so staged callers may use this bounded maintenance hook between
     * independent stages without destroying the persistent device.</p>
     */
    public void reclaimPipelines() {
        if (closed.get()) throw new IllegalStateException("Vulkan executor is closed");
        session.reclaimPipelines();
    }

    /**
     * Recreates the native device at an explicit batch/stage boundary.
     *
     * <p>This is intentionally separate from ordinary pipeline reclamation.
     * A few development drivers retain shader compiler state for the lifetime
     * of the Vulkan device, even after all pipeline handles are destroyed. A
     * staged diagnostic can opt into this stronger boundary while preserving
     * the executor, its Java-side contract, and its SPIR-V cache.</p>
     */
    public void recreateDevice() {
        if (closed.get()) throw new IllegalStateException("Vulkan executor is closed");
        session.recreateDevice();
    }

    /** Returns successful raw-dispatch measurements collected by this executor. */
    public Telemetry telemetry() {
        return new Telemetry(telemetryDispatches.get(), telemetryElements.get(), telemetryInputBytes.get(),
                telemetryOutputBytes.get(), telemetryElapsedNanos.get(), telemetryMaxElapsedNanos.get());
    }

    /** Clears only observability counters; it does not tear down the Vulkan session or cache. */
    public void resetTelemetry() {
        telemetryDispatches.set(0);
        telemetryElements.set(0);
        telemetryInputBytes.set(0);
        telemetryOutputBytes.set(0);
        telemetryElapsedNanos.set(0);
        telemetryMaxElapsedNanos.set(0);
    }

    /** Submit one bounded request to the sole queue owner. */
    public CompletionStage<Result> submit(Request request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Vulkan executor is closed"));
        try {
            return CompletableFuture.supplyAsync(() -> execute(request), submissionExecutor);
        } catch (RuntimeException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    /** Synchronous form used by bounded diagnostics and loader-owned callers. */
    public Result execute(Request request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) throw new IllegalStateException("Vulkan executor is closed");
        if (request.elementCount() > MAX_DISPATCH_ELEMENTS) {
            throw new IllegalArgumentException("Dispatch exceeds " + MAX_DISPATCH_ELEMENTS
                    + " elements; use executeBatched for dense work");
        }
        int[] coordinates = request.coordinates();
        int count = request.elementCount();
        int[] inputWords = new int[Math.multiplyExact(count, ABI_WORDS_PER_POINT)];
        for (int index = 0; index < count; index++) {
            int coordinate = index * 3;
            int word = index * ABI_WORDS_PER_POINT;
            inputWords[word] = coordinates[coordinate];
            inputWords[word + 1] = coordinates[coordinate + 1];
            inputWords[word + 2] = coordinates[coordinate + 2];
        }
        RawResult raw = executeRaw(new RawRequest(request.shader(), inputWords, ABI_WORDS_PER_POINT,
                OUTPUT_WORDS_PER_POINT, count, request.defaultStateId(), request.airStateId(), request.invalidStateId()));
        int[] stateIds = raw.outputWords();
        int[] allowedStateIds = request.allowedStateIds();
        for (int stateId : stateIds) {
            if (!containsStateId(allowedStateIds, stateId)) {
                throw new IllegalStateException("Device returned state ID outside the dispatch ABI: " + stateId);
            }
        }
        return new Result(stateIds, raw.device(), raw.shaderHash(), raw.spirvHash(), count);
    }

    /** Execute one bounded raw request and return only device-produced words. */
    public RawResult executeRaw(RawRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.shader().profile() == NumericProfile.GPU_NATIVE_DRAFT && !session.capabilities.float64()) {
            throw new IllegalStateException("GPU_NATIVE_DRAFT requires hardware shaderFloat64");
        }
        if (closed.get()) throw new IllegalStateException("Vulkan executor is closed");
        if (request.elementCount() > MAX_DISPATCH_ELEMENTS) {
            throw new IllegalArgumentException("Dispatch exceeds " + MAX_DISPATCH_ELEMENTS
                    + " elements; use executeRawBatched for dense work");
        }
        long started = System.nanoTime();
        byte[] spirv = compiled(request.shader(), request.dontInlineFunctions(), request.dontInlinePrefix());
        RawResult result = session.dispatchRaw(spirv, request.shader().localSize(), request.inputWordsUnsafe(),
                request.inputWordsPerElement(), request.outputWordsPerElement(), request.elementCount(),
                request.defaultStateId(), request.airStateId(), request.invalidStateId(),
                request.disablePipelineOptimization(), request.shader().source());
        long elapsed = Math.max(0L, System.nanoTime() - started);
        recordTelemetry(request, elapsed);
        return result;
    }

    private void recordTelemetry(RawRequest request, long elapsedNanos) {
        long inputBytes = Math.multiplyExact((long) request.inputWordCount(), Integer.BYTES);
        long outputBytes = Math.multiplyExact(
                Math.multiplyExact((long) request.elementCount(), request.outputWordsPerElement()), Integer.BYTES);
        telemetryDispatches.incrementAndGet();
        telemetryElements.addAndGet(request.elementCount());
        telemetryInputBytes.addAndGet(inputBytes);
        telemetryOutputBytes.addAndGet(outputBytes);
        telemetryElapsedNanos.addAndGet(elapsedNanos);
        telemetryMaxElapsedNanos.accumulateAndGet(elapsedNanos, Math::max);
    }

    /** Execute a raw request in bounded, non-overlapping queue submissions. */
    public RawResult executeRawBatched(RawRequest request, int maxElements) {
        Objects.requireNonNull(request, "request");
        if (maxElements <= 0 || maxElements > MAX_DISPATCH_ELEMENTS) {
            throw new IllegalArgumentException("Batch size must be in [1, " + MAX_DISPATCH_ELEMENTS + "]");
        }
        if (request.elementCount() <= maxElements) return executeRaw(request);
        int[] outputWords = new int[Math.multiplyExact(request.elementCount(), request.outputWordsPerElement())];
        DeviceCapabilities device = null;
        String shaderHash = null;
        String spirvHash = null;
        for (int offset = 0; offset < request.elementCount(); offset += maxElements) {
            int elements = Math.min(maxElements, request.elementCount() - offset);
            RawResult result = executeRaw(request.slice(offset, elements));
            int outputStart = Math.multiplyExact(offset, request.outputWordsPerElement());
            System.arraycopy(result.outputWordsUnsafe(), 0, outputWords, outputStart,
                    Math.multiplyExact(elements, request.outputWordsPerElement()));
            if (device == null) {
                device = result.device();
                shaderHash = result.shaderHash();
                spirvHash = result.spirvHash();
            } else if (!device.equals(result.device()) || !shaderHash.equals(result.shaderHash())
                    || !spirvHash.equals(result.spirvHash())) {
                throw new IllegalStateException("Batched raw Vulkan result provenance changed between slices");
            }
        }
        return new RawResult(outputWords, device, shaderHash, spirvHash,
                request.elementCount(), request.outputWordsPerElement());
    }

    /**
     * Executes a sequence of raw stages without exposing intermediate results
     * to Java. Each stage receives the previous stage's storage buffer and
     * writes a new storage buffer; only explicitly requested intermediate
     * slices and the final buffer are read back, after the same fence.
     */
    public RawResult executeRawChain(int[] inputWords, int inputWordsPerElement, int elementCount,
                                     int defaultStateId, int airStateId, int invalidStateId,
                                     List<RawStage> stages) {
        Objects.requireNonNull(inputWords, "inputWords");
        Objects.requireNonNull(stages, "stages");
        long started = System.nanoTime();
        if (inputWordsPerElement <= 0 || elementCount <= 0 || stages.isEmpty()) {
            throw new IllegalArgumentException("A raw chain requires positive geometry and at least one stage");
        }
        if (elementCount > MAX_DISPATCH_ELEMENTS) {
            throw new IllegalArgumentException("Dispatch exceeds " + MAX_DISPATCH_ELEMENTS
                    + " elements; use executeRawChainBatched for dense work");
        }
        long expectedInputWords = Math.multiplyExact((long) elementCount, inputWordsPerElement);
        if (expectedInputWords != inputWords.length) {
            throw new IllegalArgumentException("Raw chain input length does not match its element stride");
        }
        List<RawStage> chain = List.copyOf(stages);
        int currentWords = inputWordsPerElement;
        List<byte[]> binaries = new ArrayList<>(chain.size());
        StringBuilder sourceIdentity = new StringBuilder();
        int totalSpirvBytes = 0;
        for (RawStage stage : chain) {
            if (stage.inputWordsPerElement() != currentWords) {
                throw new IllegalArgumentException("Raw chain stage input stride "
                        + stage.inputWordsPerElement() + " does not match previous output " + currentWords);
            }
            byte[] binary = compiled(stage.shader(), stage.dontInlineFunctions(), stage.dontInlinePrefix());
            binaries.add(binary);
            totalSpirvBytes = Math.addExact(totalSpirvBytes, binary.length);
            sourceIdentity.append(stage.shader().profile()).append('\n')
                    .append(stage.shader().localSize()).append('\n')
                    .append(stage.disablePipelineOptimization()).append('\n')
                    .append(stage.dontInlineFunctions()).append('\n')
                    .append(stage.dontInlinePrefix()).append('\n')
                    .append(Arrays.toString(stage.uniformInputWords)).append('\n')
                    .append(stage.exportOffset()).append(':').append(stage.exportWords()).append('\n')
                    .append(stage.shader().source()).append('\n');
            currentWords = stage.outputWordsPerElement();
        }
        byte[] combinedSpirv = new byte[totalSpirvBytes];
        int spirvOffset = 0;
        for (byte[] binary : binaries) {
            System.arraycopy(binary, 0, combinedSpirv, spirvOffset, binary.length);
            spirvOffset += binary.length;
        }
        RawResult result = session.dispatchRawChain(chain, binaries, inputWords, inputWordsPerElement,
                elementCount, defaultStateId, airStateId, invalidStateId,
                Hashes.sha256(sourceIdentity.toString().getBytes(StandardCharsets.UTF_8)),
                Hashes.sha256(combinedSpirv));
        long inputBytes = Math.multiplyExact((long) inputWords.length, Integer.BYTES);
        for (RawStage stage : chain) {
            inputBytes = Math.addExact(inputBytes,
                    Math.multiplyExact((long) stage.uniformInputWords.length, Integer.BYTES));
        }
        long elapsed = Math.max(0L, System.nanoTime() - started);
        // Kernel dispatches and GPU-covered rows remain separate from host
        // traffic: intermediate storage is device-produced, never an upload.
        telemetryDispatches.addAndGet(chain.size());
        telemetryElements.addAndGet(Math.multiplyExact((long) elementCount, chain.size()));
        telemetryInputBytes.addAndGet(inputBytes);
        telemetryOutputBytes.addAndGet(Math.multiplyExact((long) result.outputWords.length, Integer.BYTES));
        telemetryElapsedNanos.addAndGet(elapsed);
        telemetryMaxElapsedNanos.accumulateAndGet(elapsed, Math::max);
        return result;
    }

    /** Execute a chained raw program in bounded, non-overlapping submissions. */
    public RawResult executeRawChainBatched(int[] inputWords, int inputWordsPerElement, int elementCount,
                                            int defaultStateId, int airStateId, int invalidStateId,
                                            List<RawStage> stages, int maxElements) {
        Objects.requireNonNull(inputWords, "inputWords");
        if (maxElements <= 0 || maxElements > MAX_DISPATCH_ELEMENTS) {
            throw new IllegalArgumentException("Batch size must be in [1, " + MAX_DISPATCH_ELEMENTS + "]");
        }
        // The whole input is checked before it is cut up: copyOfRange would pad a short last slice with zeros and
        // never look at words beyond the last row, turning a malformed input into batches that each look right.
        if (elementCount <= 0 || inputWordsPerElement <= 0) {
            throw new IllegalArgumentException("Element count and words per element must be positive");
        }
        if (inputWords.length != Math.multiplyExact((long) elementCount, (long) inputWordsPerElement)) {
            throw new IllegalArgumentException("Raw chain input has " + inputWords.length + " words for " + elementCount
                    + " elements of " + inputWordsPerElement);
        }
        if (elementCount <= maxElements) {
            return executeRawChain(inputWords, inputWordsPerElement, elementCount,
                    defaultStateId, airStateId, invalidStateId, stages);
        }
        int finalWords = rawChainResultWords(stages);
        int[] outputWords = new int[Math.multiplyExact(elementCount, finalWords)];
        DeviceCapabilities device = null;
        String shaderHash = null;
        String spirvHash = null;
        for (int offset = 0; offset < elementCount; offset += maxElements) {
            int elements = Math.min(maxElements, elementCount - offset);
            int start = Math.multiplyExact(offset, inputWordsPerElement);
            int end = Math.multiplyExact(offset + elements, inputWordsPerElement);
            RawResult result = executeRawChain(Arrays.copyOfRange(inputWords, start, end),
                    inputWordsPerElement, elements, defaultStateId, airStateId, invalidStateId, stages);
            int outputStart = Math.multiplyExact(offset, finalWords);
            System.arraycopy(result.outputWordsUnsafe(), 0, outputWords, outputStart,
                    Math.multiplyExact(elements, finalWords));
            if (device == null) {
                device = result.device();
                shaderHash = result.shaderHash();
                spirvHash = result.spirvHash();
            } else if (!device.equals(result.device()) || !shaderHash.equals(result.shaderHash())
                    || !spirvHash.equals(result.spirvHash())) {
                throw new IllegalStateException("Batched chained Vulkan result provenance changed between slices");
            }
        }
        return new RawResult(outputWords, device, shaderHash, spirvHash,
                elementCount, finalWords);
    }

    /** Execute a dense request as bounded, non-overlapping queue submissions. */
    public Result executeBatched(Request request) {
        return executeBatched(request, DEFAULT_DISPATCH_ELEMENTS);
    }

    /**
     * Execute a dense request in slices no larger than the driver's dispatch
     * bound. Results are published only after every slice completes.
     */
    public Result executeBatched(Request request, int maxElements) {
        Objects.requireNonNull(request, "request");
        if (maxElements <= 0 || maxElements > MAX_DISPATCH_ELEMENTS) {
            throw new IllegalArgumentException("Batch size must be in [1, " + MAX_DISPATCH_ELEMENTS + "]");
        }
        if (request.elementCount() <= maxElements) return execute(request);

        int[] coordinates = request.coordinates();
        int[] stateIds = new int[request.elementCount()];
        DeviceCapabilities device = null;
        String shaderHash = null;
        String spirvHash = null;
        for (int offset = 0; offset < request.elementCount(); offset += maxElements) {
            int elements = Math.min(maxElements, request.elementCount() - offset);
            int[] slice = Arrays.copyOfRange(coordinates, offset * 3, (offset + elements) * 3);
            Result result = execute(new Request(request.shader(), slice, request.defaultStateId(),
                    request.airStateId(), request.invalidStateId(), request.allowedStateIds()));
            System.arraycopy(result.stateIds(), 0, stateIds, offset, elements);
            if (device == null) {
                device = result.device();
                shaderHash = result.shaderHash();
                spirvHash = result.spirvHash();
            } else if (!device.equals(result.device()) || !shaderHash.equals(result.shaderHash())
                    || !spirvHash.equals(result.spirvHash())) {
                throw new IllegalStateException("Batched Vulkan result provenance changed between slices");
            }
        }
        return new Result(stateIds, device, shaderHash, spirvHash, stateIds.length);
    }

    private byte[] compiled(WorldgenShaderCompiler.Shader shader, boolean dontInline, String dontInlinePrefix) {
        // The source, numeric profile, local size and SPIR-V policy fully
        // determine the compiled module.  Staged callers intentionally give
        // otherwise-identical divider/noise modules different provenance
        // labels; including that label here defeats reuse and needlessly
        // re-runs shaderc for every captured stage.
        String key = Hashes.sha256((shader.profile() + "\n"
                + shader.localSize() + "\n" + dontInline + "\n" + dontInlinePrefix
                + "\n" + shader.source()).getBytes(StandardCharsets.UTF_8));
        byte[] cached = spirvCache.get(key);
        if (cached != null) {
            shadercCacheHits.incrementAndGet();
            return cached;
        }
        long started = System.nanoTime();
        byte[] binary = new ShadercCompiler().compile(shader.source(), dontInline, dontInlinePrefix);
        var inspection = new SpirvNumericContract().inspectSpirv(binary, shader.profile());
        if (!inspection.valid()) {
            throw new IllegalStateException("Generated worldgen SPIR-V violates " + shader.profile() + ": "
                    + inspection.violations());
        }
        shadercCompilations.incrementAndGet();
        shadercNanos.addAndGet(Math.max(0L, System.nanoTime() - started));
        spirvCache.put(key, binary);
        return binary;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        submissionExecutor.shutdown();
        boolean stopped = false;
        try {
            stopped = submissionExecutor.awaitTermination(10, TimeUnit.SECONDS);
            if (!stopped) {
                submissionExecutor.shutdownNow();
                stopped = submissionExecutor.awaitTermination(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            submissionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            session.quarantineWithoutTeardown("interrupted while stopping Vulkan submission worker");
            throw new IllegalStateException("Interrupted while stopping Vulkan submission worker", interrupted);
        }
        if (!stopped) {
            // A driver call can outlive Java's executor deadline.  Do not enter
            // synchronized native teardown here: it could wait on the same
            // stuck worker and turn a bounded close into an unbounded one.
            session.quarantineWithoutTeardown("Vulkan submission worker did not stop before close deadline");
            throw new IllegalStateException("Vulkan submission worker did not stop");
        }
        try {
            session.close();
        } finally {
            // SPIR-V byte arrays are Java-side cache entries, not native
            // handles. Drop them as part of executor shutdown so a closed
            // provider does not retain large staged modules until GC happens
            // to collect the whole executor.
            spirvCache.clear();
        }
    }

    private static void check(int status, String operation) {
        if (status != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult=" + status);
    }

    private static final class Session implements AutoCloseable {
        private static final String PIPELINE_ABI = "tellurium-captured-v3-compute-flags";
        private VkInstance instance;
        private VkDevice device;
        private VkPhysicalDevice physical;
        private VkQueue queue;
        private int queueFamily;
        private DeviceCapabilities capabilities;
        private final PipelineCacheLimits pipelineCacheLimits = PipelineCacheLimits.configured();
        private final PipelineCache<CompiledPipeline> pipelineCache = new PipelineCache<>(pipelineCacheLimits.ordinaryEntries());
        /* Stable host-staged Perlin/combine modules survive ordinary stage
         * reclamation. Device recreation and close still clear both caches. */
        private final PipelineCache<CompiledPipeline> reusablePipelineCache =
                new PipelineCache<>(pipelineCacheLimits.reusableEntries());
        private final QuarantineLedger quarantine = new QuarantineLedger();
        private final AtomicLong quarantineSequence = new AtomicLong();
        private final long nativeBudgetBytes;
        private volatile State state = State.READY;
        private volatile String lossReason = "";
        private volatile boolean activeSubmission;
        private DispatchResources dispatchResources;
        private long bufferAllocations;
        private long reusedDispatches;
        private final AtomicLong pipelineCacheHits = new AtomicLong();
        private final AtomicLong pipelineCreations = new AtomicLong();
        private final AtomicLong pipelineNanos = new AtomicLong();
        private boolean closed;

        private Session(long nativeBudgetBytes) {
            if (nativeBudgetBytes <= 0) {
                throw new IllegalArgumentException("Native Vulkan budget must be positive");
            }
            this.nativeBudgetBytes = nativeBudgetBytes;
            initializeDevice();
        }

        Session() {
            this(DEFAULT_NATIVE_BUDGET_BYTES);
        }

        private void initializeDevice() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var application = VkApplicationInfo.calloc(stack).sType$Default()
                        .pApplicationName(stack.UTF8("Tellurium persistent worldgen"))
                        .applicationVersion(org.lwjgl.vulkan.VK10.VK_MAKE_VERSION(0, 2, 0))
                        .apiVersion(org.lwjgl.vulkan.VK10.VK_MAKE_VERSION(1, 2, 0));
                var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application);
                PointerBuffer out = stack.mallocPointer(1);
                check(vkCreateInstance(create, null, out), "vkCreateInstance(worldgen)");
                instance = new VkInstance(out.get(0), create);
                var count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices/count");
                if (count.get(0) == 0) throw new IllegalStateException("No Vulkan physical devices");
                PointerBuffer physicalDevices = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, physicalDevices), "vkEnumeratePhysicalDevices");
                var candidates = new ArrayList<VkPhysicalDevice>(count.get(0));
                var inspected = new ArrayList<DeviceCapabilities>(count.get(0));
                for (int i = 0; i < count.get(0); i++) {
                    VkPhysicalDevice candidate = new VkPhysicalDevice(physicalDevices.get(i), instance);
                    candidates.add(candidate);
                    inspected.add(inspect(candidate, stack));
                }
                int selected = select(inspected);
                physical = candidates.get(selected);
                capabilities = inspected.get(selected);
                queueFamily = capabilities.computeQueueFamily();
                openDevice(stack);
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }

        DeviceCapabilities capabilities() { return capabilities; }

        State state() { return state; }
        long quarantinedBytes() { return quarantine.bytes(); }
        long nativeBudgetBytes() { return nativeBudgetBytes; }
        int cachedPipelineCount() { return pipelineCache.size() + reusablePipelineCache.size(); }

        synchronized StorageTelemetry storageTelemetry() {
            return new StorageTelemetry(bufferAllocations, reusedDispatches, dispatchStorageBytes());
        }

        synchronized void reclaimPipelines() {
            if (closed || state != State.READY || device == null) {
                throw new IllegalStateException("Vulkan session is " + state
                        + (lossReason.isBlank() ? "" : ": " + lossReason));
            }
            int status = vkDeviceWaitIdle(device);
            if (status != VK_SUCCESS) {
                markLost("Vulkan device idle wait failed while reclaiming pipelines with VkResult=" + status);
                throw new IllegalStateException("vkDeviceWaitIdle(worldgen reclaim) failed with VkResult=" + status);
            }
            for (CompiledPipeline pipeline : pipelineCache.clear()) destroyPipeline(pipeline);
        }

        synchronized void recreateDevice() {
            if (closed || state != State.READY || device == null) {
                throw new IllegalStateException("Vulkan session is " + state
                        + (lossReason.isBlank() ? "" : ": " + lossReason));
            }
            if (activeSubmission) {
                throw new IllegalStateException("Cannot recreate Vulkan device during an active submission");
            }
            int status = vkDeviceWaitIdle(device);
            if (status != VK_SUCCESS) {
                markLost("Vulkan device idle wait failed while recreating with VkResult=" + status);
                throw new IllegalStateException("vkDeviceWaitIdle(worldgen recreate) failed with VkResult=" + status);
            }
            for (CompiledPipeline pipeline : pipelineCache.clear()) destroyPipeline(pipeline);
            for (CompiledPipeline pipeline : reusablePipelineCache.clear()) destroyPipeline(pipeline);
            releaseDispatchResources();
            vkDestroyDevice(device, null);
            device = null;
            queue = null;
            if (instance != null) {
                vkDestroyInstance(instance, null);
                instance = null;
            }
            state = State.READY;
            lossReason = "";
            initializeDevice();
        }

        /**
         * Marks this session unusable without touching Vulkan handles.  This
         * path is intentionally non-blocking and is used when a Java worker
         * did not stop before the shutdown deadline.
         */
        void quarantineWithoutTeardown(String reason) {
            String detail = reason == null || reason.isBlank() ? "native shutdown was not proven safe" : reason;
            state = State.LOST;
            lossReason = detail;
            quarantine.quarantine("session-" + quarantineSequence.incrementAndGet(), 0L, detail);
            closed = true;
        }

        private void markLost(String reason) {
            state = State.LOST;
            lossReason = reason == null || reason.isBlank() ? "native completion was not proven" : reason;
        }

        private void openDevice(MemoryStack stack) {
            var queues = org.lwjgl.vulkan.VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0f));
            // Integer-profile shaders still contain no floating instructions.
            // Enable supported FP64 for the explicitly labelled draft route.
            var create = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues)
                    .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack).shaderFloat64(capabilities.float64()));
            PointerBuffer out = stack.mallocPointer(1);
            check(vkCreateDevice(physical, create, null, out), "vkCreateDevice(worldgen)");
            device = new VkDevice(out.get(0), physical, create);
            vkGetDeviceQueue(device, queueFamily, 0, out);
            queue = new VkQueue(out.get(0), device);
        }

        private static int select(List<DeviceCapabilities> values) {
            int selected = -1;
            for (int i = 0; i < values.size(); i++) {
                var decision = new CapabilityQualifier().qualify(values.get(i), NumericProfile.GPU_IEEE_BITS);
                if (decision.supported() && (selected < 0 || score(values.get(i)) > score(values.get(selected)))) {
                    selected = i;
                }
            }
            if (selected < 0) throw new IllegalStateException("No qualified Vulkan worldgen device: " + values);
            return selected;
        }

        private static int score(DeviceCapabilities value) {
            return value.deviceType() == 2 ? 3 : value.deviceType() == 1 ? 2 : 1;
        }

        private static DeviceCapabilities inspect(VkPhysicalDevice candidate, MemoryStack stack) {
            var controls = VkPhysicalDeviceFloatControlsProperties.calloc(stack).sType$Default();
            var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(controls.address());
            vkGetPhysicalDeviceProperties2(candidate, props);
            var features = VkPhysicalDeviceFeatures.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures(candidate, features);
            var count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
            int family = -1;
            for (int i = 0; i < families.capacity(); i++) {
                if (families.get(i).queueCount() > 0 && (families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    family = i;
                    break;
                }
            }
            return new DeviceCapabilities(props.properties().deviceNameString(), props.properties().apiVersion(),
                    props.properties().driverVersion(), props.properties().deviceType(), family,
                    features.shaderFloat64(), controls.shaderSignedZeroInfNanPreserveFloat64(),
                    controls.shaderDenormPreserveFloat64(), controls.shaderRoundingModeRTEFloat64());
        }

        synchronized RawResult dispatchRaw(byte[] spirv, int localSize, int[] inputWords,
                        int inputWordsPerElement, int outputWordsPerElement, int count,
                        int defaultStateId, int airStateId, int invalidStateId,
                        boolean disablePipelineOptimization, String source) {
            if (closed || state != State.READY || device == null) {
                throw new IllegalStateException("Vulkan session is " + state
                        + (lossReason.isBlank() ? "" : ": " + lossReason));
            }
            long inputBytes;
            long outputBytes;
            try {
                inputBytes = Math.multiplyExact(Math.multiplyExact((long) inputWords.length, Integer.BYTES), 1L);
                outputBytes = Math.multiplyExact(Math.multiplyExact((long) count, outputWordsPerElement), Integer.BYTES);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Vulkan worldgen buffer size overflow", overflow);
            }
            boolean completed = false;
            boolean submissionAttempted = false;
            activeSubmission = true;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (inputWords.length < Math.multiplyExact(count, inputWordsPerElement)) {
                    throw new IllegalArgumentException("Raw input words do not cover dispatch geometry");
                }
                LongBuffer handle = stack.mallocLong(1);
                CompiledPipeline compiledPipeline = pipelineFor(spirv, localSize, source,
                        disablePipelineOptimization, stack);
                DispatchResources resources = prepareDispatchResources(inputBytes, outputBytes, stack);
                resources.buffers[0].mapped.asIntBuffer().put(inputWords);
                check(vkAllocateDescriptorSets(device,
                        VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(resources.descriptorPool)
                                .pSetLayouts(stack.longs(compiledPipeline.descriptorLayout())), handle),
                        "vkAllocateDescriptorSets(worldgen)");
                long descriptor = handle.get(0);
                var writes = VkWriteDescriptorSet.calloc(2, stack);
                Allocation[] buffers = {resources.buffers[0], resources.buffers[1]};
                long[] ranges = {inputBytes, outputBytes};
                for (int i = 0; i < buffers.length; i++) {
                    var info = VkDescriptorBufferInfo.calloc(1, stack);
                    // Shader-visible array length is this request's range,
                    // never the larger retained capacity or stale suffix.
                    info.get(0).buffer(buffers[i].buffer).offset(0).range(ranges[i]);
                    writes.get(i).sType$Default().dstSet(descriptor).dstBinding(i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                            .pBufferInfo(info);
                }
                vkUpdateDescriptorSets(device, writes, null);

                VkCommandBuffer command = resources.command;
                check(vkBeginCommandBuffer(command,
                        VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                        "vkBeginCommandBuffer(worldgen)");
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, compiledPipeline.pipeline());
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, compiledPipeline.pipelineLayout(), 0,
                        stack.longs(descriptor), null);
                vkCmdPushConstants(command, compiledPipeline.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                        stack.ints(count, defaultStateId, airStateId, invalidStateId));
                long workgroups = ((long) count + localSize - 1L) / localSize;
                if (workgroups <= 0 || workgroups > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Vulkan dispatch workgroup count is out of range: " + workgroups);
                }
                vkCmdDispatch(command, (int) workgroups, 1, 1);
                var barriers = VkMemoryBarrier.calloc(1, stack);
                barriers.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                        0, barriers, null, null);
                check(vkEndCommandBuffer(command), "vkEndCommandBuffer(worldgen)");
                submissionAttempted = true;
                int submitStatus = vkQueueSubmit(queue,
                        VkSubmitInfo.calloc(stack).sType$Default()
                                .pCommandBuffers(stack.pointers(command.address())), resources.fence);
                if (submitStatus != VK_SUCCESS) {
                    markLost("vkQueueSubmit(worldgen) failed with VkResult=" + submitStatus);
                    throw new IllegalStateException("vkQueueSubmit(worldgen) failed with VkResult=" + submitStatus);
                }
                int waitStatus = vkWaitForFences(device, resources.fence, true, FENCE_TIMEOUT_NANOS);
                if (waitStatus != VK_SUCCESS) {
                    markLost(waitStatus == VK_TIMEOUT
                            ? "Vulkan fence timed out; native resources were quarantined"
                            : waitStatus == VK_ERROR_DEVICE_LOST
                            ? "Vulkan device was lost while waiting for the worldgen fence"
                            : "Vulkan fence wait failed with VkResult=" + waitStatus);
                    throw new IllegalStateException("vkWaitForFences(worldgen) failed with VkResult=" + waitStatus);
                }
                completed = true;
                int[] outputWords = new int[Math.multiplyExact(count, outputWordsPerElement)];
                resources.buffers[1].mapped.asIntBuffer().get(outputWords);
                return new RawResult(outputWords, capabilities,
                        Hashes.sha256(source.getBytes(StandardCharsets.UTF_8)),
                        Hashes.sha256(spirv), count, outputWordsPerElement);
            } finally {
                activeSubmission = false;
                releaseEvicted(completed || !submissionAttempted);
                if (!completed && (submissionAttempted || state == State.LOST)) {
                    String reason = lossReason.isBlank()
                            ? "queue completion was not proven; native resources remain quarantined"
                            : lossReason;
                    quarantine.quarantine("dispatch-" + quarantineSequence.incrementAndGet(),
                            dispatchStorageBytes(), reason);
                } else if (!completed) {
                    // Nothing reached the queue: even a partially initialized
                    // slot is safe to release, but not safe to retain blindly.
                    releaseDispatchResources();
                }
            }
        }

        private long dispatchStorageBytes() {
            if (dispatchResources == null) return 0;
            long bytes = 0;
            for (Allocation allocation : dispatchResources.buffers) {
                if (allocation != null) bytes = Math.addExact(bytes, allocation.mapped.capacity());
            }
            return bytes;
        }

        private DispatchResources prepareDispatchResources(long inputBytes, long outputBytes, MemoryStack stack) {
            return prepareDispatchResources(new long[]{inputBytes, outputBytes}, 1, stack);
        }

        private DispatchResources prepareDispatchResources(long[] requestedBytes, int stages, MemoryStack stack) {
            long[] previousBytes = dispatchResources == null ? new long[0]
                    : new long[dispatchResources.buffers.length];
            for (int index = 0; index < previousBytes.length; index++) {
                Allocation allocation = dispatchResources.buffers[index];
                previousBytes[index] = allocation == null ? 0 : allocation.mapped.capacity();
            }
            long[] capacities = ChainStoragePlan.fit(requestedBytes, previousBytes,
                    nativeBudgetBytes - quarantine.bytes()).capacities();
            boolean buffersChanged = !Arrays.equals(capacities, previousBytes);
            boolean reused = dispatchResources != null && !buffersChanged
                    && dispatchResources.descriptorStages >= stages;
            if (dispatchResources == null) {
                dispatchResources = new DispatchResources();
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateCommandPool(device,
                        VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                                .queueFamilyIndex(queueFamily), null, handle), "vkCreateCommandPool(worldgen reusable)");
                dispatchResources.commandPool = handle.get(0);
                PointerBuffer commandOut = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(device,
                        VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                                .commandPool(dispatchResources.commandPool)
                                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), commandOut),
                        "vkAllocateCommandBuffers(worldgen reusable)");
                dispatchResources.command = new VkCommandBuffer(commandOut.get(0), device);
                check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle),
                        "vkCreateFence(worldgen reusable)");
                dispatchResources.fence = handle.get(0);
            }
            DispatchResources resources = dispatchResources;
            if (buffersChanged) ensureDispatchStorageIdleForRelease(resources);
            if (resources.descriptorStages < stages) {
                if (resources.descriptorPool != 0) vkDestroyDescriptorPool(device, resources.descriptorPool, null);
                resources.descriptorPool = 0;
                resources.descriptorStages = 0;
                var poolSize = VkDescriptorPoolSize.calloc(1, stack);
                poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(Math.multiplyExact(stages, 2));
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateDescriptorPool(device,
                        VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(stages)
                                .pPoolSizes(poolSize), null, handle), "vkCreateDescriptorPool(worldgen reusable)");
                resources.descriptorPool = handle.get(0);
                resources.descriptorStages = stages;
            }
            // Session serialization plus the successful previous fence is the
            // ownership proof. Never reset these objects after a timeout/loss.
            check(vkResetCommandPool(device, resources.commandPool, 0), "vkResetCommandPool(worldgen)");
            check(vkResetDescriptorPool(device, resources.descriptorPool, 0), "vkResetDescriptorPool(worldgen)");
            check(vkResetFences(device, resources.fence), "vkResetFences(worldgen)");
            // Free all changing capacities before allocating either replacement
            // so a shape change cannot transiently exceed the buffer ceiling.
            Allocation[] buffers = new Allocation[capacities.length];
            for (int index = 0; index < resources.buffers.length; index++) {
                if (index < capacities.length && capacities[index] == previousBytes[index]) {
                    buffers[index] = resources.buffers[index];
                } else {
                    free(resources.buffers[index]);
                }
            }
            resources.buffers = buffers;
            for (int index = 0; index < capacities.length; index++) {
                if (resources.buffers[index] == null) resources.buffers[index] = allocate(capacities[index]);
            }
            if (reused) reusedDispatches++;
            return resources;
        }

        private void ensureDispatchStorageIdleForRelease(DispatchResources resources) {
            if (!resources.chainBuffersUsed) return;
            // Retain the target driver's historical chain teardown guard, but
            // only when a capacity change actually frees buffers. Re-recording
            // and reusing the same storage requires only the proven fence.
            int status = vkDeviceWaitIdle(device);
            if (status != VK_SUCCESS) {
                markLost("Vulkan device idle wait failed before chain storage release with VkResult=" + status);
                throw new IllegalStateException("vkDeviceWaitIdle(worldgen chain storage) failed with VkResult=" + status);
            }
            resources.chainBuffersUsed = false;
        }

        private void releaseDispatchResources() {
            DispatchResources resources = dispatchResources;
            if (resources == null) return;
            try {
                ensureDispatchStorageIdleForRelease(resources);
            } catch (Throwable failure) {
                markLost("Chain storage release was not proven safe: " + failure.getClass().getSimpleName());
                quarantine.quarantine("storage-release-" + quarantineSequence.incrementAndGet(),
                        dispatchStorageBytes(), lossReason);
                throw failure;
            }
            dispatchResources = null;
            if (resources.fence != 0) vkDestroyFence(device, resources.fence, null);
            if (resources.commandPool != 0) vkDestroyCommandPool(device, resources.commandPool, null);
            if (resources.descriptorPool != 0) vkDestroyDescriptorPool(device, resources.descriptorPool, null);
            for (Allocation allocation : resources.buffers) free(allocation);
        }

        synchronized RawResult dispatchRawChain(List<RawStage> stages, List<byte[]> binaries,
                                                 int[] inputWords, int inputWordsPerElement, int count,
                                                 int defaultStateId, int airStateId, int invalidStateId,
                                                 String chainShaderHash, String chainSpirvHash) {
            if (stages == null || binaries == null || stages.isEmpty() || stages.size() != binaries.size()) {
                throw new IllegalArgumentException("Raw chain stage/binary list is empty or mismatched");
            }
            if (closed || state != State.READY || device == null) {
                throw new IllegalStateException("Vulkan session is " + state
                        + (lossReason.isBlank() ? "" : ": " + lossReason));
            }
            if (inputWords.length != Math.multiplyExact(count, inputWordsPerElement)) {
                throw new IllegalArgumentException("Raw chain input length does not match dispatch geometry");
            }
            long[] requestedBytes = new long[stages.size() + 1];
            for (int index = 0; index < stages.size(); index++) {
                requestedBytes[index] = Math.multiplyExact((long) stages.get(index).inputBufferWordCount(count), Integer.BYTES);
            }
            requestedBytes[stages.size()] = Math.multiplyExact(Math.multiplyExact((long) count,
                    stages.get(stages.size() - 1).outputWordsPerElement()), Integer.BYTES);
            List<CompiledPipeline> pipelines = new ArrayList<>(stages.size());
            boolean completed = false;
            boolean submissionAttempted = false;
            activeSubmission = true;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                DispatchResources resources = prepareDispatchResources(requestedBytes, stages.size(), stack);
                Allocation[] allocations = resources.buffers;
                Allocation input = allocations[0];
                input.mapped.asIntBuffer().put(inputWords);
                for (int index = 0; index < stages.size(); index++) {
                    RawStage stage = stages.get(index);
                    pipelines.add(pipelineFor(binaries.get(index), stage.shader().localSize(),
                            stage.shader().source(), stage.disablePipelineOptimization(), stack));
                }
                for (int index = 0; index < stages.size(); index++) {
                    RawStage stage = stages.get(index);
                    int metadataOffset = Math.multiplyExact(count, stage.inputWordsPerElement());
                    allocations[index].mapped.asIntBuffer().position(metadataOffset)
                            .put(stage.uniformInputWords);
                }

                LongBuffer setLayouts = stack.mallocLong(stages.size());
                for (int index = 0; index < stages.size(); index++) {
                    setLayouts.put(index, pipelines.get(index).descriptorLayout());
                }
                LongBuffer descriptorSets = stack.mallocLong(stages.size());
                check(vkAllocateDescriptorSets(device,
                        VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                                .descriptorPool(resources.descriptorPool).pSetLayouts(setLayouts), descriptorSets),
                        "vkAllocateDescriptorSets(worldgen chain)");
                var writes = VkWriteDescriptorSet.calloc(Math.multiplyExact(stages.size(), 2), stack);
                for (int index = 0; index < stages.size(); index++) {
                    Allocation inputAllocation = allocations[index];
                    Allocation outputAllocation = allocations[index + 1];
                    Allocation[] buffers = {inputAllocation, outputAllocation};
                    for (int binding = 0; binding < 2; binding++) {
                        int descriptorIndex = index * 2 + binding;
                        var info = VkDescriptorBufferInfo.calloc(1, stack);
                        long descriptorBytes = binding == 0 ? requestedBytes[index]
                                : Math.multiplyExact(Math.multiplyExact((long) count,
                                        stages.get(index).outputWordsPerElement()), Integer.BYTES);
                        info.buffer(buffers[binding].buffer).offset(0).range(descriptorBytes);
                        writes.get(descriptorIndex).sType$Default().dstSet(descriptorSets.get(index))
                                .dstBinding(binding).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .descriptorCount(1).pBufferInfo(info);
                    }
                }
                vkUpdateDescriptorSets(device, writes, null);

                VkCommandBuffer command = resources.command;
                check(vkBeginCommandBuffer(command,
                        VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                        "vkBeginCommandBuffer(worldgen chain)");

                var hostToShader = VkMemoryBarrier.calloc(1, stack);
                hostToShader.get(0).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, hostToShader, null, null);
                for (int index = 0; index < stages.size(); index++) {
                    if (index > 0) {
                        var shaderToShader = VkMemoryBarrier.calloc(1, stack);
                        shaderToShader.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, shaderToShader, null, null);
                    }
                    CompiledPipeline pipeline = pipelines.get(index);
                    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline());
                    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipelineLayout(), 0,
                            stack.longs(descriptorSets.get(index)), null);
                    vkCmdPushConstants(command, pipeline.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                            stack.ints(count, defaultStateId, airStateId, invalidStateId));
                    long workgroups = ((long) count + stages.get(index).shader().localSize() - 1L)
                            / stages.get(index).shader().localSize();
                    if (workgroups <= 0 || workgroups > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("Vulkan chained dispatch workgroup count is out of range: "
                                + workgroups);
                    }
                    vkCmdDispatch(command, (int) workgroups, 1, 1);
                }
                var shaderToHost = VkMemoryBarrier.calloc(1, stack);
                shaderToHost.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                        0, shaderToHost, null, null);
                check(vkEndCommandBuffer(command), "vkEndCommandBuffer(worldgen chain)");
                submissionAttempted = true;
                int submitStatus = vkQueueSubmit(queue,
                        VkSubmitInfo.calloc(stack).sType$Default()
                                .pCommandBuffers(stack.pointers(command.address())), resources.fence);
                if (submitStatus != VK_SUCCESS) {
                    markLost("vkQueueSubmit(worldgen chain) failed with VkResult=" + submitStatus);
                    throw new IllegalStateException("vkQueueSubmit(worldgen chain) failed with VkResult="
                            + submitStatus);
                }
                int waitStatus = vkWaitForFences(device, resources.fence, true, FENCE_TIMEOUT_NANOS);
                if (waitStatus != VK_SUCCESS) {
                    markLost(waitStatus == VK_TIMEOUT
                            ? "Vulkan chained fence timed out; native resources were quarantined"
                            : waitStatus == VK_ERROR_DEVICE_LOST
                            ? "Vulkan device was lost while waiting for the chained worldgen fence"
                            : "Vulkan chained fence wait failed with VkResult=" + waitStatus);
                    throw new IllegalStateException("vkWaitForFences(worldgen chain) failed with VkResult="
                            + waitStatus);
                }
                completed = true;
                resources.chainBuffersUsed = true;
                Allocation finalOutput = allocations[stages.size()];
                int finalStride = stages.get(stages.size() - 1).outputWordsPerElement();
                int resultStride = rawChainResultWords(stages);
                int finalWords = Math.multiplyExact(count, resultStride);
                int[] outputWords = new int[finalWords];
                if (resultStride == finalStride) {
                    finalOutput.mapped.asIntBuffer().get(outputWords);
                } else {
                    int targetOffset = 0;
                    for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
                        RawStage stage = stages.get(stageIndex);
                        var view = allocations[stageIndex + 1].mapped.asIntBuffer();
                        for (int element = 0; element < count; element++) {
                            for (int word = 0; word < stage.exportWords(); word++) {
                                outputWords[element * resultStride + targetOffset + word] = view.get(
                                        element * stage.outputWordsPerElement() + stage.exportOffset() + word);
                            }
                        }
                        targetOffset += stage.exportWords();
                    }
                    var view = finalOutput.mapped.asIntBuffer();
                    for (int element = 0; element < count; element++) {
                        for (int word = 0; word < finalStride; word++) {
                            outputWords[element * resultStride + targetOffset + word] = view.get(element * finalStride + word);
                        }
                    }
                }
                return new RawResult(outputWords, capabilities, chainShaderHash, chainSpirvHash,
                        count, resultStride);
            } finally {
                activeSubmission = false;
                releaseEvicted(completed || !submissionAttempted);
                if (!completed && (submissionAttempted || state == State.LOST)) {
                    String reason = lossReason.isBlank()
                            ? "chained queue completion was not proven; native resources remain quarantined"
                            : lossReason;
                    quarantine.quarantine("chain-" + quarantineSequence.incrementAndGet(), dispatchStorageBytes(), reason);
                } else if (!completed) {
                    releaseDispatchResources();
                }
            }
        }

        private CompiledPipeline pipelineFor(byte[] spirv, int localSize, String source,
                                             boolean disablePipelineOptimization, MemoryStack stack) {
            int pipelineFlags = disablePipelineOptimization ? VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT : 0;
            String sourceHash = Hashes.sha256(source.getBytes(StandardCharsets.UTF_8));
            String spirvHash = Hashes.sha256(spirv);
            String key = Hashes.sha256((PIPELINE_ABI + "\nGPU_IEEE_BITS\n" + capabilities.name()
                    + "\n" + Integer.toUnsignedString(capabilities.apiVersion())
                    + "\n" + Integer.toUnsignedString(capabilities.driverVersion())
                    + "\n" + capabilities.deviceType() + "\n" + capabilities.computeQueueFamily()
                    + "\n" + capabilities.float64() + "\n" + capabilities.preserveSignedZeroInfNan64()
                    + "\n" + capabilities.preserveDenorm64() + "\n" + capabilities.roundingRte64()
                    + "\n" + localSize + "\n" + pipelineFlags + "\n" + sourceHash + "\n" + spirvHash)
                    .getBytes(StandardCharsets.UTF_8));
            PipelineCache<CompiledPipeline> selectedCache = reusableStageSource(source)
                    ? reusablePipelineCache : pipelineCache;
            CompiledPipeline cached = selectedCache.get(key);
            if (cached != null) {
                pipelineCacheHits.incrementAndGet();
                return cached;
            }

            long shader = 0;
            long descriptorLayout = 0;
            long pipelineLayout = 0;
            long pipeline = 0;
            ByteBuffer code = MemoryUtil.memAlloc(spirv.length).order(ByteOrder.nativeOrder());
            try {
                code.put(spirv).flip();
                LongBuffer handle = stack.mallocLong(1);
                var module = org.lwjgl.vulkan.VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
                check(vkCreateShaderModule(device, module, null, handle), "vkCreateShaderModule(worldgen)");
                shader = handle.get(0);

                var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
                for (int i = 0; i < 2; i++) {
                    bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                }
                check(vkCreateDescriptorSetLayout(device,
                        VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),
                        null, handle), "vkCreateDescriptorSetLayout(worldgen)");
                descriptorLayout = handle.get(0);

                var push = org.lwjgl.vulkan.VkPushConstantRange.calloc(1, stack);
                push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
                check(vkCreatePipelineLayout(device,
                        org.lwjgl.vulkan.VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                                .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push),
                        null, handle), "vkCreatePipelineLayout(worldgen)");
                pipelineLayout = handle.get(0);

                var pipelineCreate = VkComputePipelineCreateInfo.calloc(1, stack);
                var computeCreate = pipelineCreate.get(0).sType$Default().layout(pipelineLayout).flags(pipelineFlags);
                computeCreate.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(shader).pName(stack.UTF8("main")).flags(0);
                if (computeCreate.flags() != pipelineFlags || computeCreate.stage().flags() != 0) {
                    throw new IllegalStateException("Compute-pipeline policy leaked into shader-stage flags");
                }
                long started = System.nanoTime();
                check(vkCreateComputePipelines(device, 0, pipelineCreate, null, handle),
                        "vkCreateComputePipelines(worldgen)");
                pipeline = handle.get(0);
                pipelineCreations.incrementAndGet();
                pipelineNanos.addAndGet(Math.max(0L, System.nanoTime() - started));

                CompiledPipeline compiled = new CompiledPipeline(shader, descriptorLayout, pipelineLayout, pipeline);
                for (CompiledPipeline evicted : selectedCache.putAndCollectEvicted(key, compiled)) {
                    // A chain collects its pipelines one stage at a time: with a cache smaller than the chain, the
                    // pipeline of an earlier stage is evicted by a later one while the chain still holds it.
                    if (activeSubmission) evictedInUse.add(evicted);
                    else destroyPipeline(evicted);
                }
                return compiled;
            } catch (Throwable failure) {
                destroyPipeline(new CompiledPipeline(shader, descriptorLayout, pipelineLayout, pipeline));
                throw failure;
            } finally {
                MemoryUtil.memFree(code);
            }
        }

        /** Pipelines evicted from the cache while a submission that may use them was being built or run. */
        private final List<CompiledPipeline> evictedInUse = new ArrayList<>();

        /** Destroys what was evicted during a submission, unless that submission may still be running on the device. */
        private void releaseEvicted(boolean submissionProvenOver) {
            if (!submissionProvenOver) return; // kept with the rest of an unproven submission's resources
            for (CompiledPipeline evicted : evictedInUse) destroyPipeline(evicted);
            evictedInUse.clear();
        }

        private void destroyPipeline(CompiledPipeline pipeline) {
            if (pipeline == null || device == null) return;
            if (pipeline.pipeline() != 0) vkDestroyPipeline(device, pipeline.pipeline(), null);
            if (pipeline.pipelineLayout() != 0) vkDestroyPipelineLayout(device, pipeline.pipelineLayout(), null);
            if (pipeline.descriptorLayout() != 0) vkDestroyDescriptorSetLayout(device, pipeline.descriptorLayout(), null);
            if (pipeline.shader() != 0) vkDestroyShaderModule(device, pipeline.shader(), null);
        }

        /**
         * Identifies source-shaped modules whose identity is intentionally
         * reused across many captured normal-noise parents. They are kept in
         * a separate bounded cache because ordinary graph reclamation is still
         * needed to contain one-off captured stages.
         */
        private static boolean reusableStageSource(String source) {
            return source.contains("wg_normal_perlin_input_value(")
                    || source.contains("wg_direct_perlin_value(")
                    || source.contains("wg_direct_normal_noise_value(")
                    || source.contains("wg_shared_perlin_value(")
                    || source.contains("wg_shared_perlin_generic(")
                    || source.contains("wg_shared_spline_value(")
                    || source.contains("wg_shared_blended_value(")
                    || source.contains("wg_shared_blended_coordinates:")
                    || source.contains("wg_end_remainder13(")
                    || source.contains("wg_shared_end_island_reduction:");
        }

        private Allocation allocate(long bytes) {
            if (bytes <= 0 || bytes > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid Vulkan allocation size: " + bytes);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var create = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateBuffer(device, create, null, out), "vkCreateBuffer(worldgen)");
                long buffer = out.get(0);
                long memory = 0;
                boolean mapped = false;
                try {
                    var requirements = VkMemoryRequirements.calloc(stack);
                    vkGetBufferMemoryRequirements(device, buffer, requirements);
                    var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
                    vkGetPhysicalDeviceMemoryProperties(physical, properties);
                    int memoryType = -1;
                    int required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                    for (int i = 0; i < properties.memoryTypeCount(); i++) {
                        if ((requirements.memoryTypeBits() & (1 << i)) != 0
                                && (properties.memoryTypes(i).propertyFlags() & required) == required) {
                            memoryType = i;
                            break;
                        }
                    }
                    if (memoryType < 0) {
                        throw new IllegalStateException("Worldgen GPU output requires host-visible coherent storage memory");
                    }
                    var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                            .allocationSize(requirements.size()).memoryTypeIndex(memoryType);
                    check(vkAllocateMemory(device, allocation, null, out), "vkAllocateMemory(worldgen)");
                    memory = out.get(0);
                    check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory(worldgen)");
                    PointerBuffer mappedPointer = stack.mallocPointer(1);
                    check(vkMapMemory(device, memory, 0, bytes, 0, mappedPointer), "vkMapMemory(worldgen)");
                    mapped = true;
                    ByteBuffer view = MemoryUtil.memByteBuffer(mappedPointer.get(0), Math.toIntExact(bytes))
                            .order(ByteOrder.nativeOrder());
                    bufferAllocations++;
                    return new Allocation(buffer, memory, view, true);
                } catch (Throwable failure) {
                    if (mapped) vkUnmapMemory(device, memory);
                    if (memory != 0) vkFreeMemory(device, memory, null);
                    vkDestroyBuffer(device, buffer, null);
                    throw failure;
                }
            }
        }

        private void free(Allocation allocation) {
            if (allocation == null) return;
            if (allocation.hostMapped) vkUnmapMemory(device, allocation.memory);
            vkDestroyBuffer(device, allocation.buffer, null);
            vkFreeMemory(device, allocation.memory, null);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (state == State.LOST) {
                state = State.CLOSED;
                return;
            }
            if (device != null) {
                int status;
                try {
                    status = vkDeviceWaitIdle(device);
                } catch (Throwable failure) {
                    markLost("Vulkan device idle wait threw during close: " + failure.getClass().getSimpleName());
                    state = State.CLOSED;
                    return;
                }
                if (status != VK_SUCCESS) {
                    markLost("Vulkan device idle wait failed during close with VkResult=" + status);
                    state = State.CLOSED;
                    return;
                }
                for (CompiledPipeline pipeline : pipelineCache.clear()) destroyPipeline(pipeline);
                for (CompiledPipeline pipeline : reusablePipelineCache.clear()) destroyPipeline(pipeline);
                releaseDispatchResources();
                vkDestroyDevice(device, null);
                device = null;
            }
            if (instance != null) {
                vkDestroyInstance(instance, null);
                instance = null;
            }
            state = State.CLOSED;
        }
    }

    private record Allocation(long buffer, long memory, ByteBuffer mapped, boolean hostMapped) { }
    private static final class DispatchResources {
        private Allocation[] buffers = new Allocation[0];
        private int descriptorStages;
        private boolean chainBuffersUsed;
        private long descriptorPool;
        private long commandPool;
        private long fence;
        private VkCommandBuffer command;
    }
    private record CompiledPipeline(long shader, long descriptorLayout, long pipelineLayout, long pipeline) { }

    private static boolean containsStateId(int[] stateIds, int expected) {
        for (int stateId : stateIds) if (stateId == expected) return true;
        return false;
    }
}
