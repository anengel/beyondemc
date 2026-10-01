# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的结构。

## [0.3.2] - 2026-10-01

对应 tag `v0.3.2`（指向本节所在的发布准备提交）。本节分两部分：
**Create 集成与「第三方可见化」（改了 `src/`，已实机验收）** 与 **更早一轮的工具修复（只碰 `tools/`）**。
面向用户的发布说明见 `docs/release-notes-v0.3.2.md`；设计取舍见 `docs/plan/CREATE-INTEGRATION.md`。

### 新增 —— Create（机械动力）集成与「第三方可见化」（0.3.2，`src/` 有改动）

- **蓝图大炮能用上物化物品了。** Beyond Dimensions 的「蓝图接口」方块实体
  （`SchematicannonPathWayBlockEntity$NetedSchematicannonItemHandler`）原先只对网络做
  **精确键查询**（`getStackByKey(new ItemStackKey(…))`，底层就是 `HashMap.getOrDefault`），
  而物化条目的键是 `EmcItemKey` —— 两者 `equals` / `hashCode` 都对不上，于是
  `getStackInSlot` 恒为空。更关键的是：Create 判定「这个槽有料吗」的**唯一依据**是
  **模拟抽取 `extractItem(slot, …, true)` 的返回值**（`updateChecklist` 与
  `ItemHelper.extract` 都是先跑一遍 `simulate=true` 估量，够了才真抽），
  模拟说"没料"，真抽取那一遍**永远不会被调用**。
  现由 `NetedSchematicannonItemHandlerMixin` 补上这两处：
  `getStackInSlot` 回填物化条目（供大炮界面显示"已收集"），
  `extractItem(..., simulate=true)` 按报价补报可交付量。
  **真抽取一行未改** —— 它本来就通：走 `storage.extract(ItemStackKey, …)` →
  `UnifiedStorage.extract` → 扣 EMC + 铸造，且返回的键恰好是 `ItemStackKey`，
  能通过 BD 的 `instanceof ItemStackKey` 守卫。
- **第三方管道也能看见物化物品了**（`ItemUnifiedStorageHandlerMixin`）。
  BD 的物品能力桥按 **key 类型 id 分桶**枚举（只读 `ItemStackKey` 桶），而物化条目在
  `beyondemc:stack_type/emc_item` 桶 ⇒ 结构性看不见。现于该桥的**原生可视槽之后**
  追加一段「只读物化区」，原有槽位映射逐位不变（含 BD 那个"容量预留槽"）。
- **⚠️ 收费红线没有放宽。** 两条暴露路径的**真抽取都导回 `UnifiedStorage.extract`**，
  照常扣 EMC；模拟抽取只读存储、只算数量。报价与扣费**共用同一算式**
  （新类 `materialize/MaterializeQuote`），因此不会出现"模拟说 64 个、真取只给 1 个"。
  这条清单来自一次真实教训（0.3.0 的刷物品漏洞就是因为两条路径各自判断、标准不一致）。
- **有序版 `ItemStackTypedHandler` 刻意不动。** 它包的是**机器自己的固定槽位**
  （熔炉输入 / 燃料 / 输出、Create 装置搬运本地存储），走 BD 的 `CommonHandlerMap`，
  与维度网络是不同的能力体系；其字段类型 `StackHandler` 与 `UnifiedStorage`
  **互不相交**（各自 `implements IStackHandler`，无继承关系 —— javac 直接判定
  `instanceof` 不可能成立）。拿不到网络就拿不到 EMC 余额，暴露物化条目等于零扣费送物品。
- **物化条目不再有额外的 tooltip 说明行**：删掉「可兑换：N」与
  「由网络 EMC 物化而来 —— 兑换会扣除 EMC」两行（`EmcItemKeyRender` + 两个语言文件）。
  物化条目在界面上与普通物品完全一致（数量仍由 `renderAmount` 画在图标上）。
- **Create 的可选依赖接线**：`build.gradle` 加 `-PwithCreate`
  （**只在 `runClient` 默认开启**，不影响 `runServer` 自检），
  `neoforge.mods.toml` 声明 `create` 为 `optional` + `AFTER` + `side=BOTH`，
  新增 `beyondemc.create.mixins.json` 与门控插件 `BeyondEmcCreateMixinPlugin`
  （判据用**类路径探测**，不用 `ModList` —— 后者在本项目的 JEI 集成上实测失效过）。
  **不需要** flywheel / ponder / Registrate：它们已内嵌在 create jar 的 `META-INF/jarjar/` 里。
- **新增自检** `diag/CreatePathwaySelfTest`（报价边界 6 例、报价与收费段算式一致 64 组、
  展示栈夹取、空入参安全、桶分离不变式），并在 `MixinTargetCheck` 登记通用桥 3 个目标
  与 Create 侧 4 个可选目标（含 `grabItemsFromAttachedInventories` 的**门槛哨兵**：
  Create 一旦改掉那个方法，我们会看到 FAIL 而不是让功能静默消失）。

> **已知取舍（玩家需知情）**：物化条目对第三方可见后，接入网络的**自动抽取装置**
> （AE2 导入总线、漏斗等）会主动把网络 EMC 换成物品，而这些物品再存回网络时按**回收价**
> 折算（≤ 买入价）⇒ 会造成**静默的 EMC 净损失**。这是"让物化物品真实可用"的必然代价，
> 已与使用者确认接受。观测方法：看 `[BeyondEMC] 接口兑换：` 日志频率与 EMC 余额曲线。
> 实测环境里火药与钻石的**买入价 = 回收价**（火药 192/192），价差为 0，来回不掉 EMC；
> 有价差的物品以各自环境价格为准。
> **成因、量级、观测与潜在处置**的完整分析见 `docs/plan/CREATE-INTEGRATION.md` §5。

> **本轮验证（截至本条目）**：
> - `compileJava` **BUILD SUCCESSFUL**（仅 1 条既有无关的 deprecation 注记）。
> - 无头 `runServer` 自检：**`Create/第三方暴露` 6 项通过**；
>   `Mixin 目标存活性` **核对了 10 个目标全部存在**（较 0.3.1 新增无序桥 3 个），
>   可选集成 5 项（JEI 1 + Create 4）因对应模组未加载而**正确跳过**。
> - **结构门控行为正确**：无头环境无 Create 时，日志显示
>   `Create Mixin 配置已加载` + `门控判定：create 在类路径上=false`，且**不报错**、不应用 Mixin。
> - **离线字节码核对**（`javap`，对真实 jar）：`NetedSchematicannonItemHandler` 的字段
>   `net` / `stacksSnapshot` 与 `getSlots` / `getStackInSlot(int)` / `extractItem(int,int,boolean)`
>   全部存在；`ItemUnifiedStorageHandler` 的 `private final UnifiedStorage storage` 精确匹配；
>   Create 侧 `SchematicannonBlockEntity#grabItemsFromAttachedInventories`、
>   `MovementBehaviour#REGISTRY` 均在。

> **实机验收（2026-10-01，真实客户端 + Create 6.0.10 + 真实 EMC 表）—— 已通过**：
> - 门控与注入：`Create Mixin 门控判定：create 在类路径上=true`；
>   `Create Mixin 已应用到 …NetedSchematicannonItemHandler` ⇒ **Mixin 在真实环境成功注入**，
>   全程**无 `MixinApplyError` / 无注入失败**（`run/logs/latest.log:29,740`）。
> - 蓝图接口取料**确实走收费链**：`接口兑换：minecraft:gunpowder ×1 → 扣除 192 EMC（单价 192）`
>   （`run/logs/latest.log:1115`；该火药当时无真实库存，故只能来自物化路径）。
> - 存档层面双线并存：`BDNet_0.dat` 有 **14 条** `beyondemc:stack_type/emc_item`（数量互不相同）
>   与 `beyonddimensions:stack_type/item` 的**真实库存**（样本：装 8 把附魔合金斧的潜影盒）——
>   分桶共存、不塌缩、不互相顶替。
> - 全程日志**无本模组相关异常**（剩余 ERROR 均为 BD 自身贴图/方块状态告警与离线 SSL 告警）。
> - 仍未覆盖：多人并发、在 AE2/RS 等具体物流模组上的取用实跑、10 万件量级性能。

> **发布产物**：`beyondemc-1.21.1-neoforge-0.3.2.jar`（190,995 字节）；
> 备份 `backups/beyondemc-0.3.2.{bundle,source.zip,jar}`；真克隆演练通过（**5 个 tag 全部 peel 正确**、115 跟踪文件、工作树干净）。

### 修正 —— 工具（只碰 `tools/`）

- **`tools/install-to-mods.ps1` 会把改了名的同一个模组装成两份。**
  整合包实例会给 jar 加中文名前缀，例如 `[等价交换重制版] ProjectE-1.21.1-PE1.1.0.jar`
  与 `[超越维度] beyonddimensions-1.21.1-neoforge-0.7.30.jar`。它们与上游
  `projecte-1.21.1-1.1.0.jar`、`beyonddimensions-1.21.1-0.7.30.jar` **内容完全相同
  （SHA-256 一致）**，只是文件名不同，提供的 `modId` 都是 `projecte` / `beyonddimensions`。
  原逻辑只按文件名删旧的 `beyondemc-*.jar`，于是会把上游那份也拷进去。
  现改为**按 `modId` 去重**：读取每个 jar 的 `META-INF/neoforge.mods.toml`，只取
  `[[mods]]` 段的 `modId`（不取 `[[dependencies.*]]` 里的，否则 `projectexpansion`
  声明的 `projecte` 依赖会被误判为重复）；并显式检测"同一 `modId` 出现 2 份"并以非 0 退出码报错。
  *后果说明（已按 FML 源码核实，非实机启动验证）*：内容相同的两份 jar，其
  `Automatic-Module-Name` 也相同，FML 的 `UniqueModListBuilder.selectNewestModInfo`
  只会**按版本挑一份并打一条 INFO**，不会拒绝启动；真正的
  `fml.modloadingissue.duplicate_mod` 报错要"两个**不同模块**声明同一 `modId`"才触发。
  但留副本仍应避免：同名不同版本时被挑中的是版本号大的那份，而文件名完全不同，
  玩家看不出实际加载了哪一份。
- **`tools/install-to-mods.ps1` 的候选探测会漏掉真正的目标实例。**
  原逻辑只认版本目录名同时含 `1.21.1` 与 `NeoForge`，而整合包实例常改名
  （如 `你好，新蒸程！V1.7.5正式版`）→ 匹配不到 → 因"只找到 1 个候选"而**静默装进另一个空实例**。
  现改为"名字匹配 **或** 该 `mods` 目录内已有本模组/前置"取并集，并列出命中的 `modId` 与文件名。
- **修 `-LiteralPath`**：`Test-Path` 默认把文件名里的 `[` `]` 当通配符字符类，对
  `[等价交换重制版] ProjectE-…jar` 会返回"不存在"，导致 `modId` 读不到 —— 既不去重、
  结果核对也漏看，最后**谎报"无重复"**。这是本组改动里唯一真正会导致误判的缺陷。
- **修 `-ListOnly` 的隐性越权**：原写法在"恰好 1 个候选"时不会退出，而是继续往下
  走到复制那一步；`-ListOnly -ModsPath` 组合也会被忽略。现对任何候选数量都只报不改。

### 新增 —— 工具

- `tools/install-to-mods.ps1 -ListOnly`：只列出候选 `mods` 目录、不改任何文件
  （含 `-ListOnly -ModsPath` 组合）。
- `tools/test-install-to-mods.ps1`：安装器冒烟测试。在仓库内的临时沙盒里复现
  "中文前缀异名前置 + 旧版本本模组"的场景，断言安装后每个 `modId` 恰好 1 份，
  全程不碰真实游戏实例。把上述"静默谎报成功"固化成可复跑的回归测试。
- `tools/jarids.py`：离线查看任意 jar 的真实 `modId`，便于交叉核对。

## [0.3.1] - 2026-10-01

Minecraft 1.21.1 · NeoForge 21.1.249+ · 需要 Beyond Dimensions 0.7.30+ 与 ProjectE 1.1.0+ · JEI 19+ 可选。

只含自检与文档，**不改任何游玩行为**。

### 新增

- **自检覆盖 BD 的三条抽取入口**（关闭 Spike S-0.3-7）。0.3.0 的自检只验了按*键*抽取，
  没有覆盖按*槽位*与按*标签*抽取 —— 而"按标签抽"正是物化条目最隐蔽的零扣费路径
  （`EmcItemKey.getTags()` 委托物品标签 ⇒ 物化条目会进 BD 的 `tag2stackMap`）。
  新增 4 项断言，物化组自检 **22 → 26 项**：
  - `按槽位抽取`：外部抽取被护栏拒绝（0 交付 / 0 扣费 / 条目不减）
  - `按槽位抽取（正控）`：`MaterializingGuard` 激活时成功抽到 2（10 → 8），
    **证明该入口确实命中物化条目**，排除"拦下了但其实没抽到"的假绿
  - `按标签抽取`：外部抽取被护栏拒绝
  - `按标签抽取（正控）`：`MaterializingGuard` 激活时成功抽到 3（10 → 7），证明标签确实解析到物化条目
  静态依据：`UnifiedStorage.extract(int slot,…)` 与 `extract(TagKey,…)` 都委托到
  `extract(IStackKey,…)`，钩子在同一条链路上。

### 修正

- **README 的 NeoForge 版本说明会导致实机启动失败**：原文三处写 `21.1.234+`，
  但配套的 JEI `19.44.0.401` 自身要求 `NeoForge >= 21.1.238`，照 README 装 21.1.234 会在
  模组加载阶段直接崩并报 `Mod jei requires neoforge 21.1.238 or above`。
  已按实测环境改为 `21.1.249`，并写明最低 `21.1.238` 及其原因。
- `docs/plan/ROADMAP-0.3.0.md` §9.4b：补「0.3.x 改崩 → 退回 `v0.3.0`」的回退步骤。
- `docs/plan/RELEASE.md` §1 自检计数更新为 **54 项**（原 50 项）。

### 文档

- **新增 `docs/release-notes-v0.3.1.md`** —— 可直接粘贴到 GitHub Release 的发布说明。
- **新增 `docs/testing/phase8-materialize-report.md`** —— 物化专项实测报告。含两类不来自自检的证据：
  ①**直接解析存档**：网络里确实有 14 条 `beyondemc:stack_type/emc_item` 真实条目，
  EMC `3066234`，其中 8 项满足精确恒等式 `数量 = floor(EMC ÷ 单价)`、另 6 项落在对应价格区间内，
  且 14 条**各自独立未被合并**（从存储层证伪"塌缩"）；
  ②**写→读→再写闭环**：客户端写的 14 条被专用服务器全部读回（0 解码错误），
  服务器自动保存回写后与客户端写的**逐项完全相同**；
  ③**A/B 回退演练**（`git worktree` 检出 `v0.2.0` 读同一份 0.3.1 存档）：
  0.2.0 `Done` 不崩、EMC 与 14 项学习集无损、14 条物化条目被 BD 逐条 WARN 丢弃；
  0.3.1 读同一份存档 **0 条解码错误**。
- `docs/plan/RELEASE.md` §2.2c：把物化专项 14 项逐条标注实测状态（✅ 已实测 / 🟡 部分 / ⬜ 待做），
  并补上可复用的**回退演练步骤**。
- 新增 `tools/nbtdump.py`（最小 NBT 解析器，仅标准库）—— 用来离线核对存档里的条目与数量，
  是"物化口径对不对"最省事的检查手段。

## [0.3.0] - 2026-10-01

Minecraft 1.21.1 · NeoForge 21.1.249+ · 需要 Beyond Dimensions 0.7.30+ 与 ProjectE 1.1.0+ · JEI 19+ 可选。

### 新增

- **物化：让"已学习但无库存"的物品真实存在并存储于维度网络中**。
  0.2 里这些物品只是客户端现场算出来的一行显示（服务端不认识、不进存档、第三方物流看不到）；
  0.3.0 把它们变成**服务端按权威数据物化、写进网络存储的真实条目**。
  - **数量口径**：逐件 `floor(网络EMC ÷ 购买价)`，**各物品彼此独立、不共享预算**。
    例：`EMC=100`、钻石单价 `50`、泥土单价 `1` ⇒ 钻石 2 个 + 泥土 100 个。
  - **取出即连带收缩**：取走 1 颗钻石 ⇒ `EMC=70` ⇒ 钻石 1 个 + 泥土 70 个。
  - **已有真实库存的物品不物化**，避免与 BD 原生库存行重复。
  - 条目会随网络**存档 / 跨维度 / 多人同步**，走 BD 原生的持久化与 delta 广播链路。
  - 落进**独立的 type bucket**（`beyondemc:stack_type/emc_item`），原生的第三方管道看不到它们。
    （**0.3.2 起已主动放宽**：无序物品能力桥追加只读物化区，让管道能取用 —— 但真抽取仍走
    `UnifiedStorage.extract`，照常扣 EMC。见本文件顶部「Create 集成与第三方可见化」一节。）
  - **可丢弃重建**：物化条目是"EMC 池 + 学习集合"的**纯函数派生**，
    物化层损坏不丢任何数据 —— `/beyondemc materialize rebuild` 一键按权威数据重建。
- **命令**：`/beyondemc materialize list | rebuild | clear`
  （`list` 会同时打印"实际内容"与"应然内容"，对不上会标出来）。
- **配置** `[materialize]` 段：`materializeItems`（默认 `true`）、
  `materializeMode`（默认 `STORAGE`）、`maxMaterializedItems`（默认 `512`）。
- **网络合并时按合并后的 EMC 重建物化条目**：合并前先清空两边的物化条目，避免把
  派生数据原样搬到新网络后数量出错。

### 变更

- **界面点击的判据改为"服务端是否真的持有该条目"**（`key instanceof EmcItemKey`），
  取代 0.2 那种"用本地状态推断服务端事实"（`VirtualEntryProvider.isVirtual`）。
  后者曾两次失同步，表现为"条目看得见、点击没反应"；新判据无状态、不可能失同步。
  `isVirtual` 降级为 `materializeItems=false` 时的兜底。
- **服务端物化生效时，客户端不再注入虚拟条目**（由服务端随知识同步包下发权威标志）。
  否则同一个物品会出现两行（物化行 + 客户端注入行）。

### 安全与健壮性

- **修复：物化条目可被"零扣费"抽走的漏洞。**
  物化条目的标签会登记进 BD 的标签索引，因此除了界面点击，它还能被
  **按标签抽取** / **按槽位抽取** 命中；而原来的抽取钩子只认 `ItemStackKey`、
  对其余键一律放行 —— 那是一条**不扣任何 EMC 就能把实体物品拿走**的通道。
  现在物化条目一律**改道收费**：有 EMC 价值就扣费；
  **余额不足或无法定价则拒绝本次抽取（什么都不交付，不交半份）**。
  物化层维护自己的派生数据时用 `MaterializingGuard` 区分，避免自锁。
- 模拟抽取（外部模组的能力查询）**仍然绝不扣费** —— 0.2 立下的红线未被破坏。
- 物化写入是**差量**的（只调整变化的条目），不会每次刷新都刷出上百条 delta 广播。
- 读档期间、以及 EMC 价格表就绪之前，**一律不动存储**（避免把条目误清空）。

### 自检

- 新增 `MaterializeSelfTest`，**22 项**断言，覆盖：
  类型注册与单例；**`EmcItemKey.equals/hashCode` 不塌缩**（钻石 ≠ 铁，且不会被 `HashMap` 合并）；
  NBT / 网络 / `IStackKey.CODEC` 三条往返；逐件取整口径（含 `E=100→钻石2+泥土100`、
  `E=70→钻石1+泥土70` 两个样例）；排除规则与确定性排序；幂等与溢出；
  折算钩子放行；存储写回（真实存在 / 幂等 / 差量收缩 / 清空 / 配置默认）；
  读档保护；**零扣费护栏**（外部抽取被拒 + 守卫激活时放行 + `ItemStackKey` 通路未破坏）。
- 累计 **50 项**（专用服务器）。

### 备注

- **回退到 0.2 是安全的**：物化条目用的是本模组自定义的资源类型，0.2 不认识它，
  读档时 BD 会**静默丢弃**这些条目 —— 不崩服、不超发，EMC 池与学习集合不受影响。
- 三层熔断：`materializeItems=false`（真清空条目）→ `materializeMode=OFF`（同上）→ 移除本模组。
- 详细设计与回退策略见 `docs/plan/ROADMAP-0.3.0.md`。

## [0.2.0] - 2026-09-27

Minecraft 1.21.1 · NeoForge 21.1.234+ · 需要 Beyond Dimensions 0.7.30+ 与 ProjectE 1.1.0+ · JEI 19+ 可选。

### 变更

- **点击兑换改为吸附到鼠标**（原版拾取语义），不再直接落进背包：
  - **左键** = 一组吸附到鼠标；**右键** = 一半吸附到鼠标；**Shift+左键** = 直接放进背包
  - 鼠标上已有同类物品时合并到堆叠上限；拿着别的物品时拒绝并提示（不交换、不丢弃）
  - 不变式：**扣费数量 == 吸附到鼠标的数量**，容量在扣费之前就算定（不会先扣钱再发现放不下）

### 新增

- **JEI 配方填充自动用 EMC 兑换**：在维度网络的合成菜单里点 JEI 的 `+`，
  网络没有库存但已学会的材料会被自动兑换并填进合成栏。
  - 按购买价 × 数量扣费，与手动兑换同价；余额不足时只填能付得起的部分
  - **JEI 是可选依赖**：不装 JEI 时一切照常，只是没有该集成
  - 实现上复用了 0.1.0 就装好的服务端抽取钩子，因此服务端**零新增扣费逻辑**

### 自检

- 新增 10 项自动检查（吸附容量规则 5 项 + JEI 可用量注入 5 项），累计 **38 项**
- 其中「吸附容量·纯函数性」与「不改入参」两项分别守住了
  "扣费数量 == 交付数量" 与 "不污染 BD 客户端存储镜像" 这两条不变式

## [0.1.0] - 2026-09-27

首个可玩版本。Minecraft 1.21.1 · NeoForge 21.1.234+ · 需要 Beyond Dimensions 0.7.30+ 与 ProjectE 1.1.0+。

### 新增

- **存入折算**：把有 EMC 价值的物品放进维度网络时，自动按**回收价**（`getSellValue`）折算成网络 EMC。
  无 EMC 价值的物品按 BD 原逻辑入库。
- **网络级共享学习集合**：存入时自动并入网络自己的"已学习"集合（与玩家个人 ProjectE 知识相互独立），
  并随网络存档持久化。
- **界面直接兑换**：维度网络界面里显示所有已学习物品。有库存显示库存数（BD 原生行）；
  无库存则显示 `floor(网络EMC ÷ 购买价)`，点击即可扣除 EMC 换出物品。
  - 左键 = 1 个；Shift+左键 = 一组（原版最大堆叠数）
  - 背包放不下时按能放下的数量裁剪，**只扣对应部分的 EMC**
- **存入新物品即时可见**：增量推送，无需关闭界面重开。
- **网络接口兑换（自动化）**：维度网络接口配置过滤器后，即使网络无该物品库存，
  也能产出该物品并扣网络 EMC。空过滤器不抽取、每槽每周期最多一整堆，
  且**模拟抽取绝不扣费**；可用 `allowInterfaceWithdraw=false` 关闭。
- **读档安全**：两道防线（EMC 表就绪门禁 + 显式反序列化守卫）保证读档不会重写存档。
- **命令**：`/beyondemc ping | selftest | emc query/add/spend | knowledge list/clear | exchange`
- **配置**：8 项，含物品黑/白名单（支持标签写法）、组件物品折算开关、成功提示模式等。

### 安全与健壮性

- **兑换的身份一致性（修复刷物品漏洞）**：兑换时"用于校验与定价的身份"必须与"实际铸造出的对象"
  完全一致。修复前，请求带组件的物品（附魔/改名/耐久）会被归一化成无组件身份来定价，
  却按请求对象铸造 —— 于是可以**按普通物品的价格买到附魔物品**，凭空产生价值。
  网络接口与 GUI 两条路径都受影响（后者因 `template` 客户端可控而更严重）。
  现在统一经 `CanonicalExchange` 归一化，并复用存入侧同一套策略函数。

- 折算使用**回收价**、兑换使用**购买价**。因 ProjectE 的 `covalenceLoss ∈ [0.1, 1.0]`
  恒有 `购买价 ≥ 回收价`，**不存在"低买高卖"的净收益路径**。
  （注意：`covalenceLoss` 默认为 `1.0`，即买卖同价，此时"存入→取出"是中性循环而非亏损。）
- 所有价格/余额/权限/背包空间一律**服务端重算**，客户端传入的只有意图。
- 长整型溢出使用饱和运算；单键容量默认为 `Long.MAX_VALUE`。
- 带非默认数据组件的物品（附魔/耐久/自定义名/容器内容物）**默认不折算**，避免不可逆地销毁组件信息。

### 备注

- 移除本模组会让维度网络里的 EMC 归零（BD 对未注册的资源类型会静默丢弃条目）。
  卸载前请先用 `/beyondemc exchange` 把 EMC 换成物品。详见 `README.md`。
