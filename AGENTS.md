# Maintaining TownTrade

TownTrade targets Minecraft Java 26.1.2, Fabric, and JDK 25. The mod ID and command namespace are `towntrade`. This guide is for maintainers and automated contributors adapting TownTrade to a server or modpack. The default prices, item mix, and integrations came from Filmorae’s server. Use runtime configuration when it covers the requested change.

## Project map

| Area | Entry points |
| --- | --- |
| Boot, commands, and event registration | `src/main/java/dev/geduu/towntrade/TownTrade.java` |
| Server-side quotes, transactions, sessions, and timers | `TradeService`, `TradeRules`, `InventoryPlan` |
| SQLite, recovery, and world migrations | `TradeStore`, `PlayerTransactionTag`, the mixins in `src/main/java/dev/geduu/towntrade/mixin/` |
| Server configuration and retail migration | `TradeConfig`, `ServiceConfig`, `CatalogRetailUpgrade`, `src/main/resources/towntrade-economy.json` |
| Optional item and season support | `TradeItems`, `SeasonPrices` |
| Flight and enchanting | `FlightPermits`, `EnchantDesk`, `EnchantPricing` |
| Client/server protocol | `TradeWire` in the main source set, `TradeClient` in the client source set |
| Client UI | `src/client/java/dev/geduu/towntrade/`, including `TradeScreen`, `BalanceScreen`, `PermitScreen`, and `EnchantScreen` |
| Client search and presentation | `OfferSearch`, `NumberScroll`, `CurrencyDisplay`, and `src/main/resources/assets/towntrade/lang/` |
| Tests | `src/test/java/dev/geduu/towntrade/EconomyRulesTest.java` |

Fabric Loom separates common and client sources. Keep client-only Minecraft classes in `src/client/java` so dedicated servers can load the common classes. The package name is `dev.geduu.towntrade`. Public in-game credits and the icon are in `src/main/resources/fabric.mod.json` and `src/main/resources/assets/towntrade/icon.png`.

## Adapting the economy

| Goal | Start here |
| --- | --- |
| Change a live server’s economy | Edit `config/towntrade/economy.json` in that server’s instance and restart. Change the bundled `towntrade-economy.json` to ship new defaults for fresh installs. |
| Add or remove products | Use unique, stable product IDs and fully qualified item IDs. Use `potion` when the catalogue row represents a particular potion variant. Absent mod items are skipped when the server resolves the catalogue. |
| Change buy, sell, or restock rules | `buyPrice` and `sellPrice` are copper per transaction batch. `buyBatch` and `sellBatch` count items. `capacity` and `restock` also count items. Set `capacity` to zero on a positively priced retail entry to make it order-only. `minutes` sets restock or order-arrival time and currently validates from 10 to 20. |
| Change quota or order settings | The economy configuration controls pool capacity and points per minute, per-batch quota cost, order limits, protection minutes, premiums, and the expiry time zone. The existing pool keys are `building`, `crops`, and `minerals`. |
| Replace Coins with another item mod | Change the three `coins[].item` identifiers on the server. The current denomination order and values are fixed to 1, 16, and 256. Updating the exchange system means updating validation, server bank handling, currency labels, `TradeScreen` quotes, and `BalanceScreen` decomposition together. Plan a balance migration if the base unit’s value changes. |
| Add a category or shop pool | Look at the fixed SuperBuy tab order in `TradeScreen`, the `TradeRules.Pool` enum, command registration in `TownTrade`, default config, and language files. |
| Adjust optional integrations | The bundled crop list is an explicit catalogue. `SeasonPrices` reads the overworld season reflectively when Serene Seasons is present. Missing or incompatible season APIs fall back to base prices. Easy NPC dialogue actions call commands and require the same permissions as other counter entry points. |
| Adjust flight or enchanting | Edit `config/towntrade/services.json` for flight price/ranges, enchantment weights, and curse policy. `ServiceConfig` creates that file on first run. Change `FlightPermits` or `EnchantPricing` for game-rule changes. |

`TradeConfig` checks the highest seasonal buyback rate against the lowest comparable retail rate by cross-multiplying batch sizes. Keep that invariant when changing default prices. Stock linkage keys on the actual item and potion variant, while persisted stock and order references use the product ID. Retain existing IDs or migrate their references when changing the catalogue. The `retailCatalogVersion` field tracks the one-time additions from 0.3.0. Changing defaults alone only affects newly generated configs.

## Server authority and persistence

- The server computes quotes, checks balances, stock, quotas, order ownership, and actual inventory contents before a transaction. The client’s quantity limits are previews. The protocol pairs each request with the current view’s token and a monotonically increasing sequence number. Keep the `open` command guarded by the Gamemasters permission. `info` and `active` operate on the invoking player.
- `TradeWire` uses versioned Fabric payload IDs and JSON records for offers, board state, and actions. Coordinate both source sets when changing packet fields. Change payload IDs for incompatible clients and verify handling of missing or mismatched client versions. Keep client actions localised via `towntrade.*` keys and update all five translations with matching placeholders. Vanilla and modded item names come from the client’s resource packs.
- The server thread owns live world and inventory state. The single SQLite writer owns durable work. Inventory-changing deals prepare a journal entry, recheck and apply an `InventoryPlan` on the server thread, save a player NBT transaction marker, then commit the ledger change and receipt. Recovery uses the saved player marker to distinguish applied and unapplied transactions. Preserve idempotence and the order of these steps. Back up the world ledger and player data together before changing the schema.
- Timed state derives from epoch milliseconds. Stock and quota catch up after a pause, and order expiry uses the configured civil time zone. The first landing after an airborne permit revocation is protected once. The player data mixins clear temporary vanilla flight abilities on disk and retain the information needed to handle reconnects. Keep creative and spectator abilities distinct from TownTrade’s temporary abilities.
- Flight purchases add to unused credit and do not change the existing expiry until activation. Activation consumes the total unused credit and extends a running permit or starts one at the current time. The 0.4.0 migration converts legacy SQLite window arrays into credit records. An already started window keeps its expiry and future windows become unused credit.

## Build and verify

- Run `./gradlew build` with JDK 25. The Java `economyRulesTest` task is attached to `test` and can also be run directly. It covers pricing, timers, permits, journal recovery, and order deletion. Fabric Loom packages the client classes and the SQLite JDBC dependency in the mod JAR. Preserve the executable bit on `gradlew`.
- Use a clean temporary world for manual checks. Start a dedicated server with Fabric API and the same TownTrade JAR as the client. Install Coins 3.0.0 or configure equivalent registered items. Check the player commands, administrator-targeted counters, deposit/withdrawal, purchase and buyback, and the affected UI and language. Add optional mods when testing their integration.
- For economy or data changes, exercise simultaneous operations, rejected deals, restart persistence, and migration from a representative older save. For flight changes, check activation, extension, reconnect, midair expiry, and a normal second fall. For mods or item catalogue changes, test both with and without the optional dependency.
