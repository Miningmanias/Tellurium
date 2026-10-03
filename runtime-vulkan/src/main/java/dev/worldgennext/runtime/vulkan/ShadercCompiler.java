// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

import dev.worldgennext.compiler.vulkan.worldgen.SpirvFunctionControlPatcher;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Native loading occurs only when compile is explicitly called. */
public final class ShadercCompiler {
    public byte[] compile(String source) {
        boolean dontInline = Boolean.getBoolean("worldgennext.shaderc.dontInline");
        String prefix = dontInline
                ? System.getProperty("worldgennext.shaderc.dontInlinePrefix", "wg_") : "";
        return compile(source, dontInline, prefix);
    }

    /** Compiles with an explicit function-control policy owned by one request. */
    public byte[] compile(String source, boolean dontInline, String dontInlinePrefix) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("GLSL source required");
        if (dontInline && (dontInlinePrefix == null || dontInlinePrefix.isBlank())) {
            throw new IllegalArgumentException("DontInline requires a nonblank function prefix");
        }
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("Shaderc compiler initialization failed");
        long options = shaderc_compile_options_initialize();
        long result = 0;
        try {
            if (options == 0) throw new IllegalStateException("Shaderc options initialization failed");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, "worldgennext-synthetic.comp", "main", options);
            if (result == 0) throw new IllegalStateException("Shaderc returned no result");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalStateException("Shader compilation failed: " + shaderc_result_get_error_message(result));
            var bytes = shaderc_result_get_bytes(result);
            if (bytes == null || !bytes.hasRemaining() || bytes.remaining() % 4 != 0) throw new IllegalStateException("Invalid SPIR-V byte length");
            byte[] binary = new byte[bytes.remaining()];
            bytes.get(binary);
            if (dontInline) binary = SpirvFunctionControlPatcher.addDontInlineToNamedFunctions(binary, dontInlinePrefix);
            return binary;
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
