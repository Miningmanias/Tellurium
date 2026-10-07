// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import com.mojang.serialization.Codec;
import dev.tellurium.neoforge.fast.CavePlans;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.NetherWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Carves from a recorded plan of the cave systems of a starting chunk; see {@link CavePlans}.
 *
 * <p>{@code tellurium$record} is CaveWorldCarver.carve and createTunnel (Minecraft 1.21.1) with the same
 * random draws in the same order, storing each ellipsoid instead of carving it.  {@code tellurium$replay}
 * performs the carving calls the original would have made for one chunk.</p>
 */
@Mixin(CaveWorldCarver.class)
public abstract class CaveWorldCarverPlanMixin extends WorldCarver<CaveCarverConfiguration> {
    private CaveWorldCarverPlanMixin(Codec<CaveCarverConfiguration> codec) {
        super(codec);
    }

    @Shadow
    protected abstract int getCaveBound();

    @Shadow
    protected abstract float getThickness(RandomSource random);

    @Shadow
    protected abstract double getYScale();

    @Inject(method = "carve(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;Lnet/minecraft/world/level/levelgen/carver/CaveCarverConfiguration;Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/function/Function;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/Aquifer;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/CarvingMask;)Z",
            at = @At("HEAD"), cancellable = true)
    private void tellurium$carveFromPlan(CarvingContext context, CaveCarverConfiguration configuration, ChunkAccess chunk,
                                            Function<BlockPos, Holder<Biome>> biomes, RandomSource random, Aquifer aquifer,
                                            ChunkPos startChunk, CarvingMask mask, CallbackInfoReturnable<Boolean> callback) {
        if (!CavePlans.ENABLED) return;
        // Only the two vanilla classes: a subclass may override the room or tunnel methods this copy replaces.
        Class<?> type = ((Object) this).getClass();
        if (type != CaveWorldCarver.class && type != NetherWorldCarver.class) return;
        // The plan is a function of the random source's state; only a source whose state can be read is cached.
        if (!(random instanceof WorldgenRandom) || !(((WorldgenRandomAccessor) random).tellurium$randomSource() instanceof LegacyRandomSource source)) {
            return;
        }
        long seed = ((LegacyRandomSourceAccessor) source).tellurium$seed().get();
        CavePlans.Key key = new CavePlans.Key(((CarvingContextAccessor) context).tellurium$randomState(), this, configuration,
                startChunk.toLong(), seed);
        List<CavePlans.Cave> plan = CavePlans.get(key);
        if (plan == null) {
            plan = tellurium$record(context, configuration, random, startChunk);
            CavePlans.put(key, plan);
        }
        ChunkPos pos = chunk.getPos();
        double middleX = (double) pos.getMiddleBlockX();
        double middleZ = (double) pos.getMiddleBlockZ();
        for (CavePlans.Cave cave : plan) {
            if (!cave.bounds().mayCarve(middleX, middleZ)) continue;
            double floorLevel = cave.floorLevel();
            WorldCarver.CarveSkipChecker skip = (skipContext, dx, dy, dz, y) -> tellurium$shouldSkip(dx, dy, dz, floorLevel);
            for (Object part : cave.parts()) {
                if (part instanceof CavePlans.Room room) {
                    carveEllipsoid(context, configuration, chunk, biomes, aquifer, room.x(), room.y(), room.z(),
                            room.horizontalRadius(), room.verticalRadius(), mask, skip);
                } else {
                    tellurium$replay((CavePlans.Tunnel) part, context, configuration, chunk, biomes, aquifer, mask, skip);
                }
            }
        }
        callback.setReturnValue(true);
    }

    /** CaveWorldCarver.shouldSkip. */
    @Unique
    private static boolean tellurium$shouldSkip(double dx, double dy, double dz, double floorLevel) {
        return dy <= floorLevel ? true : dx * dx + dy * dy + dz * dz >= 1.0;
    }

    @Unique
    private void tellurium$replay(CavePlans.Tunnel tunnel, CarvingContext context, CaveCarverConfiguration configuration, ChunkAccess chunk,
                                     Function<BlockPos, Holder<Biome>> biomes, Aquifer aquifer, CarvingMask mask,
                                     WorldCarver.CarveSkipChecker skip) {
        ChunkPos pos = chunk.getPos();
        // Nothing in this tunnel or its branches can carve the chunk: walking it would only return early or carve nothing.
        if (!tunnel.bounds().mayCarve((double) pos.getMiddleBlockX(), (double) pos.getMiddleBlockZ())) return;
        int[] index = tunnel.stepIndex();
        double[] steps = tunnel.steps();
        for (int s = 0; s < index.length; s++) {
            int at = s * 5;
            // The original stops walking a tunnel, branches included, at the first offered step that cannot reach the chunk.
            if (!canReach(pos, steps[at], steps[at + 2], index[s], tunnel.stepCount(), tunnel.thickness())) return;
            carveEllipsoid(context, configuration, chunk, biomes, aquifer, steps[at], steps[at + 1], steps[at + 2],
                    steps[at + 3], steps[at + 4], mask, skip);
        }
        if (tunnel.first() != null) {
            tellurium$replay(tunnel.first(), context, configuration, chunk, biomes, aquifer, mask, skip);
            tellurium$replay(tunnel.second(), context, configuration, chunk, biomes, aquifer, mask, skip);
        }
    }

    @Unique
    private List<CavePlans.Cave> tellurium$record(CarvingContext context, CaveCarverConfiguration configuration, RandomSource random,
                                                      ChunkPos startChunk) {
        List<CavePlans.Cave> caves = new ArrayList<>();
        int i = SectionPos.sectionToBlockCoord(this.getRange() * 2 - 1);
        int j = random.nextInt(random.nextInt(random.nextInt(this.getCaveBound()) + 1) + 1);

        for (int k = 0; k < j; k++) {
            double d0 = (double) startChunk.getBlockX(random.nextInt(16));
            double d1 = (double) configuration.y.sample(random, context);
            double d2 = (double) startChunk.getBlockZ(random.nextInt(16));
            double d3 = (double) configuration.horizontalRadiusMultiplier.sample(random);
            double d4 = (double) configuration.verticalRadiusMultiplier.sample(random);
            double d5 = (double) ((CaveCarverConfigurationAccessor) configuration).tellurium$floorLevel().sample(random);
            List<Object> parts = new ArrayList<>();
            CavePlans.Bounds bounds = new CavePlans.Bounds();
            int l = 1;
            if (random.nextInt(4) == 0) {
                double d6 = (double) configuration.yScale.sample(random);
                float f1 = 1.0F + random.nextFloat() * 6.0F;
                // createRoom
                double roomRadius = 1.5 + (double) (Mth.sin((float) (Math.PI / 2)) * f1);
                double roomHeight = roomRadius * d6;
                parts.add(new CavePlans.Room(d0 + 1.0, d1, d2, roomRadius, roomHeight));
                bounds.include(d0 + 1.0, d2, roomRadius);
                l += random.nextInt(4);
            }

            for (int k1 = 0; k1 < l; k1++) {
                float f = random.nextFloat() * (float) (Math.PI * 2);
                float f3 = (random.nextFloat() - 0.5F) / 4.0F;
                float f2 = this.getThickness(random);
                int i1 = i - random.nextInt(i / 4);
                CavePlans.Tunnel tunnel = tellurium$recordTunnel(random.nextLong(), d0, d1, d2, d3, d4, f2, f, f3, 0, i1, this.getYScale());
                parts.add(tunnel);
                bounds.include(tunnel.bounds());
            }
            caves.add(new CavePlans.Cave(d5, parts, bounds));
        }
        return caves;
    }

    @Unique
    private static CavePlans.Tunnel tellurium$recordTunnel(long seed, double x, double y, double z, double horizontalMultiplier,
                                                               double verticalMultiplier, float thickness, float yaw, float pitch,
                                                               int branchIndex, int branchCount, double yScale) {
        RandomSource randomsource = RandomSource.create(seed);
        int i = randomsource.nextInt(branchCount / 2) + branchCount / 4;
        boolean flag = randomsource.nextInt(6) == 0;
        float f = 0.0F;
        float f1 = 0.0F;
        int capacity = Math.max(0, branchCount - branchIndex);
        int[] index = new int[capacity];
        double[] steps = new double[capacity * 5];
        int count = 0;
        CavePlans.Bounds bounds = new CavePlans.Bounds();

        for (int j = branchIndex; j < branchCount; j++) {
            double d0 = 1.5 + (double) (Mth.sin((float) Math.PI * (float) j / (float) branchCount) * thickness);
            double d1 = d0 * yScale;
            float f2 = Mth.cos(pitch);
            x += (double) (Mth.cos(yaw) * f2);
            y += (double) Mth.sin(pitch);
            z += (double) (Mth.sin(yaw) * f2);
            pitch *= flag ? 0.92F : 0.7F;
            pitch += f1 * 0.1F;
            yaw += f * 0.1F;
            f1 *= 0.9F;
            f *= 0.75F;
            f1 += (randomsource.nextFloat() - randomsource.nextFloat()) * randomsource.nextFloat() * 2.0F;
            f += (randomsource.nextFloat() - randomsource.nextFloat()) * randomsource.nextFloat() * 4.0F;
            if (j == i && thickness > 1.0F) {
                long firstSeed = randomsource.nextLong();
                float firstThickness = randomsource.nextFloat() * 0.5F + 0.5F;
                CavePlans.Tunnel first = tellurium$recordTunnel(firstSeed, x, y, z, horizontalMultiplier, verticalMultiplier,
                        firstThickness, yaw - (float) (Math.PI / 2), pitch / 3.0F, j, branchCount, 1.0);
                long secondSeed = randomsource.nextLong();
                float secondThickness = randomsource.nextFloat() * 0.5F + 0.5F;
                CavePlans.Tunnel second = tellurium$recordTunnel(secondSeed, x, y, z, horizontalMultiplier, verticalMultiplier,
                        secondThickness, yaw + (float) (Math.PI / 2), pitch / 3.0F, j, branchCount, 1.0);
                bounds.include(first.bounds());
                bounds.include(second.bounds());
                return new CavePlans.Tunnel(thickness, branchCount, Arrays.copyOf(index, count),
                        Arrays.copyOf(steps, count * 5), first, second, bounds);
            }

            if (randomsource.nextInt(4) != 0) {
                index[count] = j;
                int at = count * 5;
                steps[at] = x;
                steps[at + 1] = y;
                steps[at + 2] = z;
                steps[at + 3] = d0 * horizontalMultiplier;
                steps[at + 4] = d1 * verticalMultiplier;
                bounds.include(x, z, steps[at + 3]);
                count++;
            }
        }
        return new CavePlans.Tunnel(thickness, branchCount, Arrays.copyOf(index, count),
                Arrays.copyOf(steps, count * 5), null, null, bounds);
    }
}
