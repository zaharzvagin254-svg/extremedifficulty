package com.extremedifficulty;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;

@OnlyIn(Dist.CLIENT)
public class ClientSetup {

    public static void registerRenderers() {
        // Кастомный рендерер зомби (светящиеся глаза)
        EntityRenderers.register(EntityType.ZOMBIE, GlowingZombieRenderer::new);
        EntityRenderers.register(EntityType.HUSK,   GlowingZombieRenderer::new);

        // Красный экранный фильтр судной ночи
        MinecraftForge.EVENT_BUS.register(new BloodMoonScreenEffect());
    }
}
