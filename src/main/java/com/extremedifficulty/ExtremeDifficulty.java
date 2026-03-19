package com.extremedifficulty;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

@Mod("extremedifficulty")
public class ExtremeDifficulty {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String NBT_BUFFED    = "ed_buffed";
    private static final String NBT_WAS_NIGHT = "ed_night";

    private static final double DAY_MULT   = 1.50;
    private static final double NIGHT_MULT = 1.85;

    private static final double BOSS_HP_MULT     = 2.00;
    private static final double BOSS_DAMAGE_MULT = 1.60;

    private static final double DAY_AGGRO_RANGE   = 24.0;
    private static final double NIGHT_AGGRO_RANGE = 40.0;

    public ExtremeDifficulty() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModSounds.SOUNDS.register(modBus);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
        }

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new BloodMoonEvents());

        LOGGER.info("[ExtremeDifficulty] Mod loaded!");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientSetup::registerRenderers);
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();
        if (!(entity instanceof LivingEntity living)) return;
        if (level.isClientSide()) return;

        boolean night = BloodMoonManager.isNight(level);
        applyBaseBuffsOnce(living, night);
        applyAggroRange(living, night);

        // Если сейчас судная ночь — сразу бафаем нового моба
        if (level instanceof ServerLevel serverLevel) {
            BloodMoonManager mgr = BloodMoonManager.get(serverLevel);
            if (mgr.isBloodMoonActive(serverLevel)) {
                BloodMoonEvents.buffMobForBloodMoon(living, mgr.getBuffMult(), mgr.getBloodMoonCount());
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;

        long time = serverLevel.getDayTime() % 24000;
        boolean isNight = BloodMoonManager.isNight(serverLevel);

        // Базовые баффы + агрессия — раз в 100 тиков
        if (serverLevel.getGameTime() % 100 == 0) {
            serverLevel.getEntities().getAll().forEach(entity -> {
                if (!(entity instanceof LivingEntity living)) return;
                applyAggroRange(living, isNight);
                CompoundTag tag = living.getPersistentData();
                boolean wasNight = tag.getBoolean(NBT_WAS_NIGHT);
                if (wasNight != isNight) {
                    tag.putBoolean(NBT_BUFFED, false);
                    applyBaseBuffsOnce(living, isNight);
                }
            });

            // Групповая агрессия
            double range = isNight ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE;
            serverLevel.getEntities().getAll().forEach(entity -> {
                if (!(entity instanceof Mob mob)) return;
                if (mob.getTarget() != null) return;
                List<Player> nearby = serverLevel.getEntitiesOfClass(
                    Player.class,
                    mob.getBoundingBox().inflate(range),
                    p -> !p.isCreative() && !p.isSpectator() && p.isAlive()
                );
                if (!nearby.isEmpty()) {
                    Player nearest = nearby.stream()
                        .min((a, b) -> Double.compare(mob.distanceToSqr(a), mob.distanceToSqr(b)))
                        .orElse(null);
                    if (nearest != null) mob.setTarget(nearest);
                }
            });
        }

        // Проверка судной ночи — раз в 20 тиков (1 сек), только ночью
        if (serverLevel.getGameTime() % 20 == 0 && isNight) {
            BloodMoonManager mgr = BloodMoonManager.get(serverLevel);
            boolean isBloodMoon = mgr.onNightTick(serverLevel);
            if (isBloodMoon) {
                BloodMoonEvents.buffAllMobsForBloodMoon(serverLevel, mgr);
                BloodMoonEvents.announceBloodMoon(serverLevel, mgr);
            }
        }

        // Сброс флага при рассвете
        if (time >= 1000 && time <= 1020 && serverLevel.getGameTime() % 20 == 0) {
            BloodMoonManager.get(serverLevel).onDayBegins();
        }
    }

    // ─── Базовый бафф ─────────────────────────────────────────────────────────

    private static void applyBaseBuffsOnce(LivingEntity living, boolean night) {
        CompoundTag tag = living.getPersistentData();
        if (tag.getBoolean(NBT_BUFFED) && tag.getBoolean(NBT_WAS_NIGHT) == night) return;
        applyBaseBuffs(living, night);
        tag.putBoolean(NBT_BUFFED, true);
        tag.putBoolean(NBT_WAS_NIGHT, night);
    }

    private static void applyBaseBuffs(LivingEntity living, boolean night) {
        double mult = night ? NIGHT_MULT : DAY_MULT;

        if (living instanceof WitherBoss || living instanceof EnderDragon) {
            setMaxHp(living, getBaseMaxHp(living) * BOSS_HP_MULT);
            setBaseDamage(living, getBaseDamage(living) * BOSS_DAMAGE_MULT);
            return;
        }
        if (living instanceof Ghast || living instanceof WitherSkeleton
         || living instanceof Skeleton || living instanceof Stray
         || living instanceof Pillager || living instanceof Evoker) {
            setMaxHp(living, getBaseMaxHp(living) * mult);
            setBaseDamage(living, getBaseDamage(living) * mult);
            return;
        }
        if (living instanceof Ravager || living instanceof ElderGuardian) {
            setMaxHp(living, getBaseMaxHp(living) * mult);
            setBaseDamage(living, getBaseDamage(living) * mult);
            setBaseSpeed(living, getBaseSpeed(living) * (1.0 + (mult - 1.0) * 0.5));
            return;
        }
        if (living instanceof Drowned || living instanceof Guardian) {
            setMaxHp(living, getBaseMaxHp(living) * mult);
            setBaseDamage(living, getBaseDamage(living) * mult);
            setBaseSpeed(living, getBaseSpeed(living) * (1.0 + (mult - 1.0) * 0.7));
            return;
        }
        if (living instanceof Zombie || living instanceof Husk
         || living instanceof Creeper || living instanceof Spider
         || living instanceof CaveSpider || living instanceof EnderMan
         || living instanceof Witch || living instanceof Vindicator
         || living instanceof ZombifiedPiglin || living instanceof PiglinBrute
         || living instanceof Piglin || living instanceof Blaze) {
            setMaxHp(living, getBaseMaxHp(living) * mult);
            setBaseDamage(living, getBaseDamage(living) * mult);
            setBaseSpeed(living, getBaseSpeed(living) * mult);
        }
    }

    private static void applyAggroRange(LivingEntity living, boolean night) {
        if (!(living instanceof Mob mob)) return;
        AttributeInstance attr = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (attr != null) attr.setBaseValue(night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE);
    }

    // ─── Геттеры базы из NBT ──────────────────────────────────────────────────

    private static double getBaseMaxHp(LivingEntity living) {
        CompoundTag tag = living.getPersistentData();
        if (!tag.contains("ed_base_hp")) {
            AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
            tag.putDouble("ed_base_hp", attr != null ? attr.getBaseValue() : living.getMaxHealth());
        }
        return tag.getDouble("ed_base_hp");
    }

    private static double getBaseDamage(LivingEntity living) {
        CompoundTag tag = living.getPersistentData();
        if (!tag.contains("ed_base_dmg")) {
            AttributeInstance attr = living.getAttribute(Attributes.ATTACK_DAMAGE);
            tag.putDouble("ed_base_dmg", attr != null ? attr.getBaseValue() : 2.0);
        }
        return tag.getDouble("ed_base_dmg");
    }

    private static double getBaseSpeed(LivingEntity living) {
        CompoundTag tag = living.getPersistentData();
        if (!tag.contains("ed_base_spd")) {
            AttributeInstance attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
            tag.putDouble("ed_base_spd", attr != null ? attr.getBaseValue() : 0.23);
        }
        return tag.getDouble("ed_base_spd");
    }

    // ─── Сеттеры ─────────────────────────────────────────────────────────────

    private static void setMaxHp(LivingEntity living, double newMaxHp) {
        AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        double oldMax = attr.getBaseValue();
        attr.setBaseValue(newMaxHp);
        if (oldMax > 0)
            living.setHealth((float) Math.min(living.getHealth() * (newMaxHp / oldMax), newMaxHp));
    }

    private static void setBaseDamage(LivingEntity living, double value) {
        AttributeInstance attr = living.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr != null) attr.setBaseValue(value);
    }

    private static void setBaseSpeed(LivingEntity living, double value) {
        AttributeInstance attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) attr.setBaseValue(value);
    }
}
