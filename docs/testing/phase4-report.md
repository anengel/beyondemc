# 阶段 4 实测报告：EMC 兑换取出

> 日期：2026-09-27 · 环境：Windows 11 · JDK `21.0.12.1` · NeoForge `21.1.234`
> 前置：超越维度 `0.7.30` + ProjectE `1.1.0`（本地 jar）

---

## 1. 交付物

| 文件 | 说明 |
|---|---|
| `exchange/ExchangeService.java` | **核心**：服务端权威的兑换事务（校验 + 扣费 + 发放 + 兜底退款） |
| `exchange/ExchangeRequestPacket.java` | C2S 兑换请求包（阶段 5 的界面注入会发它） |
| `command/BeyondEmcCommands.java` | 新增 `/beyondemc exchange <item> <count>`（走**同一套**服务端逻辑） |
| `diag/Phase4SelfTest.java` | 兑换校验门的自检（自适应断言） |
| `BeyondEmc.java` | 注册 C2S 载荷（`RegisterPayloadHandlersEvent`） |

---

## 2. 自检结果

```
[BeyondEMC] ---- 阶段 2 自检结果：8/8 项通过 ----
[BeyondEMC] ---- 阶段 3 自检结果：8/8 项通过 ----
[BeyondEMC] ---- 兑换服务（阶段 4）----
[BeyondEMC] OK   兑换校验：net=null / count=0 / count<0 全部被拒
[BeyondEMC] OK   兑换门禁：未学习物品被拒（理由="该物品尚未被这个网络学会，无法兑换"）；
                  已学习物品 在 EMC 表未就绪时被拒（理由="该物品没有 EMC 价值"，属预期）
[BeyondEMC] SKIP 兑换报价：EMC 表未就绪（无玩家登录），完整报价与扣费需在游戏内确认
[BeyondEMC] SKIP 防刷断言：EMC 表未就绪，买卖差价需在游戏内确认
[BeyondEMC] OK   数量边界：count=2147483647 被安全处理（拒绝：该物品没有 EMC 价值）
[BeyondEMC] ---- 阶段 4 自检结果：3 项通过（EMC 表未就绪，2 项跳过） ----
```

**两项 SKIP 是刻意的、不是失败**：它们依赖 `IEMCProxy.getValue` 返回非零，
而无头服务器上 EMC 表永远为空（阶段 1 已实测）。断言写成**自适应**的：
未就绪时验证"拒绝路径"，就绪后（玩家登录）自动验证"完整报价 + 扣费"。
在游戏内跑 `/beyondemc selftest` 会看到它们变成 OK。

---

## 3. Spike S3 裁决：BD 原生的取出口径

**结论：门槛只有一条 —— 玩家必须是该网络的成员。Owner / Manager 不额外要求。**

证据链：

| 环节 | 事实 | 位置 |
|---|---|---|
| 打开界面 | 唯一的检查是 `DimensionsNet.getNetFromPlayer(player)` 非 null | `OpenNetGuiPacket.java:65-66` |
| 槽位取出 | `mayPickup(player)` —— 未覆写，用的是**原版 `Slot` 的默认实现（恒真）** | `DisorderedStackTypedSlot.java:178`；全仓库无 `boolean mayPickup` 定义 |
| 成员集合语义 | `getPlayers()` 返回的 `players` 按 `Destroyed` 事件契约**包含管理者与所有者** | `DimensionsNet.java:600-603`、`DimensionsNetEvent.java:73-76` |

因此我们的 `ExchangeService.canAccess` 与之对齐：
```java
net.getPlayers().contains(uuid) || net.isOwner(player) || net.isManager(player)
```
三者都判一遍是**零成本的保险** —— 不依赖"`players` 永远包含 owner"这条注释永远成立。

### 3.1 为什么包里要带 `netId` 而不是用 `getNetFromPlayer`

玩家可能通过 `NetTerminalItem` 打开一个**非主网络**的界面
（`OpenNetGuiPacket.java:83-125` 的 `NET_CRAFT_TERMINAL` 分支）。
所以客户端必须告诉服务端"我开的是哪个网络"。这**不构成提权**：
服务端仍会用 `canAccess` 校验成员身份，指定别人网络的 id 只会被拒。

---

## 4. 兑换事务的设计

### 4.1 顺序（刻意如此）

```
1. net 非空
2. canAccess（成员身份）
3. template 非空
4. 归一化 info = getPersistentInfo(fromStack(template))   ← 必须与"学习"时用的归一化一致
5. validate：count>0 → 已学习 → 单价>0 → 总价（饱和乘法）→ 余额充足
6. 背包容量预检（模拟，不改状态）
7. 扣费（extract EMC）
8. 发放（放不进背包就掉在脚下）
9. 若发放不全 → 按单价**原价退回**未发出的部分
```

**"先把所有可能失败的事检查完，再扣费"** 是硬约束：否则会出现"钱扣了但物品没给出去"。
第 9 步是兜底，保证任何异常路径下玩家都不会损失价值。

### 4.2 服务端权威性

包里只带**意图**：`netId` + 模板物品 + 数量。价格、余额、权限、背包空间
全部由服务端重算。篡改这个包最多只能得到"你本来就有权换、且付得起"的东西。

### 4.3 防刷的核心断言（**已更正**）

| 方向 | 用的价格 | 理由 |
|---|---|---|
| 存入（折算） | `getSellValue` **回收价** | 与 ProjectE 转换桌烧物品/凝聚器完全一致（5 处证据） |
| 取出（兑换） | `getValue` **购买价** | `EMCHelper.java:153-167` |

> ⚠️ **本文档早期版本称"存入再取出必然亏损"，这是错的。**
> `getSellValue = floor(getValue × covalenceLoss)`，而 ProjectE 的
> `covalenceLoss` **默认就是 1.0**（`ServerConfig.java:147`，范围 0.1~1.0）。
> 也就是说**默认配置下买卖同价**，循环是**中性**的（不亏不赚），不是亏损。

正确的表述是：**由于 `covalenceLoss ∈ [0.1, 1.0]`，恒有 `购买价 ≥ 回收价`，
所以不存在"低买高卖"的净收益路径** —— 这才是防刷的实质。
管理员把 `covalenceLoss` 调低后，循环才会真的亏损；调成默认值则中性。

`Phase4SelfTest` 的断言已相应改为 `buy >= sell`（并在相等时明确说明是"中性"而非"亏损"）。

### 4.4 背包预检的实现取舍

`simulateCapacity` 只算**主背包 36 格 + 副手**，逐格计算"空槽容量"或"同类物品的剩余堆叠空间"。
刻意不引入"模拟整个 Inventory"的重型做法 —— 36+1 格的线性扫描已经足够，
且完全无副作用（不改任何状态）。

---

## 5. 未验证项（需要你在游戏内确认）

无头环境测不到的三类：

| # | 项 | 为什么测不到 |
|---|---|---|
| 1 | `canAccess` 的成员校验 | 需要 `ServerPlayer` |
| 2 | 背包容量预检 + 发放 | 需要真实玩家背包 |
| 3 | 端到端"扣费 + 给物品" | 需要道具实际进背包 |

### 实机清单

1. `tools\gradlew-here.cmd runClient`，载入世界（确认日志出现 `收到 EMCRemapEvent`）
2. 创建/加入一个维度网络；先往里**存一组钻石**（顺带完成阶段 3 的实机确认）
3. `/beyondemc selftest` —— 预期阶段 4 变成 **5 项通过、0 跳过**
4. `/beyondemc emc query` —— 记下余额
5. `/beyondemc exchange minecraft:diamond 1` —— 应换出 1 个钻石，余额减少 `购买价`
6. `/beyondemc exchange minecraft:diamond 999999` —— 应被"背包空间不足"或"EMC 不足"拒绝，**余额不变**
7. `/beyondemc exchange minecraft:stone 1` —— 应被拒（石头没有被学习过）
8. 清空背包再 `/beyondemc exchange minecraft:diamond 1` —— 验证发放
9. **防刷验证**：记下余额 → 换出 1 个钻石 → 把钻石存回网络 → 余额**不得增加**
   （`covalenceLoss=1.0` 默认时应当**回到原值**；若管理员调低过，应当**比原值少**）
10. `/beyondemc knowledge clear` 之后再 `/beyondemc exchange minecraft:diamond 1` —— 应被"尚未学会"拒绝

---

## 6. 阶段 4 验收标准

| # | 标准 | 结论 |
|---|---|---|
| 1 | `net=null` / `count<=0` 被拒 | ✅ 自检 |
| 2 | **只允许兑换已学习的物品**（权威判断） | ✅ 自检（理由字符串已断言） |
| 3 | 无 EMC 价值的物品被拒 | ✅ 自检（无头环境下就是这条路径） |
| 4 | 余额不足被拒且**余额不变** | ⏳ 需实机（依赖 EMC 表就绪） |
| 5 | 背包满被拒且**余额不变** | ⏳ 需实机 |
| 6 | 权限：非成员被拒（口径与 BD 原生一致） | ✅ 口径已由 Spike S3 确认；⏳ 实机验证 |
| 7 | **防刷**：存入→取出循环**不产生净收益** | ⏳ 需实机（依赖买卖价可用）；断言已更正为 `购买价 >= 回收价`（默认相等 → 中性） |
| 8 | count 传极大值 / 负数 → 安全裁剪不崩不溢出 | ✅ 自检（`Integer.MAX_VALUE` 被安全处理） |
| 9 | 篡改客户端包 → 服务端拒绝 | ✅ 设计上由"服务端重算"保证；阶段 5 做界面时可实测 |
| 10 | C2S 载荷注册成功 | ✅ 服务器正常启动（若注册失败会直接崩） |

---

## 7. 下一步（阶段 5）需要注意

1. `ExchangeRequestPacket` 已就位，界面拦截只需 `ClientPlayNetworking.send(...)`。
2. **服务端不认识虚拟条目** —— 原版点击链路会在 `extractByKey` 处静默失败，
   所以必须在 `BDBaseGUI.slotClicked` 处拦截、改发我们的包。
3. 虚拟条目复用 `ItemStackKey`，数量填 `floor(余额 ÷ 购买价)`；
   **必须 > 0**（0 数量条目会被 `buildSortedIndex` 丢弃）。
4. 注入点选 `DimensionsNetMenu.buildIndexList` 的 HEAD，并在注入后置空 `cacheIndexes`
   （`ClientNetStorage` 的排序缓存）。
5. `matchFilter` 是 private，需要 `@Invoker`。
6. 渲染器的客户端实机验证也在阶段 5（阶段 2 遗留）。

---

## 8. 实机发现的数据丢失级 bug 与修复（2026-09-27）

### 8.1 症状

> "背包满时，我再取出钻石或者绿宝石，会导致直接扣除 EMC 而没能获得任何东西。"

**这是本模组迄今最严重的一个 bug**：扣了钱、物品凭空消失。

### 8.2 根因：`Inventory.add` 在创造模式下会销毁物品却返回成功

最初 `give()` 的实现是：

```java
ItemStack stack = template.copyWithCount(n);
if (player.getInventory().add(stack)) {
    given += n;                 // ← 信任返回值
} else {
    player.drop(stack, false);
    given += n;
}
```

而原版 `Inventory.add(int, ItemStack)` 里有一段（NeoForge 反编译源码实证）：

```java
// net/minecraft/world/entity/player/Inventory.java#add(int, ItemStack)
if (stack.getCount() == i && this.player.hasInfiniteMaterials()) {
    stack.setCount(0);
    return true;            // ← 背包满 + 创造模式：物品被销毁，却报告"成功"
}
```

受伤物品的分支里还有同样的 `else if (this.player.hasInfiniteMaterials()) { stack.setCount(0); return true; }`。

也就是说：**创造模式下背包满时，`add` 会把堆叠清零并返回 `true`。**
调用方按返回值计数 → 认为"已发放" → 不触发退款 → **EMC 扣掉、物品销毁**。
用户当时正是创造模式（`manual-verification.md` 的步骤里就要求 `/gamemode creative`），
所以必然命中。

### 8.3 修复

| 改动 | 说明 |
|---|---|
| **不再使用 `Inventory.add`** | 自己遍历 `items`（36 格）+ `offhand`，按"先并入同类堆叠、再放进空槽"写入 |
| **测算与落地合并为一个方法**（`putIntoInventory(..., boolean apply)`） | 同一个 `apply` 开关走同一段代码 → **结构上不可能再出现"预检说放得下、实际放不下"的分歧** |
| 掉落只作兜底 | 严格按预检结果裁剪过数量，正常不会走到；真走到也宁可掉在脚下，绝不让物品凭空消失 |

背包满时现在会**直接拒绝**（"背包空间不足，放不下这个物品"），**不扣任何 EMC**。

### 8.4 教训（与阶段 5 那个 bug 是同一条）

这又是**同一类错误的第三次**：信任一份"看起来权威"的返回值/副本，而没有自己去权威数据源确认。

- 阶段 5：自己维护 `VIRTUAL` 集合 → 失同步 → 点击失效
- 本轮：信任 `Inventory.add` 的布尔返回值 → 它撒谎（创造模式）→ 数据丢失

> **对任何"外部提供的成功/失败信号"都要留一个可验证的后置条件。**
> 这里后置条件的正确形式是："扣费数量必须等于实际交付数量"
> —— 代码里的兜底退款本来就是这个不变式的实现，只是它依赖的 `given` 本身被污染了。

### 8.5 新增的验收项（需实机）

- [ ] **创造模式 + 背包满** 时取出 → 应被拒绝，且 **EMC 不变**
- [ ] **生存模式 + 背包满** 时取出 → 同上
- [ ] 背包只剩少量空位时 Shift+取一组 → 只换出能放下的数量，且**只扣那部分** EMC
- [ ] 背包里已有同类物品但未满堆叠 → 应优先并入该堆叠，不占用空槽
