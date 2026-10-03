// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.nio.ByteBuffer;

@Mixin(RegionFile.class)
public interface RegionFileAccessor {
    @Accessor("version")
    RegionFileVersion worldgenNext$version();

    @Invoker("write")
    void worldgenNext$write(ChunkPos pos, ByteBuffer payload) throws IOException;
}
