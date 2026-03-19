package com.extremedifficulty;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Применяет красный пост-процессинг фильтр в судную ночь.
 * Работает через PostChain — тот же механизм что ночное зрение,
 * тошнота, наложение зелья слепоты и т.д. Совместим с шейдерами.
 */
@OnlyIn(Dist.CLIENT)
public class BloodMoonScreenEffect {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final ResourceLocation BLOOD_MOON_SHADER =
        new ResourceLocation("extremedifficulty", "shaders/post/blood_moon.json");

    private PostChain postChain = null;
    private boolean loaded = false;

    // ─── Загружаем шейдер при первом использовании ───────────────────────────
    private void loadShader() {
        if (loaded) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            postChain = new PostChain(
                mc.getTextureManager(),
                mc.getResourceManager(),
                mc.getMainRenderTarget(),
                BLOOD_MOON_SHADER
            );
            postChain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            loaded = true;
            LOGGER.info("[ExtremeDifficulty] Blood moon shader loaded");
        } catch (Exception e) {
            LOGGER.error("[ExtremeDifficulty] Failed to load blood moon shader: {}", e.getMessage());
            loaded = true; // помечаем как загруженный чтобы не спамить ошибками
        }
    }

    // ─── Применяем эффект после рендера мира ─────────────────────────────────
    @SubscribeEvent
    public void onRenderLevelLast(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (!ClientBloodMoonState.isBloodMoonActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        loadShader();

        if (postChain != null) {
            postChain.process(event.getPartialTick());
            // Восстанавливаем главный рендертаргет после постпроцессинга
            mc.getMainRenderTarget().bindWrite(false);
        }
    }

    // ─── Обновляем размер при изменении окна ─────────────────────────────────
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (postChain != null && mc.getWindow() != null) {
            // Проверяем изменился ли размер окна
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            if (w != mc.getMainRenderTarget().width || h != mc.getMainRenderTarget().height) {
                postChain.resize(w, h);
            }
        }

        // Сбрасываем шейдер если судная ночь закончилась
        if (!ClientBloodMoonState.isBloodMoonActive() && postChain != null) {
            postChain.close();
            postChain = null;
            loaded = false;
        }
    }
}
