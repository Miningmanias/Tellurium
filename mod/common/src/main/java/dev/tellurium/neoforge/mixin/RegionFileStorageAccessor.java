// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {
    @Accessor("folder")
    Path tellurium$folder();

    @Accessor("sync")
    boolean tellurium$sync();

    @Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<RegionFile> tellurium$regionCache();
}
