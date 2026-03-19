package com.extremedifficulty;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BloodMoonManager extends SavedData {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String DATA_NAME = "extremedifficulty_bloodmoon";

    public static final int FULL_MOONS_PER_BLOOD_MOON = 5;
    private static final double BUFF_PER_BLOOD_MOON   = 0.15;
    public static final double MAX_BUFF_MULT           = 3.0;

    private int fullMoonCount   = 0;
    private int bloodMoonCount  = 0;
    private double buffMult     = 1.0;
    private boolean nightHandled = false;

    // ─── SavedData API ────────────────────────────────────────────────────────

    public static BloodMoonManager get(ServerLevel overworldLevel) {
        return overworldLevel.getDataStorage().computeIfAbsent(
            BloodMoonManager::load,
            BloodMoonManager::new,
            DATA_NAME
        );
    }

    public static BloodMoonManager load(CompoundTag tag) {
        BloodMoonManager m = new BloodMoonManager();
        m.fullMoonCount  = tag.getInt("fullMoonCount");
        m.bloodMoonCount = tag.getInt("bloodMoonCount");
        m.buffMult       = tag.contains("buffMult") ? tag.getDouble("buffMult") : 1.0;
        m.nightHandled   = tag.getBoolean("nightHandled");
        return m;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("fullMoonCount",    fullMoonCount);
        tag.putInt("bloodMoonCount",   bloodMoonCount);
        tag.putDouble("buffMult",      buffMult);
        tag.putBoolean("nightHandled", nightHandled);
        return tag;
    }

    // ─── Обычная логика ───────────────────────────────────────────────────────

    public boolean onNightTick(ServerLevel overworldLevel) {
        if (nightHandled) return false;
        if (overworldLevel.getMoonPhase() != 0) return false;

        fullMoonCount++;
        nightHandled = true;
        setDirty();

        LOGGER.info("[ExtremeDifficulty] Full moon #{}", fullMoonCount);

        if (fullMoonCount % FULL_MOONS_PER_BLOOD_MOON == 0) {
            bloodMoonCount++;
            buffMult = Math.min(buffMult + BUFF_PER_BLOOD_MOON, MAX_BUFF_MULT);
            setDirty();
            LOGGER.info("[ExtremeDifficulty] BLOOD MOON #{} — mult x{}", bloodMoonCount, buffMult);
            return true;
        }

        return false;
    }

    public void onDayBegins() {
        if (nightHandled) {
            nightHandled = false;
            setDirty();
        }
    }

    public boolean isBloodMoonActive(ServerLevel level) {
        if (!isNight(level)) return false;
        return level.getServer().overworld().getMoonPhase() == 0
            && fullMoonCount > 0
            && fullMoonCount % FULL_MOONS_PER_BLOOD_MOON == 0;
    }

    // ─── Команды ─────────────────────────────────────────────────────────────

    /** /bloodmoon force — принудительно вызвать судную ночь */
    public void forceBloodMoon() {
        // Симулируем достижение следующего порога полнолуний
        int nextThreshold = ((fullMoonCount / FULL_MOONS_PER_BLOOD_MOON) + 1) * FULL_MOONS_PER_BLOOD_MOON;
        fullMoonCount = nextThreshold;
        bloodMoonCount++;
        buffMult = Math.min(buffMult + BUFF_PER_BLOOD_MOON, MAX_BUFF_MULT);
        nightHandled = true; // помечаем как обработанную чтобы не сработало повторно
        setDirty();
        LOGGER.info("[ExtremeDifficulty] Blood moon FORCED — #{}, mult x{}", bloodMoonCount, buffMult);
    }

    /** /bloodmoon reset — сбросить все счётчики */
    public void reset() {
        fullMoonCount  = 0;
        bloodMoonCount = 0;
        buffMult       = 1.0;
        nightHandled   = false;
        setDirty();
        LOGGER.info("[ExtremeDifficulty] All counters reset");
    }

    // ─── Геттеры ─────────────────────────────────────────────────────────────

    public double getBuffMult()      { return buffMult; }
    public int getBloodMoonCount()   { return bloodMoonCount; }
    public int getFullMoonCount()    { return fullMoonCount; }

    public static boolean isNight(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }
}
