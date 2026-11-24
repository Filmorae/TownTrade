package dev.geduu.towntrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;

record TradeConfig(int orderLimit, int protectionMinutes, int publicPremium, String timezone,
                   Map<String, TradeRules.PoolRule> pools, List<Coin> coins, List<Product> products) {
    static final int MAX_PRODUCTS = 4_000;
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    record Coin(String item, int value) {}

    record Product(String id, String item, String potion, String category, int buyBatch, int buyPrice,
                   int capacity, int restock, int minutes, int sellBatch, int sellPrice, String pool,
                   int quota, Map<String, Integer> seasons) {
        boolean stocked() { return buyPrice > 0 && capacity > 0; }
        boolean requestOnly() { return buyPrice > 0 && capacity == 0; }
        boolean buysFromPlayer() { return sellPrice > 0; }
        long interval() { return minutes * TradeRules.MINUTE; }

        int seasonalPrice(String season) {
            int percentage = seasons == null ? 100 : seasons.getOrDefault(season, 100);
            return (int) TradeRules.sellPrice(sellPrice, percentage);
        }
    }

    static TradeConfig load(Path configFolder) throws IOException {
        Files.createDirectories(configFolder);
        Path file = configFolder.resolve("economy.json");
        JsonObject defaults;
        try (var input = TradeConfig.class.getResourceAsStream("/towntrade-economy.json")) {
            Objects.requireNonNull(input, "Default trade catalog");
            defaults = JSON.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        }
        JsonArray shelf;
        try (var input = TradeConfig.class.getResourceAsStream("/towntrade-shelf.json")) {
            Objects.requireNonNull(input, "Default shelf expansion");
            shelf = JSON.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonArray.class);
        }
        if (!Files.exists(file)) {
            ShelfCatalog.apply(defaults, shelf);
            TradeConfig config = parse(defaults);
            write(file, defaults);
            return config;
        }
        var document = JSON.fromJson(Files.readString(file), JsonObject.class);
        TradeConfig config = parse(document);
        boolean retailUpgraded = CatalogRetailUpgrade.apply(document, defaults);
        boolean mineralsUpgraded = CatalogMineralsUpgrade.apply(document, defaults);
        boolean shelfUpgraded = ShelfCatalog.apply(document, shelf);
        if (retailUpgraded || mineralsUpgraded || shelfUpgraded) {
            config = parse(document);
            write(file, document);
        }
        return config;
    }

    private static TradeConfig parse(JsonObject document) {
        TradeConfig config = JSON.fromJson(document, TradeConfig.class);
        config.validate();
        return config;
    }

    private static void write(Path file, JsonObject contents) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "economy-", ".json.tmp");
        try {
            Files.writeString(temporary, PRETTY.toJson(contents) + "\n");
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validate() {
        require(orderLimit >= 1 && orderLimit <= 128, "orderLimit");
        require(protectionMinutes >= 0 && protectionMinutes <= 60, "protectionMinutes");
        require(publicPremium >= 0 && publicPremium <= 500, "publicPremium");
        ZoneId.of(timezone);
        for (TradeRules.Pool pool : TradeRules.Pool.values()) {
            var rule = pools.get(pool.key());
            require(rule != null && rule.capacity() >= 1 && rule.capacity() <= 10_000
                    && rule.pointsPerMinute() >= 1 && rule.pointsPerMinute() <= 1_000, "pool " + pool.key());
        }
        require(coins.size() == 3 && coins.get(0).value == 1 && coins.get(1).value == 16
                && coins.get(2).value == 256, "coin denominations");
        var currencyIds = new HashSet<String>();
        for (Coin coin : coins) {
            require(id(coin.item) && currencyIds.add(coin.item), "coin " + coin.item);
        }
        require(products.size() <= MAX_PRODUCTS, "catalog size");
        var ids = new HashSet<String>();
        var stocks = new HashSet<String>();
        for (Product product : products) {
            require(product.id != null && product.id.matches("[a-z0-9_./:-]{1,128}")
                    && ids.add(product.id), "duplicate or invalid product " + product.id);
            require(id(product.item) && !currencyIds.contains(product.item), "item " + product.id);
            require(product.potion == null || id(product.potion), "potion " + product.id);
            require(product.category != null && product.category.matches("[a-z_]{1,32}"), "category " + product.id);
            require(product.buyPrice >= 0 && product.buyPrice <= 100_000
                    && product.sellPrice >= 0 && product.sellPrice <= 100_000
                    && (product.buyPrice > 0 || product.sellPrice > 0), "price " + product.id);
            if (product.buyPrice > 0) {
                require(product.buyBatch >= 1 && product.buyBatch <= 2_304
                        && product.minutes >= 10 && product.minutes <= 20, "retail " + product.id);
                require(product.capacity >= 0 && product.capacity <= 100_000, "capacity " + product.id);
                if (product.stocked()) {
                    require(product.capacity >= product.buyBatch && product.restock >= 1
                            && product.restock <= product.capacity
                            && stocks.add(product.item + "|" + product.potion), "stock " + product.id);
                }
            }
            if (product.buysFromPlayer()) {
                var rule = pools.get(product.pool);
                require(rule != null && product.quota >= 1 && product.quota <= rule.capacity()
                        && product.sellBatch >= 1 && product.sellBatch <= 2_304, "buyback " + product.id);
                int maximumPrice = product.sellPrice;
                if (product.seasons != null) {
                    for (var season : product.seasons.entrySet()) {
                        require(List.of("spring", "summer", "autumn", "winter").contains(season.getKey())
                                && season.getValue() >= 1 && season.getValue() <= 300, "season " + product.id);
                        maximumPrice = Math.max(maximumPrice, product.seasonalPrice(season.getKey()));
                    }
                }
                for (Product retail : products) {
                    if (retail.buyPrice <= 0 || !product.item.equals(retail.item)
                            || !Objects.equals(product.potion, retail.potion)) continue;
                    require((long) maximumPrice * retail.buyBatch < (long) retail.buyPrice * product.sellBatch,
                            "buyback must be below retail for " + product.id);
                }
            }
        }
    }

    private static boolean id(String value) {
        return value != null && value.contains(":") && Identifier.tryParse(value) != null;
    }

    private static void require(boolean condition, String field) {
        if (!condition) throw new IllegalArgumentException("Invalid Town Trade configuration: " + field);
    }
}
