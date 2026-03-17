package com.extremedifficulty;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ZombieEyeGlowLayer<T extends Zombie, M extends ZombieModel<T>>
        extends RenderLayer<T, M> {

    // Текстура светящихся глаз — маска поверх стандартной текстуры зомби
    // Файл: src/main/resources/assets/extremedifficulty/textures/entity/zombie_eyes.png
    private static final ResourceLocation ZOMBIE_EYES =
        new ResourceLocation("extremedifficulty", "textures/entity/zombie_eyes.png");

    public ZombieEyeGlowLayer(ZombieRenderer renderer) {
        super((net.minecraft.client.renderer.entity.RenderLayerParent<T, M>) renderer);
    }

    @Override
    public void render(PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       int packedLight,
                       T zombie,
                       float limbSwing,
                       float limbSwingAmount,
                       float partialTick,
                       float ageInTicks,
                       float netHeadYaw,
                       float headPitch) {

        // Светимся только ночью (или в Незере/Крае)
        Level level = zombie.level();
        if (!shouldGlow(level)) return;

        // FULL_BRIGHT — глаза светятся вне зависимости от освещения
        // Это то же самое, что у эндермена или пауков
        var buffer = bufferSource.getBuffer(RenderType.eyes(ZOMBIE_EYES));

        this.getParentModel().renderToBuffer(
            poseStack,
            buffer,
            15728640, // MAX_LIGHT — полная яркость
            OverlayTexture.NO_OVERLAY,
            1.0f, 1.0f, 1.0f, 1.0f
        );
    }

    private static boolean shouldGlow(Level level) {
        // Незер и Энд — всегда
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;
        // Верхний мир — только ночью
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }
}
