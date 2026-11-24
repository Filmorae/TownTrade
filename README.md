# TownTrade

[简体中文](README.zh-CN.md)

<img src="src/main/resources/assets/towntrade/icon.png" alt="TownTrade trading window icon" width="96" align="right">

TownTrade is a configurable economy and trading system for Minecraft Java servers. It adds a bank, server shops that buy and sell items, timed purchase orders, flight permits, and an enchanting counter. The default economy uses Coins 3.0.0. Server operators can open counters with commands or connect them to NPC dialogue.

## Requirements

| Component | Requirement |
| --- | --- |
| Minecraft | Java Edition 26.1.2 with Java 25 |
| Loader | Fabric Loader 0.19.0 or later and Fabric API for 26.1.2. The build targets Loader 0.19.2. |
| Client and server | Install the same TownTrade version and Fabric API on the server and on each client that uses its interfaces. |
| Currency | Install Coins 3.0.0 for the default economy, or configure three registered currency items. |
| Optional integration | Easy NPC can open counters through dialogue. Serene Seasons can apply seasonal buyback prices. Catalogue items from mods that are absent are skipped. |

Put the built TownTrade JAR in each instance’s `mods/` directory. The bank and catalogue run together on a dedicated server or in a single-player world.

## Counters

| Counter | What players can do |
| --- | --- |
| Bank | Deposit and withdraw physical copper, iron, and gold coins at 1, 16, and 256 copper. The balance preview updates as quantities change. |
| SuperBuy | Buy stocked materials and potion variants, or place purchase orders for selected rarer items. Search by name, mod such as `@minecraft`, or item tag such as `#minecraft:logs`. |
| SuperSell | Sell items to the building, crops, and minerals counters. Each player has a separate regenerating quota per counter. Matching items can replenish SuperBuy stock. |
| SuperPermit | Buy flight time and bank it for later. Use `/towntrade active` to activate all banked time or extend an active permit. An active permit counts down while the player is offline. If it expires during permit flight, the player falls normally and receives protection for that landing. |
| SuperEnchant | Upgrade supported enchantments on equipment in the inventory or equipment slots. Quotes account for the item’s existing enchantments. Conflicting enchantments can coexist and curses are excluded by default. |

The default catalogue includes crops and planting items from Farmer’s Delight, Rustic Delight, Ube’s Delight, and Croptopia, including the 58 Croptopia 4.3.1 seed types and banana and cherry saplings. Search matches both English and Simplified Chinese where the item translations are available.

Stock, quotas, purchase orders, and active permits use real time and account for elapsed time while the server is paused. By default, the requester has five minutes of exclusive access after an order arrives. It then becomes available to other players at a 20% premium and expires at the end of the following calendar day in the configured time zone. A requester can cancel an order and delete the cancelled record from **My orders**.

Use the scroll wheel over a numeric field to change it by one, or hold Shift to change it by ten. The interface supports US and UK English, Simplified Chinese, and Traditional Chinese for Hong Kong/Macau and Taiwan.

## Commands

| Player command | Purpose |
| --- | --- |
| `/towntrade info` | Open your balance and flight status. Tap Shift to cycle through denominations. |
| `/towntrade active` | Activate your banked flight time, or add it to your active permit. |

The `open` commands require the **Gamemasters** permission, normally operator level 2. Use the target player’s name at the server console. In Easy NPC 7.8.1, run the command with the required privileges and use `@initiator` to target the player who pressed the dialogue button. If the dialogue closes as part of the action, place **Close Dialog** before the command.

| Counter | Easy NPC command |
| --- | --- |
| Bank | `/towntrade open bank @initiator` |
| SuperBuy | `/towntrade open buy @initiator` |
| SuperSell · building | `/towntrade open sell building @initiator` |
| SuperSell · crops | `/towntrade open sell crops @initiator` |
| SuperSell · minerals | `/towntrade open sell minerals @initiator` |
| SuperPermit | `/towntrade open permit @initiator` |
| SuperEnchant | `/towntrade open enchant @initiator` |

## Make it fit your modpack

**If you need to modify TownTrade to fit your modpack or world, please do!** The defaults reflect the economy of the server it was built for. Start with the server configuration, and see [AGENTS.md](AGENTS.md) if you want to change a rule or add an integration in the source.

| File | What to customise |
| --- | --- |
| `config/towntrade/economy.json` | Currency item IDs, product catalogue, prices, batch sizes, stock and restock, quotas, seasonal multipliers, order protection, premium, and time zone. |
| `config/towntrade/services.json` | Flight duration and pricing, enchantment weights, and whether the counter sells curses. |

The first server start creates both files. Restart after editing. Prices are per batch in copper units. A product with a buy price and zero stock capacity is available through purchase orders. Seasonal multipliers are whole percentages, with `100` as the base price. Keep product IDs stable to preserve the link to saved stock and orders. The three currency item IDs are configurable. Changing the number of denominations or their 1/16/256 exchange rates requires a source change.

## World saves and backups

TownTrade stores its ledger at `<world>/towntrade/economy.sqlite`. When backing up or moving a world, stop the server and copy the ledger together with the world and player data. Keep `config/towntrade/` with the backup to preserve your counter settings.

## Build

Use JDK 25 and the included Gradle Wrapper:

```sh
./gradlew build
```

On Windows, run `gradlew.bat build`. The JAR is written to `build/libs/` and bundles SQLite JDBC. The first build needs access to the Gradle, Fabric, Mojang, and Maven Central repositories.

## Credits and licence

- **Filmorae** · Concept and testing
- **ChatGPT** · Code development, testing, and design

[MIT License](LICENSE)
