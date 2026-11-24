package dev.geduu.towntrade;

import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class EconomyRulesTest {
    public static void main(String[] arguments) throws Exception {
        long minute = TradeRules.MINUTE;
        long time = Instant.parse("2026-11-23T15:58:00Z").toEpochMilli();
        var partial = TradeRules.stockAfter(new TradeRules.Stock(256, TradeRules.NO_TIME), -200, 256, 10 * minute, time);
        require(partial.equals(new TradeRules.Stock(56, time + 10 * minute)), "stock starts its clock");
        var restocked = TradeRules.replenish(partial, 256, 64, 10 * minute, time + 20 * minute);
        require(restocked.equals(new TradeRules.Stock(184, time + 30 * minute)), "rolls through elapsed cycles");
        var supplied = TradeRules.stockAfter(restocked, 72, 256, 10 * minute, time + 20 * minute);
        require(supplied.equals(new TradeRules.Stock(256, TradeRules.NO_TIME)), "sales fill stock");
        require(TradeRules.replenish(partial, 256, 64, 10 * minute, time + 1000 * minute).equals(supplied),
                "large elapsed interval caps at capacity");

        var pool = new TradeRules.PoolRule(24, 1);
        var quota = new TradeRules.Quota(12 * TradeRules.POINT, time);
        require(TradeRules.quotaNow(quota, pool, time + 6 * minute) == 18 * TradeRules.POINT, "rolling quota");
        require(TradeRules.quotaNow(quota, pool, time + 20 * minute) == pool.ceiling(), "quota cap");
        require(TradeRules.quotaNow(quota, pool, time - minute) == quota.units(), "backward clock");
        require(TradeRules.orderPrice(36, 1) == 36 && TradeRules.orderPrice(36, 2) == 76
                && TradeRules.orderPrice(36, 3) == 119 && TradeRules.orderPrice(36, 4) == 166
                && TradeRules.orderPrice(36, 5) == 216, "successive order batches increase the marginal price");
        require(TradeRules.maxOrderBatches(1) == 5 && TradeRules.maxOrderBatches(1024) == 2
                && TradeRules.maxOrderBatches(2304) == 1, "order batch limits");
        require(TradeRules.publicPrice(36, 20) == 44 && TradeRules.publicPrice(1, 20) == 2, "public premium");
        require(TradeRules.orderExpiry(time, ZoneId.of("Asia/Shanghai"))
                == Instant.parse("2026-11-24T16:00:00Z").toEpochMilli(), "next civil day expires");
        var order = new TradeRules.Order("order-id", "player", "name", "example", 10, time, time + minute,
                time + 6 * minute, time + 100 * minute, "active", 5);
        require(order.displayStatus(time).equals("waiting"), "waiting");
        require(order.displayStatus(time + minute).equals("protected"), "first moment of protection");
        require(order.displayStatus(time + 6 * minute).equals("available"), "end of protection");
        require(order.displayStatus(time + 100 * minute).equals("expired"), "end of listing");
        var purchased = order.closed("bought_by_other");
        require(purchased.displayStatus(time).equals("bought_by_other") && purchased.quantityOrBatch(1) == 5,
                "closing preserves the order quantity");
        var legacy = TradeConfig.JSON.fromJson("""
                {"id":"legacy","owner":"player","ownerName":"Player","product":"item","price":10,"status":"active"}
                """, TradeRules.Order.class);
        require(legacy.quantityOrBatch(1) == 1 && legacy.quantityOrBatch(8) == 8, "legacy orders fulfill one batch");
        testMineralUpgrade();
        testShelfCatalog();

        testServiceRules(time);
        testRecovery(time);
        System.out.println("Town Trade economy rules and journal recovery passed");
    }

    private static void testMineralUpgrade() throws Exception {
        var directory = Files.createTempDirectory("towntrade-minerals");
        var configFile = directory.resolve("economy.json");
        try {
            var defaults = TradeConfig.load(directory);
            var rows = defaults.products().stream()
                    .filter(rule -> !List.of("minecraft:emerald", "minecraft:netherite_ingot").contains(rule.id()))
                    .toList();
            var legacy = TradeConfig.JSON.toJsonTree(defaults).getAsJsonObject();
            var products = TradeConfig.JSON.toJsonTree(rows).getAsJsonArray();
            var mineralRows = TradeConfig.JSON.fromJson("""
                    [
                      {"id":"minecraft:emerald","item":"minecraft:emerald","category":"minerals",
                       "buyBatch":1,"buyPrice":22,"capacity":4,"restock":1,"minutes":20},
                      {"id":"minecraft:emerald_ore","item":"minecraft:emerald_ore","category":"minerals",
                       "sellBatch":1,"sellPrice":12,"pool":"minerals","quota":8},
                      {"id":"minecraft:deepslate_emerald_ore","item":"minecraft:deepslate_emerald_ore","category":"minerals",
                       "sellBatch":1,"sellPrice":13,"pool":"minerals","quota":8},
                      {"id":"minecraft:ancient_debris","item":"minecraft:ancient_debris","category":"minerals",
                       "buyBatch":1,"buyPrice":55,"minutes":20,"sellBatch":1,"sellPrice":18,"pool":"minerals","quota":10}
                    ]
                    """, com.google.gson.JsonArray.class);
            products.addAll(mineralRows);
            legacy.add("products", products);
            legacy.addProperty("retailCatalogVersion", 1);
            Files.writeString(configFile, TradeConfig.JSON.toJson(legacy));
            var upgraded = TradeConfig.load(directory);
            var byId = upgraded.products().stream().collect(java.util.stream.Collectors.toMap(
                    TradeConfig.Product::id, product -> product));
            require(byId.get("minecraft:emerald").sellPrice() == 4 && !byId.containsKey("minecraft:emerald_ore"),
                    "default emerald ore buyback becomes an emerald buyback");
            require(byId.get("minecraft:deepslate_emerald_ore").sellPrice() == 13, "custom ore buyback is preserved");
            require(byId.get("minecraft:netherite_ingot").requestOnly()
                    && !byId.containsKey("minecraft:ancient_debris"), "ingot uses its own product ID");
            String upgradedFile = Files.readString(configFile);
            require(TradeConfig.load(directory).equals(upgraded) && Files.readString(configFile).equals(upgradedFile),
                    "mineral migration runs once");
        } finally {
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(directory);
        }
    }

    private static void testShelfCatalog() throws Exception {
        var directory = Files.createTempDirectory("towntrade-shelf");
        var configFile = directory.resolve("economy.json");
        try {
            var fresh = TradeConfig.load(directory).products().stream()
                    .collect(Collectors.toMap(TradeConfig.Product::id, product -> product));
            var lichen = fresh.get("minecraft:glow_lichen");
            require(lichen.stocked() && lichen.buyBatch() == 8 && lichen.buyPrice() == 6
                    && lichen.capacity() == 48 && lichen.restock() == 8 && lichen.minutes() == 15, "lichen stock");
            for (String id : List.of("axolotl", "cod", "salmon", "pufferfish", "tadpole", "tropical_fish")) {
                var bucket = fresh.get("minecraft:" + id + "_bucket");
                require(bucket.requestOnly() && bucket.buyBatch() == 1
                        && bucket.category().equals("bucket_mobs"), "mob bucket order " + id);
            }
            require(fresh.get("minecraft:silence_armor_trim_smithing_template").requestOnly(), "trim order");
            require(fresh.get("minecraft:nether_star").buyPrice() == 768
                    && fresh.get("minecraft:elytra").buyPrice() == 512, "rare order prices");
            require(!fresh.containsKey("minecraft:chainmail_helmet") && !fresh.containsKey("minecraft:trident")
                    && !fresh.containsKey("minecraft:dead_brain_coral"), "requested exclusions");
            require(!fresh.containsKey("minecraft:big_dripleaf") && !fresh.containsKey("minecraft:torchflower"),
                    "deferred derivative items");

            var legacy = TradeConfig.JSON.fromJson("""
                    {
                      "retailCatalogVersion": 1,
                      "mineralsCatalogVersion": 1,
                      "orderLimit": 4, "protectionMinutes": 5, "publicPremium": 20, "timezone":"Asia/Shanghai",
                      "pools": {
                        "building": {"capacity":48,"pointsPerMinute":2},
                        "crops": {"capacity":48,"pointsPerMinute":2},
                        "minerals": {"capacity":24,"pointsPerMinute":1}
                      },
                      "coins": [{"item":"minecraft:cobweb","value":1},
                                {"item":"coins:iron_coin","value":16},{"item":"coins:gold_coin","value":256}],
                      "products": [
                        {"id":"custom_lichen","item":"minecraft:glow_lichen","category":"building",
                         "buyBatch":2,"buyPrice":99,"capacity":6,"restock":2,"minutes":15},
                        {"id":"minecraft:small_dripleaf","item":"minecraft:small_dripleaf","category":"building",
                         "buyBatch":1,"buyPrice":99,"minutes":20}
                      ]
                    }
                    """, com.google.gson.JsonObject.class);
            Files.writeString(configFile, TradeConfig.JSON.toJson(legacy));
            var expanded = TradeConfig.load(directory);
            var products = expanded.products().stream().collect(Collectors.toMap(TradeConfig.Product::id, p -> p));
            require(expanded.orderLimit() == 4 && products.get("custom_lichen").buyPrice() == 99,
                    "custom settings survive shelf migration");
            require(!products.containsKey("minecraft:glow_lichen") && !products.containsKey("minecraft:cobweb")
                    && products.get("minecraft:small_dripleaf").buyPrice() == 99, "skip alternate IDs and currencies");
            require(products.get("minecraft:sniffer_egg").requestOnly(), "new items added to existing config");
            String migrated = Files.readString(configFile);
            require(TradeConfig.load(directory).equals(expanded) && Files.readString(configFile).equals(migrated),
                    "shelf migration runs once");
        } finally {
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(directory);
        }
    }

    private static void testServiceRules(long now) {
        long minute = TradeRules.MINUTE;
        var original = new FlightPermits.Window(now - minute, now + minute);
        var queued = new FlightPermits.Window(original.end(), original.end() + minute);
        var future = new FlightPermits.Window(now + 8 * minute, now + 10 * minute);
        var elapsed = new FlightPermits.Window(now - 4 * minute, now - 2 * minute);
        var credit = FlightPermits.Credit.migrate(List.of(future, original, elapsed, queued), now);
        require(credit.equals(new FlightPermits.Credit(3 * minute, now + minute)), "keeps current and banks future");
        require(credit.add(minute).activate(now).equals(new FlightPermits.Credit(0, now + 5 * minute)),
                "activation extends the active expiry and consumes all credit");
        require(credit.activate(now + 10 * minute).equals(new FlightPermits.Credit(0, now + 13 * minute)),
                "unused time waits indefinitely before activation");
        require(new ServiceConfig.PermitPricing(15, 120, 256, 16).price(30) == 496, "permit quote");

        var single = List.of(new EnchantPricing.Power(1, 32));
        var upgraded = List.of(new EnchantPricing.Power(3, 32));
        var combined = List.of(new EnchantPricing.Power(3, 32), new EnchantPricing.Power(1, 96));
        long first = EnchantPricing.upgrade(single, upgraded);
        long second = EnchantPricing.upgrade(upgraded, combined);
        require(first > 0 && second > 96 && first + second == EnchantPricing.upgrade(single, combined),
                "batch and individual enchantments follow the same positive cumulative curve");
    }

    private static void testRecovery(long time) throws Exception {
        var directory = Files.createTempDirectory("towntrade-tests");
        try {
            var database = directory.resolve("test.sqlite");
            var deposit = new TradeStore.Pending("applied", "deposit",
                    new TradeStore.Change("player", 256L, null, null, null, null, null));
            var skipped = new TradeStore.Pending("unapplied", "deposit",
                    new TradeStore.Change("other", 256L, null, null, null, null, null));
            try (var store = new TradeStore(database)) {
                store.prepare(deposit);
                store.prepare(skipped);
            }
            try (var store = new TradeStore(database)) {
                var restored = store.recoverAndLoad((player, id) -> id.equals("applied"), time);
                require(restored.balances.get("player") == 256, "saved inventory commits its ledger delta");
                require(!restored.balances.containsKey("other"), "unapplied inventory discards the intent");
                store.commit(deposit, time);
                var replayed = store.recoverAndLoad((player, id) -> false, time);
                require(replayed.balances.get("player") == 256, "receipt deduplication");
                var permit = new FlightPermits.Credit(TradeRules.MINUTE, 0);
                store.commit(new TradeStore.Pending("permit", "permit", new TradeStore.Change("player", 0L, permit)), time);
                var closed = new TradeRules.Order("cancelled", "player", "Player", "item",
                        10, time, time, time, time + TradeRules.MINUTE, "cancelled", 4);
                store.commit(new TradeStore.Pending("cancel", "cancel", new TradeStore.Change(
                        "player", null, null, null, null, null, closed)), time);
                require(store.recoverAndLoad((player, id) -> false, time).orders.get(closed.id()).quantity() == 4,
                        "order quantity survives a reload");
                store.commit(new TradeStore.Pending("delete", "delete_order", new TradeStore.Change(
                        "player", null, null, null, null, null, null, null, closed.id())), time);
                var persisted = store.recoverAndLoad((player, id) -> false, time);
                require(persisted.balances.get("player") == 0 && persisted.permits.get("player").equals(permit),
                        "permit and balance persist in the same commit");
                require(!persisted.orders.containsKey(closed.id()), "deleted order stays deleted on reload");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean predicate, String detail) {
        if (!predicate) throw new AssertionError(detail);
    }
}
