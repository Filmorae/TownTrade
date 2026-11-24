package dev.geduu.towntrade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

final class EnchantPricing {
    static final int MAX_CHOICES = 12;

    record Power(int level, int weight) {}

    private EnchantPricing() {}

    static long value(Collection<Power> profile) {
        long total = 0;
        int count = 0;
        for (var power : profile) {
            if (power.level() <= 0 || power.weight() <= 0) continue;
            total += (long) power.level() * power.level() * power.weight();
            count++;
        }
        if (count == 0) return 0;
        long extra = count - 1L;
        return total * (100 + 18 * extra + 4 * extra * extra) / 100;
    }

    static long quote(TradeWire.EnchantView view, Map<String, Integer> levels) {
        var result = new ArrayList<>(view.powers());
        for (var option : view.options()) {
            Integer level = levels.get(option.id());
            if (level == null) continue;
            if (option.level() > 0) result.remove(new Power(option.level(), option.weight()));
            result.add(new Power(level, option.weight()));
        }
        return upgrade(view.powers(), result);
    }

    static long upgrade(Collection<Power> current, Collection<Power> result) {
        return value(result) - value(current);
    }
}
