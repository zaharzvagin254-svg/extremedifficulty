package com.extremedifficulty;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.EnumSet;
import java.util.List;

public class MobAIHandler {

    private static final String NBT_AI_SETUP = "ed_ai_setup";

    // How long mob searches last known position before giving up (ticks)
    private static final int SEARCH_DURATION = 100; // 5 seconds

    // Hear radius when player is NOT sneaking
    private static final double ZOMBIE_HEAR_RADIUS   = 10.0;
    private static final double SKELETON_HEAR_RADIUS  = 8.0;
    private static final double SPIDER_HEAR_RADIUS    = 8.0;
    private static final double CREEPER_HEAR_RADIUS   = 10.0;

    // Hear radius when player IS sneaking (reduced but not zero)
    private static final double SNEAK_HEAR_MULTIPLIER = 0.4;

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)) return;
        if (living.getPersistentData().getBoolean(NBT_AI_SETUP)) return;

        if (entity instanceof CaveSpider caveSpider) {
            addSpiderGoals(caveSpider);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        } else if (entity instanceof Zombie zombie && !(zombie instanceof ZombifiedPiglin)) {
            addZombieGoals(zombie);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        } else if (entity.getClass() == Skeleton.class) {
            addSkeletonGoals((Skeleton) entity);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        } else if (entity instanceof Spider spider) {
            addSpiderGoals(spider);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        } else if (entity instanceof Creeper creeper) {
            addCreeperGoals(creeper);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        }
    }

    private void addZombieGoals(Zombie zombie) {
        zombie.goalSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof BreakDoorGoal
        );
        zombie.goalSelector.addGoal(1, new FastBreakDoorGoal(zombie, 120));
        zombie.goalSelector.addGoal(4, new SmartSearchGoal(zombie, 1.0));
        zombie.targetSelector.addGoal(3, new SmartHearGoal(zombie, ZOMBIE_HEAR_RADIUS));
        zombie.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void addSkeletonGoals(Skeleton skeleton) {
        skeleton.targetSelector.addGoal(3, new SmartHearGoal(skeleton, SKELETON_HEAR_RADIUS));
        skeleton.goalSelector.addGoal(4, new SmartSearchGoal(skeleton, 1.0));
        skeleton.goalSelector.addGoal(2, new SafeKeepDistanceGoal(skeleton, 8.0, 12.0));
        skeleton.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void addSpiderGoals(Spider spider) {
        spider.targetSelector.addGoal(3, new SmartHearGoal(spider, SPIDER_HEAR_RADIUS));
        spider.goalSelector.addGoal(4, new SmartSearchGoal(spider, 1.2));
        spider.getNavigation().setMaxVisitedNodesMultiplier(3.0f);
    }

    private void addCreeperGoals(Creeper creeper) {
        creeper.targetSelector.addGoal(3, new SmartHearGoal(creeper, CREEPER_HEAR_RADIUS));
        creeper.goalSelector.addGoal(4, new SmartSearchGoal(creeper, 1.0));
        creeper.getNavigation().setMaxVisitedNodesMultiplier(2.5f);
    }

    // Zombie group alert on hurt
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        if (zombie instanceof ZombifiedPiglin) return;
        if (zombie.level().isClientSide()) return;
        if (!(zombie.level() instanceof ServerLevel serverLevel)) return;

        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof LivingEntity target)) return;

        // Do not alert if attacker is invisible and sneaking
        if (target instanceof Player p && p.isInvisible() && p.isCrouching()) return;

        List<Zombie> nearby = serverLevel.getEntitiesOfClass(
            Zombie.class,
            zombie.getBoundingBox().inflate(16.0),
            z -> z != zombie && !(z instanceof ZombifiedPiglin) && z.getTarget() == null
        );

        int count = 0;
        for (Zombie z : nearby) {
            if (count >= 6) break;
            z.setTarget(target);
            count++;
        }
    }

    // Tick: lose target if player is hidden (sneaking + no LOS + out of hear range)
    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        // Check every 20 ticks (1 second) - lightweight
        if (serverLevel.getGameTime() % 20 != 0) return;

        serverLevel.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof Mob mob)) return;
            if (!(mob.getTarget() instanceof Player player)) return;

            // Player must be sneaking to hide
            if (!player.isCrouching()) return;

            // Check LOS
            boolean hasLOS = mob.hasLineOfSight(player);

            // Check hear range (sneaking reduces radius)
            double hearRadius = getHearRadius(mob) * SNEAK_HEAR_MULTIPLIER;
            boolean canHear = mob.distanceToSqr(player) < hearRadius * hearRadius;

            // If no LOS AND out of sneak-hear range - lose target
            if (!hasLOS && !canHear) {
                mob.setTarget(null);
            }
        });
    }

    private double getHearRadius(Mob mob) {
        if (mob instanceof Zombie)   return ZOMBIE_HEAR_RADIUS;
        if (mob instanceof Skeleton) return SKELETON_HEAR_RADIUS;
        if (mob instanceof Spider)   return SPIDER_HEAR_RADIUS;
        if (mob instanceof Creeper)  return CREEPER_HEAR_RADIUS;
        return 8.0;
    }

    // -------------------------------------------------------------------------
    // GOALS
    // -------------------------------------------------------------------------

    static class FastBreakDoorGoal extends BreakDoorGoal {
        public FastBreakDoorGoal(Mob mob, int breakTicks) {
            super(mob, breakTicks, d -> d == Difficulty.HARD);
        }

        @Override
        public boolean canUse() {
            Level level = mob.level();
            if (level.dimension() == Level.OVERWORLD) {
                long time = level.getDayTime() % 24000;
                if (time < 13000 || time > 23000) return false;
            }
            return super.canUse();
        }
    }

    /**
     * Mob remembers last known player position.
     * Patrols around it, then gives up.
     * Does NOT reaggro if player is sneaking when mob arrives.
     */
    static class SmartSearchGoal extends Goal {

        private final Mob mob;
        private final double speed;
        private BlockPos lastKnownPos = null;
        private BlockPos patrolTarget = null;
        private int searchTicks = 0;

        public SmartSearchGoal(Mob mob, double speed) {
            this.mob = mob;
            this.speed = speed;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return mob.getTarget() == null
                && lastKnownPos != null
                && searchTicks < SEARCH_DURATION;
        }

        @Override
        public boolean canContinueToUse() {
            return mob.getTarget() == null
                && lastKnownPos != null
                && searchTicks < SEARCH_DURATION;
        }

        @Override
        public void start() {
            searchTicks = 0;
            patrolTarget = lastKnownPos;
            moveToTarget();
        }

        @Override
        public void tick() {
            // Update last known pos while target is visible
            if (mob.getTarget() instanceof Player player) {
                if (!player.isCrouching() || mob.hasLineOfSight(player)) {
                    lastKnownPos = player.blockPosition();
                    searchTicks = 0;
                }
                return;
            }

            searchTicks++;

            // Reached patrol point - pick a new random point nearby
            if (patrolTarget != null && mob.blockPosition().closerThan(patrolTarget, 2.0)) {
                if (searchTicks < SEARCH_DURATION / 2 && lastKnownPos != null) {
                    int ox = (int)((mob.getRandom().nextDouble() - 0.5) * 8);
                    int oz = (int)((mob.getRandom().nextDouble() - 0.5) * 8);
                    int nx = lastKnownPos.getX() + ox;
                    int nz = lastKnownPos.getZ() + oz;
                    // Clamp to surface - avoid going underground
                    int ny = mob.level().getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                    patrolTarget = new BlockPos(nx, ny, nz);
                    moveToTarget();
                } else {
                    // Give up
                    lastKnownPos = null;
                    patrolTarget = null;
                    searchTicks = 0;
                }
            }

            // Timeout - give up searching
            if (searchTicks >= SEARCH_DURATION) {
                lastKnownPos = null;
                patrolTarget = null;
                searchTicks = 0;
            }
        }

        private void moveToTarget() {
            if (patrolTarget != null) {
                mob.getNavigation().moveTo(
                    patrolTarget.getX() + 0.5,
                    patrolTarget.getY(),
                    patrolTarget.getZ() + 0.5,
                    speed
                );
            }
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            searchTicks = 0;
        }
    }

    /**
     * Hear player through walls at night.
     * Sneaking players are harder to hear (reduced radius).
     * Does not trigger if player is invisible.
     */
    static class SmartHearGoal extends NearestAttackableTargetGoal<Player> {

        private final double hearRadius;
        // Performance: create once
        private final TargetingConditions hearConditions;
        private final TargetingConditions sneakConditions;

        public SmartHearGoal(Mob mob, double hearRadius) {
            super(mob, Player.class, 10, true, false, null);
            this.hearRadius = hearRadius;
            this.hearConditions = TargetingConditions.forCombat()
                .range(hearRadius)
                .ignoreLineOfSight();
            this.sneakConditions = TargetingConditions.forCombat()
                .range(hearRadius * SNEAK_HEAR_MULTIPLIER)
                .ignoreLineOfSight();
        }

        @Override
        public boolean canUse() {
            // Only at night in Overworld (always active in Nether/End)
            Level level = mob.level();
            if (level.dimension() == Level.OVERWORLD) {
                long time = level.getDayTime() % 24000;
                if (time < 13000 || time > 23000) return false;
            }

            // Find nearest player
            Player nearest = mob.level().getNearestPlayer(mob, hearRadius);
            if (nearest == null || nearest.isCreative() || nearest.isSpectator()) return false;
            if (nearest.isInvisible()) return false;

            // Use reduced radius if sneaking
            this.targetConditions = nearest.isCrouching() ? sneakConditions : hearConditions;
            return super.canUse();
        }
    }

    /**
     * Skeleton keeps distance from player.
     * Checks safe ground before retreating.
     */
    static class SafeKeepDistanceGoal extends Goal {

        private final Mob mob;
        private final double minDistSq;
        private final double preferDistSq;

        public SafeKeepDistanceGoal(Mob mob, double minDist, double preferDist) {
            this.mob = mob;
            this.minDistSq    = minDist * minDist;
            this.preferDistSq = preferDist * preferDist;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = mob.getTarget();
            if (target == null) return false;
            return mob.distanceToSqr(target) < minDistSq;
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = mob.getTarget();
            if (target == null) return false;
            return mob.distanceToSqr(target) < preferDistSq;
        }

        @Override
        public void tick() {
            LivingEntity target = mob.getTarget();
            if (target == null) return;

            double dx = mob.getX() - target.getX();
            double dz = mob.getZ() - target.getZ();
            double lenSq = dx * dx + dz * dz;
            if (lenSq < 0.0001) return;

            double invLen = 1.0 / Math.sqrt(lenSq);
            double tx = mob.getX() + dx * invLen * 3.0;
            double tz = mob.getZ() + dz * invLen * 3.0;

            int ty = mob.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) tx, (int) tz
            );
            if (Math.abs(ty - mob.getBlockY()) > 4) return;

            mob.getNavigation().moveTo(tx, ty, tz, 1.0);
        }
    }
}
