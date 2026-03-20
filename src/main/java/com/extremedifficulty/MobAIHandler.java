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
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final int    SEARCH_ACTIVE_BASE  = 1200;
    private static final int    SEARCH_PASSIVE_BASE = 1200;
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
            setupZombieLike(husk, false);
        } else if (entity instanceof Drowned drowned) {
            setupDrowned(drowned);
        } else if (entity instanceof WitherSkeleton ws) {
            // Wither skeleton is always melee - treat like vindicator
            setupMeleeRaider(ws);
        } else if (entity instanceof Zombie z && !(z instanceof ZombifiedPiglin)) {
            setupZombie(z);
        } else if (entity instanceof Stray stray) {
            setupArcherSkeleton(stray);
        } else if (entity.getClass() == Skeleton.class) {
            Skeleton sk = (Skeleton) entity;
            // Check if skeleton actually has a bow - if not, treat as melee
            if (hasBow(sk)) {
                setupArcherSkeleton(sk);
            } else {
                setupZombieLike(sk);
            }
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

    /** Check if mob has bow or crossbow equipped */
    private boolean hasBow(Mob mob) {
        var item = mob.getMainHandItem().getItem();
        return item instanceof BowItem || item instanceof CrossbowItem;
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

    /** Used for Husk, melee Skeleton, other zombie-like */
    private void setupZombieLike(Mob mob) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(5, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, true));
        mob.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
    }

    /** Used for Husk (Zombie subclass) specifically */
    private void setupZombieLike(Zombie mob) {
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

    /**
     * Archer skeleton: seeks cover, keeps distance, leads shots.
     * Applied to Skeleton (with bow) and Stray.
     */
    private void setupArcherSkeleton(AbstractSkeleton mob) {
        mob.goalSelector.addGoal(2, new SkeletonCoverGoal(mob));
        mob.goalSelector.addGoal(3, new SafeKeepDistanceGoal(mob, 6.0, 14.0));
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(5, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, true));
        mob.getNavigation().setMaxVisitedNodesMultiplier(2.5f);
    }

    /** Wither skeleton - melee, sight only, good pathfinding */
    private void setupMeleeRaider(Mob mob) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(5, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, false));
        mob.getNavigation().setMaxVisitedNodesMultiplier(3.0f);
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

    private void setupRangedRaider(Pillager mob) {
        mob.goalSelector.addGoal(2, new SkeletonCoverGoal(mob));
        mob.goalSelector.addGoal(3, new SafeKeepDistanceGoal(mob, 10.0, 16.0));
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, 1.0));
        mob.goalSelector.addGoal(5, new MemoryPatrolGoal(mob));
        mob.targetSelector.addGoal(3, new DetectionGoal(mob, false));
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
                if (player.getDeltaMovement().horizontalDistanceSqr() < 0.001) continue;
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
            tag.putInt(NBT_SEARCH_STATE, 2); tag.putInt(NBT_SEARCH_TICKS, 0);
        } else if (state == 2 && ticks >= passiveDur) {
            clearSearch(tag); return;
        }

        if (tag.contains(NBT_LAST_X)) {
            Player nearby = level.getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearby != null && !nearby.isCreative() && !nearby.isSpectator()
             && mob.hasLineOfSight(nearby)) {
                mob.setTarget(nearby); clearSearch(tag);
            }
        }
    }

    private void updateFlankData(Mob mob, long gt) {
        if (!(mob instanceof Zombie) || mob instanceof ZombifiedPiglin) return;
        if (!(mob.getTarget() instanceof Player player)) return;
        if (!mob.hasLineOfSight(player)) return;

        double dx = player.getX()-mob.getX(), dz = player.getZ()-mob.getZ();
        double len = Math.sqrt(dx*dx+dz*dz);
        if (len < 1.0) return;
        double ndx = dx/len, ndz = dz/len;
        double dot = player.getDeltaMovement().x*ndx + player.getDeltaMovement().z*ndz;
        if (dot < 0.3) return;

        var tag = mob.getPersistentData();
        long currentDay = gt / 24000;
        if (tag.contains(NBT_FLEE_DAY) && currentDay - tag.getLong(NBT_FLEE_DAY) > 3) {
            tag.remove(NBT_FLEE_DX); tag.remove(NBT_FLEE_DZ); tag.putInt(NBT_FLEE_COUNT, 0);
        }
        int count = tag.contains(NBT_FLEE_COUNT) ? tag.getInt(NBT_FLEE_COUNT) : 0;
        if (count == 0) {
            tag.putFloat(NBT_FLEE_DX, (float)ndx); tag.putFloat(NBT_FLEE_DZ, (float)ndz);
        } else {
            float alpha = 0.3f;
            float ax = tag.getFloat(NBT_FLEE_DX)*(1-alpha) + (float)(ndx*alpha);
            float az = tag.getFloat(NBT_FLEE_DZ)*(1-alpha) + (float)(ndz*alpha);
            float nlen = (float)Math.sqrt(ax*ax+az*az);
            if (nlen > 0.01f) { tag.putFloat(NBT_FLEE_DX, ax/nlen); tag.putFloat(NBT_FLEE_DZ, az/nlen); }
        }
        tag.putInt(NBT_FLEE_COUNT, Math.min(count+1, 20));
        tag.putLong(NBT_FLEE_DAY, currentDay);
    }

    static void clearSearch(net.minecraft.nbt.CompoundTag tag) {
        tag.putInt(NBT_SEARCH_STATE, 0); tag.putInt(NBT_SEARCH_TICKS, 0);
        tag.remove(NBT_LAST_X); tag.remove(NBT_LAST_Y); tag.remove(NBT_LAST_Z);
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
        return level.getDayTime() % 24000 >= 13000;
    }

    // =========================================================================
    // GOALS
    // =========================================================================

    // -------------------------------------------------------------------------
    // SkeletonCoverGoal: seeks cover and shoots from there
    // -------------------------------------------------------------------------
    static class SkeletonCoverGoal extends Goal {
        private final Mob mob;
        private BlockPos coverPos   = null;
        private int      cooldown   = 0;
        private int      atCoverFor = 0;

        // How long to stay at cover before reconsidering
        private static final int STAY_AT_COVER   = 200; // 10 sec
        private static final int REPOSITION_CD   = 200; // 10 sec cooldown
        private static final int COVER_SCAN_RANGE = 14;
        private static final int PREFER_DIST_MIN  = 8;
        private static final int PREFER_DIST_MAX  = 16;

        public SkeletonCoverGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = mob.getTarget();
            if (target == null) return false;
            if (cooldown > 0) { cooldown--; return false; }
            // Only seek cover if not already in good cover
            if (coverPos != null && mob.blockPosition().closerThan(coverPos, 3.0)
             && atCoverFor < STAY_AT_COVER) return false;
            return findCover(target);
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.getTarget() == null) return false;
            if (coverPos == null) return false;
            atCoverFor++;
            // Stay at cover for up to STAY_AT_COVER ticks or until target moves far
            if (atCoverFor >= STAY_AT_COVER) return false;
            // If we're at cover and have LOS - stay here and let ranged attack work
            if (mob.blockPosition().closerThan(coverPos, 3.0)) {
                // Keep cover goal active but don't move - let ranged attack handle
                return mob.hasLineOfSight(mob.getTarget());
            }
            return true;
        }

        private boolean findCover(LivingEntity target) {
            double tx = target.getX(), ty = target.getY(), tz = target.getZ();
            int mobX = mob.getBlockX(), mobY = mob.getBlockY(), mobZ = mob.getBlockZ();
            var level = mob.level();

            BlockPos bestPos = null;
            double  bestScore = -1;

            // Scan positions in range
            for (int attempt = 0; attempt < 16; attempt++) {
                double angle = mob.getRandom().nextDouble() * Math.PI * 2;
                double dist  = PREFER_DIST_MIN + mob.getRandom().nextDouble()
                             * (PREFER_DIST_MAX - PREFER_DIST_MIN);
                int nx = mobX + (int)(Math.cos(angle) * dist);
                int nz = mobZ + (int)(Math.sin(angle) * dist);
                int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                if (ny <= 0) continue;

                BlockPos candidate = new BlockPos(nx, ny, nz);
                double dtx = nx - tx, dtz = nz - tz;
                double distToTarget = Math.sqrt(dtx*dtx + dtz*dtz);

                // Prefer positions in sniper range
                if (distToTarget < 6 || distToTarget > 20) continue;

                // Check if there's a solid block nearby for cover
                double coverQuality = evaluateCover(level, candidate, target);
                if (coverQuality <= 0) continue;

                // Must have LOS to target from this position (can shoot)
                Vec3 eyePos = new Vec3(nx + 0.5, ny + 1.6, nz + 0.5);
                Vec3 targetEye = target.getEyePosition();
                var clip = level.clip(new net.minecraft.world.level.ClipContext(
                    eyePos, targetEye,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mob));
                if (clip.getType() != net.minecraft.world.phys.HitResult.Type.MISS) continue;

                // Check path reachable
                var path = mob.getNavigation().createPath(candidate, 1);
                if (path == null || !path.canReach()) continue;

                // Score: cover quality + preferred distance bonus
                double distScore = 1.0 - Math.abs(distToTarget - 12.0) / 8.0;
                double score = coverQuality * 0.7 + Math.max(0, distScore) * 0.3;

                if (score > bestScore) {
                    bestScore = score;
                    bestPos = candidate;
                }
            }

            if (bestPos != null) {
                coverPos = bestPos;
                atCoverFor = 0;
                return true;
            }
            return false;
        }

        /**
         * Evaluate how good a position is for cover.
         * Returns 0 if no cover, 0.5-1.0 if good cover.
         * Cover = solid block between this position and target on at least one side.
         */
        private double evaluateCover(Level level, BlockPos pos, LivingEntity target) {
            // Check the 4 horizontal directions for solid blocks
            int[] dx = {1, -1, 0, 0};
            int[] dz = {0, 0, 1, -1};

            double tx = target.getX(), tz = target.getZ();
            double pdx = pos.getX() - tx, pdz = pos.getZ() - tz;
            double plen = Math.sqrt(pdx*pdx + pdz*pdz);
            if (plen < 0.1) return 0;
            pdx /= plen; pdz /= plen; // direction FROM target TO this pos

            int coverCount = 0;
            for (int i = 0; i < 4; i++) {
                BlockPos neighbor = pos.offset(dx[i], 0, dz[i]);
                BlockState bs = level.getBlockState(neighbor);
                if (!bs.isSolidRender(level, neighbor)) continue;

                // Check if this solid block is roughly between us and target
                double dot = dx[i] * (-pdx) + dz[i] * (-pdz);
                if (dot > 0.3) { // block is on the side facing target
                    coverCount++;
                }
            }

            // Also check if block above is air (can shoot over cover)
            BlockState above = level.getBlockState(pos.above());
            boolean canShootOver = above.isAir();

            if (coverCount == 0) return 0;
            if (!canShootOver && coverCount >= 3) return 0.1; // too enclosed
            return 0.5 + (coverCount * 0.15); // 0.65 for 1 cover block, 0.8 for 2
        }

        @Override
        public void start() {
            atCoverFor = 0;
            if (coverPos != null) {
                mob.getNavigation().moveTo(
                    coverPos.getX()+0.5, coverPos.getY(), coverPos.getZ()+0.5, 1.0);
            }
        }

        @Override
        public void tick() {
            if (coverPos == null) return;
            // If at cover position, stop moving and let ranged attack goal handle shooting
            if (mob.blockPosition().closerThan(coverPos, 3.0)) {
                mob.getNavigation().stop();
                // Face the target
                if (mob.getTarget() != null) {
                    mob.getLookControl().setLookAt(mob.getTarget(),
                        30.0f, mob.getMaxHeadXRot());
                }
            } else {
                // Still moving to cover
                if (atCoverFor % 20 == 0) {
                    mob.getNavigation().moveTo(
                        coverPos.getX()+0.5, coverPos.getY(), coverPos.getZ()+0.5, 1.0);
                }
            }
        }

        @Override
        public void stop() {
            cooldown = REPOSITION_CD;
            if (coverPos != null && mob.blockPosition().closerThan(coverPos, 3.0)) {
                // Stayed at cover successfully - longer cooldown before repositioning
                cooldown = REPOSITION_CD * 2;
            }
        }
    }

    // -------------------------------------------------------------------------
    // LeadShotGoal: predictive aiming for sprinting players
    // Applied as a tick event adjustment, not a separate Goal
    // Called from server tick when skeleton is about to shoot
    // -------------------------------------------------------------------------

    /**
     * Apply predictive shot adjustment.
     * Called from updateSearchState when skeleton has LOS on sprinting player.
     * 30% chance to activate. Adjusts look direction slightly ahead.
     */
    public static void applyLeadShot(AbstractSkeleton skeleton, Player player) {
        if (!player.isSprinting()) return;
        double dist = skeleton.distanceTo(player);
        if (dist < 8.0) return; // too close, lead shot not needed

        // 30% chance to apply lead shot
        if (skeleton.getRandom().nextFloat() > 0.3f) return;

        // Arrow travel time estimate: ~1.5 blocks per tick at vanilla speed
        double travelTicks = dist / 1.5;

        // Player velocity (blocks per tick)
        double pvx = player.getDeltaMovement().x;
        double pvz = player.getDeltaMovement().z;

        // Predicted position
        double predX = player.getX() + pvx * travelTicks;
        double predY = player.getY() + player.getDeltaMovement().y * travelTicks;
        double predZ = player.getZ() + pvz * travelTicks;

        // Adjust look toward predicted position
        skeleton.getLookControl().setLookAt(predX, predY, predZ,
            skeleton.getMaxHeadYRot(), skeleton.getMaxHeadXRot());
    }

    // -------------------------------------------------------------------------
    // Detection goals
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

    // -------------------------------------------------------------------------
    // Flank, Search, Patrol goals
    // -------------------------------------------------------------------------

    static class FlankGoal extends Goal {
        private final Mob mob;
        private BlockPos flankTarget = null;
        private int duration = 0;
        private static final int MAX_DURATION = 200;

        public FlankGoal(Mob mob) {
            this.mob = mob; setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (mob.getTarget() == null) return false;
            var tag = mob.getPersistentData();
            if (!tag.contains(NBT_FLEE_COUNT) || tag.getInt(NBT_FLEE_COUNT) < FLANK_THRESHOLD) return false;
            if (!tag.contains(NBT_FLEE_DX)) return false;
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            LivingEntity target = mob.getTarget();
            List<Zombie> chasers = sl.getEntitiesOfClass(Zombie.class,
                mob.getBoundingBox().inflate(20.0),
                z -> z != mob && target.equals(z.getTarget()));
            if (chasers.size() < 2) return false;
            int myCount = tag.getInt(NBT_FLEE_COUNT);
            for (Zombie z : chasers) {
                int oc = z.getPersistentData().contains(NBT_FLEE_COUNT) ? z.getPersistentData().getInt(NBT_FLEE_COUNT) : 0;
                if (oc > myCount) return false;
            }
            float fdx = tag.getFloat(NBT_FLEE_DX), fdz = tag.getFloat(NBT_FLEE_DZ);
            double fx = target.getX() + (-fdz)*8, fz = target.getZ() + fdx*8;
            int ny = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int)fx, (int)fz);
            if (ny <= 0) return false;
            BlockPos c = new BlockPos((int)fx, ny, (int)fz);
            var path = mob.getNavigation().createPath(c, 1);
            if (path == null || !path.canReach()) return false;
            flankTarget = c; return true;
        }

        @Override
        public boolean canContinueToUse() {
            return mob.getTarget() != null && flankTarget != null
                && duration < MAX_DURATION
                && !mob.blockPosition().closerThan(flankTarget, 3.0);
        }

        @Override
        public void start() { duration = 0; }

        @Override
        public void tick() {
            duration++;
            if (flankTarget != null)
                mob.getNavigation().moveTo(flankTarget.getX()+0.5, flankTarget.getY(), flankTarget.getZ()+0.5, 1.2);
        }

        @Override
        public void stop() { flankTarget = null; duration = 0; }
    }

    static class AdvancedSearchGoal extends Goal {
        private final Mob mob;
        private final double speed;
        private BlockPos target = null;
        private int localTick = 0, stuckTicks = 0;
        private double lastX = Double.MIN_VALUE, lastZ;
        private static final int STUCK_THRESHOLD = 60;

        public AdvancedSearchGoal(Mob mob, double speed) {
            this.mob = mob; this.speed = speed; setFlags(EnumSet.of(Flag.MOVE));
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
            mob.setAggressive(false); pickTarget();
        }

        @Override
        public void tick() {
            localTick++; mob.setAggressive(false);
            if (localTick % 10 == 0) {
                double dx = mob.getX()-lastX, dz = mob.getZ()-lastZ;
                if (dx*dx+dz*dz < 0.01) stuckTicks += 10;
                else { stuckTicks = 0; lastX = mob.getX(); lastZ = mob.getZ(); }
                if (stuckTicks >= STUCK_THRESHOLD) {
                    stuckTicks = 0;
                    if (localTick > 200) { clearSearch(mob.getPersistentData()); return; }
                    pickTarget(); return;
                }
            }
            int state = mob.getPersistentData().getInt(NBT_SEARCH_STATE);
            if (target == null || mob.blockPosition().closerThan(target, 2.0) || localTick % 80 == 0) pickTarget();
            if (target != null) {
                double spd = state == 1 ? speed*0.7 : speed*0.5;
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
                double angle = mob.getRandom().nextDouble()*Math.PI*2;
                double dist = radius*(0.4+mob.getRandom().nextDouble()*0.6);
                int nx=(int)(lx+Math.cos(angle)*dist), nz=(int)(lz+Math.sin(angle)*dist);
                int ny=mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,nx,nz);
                if (ny<=0||Math.abs(ny-mob.getBlockY())>5) continue;
                BlockPos c=new BlockPos(nx,ny,nz);
                var path=mob.getNavigation().createPath(c,1);
                if (path!=null&&path.canReach()) { target=c; return; }
            }
            target = null;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop(); mob.setAggressive(false);
            target=null; localTick=0; stuckTicks=0; lastX=Double.MIN_VALUE;
        }
    }

    static class MemoryPatrolGoal extends Goal {
        private final Mob mob;
        private BlockPos destination = null;
        private int patrolTicks = 0;
        private static final int PATROL_TIMEOUT = 1200;
        private static final double PATROL_SPEED = 0.4;

        public MemoryPatrolGoal(Mob mob) {
            this.mob = mob; setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (mob.getTarget() != null) return false;
            var tag = mob.getPersistentData();
            if (tag.getInt(NBT_SEARCH_STATE) > 0 || tag.contains(NBT_LAST_X)) return false;
            if (mob.level().dimension() != Level.OVERWORLD) return false;
            if (!(mob.level() instanceof ServerLevel sl)) return false;
            long gt = sl.getGameTime();
            if (tag.contains(NBT_MEM_CD) && gt < tag.getLong(NBT_MEM_CD)) return false;
            BlockPos hot = PlayerMemoryData.get(sl.getServer().overworld())
                .getNearestHotSpot(mob.getBlockX(), mob.getBlockZ(), MEMORY_PATROL_RADIUS);
            if (hot == null) return false;
            int ny = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hot.getX(), hot.getZ());
            if (ny <= 0) return false;
            BlockPos resolved = new BlockPos(hot.getX(), ny, hot.getZ());
            if (mob.blockPosition().closerThan(resolved, 8.0)) return false;
            var path = mob.getNavigation().createPath(resolved, 1);
            if (path == null || !path.canReach()) return false;
            destination = resolved; return true;
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.getTarget() != null) return false;
            var tag = mob.getPersistentData();
            if (tag.getInt(NBT_SEARCH_STATE) > 0) return false;
            return destination != null && patrolTicks < PATROL_TIMEOUT
                && !mob.blockPosition().closerThan(destination, 3.0);
        }

        @Override
        public void start() {
            patrolTicks = 0; mob.setAggressive(false);
            if (destination != null)
                mob.getNavigation().moveTo(destination.getX()+0.5, destination.getY(), destination.getZ()+0.5, PATROL_SPEED);
        }

        @Override
        public void tick() {
            patrolTicks++; mob.setAggressive(false);
            if (patrolTicks % 40 == 0 && destination != null)
                mob.getNavigation().moveTo(destination.getX()+0.5, destination.getY(), destination.getZ()+0.5, PATROL_SPEED);
        }

        @Override
        public void stop() {
            mob.getNavigation().stop(); mob.setAggressive(false);
            destination = null; patrolTicks = 0;
            if (mob.level() instanceof ServerLevel sl)
                mob.getPersistentData().putLong(NBT_MEM_CD, sl.getGameTime() + MEMORY_PATROL_COOLDOWN);
        }
    }

    static class SmartBreakDoorGoal extends BreakDoorGoal {
        public SmartBreakDoorGoal(Mob mob) { super(mob, 60, d -> d == Difficulty.HARD); }
        @Override public boolean canUse() { return mob.getTarget() != null && super.canUse(); }
        @Override public boolean canContinueToUse() { return mob.getTarget() != null && super.canContinueToUse(); }
    }

    static class SafeKeepDistanceGoal extends Goal {
        private final Mob mob; private final double minSq, preferSq;
        public SafeKeepDistanceGoal(Mob mob, double min, double prefer) {
            this.mob=mob; minSq=min*min; preferSq=prefer*prefer; setFlags(EnumSet.of(Flag.MOVE));
        }
        @Override public boolean canUse() { LivingEntity t=mob.getTarget(); return t!=null&&mob.distanceToSqr(t)<minSq; }
        @Override public boolean canContinueToUse() { LivingEntity t=mob.getTarget(); return t!=null&&mob.distanceToSqr(t)<preferSq; }
        @Override public void tick() {
            LivingEntity t=mob.getTarget(); if(t==null) return;
            double dx=mob.getX()-t.getX(),dz=mob.getZ()-t.getZ(),ls=dx*dx+dz*dz;
            if(ls<0.0001) return;
            double inv=1.0/Math.sqrt(ls), tx=mob.getX()+dx*inv*3, tz=mob.getZ()+dz*inv*3;
            int ty=mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)tx,(int)tz);
            if(Math.abs(ty-mob.getBlockY())>4) return;
            mob.getNavigation().moveTo(tx,ty,tz,1.0);
        }
    }
}
