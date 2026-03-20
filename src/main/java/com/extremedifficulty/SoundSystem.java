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

    // Sound radii
    public static final double R_FOOTSTEP     = 10.0; // updated
    public static final double R_INTERACT     = 10.0;
    public static final double R_BLOCK_EDIT   = 10.0;
    public static final double R_WEAPON_HIT   = 6.0;
    public static final double R_DEATH        = 12.0;
    public static final double R_FALL         = 10.0;
    public static final double R_ARROW_IMPACT = 10.0; // updated
    public static final double R_POTION_FOOD  = 6.0;
    public static final double R_FIRE         = 7.0;
    public static final double R_EXPLOSION    = 50.0;
    public static final double R_BELL         = 50.0;

    private static final double OCCLUSION_FACTOR    = 0.60;
    private static final int    MAX_OCCLUSION_STEPS = 8;

    // Arrow / trident IMPACT only
    @SubscribeEvent
    public void onProjectileImpact(ProjectileImpactEvent event) {
        Entity proj = event.getEntity();
        if (proj.level().isClientSide()) return;
        if (!(proj.level() instanceof ServerLevel sl)) return;
        double radius = 0;
        if (proj instanceof AbstractArrow)    radius = R_ARROW_IMPACT;
        else if (proj instanceof ThrownTrident) radius = R_ARROW_IMPACT;
        if (radius == 0) return;
        Entity owner = proj instanceof AbstractArrow a ? a.getOwner()
                     : proj instanceof ThrownTrident t ? t.getOwner() : null;
        triggerSound(sl, proj.position(), radius, owner);
    }

    // Melee hit
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (!(victim.level() instanceof ServerLevel sl)) return;
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof Player player)) return;
        triggerSound(sl, victim.position(), R_WEAPON_HIT, player);
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
        if (!(entity instanceof Player player)) return;
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
        triggerSound(sl, event.getEntity().position(), R_INTERACT, event.getEntity());
    }

    // Block break - sneak does NOT mute
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        triggerSound(sl, Vec3.atCenterOf(event.getPos()), R_BLOCK_EDIT, player);
    }

    // Block place - sneak does NOT mute
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (!(event.getEntity() instanceof Player player)) return;
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
        boolean isEdible = event.getItemStack().isEdible();
        boolean isPotion = item instanceof PotionItem
                        || item instanceof SplashPotionItem
                        || item instanceof LingeringPotionItem;
        if (!isEdible && !isPotion) return;
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
    // CORE - creepers NEVER react to sounds
    // Mobs that are already chasing someone are NOT distracted
    // -------------------------------------------------------------------------
    public static void triggerSound(ServerLevel level, Vec3 soundPos,
                                     double baseRadius, Entity source) {
        level.getEntitiesOfClass(
            net.minecraft.world.entity.Mob.class,
            new AABB(
                soundPos.x - baseRadius, soundPos.y - baseRadius, soundPos.z - baseRadius,
                soundPos.x + baseRadius, soundPos.y + baseRadius, soundPos.z + baseRadius),
            mob -> {
                // Creepers never react to sounds
                if (mob instanceof Creeper) return false;
                if (!MobAIHandler.isSoundReactiveMob(mob)) return false;
                // Sound does NOT distract mob if it is already chasing someone
                if (mob.getTarget() != null) return false;
                return true;
            }
        ).forEach(mob -> {
            double distSq = mob.distanceToSqr(soundPos.x, soundPos.y, soundPos.z);
            if (distSq > baseRadius * baseRadius) return;

            double effectiveRadius = computeOccludedRadius(
                level, soundPos, mob.getEyePosition(), baseRadius);
            if (distSq > effectiveRadius * effectiveRadius) return;

            // If source is a player - target directly
            if (source instanceof Player p && !p.isCreative() && !p.isSpectator()) {
                mob.setTarget(p);
            } else {
                // Move toward sound position using search system
                var tag = mob.getPersistentData();
                tag.putDouble(MobAIHandler.NBT_LAST_X, soundPos.x);
                tag.putDouble(MobAIHandler.NBT_LAST_Y, soundPos.y);
                tag.putDouble(MobAIHandler.NBT_LAST_Z, soundPos.z);
                tag.putInt(MobAIHandler.NBT_SEARCH_STATE, 1);
                tag.putInt(MobAIHandler.NBT_SEARCH_TICKS, 0);
            }
        });
    }

    // Compute radius after block occlusion
    private static double computeOccludedRadius(ServerLevel level,
                                                  Vec3 from, Vec3 to,
                                                  double baseRadius) {
        var clip = level.clip(new ClipContext(
            from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        if (clip.getType() == HitResult.Type.MISS) return baseRadius;

        Vec3 dir = to.subtract(from);
        double dist = dir.length();
        if (dist < 0.01) return baseRadius;
        Vec3 step = dir.normalize().scale(0.5);
        int solid = 0;
        BlockPos last = null;
        int maxSteps = Math.min((int)(dist / 0.5) + 1, MAX_OCCLUSION_STEPS * 2);

        for (int i = 1; i <= maxSteps && solid < MAX_OCCLUSION_STEPS; i++) {
            BlockPos bp = BlockPos.containing(from.add(step.scale(i)));
            if (bp.equals(last)) continue;
            last = bp;
            if (level.getBlockState(bp).isSolidRender(level, bp)) solid++;
        }

        return solid == 0 ? baseRadius : baseRadius * Math.pow(OCCLUSION_FACTOR, solid);
    }
}
