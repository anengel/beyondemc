# 决策记录（ADR）：BeyondEMC

> 配套文档：`docs/design/architecture.md`（架构基线）、`docs/research/`（源码证据）
> 每条决策记录：背景 → 备选 → 结论 → 理由 → 代价 → 何时需要重新评估

---

## ADR-001：EMC 的存储载体

### 背景
需要在维度网络里持久化一个 `long` EMC 数值，并且：
- 随网络自动保存/加载；
- 同步给所有打开该网络的客户端（客户端要拿它算"能换出多少"）；
- 网络合并/销毁时行为正确；
- 不应该被漏斗/AE2/RS 等物流当成可抽取资源。

`DimensionsNet extends SavedData`，一个网络一个文件 `<world>/data/BDNet_<id>.dat`，`save/load` 只有 9 个固定 NBT 键，**BD 没有提供任何自定义数据槽**（`bd-persistence-lifecycle-events.md` §1）。唯一官方扩展面是"注册自定义 `IStackKey` 资源类型"。

### 备选

| 方案 | 持久化 | 同步 | 界面显示 | 合并/销毁 | 卸载安全性 | 物流暴露 | 工作量 |
|---|---|---|---|---|---|---|---|
| **A. 自定义 `IStackKey` 存进 `UnifiedStorage`** | BD 自动 | BD 自动（delta 包） | 自动成为列表一行 | BD 自动搬运/删除 | ⚠️ 未注册类型会被 `catch(Throwable)` 静默丢弃（EMC 归零，存档不坏） | 由我们决定：不注册能力映射即不暴露 | 小（照抄 `ManaStackKey` 5 个文件） |
| **B. Mixin `DimensionsNet.save/load` 写进网络自己的 NBT** | 我们的 Mixin | 需自己发包 | 需自己画 | 需自己处理合并 | ✅ 孤儿 NBT 键，被忽略 | 天然不暴露 | 中（2~3 个 Mixin + 自绘 UI） |
| **C. 独立 `SavedData`（key = netId）** | 自己管 | 需自己发包 | 需自己画 | 需处理懒初始化 + `Destroyed` 清理 + 合并 | ✅ 独立文件，但会留垃圾文件 | 天然不暴露 | 中偏大 |

### 结论
**选 A**，并用 §4.5 的方式兜住它的两个弱点。

### 理由
1. 官方路径。BD 自己的四个集成模块（Botania / Ars / Mekanism / IFS）全部走这条路，是设计者预期的扩展方式（`BotaniaModule.java:60-72` 是最干净的样板）。
2. 省掉一整层基础设施：delta 同步、分包（900KiB）、客户端镜像、菜单生命周期，全部白送。方案 B/C 都要自己重写一遍，而这些正是最容易出细碎 bug 的地方。
3. EMC 天然是"一个数量"，与 `key → long` 的存储模型完美契合；折算钩子只需**把 key 换掉**，交给 `super.insert` 原子完成，不需要任何自定义事务。
4. "物流暴露"由我们控制：不注册 `CapabilityHelper.*` 与 `StackHandlerWrapperHelper` 映射，外部模组就看不到 EMC（见 `architecture.md` §8-3）。

### 代价
1. **卸载风险**：`AbstractUnorderedStackHandler.java:889-898` 用 `catch(Throwable)` 吞掉反序列化失败并跳过条目 → 移除本模组后网络 EMC 静默归零。必须写进 README，并提供 drain 命令。
2. **EMC 会成为列表里的一行**，其点击行为未知 → 风险 R3 / Spike S2。
3. 需要实现 `IStackRender`，否则 GUI/搜索/tooltip 会 NPE（`BDBaseGUI.java:56,86-87`）。

### 重新评估的触发条件
- ~~Spike S2 发现 EMC 行在界面里会崩溃或产生垃圾物品，且无法用 Mixin 干净地屏蔽 → 退到方案 B。~~

### ✅ 裁决（2026-09-27，阶段 2 实测）：**方案 A 成立，采纳**

Spike S2 已给出结论，风险解除：

1. **点击安全**（静态代码证据）：`DisorderedStackTypedSlot.click` 在「槽位有内容 + 手为空」时是
   `if (clickStack.key() instanceof ItemStackKey clickKey) {...}`，**没有 else 分支**
   （`DisorderedStackTypedSlot.java:181-195`）→ 点击非物品资源行是**完全空操作**，不崩溃、不产生垃圾物品。
   批量移动路径同理（`:468` 的 `instanceof` 链也没有兜底分支）。
2. **渲染仅客户端**（实测约束）：渲染器实现引用了 `Minecraft`/`ClientLevel`，在专用服务器上加载会被
   NeoForge 的运行时 dist 清理器直接抛异常。但 BD **从不在服务端调用 `getRender()`** ——
   全部 4 个调用点都已在客户端（枚举见 `docs/testing/phase2-report.md` §3.2）。
   该约束由 `EmcStorageSelfTest` 第 8 项做哨兵监控。
3. **附带利好**：手里拿着物品右键点击 EMC 行会走正常"存入携带物"路径，正好触发阶段 3 的折算钩子。

**未覆盖的部分**：客户端实机渲染（图标/数量/tooltip 的实际绘制）无头环境测不了，留给阶段 5 首次实机验证。
- 用户改变主意，要求 EMC 必须对 AE2/RS 可见 → 只需追加注册能力映射，不用换方案。

---

## ADR-002：网络级"已学习集合"的存储载体

### 背景
已确认"已学习"的口径是**网络级共享集合**。集合元素是物品身份（`ItemInfo`），条目数可达上千，且大多数条目的"数量"恒为 1 或 0。

### 备选

| 方案 | 结论 |
|---|---|
| 存进 `UnifiedStorage`（每个已学习物品一个自定义 key，amount=1） | ❌ 否决。会污染真实库存视图；`UnifiedStorage` 是 `RemoveZero` 策略，0 数量条目会被直接删除；还会被 BD 的排序/搜索/取出路径当成真实资源 |
| 独立 `SavedData`（key = netId） | ⚠️ 可行。`DimensionsNetEvent.Destroyed` 带 `getDestroyedId()`（`DimensionsNetEvent.java:88`）可以清理；`Created` 只在新建时触发，读档不触发 → 必须懒初始化；网络合并（`mergeOtherNet`）时需要自己搬运 |
| 存进网络自己的 NBT（Mixin `DimensionsNet.save/load`） | ✅ 选中 |
| NeoForge DataAttachment | ❌ 不可行。NeoForge 21.1 的 attachment 只支持 BlockEntity/Chunk/Entity/ItemStack，且要求目标实现 `IAttachmentHolder`；`DimensionsNet` 是普通 `SavedData` |

### 结论
**Mixin `DimensionsNet.save` / `load`（+ `mergeOtherNet`），把集合写成 `BDNet_<id>.dat` 里的一个自定义 NBT 键 `beyondemc:knowledge`。**

### 理由
1. 生命周期完全跟随网络文件：网络销毁 = 文件删除 = 集合消失，**零泄漏、零清理代码**。
2. 与 ADR-001 形成清晰分工：**数量型数据走存储资源，身份型数据走网络 NBT**。
3. 卸载本模组时只留一个无人读取的 NBT 键，比方案 C 留下整个孤儿 `.dat` 文件干净。
4. 需要 Mixin 的成本可接受 —— 界面方案本来就要用 Mixin（用户已确认），基础设施已经建好。

### 代价
1. 需要 Mixin 到 `DimensionsNet` 的存档方法，属于"耦合 BD 内部实现"（风险 R8）。缓解：`require = 0` + 版本锁定 + 回归清单。
2. 客户端同步要自己写（`KnowledgeSyncPacket`）—— 但方案 B/C 本来也躲不掉。

---

## ADR-003：折算的接入点与读档守卫

### 备选

| 方案 | 结论 |
|---|---|
| 官方 `UnifiedStorageBeforeInsertHandler.addHandler`（纯函数换算 key） | ✅ 选中（D2） |
| Mixin `UnifiedStorage.insert`（HEAD, cancellable，可看到 `simulate`） | ⚠️ 保留为 v2 精修。它能看到 `simulate`（解决风险 R2），但会绕过 BD 自己的 handler 链，且多一个内部实现耦合点 |
| Mixin `AbstractUnorderedStackHandler.acceptEntry` | ❌ 太深，覆盖不到 GUI 路径 |

### 读档守卫
`DimensionsNet.load` → `unifiedStorage.deserializeNBT` → `acceptEntry` → `insert`（`AbstractUnorderedStackHandler.java:989`）会再次触发我们的钩子。若不加守卫，**每次读档都会把存档里剩余的、有 EMC 的物品再折算一遍**，且不可逆（风险 R1）。

#### ⚠️ 2026-09-27 更正：原方案（`ServerStartedEvent` 置位）不成立

**阶段 1 实测推翻了"服务器已启动 ⇒ EMC 表就绪"这个假设。** 专用服务器上：

| 时刻 | 钻石 EMC | `EMCRemapEvent` 次数 |
|---|---|---|
| `ServerStartedEvent` | 0 | 0 |
| tick 1200（约 60s） | 0 | 0 |

**源码根因**：
- `EMCMappingHandler.loadMappers()`（在 ProjectE 的 `FMLCommonSetupEvent` 里调用）**只登记 mapper 列表，不算任何 EMC 值**（`EMCMappingHandler.java:57-68`）。
- 真正算值的是 `EMCMappingHandler.map(...)`（`:70-123`），调用点在 `PECore#dataPackSync(OnDatapackSyncEvent)`（`PECore.java:269`）—— 而 `OnDatapackSyncEvent` 在没有玩家登录时**不触发**。
- `map(...)` 末尾 `fireEmcRemapEvent()` 会 post **`EMCRemapEvent`**（`:122, 145`）。

#### 更正后的方案（两层）

**第一层：就绪判据 = `EMCRemapEvent`**
- 它是 ProjectE 的官方事件，恰好在 EMC 值算完后发出；每次 `/reload` 重算后还会再发。
- 同一个监听器兼任阶段 6 的"价格缓存失效"钩子（风险 R13）。
- 实现已在阶段 1 完成：`EmcAvailability`（现位于 `diag` 包，阶段 2 搬到正式包）。

**第二层：显式的"反序列化中不折算"守卫**
- 为什么还需要它：`DimensionsNet.getNetFromId` → `computeIfAbsent` 会在**玩家已登录之后**按需加载某个尚未载入的网络，此时 `save/load → deserializeNBT → insert` 会触发钩子，而 EMC 表**已经就绪**。
- 常规世界不会出问题（有 EMC 的物品在存入时就被折算掉了，存档里不存在这类条目），但**迁移场景**（给已有存档加装本模组）会。
- 手段：`@WrapMethod`（MixinExtras，NeoForge 21.1 已内置，阶段 1 日志确认 `MixinExtras 0.5.3` 被初始化）包住
  `AbstractUnorderedStackHandler.deserializeNBT`，用 **ThreadLocal 深度计数**置"正在加载"标志；
  钩子见此标志则原样返回、不折算。用 `@WrapMethod` 而非 `@Inject(HEAD/RETURN)` 是为了 `try/finally` 语义 ——
  反序列化抛异常时也必须复位标志。

**顺带一个利好**：因为 EMC 表在登录前必为空，"价格为 0 就不折算"这条分支本身就是一道天然防线，
所以第一层即使失效也不会造成数据损坏 —— 失败模式是安全的。

---

## ADR-004：兑换（EMC → 物品）的实现路径

### 备选

| 方案 | 结论 |
|---|---|
| 官方 `UnifiedStorageBeforeExtractHandler`，把"提取物品 X"改写成"扣 EMC" | ❌ 否决。该钩子在 `extract` 链内部（`UnifiedStorage.java:122`），在钩子里再调 `extract` 扣 EMC 会**重入同一个 handler 列表**造成死循环；而且钩子签名只有 `KeyAmount + net`，拿不到"给谁 / 要几个 / 为什么" |
| 自定义 C2S 包 + 显式服务端 `ExchangeService` | ✅ 选中（D6、§4.4） |
| 复用 BD 的 `CallSeverClickPacket` 让真实存储产生物品 | ❌ 否决。虚拟条目在服务端不存在，`extractByKey` 会因 `current<=0` 直接返回空 → 静默失败 |

### 理由
兑换是一个**有产品语义的事务**（校验知识 → 校验权限 → 校验余额 → 预检背包 → 扣费 → 发放），需要完整上下文与明确的失败反馈。塞进一个只认 `KeyAmount` 的通用钩子里，会把语义压扁成不可维护的形态。

### 代价
需要自己写一个 C2S 包 + 一个 S2C 包（后者本来也要写）。客户端必须 Mixin `BDBaseGUI.slotClicked` 才能拦截虚拟槽点击。

---

## ADR-004'：抽取钩子的**部分**推翻（2026-09-27，网络接口兑换）

> **这是对 ADR-004 的修订，不是删除。** ADR-004 的**主决策仍然有效**
> （GUI 兑换继续走 C2S 包 + `ExchangeService`，因为那是一个需要完整产品语义的事务）。
> 但其中"官方抽取钩子一律不可用"的判断被证明**过宽**。

### 背景

用户要求"用超越维度的网络接口取出已学习的物品并正常扣 EMC"，即把兑换能力开放给自动化。
自动化路径**没有玩家上下文**，因此 ADR-004 里"拿不到给谁/要几个/为什么"的否合理由不适用；
而接口的抽取入口 `NetInterfaceAccess.transferFromNet`（`NetInterfaceAccess.java:127`）
恰好会经过 `UnifiedStorageBeforeExtractHandler`，是唯一的接入点。

### ADR-004 的三条否合理由，逐条复核

| 原理由 | 复核结论 |
|---|---|
| "在钩子里再调 `extract` 扣 EMC 会重入同一个 handler 列表 → 死循环" | ⚠️ **理由成立，但可以绕开**。我们不在钩子里调 `extract`，而是调 `insert`（触发的是**另一个** handler 列表）+ `spendEmc`（根本不走抽取）。铸造出的物品再由**钩子返回后**的 `super.extract` 取走，不存在重入 |
| "钩子签名只有 `KeyAmount + net`，拿不到 `simulate`" | ✅ **理由成立**。这是真实的安全隐患（模拟抽取会真扣钱）。已用 `UnifiedStorageExtractMixin` 记录该标志解决，且**保守默认判为模拟** |
| "拿不到给谁 / 要几个 / 为什么" | ❌ **在自动化场景下不适用**。接口路径无需玩家身份；数量由 `tryExtract.amount()` 提供；"为什么"由 BD 的过滤器机制承担 |

### 修订后的结论

| 场景 | 采用方案 |
|---|---|
| **GUI 兑换**（有玩家、有失败反馈需求） | 仍走 ADR-004 的 C2S + `ExchangeService`（不变） |
| **自动化兑换**（网络接口等无玩家路径） | 采用 `UnifiedStorageBeforeExtractHandler` + `MintingGuard` + `ExtractContext` + `CanonicalExchange` |

### 新增的强行约束（实现时踩出来的）

1. **钩子只能改"抽什么"，不能凭空造物品** —— 返回的 `KeyAmount` 会被 `super.extract` 真实抽取
   （`UnifiedStorage.java:132`）。所以必须"先铸造进存储、再让原生抽取取走"。
2. **铸造必须带 `MintingGuard`** —— 否则存入折算钩子会把它当场变回 EMC，兑换自我抵消。
3. **必须做身份一致性校验**（`CanonicalExchange`）—— 否则"按归一化身份定价、按请求对象铸造"
   就是**刷物品漏洞**（实测已踩）。

详见 `docs/testing/interface-withdraw-report.md`。

---

## ADR-005：虚拟条目复用 `ItemStackKey`

### 理由
1. 已注册、有现成 `IStackRender`（`ItemStackKeyRender`），渲染链不需要服务端知情（`bd-gui-and-packets.md` §5）。
2. `DisorderedStackTypedSlot.click` 里有 `instanceof ItemStackKey` 分支，复用它可以最大化地复用 BD 的原生交互（JEI/EMI 拖拽、tooltip、数量显示）。
3. 客户端注入的 key 可以与"服务端真实存在的同种物品"用同一套 `equals`（`ItemStackKey.equals` = `item` + 规范化后的 `DataComponentPatch`，`ItemStackKey.java:344-366`）→ 用 `hasStack(key)` 就能干净地实现"有库存就不注入虚拟条目"（R6）。

### 代价
"库存 0 但可兑换"的条目必须给一个 **> 0 的虚拟数量**（`affordable`），因为负数/0 会被列表逻辑丢弃。这在语义上是"把可兑换数量伪装成库存数量"，需要在实现里用注释与命名（`virtualAmount`）明确区分，避免后续维护者误解。

---

## ADR-006：不教玩家个人知识（v1）

### 背景
需求原文"如果未学习则学习"在"网络级共享知识"口径下，指的是写入网络的集合。是否**同时**调用 `IKnowledgeProvider.addKnowledge(player, ...)` 让存入者的个人转换台也学会？

### 结论
**v1 不做。**

### 理由
1. **技术**：折算收口点 `UnifiedStorage.insert` 的签名里没有玩家（`api/dimensionnet/UnifiedStorage.java:87`）。要拿玩家必须再 Mixin 菜单槽位的转移入口 —— 而转移入口有 50+ 个调用点（GUI 点击、背包 shift、批量转移包、手持物直存、漏斗、AE2…），没有任何一处能覆盖全部。
2. **产品**：在共享网络语义下，把物品灌进**个人**转换台知识是越权行为 —— A 存入的物品不应该让 B 的个人转换台解锁它。而"教 A 不教 B"又需要精确的存入者归属，正是第 1 点做不到的。
3. 网络自己的列表已经完整满足了 R5/R7/R8。

### 何时重新评估
若用户明确要求"存入者的个人转换台也解锁该物品"，则实现路径是：只在**玩家主动操作**的入口 Mixin（`DisorderedStackTypedSlot` / `BDBaseMenu` 的转移方法），放弃对漏斗/AE2 等自动路径的覆盖，并在配置里说明该限制。

---

## ADR-007：界面注入点选在 `buildIndexList` HEAD

### 备选

| 注入点 | 结论 |
|---|---|
| `DimensionsNetMenu.buildIndexList` HEAD（每次重建索引前重新注入） | ✅ 选中 |
| `ClientNetStorage.updateViewFromStorage` 之后 | ⚠️ 作为双保险追加 |
| `DisorderedStackTypedSlot` 的取值处（`theSlot`） | ❌ 太靠后，排序/分页/搜索都已固定，虚拟条目无法参与排序 |

### 理由
`ClientNetStorage.resolvePendingOrAllUpdate(true)`（按住 Shift 触发）会遍历**视图里已有的 key** 并从真实存储覆写数量（`ClientNetStorage.java:134-142`），虚拟条目会被刷成 0。把注入点放在 `buildIndexList` HEAD，意味着每次重建列表都先重置一遍虚拟条目，天然覆盖了这个冲刷。

同时必须在注入后置 `cacheIndexes = null`，否则 `buildSortedIndex` 会命中缓存直接返回旧索引（`ClientNetStorage.java:161-165`）。
