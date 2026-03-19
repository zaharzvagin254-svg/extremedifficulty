package com.extremedifficulty;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
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

    private static final double DAY_AGGRO_RANGE   = 24.0;
    private static final double NIGHT_AGGRO_RANGE = 40.0;

    public ExtremeDifficulty() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModSounds.SOUNDS.register(modBus);
        ModNetwork.register();

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

    // ─── Спаун моба ───────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();
        if (!(entity instanceof LivingEntity living)) return;
        if (level.isClientSide()) return;

        // Только агрессия — никаких базовых баффов
        applyAggroRange(living, BloodMoonManager.isNight(level));

        // Если сейчас судная ночь — бафаем нового моба
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().overworld();
            BloodMoonManager mgr = BloodMoonManager.get(overworld);
            if (mgr.isBloodMoonActive(serverLevel)) {
                BloodMoonEvents.buffMobForBloodMoon(living, mgr.getBuffMult(), mgr.getBloodMoonCount());
            }
        }
    }

    // ─── Тик сервера ──────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;

        boolean isOverworld = serverLevel.dimension() == Level.OVERWORLD;
        boolean isNight = BloodMoonManager.isNight(serverLevel);
        long time = serverLevel.getDayTime() % 24000;

        // Агрессия — раз в 100 тиков
        if (serverLevel.getGameTime() % 100 == 0) {
            serverLevel.getEntities().getAll().forEach(entity -> {
                if (entity instanceof LivingEntity living)
                    applyAggroRange(living, isNight);
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

        // Проверка судной ночи — только в Overworld, раз в секунду
        if (isOverworld && serverLevel.getGameTime() % 20 == 0 && isNight) {
            ServerLevel overworld = serverLevel.getServer().overworld();
            BloodMoonManager mgr = BloodMoonManager.get(overworld);
            boolean isBloodMoon = mgr.onNightTick(serverLevel);
            if (isBloodMoon) {
                for (ServerLevel dim : serverLevel.getServer().getAllLevels()) {
                    BloodMoonEvents.buffAllMobsForBloodMoon(dim, mgr);
                }
                BloodMoonEvents.announceBloodMoon(serverLevel, mgr);
            }
        }

        // Сброс флага при рассвете
        if (isOverworld && time >= 1000 && time <= 1020 && serverLevel.getGameTime() % 20 == 0) {
            BloodMoonManager.get(serverLevel.getServer().overworld()).onDayBegins();
            ModNetwork.sendToAll(false);
        }
    }

    private static void applyAggroRange(LivingEntity living, boolean night) {
        if (!(living instanceof Mob mob)) return;
        AttributeInstance attr = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (attr != null) attr.setBaseValue(night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE);
    }

    // ─── Геттеры базовых значений из NBT ─────────────────────────────────────
    // (используются в BloodMoonEvents для расчёта баффов от оригинала)

    static double getBaseMaxHp(LivingEntity living) {
        var tag = living.getPersistentData();
        if (!tag.contains("ed_base_hp")) {
            AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
            tag.putDouble("ed_base_hp", attr != null ? attr.getBaseValue() : living.getMaxHealth());
        }
        return tag.getDouble("ed_base_hp");
    }

    static double getBaseDamage(LivingEntity living) {
        var tag = living.getPersistentData();
        if (!tag.contains("ed_base_dmg")) {
            AttributeInstance attr = living.getAttribute(Attributes.ATTACK_DAMAGE);
            tag.putDouble("ed_base_dmg", attr != null ? attr.getBaseValue() : 2.0);
        }
        return tag.getDouble("ed_base_dmg");
    }

    static double getBaseSpeed(LivingEntity living) {
        var tag = living.getPersistentData();
        if (!tag.contains("ed_base_spd")) {
            AttributeInstance attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
            tag.putDouble("ed_base_spd", attr != null ? attr.getBaseValue() : 0.23);
        }
        return tag.getDouble("ed_base_spd");
    }

    static void setMaxHp(LivingEntity living, double newMaxHp) {
        AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        double oldMax = attr.getBaseValue();
        attr.setBaseValue(newMaxHp);
        if (oldMax > 0)
            living.setHealth((float) Math.min(living.getHealth() * (newMaxHp / oldMax), newMaxHp));
    }

    static void setBaseDamage(LivingEntity living, double value) {
        AttributeInstance attr = living.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr != null) attr.setBaseValue(value);
    }

    static void setBaseSpeed(LivingEntity living, double value) {
        AttributeInstance attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) attr.setBaseValue(value);
    }
}
