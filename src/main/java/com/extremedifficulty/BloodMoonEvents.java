package com.extremedifficulty;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
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
    private static final String NBT_BLOOD_MOON_IDX = "ed_bm_index";

    // ─── Запрет сна ───────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onPlayerSleep(PlayerSleepInBedEvent event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        ServerLevel overworld = serverLevel.getServer().overworld();
        BloodMoonManager mgr = BloodMoonManager.get(overworld);

        if (mgr.isBloodMoonActive(serverLevel)) {
            event.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
            player.displayClientMessage(
                Component.literal("§4Судная ночь. Нежить не даст тебе спать..."),
                true
            );
        }
    }

    // ─── Бафф всех мобов в измерении ─────────────────────────────────────────
    public static void buffAllMobsForBloodMoon(ServerLevel level, BloodMoonManager mgr) {
        int currentBloodMoon = mgr.getBloodMoonCount();
        double buffMult = mgr.getBuffMult();

        level.getEntities().getAll().forEach(entity -> {
            if (entity instanceof LivingEntity living) {
                buffMobForBloodMoon(living, buffMult, currentBloodMoon);
            }
        });
    }

    /**
     * ФИХ БАГ 5: бафф считается от БАЗОВЫХ значений (NBT), а не от текущих.
     * Это гарантирует что каждая судная ночь добавляет ровно +15% от базы,
     * независимо от того какой базовый бафф (день/ночь) уже применён.
     */
    public static void buffMobForBloodMoon(LivingEntity living, double buffMult, int bloodMoonIndex) {
        CompoundTag tag = living.getPersistentData();
        if (tag.getInt(NBT_BLOOD_MOON_IDX) >= bloodMoonIndex) return;

        applyBloodMoonBuffs(living, buffMult);
        tag.putInt(NBT_BLOOD_MOON_IDX, bloodMoonIndex);
    }

    private static void applyBloodMoonBuffs(LivingEntity living, double mult) {

        // Боссы — только HP и урон от базы
        if (living instanceof WitherBoss || living instanceof EnderDragon) {
            setFromBase(living, Attributes.MAX_HEALTH,     mult);
            setFromBase(living, Attributes.ATTACK_DAMAGE,  mult);
            living.setHealth(living.getMaxHealth());
            return;
        }

        // Летающие / дальнобойные — без скорости
        if (living instanceof Ghast
         || living instanceof WitherSkeleton
         || living instanceof Skeleton
         || living instanceof Stray
         || living instanceof Pillager
         || living instanceof Evoker) {
            setFromBase(living, Attributes.MAX_HEALTH,    mult);
            setFromBase(living, Attributes.ATTACK_DAMAGE, mult);
            living.setHealth(living.getMaxHealth());
            return;
        }

        // Большие медленные
        if (living instanceof Ravager || living instanceof ElderGuardian) {
            setFromBase(living, Attributes.MAX_HEALTH,     mult);
            setFromBase(living, Attributes.ATTACK_DAMAGE,  mult);
            setFromBase(living, Attributes.MOVEMENT_SPEED, 1.0 + (mult - 1.0) * 0.4);
            living.setHealth(living.getMaxHealth());
            return;
        }

        // Водные
        if (living instanceof Drowned || living instanceof Guardian) {
            setFromBase(living, Attributes.MAX_HEALTH,     mult);
            setFromBase(living, Attributes.ATTACK_DAMAGE,  mult);
            setFromBase(living, Attributes.MOVEMENT_SPEED, 1.0 + (mult - 1.0) * 0.6);
            living.setHealth(living.getMaxHealth());
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
            setFromBase(living, Attributes.MAX_HEALTH,     mult);
            setFromBase(living, Attributes.ATTACK_DAMAGE,  mult);
            setFromBase(living, Attributes.MOVEMENT_SPEED, mult);
            living.setHealth(living.getMaxHealth());
        }
    }

    /**
     * ФИХ БАГ 5: устанавливает атрибут как base_value * mult.
     * Использует сохранённое в NBT оригинальное базовое значение —
     * НЕ умножает поверх текущего (это был источник накопления).
     */
    private static void setFromBase(LivingEntity living, Attribute attribute, double mult) {
        AttributeInstance attr = living.getAttribute(attribute);
        if (attr == null) return;

        double baseValue;
        if (attribute == Attributes.MAX_HEALTH) {
            baseValue = ExtremeDifficulty.getBaseMaxHp(living);
        } else if (attribute == Attributes.ATTACK_DAMAGE) {
            baseValue = ExtremeDifficulty.getBaseDamage(living);
        } else if (attribute == Attributes.MOVEMENT_SPEED) {
            baseValue = ExtremeDifficulty.getBaseSpeed(living);
        } else {
            baseValue = attr.getBaseValue();
        }

        attr.setBaseValue(baseValue * mult);
    }

    // ─── Объявление судной ночи ───────────────────────────────────────────────
    public static void announceBloodMoon(ServerLevel level, BloodMoonManager mgr) {
        // ФИХ БАГ 2: отправляем пакет всем клиентам чтобы включить красный фильтр
        ModNetwork.sendToAll(true);

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            // Title на экране
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                Component.literal("§4☽ Кровавая Луна ☾")
            ));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                Component.literal("§cСудная ночь #" + mgr.getBloodMoonCount() + " — нежить стала сильнее")
            ));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
                20, 80, 20
            ));

            // ФИХ БАГ 4: звук играем напрямую у каждого игрока через его позицию
            // чтобы все слышали независимо от расстояния между игроками
            player.serverLevel().playSound(
                null,
                player.getX(), player.getY(), player.getZ(),
                ModSounds.BLOOD_MOON_BELL.get(),
                SoundSource.AMBIENT,
                64.0f,  // очень большой радиус — слышно везде
                1.0f
            );
        }
    }
}
