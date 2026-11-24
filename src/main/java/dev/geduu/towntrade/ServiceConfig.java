package dev.geduu.towntrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.resources.Identifier;

record ServiceConfig(PermitPricing permit, boolean sellCurses, Map<String, Integer> enchantWeights) {
    record PermitPricing(int minimumMinutes, int maximumMinutes,
                         int minimumPrice, int copperPerExtraMinute) {
        long price(int minutes) {
            return minimumPrice + (long) (minutes - minimumMinutes) * copperPerExtraMinute;
        }
    }

    static ServiceConfig load(Path dir) throws IOException {
        var file = dir.resolve("services.json");
        if (!Files.exists(file)) {
            Files.writeString(file, """
                    {
                      "permit": {
                        "minimumMinutes": 15,
                        "maximumMinutes": 120,
                        "minimumPrice": 256,
                        "copperPerExtraMinute": 16
                      },
                      "sellCurses": false,
                      "enchantWeights": {
                        "minecraft:mending": 96,
                        "minecraft:swift_sneak": 72,
                        "minecraft:wind_burst": 88
                      }
                    }
                    """);
        }
        var config = TradeConfig.JSON.fromJson(Files.readString(file), ServiceConfig.class);
        var permit = config.permit();
        if (permit.minimumMinutes() < 1 || permit.maximumMinutes() < permit.minimumMinutes()
                || permit.maximumMinutes() > 1440
                || permit.minimumPrice() < 256
                || permit.minimumPrice() > 100_000 || permit.copperPerExtraMinute() < 1
                || permit.copperPerExtraMinute() > 10_000) {
            throw new IllegalArgumentException("Invalid SuperPermit prices or durations");
        }
        for (var weight : config.enchantWeights().entrySet()) {
            if (Identifier.tryParse(weight.getKey()) == null || weight.getValue() < 1 || weight.getValue() > 4096) {
                throw new IllegalArgumentException("Invalid enchantment weight: " + weight.getKey());
            }
        }
        return config;
    }
}
