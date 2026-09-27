# 实机确认手册（阶段 3 / 4 / 5 遗留项一次跑完）

> 目的：把无头环境验证不到的部分一次性确认掉。
> 无头环境**永远测不到**两件事：① EMC 表要等玩家登录才构建；② 界面渲染与点击交互。
> 预计耗时：**15–25 分钟**（不含启动与建世界）。

---

## 0. 为什么必须由你在游戏里跑

阶段 1 实测过一个关键事实：**专用服务器在无玩家时，ProjectE 的 EMC 表永远是空的**
（`EMCRemapEvent` 从不触发，钻石 EMC 恒为 0）。因此：

| 测不到的东西 | 原因 |
|---|---|
| 折算真的发生（`64 × 回收价`） | `getSellValue` 在表就绪前恒为 0，折算门禁会原样放行 |
| 兑换的报价、扣费、背包预检 | 同上（`getValue` 为 0） |
| 虚拟条目显示、点击兑换、渲染 | 需要客户端渲染与鼠标交互 |

自检里的断言写成了**自适应**的：未就绪时验证"拒绝路径"，就绪后自动切换成验证"完整路径"。
所以你在游戏里跑 `/beyondemc selftest`，会看到原来 SKIP 的项变成 OK —— 那本身就是一条结论。

---

## 1. 准备

### 1.1 启动客户端

**方式 A（推荐，双击即可）**：在资源管理器里双击

```
tools\play.cmd
```

它会自动设置 `JAVA_HOME`、复用项目内的 Gradle 缓存、然后启动客户端，
结束时**不会立刻关窗**（方便你看报错）。

> 等 Gradle 配置 + NeoForge 准备完成后，游戏窗口才会出现（首次约 30–90 秒）。
> **这个控制台窗口在游戏运行期间不要关**，关掉就等于结束游戏。

**方式 B（终端）**：

```powershell
cd C:\mc\mcwj\mod-learn
tools\gradlew-here.cmd runClient
```

> ⚠️ **不要双击 `tools\gradlew-here.cmd`** —— 它是"需要参数"的包装器：
> 双击时没有参数，等价于运行无任务的 Gradle（只打一段帮助就退出），
> 而且它末尾没有 `pause`，窗口瞬间关闭，看起来就像"没反应"。
> 记录见 `docs/development/environment-notes.md` §8。

### 1.2 建一个干净世界

- **新建超平坦 + 创造模式**世界（创造模式方便 `/give`，超平坦加载快；干净世界便于隔离问题）
- 进世界后执行：

```
/gamemode creative
/give @s beyonddimensions:net_creater 1
```

`beyonddimensions:net_creater` 是 BD 的**网络创建器**。右键使用它创建你的维度网络
（若物品提示与此不同，按物品自身提示操作即可）。

### 1.3 【必做】确认 EMC 表已就绪

**这是后面所有测试的前提。** 打开日志，确认出现这一行：

```
[BeyondEMC] 收到 EMCRemapEvent（第 1 次），EMC 表已就绪：钻石 购买价=…，回收价=…
```

同时 ProjectE 应该也打过一行 `Registered N EMC values`。

**如果没有这行**，后面所有数值都会是 0，测试无意义 —— 请先把这一步的日志发我。

### 1.4 日志抓取命令（随时可用）

```powershell
# 只看我们的日志
Select-String -Path run\logs\latest.log -Pattern 'BeyondEMC' | ForEach-Object { $_.Line }

# 实时跟随
Get-Content run\logs\latest.log -Wait -Tail 20
```

日志文件：`run\logs\latest.log`

---

## 2. 测试步骤

> 每一步都写了**期望**和**要记录什么**。有一步不符合预期就停下来，把原文发我，不用继续。

### A 组：折算与学习（阶段 3 遗留）

| # | 操作 | 期望 | 记录 |
|---|---|---|---|
| A1 | `/beyondemc ping` | 打印 购买价 / 回收价；`EMC表就绪=true` | **购买价 = ____，回收价 = ____** |
| A2 | `/beyondemc selftest` | 阶段 3 应为 **8/8**；阶段 4 应变成 **5 项通过、0 跳过** | 截图或原文 |
| A3 | `/beyondemc emc query` | 余额 = 0（新网络） | **存入前余额 = ____** |
| A4 | `/give @s minecraft:diamond 64`，把这 64 个钻石**放进网络**（打开界面按 `O`，把钻石拖/放进网络存储区；或手持钻石按 BD 的"存入手中物品"快捷键） | 钻石**不进入库存**；余额增加 `64 × 回收价` | **存入后余额 = ____** |
| A5 | `/beyondemc knowledge list` | 列表里有 `minecraft:diamond` | 是 / 否 |
| A6 | `/beyondemc emc query` | 与 A4 记录一致（没有被重复折算） | 一致 / 不一致 |
| A7 | `/give @s minecraft:stone 64`，同样存进网络 | 石头**正常进库存**（BD 原生行，数量 64），余额**不变** | **余额 = ____（应与 A4 相同）** |
| A8 | `/give @s minecraft:diamond_sword 1` 并附魔（用 `/enchant @s minecraft:sharpness 5` 后丢出再捡起），存进网络 | **不折算**，作为物品进库存 | 是否按预期 |

> A8 验证的是"组件物品默认不折算"（配置 `convertComponentItems=false`）——
> 折算会不可逆地销毁附魔信息，所以默认保护。若你**想**让它折算，改
> `run/config/beyondemc-server.toml` 里的 `convertComponentItems=true` 后重启游戏。

### B 组：兑换服务（阶段 4 遗留）

| # | 操作 | 期望 | 记录 |
|---|---|---|---|
| B1 | `/beyondemc exchange minecraft:diamond 1` | 成功换出 1 个钻石；余额减少 `购买价` | **兑换后余额 = ____** |
| B2 | `/beyondemc exchange minecraft:diamond 999999` | 被拒（"背包空间不足"或"EMC 不足"）；**余额不变** | 拒绝理由 = ____ |
| B3 | `/beyondemc exchange minecraft:stone 1` | 被拒（理由应含"尚未被这个网络学会"） | 拒绝理由 = ____ |
| B4 | 先把背包塞满，再 `/beyondemc exchange minecraft:diamond 1` | 被拒；**余额不变**（不能先扣钱再发现装不下） | 拒绝理由 = ____ |
| B5 | **防刷**：记下余额 → 换出 1 个钻石 → 把钻石存回网络 | 余额**不得增加**。默认 `covalenceLoss=1.0` 时应**回到原值** | **换回后余额 = ____** |
| B6 | `/beyondemc knowledge clear` 后 `/beyondemc exchange minecraft:diamond 1` | 被拒（"尚未被这个网络学会"） | 拒绝理由 = ____ |

> **B5 的期望值已更正**：早先我写的是"必然亏损"，那是**错的**。
> ProjectE 的 `covalenceLoss` 默认 `1.0`（`ServerConfig.java:147`），默认配置下买卖**同价**，
> 循环是**中性**的。防刷的实质是"不存在净收益路径"（因为 `covalenceLoss ∈ [0.1,1.0]`
> 恒保证 `购买价 ≥ 回收价`），而不是"必然亏损"。

### C 组：界面注入（阶段 5）—— **本次最重要的一组**

> 前提：A5 已确认学习集合里有钻石，且余额 ≥ 一个钻石的购买价。

| # | 操作 | 期望 | 记录 |
|---|---|---|---|
| C1 | 按 `O` 打开网络界面 | 界面正常打开 | 是 / 否 |
| C2 | **看日志**是否出现：`[BeyondEMC] 虚拟条目注入（第 N 次）：注入 X 条，余额 Y，已学习 Z 项，跳过无价格 W 项` | **必须出现** | 原文 = ____ |
| C3 | 界面列表里能否看到钻石那一行 | 能看到；数量 = `floor(余额 ÷ 购买价)` | 显示数量 = ____，按公式算 = ____ |
| C4 | **左键点击**钻石那一行 | 换出 1 个钻石，余额减少 `购买价`，动作栏提示"换出 1 个…" | 成功 / 失败 + 提示原文 |
| C5 | **Shift + 左键**点击 | 换出**一组**（该物品的原版最大堆叠数，钻石即 64），不超过可兑换数量；**屏幕上不应出现任何弹窗** | 换出数量 = ____ |
| C6 | 在搜索框输入一个无关词（如 `zzz`） | 虚拟条目被过滤掉 | 是 / 否 |
| C7 | 清空搜索框，**真的往网络里存一些钻石** | 钻石**只剩一行**（BD 原生库存行），不再有虚拟条目 | 是 / 否 |
| C8 | 把余额花到买不起 1 个钻石（可用 `/beyondemc emc spend <大数>`） | 钻石那一条**消失**（而不是显示 0） | 是 / 否 |
| C9 | 关闭界面 → **按住 Shift 不放** → 按 `O` 打开 | 虚拟条目**不应**变空/消失（这是第二个注入点存在的理由） | 正常 / 异常 |
| C10 | 关闭界面再打开 | 一切正常（学习集合会重新同步） | 正常 / 异常 |

**C2 是这一组的关键判据**：三个 Mixin 都是 `require = 0`（优雅降级），
注入失败**不会报错**，所以"界面没崩"不能说明注入生效——只有那行日志能说明。

### D 组：持久化往返（阶段 3 的硬性验收）

| # | 操作 | 期望 | 记录 |
|---|---|---|---|
| D1 | 记下当前 `/beyondemc emc query` 的余额 与 `knowledge list` 的条目数 | — | **退出前：余额 = ____，学习 = ____ 项** |
| D2 | 保存并退出到主菜单，再重新进入该世界 | — | — |
| D3 | `/beyondemc emc query` 与 `/beyondemc knowledge list` | **与 D1 完全一致** | **重进后：余额 = ____，学习 = ____ 项** |
| D4 | 再看日志 | 应出现 `[BeyondEMC] 读回网络 <id> 的已学习物品 N 项` | 原文 = ____ |

> D 组是风险 R1 的最终验证：如果读档会重写存档，这里的余额会**变大**（物品被再折算一遍）。
> 理论上已被两道防线挡住（EMC 表就绪门禁 + `LoadingGuard`），但只有真实存档往返能证明。

### E 组：边界物品（阶段 6 前置，顺手做最省事）

对每个物品：`/give @s <物品>` → 存进网络 → 观察是"变成 EMC"还是"作为物品入库"。

| 物品 | 期望行为 | 实际 |
|---|---|---|
| 石头 `minecraft:stone`（无 EMC） | 作为物品入库 | |
| 钻石 `minecraft:diamond` | 折算成 EMC | |
| 附魔剑（锋利 V） | **不折算**（默认保护组件） | |
| 用过的钻石镐（有耐久损耗） | **不折算** | |
| 装着东西的潜影盒 | **不折算** | |
| 改过名的物品（铁砧） | **不折算** | |
| 数量极大的测试: `/give @s minecraft:diamond 6400` | 折算，余额正确增加，**不溢出成负数** | |

---

## 3. 你要回报给我的内容（可直接复制填写）

```
### 环境
- 启动方式：runClient
- 日志：run/logs/latest.log
- 是否出现「收到 EMCRemapEvent」：是 / 否
- 是否出现 ProjectE 的「Registered N EMC values」：是 / 否，N = ____

### 数值
- 钻石 购买价 = ____
- 钻石 回收价 = ____
- A3 存入前余额 = ____
- A4 存入 64 钻石后余额 = ____（期望 = 64 × 回收价 = ____）
- A7 存入 64 石头后余额 = ____（期望 = 与 A4 相同）
- B1 兑换 1 钻石后余额 = ____（期望 = A4 − 购买价 = ____）
- B5 换回 1 钻石后余额 = ____（期望 = 与 B1 之前相同；不得更大）

### 自检输出
（把 `/beyondemc selftest` 的完整输出粘在这里）

### 界面（C 组）
- C2 注入日志原文：____
- C3 界面显示数量 = ____（按公式算应为 ____）
- C4 左键点击结果：成功 / 失败，提示原文 ____
- C5 Shift+左键换出数量 = ____
- C6 搜索过滤生效：是 / 否
- C7 有库存后只剩一行：是 / 否
- C8 买不起时条目消失：是 / 否
- C9 Shift 打开界面后条目正常：是 / 否

### 持久化（D 组）
- D1 退出前：余额 ____ / 学习 ____ 项
- D3 重进后：余额 ____ / 学习 ____ 项
- D4 日志原文：____

### 边界物品（E 组）
- 石头：____ / 附魔剑：____ / 耐久镐：____ / 潜影盒：____ / 改名物品：____ / 6400 钻石：____

### 异常
- 是否崩溃：是 / 否；若有，附 run/crash-reports/ 下的文件名
- 任何 FAIL 行或红字原文：____
- 截图：界面截图（有虚拟条目那张）最有帮助
```

---

## 4. 症状 → 可能原因 → 我会怎么处理

| 症状 | 最可能的原因 | 我会做的事 |
|---|---|---|
| 日志没有 `收到 EMCRemapEvent` | 玩家还没完全登录，或 ProjectE 加载异常 | 让你确认 ProjectE 在 mod 列表里，并查它的 `Registered N EMC values` |
| A4 余额没变、钻石进了库存 | 折算门禁把这次放行了（EMC 表未就绪 / 组件策略 / 配置关闭） | 对比 A1 的"EMC表就绪"，检查 `run/config/beyondemc-server.toml` 的 `enableEmcDeposit` |
| A4 余额变了但数字不对 | 用了 `getSellValue` 还是 `getValue` 的问题，或 `covalenceLoss` 非 1 | 用 A1 的回收价算期望值核对 |
| **C2 没有注入日志** | 注入没生效（三个 Mixin 静默降级）——最可能是 `buildIndexList`/`updateViewerStorage` 签名变了 | 换 `require = 1` 定位，或用 `-Dmixin.debug.verbose=true` 重跑看应用日志 |
| C2 有日志但 C3 看不到条目 | `cacheIndexes` 没破掉，或条目被过滤器挡掉 | 检查注入后是否置空缓存；试清空搜索框 |
| C3 数量不对 | 用了购买价还是回收价算错 | 核对公式 `floor(余额 ÷ 购买价)` |
| C4 点击没反应 | `slotClicked` 的 Mixin 没生效，或 `isVirtual` 判定失败 | 在该方法里加临时日志确认是否被调用 |
| C4 提示"服务端拒绝" | 服务端校验没过（未学习 / 余额 / 背包） | 看提示原文，逐条对 B 组的拒绝理由 |
| C7 出现两行（重复） | 库存判定的粒度问题 | 检查 `VirtualEntryProvider` 的 `ItemStackKey` 精确判重（**不要**退回到只看 `Item`，那会让附魔/未附魔混为一谈） |
| C9 Shift 打开后条目空了 | 第二个注入点（`updateViewerStorage` RETURN）没生效 | 检查该注入点 |
| D3 余额变大 | **读档被重写**（风险 R1）——最严重 | 立即停下，检查 `LoadingGuard` 的 Mixin 是否生效（阶段 3 第 8 项自检） |
| 崩溃 | 任何原因 | 给我 `run/crash-reports/` 下的文件名与堆栈 |

---

## 5. 关于"哪些不用你测"

以下已经在无头环境自动验证过，**不需要你重复**：
- 存储层：EMC 资源类型的注册、NBT 往返、网络编解码、溢出保护、余额不足不扣成负数（阶段 2，8/8）
- 折算钩子的路由与四道门禁、学习集合增删查/NBT 往返/合并、**读档守卫的 Mixin 生效性**（阶段 3，8/8）
- 兑换的非法输入（`net=null`/`count<=0`）、未学习的拒绝、极大 count 的安全处理（阶段 4，3 项）
- 编译、服务器启动零 Mixin 报错、载荷注册（阶段 5）

---

## 6. 附：一次性抓取全部证据的命令

跑完测试后，在项目根目录执行，把输出发我：

```powershell
Write-Output "=== BeyondEMC 日志 ==="
Select-String -Path run\logs\latest.log -Pattern 'BeyondEMC' | ForEach-Object { $_.Line }
Write-Output "=== EMC 表相关 ==="
Select-String -Path run\logs\latest.log -Pattern 'EMCRemapEvent|Registered \d+ EMC values|读回网络' | ForEach-Object { $_.Line }
Write-Output "=== 是否有崩溃 ==="
Get-ChildItem run\crash-reports -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 3 Name,LastWriteTime
```
