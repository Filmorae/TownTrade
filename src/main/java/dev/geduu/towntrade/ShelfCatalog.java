package dev.geduu.towntrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;

final class ShelfCatalog {
    private static final String VERSION_FIELD = "shelfCatalogVersion";
    private static final int VERSION = 1;

    private ShelfCatalog() {}

    static boolean apply(JsonObject config, JsonArray additions) {
        if (config.has(VERSION_FIELD) && config.get(VERSION_FIELD).getAsInt() >= VERSION) return false;

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
        config.addProperty(VERSION_FIELD, VERSION);
        return true;
    }
}
