package com.extremedifficulty;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, "extremedifficulty");

    // Звук колоколов судной ночи
    // Файл: src/main/resources/assets/extremedifficulty/sounds/blood_moon_bell.ogg
    public static final RegistryObject<SoundEvent> BLOOD_MOON_BELL = SOUNDS.register(
        "blood_moon_bell",
        () -> SoundEvent.createVariableRangeEvent(
            new ResourceLocation("extremedifficulty", "blood_moon_bell")
        )
    );
}
