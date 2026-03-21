package com.extremedifficulty;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
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

    private static final double FACE_BLOCK  = 0.55;
    private static final double DIFFRACTION = 0.75;
    private static final int    MAX_FACES   = 8;

    @SubscribeEvent
    public void onProjectileImpact(ProjectileImpactEvent event) {
        Entity proj = event.getEntity();
        if (proj.level().isClientSide()) return;
        if (!(proj.level() instanceof ServerLevel sl)) return;
        boolean isArrow   = proj instanceof AbstractArrow;
        boolean isTrident = proj instanceof ThrownTrident;
        if (!isArrow && !isTrident) return;
        Entity owner = isArrow ? ((AbstractArrow)proj).getOwner()
                               : ((ThrownTrident)proj).getOwner();
        triggerSound(sl, proj.position(), R_ARROW_IMPACT, owner);
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (!(victim.level() instanceof ServerLevel sl)) return;
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof Player)) return;
        triggerSound(sl, victim.position(), R_WEAPON_HIT, (Player) attacker);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide()) return;
        if (!(dead.level() instanceof ServerLevel sl)) return;
        triggerSound(sl, dead.position(), R_DEATH, event.getSource().getEntity());
    }

    @SubscribeEvent
    public void onLivingFall(LivingFallEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (!(entity instanceof Player)) return;
        Player player = (Player) entity;
        if (!(player.level() instanceof ServerLevel sl)) return;
        if (event.getDistance() <= 3.0f) return;
        BlockState bs = sl.getBlockState(player.blockPosition().below());
        if (bs.getBlock() instanceof BedBlock || bs.getBlock() instanceof SlimeBlock
         || bs.getBlock() instanceof HoneyBlock || bs.getBlock() instanceof WoolCarpetBlock) return;
        triggerSound(sl, player.position(), R_FALL, player);
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (event.getUseBlock() == net.minecraftforge.eventbus.api.Event.Result.DENY) return;
        triggerSound(sl, event.getEntity().position(), R_INTERACT, event.getEntity());
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        Player player = event.getPlayer();
        if (player == null) return;
        triggerSound(sl, Vec3.atCenterOf(event.getPos()), R_BLOCK_EDIT, player);
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        double radius = event.getPlacedBlock().getBlock() instanceof FireBlock
            ? R_FIRE : R_BLOCK_EDIT;
        triggerSound(sl, Vec3.atCenterOf(event.getPos()), radius, player);
    }

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

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        triggerSound(sl, event.getExplosion().getPosition(),
            R_EXPLOSION, event.getExplosion().getIndirectSourceEntity());
    }

    // -------------------------------------------------------------------------
    // CORE
    // Sound NEVER sets mob target directly.
    // It only tells the mob WHERE to investigate (NBT search coords).
    // DetectionGoal (which has LOS check) will set the actual target
    // when the mob arrives and sees the player.
    // This prevents attacking through walls.
    // -------------------------------------------------------------------------
    public static void triggerSound(ServerLevel level, Vec3 soundPos,
                                     double baseRadius, Entity source) {
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

            // Apply occlusion
            double effectiveRadius = distSq < 9.0
                ? baseRadius
                : computeOcclusion(level, soundPos, mob.getEyePosition(), baseRadius);

            if (distSq > effectiveRadius * effectiveRadius) return;

            // FIX: NEVER setTarget from sound - always just send mob to investigate.
            // mob will aggro naturally when DetectionGoal (with LOS check) fires.
            var tag = mob.getPersistentData();
            tag.putDouble(MobAIHandler.NBT_LAST_X, soundPos.x);
            tag.putDouble(MobAIHandler.NBT_LAST_Y, soundPos.y);
            tag.putDouble(MobAIHandler.NBT_LAST_Z, soundPos.z);
            tag.putInt(MobAIHandler.NBT_SEARCH_STATE, 1);
            tag.putInt(MobAIHandler.NBT_SEARCH_TICKS, 0);
        });
    }

    // -------------------------------------------------------------------------
    // 3D Occlusion with diffraction
    // -------------------------------------------------------------------------
    private static double computeOcclusion(ServerLevel level, Vec3 from,
                                            Vec3 to, double baseRadius) {
        int directBlocks = countBlockedFaces(level, from, to);
        if (directBlocks == 0) return baseRadius;

        // Build perpendiculars for offset paths
        Vec3 dir = to.subtract(from).normalize();
        Vec3 up    = new Vec3(0, 1, 0);
        Vec3 right = dir.cross(up).normalize();
        if (right.lengthSqr() < 0.01)
            right = dir.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 upPerp = right.cross(dir).normalize();

        int bestBlocks = directBlocks;

        for (double offset : new double[]{2.0, 1.5}) {
            Vec3 mid = from.add(to).scale(0.5);
            Vec3[] mids = {
                mid.add(upPerp.scale(offset)),
                mid.add(right.scale(offset)),
                mid.add(right.scale(-offset)),
                mid.add(upPerp.scale(offset * 0.7)).add(right.scale(offset * 0.7)),
            };
            for (Vec3 mp : mids) {
                int total = countBlockedFaces(level, from, mp)
                          + countBlockedFaces(level, mp, to);
                if (total < bestBlocks) bestBlocks = total;
            }
        }

        if (bestBlocks == 0)        return baseRadius * DIFFRACTION;
        if (bestBlocks >= MAX_FACES) return baseRadius * 0.03;

        if (bestBlocks < directBlocks) {
            double df = Math.pow(FACE_BLOCK, directBlocks);
            double af = Math.pow(FACE_BLOCK, bestBlocks) * DIFFRACTION;
            return baseRadius * Math.max(df, af);
        }

        return baseRadius * Math.pow(FACE_BLOCK, directBlocks);
    }

    private static int countBlockedFaces(ServerLevel level, Vec3 from, Vec3 to) {
        var clip = level.clip(new ClipContext(
            from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        if (clip.getType() == HitResult.Type.MISS) return 0;

        Vec3 dir = to.subtract(from);
        double dist = dir.length();
        if (dist < 0.01) return 0;
        Vec3 step = dir.normalize().scale(0.5);
        int solid = 0;
        BlockPos last = null;
        int maxSteps = Math.min((int)(dist / 0.5) + 1, MAX_FACES * 2);

        for (int i = 1; i <= maxSteps && solid < MAX_FACES; i++) {
            BlockPos bp = BlockPos.containing(from.add(step.scale(i)));
            if (bp.equals(last)) continue;
            last = bp;
            if (level.getBlockState(bp).isSolidRender(level, bp)) solid++;
        }
        return solid;
    }
}
