// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.fused;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Developer probe: compiles one fused kernel from a dumped GLSL source and
 * creates its compute pipeline, reporting shaderc and driver times.
 * Usage: FusedCompileProbe <source.comp> <K_COLUMN|K_CORNER|K_AQUIFER|K_BLOCK|K_HEIGHT> [extraDefine...]
 */
public final class FusedCompileProbe {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        String kernel = args[1];
        for (int i = 2; i < args.length; i++) source = "#define " + args[i] + " 1\n" + source;
        source = source.replaceFirst("#version 460\n", "");
        source = "#version 460\n" + source;
        long start = System.nanoTime();
        byte[] spirv = FusedNoiseDevice.compileGlsl(source, kernel);
        long shaderc = System.nanoTime() - start;
        System.out.println("shaderc " + kernel + ": " + spirv.length + " bytes in " + shaderc / 1_000_000 + " ms");
        try (FusedNoiseDevice device = FusedNoiseDevice.open(); MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vk = device.vkDevice();
            var bindings = VkDescriptorSetLayoutBinding.calloc(FusedNoiseDevice.BINDINGS, stack);
            for (int i = 0; i < FusedNoiseDevice.BINDINGS; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer handle = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle);
            long setLayout = handle.get(0);
            var push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
            vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, handle);
            long layout = handle.get(0);
            ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
            code.put(spirv).flip();
            vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle);
            MemoryUtil.memFree(code);
            long module = handle.get(0);
            var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            var info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(layout);
            long pipelineStart = System.nanoTime();
            int result = vkCreateComputePipelines(vk, VK_NULL_HANDLE, info, null, handle);
            System.out.println("pipeline " + kernel + ": VkResult=" + result + " in " + (System.nanoTime() - pipelineStart) / 1_000_000 + " ms");
            if (result == VK_SUCCESS) vkDestroyPipeline(vk, handle.get(0), null);
            vkDestroyShaderModule(vk, module, null);
            vkDestroyPipelineLayout(vk, layout, null);
            vkDestroyDescriptorSetLayout(vk, setLayout, null);
        }
    }
}
