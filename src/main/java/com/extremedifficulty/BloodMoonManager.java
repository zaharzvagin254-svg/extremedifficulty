package com.extremedifficulty;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Персистентные данные мода — сохраняются между сессиями в worlddata.
 * Хранит:
 *  - сколько полнолуний прошло
 *  - сколько судных ночей было
 *  - текущий множитель баффа мобов
 *  - была ли уже обработана текущая ночь
 */
public class BloodMoonManager extends SavedData {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String DATA_NAME = "extremedifficulty_bloodmoon";

    // Каждые N полнолуний — судная ночь
    public static final int FULL_MOONS_PER_BLOOD_MOON = 5;

    // Прирост за каждую судную ночь
    private static final double BUFF_PER_BLOOD_MOON = 0.15;

    // Максимальный множитель (×3.0 от базы)
    public static final double MAX_BUFF_MULT = 3.0;

    // Базовый множитель (без судных ночей) — стандартный день/ночь из мода
    public static final double BASE_MULT_DAY   = 1.50;
    public static final double BASE_MULT_NIGHT = 1.85;

    private int fullMoonCount   = 0;  // сколько всего полнолуний прошло
    private int bloodMoonCount  = 0;  // сколько судных ночей было
    private double buffMult     = 1.0; // текущий накопленный множитель (1.0 = без судных ночей)
    private boolean nightHandled = false; // обработана ли текущая ночь

    // ─── SavedData API ────────────────────────────────────────────────────────

    public static BloodMoonManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
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
        tag.putInt("fullMoonCount",  fullMoonCount);
        tag.putInt("bloodMoonCount", bloodMoonCount);
        tag.putDouble("buffMult",    buffMult);
        tag.putBoolean("nightHandled", nightHandled);
        return tag;
    }

    // ─── Логика ───────────────────────────────────────────────────────────────

    /**
     * Вызывается каждый тик ночи.
     * Возвращает true если ТОЛЬКО ЧТО наступила судная ночь (первый раз за эту ночь).
     */
    public boolean onNightTick(ServerLevel level) {
        if (nightHandled) return false;

        // Фаза луны: 0 = полнолуние, 1..7 = убывающая/растущая
        int moonPhase = level.getMoonPhase();
        if (moonPhase != 0) return false; // не полнолуние

        // Это полнолуние
        fullMoonCount++;
        nightHandled = true;
        setDirty();

        LOGGER.info("[ExtremeDifficulty] Full moon #{}", fullMoonCount);

        // Каждое 5-е полнолуние — судная ночь
        if (fullMoonCount % FULL_MOONS_PER_BLOOD_MOON == 0) {
            bloodMoonCount++;
            // Увеличиваем множитель, но не выше максимума
            buffMult = Math.min(buffMult + BUFF_PER_BLOOD_MOON, MAX_BUFF_MULT);
            setDirty();

            LOGGER.info("[ExtremeDifficulty] BLOOD MOON #{} — buff mult now x{}", bloodMoonCount, buffMult);
            return true;
        }

        return false;
    }

    /** Сбрасываем флаг ночи когда наступает день */
    public void onDayBegins() {
        if (nightHandled) {
            nightHandled = false;
            setDirty();
        }
    }

    /** Активна ли сейчас судная ночь */
    public boolean isBloodMoonActive(ServerLevel level) {
        if (!isNight(level)) return false;
        // Судная ночь = полнолуние И счётчик кратен 5
        return level.getMoonPhase() == 0 && fullMoonCount > 0
            && fullMoonCount % FULL_MOONS_PER_BLOOD_MOON == 0;
    }

    public double getBuffMult() { return buffMult; }
    public int getBloodMoonCount() { return bloodMoonCount; }
    public int getFullMoonCount() { return fullMoonCount; }

    public static boolean isNight(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }
}
