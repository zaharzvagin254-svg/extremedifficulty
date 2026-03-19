package com.extremedifficulty;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
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
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.nbt.CompoundTag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BloodMoonEvents {

    private static final Logger LOGGER = LogManager.getLogger();

    // Ключ в NBT — при какой судной ночи моб был забафан последний раз
    private static final String NBT_BLOOD_MOON_IDX = "ed_bm_index";

    // ─── Запрет сна в судную ночь ─────────────────────────────────────────────
    @SubscribeEvent
    public void onPlayerSleep(PlayerSleepInBedEvent event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        BloodMoonManager mgr = BloodMoonManager.get(serverLevel);
        if (mgr.isBloodMoonActive(serverLevel)) {
            // Запрещаем сон — отправляем причину
            event.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);

            // Сообщение игроку
            player.displayClientMessage(
                Component.literal("§4Судная ночь. Нежить не даст тебе спать..."),
                true // actionbar
            );
        }
    }

    // ─── Бафф всех мобов при наступлении судной ночи ─────────────────────────
    public static void buffAllMobsForBloodMoon(ServerLevel level, BloodMoonManager mgr) {
        int currentBloodMoon = mgr.getBloodMoonCount();
        double buffMult = mgr.getBuffMult();

        level.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof LivingEntity living)) return;
            buffMobForBloodMoon(living, buffMult, currentBloodMoon);
        });

        LOGGER.info("[ExtremeDifficulty] Buffed all mobs for blood moon #{}, mult=x{}", currentBloodMoon, buffMult);
    }

    /**
     * Применяет бафф судной ночи к конкретному мобу.
     * Вызывается и при спауне (для новых мобов) и при наступлении судной ночи.
     */
    public static void buffMobForBloodMoon(LivingEntity living, double buffMult, int bloodMoonIndex) {
        CompoundTag tag = living.getPersistentData();

        // Уже забафан на эту судную ночь — пропускаем
        if (tag.getInt(NBT_BLOOD_MOON_IDX) >= bloodMoonIndex) return;

        // Применяем бафф поверх базовых значений
        applyBloodMoonBuffs(living, buffMult);

        tag.putInt(NBT_BLOOD_MOON_IDX, bloodMoonIndex);
    }

    // ─── Логика баффов по группам ─────────────────────────────────────────────
    private static void applyBloodMoonBuffs(LivingEntity living, double mult) {

        // Боссы — только HP и урон, скорость не трогаем
        if (living instanceof WitherBoss || living instanceof EnderDragon) {
            scaleAttribute(living, Attributes.MAX_HEALTH, mult);
            scaleAttribute(living, Attributes.ATTACK_DAMAGE, mult);
            healToMax(living);
            return;
        }

        // Летающие / дальнобойные — HP и урон, без скорости
        if (living instanceof Ghast
         || living instanceof WitherSkeleton
         || living instanceof Skeleton
         || living instanceof Stray
         || living instanceof Pillager
         || living instanceof Evoker) {
            scaleAttribute(living, Attributes.MAX_HEALTH, mult);
            scaleAttribute(living, Attributes.ATTACK_DAMAGE, mult);
            healToMax(living);
            return;
        }

        // Большие медленные — скорость растёт меньше
        if (living instanceof Ravager || living instanceof ElderGuardian) {
            scaleAttribute(living, Attributes.MAX_HEALTH, mult);
            scaleAttribute(living, Attributes.ATTACK_DAMAGE, mult);
            scaleAttribute(living, Attributes.MOVEMENT_SPEED, 1.0 + (mult - 1.0) * 0.4);
            healToMax(living);
            return;
        }

        // Водные
        if (living instanceof Drowned || living instanceof Guardian) {
            scaleAttribute(living, Attributes.MAX_HEALTH, mult);
            scaleAttribute(living, Attributes.ATTACK_DAMAGE, mult);
            scaleAttribute(living, Attributes.MOVEMENT_SPEED, 1.0 + (mult - 1.0) * 0.6);
            healToMax(living);
            return;
        }

        // Стандартные ближние — полный бафф
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
            scaleAttribute(living, Attributes.MAX_HEALTH, mult);
            scaleAttribute(living, Attributes.ATTACK_DAMAGE, mult);
            scaleAttribute(living, Attributes.MOVEMENT_SPEED, mult);
            healToMax(living);
        }
    }

    // ─── Хелперы ─────────────────────────────────────────────────────────────

    /** Умножает атрибут на mult (от текущего базового значения) */
    private static void scaleAttribute(LivingEntity living,
                                        net.minecraft.world.entity.ai.attributes.Attribute attribute,
                                        double mult) {
        AttributeInstance attr = living.getAttribute(attribute);
        if (attr == null) return;
        attr.setBaseValue(attr.getBaseValue() * mult);
    }

    /** Восстанавливает HP до нового максимума после изменения */
    private static void healToMax(LivingEntity living) {
        living.setHealth(living.getMaxHealth());
    }

    // ─── Объявление судной ночи всем игрокам ─────────────────────────────────
    public static void announceBloodMoon(ServerLevel level, BloodMoonManager mgr) {
        for (ServerPlayer player : level.players()) {
            // Title на экране
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                Component.literal("§4☽ Кровавая Луна ☾")
            ));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                Component.literal("§cСудная ночь #" + mgr.getBloodMoonCount() + " — нежить стала сильнее")
            ));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
                20, 80, 20  // fadeIn, stay, fadeOut в тиках
            ));

            // Звук колоколов (кастомный)
            player.level().playSound(
                null,
                player.getX(), player.getY(), player.getZ(),
                ModSounds.BLOOD_MOON_BELL.get(),
                SoundSource.AMBIENT,
                1.5f, // громкость
                1.0f  // pitch
            );
        }
    }
}
