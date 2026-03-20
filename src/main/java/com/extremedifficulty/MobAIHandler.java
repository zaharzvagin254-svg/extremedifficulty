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
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.EnumSet;
import java.util.List;

public class MobAIHandler {

    private static final String NBT_AI_SETUP = "ed_ai_setup";

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)) return;
        if (living.getPersistentData().getBoolean(NBT_AI_SETUP)) return;

        // CaveSpider extends Spider - check first
        if (entity instanceof CaveSpider caveSpider) {
            addSpiderGoals(caveSpider);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);

        // ZombifiedPiglin extends Zombie - exclude
        } else if (entity instanceof Zombie zombie && !(zombie instanceof ZombifiedPiglin)) {
            addZombieGoals(zombie);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);

        // FIX: WitherSkeleton and Stray do NOT extend Skeleton in 1.20.1
        // They extend AbstractSkeleton. Cast to Skeleton only if it IS a Skeleton.
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
        zombie.goalSelector.addGoal(4, new RememberLastPlayerPosGoal(zombie, 1.0));
        zombie.targetSelector.addGoal(3, new HearPlayerGoal(zombie, 10.0));
        zombie.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void addSkeletonGoals(Skeleton skeleton) {
        skeleton.targetSelector.addGoal(3, new HearPlayerGoal(skeleton, 8.0));
        skeleton.goalSelector.addGoal(4, new RememberLastPlayerPosGoal(skeleton, 1.0));
        skeleton.goalSelector.addGoal(2, new SafeKeepDistanceGoal(skeleton, 8.0, 12.0));
        skeleton.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void addSpiderGoals(Spider spider) {
        spider.targetSelector.addGoal(3, new HearPlayerGoal(spider, 8.0));
        spider.goalSelector.addGoal(4, new RememberLastPlayerPosGoal(spider, 1.2));
        spider.getNavigation().setMaxVisitedNodesMultiplier(3.0f);
    }

    private void addCreeperGoals(Creeper creeper) {
        creeper.targetSelector.addGoal(3, new HearPlayerGoal(creeper, 10.0));
        creeper.goalSelector.addGoal(4, new RememberLastPlayerPosGoal(creeper, 1.0));
        creeper.getNavigation().setMaxVisitedNodesMultiplier(2.5f);
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        if (zombie instanceof ZombifiedPiglin) return;
        if (zombie.level().isClientSide()) return;
        if (!(zombie.level() instanceof ServerLevel serverLevel)) return;

        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof LivingEntity target)) return;

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

    static class RememberLastPlayerPosGoal extends Goal {

        private final Mob mob;
        private final double speed;
        private BlockPos lastKnownPos = null;
        private int searchTicks = 0;
        private static final int SEARCH_DURATION = 100;

        public RememberLastPlayerPosGoal(Mob mob, double speed) {
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
            if (lastKnownPos != null) {
                mob.getNavigation().moveTo(
                    lastKnownPos.getX() + 0.5,
                    lastKnownPos.getY(),
                    lastKnownPos.getZ() + 0.5,
                    speed
                );
            }
        }

        @Override
        public void tick() {
            if (mob.getTarget() instanceof Player player) {
                lastKnownPos = player.blockPosition();
                searchTicks = 0;
                return;
            }
            searchTicks++;
            if (lastKnownPos != null
             && mob.blockPosition().closerThan(lastKnownPos, 2.0)) {
                lastKnownPos = null;
                searchTicks = 0;
            }
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            searchTicks = 0;
        }
    }

    static class HearPlayerGoal extends NearestAttackableTargetGoal<Player> {

        // Performance: create TargetingConditions once in constructor, not every tick
        private final TargetingConditions hearConditions;

        public HearPlayerGoal(Mob mob, double hearRadius) {
            super(mob, Player.class, 10, true, false, null);
            this.hearConditions = TargetingConditions.forCombat()
                .range(hearRadius)
                .ignoreLineOfSight();
        }

        @Override
        public boolean canUse() {
            Level level = mob.level();
            if (level.dimension() == Level.OVERWORLD) {
                long time = level.getDayTime() % 24000;
                if (time < 13000 || time > 23000) return false;
            }
            this.targetConditions = hearConditions;
            return super.canUse();
        }
    }

    static class SafeKeepDistanceGoal extends Goal {

        private final Mob mob;
        // Performance: store squared distances to avoid sqrt calls
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

            // Performance: fast inverse sqrt approximation
            double invLen = 1.0 / Math.sqrt(lenSq);
            double nx = dx * invLen;
            double nz = dz * invLen;

            double tx = mob.getX() + nx * 3.0;
            double tz = mob.getZ() + nz * 3.0;

            int ty = mob.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) tx, (int) tz
            );

            if (Math.abs(ty - mob.getBlockY()) > 4) return;

            mob.getNavigation().moveTo(tx, ty, tz, 1.0);
        }
    }
}
