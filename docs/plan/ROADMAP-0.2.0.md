# 0.2.0 计划：鼠标吸附拾取 + JEI 配方填充

> 基线：`v0.1.0`（tag `v0.1.0` → `bf897a6`）
> 回退点：`backups/`（bundle + zip + jar + 前置 jar，已做还原演练，见 `backups/README.md`）
> 本文所有关于 Beyond Dimensions / 原版的断言均带 `文件:行号` 证据（项目铁律）

---

## 0. 前置：备份与回退（已完成，未验证过的不算）

| 项 | 状态 |
|---|---|
| git tag `v0.1.0`（附注 tag，指向 `bf897a6`） | ✅ 已打 |
| 分支 `backup/v0.1.0` | ✅ 已建（与 `main` 同点） |
| `backups/beyondemc-0.1.0.bundle`（完整仓库，可 clone） | ✅ |
| `backups/beyondemc-0.1.0-source.zip`（83 个跟踪文件） | ✅ |
| `backups/beyondemc-1.21.1-neoforge-0.1.0.jar`（可直接进游戏） | ✅ |
| `backups/deps/`（两个前置 jar，**不在 git 里**，缺了无法离线重建） | ✅ |
| **还原演练** | ✅ 用 bundle 克隆 → HEAD/提交数/tag/文件数/工作树全部核对通过 |
| SHA-256 校验和 | ✅ 记录在 `backups/README.md` |

**每个阶段结束、以及每次做有风险的改动之前，都要重新备份并演练一次。**

---

## 1. 需求 A：点击兑换的物品吸附到鼠标

### 1.1 现状

`BDBaseGUIMixin` 拦截虚拟条目点击 → 发 `ExchangeRequestPacket(netId, template, count)`
→ 服务端 `ExchangeService.exchange` 扣 EMC → `give()` **直接放进背包**（放不下就掉在脚下）。

### 1.2 目标

像原版从槽位里拾取一样，物品**吸附到鼠标**（carried stack），由玩家自己决定放哪。

### 1.3 原版机制（证据）

| 位置 | 内容 |
|---|---|
| `AbstractContainerMenu.java:776` | `setCarried(ItemStack)` —— **只是赋值**，没有任何同步动作 |
| `AbstractContainerMenu.java:173` | `broadcastChanges()` —— 每 tick 调用 |
| `AbstractContainerMenu.java:181` | 在 `broadcastChanges()` 里调用 `synchronizeCarriedToRemote()` |
| `AbstractContainerMenu.java:253` | `synchronizeCarriedToRemote()`：若 `getCarried()` 与 `remoteCarried` 不同，则更新并 `synchronizer.sendCarriedChange(this, remoteCarried)` |

**结论：服务端只要 `menu.setCarried(新堆叠)`，同步会在下一个 `broadcastChanges()` 自动发生 —— 不需要自定义包、不需要客户端渲染代码。** 这是最干净的实现路径。

### 1.4 设计

**协议改动**：`ExchangeRequestPacket` 增加一个意图字段（枚举），因为"吸附到鼠标"与"放进背包"是两种不同的服务端行为：

```java
public enum ExchangeIntent { PICKUP_TO_CURSOR, QUICK_MOVE_TO_INVENTORY }
```

**服务端新增 `ExchangeService.giveToCursor(player, info, requested)`**：

1. 归一化身份（**复用 `CanonicalExchange`**，防刷物品漏洞的同一条防线）
2. 取 `menu.getCarried()`
3. **先算实际能给多少，再按这个数量扣费**：
   - carried 为空 → `n = min(requested, maxStackSize)`
   - carried 同类（`ItemStack.isSameItemSameComponents`）→ `n = min(requested, maxStackSize - carried.getCount())`；为 0 则提示"鼠标上的堆叠已满"
   - carried 是**别的物品** → **拒绝并提示**（原版会交换，但虚拟槽没有"被换出去的实体"，无法交换）
4. `n = min(n, floor(网络EMC ÷ 购买价))`，为 0 则拒绝
5. 扣 `n × 购买价` → `menu.setCarried(合并后的堆叠)`

> ⚠️ **这条顺序是安全关键**：绝不能"先扣钱、再发现装不下"。
> 0.1.0 的 `Inventory.add` 数据丢失 bug 就是同型错误（信任了一个会撒谎的信号）。
> 本次的不变式：**扣费数量必须等于吸附到鼠标的数量**，并写成自检。

**客户端点击映射**（客户端给的 count 只是建议，服务端一律重新裁剪）：

| 操作 | 意图 | count |
|---|---|---|
| 左键 | `PICKUP_TO_CURSOR` | 一组（原版最大堆叠数） |
| 右键 | `PICKUP_TO_CURSOR` | 一半（向上取整，原版语义） |
| Shift + 左键 | `QUICK_MOVE_TO_INVENTORY` | 一组（保留 0.1.0 的"直接进背包"作为快捷方式） |

### 1.5 需要你拍板（见 §5）

- A1：上面这套点击映射是否合意？
- A2：是否还需要"一次只取 1 个"的入口？（原版左键是整组，"1 个"可以用右键或按住某键）
- A3：鼠标上已有**不同**物品时，拒绝是否可接受？（另一种选择是把 EMC 兑换的物品丢在脚下）

### 1.6 风险

| 风险 | 说明 | 处置 |
|---|---|---|
| R-A1 | 扣费与吸附数量不一致 → 变相刷物品/吞钱 | 单点计算 + 自检断言两者相等 |
| R-A2 | 菜单随时可能关闭，`menu` 引用失效 | 服务端重新解析当前菜单（已有 `canAccess` 与容器校验路径） |
| R-A3 | 创造模式下与原版行为冲突 | 不涉及 `Inventory.add`，天然规避 0.1.0 那个坑 |
| R-A4 | 现有自检与本功能无关，回归难 | 新增 `giveToCursor` 的纯计算部分为可无头测试的函数 |

---

## 2. 需求 B：JEI 配方填充自动用 EMC 兑换

### 2.1 关键发现：服务端**零新增代码**

BD **自带完整的 JEI 集成**，且链路是连贯的：

| 位置 | 内容 |
|---|---|
| `BDjeiPlugin.java:32-37` | 注册 4 个转移处理器：`CraftMenuRecipeTransferHandler`、`CraftTerminalRecipeTransferHandler`、`MenuUniversalRecipeTransfer`、`TerminalUniversalRecipeTransfer` |
| 四者全部 | 汇入 `TransferHelper.transferRecipe(...)`（`TransferHelper.java:29`）—— **唯一漏斗** |
| `CraftMenuRecipeTransferHandler.java:49` | 传入 `container.storage.getStorage()`（客户端网络视图，**含 EMC 条目**） |
| `TransferHelper.java:31-57` | 建立可用池 = 合成栏 + 网络存储 + 玩家背包 |
| `TransferHelper.java:88-93` | 某槽位没有可用材料 → `hasMissing = true`，加入 `missingSlots` |
| `TransferHelper.java:107` | 发送 `RecipeFillC2SPacket(keys, amounts, compressOverflow)` |
| `TransferHelper.java:110-113` | 有缺失则返回 `MissStackError`（JEI 界面上把缺的槽位标红） |
| `RecipeFillC2SPacket.java:52` | 服务端 → `menu.transferRecipe(keys, amount, compressOverflow)` |
| `DimensionsCraftMenu.java:269-273` | 先 `extractFromInventory(...)`，不够再 `extractFromStorage(...)`，然后 `craftSlots.setItem(...)` |
| **`DimensionsCraftMenu.java:337-339`** | **`extractFromStorage` 调 `storage.extract(type, amount, false, false)`** |
| — | **这正是 `UnifiedStorage.extract(IStackKey,…)` → 我们在 0.1.0 装好的抽取钩子 → `InterfaceWithdrawService` 铸造 + 扣 EMC** |

**所以服务端已经能工作了**：只要客户端发出 `RecipeFillC2SPacket`，服务端就会从 EMC 铸造出材料放进合成栏。

### 2.2 因此只需改客户端：让 JEI "别报缺料"

现状：网络的 EMC 里其实"买得起"某材料，但它在网络库存里是 0，
`TransferHelper` 的可用池里没有它 → `MissStackError` → JEI **禁止转移**（按钮报错、不发包）。

**改法**：Mixin `TransferHelper.transferRecipe`，在可用池建好之后、槽位循环之前，
把「网络已学会但无库存」的物品按可兑换数量**追加进 `storage` 列表**。

**数据从哪来（两个关键复用）**：

| 需要 | 来源 | 为什么不用别的 |
|---|---|---|
| 已学习物品 + **单价** | `ClientKnowledgeCache`（0.1.0 已同步：`KnowledgeSyncPacket` 携带服务端算好的 `unitPrice`） | ❌ 绝不用客户端自己的 EMC 价格表 —— 0.1.0 已踩过这个坑（客户端价格表可能为空/过期） |
| 网络 EMC 余额 | 同一个 `storage` 列表里的 `EmcStackKey` 条目（BD 已把它同步到客户端视图） | ❌ 不额外发包、不加同步 |

即 `affordable = floor(netEmc ÷ unitPrice)`，与服务端 `InterfaceWithdrawService` 用的是**同一个公式、同一份单价**。

### 2.3 必须解决的工程问题：JEI 缺失时的类加载

`TransferHelper` 的**方法签名**就引用了 JEI 类型（`IRecipeSlotsView`、`IRecipeTransferError`）。
若玩家**没装 JEI**，对它施加 Mixin 会在加载类时抛 `NoClassDefFoundError`，**可能直接崩游戏**。

**处置**：把 JEI 相关的 Mixin 拆到**独立的 mixin 配置**，并用 `MixinConfigPlugin` 门控：

```java
// shouldApplyMixin：JEI 未加载时返回 false
ModList.get().isLoaded("jei")
```

并在 `neoforge.mods.toml` 里把 JEI 声明为**可选**依赖（`type="optional"`），
**绝不**做成必需依赖。

### 2.4 风险

| 风险 | 说明 | 处置 |
|---|---|---|
| **R-B1（最大）** | 客户端算的可兑换数量与服务端实际能铸造的数量**不一致** → JEI 说能填、实际填不满（合成栏半空） | 两侧用**同一个公式 + 同一份服务端单价 + 同一份余额**；且服务端的抽取钩子会把不足的部分如实拒绝。需要专门实测 |
| R-B2 | 没装 JEI 时崩游戏 | 独立 mixin 配置 + `MixinConfigPlugin` 门控 + 可选依赖声明 |
| R-B3 | 双重扣费 | 不存在：只有服务端 `extract` 一处扣费 |
| R-B4 | Shift 批量填充（`maxTransfer`）用 EMC 可用量算倍数 → 一次填多组、扣多份 | 行为正确，但要测；必要时在客户端限制倍数 |
| R-B5 | JEI 版本差异导致 `TransferHelper` 签名变动 | 归入现有的 Mixin 目标存活性核查（`MixinTargetCheck`）——**把新目标加进去** |
| R-B6 | 合成终端（`DimensionsCraftMenuTerminal`）与通用转移 | 同一个 `TransferHelper` 漏斗，天然覆盖；但要分别测 |

---

## 3. 实施顺序

| 阶段 | 内容 | 产出 |
|---|---|---|
| **A** | 鼠标吸附拾取 | 协议加意图字段；`giveToCursor`；点击映射；新增自检；`v0.1.0` 回退点不变 |
| **B** | JEI 配方填充 | 独立 mixin 配置 + `MixinConfigPlugin` 门控；`TransferHelper` 池注入；可选依赖声明 |
| **C** | 回归与发布 | 跑全部自检 + 人工清单；重新备份（0.2.0）；更新 CHANGELOG/README/VERSIONS；发 Release |

**每阶段结束必须**：`clean build` + 服务器启动自检全绿 + 备份演练。
**A 与 B 相互独立**，可分别验证、分别回退。

### 3.1 新增自检（拟）

| 自检 | 覆盖 | 能否无头 |
|---|---|---|
| 吸附数量计算（空鼠标/同类/异类/上限/余额不足） | R-A1 的核心不变式 | ✅ 纯计算，可无头 |
| **"扣费数量 == 吸附数量"断言** | R-A1 | ✅ 可无头 |
| JEI 门控生效（JEI 未加载时无 Mixin 报错） | R-B2 | ✅ 服务器启动即可验证 |
| JEI 目标存活性（`TransferHelper.transferRecipe` 签名） | R-B5 | ✅ 并入 `MixinTargetCheck` |
| 客户端/服务端可兑换数量一致 | R-B1 | ❌ 需实机（JEI 装客户端） |

---

## 4. 验收标准

### 4.1 需求 A
- [ ] 左键点击虚拟条目 → 物品**吸附到鼠标**，不直接进背包
- [ ] 右键 → 一半吸附到鼠标
- [ ] Shift+左键 → 直接进背包（保留旧快捷方式）
- [ ] 鼠标上已有同类物品 → 合并到上限，**只扣实际吸附数量**的 EMC
- [ ] 鼠标上是别的物品 → 被拒绝并给出提示，**EMC 不变**
- [ ] 余额不足 → 只吸附买得起的数量，**只扣那部分**
- [ ] **回归**：0.1.0 的背包满不扣费、附魔物品不折算/不铸造、模拟抽取不扣费，全部仍然成立

### 4.2 需求 B
- [ ] 装 JEI + BD + ProjectE + 本模组，打开维度网络的**合成菜单**
- [ ] 对一个材料在**网络里没有库存但已学会**的配方，点 JEI 的"+"填充
      → 材料被填进合成栏，且**网络 EMC 相应减少**（减少量 = 数量 × 购买价）
- [ ] 网络余额不足时 → 只填能付得起的部分（或明确报错），且**不吞钱**
- [ ] **不装 JEI** 启动游戏 → 一切正常，**无 Mixin 报错、无崩溃**
- [ ] 装 JEI 但不用本功能 → 原有配方转移行为不受影响（回归）
- [ ] 合成终端（`DimensionsCraftMenuTerminal`）与通用配方转移同样可用

---

## 5. 需要你拍板的问题

> **状态（2026-09-27）：已按下列推荐默认值实现。** 若与你预期不符，随时可改。
> - **A1** ✅ 采用：左键=一组吸附到鼠标、右键=一半、Shift+左键=直接进背包
> - **A2** ✅ 不增加"只取 1 个"入口（原版语义优先；右键可作近似）
> - **A3** ✅ 鼠标上是别的物品时**拒绝**（不做交换、不丢在地上）
> - **B1** ✅ 余额不足时只填能付得起的部分（服务端语义），客户端用同一公式预判
> - **B2** ✅ JEI 作为**可选**依赖（`type="optional"`，`side="CLIENT"`）

**A1．点击映射**：左键=一组吸附到鼠标、右键=一半、Shift+左键=直接进背包。
　　这套是否符合你的预期？（原版左键就是整组，所以建议这样）

**A2．"只取 1 个"还要不要保留入口？**
　　原版语义里左键是整组。若你想保留单取，可用右键=一半、或另设一个键（如 Ctrl+左键=1 个）。

**A3．鼠标上已有不同物品时**：拒绝（更安全、更符合"虚拟槽没有实体可交换"的事实）
　　还是把兑换出的物品掉在脚下？（0.1.0 的背包满行为就是掉落）

**B1．JEI 填充时余额不足**：只填能付得起的那部分（当前服务端语义，静默），
　　还是在 JEI 界面上明确报错/标红？

**B2．是否要求 JEI 为可选依赖**：我强烈建议**可选**（不装 JEI 也能正常玩）。
　　若你希望"装了 JEI 才有完整功能"，那也保持一致做法。

---

## 6. 不建议做的事（记录理由）

| 项 | 理由 |
|---|---|
| 自己注册一个 JEI `IRecipeTransferHandler` 覆盖 BD 的 | JEI 按容器类型注册，重复注册会与 BD 冲突；而 BD 的漏斗已经覆盖 4 个场景，Mixin 池注入更小、更稳 |
| 给客户端单独发一份"可兑换数量"同步包 | 余额与单价都已在客户端视图 / 知识同步包里，再加一份就是第二份真相（本项目已为此栽过两次） |
| 把 JEI 变成必需依赖 | 会让不玩合成的玩家被迫多装一个模组 |
