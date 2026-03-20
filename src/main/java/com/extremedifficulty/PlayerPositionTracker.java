package com.extremedifficulty;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Tracks player positions in the Overworld and feeds them to PlayerMemoryData.
 * Very lightweight - runs every 600 ticks (30 seconds).
 */
public class PlayerPositionTracker {

    // Record position every 600 ticks = 30 seconds
    private static final int RECORD_INTERVAL = 600;

    // Decay weight once per game day = 24000 ticks = 20 min real time
    private static final int DECAY_INTERVAL = 24000;

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel sl)) return;

        // Only track Overworld
        if (sl.dimension() != Level.OVERWORLD) return;

        long gt = sl.getGameTime();

        // Record player positions every 30 sec
        if (gt % RECORD_INTERVAL == 0) {
            PlayerMemoryData memory = PlayerMemoryData.get(sl);
            for (var player : sl.players()) {
                if (player.isCreative() || player.isSpectator()) continue;
                memory.recordPosition(
                    player.getUUID(),
                    player.getBlockX(),
                    player.getBlockZ()
                );
            }
        }

        // Decay weights once per game day
        if (gt % DECAY_INTERVAL == 0) {
            PlayerMemoryData.get(sl).decayAll();
        }
    }
}
