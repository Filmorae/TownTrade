package dev.geduu.towntrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

final class ShelfCatalog {
    private static final String VERSION_FIELD = "shelfCatalogVersion";
    private static final int VERSION = 3;
    private static final String NETHER_STAR = "minecraft:nether_star";
    private static final Set<String> STOCKED_PLANTS = Set.of("minecraft:large_fern", "minecraft:tall_grass");

    private ShelfCatalog() {}

    static boolean apply(JsonObject config, JsonArray additions) {
        int previous = config.has(VERSION_FIELD) ? config.get(VERSION_FIELD).getAsInt() : 0;
        if (previous >= VERSION) return false;
        if (previous < 1) addProducts(config, additions);
        migrateDefaults(config.getAsJsonArray("products"), additions, previous);
        config.addProperty(VERSION_FIELD, VERSION);
        return true;
    }

    private static void addProducts(JsonObject config, JsonArray additions) {
        var ids = new HashSet<String>();
        var items = new HashSet<String>();
        for (var element : config.getAsJsonArray("coins")) {
            items.add(element.getAsJsonObject().get("item").getAsString());
        }
        var products = config.getAsJsonArray("products");
        for (var element : products) {
            var row = element.getAsJsonObject();
            ids.add(row.get("id").getAsString());
            items.add(row.get("item").getAsString());
        }
        for (var element : additions) {
            if (products.size() >= TradeConfig.MAX_PRODUCTS) break;
            var row = element.getAsJsonObject();
            String id = row.get("id").getAsString();
            String item = row.get("item").getAsString();
            if (ids.contains(id) || items.contains(item)) continue;
            products.add(row.deepCopy());
            ids.add(id);
            items.add(item);
        }
    }

    private static void migrateDefaults(JsonArray products, JsonArray additions, int previous) {
        for (var element : additions) {
            var currentDefault = element.getAsJsonObject();
            String id = currentDefault.get("id").getAsString();
            if (!(previous < 2 && id.equals(NETHER_STAR)) && !STOCKED_PLANTS.contains(id)) continue;
            var oldDefault = currentDefault.deepCopy();
            if (id.equals(NETHER_STAR)) {
                oldDefault.addProperty("buyPrice", 768);
            } else {
                oldDefault.addProperty("capacity", 0);
                oldDefault.addProperty("restock", 0);
                oldDefault.addProperty("minutes", 20);
            }
            for (int i = 0; i < products.size(); i++) {
                if (oldDefault.equals(products.get(i))) {
                    products.set(i, currentDefault.deepCopy());
                    break;
                }
            }
        }
    }

    static boolean acceptsExistingOrder(TradeConfig.Product product) {
        return product.requestOnly() || (product.stocked() && STOCKED_PLANTS.contains(product.id())
                && product.id().equals(product.item()));
    }
}
