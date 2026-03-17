package com.extremedifficulty;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("extremedifficulty")
public class ExtremeDifficulty {

    private static final Logger LOGGER = LogManager.getLogger();

    // ── множители для обычных мобов ──────────────────────────────────────────
    private static final double DAY_MULT   = 1.50;
    private static final double NIGHT_MULT = 1.85;

    // ── отдельные множители для боссов ───────────────────────────────────────
    // Боссам увеличиваем только HP и урон, скорость не трогаем совсем
    private static final double BOSS_HP_MULT     = 2.00;
    private static final double BOSS_DAMAGE_MULT = 1.60;

    // ── моб-специфичные исключения по скорости ───────────────────────────────
    // Гасты, Призыватели и летающие мобы — скорость не масштабируем
    // (у них она либо не применима, либо сделает поведение сломанным)

    public ExtremeDifficulty() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[ExtremeDifficulty] Mod loaded! Mobs are now stronger.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  При появлении моба в мире
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();

        if (!(entity instanceof LivingEntity living)) return;
        if (level.isClientSide()) return;

        boolean night = isNight(level);
        applyBuffs(living, night);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Каждый тик сервера — обновляем статы при смене дня/ночи
    // ─────────────────────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;

        // Обновляем только раз в 100 тиков (5 сек) — не каждый тик,
        // чтобы не нагружать сервер
        if (serverLevel.getGameTime() % 100 != 0) return;

        boolean night = isNight(serverLevel);
        serverLevel.getEntities().getAll().forEach(entity -> {
            if (entity instanceof LivingEntity living) {
                applyBuffs(living, night);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Определяем: ночь ли сейчас (или «вечное ночь»-измерение)
    // ─────────────────────────────────────────────────────────────────────────
    private static boolean isNight(Level level) {
        // Незер и Энд — всегда ночной режим
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;

        // В Верхнем мире — по игровому времени суток
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Главная логика распределения баффов
    // ─────────────────────────────────────────────────────────────────────────
    private static void applyBuffs(LivingEntity living, boolean night) {
        double mult = night ? NIGHT_MULT : DAY_MULT;

        // ── БОССЫ: только HP + урон, скорость неизменна ──────────────────────
        if (living instanceof WitherBoss || living instanceof EnderDragon) {
            setMaxHp(living, living.getMaxHealth() * BOSS_HP_MULT);
            scaleDamage(living, BOSS_DAMAGE_MULT);
            // скорость боссов намеренно не меняем
            return;
        }

        // ── ЛЕТАЮЩИЕ / ДАЛЬНОБОЙНЫЕ: только HP + урон, без скорости движения ─
        // Гаст летает и атакует огненными шарами — скорость не масштабируем
        // Иссушённый скелет / Скелет / Страй / Мародёр / Призыватель — стрелки
        if (living instanceof Ghast
         || living instanceof WitherSkeleton
         || living instanceof Skeleton
         || living instanceof Stray
         || living instanceof Pillager
         || living instanceof Evoker) {
            setStats(living, mult, mult, 1.0 /* скорость = без изменений */);
            return;
        }

        // ── БОЛЬШИЕ / МЕДЛЕННЫЕ МОБЫ: умеренное увеличение скорости ──────────
        // Равагер и Elder Guardian изначально медленные — чуть меньше +скорость
        if (living instanceof Ravager || living instanceof ElderGuardian) {
            double speedMult = 1.0 + (mult - 1.0) * 0.5; // половина от общего множителя
            setStats(living, mult, mult, speedMult);
            return;
        }

        // ── ВОДНЫЕ МОБЫ: Утопленник, Страж — скорость в воде, пусть растёт чуть меньше
        if (living instanceof Drowned || living instanceof Guardian) {
            double speedMult = 1.0 + (mult - 1.0) * 0.7;
            setStats(living, mult, mult, speedMult);
            return;
        }

        // ── СТАНДАРТНЫЕ БЛИЖНИЕ МОБЫ: полный множитель ────────────────────────
        // Zombie, Husk, Creeper, Spider, CaveSpider, EnderMan,
        // Witch, Vindicator, ZombifiedPiglin, PiglinBrute, Piglin, Blaze
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

        // Всё остальное (кастомные мобы из других модов и т.д.) — не трогаем
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Хелперы
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Устанавливает HP, урон и скорость с независимыми множителями.
     * hpMult, damageMult, speedMult = 1.0 означает «без изменений».
     */
    private static void setStats(LivingEntity living,
                                  double hpMult,
                                  double damageMult,
                                  double speedMult) {
        // HP
        if (hpMult != 1.0) {
            setMaxHp(living, living.getMaxHealth() * hpMult);
        }
        // Урон
        if (damageMult != 1.0) {
            scaleDamage(living, damageMult);
        }
        // Скорость
        if (speedMult != 1.0) {
            scaleSpeed(living, speedMult);
        }
    }

    /** Устанавливает максимальное HP и пропорционально восстанавливает текущее. */
    private static void setMaxHp(LivingEntity living, double newMaxHp) {
        AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;

        double oldMax = attr.getValue();
        if (oldMax <= 0) return;

        attr.setBaseValue(newMaxHp);

        // Восстанавливаем текущее HP пропорционально, чтобы не «лечить» моба до полного
        float newHp = (float) Math.min(
            living.getHealth() * (newMaxHp / oldMax),
            newMaxHp
        );
        living.setHealth(newHp);
    }

    /** Масштабирует базовый урон атаки. */
    private static void scaleDamage(LivingEntity living, double mult) {
        AttributeInstance attr = living.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr == null) return;
        attr.setBaseValue(attr.getBaseValue() * mult);
    }

    /** Масштабирует скорость передвижения. */
    private static void scaleSpeed(LivingEntity living, double mult) {
        AttributeInstance attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return;
        attr.setBaseValue(attr.getBaseValue() * mult);
    }
}
