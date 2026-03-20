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

    // Flank NBT keys
    private static final String NBT_FLEE_DX    = "ed_fdx";
    private static final String NBT_FLEE_DZ    = "ed_fdz";
    private static final String NBT_FLEE_COUNT = "ed_fn";
    private static final String NBT_FLEE_DAY   = "ed_fday"; // game day last updated

    // Memory patrol NBT keys
    private static final String NBT_MEM_CD = "ed_mem_cd"; // game time when cooldown expires

    private static final double DETECT_RANGE_NORMAL = 18.0;
    private static final double DETECT_RANGE_SNEAK  = 10.0;
    private static final double FOLLOW_RANGE_NIGHT  = 48.0;
    private static final double FOLLOW_RANGE_DAY    = 32.0;
    private static final int    SEARCH_ACTIVE_BASE  = 1200;
    private static final int    SEARCH_PASSIVE_BASE = 1200;
    private static final double HEAR_NORMAL = 10.0;
    private static final double HEAR_SNEAK  = 4.0;
    private static final double DROWNED_HEAR_LAND  = 5.0;
    private static final double DROWNED_HEAR_WATER = 10.0;

    // Min flee count before flanking
    private static final int FLANK_THRESHOLD = 3;
    // Memory patrol search radius
    private static final int MEMORY_PATROL_RADIUS = 80;
    // Memory patrol cooldown: 6000 ticks = 5 min
    private static final long MEMORY_PATROL_COOLDOWN = 6000L;

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

        if (entity instanceof CaveSpider cs) {
            setupBasic(cs, true, false);
        } else if (entity instanceof Husk husk) {
            setupZombieLike(husk, false);
        } else if (entity instanceof Drowned drowned) {
            setupDrowned(drowned);
        } else if (entity instanceof Zombie z && !(z instanceof ZombifiedPiglin)) {
            setupZombie(z);
        } else if (entity.getClass() == Skeleton.class) {
            setupBasic((Skeleton) entity, true, true);
        } else if (entity instanceof Stray stray) {
            setupBasic(stray, true, true);
        } else if (entity instanceof Spider sp) {
            setupBasic(sp, true, false);
        } else if (entity instanceof Creeper cr) {
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

    private void setupZombie(Zombie zombie) {
        zombie.goalSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof BreakDoorGoal);
        zombie.goalSelector.addGoal(1, new SmartBreakDoorGoal(zombie));
        zombie.goalSelector.addGoal(4, new AdvancedSearchGoal(zombie, 1.0));
        zombie.goalSelector.addGoal(5, new MemoryPatrolGoal(zombie));
        zombie.goalSelector.addGoal(3, new FlankGoal(zombie));
        zombie.targetSelector.addGoal(3, new DetectionGoal(zombie, true));
        zombie.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void setupZombieLike(Zombie mob, boolean breakDoors) {
        if (breakDoors) {
            mob.goalSelector.getAvailableGoals().removeIf(
                w -> w.getGoal() instanceof BreakDoorGoal);
            mob.goalSelector.addGoal(1, new SmartBreakDoorGoal(mob));
        }
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(5, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, true));
        mob.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void setupDrowned(Drowned drowned) {
        drowned.goalSelector.addGoal(4, new AdvancedSearchGoal(drowned, 1.0));
        drowned.goalSelector.addGoal(5, new MemoryPatrolGoal(drowned));
        drowned.targetSelector.addGoal(3, new DrownedDetectionGoal(drowned));
        drowned.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void setupBasic(Mob mob, boolean usesHearing, boolean keepsDistance) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(5, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, usesHearing));
        if (keepsDistance)
            mob.goalSelector.addGoal(2, new SafeKeepDistanceGoal(mob, 8.0, 12.0));
        mob.getNavigation().setMaxVisitedNodesMultiplier(
            mob instanceof Spider ? 3.0f : 2.0f);
    }

    private void setupMeleeRaider(Vindicator mob) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(5, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, false));
        mob.getNavigation().setMaxVisitedNodesMultiplier(3.0f);
    }

    private void setupRangedRaider(Pillager mob) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(5, new MemoryPatrolGoal(mob));
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
    // GROUP ALERT
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

        // Footsteps
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

        // Follow range on day/night change
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
            updateFlankData(mob, gt);
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

    /**
     * Track flee direction for flank logic.
     * When mob has LOS on target and target is moving away - record direction.
     * Runs every 10 ticks but only for mobs actively chasing.
     */
    private void updateFlankData(Mob mob, long gt) {
        if (!(mob instanceof Zombie)) return;
        if (mob instanceof ZombifiedPiglin) return;
        if (!(mob.getTarget() instanceof Player player)) return;
        if (!mob.hasLineOfSight(player)) return;

        // Check if player is moving away (dot product of direction to player and player velocity)
        double dx = player.getX() - mob.getX();
        double dz = player.getZ() - mob.getZ();
        double len = Math.sqrt(dx*dx + dz*dz);
        if (len < 1.0) return;

        double ndx = dx / len, ndz = dz / len;
        double pvx = player.getDeltaMovement().x;
        double pvz = player.getDeltaMovement().z;
        double dot = pvx * ndx + pvz * ndz;

        // dot > 0.3 means player is moving away from mob
        if (dot < 0.3) return;

        var tag = mob.getPersistentData();
        long currentDay = gt / 24000;

        // Reset if data is stale (3+ days old)
        if (tag.contains(NBT_FLEE_DAY) && currentDay - tag.getLong(NBT_FLEE_DAY) > 3) {
            tag.remove(NBT_FLEE_DX); tag.remove(NBT_FLEE_DZ);
            tag.putInt(NBT_FLEE_COUNT, 0);
        }

        // Running average of flee direction
        int count = tag.contains(NBT_FLEE_COUNT) ? tag.getInt(NBT_FLEE_COUNT) : 0;
        if (count == 0) {
            tag.putFloat(NBT_FLEE_DX, (float) ndx);
            tag.putFloat(NBT_FLEE_DZ, (float) ndz);
        } else {
            // Exponential moving average
            float alpha = 0.3f;
            float avgDx = tag.getFloat(NBT_FLEE_DX) * (1-alpha) + (float)(ndx * alpha);
            float avgDz = tag.getFloat(NBT_FLEE_DZ) * (1-alpha) + (float)(ndz * alpha);
            // Renormalize
            float nlen = (float) Math.sqrt(avgDx*avgDx + avgDz*avgDz);
            if (nlen > 0.01f) {
                tag.putFloat(NBT_FLEE_DX, avgDx / nlen);
                tag.putFloat(NBT_FLEE_DZ, avgDz / nlen);
            }
        }
        tag.putInt(NBT_FLEE_COUNT, Math.min(count + 1, 20)); // cap at 20
        tag.putLong(NBT_FLEE_DAY, currentDay);
    }

    static void clearSearch(net.minecraft.nbt.CompoundTag tag) {
        tag.putInt(NBT_SEARCH_STATE, 0);
        tag.putInt(NBT_SEARCH_TICKS, 0);
        tag.remove(NBT_LAST_X); tag.remove(NBT_LAST_Y); tag.remove(NBT_LAST_Z);
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
        private final TargetingConditions normalSight, sneakSight, normalHear, sneakHear;

        public DetectionGoal(Mob mob, boolean usesHearing) {
            super(mob, Player.class, 10, true, false, null);
            this.usesHearing = usesHearing;
            this.normalSight = TargetingConditions.forCombat().range(DETECT_RANGE_NORMAL);
            this.sneakSight  = TargetingConditions.forCombat().range(DETECT_RANGE_SNEAK);
            this.normalHear  = TargetingConditions.forCombat().range(HEAR_NORMAL).ignoreLineOfSight();
            this.sneakHear   = TargetingConditions.forCombat().range(HEAR_SNEAK).ignoreLineOfSight();
        }

        @Override
        public boolean canUse() {
            Player nearest = mob.level().getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearest == null || nearest.isCreative() || nearest.isSpectator() || nearest.isInvisible()) return false;
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

    static class DrownedDetectionGoal extends NearestAttackableTargetGoal<Player> {
        private final TargetingConditions normalSight, sneakSight, waterHear, landHear, sneakHear;

        public DrownedDetectionGoal(Drowned mob) {
            super(mob, Player.class, 10, true, false, null);
            this.normalSight = TargetingConditions.forCombat().range(DETECT_RANGE_NORMAL);
            this.sneakSight  = TargetingConditions.forCombat().range(DETECT_RANGE_SNEAK);
            this.waterHear   = TargetingConditions.forCombat().range(DROWNED_HEAR_WATER).ignoreLineOfSight();
            this.landHear    = TargetingConditions.forCombat().range(DROWNED_HEAR_LAND).ignoreLineOfSight();
            this.sneakHear   = TargetingConditions.forCombat().range(HEAR_SNEAK).ignoreLineOfSight();
        }

        @Override
        public boolean canUse() {
            Player nearest = mob.level().getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearest == null || nearest.isCreative() || nearest.isSpectator() || nearest.isInvisible()) return false;
            boolean sneaking = nearest.isCrouching();
            this.targetConditions = sneaking ? sneakSight : normalSight;
            if (super.canUse()) return true;
            this.targetConditions = sneaking ? sneakHear : (mob.isInWater() ? waterHear : landHear);
            return super.canUse();
        }
    }

    /**
     * Zombie learns player's flee direction and flanks from the opposite side.
     * Only activates if flee count >= threshold AND mob has a target.
     * Only 1 zombie per nearby group flanks at a time.
     */
    static class FlankGoal extends Goal {
        private final Mob mob;
        private BlockPos flankTarget = null;
        private int duration = 0;
        private static final int MAX_DURATION = 200; // 10 sec max

        public FlankGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (mob.getTarget() == null) return false;
            var tag = mob.getPersistentData();
            int count = tag.contains(NBT_FLEE_COUNT) ? tag.getInt(NBT_FLEE_COUNT) : 0;
            if (count < FLANK_THRESHOLD) return false;
            if (!tag.contains(NBT_FLEE_DX)) return false;

            // Only flank if at least 2 other zombies are also chasing this target
            // AND we are NOT already the nearest zombie to the target
            // This prevents ALL zombies from flanking
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            LivingEntity target = mob.getTarget();
            List<Zombie> chasers = sl.getEntitiesOfClass(Zombie.class,
                mob.getBoundingBox().inflate(20.0),
                z -> z != mob && target.equals(z.getTarget()));
            if (chasers.size() < 2) return false;

            // Only 1 out of group flanks (the one with highest flee count)
            // Check if we have highest flee count in group
            for (Zombie z : chasers) {
                int otherCount = z.getPersistentData().contains(NBT_FLEE_COUNT)
                    ? z.getPersistentData().getInt(NBT_FLEE_COUNT) : 0;
                if (otherCount > count) return false; // another zombie flanks instead
            }

            // Compute flank position: 8 blocks to the LEFT of flee direction
            float fdx = tag.getFloat(NBT_FLEE_DX);
            float fdz = tag.getFloat(NBT_FLEE_DZ);
            // Left perpendicular: (-fdz, fdx)
            double flankX = target.getX() + (-fdz) * 8;
            double flankZ = target.getZ() + fdx * 8;
            int ny = mob.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) flankX, (int) flankZ);
            if (ny <= 0) return false;

            BlockPos candidate = new BlockPos((int) flankX, ny, (int) flankZ);
            var path = mob.getNavigation().createPath(candidate, 1);
            if (path == null || !path.canReach()) return false;

            flankTarget = candidate;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.getTarget() == null) return false;
            if (flankTarget == null) return false;
            if (duration >= MAX_DURATION) return false;
            // Stop flanking if already at flank position
            return !mob.blockPosition().closerThan(flankTarget, 3.0);
        }

        @Override
        public void start() {
            duration = 0;
        }

        @Override
        public void tick() {
            duration++;
            if (flankTarget != null) {
                mob.getNavigation().moveTo(
                    flankTarget.getX() + 0.5,
                    flankTarget.getY(),
                    flankTarget.getZ() + 0.5,
                    1.2 // slightly faster when flanking
                );
            }
        }

        @Override
        public void stop() {
            flankTarget = null;
            duration = 0;
        }
    }

    /**
     * Mob patrols known player hot spots from PlayerMemoryData.
     * Activates only when mob has no target and no active search.
     * Very slow, casual movement - mob doesn't "know" player is there,
     * just happens to wander toward frequent locations.
     */
    static class MemoryPatrolGoal extends Goal {
        private final Mob mob;
        private BlockPos destination = null;
        private int patrolTicks = 0;
        private static final int PATROL_TIMEOUT = 1200; // 60 sec max
        private static final double PATROL_SPEED = 0.4;

        public MemoryPatrolGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            // Must have no target and no active search
            if (mob.getTarget() != null) return false;
            var tag = mob.getPersistentData();
            if (tag.getInt(NBT_SEARCH_STATE) > 0) return false;
            if (tag.contains(NBT_LAST_X)) return false;

            // Only in overworld
            if (mob.level().dimension() != Level.OVERWORLD) return false;
            if (!(mob.level() instanceof ServerLevel sl)) return false;

            // Cooldown check
            long gt = sl.getGameTime();
            if (tag.contains(NBT_MEM_CD) && gt < tag.getLong(NBT_MEM_CD)) return false;

            // Find hot spot
            BlockPos hotSpot = PlayerMemoryData.get(sl.getServer().overworld())
                .getNearestHotSpot(mob.getBlockX(), mob.getBlockZ(), MEMORY_PATROL_RADIUS);
            if (hotSpot == null) return false;

            // Resolve Y coordinate
            int ny = sl.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                hotSpot.getX(), hotSpot.getZ());
            if (ny <= 0) return false;

            BlockPos resolved = new BlockPos(hotSpot.getX(), ny, hotSpot.getZ());

            // Don't patrol if already there
            if (mob.blockPosition().closerThan(resolved, 8.0)) return false;

            // Check path is reachable
            var path = mob.getNavigation().createPath(resolved, 1);
            if (path == null || !path.canReach()) return false;

            destination = resolved;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.getTarget() != null) return false;
            var tag = mob.getPersistentData();
            if (tag.getInt(NBT_SEARCH_STATE) > 0) return false;
            if (destination == null) return false;
            if (patrolTicks >= PATROL_TIMEOUT) return false;
            return !mob.blockPosition().closerThan(destination, 3.0);
        }

        @Override
        public void start() {
            patrolTicks = 0;
            mob.setAggressive(false);
            if (destination != null) {
                mob.getNavigation().moveTo(
                    destination.getX() + 0.5,
                    destination.getY(),
                    destination.getZ() + 0.5,
                    PATROL_SPEED);
            }
        }

        @Override
        public void tick() {
            patrolTicks++;
            mob.setAggressive(false);
            // Re-issue move command every 40 ticks in case nav was interrupted
            if (patrolTicks % 40 == 0 && destination != null) {
                mob.getNavigation().moveTo(
                    destination.getX() + 0.5,
                    destination.getY(),
                    destination.getZ() + 0.5,
                    PATROL_SPEED);
            }
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            mob.setAggressive(false);
            destination = null;
            patrolTicks = 0;
            // Set cooldown so mob doesn't immediately try again
            if (mob.level() instanceof ServerLevel sl) {
                mob.getPersistentData().putLong(NBT_MEM_CD,
                    sl.getGameTime() + MEMORY_PATROL_COOLDOWN);
            }
        }
    }

    static class SmartBreakDoorGoal extends BreakDoorGoal {
        public SmartBreakDoorGoal(Mob mob) {
            super(mob, 60, d -> d == Difficulty.HARD);
        }
        @Override public boolean canUse() {
            if (mob.getTarget() == null) return false;
            return super.canUse();
        }
        @Override public boolean canContinueToUse() {
            if (mob.getTarget() == null) return false;
            return super.canContinueToUse();
        }
    }

    static class AdvancedSearchGoal extends Goal {
        private final Mob mob;
        private final double speed;
        private BlockPos target = null;
        private int localTick = 0, stuckTicks = 0;
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
                double dx = mob.getX()-lastX, dz = mob.getZ()-lastZ;
                if (dx*dx+dz*dz < 0.01) { stuckTicks += 10; }
                else { stuckTicks = 0; lastX = mob.getX(); lastZ = mob.getZ(); }
                if (stuckTicks >= STUCK_THRESHOLD) {
                    stuckTicks = 0;
                    if (localTick > 200) { clearSearch(mob.getPersistentData()); return; }
                    pickTarget(); return;
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
                int nx = (int)(lx + Math.cos(angle)*dist), nz = (int)(lz + Math.sin(angle)*dist);
                int ny = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                if (ny <= 0 || Math.abs(ny-mob.getBlockY()) > 5) continue;
                BlockPos c = new BlockPos(nx, ny, nz);
                var path = mob.getNavigation().createPath(c, 1);
                if (path != null && path.canReach()) { target = c; return; }
            }
            target = null;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop(); mob.setAggressive(false);
            target = null; localTick = 0; stuckTicks = 0; lastX = Double.MIN_VALUE;
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
            LivingEntity t = mob.getTarget(); return t!=null && mob.distanceToSqr(t)<minSq;
        }
        @Override public boolean canContinueToUse() {
            LivingEntity t = mob.getTarget(); return t!=null && mob.distanceToSqr(t)<preferSq;
        }
        @Override public void tick() {
            LivingEntity t = mob.getTarget(); if (t==null) return;
            double dx=mob.getX()-t.getX(), dz=mob.getZ()-t.getZ();
            double lenSq=dx*dx+dz*dz; if (lenSq<0.0001) return;
            double inv=1.0/Math.sqrt(lenSq);
            double tx=mob.getX()+dx*inv*3, tz=mob.getZ()+dz*inv*3;
            int ty=mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)tx,(int)tz);
            if (Math.abs(ty-mob.getBlockY())>4) return;
            mob.getNavigation().moveTo(tx,ty,tz,1.0);
        }
    }
}
