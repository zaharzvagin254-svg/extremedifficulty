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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.EnumSet;
import java.util.List;

public class MobAIHandler {

    private static final String NBT_AI_SETUP = "ed_ai_setup";

    // Public NBT keys used by SoundSystem
    public static final String NBT_SEARCH_STATE    = "ed_search";
    public static final String NBT_SEARCH_TICKS    = "ed_sticks";
    public static final String NBT_LAST_X          = "ed_lx";
    public static final String NBT_LAST_Y          = "ed_ly";
    public static final String NBT_LAST_Z          = "ed_lz";
    public static final String NBT_SEARCH_DURATION = "ed_sdur";

    // Detection ranges
    private static final double DETECT_RANGE_NORMAL = 18.0;
    private static final double DETECT_RANGE_SNEAK  = 10.0;

    // Follow ranges after aggro
    private static final double FOLLOW_RANGE_NIGHT = 48.0;
    private static final double FOLLOW_RANGE_DAY   = 32.0;

    // Active search = 30 sec, passive = 30 sec, total 60 sec
    private static final int SEARCH_ACTIVE_TICKS  = 600;
    private static final int SEARCH_PASSIVE_TICKS = 600;

    private static final double HEAR_NORMAL  = 10.0;
    private static final double HEAR_SNEAK   = 4.0;

    // -------------------------------------------------------------------------
    // ENTITY JOIN
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)) return;
        if (living.getPersistentData().getBoolean(NBT_AI_SETUP)) return;

        if (entity instanceof CaveSpider cs) {
            setupMob(cs, true, false);
        } else if (entity instanceof Zombie z && !(z instanceof ZombifiedPiglin)) {
            setupZombie(z);
        } else if (entity.getClass() == Skeleton.class) {
            setupMob((Skeleton) entity, true, true);
        } else if (entity instanceof Spider sp) {
            setupMob(sp, true, false);
        } else if (entity instanceof Creeper cr) {
            // Creeper only uses sight - no sound reactions, no hearing
            setupMob(cr, false, false);
        } else {
            return;
        }
        living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        assignSearchDuration((Mob) entity);
    }

    private void setupZombie(Zombie zombie) {
        zombie.goalSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof BreakDoorGoal);
        zombie.goalSelector.addGoal(1, new FastBreakDoorGoal(zombie, 120));
        zombie.goalSelector.addGoal(4, new AdvancedSearchGoal(zombie, 1.0));
        zombie.targetSelector.addGoal(3, new DetectionGoal(zombie, true));
        zombie.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void setupMob(Mob mob, boolean usesHearing, boolean keepsDistance) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, usesHearing));
        if (keepsDistance)
            mob.goalSelector.addGoal(2, new SafeKeepDistanceGoal(mob, 8.0, 12.0));
        mob.getNavigation().setMaxVisitedNodesMultiplier(
            mob instanceof Spider ? 3.0f : 2.0f);
    }

    private void assignSearchDuration(Mob mob) {
        double rand = 0.8 + mob.getRandom().nextDouble() * 0.4;
        mob.getPersistentData().putInt(NBT_SEARCH_DURATION,
            (int)(SEARCH_ACTIVE_TICKS * rand));
    }

    // -------------------------------------------------------------------------
    // GROUP ALERT
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        if (zombie instanceof ZombifiedPiglin) return;
        if (zombie.level().isClientSide()) return;
        if (!(zombie.level() instanceof ServerLevel sl)) return;
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof LivingEntity target)) return;
        if (target instanceof Player p && p.isInvisible() && p.isCrouching()) return;

        List<Zombie> nearby = sl.getEntitiesOfClass(Zombie.class,
            zombie.getBoundingBox().inflate(16.0),
            z -> z != zombie && !(z instanceof ZombifiedPiglin) && z.getTarget() == null);
        int count = 0;
        for (Zombie z : nearby) {
            if (count++ >= 6) break;
            z.setTarget(target);
        }
    }

    // -------------------------------------------------------------------------
    // SERVER TICK
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel sl)) return;
        long gt = sl.getGameTime();

        // Footsteps - every 20 ticks, ONLY muted by sneaking
        if (gt % 20 == 0) {
            for (var player : sl.players()) {
                if (player.isCrouching()) continue;
                if (player.isCreative() || player.isSpectator()) continue;
                if (player.getDeltaMovement().horizontalDistanceSqr() < 0.001) continue;
                SoundSystem.triggerSound(sl, player.position(),
                    SoundSystem.R_FOOTSTEP, player);
            }
        }

        // AI update every 10 ticks
        if (gt % 10 != 0) return;
        boolean isNight = isNight(sl);

        sl.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof Mob mob)) return;
            updateFollowRange(mob, isNight);
            updateSearchState(mob, sl);
        });
    }

    private void updateFollowRange(Mob mob, boolean isNight) {
        var attr = mob.getAttribute(
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        if (attr != null)
            attr.setBaseValue(isNight ? FOLLOW_RANGE_NIGHT : FOLLOW_RANGE_DAY);
    }

    private void updateSearchState(Mob mob, ServerLevel level) {
        var tag = mob.getPersistentData();
        int state = tag.getInt(NBT_SEARCH_STATE);

        // Has active target
        if (mob.getTarget() instanceof Player player) {
            if (mob.hasLineOfSight(player)) {
                tag.putDouble(NBT_LAST_X, player.getX());
                tag.putDouble(NBT_LAST_Y, player.getY());
                tag.putDouble(NBT_LAST_Z, player.getZ());
                tag.putInt(NBT_SEARCH_STATE, 0);
                tag.putInt(NBT_SEARCH_TICKS, 0);
            } else {
                if (!canHearPlayer(mob, player)) {
                    mob.setTarget(null);
                    tag.putInt(NBT_SEARCH_STATE, 1);
                    tag.putInt(NBT_SEARCH_TICKS, 0);
                }
            }
            return;
        }

        if (state == 0) return;

        int ticks = tag.getInt(NBT_SEARCH_TICKS) + 10;
        tag.putInt(NBT_SEARCH_TICKS, ticks);
        int dur = tag.contains(NBT_SEARCH_DURATION)
            ? tag.getInt(NBT_SEARCH_DURATION) : SEARCH_ACTIVE_TICKS;

        if (state == 1 && ticks >= dur) {
            tag.putInt(NBT_SEARCH_STATE, 2);
            tag.putInt(NBT_SEARCH_TICKS, 0);
        } else if (state == 2 && ticks >= dur) {
            // Give up
            tag.putInt(NBT_SEARCH_STATE, 0);
            tag.putInt(NBT_SEARCH_TICKS, 0);
            tag.remove(NBT_LAST_X);
            tag.remove(NBT_LAST_Y);
            tag.remove(NBT_LAST_Z);
            return;
        }

        // Spot a visible player nearby while searching
        if (tag.contains(NBT_LAST_X)) {
            Player nearby = level.getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearby != null && !nearby.isCreative() && !nearby.isSpectator()
             && mob.hasLineOfSight(nearby)) {
                mob.setTarget(nearby);
                tag.putInt(NBT_SEARCH_STATE, 0);
            }
        }
    }

    private boolean canHearPlayer(Mob mob, Player player) {
        if (player.isCrouching()) return false;
        double r = player.isSprinting() ? HEAR_NORMAL * 1.3 : HEAR_NORMAL;
        return mob.distanceToSqr(player) < r * r;
    }

    public static boolean isSoundReactiveMob(net.minecraft.world.entity.Mob mob) {
        return mob instanceof Zombie || mob instanceof Skeleton
            || mob instanceof Spider || mob instanceof CaveSpider
            || mob instanceof Vindicator || mob instanceof Pillager;
    }

    static boolean isNight(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }

    // -------------------------------------------------------------------------
    // GOALS
    // -------------------------------------------------------------------------

    static class DetectionGoal extends NearestAttackableTargetGoal<Player> {
        private final boolean usesHearing;
        private final TargetingConditions normalSight;
        private final TargetingConditions sneakSight;
        private final TargetingConditions normalHear;
        private final TargetingConditions sneakHear;

        public DetectionGoal(Mob mob, boolean usesHearing) {
            super(mob, Player.class, 10, true, false, null);
            this.usesHearing = usesHearing;
            this.normalSight = TargetingConditions.forCombat().range(DETECT_RANGE_NORMAL);
            this.sneakSight  = TargetingConditions.forCombat().range(DETECT_RANGE_SNEAK);
            this.normalHear  = TargetingConditions.forCombat()
                .range(HEAR_NORMAL).ignoreLineOfSight();
            this.sneakHear   = TargetingConditions.forCombat()
                .range(HEAR_SNEAK).ignoreLineOfSight();
        }

        @Override
        public boolean canUse() {
            Player nearest = mob.level().getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearest == null || nearest.isCreative()
             || nearest.isSpectator() || nearest.isInvisible()) return false;
            boolean sneaking = nearest.isCrouching();
            this.targetConditions = sneaking ? sneakSight : normalSight;
            if (super.canUse()) return true;
            if (usesHearing && isNight(mob.level())) {
                this.targetConditions = sneaking ? sneakHear : normalHear;
                return super.canUse();
            }
            return false;
        }
    }

    /**
     * Advanced search goal.
     * FIX: mob gives up if stuck (not moving) for too long.
     * FIX: mob does not search forever - respects search state timeout.
     */
    static class AdvancedSearchGoal extends Goal {
        private final Mob mob;
        private final double speed;
        private BlockPos target    = null;
        private int localTick      = 0;
        private Vec3 lastPos       = null;
        private int stuckTicks     = 0;
        // If mob hasn't moved in 3 sec while searching - try new point or give up
        private static final int STUCK_THRESHOLD = 60;

        public AdvancedSearchGoal(Mob mob, double speed) {
            this.mob   = mob;
            this.speed = speed;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (mob.getTarget() != null) return false;
            var tag = mob.getPersistentData();
            return tag.getInt(NBT_SEARCH_STATE) > 0 && tag.contains(NBT_LAST_X);
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.getTarget() != null) return false;
            var tag = mob.getPersistentData();
            return tag.getInt(NBT_SEARCH_STATE) > 0 && tag.contains(NBT_LAST_X);
        }

        @Override
        public void start() {
            localTick  = 0;
            stuckTicks = 0;
            lastPos    = mob.position();
            // Mob walks to sound with arms down - not in combat pose
            mob.setAggressive(false);
            pickTarget();
        }

        @Override
        public void tick() {
            localTick++;
            var tag = mob.getPersistentData();
            int state = tag.getInt(NBT_SEARCH_STATE);

            // Keep arms down while searching - no combat pose
            mob.setAggressive(false);

            // FIX: stuck detection - check if mob is actually moving
            Vec3 cur = mob.position();
            if (lastPos != null && cur.distanceToSqr(lastPos) < 0.01) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
                lastPos = cur;
            }

            // If stuck for too long - pick a new random target or give up
            if (stuckTicks >= STUCK_THRESHOLD) {
                stuckTicks = 0;
                if (localTick > 200) {
                    // Been searching a while and stuck - clear search state
                    tag.putInt(NBT_SEARCH_STATE, 0);
                    tag.putInt(NBT_SEARCH_TICKS, 0);
                    tag.remove(NBT_LAST_X);
                    tag.remove(NBT_LAST_Y);
                    tag.remove(NBT_LAST_Z);
                    return;
                }
                // Try a different random point
                pickTarget();
                return;
            }

            // Re-pick target periodically
            if (target == null
             || mob.blockPosition().closerThan(target, 2.0)
             || localTick % 80 == 0) {
                pickTarget();
            }

            if (target != null) {
                double spd = state == 1 ? speed : speed * 0.6;
                mob.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5, spd);
            }
        }

        private void pickTarget() {
            var tag = mob.getPersistentData();
            if (!tag.contains(NBT_LAST_X)) { target = null; return; }

            double lx = tag.getDouble(NBT_LAST_X);
            double lz = tag.getDouble(NBT_LAST_Z);
            int state = tag.getInt(NBT_SEARCH_STATE);
            double radius = state == 1 ? 6.0 : 12.0;

            // Try up to 5 random points, pick first reachable one
            for (int attempt = 0; attempt < 5; attempt++) {
                double angle = mob.getRandom().nextDouble() * Math.PI * 2;
                double dist  = radius * (0.4 + mob.getRandom().nextDouble() * 0.6);
                int nx = (int)(lx + Math.cos(angle) * dist);
                int nz = (int)(lz + Math.sin(angle) * dist);
                int ny = mob.level().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);

                if (ny <= 0 || Math.abs(ny - mob.getBlockY()) > 5) continue;

                BlockPos candidate = new BlockPos(nx, ny, nz);
                // Check if navigation can reach it (hasPath check)
                var path = mob.getNavigation().createPath(candidate, 1);
                if (path != null && path.canReach()) {
                    target = candidate;
                    return;
                }
            }
            // No reachable point found - clear target
            target = null;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            // Restore aggressive state when done searching
            // (will be set again by combat goals if needed)
            mob.setAggressive(false);
            target     = null;
            localTick  = 0;
            stuckTicks = 0;
            lastPos    = null;
        }
    }

    static class FastBreakDoorGoal extends BreakDoorGoal {
        public FastBreakDoorGoal(Mob mob, int ticks) {
            super(mob, ticks, d -> d == Difficulty.HARD);
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

    static class SafeKeepDistanceGoal extends Goal {
        private final Mob mob;
        private final double minSq, preferSq;

        public SafeKeepDistanceGoal(Mob mob, double min, double prefer) {
            this.mob = mob; this.minSq = min*min; this.preferSq = prefer*prefer;
            setFlags(EnumSet.of(Flag.MOVE));
        }
        @Override public boolean canUse() {
            LivingEntity t = mob.getTarget();
            return t != null && mob.distanceToSqr(t) < minSq;
        }
        @Override public boolean canContinueToUse() {
            LivingEntity t = mob.getTarget();
            return t != null && mob.distanceToSqr(t) < preferSq;
        }
        @Override
        public void tick() {
            LivingEntity t = mob.getTarget();
            if (t == null) return;
            double dx = mob.getX()-t.getX(), dz = mob.getZ()-t.getZ();
            double lenSq = dx*dx+dz*dz;
            if (lenSq < 0.0001) return;
            double inv = 1.0/Math.sqrt(lenSq);
            double tx = mob.getX()+dx*inv*3, tz = mob.getZ()+dz*inv*3;
            int ty = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)tx,(int)tz);
            if (Math.abs(ty-mob.getBlockY()) > 4) return;
            mob.getNavigation().moveTo(tx, ty, tz, 1.0);
        }
    }
}
