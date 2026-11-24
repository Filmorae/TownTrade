package dev.geduu.towntrade;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Locale;

final class TradeRules {
    static final long MINUTE = 60_000;
    static final long POINT = 60_000;
    static final long MAX_BALANCE = 1_000_000_000;
    static final long NO_TIME = Long.MAX_VALUE;

    enum Pool {
        BUILDING, CROPS, MINERALS;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Stock(int count, long next) {}
    record Quota(long units, long at) {}
    record PoolRule(int capacity, int pointsPerMinute) {
        long ceiling() {
            return capacity * POINT;
        }
    }

    record Order(String id, String owner, String ownerName, String product, long price,
                 long placed, long arrives, long protectsUntil, long expires, String status) {
        boolean active(long now) {
            return status.equals("active") && now < expires;
        }

        boolean arrived(long now) {
            return now >= arrives;
        }

        String displayStatus(long now) {
            if (!status.equals("active")) return status;
            if (now >= expires) return "expired";
            if (now < arrives) return "waiting";
            return now < protectsUntil ? "protected" : "available";
        }

        Order closed(String outcome) {
            return new Order(id, owner, ownerName, product, price, placed, arrives, protectsUntil, expires, outcome);
        }
    }

    static Stock replenish(Stock old, int capacity, int batch, long interval, long now) {
        if (old == null || old.count() >= capacity) return new Stock(capacity, NO_TIME);
        if (old.next() == NO_TIME) return new Stock(old.count(), now + interval);
        if (now < old.next()) return old;
        long elapsed = (now - old.next()) / interval + 1;
        int missing = capacity - old.count();
        if (elapsed >= (missing + batch - 1L) / batch) return new Stock(capacity, NO_TIME);
        return new Stock(old.count() + (int) (elapsed * batch), old.next() + elapsed * interval);
    }

    static Stock stockAfter(Stock current, int delta, int capacity, long interval, long now) {
        int count = Math.min(capacity, current.count() + delta);
        long next = current.next() == NO_TIME ? now + interval : current.next();
        return new Stock(count, count == capacity ? NO_TIME : next);
    }

    static long quotaNow(Quota quota, PoolRule rule, long now) {
        if (quota == null || quota.units() >= rule.ceiling()) return rule.ceiling();
        long elapsed = Math.max(0, now - quota.at());
        long needed = rule.ceiling() - quota.units();
        if (elapsed >= ceilDiv(needed, rule.pointsPerMinute())) return rule.ceiling();
        return quota.units() + elapsed * rule.pointsPerMinute();
    }

    static long ceilDiv(long numerator, long denominator) {
        return numerator / denominator + (numerator % denominator == 0 ? 0 : 1);
    }

    static long sellPrice(long base, int percentage) {
        return Math.max(1, (base * percentage + 50) / 100);
    }

    static long publicPrice(long price, int premiumPercent) {
        return price + Math.max(1, ceilDiv(price * premiumPercent, 100));
    }

    static long orderExpiry(long arrives, ZoneId timezone) {
        return Instant.ofEpochMilli(arrives).atZone(timezone).toLocalDate()
                .plusDays(2).atStartOfDay(timezone).toInstant().toEpochMilli();
    }

    static String permitDuration(long millis) {
        long seconds = ceilDiv(Math.max(0, millis), 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private TradeRules() {}
}
