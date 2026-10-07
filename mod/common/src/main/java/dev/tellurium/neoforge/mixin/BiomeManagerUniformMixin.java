// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.fast.UniformBiomeLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Answers getBiome without the seeded cell choice when every candidate cell holds the same biome; see {@link UniformBiomeLookup}. */
@Mixin(BiomeManager.class)
public abstract class BiomeManagerUniformMixin {
    @Shadow @Final private BiomeManager.NoiseBiomeSource noiseBiomeSource;

    @Unique private static final ThreadLocal<Boolean> tellurium$VERIFYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    public abstract Holder<Biome> getBiome(BlockPos pos);

    @Inject(method = "getBiome", at = @At("HEAD"), cancellable = true)
    private void tellurium$uniformCells(BlockPos pos, CallbackInfoReturnable<Holder<Biome>> callback) {
        if (!UniformBiomeLookup.ENABLED || noiseBiomeSource.getClass() != WorldGenRegion.class) return;
        if (UniformBiomeLookup.VERIFY && tellurium$VERIFYING.get()) return;
        Holder<Biome> biome = UniformBiomeLookup.find((WorldGenRegion) noiseBiomeSource, pos.getX(), pos.getY(), pos.getZ());
        if (biome == null) return;
        if (UniformBiomeLookup.VERIFY) {
            tellurium$VERIFYING.set(Boolean.TRUE);
            Holder<Biome> expected;
            try {
                expected = getBiome(pos);
            } finally {
                tellurium$VERIFYING.set(Boolean.FALSE);
            }
            if (expected != biome) {
                throw new IllegalStateException("Uniform biome lookup at " + pos + " gave " + biome + ", original " + expected);
            }
            UniformBiomeLookup.VERIFIED.incrementAndGet();
        }
        callback.setReturnValue(biome);
    }
}
