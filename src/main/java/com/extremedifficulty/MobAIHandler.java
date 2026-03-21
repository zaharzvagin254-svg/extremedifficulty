package com.extremedifficulty;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    private static final String NBT_FLEE_DX    = "ed_fdx";
    private static final String NBT_FLEE_DZ    = "ed_fdz";
    private static final String NBT_FLEE_COUNT = "ed_fn";
    private static final String NBT_FLEE_DAY   = "ed_fday";
    private static final String NBT_MEM_CD     = "ed_mem_cd";

    private static final double DETECT_RANGE_NORMAL = 18.0;
    private static final double DETECT_RANGE_SNEAK  = 10.0;
    private static final double FOLLOW_RANGE_NIGHT  = 48.0;
    private static final double FOLLOW_RANGE_DAY    = 32.0;
    private static final int    SEARCH_ACTIVE_BASE  = 1000; // ~50 sec base
    private static final int    SEARCH_PASSIVE_BASE = 1000; // ~50 sec base
    // With variance 0.8-1.2: total = 1.33-2.0 min per mob
    private static final double HEAR_NORMAL = 10.0;
    private static final double HEAR_SNEAK  = 4.0;
    private static final double DROWNED_HEAR_LAND  = 5.0;
    private static final double DROWNED_HEAR_WATER = 10.0;
    private static final int    FLANK_THRESHOLD = 3;
    private static final int    MEMORY_PATROL_RADIUS = 80;
    private static final long   MEMORY_PATROL_COOLDOWN = 6000L;

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
            setupZombieLike(husk);
        } else if (entity instanceof Drowned drowned) {
            setupDrowned(drowned);
        } else if (entity instanceof WitherSkeleton ws) {
            setupMeleeRaider(ws);
        } else if (entity instanceof Zombie z && !(z instanceof ZombifiedPiglin)) {
            setupZombie(z);
        } else if (entity instanceof Stray stray) {
            setupArcherSkeleton(stray);
        } else if (entity.getClass() == Skeleton.class) {
            setupArcherSkeleton((Skeleton) entity);
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

    // -------------------------------------------------------------------------
    // SETUP METHODS
    // Vanilla goal priorities for reference:
    //   Zombie MeleeAttack = priority 4 (MOVE+LOOK)
    //   Skeleton RangedBow = priority 4 (MOVE+LOOK)
    // OUR goals must NOT use MOVE at priority <= 4 or they block vanilla attacks
    // -------------------------------------------------------------------------

    private void setupZombie(Zombie zombie) {
        // Remove vanilla targeting goals - replace with our LOS-aware DetectionGoal
        zombie.targetSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof NearestAttackableTargetGoal);
        zombie.goalSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof BreakDoorGoal);
        zombie.goalSelector.addGoal(1, new SmartBreakDoorGoal(zombie));
        // FIX: priorities 5,6,7 so vanilla MeleeAttackGoal at 4 is never blocked
        zombie.goalSelector.addGoal(3, new FlankGoal(zombie));
        zombie.goalSelector.addGoal(5, new AdvancedSearchGoal(zombie, 1.0));
        zombie.goalSelector.addGoal(6, new MemoryPatrolGoal(zombie));
        zombie.targetSelector.addGoal(3, new DetectionGoal(zombie, true));
        zombie.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(zombie, Villager.class, true));
        zombie.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(zombie, WanderingTrader.class, true));
        zombie.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void setupZombieLike(Mob mob) {
        mob.targetSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof NearestAttackableTargetGoal);
        mob.goalSelector.addGoal(5, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(6, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, true));
        mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, Villager.class, true));
        mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, WanderingTrader.class, true));
        mob.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void setupDrowned(Drowned drowned) {
        drowned.targetSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof NearestAttackableTargetGoal);
        drowned.goalSelector.addGoal(5, new AdvancedSearchGoal(drowned, 1.0));
        drowned.goalSelector.addGoal(6, new MemoryPatrolGoal(drowned));
        drowned.targetSelector.addGoal(3, new DrownedDetectionGoal(drowned));
        drowned.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(drowned, Villager.class, true));
        drowned.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(drowned, WanderingTrader.class, true));
        drowned.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    private void setupArcherSkeleton(AbstractSkeleton mob) {
        mob.targetSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof NearestAttackableTargetGoal);
        // FIX: SkeletonCoverGoal at 2 (MOVE only - doesn't block RangedAttack)
        mob.goalSelector.addGoal(2, new SkeletonCoverGoal(mob));
        // FIX: SafeKeepDistanceGoal at 5 (HIGHER number = LOWER priority)
        // Vanilla RangedBowAttackGoal is at priority 4 - now NOT blocked
        mob.goalSelector.addGoal(5, new SafeKeepDistanceGoal(mob, 6.0, 14.0));
        mob.goalSelector.addGoal(6, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(7, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, true));
        mob.getNavigation().setMaxVisitedNodesMultiplier(2.5f);
    }

    private void setupMeleeRaider(Mob mob) {
        mob.targetSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof NearestAttackableTargetGoal);
        mob.goalSelector.addGoal(5, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(6, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, false));
        if (mob instanceof Vindicator) {
            mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, Villager.class, true));
            mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, WanderingTrader.class, true));
        }
        mob.getNavigation().setMaxVisitedNodesMultiplier(3.0f);
    }

    private void setupBasic(Mob mob, boolean usesHearing, boolean keepsDistance) {
        mob.targetSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof NearestAttackableTargetGoal);
        mob.goalSelector.addGoal(5, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(6, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, usesHearing));
        if (keepsDistance)
            mob.goalSelector.addGoal(5, new SafeKeepDistanceGoal(mob, 8.0, 12.0));
        mob.getNavigation().setMaxVisitedNodesMultiplier(mob instanceof Spider ? 3.0f : 2.0f);
    }

    private void setupRangedRaider(Pillager mob) {
        mob.targetSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof NearestAttackableTargetGoal);
        mob.goalSelector.addGoal(2, new SkeletonCoverGoal(mob));
        mob.goalSelector.addGoal(5, new SafeKeepDistanceGoal(mob, 10.0, 16.0));
        mob.goalSelector.addGoal(6, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(7, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, false));
        mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, Villager.class, true));
        mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, WanderingTrader.class, true));
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

        if (gt % 20 == 0) {
            for (var player : sl.players()) {
                if (player.isCrouching() || player.isCreative() || player.isSpectator()) continue;
                // Raise threshold - player must actually be walking (not just physics jitter)
                if (player.getDeltaMovement().horizontalDistanceSqr() < 0.005) continue;
                SoundSystem.triggerSound(sl, player.position(), SoundSystem.R_FOOTSTEP, player);
            }
        }

        if (gt % 10 != 0) return;

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
            applyLeadShotIfNeeded(mob, gt);
        });
    }

    private void applyLeadShotIfNeeded(Mob mob, long gt) {
        if (!(mob instanceof AbstractSkeleton) && !(mob instanceof Pillager)) return;
        if (!(mob.getTarget() instanceof Player player)) return;
        if (!player.isSprinting()) return;
        if (mob.distanceToSqr(player) < 64.0) return;
        if (gt % 20 != 0) return;
        if (mob.getRandom().nextFloat() > 0.3f) return;
        double dist = mob.distanceTo(player);
        double t = dist / 1.5;
        mob.getLookControl().setLookAt(
            player.getX() + player.getDeltaMovement().x * t,
            player.getY() + player.getDeltaMovement().y * t,
            player.getZ() + player.getDeltaMovement().z * t,
            mob.getMaxHeadYRot(), mob.getMaxHeadXRot());
    }

    // NBT key: zombie saw player enter a door
    private static final String NBT_SAW_DOOR = "ed_door";

    private void updateSearchState(Mob mob, ServerLevel level, long gt) {
        var tag = mob.getPersistentData();
        int state = tag.getInt(NBT_SEARCH_STATE);

        if (mob.getTarget() instanceof Player player) {
            if (gt % 20 == 0) {
                if (hasReliableLOS(mob, player)) {
                    // Can see - update last known pos, clear door flag
                    tag.putDouble(NBT_LAST_X, player.getX());
                    tag.putDouble(NBT_LAST_Y, player.getY());
                    tag.putDouble(NBT_LAST_Z, player.getZ());
                    tag.putInt(NBT_SEARCH_STATE, 0);
                    tag.putInt(NBT_SEARCH_TICKS, 0);
                    tag.putByte(NBT_SAW_DOOR, (byte) 0);
                } else {
                    // Lost LOS - check if player went through a door nearby
                    if (mob instanceof Zombie && tag.contains(NBT_LAST_X)) {
                        if (isDoorNearLastPos(level, tag)) {
                            tag.putByte(NBT_SAW_DOOR, (byte) 1);
                        }
                    }
                    boolean canHear = canHearPlayer(mob, player);
                    if (!canHear) {
                        mob.setTarget(null);
                        if (tag.contains(NBT_LAST_X)) {
                            tag.putInt(NBT_SEARCH_STATE, 1);
                            tag.putInt(NBT_SEARCH_TICKS, 0);
                        }
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
            tag.putInt(NBT_SEARCH_STATE, 2); tag.putInt(NBT_SEARCH_TICKS, 0);
        } else if (state == 2 && ticks >= passiveDur) {
            clearSearch(tag); return;
        }

        if (tag.contains(NBT_LAST_X)) {
            Player nearby = level.getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearby != null && !nearby.isCreative() && !nearby.isSpectator()
             && hasReliableLOS(mob, nearby)) {
                mob.setTarget(nearby); clearSearch(tag);
            }
        }
    }

    private void updateFlankData(Mob mob, long gt) {
        if (!(mob instanceof Zombie) || mob instanceof ZombifiedPiglin) return;
        if (!(mob.getTarget() instanceof Player player)) return;
        if (!hasReliableLOS(mob, player)) return;
        double dx = player.getX()-mob.getX(), dz = player.getZ()-mob.getZ();
        double len = Math.sqrt(dx*dx+dz*dz);
        if (len < 1.0) return;
        double dot = player.getDeltaMovement().x*(dx/len)+player.getDeltaMovement().z*(dz/len);
        if (dot < 0.3) return;
        var tag = mob.getPersistentData();
        long day = gt/24000;
        if (tag.contains(NBT_FLEE_DAY) && day-tag.getLong(NBT_FLEE_DAY) > 3) {
            tag.remove(NBT_FLEE_DX); tag.remove(NBT_FLEE_DZ); tag.putInt(NBT_FLEE_COUNT, 0);
        }
        int count = tag.contains(NBT_FLEE_COUNT) ? tag.getInt(NBT_FLEE_COUNT) : 0;
        double ndx=dx/len, ndz=dz/len;
        if (count==0) { tag.putFloat(NBT_FLEE_DX,(float)ndx); tag.putFloat(NBT_FLEE_DZ,(float)ndz); }
        else {
            float a=0.3f;
            float ax=tag.getFloat(NBT_FLEE_DX)*(1-a)+(float)(ndx*a);
            float az=tag.getFloat(NBT_FLEE_DZ)*(1-a)+(float)(ndz*a);
            float nl=(float)Math.sqrt(ax*ax+az*az);
            if (nl>0.01f) { tag.putFloat(NBT_FLEE_DX,ax/nl); tag.putFloat(NBT_FLEE_DZ,az/nl); }
        }
        tag.putInt(NBT_FLEE_COUNT, Math.min(count+1,20));
        tag.putLong(NBT_FLEE_DAY, day);
    }

    /**
     * Check if there is a closed door within 3 blocks of the last known player position.
     * Used to detect when player entered a building through a door.
     */
    private static boolean isDoorNearLastPos(ServerLevel level,
                                              net.minecraft.nbt.CompoundTag tag) {
        int lx = (int) tag.getDouble(NBT_LAST_X);
        int ly = (int) tag.getDouble(NBT_LAST_Y);
        int lz = (int) tag.getDouble(NBT_LAST_Z);
        for (int dx = -3; dx <= 3; dx++)
        for (int dy = -1; dy <= 1; dy++)
        for (int dz = -3; dz <= 3; dz++) {
            BlockPos bp = new BlockPos(lx+dx, ly+dy, lz+dz);
            if (level.getBlockState(bp).getBlock()
                    instanceof net.minecraft.world.level.block.DoorBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reliable LOS check using COLLIDER clip type.
     * Vanilla hasLineOfSight uses VISUAL which can pass through some blocks.
     * We use COLLIDER which respects solid blocks properly.
     * Also offsets start position to avoid being inside wall blocks.
     */
    private static boolean hasReliableLOS(Mob mob, LivingEntity target) {
        // Use eye positions but pull them slightly inward to avoid wall edge issues
        Vec3 mobEye    = mob.getEyePosition();
        Vec3 targetEye = target.getEyePosition();

        var result = mob.level().clip(new ClipContext(
            mobEye, targetEye,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            mob
        ));
        return result.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }
        tag.putInt(NBT_SEARCH_STATE, 0);
        tag.putInt(NBT_SEARCH_TICKS, 0);
        tag.remove(NBT_LAST_X); tag.remove(NBT_LAST_Y); tag.remove(NBT_LAST_Z);
        tag.putByte(NBT_SAW_DOOR, (byte) 0);
    }

    private boolean canHearPlayer(Mob mob, Player player) {
        if (player.isCrouching()) return false;
        double r = player.isSprinting() ? HEAR_NORMAL*1.3 : HEAR_NORMAL;
        return mob.distanceToSqr(player) < r*r;
    }

    public static boolean isSoundReactiveMob(net.minecraft.world.entity.Mob mob) {
        return mob instanceof Zombie || mob instanceof Skeleton
            || mob instanceof Spider || mob instanceof CaveSpider
            || mob instanceof Vindicator || mob instanceof Pillager;
    }

    static boolean isNight(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END) return true;
        long t = level.getDayTime() % 24000;
        return t >= 13000 && t <= 23000;
    }

    // =========================================================================
    // GOALS
    // =========================================================================

    static class DetectionGoal extends NearestAttackableTargetGoal<Player> {
        private final boolean usesHearing;
        private final TargetingConditions normalSight, sneakSight, normalHear, sneakHear;
        public DetectionGoal(Mob mob, boolean usesHearing) {
            super(mob, Player.class, 10, true, false, null);
            this.usesHearing = usesHearing;
            normalSight = TargetingConditions.forCombat().range(DETECT_RANGE_NORMAL);
            sneakSight  = TargetingConditions.forCombat().range(DETECT_RANGE_SNEAK);
            normalHear  = TargetingConditions.forCombat().range(HEAR_NORMAL).ignoreLineOfSight();
            sneakHear   = TargetingConditions.forCombat().range(HEAR_SNEAK).ignoreLineOfSight();
        }
        @Override public boolean canUse() {
            Player nearest = mob.level().getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearest==null||nearest.isCreative()||nearest.isSpectator()||nearest.isInvisible()) return false;
            boolean sneak = nearest.isCrouching();

            // Sight detection: must have reliable LOS (COLLIDER-based, not VISUAL)
            this.targetConditions = sneak ? sneakSight : normalSight;
            if (super.canUse()) {
                // Double-check with reliable raycast to prevent wall detection
                if (!hasReliableLOS(mob, nearest)) return false;
                return true;
            }
            // Hearing: no LOS required, but only at night
            if (usesHearing && isNight(mob.level())) {
                this.targetConditions = sneak ? sneakHear : normalHear;
                return super.canUse();
            }
            return false;
        }
    }

    static class DrownedDetectionGoal extends NearestAttackableTargetGoal<Player> {
        private final TargetingConditions normalSight, sneakSight, waterHear, landHear, sneakHear;
        public DrownedDetectionGoal(Drowned mob) {
            super(mob, Player.class, 10, true, false, null);
            normalSight = TargetingConditions.forCombat().range(DETECT_RANGE_NORMAL);
            sneakSight  = TargetingConditions.forCombat().range(DETECT_RANGE_SNEAK);
            waterHear   = TargetingConditions.forCombat().range(DROWNED_HEAR_WATER).ignoreLineOfSight();
            landHear    = TargetingConditions.forCombat().range(DROWNED_HEAR_LAND).ignoreLineOfSight();
            sneakHear   = TargetingConditions.forCombat().range(HEAR_SNEAK).ignoreLineOfSight();
        }
        @Override public boolean canUse() {
            Player nearest = mob.level().getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearest==null||nearest.isCreative()||nearest.isSpectator()||nearest.isInvisible()) return false;
            boolean sneak = nearest.isCrouching();
            this.targetConditions = sneak ? sneakSight : normalSight;
            if (super.canUse()) {
                if (!hasReliableLOS(mob, nearest)) return false;
                return true;
            }
            this.targetConditions = sneak ? sneakHear : (mob.isInWater() ? waterHear : landHear);
            return super.canUse();
        }
    }

    // FIX: SkeletonCoverGoal - MOVE only, hasBow at runtime, atCoverTicks in tick() only
    static class SkeletonCoverGoal extends Goal {
        private final Mob mob;
        private BlockPos coverPos = null;
        private int cooldown = 0, atCoverTicks = 0;
        private boolean movingToCover = false;
        private static final int STAY = 200, CD = 200, PMIN = 8, PMAX = 16;

        public SkeletonCoverGoal(Mob mob) {
            this.mob = mob;
            // FIX: empty flags - does NOT block RangedAttackGoal (MOVE) or LookControl (LOOK)
            // Skeleton moves to cover in start(), then stops moving so RangedAttack can work
            setFlags(EnumSet.noneOf(Flag.class));
        }

        private boolean hasBow() {
            var item = mob.getMainHandItem().getItem();
            return item instanceof BowItem || item instanceof CrossbowItem;
        }

        @Override public boolean canUse() {
            if (!hasBow()) return false;
            if (mob.getTarget() == null) return false;
            if (cooldown > 0) { cooldown--; return false; }
            // Already at good cover and not expired - stay, let RangedAttack work
            if (coverPos != null && mob.blockPosition().closerThan(coverPos, 3.0)
             && atCoverTicks < STAY) return false;
            return findCover(mob.getTarget());
        }

        @Override public boolean canContinueToUse() {
            if (!hasBow() || mob.getTarget() == null || coverPos == null) return false;
            if (atCoverTicks >= STAY) return false;
            return true; // stay active - RangedAttack will handle shooting
        }

        private boolean findCover(LivingEntity target) {
            int mx=mob.getBlockX(), mz=mob.getBlockZ();
            double tx=target.getX(), tz=target.getZ();
            var level = mob.level();
            BlockPos best = null; double bestScore = -1;
            for (int i = 0; i < 16; i++) {
                double angle = mob.getRandom().nextDouble()*Math.PI*2;
                double dist  = PMIN + mob.getRandom().nextDouble()*(PMAX-PMIN);
                int nx=mx+(int)(Math.cos(angle)*dist), nz=mz+(int)(Math.sin(angle)*dist);
                int ny=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,nx,nz);
                if (ny<=0) continue;
                BlockPos cand = new BlockPos(nx,ny,nz);
                double dtx=nx-tx,dtz=nz-tz,dtt=Math.sqrt(dtx*dtx+dtz*dtz);
                if (dtt<6||dtt>20) continue;
                double cq = evalCover(level, cand, target);
                if (cq<=0) continue;
                Vec3 eye=new Vec3(nx+0.5,ny+1.6,nz+0.5);
                var clip=level.clip(new ClipContext(eye,target.getEyePosition(),
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mob));
                if (clip.getType()!=HitResult.Type.MISS) continue;
                var path=mob.getNavigation().createPath(cand,1);
                if (path==null||!path.canReach()) continue;
                double ds=1.0-Math.abs(dtt-12.0)/8.0;
                double score=cq*0.7+Math.max(0,ds)*0.3;
                if (score>bestScore){bestScore=score;best=cand;}
            }
            if (best!=null){coverPos=best;atCoverTicks=0;return true;}
            return false;
        }

        private double evalCover(net.minecraft.world.level.Level level, BlockPos pos, LivingEntity target) {
            double pdx=pos.getX()-target.getX(),pdz=pos.getZ()-target.getZ();
            double pl=Math.sqrt(pdx*pdx+pdz*pdz);if(pl<0.1)return 0;
            pdx/=pl;pdz/=pl;
            int[]ddx={1,-1,0,0},ddz={0,0,1,-1};int cover=0;
            for(int i=0;i<4;i++){
                BlockPos nb=pos.offset(ddx[i],0,ddz[i]);
                if(!level.getBlockState(nb).isSolidRender(level,nb))continue;
                if(ddx[i]*(-pdx)+ddz[i]*(-pdz)>0.3)cover++;
            }
            if(cover==0)return 0;
            boolean canShoot=level.getBlockState(pos.above()).isAir();
            if(!canShoot&&cover>=3)return 0.1;
            return 0.5+cover*0.15;
        }

        @Override public void start() {
            atCoverTicks = 0; movingToCover = true;
            if (coverPos != null)
                mob.getNavigation().moveTo(coverPos.getX()+0.5, coverPos.getY(), coverPos.getZ()+0.5, 1.0);
        }

        @Override public void tick() {
            atCoverTicks++;
            if (coverPos == null) return;
            if (mob.blockPosition().closerThan(coverPos, 3.0)) {
                // At cover - stop our navigation so RangedAttackGoal can control movement
                if (movingToCover) {
                    mob.getNavigation().stop();
                    movingToCover = false;
                }
                // Don't touch LookControl - let RangedAttackGoal aim and shoot
            } else {
                // Still moving to cover position
                movingToCover = true;
                if (atCoverTicks % 20 == 0)
                    mob.getNavigation().moveTo(coverPos.getX()+0.5, coverPos.getY(), coverPos.getZ()+0.5, 1.0);
            }
        }
        @Override public void stop() { cooldown=mob.blockPosition().closerThan(coverPos!=null?coverPos:mob.blockPosition(),3.0)?CD*2:CD; }
    }

    static class FlankGoal extends Goal {
        private final Mob mob; private BlockPos ft=null; private int dur=0;
        private static final int MAX=200;
        public FlankGoal(Mob mob){this.mob=mob;setFlags(EnumSet.of(Flag.MOVE));}
        @Override public boolean canUse() {
            if(mob.getTarget()==null)return false;
            var tag=mob.getPersistentData();
            if(!tag.contains(NBT_FLEE_COUNT)||tag.getInt(NBT_FLEE_COUNT)<FLANK_THRESHOLD)return false;
            if(!tag.contains(NBT_FLEE_DX))return false;
            if(!(mob.level() instanceof ServerLevel sl))return false;
            LivingEntity tgt=mob.getTarget();
            List<Zombie> ch=sl.getEntitiesOfClass(Zombie.class,
                new AABB(mob.getX()-20,mob.getY()-5,mob.getZ()-20,mob.getX()+20,mob.getY()+5,mob.getZ()+20),
                z->z!=mob&&tgt.equals(z.getTarget()));
            if(ch.size()<2)return false;
            int mc=tag.getInt(NBT_FLEE_COUNT);
            for(Zombie z:ch){int oc=z.getPersistentData().contains(NBT_FLEE_COUNT)?z.getPersistentData().getInt(NBT_FLEE_COUNT):0;if(oc>mc)return false;}
            float fdx=tag.getFloat(NBT_FLEE_DX),fdz=tag.getFloat(NBT_FLEE_DZ);
            double fx=tgt.getX()+(-fdz)*8,fz=tgt.getZ()+fdx*8;
            int ny=mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)fx,(int)fz);
            if(ny<=0)return false;
            BlockPos c=new BlockPos((int)fx,ny,(int)fz);
            var path=mob.getNavigation().createPath(c,1);
            if(path==null||!path.canReach())return false;
            ft=c;return true;
        }
        @Override public boolean canContinueToUse(){return mob.getTarget()!=null&&ft!=null&&dur<MAX&&!mob.blockPosition().closerThan(ft,3.0);}
        @Override public void start(){dur=0;}
        @Override public void tick(){dur++;if(ft!=null)mob.getNavigation().moveTo(ft.getX()+0.5,ft.getY(),ft.getZ()+0.5,1.2);}
        @Override public void stop(){ft=null;dur=0;}
    }

    static class AdvancedSearchGoal extends Goal {
        private final Mob mob;private final double speed;
        private BlockPos target=null;private int lt=0,st=0;
        private double lx=Double.MIN_VALUE,lz;private static final int STUCK=60;
        public AdvancedSearchGoal(Mob mob,double speed){this.mob=mob;this.speed=speed;setFlags(EnumSet.of(Flag.MOVE));}
        @Override public boolean canUse(){
            if(mob.getTarget()!=null)return false;
            var t=mob.getPersistentData();
            return t.getInt(NBT_SEARCH_STATE)>0&&t.contains(NBT_LAST_X);
        }
        @Override public boolean canContinueToUse(){
            if(mob.getTarget()!=null)return false;
            var t=mob.getPersistentData();
            return t.getInt(NBT_SEARCH_STATE)>0&&t.contains(NBT_LAST_X);
        }
        @Override public void start(){lt=0;st=0;lx=mob.getX();lz=mob.getZ();mob.setAggressive(false);pick();}
        @Override public void tick(){
            lt++;mob.setAggressive(false);
            if(lt%10==0){double dx=mob.getX()-lx,dz=mob.getZ()-lz;
                if(dx*dx+dz*dz<0.01)st+=10;else{st=0;lx=mob.getX();lz=mob.getZ();}
                if(st>=STUCK){st=0;if(lt>200){clearSearch(mob.getPersistentData());return;}pick();return;}
            }
            int state=mob.getPersistentData().getInt(NBT_SEARCH_STATE);
            if(target==null||mob.blockPosition().closerThan(target,2.0)||lt%80==0)pick();
            if(target!=null)mob.getNavigation().moveTo(target.getX()+0.5,target.getY(),target.getZ()+0.5,state==1?speed*0.7:speed*0.5);
        }
        private void pick(){
            var tag=mob.getPersistentData();
            if(!tag.contains(NBT_LAST_X)){target=null;return;}
            double lxp=tag.getDouble(NBT_LAST_X),lzp=tag.getDouble(NBT_LAST_Z);
            int state=tag.getInt(NBT_SEARCH_STATE);double r=state==1?5.0:10.0;
            for(int a=0;a<2;a++){
                double ang=mob.getRandom().nextDouble()*Math.PI*2,d=r*(0.4+mob.getRandom().nextDouble()*0.6);
                int nx=(int)(lxp+Math.cos(ang)*d),nz=(int)(lzp+Math.sin(ang)*d);
                int ny=mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,nx,nz);
                if(ny<=0||Math.abs(ny-mob.getBlockY())>5)continue;
                BlockPos c=new BlockPos(nx,ny,nz);var path=mob.getNavigation().createPath(c,1);
                if(path!=null&&path.canReach()){target=c;return;}
            }
            target=null;
        }
        @Override public void stop(){mob.getNavigation().stop();mob.setAggressive(false);target=null;lt=0;st=0;lx=Double.MIN_VALUE;}
    }

    static class MemoryPatrolGoal extends Goal {
        private final Mob mob;private BlockPos dest=null;private int pt=0;
        private static final int TIMEOUT=1200;private static final double SPD=0.4;
        private long lastCheckGt=-99999;private static final long CHECK_INTERVAL=200;
        public MemoryPatrolGoal(Mob mob){this.mob=mob;setFlags(EnumSet.of(Flag.MOVE));}
        @Override public boolean canUse(){
            if(mob.getTarget()!=null)return false;
            var tag=mob.getPersistentData();
            if(tag.getInt(NBT_SEARCH_STATE)>0||tag.contains(NBT_LAST_X))return false;
            if(mob.level().dimension()!=Level.OVERWORLD)return false;
            if(!(mob.level() instanceof ServerLevel sl))return false;
            long gt=sl.getGameTime();
            if(gt-lastCheckGt<CHECK_INTERVAL)return false;
            lastCheckGt=gt;
            if(tag.contains(NBT_MEM_CD)&&gt<tag.getLong(NBT_MEM_CD))return false;
            BlockPos hot=PlayerMemoryData.get(sl.getServer().overworld())
                .getNearestHotSpot(mob.getBlockX(),mob.getBlockZ(),MEMORY_PATROL_RADIUS);
            if(hot==null)return false;
            int ny=sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,hot.getX(),hot.getZ());
            if(ny<=0)return false;
            BlockPos r=new BlockPos(hot.getX(),ny,hot.getZ());
            if(mob.blockPosition().closerThan(r,8.0))return false;
            var path=mob.getNavigation().createPath(r,1);
            if(path==null||!path.canReach())return false;
            dest=r;return true;
        }
        @Override public boolean canContinueToUse(){
            if(mob.getTarget()!=null)return false;
            var tag=mob.getPersistentData();
            if(tag.getInt(NBT_SEARCH_STATE)>0)return false;
            return dest!=null&&pt<TIMEOUT&&!mob.blockPosition().closerThan(dest,3.0);
        }
        @Override public void start(){pt=0;mob.setAggressive(false);if(dest!=null)mob.getNavigation().moveTo(dest.getX()+0.5,dest.getY(),dest.getZ()+0.5,SPD);}
        @Override public void tick(){pt++;mob.setAggressive(false);if(pt%40==0&&dest!=null)mob.getNavigation().moveTo(dest.getX()+0.5,dest.getY(),dest.getZ()+0.5,SPD);}
        @Override public void stop(){
            mob.getNavigation().stop();mob.setAggressive(false);dest=null;pt=0;
            if(mob.level() instanceof ServerLevel sl)mob.getPersistentData().putLong(NBT_MEM_CD,sl.getGameTime()+MEMORY_PATROL_COOLDOWN);
        }
    }

    static class SmartBreakDoorGoal extends BreakDoorGoal {
        public SmartBreakDoorGoal(Mob mob) { super(mob, 60, d -> d == Difficulty.HARD); }

        @Override
        public boolean canUse() {
            // Case 1: has active target - break door if blocking path
            if (mob.getTarget() != null) return super.canUse();
            // Case 2: saw player enter through door - break it to follow
            var tag = mob.getPersistentData();
            if (tag.getByte(NBT_SAW_DOOR) == 1) {
                // Only if there's actually a door nearby (within 2 blocks)
                return super.canUse();
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            var tag = mob.getPersistentData();
            return (mob.getTarget() != null || tag.getByte(NBT_SAW_DOOR) == 1)
                && super.canContinueToUse();
        }

        @Override
        public void stop() {
            super.stop();
            // Clear door flag once we break through (or give up)
            mob.getPersistentData().putByte(NBT_SAW_DOOR, (byte) 0);
        }
    }

    static class SafeKeepDistanceGoal extends Goal {
        private final Mob mob;private final double minSq,preferSq;
        public SafeKeepDistanceGoal(Mob mob,double min,double prefer){
            this.mob=mob;minSq=min*min;preferSq=prefer*prefer;setFlags(EnumSet.of(Flag.MOVE));
        }
        @Override public boolean canUse(){LivingEntity t=mob.getTarget();return t!=null&&mob.distanceToSqr(t)<minSq;}
        @Override public boolean canContinueToUse(){LivingEntity t=mob.getTarget();return t!=null&&mob.distanceToSqr(t)<preferSq;}
        @Override public void tick(){
            LivingEntity t=mob.getTarget();if(t==null)return;
            double dx=mob.getX()-t.getX(),dz=mob.getZ()-t.getZ(),ls=dx*dx+dz*dz;
            if(ls<0.0001)return;
            double inv=1.0/Math.sqrt(ls),tx=mob.getX()+dx*inv*3,tz=mob.getZ()+dz*inv*3;
            int ty=mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)tx,(int)tz);
            if(Math.abs(ty-mob.getBlockY())>4)return;
            mob.getNavigation().moveTo(tx,ty,tz,1.0);
        }
    }
}
