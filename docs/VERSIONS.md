# 版本基线

> **纪律**：本文件是唯一版本权威。任何版本变更必须更新此文件，并按 `docs/plan/ROADMAP.md` §3.4 跑回归清单。

## 项目自身

| 项 | 值 |
|---|---|
| 模组名 | Beyond EMC |
| mod id | `beyondemc` |
| 包名 | `com.zhuyuhang.beyondemc` |
| 版本 | `0.1.0`（未发布） |
| 作者 | ZhuYuhang |
| 许可证 | MIT（与两个前置模组一致；`LICENSE` 已放入工程根，构建时打进 jar 的 `META-INF`） |
| Minecraft | `1.21.1` |
| NeoForge（编译基线） | `21.1.234`（与 Beyond Dimensions 一致） |
| NeoForge（依赖下界） | `[21.1.194,)`（BD 与 ProjectE 中更严者） |
| Java | `21` |
| 本机 JDK | `C:\Program Files\Java\jdk-21.0.12.1`（**未进 PATH，需显式指定**，用 `tools\gradlew-here.cmd`） |
| ModDevGradle | `2.0.116`（与 Beyond Dimensions 一致） |
| Gradle Wrapper | `9.2.0`（BIN，wrapper 文件取自 Beyond Dimensions） |
| Parchment | `1.21.1` / `2024.11.17` |
| foojay-resolver-convention | `1.0.0`（与 BD 一致；**不是** `0.8.0`，后者在 Gradle 9 下未必可用） |

## 前置模组（源码参考基线）

| 模组 | 版本 | commit | 分支 | 包名 | mod id | 许可证 |
|---|---|---|---|---|---|---|
| Beyond Dimensions（超越维度） | `0.7.30` | `a0d2e76` | `1.21.1` | `com.wintercogs.beyonddimensions` | `beyonddimensions` | MIT |
| ProjectE（等价交换） | `1.1.0` | `f432b0c` | `mc1.21.1` | `moze_intel.projecte` | `projecte` | MIT |

本地路径：`reference/BeyondDimensions`、`reference/ProjectE`（**只读，禁止修改**）

**证据完整性核验（2026-09）**：两个参考仓库的 `git status --porcelain` 均为**空**（工作树干净），HEAD 分别指向上表的 commit。因此 `docs/research/` 与 `docs/development/` 中所有 `文件:行号` 证据对本机副本**有效**，不存在"本机副本被改过"的问题（该疑虑曾出现在 `docs/development/build-and-deps.md` 的早期版本，已更正作废）。

> ⚠️ 注意：`reference/BeyondDimensions` 的 `git describe` 会输出 `v0.7.30-1.20.1-forge`，这是 tag 命名遗留问题，**实际的 `gradle.properties` 与分支都是 1.21.1**。以 `gradle.properties` 的 `minecraft_version=1.21.1` / `mod_version=0.7.30` 为准。

## 依赖接入

### 首选：本地 jar（已在 `libs/` 就位并校验）

| 文件 | 字节数 | 校验结果 |
|---|---|---|
| `libs/beyonddimensions-1.21.1-0.7.30.jar` | 3550119 | modId `beyonddimensions`，version `0.7.30`，要求 NeoForge `[21.1.194,)` / MC `[1.21.1]`，MIT |
| `libs/projecte-1.21.1-1.1.0.jar` | 2426228 | modId `projecte`，version `1.1.0`，要求 NeoForge `[21.1.119,)` / MC `[1.21.1]`，MIT；构建时间戳 `2025-06-03`，与 CurseForge fileId `6611984` 的元数据吻合 |
| `libs/create-1.21.1-6.0.10.jar` | 19123767 | modId `create`，version `6.0.10`，要求 NeoForge `[21.1.219,)` / MC `1.21.1`。**可选依赖，且只在测试时需要**（我们的 Mixin 不引用任何 Create 类型）。`META-INF/jarjar/` 内已内嵌 flywheel 1.0.6 / ponder 1.0.82 / Registrate 1.3.0，**不需要**单独提供，也**不需要** Modrinth Maven |

原始文件名含中文与方括号，已重命名为 ASCII（jar 内容未动，字节数不变）。

Create 不进 `compileOnly`，只在 `-PwithCreate` 时进 `runtimeOnly` —— 理由与实测方法见下方「Create 集成」节。

### Create 集成（可选依赖）的接入方式

```groovy
def createJar = file("libs/create-1.21.1-${create_version}.jar")
if (providers.gradleProperty('withCreate').isPresent()) {
    runtimeOnly files(createJar)   // 没有 compileOnly
}
```

| 判据 | 结论 | 依据 |
|---|---|---|
| 需要 `compileOnly` 吗 | **不需要** | 我们对 Create 的集成是 Mixin **BD** 的「蓝图接口」方块实体（`...integration.module.create.block.entity.SchematicannonPathWayBlockEntity$NetedSchematicannonItemHandler`），注入的 `getSlots()I` / `getStackInSlot(I)ItemStack` / `extractItem(IIZ)ItemStack` 全是 Minecraft 类型，不引用 Create 类型 —— 这样 Create 缺席时我们的 mixin 类也能安全加载 |
| 需要单独准备 flywheel / ponder 吗 | **不需要** | `create-1.21.1-6.0.10.jar` 的 `META-INF/jarjar/` 含 `flywheel-neoforge-1.21.1-1.0.6.jar`、`ponder-neoforge-1.0.82+mc1.21.1.jar`、`Registrate-MC1.21-1.3.0+67.jar`，由 NeoForge jarJar 自动带上 |
| 需要 Modrinth Maven 仓库吗 | **不需要** | 同上；且 `libs/` 已是首选接入方式 |
| Create 缺席时会怎样 | 安全降级 | BD 的 `CreateModule` 标了 `@BDIntegrationModule(modId = OtherModIds.CREATE)`，不注册「蓝图接口」方块；我们的 `beyondemc.create.mixins.json` 由 `BeyondEmcCreateMixinPlugin` 按**类路径探测**整体跳过 |

```groovy
compileOnly files("libs/beyonddimensions-1.21.1-0.7.30.jar")
runtimeOnly files("libs/beyonddimensions-1.21.1-0.7.30.jar")
compileOnly files("libs/projecte-1.21.1-1.1.0.jar")
runtimeOnly files("libs/projecte-1.21.1-1.1.0.jar")
```

> ⚠️ **模组依赖必须用 `runtimeOnly`**（早期写法 `additionalRuntimeClasspath` 经实测会导致模组不进 run 的 Mod List）。
> 见 `docs/testing/phase1-report.md` §4。

### 备用：CurseMaven（CI 友好，不依赖本地文件）

两个前置模组**都不发布公共 Maven 构件**（各自的 `publishing` 块只指向本地 `file://` 目录或没有上传目标，CI 只发 CurseForge / Modrinth / GitHub Release），因此走 CurseMaven 代理。

```groovy
repositories {
    maven { name = 'CurseMaven'; url = 'https://cursemaven.com'; content { includeGroup 'curse.maven' } }
}
dependencies {
    compileOnly "curse.maven:beyond-dimensions-1222890:8812500"
    runtimeOnly "curse.maven:beyond-dimensions-1222890:8812500"

    compileOnly "curse.maven:projecte-226410:6611984"
    runtimeOnly "curse.maven:projecte-226410:6611984"
}
```

| 模组 | CurseForge projectId | fileId | 文件名 / 时间 |
|---|---|---|---|
| Beyond Dimensions 0.7.30 | `1222890` | `8812500` | 2026-09-05 |
| ProjectE 1.1.0 | `226410` | `6611984` | `ProjectE-1.21.1-PE1.1.0.jar`，2025-06-03 |

- ProjectE **不在 Modrinth 上**（已核实否定），只能走 CurseMaven。
- 模组依赖必须用 `compileOnly` + `runtimeOnly`（**不能**用 `additionalRuntimeClasspath` —— 实测不会进 run 的 Mod List），**不能**用 `implementation`。
- 核实过程与来源 URL 见 `docs/development/build-and-deps.md` §2.b。
- 离线兜底：下载 release jar 放 `libs/`，改用 `compileOnly files(...)` + `runtimeOnly files(...)`。

## 关键 API 锚点（升级前置模组后必须复核）

| 锚点 | 位置 | 用途 |
|---|---|---|
| `UnifiedStorage.insert` | `api/dimensionnet/UnifiedStorage.java:87` | 所有存入的唯一收口 |
| `UnifiedStorageBeforeInsertHandler.addHandler/onBeforeInsert` | `api/dimensionnet/helper/UnifiedStorageBeforeInsertHandler.java:42/57` | **折算钩子（官方 API）** |
| `UnifiedStorageBeforeExtractHandler.addHandler/onBeforeExtract` | `api/dimensionnet/helper/UnifiedStorageBeforeExtractHandler.java:46/61` | **网络接口兑换钩子（已采用）**。⚠️ 早期版本记为"未采用（重入风险）"，**该结论已作废**：重入问题由 `MintingGuard` 解决，且钩子签名**没有 `simulate` 参数**，需配 `UnifiedStorageExtractMixin` 记录该标志（见 ADR-004'） |
| `UnifiedStorage.extract(IStackKey,long,boolean,boolean)` | `api/dimensionnet/UnifiedStorage.java:118` | 全部抽取路径的收口（slot/TagKey/extractByKey 都转发到此）。⚠️ **有三个同名重载，写 Mixin 必须带完整描述符** |
| `ClientNetStorage.sourceStorage` | `common/menu/widget/ClientNetStorage.java:31` | 客户端侧"绝对真实"的存储镜像（不含我们注入的虚拟条目）——判库存的唯一正确数据源 |
| `NetInterfaceAccess.transferFromNet` | `common/menu/NetInterfaceAccess.java:127` | 网络接口从网络抽取的入口（`simulate` 硬编码 false）；`:120` 空过滤器直接跳过 |
| `StackKeyRegistry.registerType` | `api/storage/key/StackKeyRegistry.java:14` | 注册 `EmcStackKey` |
| `IStackKey` / `LongStackKey` / `IStackRender` | `api/storage/key/` | 自定义资源类型接口 |
| `DimensionsNet.save/load` | `api/dimensionnet/DimensionsNet.java:394/344` | 知识集合的持久化注入点 |
| `DimensionsNet.mergeOtherNet` | `api/dimensionnet/DimensionsNet.java:726` | 网络合并时的知识集合合并 |
| `DimensionsNetMenu.buildIndexList/updateViewerStorage` | `common/menu/DimensionsNetMenu.java:241/229` | 虚拟条目注入点 |
| `ClientNetStorage.cacheIndexes/matchFilter` | `common/menu/widget/ClientNetStorage.java:45/258` | 破排序缓存 / 搜索过滤（都是 private，需 Accessor/Invoker） |
| `BDBaseGUI.slotClicked` | `client/gui/BDBaseGUI.java:153` | 虚拟槽点击拦截点 |
| `AbstractUnorderedStackHandler.deserializeNBT` | `api/storage/handler/impl/AbstractUnorderedStackHandler.java:866` | 读档会触发 insert 钩子（风险 R1 的根源） |
| `IEMCProxy.getValue/getSellValue/getPersistentInfo` | `api/proxy/IEMCProxy.java:152/188/199` | 定价与归一化 |
| `PECapabilities.KNOWLEDGE_CAPABILITY` | `api/capabilities/PECapabilities.java:42` | 玩家知识（v1 不使用，v2 备用） |
| `EMCRemapEvent` | `api/event/EMCRemapEvent.java` | 价格缓存失效 |

## 变更记录

| 日期 | 变更 | 影响 |
|---|---|---|
| 资料整理时 | 建立基线 | — |
| 2026-09-27 | 阶段 1–7 完成；新增网络接口兑换（抽取钩子）、修复刷物品漏洞 | 新版 API 锚点见上表；`UnifiedStorageBeforeExtractHandler` 由"未采用"改为"已采用" |
| 2026-09-27 | 加入模组图标 `src/main/resources/beyondemc.png`（256×256），并在 `mods.toml` 里配 `logoFile` | 无 API 影响 |
