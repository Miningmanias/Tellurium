// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.tellurium.neoforge.threading.AsyncSectionEncoding;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;


/** Defers section palette encoding while an {@link AsyncSectionEncoding} collector is active. */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerAsyncMixin {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
            remap = false))
    private static DataResult tellurium$deferSectionEncoding(Codec codec, DynamicOps ops, Object value) {
        AsyncSectionEncoding.Deferred collector = AsyncSectionEncoding.collector();
        if (collector != null && ops == NbtOps.INSTANCE && value instanceof PalettedContainerRO) {
            return DataResult.success(AsyncSectionEncoding.defer(collector, () -> AsyncSectionEncoding.encode(codec, value)));
        }
        return codec.encodeStart(ops, value);
    }

    @Shadow
    private static void saveTicks(ServerLevel level, CompoundTag tag,
                                  ChunkAccess.TicksToSave ticks) {
        throw new AssertionError();
    }

    @Shadow
    private static CompoundTag packStructureData(
            StructurePieceSerializationContext context,
            ChunkPos pos,
            Map<Structure, StructureStart> starts,
            Map<Structure, LongSet> references) {
        throw new AssertionError();
    }

    // The three values below are also built on the save pool for a chunk on the asynchronous unload
    // path: the chunk has left the world, so its scheduled ticks, post-processing lists and structure
    // maps no longer change.  Block entities, light and attachments stay on the server thread.

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;packOffsets([Lit/unimi/dsi/fastutil/shorts/ShortList;)Lnet/minecraft/nbt/ListTag;"))
    private static ListTag tellurium$deferOffsets(ShortList[] lists) {
        AsyncSectionEncoding.Deferred collector = AsyncSectionEncoding.collector();
        if (collector == null) return ChunkSerializer.packOffsets(lists);
        return AsyncSectionEncoding.deferTop(collector, new ListTag(), () -> ChunkSerializer.packOffsets(lists));
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;saveTicks(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/chunk/ChunkAccess$TicksToSave;)V"))
    private static void tellurium$deferTicks(ServerLevel level, CompoundTag tag,
                                                ChunkAccess.TicksToSave ticks) {
        AsyncSectionEncoding.Deferred collector = AsyncSectionEncoding.collector();
        if (collector == null) {
            saveTicks(level, tag, ticks);
            return;
        }
        long gameTime = level.getLevelData().getGameTime();
        tag.put("block_ticks", AsyncSectionEncoding.deferTop(collector, new ListTag(), () -> ticks.blocks().save(gameTime,
                block -> BuiltInRegistries.BLOCK.getKey(block).toString())));
        tag.put("fluid_ticks", AsyncSectionEncoding.deferTop(collector, new ListTag(), () -> ticks.fluids().save(gameTime,
                fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString())));
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;packStructureData(Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePieceSerializationContext;Lnet/minecraft/world/level/ChunkPos;Ljava/util/Map;Ljava/util/Map;)Lnet/minecraft/nbt/CompoundTag;"))
    private static CompoundTag tellurium$deferStructures(
            StructurePieceSerializationContext context,
            ChunkPos pos,
            Map<Structure, StructureStart> starts,
            Map<Structure, LongSet> references) {
        AsyncSectionEncoding.Deferred collector = AsyncSectionEncoding.collector();
        if (collector == null) return packStructureData(context, pos, starts, references);
        return AsyncSectionEncoding.deferTop(collector, new CompoundTag(),
                () -> packStructureData(context, pos, starts, references));
    }
}
