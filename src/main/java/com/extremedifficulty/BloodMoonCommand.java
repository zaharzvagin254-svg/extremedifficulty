package com.extremedifficulty;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public class BloodMoonCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("bloodmoon")
                .requires(src -> src.hasPermission(2)) // только оп

                // /bloodmoon force — принудительно вызвать судную ночь
                .then(Commands.literal("force")
                    .executes(ctx -> forceBloodMoon(ctx.getSource())))

                // /bloodmoon status — показать текущее состояние
                .then(Commands.literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))

                // /bloodmoon reset — сбросить все счётчики
                .then(Commands.literal("reset")
                    .executes(ctx -> resetBloodMoon(ctx.getSource())))
        );
    }

    private static int forceBloodMoon(CommandSourceStack src) {
        ServerLevel overworld = src.getServer().overworld();
        BloodMoonManager mgr = BloodMoonManager.get(overworld);

        // Принудительно увеличиваем счётчик и включаем судную ночь
        mgr.forceBloodMoon();

        // Ставим время на ночь с полнолунием (день кратный 8, время 18000)
        long currentDay = overworld.getDayTime() / 24000;
        long nextFullMoonDay = ((currentDay / 8) + 1) * 8;
        overworld.setDayTime(nextFullMoonDay * 24000 + 18000);

        // Бафаем всех мобов во всех измерениях
        for (ServerLevel dim : src.getServer().getAllLevels()) {
            BloodMoonEvents.buffAllMobsForBloodMoon(dim, mgr);
        }

        // Объявляем судную ночь
        BloodMoonEvents.announceBloodMoon(overworld, mgr);

        src.sendSuccess(() -> Component.literal(
            "§4[ExtremeDifficulty] Судная ночь #" + mgr.getBloodMoonCount()
            + " вызвана принудительно. Множитель: x" + String.format("%.2f", mgr.getBuffMult())
        ), true);

        return 1;
    }

    private static int showStatus(CommandSourceStack src) {
        ServerLevel overworld = src.getServer().overworld();
        BloodMoonManager mgr = BloodMoonManager.get(overworld);

        int moonPhase = overworld.getMoonPhase();
        String phase = switch (moonPhase) {
            case 0 -> "🌕 Полнолуние";
            case 1 -> "🌖 Убывающая гиббозная";
            case 2 -> "🌗 Последняя четверть";
            case 3 -> "🌘 Убывающий серп";
            case 4 -> "🌑 Новолуние";
            case 5 -> "🌒 Растущий серп";
            case 6 -> "🌓 Первая четверть";
            case 7 -> "🌔 Растущая гиббозная";
            default -> "Неизвестно";
        };

        int toNextBloodMoon = BloodMoonManager.FULL_MOONS_PER_BLOOD_MOON
            - (mgr.getFullMoonCount() % BloodMoonManager.FULL_MOONS_PER_BLOOD_MOON);

        src.sendSuccess(() -> Component.literal(
            "\n§6=== Extreme Difficulty — Blood Moon Status ===\n" +
            "§eФаза луны: §f" + phase + "\n" +
            "§eВсего полнолуний: §f" + mgr.getFullMoonCount() + "\n" +
            "§eСудных ночей было: §f" + mgr.getBloodMoonCount() + "\n" +
            "§eТекущий множитель: §cx" + String.format("%.2f", mgr.getBuffMult()) + "\n" +
            "§eДо следующей судной ночи: §f" + toNextBloodMoon + " полнолун(ий)\n" +
            "§eМакс. множитель: §cx" + BloodMoonManager.MAX_BUFF_MULT + "\n" +
            "§6============================================"
        ), false);

        return 1;
    }

    private static int resetBloodMoon(CommandSourceStack src) {
        ServerLevel overworld = src.getServer().overworld();
        BloodMoonManager mgr = BloodMoonManager.get(overworld);
        mgr.reset();

        // Снимаем красный фильтр с клиентов
        ModNetwork.sendToAll(false);

        src.sendSuccess(() -> Component.literal(
            "§a[ExtremeDifficulty] Все счётчики сброшены. Мобы вернутся к ванильным значениям после respawn."
        ), true);

        return 1;
    }
}
