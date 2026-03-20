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
    private static final int    SEARCH_ACTIVE_BASE  = 1200; // 60 sec
    private static final int    SEARCH_PASSIVE_BASE = 1200; // 60 sec
    private static final double HEAR_NORMAL = 10.0;
    private static final double HEAR_SNEAK  = 4.0;

    // Drowned hears less on land (half range), full range in water
    private static final double DROWNED_HEAR_LAND  = 5.0;
    private static final double DROWNED_HEAR_WATER = 10.0;

    private boolean lastNightState        = false;
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

        // Order matters: subclasses before superclasses
        if (entity instanceof CaveSpider cs) {
            setupBasic(cs, true, false);
        } else if (entity instanceof Husk husk) {
            // Husk = zombie AI but no door breaking (lives in desert, no doors)
            setupZombieLike(husk, false);
        } else if (entity instanceof Drowned drowned) {
            // Drowned = zombie AI, reduced hearing on land, full in water
            setupDrowned(drowned);
        } else if (entity instanceof Zombie z && !(z instanceof ZombifiedPiglin)) {
            setupZombie(z);
        } else if (entity.getClass() == Skeleton.class) {
            setupBasic((Skeleton) entity, true, true);
        } else if (entity instanceof Stray stray) {
            // Stray = same as Skeleton
            setupBasic(stray, true, true);
        } else if (entity instanceof Spider sp) {
            setupBasic(sp, true, false);
        } else if (entity instanceof Creeper cr) {
            // Creeper: sight only, better pathfinding
            setupBasic(cr, false, false);
        } else if (entity instanceof Vindicator vin) {
            setupMeleeRaider(vin);
        } else if (entity instanceof Pillager pil) {
            setupRangedRaider(pil);
        } else {
            return;
        }
        living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        assignSearchDuration((Mob) entity);
    }

    // Standard zombie with door breaking
    private void setupZombie(Zombie zombie) {
        zombie.goalSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof BreakDoorGoal);
        zombie.goalSelector.addGoal(1, new SmartBreakDoorGoal(zombie));
        zombie.goalSelector.addGoal(4, new AdvancedSearchGoal(zombie, 1.0));
        zombie.targetSelector.addGoal(3, new DetectionGoal(zombie, true));
        zombie.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    // Zombie-like (Husk) without door breaking
    private void setupZombieLike(Zombie mob, boolean breakDoors) {
        if (breakDoors) {
            mob.goalSelector.getAvailableGoals().removeIf(
                w -> w.getGoal() instanceof BreakDoorGoal);
            mob.goalSelector.addGoal(1, new SmartBreakDoorGoal(mob));
        }
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, true));
        mob.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    // Drowned: full zombie AI but hearing depends on water/land
    private void setupDrowned(Drowned drowned) {
        drowned.goalSelector.addGoal(4, new AdvancedSearchGoal(drowned, 1.0));
        drowned.targetSelector.addGoal(3, new DrownedDetectionGoal(drowned));
        drowned.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    // Generic mob with optional hearing and distance-keeping
    private void setupBasic(Mob mob, boolean usesHearing, boolean keepsDistance) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, usesHearing));
        if (keepsDistance)
            mob.goalSelector.addGoal(2, new SafeKeepDistanceGoal(mob, 8.0, 12.0));
        // Spiders get bigger pathfinding budget (they climb)
        mob.getNavigation().setMaxVisitedNodesMultiplier(
            mob instanceof Spider ? 3.0f : 2.0f);
    }

    // Vindicator: melee raider - excellent pathfinding, no hearing (uses sight)
    private void setupMeleeRaider(Vindicator mob) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, false));
        // Vindicator already breaks doors in vanilla - keep that, just improve nav
        mob.getNavigation().setMaxVisitedNodesMultiplier(3.0f);
    }

    // Pillager: ranged raider - keeps distance, excellent pathfinding
    private void setupRangedRaider(Pillager mob) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, false));
        mob.goalSelector.addGoal(2, new SafeKeepDistanceGoal(mob, 10.0, 16.0));
        mob.getNavigation().setMaxVisitedNodesMultiplier(3.0f);
    }

    private void assignSearchDuration(Mob mob) {
        double v = 0.8 + mob.getRandom().nextDouble() * 0.4;
        mob.getPersistentData().putInt(NBT_SEARCH_DURATION, (int)(SEARCH_ACTIVE_BASE * v));
        mob.getPersistentData().putInt("ed_pdur", (int)(SEARCH_PASSIVE_BASE * v));
    }

    // -------------------------------------------------------------------------
    // GROUP ALERT - zombie family only, player melee only
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        if (zombie instanceof ZombifiedPiglin) return;
        if (zombie.level().isClientSide()) return;
        if (!(zombie.level() instanceof ServerLevel sl)) return;
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

        // Footsteps every 20 ticks
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

        // Follow range update only on day/night change
        boolean changed = !nightStateInitialized || isNight != lastNightState;
        if (changed) {
            lastNightState = isNight; nightStateInitialized = true;
            double range = isNight ? FOLLOW_RANGE_NIGHT : FOLLOW_RANGE_DAY;
            sl.getEntities().getAll().forEach(entity -> {
                if (!(entity instanceof Mob mob)) return;
                var attr = mob.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
                if (attr != null) attr.setBaseValue(range);
            });
        }

        sl.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof Mob mob)) return;
            updateSearchState(mob, sl, gt);
        });
    }

    private void updateSearchState(Mob mob, ServerLevel level, long gt) {
        var tag = mob.getPersistentData();
        int state = tag.getInt(NBT_SEARCH_STATE);

        if (mob.getTarget() instanceof Player player) {
            if (gt % 20 == 0) {
                if (mob.hasLineOfSight(player)) {
                    tag.putDouble(NBT_LAST_X, player.getX());
                    tag.putDouble(NBT_LAST_Y, player.getY());
                    tag.putDouble(NBT_LAST_Z, player.getZ());
                    tag.putInt(NBT_SEARCH_STATE, 0);
                    tag.putInt(NBT_SEARCH_TICKS, 0);
                } else if (!canHearPlayer(mob, player)) {
                    mob.setTarget(null);
                    if (tag.contains(NBT_LAST_X)) {
                        tag.putInt(NBT_SEARCH_STATE, 1);
                        tag.putInt(NBT_SEARCH_TICKS, 0);
                    }
                }
            }
            return;
        }

        if (state == 0) return;

        int ticks = tag.getInt(NBT_SEARCH_TICKS) + 10;
        tag.putInt(NBT_SEARCH_TICKS, ticks);
        int activeDur  = tag.contains(NBT_SEARCH_DURATION) ? tag.getInt(NBT_SEARCH_DURATION) : SEARCH_ACTIVE_BASE;
        int passiveDur = tag.contains("ed_pdur")           ? tag.getInt("ed_pdur")           : SEARCH_PASSIVE_BASE;

        if (state == 1 && ticks >= activeDur) {
            tag.putInt(NBT_SEARCH_STATE, 2);
            tag.putInt(NBT_SEARCH_TICKS, 0);
        } else if (state == 2 && ticks >= passiveDur) {
            clearSearch(tag);
            return;
        }

        if (tag.contains(NBT_LAST_X)) {
            Player nearby = level.getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearby != null && !nearby.isCreative() && !nearby.isSpectator()
             && mob.hasLineOfSight(nearby)) {
                mob.setTarget(nearby);
                clearSearch(tag);
            }
        }
    }

    static void clearSearch(net.minecraft.nbt.CompoundTag tag) {
        tag.putInt(NBT_SEARCH_STATE, 0);
        tag.putInt(NBT_SEARCH_TICKS, 0);
        tag.remove(NBT_LAST_X);
        tag.remove(NBT_LAST_Y);
        tag.remove(NBT_LAST_Z);
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

    // Standard detection with sight + optional hearing at night
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

    // Drowned detection: reduced hearing on land, normal in water
    static class DrownedDetectionGoal extends NearestAttackableTargetGoal<Player> {
        private final TargetingConditions normalSight;
        private final TargetingConditions sneakSight;
        private final TargetingConditions waterHear;
        private final TargetingConditions landHear;
        private final TargetingConditions sneakHear;

        public DrownedDetectionGoal(Drowned mob) {
            super(mob, Player.class, 10, true, false, null);
            this.normalSight = TargetingConditions.forCombat().range(DETECT_RANGE_NORMAL);
            this.sneakSight  = TargetingConditions.forCombat().range(DETECT_RANGE_SNEAK);
            this.waterHear   = TargetingConditions.forCombat()
                .range(DROWNED_HEAR_WATER).ignoreLineOfSight();
            this.landHear    = TargetingConditions.forCombat()
                .range(DROWNED_HEAR_LAND).ignoreLineOfSight();
            this.sneakHear   = TargetingConditions.forCombat()
                .range(HEAR_SNEAK).ignoreLineOfSight();
        }

        @Override
        public boolean canUse() {
            Player nearest = mob.level().getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearest == null || nearest.isCreative()
             || nearest.isSpectator() || nearest.isInvisible()) return false;
            boolean sneaking = nearest.isCrouching();

            // Sight detection
            this.targetConditions = sneaking ? sneakSight : normalSight;
            if (super.canUse()) return true;

            // Hearing: drowned hears much better in water than on land
            boolean inWater = mob.isInWater();
            if (sneaking) {
                this.targetConditions = sneakHear;
            } else {
                this.targetConditions = inWater ? waterHear : landHear;
            }
            return super.canUse();
        }
    }

    // Smart door breaking: only when target blocks path
    static class SmartBreakDoorGoal extends BreakDoorGoal {
        public SmartBreakDoorGoal(Mob mob) {
            super(mob, 60, d -> d == Difficulty.HARD);
        }
        @Override
        public boolean canUse() {
            if (mob.getTarget() == null) return false;
            return super.canUse();
        }
        @Override
        public boolean canContinueToUse() {
            if (mob.getTarget() == null) return false;
            return super.canContinueToUse();
        }
    }

    // Search goal: active circle then passive wander, then leave
    static class AdvancedSearchGoal extends Goal {
        private final Mob mob;
        private final double speed;
        private BlockPos target = null;
        private int localTick   = 0;
        private int stuckTicks  = 0;
        private double lastX = Double.MIN_VALUE, lastZ;
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
            lastX = mob.getX(); lastZ = mob.getZ();
            mob.setAggressive(false);
            pickTarget();
        }

        @Override
        public void tick() {
            localTick++;
            mob.setAggressive(false);

            if (localTick % 10 == 0) {
                double dx = mob.getX() - lastX, dz = mob.getZ() - lastZ;
                if (dx*dx + dz*dz < 0.01) {
                    stuckTicks += 10;
                } else {
                    stuckTicks = 0;
                    lastX = mob.getX(); lastZ = mob.getZ();
                }
                if (stuckTicks >= STUCK_THRESHOLD) {
                    stuckTicks = 0;
                    if (localTick > 200) {
                        clearSearch(mob.getPersistentData());
                        return;
                    }
                    pickTarget();
                    return;
                }
            }

            var tag = mob.getPersistentData();
            int state = tag.getInt(NBT_SEARCH_STATE);
            if (target == null || mob.blockPosition().closerThan(target, 2.0) || localTick % 80 == 0)
                pickTarget();

            if (target != null) {
                double spd = state == 1 ? speed * 0.7 : speed * 0.5;
                mob.getNavigation().moveTo(target.getX()+0.5, target.getY(), target.getZ()+0.5, spd);
            }
        }

        private void pickTarget() {
            var tag = mob.getPersistentData();
            if (!tag.contains(NBT_LAST_X)) { target = null; return; }
            double lx = tag.getDouble(NBT_LAST_X), lz = tag.getDouble(NBT_LAST_Z);
            int state = tag.getInt(NBT_SEARCH_STATE);
            double radius = state == 1 ? 5.0 : 10.0;

            for (int a = 0; a < 2; a++) {
                double angle = mob.getRandom().nextDouble() * Math.PI * 2;
                double dist  = radius * (0.4 + mob.getRandom().nextDouble() * 0.6);
                int nx = (int)(lx + Math.cos(angle) * dist);
                int nz = (int)(lz + Math.sin(angle) * dist);
                int ny = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                if (ny <= 0 || Math.abs(ny - mob.getBlockY()) > 5) continue;
                BlockPos c = new BlockPos(nx, ny, nz);
                var path = mob.getNavigation().createPath(c, 1);
                if (path != null && path.canReach()) { target = c; return; }
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
