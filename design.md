# 自动附魔插件 设计文档

ZenithProxy 插件 `auto-enchant` 的完整设计与实现说明。本文是项目唯一的设计文档：早期的需求文档与三轮重构方案已经合并进来，只保留与当前代码一致的内容。实测数据（耗时、开窗 miss）都来自本仓库部署在真实服务器上的运行日志。

- 目标游戏版本：Minecraft Java 1.21.4（`gradle.properties` 的 `mc`，另有 1.21.11 数据集）
- 运行宿主：ZenithProxy 3.8.1+，插件加载于进程内，构建用 Java 25
- 产物：`build/libs/ZenithProxyAutoEnchant-<version>.jar`

## 1. 目标与范围

在无人值守的情况下，把容器里的待附魔装备做成模板指定的附魔成品：

1. 扫描附近的铁砧、砂轮、展示框和容器，形成「作业」（一种装备一条）与「站点」（铁砧、砂轮、书箱、取货箱、卸货箱）。
2. 按批次取货，整批比对模板，需要祛魔的件在一个砂轮窗口里一次磨完。
3. 按批次当前步骤取附魔书（不多取、不塞满背包），在铁砧上按预设合并树逐步合并，每步以服务端预览为准。
4. 经验不足时只开关本体 KillAura 刷怪，够用就停。
5. 成品、半成品、失败的件都有明确的去处；单件失败不影响队列。

不在范围内：投掷经验瓶/红石经验机监控、附魔书的分级合成（只按目标等级取书）、跨维度搬运、多人协作或权限系统。

## 2. 站点布置：展示框即声明

布置方式决定作业从哪来。**一切都由展示框声明，不需要手工配坐标**（手工坐标仍然优先，见 §4）。所有展示框都遵守同一条容器解析规则：**先看展示框贴附的方块**——框直接贴在一个容器上时就用这个容器；贴附的方块不是容器时，**再从展示框所在方块垂直向下找第一个容器**（箱子、陷阱箱、木桶、潜影盒，最多 `SUPPLY_SCAN_DEPTH = 8` 格）。因此框可以贴在箱子正面、挂在箱子上方、放架子上，都不依赖朝向。

| 展示框里放的物品 | 角色 | 绑定到什么 |
| --- | --- | --- |
| 可耐久装备（剑、镐、盔甲…） | 作业模板 | 框里的附魔与名称导入作业目标；框贴附/下方的容器只在该平台没有红石块标记时作为该作业 `outputChest` 的兜底 |
| 附魔书（单条附魔） | 书箱 | 贴附/下方第一个容器 = `enchantBook[附魔id]` |
| 铁砧 / 损坏的铁砧 / 开裂的铁砧 | 铁砧补给 | 贴附/下方第一个容器 = `anvilChest` |
| 红石粉 | 共享白板取货箱 | 贴附/下方第一个容器 = **所有作业**的 `inputItemChest` |
| 红石块 | 共享成品卸货箱 | 贴附/下方第一个容器 = **所有作业**的 `outputChest`（成品、半成品、失败件共用） |
| 空框 | 装饰 | 忽略 |

- **展示框本身就是作业声明和开关**：不再读红石灯。扫描到成品模板框就为该装备生成或更新一条 config（各自的附魔集合 + 自定义名）；模板框还在平台上 → `enabled=true`，模板框被拿走 → 只把该条 `enabled=false`，**绝不删除**，重新挂回模板即可原样恢复。`scan` 只新增和更新，不清理旧条目。
- 一个平台只有一套共享取货/卸货箱：白板取货箱、成品卸货箱各一个，书箱按附魔种类各一个（12 种附魔 = 12 个容器），铁砧补给箱一个（铁砧框标记），另外扫描最近的铁砧和砂轮作为站点。
- 同一物品出现两个模板框：报冲突，保留第一个，不抢容器。
- 只有带 `max_damage` 的物品才算模板，装饰性展示框不会凭空生成作业。
- 扫描只检查已加载区块；每次真正操作前复核方块、容器和库存，坐标失效不会静默换箱子（见 §13）。
- `scan` 只在模板**真的变了**（附魔词条 + 名称的签名变化）时重新导入，所以手改 `outputEnchants add/del` 不会被周期扫描覆盖；想回到模板的定义，改展示框里的物品即可。

## 3. 配置模型

配置文件 `plugins/config/Auto Enchant.json`，由 commands 维护（插件在启动时加载，热重载在事务边界生效）。

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | false | 总开关；配置阶段保持关闭 |
| `enchants` | {} | 作业表：`key -> Enchant`，key 由扫描（物品 id）或 `add` 指定 |
| `enchantBook` | {} | `附魔id -> 书箱坐标`，扫描写入 |
| `ExpFarmSpot` | ZERO | 刷经验站位；**就是工作站位**，见 §9 |
| `grindstone` | ZERO | 砂轮坐标，未配置则不做祛魔 |
| `anvil` | ZERO | 铁砧放置点，未配置则只在现存铁砧上工作 |
| `anvilChest` | ZERO | 铁砧补给箱 |
| `anvilRestockStacks` | 1 | 铁砧损坏时从补给箱取几组 |
| `batchSize` | 10 | 一批多少件；ROW 受 36 格限制通常 4，STEP 可到 10~11 |
| `batchOrder` | ROW | `ROW` 一件做到底 / `STEP` 全批推进一步 |
| `renameOnly` | false | 只改名不附魔 |
| `actionDelay` | 5 tick | 两次改状态交互之间的基础间隔 |
| `restartDelay` | 5 tick | 失败/断线后的退避起点 |
| `openRetryTicks` | 4 tick | 开窗点击无响应后重发的等待，见 §11 |
| `waitForInteractTimeoutTicks` | 20 tick | 单次交互（开/点/关）的兜底等待 |
| `killWindowTicks` / `killWindowIntervalTicks` / `killWindowMinKills` | 40 / 60 / 2 | 杀戮窗口，见 §9 |
| `scanHorizontalBlocks` / `scanVerticalBlocks` | 8 / 8 | 扫描半径 |
| `experienceSource` | KILL | `NONE` / `KILL`（KillAura）/ `EXP` |
| `logTradeStatusToDiscord` | false | 把状态推到 Discord（沿用宿主 webhook 配置） |

作业条目 `Enchant`：

| 字段 | 说明 |
| --- | --- |
| `enabled` | 是否参与轮转 |
| `item` | 目标物品 id（短 id） |
| `inputItemChest` | 取货箱；红石粉框（共享白板取货箱）会覆盖它 |
| `outputChest` | 卸货箱（成品 + 半成品 + 失败件）；红石块框（共享成品卸货箱）会覆盖它 |
| `renameName` | 改名目标，空串 = 不改名 |
| `postEnchantStoreMode` | `NONE` 留在背包重试 / `TO_RESTOCK` 半成品回取货箱 / `TO_OVERFLOW` 半成品去卸货箱 |
| `itemEnchantments` | 目标附魔集合 `附魔id -> 等级`；由模板导入，也可用 `outputEnchants` 编辑 |

坐标一律是 `BlockPos`（`{"x":0,"y":0,"z":0}` = 未设置），旧配置里的 `"x,y,z"` 字符串由 `AutoEnchantConfigSerializer` 读取时转换，`jobs`/`targets`/`inputItemRestockStacks` 等旧字段在首次加载时迁移。

```json
{
  "enabled": true,
  "batchSize": 10,
  "batchOrder": "STEP",
  "experienceSource": "KILL",
  "ExpFarmSpot": { "x": 120, "y": 64, "z": -30 },
  "anvil": { "x": 118, "y": 64, "z": -30 },
  "enchants": {
    "diamond_sword": {
      "enabled": true,
      "item": "diamond_sword",
      "inputItemChest": { "x": 116, "y": 64, "z": -30 },
      "outputChest": { "x": 122, "y": 64, "z": -30 },
      "renameName": "Blade",
      "postEnchantStoreMode": "TO_RESTOCK",
      "itemEnchantments": {
        "sharpness": 5, "looting": 3, "sweeping_edge": 3,
        "unbreaking": 3, "mending": 1, "fire_aspect": 2, "knockback": 2
      }
    }
  },
  "enchantBook": { "sharpness": { "x": 124, "y": 64, "z": -30 } }
}
```

## 4. 目标集合：预设还是自定义

模板框里的附魔就是目标集合（`importFrameSpec`：词条 → `itemEnchantments`，名称 → `renameName`，截断 50 字符）。运行时用 `catalog.matchPreset(item, target)` 判定：

- 集合与某个 Wiki 变体的**最终集合完全相等** → 走该预设的合并树（`PRESET`），重排为零成本的最优顺序；
- 否则 → 用 `CustomEnchantPlanner` 自算最优树（`CUSTOM`）；
- 集合为空 → 没有目标，不开始事务（只改名模式下例外）。

模板没有附魔时不会覆盖已有目标，因此「先配好目标、再放模板」是安全的。

## 5. 预设数据与合并树

`src/main/resources/best-enchantments-<mc>.json`，来源是[中文 Wiki 最佳附魔指南](https://zh.minecraft.wiki/w/Tutorial:%E6%9C%80%E4%BD%B3%E9%99%84%E9%AD%94%E6%8C%87%E5%8D%97)。结构：

```json
{
  "dataVersion": "1.21.4",
  "plans": {
    "sword": {
      "display": "剑",
      "targetPlaceholder": "sword",
      "variants": {
        "all": { "display": "...", "totalLevels": 45, "minXp": 753, "expression": "{[(sword + ...)@k + (...)]@k}" }
      }
    }
  },
  "conflicts": [["minecraft:sharpness", "minecraft:smite", "minecraft:bane_of_arthropods"], ...]
}
```

- 启动时按构建目标（`mc`）挑数据集并校验 `dataVersion`，不匹配拒绝加载；1.21.4 不含矛与突进，1.21.11 数据集保留。
- 表达式语法：`expression := term ("+" term)*`，`term := token | (expression) | [expression] | {expression}`，节点可带 `@整数` 费用注记，token 末尾 `_整数` 是附魔等级。遍历按后序，不重排合并树：书与书的预合并先完成，装备只在占位符节点加入。
- `totalLevels` / `minXp` 只用于自检与 dry-run 参考，运行时门槛以服务端铁砧预览为准。
- 费用模型：先前工作惩罚 `2^n − 1`（n = 该物品被铁砧操作次数），牺牲侧附魔按最终等级 × 书的倍率计费，结果惩罚取 `max` 两侧 + 1，单人模式 39 级封顶。改名不算一次 anvil use。
- 续做：`resumePlan` 要求装备当前附魔集合能对上树中某个已完成节点，已完成的子树整棵复用、只补缺的分支。对不上任何节点（被外部改过、只有某个合并对的半个子集）就判为不匹配，卸到半成品，绝不为了"补齐"自行改序或逐本硬叠。

## 6. 祛魔

诅咒（绑定/消失）不祛魔也不附魔，取进来随批次卸货——砂轮不会移除诅咒。

**PRESET**：预设树是为白板设计的。逐件比较，只有带模板外附魔（含等级超出目标）的件进砂轮；白板件和附魔全在模板内的件按已有附魔续做。

祛魔是**批次阶段**（`BATCH_GRIND`）：一批取齐后先整批判定（每件一行 `batch grind check: slot N <item> -> <原因>`），需要清理的件在**同一个砂轮窗口**里连续做完——放件、取结果、直接放下一件，不关窗、不重走、不重复等待；全部磨完再进入取书和铁砧阶段。规则：

- 砂轮一次只放一件、只占一个输入框，另一个框必须为空；发现别的东西先点回背包，两个框都满即进入修复流程，绝不取它的输出。
- 结果必须核实附魔已全部清除：批量口径用「背包该物品白板数 +1」确认，避免把上一件磨好的同款误认成本件结果（同款白板会撞脸）。
- 砂轮方块不可达、GUI 打不开、输出仍有附魔 → 该件卸货，批次继续。
- 砂轮会重置先前工作惩罚，但仍要读取输出实测值，不沿用预计值。

**CUSTOM**：读取现有附魔、真实惩罚、目标集合与冲突表，分别算"保留现有附魔直接追加"和"磨白重做"的预计总费用；出现阻止必需目标的冲突、直接方案超上限，或重做至少便宜 `grindstoneThreshold` 级时才磨。

## 7. 改名

名称写进铁砧的名称框，效果等同玩家手动输入。两条实测结论决定了实现：

1. 改名本身**不算一次 anvil use**，不增加惩罚；但改名费 = 1 级 + 当前惩罚。所以名字要尽早写：第一次装备合并时惩罚为 0，只花 1 级；放到最后一次等于付 `合并费 + 已累积惩罚 + 1`，还会把接近 39 级的最后一步顶到"过于昂贵"。
2. **名字会在下一次装备合并时被抹掉**（结果名取自名称框，空框就是空名）。所以"这把已经有名字了，跳过"是错的：只要某次合并的结果是装备且配置了名称，就重新写一次，但只把多出来的 1 级记在真正写出名字的那一次，后续重复写同名不再加价。

装备本身不用再合并、只差名称时，书本流程结束后单独走一次"仅改名"铁砧流程（放件、改名、校验预览名称、取回）。`renameOnly` 模式下则整条流程只改名。

## 8. 批次工作流

### 8.1 两种批次顺序

- `ROW`：一件从第一刀合到最后一刀，再换下一件。整批的书要一次性背着，36 格限制下一批通常 4 件。
- `STEP`（默认推荐）：同一批装备一起推进——取第 k 步的书、给所有件做第 k 步、再取第 k+1 步的书。装备一直留在背包里，同一 36 格里能跑 10 件。

取货总是把背包**补齐到 `batchSize`**：先数列该作业的全部装备（空件、半成品、已完成的成品，以及冷却中的件都占名额），只取差额。这样不会出现"附好一把就补一把空剑、成品不卸"越滚越多，也不会把冷却件算漏导致反复取满。

### 8.2 按整批缺口取书

书的数量按**整批实际缺口**算，不是按手上这件算，也不是每种无脑补满：

- `STEP` 下只取**批次当前步骤**（frontier）的书。frontier 取自"白板件的完整计划"里第一个还有件缺的步，不是当前这件自己的下一步。例：10 把剑里 1 把已带抢夺 + 耐久 + 横扫、其余白板 → 第一步只取「抢夺 3 × 9」，做完再取「横扫 3、耐久 3 各 9」，然后「锋利 5、击退 2 各 9」。
- 只给"走得通这条梯子"的件算书：白板件，以及身上附魔正好是树上某个节点的件。附魔集合对不上任何节点（例如只有经验修补 + 耐久 3、却从没上过抢夺）的件**不参与本轮梯子战役**——给它取抢夺只会让书在背包里躺好几步。这类件排到整批梯子走完之后，单独按它自己的计划取书，同款可以合并一趟，每次只取一两种各一本。
- 限额 `freeInventorySlots() - 1`：只留一个空位，够下一次合并把结果放回，又不会把战役砍成一次一两本的小趟。
- **一种书整类取或整类不取**。半取会让部分件先跑一步、部分件原地等，下一轮就会把两步的书一起要回来（三种书 30 本 + 10 把剑 = 40 格，超 36 格）。整类都装不下时才退化为部分取，保证不空转。
- 取多了的书会按缺口退回原书箱（`surplusBookSlots`）；背包还不够放时把半成品也退回去（`flushExcessInput`），永远不吃满背包。

### 8.3 卸货

一件做完先留在背包（仍占本批名额），直接从背包接手下一件。整批没有待附魔件时一次性 shift-click 把该作业的成品批量卸进 `outputChest`，随后释放批次闩锁、作业指针前进。背包快满时提前卸货兜底；背包被超额空剑堵死且没有成品可卸时，把超出的一批空剑退回取货箱（原料回原料箱，不丢东西）。失败件走 `postEnchantStoreMode`，不走批量路径。

### 8.3.1 缺书时的半成品卸货

一个必需的书箱里没有那本书时，流程会站在书箱前反复开窗等待——而批次闩锁还握在手里，于是**整个平台都停在一种书缺失上**：取货箱里刚放进去的镐子没人取，背包里的剑也出不去。

所以缺书有一个上限，`bookStockoutSeconds`（默认 300s，`autoEnchant bookStockout <seconds>` 可调）：

1. 从这件第一次发现书取不到开始计时（书箱空、或书箱方块被活塞门/搬走挡掉都算），期间正常等，不改行为。
2. 超时后把这件当**半成品**卸到成品卸货箱（`outputChest`），附魔进度原样保留，喂回去就能接着做。它不算失败，不进冷却。
3. 这本书在 `bookStockoutSeconds` 内记为缺货：同批剩下的件不再各等一轮，直接跟着卸出去；这段时间也不会再为它开书箱。
4. 该**作业被让开**（`jobStockoutUntil`），取货箱不再取它这一类的件——这才是"去附魔下一个条目"真正生效的地方。等待时间每次翻倍（5→10→20→40 分钟，上限 1 小时），避免书长期不回时把取货箱里的同款件一批批倒进卸货箱。下次这本书真的取到时立即解除并重置翻倍。

作业本身不关、不删，书补上后自动回到队列。日志是一条 WARN：`book X Y is out of stock for Ns - the half-done <item> goes to the deposit chest ...`。

### 8.4 状态机

主链：

```
ENTRYPOINT → EVAL_RESTOCK → RESTOCK_INPUT_* → EVAL_UNCHANT/BATCH_GRIND
           → NEXT_BOOK → RESTOCK_ENCHANTED_BOOK_* → CHECK_ANVIL
           → ENCHANT_INTERACT_WITH_ANVIL → ENCHANT_TRY_START → AWAIT_ENCHANT
           → TAKE_RESULT → VERIFY_RESULT → (下一步 / BATCH_NEXT_PIECE / STORE_*)
```

子流程：`GRINDSTONE_*`（单件磨白）、`CRAFT_ANVIL_BLOCKS`（铁砧坏了补块）、`CLOSE_FOR_XP → WAIT_XP / KILL_WINDOW`（刷经验）、`POST_ENCHANT_INPUT_* / POST_ENCHANT_OVERFLOW_*`（半成品去向）、`DROP_ITEM`（事务物品丢弃）、`BACKOFF`（环境退避）。

### 8.5 关键不变量

- 单件事务串行：用窗口 id、阶段号、槽位快照防重复点击；已确认消耗的书不回滚。
- 故障恢复只处理本事务拿取的东西，不动玩家原有库存。
- 背包里已有的未完成件按"中断续做"接手，不重置、不重复花钱。
- 代价/结果一律以服务端预览为准：费用、结果附魔、名称匹配计划才取出；不符就终止该件剩余步骤。

## 9. 经验与 KillAura

经验只来自本体 KillAura：`ExpFarmSpot` 就是工作站位，铁砧、取货箱、书箱都在交互距离内，**刷经验不需要移动**，战斗与附魔交替进行。

- **模块只接管开关**：刷经验时只写 `killAura.enabled = true`，攻击间隔、武器切换、目标筛选全部保留用户手动配置；停止时只还原开关本身，不碰其他字段。
- **杀戮窗口**：蠹虫经验机超过约 9 秒不击杀就会被服务器清掉，且一次性大量击杀经验球落地远、吸收率低。所以不按等级长时间开关，而是每隔 `killWindowIntervalTicks`（60 tick）开一个 `killWindowTicks`（40 tick）的窗口，杀几只就关；每次开容器前也先补一个窗口。窗口内击杀数达到 `killWindowMinKills` 提前结束；附近没怪直接跳过。GUI 打开时不跑窗口（服务端会丢攻击）。
- **门槛用剩余总费用**：开砧前、窗口内"预览已出但等级不够"时、批次内不关窗接续下一件时，三个检查共用一个数 `refillBudget` = 本件剩余总费用 + 观察到的差额（`planCostBias`）。按单步预算会把 14 级的方案拆成 3 + 11 两趟往返。
- 刷够就停，不多攒；剩余等级留给下一件。
- 中断条件：死亡、传送、切维度、血量过低、击杀点不可达、整体超时、长时间不涨经验 → 停 KillAura，当前件进入失败恢复。

## 10. 铁砧维护

使用前后复核铁砧方块状态（anvil / chipped / damaged 都可用，`damaged` 用完即消失）。发现消失：先看背包有没有铁砧，没有就从 `anvilChest` 取 `anvilRestockStacks` 组，走到 `anvil` 放置点，核验目标方块可替换，放置后重新确认方块类型。当前件重试三次仍不行就按半成品处理；没有可用铁砧时不取新件，按 `restartDelay` 退避扫描。

铁砧补给箱是**空的**（箱子在、里面没有铁砧）要单独处理：它不算"容器消失"，所以 `reportMissingContainer` 看不见它。连续 `ANVIL_SUPPLY_EMPTY_ROUNDS`（=3）个窗口都没取到铁砧后就按 2s→4s→8s→16s→30s 退避停放，并打一条 `The anvil supply at … holds no anvil` 的 WARN。没有这个计数时该分支会在下一 tick 重新开同一个箱子，实测**每分钟约 150 次开箱、连续 5 小时（4.4 万次）**，批次计时照跑，日志里看起来像在干活。

## 11. 开窗可靠性（实测结论）

开窗点击分两条路：路径可达性由 Pathfinder 负责，窗口是否出现由计时器判断。`openRetryTicks` 就是"点了没反应，多久重发"。

500+ 个窗口的实测：

- 服务器**接受**点击时，窗口在 100ms 内到达，样本里没有一例超过 150ms。4 tick（200ms）已经是往返的两倍。
- 剩下的 miss 不是"回窗慢"，而是**首击被丢**，并且成段出现：一段 5~10 分钟里几乎每次开窗都要重发一次，`shulker_box` 和铁砧同比例，与方块、手持物无关；然后下一段又完全为零。同一段配置不变也会翻转，因此与预算无关。
- 结论：`openRetryTicks` 保持 4。调大到 8 不能救丢包，只会在爆发段给每次开窗多加 200ms（一批约 45 次开窗 ≈ +9s/批）。连续三次失败后交给 `waitForInteractTimeoutTicks` 兜底，不会对着一个坏方块猛点。

日志每 20 个窗口打一行 `open latency over 20 window(s): <100ms=…, max=…ms, misses in the same span=…`，miss 行同时打印当前生效的预算（`re-sending in N tick(s)`），因此"内存里到底是几"不用猜。

## 12. 统计与可观测

三种口径都由模块自己记录，不依赖外部计时：

- 每件：相邻两次完成的墙钟间隔；`stats: piece #N took …`。
- 每批：批次记录（取货/接管）到本批最后一件卸完，件数按实际完成数；`stats: batch #N done: … piece(s) in …s (…s per piece)`。
- 每盒：连续 27 件；`stats: shulker box #N done: …`。

`autoEnchant stats` 看累计平均/最快/最慢、当前批与当前盒进度、每小时件数与盒数；`stats reset` 清零。稳态实测：**15.4s/件**（61 件 939.6s），构成见 §15。

状态输出用一行一行的 trace 状态（`state A -> B`），另有 `list` / `stations` / `debug` 三个命令看站点、标记和作业的当前解析结果。

## 13. 失败处理

- 单件失败（缺书、缺铁砧、诅咒、超费用、预览不符、结果不匹配）→ 记录原因，按 `postEnchantStoreMode` 卸货或留包重试，队列继续，不设全局暂停。
- 缺书超过 `bookStockoutSeconds` → 不再算失败，按半成品卸到成品箱并把该作业让开一段时间（见 §8.3.1），平台继续做下一个条目。
- 环境问题（区块未加载、断线、没有可用铁砧）→ 有上限的指数退避，周期性重扫；恢复后自动续跑。
- 站点容器消失（展示框贴附或下方的箱子/潜影盒被搬走）→ 按 2s→4s→…→30s 停放并报出名字，箱子放回即续跑；空箱子（在但没货）走同一套退避。
- 槽位被玩家/漏斗改动 → 快照不匹配就取消当前事务，不碰陌生物品。
- 容器满 / 找不到输出箱 → 事务物品留在背包重试，失败三次后冷却 5 分钟再试；绝不丢弃玩家原有物品。
- 窗口超时或状态不同步 → 有限次重读，仍无法确认则放弃本件，不盲目连点。
- 错误分三级：`item-skip`（跳过本件）、`transient-retry`（退避重试环境）、`configuration-error`（告警 + 周期重载）。

## 14. 命令参考

```
autoEnchant on|off                                    总开关
autoEnchant scan                                      立即扫描，按展示框生成/同步作业
autoEnchant list                                      作业清单（开关、目标、从哪取、卸到哪、改名）
autoEnchant add <id> <enchantItem> <取货坐标> <卸货坐标>   手工建作业
autoEnchant del <id> / clear                          删除一个 / 全部
autoEnchant set help                                  set 子命令一览
autoEnchant set <id> on|off                           作业开关（scan 会按模板框是否存在覆盖它）
autoEnchant set <id> inputItem <item>                 目标物品
autoEnchant set <id> inputItemChest <x> <y> <z>       取货箱
autoEnchant set <id> outputChest <x> <y> <z>          卸货箱
autoEnchant set <id> outputEnchants add|del|clear|list  目标附魔集合与规划预览
autoEnchant set <id> postEnchantStore <none|to_restock|to_overflow>
autoEnchant batchSize <n> / batchOrder <row|step>      批次大小与推进方式
autoEnchant stats / stats reset                       吞吐统计
autoEnchant experienceSource <none|kill|exp>          经验来源
autoEnchant killWindow <ticks> / killGap <ticks> / killMinKills <n>
autoEnchant grindstone <x> <y> <z>                    砂轮坐标
autoEnchant anvil <x> <y> <z> / anvilChest <x> <y> <z>
autoEnchant anvilRestock <stacks>                     补铁砧时取几组
autoEnchant ExpFarmSpot <x> <y> <z>                   刷经验站位
autoEnchant scanRange <horizontal> <vertical>         扫描半径
autoEnchant actionDelay / restartDelay / openRetry / waitForInteractTimeout <ticks>
autoEnchant bookStockout <seconds>                     缺书多久后卸半成品并换下一个作业
autoEnchant renameOnly on|off                         只改名模式
autoEnchant logTradeStatusToDiscord on|off
```

## 15. 实测数据

2026-11-19 夜间，真实服务器、`batchOrder = STEP`、`batchSize = 10`、`experienceSource = KILL`：

| 指标 | 值 |
| --- | --- |
| 稳态单片耗时 | **15.4s/件**（61 件 939.6s，7 批） |
| 最好一批 | 14.2s/件（10 件 141.7s） |
| 开窗 miss（干净段） | 0 / 160 窗 |
| 开窗 miss（爆发段） | 85~100%，每段持续 5~10 分钟，与配置无关 |
| 一潜影盒（27 件） | 约 330s |

单片 15.4s 的构成（按状态精确统计，933s / 61 件）：

| 阶段 | 占比 | 明细 |
| --- | --- | --- |
| 刷经验 | 49.9% | 211 次窗口，均 2.21s |
| 铁砧合并 | 33.7% | 600 次 merge，单次提交 0.29s |
| 取附魔书 | 7.1% | 59 次，均 1.1s |
| 关窗 / 检查 / 补块 / 调度 | 9.3% | — |

即每件 ≈ 3.5 次刷经验窗口（7.6s）+ 9.8 次合书（5.1s）+ 1 次取书（1.1s）。要继续提速只能减少合并次数或经验量，两者都由附魔树本身决定。

## 16. 验收清单

1. 扫描能把展示框解析成作业、书箱、铁砧补给、共享白板取货箱、共享成品卸货箱，冲突有报告；贴附容器的框优先用贴附容器。
2. 每个成品模板框生成/更新一条作业（附魔 + 自定义名）；框在 = 启用，框拿走 = 禁用但不删除；手改目标不会被周期扫描覆盖。
3. 整批取货补齐到 `batchSize`，成品与冷却件都占名额。
4. 祛魔整批一次判定、一个砂轮窗口连续磨完；白板件不进砂轮。
5. STEP 下取书按 frontier，数量等于整批缺口，只留 1 个空位，不出现超 36 格。
6. 每步合并以服务端预览为准；对不上树的件进半成品，不逐本硬叠。
7. 改名只多花 1 级，且成品不会带着旧名字卸货。
8. 经验不足只开关 KillAura，够用即停，攻击间隔等参数不被改写。
9. 铁砧损坏能补块并验证；补不上时本件降级、队列继续。
10. 单件失败、满箱、断线、重连都不会让队列永久停摆。
11. 三种统计口径与 `stats reset` 正确。
12. `renameOnly` 模式不取书、不合书。

## 17. 已知限制

- 只支持单人或同权限下的单 BOT 工作区；没有多人协作/权限模型。
- 只按目标等级取书，不合成低级书（书箱里必须直接有目标等级的书）。
- 依赖服务端铁砧预览；预览被别的玩家占用时会等待/重开窗口，吞吐下降但不丢件。
- 开窗丢包是链路侧的成段问题（§11），插件只能减少损失，无法消除。
- 扫描只看已加载区块，大范围未加载区域需要先走到附近。
