package com.extremedifficulty;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Персистентные данные мода.
 * ВАЖНО: всегда получать через getOverworld() — данные хранятся только там.
 */
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

    /**
     * ФИХ БАГ 3: всегда вызывать с overworld!
     * BloodMoonManager.get(serverLevel.getServer().overworld())
     */
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

    // ─── Логика ───────────────────────────────────────────────────────────────

    /**
     * Вызывается каждую секунду ночью в Overworld.
     * Возвращает true если только что наступила судная ночь.
     */
    public boolean onNightTick(ServerLevel overworldLevel) {
        if (nightHandled) return false;

        int moonPhase = overworldLevel.getMoonPhase();
        if (moonPhase != 0) return false;

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

    /**
     * Активна ли судная ночь прямо сейчас.
     * level может быть любым измерением — проверяем ночь по нему.
     */
    public boolean isBloodMoonActive(ServerLevel level) {
        if (!isNight(level)) return false;
        // Полнолуние В OVERWORLD (фаза луны синхронизирована с overworld)
        return level.getServer().overworld().getMoonPhase() == 0
            && fullMoonCount > 0
            && fullMoonCount % FULL_MOONS_PER_BLOOD_MOON == 0;
    }

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
