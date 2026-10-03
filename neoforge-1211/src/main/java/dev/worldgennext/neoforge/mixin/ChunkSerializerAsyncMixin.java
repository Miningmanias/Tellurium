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

    @org.spongepowered.asm.mixin.Shadow
    private static void saveTicks(net.minecraft.server.level.ServerLevel level, net.minecraft.nbt.CompoundTag tag,
                                  net.minecraft.world.level.chunk.ChunkAccess.TicksToSave ticks) {
        throw new AssertionError();
    }

    @org.spongepowered.asm.mixin.Shadow
    private static net.minecraft.nbt.CompoundTag packStructureData(
            net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext context,
            net.minecraft.world.level.ChunkPos pos,
            java.util.Map<net.minecraft.world.level.levelgen.structure.Structure, net.minecraft.world.level.levelgen.structure.StructureStart> starts,
            java.util.Map<net.minecraft.world.level.levelgen.structure.Structure, it.unimi.dsi.fastutil.longs.LongSet> references) {
        throw new AssertionError();
    }

    // The three values below are also built on the save pool for a chunk on the asynchronous unload
    // path: the chunk has left the world, so its scheduled ticks, post-processing lists and structure
    // maps no longer change.  Block entities, light and attachments stay on the server thread.

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;packOffsets([Lit/unimi/dsi/fastutil/shorts/ShortList;)Lnet/minecraft/nbt/ListTag;"))
    private static net.minecraft.nbt.ListTag worldgenNext$deferOffsets(it.unimi.dsi.fastutil.shorts.ShortList[] lists) {
        AsyncSectionEncoding.Deferred collector = AsyncSectionEncoding.collector();
        if (collector == null) return ChunkSerializer.packOffsets(lists);
        return AsyncSectionEncoding.deferTop(collector, new net.minecraft.nbt.ListTag(), () -> ChunkSerializer.packOffsets(lists));
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;saveTicks(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/chunk/ChunkAccess$TicksToSave;)V"))
    private static void worldgenNext$deferTicks(net.minecraft.server.level.ServerLevel level, net.minecraft.nbt.CompoundTag tag,
                                                net.minecraft.world.level.chunk.ChunkAccess.TicksToSave ticks) {
        AsyncSectionEncoding.Deferred collector = AsyncSectionEncoding.collector();
        if (collector == null) {
            saveTicks(level, tag, ticks);
            return;
        }
        long gameTime = level.getLevelData().getGameTime();
        tag.put("block_ticks", AsyncSectionEncoding.deferTop(collector, new net.minecraft.nbt.ListTag(), () -> ticks.blocks().save(gameTime,
                block -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString())));
        tag.put("fluid_ticks", AsyncSectionEncoding.deferTop(collector, new net.minecraft.nbt.ListTag(), () -> ticks.fluids().save(gameTime,
                fluid -> net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid).toString())));
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;packStructureData(Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePieceSerializationContext;Lnet/minecraft/world/level/ChunkPos;Ljava/util/Map;Ljava/util/Map;)Lnet/minecraft/nbt/CompoundTag;"))
    private static net.minecraft.nbt.CompoundTag worldgenNext$deferStructures(
            net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext context,
            net.minecraft.world.level.ChunkPos pos,
            java.util.Map<net.minecraft.world.level.levelgen.structure.Structure, net.minecraft.world.level.levelgen.structure.StructureStart> starts,
            java.util.Map<net.minecraft.world.level.levelgen.structure.Structure, it.unimi.dsi.fastutil.longs.LongSet> references) {
        AsyncSectionEncoding.Deferred collector = AsyncSectionEncoding.collector();
        if (collector == null) return packStructureData(context, pos, starts, references);
        return AsyncSectionEncoding.deferTop(collector, new net.minecraft.nbt.CompoundTag(),
                () -> packStructureData(context, pos, starts, references));
    }
}
