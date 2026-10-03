// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.worldgennext.neoforge.threading.AsyncSectionEncoding;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


/** Defers section palette encoding while an {@link AsyncSectionEncoding} collector is active. */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerAsyncMixin {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
            remap = false))
    private static DataResult worldgenNext$deferSectionEncoding(Codec codec, DynamicOps ops, Object value) {
        AsyncSectionEncoding.Deferred collector = AsyncSectionEncoding.collector();
        if (collector != null && ops == NbtOps.INSTANCE && value instanceof PalettedContainerRO) {
            return DataResult.success(AsyncSectionEncoding.defer(collector, () -> AsyncSectionEncoding.encode(codec, value)));
        }
        return codec.encodeStart(ops, value);
    }
}
