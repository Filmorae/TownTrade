package dev.geduu.towntrade;

import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

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
        require(TradeRules.publicPrice(36, 20) == 44 && TradeRules.publicPrice(1, 20) == 2, "public premium");
        require(TradeRules.orderExpiry(time, ZoneId.of("Asia/Shanghai"))
                == Instant.parse("2026-11-24T16:00:00Z").toEpochMilli(), "next civil day expires");
        var order = new TradeRules.Order("order-id", "player", "name", "example", 10, time, time + minute,
                time + 6 * minute, time + 100 * minute, "active");
        require(order.displayStatus(time).equals("waiting"), "waiting");
        require(order.displayStatus(time + minute).equals("protected"), "first moment of protection");
        require(order.displayStatus(time + 6 * minute).equals("available"), "end of protection");
        require(order.displayStatus(time + 100 * minute).equals("expired"), "end of listing");
        require(order.closed("bought_by_other").displayStatus(time).equals("bought_by_other"), "closed");

        testServiceRules(time);
        testRecovery(time);
        System.out.println("Town Trade economy rules and journal recovery passed");
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
                        10, time, time, time, time + TradeRules.MINUTE, "cancelled");
                store.commit(new TradeStore.Pending("cancel", "cancel", new TradeStore.Change(
                        "player", null, null, null, null, null, closed)), time);
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
