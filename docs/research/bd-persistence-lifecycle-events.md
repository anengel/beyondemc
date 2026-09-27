# Beyond Dimensions（超越维度）维度网络：持久化 / 生命周期 / 事件 调研报告

> 调研对象：`reference/BeyondDimensions`
> 分支 `1.21.1`，HEAD `a0d2e76fcce7fc596503dcf4e5efaf5109fe573b`（2026-09-05），`mod_version=0.7.30`
> 加载器 NeoForge `21.1.234`（MC 1.21.1），包名 `com.wintercogs.beyonddimensions`
> 全文证据格式为 `相对路径:行号`，相对路径基准为 `reference/BeyondDimensions/`
> 本次调研只读，未修改 `reference/` 下任何文件（`git status --porcelain` 输出为空）

---

## 目录

- [0. 结论速览](#0-结论速览)
- [1. DimensionsNet 完整结构与公开方法](#1-dimensionsnet-完整结构与公开方法)
- [2. 网络数据的持久化机制](#2-网络数据的持久化机制)
- [3. 网络 ID、玩家绑定与网络生命周期](#3-网络-id玩家绑定与网络生命周期)
- [4. 扩展点评估：EMC 数值池放哪里](#4-扩展点评估emc-数值池放哪里)
- [5. api/event/dimensionnet 事件全清单与订阅方式](#5-apieventdimensionnet-事件全清单与订阅方式)
- [6. BD 初始化入口与注册时机](#6-bd-初始化入口与注册时机)
- [7. BD 是否发布 Maven 构件](#7-bd-是否发布-maven-构件)
- [8. build.gradle 构建参考](#8-buildgradle-构建参考)
- [9. 对附属模组的实现建议](#9-对附属模组的实现建议)
- [10. 未确认事项](#10-未确认事项)

---

## 0. 结论速览

| 问题 | 结论 | 关键证据 |
| --- | --- | --- |
| 网络对象是什么 | `DimensionsNet extends SavedData`，**每个网络就是一个独立的 SavedData 文件** | `src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/DimensionsNet.java:37` |
| 数据存哪 | Overworld 的 `DimensionDataStorage`，文件名 `BDNet_<id>` → `<world>/data/BDNet_<id>.dat` | `DimensionsNet.java:137`、`NetRegistryIndex.java:217` |
| 怎么触发写盘 | `setDirty()` = 原版 `SavedData` 机制（自动存档/`save-all`/关服时由原版写盘），BD 自己没有显式保存调用 | `DimensionsNet.java:394-436`、`UnifiedStorage.java:76-84` |
| 网络存储对象 | `UnifiedStorage`（无序、到 0 即删），由 `DimensionsNet` 强持有 | `DimensionsNet.java:81,104,821-824` |
| 官方扩展路径 | **注册自定义 `IStackKey` 资源类型**（BD 自己的 5 个集成模块全部这么做） | `BeyondDimensions.java:65-68`、`BotaniaModule.java:63` |
| DataAttachment 能否挂 DimensionsNet | **不行**（21.1 的 attachment 只支持 BlockEntity/Chunk/Entity，且要求实现 `IAttachmentHolder`；`DimensionsNet` 是普通 `SavedData`） | 见 [4.3](#43-方案-cneoforge-dataattachment) |
| 事件总线 | 全部走 `NeoForge.EVENT_BUS`（game bus），事件**不可取消**（未实现 `ICancellableEvent`） | `DimensionsNetEvent.java:14`、`NetedBlockEvent.java:18`、`NetedItemEvent.java:18` |
| Maven 构件 | **未发布 Maven**，只有本地 `file://` 仓库 + CurseForge/Modrinth/GitHub Release 发布 | `build.gradle:298-309`、`.github/workflows/build-and-publish.yml:94-124` |
| 推荐方案 | 首选 **(a) 自定义 `IStackKey`**；若 EMC 必须是"不可被物流系统抽取的私有数值"则用 **(b) 独立 SavedData + net id 作 key** | 见 [9](#9-对附属模组的实现建议) |

> ⚠️ 一个容易踩的坑：`README.md:65-66` 提到的 `UnifiedStorage.typedHandlerMap` / `StackTypedHandler.typedHandlerMap` **在 0.7.30 源码中已不存在**（全仓库 grep 无匹配）。当前实际对应物是 `CapabilityHelper.USHandlerMap` / `CommonHandlerMap`（静态 Map）与 `AbstractUnorderedStackHandler.type2buckets`（实例 Map）。README 的 API 说明与类路径（`Api/DataBase/...`）均已过时，**不要照 README 抄**。

---

## 1. DimensionsNet 完整结构与公开方法

**文件**：`src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/DimensionsNet.java`（897 行，约 29KB）

### 1.1 类声明与常量

```java
// DimensionsNet.java:37-42
public class DimensionsNet extends SavedData
{
    static final String NET_DATA_PREFIX = "BDNet_";
    public static final int NO_PRIMARY_NET_ID = -1;
    public static final int MAX_NETWORK_NAME_LENGTH = 48;
    private static final String CUSTOM_NAME_TAG = "custom_name";
```

### 1.2 字段清单

| 字段 | 类型 | 行号 | 说明 | 是否持久化 |
| --- | --- | --- | --- | --- |
| `id` | `int` | `49` | 网络唯一 id，从 0 递增；被删除的网络写死为 `-99` | ✅ `Id` |
| `customName` | `String` | `54` | 玩家自定义网络名，空串表示未命名 | ✅ `custom_name` |
| `deleted` | `boolean`（**public**） | `61` | 删除标记，删除后 SavedData 仍可被取到但不可使用 | ✅ `Deleted` |
| `owner` | `UUID` | `66` | 网络所有者 | ✅ `Owner` |
| `managers` | `final Set<UUID>` | `71` | 管理员（含所有者），`HashSet` | ✅ `Managers`（StringTag 列表） |
| `players` | `final Set<UUID>` | `76` | 成员（含管理员），`HashSet` | ✅ `Players` |
| `unifiedStorage` | `final @NotNull UnifiedStorage` | `81` | 通用存储空间 | ✅ `UnifiedStorage` 复合标签 |
| `temporary` | `final boolean` | `88` | 临时网络（客户端菜单用），不跑倒计时逻辑 | ❌ **不持久化**（构造参数） |
| `currentTime` | `int` | `95` | 生成"破碎时空结晶"的倒计时 | ✅ `currentTime` |

### 1.3 构造函数

```java
// DimensionsNet.java:102-107
public DimensionsNet(boolean temporary)
{
    unifiedStorage = new UnifiedStorage(this, AbstractUnorderedStackHandler.UiTimestampPolicy.AUTO);
    NeoForge.EVENT_BUS.addListener(this::onServerTick);
    this.temporary = temporary;
}
```

两个必须注意的副作用：

1. **每个实例都会向 game bus 注册一个监听器**（`:105`），且**从不注销**。网络被删除（`destroySelf()`）后对象仍留在内存与事件总线上 → 附属模组如果自行 `new DimensionsNet(true)` 会持续累积监听器（BD 自己在 `NetControlMenu.java:27` 就有一个 `private DimensionsNet net = new DimensionsNet(true);`）。
2. `UiTimestampPolicy.AUTO` 是硬编码的，`setSlotCapacity` / `setSlotMaxSize` 之外无法从外部改策略。

工厂与创建：

```java
// DimensionsNet.java:114-117 —— SavedData 的工厂方法
public static DimensionsNet create() { return new DimensionsNet(false); }

// DimensionsNet.java:127-149 —— 创建持久化网络（服务端）
public static @Nullable DimensionsNet createNewNetForPlayer(Player player, long defaultSlotCapability, int defaultSlotMaxSize)
{
    DimensionsNet net = DimensionsNet.getNetFromPlayer(player);
    if (net != null) return net;                       // 已有主网络 → 直接返回，不新建

    MinecraftServer server = player.getServer();
    if (server != null)
    {
        int allocatedNetId = NetRegistryIndex.get(server).allocateNetId(server);
        String netDataName = DimensionsNet.buildNetDataName(allocatedNetId);
        DimensionsNet newNet = server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(DimensionsNet::create, DimensionsNet::load), netDataName);
        newNet.setId(allocatedNetId);
        NetRegistryIndex.get(server).registerNet(server, allocatedNetId);
        newNet.setOwner(player.getUUID(), false);
        newNet.setDirty();
        newNet.unifiedStorage.setSlotCapacity(defaultSlotCapability);
        newNet.unifiedStorage.setSlotMaxSize(defaultSlotMaxSize);
        NeoForge.EVENT_BUS.post(new DimensionsNetEvent.Created(newNet));   // ← 唯一的 Created 触发点
        return newNet;
    }
    return null;
}
```

> **扩展点提示**：`Created` 事件只在"新建"路径派发，**`load()` 路径不派发任何事件**。附属模组不能依赖该事件枚举已有网络。

### 1.4 序列化 / 反序列化

| 方法 | 行号 | 说明 |
| --- | --- | --- |
| `static DimensionsNet load(CompoundTag, HolderLookup.Provider)` | `344-388` | SavedData 反序列化工厂 |
| `@Override CompoundTag save(CompoundTag, HolderLookup.Provider)` | `393-436` | SavedData 序列化 |

关键片段：

```java
// DimensionsNet.java:344-367（load）
DimensionsNet net = new DimensionsNet(false);
net.id = tag.getInt("Id");
if (tag.contains(CUSTOM_NAME_TAG)) net.customName = sanitizeCustomName(tag.getString(CUSTOM_NAME_TAG));
UUID owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
if (owner != null) net.owner = owner;
net.unifiedStorage.deserializeNBT(registryAccess, tag.getCompound("UnifiedStorage"));
// 旧数据兼容：把老版本独立的 EnergyStorage 迁进统一存储
if (tag.contains("EnergyStorage")) { ... net.unifiedStorage.insert(EnergyStackKey.INSTANCE, energyTag.getLong("Energy"), false); }

// DimensionsNet.java:427（save）
tag.put("UnifiedStorage", unifiedStorage.serializeNBT(registryAccess));
```

- 存 NBT 的键名固定为：`Id` / `custom_name` / `Owner` / `OldDataTag` / `Managers` / `Players` / `UnifiedStorage` / `currentTime` / `Deleted`。
- `load` 与 `save` 都**不读写任何"自定义数据槽"**——想额外持久化 EMC，要么塞进 `UnifiedStorage`，要么另起一个 SavedData。
- `save` 里 `deleted`、`players`、`managers` 是直接 `tag.put(...)` 覆盖式的，**不读取旧标签合并**，因此**不能通过往 `.dat` 里手工加键的方式塞自定义数据**（会被下一次 `save` 保留但 `load` 不读，无意义）。

### 1.5 它内部持有的 UnifiedStorage

**文件**：`src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/UnifiedStorage.java`（145 行）

```java
// UnifiedStorage.java:22-34
public class UnifiedStorage extends UnorderedStackHandlerRemoveZero
{
    private final DimensionsNet net;      // 仅用于持久化脏标记

    public UnifiedStorage(DimensionsNet net, UiTimestampPolicy uiTimestampPolicy) { ... }

    // UnifiedStorage.java:75-84 —— 把子存储的变更接到网络的脏标记上
    @Override
    public void onChange()
    {
        if (net != null) net.setDirty();
        super.onChange();                  // 基类广播 Any/Delta 订阅
    }
```

- 策略：**到 0 即删除**（`UnorderedStackHandlerRemoveZero`，`UnorderedStackHandlerRemoveZero.java:10-16`）。
- 提供了 `static UnifiedStorage getEmpty()`（`:45-67`）返回 0 容量空实现，`getNet()`（`:69-73`）可反查所属网络。
- 覆写了 4 个方法插入插入/抽取钩子：`insert`（`:86-102`）、`extract(int,long,boolean)`（`:104-115`）、`extract(IStackKey,long,boolean,boolean)`（`:117-133`）、`extract(TagKey,long,boolean)`（`:135-143`）。
- 钩子来自 `api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler` 与 `...BeforeExtractHandler`，是**公开的静态 handler 链**（`UnifiedStorageBeforeInsertHandler.java:17,42,57`），附属模组可用它做"存物品顺便加 EMC"之类的联动 —— **但它只是运行时钩子，不承载任何持久化数据**。

### 1.6 公开方法分类表

**A. 静态工厂 / 查找（服务端）**

| 方法 | 行号 | 用途 |
| --- | --- | --- |
| `create()` | `114-117` | SavedData 工厂，勿直接调用 |
| `createNewNetForPlayer(Player, long, int)` | `127-149` | 创建持久化网络（玩家已有网络则返回原网络） |
| `buildNewNetName(MinecraftServer)` | `160-163` | 分配新 id 并拼出 `BDNet_<n>`，不登记 |
| `buildNetDataName(int)` | `171-174` | `BDNet_` + id |
| `getNetFromId(int)` | `182-187` | 用当前服务端按 id 取网络；不存在/已删返回 null |
| `getNetFromId(MinecraftServer, int)` | `189-199` | 同上（**包私有**，附属模组不可用） |
| `getNetFromPlayer(Player)` | `207-211` | `@Deprecated`，转发到 `getPrimaryNetFromPlayer` |
| `getPrimaryNetFromPlayer(Player)` | `222-235` | 取玩家"主网络"，无主网络返回 null |
| `getAllNetFromPlayer(Player)` | `246-271` | 玩家所属全部有效网络 |
| `hasAnyNet(Player)` | `281-285` | 是否属于任意网络 |
| `hasPrimaryNet(Player)` | `293-296` | 是否有主网络 |
| `setPrimaryNetForPlayer(Player, @Nullable DimensionsNet)` | `308-320` | 设置/清空主网络 |
| `clearPrimaryNetForPlayer(Player)` | `330-339` | 清空主网络（不移除成员关系） |
| `load(CompoundTag, HolderLookup.Provider)` | `344-388` | 反序列化 |
| `getNetworkName(int, @Nullable String)` | `460-467` | 静态命名规则，客户端快照可复用 |

**B. 实例读写（元数据）**

| 方法 | 行号 | 说明 |
| --- | --- | --- |
| `getId()` / `setId(int)` | `444-447` / `492-496` | 后者会 `setDirty()` |
| `getCustomName()` / `hasCustomName()` / `setCustomName(String)` | `469-487` | 改名会 `setDirty()` |
| `getOwner()` / `setOwner(UUID)` | `501-514` | 私有重载 `setOwner(UUID, boolean)` `516-528`，会派发 `OwnerChanged` |
| `getManagers()` / `addManager` / `removeManager` | `533-595` | 会派发 `MemberChanged` |
| `getPlayers()` / `addPlayer` / `removePlayer` / `leavePlayer` | `600-679` | 会派发 `MemberChanged`；同时同步 `PlayerNetIndex` 成员关系 |
| `isOwner(Player|UUID)` / `isManager(Player|UUID)` | `687-717` | 权限判断 |
| `getPlayerPermissionInfoMap(MinecraftServer)` | `794-814` | UUID → 名字 + 最高权限 |
| `getUnifiedStorage()` | `821-824` | 取网络存储（**附属模组最常用的入口**） |
| `save(CompoundTag, HolderLookup.Provider)` | `393-436` | 序列化 |

**C. 生命周期 / 破坏性操作**

| 方法 | 行号 | 说明 |
| --- | --- | --- |
| `mergeOtherNet(DimensionsNet otherNet)` | `726-744` | 合并玩家 + **把所有资源 `insert` 进本网络**，随后 `otherNet.destroySelf()` |
| `destroySelf()` | `749-788` | 销毁当前网络（详见 [3.3](#33-网络何时被删除)） |
| `onServerTick(ServerTickEvent.Pre)` | `829-845` | 每 tick 累加 `currentTime` 并 **`setDirty()`** |

```java
// DimensionsNet.java:829-845 —— 每 tick 都标脏
@SubscribeEvent
public void onServerTick(ServerTickEvent.Pre event)
{
    if (temporary || ServerConfigRuntime.crystalGenerateTime <= 0) return;
    currentTime++;
    setDirty();                                  // ← 每个非临时网络每 tick 都被标脏
    if (currentTime >= ServerConfigRuntime.crystalGenerateTime * 20) { ... }
}
```

> 含义：只要存在任意非临时网络，**整个存档的 SavedData 集合几乎永远是 dirty 的**。这对"EMC 池挂在自己 SavedData 上、是否需要自己管 dirty"没有负面影响（原版每次 autosave 都会写所有 dirty 数据），但也意味着"dirty 标记"在本模组里**不是可靠的自定义变更信号**。

**D. 内部工具（私有）**

`sanitizeCustomName`（`874-896`）、`syncPlayerMembership`（`847-856`）、`syncPlayerRemoval`（`858-872`）。

---

## 2. 网络数据的持久化机制

### 2.1 结论：是 NeoForge/原版 `SavedData`，一个网络一个文件

```java
// DimensionsNet.java:137 —— 创建
DimensionsNet newNet = server.overworld().getDataStorage()
        .computeIfAbsent(new SavedData.Factory<>(DimensionsNet::create, DimensionsNet::load), netDataName);

// DimensionsNet.java:193 —— 读取
DimensionsNet net = server.overworld().getDataStorage()
        .get(new SavedData.Factory<>(DimensionsNet::create, DimensionsNet::load), buildNetDataName(id));
```

| 项 | 值 | 证据 |
| --- | --- | --- |
| 存储 API | 原版 `net.minecraft.world.level.saveddata.SavedData` + `DimensionDataStorage` | `DimensionsNet.java:20,37,137,193` |
| 挂载维度 | **Overworld**（`server.overworld()`） | `DimensionsNet.java:137,193`、`NetRegistryIndex.java:62`、`PlayerNetIndex.java:55` |
| 文件目录 | `<world>/data/` | `NetRegistryIndex.java:217`：`server.getWorldPath(LevelResource.ROOT).resolve("data")` |
| 文件名 | `BDNet_<netId>.dat` | `DimensionsNet.java:39,171-174` |
| 索引文件 | `BDNetRegistryIndex.dat`（活跃 id 集合 + 下一个候选 id） | `NetRegistryIndex.java:31,60-63` |
| 玩家索引文件 | `BDPlayerNetIndex.dat`（玩家主网络映射） | `PlayerNetIndex.java:21,53-56` |

### 2.2 `setDirty()` 之后何时真正写盘

BD 源码里**没有任何显式保存调用**（全仓库无 `saveAll`、无 `ServerStoppingEvent` 订阅）。因此完全依赖原版机制：

1. 调用点（BD 侧）：任何内容/权限变更都会标脏 —— `UnifiedStorage.onChange()`（`UnifiedStorage.java:78-81`）、`setId`（`DimensionsNet.java:495`）、`setCustomName`（`486`）、成员/所有者变更（`522,562,588,619,662`）、`destroySelf`（`786`）、**每 tick 的 `onServerTick`（`837`）**。
2. 落盘时机（**原版机制**）：`SavedData.setDirty()` 只把 `dirty=true`；真正的 `DimensionDataStorage#save()` 由服务端在 **自动存档（默认每 6000 tick ≈ 5 分钟）**、`/save-all`、维度卸载、服务端停止/关服时触发，只写 dirty 的数据。

> ⚠️ 该"落盘时机"属于原版 Minecraft 行为，本工作区**没有 MC/NeoForge 源码**可供引证（用户目录下无 `.gradle` 缓存），因此标注为**未在本地源码中确认**；但它与 NeoForge 官方文档一致：*"`setDirty`: A method that must be called after changing the data... If not called, `#save` will not get called"*（<https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/>）。

### 2.3 崩溃/非正常退出风险

因为写盘只在 autosave/关服发生，**服务器崩溃会丢失最近一次 autosave 之后的所有网络内容**。这不是 EMC 方案特有的问题，但 EMC 池同样受影响。

### 2.4 存储内容的序列化细节（决定"自定义 key 能否落盘"）

`UnifiedStorage` 继承自 `AbstractUnorderedStackHandler`：

```java
// AbstractUnorderedStackHandler.java:823-863（serializeNBT 摘要）
CompoundTag tag = new CompoundTag();
tag.putLong("slotCapacity", this.slotCapacity);
tag.putInt("slotMaxSize", this.slotMaxSize);
for (Map.Entry<IStackKey<?>, Long> entry : storage.entrySet()) {
    if (key == null || key.isEmpty()) continue;
    if (!writeZero && value <= 0L) continue;
    DataResult<Tag> enc = IStackKey.CODEC.encodeStart(ops, key);   // ← 每个 key 用 dispatch codec 编码
    stackTag.put("key", ct);
    stackTag.putLong("amount", value);                             // ← 数量是 long
}
tag.put("stacks", stacksTag);
```

```java
// AbstractUnorderedStackHandler.java:889-898（deserializeNBT 摘要）
try {
    IStackKey.CODEC.parse(ops, keyTag)
            .resultOrPartial(err -> LOGGER.warn("解码 IStackKey 失败：{}", err))
            .ifPresent(key -> acceptEntry(key, amount));
} catch (Throwable t) {
    LOGGER.warn("解码第 {} 个新格式条目时出错: {}", i, t.toString());
}
```

两个关键推论：

- **自定义资源类型天然会被持久化**：只要它实现了 `IStackKey` 并在 `StackKeyRegistry` 注册，`serializeNBT`/`deserializeNBT` 就会自动带上它，**无需写任何持久化代码**。
- **如果附属模组被卸载**，`IStackKey.CODEC` 的分发会去 `StackKeyRegistry.getType(id)` 拿不到类型并抛异常（`StackKeyRegistry.java:26-31`），但该异常被 `deserializeNBT` 的 `catch (Throwable)` 吞掉、仅打 warning，**结果是该条目被静默丢弃（数据丢失，不崩溃）**。

---

## 3. 网络 ID、玩家绑定与网络生命周期

### 3.1 三者关系

```
NetRegistryIndex（BDNetRegistryIndex.dat）        全局：活跃 net id 集合 + nextNetId（单调递增，永不复用）
        ▲ allocateNetId / registerNet / unregisterNet
        │
DimensionsNet（BDNet_<id>.dat，一个网络一个 SavedData）── 真正的数据（owner/managers/players/UnifiedStorage）
        │ addPlayer/removePlayer 时调用 syncPlayerMembership / syncPlayerRemoval
        ▼
PlayerNetIndex（BDPlayerNetIndex.dat）              玩家 UUID → 主网络 id（持久化）
                                                   玩家 UUID → 全部成员网络 id（仅运行时，不落盘）
```

- `NetRegistryIndex` 与 `PlayerNetIndex` 都是 **`final class` + 包级私有**（`NetRegistryIndex.java:26`、`PlayerNetIndex.java:16`），且 `get()` / `getActiveNetIds()` 都是包私有（`NetRegistryIndex.java:60,189`）。
  → **附属模组无法访问这两个索引，也无法枚举"当前所有网络 id"。** 这是方案 (b)（自建 SavedData + net id 作 key）最需要注意的约束。
- `DimensionsNet.getNetFromId(int)` 的公开重载使用 `ServerLifecycleHooks.getCurrentServer()`（`DimensionsNet.java:184`），所以**服务端任意位置都能查**；传入 `id < 0` 直接返回 null（`:191`），查到的网络若 `deleted` 也返回 null（`:194-198`）。

### 3.2 网络何时被创建

| 触发点 | 位置 | 说明 |
| --- | --- | --- |
| 物品「维度网络发生器」`NetCreater` | `common/item/NetCreater.java:62`：`DimensionsNet.createNewNetForPlayer(player, Long.MAX_VALUE, Integer.MAX_VALUE)` | 容量默认无限 |
| 服务端命令 | `common/command/ServerCommands.java:313` | 可指定容量；要求玩家当前无网络（`:298-303`） |
| KubeJS / 附属模组 | 直接调 `createNewNetForPlayer` | README:80 也把该签名公开为 kubeJS 扩展点 |

创建流程：`NetRegistryIndex.allocateNetId` 取候选 id → `computeIfAbsent` 建 SavedData → `registerNet` 登记并推进 `nextNetId` → 设 owner → `setDirty` → 设容量 → **post `DimensionsNetEvent.Created`**。

**ID 不会复用**：`nextNetId` 单调递增并持久化（`NetRegistryIndex.java:80-83, 296-315`），旧存档迁移时会扫描 `BDNet_*.dat` 提升 `nextNetId`（`:214-244`）。→ **用 net id 作为附属模组持久化数据的 key 是安全的**。

### 3.3 网络何时被删除

| 触发点 | 位置 |
| --- | --- |
| 物品「维度网络销毁器」`NetDestroyer`（需是 owner） | `common/item/NetDestroyer.java:66` |
| 命令删网络 | `common/command/ServerCommands.java:335`（按 id）、`:354`（按玩家） |
| 合并（`NetGifter`） | `common/item/NetGifter.java:44` → `mergeOtherNet` → `otherNet.destroySelf()`（`DimensionsNet.java:743`） |

`destroySelf()` 的完整处理：

```java
// DimensionsNet.java:749-788（摘要）
public void destroySelf()
{
    if (this.deleted) return;
    int previousNetId = this.id;
    DimensionsNetEvent.Destroyed destroyedEvent = new DimensionsNetEvent.Destroyed(
            previousNetId, this.customName, List.copyOf(this.unifiedStorage.getStorage()),
            this.owner, Set.copyOf(this.managers), Set.copyOf(this.players));   // 建事件时先快照
    for (UUID playerId : new ArrayList<>(this.players)) syncPlayerRemoval(playerId, previousNetId);

    // 这里有一些问题。即我们实际上无法删除已经存在的SaveData。
    // 所以我们要做的是巧妙地将此SaveData有关数据指向移除。
    this.owner = null;
    this.managers.clear();
    this.players.clear();
    this.id = -99;                       // 被删除的特殊标记
    this.unifiedStorage.clearStorage();  // ← 资源被清空
    this.deleted = true;
    if (server != null) NetRegistryIndex.get(server).unregisterNet(server, previousNetId);
    setDirty();
    NeoForge.EVENT_BUS.post(destroyedEvent);   // ← 事件在数据已清空之后才派发
}
```

**删除时数据处理的关键事实**：

1. **磁盘文件不会被删除**（源码注释 `:772-774` 明确说明"无法删除已存在的 SaveData"）。文件被重写为 `Id=-99, Deleted=true, UnifiedStorage 空`，永久残留。
2. **存储内容被清空**，只通过 `Destroyed` 事件的 `getDestroyedStorage()` 提供一份**快照副本**（`DimensionsNetEvent.java:61,98-101`）用于补偿。
3. `Destroyed` 事件携带的是 `destroyedId / netName / destroyedStorage / owner / managers / members`，但**不包含"被合并到哪个网络"**的信息 → 附属模组做合并迁移时只能自己推断（见 [9](#9-对附属模组的实现建议) 风险 R4）。
4. `Destroyed` 是 `DimensionsNetEvent` 中**唯一不继承 `DimensionsNetEvent` 基类的成员**（它直接 `extends Event`，`DimensionsNetEvent.java:46`），因此**拿不到 `getNet()`**，拿不到网络对象——这点很容易写错。
5. 对象本身仍留在内存、`onServerTick` 监听器仍在事件总线上（`deleted` 后 `temporary` 仍为 false，倒计时逻辑照跑，并把已删除网络继续标脏）。

---

## 4. 扩展点评估：EMC 数值池放哪里

### 4.1 方案 a：注册自定义 `IStackKey`，把 EMC 当作网络里的一种资源

**做法**：实现 `IStackKey<EmcType>`（可继承 `LongStackKey`）+ 实现 `IStackRender`，在 `FMLCommonSetupEvent` 里 `StackKeyRegistry.registerType(EmcStackKey.INSTANCE)`，然后 `net.getUnifiedStorage().insert(EmcStackKey.INSTANCE, amount, false)` 写、`extract(...)` 读。

**现成模板（BD 自己就在这么做，共 4 个集成模块）**：

| 模块 | 注册点 | 类型实现 |
| --- | --- | --- |
| Botania 魔力 | `integration/module/botania/BotaniaModule.java:63` | `integration/module/botania/storage/ManaStackKey.java:21`（`extends LongStackKey<ManaType>`，单例、无字段 codec） |
| Ars 魔源 | `integration/module/ars/ArsModule.java:55` | `integration/module/ars/storage/SourceStackKey.java` |
| Mek 化学品 | `integration/module/mekanism/MekModule.java:34` | `integration/module/mekanism/storage/ChemicalStackKey.java` |
| IFS 魂 | `integration/module/ifs/IFSModule.java:49` | `integration/module/ifs/storage/WardenSoulStackKey.java` |

最简模板（`ManaStackKey` 的核心部分，EMC 可照抄）：

```java
// integration/module/botania/storage/ManaStackKey.java:21-70（摘要）
public class ManaStackKey extends LongStackKey<ManaType>
{
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(BDConstants.MODID, "stack_type/mana");
    public static final ManaStackKey INSTANCE = new ManaStackKey();
    public static final MapCodec<ManaStackKey> TYPE_CODEC = new MapCodec<>() {
        public <T> DataResult<ManaStackKey> decode(DynamicOps<T> ops, MapLike<T> input) { return DataResult.success(INSTANCE); }
        public <T> RecordBuilder<T> encode(ManaStackKey v, DynamicOps<T> ops, RecordBuilder<T> prefix) { return prefix; }
        public <T> Stream<T> keys(DynamicOps<T> ops) { return Stream.empty(); }
    };
    @Override public MapCodec<ManaStackKey> codec() { return TYPE_CODEC; }
    @Override public ResourceLocation getTypeID() { return ID; }
    // fromStackObject / fromSourceObject / getSource / getEmpty / getEmptyStack /
    // hasTag / getTags / serialize(空) / deserialize(返回单例) /
    // serializeNBT(空 tag) / deserializeNBT(返回单例) / getRender()
}
```

**优点**

| 优点 | 证据 |
| --- | --- |
| **零持久化代码**：随 `DimensionsNet.save → unifiedStorage.serializeNBT` 自动落盘，随 `load` 自动恢复 | `DimensionsNet.java:427,358`、`AbstractUnorderedStackHandler.java:823-863,866-940` |
| **零同步代码（在网络界面内）**：BD 的菜单同步器会把存储里**所有 key**（含自定义类型）按增量/全量发给客户端 | `DisorderedSlotGroupSync.java:56-70,134-148,314-344`、`DimensionsMenu.java:94-103` |
| 客户端能解析自定义 key：客户端用的是同一套 `IStackKey.STREAM_CODEC` + `StackKeyRegistry` | `IStackKey.java:38-59`、`DimensionsNetMenu.java:59-63` |
| **网络合并时资源自动被搬运**：`mergeOtherNet` 会把被合并网络的全部 `KeyAmount` 插入存活网络 | `DimensionsNet.java:736-740` |
| 可以直接复用 BD 的存储 UI / 搜索 / tooltip / 能力包装 | `common/menu/DimensionsNetMenu.java`、`api/capability/helper/CapabilityHelper.java` |
| 官方 README 明确把"实现并注册资源类型"列为附属制作方式 | `README.md:60-72` |

**缺点与风险**

| 风险 | 说明与证据 |
| --- | --- |
| EMC 会变成"一等资源" | 会被网络通道/AE/网络泵/网络漏斗等当作可抽取资源（走 `UnifiedStorage.extract`，`UnifiedStorage.java:117-133`）；玩家在 BD 界面里能直接拿 EMC。若不希望 EMC 被物流系统抽走，需注册时**不给** `CapabilityHelper.BlockCapabilityMap` / `USHandlerMap`（`CapabilityHelper.java:37,47`），但这只挡"能力包装"，挡不住模组内部按 key 抽取。 |
| **必须提供 `IStackRender`** | BD 的 GUI/搜索/tooltip 无条件调用 `key.getRender()`：`client/gui/BDBaseGUI.java:56,86-87`、`util/TooltipHelper.java:110`、`common/menu/widget/ClientNetStorageSearchHelper.java:303`、`ClientNetStorage.java:200`。不实现会直接崩 UI。 |
| 占槽位 | 新 key 会占一个"种类槽"，受 `slotMaxSize` 限制（`AbstractUnorderedStackHandler.java:458-462`）。默认网络是 `Integer.MAX_VALUE`，一般无碍。 |
| 单键上限是 long 且受 `slotCapacity` 约束 | `setAmountByKey` 中 `Math.min(amount, slotCapacity)`（`AbstractUnorderedStackHandler.java:424`）。默认 `Long.MAX_VALUE`（`DimensionsNet.java:142`、`AbstractUnorderedStackHandler.java:163`）。 |
| 卸载附属模组 → EMC 静默丢失 | `StackKeyRegistry.getType` 抛异常被 `deserializeNBT` 的 `catch (Throwable)` 吞掉（`StackKeyRegistry.java:26-31`、`AbstractUnorderedStackHandler.java:889-898`）。 |
| 注册时机敏感、`StackKeyRegistry` 非线程安全 | `StackKeyRegistry.java:12,14-21` 是普通 `HashMap`；BD 在 `FMLCommonSetupEvent` 里直接注册（`BeyondDimensions.java:65-68`），而 FMLCommonSetupEvent 在 NeoForge 中是**并行派发**的。附属模组建议用 `event.enqueueWork(() -> ...)` 注册以规避并发写。 |

### 4.2 方案 b：自己写一个 `SavedData`，用 net id 作 key

**做法**：仿照 `NetRegistryIndex` 的写法，在 Overworld 建一个 `SavedData`（如 `EmcPoolData`），内部 `Map<Integer, Long>`（或 `Map<Integer, EmcPool>`），挂在固定名字上：

```java
// 仿 NetRegistryIndex.java:37,60-63 的模式
private static final Factory<EmcPoolData> FACTORY = new Factory<>(EmcPoolData::new, EmcPoolData::load);
public static EmcPoolData get(MinecraftServer server) {
    return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "BDAddonEmcPools");
}
```

**优点**

- 与 BD 的存储系统完全隔离：EMC 不会出现在物品列表里，不会被管道/AE 抽取，不占种类槽，不受 `slotCapacity` 限制。
- 数据结构自由：可以存 `long emc` + 升级等级 + 统计信息等复合结构。
- 生命周期清晰：`Created` 事件初始化、`Destroyed` 事件清理（`DimensionsNet.java:144,787`）。
- net id 单调且永不复用（`NetRegistryIndex.java:296-315`），key 安全。

**缺点与风险**

| 风险 | 说明与证据 |
| --- | --- |
| **无法枚举已有网络** | `NetRegistryIndex` / `PlayerNetIndex` 是包私有 `final class`（`NetRegistryIndex.java:26,60,189`）。只能靠 `Created`/`Destroyed` 事件 + `getNetFromId` / `getAllNetFromPlayer` 逐个查。 |
| **`Created` 不覆盖已存在的网络** | 事件只在 `createNewNetForPlayer` 派发（`DimensionsNet.java:144`），`load` 不派发（`:344-388`）。→ 必须做**懒初始化**：EMC 查询时若 map 无该 net id，按 0 处理并写入。 |
| 自己管 dirty 与容量回收 | 每次 EMC 变化要 `setDirty()`；被删网络的条目要主动删（否则 map 无限增长）。 |
| **合并语义要自己实现** | `mergeOtherNet` 只会搬运 `UnifiedStorage`（`DimensionsNet.java:736-740`），`Destroyed` 事件不含"并入哪个网络"（`DimensionsNetEvent.java:78-86`）。需要自行推断（如取 `owner` 当前的主网络，但 owner 可能离线）。 |
| 同步要自己写 | BD 的菜单同步（`DisorderedSlotGroupSync`）只同步 `UnifiedStorage`，不会带上你的 SavedData。 |
| 卸载附属模组 → 该 SavedData 文件变孤儿 | 不崩溃、不丢别人数据，但 EMC 数据无主（比方案 a 略好，文件还在）。 |

### 4.3 方案 c：NeoForge DataAttachment

**结论：在 NeoForge 21.1 上，不能给 `DimensionsNet` 这种普通 Java 对象挂 attachment。**

证据链：

1. NeoForge **1.21.1** 官方文档（<https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/>）原文：
   > "The data attachment system allows mods to attach and store additional data on **block entities, chunks, and entities**."
   > "_To store additional level data, you can use [SavedData](...)._"

   即 1.21.1 的支持范围**只有 BlockEntity / Chunk / Entity**；`Level` 支持是后续版本才加入的（26.1 文档已列出 "block entities, chunks, entities, and levels"，且 `net.neoforged.neoforge.attachment.LevelAttachmentsSavedData` 只出现在 1.21.4+ 的 javadoc 中）。

2. attachment 的宿主必须实现 `IAttachmentHolder`（`getData`/`setData`/`hasData` 都是该接口方法）。`DimensionsNet` 继承自原版 `SavedData`（`DimensionsNet.java:37`），**既不实现 `IAttachmentHolder`，也不是 `Level`/`Entity`/`BlockEntity`/`ChunkAccess`**。BD 源码中亦无任何 `IAttachmentHolder` / `AttachmentType` / `DataAttachment` 使用（全仓库 grep 无匹配）。

3. 想强行挂只有两条路：
   - mixin / AccessTransformer 让 `SavedData` 或 `DimensionsNet` 实现 `IAttachmentHolder` —— 侵入性强、与 BD 更新冲突风险高，**不推荐**；
   - 通过 mixin 往 `DimensionsNet.save/load` 里插自定义 NBT —— 这是"改别人类"的做法，同样不推荐。

4. 退一步：`ServerLevel` 在 21.1 是否可挂 attachment **未确认**（官方 1.21.1 文档未列 level；据此推断 21.1 不支持）。即便支持，把 EMC 挂在 `ServerLevel` 上也**只能得到"整个维度一份池子"**，无法按网络区分，不满足需求。

> 附注：`net.neoforged.neoforge.attachment.AttachmentType` 在 21.1 的 API 形态为 `AttachmentType.builder(...).serialize(Codec...)` / `AttachmentType.serializable(...)`，注册到 `NeoForgeRegistries.ATTACHMENT_TYPES`（`DeferredRegister`）。若要给**你自己的** BlockEntity 挂 EMC 显示数据，这是可用手段；但**不能**用来给 BD 的网络对象挂数据。

### 4.4 方案 d：其他发现的方式

| 方式 | 可行性 | 证据 |
| --- | --- | --- |
| **d1. `UnifiedStorageBeforeInsertHandler` / `BeforeExtractHandler` 钩子链** | ✅ 官方公开钩子，可在每次插入/抽取时改数量、取消本次操作（很适合"存物品加 EMC""取物品扣 EMC"的联动）。**但不能存数据** | `api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java:17,42,57`、`UnifiedStorageBeforeExtractHandler.java:17,46,61`、`UnifiedStorage.java:91,122` |
| **d2. `QuickDataTagPacket` + `BDBaseMenu#writeQuickDataTag/readQuickDataTag`** | ✅ BD 自有的**菜单级自定义数据同步通道**。只要你自己的菜单 `extends BDBaseMenu`，就可以复用 BD 已注册的 payload 来收发任意 `CompoundTag`（发送侧 `BDBaseMenu.writeAndSendQuickData()`，服务端每 tick 条件发送） | `network/packet/both/QuickDataTagPacket.java:19-47,49-63`、`common/menu/BDBaseMenu.java:64-146`、`common/init/BDPackets.java:135-142`；BD 内部 8 个菜单都在覆写这些钩子（如 `NetFurnaceMenu.java:172,186`、`XpExchangeMenu.java:90,98`） |
| **d3. 继承 `DimensionsNet`** | ❌ **不可行**。`DimensionsNet` 不是 final、`save` 也不是 final，但 SavedData 的工厂**硬编码**在 `createNewNetForPlayer`（`:137`）与 `getNetFromId`（`:193`）里，永远用 `DimensionsNet::create` / `DimensionsNet::load`，附属模组无法替换加载路径（除非 mixin） | `DimensionsNet.java:137,193` |
| **d4. 往网络 `.dat` 手工加 NBT 键** | ❌ 无效。`load` 不读未知键，且 `save` 会重写 | `DimensionsNet.java:344-388,393-436` |
| **d5. 自带 `netId` 数据组件的物品/方块**（仿 `NetedItem`） | ✅ 适合"EMC 终端/存储方块"这类载体，把 netId 绑在物品/方块上，数据本体仍放 a 或 b | `common/item/NetedItem.java:70-123`、`common/block/entity/NetedBlockEntity.java:41-71,152-164`、`BDDataComponents.NET_ID_DATA` |
| **d6. BD 是否有"自定义数据槽"机制** | ❌ **没有**。`DimensionsNet` 的 `save`/`load` 字段是封闭的 9 个键，没有任何 `customData` / `attachment` / `extra` 扩展槽；`UnifiedStorage` 就是官方给出的唯一"可扩展数据容器" | `DimensionsNet.java:393-436`、`README.md:60-72` |

---

## 5. api/event/dimensionnet 事件全清单与订阅方式

### 5.1 总览

| 事件基类 | 子类型 | 可取消？ | 事件总线 | 派发时机 |
| --- | --- | --- | --- | --- |
| `DimensionsNetEvent`（abstract） | `Created` | ❌ | game bus | 网络**已被创建并初始化完成**后（含容量设置） |
| | `Destroyed`（**注意：不继承基类**） | ❌ | game bus | 网络**已被销毁**（数据已清空）后 |
| | `OwnerChanged` | ❌ | game bus | 同一网络内所有者变更后（合并走 `Destroyed`） |
| | `MemberChanged` | ❌ | game bus | 成员加入/退出/被踢/权限升降后 |
| `NetedBlockEvent`（abstract） | `Bound` | ❌ | game bus | 方块**完成绑定**后 |
| | `Unbound` | ❌ | game bus | 方块**完成解绑**或方块被移除导致解绑后 |
| `NetedItemEvent`（abstract） | `Bound` | ❌ | game bus | 物品**完成主动绑定**后 |
| | `Unbound` | ❌ | game bus | 物品**完成主动解绑**后 |

### 5.2 能不能 cancel？

**都不能。** 三个基类都只 `extends net.neoforged.bus.api.Event`，**均未实现 `ICancellableEvent`**：

- `DimensionsNetEvent.java:14`：`public abstract class DimensionsNetEvent extends Event`
- `NetedBlockEvent.java:18`：`public abstract class NetedBlockEvent extends Event`
- `NetedItemEvent.java:18`：`public abstract class NetedItemEvent extends Event`

且所有派发点都是"**变更已经完成之后**才 post"（见下），因此即使能 cancel 也没有拦截窗口。**附属模组只能观察，不能阻止。**

### 5.3 `DimensionsNetEvent`

```java
// DimensionsNetEvent.java:14-26
public abstract class DimensionsNetEvent extends Event
{
    private final DimensionsNet net;
    public DimensionsNetEvent(DimensionsNet net) { this.net = net; }
    public DimensionsNet getNet() { return net; }
```

| 事件 | 行号 | 构造/读取器 | 关键性质 |
| --- | --- | --- | --- |
| `Created` | `33-39` | `getNet()` | 文档注释："你可在此时放入一些初始物资，调整网络初始容量..." |
| `Destroyed` | `46-117` | `getDestroyedId()` / `getNetName()` / `getDestroyedStorage()` / `getOwner()` / `getManagers()` / `getMembers()` | **`extends Event` 而非 `DimensionsNetEvent`，因此没有 `getNet()`**；存储是 `List<KeyAmount>` 快照（`List.copyOf`，只读） |
| `OwnerChanged` | `124-145` | `getNet()` / `getBeforeOwner()` / `getAfterOwner()` | 仅"同一网络内"变更；网络合并的本质是销毁，要监听 `Destroyed` |
| `MemberChangeState`（枚举） | `150-163` | `JOINED_AS_MEMBER/JOINED_AS_MANAGER/LEFT_AS_MEMBER/LEFT_AS_MANAGER/KICKED_AS_MEMBER/KICKED_AS_MANAGER/PROMOTED_TO_MANAGER/DEMOTED_TO_MEMBER` | — |
| `MemberChanged` | `168-189` | `getNet()` / `getChangedPlayer()` / `getChangeState()` | — |

**全部派发点**（都在 `DimensionsNet.java`，总线上都是 `NeoForge.EVENT_BUS.post`）：

| 行号 | 事件 |
| --- | --- |
| `144` | `Created` |
| `526` | `OwnerChanged`（仅 owner 真的变化且原 owner 非 null） |
| `570` | `MemberChanged`（PROMOTED_TO_MANAGER / JOINED_AS_MANAGER） |
| `589-593` | `MemberChanged`（DEMOTED_TO_MEMBER） |
| `626` | `MemberChanged`（JOINED_AS_MANAGER / JOINED_AS_MEMBER） |
| `677` | `MemberChanged`（KICKED_* / LEFT_*） |
| `787` | `Destroyed` |

### 5.4 `NetedBlockEvent`

| 事件 | 行号 | 派发点 | 触发链路 |
| --- | --- | --- | --- |
| `Bound` | `72-80` | `NetedBlockEntity.java:65-67` | `NetedBlockEntity.setNetId(int)`（`:41`）检测到 `id >= 0` 且与旧值不同 |
| `Unbound` | `85-93` | `NetedBlockEntity.java:59-61` | 同一方法中 `previousNetId >= 0` |

```java
// NetedBlockEntity.java:46-69（摘要）
int previousNetId = this.netId;
this.netId = id;
if (level != null) {
    refreshNetCache();
    setChanged();
    if (level instanceof ServerLevel serverLevel && getBlockState().getBlock() instanceof NetedBlock block) {
        if (previousNetId >= 0) NeoForge.EVENT_BUS.post(new NetedBlockEvent.Unbound(previousNetId, serverLevel, worldPosition, getBlockState(), block, this));
        if (id >= 0)            NeoForge.EVENT_BUS.post(new NetedBlockEvent.Bound(id, serverLevel, worldPosition, getBlockState(), block, this));
    }
}
```

- **仅在服务端派发**（`level instanceof ServerLevel`）。
- 方块被破坏/替换时的解绑：`NetedBlock.onRemove(...)`（`common/block/NetedBlock.java:112-121`）→ `blockEntity.clearNetId()` → `setNetId(-1)` → `Unbound`。
- 玩家主动解绑：`NetedBlock` 交互分支里的 `blockEntity.setNetId(-1)`（`common/block/NetedBlock.java:98`）。
- 可读取字段：`getNetId()` / `getLevel()` / `getPos()`（已 `immutable()`）/ `getBlockState()` / `getBlock()` / `getBlockEntity()`（`:39-67`）。

### 5.5 `NetedItemEvent`

| 事件 | 行号 | 派发点 | 触发链路 |
| --- | --- | --- | --- |
| `Unbound` | `77-84` | `common/item/NetedItem.java:99` | `NetedItem.setNet(ItemStack, Player)` 中把 `NET_ID_DATA` 从 `>=0` 改成 `-1` 之后 |
| `Bound` | `65-72` | `common/item/NetedItem.java:112` | 同一方法中把 `NET_ID_DATA` 设为 `playerNet.getId()` 之后 |

- 只有 `player instanceof ServerPlayer` 时才派发（`NetedItem.java:97,110`）。
- 类文档明确警告：**物品没有可靠的生命周期结束信号**——物品被销毁/清除/合并时**不会**派发 `Unbound`（`NetedItemEvent.java:13-16`）。附属模组不能依赖它做"物品侧 EMC 回收"。
- 可读取字段：`getNetId()` / `getPlayer()` / `getStack()`（事件描述的状态，监听器不应修改）/ `getItem()`（`:34-60`）。

### 5.6 实际订阅方式与源码中的订阅点

**（1）事件总线是 game bus（`NeoForge.EVENT_BUS`），不是 mod bus。** 证据：所有 `post` 都是 `NeoForge.EVENT_BUS.post(new ...)`（如 `DimensionsNet.java:144,526,570,589,626,677,787`、`NetedBlockEntity.java:59,65`、`NetedItem.java:99,112`），而 mod bus 上的事件（`RegisterCapabilitiesEvent`、`FMLCommonSetupEvent`）是另一套（`BDCapabilities.java:12-16`、`BeyondDimensions.java:47`）。

**（2）BD 源码里没有任何地方订阅自己的这三个事件类**（全仓库 grep `DimensionsNetEvent.` / `NetedBlockEvent` / `NetedItemEvent` 只命中"定义 + post"）。它们是**纯粹的对外扩展点，只发给附属模组**。

**（3）BD 内部实际使用的两种订阅写法：**

```java
// 写法 1：addListener（在 DimensionsNet 构造函数里注册 tick 监听）
// DimensionsNet.java:105
NeoForge.EVENT_BUS.addListener(this::onServerTick);
```

```java
// 写法 2：@EventBusSubscriber + @SubscribeEvent（静态）
// NetRegistryIndex.java:25,253-257
@EventBusSubscriber(modid = BDConstants.MODID)
final class NetRegistryIndex extends SavedData {
    @SubscribeEvent
    private static void onServerStarted(ServerStartedEvent event) {
        NetRegistryIndex.get(event.getServer()).ensureInitialized(event.getServer());
    }
}
// 同理 PlayerNetIndex.java:15,393-397（rebuildFromServer）
// 同理 BDCapabilities.java:12-16（mod bus 上的 RegisterCapabilitiesEvent）
```

```java
// 写法 3：主类构造函数里把整个对象注册到 game bus
// BeyondDimensions.java:44
NeoForge.EVENT_BUS.register(this);
```

**（4）附属模组订阅 BD 事件的推荐写法（三选一，都作用于 game bus）：**

```java
// A. 注解式（最省事，注意 bus 必须显式写 GAME）
@EventBusSubscriber(modid = "yourmod", bus = EventBusSubscriber.Bus.GAME)
public final class EmcNetListeners {
    @SubscribeEvent
    public static void onNetCreated(DimensionsNetEvent.Created e) { ... }

    @SubscribeEvent
    public static void onNetDestroyed(DimensionsNetEvent.Destroyed e) { ... }  // 注意没有 getNet()
}

// B. 代码注册
NeoForge.EVENT_BUS.addListener((DimensionsNetEvent.Created e) -> { ... });

// C. 带类型的 register（NeoForge 官方文档在 PlayerEvent.Clone 示例里用的就是这个）
NeoForge.EVENT_BUS.register(DimensionsNetEvent.Destroyed.class, e -> { ... });
```

> ⚠️ 因为事件全部是"事后派发 + 不可取消"，**EMC 池的"新建初始化"不能只靠 `Created`**：已存在的网络不会触发它，需要在读取时做懒初始化。

---

## 6. BD 初始化入口与注册时机

**主类**：`src/main/java/com/wintercogs/beyonddimensions/BeyondDimensions.java`（105 行）

```java
// BeyondDimensions.java:34-59
@Mod(BDConstants.MODID)
public class BeyondDimensions
{
    public static IEventBus MOD_EVENT_BUS;                 // :37 —— 主类静态保存了 mod bus

    public BeyondDimensions(IEventBus modEventBus, ModContainer modContainer)
    {
        MOD_EVENT_BUS = modEventBus;
        NeoForge.EVENT_BUS.register(this);                 // :44 game bus
        Config.register(modContainer);                     // :45 配置注册

        modEventBus.addListener(this::commonSetup);        // :47 关键：CommonSetup 监听

        BDMenus.register(modEventBus);                     // :49
        BDCreativeModeTabs.register(modEventBus);          // :50
        BDDataComponents.register(modEventBus);            // :51
        BDItems.register(modEventBus);                     // :52
        BDBlocks.register(modEventBus);                    // :53
        BDFluids.register(modEventBus);                    // :54
        BDBlockEntities.register(modEventBus);             // :55

        // 分发集成模块
        IntegrationManager.bootstrapCommon(modEventBus, NeoForge.EVENT_BUS);   // :58
    }

    private void commonSetup(final FMLCommonSetupEvent event)   // :61
    {
        // 注册堆叠类型，使得网络能够存储相关堆叠
        StackKeyRegistry.registerType(EmptyStackKey.INSTANCE); // :65
        StackKeyRegistry.registerType(ItemStackKey.EMPTY);     // :66
        StackKeyRegistry.registerType(FluidStackKey.EMPTY);    // :67
        StackKeyRegistry.registerType(EnergyStackKey.INSTANCE);// :68
        ...
    }
```

### 6.1 各注册时机一览

| 时机 | 事件 / 位置 | BD 用它做什么 |
| --- | --- | --- |
| 模组构造（mod bus） | `BeyondDimensions.java:41-59` | 保存 `MOD_EVENT_BUS`、注册 DeferredRegister（菜单/物品/方块/流体/方块实体/数据组件）、注册 `commonSetup` 监听、启动集成模块 |
| **`FMLCommonSetupEvent`（mod bus）** | `BeyondDimensions.java:61-93`；集成模块经 `IntegrationManager.onCommonSetup`（`IntegrationManager.java:51,83-89`） | **`StackKeyRegistry.registerType(...)`（`:65-68`）** + 能力/包装 Map 注册（`:71-92`） |
| `RegisterCapabilitiesEvent`（mod bus） | `BDCapabilities.java:12-16`（`@EventBusSubscriber`） | 给方块实体注册物品/流体/能量能力 |
| `ServerStartingEvent`（game bus） | `BeyondDimensions.java:95-99` | 只打一行日志 |
| `ServerStartedEvent`（game bus） | `NetRegistryIndex.java:253-257`、`PlayerNetIndex.java:393-397` | 初始化网络注册表（含旧存档迁移）、重建玩家网络索引 |
| `FMLLoadCompleteEvent` | **BD 完全没有使用**（全仓库 grep 无匹配） | — |

### 6.2 注册自定义 `IStackKey` 必须发生在哪个阶段

**必须早于"任何维度网络 SavedData 被反序列化"**，因为 NBT 解码时 `IStackKey.CODEC` 会立刻向 `StackKeyRegistry` 查类型（`IStackKey.java:25-33`、`StackKeyRegistry.java:24-32`），查不到就抛异常（虽被吞掉，但该条目丢失）。

BD 自己的做法 = **`FMLCommonSetupEvent`**：

- 原版流程顺序：`FMLCommonSetupEvent` → 世界加载 → `ServerStartedEvent`。而网络 SavedData 的首次反序列化发生在 `ServerStartedEvent` 触发的 `PlayerNetIndex.rebuildFromServer` → `DimensionsNet.getNetFromId`（`PlayerNetIndex.java:135-154,393-397`）与 `NetRegistryIndex.ensureInitialized` 的旧档迁移（`NetRegistryIndex.java:214-244`）。
- 因此 **`FMLCommonSetupEvent` 足够早**；`FMLLoadCompleteEvent` 也在世界加载之前，理论上同样安全，但 BD 未使用它，**建议与 BD 保持一致用 `FMLCommonSetupEvent`**。
- **不要**在 `ServerStartedEvent`/`ServerStartingEvent` 里注册 —— 已晚于迁移路径（`NetRegistryIndex.onServerStarted` 会遍历并 `getNetFromId`）。

**附属模组推荐写法**（规避并行派发下的 `HashMap` 竞态）：

```java
@EventBusSubscriber(modid = "yourmod", bus = EventBusSubscriber.Bus.MOD)
public final class EmcSetup {
    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            StackKeyRegistry.registerType(EmcStackKey.INSTANCE);
            // 可选：让 EMC 类型参与能力包装（若不希望被管道抽取则不要注册）
            // CapabilityHelper.registerUSHandler(EmcStackKey.INSTANCE, EmcUnifiedStorageHandler::new);
            // CapabilityHelper.registerStackTypedHandler(EmcStackKey.INSTANCE, ...);
        });
    }
}
```

注意 `StackKeyRegistry.registerType` 对**重复注册会抛 `IllegalStateException`**（`StackKeyRegistry.java:16-19`），所以要先检查/保证只注册一次；`registerType` 无注销方法，且 `getAllTypes()` 返回不可变副本（`:34-37`）。

### 6.3 `UnifiedStorage.typedHandlerMap` 的静态初始化位置

**该字段在 0.7.30 不存在**（全仓库 grep `typedHandlerMap` 仅命中 `README.md:65-66` 的过时文档）。当前的等价物是：

| 现状 | 位置 | 类型 | 初始化 |
| --- | --- | --- | --- |
| `AbstractUnorderedStackHandler.type2buckets` | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:57` | 实例字段 `Map<ResourceLocation, TypeBucket>` | 每个 handler 实例构造时为空 map，按需 `computeIfAbsent`（`bucketOf`，`:807-810`） |
| `TypeBucket` 内部类 | 同上 `:769` | 单一类型的 key 分组（UI/迭代用） | 由 `bucketOf` / `ensureInIndex` 维护 |
| `CapabilityHelper.USHandlerMap` | `api/capability/helper/CapabilityHelper.java:47` | `static final Map<ResourceLocation, USHandler>`（UnifiedStorage 的能力包装工厂） | 静态，空 map；由 `registerUSHandler` 填充（`:54-66`） |
| `CapabilityHelper.CommonHandlerMap` | 同上 `:52` | `static final Map<ResourceLocation, CommonHandler>`（有序 `StackHandler` 的包装工厂） | 静态，空 map；由 `registerStackTypedHandler` 填充（`:68-80`） |
| `CapabilityHelper.BlockCapabilityMap` / `ItemCapabilityMap` | 同上 `:37` / `:42` | `static final Map<ResourceLocation, BlockCapability/ItemCapability>` | 静态，空 map；在 `FMLCommonSetupEvent` 中填充（`BeyondDimensions.java:71-77`） |
| `StackKeyRegistry.TYPES` | `api/storage/key/StackKeyRegistry.java:12` | `static final Map<ResourceLocation, IStackKey<?>>` | 静态，空 map；在 `FMLCommonSetupEvent` 中填充（`BeyondDimensions.java:65-68`） |

---

## 7. BD 是否发布 Maven 构件

**结论：未发布 Maven 构件。需用 CurseMaven / Modrinth Maven / 本地 jar 依赖。**

### 7.1 构建脚本里的证据

```groovy
// build.gradle:1-6
plugins {
    id 'java-library'
    id 'maven-publish'                 // ← 有 maven-publish 插件
    id 'net.neoforged.moddev' version '2.0.116'
    id 'idea'
}

// build.gradle:297-309
// Example configuration to allow publishing using the maven-publish plugin
publishing {
    publications {
        register('mavenJava', MavenPublication) {
            from components.java
        }
    }
    repositories {
        maven {
            url "file://${project.projectDir}/repo"      // ← 只有本地文件仓库
        }
    }
}
```

- 有 `maven-publish` 插件与 `mavenJava` publication，但 **repository 只有 `file://${projectDir}/repo`（本地目录）**，没有 Nexus/Maven Central/GitHub Packages 等远端地址。
- 仓库根目录下**没有 `repo/` 目录**（`Get-ChildItem -Directory` 只列出 `.git/.github/docs/files/gradle/licenses/src`），说明作者本地也未曾发布。
- `group = mod_group_id`（`build.gradle:18`）、`version = mod_version`（`:17`）→ 若真的发布，坐标应为 **`com.wintercogs.beyonddimensions:beyonddimensions-1.21.1-neoforge:0.7.30`**（archivesName 见 `:65-67`）。**但没有任何公开仓库承载它 → 不可用。**

### 7.2 CI 里的证据

```yaml
# .github/workflows/build-and-publish.yml:94-124（摘要）
- name: Publish (GitHub / CurseForge / Modrinth)
  uses: Kir-Antipov/mc-publish@v3.3
  with:
    curseforge-id: ${{ env.CURSEFORGE_ID }}      # 1222890  (:9)
    modrinth-id: ${{ env.MODRINTH_ID }}          # 6zGxpbt7 (:10)
    github-tag: v${{ ... }}
    files: build/libs/${{ ...jar_name }}
```

- 发布目标是 **CurseForge + Modrinth + GitHub Release**，**没有任何 Maven 发布步骤**（无 `./gradlew publish`）。
- 触发方式为 `on: [workflow_dispatch]`（`:3`），即手动触发。
- 另有 `.github/workflows/build.yml`（仅构建，未见发布逻辑）。

### 7.3 给附属模组的可行依赖方式

| 方式 | 写法 | 说明 |
| --- | --- | --- |
| **本地 jar（最稳）** | `compileOnly files('libs/beyonddimensions-1.21.1-neoforge-0.7.30.jar')` + `additionalRuntimeClasspath` | 从 CurseForge/Modrinth 下载 jar 放 `libs/`；**开发期最可靠**，不依赖第三方 Maven 是否收录 |
| **Modrinth Maven** | `maven { url "https://api.modrinth.com/maven" }` + `maven.modrinth:<slug-or-id>:<version>` | BD 的 Modrinth 项目 id = `6zGxpbt7`（`.github/workflows/build-and-publish.yml:10`）。**具体 slug 未确认**，用 id 形式或从项目页取版本号 |
| **CurseMaven** | `maven { url "https://cursemaven.com" }` + `curse.maven:<slug>-<projectId>:<fileId>` | BD 的 CurseForge 项目 id = `1222890`（`.github/workflows/build-and-publish.yml:9`）。**slug 未确认**；BD 自己的 build.gradle 也在用 CurseMaven 拉第三方（`build.gradle:30-32,181-182`） |
| JitPack | `com.github.Frostbite-time:BeyondDimensions:1.21.1-SNAPSHOT` | 仓库为 <https://github.com/Frostbite-time/BeyondDimensions>（README 链接可证）。JitPack 对 ModDevGradle 项目支持不保证，**未验证** |

> 由于 `reference/BeyondDimensions` 已有完整源码，附属模组开发期做**多项目 Gradle composite build / `includeBuild` / 直接把 BD 作为 `implementation project(':BeyondDimensions')`** 同样可行，且比 jar 依赖更好调试。这是本工作区条件下最省事的方案。

---

## 8. build.gradle 构建参考

**文件**：`build.gradle`（321 行）、`gradle.properties`（41 行）、`settings.gradle`（9 行）、`gradle/wrapper/gradle-wrapper.properties`（7 行）

### 8.1 插件与工具链

```groovy
// build.gradle:1-6
plugins {
    id 'java-library'
    id 'maven-publish'
    id 'net.neoforged.moddev' version '2.0.116'   // ← ModDevGradle（不是 NeoGradle）
    id 'idea'
}

// build.gradle:65-79
base { archivesName = "${mod_id}-${minecraft_version}-neoforge" }
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
neoForge {
    version = project.neo_version
    parchment {
        mappingsVersion = project.parchment_mappings_version
        minecraftVersion = project.parchment_minecraft_version
    }
    ...
}
```

| 项 | 值 | 证据 |
| --- | --- | --- |
| 构建插件 | **ModDevGradle**（`net.neoforged.moddev`）`2.0.116` | `build.gradle:4` |
| 其他插件 | `java-library`、`maven-publish`、`idea` | `build.gradle:2,3,5` |
| Java | toolchain 21 | `build.gradle:70` |
| NeoForge | `21.1.234`（范围 `[21.1.194,)`） | `gradle.properties:21,22` |
| Parchment | MC `1.21.1` / mappings `2024.11.17` | `gradle.properties:10,11` |
| Gradle Wrapper | `gradle-9.2.0-bin` | `gradle/wrapper/gradle-wrapper.properties:3` |
| 坐标 | `group=com.wintercogs.beyonddimensions`、`archivesName=beyonddimensions-1.21.1-neoforge`、`version=0.7.30` | `gradle.properties:31`、`build.gradle:65-67,17-18` |
| `settings.gradle` | 仅 `pluginManagement { gradlePluginPortal() }` + `fooJay-resolver-convention 1.0.0` | `settings.gradle:1-9` |

### 8.2 其他值得抄的配置

| 配置 | 位置 | 说明 |
| --- | --- | --- |
| 运行配置 `client/server/gameTestServer/data`，JVM `-Xms6G -Xmx8G`（data 未设） | `build.gradle:86-133` | `data` 跑 datagen 输出到 `src/generated/resources/`（`:109-117`） |
| `mods { "${mod_id}" { sourceSet(sourceSets.main) } }` | `build.gradle:135-142` | ModDevGradle 必需的 mod↔源码绑定 |
| `src/generated/resources` 加入 main resources | `build.gradle:146` |  |
| `localRuntime` 配置 | `build.gradle:148-154` | 运行期存在但**不被依赖方传递**的依赖（附属模组做"仅开发期测试 BD"时很有用） |
| `jarJar` 内嵌 `tinypinyin` | `build.gradle:156-166` | 把第三方库打包进模组并声明 `additionalRuntimeClasspath` |
| `generateModMetadata` 从 `src/main/templates` 展开 `neoforge.mods.toml` | `build.gradle:255-277`；模板 `src/main/templates/META-INF/neoforge.mods.toml:1-100` | 用 `${mod_id}` 等占位符；`neoForge.ideSyncTask generateModMetadata`（`:295`） |
| License/第三方声明打进 `META-INF` | `build.gradle:279-292` |  |
| 编译编码 UTF-8 | `build.gradle:311-313` |  |
| 依赖仓库清单 | `build.gradle:20-63` | `mavenCentral`、`maven.blamejared.com`、`modmaven.dev`、`cursemaven.com`、`api.modrinth.com/maven`、`maven.createmod.net`、aliyun 镜像等 |
| 软依赖（optional）示例 | `src/main/templates/META-INF/neoforge.mods.toml:89-94` | `modId="create" type="optional" ordering="AFTER"` —— 附属模组声明 BD 依赖时可照此写 `type="required"` |

### 8.3 附属模组可直接复用的最小骨架

```groovy
plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '2.0.116'      // 与 BD 相同版本，避免 plugin 冲突
}
group = 'your.group'                                  // 不要用 com.wintercogs.*
version = '0.1.0'
base { archivesName = "youremc-${minecraft_version}-neoforge" }
java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = '21.1.234'                              // 与 BD 对齐
    parchment { minecraftVersion = '1.21.1'; mappingsVersion = '2024.11.17' }
    runs { client { client() }; server { server() } }
    mods { "youremc" { sourceSet(sourceSets.main) } }
}

repositories {
    maven { url 'https://cursemaven.com' }
    maven { url 'https://api.modrinth.com/maven' }
}
dependencies {
    // 开发期用本地 jar / composite build
    compileOnly files('libs/beyonddimensions-1.21.1-neoforge-0.7.30.jar')
    additionalRuntimeClasspath files('libs/beyonddimensions-1.21.1-neoforge-0.7.30.jar')
}
```

并且在 `src/main/templates/META-INF/neoforge.mods.toml` 里声明对 BD 的依赖：

```toml
[[dependencies.youremc]]
    modId = "beyonddimensions"
    type = "required"
    versionRange = "[0.7.30,)"
    ordering = "AFTER"
    side = "BOTH"
```

---

## 9. 对附属模组的实现建议

### 9.1 推荐方案（首选）：**(a) 自定义 `IStackKey` 资源类型**

**理由**

1. **这是 BD 唯一官方支持、且被官方自己反复验证过的"网络级可持久化扩展点"**：BD 的 Botania / Ars / Mek / IFS 四个集成模块走的就是这条路（`BotaniaModule.java:63`、`ArsModule.java:55`、`MekModule.java:34`、`IFSModule.java:49`），README 也把它写成附属制作方式（`README.md:60-72`）。
2. **"随网络一起保存/加载"零成本**：`DimensionsNet.save` 会把整个 `UnifiedStorage` 序列化（`DimensionsNet.java:427`），`load` 会整体反序列化（`:358`）；你的 key 只要注册过就能自动往返。
3. **"随网络一起同步"大部分零成本**：BD 的网络界面用 `DisorderedSlotGroupSync` 把存储里**所有 key**（增量 + 首次全量）推给客户端（`DisorderedSlotGroupSync.java:56-70,134-148,314-344`），客户端用同一套 `IStackKey.STREAM_CODEC` 解码（`IStackKey.java:38-59`、`DimensionsNetMenu.java:59-63`）。
4. 网络合并（`NetGifter`）时 EMC 会自动被搬运到存活网络（`DimensionsNet.java:736-740`），省掉一大块边界逻辑。
5. 生命周期天然正确：EMC 就是网络存储的一部分，网络删除即 `clearStorage()`（`DimensionsNet.java:779`）。

**必须做的三件事**

| # | 事项 | 依据 |
| --- | --- | --- |
| 1 | 实现 `IStackKey<EmcType>`（推荐 `extends LongStackKey<EmcType>`，抄 `ManaStackKey` / `EnergyStackKey` 的单例模式：无字段 `MapCodec`、`serialize/deserialize` 空实现、`serializeNBT` 空 tag） | `LongStackKey.java:10-116`、`EnergyStackKey.java:22-181` |
| 2 | 实现并返回一个 `IStackRender`（`getRender()` 不可返回 null），否则 BD 存储界面/搜索/tooltip 会 NPE | `BDBaseGUI.java:56,86-87`、`TooltipHelper.java:110`、`ClientNetStorageSearchHelper.java:303`、`IStackKey.java:194` |
| 3 | 在 `FMLCommonSetupEvent` + `event.enqueueWork(...)` 中 `StackKeyRegistry.registerType(...)`（只注册一次） | `BeyondDimensions.java:61-68`、`StackKeyRegistry.java:14-21` |

**建议同时做的两件事**

- 用 `UnifiedStorageBeforeInsertHandler.addHandler(...)` / `BeforeExtractHandler` 把"物品进出网络"与 EMC 增减绑定（官方公开钩子，`UnifiedStorageBeforeInsertHandler.java:42,57`、`UnifiedStorage.java:91,122`）。
- 如果你要做 EMC 专属 UI（不是 BD 的存储界面），让菜单 `extends BDBaseMenu` 并覆写 `writeQuickDataTag` / `readQuickDataTag`，即可**复用 BD 已注册的 `QuickDataTagPacket`** 同步 EMC 数值（`QuickDataTagPacket.java:19-47`、`BDBaseMenu.java:117-146`、`BDPackets.java:135-142`）。

### 9.2 备选方案：**(b) 独立 SavedData，net id 作 key**

**何时选它**：EMC 必须是一种**私有数值**——不希望出现在 BD 存储界面里、不希望被网络通道/AE/网络泵抽取、不希望占用"种类槽"、或者 EMC 池结构比单个 long 复杂（含等级/倍率/统计）。

**必须处理的四件事**

| # | 事项 | 依据 |
| --- | --- | --- |
| 1 | 挂 Overworld：`server.overworld().getDataStorage().computeIfAbsent(FACTORY, "<yourmod>_emc")` | 仿 `NetRegistryIndex.java:60-63`、NeoForge 文档"非维度专属数据应挂 Overworld" |
| 2 | **懒初始化**：`get(netId)` 找不到就返回 0 并写入；不要依赖 `Created` | `DimensionsNet.java:144`（只有新建才派发）、`:344-388`（load 不派发） |
| 3 | `Destroyed` 事件里删除条目（注意它**没有 `getNet()`**，只能用 `getDestroyedId()`） | `DimensionsNetEvent.java:46-117,787` |
| 4 | 自己发同步包（BD 的菜单同步只覆盖 `UnifiedStorage`）；或在自建菜单里借道 `QuickDataTagPacket` | `DisorderedSlotGroupSync.java:314-344`、`QuickDataTagPacket.java:19-47` |

### 9.3 不推荐

| 方案 | 原因 |
| --- | --- |
| (c) DataAttachment 挂 `DimensionsNet` | **技术上不可行**（21.1 attachment 仅支持 BlockEntity/Chunk/Entity，且要求 `IAttachmentHolder`；`DimensionsNet` 是 `SavedData`） |
| (c') DataAttachment 挂 `ServerLevel` | 21.1 是否支持未确认；即便支持也只能得到"每维度一份池子"，无法按网络区分 |
| 继承 `DimensionsNet` | SavedData 工厂硬编码（`DimensionsNet.java:137,193`），无法替换加载路径 |
| mixin `DimensionsNet.save/load` | 侵入性强、版本升级易碎；且方案 (a) 已能达成同样目标 |

### 9.4 风险清单（按严重度排序）

| ID | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| R1 | 附属模组被卸载后，已落盘的自定义 `IStackKey` 条目在 `deserializeNBT` 中因类型未注册而**被静默丢弃** | EMC 数据永久丢失（不崩溃） | 文档提示；或额外把 EMC 抄一份到自己的 SavedData 作为冗余；或改用方案 (b) |
| R2 | `IStackRender` 未实现/返回 null | BD 存储 UI、搜索、tooltip 崩溃 | 必须实现；参考 `EnergyStackKeyRender`（`api/storage/key/render/EnergyStackKeyRender.java:27`） |
| R3 | 同步只覆盖"BD 界面打开时" | 关掉界面后客户端 EMC 数值是陈旧的 | 自建 HUD/机器 UI 时自行发包（可借道 `QuickDataTagPacket`） |
| R4 | 网络合并（`mergeOtherNet`）语义：选 (a) 自动合并；选 (b) 只能拿到 `Destroyed` 事件的快照，**不知道并入哪个网络** | 选 (b) 时 EMC 可能在合并中丢失或错并 | 选 (a) 规避；选 (b) 时用 `getOwner()` + `server.getPlayerList().getPlayer(uuid)` 推断目标网络，并准备"找不到就当消失"的兜底 |
| R5 | `StackKeyRegistry` 是 `HashMap`，`FMLCommonSetupEvent` 并行派发 | 极端情况下注册竞态 | 用 `event.enqueueWork(...)` 注册 |
| R6 | EMC 作为资源会被物流系统/其他模组抽取（方案 a） | 经济性 bug、玩家可刷 EMC | 不注册 `BlockCapabilityMap` / `USHandlerMap`（`CapabilityHelper.java:37,47`）；或在 `BeforeExtractHandler` 里拒绝 EMC key 的抽取（`UnifiedStorageBeforeExtractHandler.java:46`） |
| R7 | 网络被删除后 `DimensionsNet` 对象仍在内存、仍在 game bus 上（`onServerTick` 监听器永不注销，且会继续 `setDirty()`） | 轻微内存/性能泄漏 | 附属模组**不要**自行 `new DimensionsNet(...)`；必须用 `createNewNetForPlayer` / `getNetFromId` |
| R8 | 服务器崩溃丢失最近 autosave 之后的 EMC | 数据回退 | 无法从模组层解决（BD 本身也一样）；可在关键交易后调用原版 `server.saveEverything`（代价高，不建议） |
| R9 | `Destroyed` 不继承 `DimensionsNetEvent` | 写成 `DimensionsNetEvent e` 统一处理会漏掉它 | 单独监听 `DimensionsNetEvent.Destroyed`，只用 `getDestroyedId()` 等 |

### 9.5 建议的落地顺序

1. 实现 `EmcStackKey`（抄 `EnergyStackKey`）+ `EmcStackKeyRender`；
2. 在 `FMLCommonSetupEvent#enqueueWork` 中 `StackKeyRegistry.registerType(EmcStackKey.INSTANCE)`；
3. 用 `net.getUnifiedStorage().insert/extract(EmcStackKey.INSTANCE, ...)` 读写 EMC（`DimensionsNet.java:821-824`、`UnifiedStorage.java:86-133`）；
4. 用 `UnifiedStorageBeforeInsertHandler/BeforeExtractHandler` 把物品进出与 EMC 增减绑定；
5. 监听 `DimensionsNetEvent.Created`（初始 EMC）、`Destroyed`（补偿）；**不要**依赖它做已存在网络的初始化；
6. 若需要 BD 界面之外的 EMC 显示，自建 `extends BDBaseMenu` 的菜单并覆写 `writeQuickDataTag/readQuickDataTag` 走 `QuickDataTagPacket`；
7. 联调：确认 `world/data/BDNet_<id>.dat` 中出现 `UnifiedStorage.stacks[].key.type = "<yourmod>:stack_type/emc"`。

---

## 10. 未确认事项

| # | 事项 | 状态 |
| --- | --- | --- |
| 1 | `setDirty()` 后"具体哪一 tick 落盘"的**原版实现细节**（autosave 间隔 6000 tick、`/save-all`、关服的调用链） | **未在本地源码确认**（工作区无 MC/NeoForge 源码与 gradle 缓存）；结论依据 NeoForge 官方 1.21.1 文档与通用原版行为 |
| 2 | NeoForge 21.1 的 `ServerLevel` 是否实现 `IAttachmentHolder`（即 level attachment 是否可用） | **未确认**。官方 1.21.1 文档只列 block entities / chunks / entities；`LevelAttachmentsSavedData` 仅见于 1.21.4+ javadoc，故推断 21.1 不支持 |
| 3 | BD 在 Modrinth / CurseForge 的确切 **slug 与文件 id**（用于 CurseMaven / Modrinth Maven 坐标） | **未确认**（`api.modrinth.com` 请求超时；仅有 project id：CurseForge `1222890`、Modrinth `6zGxpbt7`，来自 `.github/workflows/build-and-publish.yml:9-10`） |
| 4 | `.github/workflows/build.yml` 的完整内容 | **未读取**（仅确认存在；本报告结论只依赖 `build-and-publish.yml`） |
| 5 | `reference/BeyondDimensions/files/` 下的性能测试报告内容 | **未读取**（与本次问题无关） |

---

### 附：本报告引用的所有文件

```
build.gradle
gradle.properties
settings.gradle
gradle/wrapper/gradle-wrapper.properties
README.md
.github/workflows/build-and-publish.yml
src/main/templates/META-INF/neoforge.mods.toml
src/main/java/com/wintercogs/beyonddimensions/BeyondDimensions.java
src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/DimensionsNet.java
src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/UnifiedStorage.java
src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/NetRegistryIndex.java
src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/PlayerNetIndex.java
src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java
src/main/java/com/wintercogs/beyonddimensions/api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java
src/main/java/com/wintercogs/beyonddimensions/api/event/dimensionnet/DimensionsNetEvent.java
src/main/java/com/wintercogs/beyonddimensions/api/event/dimensionnet/NetedBlockEvent.java
src/main/java/com/wintercogs/beyonddimensions/api/event/dimensionnet/NetedItemEvent.java
src/main/java/com/wintercogs/beyonddimensions/api/storage/key/IStackKey.java
src/main/java/com/wintercogs/beyonddimensions/api/storage/key/IStackRender.java
src/main/java/com/wintercogs/beyonddimensions/api/storage/key/StackKeyRegistry.java
src/main/java/com/wintercogs/beyonddimensions/api/storage/key/impl/LongStackKey.java
src/main/java/com/wintercogs/beyonddimensions/api/storage/key/impl/EnergyStackKey.java
src/main/java/com/wintercogs/beyonddimensions/api/storage/handler/IStackHandler.java
src/main/java/com/wintercogs/beyonddimensions/api/storage/handler/impl/AbstractUnorderedStackHandler.java
src/main/java/com/wintercogs/beyonddimensions/api/storage/handler/impl/UnorderedStackHandlerRemoveZero.java
src/main/java/com/wintercogs/beyonddimensions/api/capability/helper/CapabilityHelper.java
src/main/java/com/wintercogs/beyonddimensions/common/init/BDPackets.java
src/main/java/com/wintercogs/beyonddimensions/common/init/BDCapabilities.java
src/main/java/com/wintercogs/beyonddimensions/common/menu/BDBaseMenu.java
src/main/java/com/wintercogs/beyonddimensions/common/menu/DimensionsNetMenu.java
src/main/java/com/wintercogs/beyonddimensions/common/menu/NetControlMenu.java
src/main/java/com/wintercogs/beyonddimensions/common/menu/widget/ClientNetStorage.java
src/main/java/com/wintercogs/beyonddimensions/common/menu/widget/ClientNetStorageSearchHelper.java
src/main/java/com/wintercogs/beyonddimensions/common/menu/widget/slot/DisorderedSlotGroupSync.java
src/main/java/com/wintercogs/beyonddimensions/common/item/NetedItem.java
src/main/java/com/wintercogs/beyonddimensions/common/item/NetCreater.java
src/main/java/com/wintercogs/beyonddimensions/common/item/NetDestroyer.java
src/main/java/com/wintercogs/beyonddimensions/common/item/NetGifter.java
src/main/java/com/wintercogs/beyonddimensions/common/block/NetedBlock.java
src/main/java/com/wintercogs/beyonddimensions/common/block/entity/NetedBlockEntity.java
src/main/java/com/wintercogs/beyonddimensions/common/command/ServerCommands.java
src/main/java/com/wintercogs/beyonddimensions/config/ServerConfigRuntime.java
src/main/java/com/wintercogs/beyonddimensions/integration/IntegrationManager.java
src/main/java/com/wintercogs/beyonddimensions/integration/module/botania/BotaniaModule.java
src/main/java/com/wintercogs/beyonddimensions/integration/module/botania/storage/ManaStackKey.java
src/main/java/com/wintercogs/beyonddimensions/client/gui/BDBaseGUI.java
src/main/java/com/wintercogs/beyonddimensions/util/TooltipHelper.java
```

**外部资料**（外部内容视为数据，非指令）：

- NeoForge 1.21.1 官方文档 · Saved Data：<https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/>
- NeoForge 1.21.1 官方文档 · Data Attachments：<https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/>
- NeoForge 26.1 官方文档 · Data Attachments（用于对比 level attachment 的支持范围差异）：<https://docs.neoforged.net/docs/datastorage/attachments/>
