package com.extremedifficulty;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class SoundAlertHandler {

    // Explosion - very loud, attracts everything nearby
    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        Vec3 pos = event.getExplosion().getPosition();
        Entity source = event.getExplosion().getIndirectSourceEntity();

        // Explosions attract mobs including creepers (it's chaos)
        MobAIHandler.attractMobsToSound(serverLevel, pos,
            MobAIHandler.SOUND_EXPLOSION_RADIUS_PUBLIC, source, true);
    }
}
