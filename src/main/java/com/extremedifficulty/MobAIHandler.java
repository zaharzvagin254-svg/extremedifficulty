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

    // Public NBT keys used by SoundSystem
    public static final String NBT_SEARCH_STATE    = "ed_search";
    public static final String NBT_SEARCH_TICKS    = "ed_sticks";
    public static final String NBT_LAST_X          = "ed_lx";
    public static final String NBT_LAST_Y          = "ed_ly";
    public static final String NBT_LAST_Z          = "ed_lz";
    public static final String NBT_SEARCH_DURATION = "ed_sdur";

    private static final double DETECT_RANGE_NORMAL = 18.0;
    private static final double DETECT_RANGE_SNEAK  = 10.0;
    private static final double FOLLOW_RANGE_NIGHT  = 48.0;
    private static final double FOLLOW_RANGE_DAY    = 32.0;
    private static final int    SEARCH_ACTIVE_TICKS  = 600;
    private static final double HEAR_NORMAL  = 10.0;
    private static final double HEAR_SNEAK   = 4.0;

    // OPT: track last night state to skip redundant attribute updates
    private boolean lastNightState = false;
    private boolean nightStateInitialized = false;

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
    // GROUP ALERT - FIX: only on player melee damage
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        if (zombie instanceof ZombifiedPiglin) return;
        if (zombie.level().isClientSide()) return;
        if (!(zombie.level() instanceof ServerLevel sl)) return;

        // FIX: only react to player attacks, not fire/poison/fall etc
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof Player player)) return;
        if (player.isInvisible() && player.isCrouching()) return;

        List<Zombie> nearby = sl.getEntitiesOfClass(Zombie.class,
            zombie.getBoundingBox().inflate(16.0),
            z -> z != zombie && !(z instanceof ZombifiedPiglin) && z.getTarget() == null);
        int count = 0;
        for (Zombie z : nearby) {
            if (count++ >= 6) break;
            z.setTarget(player);
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
        boolean isNight = isNight(sl);

        // Footsteps every 20 ticks - sneak mutes only footsteps
        if (gt % 20 == 0) {
            for (var player : sl.players()) {
                if (player.isCrouching()) continue;
                if (player.isCreative() || player.isSpectator()) continue;
                if (player.getDeltaMovement().horizontalDistanceSqr() < 0.001) continue;
                SoundSystem.triggerSound(sl, player.position(),
                    SoundSystem.R_FOOTSTEP, player);
            }
        }

        if (gt % 10 != 0) return;

        // OPT: only update follow range when day/night changes
        boolean nightChanged = !nightStateInitialized || (isNight != lastNightState);
        if (nightChanged) {
            lastNightState = isNight;
            nightStateInitialized = true;
            double range = isNight ? FOLLOW_RANGE_NIGHT : FOLLOW_RANGE_DAY;
            sl.getEntities().getAll().forEach(entity -> {
                if (!(entity instanceof Mob mob)) return;
                var attr = mob.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
                if (attr != null) attr.setBaseValue(range);
            });
        }

        // AI search state update every 10 ticks
        sl.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof Mob mob)) return;
            updateSearchState(mob, sl, gt);
        });
    }

    // OPT: pass gameTime to avoid redundant calls
    private void updateSearchState(Mob mob, ServerLevel level, long gt) {
        var tag = mob.getPersistentData();
        int state = tag.getInt(NBT_SEARCH_STATE);

        if (mob.getTarget() instanceof Player player) {
            // OPT: check LOS every 20 ticks instead of every 10
            if (gt % 20 == 0) {
                if (mob.hasLineOfSight(player)) {
                    tag.putDouble(NBT_LAST_X, player.getX());
                    tag.putDouble(NBT_LAST_Y, player.getY());
                    tag.putDouble(NBT_LAST_Z, player.getZ());
                    tag.putInt(NBT_SEARCH_STATE, 0);
                    tag.putInt(NBT_SEARCH_TICKS, 0);
                } else if (!canHearPlayer(mob, player)) {
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
            tag.putInt(NBT_SEARCH_STATE, 0);
            tag.putInt(NBT_SEARCH_TICKS, 0);
            tag.remove(NBT_LAST_X);
            tag.remove(NBT_LAST_Y);
            tag.remove(NBT_LAST_Z);
            return;
        }

        // Spot visible player while searching
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

    static class AdvancedSearchGoal extends Goal {
        private final Mob mob;
        private final double speed;
        private BlockPos target = null;
        private int localTick   = 0;
        private int stuckTicks  = 0;
        // OPT: store coords instead of Vec3 to avoid allocation every tick
        private double lastX = Double.MIN_VALUE, lastY, lastZ;
        private static final int STUCK_THRESHOLD = 60;

        public AdvancedSearchGoal(Mob mob, double speed) {
            this.mob = mob; this.speed = speed;
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
            localTick = 0; stuckTicks = 0;
            lastX = mob.getX(); lastY = mob.getY(); lastZ = mob.getZ();
            mob.setAggressive(false);
            pickTarget();
        }

        @Override
        public void tick() {
            localTick++;
            mob.setAggressive(false);

            // OPT: stuck check every 10 ticks using raw coords (no Vec3 alloc)
            if (localTick % 10 == 0) {
                double dx = mob.getX() - lastX;
                double dz = mob.getZ() - lastZ;
                if (dx*dx + dz*dz < 0.01) {
                    stuckTicks += 10;
                } else {
                    stuckTicks = 0;
                    lastX = mob.getX(); lastY = mob.getY(); lastZ = mob.getZ();
                }

                if (stuckTicks >= STUCK_THRESHOLD) {
                    stuckTicks = 0;
                    if (localTick > 200) {
                        var tag = mob.getPersistentData();
                        tag.putInt(NBT_SEARCH_STATE, 0);
                        tag.putInt(NBT_SEARCH_TICKS, 0);
                        tag.remove(NBT_LAST_X); tag.remove(NBT_LAST_Y); tag.remove(NBT_LAST_Z);
                        return;
                    }
                    pickTarget();
                    return;
                }
            }

            var tag = mob.getPersistentData();
            int state = tag.getInt(NBT_SEARCH_STATE);

            if (target == null
             || mob.blockPosition().closerThan(target, 2.0)
             || localTick % 80 == 0) {
                pickTarget();
            }

            if (target != null) {
                mob.getNavigation().moveTo(
                    target.getX()+0.5, target.getY(), target.getZ()+0.5,
                    state == 1 ? speed : speed * 0.6);
            }
        }

        private void pickTarget() {
            var tag = mob.getPersistentData();
            if (!tag.contains(NBT_LAST_X)) { target = null; return; }
            double lx = tag.getDouble(NBT_LAST_X);
            double lz = tag.getDouble(NBT_LAST_Z);
            int state = tag.getInt(NBT_SEARCH_STATE);
            double radius = state == 1 ? 6.0 : 12.0;

            // OPT: max 2 path attempts (was 5) - cheaper
            for (int attempt = 0; attempt < 2; attempt++) {
                double angle = mob.getRandom().nextDouble() * Math.PI * 2;
                double dist  = radius * (0.4 + mob.getRandom().nextDouble() * 0.6);
                int nx = (int)(lx + Math.cos(angle) * dist);
                int nz = (int)(lz + Math.sin(angle) * dist);
                int ny = mob.level().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                if (ny <= 0 || Math.abs(ny - mob.getBlockY()) > 5) continue;
                BlockPos candidate = new BlockPos(nx, ny, nz);
                var path = mob.getNavigation().createPath(candidate, 1);
                if (path != null && path.canReach()) {
                    target = candidate; return;
                }
            }
            target = null;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            mob.setAggressive(false);
            target = null; localTick = 0; stuckTicks = 0;
            lastX = Double.MIN_VALUE;
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
            this.mob = mob; minSq = min*min; preferSq = prefer*prefer;
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
        @Override public void tick() {
            LivingEntity t = mob.getTarget();
            if (t == null) return;
            double dx = mob.getX()-t.getX(), dz = mob.getZ()-t.getZ();
            double lenSq = dx*dx+dz*dz;
            if (lenSq < 0.0001) return;
            double inv = 1.0/Math.sqrt(lenSq);
            double tx = mob.getX()+dx*inv*3, tz = mob.getZ()+dz*inv*3;
            int ty = mob.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int)tx, (int)tz);
            if (Math.abs(ty-mob.getBlockY()) > 4) return;
            mob.getNavigation().moveTo(tx, ty, tz, 1.0);
        }
    }
}
