package com.extremedifficulty;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private static final double BRUTE_DMG_MULT   = 1.10;

    private static final double DAY_AGGRO_RANGE   = 32.0;
    private static final double NIGHT_AGGRO_RANGE = 48.0;

    public ExtremeDifficulty() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        if (FMLEnvironment.dist == Dist.CLIENT)
            modBus.addListener(this::onClientSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new SpawnHandler());
        MinecraftForge.EVENT_BUS.register(new MobAIHandler());
        MinecraftForge.EVENT_BUS.register(new SoundSystem());
        MinecraftForge.EVENT_BUS.register(new PlayerPositionTracker());
        MinecraftForge.EVENT_BUS.register(new DebugCommand());
        LOGGER.info("[ExtremeDifficulty] Mod loaded!");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientSetup::registerRenderers);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        event.getServer().setDifficulty(Difficulty.HARD, true);
        LOGGER.info("[ExtremeDifficulty] Difficulty locked to HARD");
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        sp.connection.send(new net.minecraft.network.protocol.game.ClientboundTabListPacket(
            Component.literal("\u00a74\u00a7l\u2620 EXTREME DIFFICULTY \u2620"),
            Component.literal(
                "\u00a7cDay:   HP+30%  DMG+30%  Speed+12%\n" +
                "\u00a74Night: HP+50%  DMG+50%  Speed+20%\n" +
                "\u00a77Aggro range doubled at night"
            )
        ));
        sp.sendSystemMessage(Component.literal(
            "\u00a74[\u00a7cEXTREME DIFFICULTY\u00a74] \u00a7cMobs are stronger. Night is dangerous."
        ));
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

        // OPT: skip aggro loop if no players in this level
        List<ServerPlayer> players = serverLevel.players();
        boolean hasPlayers = !players.isEmpty();

        serverLevel.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof LivingEntity living)) return;

            CompoundTag tag = living.getPersistentData();
            if (tag.getBoolean(NBT_WAS_NIGHT) != night) {
                tag.putBoolean(NBT_BUFFED, false);
                applyBuffsOnce(living, night);
            }

            // OPT: skip aggro range if no players present
            if (hasPlayers) applyAggroRange(living, night);
        });
        // NOTE: No custom aggro loop - vanilla NearestAttackableTargetGoal
        // handles targeting with proper LOS checks. Adding setTarget() here
        // bypasses LOS and causes mobs to target through walls.
    }

    // -------------------------------------------------------------------------

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
            setMaxHp(living, baseHp(living) * BOSS_HP_MULT);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * BOSS_DAMAGE_MULT);
            return;
        }
        if (living instanceof Ghast || living instanceof WitherSkeleton
         || living instanceof Skeleton || living instanceof Stray
         || living instanceof Pillager || living instanceof Evoker) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            return;
        }
        if (living instanceof Ravager || living instanceof ElderGuardian) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED,
                baseSpd(living) * (1.0 + (mult-1.0)*SPEED_FRACTION*0.5));
            return;
        }
        if (living instanceof Drowned || living instanceof Guardian) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED,
                baseSpd(living) * (1.0 + (mult-1.0)*SPEED_FRACTION*0.7));
            return;
        }
        if (living instanceof EnderMan) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }
        if (living instanceof Creeper) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }
        if (living instanceof Witch) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }
        if (living instanceof PiglinBrute) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * BRUTE_DMG_MULT);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }
        if (living instanceof ZombifiedPiglin) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }
        if (living instanceof Husk) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }
        if (living instanceof CaveSpider) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
            return;
        }
        if (living instanceof Zombie || living instanceof Spider
         || living instanceof Vindicator
         || living instanceof Piglin || living instanceof Blaze) {
            setMaxHp(living, baseHp(living) * mult);
            setAttr(living, Attributes.ATTACK_DAMAGE, baseDmg(living) * mult);
            setAttr(living, Attributes.MOVEMENT_SPEED, baseSpd(living) * speedMult);
        }
    }

    private static void applyAggroRange(LivingEntity living, boolean night) {
        if (!(living instanceof Mob mob)) return;
        AttributeInstance attr = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (attr != null) attr.setBaseValue(night ? NIGHT_AGGRO_RANGE : DAY_AGGRO_RANGE);
    }

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
            tag.putDouble("ed_base_dmg", attr != null ? attr.getBaseValue() : 0.0);
        }
        return tag.getDouble("ed_base_dmg");
    }

    private static double baseSpd(LivingEntity living) {
        CompoundTag tag = living.getPersistentData();
        if (!tag.contains("ed_base_spd")) {
            AttributeInstance attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
            tag.putDouble("ed_base_spd", attr != null ? attr.getBaseValue() : 0.23);
        }
        return tag.getDouble("ed_base_spd");
    }

    private static void setMaxHp(LivingEntity living, double newMax) {
        AttributeInstance attr = living.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        double oldMax = attr.getBaseValue();
        attr.setBaseValue(newMax);
        if (oldMax > 0)
            living.setHealth((float) Math.min(living.getHealth()*(newMax/oldMax), newMax));
    }

    private static void setAttr(LivingEntity living, Attribute attribute, double value) {
        if (value <= 0) return;
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
