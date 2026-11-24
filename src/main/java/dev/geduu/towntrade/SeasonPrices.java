package dev.geduu.towntrade;

import java.lang.reflect.Method;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

final class SeasonPrices {
    private Method seasonState;
    private Method getSeason;

    SeasonPrices() {
        if (!FabricLoader.getInstance().isModLoaded("sereneseasons")) return;
        try {
            seasonState = Class.forName("sereneseasons.api.season.SeasonHelper").getMethod("getSeasonState", Level.class);
            getSeason = Class.forName("sereneseasons.api.season.ISeasonState").getMethod("getSeason");
        } catch (ReflectiveOperationException | LinkageError failure) {
            TownTrade.LOGGER.warn("Serene Seasons API unavailable; using base buyback prices", failure);
        }
    }

    String season(MinecraftServer server) {
        if (seasonState == null || getSeason == null) return "";
        try {
            var season = (Enum<?>) getSeason.invoke(seasonState.invoke(null, server.overworld()));
            return season.name().toLowerCase(Locale.ROOT);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            TownTrade.LOGGER.warn("Could not read the world season; using base buyback prices", failure);
            seasonState = null;
            return "";
        }
    }
}
