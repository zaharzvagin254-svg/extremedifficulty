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
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.EnumSet;
import java.util.List;

public class MobAIHandler {

    private static final String NBT_AI_SETUP     = "ed_ai_setup";

    // Detection range (must SEE player first)
    private static final double DETECT_RANGE_NORMAL = 18.0;
    private static final double DETECT_RANGE_SNEAK  = 10.0;

    // Follow range (after aggro, chases much further)
    private static final double FOLLOW_RANGE_NIGHT  = 48.0;
    private static final double FOLLOW_RANGE_DAY    = 32.0;

    // Search state durations (in ticks, with per-mob randomness +-20%)
    private static final int SEARCH_ACTIVE_TICKS  = 600; // 30 sec - circles last pos
    private static final int SEARCH_PASSIVE_TICKS = 600; // 30 sec - waits near area
    // After both = 60 sec total, mob gives up

    // Sound attraction radius - public for SoundAlertHandler
    public static final double SOUND_STEP_RADIUS             = 8.0;
    public static final double SOUND_INTERACT_RADIUS         = 12.0;
    public static final double SOUND_ARROW_RADIUS            = 20.0;
    public static final double SOUND_EXPLOSION_RADIUS_PUBLIC = 40.0;
    public static final double SOUND_BELL_RADIUS             = 50.0;

    // NBT keys for search state
    private static final String NBT_SEARCH_STATE    = "ed_search"; // 0=none 1=active 2=passive
    private static final String NBT_SEARCH_TICKS    = "ed_sticks";
    private static final String NBT_LAST_X          = "ed_lx";
    private static final String NBT_LAST_Y          = "ed_ly";
    private static final String NBT_LAST_Z          = "ed_lz";
    private static final String NBT_SEARCH_DURATION = "ed_sdur";  // randomised per mob

    // -------------------------------------------------------------------------
    // ENTITY JOIN
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)) return;
        if (living.getPersistentData().getBoolean(NBT_AI_SETUP)) return;

        if (entity instanceof CaveSpider caveSpider) {
            setupMob(caveSpider, true,  false, false);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        } else if (entity instanceof Zombie zombie && !(zombie instanceof ZombifiedPiglin)) {
            setupZombie(zombie);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        } else if (entity.getClass() == Skeleton.class) {
            setupMob((Skeleton) entity, true, true, false);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        } else if (entity instanceof Spider spider) {
            setupMob(spider, true, false, false);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        } else if (entity instanceof Creeper creeper) {
            // Creeper does NOT react to sounds - only sight
            setupMob(creeper, false, false, true);
            living.getPersistentData().putBoolean(NBT_AI_SETUP, true);
        }
    }

    private void setupZombie(Zombie zombie) {
        zombie.goalSelector.getAvailableGoals().removeIf(
            w -> w.getGoal() instanceof BreakDoorGoal
        );
        zombie.goalSelector.addGoal(1, new FastBreakDoorGoal(zombie, 120));
        zombie.goalSelector.addGoal(4, new AdvancedSearchGoal(zombie, 1.0));
        zombie.targetSelector.addGoal(3, new DetectionGoal(zombie, true));
        zombie.getNavigation().setMaxVisitedNodesMultiplier(2.0f);
        assignSearchDuration(zombie);
    }

    private void setupMob(Mob mob, boolean hearsSounds, boolean keepsDistance, boolean sightOnly) {
        mob.goalSelector.addGoal(4, new AdvancedSearchGoal(mob, sightOnly ? 1.0 : 1.1));
        if (!sightOnly) {
            mob.targetSelector.addGoal(3, new DetectionGoal(mob, true));
        } else {
            mob.targetSelector.addGoal(3, new DetectionGoal(mob, false));
        }
        if (keepsDistance) {
            mob.goalSelector.addGoal(2, new SafeKeepDistanceGoal(mob, 8.0, 12.0));
        }
        mob.getNavigation().setMaxVisitedNodesMultiplier(mob instanceof Spider ? 3.0f : 2.0f);
        assignSearchDuration(mob);
    }

    // Each mob gets a slightly randomised search duration so they dont all give up together
    private void assignSearchDuration(Mob mob) {
        double rand = 0.8 + mob.getRandom().nextDouble() * 0.4; // 0.8 - 1.2
        int duration = (int)(SEARCH_ACTIVE_TICKS * rand);
        mob.getPersistentData().putInt(NBT_SEARCH_DURATION, duration);
    }

    // -------------------------------------------------------------------------
    // GROUP ALERT ON HURT
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        if (zombie instanceof ZombifiedPiglin) return;
        if (zombie.level().isClientSide()) return;
        if (!(zombie.level() instanceof ServerLevel serverLevel)) return;

        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof LivingEntity target)) return;
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

    // -------------------------------------------------------------------------
    // SOUND EVENTS - attract mobs
    // -------------------------------------------------------------------------

    // Player opens chest, crafting table etc
    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        Player player = event.getEntity();
        if (player.isCrouching()) return; // sneaking = silent

        attractMobsToSound(serverLevel, player.position(), SOUND_INTERACT_RADIUS, player, false);
    }

    // Block break (mining)
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.isCrouching()) return;

        attractMobsToSound(serverLevel, Vec3.atCenterOf(event.getPos()),
            SOUND_INTERACT_RADIUS, player, false);
    }

    // -------------------------------------------------------------------------
    // SERVER TICK - main AI update loop
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getGameTime() % 10 != 0) return; // every 10 ticks = 0.5 sec

        boolean isNight = isNight(serverLevel);

        serverLevel.getEntities().getAll().forEach(entity -> {
            if (!(entity instanceof Mob mob)) return;

            // Update follow range based on day/night and aggro state
            updateFollowRange(mob, isNight);

            // Handle target loss and search state
            updateSearchState(mob, serverLevel);
        });
    }

    private void updateFollowRange(Mob mob, boolean isNight) {
        var attr = mob.getAttribute(
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        if (attr == null) return;
        double range = isNight ? FOLLOW_RANGE_NIGHT : FOLLOW_RANGE_DAY;
        attr.setBaseValue(range);
    }

    private void updateSearchState(Mob mob, ServerLevel level) {
        var tag = mob.getPersistentData();
        int state = tag.getInt(NBT_SEARCH_STATE);

        // If mob has active target - update last known pos and reset search
        if (mob.getTarget() instanceof Player player) {
            if (mob.hasLineOfSight(player)) {
                // Can see player - update last known position
                tag.putDouble(NBT_LAST_X, player.getX());
                tag.putDouble(NBT_LAST_Y, player.getY());
                tag.putDouble(NBT_LAST_Z, player.getZ());
                tag.putInt(NBT_SEARCH_STATE, 0);
                tag.putInt(NBT_SEARCH_TICKS, 0);
            } else {
                // Lost line of sight - check if should lose target
                boolean canHear = canHearPlayer(mob, player);
                if (!canHear) {
                    // Lost sight AND cant hear - enter search state
                    mob.setTarget(null);
                    tag.putInt(NBT_SEARCH_STATE, 1); // active search
                    tag.putInt(NBT_SEARCH_TICKS, 0);
                }
                // If can still hear - keep target but dont chase past follow range
            }
            return;
        }

        // No target - process search states
        if (state == 0) return; // idle, nothing to do

        int ticks = tag.getInt(NBT_SEARCH_TICKS) + 10; // += tick interval
        tag.putInt(NBT_SEARCH_TICKS, ticks);

        int activeDuration = tag.contains(NBT_SEARCH_DURATION)
            ? tag.getInt(NBT_SEARCH_DURATION) : SEARCH_ACTIVE_TICKS;

        if (state == 1) {
            // Active search - circles last known position
            // Transition to passive after activeDuration
            if (ticks >= activeDuration) {
                tag.putInt(NBT_SEARCH_STATE, 2);
                tag.putInt(NBT_SEARCH_TICKS, 0);
            }

            // Check if another player is nearby to switch to
            if (tag.contains(NBT_LAST_X)) {
                Player nearby = level.getNearestPlayer(mob, DETECT_RANGE_NORMAL);
                if (nearby != null && !nearby.isCreative() && !nearby.isSpectator()
                    && mob.hasLineOfSight(nearby)) {
                    mob.setTarget(nearby);
                    tag.putInt(NBT_SEARCH_STATE, 0);
                    return;
                }
            }

        } else if (state == 2) {
            // Passive wait - lingers near area
            int passiveDuration = (int)(activeDuration * 1.0);
            if (ticks >= passiveDuration) {
                // Give up completely - clear search data
                tag.putInt(NBT_SEARCH_STATE, 0);
                tag.putInt(NBT_SEARCH_TICKS, 0);
                tag.remove(NBT_LAST_X);
                tag.remove(NBT_LAST_Y);
                tag.remove(NBT_LAST_Z);
            }
        }
    }

    // Check if mob can hear player (walking noise, not sneaking)
    private boolean canHearPlayer(Mob mob, Player player) {
        if (player.isCrouching()) return false; // sneaking = silent
        double hearRadius = mob instanceof Zombie ? 10.0 : 8.0;
        return mob.distanceToSqr(player) < hearRadius * hearRadius;
    }

    // Attract mobs to a sound source
    public static void attractMobsToSound(ServerLevel level, Vec3 soundPos,
                                           double radius, Entity source,
                                           boolean includeCreepers) {
        level.getEntitiesOfClass(Mob.class,
            new net.minecraft.world.phys.AABB(
                soundPos.x - radius, soundPos.y - radius, soundPos.z - radius,
                soundPos.x + radius, soundPos.y + radius, soundPos.z + radius),
            mob -> {
                if (mob.getTarget() != null) return false; // already targeting something
                if (!isSoundReactiveMob(mob)) return false;
                if (!includeCreepers && mob instanceof Creeper) return false;
                return mob.distanceToSqr(soundPos.x, soundPos.y, soundPos.z) < radius * radius;
            }
        ).forEach(mob -> {
            // If sound came from a player - target that player
            if (source instanceof Player p && !p.isCreative() && !p.isSpectator()) {
                mob.setTarget(p);
            } else {
                // Move toward sound position
                var tag = mob.getPersistentData();
                tag.putDouble(NBT_LAST_X, soundPos.x);
                tag.putDouble(NBT_LAST_Y, soundPos.y);
                tag.putDouble(NBT_LAST_Z, soundPos.z);
                tag.putInt(NBT_SEARCH_STATE, 1);
                tag.putInt(NBT_SEARCH_TICKS, 0);
            }
        });
    }

    private static boolean isSoundReactiveMob(Mob mob) {
        return mob instanceof Zombie || mob instanceof Skeleton
            || mob instanceof Spider || mob instanceof CaveSpider
            || mob instanceof Vindicator || mob instanceof Pillager;
    }

    private static boolean isNight(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }

    // -------------------------------------------------------------------------
    // GOALS
    // -------------------------------------------------------------------------

    /**
     * Detects player with proper range separation:
     * - Detection range: ~18 blocks normal, ~10 sneaking (requires LOS)
     * - After detected, FOLLOW_RANGE attribute handles chase distance
     */
    static class DetectionGoal extends NearestAttackableTargetGoal<Player> {

        private final boolean usesHearing;
        private final TargetingConditions normalConditions;
        private final TargetingConditions sneakConditions;
        // Hearing: no LOS required, shorter range
        private final TargetingConditions hearNormalConditions;
        private final TargetingConditions hearSneakConditions;

        public DetectionGoal(Mob mob, boolean usesHearing) {
            super(mob, Player.class, 10, true, false, null);
            this.usesHearing = usesHearing;
            this.normalConditions = TargetingConditions.forCombat()
                .range(DETECT_RANGE_NORMAL);
            this.sneakConditions = TargetingConditions.forCombat()
                .range(DETECT_RANGE_SNEAK);
            this.hearNormalConditions = TargetingConditions.forCombat()
                .range(10.0).ignoreLineOfSight();
            this.hearSneakConditions = TargetingConditions.forCombat()
                .range(4.0).ignoreLineOfSight();
        }

        @Override
        public boolean canUse() {
            Player nearest = mob.level().getNearestPlayer(mob, DETECT_RANGE_NORMAL);
            if (nearest == null || nearest.isCreative() || nearest.isSpectator()) return false;
            if (nearest.isInvisible()) return false;

            boolean sneaking = nearest.isCrouching();

            // Only at night for hearing
            boolean nightOrDim = isNight(mob.level());

            // Try sight detection first
            this.targetConditions = sneaking ? sneakConditions : normalConditions;
            if (super.canUse()) return true;

            // Try hearing detection (not for creepers, only at night)
            if (usesHearing && nightOrDim && !nearest.isInvisible()) {
                this.targetConditions = sneaking ? hearSneakConditions : hearNormalConditions;
                return super.canUse();
            }

            return false;
        }
    }

    /**
     * Advanced search goal with three states:
     * 1. Active search (0-30s): circles last known position
     * 2. Passive wait (30-60s): lingers near area, waits
     * 3. Give up: clears state
     */
    static class AdvancedSearchGoal extends Goal {

        private final Mob mob;
        private final double speed;
        private BlockPos currentTarget = null;
        private int localTick = 0;

        public AdvancedSearchGoal(Mob mob, double speed) {
            this.mob = mob;
            this.speed = speed;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (mob.getTarget() != null) return false;
            var tag = mob.getPersistentData();
            int state = tag.getInt(NBT_SEARCH_STATE);
            return state > 0 && tag.contains(NBT_LAST_X);
        }

        @Override
        public boolean canContinueToUse() {
            if (mob.getTarget() != null) return false;
            var tag = mob.getPersistentData();
            return tag.getInt(NBT_SEARCH_STATE) > 0 && tag.contains(NBT_LAST_X);
        }

        @Override
        public void start() {
            localTick = 0;
            pickNewTarget();
        }

        @Override
        public void tick() {
            localTick++;
            var tag = mob.getPersistentData();
            int state = tag.getInt(NBT_SEARCH_STATE);

            if (currentTarget == null
             || mob.blockPosition().closerThan(currentTarget, 2.0)
             || localTick % 60 == 0) { // re-pick every 3 sec
                pickNewTarget();
            }

            if (currentTarget != null) {
                if (state == 1) {
                    // Active: move at normal speed
                    mob.getNavigation().moveTo(
                        currentTarget.getX() + 0.5,
                        currentTarget.getY(),
                        currentTarget.getZ() + 0.5,
                        speed
                    );
                } else {
                    // Passive: slower, more aimless
                    mob.getNavigation().moveTo(
                        currentTarget.getX() + 0.5,
                        currentTarget.getY(),
                        currentTarget.getZ() + 0.5,
                        speed * 0.6
                    );
                }
            }
        }

        private void pickNewTarget() {
            var tag = mob.getPersistentData();
            if (!tag.contains(NBT_LAST_X)) return;

            double lx = tag.getDouble(NBT_LAST_X);
            double ly = tag.getDouble(NBT_LAST_Y);
            double lz = tag.getDouble(NBT_LAST_Z);
            int state = tag.getInt(NBT_SEARCH_STATE);

            // Active: wander 4-8 blocks around last pos
            // Passive: wander 8-16 blocks (more spread out)
            double radius = state == 1 ? 6.0 : 12.0;

            double angle = mob.getRandom().nextDouble() * Math.PI * 2;
            double dist  = radius * (0.5 + mob.getRandom().nextDouble() * 0.5);

            int nx = (int)(lx + Math.cos(angle) * dist);
            int nz = (int)(lz + Math.sin(angle) * dist);
            int ny = mob.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);

            if (ny > 0 && Math.abs(ny - mob.getBlockY()) <= 5) {
                currentTarget = new BlockPos(nx, ny, nz);
            }
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
            currentTarget = null;
            localTick = 0;
        }
    }

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
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) tx, (int) tz);
            if (Math.abs(ty - mob.getBlockY()) > 4) return;
            mob.getNavigation().moveTo(tx, ty, tz, 1.0);
        }
    }
}
