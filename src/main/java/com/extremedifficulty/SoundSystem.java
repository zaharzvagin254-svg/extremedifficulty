package com.extremedifficulty;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class SoundSystem {

    public static final double R_FOOTSTEP     = 10.0;
    public static final double R_INTERACT     = 10.0;
    public static final double R_BLOCK_EDIT   = 10.0;
    public static final double R_WEAPON_HIT   = 6.0;
    public static final double R_DEATH        = 12.0;
    public static final double R_FALL         = 10.0;
    public static final double R_ARROW_IMPACT = 10.0;
    public static final double R_POTION_FOOD  = 6.0;
    public static final double R_FIRE         = 7.0;
    public static final double R_EXPLOSION    = 50.0;
    public static final double R_BELL         = 50.0;

    // Occlusion: how much each SOLID block face reduces sound (0.0-1.0)
    // 0.5 = 50% reduction per solid face encountered
    // Reasoning: real sound loses ~6dB per doubling of distance through walls
    // We model each sealed face as -50% amplitude
    private static final double FACE_OCCLUSION = 0.50;

    // Arrow / trident IMPACT only
    @SubscribeEvent
    public void onProjectileImpact(ProjectileImpactEvent event) {
        Entity proj = event.getEntity();
        if (proj.level().isClientSide()) return;
        if (!(proj.level() instanceof ServerLevel sl)) return;
        boolean isArrow   = proj instanceof AbstractArrow;
        boolean isTrident = proj instanceof ThrownTrident;
        if (!isArrow && !isTrident) return;
        Entity owner = isArrow ? ((AbstractArrow) proj).getOwner()
                               : ((ThrownTrident) proj).getOwner();
        triggerSound(sl, proj.position(), R_ARROW_IMPACT, owner);
    }

    // Melee hit
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (!(victim.level() instanceof ServerLevel sl)) return;
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof Player)) return;
        triggerSound(sl, victim.position(), R_WEAPON_HIT, (Player) attacker);
    }

    // Death
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide()) return;
        if (!(dead.level() instanceof ServerLevel sl)) return;
        triggerSound(sl, dead.position(), R_DEATH, event.getSource().getEntity());
    }

    // Fall - ignore soft surfaces
    @SubscribeEvent
    public void onLivingFall(LivingFallEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (!(entity instanceof Player)) return;
        Player player = (Player) entity;
        if (!(player.level() instanceof ServerLevel sl)) return;
        if (event.getDistance() <= 3.0f) return;
        BlockState bs = sl.getBlockState(player.blockPosition().below());
        if (bs.getBlock() instanceof BedBlock
         || bs.getBlock() instanceof SlimeBlock
         || bs.getBlock() instanceof HoneyBlock
         || bs.getBlock() instanceof WoolCarpetBlock) return;
        triggerSound(sl, player.position(), R_FALL, player);
    }

    // Interact - sneak does NOT mute
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (event.getUseBlock() == net.minecraftforge.eventbus.api.Event.Result.DENY) return;
        triggerSound(sl, event.getEntity().position(), R_INTERACT, event.getEntity());
    }

    // Block break
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        Player player = event.getPlayer();
        if (player == null) return;
        triggerSound(sl, Vec3.atCenterOf(event.getPos()), R_BLOCK_EDIT, player);
    }

    // Block place
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        double radius = event.getPlacedBlock().getBlock() instanceof FireBlock
            ? R_FIRE : R_BLOCK_EDIT;
        triggerSound(sl, Vec3.atCenterOf(event.getPos()), radius, player);
    }

    // Potion / food
    @SubscribeEvent
    public void onItemUse(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        Player player = event.getEntity();
        var item = event.getItemStack().getItem();
        if (!event.getItemStack().isEdible()
         && !(item instanceof PotionItem)
         && !(item instanceof SplashPotionItem)
         && !(item instanceof LingeringPotionItem)) return;
        triggerSound(sl, player.position(), R_POTION_FOOD, player);
    }

    // Explosion
    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        triggerSound(sl, event.getExplosion().getPosition(),
            R_EXPLOSION, event.getExplosion().getIndirectSourceEntity());
    }

    // -------------------------------------------------------------------------
    // CORE - 3D sound propagation with realistic occlusion
    // -------------------------------------------------------------------------
    public static void triggerSound(ServerLevel level, Vec3 soundPos,
                                     double baseRadius, Entity source) {
        // Use sphere AABB (equal on all axes including Y)
        level.getEntitiesOfClass(
            net.minecraft.world.entity.Mob.class,
            new AABB(
                soundPos.x - baseRadius, soundPos.y - baseRadius, soundPos.z - baseRadius,
                soundPos.x + baseRadius, soundPos.y + baseRadius, soundPos.z + baseRadius),
            mob -> {
                if (mob instanceof Creeper) return false;
                if (!MobAIHandler.isSoundReactiveMob(mob)) return false;
                if (mob.getTarget() != null) return false;
                return true;
            }
        ).forEach(mob -> {
            double distSq = mob.distanceToSqr(soundPos.x, soundPos.y, soundPos.z);
            if (distSq > baseRadius * baseRadius) return;

            // Compute 3D occlusion
            double effectiveRadius = distSq < 9.0
                ? baseRadius // very close (< 3 blocks) - skip occlusion
                : computeOcclusion(level, soundPos, mob.getEyePosition(), baseRadius);

            if (distSq > effectiveRadius * effectiveRadius) return;

            if (source instanceof Player p && !p.isCreative() && !p.isSpectator()) {
                mob.setTarget(p);
            } else {
                var tag = mob.getPersistentData();
                tag.putDouble(MobAIHandler.NBT_LAST_X, soundPos.x);
                tag.putDouble(MobAIHandler.NBT_LAST_Y, soundPos.y);
                tag.putDouble(MobAIHandler.NBT_LAST_Z, soundPos.z);
                tag.putInt(MobAIHandler.NBT_SEARCH_STATE, 1);
                tag.putInt(MobAIHandler.NBT_SEARCH_TICKS, 0);
            }
        });
    }

    /**
     * Realistic 3D sound occlusion.
     *
     * Instead of counting blocks along a single ray (which is flat/2D biased),
     * we sample the 6 axis-aligned directions from source and measure how many
     * solid faces seal the path in each direction.
     *
     * Logic:
     * - Walk from sound source to mob in integer block steps
     * - For each block transition, check if the FACE between adjacent blocks is solid
     * - Each sealed face reduces amplitude by FACE_OCCLUSION
     * - Air gaps (open spaces) reset/reduce the occlusion count
     * - This naturally handles vertical sound propagation
     *
     * Performance: max 16 block steps, early exit if fully occluded
     */
    private static double computeOcclusion(ServerLevel level,
                                            Vec3 from, Vec3 to,
                                            double baseRadius) {
        // Integer block positions
        BlockPos fromPos = BlockPos.containing(from);
        BlockPos toPos   = BlockPos.containing(to);

        if (fromPos.equals(toPos)) return baseRadius; // same block = no occlusion

        // Step through blocks along the path using integer grid traversal
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 0.01) return baseRadius;

        // Normalize
        dx /= len; dy /= len; dz /= len;

        int sealedFaces   = 0;
        int openFaces     = 0;     // open transitions reset occlusion
        BlockPos prev     = fromPos;
        double t          = 0;
        double stepSize   = 0.8;   // slightly less than 1 block to catch all transitions
        int maxSteps      = Math.min((int)(len / stepSize) + 2, 20);

        for (int s = 1; s <= maxSteps; s++) {
            t = s * stepSize;
            if (t > len) break;

            BlockPos cur = BlockPos.containing(
                from.x + dx * t,
                from.y + dy * t,
                from.z + dz * t
            );

            if (cur.equals(prev)) continue;

            // Determine which face we crossed (dominant axis)
            int bx = cur.getX() - prev.getX();
            int by = cur.getY() - prev.getY();
            int bz = cur.getZ() - prev.getZ();

            Direction face = null;
            int abx = Math.abs(bx), aby = Math.abs(by), abz = Math.abs(bz);
            if (abx >= aby && abx >= abz) face = bx > 0 ? Direction.EAST  : Direction.WEST;
            else if (aby >= abz)          face = by > 0 ? Direction.UP    : Direction.DOWN;
            else                          face = bz > 0 ? Direction.SOUTH : Direction.NORTH;

            BlockState prevState = level.getBlockState(prev);
            BlockState curState  = level.getBlockState(cur);

            // A face is occluding if BOTH sides are solid at that face
            // This means sound is truly blocked (like inside a wall)
            boolean prevSolid = prevState.isFaceSturdy(level, prev, face);
            boolean curSolid  = curState.isFaceSturdy(level, cur, face.getOpposite());

            if (prevSolid && curSolid) {
                sealedFaces++;
            } else if (!prevSolid && !curSolid) {
                // Open air transition - sound travels freely
                // Partially cancel out previous occlusion (open space diffraction)
                openFaces++;
                if (openFaces >= 2 && sealedFaces > 0) {
                    sealedFaces = Math.max(0, sealedFaces - 1);
                    openFaces = 0;
                }
            }
            // One side solid = partial occlusion, counts as nothing (diffraction around edge)

            prev = cur;

            // Early exit: fully occluded (8 solid faces = nearly zero radius)
            if (sealedFaces >= 8) return baseRadius * 0.02;
        }

        if (sealedFaces == 0) return baseRadius;
        return baseRadius * Math.pow(FACE_OCCLUSION, sealedFaces);
    }
}
