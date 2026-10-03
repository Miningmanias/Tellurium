// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import dev.worldgennext.semantic.program.NumericProfile;

public record ShaderCacheKey(String programHash, String contextHash, NumericProfile profile,
                             String abiVersion, String deviceIdentity, int localSize) {
    public ShaderCacheKey {
        if (programHash == null || programHash.isBlank() || contextHash == null || contextHash.isBlank() || abiVersion == null || abiVersion.isBlank() || deviceIdentity == null || deviceIdentity.isBlank()) throw new IllegalArgumentException("Incomplete shader identity");
        if (profile == null || localSize <= 0) throw new IllegalArgumentException("Invalid shader profile/size");
    }
}
