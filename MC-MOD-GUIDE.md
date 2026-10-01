# Minecraft 模组开发参考（本机环境）

> **这是什么**：在本机环境下开发 Minecraft 模组（NeoForge 1.21.1）的可复制参考。
> 面向"以后接手这件事的 agent"，把**踩过的坑**和**验证方法**写清楚，省掉重复的排查。
>
> **⚠️ 这不是铁律**：本文写的是"在本机这台机器 + 这套工具链上确实如此"。
> 换机器、换 MC 版本、换加载器之后，**很多结论会失效**。
> 每条都尽量写了"为什么"，方便判断何时不再适用。
>
> **怎么用**：整份文件是自包含的，可以单独复制到新工作区。
> 建议重命名为 `AGENTS.md` 放在工作区根目录 —— 多数 agent 会自动读取该文件。

---

## 1. 本机环境事实

| 项 | 值 | 备注 |
|---|---|---|
| 操作系统 | Windows 11 (amd64) | |
| 用户名 | `朱雨杭` | **非 ASCII**，路径含中文，很多工具要小心 |
| JDK | `C:\Program Files\Java\jdk-21.0.12.1` | **未进 PATH**，必须显式指定 `JAVA_HOME` |
| PowerShell | 7.6.6（`pwsh`）+ 5.1（`powershell`） | `.ps1` 受执行策略限制，需 `-ExecutionPolicy Bypass` |
| Git | `C:\Program Files\Git`（2.55.0.windows.5） | 自带 curl 是 **Schannel 后端**（无 OpenSSL） |
| 启动器 | **PCL2**，游戏目录 `C:\mc` | 版本隔离布局，见 §1.2 |
| Gradle 缓存 | 必须放在**工程内** | 见 §1.1 |
| 工程示例 | `C:\mc\mcwj\mod-learn` | 本文的命令都以它为例 |

### 1.1 Gradle 缓存必须放在工程内

`%USERPROFILE%\.gradle`（即 `C:\Users\朱雨杭\.gradle`）在本机**不可写**。
所以：

```bat
set "GRADLE_USER_HOME=%~dp0..\.gradle-home"
```

否则 Gradle 会在启动阶段直接失败。

### 1.2 PCL2 的游戏目录与 mods 位置

```
C:\mc\                                  ← 游戏目录（不是 %APPDATA%\.minecraft！）
├── versions\
│   └── 1.21.1-NeoForge_21.1.249\       ← 版本隔离：每个版本有自己的 mods
│       ├── mods\                       ← ★ 装模组的地方
│       ├── config\
│       ├── logs\latest.log             ← 出问题先看这里
│       └── 1.21.1-NeoForge_21.1.249.jar
├── libraries\
├── assets\
├── launcher_profiles.json
└── PCL.ini
```

**`%APPDATA%\.minecraft` 几乎是空的**（只有一个 `runtime` 目录）——
不要想当然地往那儿找 mods。

### 1.3 网络与 TLS（本机最反直觉的一块）

| 客户端 | 对 github.com | 对 mirrors.cloud.tencent.com | 说明 |
|---|---|---|---|
| PowerShell `Invoke-WebRequest` | ❌ | ❌ | `schannel: SEC_E_NO_CREDENTIALS` |
| Windows 自带 `curl`（Schannel） | ❌ | ❌ | 同上；`--cacert` 被忽略（无 OpenSSL 后端） |
| Git 自带 curl | ❌ | ❌ | `libcurl/… Schannel`，同样是 Schannel |
| **Java（JVM）** | ❌ 证书链不受信 | ✅ **200 OK** | 不是全局不可信，是**特定证书链**的问题 |
| **git（切 OpenSSL + 导出 CA）** | ✅ | ✅ | 见下 |

**结论：Java 是唯一稳定可用的 HTTPS 客户端**（对部分站点）。要让 git 通，需要两步：

```powershell
# 1) 把 Windows 证书存储导出成 PEM（git 的 CA bundle 不含本机信任链）
$sb = New-Object System.Text.StringBuilder
foreach ($s in @('Cert:\LocalMachine\Root','Cert:\CurrentUser\Root','Cert:\LocalMachine\CA','Cert:\CurrentUser\CA')) {
  foreach ($c in (Get-ChildItem $s -ErrorAction SilentlyContinue)) {
    [void]$sb.AppendLine("-----BEGIN CERTIFICATE-----")
    [void]$sb.AppendLine([Convert]::ToBase64String($c.RawData, 'InsertLineBreaks'))
    [void]$sb.AppendLine("-----END CERTIFICATE-----")
  }
}
[System.IO.File]::WriteAllText("$PWD\.git\win-ca-bundle.pem", $sb.ToString())

# 2) 让本仓库用 OpenSSL 后端 + 这份 bundle（仓库级，不污染全局）
git config http.sslBackend openssl
git config http.sslCAInfo "$PWD\.git\win-ca-bundle.pem"
```

**Gradle 的发行版下载**同理：`services.gradle.org` 会 PKIX 失败，改用腾讯镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.2.0-bin.zip
```

> 判断"是全局不可信还是只某条链不可信"的方法：**拿一个已知能通的站点做对照**
> （`mirrors.cloud.tencent.com` 就是那个对照组）。

### 1.4 Git 推送：agent 做不到，必须用户来

在 agent 的沙箱里执行 `git push` 会失败：

```
bash.exe: *** fatal error - couldn't create signal pipe, Win32 error 5
fatal: could not read Username for 'https://github.com'
```

原因是凭据助手（GCM）的辅助 shell 无法创建命名管道 —— **沙箱限制，不是网络问题**。
用户在自己的终端里推送是正常的（他成功推过）。

**所以：agent 负责"提交好、配置好远程、打好 tag"，把 `git push` 留给用户。**

---

## 2. 一次性搭建

```groovy
// build.gradle 要点
plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '2.0.116'    // 与目标模组保持一致最省事
}
java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = project.neo_version
    parchment { minecraftVersion = '1.21.1'; mappingsVersion = '2024.11.17' }
    runs {
        client { client() }
        server { server(); programArgument '--nogui' }
    }
    mods { "${mod_id}" { sourceSet(sourceSets.main) } }
}

// 用 src/main/templates 展开 neoforge.mods.toml 里的 ${} 占位符
var generateModMetadata = tasks.register('generateModMetadata', ProcessResources) { /* … */ }
sourceSets.main.resources.srcDir generateModMetadata
neoForge.ideSyncTask generateModMetadata

// MIT 要求保留版权声明
tasks.named('processResources', ProcessResources).configure {
    from(rootProject.file('LICENSE')) { into 'META-INF' }
}
```

```properties
# gradle.properties 要点
org.gradle.jvmargs=-Xmx4G
org.gradle.parallel=true
org.gradle.caching=false
org.gradle.configuration-cache=false
org.gradle.vfs.watch=false          # 沙箱下原生文件监视会报 "error = 5"

minecraft_version=1.21.1
neo_version=21.1.249                # ★ 见 §5.4：对齐用户的实机版本
parchment_minecraft_version=1.21.1
```

### 2.1 依赖：模组必须用 `compileOnly` + `runtimeOnly`

```groovy
// ✅ 正确
compileOnly files('libs/xxx.jar')
runtimeOnly files('libs/xxx.jar')

// ❌ 错误：additionalRuntimeClasspath 是给"普通库"用的
//    实测结果：run 的 Mod List 里看不到它，
//    报 "Mod beyondemc requires beyonddimensions … Currently, beyonddimensions is not installed"
additionalRuntimeClasspath files('libs/xxx.jar')
```

### 2.2 依赖来源：本地 jar 优先，Maven 兜底

仓库里**不要提交**第三方模组 jar（`.gitignore` 排除 `libs/*.jar`），
但要让克隆下来的人也能构建：

```groovy
def bdJar = file('libs/beyonddimensions-1.21.1-0.7.30.jar')
if (bdJar.exists()) {
    compileOnly files(bdJar); runtimeOnly files(bdJar)
} else {
    compileOnly "curse.maven:beyond-dimensions-1222890:8812500"
    runtimeOnly "curse.maven:beyond-dimensions-1222890:8812500"
}
```

可选集成的编译期依赖（如 JEI）从公共 Maven 取 —— 本机实测
`https://maven.blamejared.com` 可达（200）：

```groovy
maven { name = 'BlameJared'; url = 'https://maven.blamejared.com'
        content { includeGroup 'mezz.jei' } }
compileOnly "mezz.jei:jei-1.21.1-neoforge:${jei_version}"
```

---

## 3. 日常开发循环

```bat
tools\play.cmd                  @ 双击：启动客户端（runClient）
tools\play.cmd runServer        @ 从终端跑服务器
tools\play.cmd build            @ 构建
tools\gradlew-here.cmd <task>   @ 参数化的包装脚本
```

**这两个 `.cmd` 的约定**（都是踩出来的）：

1. **必须是纯 ASCII** —— `.cmd` 用 OEM 代码页解析，UTF-8 中文注释会破坏换行并**被当作命令执行**
2. **必须是 CRLF 换行** —— LF-only 时 cmd.exe 解析多行块（`(...)`、`if/else`）会出错
3. **显式设置 `JAVA_HOME` 与 `GRADLE_USER_HOME`**（§1.1）
4. **结尾 `pause`** —— 双击失败时窗口不会瞬间关闭，用户才看得到错误

`.ps1` 脚本受执行策略限制，从 `.cmd` 这样调：

```bat
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
```

### 3.1 日志位置

| 日志 | 路径 |
|---|---|
| 开发环境（runClient / runServer 共用 `run/`） | `run/logs/latest.log` |
| 用户实机（PCL2） | `C:\mc\versions\<版本>\logs\latest.log` |
| 崩溃报告 | `run/crash-reports\crash-*-fml.txt`（**模组加载崩溃只有这里能看到原因**） |

**agent 应当自己去读日志**，不要让用户复制粘贴 —— 日志能读，且量往往很大。

---

## 4. Mixin 相关（版本敏感，最容易静默失效）

### 4.1 `require = 0` 的覆盖范围被高估过

> `require = 0` **只覆盖"注入点找不到"**。
> handler 的**签名/描述符错误是硬错误** —— 无论 `require` 设成什么，
> 都会抛 `InvalidInjectionException` 并**中断启动**。

**存在重载时务必写完整描述符**：

```java
// ❌ UnifiedStorage 有三个同名 extract 重载，只写名字会匹配错
@Inject(method = "extract", at = @At("HEAD"))

// ✅ 完整描述符（名字 + 参数 + 返回类型）
private static final String EXTRACT_BY_KEY =
        "extract(Lcom/…/IStackKey;JZZ)Lcom/…/KeyAmount;";
@Inject(method = EXTRACT_BY_KEY, at = @At("HEAD"))
```

### 4.2 可选集成的 Mixin 必须门控

引用可选模组类型的 Mixin，在对方缺席时加载会 `NoClassDefFoundError` → **崩游戏**。
做法：**独立的 mixin 配置** + `MixinConfigPlugin`：

```json
// beyondemc.jei.mixins.json
{ "required": false,
  "package": "com.….mixin.jei",
  "plugin": "com.….BeyondEmcJeiMixinPlugin",
  "client": ["TransferHelperMixin"],
  "injectors": { "defaultRequire": 0 } }
```

```toml
# mods.toml 里可以有多个 [[mixins]] 块
[[mixins]]
config="beyondemc.mixins.json"
[[mixins]]
config="beyondemc.jei.mixins.json"
```

**门控判据用"类路径"而不是 `ModList`**：

```java
// ✅ 只看类路径，与调用时机无关
Class.forName("mezz.jei.api.IModPlugin", false, getClass().getClassLoader());

// ❌ 实测失效：shouldApplyMixin 会在 Mixin 配置准备阶段被调用，
//    那时 ModList 未必就绪；一旦判为 false 并被缓存，Mixin 永不应用（且无任何报错）
ModList.get().isLoaded("jei");
```

### 4.3 客户端专有的 Mixin 无法无头验证

`runServer` 上客户端专有类不会被加载，所以那部分 Mixin 是否生效**测不出来**。
对策（本节最有价值的一条）：

> **给每种失败模式一个可区分的日志行**，让用户跑一次就能定位到你想要的那一层。

例如 JEI 集成分三层：

```
JEI Mixin 配置已加载（package=…）    ← 没有它 = 配置根本没注册
JEI Mixin 门控判定：jei 在类路径上=… ← 有第一行没第三行 = 被门控挡掉
JEI Mixin 已应用到 <目标类>          ← 有它 = 确实生效
JEI 转移钩子被调用（第 N 次）        ← 有它 = 目标方法真的被调用了
```

另外，**不要依赖"目标类存在"来推断"我们的注入生效了"**——
二者是两件事。用反射/`javap` 只能证明前者。

---

## 5. 验证纪律（本项目最有价值的部分）

### 5.1 `BUILD SUCCESSFUL` ≠ 能用

Gradle 报成功时，Mixin 仍可能在启动时把游戏搞崩。
**每次改动 Mixin 或模组元数据后，必须真跑一次服务器**：

```powershell
tools\gradlew-here.cmd runServer     @ 看日志，不要只看 BUILD SUCCESSFUL
tools\gradlew-here.cmd --stop        @ 停守护进程；不要枚举/杀 java 进程
```

### 5.2 把自检写进启动流程

在启动时跑一组断言并打到日志里，这样才能**无头验证**。
本项目积累到 38 项，覆盖：资源类型往返、读档守卫、兑换规则、
**Mixin 目标存活性**、策略配置、注入逻辑的纯函数部分。

要点：

- 断言要**决定性**：不要只断言"函数返回了"，要断言**具体原因**
  （教训：多道门禁都返回"放行"时，光看返回值区分不出是被哪道门挡的。
  解决：把策略抽成返回**原因字符串**的纯函数）
- 把易错的规则抽成**纯函数**（如"鼠标能再吸附多少个"），
  这样不必依赖 GUI 就能测
- **自检的日志不能与生产日志混淆**（见 §5.5）

### 5.3 用 `javap` 核对依赖方的真实签名

不用跑客户端就能确认注入点是否写对：

```powershell
& "$env:JAVA_HOME\bin\javap.exe" -p -s -classpath libs\beyonddimensions-….jar `
    com.wintercogs.….TransferHelper
```

输出会给出完整描述符，据此判断参数索引 / 有无重载。

### 5.4 对齐开发环境与用户实机的版本

**实测教训**：开发环境是 NeoForge `21.1.234`，用户实机是 `21.1.249`。
给 dev 端加上 JEI 后启动即崩：

```
Mod jei requires neoforge 21.1.238 or above
Currently, neoforge is 21.1.234
```

**结论：把 `neo_version` 对齐到用户实机版本**，消除偏差。
另外**每个前置/可选模组都要核对它自己声明的版本要求**（读它的
`META-INF/neoforge.mods.toml`，而不是只看文件名）。

### 5.5 同一条纪律的两个面（本项目反复吃亏）

> **凡是能从权威数据源现场推导的事实，就不要维护第二份副本。**

- 用可变集合记录"虚拟条目" → 被自己的注入污染 → 失同步（功能静默失效）
- 信任 `Inventory.add` 的返回值 → 它在创造模式下会**销毁物品却返回 true** → 扣了钱没拿到东西

对策：让不变式**由构造保证**，并写成自检。例如
「**扣费数量 == 交付数量**」——
在扣费**之前**就算定容量，而不是扣完再发现放不下。

### 5.6 测量手段不能改变被测对象

**实测教训**：自检用的合成数据（余额 `819200` 等）也打出了与真实交互
**一模一样**的日志，导致"功能到底有没有生效"无法判断，白跑一轮实机。

对策：自检期间静默生产日志（`setQuiet`），或让两类日志**前缀可区分**。

---

## 6. 备份与回退

每个可玩版本都要有**验证过**的备份（没演练过的备份不算备份）：

```powershell
$v = "0.2.0"
git bundle create "backups/beyondemc-$v.bundle" --all   # 完整仓库，可 clone
git archive --format=zip -o "backups/beyondemc-$v-source.zip" HEAD
Copy-Item build\libs\beyondemc-*$v*.jar backups\
# 前置模组不在 git 里，也要备份，否则无法离线重建
Copy-Item libs\*.jar backups\deps\

# ★ 演练：真的克隆出来核对
git clone backups/beyondemc-$v.bundle backups\_verify
# 核对 HEAD / 提交数 / tag / 文件数 / 工作树，然后删掉演练目录
```

`backups/` 要写进 `.gitignore`（体积大且不该进仓库），并配一份 `README.md`
记录**校验和**与**回退步骤**。

---

## 7. 发布

1. `mod_version` 提版本 → 干净重建（`clean build`）→ 产物在 `build/libs/`
2. 写 `docs/release-notes-vX.Y.Z.md`（**可直接整段粘贴**到 GitHub Release）
3. 打**附注 tag** 并核对它指向 HEAD：
   ```powershell
   git tag -a vX.Y.Z -m "…"
   git rev-list -n1 vX.Y.Z   # 与 git rev-parse HEAD 比对
   ```
4. 推送与建 Release 由**用户**做（§1.4）：
   ```powershell
   git push origin main
   git push origin vX.Y.Z
   ```
   然后浏览器里 `releases/new` → 选**已有 tag**（不要新建同名 tag）→ 粘贴说明 → 拖入 jar

### 7.1 Release 说明里值得写的

- **必需前置与版本要求**（尤其"只放本模组会拒绝加载"）
- **行为变化**（用户升级会懵的地方）
- **卸载警告**，如果移除模组会丢数据
- **排查入口**：告诉用户看哪几行日志

---

## 8. 本机特有的坑（症状 → 原因 → 解法）

| 症状 | 原因 | 解法 |
|---|---|---|
| Gradle 启动即失败 | `C:\Users\朱雨杭\.gradle` 不可写 | `GRADLE_USER_HOME` 指向工程内 |
| `PKIX path building failed` | `services.gradle.org` 证书链不受信 | 换腾讯镜像 |
| `schannel: SEC_E_NO_CREDENTIALS` | PowerShell/curl 的 Schannel 拿不到凭据 | 改用 **Java** 做 HTTPS；git 切 OpenSSL + 导出 CA（§1.3） |
| `git push` 失败 `couldn't create signal pipe` | 沙箱里 GCM 辅助进程起不来 | **让用户在自己终端推** |
| 双击 `.cmd` 报语法错误 / 乱码 | 文件是 UTF-8 中文或 LF 换行 | `.cmd` 保持**纯 ASCII + CRLF** |
| `.ps1` 拒绝执行 | 执行策略 | `powershell -ExecutionPolicy Bypass -File` |
| `git add` 半途中断、`index.lock` 残留 | 把改动状态的命令接进了 `Select-Object -First N`（管道被提前拆掉） | **不要管道化会改动状态的命令**；确认无 git 进程后删 lock |
| `git commit` 报 `pathspec` 错误 | 提交信息里的引号被 PowerShell 当参数分隔符 | 写进临时文件用 `git commit -F <file>` |
| 日志里"找不到证据" | 自检日志与生产日志混在一起 | 自检期间静默（§5.6） |
| 模组加载崩溃但 `latest.log` 看不出原因 | 加载期崩溃只写崩溃报告 | 看 `run/crash-reports/crash-*-fml.txt` |

---

## 9. 与用户协作的方式

- **日志自己读**，别让用户贴（日志量大，且 agent 读得到）
- **明确区分"我验证过的"与"需要你实机验证的"**；
  客户端专有路径（GUI、JEI、渲染）基本都属于后者
- 需要人工验证时，给出**可区分的日志行**与**三种结果对应的结论**（§4.3），
  而不是"你试试看"
- **用户报告的 symptom 是起点，不是全部**：
  同一个根因往往存在于多条路径上
  （实测：用户只报了"网络接口能刷物品"，但 GUI 路径有同一个洞、且更容易被脚本化利用）
- 用户的**拒绝和更正**要记下来（例如"不要用 danger-full-access 当借口"），
  这类偏好比技术细节更容易被忘掉

---

## 10. 一句话总结

> 在这个环境里，**唯一可靠的验证方式是"真跑一遍 + 读日志"**；
> **唯一可靠的失败诊断方式是"让每种失败模式打出不同的日志"**；
> 而最容易犯的错，是**相信一个看起来权威的信号**（编译成功、返回值、
> 目标类存在、自己维护的副本）。
