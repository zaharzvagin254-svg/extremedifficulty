package com.extremedifficulty;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

public class SpawnHandler {

    private static final int ZOMBIE_SPAWN_INTERVAL = 72;
    private static final int ZOMBIE_MIN_GROUP = 3;
    private static final int ZOMBIE_MAX_GROUP = 6;
    private static final int SPAWN_MIN_DIST = 24;
    private static final int SPAWN_MAX_DIST = 48;

    // FIX: count only zombies (not all monsters) so the cap isn't hit
    // by vanilla skeleton/spider spawns blocking our zombie waves
    private static final int MAX_ZOMBIES_NEARBY = 20;

    private final Random random = new Random();

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.dimension() != Level.OVERWORLD) return;

        long time = serverLevel.getDayTime() % 24000;
        if (time < 13000 || time > 23000) return;

        if (serverLevel.getGameTime() % ZOMBIE_SPAWN_INTERVAL != 0) return;

        for (ServerPlayer player : serverLevel.players()) {
            if (player.isCreative() || player.isSpectator()) continue;

            // FIX: count only zombies nearby, not all monsters
            int nearbyZombies = serverLevel.getEntitiesOfClass(
                Zombie.class,
                player.getBoundingBox().inflate(SPAWN_MAX_DIST),
                z -> true
            ).size();

            if (nearbyZombies >= MAX_ZOMBIES_NEARBY) continue;

            int toSpawn = Math.min(
                ZOMBIE_MIN_GROUP + random.nextInt(ZOMBIE_MAX_GROUP - ZOMBIE_MIN_GROUP + 1),
                MAX_ZOMBIES_NEARBY - nearbyZombies
            );

            for (int i = 0; i < toSpawn; i++) {
                spawnZombieNearPlayer(serverLevel, player);
            }
        }
    }

    private void spawnZombieNearPlayer(ServerLevel level, ServerPlayer player) {
        double angle = random.nextDouble() * Math.PI * 2;
        double dist  = SPAWN_MIN_DIST + random.nextDouble() * (SPAWN_MAX_DIST - SPAWN_MIN_DIST);

        int x = (int)(player.getX() + Math.cos(angle) * dist);
        int z = (int)(player.getZ() + Math.sin(angle) * dist);

        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

        if (y <= 0) return;

        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) return;
        if (!level.getBlockState(pos.below()).isSolidRender(level, pos.below())) return;
        if (level.getMaxLocalRawBrightness(pos) > 7) return;

        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) return;

        zombie.moveTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
        zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
            MobSpawnType.NATURAL, null, null);

        if (!zombie.getPersistentData().contains("ed_base_hp")) {
            var attr = zombie.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (attr != null)
                zombie.getPersistentData().putDouble("ed_base_hp", attr.getBaseValue());
        }

        if (zombie.checkSpawnRules(level, MobSpawnType.NATURAL)
         && zombie.checkSpawnObstruction(level)) {
            level.addFreshEntity(zombie);
        }
    }
}
