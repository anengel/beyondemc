# 架构设计：BeyondEMC（暂定名）

> 目标：Minecraft 1.21.1 + NeoForge 附属模组，依赖 **Beyond Dimensions（超越维度）** 与 **ProjectE（等价交换）**
> 本文是设计基线，所有"结论"都必须能追溯到 `docs/research/` 下的源码证据；未验证项集中在 §9。

---

## 0. 版本基线与参考资料

| 项 | 值 | 来源 |
|---|---|---|
| Minecraft | 1.21.1 | 两个前置模组的 `gradle.properties` |
| NeoForge | `21.1.234`（BD 使用）/ `21.1.148`（PE 开发版，最低 `21.1.119`） | `reference/BeyondDimensions/gradle.properties`、`reference/ProjectE/gradle.properties` |
| Beyond Dimensions | `0.7.30`，分支 `1.21.1`，commit `a0d2e76`，包名 `com.wintercogs.beyonddimensions`，MIT，mod id `beyonddimensions` | 本地克隆 |
| ProjectE | `1.1.0`，分支 `mc1.21.1`，commit `f432b0c`，包名 `moze_intel.projecte`，MIT，mod id `projecte` | 本地克隆 |
| 构建 | ModDevGradle `2.0.116`，Java 21，Gradle wrapper 9.2.0 | `docs/research/bd-persistence-lifecycle-events.md` §8 |

**硬约束**：`reference/` 下两个仓库是**只读参考**，禁止修改。所有对它们的介入只能通过：① 它们公开的 API；② 我们自己的 Mixin。

---

## 1. 需求拆解与验收口径

| # | 需求原文 | 验收口径（可测） |
|---|---|---|
| R1 | 在维度网络中同步存储 EMC 值 | 同一个网络的所有成员看到同一个 EMC 余额；重进存档、换维度、重连后数值不变；多人同时在线时数值一致 |
| R2 | 存入物品时有 EMC 值的物品转化为 EMC | 把任意有 EMC 的物品放进网络，网络 EMC 增加 `floor(回收价 × 数量)`，且该物品**不进入**网络库存 |
| R3 | 如果未学习则学习 | 存入后，该物品出现在网络的"已学习集合"中；重复存入不产生重复项 |
| R4 | 无 EMC 值的物品按原本逻辑存储 | 石头/泥土等无 EMC 物品照常进库存，数量正确 |
| R5 | 网络界面显示所有已学习物品 | 打开网络界面（`O` 键）能看到已学习物品列表 |
| R6 | 有库存则显示库存数 | 若该物品在网内有真实库存，显示真实库存数，点击走原版取出逻辑 |
| R7 | 无库存则显示"EMC 能转换出的数量" | 显示 `floor(网络EMC ÷ 购买价)` |
| R8 | 可直接扣除 EMC 取出 | 点击该条目后：网络 EMC 减去 `购买价 × 数量`，物品进玩家背包 |

### 1.1 已确认的产品决策（来自需求澄清）

| 决策点 | 选择 | 影响 |
|---|---|---|
| "已学习"的口径 | **网络级共享知识集合**（不是玩家个人 ProjectE 知识） | 共享网络下所有成员看到同一份列表；不修改玩家个人转换台知识 |
| 存入折算价 | **回收价 `getSellValue`** | 与 ProjectE 转换桌烧物品、凝聚器完全一致（`SlotConsume.java:29`、`CondenserBlockEntity.java:150` 等 5 处证据）；`covalenceLoss < 1` 的服务器上不产生套利 |
| 取出兑换价 | **购买价 `getValue`** | `EMCHelper.java:153-167`。因 `covalenceLoss ∈ [0.1,1.0]`（默认 1.0，`ServerConfig.java:147`）恒有 `购买价 ≥ 回收价` → **不存在"低买高卖"的净收益路径**。注意默认配置下两者相等，循环是**中性**的，不是亏损 |
| 网络 EMC 与玩家个人 EMC | **完全独立** | 不读写 `IKnowledgeProvider.setEmc`（那是无上限 `BigInteger`，玩家私产） |
| 界面实现方式 | **Mixin 注入 BD 原生界面** `DimensionsNetGUI` | 体验统一；代价是强耦合 BD 内部实现，须锁定版本 + 回归测试 |

### 1.2 一个必须让用户知情的必然结果

R2 与 R6 存在**逻辑上的互斥面**：既然有 EMC 的物品在存入时都被折算了，那么"有库存的已学习物品"在实践中**几乎不会出现**。

- R6 的分支只会在以下情况命中：① 该物品无 EMC 但已被学习（理论上不可能，学习只发生在折算时）；② 存档里遗留的旧物品（本模组加入之前存的）；③ 配置里被排除在折算之外的物品（例如带非默认数据组件的附魔/耐久物品，见 §6 `convertComponentItems`）；④ 通过绕过 `UnifiedStorage.insert` 的路径进入的物品。
- **这不是缺陷，是 R2 的直接推论。** 设计上保留该分支以保证语义完整，并在 v1 用"③配置排除"作为它真实生效的场景。
- 若希望"有库存优先显示库存"成为常态，需要改变 R2 的语义（例如：只折算"未学习"的物品，已学习的物品照常入库）。

> **✅ 已定稿（用户确认）：采用无条件折算。** 上述"必然结果"因此是**确定的产品行为**，不是待议项。
> - R6（显示库存数）保留实现，但只在"被配置排除的物品"（带非默认数据组件的附魔/耐久/容器物品）与旧存档物品上生效，须在 README 向玩家说明。
> - 不实现"只折算未学习的物品"这条备选路径（避免维护未验证代码），仅预留 `depositPolicy` 配置项骨架，默认且唯一实现为 `ALWAYS`。
> - Spike 清单中的 **S1 已关闭**。

---

## 2. 关键设计决策摘要

| ID | 决策 | 理由（证据见 `docs/research/`） |
|---|---|---|
| D1 | EMC 以**自定义 `IStackKey` 资源**的形式存进 `UnifiedStorage` | BD 自动完成持久化、跨维度同步、界面显示、网络合并搬运；BD 自己的 Botania/Ars/Mek/IFS 四个集成模块都用这条路（`BotaniaModule.java:63-68`）。`DimensionsNet` 没有任何自定义数据槽，`UnifiedStorage` 是唯一官方扩展面 |
| D2 | 折算逻辑挂在**官方钩子** `UnifiedStorageBeforeInsertHandler.addHandler` | 它是全局唯一收口点（`UnifiedStorage.java:91`），且 BD 自己从未注册过 handler，零竞争 |
| D3 | 网络级"已学习集合"存在**网络自己的 NBT**里（Mixin `DimensionsNet.save/load`） | 集合是"物品身份"元数据，不适合塞进"key→long 数量"的存储模型；独立 `SavedData` 需要额外的生命周期/合并处理，写进 `BDNet_<id>.dat` 则零泄漏、随网络销毁自动消失 |
| D4 | "教玩家个人知识"**不在 v1 范围** | 折算收口点 `UnifiedStorage.insert` **没有玩家上下文**，无法可靠拿到存入者；且在"网络级共享知识"语义下，把知识灌进个人转换台是越权行为。v2 通过 Mixin 菜单槽位转移入口实现 |
| D5 | 读档期间**禁止折算**；就绪判据用 **`EMCRemapEvent`**，不是 `ServerStartedEvent` | `DimensionsNet.load` 会调用 `unifiedStorage.deserializeNBT(...)`，而反序列化内部会走 `acceptEntry → insert`（`AbstractUnorderedStackHandler.java:989`）再次触发钩子；不守卫会导致"每次读档重写存档"。**阶段 1 实测推翻了原方案**：专用服务器上 `ServerStartedEvent` 时 ProjectE 的 EMC 表还是空的（钻石 EMC=0，空跑 60 秒 `EMCRemapEvent` 从未触发），因为真正算值的是 `EMCMappingHandler.map(...)`，它由 `PECore#dataPackSync(OnDatapackSyncEvent)` 在**有玩家登录或 `/reload` 时**才调用（`PECore.java:269`）。详见 `docs/testing/phase1-report.md` §5 |
| D6 | 兑换不走 `UnifiedStorageBeforeExtractHandler`，走**自定义 C2S 包 + 显式服务端逻辑** | 该钩子在 `extract` 链内，若在钩子里再调 `extract` 扣 EMC 会重入同一 handler 列表造成死循环；且它拿不到"要换多少个/给谁"的上下文 |
| D7 | 虚拟条目**复用 `ItemStackKey`** | 已注册、有现成渲染器、能命中 `DisorderedStackTypedSlot.click` 里的 `instanceof ItemStackKey` 分支、JEI/EMI 行为一致 |
| D8 | 客户端虚拟条目在 `buildIndexList` 的 **HEAD 注入**，并置空 `cacheIndexes` | 每次重建索引前先注入，天然规避 `resolvePendingOrAllUpdate(true)`（Shift 路径）把虚拟条目数量刷成 0 的问题；`cacheIndexes` 是排序缓存，不置空会返回旧结果 |

---

## 3. 总体结构

### 3.1 模块划分

```
beyondemc/
├── EmcStorage (服务端数据层)
│   ├── EmcType / EmcStackKey / EmcStackKeyRender      自定义资源类型（照抄 ManaStackKey 模板）
│   ├── EmcKeyRegistration                             FMLCommonSetupEvent 注册
│   └── NetEmcAccessor                                 net → EMC 余额 / 增减，含溢出保护
├── Knowledge (服务端数据层)
│   ├── NetKnowledgeStore                              netId → Set<ItemInfo>，读写 + 脏标记
│   ├── NetKnowledgeNbt                                NBT 序列化（Codec），挂进 BDNet_<id>.dat
│   └── DepositLearnHook                               折算时把物品并入网络学习集合
├── Deposit (服务端逻辑)
│   ├── EmcDepositHandler                              UnifiedStorageBeforeInsertHandler 实现（纯函数）
│   └── ServerReadyGuard                               ServerStartedEvent 打标
├── Exchange (服务端逻辑)
│   ├── ExchangeRequestPacket (C2S)                    兑换请求
│   ├── ExchangeService                                校验 + 扣费 + 发放（唯一权威）
│   └── KnowledgeSyncPacket (S2C)                      学习集合 + EMC 余额同步
├── Client (客户端)
│   ├── VirtualEntryProvider                           计算"已学习但无库存"的虚拟条目
│   ├── DimensionsNetMenuMixin                         buildIndexList / updateViewerStorage 注入
│   ├── ClientNetStorageAccessor                       @Accessor 读 matchFilter / 写 cacheIndexes
│   └── BDBaseGUIMixin                                 slotClicked 拦截虚拟槽 → 发兑换包
└── MineMixin（BD 内部）
    ├── DimensionsNetSaveLoadMixin                     save/load 读写 beyondemc:knowledge
    └── DimensionsNetMergeMixin                        mergeOtherNet 时合并学习集合
```

### 3.2 数据模型

| 数据 | 载体 | 位置 | 持久化 | 同步 | 生命周期 |
|---|---|---|---|---|---|
| **网络 EMC 余额** | `EmcStackKey` 资源（long） | 网络 `UnifiedStorage` 的一个槽位 | BD 自动（`BDNet_<id>.dat` 的 `UnifiedStorage` 键） | BD 自动（`DisorderedSlotGroupSyncPacket` delta） | 随网络销毁/合并自动处理 |
| **网络已学习集合** | `Set<ItemInfo>`（用 ProjectE 的 `getPersistentInfo` 归一化） | `BDNet_<id>.dat` 的自定义键 `beyondemc:knowledge` | 我们的 Mixin 写入 | 我们的 `KnowledgeSyncPacket`（菜单打开时全量 + 变更时增量） | 随网络销毁自动消失；合并时我们的 Mixin 负责合并 |
| 折算价缓存 | `Map<ItemInfo, Long>` | 仅内存 | 无（可重建） | 不需要（客户端各自算） | 监听 `EMCRemapEvent` 失效 |

**为什么 EMC 和知识用两套载体？** 因为它们是两种不同形状的数据：EMC 是"一个数量"（完美契合 `key→long`），知识是"一组物品身份"（契合 `Set<ItemInfo>`）。硬塞进同一种载体都会变形。这个不对称是刻意的。

### 3.3 与两个前置模组的边界

**我们从 BD 取的（全部是官方 API，除 Mixin 外）**

| 用途 | 接口 | 位置 |
|---|---|---|
| 拿网络对象 | `DimensionsNet.getNetFromId(int)` / `getNetFromPlayer(Player)` | `api/dimensionnet/DimensionsNet.java:182/208` |
| 拿网络存储 | `DimensionsNet.getUnifiedStorage()` | `:821` |
| 存/取 EMC | `UnifiedStorage.insert/extract`、`getStackByKey` | `api/dimensionnet/UnifiedStorage.java:87/105` |
| 折算钩子 | `UnifiedStorageBeforeInsertHandler.addHandler` | `api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java:42` |
| 注册资源类型 | `StackKeyRegistry.registerType` | `api/storage/key/StackKeyRegistry.java:14` |
| 权限判定 | `DimensionsNet.isOwner/isManager`、`getPlayers()` | `:687/706/600` |
| 界面注入 | `DimensionsNetMenu.buildIndexList`（public）、`ClientNetStorage` | `common/menu/DimensionsNetMenu.java:241`、`common/menu/widget/ClientNetStorage.java` |

**我们从 ProjectE 取的**

| 用途 | 接口 | 位置 |
|---|---|---|
| 回收价（折算） | `IEMCProxy.INSTANCE.getSellValue(ItemInfo)` | `api/proxy/IEMCProxy.java:188` |
| 购买价（兑换） | `IEMCProxy.INSTANCE.getValue(ItemInfo)` | `:152` |
| 归一化 | `IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo)` | `:199` |
| 知识（v2 才用） | `player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)` | `api/capabilities/PECapabilities.java:42` |
| 失效通知 | `EMCRemapEvent` | `api/event/EMCRemapEvent.java` |

**关键约定**
- `IEMCProxy` 在**双端**可用，但**世界未加载时返回 0**。所有价格查询必须容忍 0（0 = 无 EMC 值 = 不折算 = 原样入库）。
- `getSellValue` / `getValue` 对**带数据组件的物品**会走 `DataComponentProcessor` 链（耐久打折、储能加价、容器内容物）；默认**不**给附魔加价（`EnchantmentProcessor` 默认禁用）。→ 见 §6 与 §7-R4。

---

## 4. 关键流程

### 4.1 存入折算（核心路径，零 Mixin）

```
玩家/AE2/漏斗/网络接口 → UnifiedStorage.insert(key, amount, simulate)
  └─ UnifiedStorage.java:91  UnifiedStorageBeforeInsertHandler.onBeforeInsert(input, net)
       └─ [我们] EmcDepositHandler:
            if (net == null) return 原样                 // UnifiedStorage.getEmpty() 的 net 是 null
            if (!ServerReadyGuard.isReady()) return 原样   // 读档/启动期不折算（D5）
            if (simulate 不可知) → 处理器必须是纯函数       // 见 §7-R2
            if (key 不是 ItemStackKey) return 原样
            ItemStack st = key.getReadOnlyStack()
            if (!configAllows(st)) return 原样             // 组件物品策略，§6
            long sell = IEMCProxy.INSTANCE.getSellValue(ItemInfo.fromStack(st))
            if (sell <= 0) return 原样                     // 无 EMC 值 → 原样入库（R4）
            long total = saturatingMultiply(sell, amount)  // 溢出保护
            DepositLearnHook.learn(net, st)                // R3：并入网络学习集合
            return new KeyAmount(EmcStackKey.INSTANCE, total), cancel=false
  └─ super.insert(EmcStackKey, total, simulate)           // BD 原生写入 + onChange + delta 广播
```

**要点**
- 钩子返回 `cancel=false` + `KeyAmount(EMC, total)`，让 BD 把"物品"当成"EMC 资源"存进去。语义上是**用 EMC 替换了物品**。
- **不能**用"返回空 KeyAmount"表示"什么都不做"：`UnifiedStorage.java:98-99` 会把空结果直接返回给调用方，界面槽位会认为没插入成功而把物品留在原地。要么返回换算后的 EMC，要么原样返回 `current`。
- `unzipMatterBall` 内部会**递归**调用 `insert`（`AbstractUnorderedStackHandler.java:624`），所以钩子必须幂等、无状态副作用累积 —— 我们的实现天然满足（纯函数 + `Set.add`）。
- 钩子**不区分 `simulate`**（BD 未把该参数传进钩子）。这让"学习"成为唯一的非纯副作用，见 §7-R2。

### 4.2 学习（R3）

```
DepositLearnHook.learn(net, stack):
    ItemInfo info = IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(stack))
    if (info 为空) return
    Set<ItemInfo> set = NetKnowledgeStore.get(net)   // 懒加载 + 缓存
    if (set.add(info)) NetKnowledgeStore.markDirty(net)
```

- 用 `getPersistentInfo` 归一化：与 ProjectE 内部 `addKnowledge` 的行为一致（`KnowledgeImpl.java:154`），保证"同一种物品"的判定和 ProjectE 转换桌相同（例如剥离不影响价值的组件）。
- **不写玩家个人知识**（D4）。玩家个人转换台的知识不受本模组影响。
- 集合里存的是 `ItemInfo`（`Item` + `DataComponentPatch`），有正确的 `equals/hashCode`，可直接做 `Set` 元素（`ItemInfo.java:201-221`）。

### 4.3 界面虚拟条目注入（R5/R6/R7）

**服务端**：`KnowledgeSyncPacket`（S2C）在网络菜单打开时下发该网络的学习集合；集合变更时下发增量。EMC 余额**不需要**在这个包里，因为 BD 已经把它作为真实资源同步到客户端视图了。

**客户端注入时机**：Mixin `DimensionsNetMenu.buildIndexList` 的 `HEAD`。

```
buildIndexList() HEAD:
    1. long emc = clientNetStorage.getStackByKey(EmcStackKey.INSTANCE).amount()
    2. for (ItemInfo info : syncedKnowledge):
         ItemStackKey key = ItemStackKey.of(info.createStack())   // 复用 BD 已注册的 key
         if (clientNetStorage.hasStack(key)) continue             // R6：有库存走 BD 原生行，不重复注入
         if (!clientNetStorage.matchFilter(key)) continue          // 尊重搜索框（matchFilter 是 private，需 @Accessor）
         long buy = IEMCProxy.INSTANCE.getValue(info)
         if (buy <= 0) continue
         long affordable = emc / buy
         if (affordable <= 0) continue                             // 0 数量条目会被 buildSortedIndex 丢弃，见下
         clientNetStorage.setAmountByKey(key, affordable)          // 写进视图
    3. clientNetStorage.cacheIndexes = null                       // @Accessor 破排序缓存
```

**已知的两个"坑"与对策**

| 坑 | 证据 | 对策 |
|---|---|---|
| `buildSortedIndex` 命中缓存直接返回旧索引，注入不生效 | `ClientNetStorage.java:161-165` | 注入后置 `cacheIndexes = null`（需要 `@Accessor`） |
| `updateViewerStorage(true)`（按住 Shift 时）会遍历视图已有 key 并从真实存储覆写数量，把虚拟条目刷成 0 | `DimensionsNetMenu.java:99`、`ClientNetStorage.java:134-142` | 我们把注入点放在 `buildIndexList` HEAD，每次重建索引前都重新注入；同时在该路径注入（即 `updateViewerStorage` RETURN 也补一次注入）作为双保险 |
| 0 数量条目会被列表丢弃 → **买不起的物品不会出现在列表里** | `ClientNetStorage.java:191` + `KeyAmount.java:204-206`（`isEmpty() = amount<=0 \|\| key.isEmpty()`） | v1 接受该行为（买不起就不显示，符合"显示能转换出的数量"）。若产品上要求"显示但置灰"，需要额外的渲染层注入，列入 v2 |
| 列表硬上限 `getLines()*9 = 891` 条 | `DimensionsNetMenu.java:257` | v1 接受；学习集合很大时会挤占真实物品的展示位。列入 §7-R7 |

### 4.4 EMC 兑换取出（R8）

```
客户端：BDBaseGUI.slotClicked 里，若点击的槽位映射到一个"虚拟条目"
   → 取消原逻辑，发 ExchangeRequestPacket(ItemStackKey, count)
   → count 规则：左键=1；Shift+左键=尽可能多（客户端只做预估值，服务端裁剪）

服务端：ExchangeService.handle(player, key, count)
   1. net = DimensionsNet.getNetFromPlayer(player); if (net == null) 拒绝
   2. 权限：net.isOwner/isManager/是成员 —— 与 BD 的取出权限保持一致（§9-S3 待确认口径）
   3. ItemInfo info = 归一化(key 的 stack)
   4. if (!NetKnowledgeStore.get(net).contains(info)) 拒绝      // 只允许兑换已学习的物品
   5. long buy = IEMCProxy.INSTANCE.getValue(info); if (buy <= 0) 拒绝
   6. long cost = saturatingMultiply(buy, count)
   7. if (net.getUnifiedStorage().getStackByKey(EmcStackKey.INSTANCE).amount() < cost) 拒绝
   8. 背包空间预检：**装不下时按能装下的数量裁剪**（完全装不下才拒绝）；只按实际发放数量收费。
     安全性质不变：绝不会先扣钱再发现装不下

> ⚠️ **关于 `require = 0` 的更正**：架构文档早先把 `require = 0` 描述为"优雅降级"，
> 这是**不完整**的。`require = 0` 只覆盖「**注入点找不到**」这一种情况；
> handler 的**签名/描述符错误**是硬错误 —— 无论 `require` 设成什么，Mixin 都会抛
> `InvalidInjectionException` 并**中断启动**（实测：给 `UnifiedStorage` 写
> `method = "extract"` 时它有 3 个同名重载，匹配错了，服务器直接崩）。
> 所以存在重载时必须写**完整描述符**。详见 `docs/testing/interface-withdraw-report.md` §6。
   9. 原子扣费：storage.extract(EmcStackKey.INSTANCE, cost, false, false)
  10. 发放：把 count 个 ItemStack 放进玩家背包
  11. 广播/脏标记：BD 的 extract 会自动 onChange → 自动 delta 同步给所有打开界面的玩家
```

**为什么每一步都要重算？** 客户端传上来的只有"物品 + 数量"，价格与余额一律服务端重算。客户端显示的数量只是 UI 提示，**不可信**。

**为什么不用 `UnifiedStorageBeforeExtractHandler`？** 见 D6：重入风险 + 缺上下文。

### 4.5 读档 / 合并 / 销毁

| 场景 | EMC（在 UnifiedStorage 里） | 知识集合（在我们注入的 NBT 里） |
|---|---|---|
| 读档 | BD 自动反序列化；反序列化路径会再次触发我们的钩子，但 `ServerReadyGuard` 未就绪 → 不折算，条目原样通过 | Mixin `DimensionsNet.load` RETURN：从 `tag` 读 `beyondemc:knowledge` |
| 保存 | BD 自动 | Mixin `DimensionsNet.save` RETURN：写入 `tag` |
| 网络合并 | BD 原生搬运 | Mixin `mergeOtherNet`：并集合并 |
| 网络销毁 | 随文件删除 | 随文件删除（零泄漏） |
| 卸载本模组 | ⚠️ `EmcStackKey` 类型未注册 → `AbstractUnorderedStackHandler.java:889-898` 会 `catch(Throwable)` 吞掉并**静默丢弃该条目**（网络 EMC 归零，但存档不坏） | 无害的孤儿 NBT 键，被忽略 |

---

## 5. 接口契约（实现锚点速查）

| 我们要写的 | 依赖的目标 | 注入方式 | 备注 |
|---|---|---|---|
| `EmcDepositHandler` | `UnifiedStorageBeforeInsertHandler.addHandler` | 官方 API | 必须在 mod 构造期或 `FMLCommonSetupEvent` 一次性注册（`handlers` 是 `private static final ArrayList`，非线程安全） |
| `EmcStackKey` 注册 | `StackKeyRegistry.registerType` | 官方 API | 必须在 `FMLCommonSetupEvent`，且**早于任何网络反序列化** |
| `ServerReadyGuard` | `ServerStartedEvent` | `@EventBusSubscriber`（game bus） | 另在 `ServerStoppedEvent` 复位 |
| 知识读写 | `DimensionsNet.save/load/mergeOtherNet` | Mixin `@Inject(at=RETURN)` | `load` 是 `public static`，`save` 是实例方法（`@Override SavedData.save`） |
| 虚拟条目 | `DimensionsNetMenu.buildIndexList` (HEAD) + `updateViewerStorage` (RETURN) | Mixin | 两个方法都是 `public` |
| 破排序缓存 / 读搜索过滤 | `ClientNetStorage.cacheIndexes`（private 字段）、`matchFilter`（private 方法，`ClientNetStorage.java:258`） | Mixin `@Accessor` / `@Invoker`，或直接复刻过滤逻辑 | 优先用 `@Invoker` 调用原方法，避免搜索语义漂移 |
| 点击拦截 | `BDBaseGUI.slotClicked` | Mixin `@Inject(HEAD, cancellable)` | `client/gui/BDBaseGUI.java:153-217` |
| 网络事件 | `DimensionsNetEvent.Created/Destroyed` | `@EventBusSubscriber`（game bus，**不可取消**） | `Destroyed` 不带 `getNet()`，但有 `getDestroyedId()`（`:88`） |

---

## 6. 配置项清单（草案）

| 配置 | 默认 | 说明 |
|---|---|---|
| `enableEmcDeposit` | `true` | 总开关。关闭后一切物品按原逻辑入库 |
| `convertComponentItems` | `false` | `false`：带非默认数据组件的物品（附魔、耐久、自定义名、储能）**不折算**，原样入库。`true`：按 ProjectE 的组件加成规则折算（会丢失组件信息） |
| `emcDepositBlacklist` / `Whitelist` | 空 | 物品 ID / 标签 级别的排除与白名单 |
| `teachDepositingPlayer` | `false` | v1 不实现（D4），配置项预留给 v2 |
| `showVirtualEntries` | `true` | 是否在界面注入"无库存可兑换"条目 |
| `exchangeRequiresKnowledge` | `true` | 兑换是否必须命中网络学习集合（关闭 = 任何有 EMC 的物品都能换，不推荐） |
| `maxExchangePerClick` | `Long.MAX_VALUE` | 单次兑换上限，防误操作 |

---

## 7. 风险登记册

> 严重度：🔴 阻断 / 🟠 高 / 🟡 中 / 🟢 低

| ID | 风险 | 严重度 | 证据 | 对策 / 验证 |
|---|---|---|---|---|
| R1 | **读档时反复折算，不可逆污染存档** | 🟡→🟢 | `DimensionsNet.java:358` → `AbstractUnorderedStackHandler.java:989`（`acceptEntry → insert`） | **两道防线均已落地并验证**：① `EmcAvailability`（`EMCRemapEvent`）—— 读档发生在玩家登录前，EMC 表必为空，价格恒为 0 → 不折算；② 显式的 `LoadingGuard` + `AbstractUnorderedStorageLoadMixin`（`@WrapMethod` 保证 try/finally 语义）。**Mixin 生效性由 `Phase3SelfTest` 第 8 项实测证明**（反序列化期间 `enter()` 被调用、退出后复位、数据完整）。该 Mixin 是全项目唯一用 `require = 1` 的一处 —— 静默失效等于数据损坏，宁可启动时响亮地崩。剩余：真实存档往返仍需实机确认（`testing/phase3-report.md` §6） |
| R2 | 钩子拿不到 `simulate`，学习成为非纯副作用 → 模拟存入（容量不足等）也会"学会"该物品 | 🟢 | BD 未把 `simulate` 传进 `BeforeInsertHandler` | **阶段 3 采纳并实现**：接受该偏差。理由：`Set.add` 幂等；玩家手里本来就有该物品，而 ProjectE 的学习本就只需持有物品，无实质套利空间。精确版需要订阅 delta 事件 + 待定队列，复杂度不值当。折算本身仍是纯函数（唯一副作用是学习）。见 `EmcDepositHandler` 类注释 |
| R3 | ~~**EMC 行在 BD 界面里被点击会发生什么未知**~~ | ✅ 已解除 | `DisorderedStackTypedSlot.java:181-195` 的 `if (key instanceof ItemStackKey)` **没有 else 分支** | **阶段 2 裁决**：点击非物品资源行是安全空操作；渲染器为客户端专用（BD 的 4 个 `getRender()` 调用点全在客户端）。详见 `docs/testing/phase2-report.md` §3 |
| R4 | 组件物品折算语义有损：附魔/耐久/容器内容物被按 ProjectE 规则计价后**组件信息消失** | 🟠 | `DataComponentManager.java:56-89`、`DamageProcessor`、`StoredEMCProcessor`；`EnchantmentProcessor` 默认禁用 | 默认 `convertComponentItems=false` 把它们排除在折算外。**专项测试**：附魔剑、耐久镐、带内容的潜影盒、存有 EMC 的 Klein Star |
| R5 | `long` 溢出：`sell × amount` / `buy × count` | 🟠 | 网络默认槽容量 `2^63-1` | 一律用饱和乘法（`Math.multiplyExact` + catch 或手写溢出检测），溢出即拒绝操作并记录日志 |
| R6 | `EmcStackKey.getVanillaMaxStackSize()` 选值不当导致"一次只能换 100 万" | 🟡 | `EnergyStackKey`/`ManaStackKey` 都返回 `1000000`（`ManaStackKey.java:93-96`）；BD 多处 `Math.min(..., getVanillaMaxStackSize())` | EMC 键返回 `Long.MAX_VALUE`（`LongStackKey` 的默认值），**专项测试**大额存入/兑换 |
| R7 | 界面 891 条硬上限，学习集合很大时挤占真实物品展示 | 🟡 | `DimensionsNetMenu.java:257` | v1 接受并在 tooltip/文档说明；v2 考虑给虚拟条目加独立分页或开关 |
| R8 | Mixin 强耦合 BD 内部实现，BD 更新即可能失效 | 🟠 | 我们的 6 个 Mixin 目标 | 全部用 `require = 0` + `remap = false` 优雅降级（Mixin 失败时功能静默禁用，不崩游戏）；锁定并记录 BD commit；建立"升级 BD 后的回归测试清单"（见 `docs/plan/ROADMAP.md` §升级回归） |
| R9 | 卸载本模组 → 网络 EMC 条目被 `catch(Throwable)` 静默丢弃 | 🟡 | `AbstractUnorderedStackHandler.java:889-898` | 在 README 明确警告"移除本模组前请先清空网络 EMC"；可选：提供 `/beyondemc drain` 命令把 EMC 兑换成物品 |
| R10 | 客户端虚拟条目与服务端真实状态不一致（幽灵物品） | 🟠 | 客户端注入是本地的，服务端不知情 | 所有兑换都在服务端重新校验（§4.4）；客户端注入只影响显示；`KnowledgeSyncPacket` 保证学习集合一致；兑换成功后由 BD 的 delta 同步刷新 EMC 行 |
| R11 | 共享网络的**权限口径**：谁能用网络 EMC 兑换 | ✅ 已定 | `OpenNetGuiPacket.java:65-66`（唯一检查是 `getNetFromPlayer != null`）+ `DisorderedStackTypedSlot.java:178` 的 `mayPickup` 用原版默认实现（恒真，全仓库无覆写） | **Spike S3 已关闭（阶段 4）**：BD 原生取出权限 = "是该网络成员"，Owner/Manager 不额外要求。`ExchangeService.canAccess` 与之对齐（并额外判 owner/manager 作为零成本保险） |
| R12 | `getKnowledge()` 对"全书"玩家每次调用都新建全量 `HashSet` | 🟢（v1 不触发） | `KnowledgeImpl.java:199`、`EMCMappingHandler.java:195` | v1 不读玩家个人知识；v2 若读，必须缓存并在 `PlayerKnowledgeChangeEvent`/`EMCRemapEvent` 时失效 |
| R13 | `/reload` 后 EMC 定价表变化，缓存过期可能被用于套利 | 🟡 | `EMCRemapEvent`、`EMCMappingHandler.java:134-145` | 任何价格缓存都监听 `EMCRemapEvent` 清空；**绝不缓存**"物品→价格"到存档里 |

---

## 8. 非目标（明确不做）

1. 不修改玩家个人 ProjectE 知识 / 个人 EMC（D4）。
2. 不做"个人 EMC ↔ 网络 EMC"的互通（已确认：完全独立）。
3. 不让 EMC 成为可被漏斗/AE2/RS 抽取的一等物流资源 —— 即**不注册** `CapabilityHelper.USHandlerMap` / `AUHandlerMap` / `BlockCapabilityMap` / `ItemCapabilityMap` / `StackHandlerWrapperHelper` 等对外能力映射（这些是可选注册，见 `BotaniaModule.java:64-68`）。
4. 不做跨模组的 EMC 记账（不与其它 EMC 类模组互通）。
5. 不支持 1.21.1 以外的 MC 版本（v1）。

---

## 9. 待验证清单（Spike，必须在写正式代码前解决）

| ID | 问题 | 为什么重要 | 建议的验证方式 | 计划阶段 |
|---|---|---|---|---|
| S1 | ~~R2/R6 互斥：是否要把折算语义改成"只折算未学习的物品"？~~ | — | **已关闭**：用户确认采用无条件折算（A 方案），不做备选路径 | 阶段 0 ✅ |
| S2 | ~~🔴 EMC 行在 BD 界面里的点击行为（风险 R3）~~ | — | **✅ 已关闭（阶段 2）**：点击是安全空操作（`DisorderedStackTypedSlot.java:181-195` 无 else 分支）；渲染器仅客户端可用（BD 的 `getRender()` 调用点全在客户端）。EMC 载体确认为方案 A | 阶段 2 ✅ |
| S3 | ~~BD 原生"从网络取出物品"的权限门槛到底是什么~~ | — | **✅ 已关闭（阶段 4）**：只有"是该网络成员"一条门槛。`OpenNetGuiPacket.java:65-66` 的唯一检查是 `DimensionsNet.getNetFromPlayer(player) != null`；槽位侧 `mayPickup` 是原版 `Slot` 的默认实现（恒真，全仓库无覆写）→ Member 及以上都能取出。兑换口径已与之对齐 | 阶段 4 ✅ |
| S4 | ~~读档期间 `IEMCProxy` 是否已可用（EMC 表是否已构建）~~ | — | **✅ 已实测（阶段 1）**：专用服务器上 `ServerStartedEvent` 时 EMC 表仍为空（钻石 EMC=0），空跑 60 秒 `EMCRemapEvent` 从未触发。EMC 表要等**有玩家登录或 `/reload`** 才由 `EMCMappingHandler.map(...)` 算出。→ 就绪判据改用 `EMCRemapEvent`，见 D5 与 ADR-003 | 阶段 1 ✅ |
| S5 | `@WrapMethod`（MixinExtras）在 NeoForge 21.1 是否可用 | 影响 Mixin 写法（try/finally 守卫） | 写一个最小 Mixin 试编译 | 阶段 1 |
| S6 | `ItemStackKey` 从 `ItemInfo.createStack()` 构造的正确姿势（是否需要规范化组件） | 决定虚拟条目能否命中 `hasStack` / `matchFilter` | 阶段 5 用已知物品往返测试（存入→视图里的 key 与虚拟条目 key 是否 `equals`） | 阶段 5 |
| S7 | BD 的两套依赖接入方式（本地 jar vs Modrinth Maven）在本机能否跑通 | 决定阶段 1 能否开始 | 见 `docs/development/build-and-deps.md` | 阶段 1 |
| S8 | `ClientNetStorage.matchFilter` 用 `@Invoker` 调用是否可行 | 决定搜索框能否过滤虚拟条目 | 阶段 5 实测 | 阶段 5 |
| S9 | 网络合并（`mergeOtherNet`）时学习集合如何合并 | 影响数据完整性 | 阶段 3 双网络合并实测 | 阶段 3 |
