# Create 集成与「第三方可见化」（0.3.2）

> 目标读者：维护本模组、需要判断「第三方模组为什么能/不能取用物化物品」的人。
> 本文是 0.3.2 两项改动的**取证记录 + 设计说明 + 风险登记**：
> ① 让 Create 蓝图大炮的「蓝图接口」看见物化物品；
> ② 让 BD 的**通用物品能力桥**也能取用物化物品。
>
> 关联文档：`docs/plan/ROADMAP-0.3.0.md` §2.2 / §3.1 / §9；`CHANGELOG.md`「0.3.2」节；
> 自检代码 `diag/CreatePathwaySelfTest.java`；人工验收清单见本文 §8。

---

## 0. TL;DR

- **现象**：0.3.0 把"已学习但无库存"的物品物化进维度网络，写进了**独立的 type bucket**。
  BD 对外的物品能力桥只枚举 `ItemStackKey` 那一个桶 ⇒ **结构性看不见**，跟物品值多少 EMC 无关。
- **0.3.2 的改法**：不是改 BD 的存储，而是**只在该能力桥的"原生可视槽之后"追加一段只读物化区**，
  让"看见"真正发生；**真抽取一律导回 `UnifiedStorage.extract`**，从而命中既有的扣费铸造钩子。
- **红线**：模拟抽取（`simulate=true`）**只读不扣费**；真抽取**绝不**在 Mixin 里直接交付，
  必须经过 BD 原生 `extractItem` → `UnifiedStorage.extract`。任何"直接改桶"的优化都会重开
  0.3.0 已修的**零扣费缺口**（`S-0.3-7`）。
- **只动一个 BD 类**：`ItemUnifiedStorageHandler`（无序版）。
  它的"有序版兄弟" `ItemStackTypedHandler` **刻意不动** —— 理由见 §3。
- **已知风险**：自动化装置（抽取→回插回路）会**静默折价损失 EMC**（买入价 vs 卖出价的价差），
  成因 / 量级 / 观测方法见 §5。这是把物化物品交给自动化的**必然代价**，已由用户明确接受。
- **⚠️ 发布后实测缺陷（已修，见 §11）**：第一版暴露路径**额外要求「物化条目存在」**才报量，
  于是"条目暂时缺失"的材料在大炮清单里显示「无库存」，而其实一取就能取到。
  判据必须**只看余额**（与真实扣费同源），**绝不看派生数据**。

---

## 1. 取证：为什么 0.3.0 的物化条目"天生看不见"

### 1.1 BD 的存储是「key → long」无序映射，且**按 key 类型 id 分桶**

`AbstractUnorderedStackHandler` 内部维护 `type2buckets: Map<ResourceLocation, TypeBucket>`，
每个 `TypeBucket` 是一串 `IStackKey<?>`。对外枚举时**逐桶**进行：

```
getSlots()       → 只数 ItemStackKey.ID 那个桶
getStackInSlot() → 只从 ItemStackKey.ID 那个桶取第 slot 个 key
extractItem()    → 同上取 key，再 storage.extract(key, …)
```

出处：`api/capability/helper/unordered/ItemUnifiedStorageHandler.java:30-33,39,58,99`。
我们的物化条目落在 `EmcItemKey.ID`（另一个桶）⇒ **在枚举阶段就被跳过了**，与 EMC 定价无关。

### 1.2 即使不看桶，`getStackByKey` 也精确查不到

BD 的 `AbstractUnorderedStackHandler.getStackByKey(key)` 是 `storage.getOrDefault(key, 0L)`
（`HashMap` 精确查表，`:402-406`）。而：

- `ItemStackKey.equals` 只认 `instanceof ItemStackKey`（`:492-500`）；
- `ItemStackKey.hashCode` 基于 `item.hashCode + patch 字节`（`:502-515`）；
- 我们的 `EmcItemKey.hashCode = 31 + info.hashCode()`（`:198-200`）。

⇒ 用 `new EmcItemKey(info)` 去查 `ItemStackKey` 的条目，**hash 与 equals 都对不上**，恒 miss。
这是 `NetedSchematicannonItemHandler` 里 `getStackInSlot` 恒返回 `EMPTY` 的直接原因。

### 1.3 Create 蓝图大炮判定「有料」的**唯一**门槛是「模拟抽取」

对 `create-1.21.1-6.0.10.jar` 的 `SchematicannonBlockEntity` 与 `Create.ItemHelper`
逐条反汇编（`javap -p -c`）确认：

- `updateChecklist()`：
  `if (extractItem(i, 1, true).isEmpty()) continue;` —— **模拟抽取为空就跳过，连
  `getStackInSlot` 都不读**；不为空才 `checklist.collect(getStackInSlot(i))`。
- `grabItemsFromAttachedInventories()` 与 `ItemHelper.extract(...)`：
  一律**先跑一遍 `simulate=true` 估量，够了才跑 `simulate=false` 真抽**。

⇒ 结论：**模拟抽取说"没料"，真抽取那一遍永远不会被调用**。
所以修法的落点不在"真抽取"，而在"让模拟抽取报得出量"。

### 1.4 但真抽取链路**本来就是通的**

`simulate=false` 时，BD 原生 `extractItem` 会调
`storage.extract(new ItemStackKey(snap), count, false, false)` →
`UnifiedStorage.extract` → `InterfaceWithdrawService` 钩子**扣 EMC + 铸造 `ItemStackKey`** →
返回的键恰好是 `ItemStackKey`，通过 `instanceof ItemStackKey` 守卫 → 交出真实物品。

它唯一的"毛病"是**走不到**（被 §1.3 的模拟筛掉了）。所以真抽取**一行都不用改**。

---

## 2. 方案：两条暴露路径

### 2.1 蓝图接口：`NetedSchematicannonItemHandlerMixin`

- **目标**：BD 的 `integration.module.create.block.entity.
  SchematicannonPathWayBlockEntity$NetedSchematicannonItemHandler`（BD 为 Create 提供的接口方块实体）。
- **注入**（`create/NetedSchematicannonItemHandlerMixin.java`）：
  | 方法 | 时机 | 动作 |
  |---|---|---|
  | `getStackInSlot(int)` | `@At("RETURN")` | 原生为空时回填物化条目（供大炮界面「已收集」计数） |
  | `extractItem(int,int,boolean)` | `@At("RETURN")` | **仅 `simulate=true`** 补报可交付量；`simulate=false` 第一句就 `return` |
- **刻意不注入** `getSlots()`：`allSchematicannonItems` 快照本来就包含 checklist 里的全部物品
  （不看网络有没有），槽位数已覆盖。
- **本类不引用任何 Create 类型**：注入点签名全是 Minecraft 类型；Create 缺席时由
  `BeyondEmcCreateMixinPlugin` 整体跳过。

### 2.2 通用物品能力桥（无序版）：`ItemUnifiedStorageHandlerMixin`

- **目标**：`api.capability.helper.unordered.ItemUnifiedStorageHandler`（**维度网络**对外的物品视图）。
- **注入**（`mixin/ItemUnifiedStorageHandlerMixin.java`）：
  记原生 `getSlots()` 返回值为 `base`，我们的区段是 `[base, base + emcCount)`：
  | 方法 | 时机 | 动作 |
  |---|---|---|
  | `getSlots()` | `@At("RETURN")` | 返回值 `+ emcCount` |
  | `getStackInSlot(slot)` | `@At("RETURN")` | 原生为空**且** slot 落在区段内 → 回填物化条目 |
  | `extractItem(slot,count,sim)` | `@At("RETURN")` | 同上；模拟只算量，真实导回 `UnifiedStorage.extract` |
- **`base` 的来源**：用 `getSlots() - emcCount` **反推**（`getSlots()` 已被本 Mixin 注入）。
  这样 `base` 的算式**唯一来源就是 BD 自己**，我们不去复制它，BD 改了也不会错位。
- **原生槽位逐位不变**：区段严格从 `base` 之后开始，包括 BD 那个「容量预留槽」
  （非满时 `getSlots()` 会 `+1`，那是给第三方模组插物品用的）—— 它属于 `base` 之内，绝不触碰。
- **`insertItem` / `setStackInSlot` 不注入**：逐个核对过——`setStackInSlot` 对 `slot > 桶大小`
  的分支会直接 `return`（拒绝写），`insertItem` 本就忽略 slot 参数（BD 存储无槽位，插哪儿都进网络）。
  我们的区段在这两个方法上**已经是**安全行为，多注入一处只会多一份出错面。

---

## 3. ⚠️ 为什么「有序版」`ItemStackTypedHandler` **刻意不动**

BD 有**两套名字很像但完全不同**的能力体系：

| 体系 | `CapabilityHelper` 字段 | 包装的类 | 含义 |
|---|---|---|---|
| **无序版**（本方案改造对象） | `USHandlerMap` | `ItemUnifiedStorageHandler`（包 **`UnifiedStorage`**） | **维度网络**对外的物品视图 |
| **有序版**（刻意不动） | `CommonHandlerMap` | `ItemStackTypedHandler`（包 **`StackHandler`**） | **机器自己**的固定槽位：熔炉输入/燃料/输出、Create 装置搬运时的本地存储 |

关键事实：`UnifiedStorage` 与 `StackHandler` 在 BD 里是**互不相交**的两个类
（各自 `implements IStackHandler`，**没有继承关系** —— 这一点由 javac 直接判定
「`StackHandler` 无法转换为 `UnifiedStorage`」而确认，曾在实现中真实触发编译错误）。

所以：**那个视图底下永远不可能是维度网络**，拿不到 `DimensionsNet`、也就拿不到 EMC 余额
⇒ **无法收费**。往那里塞物化条目，等于开一个**零扣费送物品**的口子
（正是 `S-0.3-7` 要堵的东西）。故**刻意不动**，保持它原生的"看不见"。

> 这与最初的计划不同：原计划要同时改两个类共 5 个方法。追查 BD 源码后**按证据收缩范围**
> 到只改这一个类。该理由也写在 `ItemUnifiedStorageHandlerMixin` 的类注释与
> `MixinTargetCheck.java:109` 附近的注释里。

---

## 4. 收费红线与「报价器」收口：`MaterializeQuote`

### 4.1 为什么必须收口

0.3.0 曾因**两个入口各自判断"该不该折算"**而出过真实的刷物品漏洞。
本期新增了两条暴露路径（§2.1、§2.2），它们要做的是**同一套**判断
（已学习？能定价？余额够？）。若各写一份，就是同一个坑再踩一次。

⇒ 判断链整体搬进 `materialize/MaterializeQuote.java`，**所有调用方共用**：

```
MaterializeQuote.policy(net, info)   // 前置链不成立 → null（这条路不通）
  ├─ net != null
  ├─ BeyondEmcConfig.allowInterfaceWithdraw()      // 关掉时必须报"不可交付"，否则模拟会谎报有料
  ├─ EmcAvailability.isReady()                     // EMC 表未就绪一律不兑换
  ├─ exchangeRequiresKnowledge() → NetKnowledgeStore.knows(net, info)
  └─ IEMCProxy.getValue(info) > 0                   // 购买价（取价抛异常同样视为不可交付）
       → new Policy(info, unitPrice, balance, balance / unitPrice)

policy.deliverable(want, materializedAmount)         // 本类只读，绝不写
  = min(want, materializedAmount, affordable)
```

- `MaterializeQuote.displayStack(info, amount)`：数量**必须夹在 `[1, 原版堆叠数]`**。
  物化量可能有上百万，直接塞进 `ItemStack` 交给第三方容器会越界 / 显示错乱。
- `MaterializeQuote.materializedAmount(net, info)`：按 `EmcItemKey` **HashMap 精确查表**，
  而不是遍历 `ItemMaterializer.currentEntries()` 建整张表（后者是每帧被高频调用的路径）。
  只认 `EmcItemKey` 类型，其余一律报 0。
- **`EmcDepositHandler.skipReason` 刻意不放进本类**：它判的是"这个 `ItemStack` 本身"
  （黑/白名单、带组件物品），必须由调用方用**请求方给的那个 stack**去判，
  而不是用归一化重建出来的 stack —— 否则两者会在"带组件物品"的边界上分叉。

### 4.2 两条红线（写在所有相关类的注释里）

1. **模拟绝不扣费**：`simulate=true` 路径只读存储、只算数量，不产生任何副作用。
   与 0.2 立下的红线一致。数量上限取自 `Policy#deliverable`，与真实扣费段**同一算式**
   ⇒ 模拟报量与真实交付量在数学上一致，不会"报得比给得多"。
2. **真抽取绝不绕过收费链**：真交付**只能**发生在 BD 原生 `extractItem` 里，
   经 `UnifiedStorage.extract(IStackKey,long,boolean,boolean)` 命中扣费铸造钩子。
   Mixin 里**绝不直接交付物品**。任何"直接改桶"的优化都会重开 `S-0.3-7`。

---

## 5. 已知风险：自动化装置会「静默折价损失 EMC」

### 5.1 成因

物化物品一旦被抽取，就变成**真实的 `ItemStackKey` 库存**，第三方机器**无法区分**
它是"EMC 物化来的"还是"玩家真存进去的"。于是一条 `抽取 → 回插` 的自动化回路会：

1. 机器从网络**抽出**物化物品 → 触发扣费，按**购买价**扣 EMC；
2. 机器把物品**回插**网络 → 命中存入钩子，按**卖出价**折算 EMC。

而 ProjectE 的**买入价 > 卖出价**（价差存在）。每循环一次，网络 EMC **净减少**这个价差，
且**没有任何报错或提示**——这就是"静默折价损失"。

### 5.2 量级与边界

- 只对**刻意搭建** `抽取→回插` 回路、或让自动化**大量**搬运物化物品的玩家发生。
- 正常玩法（大炮按蓝图取料 → 建成方块；管道取出交给机器加工）**不构成回路**，没有损失。
- 单次损失 = 该物品的 `买入价 − 卖出价`，与物化总量无关，**不会滚雪球**。
- ⚠️ 这条风险的**根源不是本集成**，而是"把 EMC 物品交给不认识 EMC 的自动化"这件事本身。

### 5.3 观测方法

- 记下网络 EMC 余额，跑一段时间自动化，**再记一次**：若余额下降、而存储里该物品的净数量
  并未增加（甚至持平），即可确认是抽插回路的价差损失。
- 需要区分时，看日志 `[BeyondEMC] 接口兑换：`（真抽取会在扣费时打印）。
  抽出次数 × 价差 ≈ 余额下降量。
- 排查时优先怀疑：**任何会把物品又送回同一网络的自动化**（回插漏斗、机械臂、返回式管道）。

### 5.4 处置（可选，非必须）

本期**不加配置开关**（用户已确认"始终允许"）。若未来需要收敛，可选：
① 给物化物品附一个**不可存入**的标记（成本高，要改 `EmcItemKey` 的往返）；
② 在存入钩子里对"刚物化出来的物品"识别并**按购买价**折算（消除价差，但会让 EMC 无损循环，
引入更复杂的记账）；③ 加配置开关，默认关掉通用桥只留蓝图接口。
**当前不实施**，仅登记为已知行为。

---

## 6. 构建与运行依赖：为什么**不需要** Modrinth 坐标

- 实测 `create-1.21.1-6.0.10.jar` 的 `META-INF/jarjar/` 里**已内嵌**：
  flywheel `1.0.6`、ponder `1.0.82`、Registrate `1.3.0`。
  NeoForge 的 **jarJar 机制**在加载 create 时会**自动带上**它们。
  ⇒ **不需要**在 `build.gradle` 里声明 Modrinth maven 拉 flywheel/ponder
  （原计划要拉，取证后整个去掉，避免引入远程依赖）。
- 本模组对 create **只在测试时需要**，连 `compileOnly` 都不加：
  我们对 Create 的集成方式是 Mixin BD 的**接口方块实体**，且**不引用任何 Create 类型**
  （只用字符串类名），所以**编译期根本不需要 create**。
- `build.gradle`：仅当传 `-PwithCreate` 时 `runtimeOnly files(libs/create-1.21.1-6.0.10.jar)`；
  缺文件时抛 `GradleException`（不静默降级）。版本号在 `gradle.properties` 的 `create_version`。
- `tools/play.cmd`：`runClient` 默认带 `-PwithCreate`；可用 `nocreate` / `withcreate` 参数覆盖。

---

## 7. 门控与可观测性：`BeyondEmcCreateMixinPlugin`

- 配置 `beyondemc.create.mixins.json` 标 `required:false` + `plugin`。
- **判据用「类路径探测」而非 `ModList`**：`shouldApplyMixin` 在 Mixin **配置准备阶段**
  就会被调用，那时 `ModList` 未必就绪；一旦那时判为 false 并被缓存，这个 Mixin 就
  **永远不会应用**（而 `required=false` 让整件事毫无报错）。那个坑在 JEI 集成上已实测踩过。
- **探测类挑一个「接口」**：`Class.forName("com.simibubi.create.api.behaviour.movement.
  MovementBehaviour", false, loader)`。挑的类越"重"，加载它需要解析的父子类/接口越多，
  中间任何一个没就位就抛 `NoClassDefFoundError`，会被误判成「Create 不在场」。
  接口加载几乎不解析别的东西。
- **三处日志**（启动时可见）：
  - `[BeyondEMC] Create Mixin 配置已加载（package=…）`（`onLoad`）；
  - `[BeyondEMC] Create Mixin 门控判定：create 在类路径上=…（判据=…），目标=…`（首次 `shouldApplyMixin`）；
  - `[BeyondEMC] Create Mixin 已应用到 …（…）`（`postApply`，只有真正应用才会出现）。
- `MixinTargetCheck` 的 `OPTIONAL_TARGETS` 登记了 4 项 create 目标：
  门控判据字段 `MovementBehaviour#REGISTRY`、`NetedSchematicannonItemHandler#getStackInSlot(int)`
  / `#extractItem(int,int,boolean)`、以及「Create 侧门槛」哨兵
  `SchematicannonBlockEntity#grabItemsFromAttachedInventories`。启动自检会核对它们是否还在。

---

## 8. 自检与人工验收

### 8.1 自动自检（每次启动 / `/beyondemc selftest`）

`diag/CreatePathwaySelfTest.java`，6 组**纯函数**断言（无需开游戏、无需 EMC 表就绪）：

| 组 | 断言 |
|---|---|
| `bucketSeparation` | `EmcItemKey.ID != ItemStackKey.ID`（两者若相同会塌缩合并，是比"看不见"更严重的**数据事故**） |
| `exposedAmountInvariant` | **70 组**（需求 × 余额 × 策略）满足「报得出量 ⟺ 策略放行 ∧ 有需求 ∧ 余额买得起」。算式里**没有**"物化条目"这一项 ⇒ 条目缺失/偏少都不可能把量压成 0（§11 实测缺陷的回归） |
| `deliverableBoundaries` | 不要=0 / 取需求 / 受余额限 / 买不起=0 / 负数=0 / `MAX` 不溢出 |
| `deliverableMatchesChargeFormula` | 4 单价 × 4 余额 × 4 需求量 = 64 组，报价与收费段 `min(请求量, 余额/单价)` **逐组相同** |
| `displayStackClamping` | `1,000,000 → 64`（原版堆叠数）；`1 → 1`；`0`/负数 → 空栈 |
| `nullSafety` | `net=null` / `info=null` 时报价为 `null`（不可交付），物化量报 0 |

> **一组旧断言被删掉了（重要）**：原 `deliverableBoundaries` 里有一条
> `rich.deliverable(64, 0) == 0`（"没有物化条目 → 报 0"），它把 §11 的缺陷**钉成了期望行为**，
> 所以自检全绿却掩盖着真实 bug。现由 `exposedAmountInvariant` 取代，断言方向正好相反。
> **教训**：自检必须断言**不变式**（"什么条件下必须报得出量"），而不是**当前实现**的取值。


> 依赖真实 EMC 价格（`IEMCProxy.getValue`）与配置开关的路径**无法在无头环境断言**，
> 故记入下面的**人工验收清单**。

### 8.2 人工验收清单（客户端，带 `-PwithCreate`）

> **2026-10-01 实测（真实客户端 + Create 6.0.10 + 真实 EMC 表）**：状态见每项后的标记。
> 证据文件：`run/logs/latest.log`（行号见下）。

- [x] **A. 蓝图接口可见性** —— 大炮识别到网络里的**火药**并取用。
      旁证：门控 `create 在类路径上=true`（`:29`）、`Create Mixin 已应用到
      …NetedSchematicannonItemHandler`（`:740`）。
- [x] **B. 大炮开工扣费** —— `[BeyondEMC] 接口兑换：minecraft:gunpowder ×1 → 扣除 192 EMC（单价 192）`（`:1115`）。
      该火药刚被折算成 EMC（`:1114`，回收单价 192），**网络里没有它的真实库存** ⇒ 交付只能来自物化路径。
- [ ] **C. 通用桥在真实第三方管道上抽物化物品** —— ⬜ 未在 AE2 / RS 上实跑。
      暴露层本身已由自检（6 项）与存档双线验证（`emc_item` 桶 15 条与真实库存分桶共存）。
- [x] **H. §11 回归的端到端验证（无头，2026-10-01）** —— 把用户那份"15 项已学习 / 仅 14 条物化条目"
      的真实存档铺成 `run/world`，在专用服务器上 `/reload` 触发一次重算：
      日志唯一一条 `物化（第 1 次）：网络 0 EMC=3066234 → 15 条物化条目`，
      存档内 `emc_item` 由 **14 → 15**（补齐 `minecraft:gunpowder 15969`），EMC 池 3066234 与
      15 项学习集无损。证据：`run/verify-032-canon.log`（0 FAIL）。
      方法与"为什么必须走 RCON"见 §11.5。
- [~] **D. 模拟不扣费** —— 🟡 已由自检断言（`CreatePathwaySelfTest` 的算式一致性与报价边界）；
      实机未单独构造"反复模拟抽取"的观测。
- [x] **E. jarJar 生效** —— 客户端带 Create 正常启动，日志**无**缺 flywheel / ponder 的报错
      （仅有一条 dev 环境无害的 `ponder.refmap.json` 提示）。
- [x] **F. 门控两个方向** —— 装 Create：`:29` `true` + `:740` 已应用；
      无头（不装 Create，`run/verify-create05.log`）：`create 在类路径上=false`、不应用、**不报错**。
- [x] **G. 回退到 0.2** —— 见 §9；已由 `docs/testing/phase8-materialize-report.md` 的 A/B 演练覆盖
      （0.3.x 存档交给 `v0.2.0` 代码：启动成功、EMC 与学习集无损、物化条目被逐条 WARN 丢弃）。
      0.3.2 **未改动** `EmcItemKey` 的类型或往返，故该结论继续成立。

### 8.3 已知**不覆盖**的项

- 真实 EMC 价格与配置开关组合下的端到端行为，只有人工验收（§8.2）能覆盖。
  **A / B / E / F / G 已于 2026-10-01 实测通过**；C（AE2 / RS 实跑）与 D（反复模拟抽取的实机观测）仍空缺。
- §5 的折价损失**不会**被任何自动断言捕获（它是玩法层面的性质，不是代码 bug）。
  实测环境里火药与钻石的买入价 = 回收价（火药 192/192），价差为 0，来回不掉 EMC。

---

## 9. 回退路径

### 场景 A：只回游戏版本（最快，保留本模组）
设 `materialize.materializeItems=false` → 下次 `ItemMaterializer.refresh` **清空**全部物化条目。
→ 回到 0.2 行为（物化条目不存在，两条暴露路径自然无料可报）。

### 场景 B：卸载本模组
物化条目用的是**本模组自定义的键类型**。卸载后该类型未注册 →
`StackKeyRegistry.getType(id)` 返回 `null` → `IStackKey.CODEC` 的 `dispatch` 抛异常 →
`AbstractUnorderedStackHandler.deserializeNBT` 的 `catch(Throwable)` 吞掉（`:889-898`）
⇒ **条目级静默丢弃**。

- **不会**变成"免费的真实库存"——类型不匹配 `ItemStackKey`，且两个 Mixin **本身不复存在**
  （它们属于本模组），能力桥回到原生"看不见"。
- **不会**损坏存档——丢弃是条目级的，其余数据（EMC 池、学习集合、真实库存）完好。
- 代价：回退后 EMC 池本身也会被丢弃（0.2 的已知行为 R9，README 已警示"卸载前先清空 EMC"）。

---

## 10. 变更清单（文件级）

**新增**
- `materialize/MaterializeQuote.java` —— 报价唯一真相源（§4）
- `mixin/create/NetedSchematicannonItemHandlerMixin.java` —— 蓝图接口暴露（§2.1）
- `mixin/ItemUnifiedStorageHandlerMixin.java` —— 通用桥暴露（§2.2）
- `mixin/BeyondEmcCreateMixinPlugin.java` —— Create 门控（§7）
- `resources/beyondemc.create.mixins.json` —— create 专用 Mixin 配置
- `diag/CreatePathwaySelfTest.java` —— 6 组纯逻辑断言（§8.1）
- `docs/plan/CREATE-INTEGRATION.md` —— 本文
- `tools/netaudit.py` —— 离线审计 BD 网络存档（EMC 池 / 按桶分组的存储条目 / 学习集合）
- `tools/rcon.py` —— 极简 RCON 客户端，供无头验证在专用服务器上发控制台指令（§11.5）

**修改**
- `exchange/InterfaceWithdrawService.java` —— 定价/余额段改为调用 `MaterializeQuote.policy(...)`；
  算式改用 `MaterializeQuote.exposedAmount(...)`；真实库存被取走的分支补 `scheduleRefresh`（§11.3 触发点 ⑥）
- `materialize/MaterializeQuote.java` —— 新增纯函数 `exposedAmount(...)` 与对外唯一入口
  `externalDeliverable(...)`；**删除** `Policy.deliverable(...)`（它就是 §11 缺陷的载体）
- `materialize/ItemMaterializer.java` —— `PENDING` 由 `Set` 改 `Map<net, 排队时刻>` + 2s 超时自愈（§11.4）；
  新增 `observedSnapshot()` / `restoreObserved()` 供自检做全局状态隔离（§11.6）
- `mixin/create/NetedSchematicannonItemHandlerMixin.java` / `mixin/ItemUnifiedStorageHandlerMixin.java`
  —— 报量与显示一律改走 `MaterializeQuote.externalDeliverable(...)`（§11.2）
- `command/BeyondEmcCommands.java` —— 新增 `/beyondemc why <item>`（§11.7）
- `diag/MaterializeSelfTest.java` —— 新增「诊断隔离不变式」断言（§11.6）
- `BeyondEmc.java` —— 自检段追加 `CreatePathwaySelfTest` 输出；整个自检段包在
  `observedSnapshot`/`restoreObserved` 里，保证**自检零全局副作用**（§11.6）
- `emc/EmcItemKeyRender.java` —— `getTooltipLines` 收敛为直接转发（**删除两行 tooltip 文案**）
- `resources/assets/beyondemc/lang/{zh_cn,en_us}.json` —— 删 `types.beyondemc.emc_item_amount`
  与 `emc_item_hint` 两键
- `resources/beyondemc.mixins.json` —— 加 `ItemUnifiedStorageHandlerMixin`
- `diag/MixinTargetCheck.java` —— 加无序桥 3 个目标 + create 4 项可选目标
- `BeyondEmc.java` —— 自检段追加 `CreatePathwaySelfTest` 输出
- `templates/META-INF/neoforge.mods.toml` —— 加第三个 `[[mixins]]` + create `optional` 依赖块
- `build.gradle` / `gradle.properties` —— `-PwithCreate` 接线（§6）
- `tools/play.cmd` —— `CREATE_FLAG` + 参数扫描（`nojei`/`nocreate`/`withcreate`）
- `emc/EmcItemKey.java` —— 类注释更正（0.3.2 放宽说明 + 收费红线）
- `libs/README.md` / `docs/VERSIONS.md` —— 登记 create jar 与 jarJar 说明

**删除**
- `mixin/ItemStackTypedHandlerMixin.java` —— 创建后**按证据删除**（理由见 §3）

**文档更正**（推翻 0.3.0 的旧结论）
- `docs/plan/ROADMAP-0.3.0.md` L154 / L311 / L402 / L527
- `docs/plan/RELEASE.md` L110
- `docs/README.md` L137
- `CHANGELOG.md`「0.3.2」节 + 0.3.0 正文"第三方管道看不到它们"一句

---

## 11. 0.3.2 发布后回归：实测缺陷与修复

> 触发：使用者实测 **"蓝图大炮的清单里只有部分材料显示满足条件，另一部分显示无库存"**。
> 结论：两条独立缺陷叠加，**都能用真实存档与日志取证**，均已修并端到端验证（§8.2 H）。

### 11.1 取证（先取证，再动手）

- **存档**（`tools/netaudit.py`，离线解析 `BDNet_0.dat`）：**15 项已学习，只有 14 条
  `beyondemc:stack_type/emc_item` 条目**，缺的正是 `minecraft:gunpowder`。EMC 池 3066234，
  另有 1 条真实库存（`minecraft:shulker_box`，带 `container` 组件）。**槽位未满**
  （`slotCapacity=Long.MAX`、`slotMaxSize=Int.MAX`）⇒ 不是容量问题。
- **日志**（`run/logs/latest.log`，1180 行）：
  - 唯一一次成功重算在 `:846` —— `物化（第 1 次）：网络 0 EMC=3066234 → 14 条物化条目`（当时学会 14 项）；
  - `:1114` `折算：minecraft:gunpowder ×1 → 192 EMC` ⇒ **火药是在那次重算之后才学会的**；
  - `:1116` 最后一条本模组输出是"知识同步到达，已触发界面重建（第 4 次）"，
    **此后到 `:1180` 退出再无任何"物化（第 N 次）"日志**。

⇒ 现象与"火药从来没被物化过"完全吻合。

### 11.2 根因一：判据分叉 —— 对外报量错误地要求「物化条目存在」

物化条目是**派生数据**（`ItemMaterializer` 的"权威与派生"边界），可以合法地缺失或暂时陈旧；
而**真实交付根本不看它** —— `InterfaceWithdrawService` 是"扣 EMC + 当场铸造"，只用
`min(请求量, 余额 ÷ 购买价)`。

第一版暴露路径却写成 `policy.deliverable(count,物化条目量)`，把"派生数据存在"当成了交付前提
⇒ **对外比真实更严** ⇒ 条目缺失的材料显示"无库存"，其实一取就能取到。火药正是这一例。

**修法**：删除 `Policy.deliverable(...)`（不提供任何"按条目截断"的方法），
把对外报量收口成一个**纯函数** `MaterializeQuote.exposedAmount(want, affordable, allowed)`，
并让 `externalDeliverable(net, info, want)` 成为两条暴露路径**唯一**的出口。
算式里**没有"物化条目"这一项** —— 于是"条目缺失把量压成 0"在数学上不可能发生。

### 11.3 根因二：条目永久陈旧（两个叠加的缺口）

`MaterializeMath.solve` 有一条排除规则：**当前有真实库存的物品不物化**（避免与 BD 原生行重复）。
而**真实库存的减少不改变 EMC**，原本没有任何触发点会因此重算 ⇒ 一旦某物品的真实库存被取走，
它的物化条目**永久缺失**。

- **修法**：在 `InterfaceWithdrawService` 的"真实库存放行"分支补上
  `ItemMaterializer.scheduleRefresh(net)`（**触发点 ⑥**），注释写明"真实库存的减少不改变 EMC，
  所以原来没有任何触发点；火药即此例"。

### 11.4 配套：`PENDING` 去重标记的永久冻结

`scheduleRefresh` 用 `PENDING` 合并同一 tick 的多次触发，原实现是 `Set` 去重 + 由队列任务清标记。
若 `server.execute(...)` 的任务**没能被执行**（服务端正在停止、执行器已关等），标记会**永久残留**，
此后每一次 `scheduleRefresh` 都在 `if (!PENDING.add(net)) return;` 处**静默返回** —— 物化层冻结，
且**没有任何日志**。

**修法**：`PENDING` 改为 `Map<DimensionsNet, 排队时刻>`（`WeakHashMap`），超过
`PENDING_STALE_MS = 2_000 ms` 未跑即判定"任务已丢失"，打 **WARN** 并重新排队。
一个 tick 是 50 ms，正常任务一个 tick 内就跑掉；2 秒远大于正常延迟，又能让被冻结的物化层
**在下一次触发时自愈**，并留下证据。

### 11.5 无头端到端验证：为什么必须走 RCON

`runServer` 是 Gradle 的 JavaExec 任务，**不把 stdin 转发**给服务端 JVM ——
`printf 'reload\n' | ./gradlew runServer` 收不到任何指令（实测：日志停在自检，`reload` 零输出）。

而无头环境下**只有 `/reload` 一条路**能让 ProjectE 构建 EMC 表：
`PECore#dataPackSync(OnDatapackSyncEvent)` 是无玩家的专用服务器上唯一会跑 `EMCMappingHandler.map`
的入口，`map()` 结尾 `fireEmcRemapEvent()` → `EMCRemapEvent` → `ItemMaterializer.refreshAll()`。
（`ServerStartedEvent` 时刻 EMC 表必然未就绪：`EMC 表就绪=false，remap 次数=0`。）

⇒ 解法：`server.properties` 临时 `enable-rcon=true` + 口令，用 `tools/rcon.py`
（纯 `socket` + `struct`，零依赖）从外部发 `reload` / `stop`。

**完整流程**（已验证，证据 `run/verify-032-canon.log`）：

```
① 把真实存档铺成 run/world（先 `mv world world.bak`，验完还原）
② ./gradlew runServer --console=plain          # 后台
③ python tools/rcon.py 127.0.0.1 25575 <口令> reload
④ 查日志：应出现唯一一条「物化（第 N 次）：网络 0 EMC=… → N 条物化条目」
⑤ python tools/rcon.py … stop                  # 触发存档落盘
⑥ python tools/netaudit.py run/world/data/BDNet_0.dat
⑦ 还原 run/world、把 enable-rcon 改回 false
```

> 判定要点：`reload` 后**必须看到真实网络的 EMC 与条目数**（`EMC=3066234 → 15 条`）
> 才算通过。只看到"自检 0 FAIL"是**不够的** —— §11.6 说明了为什么。

### 11.6 诊断污染：自检不得留下全局副作用

排查过程中发现：8 次重算全部落在 `网络 0 EMC=0` / `EMC=Long.MAX/4` 上，
**真实网络那条 `EMC=3066234 → 15 条` 完全看不到**。

原因：自检用 `new DimensionsNet(true)` 造临时网络去验证物化数学与写回，
这些临时网络经 `refresh` / `scheduleRefresh` 被 `ItemMaterializer.observe` **登记进全局
`OBSERVED` 集合后一直留在里面**；于是每次 `refreshAll` 都要为一批死网络空转，
而日志的 `LOG_FIRST_N = 8` 上限被它们**占满**，真实网络那条被挤了出去。

**修法**：新增 `ItemMaterializer.observedSnapshot()` / `restoreObserved(snapshot)`，
在 `BeyondEmc.onServerStarted` 里把**整个自检段**包进 `try/finally`，
结束时把 `OBSERVED` 还原成自检前的快照（只删自检期间新登记的，真实网络一律保留）。
另在 `MaterializeSelfTest` 加一条**不变式断言**（快照内保留 / 快照后移除 / 收尾零泄漏）。

> 修复后的实测：自检中期 `OBSERVED` 为 **9 个**（8 个临时 + 1 个真实），
> 自检收尾还原为 **1 个**；`reload` 后日志**只有一条** —— `网络 0 EMC=3066234 → 15 条物化条目`。

### 11.7 诊断命令 `/beyondemc why <item>`

实机上看，"未学习"与"条目缺失"在界面上**长得一模一样**（都显示"无库存"），
而原因有六种、处理方向完全不同。故新增：

```
/beyondemc why <item>
① 归一化身份  ② 配置与 EMC 表就绪  ③ 是否要求已学习 / 本网络是否已学习
④ 购买价与余额可买份数  ⑤ 存入侧 skipReason  ⑥ 现状（真实库存 + 物化条目）
⑦ 结论 ← 只由 MaterializeQuote.externalDeliverable(...) 给出
```

**结论只由唯一入口给出**，所以命令输出不可能与真实行为分叉；中间几行只解释"为什么"。


### 11.8 第二轮实测回归：圆石满足、传动杆不满足（数量口径，非判据）

用户在 0.3.2 修复后再次实测：同一份网络里**圆石被识别、传动杆不被识别**。
权威数据完全对称（都已学习、都有价、都有物化条目、余额都充足）⇒ 分叉不在报量判据，
在**数量口径**。

**取证（全部离线完成，一次无头复现收口）**：

1. **离线审计存档**（`tools/netaudit.py`）：18 项已学习 ↔ 18 条物化条目一一对应，
   `create:shaft` 有 139367 条、`minecraft:cobblestone` 有 3066095 条 —— 排除"条目缺失"。
2. **反汇编 Create 6.0.10**（`javap`）：`updateChecklist` 的判定门槛与会计口径是两条不同的路：
   - 门槛：`extractItem(i, 1, true).isEmpty() → continue`（模拟抽取）；
   - 会计：`checklist.collect(getStackInSlot(i))` —— **累加的是返回堆叠的 `getCount()`**。
3. **读用户日志**（`run/logs/latest.log`）：`折算：create:shaft ×1 → 22 EMC` 与
   `折算：minecraft:cobblestone ×1 → 1 EMC` 相继出现，两者路径对称 —— 排除"没学会/没价"。
4. **解析用户蓝图**（`run/schematics/Iron & Andesite Alloy All-in-One Producer.nbt`）：
   按方块名统计需求，并扫描方块实体 NBT —— 发现 **60 个 `create:encased_chain_drive`
   的 BE id 全是 `create:encased_shaft`**（Create 6 改了方块名、保留了旧 BE id），
   而 Create 对带壳传动类方块的 `ItemRequirement` 会**额外要传动杆**。
5. **无头复现**（把用户存档铺成 `run/world`，RCON `reload` 后执行新命令
   `/beyondemc cannon 17 -60 1`）：`required[shaft]=125`、`gathered=64` → 还缺 61；
   `required[cobblestone]=58`、`gathered=64` → 满足。**与实测报告逐字吻合。**

**根因**：BD 的蓝图接口**每个物品只有一个槽**（`stacksSnapshot` 按物品去重），
而上一版的 `displayStack` 把显示栈夹到原版堆叠数（64）⇒ `gathered` 封顶 64
⇒ 任何需求量超过一组的材料**永远显示"还缺"**，无论网络买得起多少。

**修法**：`displayStack` 的数量只按 int 范围夹取、不按堆叠数夹取 —— 与 BD 两处
`getStackInSlot` 的原生语义对齐（蓝图接口 `copyStackWithAmount(ka.amount())`、
通用物品桥 `clampLongToInt(...)` 对真实库存都返回全量，从不夹 64）。
`extractItem` 语义本来就是"最多给 count 个"（Create 每次只请求 1 个或一组以内），不受影响。
自检 `displayStackClamping` 同步改为断言新口径（`1,000,000 → 1,000,000`）。

### 11.9 诊断命令 `/beyondemc cannon <x> <y> <z>`（控制台可用）

"部分材料不满足"的判定链散在**三个模组**里（Create 的 required/gathered/门槛、
BD 的槽位快照与精确键查询、本模组的两条补报 mixin），实机上各种失败长得一模一样。
该命令一次把三层全部打出来：

- 大炮状态（state/status/已放/蓝图加载）；
- 反射调用**生产路径** `updateChecklist()` 后逐项打印 required / gathered
  与"满足/还缺"（与打印清单 `createWrittenBook` 同一口径）；
- 蓝图接口每个槽位的 `getStackInSlot`（判据 A：会计来源）与
  `extractItem(1, true)`（判据 B：门槛）。

实现要点：Create 在本工程只是 `runtimeOnly`（编译期不可依赖），所以**全走反射**
（`Class.forName` + `getMethod/getField`）；槽位快照经能力查询
（`Capabilities.ItemHandler.BLOCK`）+ 反射读 `stacksSnapshot`；Create 缺席时给一行
说明并退出，不炸命令树。`updateChecklist()` 内部对清单的扫库全是模拟抽取
（本模组模拟路径只读），故无副作用。

无头用法（配合 §11.5 的 RCON）：`/forceload add <x> <z>`（若大炮不在出生区块）、
`reload`（EMC 表就绪）、`beyondemc cannon <x> <y> <z>`。
