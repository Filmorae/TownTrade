# TownTrade

[English](README.md)

<img src="src/main/resources/assets/towntrade/icon.png" alt="TownTrade 交易窗口图标" width="96" align="right">

TownTrade 是一套面向 Minecraft Java 服务器的可配置交易与经济系统。它提供银行、买卖柜台、限时求购、飞行许可和附魔柜台，默认使用 Coins 3.0.0 货币。管理员可以通过命令打开柜台，也可以把柜台接入 NPC 对话。

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
| SuperBuy | 购买常备材料和各规格药水，或对指定的稀有物品发起求购。搜索支持名称、`@minecraft` 等模组筛选和 `#minecraft:logs` 等物品标签。 |
| SuperSell | 向建材、作物、矿物柜台出售物品。每位玩家在各柜台拥有独立的恢复型额度。匹配的物品可以补入 SuperBuy 库存。 |
| SuperPermit | 购买飞行时长并留待使用。`/towntrade active` 会激活全部待激活时长，已有许可时会顺延。激活后离线照常计时。使用许可飞行时若遇到期，玩家会自然坠落，并免除当次落地摔伤。 |
| SuperEnchant | 为背包或装备栏里的装备升级支持的附魔。报价会考虑原有附魔，允许互斥附魔共存，默认排除诅咒。 |

默认目录包含 Farmer’s Delight、Rustic Delight、Ube’s Delight 和 Croptopia 的作物及种植材料，其中包括 Croptopia 4.3.1 的 58 种种子，以及香蕉和樱桃树苗。物品有对应翻译时，可以用简体中文或英文搜索。

补货、额度恢复、求购单和已激活的许可按现实时间计算，包含空服暂停期间。默认情况下，求购货品到货后由求购者独享五分钟，之后其他玩家可加价 20% 购买，并在所设时区的到货次日结束时下架。求购者可以取消订单，并在「我的求购」中删除已取消的记录。

在数字控件上滚动鼠标，每格增减 1，按住 Shift 时增减 10。界面支持美式英语、英式英语、简体中文、港澳繁体及台湾繁体。

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

首次启动服务端时会创建这两份文件。编辑后重启生效。价格是每批所需的铜币数。设有购买价、库存上限为零的物品走求购流程。季节倍率为整数百分比，`100` 是原价。保留商品 ID，已有库存和求购单才能继续对应原来的商品。三种货币的物品 ID 可以配置，币种数量及 1/16/256 的面值需要改源码。

## 存档与升级

账本位于 `<世界目录>/towntrade/economy.sqlite`。升级前请停服并备份世界，备份应同时包括账本和玩家数据。若要保留自定义柜台设置，也请保留配置文件。升级时同步替换客户端和服务端的 JAR。

- **0.3.0 目录迁移**会向旧配置补充种植材料及部分模组作物的零售项，保留已有收购设置和自定义零售项。
- **0.4.0 飞行许可迁移**会保留正在生效的许可的到期时间，并将未来的预约转为等长的待激活时长。若要退回 0.3.0，请恢复升级前的世界备份。

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
