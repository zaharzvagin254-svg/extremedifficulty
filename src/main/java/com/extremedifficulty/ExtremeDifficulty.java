package com.extremedifficulty;

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

    // ── Множители статов ─────────────────────────────────────────────────────
    private static final double DAY_MULT   = 1.50;
    private static final double NIGHT_MULT = 1.85;

    // ── Боссы: только HP и урон ──────────────────────────────────────────────
    private static final double BOSS_HP_MULT     = 2.00;
    private static final double BOSS_DAMAGE_MULT = 1.60;

    // ── Агрессия: радиус в блоках ────────────────────────────────────────────
    private static final double DAY_AGGRO_RANGE   = 24.0; // днём видят дальше обычного (ванилла ~16)
    private static final double NIGHT_AGGRO_RANGE = 40.0; // ночью — ещё дальше

    public ExtremeDifficulty() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Регистрируем рендерер только на клиенте
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
        }

        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[ExtremeDifficulty] Mod loaded! Mobs are now stronger and more aggressive.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Клиентская инициализация — регистрируем кастомный рендерер зомби
    // ─────────────────────────────────────────────────────────────────────────
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientSetup::registerRenderers);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  При появлении моба — применяем баффы и расширяем радиус агрессии
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();

        if (!(entity instanceof LivingEntity living)) return;
        if (level.isClientSide()) return;

        boolean night = isNight(level);
        applyBuffs(living, night);
        applyAggroRange(living, night);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Тик сервера — обновляем раз в 100 тиков (5 сек)
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getGameTime() % 100 != 0) return;

        boolean night = isNight(serverLevel);

        serverLevel.getEntities().getAll().forEach(entity -> {
            if (entity instanceof LivingEntity living) {
                applyBuffs(living, night);
                applyAggroRange(living, night);
            }
        });

        // ── Агрессия в радиусе: каждые 100 тиков ищем игроков поблизости ────
        double range = night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE;

        serverLevel.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof Mob mob)) return;
            if (mob.getTarget() != null) return; // уже атакует кого-то

            List<Player> nearbyPlayers = serverLevel.getEntitiesOfClass(
                Player.class,
                mob.getBoundingBox().inflate(range),
                p -> !p.isCreative() && !p.isSpectator() && p.isAlive()
            );

            if (!nearbyPlayers.isEmpty()) {
                // Атакуем ближайшего игрока
                Player nearest = nearbyPlayers.stream()
                    .min((a, b) -> Double.compare(
                        mob.distanceToSqr(a),
                        mob.distanceToSqr(b)
                    ))
                    .orElse(null);

                if (nearest != null) {
                    mob.setTarget(nearest);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Расширяем атрибут дальности преследования
    // ─────────────────────────────────────────────────────────────────────────
    private static void applyAggroRange(LivingEntity living, boolean night) {
        if (!(living instanceof Mob mob)) return;

        AttributeInstance attr = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (attr == null) return;

        double range = night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE;
        attr.setBaseValue(range);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ночь / день
    // ─────────────────────────────────────────────────────────────────────────
    private static boolean isNight(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Распределение баффов по группам мобов
    // ─────────────────────────────────────────────────────────────────────────
    private static void applyBuffs(LivingEntity living, boolean night) {
        double mult = night ? NIGHT_MULT : DAY_MULT;

        // Боссы
        if (living instanceof WitherBoss || living instanceof EnderDragon) {
            setMaxHp(living, living.getMaxHealth() * BOSS_HP_MULT);
            scaleDamage(living, BOSS_DAMAGE_MULT);
            return;
        }

        // Летающие / дальнобойные — без скорости
        if (living instanceof Ghast
         || living instanceof WitherSkeleton
         || living instanceof Skeleton
         || living instanceof Stray
         || living instanceof Pillager
         || living instanceof Evoker) {
            setStats(living, mult, mult, 1.0);
            return;
        }

        // Большие медленные
        if (living instanceof Ravager || living instanceof ElderGuardian) {
            setStats(living, mult, mult, 1.0 + (mult - 1.0) * 0.5);
            return;
        }

        // Водные
        if (living instanceof Drowned || living instanceof Guardian) {
            setStats(living, mult, mult, 1.0 + (mult - 1.0) * 0.7);
            return;
        }

        // Стандартные ближние
        if (living instanceof Zombie
         || living instanceof Husk
         || living instanceof Creeper
         || living instanceof Spider
         || living instanceof CaveSpider
         || living instanceof EnderMan
         || living instanceof Witch
         || living instanceof Vindicator
         || living instanceof ZombifiedPiglin
         || living instanceof PiglinBrute
         || living instanceof Piglin
         || living instanceof Blaze) {
            setStats(living, mult, mult, mult);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Хелперы
    // ─────────────────────────────────────────────────────────────────────────

    private static void setStats(LivingEntity living, double hpMult, double damageMult, double speedMult) {
        if (hpMult != 1.0)     setMaxHp(living, living.getMaxHealth() * hpMult);
        if (damageMult != 1.0) scaleDamage(living, damageMult);
        if (speedMult != 1.0)  scaleSpeed(living, speedMult);
    }

    private static void setMaxHp(LivingEntity living, double newMaxHp) {
        AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        double oldMax = attr.getValue();
        if (oldMax <= 0) return;
        attr.setBaseValue(newMaxHp);
        float newHp = (float) Math.min(living.getHealth() * (newMaxHp / oldMax), newMaxHp);
        living.setHealth(newHp);
    }

    private static void scaleDamage(LivingEntity living, double mult) {
        AttributeInstance attr = living.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr == null) return;
        attr.setBaseValue(attr.getBaseValue() * mult);
    }

    private static void scaleSpeed(LivingEntity living, double mult) {
        AttributeInstance attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return;
        attr.setBaseValue(attr.getBaseValue() * mult);
    }
}
