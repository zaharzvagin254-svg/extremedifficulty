package com.extremedifficulty;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientSetup {

    public static void registerRenderers() {
        // Заменяем стандартный рендерер зомби на наш кастомный
        EntityRenderers.register(EntityType.ZOMBIE, GlowingZombieRenderer::new);
        // Хаск тоже зомби-подобный — тоже светятся
        EntityRenderers.register(EntityType.HUSK, GlowingZombieRenderer::new);
    }
}
