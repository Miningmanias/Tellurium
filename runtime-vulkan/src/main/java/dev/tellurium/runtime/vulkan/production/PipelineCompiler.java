// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

@FunctionalInterface
public interface PipelineCompiler<T> { T compile(String key, String source) throws Exception; }
