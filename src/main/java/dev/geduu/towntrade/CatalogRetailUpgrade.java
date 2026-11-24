package dev.geduu.towntrade;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The one-time 0.3.0 retail catalogue addition for existing worlds. */
final class CatalogRetailUpgrade {
    private static final int VERSION = 1;
    private static final Set<String> FARM_MODS = Set.of("farmersdelight", "rusticdelight", "ubesdelight", "croptopia");

    private record Buyback(int price, int batch) {}

    private CatalogRetailUpgrade() {}

    static boolean apply(JsonObject config, JsonObject defaults) {
        if (config.has("retailCatalogVersion") && config.get("retailCatalogVersion").getAsInt() >= VERSION) return false;
        var products = config.getAsJsonArray("products");
        var existing = new HashMap<String, JsonObject>();
        var stock = new HashSet<String>();
        var represented = new HashSet<String>();
        var buybacks = new HashMap<String, List<Buyback>>();
        var currencies = new HashSet<String>();
        for (var coin : config.getAsJsonArray("coins")) currencies.add(coin.getAsJsonObject().get("item").getAsString());
        for (var element : products) {
            var row = element.getAsJsonObject();
            var product = TradeConfig.JSON.fromJson(row, TradeConfig.Product.class);
            existing.put(product.id(), row);
            String item = itemKey(product);
            represented.add(item);
            if (product.buyPrice() > 0) stock.add(item);
            if (product.buysFromPlayer()) {
                int best = product.sellPrice();
                if (product.seasons() != null) {
                    for (var season : product.seasons().keySet()) {
                        best = Math.max(best, product.seasonalPrice(season));
                    }
                }
                buybacks.computeIfAbsent(item, ignored -> new ArrayList<>()).add(new Buyback(best, product.sellBatch()));
            }
        }
        for (var element : defaults.getAsJsonArray("products")) {
            var defaultsRow = element.getAsJsonObject();
            var product = TradeConfig.JSON.fromJson(defaultsRow, TradeConfig.Product.class);
            if (!product.stocked() || !FARM_MODS.contains(product.item().split(":", 2)[0])
                    || currencies.contains(product.item())) continue;
            var target = existing.get(product.id());
            String item = itemKey(product);
            if (!product.buysFromPlayer()) {
                if (target == null && !represented.contains(item) && products.size() < TradeConfig.MAX_PRODUCTS) {
                    products.add(defaultsRow.deepCopy());
                    represented.add(item);
                }
                continue;
            }
            if (target == null || stock.contains(item)) continue;
            var current = TradeConfig.JSON.fromJson(target, TradeConfig.Product.class);
            if (!item.equals(itemKey(current)) || current.buyPrice() > 0 || current.buyBatch() != 0
                    || current.capacity() != 0 || current.restock() != 0 || current.minutes() != 0) continue;
            long price = product.buyPrice();
            for (var buyback : buybacks.getOrDefault(item, List.of())) {
                price = Math.max(price, (long) product.buyBatch() * buyback.price() / buyback.batch() + 1);
            }
            if (price > 100_000) {
                TownTrade.LOGGER.warn("Skipping new retail listing {} because of its configured buyback price", product.id());
                continue;
            }
            target.addProperty("buyBatch", product.buyBatch());
            target.addProperty("buyPrice", price);
            target.addProperty("capacity", product.capacity());
            target.addProperty("restock", product.restock());
            target.addProperty("minutes", product.minutes());
            stock.add(item);
        }
        config.addProperty("retailCatalogVersion", VERSION);
        return true;
    }

    private static String itemKey(TradeConfig.Product product) {
        return product.item() + "|" + product.potion();
    }
}
