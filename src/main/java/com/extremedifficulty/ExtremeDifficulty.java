package com.extremedifficulty;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
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

    private static final double DAY_MULT       = 1.30;
    private static final double NIGHT_MULT     = 1.50;
    private static final double SPEED_FRACTION = 0.4;

    private static final double BOSS_HP_MULT     = 1.70;
    private static final double BOSS_DAMAGE_MULT = 1.40;

    // FIX: брут-пиглин — урон только +10% вместо x1.5 (слишком смертоносно)
    private static final double BRUTE_DMG_MULT = 1.10;

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

    // ─── При старте сервера — ставим Hard и показываем EXTREME ───────────────
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();

        // Принудительно Hard
        server.overworld().getLevelData().setDifficulty(Difficulty.HARD);

        // Сообщение в консоль
        LOGGER.info("[ExtremeDifficulty] Difficulty locked to HARD (EXTREME MODE)");

        // Отправляем всем игрокам системное сообщение
        server.getPlayerList().getPlayers().forEach(player ->
            player.sendSystemMessage(Component.literal(
                "\u00a74[\u00a7cEXTREME DIFFICULTY\u00a74] \u00a7cThe world is unforgiving. Good luck."
            ))
        );
    }

    // ─── При входе игрока — показываем статус сложности ──────────────────────
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;

        // Показываем в tab-листе кастомный footer
        sp.connection.send(new net.minecraft.network.protocol.game.ClientboundTabListPacket(
            Component.literal("\u00a74\u00a7l\u2620 EXTREME DIFFICULTY \u2620"),
            Component.literal(
                "\u00a7cDay: HP+30% DMG+30% Speed+12%\n" +
                "\u00a74Night: HP+50% DMG+50% Speed+20%\n" +
                "\u00a77Aggro range doubled at night"
            )
        ));

        // Приветственное сообщение
        sp.sendSystemMessage(Component.literal(
            "\u00a74[EXTREME DIFFICULTY] \u00a7cMobs are stronger. Night is dangerous."
        ));
    }

    // ─── Спаун моба ───────────────────────────────────────────────────────────
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

    // ─── Тик сервера ──────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getGameTime() % 100 != 0) return;

        boolean night = isNight(serverLevel);
        double aggroRange = night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE;

        serverLevel.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof LivingEntity living)) return;

            applyAggroRange(living, night);

            CompoundTag tag = living.getPersistentData();
            if (tag.getBoolean(NBT_WAS_NIGHT) != night) {
                tag.putBoolean(NBT_BUFFED, false);
                applyBuffsOnce(living, night);
            }

            if (living instanceof Mob mob && mob.getTarget() == null) {
                List<Player> nearby = serverLevel.getEntitiesOfClass(
                    Player.class,
                    mob.getBoundingBox().inflate(aggroRange),
                    p -> !p.isCreative() && !p.isSpectator() && p.isAlive()
                );
                if (!nearby.isEmpty()) {
                    Player nearest = nearby.stream()
                        .min((a, b) -> Double.compare(mob.distanceToSqr(a), mob.distanceToSqr(b)))
                        .orElse(null);
                    if (nearest != null) mob.setTarget(nearest);
                }
            }
        });
    }

    // ─── Применяем баффы один раз через NBT ──────────────────────────────────
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

        // Боссы — только HP и урон
        if (living instanceof WitherBoss || living instanceof EnderDragon) {
            setMaxHp(living, baseHp(living)  * BOSS_HP_MULT);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * BOSS_DAMAGE_MULT);
            return;
        }

        // Летающие / дальнобойные — без скорости
        if (living instanceof Ghast || living instanceof WitherSkeleton
         || living instanceof Skeleton || living instanceof Stray
         || living instanceof Pillager || living instanceof Evoker) {
            setMaxHp(living, baseHp(living)  * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            return;
        }

        // Большие медленные — половина скорости
        if (living instanceof Ravager || living instanceof ElderGuardian) {
            setMaxHp(living, baseHp(living)  * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            // FIX 3: используем реальную базовую скорость моба из NBT
            setAttr(living, Attributes.MOVEMENT_SPEED,
                baseSpd(living) * (1.0 + (mult - 1.0) * SPEED_FRACTION * 0.5));
            return;
        }

        // Водные
        if (living instanceof Drowned || living instanceof Guardian) {
            setMaxHp(living, baseHp(living)  * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED,
                baseSpd(living) * (1.0 + (mult - 1.0) * SPEED_FRACTION * 0.7));
            return;
        }

        // FIX 4: Эндермен — отдельно, у него есть ATTACK_DAMAGE (7 в ванилле)
        if (living instanceof EnderMan) {
            setMaxHp(living, baseHp(living)  * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }

        // FIX 5: Крипер — только HP, урон идёт через взрыв (нет атрибута урона)
        if (living instanceof Creeper) {
            setMaxHp(living, baseHp(living) * mult);
            // скорость слегка увеличиваем
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }

        // FIX 6: Ведьма — только HP и скорость, урон через зелья (нет атрибута)
        if (living instanceof Witch) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }

        // FIX 7: Брут-пиглин — урон только +10%, HP полный
        if (living instanceof PiglinBrute) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * BRUTE_DMG_MULT);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }

        // Стандартные ближние — полный бафф
        if (living instanceof Zombie || living instanceof Husk
         || living instanceof Spider || living instanceof CaveSpider
         || living instanceof Vindicator
         || living instanceof ZombifiedPiglin
         || living instanceof Piglin || living instanceof Blaze) {
            setMaxHp(living, baseHp(living)  * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
        }
    }

    private static void applyAggroRange(LivingEntity living, boolean night) {
        if (!(living instanceof Mob mob)) return;
        AttributeInstance attr = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (attr != null) attr.setBaseValue(night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE);
    }

    // ─── Геттеры базовых значений из NBT ─────────────────────────────────────
    // FIX 3: каждый геттер читает реальный атрибут моба при первом вызове

    private static double baseHp(LivingEntity living) {
        CompoundTag tag = living.getPersistentData();
        if (!tag.contains("ed_base_hp")) {
            AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
            tag.putDouble("ed_base_hp", attr != null ? attr.getBaseValue() : 20.0);
        }
        return tag.getDouble("ed_base_hp");
    }

    private static double baseDmg(LivingEntity living) {
        CompoundTag tag = living.getPersistentData();
        if (!tag.contains("ed_base_dmg")) {
            AttributeInstance attr = living.getAttribute(Attributes.ATTACK_DAMAGE);
            // FIX 4: если атрибут есть — берём реальное значение
            // если нет (крипер, ведьма) — 0.0 чтобы не применять
            tag.putDouble("ed_base_dmg", attr != null ? attr.getBaseValue() : 0.0);
        }
        return tag.getDouble("ed_base_dmg");
    }

    private static double baseSpd(LivingEntity living) {
        CompoundTag tag = living.getPersistentData();
        if (!tag.contains("ed_base_spd")) {
            AttributeInstance attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
            // FIX 3: реальная скорость моба, не дефолт 0.23
            tag.putDouble("ed_base_spd", attr != null ? attr.getBaseValue() : 0.23);
        }
        return tag.getDouble("ed_base_spd");
    }

    // ─── Сеттеры ─────────────────────────────────────────────────────────────
    private static void setMaxHp(LivingEntity living, double newMax) {
        AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        double oldMax = attr.getBaseValue();
        attr.setBaseValue(newMax);
        if (oldMax > 0)
            living.setHealth((float) Math.min(living.getHealth() * (newMax / oldMax), newMax));
    }

    private static void setAttr(LivingEntity living, Attribute attribute, double value) {
        if (value <= 0) return; // не применяем нулевые значения
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
