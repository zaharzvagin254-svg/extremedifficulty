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

    // ── Множители день/ночь ──────────────────────────────────────────────────
    private static final double DAY_MULT   = 1.30;
    private static final double NIGHT_MULT = 1.50;

    // ── Скорость = 0.4 от общего множителя ──────────────────────────────────
    private static final double SPEED_FRACTION = 0.4;

    // ── Боссы ────────────────────────────────────────────────────────────────
    private static final double BOSS_HP_MULT     = 1.70;
    private static final double BOSS_DAMAGE_MULT = 1.40;

    // ── Агрессия ─────────────────────────────────────────────────────────────
    private static final double DAY_AGGRO_RANGE   = 32.0;
    private static final double NIGHT_AGGRO_RANGE = 48.0;

    public ExtremeDifficulty() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
        }

        MinecraftForge.EVENT_BUS.register(this);
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

        boolean night = isNight(level);
        applyBuffsOnce(living, night);
        applyAggroRange(living, night);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getGameTime() % 100 != 0) return;

        boolean night = isNight(serverLevel);

        serverLevel.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof LivingEntity living)) return;
            applyAggroRange(living, night);
            CompoundTag tag = living.getPersistentData();
            if (tag.getBoolean(NBT_WAS_NIGHT) != night) {
                tag.putBoolean(NBT_BUFFED, false);
                applyBuffsOnce(living, night);
            }
        });

        // Групповая агрессия
        double range = night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE;
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

    private static void applyBuffsOnce(LivingEntity living, boolean night) {
        CompoundTag tag = living.getPersistentData();
        if (tag.getBoolean(NBT_BUFFED) && tag.getBoolean(NBT_WAS_NIGHT) == night) return;
        applyBuffs(living, night);
        tag.putBoolean(NBT_BUFFED, true);
        tag.putBoolean(NBT_WAS_NIGHT, night);
    }

    private static void applyBuffs(LivingEntity living, boolean night) {
        double mult      = night ? NIGHT_MULT : DAY_MULT;
        double speedMult = 1.0 + (mult - 1.0) * SPEED_FRACTION;

        if (living instanceof WitherBoss || living instanceof EnderDragon) {
            setMaxHp(living, getBase("ed_base_hp", living, Attributes.MAX_HEALTH) * BOSS_HP_MULT);
            setAttr(living, Attributes.ATTACK_DAMAGE,
                getBase("ed_base_dmg", living, Attributes.ATTACK_DAMAGE) * BOSS_DAMAGE_MULT);
            return;
        }
        if (living instanceof Ghast || living instanceof WitherSkeleton
         || living instanceof Skeleton || living instanceof Stray
         || living instanceof Pillager || living instanceof Evoker) {
            setMaxHp(living, getBase("ed_base_hp", living, Attributes.MAX_HEALTH) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE,
                getBase("ed_base_dmg", living, Attributes.ATTACK_DAMAGE) * mult);
            return;
        }
        if (living instanceof Ravager || living instanceof ElderGuardian) {
            setMaxHp(living, getBase("ed_base_hp", living, Attributes.MAX_HEALTH) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE,
                getBase("ed_base_dmg", living, Attributes.ATTACK_DAMAGE) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED,
                getBase("ed_base_spd", living, Attributes.MOVEMENT_SPEED) * (1.0 + (mult - 1.0) * SPEED_FRACTION * 0.5));
            return;
        }
        if (living instanceof Drowned || living instanceof Guardian) {
            setMaxHp(living, getBase("ed_base_hp", living, Attributes.MAX_HEALTH) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE,
                getBase("ed_base_dmg", living, Attributes.ATTACK_DAMAGE) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED,
                getBase("ed_base_spd", living, Attributes.MOVEMENT_SPEED) * (1.0 + (mult - 1.0) * SPEED_FRACTION * 0.7));
            return;
        }
        if (living instanceof Zombie || living instanceof Husk
         || living instanceof Creeper || living instanceof Spider
         || living instanceof CaveSpider || living instanceof EnderMan
         || living instanceof Witch || living instanceof Vindicator
         || living instanceof ZombifiedPiglin || living instanceof PiglinBrute
         || living instanceof Piglin || living instanceof Blaze) {
            setMaxHp(living, getBase("ed_base_hp", living, Attributes.MAX_HEALTH) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE,
                getBase("ed_base_dmg", living, Attributes.ATTACK_DAMAGE) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED,
                getBase("ed_base_spd", living, Attributes.MOVEMENT_SPEED) * speedMult);
        }
    }

    private static void applyAggroRange(LivingEntity living, boolean night) {
        if (!(living instanceof Mob mob)) return;
        AttributeInstance attr = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (attr != null) attr.setBaseValue(night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE);
    }

    private static double getBase(String key, LivingEntity living,
                                   net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        CompoundTag tag = living.getPersistentData();
        if (!tag.contains(key)) {
            AttributeInstance attr = living.getAttribute(attribute);
            tag.putDouble(key, attr != null ? attr.getBaseValue() : 1.0);
        }
        return tag.getDouble(key);
    }

    private static void setMaxHp(LivingEntity living, double newMax) {
        AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        double oldMax = attr.getBaseValue();
        attr.setBaseValue(newMax);
        if (oldMax > 0)
            living.setHealth((float) Math.min(living.getHealth() * (newMax / oldMax), newMax));
    }

    private static void setAttr(LivingEntity living,
                                  net.minecraft.world.entity.ai.attributes.Attribute attribute,
                                  double value) {
        AttributeInstance attr = living.getAttribute(attribute);
        if (attr != null) attr.setBaseValue(value);
    }

    private static boolean isNight(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }
}
