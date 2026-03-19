package com.extremedifficulty;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Пакет от сервера к клиенту — сообщает активна ли судная ночь.
 * Нужен для рендера красной луны на клиенте.
 */
public class BloodMoonPacket {

    private final boolean active;

    public BloodMoonPacket(boolean active) {
        this.active = active;
    }

    public static BloodMoonPacket decode(FriendlyByteBuf buf) {
        return new BloodMoonPacket(buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Выполняется на клиентском потоке
            ClientBloodMoonState.setBloodMoonActive(active);
        });
        ctx.get().setPacketHandled(true);
    }
}
