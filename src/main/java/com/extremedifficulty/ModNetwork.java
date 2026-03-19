package com.extremedifficulty;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("extremedifficulty", "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );

    public static void register() {
        CHANNEL.registerMessage(
            0,
            BloodMoonPacket.class,
            BloodMoonPacket::encode,
            BloodMoonPacket::decode,
            BloodMoonPacket::handle,
            java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    /** Отправляем всем игрокам на сервере */
    public static void sendToAll(boolean bloodMoonActive) {
        CHANNEL.send(
            PacketDistributor.ALL.noArg(),
            new BloodMoonPacket(bloodMoonActive)
        );
    }

    /** Отправляем конкретному игроку (например при входе) */
    public static void sendToPlayer(ServerPlayer player, boolean bloodMoonActive) {
        CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            new BloodMoonPacket(bloodMoonActive)
        );
    }
}
