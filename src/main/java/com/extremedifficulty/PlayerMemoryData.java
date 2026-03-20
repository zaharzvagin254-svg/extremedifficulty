package com.extremedifficulty;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Stores weighted player positions so mobs can patrol known player locations.
 * Persists across server restarts and mob despawns.
 * Only tracks Overworld positions.
 */
public class PlayerMemoryData extends SavedData {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String DATA_NAME = "extremedifficulty_memory";

    // Max hot spots stored per player
    private static final int MAX_POSITIONS = 8;
    // Positions closer than this are merged (same "area")
    private static final int MERGE_RADIUS  = 16;
    // Weight added when player is seen at a position
    private static final float WEIGHT_ADD  = 0.2f;
    // Starting weight for a new position
    private static final float WEIGHT_NEW  = 0.3f;
    // Max weight cap
    private static final float WEIGHT_MAX  = 1.0f;
    // Weight removed per game day (20 min real time)
    private static final float WEIGHT_DECAY = 0.1f;

    // UUID string -> list of weighted positions
    private final Map<String, List<WeightedPos>> memory = new HashMap<>();

    // -------------------------------------------------------------------------
    // SavedData API
    // -------------------------------------------------------------------------

    public static PlayerMemoryData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
            PlayerMemoryData::load,
            PlayerMemoryData::new,
            DATA_NAME
        );
    }

    public static PlayerMemoryData load(CompoundTag tag) {
        PlayerMemoryData data = new PlayerMemoryData();
        CompoundTag players = tag.getCompound("players");
        for (String uuid : players.getAllKeys()) {
            ListTag list = players.getList(uuid, Tag.TAG_COMPOUND);
            List<WeightedPos> positions = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                positions.add(new WeightedPos(
                    entry.getInt("x"),
                    entry.getInt("z"),
                    entry.getFloat("w")
                ));
            }
            if (!positions.isEmpty()) {
                data.memory.put(uuid, positions);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag players = new CompoundTag();
        for (Map.Entry<String, List<WeightedPos>> entry : memory.entrySet()) {
            ListTag list = new ListTag();
            for (WeightedPos pos : entry.getValue()) {
                CompoundTag p = new CompoundTag();
                p.putInt("x", pos.x);
                p.putInt("z", pos.z);
                p.putFloat("w", pos.weight);
                list.add(p);
            }
            players.put(entry.getKey(), list);
        }
        tag.put("players", players);
        return tag;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Record player's current position.
     * Called every 30 seconds by PlayerPositionTracker.
     */
    public void recordPosition(java.util.UUID uuid, int x, int z) {
        String key = uuid.toString();
        List<WeightedPos> positions = memory.computeIfAbsent(key, k -> new ArrayList<>());

        // Find existing position within merge radius
        WeightedPos nearest = null;
        int nearestDistSq = MERGE_RADIUS * MERGE_RADIUS;
        for (WeightedPos pos : positions) {
            int dx = pos.x - x, dz = pos.z - z;
            int distSq = dx*dx + dz*dz;
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = pos;
            }
        }

        if (nearest != null) {
            // Update existing - increase weight
            nearest.weight = Math.min(nearest.weight + WEIGHT_ADD, WEIGHT_MAX);
            // Nudge position toward current location (weighted average)
            nearest.x = (nearest.x + x) / 2;
            nearest.z = (nearest.z + z) / 2;
        } else {
            // New position
            if (positions.size() >= MAX_POSITIONS) {
                // Remove lightest entry to make room
                positions.sort(Comparator.comparingDouble(p -> p.weight));
                positions.remove(0);
            }
            positions.add(new WeightedPos(x, z, WEIGHT_NEW));
        }

        setDirty();
    }

    /**
     * Decay all weights by one game day's worth.
     * Called once per game day by PlayerPositionTracker.
     * Removes positions that have faded completely.
     */
    public void decayAll() {
        boolean changed = false;
        Iterator<Map.Entry<String, List<WeightedPos>>> mapIter = memory.entrySet().iterator();
        while (mapIter.hasNext()) {
            Map.Entry<String, List<WeightedPos>> entry = mapIter.next();
            List<WeightedPos> positions = entry.getValue();
            positions.removeIf(pos -> {
                pos.weight -= WEIGHT_DECAY;
                return pos.weight <= 0;
            });
            if (positions.isEmpty()) {
                mapIter.remove();
            }
            changed = true;
        }
        if (changed) setDirty();
    }

    /**
     * Find the nearest hot spot to a given position, within maxRadius blocks.
     * Returns null if nothing found.
     * Used by MemoryPatrolGoal to pick patrol destination.
     */
    public BlockPos getNearestHotSpot(int fromX, int fromZ, int maxRadius) {
        int maxRadiusSq = maxRadius * maxRadius;
        BlockPos best = null;
        float bestScore = -1;

        for (List<WeightedPos> positions : memory.values()) {
            for (WeightedPos pos : positions) {
                int dx = pos.x - fromX, dz = pos.z - fromZ;
                int distSq = dx*dx + dz*dz;
                if (distSq > maxRadiusSq) continue;

                // Score: weight / distance - prefer heavy nearby spots
                float score = pos.weight / (float) Math.max(1, Math.sqrt(distSq));
                if (score > bestScore) {
                    bestScore = score;
                    best = new BlockPos(pos.x, 0, pos.z); // Y resolved at runtime
                }
            }
        }
        return best;
    }

    /**
     * Get all hot spots within radius, sorted by weight descending.
     * Used for patrol planning.
     */
    public List<BlockPos> getHotSpotsNear(int fromX, int fromZ, int maxRadius) {
        int maxRadiusSq = maxRadius * maxRadius;
        List<BlockPos> result = new ArrayList<>();

        for (List<WeightedPos> positions : memory.values()) {
            for (WeightedPos pos : positions) {
                int dx = pos.x - fromX, dz = pos.z - fromZ;
                if (dx*dx + dz*dz <= maxRadiusSq) {
                    result.add(new BlockPos(pos.x, 0, pos.z));
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Inner class
    // -------------------------------------------------------------------------

    static class WeightedPos {
        int x, z;
        float weight;

        WeightedPos(int x, int z, float weight) {
            this.x = x; this.z = z; this.weight = weight;
        }
    }
}
