package com.extremedifficulty;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ZombieEyeGlowLayer<T extends Zombie, M extends ZombieModel<T>>
        extends RenderLayer<T, M> {

    private static final ResourceLocation ZOMBIE_EYES =
        new ResourceLocation("extremedifficulty", "textures/entity/zombie_eyes.png");

    public ZombieEyeGlowLayer(RenderLayerParent<T, M> renderer) {
        super(renderer);
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

        if (!shouldGlow(zombie.level())) return;

        M model = this.getParentModel();

        // Прячем все части модели кроме головы
        boolean bodyVisible    = model.body.visible;
        boolean headVisible    = model.head.visible;
        boolean hatVisible    = model.hat.visible;
        boolean rightArmVis   = model.rightArm.visible;
        boolean leftArmVis    = model.leftArm.visible;
        boolean rightLegVis   = model.rightLeg.visible;
        boolean leftLegVis    = model.leftLeg.visible;

        model.body.visible    = false;
        model.rightArm.visible = false;
        model.leftArm.visible  = false;
        model.rightLeg.visible = false;
        model.leftLeg.visible  = false;
        model.hat.visible      = false;
        model.head.visible     = true; // только голова

        // Рендерим только голову с текстурой глаз на полной яркости
        var buffer = bufferSource.getBuffer(RenderType.eyes(ZOMBIE_EYES));
        model.renderToBuffer(
            poseStack,
            buffer,
            15728640, // MAX_LIGHT — полная яркость независимо от окружения
            OverlayTexture.NO_OVERLAY,
            1.0f, 1.0f, 1.0f, 1.0f
        );

        // Восстанавливаем видимость всех частей
        model.body.visible     = bodyVisible;
        model.head.visible     = headVisible;
        model.hat.visible      = hatVisible;
        model.rightArm.visible = rightArmVis;
        model.leftArm.visible  = leftArmVis;
        model.rightLeg.visible = rightLegVis;
        model.leftLeg.visible  = leftLegVis;
    }

    private static boolean shouldGlow(Level level) {
        if (level.dimension() == Level.NETHER) return true;
        if (level.dimension() == Level.END)    return true;
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time <= 23000;
    }
}
