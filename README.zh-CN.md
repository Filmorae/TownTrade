# TownTrade

[English](README.md)

<img src="src/main/resources/assets/towntrade/icon.png" alt="TownTrade 交易窗口图标" width="96" align="right">

TownTrade 是一套面向 Minecraft Java 服务器的可配置交易与经济系统。它提供银行、买卖柜台、限时求购、飞行许可、附魔柜台和可建在世界里的公共交易看板，默认使用 Coins 3.0.0 货币。管理员可以通过命令打开柜台，也可以把柜台接入 NPC 对话。

## 安装要求

| 组件 | 要求 |
| --- | --- |
| Minecraft | Java 版 26.1.2，使用 Java 25 |
| 加载器 | Fabric Loader 0.19.0 或更新版本，以及对应 26.1.2 的 Fabric API。项目以 Loader 0.19.2 为构建目标。 |
| 客户端和服务端 | 服务端和使用面板的客户端均需安装 Fabric API 及相同版本的 TownTrade。 |
| 货币 | 使用默认配置时安装 Coins 3.0.0，也可配置三个已注册物品作为货币。 |
| 可选集成 | Easy NPC 可从对话打开柜台，Serene Seasons 可为收购价应用季节倍率。目录会跳过来自未安装模组的物品。 |

把构建出的 TownTrade JAR 放进相应实例的 `mods/` 目录。专用服务器和单人世界均可使用。

## 柜台

| 柜台 | 功能 |
| --- | --- |
| 银行 | 存取实体铜、铁、金币，面值分别为 1、16、256 铜。调整数量时可预览交易后余额。 |
| SuperBuy | 购买常备材料、自然装饰和各规格药水，或求购桶装生物、收藏品和稀有货品。搜索支持名称、`@minecraft` 等模组筛选和 `#minecraft:logs` 等物品标签。 |
| SuperSell | 向建材、作物、矿物柜台出售物品。每位玩家在各柜台拥有独立的恢复型额度。匹配的物品可以补入 SuperBuy 库存。 |
| SuperPermit | 购买飞行时长并留待使用。`/towntrade active` 会激活全部待激活时长，已有许可时会顺延。激活后离线照常计时。使用许可飞行时若遇到期，玩家会自然坠落，并免除当次落地摔伤。 |
| SuperEnchant | 为背包或装备栏里的装备升级支持的附魔。报价会考虑原有附魔，允许互斥附魔共存，默认排除诅咒。 |

默认目录包含 Farmer’s Delight、Rustic Delight、Ube’s Delight 和 Croptopia 的作物及种植材料，其中包括 Croptopia 4.3.1 的 58 种种子，以及香蕉和樱桃树苗。物品有对应翻译时，可以用简体中文或英文搜索。

每张求购单可选 1–5 批，受单笔交易的物品数量上限约束。默认求购商品每批为 1 件。第 1–5 批分别按基础批价的 100%、110%、120%、130% 和 140% 计价，合计后向上取整到铜币并锁价。购买时一次结清并领取整单。

补货、额度恢复、求购单和已激活的许可按现实时间计算，包含空服暂停期间。默认情况下，求购货品到货后由求购者独享五分钟，之后其他玩家可加价 20% 购买，并在所设时区的到货次日结束时下架。「我的求购」显示进行中、可购买、已完成、已取消和已过期状态。求购者可以删除已完成及已取消的记录。下界之星的新求购基础批价为 5 金，已下单的锁定价格照旧。

在数字控件上滚动鼠标，每格增减 1，按住 Shift 时增减 10。界面支持美式英语、英式英语、简体中文、港澳繁体及台湾繁体。

大型蕨和高草丛按现货销售，默认各有 48 件库存上限，每 15 分钟补货 12 件。改为现货前下的订单仍可按锁定价格领取。

## 城镇交易看板

把面板铺成朝向一致的矩形。从正面潜行右键两个对角即可组装，空手或手持面板都可以。9×6 看板用 54 块面板。支持宽 3–9 格、高 2–6 格且比例在 4:3 至 2:1 之间的矩形。拆下一块后，剩余面板可重新组装。

面板在创造背包的「功能方块」栏中。也可以用一排三个白色染色玻璃板，下方放铁粒、红石粉、铁粒，合成六块面板。管理员可用 `/give @s towntrade:board_panel 54` 取一整面 9×6 的用量。

总览显示 SuperBuy、SuperSell、SuperPermit、SuperEnchant 的服务状态及最近三项补货。存在有效求购时，看板会在总览与求购公告之间轮播，每页三条。公告显示求购者、公开价、预计到货时间、保护截止和公开购买截止时间。成交、取消和过期后，公告会撤下。购买和领取仍在 SuperBuy 柜台办理。

支持 LabPBR 的光影可读取屏面的自发光贴图，例如启用了 `SPECULAR_MAPPING` 的 Photon。Flashback 回放会显示录制时保存的公开数据和预计时间。

## 命令

| 玩家命令 | 功能 |
| --- | --- |
| `/towntrade info` | 查看自己的余额和飞行状态。轻按 Shift 可切换币种。 |
| `/towntrade active` | 激活待使用的飞行时长，或延长正在生效的许可。 |

`open` 命令需要 **Gamemasters** 权限，通常对应 OP 等级 2。从服务端控制台执行时可填写玩家名。在 Easy NPC 7.8.1 中，以相应权限运行命令，并用 `@initiator` 指向点击对话按钮的玩家。如果操作需要关闭对话，把 **Close Dialog** 放在命令之前。

| 柜台 | Easy NPC 命令 |
| --- | --- |
| 银行 | `/towntrade open bank @initiator` |
| SuperBuy | `/towntrade open buy @initiator` |
| SuperSell · 建材 | `/towntrade open sell building @initiator` |
| SuperSell · 作物 | `/towntrade open sell crops @initiator` |
| SuperSell · 矿物 | `/towntrade open sell minerals @initiator` |
| SuperPermit | `/towntrade open permit @initiator` |
| SuperEnchant | `/towntrade open enchant @initiator` |

## 适配你的整合包

**如果需要修改 TownTrade 来适配你的整合包或世界，欢迎这样做！** 默认配置来自项目最初使用的服务器。可以从服务器配置入手。如果想改交易规则或扩展兼容性，请参考供维护者和自动化工具使用的 [AGENTS.md](AGENTS.md)。

| 文件 | 可以调整的内容 |
| --- | --- |
| `config/towntrade/economy.json` | 货币物品 ID、商品目录、价格、批量、库存、补货、额度、季节倍率、求购保护期、加价和时区。 |
| `config/towntrade/services.json` | 飞行时长和价格、附魔权重，以及是否出售诅咒。 |

首次启动服务端时会创建这两份文件。编辑后重启生效。价格是每批所需的铜币数。设有购买价、库存上限为零的物品走求购流程。季节倍率为整数百分比，`100` 是原价。保留商品 ID，已有库存和求购单才能继续对应原来的商品。更新后首次启动时，仍采用旧默认设置的绿宝石及远古残骸商品会迁移到新的矿物目录。远古残骸商品移除后，对应旧单无法提货。旧单在提货时才扣款，因此无需退款。货架扩展也会一次性为旧配置补入缺失的新商品，保留既有商品 ID 和物品对应的自定义条目，并跳过配置中的货币物品。三种货币的物品 ID 可以配置，币种数量及 1/16/256 的面值需要改源码。

## 世界存档与备份

TownTrade 的账本位于 `<世界目录>/towntrade/economy.sqlite`。备份或迁移世界时，请先停服，再一并复制世界、玩家数据和账本。若要保留自定义柜台设置，也请备份 `config/towntrade/`。

## 构建

使用 JDK 25 和仓库自带的 Gradle Wrapper：

```sh
./gradlew build
```

Windows 下运行 `gradlew.bat build`。生成的 JAR 位于 `build/libs/`，并附带 SQLite JDBC。首次构建需要访问 Gradle、Fabric、Mojang 和 Maven Central 的仓库。

## 署名与许可证

- **Filmorae** · 创意、测试
- **ChatGPT** · 代码开发、测试、设计

[MIT 许可证](LICENSE)
