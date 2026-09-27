# 阶段 3 实测报告：存入折算 + 网络学习集合 + 读档守卫

> 日期：2026-09-27 · 环境：Windows 11 · JDK `21.0.12.1` · NeoForge `21.1.234`
> 前置：超越维度 `0.7.30` + ProjectE `1.1.0`（本地 jar）
> 本报告只记录**实际跑出来的结果**。

---

## 1. 交付物

| 文件 | 说明 |
|---|---|
| `emc/EmcDepositHandler.java` | **核心**：存入折算钩子（挂在 BD 官方扩展点上，零 Mixin） |
| `knowledge/NetKnowledgeStore.java` | 网络级"已学习物品"集合（内存态 + NBT 读写） |
| `core/LoadingGuard.java` | "正在反序列化"标志（ThreadLocal 深度计数） |
| `mixin/AbstractUnorderedStorageLoadMixin.java` | `@WrapMethod` 包住 `deserializeNBT`，置/复位守卫 |
| `mixin/DimensionsNetMixin.java` | 教学集合写进网络存档（`save`/`load`/`mergeOtherNet`） |
| `config/BeyondEmcConfig.java` | 服务端配置（折算总开关、组件物品策略） |
| `diag/Phase3SelfTest.java` | 8 项无头自检 |
| 命令 | `/beyondemc knowledge list\|clear`，`selftest` 现在同时跑阶段 2+3 |

---

## 2. 自检结果

启动日志原文（服务器自动执行）：

```
[BeyondEMC] 已注册存入折算钩子
[BeyondEMC] ---- 阶段 2 自检结果：8/8 项通过 ----
[BeyondEMC] ---- 折算与学习集（阶段 3）----
[BeyondEMC] OK   钩子路由：net=null / 非物品资源 / 空堆叠 三类输入均原样通过（net=null:OK 非物品资源:OK 空堆叠:OK）
[BeyondEMC] OK   读档守卫：加载中不折算（返回仍是物品），且退出后标志已复位
[BeyondEMC] OK   折算门禁：EMC 表未就绪（remap 次数=0）→ 不折算，物品原样入库
[BeyondEMC] OK   学习集合：未学→学会(true)→重复学(false)→可查→size=1→清空生效
[BeyondEMC] OK   学习集合 NBT 往返：3 项完整还原，NBT 键=beyondemc:knowledge
[BeyondEMC] OK   学习集合并：两个网络各 1 项，合并后 2 项
[BeyondEMC] OK   LoadingGuard：嵌套计数正确（enter→true, 内层 exit 后仍 true, 外层 exit 后复位）
[BeyondEMC] OK   读档守卫 Mixin 已生效：反序列化期间 enter() 被调用 1 次，退出后标志复位，且数据完整（EMC=4242）
[BeyondEMC] ---- 阶段 3 自检结果：8/8 项通过 ----
```

### 2.1 最关键的一项：Mixin 生效性

第 8 项是**我补测的**，因为第 2 项存在一个盲区：它只证明了 `LoadingGuard` 自身的语义，
**完全无法证明 Mixin 真的挂上了**。若注入静默失效，标志永远不被置起，
"读档重写存档"这个数据损坏问题会回来，而所有自检依然是绿的。

补测方式：真的做一次存储反序列化（`serializeNBT` → `deserializeNBT`），
用 `LoadingGuard.enterCount()` 的增量证明 Mixin 被调用过，并同时校验数据完整（EMC=4242）。

对应地，`LoadingGuard` 里加了一个 `ENTER_COUNT` 计数器，注释里写明了它存在的唯一目的
就是让这条自检能证明 Mixin 生效。

### 2.2 四道安全门

`EmcDepositHandler` 按以下顺序守卫，任何一道不通过都"原样放行"（而不是丢弃）：

| # | 门 | 为什么 |
|---|---|---|
| 1 | `net == null` | `UnifiedStorage.getEmpty()` 的空壳实现 net 为 null |
| 2 | 总开关 `enableEmcDeposit` | 玩家可整体关闭 |
| 3 | `LoadingGuard.isLoading()` | **读档中不折算**（风险 R1，数据损坏级） |
| 4 | `!EmcAvailability.isReady()` | EMC 表未构建时价格必为 0，显式判一次语义更清晰 |
| 5 | `key instanceof ItemStackKey` | 只处理物品；流体/能量/EMC 自身原样通过 |
| 6 | 组件策略 `convertComponentItems` | 默认 `false`：附魔/耐久/储能/容器物品不折算（风险 R4） |
| 7 | `sell > 0` | 无 EMC 价值的物品按原逻辑入库（需求 R4） |

**一个必须记住的坑**：第 5 条往后若"什么都不做"，**不能返回空的 KeyAmount**
—— `UnifiedStorage.java:98-99` 会把空结果直接返回给调用方，
界面槽位会认为插入失败而把物品留在原地。必须原样返回当前堆叠 + `cancel=false`。
代码里用 `pass(current)` 这个私有方法把这条约束固定下来。

---

## 3. 设计要点与取舍

### 3.1 为什么折算用官方扩展点而不是 Mixin

`UnifiedStorageBeforeInsertHandler` 是 BD 全仓库**唯一的存入收口**（`UnifiedStorage.java:91`），
且 BD 自己从未注册过任何 handler。所有进网路径（界面点击、背包 shift、批量转移、网络接口、
漏斗、AE2/RS）都收敛到它。因此核心功能**一行 Mixin 都不需要**，
只在"读档守卫"和"知识持久化"两处用了 Mixin（BND 没有提供对应扩展点）。

### 3.2 钩子必须是纯函数

BD **没有把 `simulate` 参数传进钩子**，所以"先模拟后提交"的调用方会让我们按同样条件算两次；
而且 `unzipMatterBall` 内部会**递归**调用 `insert`（`AbstractUnorderedStackHandler.java:624`）。
因此本类不持有任何按调用变化的状态，只做确定性的输入→输出映射。

**唯一的副作用是"学习"**（写 `NetKnowledgeStore`）。它无法区分 simulate，代价见风险 R2：
模拟调用也可能把一个物品记进学习集合。考虑到玩家手里本来就有该物品、
而 ProjectE 的学习本就只需持有物品，这个偏差无实质套利空间，因此**接受**
（要做精确版需要订阅 delta 事件 + 待定队列，复杂度不值当）。

### 3.3 为什么要显式的"反序列化中"守卫，而不是只靠"EMC 表未就绪"

阶段 1 已证明：EMC 表要等玩家登录才构建，而读档发生在登录之前，
所以常规读档时"价格查出来是 0"这道天然防线已经生效。但仍有一个漏洞：

`DimensionsNet.getNetFromId` → `computeIfAbsent` 会在**玩家已登录之后**按需加载某个网络，
此时 EMC 表已就绪，存档里若有"带 EMC 价值的物品"就会被折叠算。
正常存档不会出现（存入时已折算掉），但**迁移场景**（给已有存档加装本模组）会。

### 3.4 `require = 1` 的唯一一处例外

全项目的 Mixin 策略是 `injectors.defaultRequire = 0`（优雅降级，风险 R8），
但 `AbstractUnorderedStorageLoadMixin` **刻意用了 `require = 1`**：

> 其余 Mixin 静默失效的后果是"功能退化"（学习列表丢失）；
> 而**本守卫静默失效的后果是"数据损坏"**（每次读档重写玩家存档）。
> BD 哪天改了方法名，宁可启动时响亮地崩，也不要安静地毁存档。

---

## 4. 我在这轮犯的两个错误（记录在案）

1. **把"沙箱故障"当成了既成事实并据此申请提权。**
   实际那次申请的用途是"枚举并杀掉沙箱外的 java 进程"，而不是修沙箱 ——
   沙箱本身在 2026-09-27 已修复（详见 `docs/development/environment-notes.md` §7）。
   正确做法是：停止开发服务端用 `tools\gradlew-here.cmd --stop`，
   整条命令因此不需要 CIM、也就不需要提权。
   后文 §5 已把这条写成工作流规则。

2. **第一版守卫自检有盲区**：只测了 `LoadingGuard` 自身，没测 Mixin 是否生效（见 §2.1）。
   补测之后才真正闭环。

---

## 5. 沙箱内启动/停止开发服务端的正确做法（新增工作流规则）

```powershell
# 启动（日志写文件，便于轮询）
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "cmd.exe"
$psi.Arguments = '/c tools\gradlew-here.cmd runServer --console=plain > run\verify.log 2>&1'
$psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
[System.Diagnostics.Process]::Start($psi) | Out-Null

# 停止
tools\gradlew-here.cmd --stop
```

**不要**用 `Get-CimInstance Win32_Process` / `tasklist` 去枚举并杀 java 进程：

- 沙箱令牌是 Low 完整性受限令牌，**看不到也不该管**沙箱外的进程 ——
  "看不到 java 进程"**不代表**没有 Gradle 守护进程（`environment-notes.md` §7 有实测记录）。
- 杀外部进程既越权、又不可靠，还让整条命令被迫申请提权。
- `--stop` 停止守护进程即可，通常会连带结束 `runServer` 的子 JVM。
- 停止后用一个 TCP 连接探测端口（而不是枚举进程）来确认服务端真的退了：

```powershell
$c = New-Object System.Net.Sockets.TcpClient
try { $t = $c.ConnectAsync('127.0.0.1', 25565); if ($t.Wait(3000) -and $c.Connected) { "仍占用" } else { "已释放" } }
catch { "已释放" } finally { $c.Close() }
```

（`--stop` 会中断正在执行的 runServer 构建，因此 Gradle 会报一次 FAILURE —— 属预期，不是错误。）

---

## 6. 遗留：完整折算路径**无法无头验证**

阶段 1 已实测：专用服务器**无玩家时 EMC 表永远为空**（`EMCRemapEvent` 不触发）。
因此"折算真的发生"这条路径在无头环境里走不到 —— 自检对它的断言是**自适应**的：
未就绪时断言"原样通过"（无头环境的常态），就绪时才断言"折算成 EMC"。

**需要你在游戏内确认一次**，步骤：

1. `tools\gradlew-here.cmd runClient`
2. 单机新建或载入一个世界（**有玩家之后 EMC 表才会构建**；
   日志里应出现 ProjectE 的 `Registered N EMC values` 与我们的 `收到 EMCRemapEvent`）
3. 用超越维度的方式创建/加入一个维度网络
4. `/beyondemc selftest` —— 此时"完整折算"分支会被走到，预期仍是 8/8
5. `/beyondemc emc query` —— 记下初始值（应为 0）
6. 把一组钻石放进网络
7. `/beyondemc emc query` —— 应等于 `64 × 回收价`（回收价见第 4 步输出）
8. `/beyondemc knowledge list` —— 应包含钻石
9. 保存退出、重进世界 —— 第 7 步的数值应**不变**（这是需求 R1 的持久化往返验证）
10. 按 `O` 打开网络界面 —— 应能看到一行 EMC（阶段 5 的渲染实机验证，同时确认行内容正确）

第 6 步同时会验证一个附带行为：**手里拿着物品右键点击 EMC 行会把该物品存进去并折算**
（阶段 2 §3.1 从源码推出的结论）。

---

## 7. 阶段 3 验收标准

| # | 标准 | 结论 |
|---|---|---|
| 1 | 折算钩子注册成功，且不干扰其它资源类型 | ✅ 钩子路由自检 |
| 2 | 读档期间不折算（风险 R1） | ✅ 守卫 + **Mixin 生效性**双双验证 |
| 3 | EMC 表未就绪时不折算 | ✅ |
| 4 | 无 EMC 价值的物品按原逻辑入库 | ⚠️ 逻辑已实现（`sell <= 0` 放行），实机验证见 §6 |
| 5 | 存入后物品并入网络学习集合（R3） | ✅ 集合逻辑 + NBT 往返 + 合并；实机验证见 §6 |
| 6 | 学习集合随网络存档持久化 | ✅ NBT 往返 3/3；真实存档往返见 §6 |
| 7 | 组件物品默认不折算（R4） | ✅ 逻辑已实现（`convertComponentItems=false`），**边界物品矩阵待阶段 6 逐项实测** |
| 8 | 网络合并时学习集合一并合并 | ✅ |
| 9 | 8 项无头自检通过 | ✅ |
| 10 | **完整折算 + 真实存档往返的实机确认** | ⏳ 需你在游戏内跑一遍 §6 的清单 |

---

## 8. 下一步（阶段 4）需要注意

1. `NetEmcAccessor.saturatingMultiply` / `spendEmc` 已就位，兑换的扣费直接用。
2. **兑换必须服务端重算价格与余额**，客户端传来的只有物品与数量。
3. **兑换前必须查 `NetKnowledgeStore.knows(net, persistentInfo)`** —— 这是"只允许兑换已学习物品"的权威判断。
4. 权限口径（风险 R11 / Spike S3）仍需在阶段 4 确认 BD 原生取出物品的门槛。
5. `EmcAvailability` 同时是阶段 6 的价格缓存失效钩子（风险 R13）。
