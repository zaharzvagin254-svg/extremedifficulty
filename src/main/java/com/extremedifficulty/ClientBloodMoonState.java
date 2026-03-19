package com.extremedifficulty;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Хранит состояние судной ночи на клиенте.
 * Обновляется сервером через пакет BloodMoonPacket.
 */
@OnlyIn(Dist.CLIENT)
public class ClientBloodMoonState {

    private static boolean bloodMoonActive = false;

    public static boolean isBloodMoonActive() {
        return bloodMoonActive;
    }

    public static void setBloodMoonActive(boolean active) {
        bloodMoonActive = active;
    }
}
