package com.extremedifficulty;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.CaveSpider;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.WitherSkeleton;
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

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();
        if (!(entity instanceof LivingEntity living)) return;
        if (level.isClientSide()) return;
        applyBuffs(living, isNight(level));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getGameTime() % 40 != 0) return;
        boolean night = isNight(serverLevel);
        serverLevel.getAllEntities().forEach(e -> {
            if (e instanceof LivingEntity living) applyBuffs(living, night);
        });
    }

    private boolean isNight(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END) return true;
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }

    private void applyBuffs(LivingEntity e, boolean n) {
        if (e instanceof Zombie)               setStats(e, n?50:35,   n?7:5,   n?0.40:0.32);
        else if (e instanceof Skeleton)        setStats(e, n?45:32,   n?6:5,   n?0.36:0.28);
        else if (e instanceof Husk)            setStats(e, n?50:35,   n?8:6,   n?0.38:0.30);
        else if (e instanceof Stray)           setStats(e, n?45:32,   n?8:6,   n?0.36:0.28);
        else if (e instanceof Creeper)         setStats(e, n?42:30,   n?4:3,   n?0.38:0.30);
        else if (e instanceof CaveSpider)      setStats(e, n?28:20,   n?5:4,   n?0.56:0.45);
        else if (e instanceof Spider)          setStats(e, n?36:26,   n?5:4,   n?0.52:0.42);
        else if (e instanceof EnderMan)        setStats(e, n?90:65,   n?14:11, n?0.44:0.35);
        else if (e instanceof Drowned)         setStats(e, n?48:34,   n?8:6,   n?0.36:0.28);
        else if (e instanceof Witch)           setStats(e, n?58:42,   n?4:3,   n?0.36:0.28);
        else if (e instanceof Vindicator)      setStats(e, n?56:40,   n?20:15, n?0.40:0.32);
        else if (e instanceof Pillager)        setStats(e, n?52:38,   n?8:6,   n?0.38:0.30);
        else if (e instanceof Evoker)          setStats(e, n?52:38,   n?6:4,   n?0.36:0.28);
        else if (e instanceof Ravager)         setStats(e, n?240:175, n?26:20, n?0.38:0.30);
        else if (e instanceof ZombifiedPiglin) setStats(e, n?48:35,   n?13:10, n?0.38:0.30);
        else if (e instanceof PiglinBrute)     setStats(e, n?115:85,  n?36:28, n?0.46:0.38);
        else if (e instanceof Piglin)          setStats(e, n?38:28,   n?11:8,  n?0.40:0.32);
        else if (e instanceof ElderGuardian)   setStats(e, n?175:130, n?22:16, n?0.30:0.25);
        else if (e instanceof Guardian)        setStats(e, n?68:50,   n?14:10, n?0.30:0.25);
        else if (e instanceof Blaze)           setStats(e, n?46:34,   n?11:8,  n?0.30:0.25);
        else if (e instanceof Ghast)           setStats(e, n?18:14,   n?8:6,   n?0.30:0.25);
        else if (e instanceof WitherSkeleton)  setStats(e, n?80:60,   n?16:12, n?0.36:0.30);
        else if (e instanceof WitherBoss)      setStats(e, n?650:500, n?20:15, n?0.30:0.25);
        else if (e instanceof EnderDragon)     setMaxHp(e, n?420:320);
    }

    private void setStats(LivingEntity e, double hp, double dmg, double spd) {
        setMaxHp(e, hp);
        if (e.getAttribute(Attributes.ATTACK_DAMAGE) != null)
            e.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(dmg);
        if (e.getAttribute(Attributes.MOVEMENT_SPEED) != null)
            e.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(spd);
    }

    private void setMaxHp(LivingEntity e, double hp) {
        if (e.getAttribute(Attributes.MAX_HEALTH) != null) {
            float cur = e.getHealth();
            float curMax = e.getMaxHealth();
            e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
            if (curMax > 0) {
                float newHp = (float)(cur / curMax * hp);
                e.setHealth(Math.max(newHp, cur));
            }
        }
    }
}
