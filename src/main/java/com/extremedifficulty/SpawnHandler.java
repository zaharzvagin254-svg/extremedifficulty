package com.extremedifficulty;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.Random;

public class SpawnHandler {

    // Ванильный mob cap для hostile = 70
    // Ночью увеличиваем до 105 (+50%)
    private static final int VANILLA_MOB_CAP   = 70;
    private static final int NIGHT_MOB_CAP     = 105;

    // Дополнительные зомби спавним каждые N тиков ночью
    // 72 тика = ~3.6 сек — умеренно, не спамит
    private static final int ZOMBIE_SPAWN_INTERVAL = 72;

    // Максимум дополнительных зомби за один спавн на игрока
    private static final int ZOMBIE_MIN_GROUP = 3;
    private static final int ZOMBIE_MAX_GROUP = 6;

    // Радиус спавна вокруг игрока (не слишком близко и не слишком далеко)
    private static final int SPAWN_MIN_DIST = 24;
    private static final int SPAWN_MAX_DIST = 48;

    // Максимум всего мобов в радиусе 48 блоков — чтобы не переспавнить
    private static final int MAX_MOBS_NEARBY = 30;

    private final Random random = new Random();

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;

        // Только Overworld
        if (serverLevel.dimension() != Level.OVERWORLD) return;

        // Только ночью
        long time = serverLevel.getDayTime() % 24000;
        if (time < 13000 || time > 23000) return;

        // Спавним каждые ZOMBIE_SPAWN_INTERVAL тиков
        if (serverLevel.getGameTime() % ZOMBIE_SPAWN_INTERVAL != 0) return;

        for (net.minecraft.server.level.ServerPlayer player : serverLevel.players()) {
            if (player.isCreative() || player.isSpectator()) continue;

            // Считаем сколько мобов уже есть рядом — защита от переполнения
            int nearbyMobs = serverLevel.getEntitiesOfClass(
                net.minecraft.world.entity.monster.Monster.class,
                player.getBoundingBox().inflate(SPAWN_MAX_DIST),
                e -> true
            ).size();

            if (nearbyMobs >= MAX_MOBS_NEARBY) continue;

            // Сколько зомби спавним в этой волне
            int count = ZOMBIE_MIN_GROUP + random.nextInt(ZOMBIE_MAX_GROUP - ZOMBIE_MIN_GROUP + 1);

            // Ограничиваем чтобы не превысить лимит
            count = Math.min(count, MAX_MOBS_NEARBY - nearbyMobs);
            if (count <= 0) continue;

            for (int i = 0; i < count; i++) {
                spawnZombieNearPlayer(serverLevel, player);
            }
        }
    }

    private void spawnZombieNearPlayer(ServerLevel level,
                                        net.minecraft.server.level.ServerPlayer player) {
        // Случайная точка в кольце SPAWN_MIN_DIST..SPAWN_MAX_DIST
        double angle = random.nextDouble() * Math.PI * 2;
        double dist  = SPAWN_MIN_DIST + random.nextDouble() * (SPAWN_MAX_DIST - SPAWN_MIN_DIST);

        int x = (int)(player.getX() + Math.cos(angle) * dist);
        int z = (int)(player.getZ() + Math.sin(angle) * dist);

        // Получаем высоту поверхности
        int y = level.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            x, z
        );

        // Не спавним в воде или лаве
        // FIX: y=0 означает незагруженный чанк — не спавним
        if (y <= 0) return;

        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getBlockState(pos).isAir()) return;
        if (!level.getBlockState(pos.below()).isSolidRender(level, pos.below())) return;

        // Не спавним на свету (уровень освещения > 7)
        if (level.getMaxLocalRawBrightness(pos) > 7) return;

        // Создаём зомби
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) return;

        zombie.moveTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
        zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
            MobSpawnType.NATURAL, null, null);

        // Бафаем нового зомби если мод активен
        String key = "ed_base_hp";
        if (!zombie.getPersistentData().contains(key)) {
            var attr = zombie.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (attr != null)
                zombie.getPersistentData().putDouble(key, attr.getBaseValue());
        }

        // Проверяем правила спавна и добавляем в мир
        if (zombie.checkSpawnRules(level, MobSpawnType.NATURAL)
         && zombie.checkSpawnObstruction(level)) {
            level.addFreshEntity(zombie);
        }
    }
}
