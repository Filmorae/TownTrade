package dev.geduu.towntrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

final class CatalogMineralsUpgrade {
    private static final String EMERALD = "minecraft:emerald";
    private static final String NETHERITE = "minecraft:netherite_ingot";
    private static final int VERSION = 1;

    private static final TradeConfig.Product OLD_EMERALD = new TradeConfig.Product(EMERALD, EMERALD,
            null, "minerals", 1, 22, 4, 1, 20, 0, 0, null, 0, null);
    private static final TradeConfig.Product OLD_DEBRIS = new TradeConfig.Product("minecraft:ancient_debris",
            "minecraft:ancient_debris", null, "minerals", 1, 55, 0, 0, 20, 1, 18, "minerals", 10, null);

    private CatalogMineralsUpgrade() {}

    static boolean apply(JsonObject config, JsonObject defaults) {
        if (config.has("mineralsCatalogVersion")
                && config.get("mineralsCatalogVersion").getAsInt() >= VERSION) return false;
        var products = config.getAsJsonArray("products");
        var defaultProducts = defaults.getAsJsonArray("products");
        var pool = config.getAsJsonObject("pools").getAsJsonObject("minerals");
        int quota = pool.get("capacity").getAsInt();

        var emerald = find(products, EMERALD);
        var ore = find(products, "minecraft:emerald_ore");
        var deepslateOre = find(products, "minecraft:deepslate_emerald_ore");
        boolean replaceOre = matches(ore, oldOre("minecraft:emerald_ore"));
        boolean replaceDeepslateOre = matches(deepslateOre, oldOre("minecraft:deepslate_emerald_ore"));
        if (matches(emerald, OLD_EMERALD) && quota >= 8 && (replaceOre || replaceDeepslateOre)
                && !hasItem(products, EMERALD, emerald)) {
            replace(products, emerald, find(defaultProducts, EMERALD));
            if (replaceOre) products.remove(ore);
            if (replaceDeepslateOre) products.remove(deepslateOre);
        }

        var debris = find(products, OLD_DEBRIS.id());
        if (matches(debris, OLD_DEBRIS) && quota >= 10 && find(products, NETHERITE) == null
                && !hasItem(products, NETHERITE, null) && !hasItem(config.getAsJsonArray("coins"), NETHERITE, null)) {
            // Give the ingot its own product ID so old debris orders cannot turn into ingots.
            replace(products, debris, find(defaultProducts, NETHERITE));
        }

        config.addProperty("mineralsCatalogVersion", VERSION);
        return true;
    }

    private static TradeConfig.Product oldOre(String id) {
        return new TradeConfig.Product(id, id, null, "minerals", 0, 0, 0, 0, 0, 1, 12, "minerals", 8, null);
    }

    private static boolean matches(JsonObject row, TradeConfig.Product expected) {
        return row != null && expected.equals(TradeConfig.JSON.fromJson(row, TradeConfig.Product.class));
    }

    private static JsonObject find(JsonArray rows, String id) {
        for (var element : rows) {
            var row = element.getAsJsonObject();
            if (id.equals(row.get("id").getAsString())) return row;
        }
        return null;
    }

    private static boolean hasItem(JsonArray rows, String item, JsonObject except) {
        for (var element : rows) {
            var row = element.getAsJsonObject();
            if (row != except && item.equals(row.get("item").getAsString())) return true;
        }
        return false;
    }

    private static void replace(JsonArray rows, JsonObject previous, JsonObject replacement) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index) == previous) {
                rows.set(index, replacement.deepCopy());
                return;
            }
        }
    }
}
