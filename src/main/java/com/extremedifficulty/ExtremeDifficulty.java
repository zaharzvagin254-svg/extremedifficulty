package com.extremedifficulty;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.hoglin.Zoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("extremedifficulty")
public class ExtremeDifficulty {

    public static final Logger LOGGER = LogManager.getLogger();

    public ExtremeDifficulty() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[ExtremeDifficulty] Mod loaded! Mobs are now stronger.");
    }

    // Called when any entity spawns into the world
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();

        if (!(entity instanceof LivingEntity living)) return;
        if (level.isClientSide()) return;

        // Determine if it's night or nether/end (always night stats)
        boolean isNight = isNightOrDangerousDimension(level);

        applyBuffs(living, isNight);
    }

    // Called every server tick - reapply buffs in case Forge reset them
    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;

        // Only run every 40 ticks (2 seconds) to save performance
        if (serverLevel.getGameTime() % 40 != 0) return;

        boolean isNight = isNightOrDangerousDimension(serverLevel);

        // Apply to all living entities in loaded chunks
        serverLevel.getAllEntities().forEach(entity -> {
            if (entity instanceof LivingEntity living) {
                applyBuffs(living, isNight);
            }
        });
    }

    private boolean isNightOrDangerousDimension(Level level) {
        // Nether and End are always "night mode"
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END) return true;
        // Overworld: check time (13000-23000 = night)
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }

    private void applyBuffs(LivingEntity entity, boolean isNight) {
        if (entity instanceof Zombie) {
            setStats(entity, isNight ? 50 : 35, isNight ? 7 : 5, isNight ? 0.40 : 0.32);
        } else if (entity instanceof Skeleton) {
            setStats(entity, isNight ? 45 : 32, isNight ? 6 : 5, isNight ? 0.36 : 0.28);
        } else if (entity instanceof Husk) {
            setStats(entity, isNight ? 50 : 35, isNight ? 8 : 6, isNight ? 0.38 : 0.30);
        } else if (entity instanceof Stray) {
            setStats(entity, isNight ? 45 : 32, isNight ? 8 : 6, isNight ? 0.36 : 0.28);
        } else if (entity instanceof Creeper) {
            setStats(entity, isNight ? 42 : 30, isNight ? 4 : 3, isNight ? 0.38 : 0.30);
        } else if (entity instanceof Spider) {
            setStats(entity, isNight ? 36 : 26, isNight ? 5 : 4, isNight ? 0.52 : 0.42);
        } else if (entity instanceof CaveSpider) {
            setStats(entity, isNight ? 28 : 20, isNight ? 5 : 4, isNight ? 0.56 : 0.45);
        } else if (entity instanceof EnderMan) {
            setStats(entity, isNight ? 90 : 65, isNight ? 14 : 11, isNight ? 0.44 : 0.35);
        } else if (entity instanceof Drowned) {
            setStats(entity, isNight ? 48 : 34, isNight ? 8 : 6, isNight ? 0.36 : 0.28);
        } else if (entity instanceof Witch) {
            setStats(entity, isNight ? 58 : 42, isNight ? 4 : 3, isNight ? 0.36 : 0.28);
        } else if (entity instanceof Vindicator) {
            setStats(entity, isNight ? 56 : 40, isNight ? 20 : 15, isNight ? 0.40 : 0.32);
        } else if (entity instanceof Pillager) {
            setStats(entity, isNight ? 52 : 38, isNight ? 8 : 6, isNight ? 0.38 : 0.30);
        } else if (entity instanceof Evoker) {
            setStats(entity, isNight ? 52 : 38, isNight ? 6 : 4, isNight ? 0.36 : 0.28);
        } else if (entity instanceof Ravager) {
            setStats(entity, isNight ? 240 : 175, isNight ? 26 : 20, isNight ? 0.38 : 0.30);
        } else if (entity instanceof ZombifiedPiglin) {
            setStats(entity, isNight ? 48 : 35, isNight ? 13 : 10, isNight ? 0.38 : 0.30);
        } else if (entity instanceof PiglinBrute) {
            setStats(entity, isNight ? 115 : 85, isNight ? 36 : 28, isNight ? 0.46 : 0.38);
        } else if (entity instanceof Piglin) {
            setStats(entity, isNight ? 38 : 28, isNight ? 11 : 8, isNight ? 0.40 : 0.32);
        } else if (entity instanceof Hoglin) {
            setStats(entity, isNight ? 85 : 65, isNight ? 14 : 10, isNight ? 0.40 : 0.32);
        } else if (entity instanceof Zoglin) {
            setStats(entity, isNight ? 85 : 65, isNight ? 14 : 10, isNight ? 0.40 : 0.32);
        } else if (entity instanceof Guardian) {
            setStats(entity, isNight ? 68 : 50, isNight ? 14 : 10, isNight ? 0.30 : 0.25);
        } else if (entity instanceof ElderGuardian) {
            setStats(entity, isNight ? 175 : 130, isNight ? 22 : 16, isNight ? 0.30 : 0.25);
        } else if (entity instanceof Blaze) {
            setStats(entity, isNight ? 46 : 34, isNight ? 11 : 8, isNight ? 0.30 : 0.25);
        } else if (entity instanceof Ghast) {
            setStats(entity, isNight ? 18 : 14, isNight ? 8 : 6, isNight ? 0.30 : 0.25);
        } else if (entity instanceof WitherSkeleton) {
            setStats(entity, isNight ? 80 : 60, isNight ? 16 : 12, isNight ? 0.36 : 0.30);
        } else if (entity instanceof WitherBoss) {
            setStats(entity, isNight ? 650 : 500, isNight ? 20 : 15, isNight ? 0.30 : 0.25);
        } else if (entity instanceof EnderDragon) {
            setMaxHealth(entity, isNight ? 420 : 320);
        }
    }

    private void setStats(LivingEntity entity, double hp, double damage, double speed) {
        setMaxHealth(entity, hp);
        if (entity.getAttribute(Attributes.ATTACK_DAMAGE) != null)
            entity.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(damage);
        if (entity.getAttribute(Attributes.MOVEMENT_SPEED) != null)
            entity.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
    }

    private void setMaxHealth(LivingEntity entity, double hp) {
        if (entity.getAttribute(Attributes.MAX_HEALTH) != null) {
            double currentHp = entity.getHealth();
            double currentMax = entity.getMaxHealth();
            entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
            // Scale current HP proportionally
            if (currentMax > 0) {
                float newHp = (float) (currentHp / currentMax * hp);
                entity.setHealth(Math.max(newHp, (float)currentHp));
            }
        }
    }
}
