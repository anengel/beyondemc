# 本机环境注意事项（BeyondEMC 开发环境）

> 这份文档记录的都是**本机特有**的坑，与模组逻辑无关。每条都是实际踩到并解决的，不是推测。
> 换一台机器开发时，这份文档大部分可以忽略。

---

## 1. JDK 21 不在 PATH，`JAVA_HOME` 为空

**现象**：`java -version` 报 "The term 'java' is not recognized"；`gradlew` 报
`ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.`

**实际**：JDK 21 **已安装**于 `C:\Program Files\Java\jdk-21.0.12.1`（`java 21.0.12.1 LTS` / `javac 21.0.12.1`）。

**根因**：JDK 未加入 `PATH`，`JAVA_HOME` 为空；而且**已经运行的 DSH 宿主进程持有的是旧环境块**，
所以即使之后设置了系统环境变量，本会话新开的 shell 也读不到 —— 除非重启 DSH。

**解决**：用 `tools\gradlew-here.cmd`，它自带 `JAVA_HOME`。或在命令里显式导出。

---

## 2. `pwsh` 实际是 Windows PowerShell 5.1 —— `.ps1` 的执行策略 + 编码双重坑

> 2026-09-27 实测：本机原先**没有安装 PowerShell 7**，DSH 的 pwsh 工具跑的是
> `C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe`（5.1）。
> 现已安装 **PowerShell 7.6.6**，本节记录完整根因与现状。

**现象 A（执行策略）**：
```
. : File ...\tools\gradle-env.ps1 cannot be loaded because running scripts is disabled on this system.
```

**现象 B（放开策略后才会暴露）**：dot-source 仍报 **7 处语法错误**，且报错行号比文件实际行号**少 6 行**：
```
所在位置 ...\tools\gradle-env.ps1:23 字符: 14
+ $env:Path = "$jdk\bin;$env:Path"
+              ~~~~~~~~
表达式或语句中包含意外的标记“$jdk\bin”。
```

**根因（三层，缺一不可）**：

1. **执行策略**：5.1 的五个作用域全是 `Undefined`（无组策略），客户端默认值 `Restricted` 生效。
2. **DSH 用哪个 shell 是探测出来的**：`dsh-pwsh-local` 的 `candidatePwshPaths()` 依次探
   `%ProgramFiles%\PowerShell\7\pwsh.exe` → PATH 里的 `pwsh.exe` → 兜底 5.1；
   当时前两项都不存在，所以落到 5.1。
3. **5.1 按 ANSI 读无 BOM 的 `.ps1`**：本机 ANSI 代码页是 `gb2312`，而该文件是无 BOM 的 UTF-8。
   同一份字节实测：按 UTF-8 解析 **0 错**，按 ANSI 解析 **7 错**。
   中文注释乱码成 `鍦ㄦ湰鏈鸿窇`，乱码还会与行尾 CR/LF“配对”吞掉换行 —— 这正是行号偏移 6 行的原因
   （与第 3 节 `.cmd` 的机制同源）。

**坑 4（脚本自身缺陷，与 shell 无关）**：`$ErrorActionPreference = 'Stop'` 配上末行的
`& java.exe -version 2>&1 | ...`，会把 java 写在 stderr 的正常版本信息变成 `NativeCommandError`
**终止性错误**，从而中断调用方后续语句 —— `. .\tools\gradle-env.ps1; .\gradlew.bat build` 会断在中间。

**现状（装上 PS7 后实测）**：

| | Windows PowerShell 5.1 | PowerShell 7.6.6 |
|---|---|---|
| 有效执行策略 | `Restricted` | **`RemoteSigned`** |
| 无 BOM UTF-8 `.ps1` | 按 ANSI 读 → 语法错误 | 按 UTF-8 读 → 正常 |
| 原生命令 stderr + `EAP=Stop` | 终止性错误 | 正常 |
| dot-source `gradle-env.ps1` | 失败 | **成功，且后续语句照常执行** |

PS7 的 `RemoteSigned` 来自其自带的 `$PSHOME\powershell.config.json`
（`"Microsoft.PowerShell:ExecutionPolicy": "RemoteSigned"`），**因此不需要再改执行策略**就能
直接 dot-source 工程内的 `.ps1`。（`RemoteSigned` 只拦带“来自 Internet”标记的文件，遇到用 `Unblock-File` 解锁。）

**⚠️ 必须重启 DSH Desktop 才会切换**：`PwshLocalExecutor` 在**构造时**解析一次 `pwshPath`，
之后只有“声明的 `pwshPath` 配置值发生变化”才会重新探测文件系统。仅安装不重启，会话仍是 5.1。

**仍然保留 `.cmd` 的理由**：`tools\gradlew-here.cmd` 不受执行策略与编码影响，5.1 与 PS7 下都能跑，
继续作为兜底入口。

---

## 3. `.cmd` 文件必须是纯 ASCII

**现象**：`.cmd` 里写中文注释后，执行时报一堆
```
'为什么需要它：本机' is not recognized as an internal or external command
```

**根因**：`cmd.exe` 按 **OEM 代码页（本机为 GBK）** 解析批处理文件，而文件是 UTF-8。
中文的多字节序列会与行尾的 CR/LF 字节"配对"，把换行吞掉，导致下一行的 `REM` 与中文一起被当成命令执行。

**解决**：`tools\gradlew-here.cmd` 保持**纯 ASCII**。中文说明放在本文档里；放进 `.ps1` 也可以，
但那要求读取它的是 **PowerShell 7** —— 5.1 下无 BOM 的中文注释同样会吞掉换行（见第 2 节）。
（加 `chcp 65001` 不可靠：解析器在此之前就已经按旧代码页读入了。）

---

## 4. Gradle 家目录必须放在工作区内

**现象**：
```
java.lang.RuntimeException: Could not create parent directory for lock file
    C:\Users\<用户>\.gradle\wrapper\dists\gradle-9.2.0-bin\...\gradle-9.2.0-bin.zip.lck
```

**根因**：DSH 文件沙箱只允许写入会话工作区，Gradle 默认的 `~/.gradle` 在工作区之外。

**解决**：把 `GRADLE_USER_HOME` 指到工作区内：

```powershell
$env:GRADLE_USER_HOME = "C:\mc\mcwj\mod-learn\.gradle-home"
```

该目录已在 `.gitignore` 中。首次构建会把 Gradle 发行包与全部依赖缓存到里面（数百 MB 到 2GB）。

> 若希望用标准的 `~/.gradle`（例如之后要在 IDEA 里构建，想共用缓存），需要放宽沙箱权限，
> 而不是改工程配置。

---

## 5. `services.gradle.org` 的证书链在 JVM 中不受信

**现象**：
```
javax.net.ssl.SSLHandshakeException: (certificate_unknown) PKIX path building failed:
    sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
    at org.gradle.wrapper.Install.forceFetch
    at java.net.HttpURLConnection.followRedirect0
```

**根因**：`services.gradle.org` 与 `downloads.gradle.org` 的 zip 地址在 JVM 下证书链无法验证
（实测 Java 探针：这两个地址 `SunCertPathBuilderException`，而其他所有依赖源都正常）。
典型的域名劫持/CDN 证书问题，与 JDK 的 `cacerts` 无关 —— 同一 JVM 访问
`repo1.maven.org` / `maven.neoforged.net` / `plugins.gradle.org` / `maven.parchmentmc.org`
/ `piston-meta.mojang.com` 全部 HTTP 200。

**解决**：`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 改指腾讯云镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.2.0-bin.zip
```

官方源地址以注释形式保留在文件里，换网络后对调即可。

---

## 6. 用 Java 做网络诊断，不要用 `Invoke-WebRequest`

**现象**：PowerShell 的 `Invoke-WebRequest` 对**所有** HTTPS 站点都失败
（`基础连接已经关闭: 接收时发生错误`），`curl.exe` 则报
`schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS (0x8009030e)`。
但纯 HTTP（80 端口）正常，原始 TCP 连接（443）也正常。

**解读**：这是 Windows SChannel 在**本进程内**拿不到加密凭证，属于本地环境问题
（很可能与沙箱对加密密钥目录的访问限制有关），**不是网络不通**。

**关键结论**：Gradle 跑在 JVM 上，而**同一时刻 JVM 的 HTTPS 完全正常**。
所以判断"某个依赖源能不能用"必须用 Java 探测，用 PowerShell 探测会得到全盘失败的假象。

**工具**：`tools/netprobe/NetProbe.java`（纯 JDK，无第三方依赖）。

```powershell
$jdk = "C:\Program Files\Java\jdk-21.0.12.1"
& "$jdk\bin\javac.exe" -d tools\netprobe\out tools\netprobe\NetProbe.java
& "$jdk\bin\java.exe" -cp tools\netprobe\out NetProbe https://example.com/
```

---

## 7. 工作区沙箱的 ACL 授权故障（2026-09-27 已修复）

**现象**：从阶段 2 中段起，**所有** `pwsh` 命令都失败，连无副作用的 `Get-Location` 也一样：

```
Error: SetNamedSecurityInfoW failed (Win32 5): grantWrite(C:\mc\mcwj\mod-learn)
```

失败发生在**沙箱自身的授权步骤**（Win32 5 = 拒绝访问），不是命令本身的问题。

**根因（两层，都在 `@deepseek-ai/dsh-sandbox-windows-acl` 的实现里）**：

1. **`grantWrite` 需要 `WRITE_OWNER`，而“属主”并不隐式拥有它。**
   该步骤用**一次** `SetNamedSecurityInfoW(path, SE_FILE_OBJECT, 20 /* DACL|LABEL */, …)`
   同时写入 DACL 与 **SACL 完整性标签**；写标签要求 `WRITE_OWNER`，而 Windows 只给属主隐式授予
   `READ_CONTROL + WRITE_DAC`。源码注释写得很明确：*“The directory must be owned by the caller AND
   grant WRITE_OWNER (the label lives in the SACL; owner-implicit rights cover only READ_CONTROL and
   WRITE_DAC) — a Full-control workspace satisfies both.”*
   本工作区只给了 `NT AUTHORITY\Authenticated Users: Modify`，**没有 `WRITE_OWNER`** → 拒绝访问。
   （属主是本机用户 `DESKTOP-QPDU34O\朱雨杭`；`BUILTIN\Administrators`、`SYSTEM` 持 `FullControl`。）

2. **幂等跳过要求三样精确匹配，而这份工作区只有其中一样。**
   根目录需同时具备 `grant ACE(type0, OI|CI, mask 0x110156)` + `deny(Everyone, CI, FILE_DELETE_CHILD)`
   + `label(Low, OI|CI, NO_WRITE_UP)` 才跳过；本工作区只有 grant ACE（mask `0x110156` 精确匹配），
   缺 deny 与 label → 每次供给都走写入路径 → 每次都被第 1 条拒掉。源码注释预判了这种状态：
   *“a root granted by an earlier build receives the deny on its next provision”*。

> 早先本文档猜测的“重启 DSH Desktop 即可”**不成立**：ACE 早已存在，问题在权限与传播。
> 那条“解析不出的 SID `S-1-4-65693021-341262876`”是按工作区路径确定性派生的“工作区写 SID”
> （`workspaceWriteSid`），不是残留垃圾。

**修复（两步，缺一不可）**：

1. **给根目录补 `WRITE_OWNER`**（免管理员 —— 属主可用隐式 `WRITE_DAC` 改 DACL）：
   ```powershell
   $acl = Get-Acl C:\mc\mcwj\mod-learn
   $me  = [Security.Principal.WindowsIdentity]::GetCurrent().Name
   $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
       $me, 'TakeOwnership, ChangePermissions', 'Allow')))
   Set-Acl -Path C:\mc\mcwj\mod-learn -AclObject $acl
   ```
   补完后 DSH 的供给一次成功：根目录 DACL 出现 `(D;CI;DT;;;WD)`，标签也在同一次调用里写入，
   之后每次供给都命中精确跳过。

2. **把 Low 完整性标签传播到“已有”的整棵树**（需提权一次）。
   沙箱令牌是 **Low 完整性 + `WRITE_RESTRICTED` 受限令牌**：**读**按普通 SID 放行，
   **写**必须同时通过 capability SID 与完整性检查（Low 主体写 Medium 对象会被 `no-write-up` 拒）。
   而 DSH 那次 `SetNamedSecurityInfoW` **不会回溯传播**到已存在的子对象 —— 实测 10 个一级子目录
   全都拿到了继承的 capability ACE，却**都没有** deny 与标签，于是沙箱内写子目录一律 `ACCESS_DENIED`，
   Gradle 连 `.gradle-home\...\*.lck` 都建不出来。一次性修法（UAC 提权，`Administrators` 继承 `FA`
   才具备 `WRITE_OWNER`）：
   ```powershell
   icacls C:\mc\mcwj\mod-learn /setintegritylevel (OI)(CI)L /T /C
   ```
   本机实测：**9983 个对象全部处理、0 个失败、耗时 4.8 秒**。

**修复后实测（全部在 `workspace-write` 下、零提权）**：

| 验证项 | 结果 |
|---|---|
| `pwsh` 命令是否还需 `danger-full-access` | ✅ 不再需要 |
| 根目录 + 全部 10 个一级子目录 写入/删除 | ✅ 全部通过 |
| 工作区外写入（`C:\Windows\Temp`） | ✅ 仍被拒 |
| 沙箱内 `TEMP` | ✅ 重定向到 `…\Temp\dsh-<随机>` |
| 沙箱内 `whoami /groups` | ✅ `Mandatory Label\Low Mandatory Level (S-1-16-4096)` |
| 沙箱内跑 `tools\gradlew-here.cmd --version` | ✅ `exit 0`，Gradle 9.2.0 |

**整棵树已一致**：`icacls … /T` 这趟遍历会把父目录的可继承 ACE 一并重新物化到既有子对象上，
因此修复后**每一层**目录都同时具备 capability ACE、`deny` 与 Low 标签，与 DSH「从空目录物化」的
预期状态一致。实测：10/10 个一级子目录 + 9 个深层目录全部为 `capACE=1`、`deny=1`、继承未被阻断、沙箱内可写
（`tools` 的 deny 已带 `CIID`，会继续向更深层继承）。

**抽样核对（从既有文件中随机取 400 个逐个以写方式打开）**：其中 23 个失败，全部位于 `.gradle-home`，
且**都是 Gradle 守护进程持有句柄造成的共享冲突**（错误文本为“正由另一进程使用”），
**不是权限或完整性拒绝** —— 非沙箱进程同样打不开它们。同目录内新建文件一切正常。
（沙箱内 `tasklist`/`Get-Process` 会因受限令牌而 `Access denied` 或看不到这些进程，
所以"看不到 java 进程"不代表没有守护进程；遇到这类“打不开”，先跑
`tools\gradlew-here.cmd --stop` 再试。）

**通用结论**：这套沙箱假定工作区对象树“由 DSH 从空目录开始物化”。把大量既有文件放进一个旧工作区，
或让内容在 ACE 授予**之后**才写入，都会踩到“标签追不上子对象”的问题；修法就是上面的第 2 步。

---

## 8. 两个启动脚本的分工（别搞混）

工程里有**两个**看起来相似的脚本，用途不同：

| 脚本 | 用途 | 能双击吗 |
|---|---|---|
| `tools\gradlew-here.cmd` | **需要参数**的通用包装器（自动化、终端）。例如 `tools\gradlew-here.cmd build`、`… runServer`、`… --stop` | ❌ |
| `tools\play.cmd` | **给双击用**的启动器。不带参数时默认 `runClient`；也能 `tools\play.cmd build` | ✅ |

**为什么双击 `gradlew-here.cmd` 看起来"没反应"**（实测结论）：

1. 双击时没有参数 → 脚本执行 `call gradlew.bat`（**无任务**）→ Gradle 只打印一段帮助就退出；
2. 该脚本**末尾没有 `pause`** → 控制台窗口在进程退出瞬间关闭，输出根本来不及看。

`play.cmd` 针对这两点做了处理：默认任务为 `runClient`、末尾 `pause`，
并且显式设置 `GRADLE_USER_HOME=<项目>\.gradle-home`
（否则会退回 `%USERPROFILE%\.gradle`，把 Gradle 发行包与 NeoForge/Minecraft 重新下载 1–2 GB）。

实测：`tools\play.cmd --version` → 打印 `Gradle 9.2.0`、退出码 0、窗口停在 `pause`。

> 两个 `.cmd` **都必须保持纯 ASCII**（见本文档 §3）：`cmd.exe` 按 OEM 代码页解析批处理，
> UTF-8 中文注释会吞掉换行并被当作命令执行。
