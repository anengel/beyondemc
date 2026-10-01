# 0.3.0 计划：让维度网络里的物品真实存在（服务端物化）

> **基线**：`v0.2.0`（附注 tag → 提交 `9aa9f43`）· 工作区 HEAD `d30cbff`
> **回退点**：分支 `backup/v0.2.0` + `backups/beyondemc-0.2.0.{bundle,source.zip}` + `beyondemc-1.21.1-neoforge-0.2.0.jar`（均已在本轮重新演练通过）
> **起点基线**：`backups/beyondemc-0.3-baseline.{bundle,source.zip}`（本轮新建并演练）
> 本文所有关于 Beyond Dimensions / ProjectE / 原版的断言均带 `文件:行号` 证据（项目铁律 §证据驱动）。凡标注 **［待验证］** 的，必须在写代码前按 §11 的 Spike 清单实测后再定稿。
>
> **修订 rev2 / rev3（本轮，已与定稿计划 `plans/quantum-nebula-curie-zs5_Xpk9.md` 对齐）**，共三处实质更正：
> 1. **§2.3 的物化分配策略改写**：由"共享预算 + 不变量 INV-1"改为**每件独立 `floor(EMC ÷ 单价)`**（忠实搬运 0.2 的显示口径，`client/VirtualEntryProvider.java:151`）。原 INV-1 删除，改为 **INV-1′「交付必扣费」**。理由见 §2.3。
> 2. **新增 §5.2「物化条目不得零扣费被抽走」**：本轮核实发现 BD 的 `extract(TagKey,…)` / `extract(slot,…)` 会以 `EmcItemKey` 身份进入抽取钩子，而现有钩子只认 `ItemStackKey`、对其余键放行 ⇒ **零扣费交付**。这是真实刷物品入口，必须封。
> 3. **§3.1 类型契约更正 5 处**：`equals` 的真正基类是 `LongStackKey`（比 `getTypeId()`，不是"比类"）；`codec()` 要用 `ItemInfo.MAP_CODEC`（不是 `CODEC`）；新增 `getModId()` / `getTags()` 语义；`getVanillaMaxStackSize()` 应覆写。
> 勘误登记见 §13。

---

## 0. 需求口径

需求原文：**"让维度网络中的物品真实存在并实际存储于维度网络中"**。

拆成可验收的五条：

| 编号 | 口径 | 可测判据 |
|---|---|---|
| **R0.3-A** | 物品在网络存储中**真实存在** | 网络存储的条目表里能查到该物品条目（服务端持有实体，不是客户端算出来的显示行） |
| **R0.3-B** | **实际持久化** | 存入 → 退出 → 重进 → 换维度 → 重连，条目与数量不变 |
| **R0.3-C** | 可被**正确读取与访问** | 界面 / 命令 / 自动化 / 自检都能按正常路径读到同一条目、同一个数量 |
| **R0.3-D** | 不破坏 0.1/0.2 已确认的产品决策 | EMC 经济（回收价折算 / 购买价兑换）、网络级学习集合口径、兑换吸附语义全部不变 |
| **R0.3-E** | **不刷物品** | 物化数量 = 逐件 `floor(EMC ÷ 单价)`；**任何**交付路径都按 `数量 × 单价` 扣 EMC，不存在零扣费交付（§5.2） |

---

## 1. 现状（0.2）与根因

### 1.1 网络存储里现在到底有什么

| 内容 | 载体 | 是否"真实存在" |
|---|---|---|
| 无 EMC 值的物品（石头/泥土…） | `UnifiedStorage` 的 `ItemStackKey` 条目 | ✅ 真实条目 |
| 被配置排除折算的物品（附魔/耐久/带容器内容物） | 同上 | ✅ 真实条目 |
| **EMC 数值池** | `UnifiedStorage` 的 `EmcStackKey` 条目（自定义资源类型） | ✅ 真实条目 |
| 网络已学习物品集合 | 网络存档 NBT 自定义键 `beyondemc:knowledge` | ✅ 真实持久化（`NetKnowledgeStore` + `DimensionsNetMixin`） |
| **"已学习但无库存"的物品** | **只存在于客户端视图** | ❌ **不真实**——见下 |

### 1.2 根因：这些物品是客户端"现算"出来的显示行

`VirtualEntryProvider.inject()`（`src/main/java/com/zhuyuhang/beyondemc/client/VirtualEntryProvider.java`）在客户端 `ClientNetStorage` 的视图里现场写入数量：

```
affordable = floor(网络EMC ÷ 购买价)     // 逐件独立计算（:151）
view.setAmountByKey(ItemStackKey.of(info.createStack()), affordable)
```

它的类注释写得很直白：**"服务端不认识虚拟条目"**。由此派生三个后果：

1. **不进存档**——重进后由客户端重新算，算不出来就没有（例如 EMC 表未就绪）。
2. **服务端不可见**——点击必须由 `BDBaseGUIMixin.slotClicked` 拦截、发 `ExchangeRequestPacket`、再由 `ExchangeService` 现铸造。原生路径（BD 的 `UnifiedStorage.extract`）完全取不到它们。
3. **多人/多端不一致**——每个客户端各算一份，判据是"`sourceStorage` 里有没有"，属于用本地状态推断服务端事实。

这正是架构文档 **风险 R10「客户端虚拟条目与服务端真实状态不一致（幽灵物品）」**（`docs/design/architecture.md` §7）。

> 结论：0.3 要做的，就是把上表最后一行从"客户端派生"变成"服务端真实持有"；**数量公式不变**（仍是逐件 `floor(EMC ÷ 单价)`，§2.3），变的是它由服务端算、且落进真实存储。

---

## 2. 设计决策（拍板）

### 2.1 决策 D9：物化条目用**本模组自定义资源类型**，不用 BD 原生 `ItemStackKey`

物化条目需要一个"键"来在 `UnifiedStorage` 里标识"哪个物品"。两个候选：

| 方案 | 结论 | 理由 |
|---|---|---|
| **B：直接用 BD 原生 `ItemStackKey`** | ❌ **否决** | BD 的存储是 `key → long`（无序映射）。同一物品的"物化份"与"玩家真实存入的库存"会是**同一个 key**，被合并成一个数量，**无法区分**。于是原生抽取时无法判断该不该扣 EMC；回退到 0.2 后这些条目更会直接变成**免费真实库存**（超发）。 |
| **A：自定义 `EmcItemKey`（推荐）** | ✅ **采用** | 与真实物品库存**不合并**（不同 key 类型） ⇒ 原生抽取路径不会被"已有库存"分支短接；持久化 / 同步 / 网络合并 / 销毁全部由 BD 自动完成；**回退安全**——见 §9.3。 |

> **勘误（E3）**：原文此处曾写"不会出现重复行"。**不成立**——若某物品既有真实库存（未被折算，如附魔/被过滤的）又在学习集合里，界面会出现**两行**（`ItemStackKey` 行 + `EmcItemKey` 行）。0.2 的 `VirtualEntryProvider` 对已有真实库存的物品是 `skipStocked` 跳过（`:137-141`）。因此物化规则**增加"`realStock > 0` 不物化"**，与 0.2 口径一致、同时消除重复行（§2.4 输入定义）。

方案 A 与项目已有的 `EmcStackKey` **完全同构**（`docs/design/decisions.md` ADR 系列已证明该路径可行），不是新机制。

### 2.2 决策 D10：EMC 池仍是唯一权威，物化条目是**可丢弃的纯函数派生**

沿用项目纪律 **§5.5「凡是能从权威数据源现场推导的事实，就不要维护第二份副本」**：

- **权威**：`EmcStackKey` 池 + 网络学习集合。二者是唯一需要保证正确的数据。
- **派生**：物化条目 = `f(EMC, 学习集合, 价格表, 真实库存)`。任何时刻都可**丢弃并重建**，重建结果是确定的。
- 因此物化条目**不参与** EMC 的计算，任一方向都只有一条推导链：`权威 → 派生`，不存在 `派生 → 权威` 的回写。

> 这条是 0.3 的首要安全性质：**物化层彻底损坏也不会丢数据**，最坏情况是"界面看不到物化条目"，重建即可（§9.2）。

### 2.3 决策 D11（已改写）：物化数量 = **逐件独立 `floor(EMC ÷ 单价)`**，不共享预算

**（本节为 rev2 改写，替代原"共享预算 + INV-1"算法）**

```
输入：E  = 网络 EMC (long)
      L  = 学习集合 Set<ItemInfo>
      price(ItemInfo) -> long       // IEMCProxy.getValue（购买价）
      realStock(ItemInfo) -> long   // UnifiedStorage.getStackByKey(new ItemStackKey(canonical)).amount()

对每个 info ∈ L：
    p = price(info)
    若 p <= 0                → 不物化
    若 realStock(info) > 0   → 不物化        // ★ 与 0.2 的 skipStocked 一致，消除重复行（§2.1 勘误 E3）
    n = floor(E / p)                          // ★ 逐件独立，不扣减共享预算
    若 n == 0                → 不物化         // 与 0.2 一致：0 数量条目列表本身也会丢弃
    materialized[info] = n

若条数 > maxMaterializedItems → 按 (price 升序, ItemInfo 稳定序) 取前 N
```

**关键性质**：各物品**彼此独立**，`Σ (count_i × price_i)` **允许**大于 `E`。

- 例：`E=100、钻石单价 50、泥土单价 1` ⇒ **钻石 2、泥土 100**（合计价值 200 > 100）。
- 取出 1 钻（扣 50）⇒ `E=70` ⇒ **钻石 1、泥土 70**。

**为什么这样是对的（不超发的严格论证）**：

设 `D` = 历史累计存入/产生的 EMC，`C` = 历史累计为交付而扣掉的 EMC，当前 `E = D − C`。
任何一次交付 `q` 件（单价 `p`）都扣 `q·p`，交付物价值也恰为 `q·p` ⇒ **累计交付价值 ≡ C ≤ D**。
即：物品总量恒受"曾经存入的 EMC"约束，**与显示的数量之和无关**。显示的合计超 E 只是"每样东西单独看都买得起"的 UI 口径，**不是一张可同时兑现的清单**——兑现每次都要付 EMC，而 EMC 只有那么多。

**不变量（替代原 INV-1）**：

```
INV-1′（收费唯一）：任何一次把物化物品交给玩家或自动化的操作，
                    都必须原子地扣掉「数量 × 单价」的 EMC；不存在"交付但不扣费"的路径。
INV-2 （扣费==交付）：扣的 EMC == 交付物品的原价之和；容量在扣费之前算定。
```

> **勘误（E9）**：原 INV-1（`Σ count × price ≤ EMC`）删除。它与 0.2 的逐件取整口径冲突，而且**不是防刷物品所必需**——防超发靠"交付即收费"，不靠"显示不超额"。其唯一前提 INV-1′ 的落地护栏见 §5.2。
>
> **为什么不再用"预算顺序分配"**：0.2 从来不是共享预算，而是每件独立取整；上一版算法（`E=100, p=[1,80] → 泥土 20 + 钻石 1`）会让便宜物品的数量**比 0.2 少**，属于对既有语义的擅自改动。

### 2.4 被否的其它路线（记录理由）

| 方案 | 否决理由 |
|---|---|
| **D：改变折算语义**（"只折算未学习的物品，已学习的照常入库"，即架构文档 §1.2 的备选） | 与已确认的产品决策**直接冲突**（用户已拍板"无条件折算"，`docs/design/architecture.md` §1.1 / S1 已关闭）。它解决的是另一个问题（让 R6 分支成为常态），不是让现有物品真实化。 |
| **E：物化账本只写进网络 NBT，不进 `UnifiedStorage`** | 不算"存储于维度网络的存储中"，且网络合并 / 生命周期要自己维护。**但保留为降级开关**（见 §9.1 的 `materializeMode=LEDGER`）。其真实成本是**需自实现持久化/同步/合并/销毁**，工作量非零（勘误 E8）。 |
| **F：服务端只算、不落盘，仅把结果下发给客户端** | 治标不治本：仍然不是"真实存在"，且没有解决持久化（R0.3-B）。 |
| **G：让 `getTags()` 返回空，以回避标签抽取路径** | ❌ 否决。会让界面"按标签搜索"找不到物化物品（`ClientNetStorageSearchHelper` 依赖 `key.getTags()`）。采用"改道收费"而非"砍掉能力"（§5.2）。 |

---

## 3. 数据结构（0.3 涉及的全部数据）

| 数据 | 载体 | 位置 | 持久化 | 同步 | 生命周期 |
|---|---|---|---|---|---|
| 网络 EMC 余额（**权威**） | `EmcStackKey`（long） | `UnifiedStorage` 条目 | BD 自动 | BD 自动（delta） | 随网络销毁/合并 |
| 网络学习集合（**权威**） | `Set<ItemInfo>` | `BDNet_<id>.dat` 的 `beyondemc:knowledge` | 我们的 Mixin | `KnowledgeSyncPacket` | 随网络销毁 |
| **物化物品条目（0.3 新增，派生）** | **`EmcItemKey`（`LongStackKey<EmcItemType>`）** | **`UnifiedStorage` 条目（独立 type bucket）** | **BD 自动** | **BD 自动（delta）** | **随网络销毁；可丢弃重建** |
| 物化预算余量（诊断用） | 内存 | 仅运行时 | 无 | 不需要 | 每次物化重算 |

> 物化条目落在**独立的 type bucket**（`bucketOf(key.getTypeId())`）。BD 对外的物品能力桥 `ItemUnifiedStorageHandler` **只读 `ItemStackKey` 分桶**（`api/capability/helper/unordered/ItemUnifiedStorageHandler.java:30-33,39,58,99`）⇒ 第三方模组的管道**看不见**物化条目、不占 `getSlots()`、不会被误抽。

### 3.1 新增类型：`EmcItemKey` / `EmcItemType`（契约已按实读源码更正）

照抄 `EmcStackKey` / `EmcType` 的样板（`src/main/java/com/zhuyuhang/beyondemc/emc/`），差异只有"键要携带是哪个物品"：

| 成员 | 说明 |
|---|---|
| `EmcItemType` | `LongType<EmcItemType>` 子类，字段 `ItemInfo info` + 继承的 `stackCount` |
| `EmcItemKey` 基类 | `LongStackKey<EmcItemType>`（**不是** `LongType`；`LongType` 只是 `EmcItemType` 的父类） |
| **`EmcItemKey.equals/hashCode`** | ⚠️ **必须覆写为"比较 `ItemInfo`"**：`o instanceof EmcItemKey k && info.equals(k.info)`；`hashCode = 31 + info.hashCode()`。**基类 `LongStackKey.equals` 比的是 `getTypeId()`**（`LongStackKey.java:98-115`，已用 `javap -c` 字节码复核），因此不覆写会让**所有物品塌缩成同一个键**；而存储是 `Map<IStackKey,Long>`（`AbstractUnorderedStackHandler.java:53-71`），钻石与铁的物化条目会**合并成一条数**。 |
| `EmcItemKey.codec()` | 必须是**有字段**的 `MapCodec`：`ItemInfo.MAP_CODEC.xmap(EmcItemKey::new, EmcItemKey::info)`。**不能**像 `EmcStackKey` 那样写空编码；**也不能**用 `ItemInfo.CODEC`（`Codec` 不能放在 `dispatch` 的 MapCodec 位）。 |
| `EmcItemKey.serialize/deserialize`（网络） | `ItemInfo.STREAM_CODEC`；数量在 `KeyAmount` 里 |
| `EmcItemKey.serializeNBT/deserializeNBT` | 写 `ItemInfo`（`Codec.encodeStart` + `NbtOps`）；读取失败返回 `null`（交给 BD 的 `catch(Throwable)` 容错路径） |
| `EmcItemKey.getTypeID()` | `beyondemc:stack_type/emc_item`（大写 D，`IStackKey.getTypeId()` 由它转调） |
| **`EmcItemKey.getModId()`** | **返回物品自身命名空间**：`info.getItem().getKey().getNamespace()`。`SORT_MODID` 与"模组搜索"直接用这个值（`ClientNetStorage.java:190-300`）；返回 `beyondemc` 会让所有物化条目挤成一个模组。 |
| **`EmcItemKey.getTags()` / `hasTag(tag)`** | 委托物品自身 tag（标签搜索依赖它）。⚠️ **代价**：存储会把 `key.getTags()` 登记进 `tag2stackMap`（`AbstractUnorderedStackHandler.java:736-738`），使物化条目可被 `extract(TagKey,…)` 命中 ⇒ **必须同步落地 §5.2 的抽取护栏**。 |
| `EmcItemKey.getRender()` | 返回自定义 `IStackRender`：画物品图标 + 数量（参照 `EmcStackKeyRender`）。**身份只能从传入的 `key` 取**，绝不能读 `getRenderStack()`——它返回原型栈（`LongStackKey.java:26-38`）。 |
| `EmcItemKey.getDisplayName()`（在渲染器上） | **必须返回物品名**：`SORT_NAME` 与文本搜索都依赖 `getRender().getDisplayName()`。 |
| **`EmcItemKey.getVanillaMaxStackSize()`** | **改为建议覆写**为物品自身最大堆叠数（与 `ItemStackKey` 对齐）。`EmcStackKey` "不覆写（保持 `Long.MAX_VALUE`）"的理由是避免 EMC 池被 100 万截断（架构文档 R6），**不适用于物品型键**。［待验证］列 S-0.3-3。 |

### 3.2 注册（时序是硬约束）

`StackKeyRegistry.registerType(EmcItemKey.INSTANCE)` 必须在 `FMLCommonSetupEvent`，且**早于任何网络反序列化**——否则读档时找不到类型，BD 会 `catch(Throwable)` 静默丢弃条目（`AbstractUnorderedStackHandler.java:889-898`）。与 `EmcKeyRegistration` 合并成一处理即可。

---

## 4. 存储机制

### 4.1 存储位置与写入方式

- 位置：与 EMC 池**同一个** `UnifiedStorage`（`net.getUnifiedStorage()`），只是 key 类型不同。
- 写入：`UnifiedStorage.insert(EmcItemKey, n, simulate)`；删除：`extract(EmcItemKey, n, false, false)`——**必须复用 `NetEmcAccessor` 的语义包装**（BD 的 `insert` 返回"剩余未插入"，`extract` 返回"实际提取"，二者相反，`NetEmcAccessor` 已统一）。
- **这两类写回必须包在 `MaterializingGuard` 内**（§5.2-2）：`refresh` 对 `EmcItemKey` 的 insert/extract 属于"内部维护"，需与外部抽取区分，否则会被抽取护栏当成外部抽取而改道/拦截（自锁）。
- 由 BD 自动完成：写进 `BDNet_<id>.dat`、向打开的界面广播 delta、网络合并时搬运、网络销毁时随文件消失。**我们不写任何持久化代码**。

### 4.2 物化触发点（5 个，缺一不可）

| 触发 | 挂在哪 | 为什么需要 |
|---|---|---|
| ① EMC 增加（折算/加余额） | `EmcDepositHandler.beforeInsert` 返回前（`emc/EmcDepositHandler.java:152`） | 新 EMC 应立刻可兑换 |
| ② EMC 减少（兑换 / 接口抽取 / 命令） | `ExchangeService` 扣费后（`exchange/ExchangeService.java:175-182`、`:265-336`）、`InterfaceWithdrawService` 扣费后（`:206-227`） | 余额变小，物化数量必须收缩 |
| ③ 学习集合新增 | `NetKnowledgeStore.learn` 返回 true 处（`knowledge/NetKnowledgeStore.java:72-79`） | 新物品才有条目 |
| ④ 价格重映射 **＋** ⑤ 读档后修复 | `EmcAvailability.onRemap`（`core/EmcAvailability.java:38-45`，监听的正是 `EMCRemapEvent`） | `/reload` 后单价变化必须重算；**读档后的修复也只能挂这里** |
| ~~⑤ 读档完成后~~ | ~~`DimensionsNetMixin.load` RETURN~~ | ❌ **不可行**，见下 |

> **勘误（E4）**：读档后**不能**挂在 `DimensionsNet.load` 的 RETURN。阶段 1 实测结论：EMC 表要到 `EMCRemapEvent` 才就绪（专用服务器无玩家时，`ServerStartedEvent` 时刻钻石 EMC 仍为 0；需玩家登录或 `/reload`）。因此 ④⑤ 合并为"挂 `EmcAvailability.onRemap`，遍历全部网络各 `refresh` 一次"。

> **统一收口**：不要在各处各写一遍。新增 `ItemMaterializer.refresh(net)`（幂等、可重入），上面各触发点只调用它。这是 0.2 踩过两次"用可变状态描述可现场推导的事实 → 失同步"的教训（`VirtualEntryProvider` 类注释）的直接对策。
>
> **递归防护**：`refresh` 写存储会触发 `UnifiedStorage.onChange` ⇒ `net.setDirty()` + 广播 delta。**绝不可**把 `refresh` 挂在"存储变化事件"上（会自激）。

### 4.3 性能与规模

物化条目数 ≤ "有价格 ∧ 买得起 ∧ 无真实库存"的已学习物品种类数。两道闸：

- 只物化 `n > 0` 的物品（买不起的不物化）——与 0.2 显示口径一致。
- 配置 `maxMaterializedItems`（默认 512），超出按价格升序裁剪。理由：BD 界面列表有 `getLines()*9` 硬上限（`DimensionsNetMenu.java:241,257`，架构文档 R7），且条目数会放大同步包体积。

注意：`E` 很大时便宜物品的物化数量可以很大（`E=10^6`、单价 1 → 100 万）。这与 0.2 一致，且 `UnifiedStorage` 用 `long` 存数量、无溢出问题；但会放大同步包。

**［待验证］** 物化 512 条时 BD 的同步包与界面响应是否可接受（§11 Spike S-0.3-2）。

---

## 5. 生成流程：物品如何被"真实生成"

**兑换一个物品时的完整链路（0.3 之后）：**

```
① 服务端 ItemMaterializer.refresh(net)
     EMC 池 ──(纯函数, §2.3)──> Map<ItemInfo,Long>
     └─ 对每个 (info, n)：insert(EmcItemKey.of(info), n, false)      [MaterializingGuard 内]
     └─ 对已存在的旧量：先差量调整，不整表重建（避免刷屏 delta）

② BD 自动：写入网络存档 + 向所有打开该网络的玩家广播 delta
     ⇒ 物品此刻"真实存在于维度网络中"（R0.3-A / R0.3-B）

③ 界面：BD 原生列表按 `EmcItemKey` 渲染出真实行（我们的 IStackRender 画物品图标与数量）
```

**玩家兑换时的链路：**

```
客户端：BDBaseGUIMixin.slotClicked 命中"key instanceof EmcItemKey 的槽位"
        → cancel 原逻辑 → 发 ExchangeRequestPacket(netId, itemInfo, count, intent)

服务端：ExchangeService（唯一权威）
  1. net = getNetFromPlayer(player)；判成员权限（沿用 R11 已定口径）
  2. 该物品必须在网络学习集合内（exchangeRequiresKnowledge）
  3. buy = getValue(info)；余额校验 emc >= buy × count（饱和运算）
  4. spendEmc(buy × count)                ── 扣 EMC（唯一权威动作）
  5. 发放（吸附到鼠标 / 进背包，沿用 0.2 语义）
  6. 不变式 INV-2：扣费数量 == 交付数量，容量在扣费之前算定
  7. 再次 ItemMaterializer.refresh(net)   ── EMC 变小，全部条目数量一起收缩
```

> **勘误（E7）**：原文此处写"【原子】`extract(EmcItemKey, count)` + `spendEmc`"。**已删除该 extract 步**——物化条目是纯派生，扣费后 `refresh` 全量重解即得正确数量；`extract(EmcItemKey)` 既冗余，又会把 `EmcItemKey` 拖进抽取钩子链路。INV-2 的语义因此回归本义：它约束的是「扣掉的 EMC」与「交付物品」之间，**不是**条目增量。
>
> **行为说明**（写进文档，避免被误判为 bug）：取出任何物品都会使 `E` 下降，因而**所有**物化条目的数量一起下降（如取出 50 泥土后钻石从 2 变 1）。这是"数量 = `floor(E ÷ 单价)`"的确定结果，不是失同步。

### 5.1 三条硬约束

1. **物化写入不能触发折算钩子**。`insert(EmcItemKey, n)` 会经过 `UnifiedStorageBeforeInsertHandler`；但我们的 `EmcDepositHandler` 第 5 步只处理 `ItemStackKey`（`if (!(tryInsert.key() instanceof ItemStackKey itemKey)) return pass(...)`，`emc/EmcDepositHandler.java:85-88`），`EmcItemKey` 天然不满足 → 直接放行。**仍需在自检里断言这一点**，因为这是"物化自我抵消"的唯一边界。
2. **物化不得在 `LoadingGuard.isLoading()` 期间执行**（读档期只做 §4.2 ④⑤ 的"读档后修复"）。
3. **物化必须幂等**：重复 `refresh` 的结果必须相同（自检断言）。

### 5.2 ⚠️ 物化条目「零扣费」被抽走 —— 必修缺口与护栏

**机制**（本轮实读源码确认）：

- BD 的 `UnifiedStorage` 是抽取的**完整收口**：
  - `extract(int slot,…)`（`api/dimensionnet/UnifiedStorage.java:105-115`）→ 取 `getStackBySlot(slot)` → 转 `extract(IStackKey,…)`
  - `extract(TagKey,…)`（`:136-143`）→ 从 `tag2stackMap` 取一个键 → 转 `extract(IStackKey,…)`
  - `extract(IStackKey,…)`（`:118-133`）→ **跑钩子** `UnifiedStorageBeforeExtractHandler.onBeforeExtract`
- 但现有钩子 `InterfaceWithdrawService.beforeExtract:117-119` **只认 `ItemStackKey`，对其它键一律 `pass` 放行**。
- 而存储维护 `Multimap<TagKey<?>, IStackKey<?>> tag2stackMap`，在 `ensureInIndex` 里用 `key.getTags().forEach(tag -> tag2stackMap.put(tag,key))` 登记（`AbstractUnorderedStackHandler.java:58,736-738,760-762`）。

⇒ 物化条目按 §3.1 实现"`getTags()` 委托物品 tag"后，会以 `EmcItemKey` 身份被 `extract(TagKey,…)` 或 `extract(slot,…)` 命中 → 钩子 `pass` → `super.extract` → **零扣费交付实体物品**。若不封堵，§2.3 的"不刷物品"结论不成立。

**护栏（3 条，必须实现）**：

1. **钩子对 `EmcItemKey` 不放行，改为"改道收费"**：在 `beforeExtract` 顶部把键归一——
   ```
   IStackKey<?> raw = tryExtract.key();
   ItemStackKey itemKey;
   if (raw instanceof ItemStackKey k)      itemKey = k;
   else if (raw instanceof EmcItemKey ek)  itemKey = new ItemStackKey(ek.info().createStack());  // 归一
   else                                    return pass(tryExtract);                               // 流体/能量/EMC 仍放行
   ```
   之后**完整复用现有收费铸造链路**（学习/价格/余额校验 → `spendEmc` → `MintingGuard` 内 `insert` → 返回 `KeyAmount(ItemStackKey, 已扣费数量)`）。
   **用户拍板**：物品**有 EMC 价值就扣 EMC**；**余额不足（`floor(E ÷ 单价) == 0`）时一律 `cancel` —— 什么也抽不出来，绝不交付半份**（复用现有 `want <= 0 → cancel`，`InterfaceWithdrawService.java:186-191`）。`price <= 0` 的物化键在正常数据里不存在；万一出现也按 `cancel` 处理，**不沿用**现有 `unitPrice<=0 → pass` 的写法，避免留下放行口。
2. **引入 `MaterializingGuard`（仿 `MintingGuard`）**：`refresh` 对 `EmcItemKey` 的写回/清空要能**放行**抽取钩子，否则 `refresh` 无法收缩自己的条目（自锁）。钩子规则：`EmcItemKey` 仅在 `MaterializingGuard.isActive()` 时 `pass`，否则一律改道收费。
3. **模拟抽取仍不扣费**：`ExtractContext.isSimulate()` 的提前返回必须排在改道**之前**，保持 0.2 红线（外部模组的能力查询会大量走这里）。

**统一后的键规则**：

| 操作 | `EmcItemKey` 的处理 |
|---|---|
| insert | `EmcDepositHandler` 只认 `ItemStackKey` ⇒ 天然放行；`refresh` 写入无碍（§5.1-1） |
| extract | **仅当 `MaterializingGuard` 激活时放行**（refresh 自用）；否则改道到收费铸造路径 |

---

## 6. 读取与访问

| 访问方 | 路径 | 0.3 后的变化 |
|---|---|---|
| 维度网络界面 | BD 原生列表（`DimensionsNetMenu.buildIndexList`） | **不再需要客户端注入**——条目已在存储里。`DimensionsNetMenuMixin` 的注入降级为兼容开关 |
| 点击兑换 | `BDBaseGUIMixin.slotClicked` 拦截 | 判据从"`sourceStorage` 里没有"（推断）改为**"key 是 `EmcItemKey`"**（直接判定，无状态） |
| 名称搜索 / `SORT_NAME` | `key.getRender().getDisplayName()` | 渲染器必须返回物品名（§3.1） |
| 模组搜索 / `SORT_MODID` | `key.getModId()` | 必须返回物品自身命名空间（§3.1） |
| 标签搜索 | `key.getTags()` | 委托物品 tag（§3.1）；代价与护栏见 §5.2 |
| **按物品注册名搜索** | `SearchHelper.getItemId` | **已知限制**：对非 `ItemStackKey` 返回 `""`，物化条目搜不到。不做 hack（S-0.3-6） |
| 命令 | 新增 `/beyondemc materialize list \| rebuild \| clear` | 新增 |
| 自动化（网络接口，按物品过滤器） | `extract(ItemStackKey, …)` + 钩子铸造路径 | **行为不变**，只是抽取后多一次 `refresh` |
| **自动化（按标签 / 按槽位）** | `extract(TagKey,…)` / `extract(slot,…)` | ⚠️ **原本会零扣费命中物化条目**，必须按 §5.2 改道收费 |
| 其它模组 | BD 的存储抽象 | 物品能力桥只读 `ItemStackKey` 分桶 ⇒ **看不到**物化条目、不占 `getSlots()`、不会被误抽（安全） |
| 自检 | `/beyondemc selftest` | 新增 §10.2 的断言 |

> **读取正确性的唯一口径**：任何访问方读到的数量，都必须等于"服务端按 §2.3 从 EMC 现场算出的数量"。任何缓存/副本都不得作为判据来源。

---

## 7. 与现有维度网络系统的集成方式

### 7.1 保留不动（0.1/0.2 已建立、且仍是权威链路）

| 组件 | 文件 | 角色 |
|---|---|---|
| `EmcDepositHandler` | `emc/EmcDepositHandler.java` | 折算钩子（官方 API，唯一收口） |
| `EmcStackKey` / `EmcType` / `NetEmcAccessor` | `emc/` | EMC 权威池 |
| `NetKnowledgeStore` + `DimensionsNetMixin` | `knowledge/`、`mixin/` | 学习集合的持久化 |
| `ExchangeService` / `ExchangeRequestPacket` / `ExchangeIntent` | `exchange/` | 兑换的唯一权威 |
| `UnifiedStorageExtractMixin` / `MintingGuard` / `LoadingGuard` | `mixin/`、`core/` | 抽取标志记录 / 铸造保护 / 读档保护 |
| `EmcAvailability`（`EMCRemapEvent`） | `core/` | 就绪判据（同时是物化触发点 ④⑤） |
| `KnowledgeSyncPacket` / `ClientKnowledgeCache` | `exchange/`、`client/` | 学习集合同步（单价仍由此下发） |

### 7.2 新增

| 组件 | 角色 |
|---|---|
| `emc/EmcItemKey`、`emc/EmcItemType`、`emc/EmcItemKeyRender` | 物化条目的资源类型 |
| `emc/EmcRegistration`（扩展） | 注册 `EmcItemKey` |
| `materialize/ItemMaterializer` | §4.2 的统一收口，幂等 |
| `materialize/MaterializeMath`（纯函数） | §2.3 的逐件取整算法，**必须可无头单测** |
| `materialize/MaterializingGuard`（仿 `MintingGuard`） | 让 `refresh` 自用写回能放行抽取护栏（§5.2-2） |
| `command/BeyondEmcCommands`（扩展） | `/beyondemc materialize …` |
| `config/BeyondEmcConfig`（扩展） | `materializeItems`、`materializeMode`、`maxMaterializedItems` |

### 7.3 改造 / 降级

| 组件 | 改造 |
|---|---|
| `BDBaseGUIMixin` | 点击判据改为 `key instanceof EmcItemKey`；`VirtualEntryProvider.isVirtual` 退役 |
| `InterfaceWithdrawService` | ① `key instanceof EmcItemKey` 时**不得 `pass`**，改道到同一条收费铸造路径（§5.2-1）；② 铸造扣费成功后调 `refresh`（触发点 ②） |
| `DimensionsNetMixin` | 新增 `mergeOtherNet` 的物化条目清理（与已有 `beyondemc$mergeKnowledge` 同注入点） |
| `VirtualEntryProvider` + `DimensionsNetMenuMixin` | 保留为 `materializeItems=false` 时的兜底（回到 0.2 行为），默认不启用 |

**接口契约层面的新增锚点**（写代码前用 `javap` 核对 `libs/beyonddimensions-1.21.1-0.7.30.jar`，见 §11）：

| 需要 | 目标 |
|---|---|
| 注册类型 | `StackKeyRegistry.registerType`（`api/storage/key/StackKeyRegistry.java`） |
| 自定义键基类 | `LongStackKey` / `IStackKey` / `IStackRender`（`api/storage/key/`） |
| 读写条目 | `UnifiedStorage.insert/extract/getStackByKey`（`api/dimensionnet/UnifiedStorage.java:87/118`） |
| 抽取收口 | `UnifiedStorageBeforeExtractHandler`（`api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java`） |
| 折算钩子的 key 类型 | `com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey` |

---

## 8. 存档迁移与兼容（0.2 → 0.3）

| 场景 | 行为 | 是否有数据风险 |
|---|---|---|
| 0.2 存档 → 0.3 首次加载 | 存档里没有 `EmcItemKey` 条目；EMC 池与学习集合照常读回；首个触发点（`EMCRemapEvent`，§4.2-④⑤）生成物化条目 | 无。物化是纯派生 |
| 0.3 存档 → 0.3 重载 | 条目照常读回；随后 ④⑤ 重算一次并**修复**任何不一致 | 无 |
| 0.3 存档 → **回退到 0.2** | `EmcItemKey` 类型未注册 → BD `catch(Throwable)` **静默丢弃**这些条目；EMC 池与学习集合照常 | **无**——见 §9.3 |
| 网络合并 | 物化条目由 BD 搬运是**有害**的（两个网络的 EMC 合并后数量要重算）。在网络合并路径上先 `clear` 源网络的物化条目，合并后按新 EMC 重建 | 需要在 `mergeOtherNet` 注入点补一步 |

> **注意**：合并这一条是新建的注入点，须按项目纪律用 `require = 1`（静默失效 = 物化条目被错误搬运，属于数据正确性问题，不是功能退化）。

---

## 9. 回退与失败保护策略

### 9.1 三层熔断（由快到慢）

| 层 | 手段 | 触发条件 | 效果 |
|---|---|---|---|
| **L1 配置熔断** | `materializeItems=false`（服务端配置，**热改生效**） | 线上发现物化行为异常 | 下次 `refresh` **清空全部 `EmcItemKey` 条目**；`VirtualEntryProvider` 兜底注入恢复 → **等价于 0.2 行为**。⚠️ 必须**真清空**，只"忽略"不够——条目是**真实存储条目**，BD 原生列表照样渲染它们（勘误 E1） |
| **L2 模式降级** | `materializeMode`：`STORAGE`（默认，写进 `UnifiedStorage`） / `LEDGER`（只写网络 NBT） / `OFF` | L1 不够或需要并存验证 | `LEDGER` 完全不碰 `UnifiedStorage`，把风险面缩到最小；**代价**：需自实现持久化/同步/合并/销毁，工作量非零（勘误 E8） |
| **L3 版本回退** | §9.4 的三个场景 | 功能不可用时 | 回到 0.2 |

### 9.2 数据安全性质（设计保证，非流程保证）

1. **物化条目 = 可丢弃派生**（D10）。任何损坏都可用 `/beyondemc materialize rebuild` 从权威重建。**0.3 不引入任何"只有物化条目里才有"的数据。**
2. **物化失败不影响主链路**。`ItemMaterializer.refresh` 全程 `try/catch`，异常只记日志；折算、兑换、接口抽取全部照常。
3. **不做"先扣钱再交付"**。沿用 0.2 的核心不变式 **INV-2：扣费数量 == 交付数量**，容量在扣费**之前**算定。
4. **所有价格/余额/权限/容量一律服务端重算**，客户端只发意图。
5. **物化条目不能被"零扣费"抽走**（INV-1′）：抽取钩子对 `EmcItemKey` 一律改道收费路径（§5.2-1）；`MaterializingGuard` 只豁免 `refresh` 自身的清理（§5.2-2）。自检断言见 §10.2。

### 9.3 为什么"回退到 0.2"是安全的（选自定义类型的核心收益）

物化条目用的是**本模组自定义的键类型**。回退到 0.2（或直接卸载本模组）时该类型未注册，`StackKeyRegistry.getType(id)` 返回 `null` → `IStackKey.CODEC` 的 `dispatch` 抛异常 → `AbstractUnorderedStackHandler.deserializeNBT` 的 `catch(Throwable)` 吞掉（`AbstractUnorderedStackHandler.java:889-898`）⇒ **条目级静默丢弃**。

- **不会**变成"免费的真实库存"——类型不匹配 `ItemStackKey`，物品能力桥的分桶也拿不到它（`ItemUnifiedStorageHandler.java:30-33`）。
- **不会**损坏存档——丢弃是条目级的，其余数据（EMC 池、学习集合、真实库存）完好。
- 代价：回退后 EMC 池本身也会被丢弃（0.2 的已知行为 R9，README 已警示"卸载前先清空 EMC"）。这是 0.2 既有性质，不是 0.3 引入的。

### 9.4 回退操作（三个场景）

**场景 A：只回游戏版本（最快）**
```powershell
# 删掉 0.3 的 jar，把 0.2 的 jar 与前置一起放回 mods/
Remove-Item "C:\mc\versions\1.21.1-NeoForge_21.1.249\mods\beyondemc-1.21.1-neoforge-0.3.0.jar"
Copy-Item "C:\mc\mcwj\mod-learn\backups\beyondemc-1.21.1-neoforge-0.2.0.jar" "C:\mc\versions\1.21.1-NeoForge_21.1.249\mods\"
Copy-Item "C:\mc\mcwj\mod-learn\backups\deps\*.jar" "C:\mc\versions\1.21.1-NeoForge_21.1.249\mods\"
```

**场景 B：源码回退（保留 0.3 的工作）**
```powershell
cd C:\mc\mcwj\mod-learn
git switch -c try/0.3.0            # 先把 0.3 的成果放到分支上，别丢
git switch main
git reset --hard v0.2.0            # 或用分支：git checkout backup/v0.2.0
```

**场景 C：仓库损坏，从备份重建**
```powershell
cd C:\mc\mcwj
git clone mod-learn\backups\beyondemc-0.2.0.bundle beyondemc-restored
cd beyondemc-restored
New-Item -ItemType Directory -Force libs | Out-Null
Copy-Item ..\mod-learn\backups\deps\*.jar libs\
tools\gradlew-here.cmd build
```

### 9.4b 0.3.0 之后的回退（更常用）

0.3.0 已发版，因此以后最常见的回退是「**0.3.x 改崩了 → 退回 0.3.0**」，而不是退回 0.2：

```powershell
cd C:\mc\mcwj\mod-learn
git switch -c try/<改崩的分支>      # 先保住现场
git switch main
git reset --hard v0.3.0            # 源码回到 0.3.0
tools\gradlew-here.cmd build       # 重新出 jar
# 再把 backups\beyondemc-1.21.1-neoforge-0.3.0.jar 放回 mods\ 替换掉坏的那份
```
> `v0.3.0` 是**附注 tag**，`git switch --detach v0.3.0` 也能直接落到发布点，不会丢东西。
> 若连仓库都没了：`git clone backups\beyondemc-0.3.0.bundle beyondemc-restored`（已真克隆演练通过）。

### 9.5 本轮已落实的备份与版本管理（可核验）

| 项 | 状态 | 证据 |
|---|---|---|
| tag `v0.2.0`（附注 tag → `9aa9f43`） | ✅ 存在 | `git rev-parse 'v0.2.0^{commit}'` |
| tag `v0.1.0`（→ `bf897a6`） | ✅ 存在 | 同上 |
| 分支 `backup/v0.1.0` | ✅ 原有 | `git branch -a` |
| 分支 `backup/v0.2.0` | ✅ **本轮新建**（指向 `9aa9f43`） | `git branch -a` |
| `backups/beyondemc-0.2.0.bundle` | ✅ 复核可用 | 演练：HEAD `9aa9f43`、14 提交、`v0.1.0`+`v0.2.0` 均在、92 文件、工作树干净 |
| `backups/beyondemc-0.2.0-source.zip` | ✅ 校验和与 `backups/README.md` 记录一致 | SHA-256 `4fa9385f…` |
| `backups/beyondemc-1.21.1-neoforge-0.2.0.jar` | ✅ 校验和一致 | SHA-256 `71c4f3ba…` |
| `backups/deps/`（两个前置 jar，不在 git 里） | ✅ 就位 | `beyonddimensions-1.21.1-0.7.30.jar`、`projecte-1.21.1-1.1.0.jar` |
| `backups/beyondemc-0.3-baseline.bundle` | ✅ **本轮新建** | 演练：HEAD `d30cbff`、15 提交、93 文件、工作树干净 |
| `backups/beyondemc-0.3-baseline-source.zip` | ✅ **本轮新建** | SHA-256 `72e2b7a7…` |
| tag `v0.3.0`（附注 tag → `e1d0958`） | ✅ **本轮新建** | `git rev-parse 'v0.3.0^{commit}'` = `e1d0958` |
| `backups/beyondemc-0.3.0.bundle` | ✅ **本轮新建**（每次提交后**重跑演练**） | 演练（临时克隆自该 bundle）：3 个 tag 全在、`v0.3.0` → `e1d0958`、102 跟踪文件、工作树干净；跑完删掉克隆目录 |
| `backups/beyondemc-0.3.0-source.zip` | ✅ **本轮新建** | SHA-256 `15F78EED…` |
| `backups/beyondemc-1.21.1-neoforge-0.3.0.jar` | ✅ **本轮新建** | SHA-256 `C235AC96…` |
| `backups/本地开发环境参考手册.md` | ✅ **本轮新建** | 手工复制（该文件未跟踪，bundle/archive 拿不到）；SHA-256 `d476b6e4…` |

> **纪律**：每个阶段结束、以及每次做有风险的改动之前，重新跑一遍备份并**真克隆一次**（`docs/plan/ROADMAP.md` §6 与项目根 `MC-MOD-GUIDE.md` §6）。没演练过的备份不算备份。
> **`本地开发环境参考手册.md` 的处理结论**：它含本机私有路径（含 `C:\Users\朱雨杭\.ssh\id_ed25519`，并注明"无密码短语"），而仓库将来要推到 GitHub ⇒ **决定不纳入 git**，改为在 `backups/` 里手工存一份磁盘兜底（已做，校验和一致）。因此它是唯一「有备份、但不在任何 bundle 里」的文件。

### 9.6 逐阶段的失败保护动作

| 阶段 | 动作 |
|---|---|
| 开工前 | ✅ 本轮的 bundle + 分支 + 演练（已完成） |
| 每阶段结束 | `clean build` → `runServer` 自检全绿 → 重新 bundle + 演练 |
| 阶段 C 之前 | 提前准备一版"L1 熔断可用"的 jar，作为线上热回退件放 `backups/` |
| 发布前 | `docs/plan/RELEASE.md` 的清单 + `v0.3.0` 附注 tag 并核对指向 HEAD |

---

## 10. 实施阶段与验收标准

### 10.1 阶段划分

| 阶段 | 内容 | 产出 |
|---|---|---|
| **A** | `EmcItemKey`/`EmcItemType`/渲染器/注册；`MaterializeMath` 纯函数 + 单测 | 类型可用、可持久化（先用命令手动插一条验证） |
| **B** | `ItemMaterializer` + `MaterializingGuard` + 各触发点；命令 `/beyondemc materialize …` | EMC 变化时条目自动真实生成、持久化 |
| **C** | 改造点击判据；**`InterfaceWithdrawService` 的 `EmcItemKey` 改道（§5.2）**；`ExchangeService` 接 `refresh`；`mergeOtherNet` 清理；`VirtualEntryProvider` 降级为兜底 | 兑换与接口抽取闭环；INV-1′/INV-2 全绿 |
| **D** | 回归与发布：`clean build` + 全部自检 + 人工清单 + 重新备份 | 发 `v0.3.0` |

**A/B/C 可分别验证、分别回退**（与 0.2 的阶段设计一致）。

### 10.2 新增自检（可无头跑的进 A/B/C 阶段）

| 自检 | 覆盖 | 能否无头 |
|---|---|---|
| `MaterializeMath` 逐件取整：单物品 / 多物品 / 无价格 / 溢出 / **样例 `E=100,p=[50,1] → 钻石2 + 泥土100`**；`E=70 → 钻石1 + 泥土70`（不共享预算） | §2.3 | ✅ 纯函数 |
| 物化幂等性（连跑两次结果相同） | §5.1-③ | ✅ |
| `EmcItemKey.equals/hashCode` 区分不同物品（钻石 ≠ 铁） | §3.1 的塌缩风险（**最关键**） | ✅ |
| **`EmcItemKey` 条目不能被零扣费抽走**：直接 `extract(EmcItemKey,…)` ⇒ EMC 减少、条目下降；**EMC 不足 ⇒ `cancel`（0 交付）**；仅 `MaterializingGuard` 激活时才放行 | **INV-1′（最关键）** | ✅ |
| `EmcItemKey` 的 NBT / 网络往返 | 持久化与同步 | ✅ |
| 折算钩子对 `EmcItemKey` 放行 | §5.1-①（防"物化自我抵消"） | ✅ |
| `refresh` 在 `LoadingGuard.isLoading()` / `EmcAvailability.isReady()==false` 时不动存储 | §4.2 | ✅ |
| `materializeItems=false` ⇒ 全部 `EmcItemKey` 条目被清空 | L1 熔断（§9.1） | ✅ |
| 读档后物化与 EMC 一致 | §8 | ✅ |
| INV-2「扣费数量 == 交付数量」 | 0.2 已有，须**回归** | ✅ |
| 物化数目 ≤ `maxMaterializedItems` | §4.3 | ✅ |

### 10.3 验收标准

- [ ] 存入一组钻石 → 网络存储里出现该物品的**真实条目**（不是只有 EMC 行）
- [ ] 退出重进 / 换维度 / 重连 → 条目与数量不变（R0.3-B）
- [ ] 界面里该物品是**真实行**；名称/模组/标签搜索与排序正常；`/beyondemc materialize list` 读到同一个数量（R0.3-C）
- [ ] 物化数量 == 逐件 `floor(EMC ÷ 单价)`；买不起（数量 0）的物品不出现（§2.3）
- [ ] 点击兑换 → EMC 减少、物品到手、`refresh` 后全部条目数量一起收缩；INV-2 全绿
- [ ] **直接抽取物化条目（按标签 / 按槽位）会扣 EMC**，不存在零扣费交付（INV-1′）；**EMC 不足时不交付任何数量（cancel，不交半份）**
- [ ] 有过滤器的 BD 网络接口能把物化物品抽走并扣 EMC；模拟抽取**不扣费**
- [ ] 第三方管道**看不到**物化条目（`getSlots()` 不含它们）
- [ ] `materializeItems=false` 后 → 行为与 0.2 完全一致（**条目清空** + 兜底注入）
- [ ] **回退演练**：把存档放到 0.2 + 0.2 的 jar 启动 → 不崩、不超发、EMC 与学习集合照常
- [ ] 0.2 存档直接进 0.3 → 不崩，首个触发点后条目正确生成

---

## 11. 风险与待验证（Spike）

| ID | 项 | 严重度 | 处置 |
|---|---|---|---|
| S-0.3-1 | `LongStackKey` / `IStackKey` 携带 `ItemInfo` 时，`codec()` 与 `equals` 的正确写法 | ✅ **已解除** | 已用实读源码 + `javap -p -c` 核实：`equals` 基类比 `getTypeId()`、`codec()` 用 `ItemInfo.MAP_CODEC`、网络用 `ItemInfo.STREAM_CODEC`（§3.1）。剩余动作：阶段 A 先写最小实现跑通 NBT/网络往返 |
| **S-0.3-7** | `EmcItemKey` 抽取改道是否覆盖 BD **全部**抽取入口（slot / tag / key），以及 `MaterializingGuard` 是否会造成 refresh 与外部抽取的重入死角 | ✅ **已解除** | 静态：`UnifiedStorage.extract(int slot,…)`（`:110-114`）与 `extract(TagKey,…)`（`:136-142`）都委托到 `extract(IStackKey,…)`，钩子就在 `:122` ⇒ 三条入口同源。运行期：`MaterializeSelfTest` 新增第 9/10 组（4 项）——按槽位 / 按标签各做「外部必被拒」+「`MaterializingGuard` 激活必放行」正控，实测全绿（物化组 22 → **26 项**）。`MaterializingGuard` 为重入无死角：深度计数，见 `loadingGuardBlocks` 与正控用例 |
| S-0.3-2 | 物化 512 条时的同步包体积与界面响应 | 🟠 高 | 实机压测；必要时下调 `maxMaterializedItems` 或改分页 |
| S-0.3-4 | 网络合并时物化条目的清理时机（`mergeOtherNet` HEAD 是否早于 EMC 合并完成） | 🟠 高 | 双网络合并实测（沿用 S9 的方法） |
| S-0.3-5 | `EMCRemapEvent` 时能否枚举到全部已加载网络（BD `NetRegistryIndex` 的可用时机） | 🟠 高 | 阶段 B 实测；退路：改为"玩家登录后 + 打开 GUI 时"刷新 |
| S-0.3-3 | `getVanillaMaxStackSize()` 覆写为物品最大堆叠数后，BD 原生槽位操作是否异常 | 🟡 中 | 阶段 A 实机验证 |
| S-0.3-6 | 按物品注册名搜索物化条目不可用（`SearchHelper.getItemId` 对非 `ItemStackKey` 返回 `""`） | 🟡 低 | 记入"已知限制"，不做 hack |
| R-0.3-1 | 与架构文档 **R10**（幽灵物品）的关系 | ✅ 转正面 | 0.3 正是 R10 的结构性修复：判据从"客户端推断"改为"服务端持有" |
| R-0.3-2 | 卸载本模组 → 物化条目 + EMC 条目被静默丢弃 | 🟡 中（既有） | README 已有警示（架构文档 R9）。**已裁定不纳入 0.3**（维持现状）；`/beyondemc drain` 作为可选项，未排入本版 |

---

## 12. 一句话总结

> 0.3 把"已学习但无库存"的物品从**客户端算出来的显示行**，变成**服务端按"逐件 `floor(EMC ÷ 单价)`"（0.2 口径）物化、写进网络存储（独立 type bucket）、由 BD 自动持久化与同步**的真实条目；
> 唯一权威仍是 EMC 池 + 学习集合，物化条目是**可随时丢弃重建的纯函数派生**，因此物化层损坏不丢数据、回退到 0.2 也不超发。
> 显示的数量之和**允许**超过 EMC（这是 0.2 的口径，不是 bug）：防刷物品靠 **INV-1′「交付即扣费」**——含本轮新发现并封堵的"按标签 / 按槽位零扣费抽走"路径（§5.2）。

---

## 13. 勘误登记（rev2 / rev3）

| # | 原文断言 | 事实 | 处置 |
|---|---|---|---|
| **E1** | §9.1「`materializeItems=false` 后存档里的物化条目被忽略（不参与任何结算）」等价 0.2 | 条目是**真实存储条目**，BD 原生列表照样渲染它们 ⇒ 只"忽略"**不等于** 0.2 行为 | L1 熔断改为**清空**条目（§9.1） |
| **E2** | §3.1「基类 `LongType.equals` 只比较类」 | 真正的基类是 **`LongStackKey`**，其 `equals` 比的是 **`getTypeId()`**。`LongType` 只是 `EmcItemType` 的父类 | 结论（必须覆写）不变；机制与引用更正为 §3.1 |
| **E3** | §2.1「…**不会出现重复行**」 | 若某物品既有真实库存又在学习集合里，会出现**两行** | 物化规则增加"`realStock > 0` 不物化"，与 0.2 `skipStocked` 一致 |
| **E4** | §4.2 触发点 ⑤「读档完成后：`DimensionsNetMixin.load` RETURN」 | 那时 EMC 表**尚未就绪**（需 `EMCRemapEvent`） | ④⑤ 合并挂 `EmcAvailability.onRemap` |
| **E5** | §3.1「`EmcItemKey.codec()` 用 `ItemInfo.CODEC`」 | `Codec` 不能用于 `MapCodec` 分发位；`ItemInfo` 另提供 `MAP_CODEC` 与 `STREAM_CODEC` | 改用 `ItemInfo.MAP_CODEC.xmap(...)`（§3.1） |
| **E6** | §3.1 未提 `getModId` / `getTags` 语义 | `SORT_MODID`、模组搜索、标签搜索直接用它们 | 追加两行（§3.1），并登记 `getTags()` 的连带风险 |
| **E7** | §5 兑换链路「【原子】`extract(EmcItemKey, count)` + `spendEmc`」 | 物化是纯派生，扣费后 `refresh` 全量重解即可 | 删除该 extract 步；INV-2 语义回归"扣费 vs 交付" |
| **E8** | §2.4 备选 E「`LEDGER` 只写网络 NBT」 | 未提其需自实现持久化/同步/合并/销毁 | 保留为 L2 降级并标注成本（§9.1） |
| **E9** | §2.3「INV-1：`Σ count×price ≤ EMC`」 | 与 0.2 逐件取整口径冲突；防超发并不依赖它 | 删除 INV-1，改为 **INV-1′（收费唯一）**；计数改逐件 `floor(E÷单价)`（§2.3） |
| **E10** | §6「接口过滤器仍是 `ItemStackKey`，不会命中 `EmcItemKey`」（隐含"安全"） | `extract(TagKey,…)` / `extract(slot,…)` 会以 `EmcItemKey` 身份进入钩子并被放行 ⇒ **零扣费交付** | 新增 §5.2 护栏（改道收费 + `MaterializingGuard`）；S-0.3-7 |
