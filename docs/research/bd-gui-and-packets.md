# Beyond Dimensions 存储界面与网络同步机制调研

> 调研对象：`reference/BeyondDimensions`（分支 `1.21.1`，模组版本 `0.7.30`，NeoForge 21.1.234，包名 `com.wintercogs.beyonddimensions`）
> 调研目的：为"在 BD 存储界面中显示 ProjectE 已学习物品虚拟条目、并用网络 EMC 兑换取出"这一附属模组目标，摸清界面/菜单/同步/点击/渲染的实现路径与可注入点。
> 阅读约定：本文所有路径均为相对 `reference/BeyondDimensions/` 的相对路径；源码为只读，本次调研未修改 `reference/` 下任何文件。
> 重要术语澄清：**旧版 API 文档中的 `IStackType` 在当前 0.7.30 源码中已改名为 `IStackKey`**，`IStackTypeHandler`/`StackTypedHandler` 对应现在的 `IStackHandler`/`StackHandler`。另见 `docs/research/beyond-dimensions-api.md`（该文档基于旧版网络资料，接口名已过时）。

---

## 目录

- [0. 结论速览](#0-结论速览)
- [1. 打开存储界面的完整链路](#1-打开存储界面的完整链路)
- [2. 菜单/界面的数据同步机制](#2-菜单界面的数据同步机制)
- [3. 物品列表的构建：搜索/排序/过滤在哪一层](#3-物品列表的构建搜索排序过滤在哪一层)
- [4. 点击列表项取出物品的完整流程](#4-点击列表项取出物品的完整流程)
- [5. 列表渲染机制](#5-列表渲染机制)
- [6. 扩展性评估](#6-扩展性评估)
- [7. `common/menu/widget/slot/` 槽位类：有无现成的只读/虚拟槽](#7-commonmenuwidgetslot-槽位类有无现成的只读虚拟槽)
- [8. BD 自身的 Mixin 组织方式与可行性边界](#8-bd-自身的-mixin-组织方式与可行性边界)
- [9. 对附属模组的实现建议](#9-对附属模组的实现建议)
- [附录 A：证据索引](#附录-a证据索引)
- [附录 B：未确认项](#附录-b未确认项)

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 存储界面 Screen | `client/gui/DimensionsNetGUI.java`（泛型基类，`<T extends DimensionsNetMenu>`） |
| 对应 Menu | `common/menu/DimensionsNetMenu.java` |
| 按键 | `client/init/BDShortKeys.java:40-44`，默认 `O` 键；回调在 `BDShortKeys.java:122-138` |
| 开关界面走网络包 | `OpenNetGuiPacket`（c2s，服务端 `player.openMenu`） |
| 存储内容同步包 | **只有 `s2c/DisorderedSlotGroupSyncPacket`** 负责网络存储内容同步；**增量（delta）+ 每 tick 合并**，不是全量 |
| 全量同步时机 | 菜单打开后第一个 tick（`DisorderedSlotGroupSync.updateChange` 的 `initialized` 标志） |
| 搜索/排序/过滤层 | **全部在客户端**（`common/menu/widget/ClientNetStorage.java` + `ClientNetStorageSearchHelper.java`） |
| 列表项数据结构 | 客户端视图里是 `KeyAmount(key, amount)`；屏幕槽位持有的是 `int theSlot`（视图下标），由 `list<索引>` 映射 |
| 分页/虚拟滚动 | 无。而是"一次性构建 891 个真实 `Slot`，只激活前 `getLines()*9` 个"，滚动靠 `lineData` 起始行偏移 |
| 点击取出链路 | `BDBaseGUI.slotClicked` → `CallSeverClickPacket`(c2s) → `BDBaseMenu.customClickHandler` → `AbstractStackTypedSlot.click` → `storage.extract` → `UnifiedStorage.extract` |
| 有无现成只读/虚拟槽 | **没有**。`FlagStackTypedSlot` 是最接近的"假槽位"（`isFake()==true`），但它的语义是"标记槽"且必须用于有序容器，不是只读展示槽 |
| 有无 GUI 事件 | **没有**。`api/event/` 下只有 3 个事件类，全部与 GUI/物品列表无关 |
| BD 自带 Mixin | 有，但极简：1 个配置 `beyonddimensions.mixins.json`，仅 2 个 mixin 目标 |
| 推荐方案 | **Mixin `DimensionsNetMenu.buildIndexList` + Mixin `BDBaseGUI.slotClicked`（客户端拦截虚拟槽点击）**，详见 [第 9 节](#9-对附属模组的实现建议) |

---

## 1. 打开存储界面的完整链路

### 1.1 链路总览

```
[客户端] 玩家按 O
  → KeyMapping OPEN_GUI_KEY (client/init/BDShortKeys.java:40-44)
  → ClientTickEvent.Post 轮询 (client/event/listener/ShortKeysListener.java:13-17)
  → BDShortKeys.processKeyInput() (client/init/BDShortKeys.java:95-106)
  → 回调 lambda (client/init/BDShortKeys.java:122-138)
  → PacketDistributor.sendToServer(new OpenNetGuiPacket(uuid, NetMenuType.NET_MENU))
────────────────────────────────────────────────────────────────────
[服务端] OpenNetGuiPacket.handleInServer (network/packet/c2s/OpenNetGuiPacket.java:60-127)
  → DimensionsNet.getNetFromPlayer(player) (api/dimensionnet/DimensionsNet.java:208-211)
  → net.getUnifiedStorage() (api/dimensionnet/DimensionsNet.java:821)
  → player.openMenu(new DimensionsNetMenu(..., net.getUnifiedStorage()))
    (network/packet/c2s/OpenNetGuiPacket.java:76-82)
────────────────────────────────────────────────────────────────────
[客户端] RegisterMenuScreensEvent 把 Menu 映射到 Screen
  → client/init/BDScreens.java:18
  → new DimensionsNetGUI<>(menu, inv, title)
```

### 1.2 逐环节证据

**① 按键定义**

`client/init/BDShortKeys.java:40-44`
```java
public static final KeyMapping OPEN_GUI_KEY = new KeyMapping(
        "key.beyonddimensions.open_gui", // 键位描述
        GLFW.GLFW_KEY_O,                 // 默认按键 "O"
        "key.categories.beyonddimensions" // 键位分类
);
```

**② 按键轮询入口**

`client/event/listener/ShortKeysListener.java:13-17`
```java
@SubscribeEvent
public static void onKeyInput(ClientTickEvent.Post event)
{
    BDShortKeys.processKeyInput();
}
```

`client/init/BDShortKeys.java:95-106`（每 tick 消费一次点击事件）
```java
public static void processKeyInput()
{
    for (Pair<KeyMapping, Runnable> pair : KEY_MAPPINGS_WITH_CALLBACK)
    {
        KeyMapping keyMapping = pair.getA();
        Runnable runnable = pair.getB();
        while (keyMapping.consumeClick())
        {
            runnable.run();
        }
    }
}
```

**③ 发 c2s 包**

`client/init/BDShortKeys.java:122-138`
```java
BDShortKeys.registerKey(OPEN_GUI_KEY, () -> {
    LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) { return; }

    if (CommonConfigRuntime.uiCraftButton == ButtonState.ENABLED)
    {
        PacketDistributor.sendToServer(new OpenNetGuiPacket(player.getStringUUID(), NetMenuType.NET_CRAFT_MENU));
    }
    else if (CommonConfigRuntime.uiCraftButton == ButtonState.DISABLED)
    {
        PacketDistributor.sendToServer(new OpenNetGuiPacket(player.getStringUUID(), NetMenuType.NET_MENU));
    }
});
```

注意：`NetMenuType` 有 3 个取值（`client/gui/NetMenuType.java` 共 7 行）——`NET_MENU` / `NET_CRAFT_MENU` / `NET_CRAFT_TERMINAL`。其中 `NET_CRAFT_TERMINAL` 由 `P` 键（`OPEN_TERMINAL_QUICK_KEY`，`BDShortKeys.java:46-50`）与 `NetTerminalItem` 触发，走的是 `DimensionsCraftMenuTerminal` + `DimensionsTerminalCraftGUI`。

**④ 服务端打开菜单**

`network/packet/c2s/OpenNetGuiPacket.java:60-82`
```java
private void handleInServer(final IPayloadContext context)
{
    Player player = context.player();
    DimensionsNet net = DimensionsNet.getNetFromPlayer(player);
    if (net != null)
    {
        NetMenuType targetMenu = this.target();
        ...
        else if (targetMenu == NetMenuType.NET_MENU)
        {
            player.openMenu(new SimpleMenuProvider(
                    (containerId, playerInventory, _player) -> new DimensionsNetMenu(DimensionsNetMenu.Dimensions_Net_Menu.get(), containerId, playerInventory, net.getUnifiedStorage()),
                    Component.translatable("menu.title.beyonddimensions.dimensionnetmenu")
            ));
        }
    }
}
```

**⑤ Screen 注册**

`client/init/BDScreens.java:18`
```java
event.<DimensionsNetMenu, DimensionsNetGUI<DimensionsNetMenu>>register(DimensionsNetMenu.Dimensions_Net_Menu.get(), DimensionsNetGUI::new);
```

### 1.3 各类职责表

| 类 | 相对路径 | 职责 | 所在端 |
|---|---|---|---|
| `BDShortKeys` | `client/init/BDShortKeys.java` | 注册 `KeyMapping`；`O` 键回调发 `OpenNetGuiPacket` | 客户端 |
| `ShortKeysListener` | `client/event/listener/ShortKeysListener.java` | `ClientTickEvent.Post` 中轮询按键 | 客户端 |
| `OpenNetGuiPacket` | `network/packet/c2s/OpenNetGuiPacket.java` | c2s；服务端按 `NetMenuType` 打开对应 Menu；也用于界面内切换（普通/合成/终端） | 双向 |
| `BDScreens` | `client/init/BDScreens.java` | `RegisterMenuScreensEvent` 中把 MenuType 绑定到 Screen 构造器 | 客户端 |
| `DimensionsNetGUI` | `client/gui/DimensionsNetGUI.java` | 存储界面：搜索框、排序/倒序/搜索策略按钮、页数增减、滚动条、背景绘制、Shift 状态维护 | 客户端 |
| `DimensionsCraftGUI` | `client/gui/DimensionsCraftGUI.java` | 继承 `DimensionsNetGUI`，额外加 3x3 合成区与转移按钮 | 客户端 |
| `DimensionsTerminalCraftGUI` | `client/gui/DimensionsTerminalCraftGUI.java`（21 行） | 终端物品版（带合成槽数据组件） | 客户端 |
| `BDBaseGUI` | `client/gui/BDBaseGUI.java` | 所有 BD 界面基类：重写 `renderSlot`/`renderTooltip`/`slotClicked`，把原版 `ItemStack` 渲染换成 `IStackRender` | 客户端 |
| `DimensionsNetMenu` | `common/menu/DimensionsNetMenu.java` | 服务端持有 `UnifiedStorage`；客户端持有 `ClientNetStorage` 视图；构建索引列表、槽位激活管理、行数增删 | 双端 |
| `DimensionsCraftMenu` | `common/menu/DimensionsCraftMenu.java` | 继承 `DimensionsNetMenu`，加 3x3 合成容器与自动补料 | 双端 |
| `BDBaseMenu` | `common/menu/BDBaseMenu.java` | 所有 BD 菜单基类：拦截 `broadcastChanges`（跳过 `AbstractStackTypedSlot` 的原版同步）、`customClickHandler`、`quickMoveStack` | 双端 |
| `DimensionsNetMenu.Dimensions_Net_Menu` | `common/menu/DimensionsNetMenu.java:51-52` | `MenuType` 注册（`IMenuTypeExtension`，带 `FriendlyByteBuf` 构造） | 双端 |
| `DimensionsNetGUI.menu` 字段 | 继承自 `AbstractContainerScreen<T>` | 屏幕直接读 `menu` 字段访问客户端视图 | 客户端 |

**服务端数据来源（关键）**：`DimensionsNetMenu` 服务端构造拿到的是 `net.getUnifiedStorage()`，即 **`api/dimensionnet/UnifiedStorage.java`**（`extends UnorderedStackHandlerRemoveZero`，`UnifiedStorage.java:22`），归属于 `DimensionsNet`（`SavedData`）。客户端构造则拿的是一个**空壳存储**：

`common/menu/DimensionsNetMenu.java:59-63`
```java
public DimensionsNetMenu(int id, Inventory playerInventory, FriendlyByteBuf data)
{
    // 客户端函数，故将Net设为临时Net
    this(Dimensions_Net_Menu.get(), id, playerInventory, new UnorderedStackHandlerRemoveZero(AbstractUnorderedStackHandler.UiTimestampPolicy.NONE));
}
```

`common/menu/DimensionsNetMenu.java:82-92`
```java
// 初始化维度网络容器
storage = data;
if (player.level().isClientSide())
{
    clientNetStorage = new ClientNetStorage(storage);
}
else
{
    // 服务端传入空挂
    clientNetStorage = null;
}
```

即：**客户端 `storage` 字段只是"服务端真存储的客户端镜像"，由网络包填充；`clientNetStorage` 是镜像之上的"稳定视图"（多一层拷贝）。**

---

## 2. 菜单/界面的数据同步机制

### 2.1 相关包清单

`network/packet/` 下全部包（共 18 个）：

| 包 | 类 | 方向 | 是否与存储内容同步有关 |
|---|---|---|---|
| `c2s` | `BatchTransferPacket` | 客户端→服务端 | 否（批量转移请求） |
| `c2s` | `CallSeverClickPacket` | 客户端→服务端 | **是**（点击槽位，见第 4 节） |
| `c2s` | `ClickTransferCraftButtonPacket` | 客户端→服务端 | 否 |
| `c2s` | `NetControlActionPacket` | 客户端→服务端 | 否 |
| `c2s` | `OpenMagnetGuiPacket` | 客户端→服务端 | 否 |
| `c2s` | `OpenNetGuiPacket` | 客户端→服务端 | 否（只负责开界面） |
| `c2s` | `OpenPrimaryNetSwitcherPacket` | 客户端→服务端 | 否 |
| `c2s` | `PickBlockFromNetPacket` | 客户端→服务端 | 否（创造式中键取方块） |
| `c2s` | `PrimaryNetSwitchActionPacket` | 客户端→服务端 | 否 |
| `c2s` | `PutHandItemToNetPacket` | 客户端→服务端 | 否（手上物品入库） |
| `c2s` | `RecipeFillC2SPacket` | 客户端→服务端 | 否（JEI 配方填充） |
| `c2s` | `RenameNetPacket` | 客户端→服务端 | 否 |
| `c2s` | `ToggleMagnetPacket` | 客户端→服务端 | 否 |
| `s2c` | **`DisorderedSlotGroupSyncPacket`** | 服务端→客户端 | **是（唯一）** |
| `s2c` | `OrderedStackTypedSlotPacket` | 服务端→客户端 | 部分（有序槽位单槽同步，用于网络接口/机器页，不用于存储网格） |
| `s2c` | `PlayerPermissionInfoPacket` | 服务端→客户端 | 否 |
| `both` | `QuickDataTagPacket` | 双向 | 否（设置项同步，`CompoundTag`） |
| `both` | `SetSlotDirectlyPacket` | 双向 | 否（有序槽位直接设值） |

包注册总表在 `common/init/BDPackets.java:20-188`。

### 2.2 存储内容同步：`DisorderedSlotGroupSync` + `DisorderedSlotGroupSyncPacket`

**同步器**：`common/menu/widget/slot/DisorderedSlotGroupSync.java`，每个 `DimensionsNetMenu` 在构造时创建两个：

`common/menu/DimensionsNetMenu.java:94-103`
```java
addSlotGroupSync(new DisorderedSlotGroupSync(this, slotGroupSyncs.size(), storage)
{
    @Override
    public void afterLoadChange()
    {
        updateViewerStorage(hasShiftDown);
        TooltipHelper.readAsCache(storage.getStorage(), Item.TooltipContext.of(player.level()), player, TooltipFlag.Default.NORMAL);
        TooltipHelper.readAsCache(storage.getStorage(), Item.TooltipContext.of(player.level()), player, TooltipFlag.Default.ADVANCED);
    }
});
```

注意这里传入的是 `storage`（客户端是镜像、服务端是真存储），而不是 `clientNetStorage`。`groupId = 0`（`slotGroupSyncs.size()` 在加入前为 0）。

**同步粒度：增量（delta）+ 每 tick 合并 + 分包**。类头注释直接说明：

`common/menu/widget/slot/DisorderedSlotGroupSync.java:17-22`
```java
/**
 * 用于无序槽位的同步器（事件驱动 + 逐 tick 合并发送）
 * - 服务端仅入队更新，不立刻发包；
 * - updateChange() 每 tick 合并一次并分包发送；
 * - 每个 key 在一次发送周期内只发送一次，且为“最近一次”的绝对状态。
 */
```

**订阅安装（仅服务端）**：

`common/menu/widget/slot/DisorderedSlotGroupSync.java:62-69`
```java
// 仅在服务端订阅
if (isServerSide())
{
    // 订阅 Any（全量结构变更 -> 仅置脏，不立刻发送）
    this.anySub = storage.subscribeAny(menu, this::onAnyChange);
    // 订阅 Delta（单次增量变更 -> 仅入队绝对状态，不立刻发送）
    this.deltaSub = storage.subscribeDelta(menu, this::onDeltaChange);
}
```

**两类事件回调**：

`common/menu/widget/slot/DisorderedSlotGroupSync.java:111-130`
```java
private void onAnyChange()
{
    if (!isServerSide()) return;
    dirtyFullRescan = true;
}

private void onDeltaChange(IStackKey<?> key, long size, boolean insert)
{
    if (!isServerSide() || key == null) return;

    long countNow = storage.getStackByKey(key).amount();
    long lastModified = getLastModifiedOrZero(key);
    long insertedTime = getCreationOrZero(key);

    // 覆盖式缓存：确保同一 key 在一个发送周期内只保留最新状态
    pending.put(key, new PendingRecord(countNow, lastModified, insertedTime));
}
```

**首次全量**：

`common/menu/widget/slot/DisorderedSlotGroupSync.java:134-148`
```java
@Override
public void updateChange()
{
    if (!isServerSide()) return;

    // 首次：做一次全量对比（但也放在本 tick 发送逻辑里处理）
    if (!initialized)
    {
        initialized = true;
        dirtyFullRescan = true;
    }

    // 把 full-rescan 与 pending 合并为最终 toSend，再分包发送
    drainAndSend();
}
```

**全量对比 / 增量发送二选一**（注意："全量"也是基于 `lastStorage` 基线做**差集**，只发变化项）：

`common/menu/widget/slot/DisorderedSlotGroupSync.java:153-198`（节选）
```java
private void drainAndSend()
{
    if (!dirtyFullRescan && pending.isEmpty()) return;

    Map<IStackKey<?>, PendingRecord> toSend = new LinkedHashMap<>();

    if (dirtyFullRescan)
    {
        // 仅做全量对比
        Map<IStackKey<?>, Long> lastMap = new HashMap<>();
        for (KeyAmount ka : this.lastStorage) { lastMap.merge(ka.key(), ka.amount(), Long::sum); }
        Map<IStackKey<?>, Long> nowMap = new HashMap<>();
        for (KeyAmount ka : this.storage.getStorage()) { nowMap.merge(ka.key(), ka.amount(), Long::sum); }

        Set<IStackKey<?>> allKeys = new HashSet<>();
        allKeys.addAll(lastMap.keySet());
        allKeys.addAll(nowMap.keySet());

        for (IStackKey<?> key : allKeys)
        {
            long lastCount = lastMap.getOrDefault(key, 0L);
            long nowCount = nowMap.getOrDefault(key, 0L);
            if (nowCount != lastCount)
            {
                long mtime = getLastModifiedOrZero(key);
                long ctime = getCreationOrZero(key);
                toSend.put(key, new PendingRecord(nowCount, mtime, ctime));
            }
        }

        // 本轮 full-rescan 权威，丢弃本轮 pending；下一 tick 再积累新的事件
        pending.clear();
        dirtyFullRescan = false;
    }
    else
    {
        // 仅发送 pending
        toSend.putAll(pending);
        pending.clear();
    }
    ...
    // 推进基线
    refreshLast();
}
```

**发送哪些字段**：`key + 绝对数量 + lastModified + insertedTime`（4 个列表），分包上限 900 KiB：

`common/menu/widget/slot/DisorderedSlotGroupSync.java:23-26` / `227-300`
```java
private static final int MAX_PACKET_SIZE = 900 * 1024; // 921,600 bytes
```
```java
/**
 * 估算每条记录字节大小并按 MAX_PACKET_SIZE 分包（key + count + lastModified + inserted）
 */
private List<DisorderedSlotGroupSyncPacket> buildBatchedPackets(...)
```

**包定义与客户端应用**：

`s2c/DisorderedSlotGroupSyncPacket.java:19-51`（字段顺序 `groupId, keys, newCounts, newModifiedTime, newInsertedTime`）

`s2c/DisorderedSlotGroupSyncPacket.java:53-66`
```java
private void handleInClient(final IPayloadContext context)
{
    Player player = context.player();
    if (player.containerMenu instanceof BDBaseMenu menu)
    {
        SlotGroupSync sync = menu.slotGroupSyncs.get(this.groupId());
        if (sync != null)
        {
            sync.loadChange(this.keys(), this.newCounts(), this.newModifiedTime(), this.newInsertedTime());
            sync.afterLoadChange();
        }
    }
}
```

`common/menu/widget/slot/DisorderedSlotGroupSync.java:317-344`
```java
@Override
public void loadChange(List<IStackKey<?>> keys, List<Long> newCounts,
                       List<Long> newModifiedTime, List<Long> newInsertedTime)
{
    AbstractUnorderedStackHandler clientStorage = storage;
    final int n = keys.size();

    for (int i = 0; i < n; i++)
    {
        IStackKey<?> key = keys.get(i);
        long count = (i < newCounts.size()) ? newCounts.get(i) : 0L;
        long mtime = (i < newModifiedTime.size()) ? newModifiedTime.get(i) : 0L;
        long ctime = (i < newInsertedTime.size()) ? newInsertedTime.get(i) : 0L;

        // 直接设置绝对数量（0 会按策略移除或保留）
        if (key != null)
        {
            clientStorage.setAmountByKey(key, count);
            storage.setLastModifiedTime(key, mtime);
            storage.setCreationTime(key, ctime);
        }
    }
}
```

**驱动时机**：由 `BDBaseMenu.broadcastChanges` 每 tick 调用。

`common/menu/BDBaseMenu.java:94-112`
```java
// 确保自定义同步不会被客户端调用
if (!player.level().isClientSide())
{
    if (!init)
    {
        initUpdate();
        init = true;
    }

    if (shouldSendQuickData())
    {
        CompoundTag updateTag = new CompoundTag();
        writeQuickDataTag(updateTag);
        PacketDistributor.sendToPlayer((ServerPlayer) player, new QuickDataTagPacket(updateTag));
    }

    setSlotGroupSyncsUpdate();
    abstractSlotsUpdate();
    updateChange();
}
```

`common/menu/BDBaseMenu.java:158-164`
```java
protected void setSlotGroupSyncsUpdate()
{
    for (SlotGroupSync slotGroupSync : slotGroupSyncs)
    {
        slotGroupSync.updateChange();
    }
}
```

> **`groupIds` 是列表下标**：`addSlotGroupSync` 只是 `list.add`（`BDBaseMenu.java:50-53`），包里的 `groupId` 就是该下标。**附属模组若想自加一个同步器，只要在菜单构造期间 `addSlotGroupSync`，`groupId` 会自动变成 1**，无需改 BD 的包注册逻辑 —— 但仍需自己的 S2C 包与自己的 `SlotGroupSync` 实现。

### 2.3 `UiTimestampPolicy` 与 "Any/Delta 订阅"

#### `UiTimestampPolicy`

定义：`api/storage/handler/impl/AbstractUnorderedStackHandler.java:39-41`
```java
/* ---------- UI 时间戳维护策略 ---------- */
public enum UiTimestampPolicy
{NONE, AUTO} // NONE: 不主动维护；AUTO: 自动维护
```

两张"仅供 UI 使用"的时间表：`AbstractUnorderedStackHandler.java:60-68`
```java
/* ---------- 仅供 UI 使用的时间表 ---------- */
/**
 * 记录该 Key 最近一次“从无到有建槽位”的时间（毫秒时间戳）。仅供 UI 展示，无其他语义。
 */
protected final Map<IStackKey<?>, Long> creationTimeMap = new HashMap<>();
/**
 * 记录该 Key 最近一次“数量被修改”的时间（毫秒时间戳）。仅供 UI 展示，无其他语义。
 */
protected final Map<IStackKey<?>, Long> lastModifiedTimeMap = new HashMap<>();
```

**AUTO 时的写入点**（`AUTO` 才写，`NONE` 完全不写）：

| 位置 | 相对路径:行号 | 写入内容 |
|---|---|---|
| `ensureInIndex`（首次建立槽位） | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:739-743` | `creationTimeMap.put(key, nowMillis())` |
| `setAmountByKey`（数量变化） | 同上 `:436-439`、`:447-450`、`:465-468` | `lastModifiedTimeMap.put(key, nowMillis())` |
| `setStackDirectly` | 同上 `:499-502`、`:508-512` | `lastModifiedTimeMap.put(key, nowMillis())` |
| `insert` | 同上 `:557-560` | `lastModifiedTimeMap.put(key, nowMillis())` |
| `extractByKey` | 同上 `:651-654`、`:669-672` | `lastModifiedTimeMap.put(key, nowMillis())` |
| `acceptEntry`（NBT 反序列化） | 同上 `:972-977` | 两张表都写 `now` |
| `removeFromIndex`（移除键） | 同上 `:763-765` | 两张表都 `remove(key)`（避免无界增长） |

这三处是 `AUTO` 与 `NONE` 的唯一区别。**各端使用的策略**：

| 存储实例 | 策略 | 证据 |
|---|---|---|
| 服务端 `UnifiedStorage`（真存储） | **`AUTO`** | `api/dimensionnet/DimensionsNet.java:104`（`unifiedStorage = new UnifiedStorage(this, AbstractUnorderedStackHandler.UiTimestampPolicy.AUTO);`） |
| 客户端镜像 `UnorderedStackHandlerRemoveZero` | `NONE`（构造处显式传入） | `common/menu/DimensionsNetMenu.java:62`、`common/menu/DimensionsCraftMenu.java:74` |
| `ClientNetStorage`（视图） | `NONE`（`super(ZeroPolicy.KEEP_ZERO, UiTimestampPolicy.NONE)`） | `common/menu/widget/ClientNetStorage.java:53` |

客户端的时间戳**不由本地 `AUTO` 产生**，而是从包里读入后**直接覆写**：

`common/menu/widget/ClientNetStorage.java` 无此逻辑；实际写入在 `DisorderedSlotGroupSync.java:339-340`
```java
storage.setLastModifiedTime(key, mtime);
storage.setCreationTime(key, ctime);
```
（`setCreationTime`/`setLastModifiedTime` 是无条件 put，与策略无关：`AbstractUnorderedStackHandler.java:175-186`）

**用途**：支撑排序策略 `SORT_INSERTED_TIME` / `SORT_MODIFIED_TIME`。排序时读的是 `sourceStorage` 的时间表：

`common/menu/widget/ClientNetStorage.java:183-184`
```java
final @Nullable Map<IStackKey<?>, Long> creationTimeMap = needCreationTimeSort ? sourceStorage.getCreationTimeMap() : null;
final @Nullable Map<IStackKey<?>, Long> modificationTimeMap = needModificationTimeSort ? sourceStorage.getLastModifiedTimeMap() : null;
```

排序按钮映射（`client/gui/DimensionsNetGUI.java:158-159`、`166-167`）：
```java
iconMap.put(ButtonState.SORT_INSERTED_TIME, ResourceLocation.tryBuild(BDConstants.MODID, "widget/sort_inserted_time"));
iconMap.put(ButtonState.SORT_MODIFIED_TIME, ResourceLocation.tryBuild(BDConstants.MODID, "widget/sort_modified_time"));
```

#### Any/Delta 订阅机制

`AbstractUnorderedStackHandler` 里两套监听器 + 一套"delta 上下文深度"：

`api/storage/handler/impl/AbstractUnorderedStackHandler.java:141-158`
```java
private final CopyOnWriteArrayList<AnyEntry> anyListeners = new CopyOnWriteArrayList<>();
private final CopyOnWriteArrayList<DeltaEntry> deltaListeners = new CopyOnWriteArrayList<>();
private int deltaContextDepth = 0;

private void beginDeltaContext() { deltaContextDepth++; }
private void endDeltaContext() { deltaContextDepth = Math.max(0, deltaContextDepth - 1); }
private boolean inDeltaContext() { return deltaContextDepth > 0; }
```

**关键：`onContentChanged` 是所有变更的统一出口**——它先 `beginDeltaContext()`，在"delta 上下文内"调 `onChange()`（此时 `fireChange()` 会**静默跳过** Any 回调），退出上下文后才 `fireDelta(...)`：

`api/storage/handler/impl/AbstractUnorderedStackHandler.java:309-370`
```java
protected void fireChange()
{
    if (inDeltaContext()) return;   // ← delta 上下文内不发 Any
    drainRefQueue();
    for (AnyEntry e : anyListeners)
    {
        try { e.listener.onAnyChange(); } catch (Throwable ignored) { }
    }
}

protected void fireDelta(IStackKey<?> type, long size, boolean insert)
{
    drainRefQueue();
    for (DeltaEntry e : deltaListeners)
    {
        try { e.listener.onDelta(type, size, insert); } catch (Throwable ignored) { }
    }
}

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

`UnifiedStorage` 覆写 `onChange` 以叠加脏标记：

`api/dimensionnet/UnifiedStorage.java:75-84`
```java
@Override
public void onChange()
{
    if (net != null)
    {
        net.setDirty();
    }
    // 调用基类的广播逻辑（Any/Delta 订阅）
    super.onChange();
}
```
（用户提到的"Any/Delta 订阅"注释即此处 `UnifiedStorage.java:82`）

因此**语义总结**：

| 订阅 | 触发条件 | 回调参数 | BD 内的消费者 |
|---|---|---|---|
| `subscribeAny` | 任何**结构性**变更（`onChange` 被直接调用，即不在 delta 上下文内的路径，如 `clearStorage`、`setSlotCapacity`、`setZeroPolicy` 清理） | 无参数 | `DisorderedSlotGroupSync.onAnyChange` → 置 `dirtyFullRescan` |
| `subscribeDelta` | **每一次** `onContentChanged`（数量/插入/取出） | `(key, 变化量, 是否插入)` | `DisorderedSlotGroupSync.onDeltaChange` → 缓存该 key 的绝对状态 |
| `subscribeAnyWeak` / `subscribeDeltaWeak` | 同上，但以弱引用持有 owner | 同上（Consumer/QuadConsumer） | `ClientNetStorage` 在**客户端**订阅自己的 `sourceStorage` |

`ClientNetStorage` 的订阅与"视图稳定"策略：

`common/menu/widget/ClientNetStorage.java:50-59`
```java
public ClientNetStorage(@NotNull AbstractUnorderedStackHandler sourceStorage)
{
    // 这里初始化为KEEP_ZERO，但是后续调用时，应当在必要时手动设置
    super(ZeroPolicy.KEEP_ZERO, UiTimestampPolicy.NONE);

    this.sourceStorage = sourceStorage;

    this.anySubscriber = this.sourceStorage.subscribeAnyWeak(this, ClientNetStorage::markForceAllUpdate);
    this.deltaSubscriber = this.sourceStorage.subscribeDeltaWeak(this, ClientNetStorage::loadFromDeltaSubscription);
}
```

`common/menu/widget/ClientNetStorage.java:72-126`
```java
private void loadFromDeltaSubscription(IStackKey<?> key, long delta, boolean insert)
{
    if (mustUpdateAllFromSource)
    {
        pendingCache.clear();
        return;
    }
    if (delta != 0)
    {
        pendingCache.add(key);
    }
}

public void resolvePendingOrAllUpdate(boolean onlyAmountUpdate)
{
    boolean anyChanged = false;
    if (mustUpdateAllFromSource)
    {
        pendingCache.clear();
        updateViewFromStorage(onlyAmountUpdate);
        this.mustUpdateAllFromSource = false;
        anyChanged = true;
    }
    else
    {
        Iterator<IStackKey<?>> it = pendingCache.iterator();
        while (it.hasNext())
        {
            IStackKey<?> key = it.next();
            if (key.isEmpty()) continue;

            long newAmount = sourceStorage.getStackByKey(key).amount();
            if (this.hasStack(key))
            {
                // 视图内已经有这个键，则说明其符合过滤器，直接设置数量
                anyChanged = true;
                this.setAmountByKey(key, newAmount);
            }
            else if (!onlyAmountUpdate && matchFilter(key))
            {
                // 否则，我们要求符合过滤器，且不处于仅数量更新的情况下，才允许向视图内增加新键
                anyChanged = true;
                this.setAmountByKey(key, newAmount);
            }

            it.remove();
        }
    }

    if (anyChanged)
    {
        this.cacheIndexes = null;
    }
}
```

> **`onlyAmountUpdate` 的来源是"Shift 是否按下"** —— 见 `common/menu/DimensionsNetMenu.java:99`（`updateViewerStorage(hasShiftDown)`）、`client/gui/DimensionsNetGUI.java:542-555`（`keyReleased` 里 Shift 抬起时 `markForceAllUpdateClientView(); updateViewerStorage(false);`）。含义：**按住 Shift 时只刷新已有条目的数量，不新增条目**，避免视图在操作中跳动。

**视图与真存储的分层：**

```
[服务端] UnifiedStorage (SavedData, 真数据, AUTO 时间戳)
   │  subscribeAny / subscribeDelta
   ▼
DisorderedSlotGroupSync  (每 tick 合并 → 分包)
   │  DisorderedSlotGroupSyncPacket (s2c)
   ▼
[客户端] menu.storage   (UnorderedStackHandlerRemoveZero, UiTimestampPolicy.NONE)  ← 镜像
   │  subscribeAnyWeak / subscribeDeltaWeak
   ▼
[客户端] menu.clientNetStorage (ClientNetStorage, ZeroPolicy.KEEP_ZERO)  ← 稳定视图（搜索+排序的对象）
   │  过滤 matchFilter / 排序 buildSortedIndex
   ▼
[客户端] menu.buildIndexList() → loadIndexList() → 891 个 Slot 的 theSlot 指针
```

`ClientNetStorage` 类注释说明了这份分层的动机：

`common/menu/widget/ClientNetStorage.java:18-23`
```java
/**
 * 维度网络UI特化的IStackKey客户端存储。为其指定一个源存储、其负责从源存储处同步数据、应用搜索、排序功能
 * <p>
 * 其可以被理解为一个客户端专用的存储视图，其与真存储区分开的原因是需要在一定时间内提供给客户端一个稳定不变的视图
 * 避免因真存储在服务端与客户端之间的变动导致存储视图频繁闪烁
 */
```

---

## 3. 物品列表的构建：搜索/排序/过滤在哪一层

### 3.1 结论：**全部在客户端**

服务端 `DimensionsNetMenu` **完全不知道**搜索文本、排序策略、翻页位置。证据：

1. 搜索文本只写进客户端视图：`common/menu/DimensionsNetMenu.java:291-297`
```java
public void loadSearchText(String text)
{
    if (clientNetStorage == null) return;

    this.searchText = text.toLowerCase(Locale.ENGLISH);
    this.clientNetStorage.setSearchText(searchText);
}
```
`clientNetStorage` 在服务端为 `null`（`DimensionsNetMenu.java:84-92`），所以服务端直接 return。

2. 排序只在 `buildIndexList` 里做，而它开头就有客户端校验：`common/menu/DimensionsNetMenu.java:241-251`
```java
public void buildIndexList()
{
    if (!this.player.level().isClientSide() || clientNetStorage == null)
    {
        return;
    }
    // 1 构建正确的索引数据
    List<Integer> indexes = clientNetStorage.buildSortedIndex(
            CommonConfigRuntime.uiSortButton,
            CommonConfigRuntime.uiSecondSortButton,
            CommonConfigRuntime.uiReverseButton == ButtonState.ENABLED);
```

3. 过滤发生在**同步落地时**（而不是渲染时），且只作用于视图：`common/menu/widget/ClientNetStorage.java:131-154`
```java
private void updateViewFromStorage(boolean onlyAmountUpdate)
{
    // 只更新视图内已有Key的数量，不同步新增key
    if (onlyAmountUpdate)
    {
        for (IStackKey<?> key : this.storage.keySet())
        {
            long amount = sourceStorage.getStackByKey(key).amount();
            this.setAmountByKey(key, amount);
        }
    }
    // 完全更新状态
    else
    {
        this.clearStorage();
        for (KeyAmount ka : this.sourceStorage.getStorage())
        {
            if (ka == null || !matchFilter(ka.key())) continue;

            this.setAmountByKey(ka.key(), ka.amount());
        }
    }
}
```
```java
private boolean matchFilter(IStackKey<?> key)
{
    return this.searchHelper.matches(key);
}
```
（`ClientNetStorage.java:258-261`）

### 3.2 搜索语法（客户端 `ClientNetStorageSearchHelper`）

`common/menu/widget/ClientNetStorageSearchHelper.java`：支持空格分词（AND）、`|`（OR）、`-` 前缀（取反）、引号包裹、`\` 转义、以及 5 种前缀：

`ClientNetStorageSearchHelper.java:199-227`
```java
char prefix = actual.charAt(0);
String needle;
switch (prefix)
{
    case '@' -> { needle = actual.substring(1); matched = needle.isEmpty() || matchesModId(keyAmount, needle); }      // 模组 id
    case '$' -> { needle = actual.substring(1); matched = needle.isEmpty() || matchesTooltip(keyAmount, needle); }   // tooltip
    case '#' -> { needle = actual.substring(1); matched = needle.isEmpty() || matchesTag(keyAmount, needle); }       // tag
    case '*' -> { needle = actual.substring(1); matched = needle.isEmpty() || matchesItemId(keyAmount, needle); }    // 注册名 path
    default -> matched = matchesName(keyAmount, actual);                                                              // 显示名
}
```
还带**拼音匹配**（`TinyPinyinUtils`，中文语言环境时启用）：`ClientNetStorageSearchHelper.java:271-298`。

> **对附属模组的意义**：虚拟条目如果想被 BD 的搜索引擎自动命中，其 `IStackKey.getRender().getDisplayName()` 与 `getModId()` 必须给出合理值 —— 但因为虚拟条目是我们自己塞进 `ClientNetStorage` 的，**可以不经过 `matchFilter`**，自行决定是否受搜索影响。

### 3.3 排序（客户端 `ClientNetStorage.buildSortedIndex`）

`common/menu/widget/ClientNetStorage.java:159-252`（节选）
```java
public List<Integer> buildSortedIndex(ButtonState primarySortPolicy, ButtonState secondarySortPolicy, boolean reverse)
{
    if (cacheIndexes != null
            && primarySortPolicy == lastSortProperties.primarySortPolicy()
            && secondarySortPolicy == lastSortProperties.secondarySortPolicy()
            && reverse == lastSortProperties.reverse())
        return cacheIndexes;
    ...
    final ArrayList<Row> rows = new ArrayList<>(this.getStorage().size());

    for (int i = 0; i < this.getStorage().size(); i++)
    {
        KeyAmount ka = this.getStorage().get(i);
        if (ka == null || ka.isEmpty()) continue;

        IStackKey<?> key = ka.key();
        ...
        rows.add(new Row(i, displayName, modIdSort, amt, maxStack, ctime, mtime, creativeTabOrder, creativeItemOrder));
    }

    if (!rows.isEmpty())
    {
        final Comparator<Row> primary = buildRowComparator(primarySortPolicy);
        if (useSecondary)
        {
            final Comparator<Row> secondary = buildRowComparator(secondarySortPolicy);
            rows.sort(primary.thenComparing(secondary));
        }
        else
        {
            rows.sort(primary);
        }
        if (reverse)
        {
            Collections.reverse(rows);
        }
    }

    ArrayList<Integer> result = new ArrayList<>(rows.size());
    for (Row row : rows)
    {
        result.add(row.idx);
    }
    this.cacheIndexes = result;
    this.lastSortProperties = new SortProperties(primarySortPolicy, secondarySortPolicy, reverse);
    return result;
}
```

支持 7 种主/副排序策略，比较器在 `ClientNetStorage.java:266-281`：
```java
return switch (state)
{
    case SORT_CREATIVE_TAB -> ...
    case SORT_QUANTITY -> Comparator.comparingLong((Row r) -> r.amount);
    case SORT_MAX_STACK -> Comparator.comparingLong((Row r) -> r.maxStack);
    case SORT_NAME -> Comparator.comparing((Row r) -> r.name, String::compareTo);
    case SORT_MODID -> Comparator.comparing((Row r) -> r.modIdSort, String::compareTo);
    case SORT_INSERTED_TIME -> Comparator.comparingLong((Row r) -> r.ctime);
    case SORT_MODIFIED_TIME -> Comparator.comparingLong((Row r) -> r.mtime);
    default -> Comparator.comparing((Row r) -> r.name, String::compareTo);
};
```

**结果缓存**：`cacheIndexes` + `lastSortProperties`，在 `resolvePendingOrAllUpdate` 里被置 `null` 失效（`ClientNetStorage.java:122-125`）。

### 3.4 列表项数据结构

**不是 `KeyAmount` 直接进槽位，而是"下标间接层"**：

| 层 | 数据结构 | 位置 |
|---|---|---|
| 视图存储 | `AbstractUnorderedStackHandler.storage : Map<IStackKey<?>, Long>` + `slotIndex : ArrayList<IStackKey<?>>` | `AbstractUnorderedStackHandler.java:53-55` |
| 只读视图 | `List<KeyAmount> getStorage()`，动态 AbstractList，`get(i)` 即时构造 `new KeyAmount(key, amt)` | `AbstractUnorderedStackHandler.java:70-88`、`372-377` |
| 排序结果 | `List<Integer> indexes`（指向视图 storage 的下标） | `DimensionsNetMenu.java:248` |
| 屏幕索引表 | `ArrayList<Integer> indexList`，长度 `getLines()*9`，**空位填 `-1`** | `DimensionsNetMenu.java:256-269` |
| 槽位 | `AbstractStackTypedSlot.theSlot : int`（默认 `-1` 表示空） | `AbstractStackTypedSlot.java:49` |

`common/menu/DimensionsNetMenu.java:253-284`
```java
// 2 构建linedata
updateScrollLineData(indexes.size());
// 3 填入索引表
ArrayList<Integer> indexList = new ArrayList<>();
for (int i = 0; i < getLines() * 9; i++)
{
    //根据翻页数据构建索引列表
    if (i + lineData * 9 < indexes.size())
    {
        int index = indexes.get(i + lineData * 9);
        indexList.add(index);
    }
    else
    {
        indexList.add(-1); //传入不存在的索引，可以使对应槽位成为空
    }
}
// 加载索引表
loadIndexList(indexList);
```
```java
// 双端函数，根据传入列表构建索引
// 此函数实际并不安全，其生效的重要条件是 存储槽位必须首先完全添加
public void loadIndexList(ArrayList<Integer> list)
{
    int listIndex = 0;
    for (int slotIndex = storageStartIndex; listIndex < list.size() && slotIndex < storageEndIndex; slotIndex++)
    {
        ((AbstractStackTypedSlot) slots.get(slotIndex)).setTheSlotIndex(list.get(listIndex));
        listIndex++;
    }
}
```

> **这是虚拟条目注入的最关键事实**：`indexList` 里的元素只是"视图 storage 的下标"。**只要客户端 `ClientNetStorage` 里多了条目，`buildSortedIndex` 就会把它排进去，`loadIndexList` 就会把它的下标写进某个屏幕槽位，`AbstractStackTypedSlot.getStack()` 就会去视图里取到它，`BDBaseGUI.renderSlot` 就会把它画出来。** 整条渲染链**不需要任何服务端知情**。

### 3.5 分页 / 虚拟滚动：**没有真正的虚拟滚动**

BD 的做法是**在菜单里一次性创建 891 个真实 `Slot`（99 行 × 9 列），再用 `setActive(false)` 关闭超出可见行数的槽位**：

`common/menu/DimensionsNetMenu.java:113-148`
```java
protected void addStorageSlots()
{
    // 默认添加99行，但将99之外的行全部设置为不激活状态，以实现动态增加和减少行数
    storageStartIndex = slots.size();
    vanillaQuickMoveStartIndex = storageStartIndex;
    if (player.level().isClientSide())
    {
        for (int row = 0; row < 99; ++row)
        {
            for (int col = 0; col < 9; ++col)
            {
                DisorderedStackTypedSlot newSlot = new DisorderedStackTypedSlot(this, clientNetStorage, -1, inventoryStartIndex, inventoryEndIndex, 8 + col * 18, 25 + row * 18);
                if (row >= getLines())
                    newSlot.setActive(false);
                this.addSlot(newSlot);
            }
        }
    }
    else
    {
        for (int row = 0; row < 99; ++row)
        {
            for (int col = 0; col < 9; ++col)
            {
                DisorderedStackTypedSlot newSlot = new DisorderedStackTypedSlot(this, storage, -1, inventoryStartIndex, inventoryEndIndex, 8 + col * 18, 25 + row * 18);
                if (row >= getLines())
                    newSlot.setActive(false);
                this.addSlot(newSlot);
            }
        }
    }
    storageEndIndex = slots.size();
    vanillaQuickMoveEndIndex = storageEndIndex;
}
```

行数（可见行）默认上限由屏幕高度和配置决定：

`common/menu/DimensionsNetMenu.java:36-39`（`public int maxLines = 6;`）、`client/gui/DimensionsNetGUI.java:104-112`（`calMaxLines()` 上限）、`client/gui/DimensionsNetGUI.java:420-423`
```java
protected int calMaxLines()
{
    return (int) ((this.height - 36 - (TOP_BASE_HEIGHT + TOP_SLOTS_HEIGHT + BOTTOM_SLOTS_HEIGHT + PLAYER_INV_HEIGHT)) / (float) MID_SLOTS_HEIGHT + 2);
}
```

"翻页"其实是**按行滚动**：`lineData` 是当前页第一行在索引表中的行号，由滚动条回调驱动：

`client/gui/DimensionsNetGUI.java:300-318`
```java
int trackLength = 18 * menu.getLines() - 15 - 2;
this.scroller = new BigScroller(
        this.leftPos + 174,
        this.topPos + TOP_BASE_HEIGHT + 1,
        trackLength,
        menu.lineData,
        menu.maxLineData,
        pos ->
        {
            if (menu.lineData != pos)
            {
                menu.lineData = pos;
                menu.buildIndexList();
            }
        }
);
this.scroller.setStep(1);
addRenderableWidget(scroller);
```

每 tick 回写滚动条位置：`client/gui/DimensionsNetGUI.java:354`
```java
scroller.updateScrollPosition(menu.lineData, menu.maxLineData); // 读取翻页数据并应用
```

`maxLineData` 计算：`common/menu/DimensionsNetMenu.java:306-317`
```java
public void updateScrollLineData(int dataSize)
{
    maxLineData = dataSize / 9;
    if (dataSize % 9 != 0) //如果余数不为0，说明还有一行，加1
    {
        maxLineData++;
    }
    maxLineData -= getLines();
    maxLineData = Math.max(maxLineData, 0);
    lineData = Math.max(lineData, 0);
    lineData = Math.min(lineData, maxLineData);
}
```

> **容量上限**：视图最多被展示 `891` 个条目（`getLines()` 最大 99，`DimensionsNetGUI.java:197-203` 里 `menu.getLines() >= 99` 会阻止继续增大）。但 `indexList` 只有 `getLines()*9` 个位置 —— 也就是说**同一时刻列表总长度受"可见行数 × 9"限制**，超出部分无法通过翻页看到（`maxLineData` 也只是 `数据量/9 - getLines()`）。这一点对"把几百上千个已学习物品也塞进同一个列表"是**硬性容量风险**。

### 3.6 界面上的搜索框交互

`client/gui/DimensionsNetGUI.java:248-288`
```java
this.searchField.setResponder(text -> {
    if (text.isEmpty())
    {
        searchField.setSuggestion(Component.translatable("wintercogs.beyonddimensions.dimensionsguisearch").getString());
    }
    else
    {
        searchField.setSuggestion(null);
    }
    menu.loadSearchText(text);
    CommonConfigRuntime.uiSearch = text;
    menu.markForceAllUpdateClientView();
    menu.updateViewerStorage(false);
    lastSearchText = text;
    ...
});
```

即：**输入 → `loadSearchText` 更新 helper → `markForceAllUpdate` 置脏 → `updateViewerStorage(false)` 重建视图 → `buildIndexList` 重建索引**。（`updateViewerStorage` 内部在 `!onlyAmountUpdate` 时调 `buildIndexList`：`common/menu/DimensionsNetMenu.java:229-235`）

排序/倒序按钮按下时**只重建索引，不重建视图**（`menu.buildIndexList()`）：`client/gui/DimensionsNetGUI.java:137`、`147`、`184`。

---

## 4. 点击列表项取出物品的完整流程

### 4.1 完整链路

```
[客户端] 鼠标点击屏幕槽位
  → AbstractContainerScreen.mouseClicked → slotClicked(...)
  → BDBaseGUI.slotClicked(Slot slot, int slotIndex, int mouseButton, ClickType type)
      client/gui/BDBaseGUI.java:153-217
      · 非 AbstractStackTypedSlot → 交给 super 处理
      · 是 AbstractStackTypedSlot → slot 的渲染内容 = sSlot.getVanillaActualStack()
      · shiftDown → clickItem = sSlot.getVanillaActualStack()
      · 非 shift  → clickItem = sSlot.getVanillaActualStack()
  → PacketDistributor.sendToServer(new CallSeverClickPacket(slotId, clickItem, mouseButton, shiftDown))
      client/gui/BDBaseGUI.java:197 / :207 / :212
────────────────────────────────────────────────────────────────────
[服务端] CallSeverClickPacket.handleInServer
      network/packet/c2s/CallSeverClickPacket.java:39-47
  → menu.customClickHandler(slotIndex, clickItem, button, shiftDown)
  → menu.broadcastChanges()
────────────────────────────────────────────────────────────────────
[服务端] BDBaseMenu.customClickHandler
      common/menu/BDBaseMenu.java:179-199
  → slots.get(slotIndex) instanceof AbstractStackTypedSlot
  → shiftDown ? slot.quickMove(clickedStack, button, player)
              : slot.click(clickedStack, button, player)
────────────────────────────────────────────────────────────────────
[服务端] DisorderedStackTypedSlot.click(KeyAmount clickStack, int button, Player player)
      common/menu/widget/slot/DisorderedStackTypedSlot.java:49-431
  · 槽位为空 + 手上有物 → storage.insert(...)
  · 槽位有物 + 手上为空 → 「取出」分支 :181-195
        int woundChangeNum = clampLongToInt(min(clickStack.amount(), clickKey.getVanillaMaxStackSize()));
        int actualChangeNum = button == LEFT ? woundChangeNum : (woundChangeNum + 1) / 2;
        ItemStack takenItem = (ItemStack) storage.extract(clickKey, actualChangeNum, false, false).toStack();
        if (takenItem != null) menu.setCarried(takenItem);
  · 槽位有物 + 手上有物 → 能力交互/经验棒/最终回退插入
────────────────────────────────────────────────────────────────────
[服务端] IStackHandler.extract → AbstractUnorderedStackHandler.extractByKey
      api/storage/handler/impl/AbstractUnorderedStackHandler.java:640-700
  但存储实例是 UnifiedStorage，它覆写了 extract 以插入前置钩子：
      api/dimensionnet/UnifiedStorage.java:104-133
  → UnifiedStorageBeforeExtractHandler.onBeforeExtract(input, net)   ← 公共钩子！
  → super.extract(...) → extractByKey → onContentChanged(key, take, false)
      → fireDelta → DisorderedSlotGroupSync.onDeltaChange → pending
      → 下一 tick 随 DisorderedSlotGroupSyncPacket 回到客户端
```

### 4.2 关键代码

**客户端发送**：`client/gui/BDBaseGUI.java:153-217`（完整）
```java
@Override
protected void slotClicked(Slot slot, int slotIndex, int mouseButton, ClickType type)
{
    if (!(slot instanceof AbstractStackTypedSlot))
        super.slotClicked(slot, slotIndex, mouseButton, type);

    if (slot == null) return; // slot绝对可能为null，不可移除此行

    int slotId = slot.index;
    KeyAmount clickItem;
    if (hasShiftDown())
    {
        if (slot instanceof AbstractStackTypedSlot sSlot)
        {
            clickItem = sSlot.getVanillaActualStack();
            if (!lastStorageClickedStack.isEmpty() && lastStorageClickedStack.equals(clickItem.key()))
            {
                // TODO 相当一部分人不喜欢存储物品双击后全量进入背包的实现，所以我们先禁用这条线，回头有空再整体更改点击处理
                // PacketDistributor.sendToServer(new BatchTransferPacket(clickItem, false));
            }
            else if (!clickItem.isEmpty() && clickItem.key() instanceof ItemStackKey itemStackKey)
            {
                this.lastStorageClickedStack = itemStackKey;
            }
        }
        else
        {
            clickItem = new KeyAmount(new ItemStackKey(slot.getItem()), slot.getItem().getCount());
            ...
        }
        PacketDistributor.sendToServer(new CallSeverClickPacket(slotId, clickItem, mouseButton, true));
    }
    else
    {
        if (slot instanceof AbstractStackTypedSlot sSlot)
        {
            if (sSlot.isFake())
            {
                // 对于标记槽位
                clickItem = sSlot.getVanillaActualStack();
                PacketDistributor.sendToServer(new CallSeverClickPacket(slotId, clickItem, mouseButton, false));
            }
            else
            {
                clickItem = sSlot.getVanillaActualStack();
                PacketDistributor.sendToServer(new CallSeverClickPacket(slotId, clickItem, mouseButton, false));
            }
        }
    }
}
```

> 注意 `slotId = slot.index`：**这是 `AbstractContainerMenu.slots` 列表下标**，两端一致（服务端也按同一顺序 add 了 891 个存储槽 + 玩家背包槽）。`if/else` 的两个分支代码完全相同，是冗余写法。

**c2s 包**：`network/packet/c2s/CallSeverClickPacket.java:15-47`
```java
public record CallSeverClickPacket(int slotIndex, KeyAmount clickItem, int button,
                                   boolean shiftDown) implements CustomPacketPayload
{
    ...
    private void handleInServer(final IPayloadContext context)
    {
        Player player = context.player();
        if (player.containerMenu instanceof BDBaseMenu menu)
        {
            menu.customClickHandler(this.slotIndex(), this.clickItem(), this.button(), this.shiftDown());
            menu.broadcastChanges();
        }
    }
    ...
}
```

**服务端分发**：`common/menu/BDBaseMenu.java:178-199`
```java
// 自定义点击操作
public void customClickHandler(int slotIndex, KeyAmount clickedStack, int button, boolean shiftDown)
{
    if (inventoryStartIndex < 0 || inventoryEndIndex < 0)
        BeyondDimensions.LOGGER.info("警告:背包索引设置错误！！！");

    if (slots.get(slotIndex) instanceof AbstractStackTypedSlot slot)
    {
        if (shiftDown)
            slot.quickMove(clickedStack, button, player);
        else
            slot.click(clickedStack, button, player);
    }
    else
    {
        // 用于处理原版槽位的快速转移
        if (shiftDown && vanillaQuickMoveEndIndex >= 0 && vanillaQuickMoveStartIndex >= 0 && vanillaQuickMoveStartIndex < vanillaQuickMoveEndIndex)
        {
            quickMoveHandle(player, slotIndex, clickedStack, vanillaQuickMoveStartIndex, vanillaQuickMoveEndIndex);
        }
    }
}
```

**取出分支**：`common/menu/widget/slot/DisorderedStackTypedSlot.java:178-195`
```java
else if (mayPickup(player))
{
    if (carriedItem.isEmpty())
    {
        if (clickStack.key() instanceof ItemStackKey clickKey)
        {
            //槽位物品存在，携带物品为空，尝试取出槽位物品
            // 确保一次取出最大不得超过原版数量
            int woundChangeNum = BDMath.clampLongToInt(Math.min(clickStack.amount(), clickKey.getVanillaMaxStackSize()));
            int actualChangeNum = button == GLFW.GLFW_MOUSE_BUTTON_LEFT ? woundChangeNum : (woundChangeNum + 1) / 2;
            ItemStack takenItem = (ItemStack) storage.extract(clickKey, actualChangeNum, false, false).toStack();
            if (takenItem != null)
            {
                menu.setCarried(takenItem);
            }
        }
    }
    ...
}
```

> **重要**：`clickStack.key()` 的类型检查是 `instanceof ItemStackKey`，且 `storage` 是**服务端真存储 `UnifiedStorage`**。取出时用的是 `clickKey`（即 `clickStack` 的 key）。因为虚拟条目的物品并不真的在网络里，`extract` 会走到 `extractByKey`：

`api/storage/handler/impl/AbstractUnorderedStackHandler.java:640-644`
```java
private @NotNull KeyAmount extractByKey(IStackKey<?> key, long count, boolean simulate)
{
    long current = storage.getOrDefault(key, 0L);
    if (current <= 0L) return new KeyAmount(key, 0L);
    ...
```
`current == 0` → 直接返回空 → `toStack()` 得到一个 count 为 0 的 `ItemStack` → `menu.setCarried(空)` → **静默失败**。这就是"必须在服务端为虚拟条目补一层兑换逻辑"的原因。

**`UnifiedStorage.extract` 的公共钩子**（这是**不需要 mixin 的官方扩展点**）：

`api/dimensionnet/UnifiedStorage.java:117-133`
```java
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

`api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java:43-49`
```java
/**
 * 调用此函数以添加处理
 */
public static void addHandler(BeforeExtractHandler handler)
{
    handlers.add(handler);
}
```

注意：`extract(int slot, long amount, boolean simulate)` 覆写版（`UnifiedStorage.java:104-115`）**也会**转发到带 key 的版本，所以两条路径都过钩子。

---

## 5. 列表渲染机制

### 5.1 `client/gui/widget/` 下的组件清单

| 文件 | 行数 | 作用 | 是否用于存储列表 |
|---|---|---|---|
| `client/gui/widget/LeftButtonSidebar.java` | 38 | 左侧竖排按钮容器（`GuiElementAccess` 的持有者） | 是（排序/搜索/翻页按钮都挂在它上） |
| `client/gui/widget/scroller/BigScroller.java` | 18 | `ScrollBar` 的子类，指定滑条贴图 `minecraft:container/creative_inventory/scroller` 与尺寸 `12x15` | 是 |
| `client/gui/widget/shared/ScrollBar.java` | 310 | 滑动条组件：拖拽、滚轮、量化步长、`updateScrollPosition(current, max)`、`setStep` | 是 |
| `client/gui/widget/shared/IconButton.java` | 90 | 带 sprite 图标的按钮 | 是 |
| `client/gui/widget/shared/StatusButton.java` | 76 | 双状态切换按钮（基类） | 是 |
| `client/gui/widget/shared/LeftTabButton.java` / `RightTabButton.java` | 24 / 25 | 左侧/右侧标签页按钮 | 否（用于其他界面） |
| `client/gui/widget/shared/GuiElementAccess.java` | 10 | 极简接口，暴露 `getX()/getY()` 等给 sidebar 使用 | 间接 |
| `client/gui/widget/button/SortMethodButton.java` | 38 | 排序策略按钮 + `iconMap`/`tooltipMap` | 是 |
| `client/gui/widget/button/ReverseButton.java` | 28 | 倒序切换 | 是 |
| `client/gui/widget/button/SearchToggleButton.java` | 28 | 搜索是否随界面持久化 | 是 |
| `client/gui/widget/button/PermissionInfoButton.java` | 32 | 权限信息（其他界面） | 否 |

`BigScroller` 全文（`client/gui/widget/scroller/BigScroller.java:10-18`）：
```java
public class BigScroller extends ScrollBar
{
    public static final ResourceLocation sprite = ResourceLocation.tryBuild("minecraft", "container/creative_inventory/scroller");

    public BigScroller(int x, int y, int maxScrollLength, int currentPosition, int maxPosition, @Nullable IntConsumer onScroll)
    {
        super(x, y, 12, 15, sprite, maxScrollLength, currentPosition, maxPosition, onScroll, Component.empty());
    }
}
```

### 5.2 槽位渲染入口：`BDBaseGUI.renderSlot`

**关键点：BD 没有"自己的 slot 渲染器"体系，而是覆写了原版 `AbstractContainerScreen.renderSlot`，把渲染委派给 `IStackKey.getRender()`。**

`client/gui/BDBaseGUI.java:66-94`
```java
@Override
protected void renderSlot(GuiGraphics guiGraphics, Slot slot)
{
    if (slot instanceof AbstractStackTypedSlot sSlot)
    {
        // 获取stack
        int x = slot.x;
        int y = slot.y;
        KeyAmount stack = sSlot.getStack();

        if (stack.key().isEmpty())
        {
            var noItemIcon = slot.getNoItemIcon();
            if (noItemIcon != null && this.minecraft != null)
            {
                TextureAtlasSprite textureatlassprite = this.minecraft.getTextureAtlas(noItemIcon.getFirst()).apply(noItemIcon.getSecond());
                guiGraphics.blit(x, y, 0, 16, 16, textureatlassprite);
            }
            return;
        }
        stack.key().getRender().render(guiGraphics, stack.key(), x, y);
        stack.key().getRender().renderAmount(guiGraphics, stack.amount(), x, y);

    }
    else
    {
        super.renderSlot(guiGraphics, slot);
    }
}
```

`AbstractStackTypedSlot.getStack()` 是取数入口：

`common/menu/widget/slot/AbstractStackTypedSlot.java:185-192`
```java
public @NotNull KeyAmount getStack()
{
    if (getSlotIndex() < 0 || getSlotIndex() >= storage.getSlots())
    {
        return new KeyAmount(EmptyStackKey.INSTANCE, 0);
    }
    return storage.getStackBySlot(getSlotIndex());
}
```
（`getSlots()` 默认返回 `getStorage().size()`：`api/storage/handler/IStackHandler.java:27-30`）

**注意**：这里的 `storage` 是 `clientNetStorage`（见 `DimensionsNetMenu.java:124`，客户端分支构造槽位时传入 `clientNetStorage`），因此**虚拟条目只要进了 `clientNetStorage`，渲染路径自动生效**。

### 5.3 Tooltip 渲染

`client/gui/BDBaseGUI.java:48-64`
```java
@Override
protected void renderTooltip(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY)
{
    if (this.menu.getCarried().isEmpty() && this.hoveredSlot != null && this.hoveredSlot.hasItem())
    {
        if (this.hoveredSlot instanceof AbstractStackTypedSlot sSlot)
        {
            KeyAmount stack = sSlot.getStack();
            stack.key().getRender().renderTooltip(guiGraphics, minecraft.font, stack.key(), stack.amount(), mouseX, mouseY);
        }
        else
        {
            ItemStack itemstack = this.hoveredSlot.getItem();
            guiGraphics.renderTooltip(this.font, this.getTooltipFromContainerItem(itemstack), itemstack.getTooltipImage(), itemstack, mouseX, mouseY);
        }
    }
}
```

### 5.4 `IStackRender` 接口与 `ItemStackKeyRender`

`api/storage/key/IStackRender.java:20-59`
```java
public interface IStackRender
{
    /** UI渲染，即绘制当前资源的图标 —— 必须以注解标注为仅客户端 */
    @OnlyIn(Dist.CLIENT)
    void render(GuiGraphics gui, IStackKey<?> key, int x, int y);

    /** 将数量绘制到屏幕上 */
    void renderAmount(GuiGraphics gui, long amount, int x, int y);

    /** 对当前存储数量进行格式化 */
    String getCountText(long count);

    /** 获取资源名称 */
    Component getDisplayName(IStackKey<?> key);

    /** 获取资源的工具提示 */
    List<Component> getTooltipLines(IStackKey<?> key, long amount, Item.TooltipContext tooltipContext, @Nullable Player player, TooltipFlag tooltipFlag);

    Optional<TooltipComponent> getTooltipImage(IStackKey<?> key);

    /** 绘制工具提示，必须要标记为仅客户端 */
    @OnlyIn(Dist.CLIENT)
    void renderTooltip(GuiGraphics gui, Font font, IStackKey<?> key, long amount, int mouseX, int mouseY);
}
```

物品渲染器 `api/storage/key/render/ItemStackKeyRender.java:28-74`（图标 + 数量，数量用 `StringFormat.formatCount` 做了千/万/亿缩写）
```java
@Override
public void render(GuiGraphics gui, IStackKey<?> key, int x, int y)
{
    if (key instanceof ItemStackKey itemKey)
    {
        var poseStack = gui.pose();
        poseStack.pushPose();
        ItemStack renderStack = itemKey.getRenderStack();
        gui.renderFakeItem(renderStack, x, y);
        gui.renderItemDecorations(Minecraft.getInstance().font, renderStack, x, y, "");
        poseStack.popPose();
    }
}

@Override
public void renderAmount(GuiGraphics gui, long amount, int x, int y)
{
    String countText = getCountText(amount);
    if (countText.isEmpty()) return;

    float scale = 0.666f;
    ...
    gui.drawString(Minecraft.getInstance().font, countText, X, Y, 0xFFFFFF);
    poseStackText.popPose();
}

@Override
public String getCountText(long count)
{
    if (count < 0) return "";
    return StringFormat.formatCount(count);
}
```

Tooltip 里会追加一行"存储数量"：`ItemStackKeyRender.java:94`
```java
tooltips.add(Component.translatable("istack.beyonddimensions.storage_num.item", amount));
```

渲染器分发来自 `IStackKey.getRender()`：

`api/storage/key/impl/ItemStackKey.java:456`（`public @NotNull IStackRender getRender()`）
`api/storage/key/impl/EnergyStackKey.java:176-180`
```java
@Override
public @NotNull IStackRender getRender()
{
    return EnergyStackKeyRender.INSTANCE; // 若不需要渲染器，可改为抛 UnsupportedOperationException
}
```

`EnergyStackKeyRender` 是"非物品资源"渲染器的现成范例（用水的静态贴图 + 绿色 tint 画一个占位方块，`api/storage/key/render/EnergyStackKeyRender.java:36-56`）：
```java
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
        IngredientRenderer.drawTiledSprite(gui, 16, 16, tint, 16, sprite, x, y);
    }

    pose.popPose();
}
```

### 5.5 渲染一个"虚拟条目"需要哪些数据？

**必要条件（缺一不可）：**

| # | 数据 | 谁提供 | 证据 |
|---|---|---|---|
| 1 | 一个 `IStackKey<?>` 实例，且其 `getTypeId()` 已在 `StackKeyRegistry` 注册（否则编解码/`getType` 会抛异常） | `api/storage/key/StackKeyRegistry.java:14-32` | 注册入口 `BeyondDimensions.java:65-68` |
| 2 | 该 key 的 `getRender()` 返回一个 `IStackRender`（客户端渲染图标 + 数量） | `api/storage/key/IStackKey.java:194` | |
| 3 | 一个 `long amount`（要显示的数值；可为"用 EMC 能兑换出的数量"，不要求是真实库存） | `KeyAmount.key/amount`，`api/storage/key/KeyAmount.java:16` | |
| 4 | 该 key 的 `isEmpty()` 必须返回 `false`（否则 `KeyAmount.isEmpty()` 为真，工具提示/渲染直接跳过） | `KeyAmount.java:204-207`、`BDBaseGUI.java:76-85` | |
| 5 | 在 `clientNetStorage` 里出现（通过 `setAmountByKey` 写入，`ZeroPolicy.KEEP_ZERO` 下允许 0 数量占位） | `ClientNetStorage.java:53`、`AbstractUnorderedStackHandler.java:419-472` | |
| 6 | 在 `buildSortedIndex` 中不被 `ka.isEmpty()` 排除（即 amount > 0 且 key 非空） | `ClientNetStorage.java:188-193` | |
| 7 | `getDisplayName()` / `getModId()` 合理（用于排序、搜索、tooltip） | `IStackRender.getDisplayName`、`IStackKey.getModId` | |

**额外有用的（为了更好的体验）：**

| 数据 | 用途 |
|---|---|
| `getVanillaMaxStackSize()` | 决定单次点击取出多少（`DisorderedStackTypedSlot.java:187`），以及 `SORT_MAX_STACK` |
| `copyStackWithCount(long)` | `KeyAmount.toStack()` 使用（`KeyAmount.java:212-215`） |
| `getTags()` | 支持 `#tag` 搜索（`ClientNetStorageSearchHelper.java:246-256`） |
| `getTooltipLines(...)` | tooltip 内容，可在此标注"库存 0 / EMC 可兑换 N" |
| 网络序列化 `serialize/deserialize` + `STREAM_CODEC` | **仅当该 key 需要跨端传输时才必要**（若纯客户端构造虚拟 key，理论上可省，但 `IStackKey.STREAM_CODEC` 会调 `StackKeyRegistry.getType(typeId)`，注册仍建议做） |

**服务端侧的额外需求**（为了"点击取出、扣 EMC"）：
- 服务端需要知道玩家点击的是虚拟条目还是真实条目。`CallSeverClickPacket` 只带 `slotIndex + clickItem`，**没有"这是虚拟条目"的标志位**。
- 服务端需要知道该物品的 EMC 单价与网络 EMC 余额。

---

## 6. 扩展性评估

### 6.0 硬约束回顾

- 不能修改 BD 源码 → 只能用 **Mixin** 或 **BD 提供的 API/事件**。
- BD 的 API 面（`api/` 包）确实是公开的：`StackKeyRegistry`、`UnifiedStorageBeforeInsertHandler`、`UnifiedStorageBeforeExtractHandler`、`IStackKey`、`IStackRender`、`AbstractUnorderedStackHandler.subscribeAny/Delta`、`SlotGroupSync`、`CapabilityHelper` 等。

### 6.1 `api/event/` 下**全部**事件类及其触发点

`api/event/` 下只有 3 个文件（`api/event/dimensionnet/`），**没有任何 GUI / 物品列表 / 菜单 / 屏幕相关事件**：

| 事件类 | 相对路径 | 子类 | 触发点（`相对路径:行号`） | 对本项目是否有用 |
|---|---|---|---|---|
| `DimensionsNetEvent` | `api/event/dimensionnet/DimensionsNetEvent.java:14` | `Created`（`net`） | `api/dimensionnet/DimensionsNet.java:144`（`createNewNetForPlayer` 中 `NeoForge.EVENT_BUS.post(new DimensionsNetEvent.Created(newNet))`） | 可用于初始化附属数据（如给网络挂 EMC 余额） |
| | | `Destroyed`（`destroyedId, netName, destroyedStorage, owner, managers, members`） | `api/dimensionnet/DimensionsNet.java:757`（构造）、`:787`（`post`） | 可用于清理/返还 EMC |
| | | `OwnerChanged` | `api/dimensionnet/DimensionsNet.java:526` | 否 |
| | | `MemberChanged` | `api/dimensionnet/DimensionsNet.java:570`、`:589`、`:626`、`:677` | 否 |
| `NetedBlockEvent` | `api/event/dimensionnet/NetedBlockEvent.java:18` | `Bound` | `common/block/entity/NetedBlockEntity.java:65` | 否 |
| | | `Unbound` | `common/block/entity/NetedBlockEntity.java:59` | 否 |
| `NetedItemEvent` | `api/event/dimensionnet/NetedItemEvent.java:18` | `Bound` | `common/item/NetedItem.java:112` | 否 |
| | | `Unbound` | `common/item/NetedItem.java:99` | 否 |

**结论：`api/event/` 完全没有可用于"界面/物品列表"的事件。** 想在列表里注入虚拟条目，**必须走 Mixin，或走"注册真实资源 key"这条路。**

替代的"非事件"API 钩子（都在 `api/` 下，无需 mixin）：

| 钩子 | 位置 | 用途 |
|---|---|---|
| `UnifiedStorageBeforeInsertHandler.addHandler` | `api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java:46-49` | 插入前改写/取消（可做"物品入库自动转 EMC"） |
| `UnifiedStorageBeforeExtractHandler.addHandler` | `api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java:46-49` | 提取前改写/取消（可做"用 EMC 兑换物品"的**服务端**落地） |
| `AbstractUnorderedStackHandler.subscribeAny/Delta(+Weak)` | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:263-307` | 观测网络存储变化（可做附属数据失效/重算） |
| `StackKeyRegistry.registerType` | `api/storage/key/StackKeyRegistry.java:14-21` | 注册新资源类型（EMC key） |
| `IStackKey` / `IStackRender` 实现 | `api/storage/key/IStackKey.java:20`、`IStackRender.java:20` | 自定义资源的编解码与渲染 |
| `SlotGroupSync` 接口 | `common/menu/widget/slot/SlotGroupSync.java:10` | ⚠️ **在 `common/` 下，不是 `api/`**；实现它需要能把自己的同步器 `addSlotGroupSync` 进菜单（需要 mixin 或自建 Menu） |
| `CapabilityHelper.*` | `api/capability/helper/CapabilityHelper.java` | 让管道/机器访问自定义资源 |

### 6.2 方案 a：Mixin 注入菜单/界面的列表构建方法

**目标方法**：`DimensionsNetMenu.buildIndexList()`（`common/menu/DimensionsNetMenu.java:241-272`）。

**可行性：高。** 原因是虚拟条目只需要进 `clientNetStorage`（一个纯客户端的 `AbstractUnorderedStackHandler`），而 `buildIndexList` 是唯一把视图转成屏幕槽位指针的函数。

**推荐注入点（三选一 / 组合）**：

| 注入点 | 时机 | 适合做什么 |
|---|---|---|
| `updateViewerStorage`（`:229-235`）`@Inject(at = @At("HEAD"))` | 视图重建前/后 | 先把虚拟条目写入 `clientNetStorage`，再让它走 `buildIndexList` |
| `buildIndexList`（`:241`）`@Inject(at = @At("HEAD"))` | 每次重建索引前 | 保证虚拟条目始终在视图里（最稳，覆盖排序按钮/翻页/搜索所有触发路径） |
| `loadSearchText`（`:291-297`）`@Inject(at = @At("RETURN"))` | 搜索变化后 | 控制虚拟条目是否受搜索过滤影响 |

**最小实现示意（伪代码，混入 `DimensionsNetMenu`）**：
```java
@Mixin(DimensionsNetMenu.class)
public abstract class DimensionsNetMenuMixin
{
    @Shadow public @Nullable ClientNetStorage clientNetStorage;

    @Inject(method = "buildIndexList", at = @At("HEAD"))
    private void bdaddon$injectVirtualEntries(CallbackInfo ci)
    {
        DimensionsNetMenu self = (DimensionsNetMenu)(Object)this;
        if (self.player.level().isClientSide() && clientNetStorage != null)
        {
            for (VirtualEntry e : VirtualEntryProvider.current())
            {
                // 只写"视图"，不碰真存储
                clientNetStorage.setAmountByKey(e.key(), e.displayAmount());
            }
        }
    }
}
```

**注意点与风险**：

| 风险 | 说明 | 证据 |
|---|---|---|
| `clientNetStorage` 是 `public`、`buildIndexList` 是 `public`，但 `storage`/`slotIndex` 是 `protected` | 用 `@Shadow` 访问 `clientNetStorage` 没问题；操作 `clientNetStorage.setAmountByKey` 是 public 方法，**无需 accessor** | `DimensionsNetMenu.java:42`、`:241`；`AbstractUnorderedStackHandler.java:419` |
| **虚拟条目会污染"数量"语义** | `ClientNetStorage` 的 `storage` 同时被"视图"和"排序"使用；`resolvePendingOrAllUpdate` 的 `onlyAmountUpdate` 分支会遍历 `this.storage.keySet()` 并从 `sourceStorage` 覆写数量 —— **虚拟 key 在 `sourceStorage` 里不存在 → 数量被覆写成 0**！`updateViewFromStorage(true)` 同理只更新已有 key，不会加新的，但会把虚拟条目数量刷成 0 | `ClientNetStorage.java:131-142` |
| 因此**必须在每次 `resolvePendingOrAllUpdate` 之后再补写虚拟条目**，否则按住 Shift / 每次 delta 同步都会把虚拟数量清 0 | 建议注入点选 `updateViewerStorage` 的 `@At("RETURN")` **并且**在 `buildIndexList` HEAD 再补一次 | |
| `cacheIndexes` 失效 | `setAmountByKey` 会触发 `onContentChanged` → `fireDelta`，但 `ClientNetStorage` 自己的 `cacheIndexes` 不会失效（`resolvePendingOrAllUpdate` 才置 null）。所以补写虚拟条目后要能触发 `buildIndexList` 重新排序 —— 而 `buildIndexList` 本身就会重新 sort，只要 `cacheIndexes == null`。**若排序策略未变，`buildSortedIndex` 会直接返回缓存**！ | `ClientNetStorage.java:161-165`、`:122-125` |
| `list` 长度上限 891 | `indexList` 只填 `getLines()*9` 个；`getLines()` 最大 99 | `DimensionsNetMenu.java:257`；`DimensionsNetGUI.java:197-203` |
| 服务端完全不知情 → 点击会静默失败 | 见第 4.2 节分析 | `AbstractUnorderedStackHandler.java:640-644` |
| 双端列表顺序不一致 | `indexList` 只在客户端算，`loadIndexList` 注释也点了"双端函数"，但服务端从未调用它（`buildIndexList` 开头就 `return`） | `DimensionsNetMenu.java:243-246`、`:274-275` |

**缓解 `cacheIndexes` 缓存问题的办法**：在补写虚拟条目前先切一次排序策略，或直接对 `cacheIndexes` 字段做 `@Accessor`/`@Shadow` 置 `null`（字段是 `private @Nullable List<Integer> cacheIndexes = null;`，`ClientNetStorage.java:45`）。也可对 `ClientNetStorage.buildSortedIndex` 做 `@Inject(at = @At("HEAD"), cancellable = true)` 自行实现排序。

**方案 a 的补充：点击拦截**
由于服务端不认识虚拟条目，方案 a 必须**同时**再 Mixin `BDBaseGUI.slotClicked`（客户端），在识别出"被点的是虚拟槽"时改发**自定义包**而不是 `CallSeverClickPacket`：

```java
@Mixin(BDBaseGUI.class)
public abstract class BDBaseGUIMixin
{
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void bdaddon$interceptVirtual(Slot slot, int slotIndex, int mouseButton, ClickType type, CallbackInfo ci)
    {
        if (VirtualSlotRegistry.isVirtual(slot))
        {
            PacketDistributor.sendToServer(new ExchangeWithEmcPacket(slot.index, mouseButton));
            ci.cancel();
        }
    }
}
```
（本文件未在源码中确认存在其他更合适的注入点；`slotClicked(Slot, int, int, ClickType)` 是 `protected`，用 Mixin 注入是可行的。）

### 6.3 方案 b：自写全新 Screen/Menu（复用 BD 组件类）

**可行性：中。** BD 的组件类基本都是 `public`，可复用，但有若干硬依赖。

**可复用的 `public` 组件**：

| 组件 | 位置 | 复用条件 |
|---|---|---|
| `BDBaseGUI<T>` | `client/gui/BDBaseGUI.java:27` | `public abstract class`，直接继承即可 |
| `BDBaseMenu` | `common/menu/BDBaseMenu.java:28` | `public abstract class`，需实现 `stillValid` |
| `DimensionsNetMenu` | `common/menu/DimensionsNetMenu.java:34` | **不可直接继承**（构造函数需 `AbstractUnorderedStackHandler`；构造函数会自己 `addStorageSlots`，无法阻止）——但可以让自己的 Menu 继承它然后覆盖 `getLines()`/`addStorageSlots()` |
| `ClientNetStorage` | `common/menu/widget/ClientNetStorage.java:24` | `public`，构造需一个 `AbstractUnorderedStackHandler`；`resolvePendingOrAllUpdate`/`buildSortedIndex` 都是 public |
| `DisorderedStackTypedSlot` | `common/menu/widget/slot/DisorderedStackTypedSlot.java:29` | `public`，构造 `(BDBaseMenu menu, IStackHandler, int slotIndex, int quickMoveStart, int quickMoveEnd, int x, int y)` |
| `AbstractStackTypedSlot` | `common/menu/widget/slot/AbstractStackTypedSlot.java:22` | `public abstract`，可继承做"只读虚拟槽"（见第 7 节） |
| `DisorderedSlotGroupSync` | `common/menu/widget/slot/DisorderedSlotGroupSync.java:23` | `public`，但它 `implements SlotGroupSync`，`updateChange/loadChange` 已有完整实现 —— **可直接实例化并 `addSlotGroupSync`**；不过 `loadChange` 的参数形状决定了**只能同步真实存在的 key**（服务端 `onDeltaChange` 只能由真存储的 delta 触发，`DisorderedSlotGroupSync.java:120-130`） |

> ⚠️ `AbstractStackTypedSlot` 的类注释明确警告：**"请确保其只被添加到 BDBaseMenu 或 BDBaseGUI 及其子类；如果你需要将其添加到你自定义的菜单或 ui，你需要修改他们，以确保会正确调用 click 函数以及同步函数"**（`common/menu/widget/slot/AbstractStackTypedSlot.java:19-20`）。这意味着自建 Menu 时必须自己复刻 `BDBaseMenu.customClickHandler` 的分发逻辑（`BDBaseMenu.java:179-199`）。

**风险/成本**：

| 项 | 说明 |
|---|---|
| 成本最高 | 需要重做搜索框、排序按钮、7 种排序策略、滚动条、翻页、合成区（如果要）、JEI/EMI 拖拽、`BatchTransferPacket`、`PutHandItemToNetPacket`、`PickBlockFromNetPacket`、`IPNIgnore` 兼容…… |
| 与 BD 后续版本脱节 | BD 的 GUI 尺寸/贴图/行数逻辑都在 `DimensionsNetGUI` 里，重写后无法自动跟随 |
| 与 JEI/EMI 集成冲突 | BD 用 `integration/module/jei/BDjeiPlugin.java`、`integration/module/emi/BDEMIPlugin.java` 注册了菜单/槽位处理器，自建 Menu 很可能不被识别为"BD 存储界面" |
| 不受"不能改 BD 源码"限制 | 这个方案完全合规，且最可控 |

### 6.4 方案 c：注册一种真实的资源 Key（EMC），让 BD 原生把它当资源显示

**可行性：中低（能满足"显示 EMC 余额"，但**不能满足"显示已学习物品列表"**）。**

**BD 原生就支持注册新资源类型**（这是 BD 明确公开的扩展点，且有 4 个内置范例 + 5 个模组集成范例）：

| 注册点 | 位置 |
|---|---|
| 类型注册 API | `api/storage/key/StackKeyRegistry.java:14-21` |
| 内置注册时机 | `BeyondDimensions.java:61-68`（`commonSetup`，`FMLCommonSetupEvent`） |
| 内置类型 | `EmptyStackKey.INSTANCE`、`ItemStackKey.EMPTY`、`FluidStackKey.EMPTY`、`EnergyStackKey.INSTANCE` |
| 集成模组范例 | `integration/module/ars/ArsModule.java:53-55`（`SourceStackKey`）、`integration/module/mekanism/MekModule.java:34`（`ChemicalStackKey`）、`integration/module/ifs/IFSModule.java:49`（`WardenSoulStackKey`）、`integration/module/botania/BotaniaModule.java:63`（`ManaStackKey`） |
| 配套能力注册 | `BeyondDimensions.java:70-89`：`CapabilityHelper.BlockCapabilityMap` / `ItemCapabilityMap` / `registerUSHandler` / `registerStackTypedHandler` |
| 无字段 Key 的最佳范例 | `EnergyStackKey`（`api/storage/key/impl/EnergyStackKey.java`）+ `EnergyType`（`api/longtype/EnergyType.java`）+ `EnergyStackKeyRender`（`api/storage/key/render/EnergyStackKeyRender.java`）——**EMC 几乎可以照抄这一套** |

**能做**：EMC 成为一个真实资源条目，出现在存储网格里，数量 = 网络 EMC 余额，可被排序/搜索/取出（取出 = 从网络扣除）。

**不能做**：
- **"已学习但库存为 0 的物品"作为条目出现**。BD 的列表完全来自 `clientNetStorage`，而它的数据来自服务端同步的**真实存储内容**（`DisorderedSlotGroupSync` 只遍历 `storage.getStorage()`，`:167-171`）。注册 EMC key 不会凭空产生"某物品 ×0"的条目 —— 除非真把 0 数量的物品写进网络，但 `UnifiedStorage` 是 `UnorderedStackHandlerRemoveZero`（`api/dimensionnet/UnifiedStorage.java:22`），**0 数量的 key 会被直接移除**（`AbstractUnorderedStackHandler.java:427-442`）。
- **"用 EMC 兑换出的数量"这一虚拟数值**：除非真的往网络里插入那么多物品。
- **一个物品同时显示"库存数"和"EMC 可兑换数"**：一个 key 在视图里只有一个 `amount`。

**风险**：

| 风险 | 证据 |
|---|---|
| `StackKeyRegistry` 用普通 `HashMap`，且无版本校验；注册顺序敏感 | `StackKeyRegistry.java:12` |
| 新 key 会被 JEI/EMI 幽灵拖拽处理遍历到（`StackKeyRegistry.getAllTypes()`），需要保证 `getSourceClass()` 可被安全 `isAssignableFrom` | `integration/module/emi/slothandler/SlotDragHandler.java:71-76`、`integration/module/jei/NetInterfaceGhostHandler.java:71` |
| 新 key 会被存档序列化（`IStackKey.CODEC` dispatch），**一旦该模组被移除，旧存档里的 EMC 条目解码会抛异常** | `api/storage/key/IStackKey.java:25-33`、`AbstractUnorderedStackHandler.java:840-853`、`:886-899` |
| 网络容量按"槽位数"计（`slotMaxSize`），新资源类型会占用槽位 | `AbstractUnorderedStackHandler.java:459-462` |
| 注册时机会与 BD 的 `commonSetup` 竞争（同样在 `FMLCommonSetupEvent`，顺序不保证但通常安全，因为只是往 map 里放） | `BeyondDimensions.java:61` |

### 6.5 方案 d：用 BD 的事件

**可行性：无。** 见第 6.1 节 —— `api/event/` 下全部 3 个事件类（共 8 个子类）**没有一个**与 GUI、菜单、槽位、物品列表、存储内容变更相关。

唯一可用于"感知存储变化"的机制是**订阅**（不是事件总线）：

`api/storage/handler/impl/AbstractUnorderedStackHandler.java:262-307`
```java
/* ================= 公共订阅 API ================= */
public AutoCloseable subscribeAny(Object owner, AnyChangeListener onAny) { ... }
public AutoCloseable subscribeDelta(Object owner, DeltaListener onDelta) { ... }
public <T> AutoCloseable subscribeAnyWeak(T owner, java.util.function.Consumer<T> onAny) { ... }
public <T> AutoCloseable subscribeDeltaWeak(T owner, QuadConsumer<T, IStackKey<?>, Long, Boolean> onDelta) { ... }
```

**注意**：这些订阅挂在**具体的存储实例**上。服务端可以订阅 `net.getUnifiedStorage()`；**客户端只能订阅 `menu.storage`（镜像）或 `menu.clientNetStorage`（视图）**，而客户端拿到 `menu` 实例的时机需要在 `ScreenEvent.Init` 或 Screen 构造之后（BD 自身没有暴露事件，需自己 Mixin 或用自己的 Screen）。

### 6.6 方案对比小结（针对本项目目标）

| 目标需求 | a) Mixin 列表注入 | b) 自写 Screen/Menu | c) 注册 EMC 真实 Key | d) BD 事件 |
|---|---|---|---|---|
| 显示"已学习物品（含 0 库存）" | ✅ 可以 | ✅ 可以 | ❌ 做不到（0 数量会被 `RemoveZero` 清掉） | ❌ |
| 显示"EMC 可兑换数量" | ✅ 可以（虚拟 amount） | ✅ 可以 | ⚠️ 需真的插入那么多物品 | ❌ |
| 点击取出并扣 EMC | ⚠️ 需再 Mixin 点击 | ✅ 完全可控 | ⚠️ 取出的是 EMC 本身，不是物品 | ❌ |
| 复用 BD 搜索/排序/滚动 | ✅ 完全复用 | ❌ 需重写 | ✅ 复用 | — |
| 改动量 | 小～中 | 大 | 中（但要另想办法做列表） | — |

---

## 7. `common/menu/widget/slot/` 槽位类：有无现成的只读/虚拟槽

### 7.1 完整清单

| 类 | 行数 | `isOrdered()` | 用途 | 是否只读 |
|---|---|---|---|---|
| `AbstractStackTypedSlot` | 352 | 抽象 | 所有 `IStackKey` 驱动槽位的基类 | 否（抽象） |
| `DisorderedStackTypedSlot` | 545 | `false`（`:42-46`） | **存储网格槽位**（本界面用的就是它） | 否 |
| `OrderedStackTypedSlot` | 475 | `true` | 有序容器槽位（网络接口/机器） | 否 |
| `FlagStackTypedSlot` | 157 | `true`（`:38-41`） | **标记槽（假槽位）**，构造时 `setFake(true)`（`:32-36`） | 语义上是"过滤器"，不是"只读展示" |
| `AutoRefillResultSlot` | 116 | — | 合成结果槽 + 自动补料 | 否（是 `Slot` 子类，不是 `AbstractStackTypedSlot`） |
| `DisorderedSlotGroupSync` | 360 | — | 无序槽位的**同步器**（实现 `SlotGroupSync`） | — |
| `SlotGroupSync` | 31 | — | 同步器接口 | — |
| `ItemCapInteractionBlackList` | 21 | — | 能力交互黑名单（静态列表） | — |

### 7.2 结论：**没有任何"只读展示槽"或"虚拟槽"的现成实现**

**当前唯一的"假槽位"是 `FlagStackTypedSlot`**，但：

`common/menu/widget/slot/FlagStackTypedSlot.java:25-36`
```java
// 用于标记性槽位的AbstractStackTypedSlot实现
// 注意，标记性槽位必须用于有序容器
public class FlagStackTypedSlot extends AbstractStackTypedSlot
{
    private KeyAmount lastStack = new KeyAmount(ItemStackKey.EMPTY, 0);

    public FlagStackTypedSlot(BDBaseMenu menu, IStackHandler storage, int slotIndex, int xPosition, int yPosition)
    {
        super(menu, storage, slotIndex, xPosition, yPosition);
        setFake(true); // 标记性槽位为假槽位
    }
```
它**不是只读的**：`click` 会修改存储（`FlagStackTypedSlot.java:70-145`，通过 `setStackDirectly` 写入标记），`safeInsert` 也会写入。

**"假槽位"标志 `fake` 也不构成只读保护**：`fake` 只在两处被读取 ——
- `AbstractStackTypedSlot.isFake()`/`setFake()`（`:285-294`）
- 客户端 `BDBaseGUI.slotClicked` 里的一个分支：`if (sSlot.isFake()) {...} else {...}`，而**两个分支的代码完全相同**（`client/gui/BDBaseGUI.java:201-214`）

服务端 `DisorderedStackTypedSlot.click` **完全没有检查 `fake`**（`DisorderedStackTypedSlot.java:49-431`）。

### 7.3 客户端点击这些槽位会发生什么

`AbstractStackTypedSlot` 从原版 `Slot` 继承来的所有"写入"方法都被**刻意废弃**：

`common/menu/widget/slot/AbstractStackTypedSlot.java:307-351`
```java
// 仅对原版slot的重写，但不实际使用它们
// 如果发现意外使用则可能需要重写原版方法
// 大部分情况下，我们仅提供空实现或其他占位实现，因为我们不希望被原版方法干扰

@Override
public void set(@NotNull ItemStack stack)
{
    // 此方法会在AbstractContainerMenu初始化时被数据包处理调用
}

@Override
public void setByPlayer(@NotNull ItemStack newStack, @NotNull ItemStack oldStack)
{
    // 当玩家拿着物品点击这个槽会发生什么
    // 点击事件交由其他函数处理，此处废弃
}

@Override
public int getMaxStackSize() { return Integer.MAX_VALUE; }

@Override
public int getMaxStackSize(@NotNull ItemStack stack) { return Integer.MAX_VALUE; }

@Override
public @NotNull ItemStack remove(int amount)
{
    // 交由点击函数一并处理，此处废弃
    return ItemStack.EMPTY;
}

@Override
public @NotNull ItemStack safeInsert(@NotNull ItemStack stack, int increment)
{
    // 此处废弃
    return stack;
}
```

同时 `BDBaseMenu` 把原版的槽位同步与快速移动都掐断了：

`common/menu/BDBaseMenu.java:63-77`
```java
@Override
public void broadcastChanges()
{
    // 在原版方法上剔除了对AbstractStackTypedSlot的处理
    for (int i = 0; i < this.slots.size(); ++i)
    {
        Slot slot = this.slots.get(i);
        if (slot instanceof AbstractStackTypedSlot)
            continue; // 不允许broadcastChanges自动同步StoredItemStackSlot以便自定义处理
        ...
```

`common/menu/BDBaseMenu.java:336-346`
```java
// 完全重写快速移动方案
@Override
public @NotNull ItemStack quickMoveStack(@NotNull Player player, int slotIndex)
{
    return ItemStack.EMPTY;
}

@Override
public boolean moveItemStackTo(@NotNull ItemStack stack, int startIndex, int endIndex, boolean reverseDirection)
{
    return false;
}
```

`common/menu/BDBaseMenu.java:352-358`
```java
@Override
public boolean canTakeItemForPickAll(@NotNull ItemStack stack, @NotNull Slot slot)
{
    if (!(slot instanceof AbstractStackTypedSlot))
        return super.canTakeItemForPickAll(stack, slot);
    return false;
}
```

**点击流程（客户端 → 服务端）逐条**：

| 客户端动作 | 触发路径 | 服务端结果 |
|---|---|---|
| 左键点空槽（手上有物） | `slotClicked` → `CallSeverClickPacket(slotId, EMPTY, 0, false)` | `DisorderedStackTypedSlot.click` 的 `clickStack.isEmpty()` 分支 → `storage.insert(...)`（`DisorderedStackTypedSlot.java:54-177`） |
| 左键点有物槽（手上为空） | `slotClicked` → `CallSeverClickPacket(slotId, getVanillaActualStack(), 0, false)` | `mayPickup` 分支 → `storage.extract(clickKey, min(amount, maxStack), false, false)` → `menu.setCarried(...)`（`:178-195`） |
| 右键点有物槽（手上为空） | 同上，`mouseButton=1` | 取出 `(n+1)/2`（`:188`） |
| Shift 点任意槽 | `slotClicked` 的 `hasShiftDown()` 分支 → `CallSeverClickPacket(..., shiftDown=true)` | `quickMove(...)`（`:433-510`）：先 `storage.extract`，再塞进 `quickMoveSlotStart/End` 区间（即玩家背包） |
| 中键 / 拖拽 / 数字键换位 / F 键副手 | `BDBaseGUI.mouseDragged`（`:122-142`）对 `AbstractStackTypedSlot` 直接 `return true` 拦截；`checkHotbarKeyPressed`（`:220-250`）对 `AbstractStackTypedSlot` 空实现 | 不生效 |

**`DisorderedStackTypedSlot` 的同步全部由槽位组代管**：

`common/menu/widget/slot/DisorderedStackTypedSlot.java:533-544`
```java
// 无序槽位由槽位组负责处理同步
@Override
public void updateChange()
{

}

@Override
public void loadChange(int where, IStackKey<?> newStack, long newAmount)
{

}
```

（对比 `FlagStackTypedSlot.updateChange` 会自己发 `OrderedStackTypedSlotPacket`：`FlagStackTypedSlot.java:158-169`）

### 7.4 结论与建议

- **没有可复用的"只读/虚拟展示槽"**，需要自己继承 `AbstractStackTypedSlot` 写一个（`isOrdered() → false`，`click()/quickMove()` 空实现，`getStorage()` 返回一个纯客户端视图）。
- **但更省力的做法是复用 `DisorderedStackTypedSlot` + 让虚拟条目"看起来像真的"**，然后在客户端拦截点击（第 6.2 节）。
- 若自建只读槽，注意 `AbstractStackTypedSlot` 的类注释要求宿主 Menu 自行提供 `click()` 调用与同步调用（`AbstractStackTypedSlot.java:19-20`）。

---

## 8. BD 自身的 Mixin 组织方式与可行性边界

### 8.1 BD 的 Mixin 配置

**唯一配置文件**：`src/main/resources/beyonddimensions.mixins.json`（全文 17 行）
```json
{
  "required": true,
  "package": "com.wintercogs.beyonddimensions.mixin",
  "plugin": "com.wintercogs.beyonddimensions.mixin.integration.create.plugin.CreateIntegrationMixinPlugin",
  "mixins": [
    "integration.create.target.SchematicannonBlockEntityMixin"
  ],
  "client": [
    "target.blitSpriteMixin"
  ],
  "server": [
  ],
  "compatibilityLevel": "JAVA_21",
  "injectors": {
    "defaultRequire": 1
  }
}
```

**Mixin 类只有 2 个目标 + 1 个 plugin**：

| 类 | 相对路径 | 归属 |
|---|---|---|
| `CreateIntegrationMixinPlugin` | `src/main/java/com/wintercogs/beyonddimensions/mixin/integration/create/plugin/CreateIntegrationMixinPlugin.java`（58 行） | Mixin plugin，按 Create 是否加载决定是否应用 |
| `SchematicannonBlockEntityMixin` | `src/main/java/com/wintercogs/beyonddimensions/mixin/integration/create/target/SchematicannonBlockEntityMixin.java`（34 行） | 通用 mixin 组 |
| `blitSpriteMixin` | `src/main/java/com/wintercogs/beyonddimensions/mixin/target/blitSpriteMixin.java`（42 行） | 客户端 mixin 组 |

**组织方式特点**：
- 目录按**用途**分：`mixin/target/`（原版目标）、`mixin/integration/<模组名>/target/`（跨模组兼容目标）、`mixin/integration/<模组名>/plugin/`（条件 plugin）。
- 用 **Mixin plugin** 做模组软依赖判定，而不是 `@Pseudo`/`require = 0`。
- `"required": true` + `"defaultRequire": 1`：**注入失败会硬报错**，BD 对 mixin 稳定性要求高。
- 向 FML 声明的方式是 `src/main/templates/META-INF/neoforge.mods.toml:50-52`（Processing 模板，由 `neoForge.ideSyncTask generateModMetadata`，`build.gradle:295`，在构建时展开 `${mod_id}`）：
```toml
# The [[mixins]] block allows you to declare your mixin config to FML so that it gets loaded.
[[mixins]]
config="${mod_id}.mixins.json"
```

### 8.2 构建配置

`build.gradle:72-83`（节选）
```gradle
neoForge {
    parchment {
        mappingsVersion = project.parchment_mappings_version
        minecraftVersion = project.parchment_minecraft_version
    }
    // accessTransformers = project.files('src/main/resources/META-INF/accesstransformer.cfg')
```
- 使用 **ModDevGradle**（`neoForge {}` 块）+ Parchment 映射（`gradle.properties:10-11`，`2024.11.17` for 1.21.1）。
- **AccessTransformer 被注释掉了** —— BD 没有用 AT 打开原版字段。
- `gradle.properties:21`：`neo_version=21.1.234`；`:30`：`mod_version=0.7.30`；`:35-41` 列出全部集成模组版本。

### 8.3 附属模组用 Mixin 的可行性边界（简要）

| 事项 | 结论 |
|---|---|
| 技术上可行？ | 是。NeoForge 1.21.1 用 Mixin（`MixinConfigs` 声明在 `neoforge.mods.toml`，配 `*.mixins.json`），与 BD 的做法一致 |
| BD 的类是 `final` 吗？ | `DimensionsNetMenu`、`BDBaseGUI`、`ClientNetStorage`、`AbstractUnorderedStackHandler` 都**不是 final**（`public class ...` / `public abstract class ...`），可以继承/混入 |
| 目标方法可注入吗？ | `buildIndexList()` / `updateViewerStorage(boolean)` / `loadSearchText(String)` 均为 `public`（`DimensionsNetMenu.java:229/241/291`）；`BDBaseGUI.slotClicked` 为 `protected`（`BDBaseGUI.java:153`）；`ClientNetStorage.buildSortedIndex` 为 `public`（`ClientNetStorage.java:159`）。Mixin 可注入 |
| 需要注意的 `private` 成员 | `ClientNetStorage.cacheIndexes`（`private`，`:45`）、`pendingCache`（`private final`，`:33`）、`mustUpdateAllFromSource`（`private`，`:35`）需要 `@Shadow`/`@Accessor`，或改用 `@Invoker`/反射 |
| 字段名稳定性 | 附属模组编译期可依赖 `@Shadow`（编译时字段名来自 Parchment 映射）；**若 BD 升级改了字段名，附属需重新编译**。风险中等 |
| 与 BD 自身 mixin 冲突 | BD 的 mixin 只针对 Create 和原版 `blitSprite`，**不针对自己的类**，因此不会有同方法竞争 |
| 是否要 mixin 原版类 | 不必。虚拟条目走 BD 自己的类即可 |
| 分发建议 | 用 `@Mixin(value = DimensionsNetMenu.class, remap = false)`（BD 的类不需要 remap） |
| 兼容性边界 | 若 BD 后续把 `buildIndexList` 改名/改签名，`defaultRequire = 1` 会让附属启动失败；建议附属用 `require = 0` 并在失败时降级 |

---

## 9. 对附属模组的实现建议

### 9.1 推荐方案：**Mixin 客户端视图注入 + 自定义 c2s 兑换包 + `UnifiedStorageBeforeExtractHandler` 服务端落地**

#### 架构分层

```
┌─ 服务端 ────────────────────────────────────────────────────────────┐
│ 1. ProjectE 知识数据 / EMC 余额                                     │
│    · EMC 余额建议存成一个真实资源 key（照抄 EnergyStackKey）        │
│      → 可直接在存储网格里显示余额，也可被管道/机器访问              │
│    · 已学习物品清单 = ProjectE 知识（服务端权威）                   │
│ 2. 兑换逻辑                                                        │
│    · 注册 UnifiedStorageBeforeExtractHandler：                      │
│      当 extract 的是"网络里没有、但玩家已学习"的 ItemStackKey 时，  │
│      改为：扣 EMC（extract EmcStackKey）→ 返回物品                  │
│    · 这样 getStackBySlot / 提取 的语义都被覆盖，复用 BD 原路径      │
│ 3. 自定义 S2C 包：同步每个已学习物品的 (itemId → emcPrice)          │
│    · 只在菜单打开时发一次（数据量大，可分片）                       │
│    · 或复用 BD 的 SlotGroupSync 机制自加一个 groupId=1 的同步器     │
└─────────────────────────────────────────────────────────────────────┘
┌─ 客户端 ────────────────────────────────────────────────────────────┐
│ 4. Mixin DimensionsNetMenu.buildIndexList (HEAD)                     │
│    · 在 clientNetStorage 里补写虚拟条目：                            │
│      · 有库存 → 用真实数量（BD 已经写了，不重复写）                  │
│      · 无库存 but 已学习 → setAmountByKey(key, emcAvailable)         │
│    · 同时 Mixin updateViewerStorage (RETURN) 再补一次，              │
│      防止 resolvePendingOrAllUpdate 把虚拟数量覆写成 0               │
│    · 需要时 @Shadow / @Accessor 把 cacheIndexes 置 null 以破排序缓存  │
│ 5. Mixin BDBaseGUI.slotClicked (HEAD, cancellable)                   │
│    · 命中虚拟条目 → cancel 原逻辑，改发 ExchangeWithEmcPacket        │
│      (slotIndex, isVirtual=true, button)                            │
│ 6. 自定义 IStackRender（可选）                                       │
│    · 让虚拟条目的数量显示为特殊颜色 / 追加 "EMC 可兑换" tooltip      │
└─────────────────────────────────────────────────────────────────────┘
```

#### 关键实现要点（附证据）

1. **虚拟条目必须以"真实 key"形式存在**：直接用 `ItemStackKey`（已注册，`api/storage/key/impl/ItemStackKey.java:39`），**不要**新建一个 key 类型 —— 这样 `DisorderedStackTypedSlot.click` 的 `instanceof ItemStackKey` 判断（`DisorderedStackTypedSlot.java:183`）能通过，渲染器也能用现成的 `ItemStackKeyRender`。

2. **写入位置必须是 `clientNetStorage`，绝不能是 `menu.storage`**：
   - `menu.storage` 是服务端同步镜像，写进去会在下次 delta 同步时被覆盖（`ClientNetStorage.java:131-142`）；
   - 屏幕槽位持有的是 `clientNetStorage`（`DimensionsNetMenu.java:124`），渲染读的是它。

3. **数量语义**：`setAmountByKey(key, 0)` 在 `ClientNetStorage`（`KEEP_ZERO`）下会保留条目（`AbstractUnorderedStackHandler.java:443-454`），但 `ClientNetStorage.buildSortedIndex` 会 `if (ka == null || ka.isEmpty()) continue;`（`ClientNetStorage.java:191`）把 0 数量条目**排除出列表**。**所以"库存 0 但可兑换"的条目必须给一个 > 0 的虚拟数量**（即"EMC 可兑换数量"），这正好符合需求。若 EMC 不足 1 个，则该物品不显示（或给 1 并标记为不可兑换）。

4. **搜索兼容**：虚拟条目也会被 `ClientNetStorageSearchHelper.matches` 过滤。若希望"搜索只影响真实库存"，需要在补写时判断 `searchText` 是否为空（可 `@Shadow` 读 `private String searchText`，`DimensionsNetMenu.java:40`）。

5. **排序兼容**：`SORT_INSERTED_TIME` / `SORT_MODIFIED_TIME` 对虚拟条目会取到 0（因为服务端没发时间戳），会排在最后/最前。可以主动调 `clientNetStorage.setCreationTime/setLastModifiedTime` 补一个值（这两个方法是 public，`AbstractUnorderedStackHandler.java:175-186`）。

6. **Shift 点击（快速转移）**：`DisorderedStackTypedSlot.quickMove` 也走 `storage.extract`（`:456`、`:470`），因此**服务端的 `BeforeExtract` 钩子同样能覆盖 Shift 路径**。但客户端仍需拦截 `slotClicked` 的 shift 分支（`BDBaseGUI.java:163-198`）以免发出错误的 `clickItem`（此时 `clickItem` 取自 `getVanillaActualStack()`，虚拟条目也能取到值，其实可以放过 —— **这里存在一个更省事的变体：不拦客户端点击，只靠服务端 `BeforeExtract` 钩子**，见下表"方案 E"）。

7. **容量风险**：`indexList` 长度 = `getLines()*9`，最大 891（`DimensionsNetMenu.java:257`、`DimensionsNetGUI.java:197-203`）。ProjectE 已学习物品常有数百～上千，**建议给虚拟条目加"只显示前 N 个 / 按 EMC 价格排序"的裁剪策略**。

8. **性能**：`buildIndexList` 在每次搜索输入、排序切换、翻页、delta 同步后都会调用（`DimensionsNetGUI.java:137/147/184/260`）。虚拟条目列表建议在客户端缓存，只在 EMC 余额/学习清单变化时重算。

### 9.2 工作量与风险对比表

| 方案 | 工作量 | 能否满足全部需求 | 主要风险 | 推荐度 |
|---|---|---|---|---|
| **A. Mixin 客户端视图注入 + 自定义 c2s 兑换包** | ★★☆（2-4 人日） | ✅ 是 | 依赖 BD 内部字段/方法名；`resolvePendingOrAllUpdate` 会覆写虚拟数量（需双注入点）；`cacheIndexes` 缓存需破；891 条目上限 | ⭐⭐⭐⭐⭐ **推荐** |
| **B. 方案 A 的简化变体：只 Mixin 视图注入，不拦点击，靠服务端 `UnifiedStorageBeforeExtractHandler` 兑换** | ★☆☆（1-2 人日） | ✅ 是（点击/Shift 都能兑换） | 客户端会在虚拟条目上发 `CallSeverClickPacket`，服务端 `DisorderedStackTypedSlot.click` 会先 `storage.extract(...)` 拿到 0 数量 → **在钩子生效前就返回空了**。需要在钩子里让 `extract` 返回非空物品，但 `click` 取的是 `clickStack.amount()` 的 `min(..., getVanillaMaxStackSize())`，仍可能失败 | ⭐⭐⭐⭐ **次推荐（需实测）** |
| **C. 自写 Screen/Menu** | ★★★★★（1-2 周） | ✅ 是 | 重写搜索/排序/滚动/翻页/JEI/EMI 集成；与 BD 版本脱节；不受"不改源码"约束 | ⭐⭐ 仅当 A 不可行时 |
| **D. 注册 EMC 为真实资源 key** | ★★★（3-5 人日） | ❌ 做不到"已学习但 0 库存"与"可兑换数量" | 0 数量被 `RemoveZero` 清除；一 key 一 amount；存档兼容风险 | ⭐⭐⭐ 作为 A 的**服务端 EMC 余额载体**很有价值，但不单独用 |
| **E. 纯 API（订阅 + 自建覆盖层 Screen）** | ★★★★ | ⚠️ 只能做"叠加面板"，不能进 BD 列表 | BD 无 GUI 事件（第 6.1 节），拿不到 `menu` 实例；只能另开一个 Screen | ⭐⭐ |

### 9.3 落地步骤清单（推荐方案 A）

| # | 步骤 | 涉及 BD 扩展点/注入点 |
|---|---|---|
| 1 | 服务端：把网络 EMC 余额落成资源 key（照抄 `EnergyStackKey`/`EnergyType`/`EnergyStackKeyRender`，`api/longtype/EnergyType.java`、`api/storage/key/impl/EnergyStackKey.java`、`api/storage/key/render/EnergyStackKeyRender.java`），在 `FMLCommonSetupEvent` 里 `StackKeyRegistry.registerType(...)` | `StackKeyRegistry.registerType`（`api/storage/key/StackKeyRegistry.java:14`） |
| 2 | 服务端：注册兑换前置钩子 `UnifiedStorageBeforeExtractHandler.addHandler(...)` | `api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java:46` |
| 3 | 服务端：新增 S2C 包（已学习物品 → EMC 单价表 + 当前 EMC 余额），在菜单打开后发一次 | 参考 `common/init/BDPackets.java:20-188` 的 `register` 写法 |
| 4 | 客户端：Mixin `DimensionsNetMenu.buildIndexList`（HEAD）补写虚拟条目 | `common/menu/DimensionsNetMenu.java:241` |
| 5 | 客户端：Mixin `DimensionsNetMenu.updateViewerStorage`（RETURN）再补一次（防覆写） | `common/menu/DimensionsNetMenu.java:229` |
| 6 | 客户端：Mixin `BDBaseGUI.slotClicked`（HEAD, cancellable）拦截虚拟槽，改发自己的包 | `client/gui/BDBaseGUI.java:153` |
| 7 | 客户端（可选）：为虚拟条目配置 `IStackRender` 或在 tooltip 中标注"EMC 兑换" | `api/storage/key/IStackRender.java:20`、`api/storage/key/render/ItemStackKeyRender.java:87-98` |
| 8 | 服务端：处理自定义包 → 二次校验（登录态、已学习、EMC 足够）→ `extract(EmcKey, price, false, false)` → `insert(ItemKey, 1, false)` → `player` 拿物品 | `api/dimensionnet/UnifiedStorage.java:104-133` |
| 9 | 打包：`neoforge.mods.toml` 里声明自己的 `xxx.mixins.json`，`@Mixin(value = DimensionsNetMenu.class, remap = false)`，`require = 0` 以优雅降级 | 参考 `src/main/resources/beyonddimensions.mixins.json` |

---

## 附录 A：证据索引

### A.1 第 1 节（打开界面链路）

| 结论 | 证据 |
|---|---|
| `O` 键定义 | `client/init/BDShortKeys.java:40-44` |
| 按键轮询 | `client/event/listener/ShortKeysListener.java:13-17`、`client/init/BDShortKeys.java:95-106` |
| 发 `OpenNetGuiPacket` | `client/init/BDShortKeys.java:122-138` |
| 服务端开菜单 | `network/packet/c2s/OpenNetGuiPacket.java:60-82` |
| 取网络 | `api/dimensionnet/DimensionsNet.java:208-211`、`:821` |
| 取真存储 | `api/dimensionnet/UnifiedStorage.java:22` |
| Screen 注册 | `client/init/BDScreens.java:18` |
| 客户端空壳存储 | `common/menu/DimensionsNetMenu.java:59-63` |
| 客户端视图创建 | `common/menu/DimensionsNetMenu.java:82-92` |

### A.2 第 2 节（同步机制）

| 结论 | 证据 |
|---|---|
| 包注册总表 | `common/init/BDPackets.java:20-188` |
| 同步器安装 | `common/menu/DimensionsNetMenu.java:94-103` |
| 增量/合并/分包注释 | `common/menu/widget/slot/DisorderedSlotGroupSync.java:17-22`、`:23-26` |
| 服务端订阅 | `common/menu/widget/slot/DisorderedSlotGroupSync.java:62-69` |
| Any/Delta 回调 | `common/menu/widget/slot/DisorderedSlotGroupSync.java:111-130` |
| 首次全量 | `common/menu/widget/slot/DisorderedSlotGroupSync.java:134-148` |
| 全量对比/增量发送 | `common/menu/widget/slot/DisorderedSlotGroupSync.java:153-225` |
| 包字段 | `network/packet/s2c/DisorderedSlotGroupSyncPacket.java:19-51` |
| 客户端应用 | `network/packet/s2c/DisorderedSlotGroupSyncPacket.java:53-66`、`common/menu/widget/slot/DisorderedSlotGroupSync.java:317-344` |
| 每 tick 驱动 | `common/menu/BDBaseMenu.java:94-112`、`:158-164` |
| `UiTimestampPolicy` 定义 | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:39-41` |
| 时间表定义 | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:60-68` |
| AUTO 写入点 | 同上 `:436-439`、`:447-450`、`:465-468`、`:499-502`、`:508-512`、`:557-560`、`:651-654`、`:669-672`、`:739-743`、`:972-977`；移除 `:763-765` |
| 客户端策略 NONE | `common/menu/DimensionsNetMenu.java:62`、`common/menu/widget/ClientNetStorage.java:53` |
| Any/Delta 机制 | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:141-158`、`:262-338`、`:309-370` |
| `UnifiedStorage.onChange` | `api/dimensionnet/UnifiedStorage.java:75-84` |
| `ClientNetStorage` 订阅 | `common/menu/widget/ClientNetStorage.java:50-59`、`:72-126` |
| Shift = onlyAmountUpdate | `common/menu/DimensionsNetMenu.java:99`、`client/gui/DimensionsNetGUI.java:542-555` |

### A.3 第 3 节（列表构建）

| 结论 | 证据 |
|---|---|
| 搜索只在客户端 | `common/menu/DimensionsNetMenu.java:291-297` |
| `buildIndexList` 客户端校验 | `common/menu/DimensionsNetMenu.java:241-251` |
| 过滤在同步落地时 | `common/menu/widget/ClientNetStorage.java:131-154`、`:258-261` |
| 搜索语法 | `common/menu/widget/ClientNetStorageSearchHelper.java:156-227` |
| 拼音匹配 | `common/menu/widget/ClientNetStorageSearchHelper.java:271-298` |
| 排序实现 | `common/menu/widget/ClientNetStorage.java:159-252`、`:266-281` |
| 排序缓存 | `common/menu/widget/ClientNetStorage.java:45`、`:161-165`、`:122-125` |
| 列表项结构 | `common/menu/DimensionsNetMenu.java:253-284`、`api/storage/key/KeyAmount.java:16`、`common/menu/widget/slot/AbstractStackTypedSlot.java:49` |
| 891 个真实槽位 | `common/menu/DimensionsNetMenu.java:113-148` |
| 行数上限 | `common/menu/DimensionsNetMenu.java:36-39`、`client/gui/DimensionsNetGUI.java:104-112`、`:197-203`、`:420-423` |
| 滚动条/翻页 | `client/gui/DimensionsNetGUI.java:300-318`、`:354`、`common/menu/DimensionsNetMenu.java:306-317` |
| 搜索框响应 | `client/gui/DimensionsNetGUI.java:248-288` |
| 排序按钮触发 | `client/gui/DimensionsNetGUI.java:137`、`:147`、`:184` |

### A.4 第 4 节（点击取出）

| 结论 | 证据 |
|---|---|
| 客户端点击入口 | `client/gui/BDBaseGUI.java:153-217` |
| c2s 包 | `network/packet/c2s/CallSeverClickPacket.java:15-47` |
| 服务端分发 | `common/menu/BDBaseMenu.java:178-199` |
| 取出分支 | `common/menu/widget/slot/DisorderedStackTypedSlot.java:178-195` |
| Shift 快速转移 | `common/menu/widget/slot/DisorderedStackTypedSlot.java:433-510` |
| `extractByKey` 空存储返回 | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:640-644` |
| `UnifiedStorage.extract` 钩子 | `api/dimensionnet/UnifiedStorage.java:104-133` |
| `BeforeExtractHandler` 注册 | `api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java:23-49` |
| 包裹注册为双向 | `common/init/BDPackets.java:36-43` |

### A.5 第 5 节（渲染）

| 结论 | 证据 |
|---|---|
| 覆写 `renderSlot` | `client/gui/BDBaseGUI.java:66-94` |
| 取数入口 `getStack()` | `common/menu/widget/slot/AbstractStackTypedSlot.java:185-192` |
| 槽位持有 `clientNetStorage` | `common/menu/DimensionsNetMenu.java:124` |
| Tooltip 渲染 | `client/gui/BDBaseGUI.java:48-64` |
| `IStackRender` 接口 | `api/storage/key/IStackRender.java:20-59` |
| 物品渲染器 | `api/storage/key/render/ItemStackKeyRender.java:28-118` |
| 非物品渲染器范例 | `api/storage/key/render/EnergyStackKeyRender.java:36-56` |
| 渲染器分发 | `api/storage/key/impl/ItemStackKey.java:456`、`api/storage/key/impl/EnergyStackKey.java:176-180` |
| `KeyAmount.isEmpty` | `api/storage/key/KeyAmount.java:204-207` |
| `getVanillaMaxStackSize` 用途 | `common/menu/widget/slot/DisorderedStackTypedSlot.java:187` |
| `IStackKey` 全接口 | `api/storage/key/IStackKey.java:20-212` |
| `StackKeyRegistry` | `api/storage/key/StackKeyRegistry.java:10-37` |

### A.6 第 6 节（扩展性）

| 结论 | 证据 |
|---|---|
| 事件类全部清单 | `api/event/dimensionnet/DimensionsNetEvent.java:33/46/124/168`、`NetedBlockEvent.java:72/85`、`NetedItemEvent.java:65/77` |
| 事件触发点 | `api/dimensionnet/DimensionsNet.java:144`、`:526`、`:570`、`:589`、`:626`、`:677`、`:757`、`:787`；`common/block/entity/NetedBlockEntity.java:59`、`:65`；`common/item/NetedItem.java:99`、`:112` |
| 无 GUI 事件 | 全仓库 `ScreenEvent|ContainerScreenEvent` 搜索结果为空（仅 `RegisterMenuScreensEvent`/`RegisterKeyMappingsEvent`/`ClientTickEvent`，见 `client/init/BDScreens.java:9`、`client/init/BDShortKeys.java:26`、`client/event/listener/ShortKeysListener.java:8`） |
| 内置类型注册 | `BeyondDimensions.java:61-68` |
| 集成模组注册范例 | `integration/module/ars/ArsModule.java:53-55`、`integration/module/mekanism/MekModule.java:34`、`integration/module/ifs/IFSModule.java:49`、`integration/module/botania/BotaniaModule.java:63` |
| 能力/包装注册 | `BeyondDimensions.java:70-89` |
| `RemoveZero` 会删 0 值 | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:427-442`；`api/dimensionnet/UnifiedStorage.java:22` |
| 新 key 会被 JEI/EMI 遍历 | `integration/module/emi/slothandler/SlotDragHandler.java:71-76`、`integration/module/jei/NetInterfaceGhostHandler.java:71` |
| 存储槽位/容量限制 | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:459-462`、`:539-548` |

### A.7 第 7 节（槽位类）

| 结论 | 证据 |
|---|---|
| 基类与"非 BD 菜单需自行适配"警告 | `common/menu/widget/slot/AbstractStackTypedSlot.java:17-22` |
| `theSlot` / `fake` / `active` | `common/menu/widget/slot/AbstractStackTypedSlot.java:49/54/59` |
| 原版写入方法被废弃 | `common/menu/widget/slot/AbstractStackTypedSlot.java:307-351` |
| `FlagStackTypedSlot` 是假槽 | `common/menu/widget/slot/FlagStackTypedSlot.java:25-36` |
| `fake` 不提供只读保护 | `common/menu/widget/slot/AbstractStackTypedSlot.java:285-294`、`client/gui/BDBaseGUI.java:201-214`、`common/menu/widget/slot/DisorderedStackTypedSlot.java:49-431` |
| `broadcastChanges` 跳过 | `common/menu/BDBaseMenu.java:63-77` |
| `quickMoveStack`/`moveItemStackTo` 被禁 | `common/menu/BDBaseMenu.java:336-346` |
| `canTakeItemForPickAll` 被禁 | `common/menu/BDBaseMenu.java:352-358` |
| 无序槽位同步由槽位组代管 | `common/menu/widget/slot/DisorderedStackTypedSlot.java:533-544` |
| 有序槽位自带同步 | `common/menu/widget/slot/FlagStackTypedSlot.java:158-169`、`network/packet/s2c/OrderedStackTypedSlotPacket.java:20-46` |
| 槽位组接口 | `common/menu/widget/slot/SlotGroupSync.java:10-31` |
| `groupId` = 列表下标 | `common/menu/BDBaseMenu.java:50-53` |

### A.8 第 8 节（Mixin）

| 结论 | 证据 |
|---|---|
| 唯一 mixin 配置 | `src/main/resources/beyonddimensions.mixins.json:1-17` |
| Mixin 向 FML 的声明 | `src/main/templates/META-INF/neoforge.mods.toml:50-52`、`build.gradle:295` |
| Mixin 类仅 3 个 | `mixin/integration/create/plugin/CreateIntegrationMixinPlugin.java`(58 行)、`mixin/integration/create/target/SchematicannonBlockEntityMixin.java`(34 行)、`mixin/target/blitSpriteMixin.java`(42 行) |
| 构建配置 | `build.gradle:72-83`、`gradle.properties:10-11`、`:21`、`:30` |
| AT 未启用 | `build.gradle:82`（被注释） |

---

## 附录 B：未确认项

以下项目本次源码阅读**未能确认**，需要后续实测或进一步阅读：

1. ~~**服务端 `UnifiedStorage` 实际的 `UiTimestampPolicy`**~~ —— **已确认**：`api/dimensionnet/DimensionsNet.java:104` 显式传入 `UiTimestampPolicy.AUTO`，因此 `SORT_INSERTED_TIME` / `SORT_MODIFIED_TIME` 对真实条目有效可用。~~若为 `NONE`...~~（此疑点已排除）
2. **`menu.slotGroupSyncs` 的 `groupId` 是否总能安全复用**：`addSlotGroupSync` 会在 `DimensionsNetMenu` 构造早期被调用一次（`:94`），因此附属若在 `@Inject(at = @At("TAIL"))` 里追加，`groupId` 应为 1。**但 BD 未来若增加同步器，编号会改变**，附属不应硬编码。
3. **`ClientNetStorage.setAmountByKey` 补写虚拟条目后，是否会因为 `onContentChanged` → `fireDelta` 触发任何自反馈**：`ClientNetStorage` 的 delta 订阅者是自己（`subscribeDeltaWeak(this, ClientNetStorage::loadFromDeltaSubscription)`，`ClientNetStorage.java:58`），补写会往 `pendingCache` 里塞自己 —— 但 `resolvePendingOrAllUpdate` 只在 `updateViewerStorage` 时被调用，因此**应该**只是无害的自我标记。**未实测确认**在真实运行中不会造成抖动。
4. **`getLines()*9` 之外的数据是否真的完全不可见**：`updateScrollLineData` 允许 `maxLineData > 0`，但 `indexList` 只填 `getLines()*9` 项，理论上 `lineData` 只能滚到 `maxLineData`，而 `maxLineData = ceil(size/9) - getLines()`，**看起来**是能滚到最后一行的。但 `buildIndexList` 里 `i + lineData * 9 < indexes.size()` 的边界与 `indexList.size() == getLines()*9` 的配合**未逐值验证**；若列表长度远超 `getLines()*9`，需要实测确认是否能看到全部条目。
5. **`BDBaseGUI.slotClicked` 的 Mixin 注入是否会在 `slot == null` 时出问题**：原方法在 `if (!(slot instanceof AbstractStackTypedSlot)) super.slotClicked(...)` 之后才有 `if (slot == null) return;`（`BDBaseGUI.java:155-159`），即**原代码本身就存在 null 时序问题**（`super.slotClicked` 会先拿到 null）。在 HEAD 注入并 `cancellable` 时需自行做 null 判断。
6. **ProjectE 侧接口**：本调研只覆盖 BD 侧。ProjectE 的"已学习物品"与 EMC 数值读取方式（`IKnowledgeProvider`、`EMCProxy.get().getValue(...)`、`PlayerKnowledge` 等）**未调研**。
7. **JEI/EMI 幽灵拖拽与虚拟条目的交互**：`SlotDragHandler` 会遍历 `StackKeyRegistry.getAllTypes()` 并 `fromSourceObject(...)`（`integration/module/emi/slothandler/SlotDragHandler.java:71-76`）。若虚拟条目用标准 `ItemStackKey`，则拖拽行为与真实条目完全一致（**这是期望行为，但未实测**）。
8. **`displayAmount`（用 EMC 可兑换数量）的计算时机**：EMC 余额变化时（例如其他机器消耗/生产 EMC），客户端 `clientNetStorage` 里的虚拟数量需要重算。是选择在每次 `buildIndexList` 重算（简单但可能性能差），还是订阅 EMC 存储的 delta（`subscribeDeltaWeak`）——**未做设计决策**。
