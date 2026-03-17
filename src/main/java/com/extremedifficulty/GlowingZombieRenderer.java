package com.extremedifficulty;

import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class GlowingZombieRenderer extends ZombieRenderer {

    public GlowingZombieRenderer(EntityRendererProvider.Context context) {
        super(context);
        // Добавляем слой светящихся глаз поверх стандартного рендера
        this.addLayer(new ZombieEyeGlowLayer<>(this));
    }
}
