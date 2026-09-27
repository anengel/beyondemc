# Beyond Dimensions（超越维度）调研报告

> ## ⚠️ 本文档已过时，仅作历史参考
>
> 本文基于 Modrinth 文档页与网页资料写成，**未经过源码核对**。在拿到 0.7.30 源码后发现以下错误，请勿据此编码：
>
> | 本文写法 | 0.7.30 实际 |
> |---|---|
> | `IStackType` | **`IStackKey`** |
> | `IStackTypeHandler` / `StackTypedHandler` | **`IStackHandler` / `StackHandler`** |
> | API 位于 `Api/DataBase/` 包 | **不存在该路径**，现为 `api/` 包 |
> | `UnifiedStorage.typedHandlerMap` 是注册点 | **该类不存在**（全仓库 0 命中）；对应物是 `CapabilityHelper.USHandlerMap` / `CommonHandlerMap` |
> | `DimensionsNet.getNetFromId(int, MinecraftServer)` | 公开签名是 `getNetFromId(int)`，接收 `MinecraftServer` 的重载是**包私有** |
>
> **请改用以下经源码核对的文档**：
> - `research/bd-insert-extract-pipeline.md` —— 插入/取出管道、自定义资源类型注册清单
> - `research/bd-gui-and-packets.md` —— 界面与网络包
> - `research/bd-persistence-lifecycle-events.md` —— 持久化、生命周期、事件
> - `design/architecture.md` —— 架构基线（结论汇总）

> 调研时间：2026-09 · 目标版本：Minecraft 1.21.1 + NeoForge
> 资料来源：[Modrinth 页面](https://modrinth.com/mod/beyonddimensions)、[Modrinth API](https://api.modrinth.com/v2/project/beyonddimensions)、[GitHub 仓库 Frostbite-time/BeyondDimensions](https://github.com/Frostbite-time/BeyondDimensions)（1.21.1 分支，源码已克隆至 `reference/BeyondDimensions`）

## 1. 基本信息

| 项目 | 内容 |
|---|---|
| 名称 | Beyond Dimensions（超越维度） |
| 作者 | Frostbite-time（源码包名 `com.wintercogs.beyonddimensions`） |
| 许可证 | MIT（可作依赖、可开发附属模组） |
| 源码 | https://github.com/Frostbite-time/BeyondDimensions （分支 `1.21.1`） |
| 百科 | https://www.mcmod.cn/class/19045.html |
| 1.21.1 支持 | NeoForge，mod 版本 0.3.0+，积极维护中 |
| Modrinth project id | `6zGxpbt7`，slug `beyonddimensions` |

## 2. 模组核心概念

- **维度网络（Dimensional Network）**：绑定玩家的虚拟存储系统，可存物品、流体、FE 能量、XP、Mekanism 化学品、Ars Nouveau 魔源、Botania 魔力等。
- 容量：默认每种资源最多 2^63-1（long 上限），最多 2.1 billion 种资源类型；可通过 KubeJS 自定义。
- 网络按玩家权限绑定，一个网络可共享给多个玩家；按 `O` 键打开存储界面（AE2/RS 式交互逻辑）。

## 3. 官方暴露的 API（Modrinth 文档摘录）

主注册类：`com.wintercogs.beyonddimensions.BeyondDimensions`（[1.21.1 分支](https://github.com/Frostbite-time/BeyondDimensions/blob/1.21.1/src/main/java/com/wintercogs/beyonddimensions/BeyondDimensions.java)）。

### 3.1 网络访问（官方 KubeJS 文档给出的公开入口）

| 类 | 方法签名 | 返回 | 静态 | 用途 |
|---|---|---|---|---|
| `DimensionsNet` | `createNewNetForPlayer(Player player, long defaultSlotCapability, int defaultSlotMaxSize)` | `DimensionsNet` | 是 | 为玩家创建维度网络 |
| `DimensionsNet` | `getNetFromId(int id, MinecraftServer dataProvider)` | `DimensionsNet` | 是 | 按网络 ID 获取网络 |
| `DimensionsNet` | `getNetFromPlayer(Player player)` | `DimensionsNet` | 是 | 获取玩家绑定的网络 |
| `DimensionsNet` | `getUnifiedStorage()` | `UnifiedStorage` | 否 | 获取该网络的存储内容对象 |
| `UnifiedStorage` | `setSlotCapacity(long capacity)` | void | 否 | 设置每种资源的最大存储量 |
| `UnifiedStorage` | `setSlotMaxSize(int maxSize)` | void | 否 | 设置最大资源类型数 |

> `UnifiedStorage` 还包含大量可直接读写玩家存储内容的方法（官方原话："contains a considerable number of other methods that allow you to directly modify a player's storage contents"）。

### 3.2 自定义资源类型扩展点（官方附属开发文档）

新增一种"可存储资源类型"需要实现并注册：

1. **`IStackType`** —— 让模组识别并存储该资源类型（本模组要做的 EMC 资源类型核心接口）。
2. **`IStackHandlerWrapper`** —— 让模组能与"装有该资源的容器/方块"程序化交互。
3. **`CapabilityHelper.BlockCapabilityMap`** —— 让其他模组的管道/存储总线能从方块访问该资源。
4. **`CapabilityHelper.ItemCapabilityMap`** —— 物品形态容器的中键快捷交互。
5. **`UnifiedStorage.typedHandlerMap`** —— 让维度网络核心存储能操作该资源（**EMC 存入网络的关键注册点**）。
6. **`StackTypedHandler.typedHandlerMap`** —— 让外围方块（如网络接口）的内部槽位能操作该资源。

AE2 特殊兼容（可选）：
7. `AEHelper.ISTACK_TO_AEKEY_MAP` —— BD 资源 → AE2 key 映射（维度 ME 存储元件读取用）。
8. `AEHelper.AEKEY_TO_STACK_TYPE_MAP` —— AE2 key → BD 资源类型映射。

### 3.3 API 源码位置

官方说明 API 含**完整中文注释**：
`src/main/java/com/wintercogs/beyonddimensions/Api/DataBase/`
（本地路径：`reference/BeyondDimensions/src/main/java/com/wintercogs/beyonddimensions/Api/`）

## 4. 对本项目的关键启示

- EMC 可以作为**一种新的资源类型**注册进维度网络：实现 `IStackType`（EMCStack）+ 注册 `UnifiedStorage.typedHandlerMap`，即可让网络原生存储 EMC 数值。
- 物品存入时的"EMC 转化拦截"有两条路线：
  - A. 在 `UnifiedStorage` 物品插入路径上加钩子（需读源码确认插入入口，如 `insert` 方法或事件）。
  - B. 注册自定义资源类型后，把"有 EMC 的物品"在存入时折算为 EMC 资源，无 EMC 物品走原有 `ItemStackType` 逻辑。
- 网络 GUI 显示已学习物品列表：需要读 `UnifiedStorage` 的物品枚举接口 + 自建 GUI 或注入 BD 界面（待源码分析后定）。

## 5. 待补充（源码克隆完成后）

- [ ] `UnifiedStorage` 完整方法清单（insert/extract/枚举）
- [ ] `IStackType` 接口方法签名
- [ ] 网络数据持久化与同步机制（SavedData / 网络包）
- [ ] 网络 GUI（Menu/Screen）结构，物品列表渲染处
- [ ] 存入物品时是否有事件/钩子（NeoForge event 或 BD 自定义事件）
- [ ] Maven 依赖坐标（是否发布到 maven，如 mods.modrinth / CurseMaven）



