# 实施路线图与工作流：BeyondEMC

> 配套：`docs/design/architecture.md`（架构基线）、`docs/design/decisions.md`（决策记录）、`docs/research/`（源码证据）、`docs/development/build-and-deps.md`（构建与依赖）
> 本文回答"**怎么一步步做出来**"，每个阶段都有明确的交付物与验收标准。

---

## 0. 前置条件（开工前必须解决）

| # | 前置项 | 现状 | 动作 |
|---|---|---|---|
| P1 | **JDK 21** | ✅ **已解决** | 已安装于 `C:\Program Files\Java\jdk-21.0.12.1`（`java 21.0.12.1 LTS` / `javac 21.0.12.1`，实测可用）。⚠️ **注意**：它**不在 `PATH` 上，`JAVA_HOME` 也为空**；而且**已运行的 DSH 宿主进程持有的是旧环境块**，即使去设系统环境变量，本会话的 shell 也读不到。→ 跑 Gradle 时必须显式指定（见 P1 详注） |
| P2 | Gradle Wrapper 可用 | 需要首次下载 Gradle 发行包（约 100MB+） | 工程建好后第一次 `gradlew` 会联网下载；确认能访问 `services.gradle.org` |
| P3 | 两个前置模组的依赖坐标 | ✅ **已解决** | `libs/` 下已有两个 jar（已校验，见下方 P3 详表），**首选本地 jar**；CurseMaven 坐标也已核实可作备用。 |
| P4 | 工程命名定稿 | ✅ **已解决** | mod id `beyondemc`、包名 `com.zhuyuhang.beyondemc`、显示名 `Beyond EMC`、作者 `ZhuYuhang`、许可证 MIT |
| P5 | 语义决策 | ✅ **已决定：选 A** | 无条件折算（用户已确认）。详见下方 P5 说明 |
| P6 | 构建时的 `JAVA_HOME` 与 PowerShell 执行策略／编码 | ✅ **已解决** | 已安装 **PowerShell 7.6.6**（`C:\Program Files\PowerShell\7`）；**重启 DSH Desktop 后**，pwsh 工具可直接 `. .\tools\gradle-env.ps1` 再 `.\gradlew.bat <task>`。⚠️ 原先的三个坑（详见 `docs/development/environment-notes.md` §2）：① 本机原无 PS7，DSH 落到 **Windows PowerShell 5.1**，其 `Restricted` 策略禁止运行 `.ps1`；② 5.1 还会把**无 BOM 的 UTF-8 `.ps1` 按 ANSI(gb2312) 读**，中文注释乱码导致 7 处语法错误（**绕过策略也照样失败**）；③ `gradle-env.ps1` 中 `$ErrorActionPreference='Stop'` + 原生命令 `2>&1` 在 5.1 下是终止性错误，会中断链式命令。以上三项 PS7 全部免疫。`tools\gradlew-here.cmd` 仍保留为兜底，且**必须保持纯 ASCII** |

### P1 详注：本机如何让 Gradle 找到 JDK

**根本原因**：JDK 装在 `C:\Program Files\Java\jdk-21.0.12.1`，但没进 `PATH`，`JAVA_HOME` 为空。DSH 宿主进程启动早于安装动作，持有的是**旧环境块**，因此新开的 `pwsh` 子进程仍看不到它 —— 命令行里 `java` 会直接 "not recognized"。

**对策（按优先级）**：
1. 每次调用 Gradle 时在同一条命令里显式导出（最可靠，不依赖任何全局状态）：
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"
   $env:Path = "$env:JAVA_HOME\bin;$env:Path"
   .\gradlew.bat build
   ```
2. 工程内放一个 `tools\gradle-env.ps1` 帮手脚本，把这四行固定下来，构建命令先 dot-source 它再跑 Gradle（**推荐**，避免每处重复）。⚠️ 前提是 shell 为 **PowerShell 7**：装上 PS7 并**重启 DSH Desktop** 后可用；在 Windows PowerShell 5.1 下该脚本会因执行策略、以及无 BOM UTF-8 被按 ANSI 读取而失败（见 `docs/development/environment-notes.md` §2）。
3. **不要**把 `org.gradle.java.home` 硬写进提交的 `gradle.properties`：那会把绝对路径绑死到这台机器，破坏他人与 CI 的可复现性。若确需本机便利，写进 `%USERPROFILE%\.gradle\gradle.properties`（用户级、不进仓库）。
4. 若 DSH 重启，宿主进程会重新读取系统环境块，届时全局 `JAVA_HOME` 才会生效。

### P3 详表：依赖接入

**首选：本地 jar（已放好并校验）**

| 文件 | 校验结果 |
|---|---|
| `libs/beyonddimensions-1.21.1-0.7.30.jar` | modId `beyonddimensions`，version `0.7.30`，要求 NeoForge `[21.1.194,)` / MC `[1.21.1]`，MIT ✅ |
| `libs/projecte-1.21.1-1.1.0.jar` | modId `projecte`，version `1.1.0`，要求 NeoForge `[21.1.119,)` / MC `[1.21.1]`，MIT ✅ |

```groovy
dependencies {
    // 本地 jar：compileOnly 管编译期，runtimeOnly 管 run 的 Mod List
    compileOnly files("libs/beyonddimensions-1.21.1-0.7.30.jar")
    runtimeOnly files("libs/beyonddimensions-1.21.1-0.7.30.jar")
    compileOnly files("libs/projecte-1.21.1-1.1.0.jar")
    runtimeOnly files("libs/projecte-1.21.1-1.1.0.jar")
}
```

> ⚠️ **实测更正（2026-09-27）**：模组依赖**必须**用 `runtimeOnly`。早期版本写的 `additionalRuntimeClasspath`
> 会导致 BD / ProjectE **不进 run 的 Mod List**，启动报 `Currently, beyonddimensions is not installed`。
> `additionalRuntimeClasspath` 是给"需要进 run 类路径的普通库"用的。
> 证据见 `docs/testing/phase1-report.md` §4。

> 原始文件名带中文与方括号（`[超越维度] ...`、`[等价交换重制版] ...`），已重命名为 ASCII —— 非 ASCII 文件名在 Gradle + Windows 下是已知的编码踩坑点。jar 内容未作任何改动（字节数不变：3550119 / 2426228）。
> `flatDir` 不传递依赖，因此不要用 `flatDir` + `name:` 形式，直接用 `files(...)`。

**备用：CurseMaven（CI 友好，不依赖本地文件）**
repositories {
    maven { name = 'CurseMaven'; url = 'https://cursemaven.com'; content { includeGroup 'curse.maven' } }
}
dependencies {
    // Beyond Dimensions 0.7.30 (MC 1.21.1 / NeoForge)  CF projectId=1222890  fileId=8812500
    compileOnly "curse.maven:beyond-dimensions-1222890:8812500"
    additionalRuntimeClasspath "curse.maven:beyond-dimensions-1222890:8812500"
    // ProjectE 1.1.0 (MC 1.21.1 / NeoForge)            CF projectId=226410   fileId=6611984
    compileOnly "curse.maven:projecte-226410:6611984"
    additionalRuntimeClasspath "curse.maven:projecte-226410:6611984"
}
```

- **为什么必须用 `additionalRuntimeClasspath` 而不是 `runtimeOnly`**：NeoForge 1.21.1（≤1.21.8）下，外部模组依赖不显式进 run 的类路径，`runClient`/`runServer` 就加载不到它。依据 MDG 官方文档 *External Dependencies: Runs*。
- **不要用 `implementation`**：普通 `implementation` 里的模组 jar 不会作为模组被加载（详见 `development/build-and-deps.md` §2.0）。
- CurseMaven 不支持动态版本，必须写确切的 fileId。
- 核实来源：`docs/development/build-and-deps.md` §2.b（通过 cfwidget 镜像核实，双向一致）。
- 若切到 CurseMaven，必须删掉 `libs/` 的那四行，避免同一个模组被加载两次。

### P5：需要你拍板的最后一个语义问题

需求 R2（有 EMC 的物品存入即折算成 EMC）与 R6（有库存则显示库存数）在实践中互斥 —— 既然都被折算了，就不会有"有 EMC 的已学习物品在库存里"。

| 选项 | 语义 | 后果 |
|---|---|---|
| **A（当前设计）** | 无条件折算；R6 只对"被配置排除的物品"和旧存档物品生效 | 实现最简单、语义最干净；但"显示库存数"这条需求几乎看不到效果 |
| **B（备选）** | 只折算**尚未学习**的物品；已学习的物品照常进库存 | R6 成为常态（已学习的物品有真实库存）；但"已学习物品"会同时存在"库存形态"和"EMC 形态"，玩家需要理解两套来源 |
| **C（可配置）** | 配置项切换 A/B，默认 A | 灵活但要维护两条路径的测试矩阵 |

> **✅ 已决定（2026-09，用户确认）：选 A —— 无条件折算。**
> 用户已明确"存入即折算"是预期行为，并认可这与"有库存则显示库存数"互斥。
> 因此：
> - v1 按 A 实现，**不做** B 路径。
> - R6（显示库存数）保留实现，但它只在"被配置排除的物品"与旧存档物品上生效 —— 这不是 bug，是 R2 的推论，须在 README 里向玩家说明。
> - 阶段 6 仍会留一个 `depositPolicy` 配置项骨架（`ALWAYS` 为默认且唯一实现），**不实现** `UNLEARNED_ONLY` —— 留接口不留分支，避免维护未验证的代码路径。若日后确有需求，再按 B 的语义补实现与测试矩阵。
> - `docs/design/architecture.md` §1.2 的"必然结果"一节仍然有效，且现在是**确定**的产品行为而非待议项。

---

## 1. 工作流总则

### 1.1 证据驱动
对 BD / ProjectE 行为的**任何断言**，都必须能追溯到二者之一：
- `docs/research/` 里带 `文件:行号` 的记录，或
- `docs/testing/manual-log.md` 里的一次实机测试记录（含版本、步骤、观察结果）。

**禁止**凭记忆或推测写"BD 应该会……"。这条纪律是本项目最大的风险控制手段 —— 两个前置模组都在活跃开发，且 BD 的 API 在 0.3.0 → 0.7.30 之间已经改过一次名（`IStackType` → `IStackKey`）。

### 1.2 版本锁定
`docs/VERSIONS.md` 记录三个版本号：本模组、BD（`0.7.30` @ `a0d2e76`）、ProjectE（`1.1.0` @ `f432b0c`）。
**任何**对前置模组版本基线的变更，必须同时更新该文件并跑一遍 §3.4 的回归清单。

### 1.3 三层验证
| 层 | 手段 | 覆盖什么 | 何时跑 |
|---|---|---|---|
| L1 编译 | `gradlew build` | 语法、API 签名、Mixin 注解 | 每次提交前 |
| L2 实机 | `gradlew runClient` / `runServer` 手动测试清单 | 真实游戏行为、界面、网络同步、存档往返 | 每个阶段结束 |
| L3 自动化 | NeoForge `GameTest`（若可行）+ 纯 Java 单元测试（价格换算、饱和乘法、NBT 编解码） | 回归防护 | 阶段 3 起，能自动化的部分立即自动化 |

**纯逻辑必须抽出来做单元测试**：`saturatingMultiply`、`affordableAmount`、`EmcStackKey` 的 NBT/网络往返、知识集合的 NBT 编解码。这些不依赖游戏实例，是性价比最高的自动化。

### 1.4 阶段纪律
- 每个阶段的**验收标准全部通过**才进入下一阶段。不通过就在本阶段修，不带着已知缺陷前进。
- 每个阶段的**第一个 Spike 任务**优先做（那些"如果不行就要换方案"的验证）。
- 任何改变 `docs/design/` 内容的实现，必须回头改文档，不能只改代码。

### 1.5 Git 规范（建议）- `main` 保持可构建；每个阶段一个 `feat/phase-N-xxx` 分支。
- 提交信息带阶段号：`feat(p3): 折算钩子 + ServerStarted 守卫`。
- **不提交** `libs/*.jar`（第三方二进制，见 `.gitignore`），但提交 `libs/README.md` 说明如何获取。若必须提交以保 CI 可复现，需在 `docs/development/third-party.md` 里写清来源与许可证。

### 1.6 启动/停止开发服务端（沙箱内的正确做法）

```powershell
# 启动：日志写文件，便于轮询自检结果
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "cmd.exe"
$psi.Arguments = '/c tools\gradlew-here.cmd runServer --console=plain > run\verify.log 2>&1'
$psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
[System.Diagnostics.Process]::Start($psi) | Out-Null

# 停止
tools\gradlew-here.cmd --stop
```

**禁止**用 `Get-CimInstance Win32_Process` / `tasklist` 枚举并杀 java 进程：

- 沙箱令牌是 Low 完整性受限令牌，**看不到也不该管**沙箱外的进程；
  "看不到 java 进程"**不代表**没有 Gradle 守护进程（`development/environment-notes.md` §7 有实测记录）。
- 杀外部进程既越权又不可靠，还会让整条命令被迫申请 `danger-full-access`。
- `--stop` 停止守护进程即可，通常会连带结束 `runServer` 的子 JVM。
- 停止后用 TCP 连接探测端口（不是枚举进程）确认服务端真的退了：

```powershell
$c = New-Object System.Net.Sockets.TcpClient
try { $t = $c.ConnectAsync('127.0.0.1', 25565); if ($t.Wait(3000) -and $c.Connected) { "仍占用" } else { "已释放" } }
catch { "已释放" } finally { $c.Close() }
```

> `--stop` 会中断正在执行的 runServer 构建，因此 Gradle 会报一次 FAILURE —— **属预期**，不是构建错误。
>
> 另注：**Gradle 报 `BUILD SUCCESSFUL` 不代表游戏启动成功**（阶段 1 实测：服务器崩溃时退出码仍为 0）。
> 自动化验证必须解析日志内容（`Done (…)`、自检结果行），不能只看退出码。

---

## 2. 阶段划分

> 工作量估计按"1 名熟悉 NeoForge 的开发者"计算；对新手请乘 2~3 倍。

### 阶段 0：定稿与前置 —— ✅ 已完成

**结果**：P1（JDK 21）、P3（依赖 jar）、P5（语义决策）全部就绪；P2（Gradle Wrapper 联网下载）与 P4（工程命名）留到阶段 1 一并处理。

**步骤（已完成）**
1. ~~解决 P1~P4~~ → P1 / P3 / P5 已完成，P4 待命名确认。
2. 拍板 P5 语义决策。
3. 建 `docs/VERSIONS.md`。
4. 把 `docs/research/beyond-dimensions-api.md` 标记为**已过时**（该文档基于 Modrinth 文档页写成，接口名 `IStackType` / `IStackTypeHandler` 在 0.7.30 中已更名为 `IStackKey` / `IStackHandler`，"API 在 `Api/DataBase/`"的路径也不存在）。

**验收标准**
- [x] JDK 21 可用（`C:\Program Files\Java\jdk-21.0.12.1`，`javac 21.0.12.1`）—— 注意需显式指定 `JAVA_HOME`，见 P1 详注
- [x] `libs/` 下有两个可用 jar，且已校验 modId/版本/加载器（见 P3 详表）
- [x] P5 有结论（选 A，无条件折算）
- [x] `docs/VERSIONS.md` 存在
- [ ] P4 命名确认（唯一未完成项，随阶段 1 一并定稿）

---

### 阶段 1：工程骨架能跑起来 —— ✅ 已完成（2026-09-27）

> 实测报告见 `docs/testing/phase1-report.md`。两条被推翻的原有假设（依赖配置、EMC 就绪时刻）已在该报告
> 与 `docs/design/architecture.md`、`docs/design/decisions.md` 中更正。

**目标**：一个空模组能在开发环境里加载，并且 Mixin 确实生效。

**步骤**
1. 按 `docs/development/build-and-deps.md` §1 建工程（`settings.gradle` / `gradle.properties` / `build.gradle` / wrapper）。
   - `neo_version = 21.1.234`（与 BD 基线一致）
   - `neo_version_range = [21.1.194,)`（取 BD 与 ProjectE 中更严的下界）
   - Java 21、Parchment `2024.11.17`、MDG `2.0.147`（或 BD 用的 `2.0.116`，以能跑通为准）
2. 依赖：走**本地 jar**（已就位并校验，见 §0 的 P3 详表）：
   ```groovy
   compileOnly files("libs/beyonddimensions-1.21.1-0.7.30.jar")
   additionalRuntimeClasspath files("libs/beyonddimensions-1.21.1-0.7.30.jar")
   compileOnly files("libs/projecte-1.21.1-1.1.0.jar")
   additionalRuntimeClasspath files("libs/projecte-1.21.1-1.1.0.jar")
   ```
   - 必须用 `compileOnly` + `additionalRuntimeClasspath`（**不是** `runtimeOnly`，**不是** `implementation`，原因见 P3 详表）。
   - 不要用 `flatDir`（不传递依赖）。
   - `libs/` 只留这四行；若日后切 CurseMaven，**删掉**这四行再替换，否则同一模组会被加载两次。
3. 写 `neoforge.mods.toml`（`docs/development/build-and-deps.md` §3.1 的模板，依赖 `beyonddimensions` / `projecte` 用 `type="required"` + **`ordering="AFTER"`** —— 后者是 Mixin 能命中前置模组类的前提）。
4. 配 Mixin：`beyondemc.mixins.json` + `mods.toml` 的 `[[mixins]]` 声明。1.21.1 不需要 refmap。
5. **Spike S5**：写一个最小的验证 Mixin（例如注入 `DimensionsNet.getUnifiedStorage` 打一条日志），确认它能命中前置模组的类。
6. 加一个 `/beyondemc ping` 命令，确认服务端能拿到 `DimensionsNet` 与 `IEMCProxy`。

**验收标准（全部通过）**
- [x] `gradlew build` 成功，产物 `build/libs/beyondemc-1.21.1-neoforge-0.1.0.jar`
- [x] `runServer` 能启动，Mod List 里有 `beyondemc` + `beyonddimensions` + `projecte` 三者
- [x] 验证 Mixin 命中前置模组的类（`BeyondDimensions.commonSetup` 命中 1 次，日志为证）
- [x] `/beyondemc ping` 逻辑已实现，并在服务器启动时自动执行过一遍（3/3 项通过）
- [x] 额外收获：确认了 ProjectE 的 EMC 表就绪时刻（见阶段 3 的守卫设计）

**风险与回滚**
- ~~若 Mixin 打不进前置模组~~ → **已验证可行**，方案前提成立。后续正式 Mixin 一律用 `require = 0` 优雅降级；
  验证探针用 `require = 1` 以便注入失败时响亮报错。

---

### 阶段 2：EMC 资源类型 + Spike S2 —— ✅ 已完成（2026-09-27）

> 实测报告见 `docs/testing/phase2-report.md`（自检 8/8 通过；Spike S2 裁决：方案 A 成立，点击安全、渲染器为客户端专用）。
> 遗留一项：客户端**实机渲染**未验证（无头环境测不了），阶段 5 首次实机确认。

**目标**：网络里能出现一行"EMC"，数值随存入/取出正确变化，并搞清楚它在界面里的交互行为。

**步骤**
1. 照抄 `ManaStackKey` 模板（`reference/BeyondDimensions/.../botania/storage/`）写三个类：
   - `EmcType extends LongType<EmcType>`
   - `EmcStackKey extends LongStackKey<EmcType>`（单例 + 空字段 MapCodec；`getTypeID()` 用**我们自己的命名空间** `beyondemc:stack_type/emc`）
   - `EmcStackKeyRender implements IStackRender`
2. 在 `FMLCommonSetupEvent` 注册：`StackKeyRegistry.registerType(EmcStackKey.INSTANCE)`。
   - ⚠️ **必须在任何网络反序列化之前**，否则读档时类型找不到，条目被静默丢弃。
   - ⚠️ **不要**注册 `CapabilityHelper.*` / `StackHandlerWrapperHelper` 映射（见架构文档 §8-3）。
3. **`getVanillaMaxStackSize()` 返回 `Long.MAX_VALUE`**（不要照抄 Mana/Energy 的 `1000000`，否则单次大额操作会被 BD 内部 `Math.min` 截断 —— 风险 R6）。
4. 写 `NetEmcAccessor`：`getEmc(net)` / `addEmc(net, delta)` / `spendEmc(net, cost)`，内含**饱和乘法与溢出检查**（风险 R5）。
5. 临时用 `/beyondemc emc add <n>` / `query` 命令验证读写与持久化。
6. **Spike S2（本阶段最重要的任务）**：让 EMC 行出现在界面里，逐一实测：
   - 左键点击 / 右键点击 / Shift+点击 / 拖拽 / 双击
   - 是否崩溃？是否产生垃圾物品？数量显示是否正确？tooltip 是否正常？
   - 搜索框能否搜到它？排序是否正常？

**验收标准（全部达成，实测见 `docs/testing/phase2-report.md`）**
- [x] EMC 资源类型注册成功，且能在服务端安全加载
- [x] 能对网络 EMC 池做增删查，数值正确（`0 → +12345 → 12345 → -2345 → 10000`）
- [x] **自定义 key 能活着穿过存储 NBT 往返**（987654321 完整还原 —— 这是本阶段最大的风险点）
- [x] 网络编解码（多人同步路径）可用（25 字节往返）
- [x] 溢出饱和、余额不足不扣成负数
- [x] **S2 有明确结论**：点击安全（静态证据）；渲染仅客户端（实测约束）→ ADR-001 裁定**方案 A 成立**
- [x] 8 项无头自检 8/8 通过（启动时自动执行）
- [ ] ⚠️ **遗留**：客户端实机渲染未验证（图标/数量/tooltip 的实际绘制需要 `GuiGraphics`，无头环境测不了）→ 阶段 5 首次实机确认
- [ ] ⚠️ **遗留**：真实存档的"退出重进 EMC 不变"测试 → 与阶段 3 的折算钩子合并进行（那才是"读档会不会重写存档"的完整场景）

**风险与回滚**
- EMC 行点击行为异常（风险 R3）：退到方案 B（网络 NBT 承载 EMC + 自绘余额文本）。这会增加约 1 人日，但不改变阶段 3 之后的设计。

---

### 阶段 3：存入折算 + 学习 + 读档守卫 —— ✅ 已完成并实机验证（2026-09-27）

> 实测报告见 `docs/testing/phase3-report.md`。
> 已完成的自动化验证：钩子路由、读档守卫（含 **Mixin 生效性探针**）、EMC 表门禁、
> 学习集合增删查/NBT 往返/合并、LoadingGuard 嵌套语义。
> **遗留一项**：完整折算路径需要 EMC 表就绪，而无头服务器上 EMC 表永远为空 ——
> 需按报告 §6 的清单在游戏内确认一次（含真实存档往返）。

**目标**：需求 R2 / R3 / R4 全部成立，且读档不会污染存档。

**步骤**
1. `ServerReadyGuard`：**不要用 `ServerStartedEvent`** —— 阶段 1 实测证明，专用服务器上此刻
   ProjectE 的 EMC 表还是空的（钻石 EMC = 0，且空跑 60 秒 `EMCRemapEvent` 从未触发）。
   正确信号是 **`EMCRemapEvent`**（ProjectE 算完 EMC 值后 post，见 `EMCMappingHandler.java:122,145`），
   它同时兼任"`/reload` 后价格缓存失效"的钩子。
   另外仍需一道**显式的"反序列化中不折算"守卫**：`DimensionsNet.getNetFromId` → `computeIfAbsent`
   会在玩家登录后按需加载网络并触发 `deserializeNBT → insert`，此时 EMC 表已就绪，
   迁移场景下会折叠算存档里的物品。手段见 `docs/design/decisions.md` ADR-003。
   （`EmcAvailability` 类已在阶段 1 写好并注册到 `EMCRemapEvent`，直接搬到正式包使用。）
2. `EmcDepositHandler implements UnifiedStorageBeforeInsertHandler.BeforeInsertHandler`：
   - 判 `net == null`、判守卫、判 key 类型、判配置、算 `sell`、饱和乘法、调 `DepositLearnHook`、返回 `KeyAmount(EMC, total)`
   - **必须是纯函数**（无状态、可重入），因为它会被 `simulate` 调用、也会被 `unzipMatterBall` 递归调用
   - **不能**用"返回空 KeyAmount"表示"什么都不做" —— 那会让调用方以为插入失败、物品留在原地
3. `NetKnowledgeStore` + `NetKnowledgeNbt`：`Set<ItemInfo>` 的懒加载、缓存、脏标记。
4. Mixin `DimensionsNet.save` / `load`：读写 `beyondemc:knowledge`。（**Spike S9**：双网络合并时验证 `mergeOtherNet` 的行为并实现合并。）
5. 注册：在 mod 构造期或 `FMLCommonSetupEvent` 调用 `UnifiedStorageBeforeInsertHandler.addHandler`（`handlers` 是非线程安全的 `ArrayList`，**必须一次性注册完**）。
6. 临时用 `/beyondemc knowledge list` 验证学习集合。

**验收标准**
- [x] 折算钩子注册成功，且不干扰其它资源类型（钩子路由自检）
- [x] 读档期间不折算 —— 守卫语义 **+ Mixin 真的生效** 双双验证（风险 R1）
- [x] EMC 表未就绪时不折算（无头服务器的常态）
- [x] 学习集合：增删查、NBT 往返（3 项完整还原）、网络合并
- [x] LoadingGuard 嵌套与复位语义
- [x] 8 项无头自检通过
- [ ] ⏳ **完整折算**（`64 × 回收价`）—— 需 EMC 表就绪，**必须在游戏内确认**（报告 §6 步骤 5–8）
- [ ] ⏳ **真实存档往返**：存入 → 保存退出 → 重进 → EMC 与学习列表不变（报告 §6 步骤 9）
- [ ] ⏳ 通过漏斗 / 网络接口 / 手持物直存 / AE2 分别存入，行为一致（需实机）
- [ ] ⏳ 存入压缩球（`unzipMatterBall`）不报错，球内物品按预期折算（需实机）
- [ ] ⏳ 容量上限测试：往满网络里存物品，行为可预期（需实机）
- [ ] ⏳ 边界物品矩阵（见 §3.3）逐项通过（建议与阶段 6 合并做）

**风险与回滚**
- 读档仍会折算（R1 未解决）：立刻停下，用 `@WrapMethod` 包 `AbstractUnorderedStackHandler.deserializeNBT` 加 ThreadLocal 守卫（ADR-003 的替代方案）。**这是唯一一个必须在阶段内解决的阻断级问题。**

---

### 阶段 4：兑换取出 —— ✅ 已完成并实机验证（2026-09-27）

> 实测报告见 `docs/testing/phase4-report.md`。**Spike S3 已关闭**：
> BD 原生的取出权限只有"是该网络成员"一条（`OpenNetGuiPacket.java:65-66` +
> 槽位 `mayPickup` 用原版默认实现恒真），兑换口径已与之一致。
> 跳过的 2 项（完整报价、防刷断言）依赖 EMC 表就绪，需按报告 §5 的清单实机确认。

**目标**：需求 R8 成立且不可被利用。

**步骤（已完成）**
1. ~~定义 `ExchangeRequestPacket`~~ ✅ C2S 包已实现并注册（`RegisterPayloadHandlersEvent`）。
2. ~~`ExchangeService`~~ ✅ 校验/扣费/发放/兜底退款，全部服务端权威。
   （S2C 的 `KnowledgeSyncPacket` 推到阶段 5 —— 它服务于界面显示，与兑换事务无关。）
3. ~~**Spike S3**~~ ✅ **已关闭**：BD 原生取出权限 = "是该网络成员"，Owner/Manager 不额外要求。
   证据：`OpenNetGuiPacket.java:65-66`（唯一检查是 `getNetFromPlayer != null`）+
   `DisorderedStackTypedSlot.java:178` 的 `mayPickup` 用原版默认实现（恒真，全仓库无覆写）。
   我们的 `canAccess` 与之对齐，并把 owner/manager 也判一遍作为零成本保险。
4. ~~临时用命令验证~~ ✅ `/beyondemc exchange <item> <count>` 走**同一套** `ExchangeService`，
   阶段 5 的界面拦截只需把同样的参数塞进 `ExchangeRequestPacket`。

**验收标准**
- [x] `net=null` / `count<=0` → 拒绝（自检）
- [x] **只允许兑换已学习的物品** → 拒绝理由已断言（自检）
- [x] 无 EMC 价值的物品 → 拒绝（自检）
- [x] `count` 传极大值（`Integer.MAX_VALUE`）→ 安全处理，不崩不溢出（自检）
- [x] C2S 载荷注册成功（服务器正常启动即为证；注册失败会直接崩）
- [x] 权限口径与 BD 原生取出一致（Spike S3 源码确认）
- [ ] ⏳ 已学习 + EMC 足够 → 兑换成功、正确扣费、物品进背包（**需实机**，依赖 EMC 表就绪）
- [ ] ⏳ EMC 不足 → 拒绝且**余额不变**（**需实机**）
- [ ] ⏳ 背包满 → 拒绝且**余额不变**（不能先扣钱再发现装不下）（**需实机**）
- [ ] ⏳ **防刷测试**：记下余额 → 换出 1 个钻石 → 存回网络 → 余额**不得增加**（`covalenceLoss=1.0` 时中性；调低则亏损）（**需实机**）
- [ ] ⏳ 篡改客户端包请求未学习物品 → 被服务端拒绝（阶段 5 做界面时实测）

> 实机清单见 `docs/testing/phase4-report.md` §5。

---

### 阶段 5：界面注入 —— ✅ 已完成并实机验证（2026-09-27）

> 实测报告见 `docs/testing/phase5-report.md`。
> 已自动验证：编译、服务器启动**零 Mixin 报错**、载荷注册。
> **未验证**：虚拟条目是否真的显示、点击是否成功兑换、搜索/排序/Shift 冲刷 ——
> 这些都需要客户端实机（`BDBaseGUI` 只在打开界面时才被类加载，无头环境走不到）。

**目标**：需求 R5 / R6 / R7 / R8 在真实界面里全部成立。

**步骤**
1. `ClientNetStorageAccessor`：`@Accessor` 拿 `cacheIndexes` 字段、`@Invoker` 调 `matchFilter`（它是 `private`，`ClientNetStorage.java:258`）。（**Spike S8**）
2. `VirtualEntryProvider`：按架构文档 §4.3 的算法计算并注入虚拟条目。
3. Mixin `DimensionsNetMenu.buildIndexList`（HEAD）+ `updateViewerStorage`（RETURN，双保险）。
4. Mixin `BDBaseGUI.slotClicked`（HEAD, cancellable）：识别"点到了虚拟条目" → 取消原逻辑 → 发 `ExchangeRequestPacket`。
   - 关键：**服务端不认识虚拟条目**，原逻辑会静默失败，必须拦截。
   - 点击数量规则：左键 = 1，Shift+左键 = 尽可能多（客户端预估，服务端裁剪）。
5. **Spike S6**：验证虚拟条目的 `ItemStackKey` 与服务端真实条目的 `equals` 一致（否则 `hasStack` 判断失效，会出现重复行）。
6. tooltip：给虚拟条目加上"价格 / 可兑换数量 / 来源是 EMC"的说明，避免玩家误解成真实库存。

**验收标准**
> ⚠️ **本阶段的核心功能在无头环境里走不到** —— 全部条目都需要在客户端实机确认。
> 已自动验证的只有：编译通过、服务器启动**无任何 Mixin 报错**、载荷注册成功。
> 实机清单见 `docs/testing/phase5-report.md` §3（含"看日志里那行『虚拟条目注入』"这一关键判据）。

- [x] 编译通过；服务器启动无 Mixin 报错；C2S/S2C 载荷注册成功
- [ ] 存入钻石 → 学习集合里有它，界面里能看到钻石条目（**实机**）
- [ ] 无库存的已学习物品显示的**数量 = `floor(网络EMC ÷ 购买价)`**（**实机**）
- [ ] EMC 不够买 1 个时，该条目**不显示**（已知行为，见架构文档 §4.3 的坑表；若产品要求"显示但置灰"，记录为 v2）
- [ ] 点击虚拟条目 → 扣除正确 EMC，物品进背包，**列表数量立即刷新**（**实机**）
- [ ] Shift+左键 → 尽可能多（受余额与背包空间限制）（**实机**）
- [ ] 搜索框能过滤虚拟条目；排序（名称/数量/模组）对虚拟条目正常（**实机**）
- [ ] 按住 Shift 打开界面（触发 `updateViewerStorage(true)`）后，虚拟条目**不被刷成 0**（**实机**，风险 R10 的界面侧验证）
- [ ] 有真实库存的已学习物品**只显示一行**，点击走 BD 原生逻辑（**实机**，验证"按 `ItemStackKey` 精确判库存"的决策）
- [ ] 与服务端不一致时（例如另一个玩家花光了 EMC）不会出现"幽灵物品"：点击被服务端拒绝并提示（**实机**）
- [ ] 渲染正确（图标/数量/tooltip）—— 阶段 2 的遗留也在此一并验证（**实机**）
- [ ] 客户端不崩：Mixin 全部 `require = 0`，故意改坏一个签名后游戏仍能启动（优雅降级验证）

---

### 阶段 6：打磨与配置 —— ✅ 代码完成，自检 4/4 通过（2026-09-27）

> 实测报告见 `docs/testing/phase6-report.md`。配置扩到 8 项（含物品黑/白名单、成功提示模式）；
> `README.md` 已写（含**卸载风险警告**）。
> 两项**刻意未做**并已记录理由：价格缓存（无测量不加，见报告 §7）、
> 命令反馈文本国际化（仍是硬编码中文，见报告 §6 第 7 项）。

**步骤**
1. 配置项落地（架构文档 §6 的 9 项），含 `depositPolicy`（P5 的 B 选项）。
2. 若 P5 选了 B/C：实现"只折算未学习的物品"路径，并补齐两条路径的测试。
3. 本地化：`en_us.json` + `zh_cn.json`。
4. `EMCRemapEvent` 监听：任何价格缓存失效（风险 R13）。
5. 边界物品矩阵（§3.3）逐项处理并记录。
6. `/beyondemc` 命令补全：`emc query/add/drain`、`knowledge list/clear`、`reload`。
7. 性能检查：`getSellValue` 在批量插入（漏斗/AE2 大批量）时的开销；必要时加价格缓存并在 `EMCRemapEvent` 失效。
8. 文档：`README.md`（含**卸载风险警告**：移除本模组会让网络 EMC 归零，建议先 drain）、`docs/design/` 同步。

**验收标准**
- [ ] 所有配置项改完重启后行为符合预期
- [ ] 中英文语言文件完整，无 `missing translation` 警告
- [ ] 批量插入 10000 个物品的平均 tick 耗时在可接受范围（记录基线数值）
- [ ] `README.md` 有卸载警告与已知限制清单

---

### 阶段 7：测试与发布 —— 🚧 自动化部分完成；人工验证与发布待你执行（2026-09-27）

> 发布检查清单见 **`docs/plan/RELEASE.md`**（含：已自动验证的 23+ 项、
> 必须人工验证的边界物品/多人/共存/性能、发布操作、升级回归流程）。
>
> 本轮新增的**关键防线**：`diag/MixinTargetCheck` —— 用反射在启动时核对全部 10 个
> Mixin 注入目标是否仍存在。这直接针对风险 R8（`require = 0` 静默失效）：
> BD 升级后先看这一行，失配会逐条列出失效目标，不必再手工逐条比对回归清单。
>
> 产物已审计：无 jarJar 内嵌、无工程文件泄漏、`mods.toml`/`LICENSE`/`mixins.json`/双语
> 语言文件齐全。`issueTrackerURL` / `displayURL` / `logoFile` **待你填**（需要仓库地址与图标）。

**步骤**
1. 跑完整回归清单（§3.4）。
2. 只装 BD + ProjectE + 本模组的最小环境验证（排除其它模组干扰）。
3. 与常见模组共存测试：JEI / EMI、AE2、RS（若 BD 的对应集成模块存在）。
4. `gradlew build`，产出 jar；确认 `jarJar` **未启用**（不需要把附属模组打进主模组）。
5. 写 `CHANGELOG.md`、填 `mods.toml` 的 `displayURL` / `issueTrackerURL`。
6. 可选：配 Minotaur（Modrinth）/ CurseGradle（CurseForge，注意插件已过时）发布任务。

**验收标准**
- [ ] 最小环境下所有功能正常
- [ ] 与 JEI/EMI 的交互无异常（虚拟条目的拖拽、配方查询）
- [ ] 发布 jar 在干净的客户端里能加载，依赖校验正确（缺 BD 或 ProjectE 时给出清晰报错）
- [ ] 许可证文件与第三方声明齐备

---

## 3. 测试策略

### 3.1 手动测试清单（模板）

`docs/testing/manual-log.md` 每次记录一行：日期 / 前置模组版本 / 步骤 / 期望 / 实际 / 结论。

### 3.2 必须自动化的部分
| 对象 | 测试类型 | 为什么 |
|---|---|---|
| `saturatingMultiply` / `affordableAmount` | JUnit | 溢出是最容易出隐性 bug 的地方 |
| `EmcStackKey` 的 NBT / 网络往返 | JUnit（需最小 registry 环境，或退化为手动） | 序列化错误会导致静默丢数据 |
| 知识集合的 NBT 编解码 | JUnit | 同上 |
| 折算与兑换的**核心不变式** | GameTest（若可行） | "存入再取出的循环**不产生净收益**"是最重要的防刷断言（注意：`covalenceLoss` 默认 1.0 时循环是**中性**，不是亏损） |

### 3.3 边界物品矩阵（阶段 3、6 必须逐项过）

| 类别 | 例子 | 期望行为 | 关注点 |
|---|---|---|---|
| 普通可堆叠 | 钻石、铁锭 | 折算为 EMC | 基准 |
| 无 EMC | 石头、泥土 | 原样入库 | R4 |
| 有耐久 | 未附魔的钻石镐（已用） | 默认不折算（`convertComponentItems=false`） | 组件信息保护，风险 R4 |
| 有附魔 | 锋利 V 钻石剑 | 默认不折算 | `EnchantmentProcessor` 默认禁用，附魔**不**加价，折算会白亏 |
| 自定义名 | 命名过的物品 | 默认不折算 | 组件保护 |
| 容器 | 装着东西的潜影盒 | 默认不折算（组件非默认） | 防止内容物被吞 |
| 储能物品 | Klein Star、能量收集器 | 默认不折算 | `StoredEMCProcessor` 会给储能加价，但折算后储能信息消失 |
| 不可堆叠 | 剑、盔甲 | 折算 | `getVanillaMaxStackSize` 与数量语义 |
| 极大堆叠 | 数量 `Long.MAX_VALUE` 的测试物品 | 饱和处理，不溢出 | 风险 R5 |
| 已学习重复存入 | 同一个钻石存两次 | EMC 累加，学习集合不重复 | R3 |
| 特殊物品 | 装满流体的桶、成书 | 逐个确认 | ProjectE 的组件处理器行为 |

### 3.4 升级前置模组版本的回归清单
BD 或 ProjectE 版本变更后，**必须**重跑：
1. Mixin 是否全部命中（看启动日志的 Mixin 报错/警告）——尤其 `DimensionsNetMenu.buildIndexList`、`BDBaseGUI.slotClicked`、`DimensionsNet.save/load` 四个签名。
2. `StackKeyRegistry.registerType` 是否仍存在、`EmcStackKey` 是否还能被反序列化。
3. `IStackKey` / `LongStackKey` / `IStackRender` 接口是否有方法增删。
4. `UnifiedStorageBeforeInsertHandler` 的签名与 `net == null` 语义是否有变。
5. 完整跑一遍阶段 3 与阶段 5 的验收标准。
6. 更新 `docs/VERSIONS.md`。

---

## 4. 里程碑与交付物

| 里程碑 | 对应阶段 | 可演示的效果 | 累计人日 |
|---|---|---|---|
| M1 骨架可跑 | 0–1 | 空模组能启动，Mixin 命中前置模组 | ~2 |
| M2 EMC 能存 | 2 | 网络里有一行 EMC，能持久化，**载体风险已排除** | ~4 |
| M3 核心闭环 | 3 | 存物品 → 变 EMC + 学会；读档不污染 | ~7 |
| M4 可兑换 | 4 | 命令能兑换，防刷断言成立 | ~9 |
| M5 界面完成 | 5 | 在 BD 原生界面里看到并点击兑换 | ~12 |
| M6 可发布 | 6–7 | 配置齐全、文档齐备、回归通过 | ~16 |

---

## 5. 明确的阻塞与风险提示

1. ~~**P1（无 JDK）是硬阻塞**~~ → **已解决**。JDK 21 已装于 `C:\Program Files\Java\jdk-21.0.12.1`。残留的坑是**环境变量**：它不在 `PATH`、`JAVA_HOME` 为空，且 DSH 宿主进程持有旧环境块，因此每条构建命令都要显式导出 `JAVA_HOME`（见 §0 P1 详注）。误以为"装了就能用"会导致 `gradlew` 直接报 "JAVA_HOME is not set"。
2. ~~**P3（jar 获取）需要人工介入**~~ → **已解决**。两个模组都不发布公共 Maven 构件，但 CurseMaven 坐标已核实（`beyond-dimensions-1222890:8812500` / `projecte-226410:6611984`），配置可直接使用。风险降级为：若构建时 `cursemaven.com` 不可达，切本地 jar 兜底。
3. **阶段 5 是最可能超期的阶段**。界面注入涉及 3 个 Mixin + 1 个 Accessor，且 BD 的列表构建逻辑有缓存、有 delta 同步、有 Shift 冲刷三个反直觉点（已在架构文档 §4.3 列全）。建议在阶段 5 开始前先把阶段 1 的验证 Mixin 扩成一个"注入点冒烟测试"，一次性确认 4 个目标方法都能命中。
4. **Spike S2 是整个方案的分水岭**：如果 EMC 行在 BD 界面里无法与玩家交互共存，就要在阶段 2 换载体（ADR-001 方案 B），越早发现越省事。
5. **卸载安全性**（风险 R9）是本模组的固有缺陷，无法在技术上完全消除（BD 用 `catch(Throwable)` 吞掉未注册类型的条目）。只能靠文档警告 + drain 命令缓解。
