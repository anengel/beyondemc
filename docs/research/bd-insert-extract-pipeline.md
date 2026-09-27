# Beyond Dimensions「物品进入维度网络」全路径调研

> 调研对象：`reference/BeyondDimensions`（分支 `1.21.1`，模组版本 0.7.30，NeoForge，包名 `com.wintercogs.beyonddimensions`）
> 调研目的：为附属模组「把带 EMC 值的物品在存入维度网络时折算为 EMC 数值」确定精确接入点
> 说明：本文所有 `文件:行号` 均以仓库根目录 `reference/BeyondDimensions` 为基准的相对路径。**未确认**的结论已显式标注。

## 目录

1. [总览：三层结构](#1-总览三层结构)
2. [`UnifiedStorage.insert` 的完整调用链](#2-unifiedstorageinsert-的完整调用链)
3. [`UnifiedStorageBeforeInsertHandler` 的覆盖范围与绕过路径（关键风险）](#3-unifiedstoragebeforeinserthandler-的覆盖范围与绕过路径关键风险)
4. [`AbstractUnorderedStackHandler` 与 `StackHandler` 结构](#4-abstractunorderedstackhandler-与-stackhandler-结构)
5. [`KeyAmount` 语义](#5-keyamount-语义)
6. [`ItemStackKey`：同种物品的判定](#6-itemstackkey同种物品的判定)
7. [`UnifiedStorageBeforeExtractHandler` 能力评估](#7-unifiedstoragebeforeextracthandler-能力评估)
8. [`LongType` / `LongStackKey` / `EnergyStackKey` 差异与模板选择](#8-longtype--longstackkey--energystackkey-差异与模板选择)
9. [新增一种资源类型的最小注册清单](#9-新增一种资源类型的最小注册清单)
10. [客户端渲染侧要求](#10-客户端渲染侧要求)
11. [对附属模组的实现建议](#11-对附属模组的实现建议)

---

## 1. 总览：三层结构

| 层 | 类 | 职责 | 与 EMC 需求的关系 |
| --- | --- | --- | --- |
| 网络存储层 | `UnifiedStorage`（无序、到 0 即删） | 每个 `DimensionsNet` 独占一个实例 | **唯一带 `onBeforeInsert` 钩子的类** |
| 抽象容器层 | `AbstractUnorderedStackHandler` | `Map<IStackKey,Long>` + `slotIndex` 槽位视图、订阅广播、NBT | 钩子之下，**无任何钩子** |
| 有序容器层 | `StackHandler` | `IStackKey[] keys` + `long[] amounts` 数组（箱子类） | **完全不在网络路径上**（见 §3.4） |

`UnifiedStorage` 的类头与核心覆写：

```java
// api/dimensionnet/UnifiedStorage.java:22
public class UnifiedStorage extends UnorderedStackHandlerRemoveZero
```

```java
// api/dimensionnet/UnifiedStorage.java:86-102
    @Override
    public @NotNull KeyAmount insert(IStackKey<?> key, long amount, boolean simulate)
    {
        KeyAmount input = new KeyAmount(Objects.requireNonNullElse(key, EmptyStackKey.INSTANCE), amount);

        var info = UnifiedStorageBeforeInsertHandler.onBeforeInsert(input, net);

        if (info.cancel())
            return input;

        KeyAmount adjusted = info.beforeInsert();

        if (adjusted.isEmpty())
            return adjusted;

        return super.insert(adjusted.key(), adjusted.amount(), simulate);
    }
```

`net` 字段即钩子能拿到的网络上下文：

```java
// api/dimensionnet/UnifiedStorage.java:28-34
    private final DimensionsNet net;

    public UnifiedStorage(DimensionsNet net, UiTimestampPolicy uiTimestampPolicy)
```

每个网络在构造时新建一个 `UnifiedStorage`：

```java
// api/dimensionnet/DimensionsNet.java:104
        unifiedStorage = new UnifiedStorage(this, AbstractUnorderedStackHandler.UiTimestampPolicy.AUTO);
```

**关键事实**：`UnifiedStorageBeforeInsertHandler.addHandler` / `UnifiedStorageBeforeExtractHandler.addHandler` 在本仓库源码中**从未被调用过**（除定义处外无任何调用点，见 §3.1），它们是为附属模组预留的公共 API。

---

## 2. `UnifiedStorage.insert` 的完整调用链

`IStackHandler` 声明了两个重载：

```java
// api/storage/handler/IStackHandler.java:78        // 无序全容器插入
    @NotNull KeyAmount insert(int slot, IStackKey<?> key, long amount, boolean simulate);
// api/storage/handler/IStackHandler.java:87        // 按槽插入
    @NotNull KeyAmount insert(IStackKey<?> key, long amount, boolean simulate);
```

`AbstractUnorderedStackHandler` 中二者等价（槽位参数被忽略）：

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:521-525
    @Override
    public @NotNull KeyAmount insert(int slot, IStackKey<?> key, long amount, boolean simulate)
    {
        return insert(key, amount, simulate);
    }
```

因此**只要动态类型是 `UnifiedStorage`，两个重载都会经过 §1 的钩子**。以下按路径分类列出全部调用点。

### 2.1 GUI 菜单槽位点击（普通点击）

链路：

`CallSeverClickPacket.handleInServer` → `BDBaseMenu.customClickHandler` → `AbstractStackTypedSlot.click(...)`（由 `DisorderedStackTypedSlot` / `OrderedStackTypedSlot` 实现）→ `storage.insert(...)`

```java
// network/packet/c2s/CallSeverClickPacket.java:39-47
    private void handleInServer(final IPayloadContext context)
    {
        Player player = context.player();
        if (player.containerMenu instanceof BDBaseMenu menu)
        {
            menu.customClickHandler(this.slotIndex(), this.clickItem(), this.button(), this.shiftDown());
            menu.broadcastChanges();
        }
    }
```

```java
// common/menu/BDBaseMenu.java:184-190
        if (slots.get(slotIndex) instanceof AbstractStackTypedSlot slot)
        {
            if (shiftDown)
                slot.quickMove(clickedStack, button, player);
            else
                slot.click(clickedStack, button, player);
        }
```

无序槽位（维度网络主界面 `DimensionsNetMenu` 使用，见 `common/menu/DimensionsNetMenu.java:124` 与 `:137`，服务端传的是 `storage` 即 `UnifiedStorage`）：

| 位置 | 场景 |
| --- | --- |
| `common/menu/widget/slot/DisorderedStackTypedSlot.java:75` | 经验棒右键存入 XP 流体 |
| `.../DisorderedStackTypedSlot.java:102,106` | 桶/流体容器物品右键倒入流体（先 simulate 后 real） |
| `.../DisorderedStackTypedSlot.java:133` | 通用能力物品（含 `ItemStackKey`）内容物插入 |
| `.../DisorderedStackTypedSlot.java:163` | **兜底：手持物品插入网络（最主流的物品存入路径）** |
| `.../DisorderedStackTypedSlot.java:220,248` | 已有流体键上的经验棒交互 |
| `.../DisorderedStackTypedSlot.java:307` | 桶继续投放流体 |
| `.../DisorderedStackTypedSlot.java:374` | 手持容器物品内容物存入 |
| `.../DisorderedStackTypedSlot.java:409` | **兜底：手持物品与槽位物品不同时的存入** |
| `.../DisorderedStackTypedSlot.java:473,482,489,498,499` | 快速转移回滚/流体装桶回插 |
| `.../DisorderedStackTypedSlot.java:517` | `safeInsert(key, amount)` 实现 |
| `common/menu/widget/slot/OrderedStackTypedSlot.java:80,107,111` | 有序槽位经验棒/桶 |
| `.../OrderedStackTypedSlot.java:137` | 有序槽位能力物品内容物 |
| `.../OrderedStackTypedSlot.java:167,207` | **有序槽位手持物品插入** |
| `.../OrderedStackTypedSlot.java:241,272,276,282` | 有序槽位经验棒/物品交换 |
| `.../OrderedStackTypedSlot.java:328` | 有序槽位桶投放 |
| `.../OrderedStackTypedSlot.java:454` | 快速转移回的桶回插 |

> ⚠️ `OrderedStackTypedSlot` 的 `storage` 字段虽然声明为 `IStackHandler`，但它由 `CapabilityHelper.registerStackTypedHandler` 传入的是 **`StackHandler`**（构造器签名见 `api/capability/helper/ordered/ItemStackTypedHandler.java:20-29`，字段 `private final StackHandler handlerStorage`）。所以有序槽位路径**不经过** `UnifiedStorage`，详见 §3.4。

### 2.2 玩家背包 shift 点击（快速转移）

链路：`CallSeverClickPacket` → `BDBaseMenu.customClickHandler`（`shiftDown == true`）→ 两个分支：

**(a) 点击的是原版背包槽位** → `BDBaseMenu.quickMoveHandle(...)`：

```java
// common/menu/BDBaseMenu.java:308-311（普通快速移动分支）
                    if (targetSlot instanceof AbstractStackTypedSlot aTargetSlot)
                    {
                        newSize = (int) aTargetSlot.safeInsert(new ItemStackKey(remaining), remaining.getCount()).amount();
                    }
```

`safeInsert` 对无序槽位直通 `UnifiedStorage.insert`：

```java
// common/menu/widget/slot/DisorderedStackTypedSlot.java:512-521
    @Override
    public KeyAmount safeInsert(IStackKey<?> stack, long amount)
    {
        if (stack != null)
        {
            return storage.insert(stack, amount, false);
        }
        return new KeyAmount(ItemStackKey.EMPTY, 0);
    }
```

同函数内的合成产物分支也走同一路径（`BDBaseMenu.java:239`）。若目标是**有序**网络槽位则为 `stack.insert(theSlot, ...)`（`OrderedStackTypedSlot.java:473`），落到 `StackHandler`。

**(b) 点击的是网络存储槽位** → `AbstractStackTypedSlot.quickMove(...)`（把网络里的东西搬到背包）：

- `DisorderedStackTypedSlot.quickMove` — `.../DisorderedStackTypedSlot.java:456,459,473,482,489,498,499`
- `OrderedStackTypedSlot.quickMove` — `.../OrderedStackTypedSlot.java:406,409,427,437,444,453,454`

**(c) 批量转移（Ctrl 批量之类）** → `BatchTransferPacket`：

```java
// network/packet/c2s/BatchTransferPacket.java:51-55
                        if (menu.inventoryStartIndex <= invSlot.index && invSlot.index < menu.inventoryEndIndex)
                        {
                            if (clickItem.equals(new ItemStackKey(invSlot.getItem())))
                                menu.customClickHandler(invSlot.index, new KeyAmount(new ItemStackKey(invSlot.getItem()), invSlot.getItem().getCount()), 0, true);
                        }
```

反向（存储→背包）在同文件 `:70-78`，同样调用 `storage.insert`。

**(d) 直接把手持物放进主网络** → `PutHandItemToNetPacket`：

```java
// network/packet/c2s/PutHandItemToNetPacket.java:43-47
        DimensionsNet net = DimensionsNet.getNetFromPlayer(player);
        if (net == null) return;
        UnifiedStorage storage = net.getUnifiedStorage();
        KeyAmount remaining = storage.insert(new ItemStackKey(player.getMainHandItem()), player.getMainHandItem().getCount(), false);
        player.getMainHandItem().setCount((BDMath.clampLongToInt(remaining.amount())));
```

### 2.3 外部方块（网络接口 / 物品栏 / 通道）

**网络接口 `NetInterfaceBlockEntity`（`NetInterfaceAccess.transferToNet`）**：

```java
// common/menu/NetInterfaceAccess.java:68-74
            KeyAmount stack = stackHandler.getStackBySlot(i);
            if (!stack.isEmpty() && !isInputTypeBlocked(stack.key(), 0))
            {
                KeyAmount extracted = stackHandler.extract(i, stack.amount(), false);
                KeyAmount remaining = net.getUnifiedStorage().insert(extracted.key(), extracted.amount(), false);
                if (!remaining.isEmpty())
                    stackHandler.insert(i, remaining.key(), remaining.amount(), false);
```

反向 `transferFromNet` 在 `:127-133`。接口的 `storage` 是 `StackHandler`（`common/menu/NetInterfaceBaseMenu.java:91` → `access.getStackHandler()`），最终到网络的必经之路就是这一行 `net.getUnifiedStorage().insert(...)`。

接口方块实体的另一处直连：

```java
// common/block/entity/NetInterfaceBlockEntity.java:283-285
                                long remainging = stackHandlerWrapper.insert(slot, current.toStack(), false);
                                ...
                                stackHandler.extract(i, extract, false);
```

**网络通道 `NetPathwayBlockEntity`（能力暴露）**：路径方块把网络包装成外部能力（`CapabilityHelper.USHandlerMap`），包装器内部最终调用 `storage.insert`：

```java
// common/block/entity/NetPathwayBlockEntity.java:34-43
                                DimensionsNet net = be.getNet();
                                if (net != null && handler != null)
                                {
                                    if (handler.isContextual())
                                        return handler.apply(net.getUnifiedStorage(), new CapCtx(be.level, be.getBlockPos(), be));
                                    else
                                        return handler.apply(net.getUnifiedStorage(), null);
                                }
```

具体包装器（均直接持有 `UnifiedStorage`）：

| 位置 | 类型 |
| --- | --- |
| `api/capability/helper/unordered/ItemUnifiedStorageHandler.java:90` | `insertItem` → `storage.insert(new ItemStackKey(itemStack), ...)` |
| `api/capability/helper/unordered/FluidUnifiedStorageHandler.java:66` | 流体 |
| `api/capability/helper/unordered/EnergyUnifiedStorageHandler.java:20` | 能量 |
| `integration/module/mekanism/storage/ChemicalUnifiedStorageHandler.java:52,73,94` | 化学品 |
| `integration/module/botania/storage/ManaUnifiedStorageHandler.java:75,77` | 魔力 |
| `integration/module/ars/storage/SourceUnifiedStorageHandler.java:92,94,108` | 源质 |
| `integration/module/ifs/storage/WardenSoulUnifiedStorageHandler.java:51` | 监守者之魂 |

`ItemUnifiedStorageHandler` 关键代码：

```java
// api/capability/helper/unordered/ItemUnifiedStorageHandler.java:88-90
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack itemStack, boolean sim)
    {
        KeyAmount remaining = storage.insert(new ItemStackKey(itemStack), itemStack.getCount(), sim);
```

**其它方块实体直连网络**：

| 位置 | 说明 |
| --- | --- |
| `common/block/entity/NetHopperBlockEntity.java:117,121` | 维度漏斗吸收掉落物（先 simulate 后 real） |
| `common/block/entity/NetHopperBlockEntity.java:141,144` | 吸收经验球 |
| `common/block/entity/NetHopperBlockEntity.java:272,274` | 流体 |
| `common/block/entity/NetPumpBlockEntity.java:123,126` | 维度泵 |
| `common/block/entity/NetEnergyPathwayBlockEntity.java:122` | 能量通道（只取） |
| `common/block/entity/BaseNetFurnaceBlockEntity.java:425,528,712,753,773,802` | 维度熔炉 |
| `integration/module/create/block/entity/SchematicannonPathWayBlockEntity.java:319` | 机械动力 |
| `integration/module/ars/block/entity/SourcePathwayBlockEntity.java:78` |  Ars |
| `integration/module/rs/block/entity/RSNetPathwayBlockEntity.java:126` | 只读暴露 |

### 2.4 物品 / 工具 / 其它

| 位置 | 说明 |
| --- | --- |
| `common/item/NetMagnetItem.java:134,149` | 磁铁吸物品（simulate 后 real） |
| `common/item/NetMagnetItem.java:168,171` | 磁铁吸经验 |
| `common/item/NetMagnetItem.java:328,330` | 磁铁吸流体 |
| `common/item/NetFeederItem.java:127,136` | 自动喂食回流 |
| `common/item/NetRestockerItem.java:106,150,168,175,183,217,224,261,273,280` | 自动补货 |
| `common/item/NetCreater.java:82` | 建网赠品 |
| `common/item/XpExchangeItem.java:109,111,159` | 经验交换棒 |
| `common/item/MatterCompressionBall.java:60` | 物质压缩球解包 |
| `common/item/TestItem_ItemGenerate.java:62` | 测试物品 |
| `common/menu/widget/slot/AutoRefillResultSlot.java:113` | 自动补充产物槽 |
| `common/menu/DimensionsCraftMenu.java:417,436` | 合成菜单产物回插 |
| `api/dimensionnet/DimensionsNet.java:365` | 旧版 `EnergyStorage` 数据迁移 |
| `api/dimensionnet/DimensionsNet.java:739` | 网络合并 `mergeOtherNet` |
| `api/dimensionnet/DimensionsNet.java:841` | 定时生成破碎时空结晶 |
| `integration/module/ae2/me/NetStorageCell.java:75` | AE2 ME 存储总线 |
| `integration/module/rs/storage/BD_RSExternalStorageProvider.java:68` | RS 外部存储 |

### 2.5 结论

**所有「把资源交给某个维度网络」的语义路径，除 §3.4 的绕过情形外，最终都收敛到 `UnifiedStorage.insert(IStackKey, long, boolean)`。** 这是做 EMC 折算的正确切入面。

---

## 3. `UnifiedStorageBeforeInsertHandler` 的覆盖范围与绕过路径（关键风险）

### 3.1 注册与触发机制

```java
// api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java:15-45
public final class UnifiedStorageBeforeInsertHandler
{
    private static final List<BeforeInsertHandler> handlers = new ArrayList<>();

    @FunctionalInterface
    public interface BeforeInsertHandler
    {
        @NotNull
        BeforeInsertHandlerReturnInfo beforeInsert(
                @NotNull KeyAmount originalInsert,
                @NotNull KeyAmount tryInsert,
                @Nullable DimensionsNet net
        );
    }

    public record BeforeInsertHandlerReturnInfo(@NotNull KeyAmount beforeInsert, boolean cancel)
    {
    }

    public static void addHandler(BeforeInsertHandler handler)
    {
        handlers.add(handler);
    }
```

链式调用与短路语义：

```java
// api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java:69-86
        KeyAmount current = tryInsert;

        for (var handler : handlers)
        {
            if (handler == null)
                continue;

            var ret = handler.beforeInsert(original, current, net);
            current = ret.beforeInsert();

            if (ret.cancel())
                return new BeforeInsertHandlerReturnInfo(current, true);

            if (current.isEmpty())
                return new BeforeInsertHandlerReturnInfo(current, false);
        }

        return new BeforeInsertHandlerReturnInfo(current, false);
```

要点：

- `handlers` 是**静态全局列表**，一次注册对所有网络、所有维度生效。`ArrayList` 非线程安全，建议在 `FMLCommonSetupEvent` 内注册一次。
- **没有 `net != null` 校验**：`UnifiedStorage.getEmpty()` 的实例 `net == null`（`api/dimensionnet/UnifiedStorage.java:45-67`），但它的 `onChange` 被 no-op 化，`isEmpty()` 恒真，且 `insert` 未覆写……实际 `getEmpty()` 返回的是匿名子类，仍会走 `UnifiedStorage.insert` 并触发 handler，**handler 必须容忍 `net == null`**。
- 每个 handler 都能拿到 `originalInsert`（最原始请求）与 `current`（链上前一个处理过的结果），便于做幂等判断。
- 返回 `cancel = true` 时，`UnifiedStorage.insert` 直接 `return input;`——**注意返回的是原始 `input` 而不是 `adjusted`**（`api/dimensionnet/UnifiedStorage.java:93-94`），语义上「网络完全没接受任何东西」。

调用点（`onBeforeInsert` 唯一调用处）：

```java
// api/dimensionnet/UnifiedStorage.java:91
        var info = UnifiedStorageBeforeInsertHandler.onBeforeInsert(input, net);
```

**A. 被覆盖的路径**：所有通过 `UnifiedStorage.insert` 的路径（§2.1 ~ §2.4 的绝大多数），包括 `simulate = true` 的模拟调用。

**B. `addHandler` 从未被本模组调用**：

搜索结果（`addHandler\(|onBeforeInsert|onBeforeExtract`，全仓库 6 处）仅包含定义与 `UnifiedStorage` 内的调用：

- `api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java:46`（定义）
- `api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java:61`（定义 `onBeforeExtract`）
- `api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java:42`（定义 `addHandler`）
- `api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java:57`（定义 `onBeforeInsert`）
- `api/dimensionnet/UnifiedStorage.java:91`（插入侧调用）
- `api/dimensionnet/UnifiedStorage.java:122`（提取侧调用）

→ **该 API 完全为附属模组预留，本模组自身无任何 handler，不存在顺序竞争。**

### 3.2 ⚠️ 绕过路径（不经过 `UnifiedStorage.insert` 的写入）

这是本调研的核心风险清单。

**(1) 直接反序列化写入**

`DimensionsNet.load` 走 `UnifiedStorage.deserializeNBT`：

```java
// api/dimensionnet/DimensionsNet.java:358
        net.unifiedStorage.deserializeNBT(registryAccess, tag.getCompound("UnifiedStorage"));
```

而 `UnifiedStorage` 未覆写 `deserializeNBT`，落到基类 `AbstractUnorderedStackHandler.deserializeNBT`（`:866`），其内部通过 `acceptEntry` → `insert`：

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:987-990
        else
        {
            insert(key, amount, false);
        }
```

**结论**：此处的 `insert` 是**虚方法调用**，动态类型是 `UnifiedStorage`，因此 **`onBeforeInsert` 会被触发**。
⚠️ 但要注意副作用：读档时把已存的「物品键」重新折算成 EMC 会导致**读档过程发生数据改写**，玩家存档里的物品会在加载瞬间被转成 EMC（且 `ifPresent`/loop 内无 simulate 保护）。这是必须显式规避的陷阱，详见 §11。

**(2) `setAmountByKey` / `setStackDirectly` —— 真正的绕过**

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:419-472
    public long setAmountByKey(IStackKey<?> key, long amount)
    {
        ...
        storage.put(key, target);      // 直接写 Map，不经过 insert()
        ensureInIndex(key);
        ...
        if (delta > 0L) onContentChanged(key, delta, target > current);
        return target;
    }
```

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:474-513
    @Override
    public void setStackDirectly(int slot, IStackKey<?> newKey, long amount)
    {
        ...
            setAmountByKey(newKey, target);   // 同样不经 insert
```

**`setAmountByKey` 与 `setStackDirectly` 都不会调用 `insert`，因此不会触发 `onBeforeInsert`。** `UnifiedStorage` 未覆写这两者。

**(3) `setStackInSlot`（NeoForge 能力接口）→ 直通 `setStackDirectly`**

```java
// api/capability/helper/ordered/ItemStackTypedHandler.java:139-152
    @Override
    public void setStackInSlot(int slot, @NotNull ItemStack stack)
    {
        int actualIndex = resolveActualIndex(slot);
        if (actualIndex < 0) return;

        if (stack.isEmpty())
        {
            handlerStorage.setStackDirectly(actualIndex, EmptyStackKey.INSTANCE, 0);
            return;
        }

        handlerStorage.setStackDirectly(actualIndex, new ItemStackKey(stack), stack.getCount());
    }
```

**(4) 网络客户端侧同步（不是网络真存储，但会导致客户端视图不一致）**

无序槽位的客户端同步走 `setAmountByKey`：

```java
// common/menu/widget/slot/DisorderedSlotGroupSync.java:334-341
            if (key != null)
            {
                clientStorage.setAmountByKey(key, count);
```

`clientStorage` 在服务端菜单里就是 `UnifiedStorage`（同一个对象引用），但在客户端是 `ClientNetStorage`（`common/menu/widget/ClientNetStorage.java:24`，`extends AbstractUnorderedStackHandler`，**不是 `UnifiedStorage`**）。
→ 客户端不会触发 handler（好），但也说明**客户端的存储视图直接照抄服务端的 key 列表**，所以只要服务端存的是 EMC key，客户端就会显示 EMC key，无需客户端额外折算。

有序槽位的客户端同步也是直写：

```java
// common/menu/widget/slot/OrderedStackTypedSlot.java:506-510
    @Override
    public void loadChange(int where, IStackKey<?> newStack, long newAmount)
    {
        storage.setStackDirectly(where, newStack, newAmount);
    }
```

**(5) 有序容器 `StackHandler` 整体不在网络路径上**

`StackHandler` 有独立的 `insert` 实现，**完全不经过 `UnifiedStorage`**：

```java
// api/storage/handler/impl/StackHandler.java:488-491
    @Override
    public @NotNull KeyAmount insert(IStackKey<?> key, long amount, boolean simulate)
    {
        if (key == null || key == EmptyStackKey.INSTANCE || amount <= 0L)
            return new KeyAmount(EmptyStackKey.INSTANCE, 0L);
```

它被用于：网络接口的输入/输出槽、维度熔炉的输入/燃料/输出槽、净磁铁/补货器/喂食器的过滤槽等（`common/menu/NetRestockerMenu.java:44`、`NetMagnetMenu.java:40`、`NetFeederMenu.java:41`、`NetHopperMenu.java:51,55`、`NetPumpMenu.java:51,55`）。
→ **风险**：接口槽位「暂存」阶段不会被折算，只有真正从接口推进网络的那一刻（`NetInterfaceAccess.java:72`）才折算。这符合期望，但要注意接口槽位里的物品**不会**变成 EMC。

**(6) `clearStorage()`**

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:379-391
    @Override
    public void clearStorage()
```

只清空，无插入语义，无风险。被 `DimensionsNet.destroySelf()`（`api/dimensionnet/DimensionsNet.java:779`）与 `deserializeNBT`（`:868`）调用。

**(7) `addStackDirectly`**

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:515-519
    @Override
    public void addStackDirectly(IStackKey<?> key, long amount)
    {
        insert(key, amount, false);
    }
```

→ **会经过钩子**（虚方法）。`UnifiedStorage` 上未被调用（`IStackHandler.addStackDirectly` 的调用点只在 `StackHandler.java:425` 的定义处），属低风险。

**(8) ⚠️ 重入路径：物质压缩球解包**

`AbstractUnorderedStackHandler.insert` 内部有对 `insert(...)` 的**递归调用**：

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:534-537
        if (key instanceof ItemStackKey itemKey && itemKey.getSource() == BDItems.MATTER_COMPRESS_BALL.get())
        {
            return unzipMatterBall(itemKey, add, simulate);
        }
```

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:624
            KeyAmount leftover = insert(entry.key(), scaled, false);
```

因为这是 `super.insert(...)` 内部对 `this.insert` 的调用（`this` 是 `UnifiedStorage`），**它会对压缩球内部的每一个子堆叠再触发一次 `onBeforeInsert`**。这实际上是**期望行为**（球内物品也应折算 EMC），但意味着：

- handler 必须**幂等**，不能因为「已经是 EMC key」而误判为「原始请求」；
- 不能在 handler 里依赖外层调用次数；
- 压缩球内容物**不经过接口的 `isInputTypeBlocked` 过滤**逻辑（该过滤只在 `NetInterfaceAccess` 中，`api/.../NetInterfaceAccess.java:81-111`），所以附属模组若想限制某些物品折算，需要在 handler 里自己递归检查压缩球。

### 3.3 风险清单汇总

| 编号 | 绕过点 | 是否触发 `onBeforeInsert` | 严重度 |
| --- | --- | --- | --- |
| B1 | `AbstractUnorderedStackHandler.deserializeNBT` → `acceptEntry` → `insert` | ✅ 会（虚调用） | **高**（读档时意外改写） |
| B2 | `setAmountByKey` | ❌ 不会 | 中（客户端同步/外部模组直改） |
| B3 | `setStackDirectly` | ❌ 不会 | 中 |
| B4 | `ItemStackTypedHandler.setStackInSlot` → `setStackDirectly` | ❌ 不会 | 中 |
| B5 | `StackHandler.insert`（有序容器） | ❌ 不会 | 低（不在网络存储上） |
| B6 | `ClientNetStorage` 客户端视图 | ❌ 不会（也不应触发） | 低 |
| B7 | `unzipMatterBall` 递归 `insert` | ✅ 会（多次） | 中（需幂等） |
| B8 | `UnifiedStorage.getEmpty()`（`net == null`） | ✅ 会 | 低（需容忍 null net） |

---

## 4. `AbstractUnorderedStackHandler` 与 `StackHandler` 结构

### 4.1 `AbstractUnorderedStackHandler`（无序，网络存储用）

**存储数据结构 = Map + 数组索引 + 类型桶**：

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:52-58
    /* ---------- 内部存储 ---------- */
    protected final Map<IStackKey<?>, Long> storage = new HashMap<>();
    protected final ArrayList<IStackKey<?>> slotIndex = new ArrayList<>();
    protected final Map<IStackKey<?>, Integer> posMap = new HashMap<>();
    protected final Map<IStackKey<?>, Object> key2stackMap = new HashMap<>();
    protected final Map<ResourceLocation, TypeBucket> type2buckets = new HashMap<>();
    protected final Multimap<TagKey<?>, IStackKey<?>> tag2stackMap = HashMultimap.create();
```

- `storage`：key → 数量，**唯一真值来源**。
- `slotIndex` / `posMap`：把 Map 的键投影成「槽位数组」，`removeFromIndex` 使用 O(1) 换尾删除（`:746-766`）。无序容器的「槽位」不是稳定位置。
- `type2buckets`：`ResourceLocation`（typeId）→ 该类型下的所有 key。
- `entriesView`：动态只读 `List<KeyAmount>` 视图（`:71-88`），`getStorage()` 返回它。

**容量语义**：`slotCapacity` 是**每个 key 各自的上限**（不是总量），`slotMaxSize` 是**key 总数上限**（即「槽位数」）：

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:162-164
    /* ---------- 可配置容量/槽位上限 ---------- */
    public long slotCapacity = Long.MAX_VALUE;
    public int slotMaxSize = Integer.MAX_VALUE;
```

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:539-548
        long current = storage.getOrDefault(key, 0L);
        boolean needNewSlot = (current == 0L) && !posMap.containsKey(key);
        if (needNewSlot && slotIndex.size() >= slotMaxSize)
        {
            return new KeyAmount(key, add);
        }

        long cap = slotCapacity;
        long room = cap <= current ? 0L : (cap - current);
        if (room <= 0L) return new KeyAmount(key, add);
```

新建网络的默认值来自 `NetCreater`（`DimensionsNet.createNewNetForPlayer(player, Long.MAX_VALUE, Integer.MAX_VALUE)`，见 `common/item/NetCreater.java:62`）。

**`onChange()` 语义**：

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:351-370
    /* ============== 生命周期：留给子类覆写的统一入口 ============== */
    @Override
    public void onChange()
    {
        fireChange();
    }

    protected final void onContentChanged(IStackKey<?> type, long size, boolean insert)
    {
        beginDeltaContext();
        try
        {
            onChange();
        }
        finally
        {
            endDeltaContext();
        }
        fireDelta(type, size, insert);
    }
```

- `onChange()`：内容变更通知钩子，子类覆写。`UnifiedStorage` 覆写为「标记存档脏 + 广播」（`api/dimensionnet/UnifiedStorage.java:75-84`）。
- `onContentChanged(key, size, insert)`：**唯一的内部变更出口**，先 `onChange()`（处于 delta 上下文中，`fireChange` 被抑制），再 `fireDelta`（`insert=true` 表示净增，`false` 表示净减）。
- 订阅模型：`subscribeAny` / `subscribeDelta`（强引用 owner）+ `subscribeAnyWeak` / `subscribeDeltaWeak`（弱引用），全部显式 owner，返回 `AutoCloseable`（`:263-307`）。

**`UiTimestampPolicy` 的作用**：仅用于 **UI 排序**，与业务逻辑无关。

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:39-41
    /* ---------- UI 时间戳维护策略 ---------- */
    public enum UiTimestampPolicy
    {NONE, AUTO} // NONE: 不主动维护；AUTO: 自动维护
```

```java
// api/storage/handler/impl/AbstractUnorderedStackHandler.java:61-68
    /**
     * 记录该 Key 最近一次“从无到有建槽位”的时间（毫秒时间戳）。仅供 UI 展示，无其他语义。
     */
    protected final Map<IStackKey<?>, Long> creationTimeMap = new HashMap<>();
    /**
     * 记录该 Key 最近一次“数量被修改”的时间（毫秒时间戳）。仅供 UI 展示，无其他语义。
     */
    protected final Map<IStackKey<?>, Long> lastModifiedTimeMap = new HashMap<>();
```

`AUTO` 时，每次建槽位/改数量都会刷新（`:436-468`、`:557-560`、`:740-743`），供 `ButtonState.SORT_INSERTED_TIME` / `SORT_MODIFIED_TIME` 使用（`common/menu/widget/ClientNetStorage.java:183-184,209-210`）。
> **副作用提醒**：把物品折算成 EMC 会让「物品 key 的时间戳随槽位一起消失，EMC key 新获得时间戳」，UI 上表现为该物品消失、EMC 条目被标记为新插入。

**对外公开的增删查改方法签名**（`AbstractUnorderedStackHandler`，按源码顺序）：

| 行号 | 签名 |
| --- | --- |
| `:263` | `AutoCloseable subscribeAny(Object owner, AnyChangeListener onAny)` |
| `:272` | `AutoCloseable subscribeDelta(Object owner, DeltaListener onDelta)` |
| `:281` | `<T> AutoCloseable subscribeAnyWeak(T owner, Consumer<T> onAny)` |
| `:295` | `<T> AutoCloseable subscribeDeltaWeak(T owner, QuadConsumer<T, IStackKey<?>, Long, Boolean> onDelta)` |
| `:353` | `void onChange()` |
| `:374` | `List<KeyAmount> getStorage()` |
| `:380` | `void clearStorage()` |
| `:394` | `KeyAmount getStackBySlot(int slot)` |
| `:402` | `KeyAmount getStackByKey(IStackKey<?> key)` |
| `:409` | `boolean hasStack(IStackKey<?> key)` |
| `:419` | `long setAmountByKey(IStackKey<?> key, long amount)` ← **无钩子** |
| `:475` | `void setStackDirectly(int slot, IStackKey<?> newKey, long amount)` ← **无钩子** |
| `:516` | `void addStackDirectly(IStackKey<?> key, long amount)`（内部转 `insert`） |
| `:522` | `KeyAmount insert(int slot, IStackKey<?> key, long amount, boolean simulate)`（转 `insert(key,...)`） |
| `:528` | `KeyAmount insert(IStackKey<?> key, long amount, boolean simulate)` |
| `:566` | `KeyAmount unzipMatterBall(ItemStackKey ballKey, long ballCount, boolean simulate)`（protected） |
| `:680` | `KeyAmount extract(int slot, long count, boolean simulate)` |
| `:691` | `KeyAmount extract(IStackKey<?> key, long amount, boolean simulate, boolean fuzzy)` |
| `:702` | `KeyAmount extract(TagKey<?> tagKey, long amount, boolean simulate)` |
| `:710` | `long getSlotCapacity(int slot)` |
| `:716` | `boolean isStackValid(int slot, IStackKey<?> key)`（恒 `true`） |
| `:722` | `boolean isEmpty()` |
| `:728` | `void ensureInIndex(IStackKey<?> key)`（protected） |
| `:746` | `void removeFromIndex(IStackKey<?> key)`（protected） |
| `:807` | `TypeBucket bucketOf(ResourceLocation type)`（protected） |
| `:812` | `Optional<TypeBucket> getBucket(ResourceLocation type)` |
| `:817` | `Object getOutStackByKey(IStackKey<?> key)` |
| `:823` | `CompoundTag serializeNBT(HolderLookup.Provider)` |
| `:866` | `void deserializeNBT(HolderLookup.Provider, CompoundTag)` |
| `:995` | `void setSlotCapacity(long capacity)` |
| `:1001` | `void setSlotMaxSize(int maxSize)` |
| `:1007` | `boolean isFullSlotsSize()` |

遍历方式：`getStorage()` 返回的 `entriesView` 是**动态只读视图**（不是快照），遍历时若发生写入会抛 `ConcurrentModificationException`。安全遍历应复制：`List.copyOf(storage.getStorage())`（本模组自身就是这么做的，如 `api/dimensionnet/DimensionsNet.java:760`）。

时间戳访问器：`setCreationTime`（`:175`）、`setLastModifiedTime`（`:183`）、`getCreationTimeMap`（`:191`）、`getLastModifiedTimeMap`（`:199`）、`setUiTimestampPolicy`（`:207`）、`getUiTimestampPolicy`（`:215`）。

### 4.2 `StackHandler`（有序，固定槽位，非网络存储）

**存储数据结构 = 定长数组 + 三类桶**：

```java
// api/storage/handler/impl/StackHandler.java:131-148
    /* ================= 基本存储（固定大小） ================= */

    private final int size;

    /**
     * 槽位上的 Key（EmptyStackKey.INSTANCE 代表空，不使用 null）
     */
    private final IStackKey<?>[] keys;

    /**
     * 槽位上的数量（空槽位必须为 0，与 keys 同步）
     */
    private final long[] amounts;

    /**
     * key -> 具体存储物的对照（只维护类型，不维护数量；不缓存 EmptyStackKey）
     */
    private final Map<IStackKey<?>, Object> key2stackMap = new HashMap<>();
```

索引：`typeBuckets`（typeId → `SlotBucket`，`:216`）、`keyBuckets`（精确 key → `SlotBucket`，允许同 key 多槽，`:229`）、以及由 `EmptyStackKey.INSTANCE` 构成的**空槽桶**（`:254-258`）。

**关键差异**：

- `insert(slot, key, amount, simulate)` **只对指定槽位操作**（`:435-485`），且非空槽要求 `curKey.equals(key)`（**精确相等**，含组件）。
- `insert(key, amount, simulate)` 分两阶段：先合并同 key 的已有槽位，再填空槽（`:488-555`）。
- 单槽上限 = `min(key.getVanillaMaxStackSize(), getSlotCapacity(slot))`（`:447`、`:474`），而 `AbstractUnorderedStackHandler` 用的是 `slotCapacity` 单独一项。这是两个实现**容量语义不一致**的地方，写跨容器逻辑时需注意。
- `onChange()` 是**空实现**：`// 根据需要覆写（保存/脏标记/事件）`（`:286-288`）。
- **无订阅机制**（没有 `subscribeAny` / `subscribeDelta`），同步靠 `OrderedStackTypedSlot.updateChange()` 逐槽发包（`.../OrderedStackTypedSlot.java:489-504`）。
- `isEmpty()` 判断所有槽都是空桶（`:686-690`）。

---

## 5. `KeyAmount` 语义

```java
// api/storage/key/KeyAmount.java:11-16
/**
 * 一个包含key和amount的记录类，极其轻量
 * <p>
 * 一般仅作于外部的只读视图
 */
public record KeyAmount(@NotNull IStackKey<?> key, long amount)
```

- **`amount <= 0` 是被允许的**：记录类不做任何规范化、不校验、不抛异常，`new KeyAmount(key, 0)` / `new KeyAmount(key, -5)` 都合法。
- **`isEmpty()` 的判定是「或」关系**（`amount <= 0` **或** key 为空）：

```java
// api/storage/key/KeyAmount.java:204-207
    public boolean isEmpty()
    {
        return amount <= 0L || key.isEmpty();
    }
```

- `toStack()` 会按数量拷贝出实际堆叠（int 截断警告已写在注释里）：

```java
// api/storage/key/KeyAmount.java:209-215
    /**
     * 给出当前kv对所代表的实际stack副本，不支持long数量的stack可能会被内部实现自动限制到int上限
     */
    public Object toStack()
    {
        return key.copyStackWithCount(amount);
    }
```

- **返回值约定**（贯穿全代码库，务必遵守）：
  - `insert(...)` 返回的是**未被接受的余量**（`AbstractUnorderedStackHandler.java:563` `return new KeyAmount(key, leftover);`；`StackHandler.java:554` 同）。
  - `extract(...)` 返回的是**实际取出的量**（`AbstractUnorderedStackHandler.java:676` `return new KeyAmount(key, take);`）。
  - **失败/空结果是 `KeyAmount(EmptyStackKey.INSTANCE, 0)`，不是 `null`**。
- 序列化：`CODEC`（`KeyAmount.java:193`）与 `STREAM_CODEC`（`:195-202`），网络侧用 `IStackKey.STREAM_CODEC + ByteBufCodecs.VAR_LONG`。
- ⚠️ 未确认：`KeyAmount` 的 `record` 自动 `equals` 是**逐字段比较**（`key.equals` + `amount`），因此比较两个 `KeyAmount` 会走 `IStackKey.equals` 的完整组件语义，比 `isSameTypeSameComponents` 多一层包装（语义等价）。

---

## 6. `ItemStackKey`：同种物品的判定

### 6.1 它包装什么

```java
// api/storage/key/impl/ItemStackKey.java:145-152
    // 实际存储-持久化保存和网络传输均以此为准
    private final Item item;
    private final DataComponentPatch patch; // 只拿额外组件，理论上完全足够了

    // 识别字段-用于hashcode和equals，用于解决组件可能未正确实现hashcode和equals的问题，同时也能同时处理数字值为NaN时的比较
    private byte[] patchByte = new byte[0]; // patch -> NBT -> 递归排序 -> 写为非压缩字节
```

**只保留 `Item` + `DataComponentPatch`，不保存 `ItemStack` 对象、不保存 count。** 数量由外部 `KeyAmount.amount` 承载。

```java
// api/storage/key/impl/ItemStackKey.java:39-40
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(BDConstants.MODID, "stack_type/item");
    public static final ItemStackKey EMPTY = new ItemStackKey();
```

```java
// api/storage/key/impl/ItemStackKey.java:169-179
    public ItemStackKey(ItemStack stack)
    {
        this(stack.getItem(), stack.getComponentsPatch());
    }

    // 仅供内部使用，不直接对外暴露
    private ItemStackKey(Item item, DataComponentPatch patch)
    {
        this.item = item;
        this.patch = patch == null ? DataComponentPatch.EMPTY : patch;
    }
```

### 6.2 是否保留 DataComponentPatch（NBT 组件）

**是，完整保留**，且以「规范化字节快照」参与 `equals`/`hashCode`：

```java
// api/storage/key/impl/ItemStackKey.java:344-366
    @Override
    public boolean isSameTypeSameComponents(IStackKey<?> other)
    {
        if (this == other) return true; // 额外做一次引用对比
        if (other instanceof ItemStackKey otherKey) // 会顺带处理null
        {
            if (this.item != otherKey.item) return false;

            // 确保两侧都有规范化后的字节快照
            this.ensureByte();
            otherKey.ensureByte();

            if (this.patchByte != null && this.patchByte.length > 0
                    && otherKey.patchByte != null && otherKey.patchByte.length > 0)
            {
                return Arrays.equals(this.patchByte, otherKey.patchByte);
            }

            // 回退：在 Provider 尚未就绪或异常时，退回到 patch 的值语义
            return Objects.equals(this.patch, otherKey.patch);
        }
        return false;
    }
```

```java
// api/storage/key/impl/ItemStackKey.java:517-544（节选）
    private void ensureByte()
    {
        // 优先使用最优提供者（Server→Connection→Level→Builtin）
        HolderLookup.Provider current = null;
        try { current = RegistryAccessResolver.resolve(); } catch (Throwable ignored) {}
        ...
            HolderLookup.Provider use = (current != null) ? current : RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            byte[] out = DataComponentPatchHelper.toCanonicalBytes(this.patch, use);
```

`hashCode` 与之配套（`:502-515`），同样优先用 `patchByte`，失败时回退 `patch.hashCode()`。

### 6.3 `equals` / `hashCode`

```java
// api/storage/key/impl/ItemStackKey.java:491-500
    @Override
    public boolean equals(Object other)
    {
        if (this == other) return true;
        if (other instanceof ItemStackKey otherKey) // 此处会顺带处理null
        {
            return this.isSameTypeSameComponents(otherKey);
        }
        return false;
    }
```

**`equals` == 物品 + 组件完全一致。** 数量不参与。

对比 `isSame`（模糊匹配，**只看物品，忽略组件**）：

```java
// api/storage/key/impl/ItemStackKey.java:333-342
    @Override
    public boolean isSame(IStackKey<?> other)
    {
        if (this == other) return true; // 额外做一次引用对比
        if (other instanceof ItemStackKey otherItemStackKey) // 顺手处理空
        {
            return this.item == otherItemStackKey.item; // 直接比对
        }
        return false;
    }
```

### 6.4 `fromStackObject(Object)` 行为

```java
// api/storage/key/impl/ItemStackKey.java:193-199
    @Override
    public @Nullable KeyAmount fromStackObject(Object stack)
    {
        if (stack instanceof ItemStack itemStack)
            return new KeyAmount(new ItemStackKey(itemStack), itemStack.getCount());
        return null;
    }
```

- 只有 `ItemStack` 才返回；**其他任何类型（包括 `null`）返回 `null`**。
- 数量直接取 `itemStack.getCount()`。
- **不做归一化**：即便传入 `ItemStack.EMPTY`，也会构造出 `ItemStackKey(Items.AIR, ...)`（等价于 `EMPTY`，因为 `isEmpty()` 判 `item == Items.AIR`，`:267-271`）。

对比 `fromSourceObject(Object, DataComponentPatch)`（`:201-211`），它会把 patch 清洗一遍（`PatchedDataComponentMap.fromPatch`）。

### 6.5 对 EMC 折算的含义

- 「同一种物品」= **item + 全部 DataComponentPatch**。所以：附魔不同的钻石剑是**两种**资源；耐久不同的工具是**两种**资源；伤害值/自定义名/NBT 不同也是不同资源。
- 若 EMC 折算想按「忽略组件」的粒度（例如把所有附魔书都算同一个 EMC 值），需要在 handler 里**自行做模糊匹配归一化**——`ItemStackKey` 本身不提供。
- 网络里**没有存在的 key 就不会有槽位**；折算成 EMC 后，原来的 `ItemStackKey` 槽位会被释放（`REMOVE_ON_ZERO`），`slotMaxSize` 压力下降。

---

## 7. `UnifiedStorageBeforeExtractHandler` 能力评估

### 7.1 接口能力

```java
// api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java:23-49
    @FunctionalInterface
    public interface BeforeExtractHandler
    {
        @NotNull
        BeforeExtractHandlerReturnInfo beforeExtract(
                @NotNull KeyAmount originalExtract,
                @NotNull KeyAmount tryExtract,
                @Nullable DimensionsNet net
        );
    }

    public record BeforeExtractHandlerReturnInfo(@NotNull KeyAmount beforeExtract, boolean cancel)
    {
    }

    public static void addHandler(BeforeExtractHandler handler)
    {
        handlers.add(handler);
    }
```

链式语义与插入侧完全对称（`:73-90`），**可以替换 key 与 amount，也可以 cancel**。

调用点（唯一）：

```java
// api/dimensionnet/UnifiedStorage.java:117-133
    @Override
    public @NotNull KeyAmount extract(IStackKey<?> key, long amount, boolean simulate, boolean fuzzy)
    {
        KeyAmount input = new KeyAmount(Objects.requireNonNullElse(key, EmptyStackKey.INSTANCE), amount);

        var info = UnifiedStorageBeforeExtractHandler.onBeforeExtract(input, net);

        if (info.cancel())
            return new KeyAmount(input.key(), 0);

        KeyAmount adjusted = info.beforeExtract();

        if (adjusted.isEmpty())
            return adjusted;

        return super.extract(adjusted.key(), adjusted.amount(), simulate, fuzzy);
    }
```

### 7.2 ⚠️ 覆盖面缺口：`extract(int slot, ...)` 与 `extract(TagKey, ...)`

`UnifiedStorage` 覆写了 `extract(int slot, ...)`，但**内部转调的是带钩子的重载**：

```java
// api/dimensionnet/UnifiedStorage.java:104-115
    @Override
    public @NotNull KeyAmount extract(int slot, long amount, boolean simulate)
    {
        if (amount <= 0L)
            return new KeyAmount(EmptyStackKey.INSTANCE, 0L);

        KeyAmount stack = getStackBySlot(slot);
        if (stack.isEmpty())
            return stack;

        return extract(stack.key(), amount, simulate, false);   // ← 走带钩子的重载
    }
```

```java
// api/dimensionnet/UnifiedStorage.java:135-143
    @Override
    public @NotNull KeyAmount extract(TagKey<?> tagKey, long amount, boolean simulate)
    {
        var key = tag2stackMap.get(tagKey).stream().findFirst();
        if (key.isEmpty() || amount <= 0L)
            return new KeyAmount(EmptyStackKey.INSTANCE, 0L);

        return extract(key.get(), amount, simulate, false);     // ← 同样走带钩子的重载
    }
```

**结论：三种 `extract` 重载最终都会触发 `onBeforeExtract`。** 这一点比插入侧更干净（插入侧 `insert(int slot, ...)` 在基类被覆写为转 `insert(key, ...)`，也是全走钩子）。

**唯一缺口**：`abstractUnorderedStackHandler.extract(...)` 被直接以 `AbstractUnorderedStackHandler` 静态类型调用时不会走钩子（例如 `DimensionsNetMenu.storage` 声明为 `AbstractUnorderedStackHandler`，但服务端指向的实例就是 `UnifiedStorage`，仍是虚分发，所以**没问题**）。真正不经钩子的只有 §3.2 的 `setAmountByKey` / `setStackDirectly`，与 extract 无关。

### 7.3 对「用 EMC 兑换出物品」是否够用

**够用，但有三个必须自己解决的点：**

1. **`cancel` 的返回语义不同**：插入侧 cancel 时返回**原始 `input`**（`UnifiedStorage.java:94`），提取侧 cancel 时返回 **`new KeyAmount(input.key(), 0)`**（`:125`）——即「什么都没取到，且 key 保留」。这对调用方是「提取失败」的正常表现，不会报错。
2. **它不是事务**：`super.extract(adjusted...)` 之后的扣除是原子的（`extractByKey`，`AbstractUnorderedStackHandler.java:640-677`），但 **handler 内没有「先校验 EMC 够不够再产出物品」的两阶段机制**。若 handler 把「物品 key」改写成「EMC key」，实际扣除的是 EMC；但**如果请求的 EMC 量超过库存，会静默地只扣除库存量并返回不足的物品数量**，需要在 handler 里用 `net.getUnifiedStorage().getStackByKey(EMC_KEY).amount()` 自己先算够不够，不够就 `cancel` 或按比例缩减 `amount`。
3. **递归风险**：handler 内部若调用 `net.getUnifiedStorage().extract(...)` 会**再次进入钩子**（同一个静态 handler 列表），必须靠 `originalExtract` 判断或加线程/深度标记来防死循环。

---

## 8. `LongType` / `LongStackKey` / `EnergyStackKey` 差异与模板选择

### 8.1 `LongType`：纯数值堆叠的基类

```java
// api/longtype/LongType.java:8-18
/**
 * 任何纯数值型堆叠的包装类
 */
public abstract class LongType<T>
{
    protected static <C extends LongType<C>> Codec<C> createCodec(Function<Long, C> constructor)
    {
        return Codec.LONG.xmap(constructor, LongType::getStackCount);
    }

    protected long stackCount;
```

```java
// api/longtype/LongType.java:40-43
    public boolean isEmpty()
    {
        return stackCount <= 0;
    }
```

成员：`getStackCount` / `setStackCount` / `grow` / `shrink` / `isEmpty` / `getName()`（抽象）/ `getEmpty()`（抽象）/ `copy()`（抽象）/ `copyWithAmount`（抽象）/ `isSame`。
**`equals`/`hashCode` 只按 `getClass()` 比较**（`:62-73`）——**数值不参与相等性**。这是刻意的：`LongType` 实例只是「值载体」，语义身份由 `LongStackKey` 单例承担。

`EnergyType` 是它的最小实现：

```java
// api/longtype/EnergyType.java:6-20
public final class EnergyType extends LongType<EnergyType>
{
    public static final Codec<EnergyType> CODEC = createCodec(EnergyType::new);

    public EnergyType(long amount)
    {
        this.stackCount = amount;
    }

    @Override
    public Component getName()
    {
        return Component.translatable("types.beyonddimensions.energytype.name");
    }
```

### 8.2 `LongStackKey`：单例化数值资源 Key

```java
// api/storage/key/impl/LongStackKey.java:10-36
public abstract class LongStackKey<T extends LongType<T>> implements IStackKey<T>
{
    private static final long CUSTOM_MAX_STACK_SIZE = Long.MAX_VALUE; // 自定义堆叠大小

    public abstract ResourceLocation getTypeID();

    protected T stack;

    protected int hashCodeCache = 0; // 哈希码缓存

    @Override
    public ResourceLocation getTypeId()
    {
        return getTypeID();
    }

    @Override
    public T getReadOnlyStack()
    {
        this.stack.setStackCount(1);
        return this.stack;
    }
```

关键语义（**整个类型只有一个 Key 实例**）：

```java
// api/storage/key/impl/LongStackKey.java:52-59
    /**
     * 不可能为空键
     */
    @Override
    public boolean isEmpty()
    {
        return false;
    }
```

```java
// api/storage/key/impl/LongStackKey.java:85-104
    @Override
    public boolean isSame(IStackKey<?> other)
    {
        return other != null && Objects.equals(other.getTypeId(), this.getTypeId());
    }

    @Override
    public boolean isSameTypeSameComponents(IStackKey<?> other)
    {
        // LongType Key 无组件，语义与 isSame 相同
        return isSame(other);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (!(o instanceof IStackKey<?> k)) return false;
        return Objects.equals(k.getTypeId(), this.getTypeId());
    }
```

→ **`equals` / `isSame` / `isSameTypeSameComponents` 三者全部退化为「typeId 相同」。** 也就是说 `LongStackKey` 家族**一个 typeId 对应网络里的一格资源**，这就是「整个网络只有一个池」的实现方式。

`getVanillaMaxStackSize()` 被覆写为 `Long.MAX_VALUE`（`:73-77`），意即「在接口方块中的一次性最大容量不受限」。

### 8.3 `EnergyStackKey`：是的，它就是「只有一个 long 数值的网络能量池」

```java
// api/storage/key/impl/EnergyStackKey.java:22-31
public class EnergyStackKey extends LongStackKey<EnergyType>
{

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(BDConstants.MODID, "stack_type/energy");

    /**
     * 唯一实例（不区分空/非空）
     */
    public static final EnergyStackKey INSTANCE = new EnergyStackKey();
```

```java
// api/storage/key/impl/EnergyStackKey.java:62-65
    private EnergyStackKey()
    {
        this.stack = new EnergyType(0);
    }
```

序列化**完全不写任何字段**，反序列化直接返回单例：

```java
// api/storage/key/impl/EnergyStackKey.java:33-58（CODEC）
    /**
     * 无字段的新格式：decode 直接返回单例，encode 不写任何键
     */
    public static final MapCodec<EnergyStackKey> TYPE_CODEC = new MapCodec<>()
    {
        @Override
        public <T> DataResult<EnergyStackKey> decode(...) { return DataResult.success(EnergyStackKey.INSTANCE); }

        @Override
        public <T> RecordBuilder<T> encode(...) { return prefix; } // 不写任何字段
        ...
    };
```

```java
// api/storage/key/impl/EnergyStackKey.java:161-172
    @Override
    public @NotNull CompoundTag serializeNBT(HolderLookup.Provider levelRegistryAccess)
    {
        return new CompoundTag();
    }

    @Override
    public @NotNull EnergyStackKey deserializeNBT(CompoundTag nbt, HolderLookup.Provider levelRegistryAccess)
    {
        // 无论旧/新，都统一成单例 Key（旧数据里的 Amount 属于值层，不参与 Key）
        return INSTANCE;
    }
```

**答案：是。** `EnergyStackKey` 是纯粹的「只有一个 long 数值」的资源类型——整个 `DimensionsNet` 里只有**一格槽位**存放能量，容量上限由 `slotCapacity` 决定，数值由 `storage.get(EnergyStackKey.INSTANCE)` 决定。全网络的能量池（`NetEnergyMenu.java:149`、`NetEnergyPathwayBlockEntity.java:120-122`、`EnergyUnifiedStorageHandler.java:20-32`）都是这一个 key。

### 8.4 差异对照表

| 维度 | `LongType` | `LongStackKey<T>` | `EnergyStackKey` |
| --- | --- | --- | --- |
| 角色 | 值载体（`long stackCount`） | Key 抽象基类（单例化） | 具体类型实现 |
| `equals` | 按 `getClass()` | 按 `typeId` | 继承 → 按 `typeId` |
| `isEmpty` | `stackCount <= 0` | 恒 `false` | 恒 `false`（继承） |
| 组件/NBT | 无 | 无 | 无（`serializeNBT` 返回空 `CompoundTag`） |
| 网络传输 | 仅值 | 仅 typeId | 仅 typeId，payload 空 |
| 槽位语义 | — | 每 typeId 一格 | 全网络一格 |
| 最大堆叠 | — | `Long.MAX_VALUE` | `getVanillaMaxStackSize()=1000000` |

### 8.5 模板选择结论

**新增「EMC」资源 Key 最接近的模板是 `EnergyStackKey`（或 `ManaStackKey`）。**

`ManaStackKey` 是同一模式的范例（`integration/module/botania/storage/ManaStackKey.java`），且它连同 `ManaType`、`ManaStackKeyRender`、`ManaUnifiedStorageHandler`、`ManaStackTypedHandler`、`ManaHandlerWrapper` 一起构成了一个**完整的、可照抄的「新增一种简单数值资源」样板**，比 `EnergyStackKey` 更值得作为参考（因为 Energy 是内建类型，注册散落在 `BeyondDimensions.commonSetup` 里，而 Mana 是一个独立模块，注册集中在 `BotaniaModule.onCommonSetup`）。

`ManaType`（`integration/module/botania/storage/ManaType.java`）结构与 `EnergyType` 相同，唯一的差异是 `getName()` 的翻译键。

> ⚠️ 不建议把 EMC 直接做成 `EnergyStackKey` 的别名：`EnergyStackKey` 的 typeId 被 `CapabilityHelper.BlockCapabilityMap` 绑定到 `Capabilities.EnergyStorage.BLOCK`、被 `AEHelper`/`RSHelper` 绑定到 FE 能量类型（见 §9），复用会污染能量语义。

---

## 9. 新增一种资源类型的最小注册清单

### 9.1 ⚠️ 首先澄清：`UnifiedStorage.typedHandlerMap` 在本版本中不存在

搜索 `typedHandlerMap|TypedHandlerMap|typedHandler`（全仓库）**0 命中**。

本版本（0.7.30）中，本应叫「typedHandlerMap」的东西被拆成 `CapabilityHelper` 的两个 Map：

```java
// api/capability/helper/CapabilityHelper.java:44-52
    /**
     * 存储类型 -> 分化包装
     */
    public static final Map<ResourceLocation, USHandler> USHandlerMap = new HashMap<>();

    /**
     * 存储类型 -> 分化包装
     */
    public static final Map<ResourceLocation, CommonHandler> CommonHandlerMap = new HashMap<>();
```

`UnifiedStorage` 所在的网络存储确实**没有**按类型的 handler 分发表——`UnifiedStorage` 是单一 Map 承载所有类型（`AbstractUnorderedStackHandler.java:53`），类型区分靠 `IStackKey.getTypeId()` 与 `type2buckets`（`:57`）。**「新增资源类型需要往 typedHandlerMap 注册」这一假设在本版本不成立。**

### 9.2 已存在的注册点（全量清单）

| # | 注册点 | 位置 | 写入什么 | 是否必需 |
| --- | --- | --- | --- | --- |
| 1 | `StackKeyRegistry.registerType(IStackKey)` | `api/storage/key/StackKeyRegistry.java:14-21` | `TYPES: Map<ResourceLocation, IStackKey<?>>` | **必需**（否则 Codec/StreamCodec 分发失败抛异常） |
| 2 | `CapabilityHelper.BlockCapabilityMap.put(...)` | `api/capability/helper/CapabilityHelper.java:37` | typeId → `BlockCapability<?, Direction>` | 可选（仅当要让方块暴露该能力） |
| 3 | `CapabilityHelper.ItemCapabilityMap.put(...)` | `api/capability/helper/CapabilityHelper.java:42` | typeId → `ItemCapability<?, Void>` | 可选（仅当要让物品暴露该能力） |
| 4 | `CapabilityHelper.registerUSHandler(key, Fn)` | `api/capability/helper/CapabilityHelper.java:54-66` | typeId → `USHandlerMap` | 与 2/3 配套（能被外部读写必须注册） |
| 5 | `CapabilityHelper.registerStackTypedHandler(key, Fn)` | `api/capability/helper/CapabilityHelper.java:68-80` | typeId → `CommonHandlerMap` | 同上 |
| 6 | `StackHandlerWrapperHelper.stackWrappers.put(...)` | `api/capability/helper/wrapper/StackHandlerWrapperHelper.java:15` | typeId → 外部 handler 包装器工厂 | 可选（仅当要消费外部模组的同名容器） |
| 7 | `AEHelper.AEKEY_TO_STACK_TYPE_MAP` / `ISTACK_TO_AEKEY_MAP` | `integration/module/ae2/AEHelper.java:22-23`（默认项在 `:25-32`） | AE2 互转 | 可选（AE2 集成） |
| 8 | `RSHelper.RSKEY_TO_STACK_TYPE_MAP` | `integration/module/rs/RSHelper.java:17` | RS 互转 | 可选（RS 集成） |
| 9 | `ManaStackKey.getRender()` → `IStackRender` 实现 | 见 §10 | 客户端渲染器 | 客户端必需 |

**注册时机**：内建类型在 `BeyondDimensions.commonSetup`（`FMLCommonSetupEvent`）里注册：

```java
// BeyondDimensions.java:61-93
    private void commonSetup(final FMLCommonSetupEvent event)
    {

        // 注册堆叠类型，使得网络能够存储相关堆叠
        StackKeyRegistry.registerType(EmptyStackKey.INSTANCE); // 全空堆叠，用于避免使用null
        StackKeyRegistry.registerType(ItemStackKey.EMPTY);
        StackKeyRegistry.registerType(FluidStackKey.EMPTY);
        StackKeyRegistry.registerType(EnergyStackKey.INSTANCE);

        // 注册方块能力类型，用于动态为方块注册能力
        CapabilityHelper.BlockCapabilityMap.put(ItemStackKey.ID, Capabilities.ItemHandler.BLOCK);
        CapabilityHelper.BlockCapabilityMap.put(FluidStackKey.ID, Capabilities.FluidHandler.BLOCK);
        CapabilityHelper.BlockCapabilityMap.put(EnergyStackKey.ID, Capabilities.EnergyStorage.BLOCK);
        // 注册物品能力，用于动态操作
        CapabilityHelper.ItemCapabilityMap.put(ItemStackKey.ID, Capabilities.ItemHandler.ITEM);
        CapabilityHelper.ItemCapabilityMap.put(FluidStackKey.ID, Capabilities.FluidHandler.ITEM);
        CapabilityHelper.ItemCapabilityMap.put(EnergyStackKey.ID, Capabilities.EnergyStorage.ITEM);

        // 注册网络能力，使得网络通道能暴露对应存储能力
        CapabilityHelper.registerUSHandler(ItemStackKey.EMPTY, ItemUnifiedStorageHandler::new);
        CapabilityHelper.registerUSHandler(FluidStackKey.EMPTY, FluidUnifiedStorageHandler::new);
        CapabilityHelper.registerUSHandler(EnergyStackKey.INSTANCE, EnergyUnifiedStorageHandler::new);

        // 注册存储分化包装
        CapabilityHelper.registerStackTypedHandler(ItemStackKey.EMPTY, ItemStackTypedHandler::new);
        CapabilityHelper.registerStackTypedHandler(FluidStackKey.EMPTY, FluidStackTypedHandler::new);
        CapabilityHelper.registerStackTypedHandler(EnergyStackKey.INSTANCE, EnergyStackTypedHandler::new);

        // 注册堆叠处理包装，用于动态包装来自其他模组的handler (如原版的IItemHandler)
        StackHandlerWrapperHelper.stackWrappers.put(ItemStackKey.ID, ItemHandlerWrapper::new);
        StackHandlerWrapperHelper.stackWrappers.put(FluidStackKey.ID, FluidHandlerWrapper::new);
        StackHandlerWrapperHelper.stackWrappers.put(EnergyStackKey.ID, EnergyHandlerWrapper::new);
    }
```

**集成模块是集中注册的范例**（`BotaniaModule`，最贴近 EMC 的场景）：

```java
// integration/module/botania/BotaniaModule.java:60-72
    @Override
    public void onCommonSetup(FMLCommonSetupEvent event)
    {
        StackKeyRegistry.registerType(ManaStackKey.INSTANCE);
        CapabilityHelper.BlockCapabilityMap.put(ManaStackKey.ID, BotaniaCapabilitiesHelper.getBlockApiLookupById(ManaReceiver.LOOKUP));
        CapabilityHelper.ItemCapabilityMap.put(ManaStackKey.ID, BotaniaCapabilitiesHelper.getItemApiLookupById(ManaItem.LOOKUP));
        CapabilityHelper.registerUSHandler(ManaStackKey.INSTANCE, ManaUnifiedStorageHandler::new);
        CapabilityHelper.registerStackTypedHandler(ManaStackKey.INSTANCE, ManaStackTypedHandler::new);
        StackHandlerWrapperHelper.stackWrappers.put(ManaStackKey.ID, ManaHandlerWrapper::new);

        // 能力交互黑名单（魔力手镜）
        BDBotaniaPlugin.registerItemCapBlackList();
    }
```

`ArsModule.java:55`、`IFSModule.java:49`、`MekModule.java:34` 是同样的一行 `StackKeyRegistry.registerType(...)`。

**集成模块的注册规则**（供参考）：`@BDIntegrationModule(modId = ...)` + `IIntegrationModule`，由 `IntegrationManager.bootstrapCommon` 分发（`BeyondDimensions.java:58`）。附属模组**不需要**走这套机制，直接在 `FMLCommonSetupEvent` 里注册即可（`StackKeyRegistry`、`CapabilityHelper` 都是 public 静态容器）。

### 9.3 EMC 资源类型的**最小注册清单**

假设 EMC 只用于「网络内部数值池 + 附属模组自己读写」，不需要暴露成方块/物品能力：

| 步骤 | 位置 | 说明 |
| --- | --- | --- |
| **必需 1** | 新建 `EmcType extends LongType<EmcType>` | 参照 `api/longtype/EnergyType.java` 或 `integration/module/botania/storage/ManaType.java` |
| **必需 2** | 新建 `EmcStackKey extends LongStackKey<EmcType>`，带 `public static final ResourceLocation ID`、`public static final EmcStackKey INSTANCE`、空字段 `MapCodec TYPE_CODEC`、`getEmpty()` 返回 `INSTANCE`、`getRender()` 返回渲染器 | 参照 `api/storage/key/impl/EnergyStackKey.java` 全文，或 `integration/module/botania/storage/ManaStackKey.java` 全文 |
| **必需 3** | `StackKeyRegistry.registerType(EmcStackKey.INSTANCE)`（`FMLCommonSetupEvent`，必须在任何网络读写之前） | 否则 `IStackKey.CODEC` 分发抛 `IllegalArgumentException`（`StackKeyRegistry.java:29`），**且是先抛异常后崩档**——`AbstractUnorderedStackHandler.deserializeNBT` 有 `try/catch`（`:889-899`）会记 warn 后**丢弃该条目**，即静默丢数据 |
| **必需 4** | `EmcStackKey.getRender()` 返回一个 `IStackRender` 实现（客户端） | 见 §10 |
| **推荐 5** | `CapabilityHelper.registerUSHandler(EmcStackKey.INSTANCE, ...)` + `CapabilityHelper.registerStackTypedHandler(EmcStackKey.INSTANCE, ...)` | 若希望网络通道方块能对外暴露 EMC |
| **推荐 6** | `CapabilityHelper.BlockCapabilityMap` / `ItemCapabilityMap` 绑定 | 仅当存在对应的 NeoForge 能力 |
| **可选 7** | `AEHelper.AEKEY_TO_STACK_TYPE_MAP`（`integration/module/ae2/AEHelper.java:23`）/ `RSHelper.RSKEY_TO_STACK_TYPE_MAP`（`integration/module/rs/RSHelper.java:17`） | 仅当要让 AE2/RS 直接看到 EMC 资源；**对「存物品换 EMC」的需求不是必需的** |

> ⚠️ **注意 `registerType` 的重复注册会抛异常**：`StackKeyRegistry.registerType` 在重复 typeId 时抛 `IllegalStateException`（`StackKeyRegistry.java:16-19`），`CapabilityHelper.registerUSHandler` / `registerStackTypedHandler` 重复时抛 `RuntimeException`（`CapabilityHelper.java:56-57,63-64,70-71,77-78`）。若两个附属模组都提供 EMC，需要 try/catch 或先检查（`StackKeyRegistry` 没有 `contains` 方法，只能靠 `getAllTypes()` 遍历，`:34-37`）。

---

## 10. 客户端渲染侧要求

### 10.1 `IStackRender` 接口

```java
// api/storage/key/IStackRender.java:17-59
/**
 * 用与StackKey的渲染器，实现一般可以使用单例模式
 */
public interface IStackRender
{
    /**
     * UI渲染，即绘制当前资源的图标
     * <p>
     * 必须以注解标注为仅客户端
     */
    @OnlyIn(Dist.CLIENT)
    void render(GuiGraphics gui, IStackKey<?> key, int x, int y);

    /**
     * 将数量绘制到屏幕上
     */
    void renderAmount(GuiGraphics gui, long amount, int x, int y);

    /**
     * 对当前存储数量进行格式化
     */
    String getCountText(long count);

    /**
     * 获取资源名称
     */
    Component getDisplayName(IStackKey<?> key);

    /**
     * 获取资源的工具提示
     */
    List<Component> getTooltipLines(IStackKey<?> key, long amount, Item.TooltipContext tooltipContext, @Nullable Player player, TooltipFlag tooltipFlag);

    Optional<TooltipComponent> getTooltipImage(IStackKey<?> key);

    /**
     * 绘制工具提示，必须要标记为仅客户端
     */
    @OnlyIn(Dist.CLIENT)
    void renderTooltip(GuiGraphics gui, Font font, IStackKey<?> key, long amount, int mouseX, int mouseY);
}
```

获取入口是 `IStackKey.getRender()`（`api/storage/key/IStackKey.java:194`），注释明确写「仅在客户端调用」。

### 10.2 `EnergyStackKeyRender` 的实现要点

**`render`：画一个纯色占位图标（拿水的静态贴图 + 绿色着色）**

```java
// api/storage/key/render/EnergyStackKeyRender.java:35-56
    @Override
    public void render(GuiGraphics gui, IStackKey<?> key, int x, int y)
    {
        var pose = gui.pose();
        pose.pushPose();

        // 占位图标：用水的静态贴图 + 绿色
        ResourceLocation still = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                .of(net.minecraft.world.level.material.Fluids.WATER)
                .getStillTexture();
        TextureAtlasSprite sprite = still == null ? null
                : Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);

        if (sprite != null && sprite.atlasLocation() != MissingTextureAtlasSprite.getLocation())
        {
            int tint = 0x50F18E; // 能量绿色
            IngredientRenderer
                    .drawTiledSprite(gui, 16, 16, tint, 16, sprite, x, y);
        }

        pose.popPose();
    }
```

**`renderAmount`：标准右下角数量绘制（0.666 缩放 + z=200）**

```java
// api/storage/key/render/EnergyStackKeyRender.java:58-77（节选）
        float scale = 0.666f;
        var pose = gui.pose();
        pose.pushPose();
        pose.translate(0, 0, 200);
        pose.scale(scale, scale, scale);
        RenderSystem.disableBlend();

        int w = Minecraft.getInstance().font.width(text);
        final int X = (int) ((x - 1 + 16.0f + 2.0f - w * 0.666f) / 0.666f);
        final int Y = (int) ((y - 1 + 16.0f - 5.0f * 0.666f) / 0.666f);
        gui.drawString(Minecraft.getInstance().font, text, X, Y, 0xFFFFFF);
```

**`getCountText` / `getDisplayName` / `getTooltipLines`**

```java
// api/storage/key/render/EnergyStackKeyRender.java:79-102
    @Override
    public String getCountText(long count)
    {
        if (count < 0) return "";
        return StringFormat.formatCount(count);
    }

    @Override
    public Component getDisplayName(IStackKey<?> key)
    {
        // 使用最小非空渲染栈的名称
        return EnergyStackKey.INSTANCE.getRenderStack().getName();
    }

    @Override
    public List<Component> getTooltipLines(IStackKey<?> key, long amount, Item.TooltipContext tooltipContext,
                                           @Nullable net.minecraft.world.entity.player.Player player,
                                           TooltipFlag tooltipFlag)
    {
        return List.of(
                getDisplayName(key),
                Component.translatable("istack.beyonddimensions.storage_num.long_type", amount)
        );
    }
```

`getTooltipImage` 返回 `Optional.empty()`（`:105-108`），`renderTooltip` 走原版 `gui.renderTooltip`（`:110-124`）。

### 10.3 对 EMC 渲染的要求小结

- **必须提供一个 `IStackRender` 单例**并在 `EmcStackKey.getRender()` 返回它——否则 UI 打开时会 `NullPointerException`（`AbstractStackTypedSlot` / `ClientNetStorage.buildSortedIndex` 会调用 `key.getRender().getDisplayName(key)`，见 `common/menu/widget/ClientNetStorage.java:200`）。
- 最省事的做法：**照抄 `EnergyStackKeyRender`**，只改 `tint` 颜色、`getDisplayName` 的翻译键和 `getTooltipLines` 的翻译键。
- 也可参考 `ManaStackKeyRender`（`integration/module/botania/storage/ManaStackKeyRender.java`），它是同样结构。
- `renderAmount` 的坐标魔法数字（`0.666f`、`z=200`、`x-1+16+2`）建议原样保留，以保证与其它资源对齐。
- `getRenderStack()` 由 `LongStackKey.getRenderStack()` 提供（`api/storage/key/impl/LongStackKey.java:33-38`），返回把数量置 1 的 `LongType` 实例。**注意它有副作用**：`this.stack.setStackCount(1)` 直接修改了单例的共享 `stack` 字段——因为 `LongStackKey` 是单例、`stack` 是共享可变对象，多线程/多 UI 下存在**竞态**（本模组自身已如此，属既有设计，附属模组无需修复但要知道）。

---

## 11. 对附属模组的实现建议

### 11.1 接入点排序（风险从低到高）

#### ★ 首选（风险最低）：`UnifiedStorageBeforeInsertHandler.addHandler`

**理由**：
- 它是**官方预留的公共 API**，本模组自身零调用（§3.1 B），无顺序冲突。
- 是**唯一**同时满足「覆盖全部物品进网路径」（§2.1–§2.4）与「不修改 `reference/` 任何文件」的接入点。
- 能在 `simulate` 阶段也被调用，天然支持「先试算再提交」的两阶段语义。
- 能拿到 `DimensionsNet net`，可查询/预写入 EMC。

**推荐骨架**（伪代码，仅为设计示意）：

```java
// 在 FMLCommonSetupEvent 中
UnifiedStorageBeforeInsertHandler.addHandler((original, current, net) -> {
    // 1) 必须容忍 net == null（UnifiedStorage.getEmpty()，§3.1）
    if (net == null) return new BeforeInsertHandlerReturnInfo(current, false);

    // 2) 只处理物品键；其它资源（流体/能量/EMC 自身）原样放行
    if (!(current.key() instanceof ItemStackKey itemKey)) 
        return new BeforeInsertHandlerReturnInfo(current, false);
    if (current.key().isEmpty())
        return new BeforeInsertHandlerReturnInfo(current, false);

    // 3) 查 EMC 值：以「忽略组件」的粒度查询（ItemStackKey.equals 含组件，见 §6）
    long emcPerItem = EmcValues.lookup(itemKey);   // 自己实现，建议按 item + 白名单组件
    if (emcPerItem <= 0L)
        return new BeforeInsertHandlerReturnInfo(current, false);   // 无 EMC → 原逻辑

    // 4) 原子性：先确认 EMC 能全部放下，否则整体 cancel（避免半折算）
    long totalEmc;
    try { totalEmc = Math.multiplyExact(emcPerItem, current.amount()); }
    catch (ArithmeticException e) { return new BeforeInsertHandlerReturnInfo(current, true); }

    var store = net.getUnifiedStorage();
    if (store.getStackByKey(EMC_KEY).amount() + totalEmc > store.getSlotCapacity(ANY_SLOT))
        return new BeforeInsertHandlerReturnInfo(current, false);  // 或 cancel，看产品决策

    // 5) 改写为 EMC 键
    return new BeforeInsertHandlerReturnInfo(
            new KeyAmount(EMC_KEY, totalEmc), false);
});
```

#### 次选（风险中）：包装 `DimensionsNet` 的事件 + 统一在 `UnifiedStorageBeforeExtractHandler` 里做兑换

**用于「用 EMC 换出物品」**。理由：`onBeforeExtract` 是唯一能在取出前替换资源的官方 API（§7.1），且三种 `extract` 重载都会触发（§7.2）。
必须自己处理：EMC 余额校验、按比例缩减、防递归（§7.3）。

#### 再次（风险中高）：`DimensionsNetEvent` 事件订阅

`api/event/dimensionnet/DimensionsNetEvent.java`、`NetedBlockEvent.java`、`NetedItemEvent.java` 提供网络级事件。**未确认**是否覆盖「单次插入」粒度——从 §2 的调用面看，插入是高频、逐次的操作，事件粒度大概率过粗且无法改写输入。**不建议作为主接入点**，但很适合作为「网络删除/合并时清理 EMC 缓存」的辅助。

#### 不推荐（风险高）：Mixin `AbstractUnorderedStackHandler` / `UnifiedStorage`

理由：
- `AbstractUnorderedStackHandler` 是**有序 `StackHandler` 与无序网络存储的公共基类**（`:32`），Mixin 进去会同时影响接口槽位、熔炉槽位等**所有容器**（§3.2 (5)），语义远超出「物品进维度网络」。
- 该文件很可能是构建产物中被优化的热点（有性能测试报告 `files/PerformanceTestReport/`），Mixin 风险牵连面大。
- 完全没有必要——`onBeforeInsert` 已经覆盖了全部路径。

#### 明确不要用的接入点

| 接入点 | 为什么不行 |
| --- | --- |
| 修改 `UnifiedStorage.java` | 题目要求只读 `reference/` |
| 修改 `StackKeyRegistry` 的 `TYPES` | 只能是「注册自己的类型」，不要试图改行为 |
| 在 `setAmountByKey` / `setStackDirectly` 上做拦截 | 这两个方法是**同步/外部直写**的通道（§3.2 (2)(3)(4)），在这里转换会导致客户端与服务端视图分裂 |

### 11.2 已知风险与必须处理的细节

**R1 — 读档时被意外折算（高危）**
`deserializeNBT` → `acceptEntry` → `insert`（`AbstractUnorderedStackHandler.java:989`）会触发 handler。若 handler 无条件把「物品键」换成 EMC，**玩家存档在加载瞬间就被改写**，且这是**不可逆**的静默数据迁移。
→ 缓解方案：在 handler 里检测「是否处于读档上下文」。可行手段（**未确认**，需实测）：
  - `net.getUnifiedStorage().getStorage()` 遍历期间不注册 handler；
  - 用 `DimensionsNet.load` 前后可观察的状态差（例如 `net.getId()` 尚未设置）；
  - 自己维护一个「初始化中」标记（订阅 `ServerAboutToStartEvent` / `ServerStartedEvent` 或 `DimensionsNetEvent`）。
  - 最稳妥：不依赖任何内部上下文，而是**只在 `original.amount > 0 && original.key() instanceof ItemStackKey` 且 EMC 值可查时才转换**，并接受「读档也会折算」这一行为（产品上可解释为「读档时批量折算」）。**两个方案的取舍必须让需求方明确拍板。**

**R2 — `simulate` 与真实写入必须行为一致**
`onBeforeInsert` 不区分 simulate，handler 必须保证：**同一个输入在 simulate / real 两次调用中返回同样的改写结果**（否则 `NetHopperBlockEntity.java:117/121`、`DisplacedStackTypedSlot.java:102/106` 这类「先模拟后提交」的代码会判断失真，可能出现「模拟说能放下、实际放不下」而丢物品）。
→ 不要在 handler 里读 `simulate`（也读不到），改写逻辑必须是纯函数（除 EMC 值的查询）。

**R3 — 大量 `Math.min(..., key.getVanillaMaxStackSize())` 会被 EMC 键的 `getVanillaMaxStackSize()` 影响**
多处路径会用 `key.getVanillaMaxStackSize()` 限制单次操作量（例如 `DisplacedStackTypedSlot.java:100`、`OrderedStackTypedSlot.java:105`、`NetInterfaceAccess.java:125`）。
`LongStackKey.getVanillaMaxStackSize()` 默认是 `Long.MAX_VALUE`（`api/storage/key/impl/LongStackKey.java:74-77`），而 `EnergyStackKey` 覆写为 `1000000`（`EnergyStackKey.java:90-93`）。
→ EMC 键应显式覆写 `getVanillaMaxStackSize()`（建议 `Long.MAX_VALUE` 或一个很大的值），否则若返回小值（例如继承 `EnergyStackKey` 风格的 `1000000`）会**限制兑换/存入的批量规模**，表现为「一次只能换 1000000 EMC」。

**R4 — `slotMaxSize` 被消耗**
EMC 键在网络里占**一格**（§4.1）。如果网络恰好已满（`slotIndex.size() >= slotMaxSize`），`super.insert` 会返回全量余量（`AbstractUnorderedStackHandler.java:541-544`），即**物品全部退回**。
→ handler 应在改写前用 `net.getUnifiedStorage().hasStack(EMC_KEY) || !isFullSlotsSize()` 预判（`isFullSlotsSize()` 见 `:1007-1010`），否则会出现「物品被折算掉一半、EMC 却塞不进去」的部分失败。

**R5 — `slotCapacity` 是单个 key 的上限**
EMC 池上限 = 网络的 `slotCapacity`（`AbstractUnorderedStackHandler.java:546-548`）。默认新建网络是 `Long.MAX_VALUE`（`NetCreater.java:62`），但管理员可通过命令改小（`common/command/ServerCommands.java:313`）。
→ 兑换时若 EMC 需求超过 `slotCapacity - current`，`super.insert` 会截断（`actual = Math.min(room, add)`，`:550`），`UnifiedStorage.insert` 把**截断后的余量**返回给调用方。
→ **handler 无法阻止截断**（改写发生在 `super.insert` 之前），只能在改写前预判，超限时 `cancel`。

**R6 — 物质压缩球递归（§3.2 (8)）**
球内每个子堆叠都会各自触发一次 `onBeforeInsert`。
→ 好处：球内物品**会自动折算**（符合直觉）。
→ 风险：handler 必须**幂等**；如果产品要求「压缩球整体不折算」，必须在 handler 里检测 `itemKey.getSource() == BDItems.MATTER_COMPRESS_BALL.get()` 并 cancel —— 但这需要依赖 BD 的内部类 `BDItems`（`common/init/BDItems.java`），编译期耦合较深。

**R7 — `ItemStackKey.equals` 含全部组件（§6）**
「同一种物品」= item + DataComponentPatch 全等。若 EMC 表是按「物品」登记的（例如所有附魔书同一个 EMC），需要在 handler 里**自行做组件剥离归一化**再查表，并想清楚「附魔书含附魔时应查哪个 EMC」。
→ 建议：查表时先用完整 `ItemStackKey`（保留精确语义），miss 后再退化为「按 `getSource()`（即 `Item`）」查询。

**R8 — `KeyAmount` 允许 `amount <= 0`（§5）**
handler 可能收到 `amount == 0` 的输入（虽然 `onBeforeInsert` 会在 `tryInsert.isEmpty()` 时提前返回，`UnifiedStorageBeforeInsertHandler.java:66-67`）。但 `cancel=true` 时 `UnifiedStorage.insert` 返回的是原始 `input` 而**不是** `adjusted`（`UnifiedStorage.java:93-94`），所以 handler **不能靠「返回一个空 KeyAmount」来表示「什么都不做」**——必须显式 `cancel=false` + 原样返回 `current`。

**R9 — 静态 handler 列表的注册时机与线程安全（§3.1）**
`handlers` 是 `private static final List`（`ArrayList`，非线程安全），`addHandler` 无同步。
→ 必须在 `FMLCommonSetupEvent`（或更早的 mod 构造期）一次性注册。**不要在 `ServerStartingEvent` 之后动态注册**——服务器主线程此时可能在处理插入，`ArrayList.add` 与迭代并发会产生不可预期结果。

**R10 — `net == null`**
`UnifiedStorage.getEmpty()` 的实例 `net == null`（`api/dimensionnet/UnifiedStorage.java:45-67`），且它的 `insert` 未被覆写，所以 handler 会以 `net == null` 被调用。
→ 第一行就要 `if (net == null) return ...current, false`。

**R11 — 提取侧的 `cancel` 语义与 `simulate`**
`UnifiedStorage.extract` 的 cancel 分支返回 `new KeyAmount(input.key(), 0)`（`UnifiedStorage.java:125`）——「什么都没取到，但 key 保留」。调用方普遍用 `isEmpty()` 判断（`KeyAmount.java:206` → `amount <= 0` 为真），因此 cancel 会被正常识别为「提取失败」，不会崩。
→ 但**必须处理**：只提取 EMC 但物品产出为 0 时，下游可能误认为「该物品不存在于网络」，从而反复重试。建议取不到 EMC 时返回 `cancel=true` 而不是返回一个 `amount` 很小的结果。

**R12 — 是否需要注册自定义 `IStackRender`**
必需（§10.3）。遗漏会在打开维度网络 UI 时 `NullPointerException`。

### 11.3 推荐的落地顺序

1. **第一步：只做插入侧 `addHandler`，只处理 `ItemStackKey`，先不接入取回。**
   验证全部路径：GUI 点击存入、背包 shift 快转、批量转移、`PutHandItemToNetPacket`、接口 `transferToNet`、漏斗、泵、AE2/RS 总线。用日志打印 `original` / `current` / `net.getId()` 观察触发情况。
2. **第二步：为 EMC 建 `EmcType` + `EmcStackKey` + `EmcStackKeyRender`，注册 `StackKeyRegistry.registerType`。**
   （此时可以先不注册能力，纯粹作为网络内部的数值池。）
3. **第三步：在插入 handler 中完成折算，并处理 R1（读档上下文）、R2（幂等）、R4/R5（容量预判）。**
4. **第四步：加 `UnifiedStorageBeforeExtractHandler.addHandler` 实现 EMC → 物品兑换，处理 R11 与递归防护。**
5. **第五步（可选）：注册 `CapabilityHelper` / `AEHelper` / `RSHelper`，让 EMC 能被其它模组直接读写（见 §9.3 步骤 5–7）。**

---

## 附录 A：本次调研使用到的关键搜索

| 关键词 | 命中 |
| --- | --- |
| `\.insert\(\|insertItem\|\.extract\(` | 217 处（见 §2 分类整理） |
| `typedHandlerMap` | **0 处** → 本版本不存在该类/字段（§9.1） |
| `addHandler\(\|onBeforeInsert\|onBeforeExtract` | 6 处，全为定义或 `UnifiedStorage` 内调用，**无任何注册调用**（§3.1） |
| `EMC\|emc\|projecte\|ProjectE` | **0 处** → 本模组无任何 EMC 相关实现，完全从零开始 |
| `registerType\(` | `BeyondDimensions.java:65-68`（内建 4 种）+ 各集成模块（§9.2） |
| `setStackDirectly\|setAmountByKey` | 绕过点，见 §3.2 (2)(3)(4) |

## 附录 B：逐条问题的答案索引

| 问题 | 对应章节 |
| --- | --- |
| 1. `UnifiedStorage.insert` 全调用链 | §2（含 §2.1 GUI、§2.2 背包、§2.3 外部方块、§2.4 其它） |
| 2. `addHandler` 触发范围 + 绕过路径 | §3（§3.1 覆盖，§3.2 绕过，§3.3 风险表） |
| 3. `AbstractUnorderedStackHandler` / `StackHandler` 结构 | §4（§4.1 无序、§4.2 有序） |
| 4. `KeyAmount` 语义 | §5 |
| 5. `ItemStackKey` 包装/equals/组件/`fromStackObject` | §6 |
| 6. `UnifiedStorageBeforeExtractHandler` 能力 | §7（含 §7.2 覆盖缺口、§7.3 够不够用） |
| 7. `LongType`/`LongStackKey`/`EnergyStackKey` 差异与模板 | §8（§8.5 模板结论） |
| 8. 自定义资源类型注册清单 | §9（§9.1 澄清 `typedHandlerMap` 不存在、§9.3 最小清单） |
| 9. 客户端渲染要求 | §10 |
| 实现建议 | §11（§11.1 接入点排序、§11.2 R1–R12 风险、§11.3 落地顺序） |
