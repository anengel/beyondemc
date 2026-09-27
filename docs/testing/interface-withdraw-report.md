# 网络接口兑换（自动化向）实测报告

> 日期：2026-09-27 · Minecraft 1.21.1 · NeoForge 21.1.234 · BD 0.7.30 · ProjectE 1.1.0

---

## 1. 需求

> "我希望能用超越维度的网络接口取出已学习的物品，并且正常扣取 EMC。"

把界面里的"用 EMC 兑换物品"能力**开放给自动化**：在网络接口里配置某物品的过滤器后，
即使网络里没有该物品的库存，接口也能产出它并扣除网络 EMC。

---

## 2. 资料调研结论（全部有 `file:line` 证据）

### 2.1 BD 的全部抽取路径收口于一个钩子

`UnifiedStorageBeforeExtractHandler` 是插入钩子的对称版本：

```
extract(int slot, long, boolean)      → extract(key, amount, simulate, false)
extract(TagKey, long, boolean)        → extract(key, amount, simulate, false)
extractByKey(...)                     → extract(key, amount, simulate, fuzzy)
                                              ↓
                             UnifiedStorageBeforeExtractHandler.onBeforeExtract(input, net)
                             （UnifiedStorage.java:105-133）
```

网络接口的自动输出正是其中的一条：

```java
// NetInterfaceAccess.java:127
KeyAmount stack = net.getUnifiedStorage().extract(flag.key(), missing, false, fuzzy);
//                                                          ↑ simulate 硬编码 false
```

### 2.2 接口有内置的"防抽干"设计

```java
// NetInterfaceAccess.java:119-120
KeyAmount flag = fakeStackHandler.getStackBySlot(i);
if (flag.isEmpty()) continue;          // ← 空过滤器 = 完全不抽取
// ...
long missing = flag.key().getVanillaMaxStackSize() - currentAmount;   // 每槽每周期最多一整堆
```

**接口必须显式配置过滤器才会抽取**，且每个槽位每周期最多取一整堆。
这意味着本功能**不存在"接口把网络 EMC 抽干"的隐患** —— 这也是我们把默认值定为
`allowInterfaceWithdraw = true` 的依据。

### 2.3 钩子的致命限制

```java
// UnifiedStorage.java:122-132
var info = UnifiedStorageBeforeExtractHandler.onBeforeExtract(input, net);
if (info.cancel()) return new KeyAmount(input.key(), 0);
KeyAmount adjusted = info.beforeExtract();
if (adjusted.isEmpty()) return adjusted;
return super.extract(adjusted.key(), adjusted.amount(), simulate, fuzzy);   // ← 真实抽取
```

钩子返回的 `KeyAmount` 会被交给 `super.extract(...)` **真实抽取** ——
也就是说钩子**只能改"抽什么"，不能凭空造出库存里没有的东西**。

---

## 3. 设计：先铸造、再让原生抽取取走

```
① 钩子里判定：模拟？→ 放行（不扣费）
              有真实库存？→ 放行（走原生）
              已学习 & 有价 & 买得起？→ 继续
② 扣 EMC（按购买价 × 数量）
③ 用 MintingGuard 包着把物品 insert 进存储   ← 守卫让"存入折算钩子"放行
④ 返回 new KeyAmount(key, minted)，随后的 super.extract 把它取走
────────────────────────────────────────────
净效果：存储不变，EMC 减少，物品交给调用方
```

### 3.1 为什么必须要有 `MintingGuard`

第 ③ 步若不设防，那件物品会被**我们自己的存入折算钩子**当场按回收价折算回 EMC ——
于是"按购买价扣费、又按回收价退回"，兑换自我抵消。

这与读档守卫（`LoadingGuard`）是同一套模式：用一个线程内可见的显式标记，
让钩子知道"这次插入不是玩家存的"。

### 3.2 为什么必须要有 `ExtractContext`

`BeforeExtractHandler#beforeExtract(originalExtract, tryExtract, net)` 的签名
（`UnifiedStorageBeforeExtractHandler.java:32-36`）**没有 `simulate` 参数**，
而它的调用点有。

这个区分是**安全关键**：外部模组会大量发起**模拟抽取**（能力查询、
`IItemHandler.extractItem(..., true)`、漏斗预热等）。若在模拟时也扣 EMC，
一次能力查询就会真扣玩家的钱。

所以由 `UnifiedStorageExtractMixin` 在进入/离开
`extract(IStackKey, long, boolean, boolean)` 时维护这个标志。

---

## 4. 安全性质

| 性质 | 如何保证 |
|---|---|
| **模拟抽取绝不扣费** | `ExtractContext.isSimulate()` 为真时直接放行；且其默认值**保守判为模拟** |
| **Mixin 失效时不会错扣钱** | 若抽取 Mixin 未应用，深度恒为 0 → `isSimulate()` 恒真 → 钩子一律放行 → **功能静默关闭，但绝不扣错钱**（安全失败模式） |
| **不会把网络抽干** | 接口空过滤器不抽取（`NetInterfaceAccess.java:120`）；每槽每周期最多一整堆 |
| **必须已学习** | 除非配置显式关闭 `exchangeRequiresKnowledge` |
| **无套利** | 按**购买价**买、按**回收价**卖；ProjectE 恒有 `购买价 ≥ 回收价` |
| **绝不先扣钱不交货** | 铸造不满额时按未交付部分原价退回；扣费失败立即回滚 |
| **买不起时拒绝而非交半份** | 数量 = `min(请求量, 余额 ÷ 购买价)`；为 0 则 `cancel` |

---

## 5. 自检结果

```
[BeyondEMC] ---- 网络接口兑换（自动化向）----
[BeyondEMC] OK   铸造守卫：进入前=false → 外层=true → 内层退出后仍=true → 全部退出=false
                 （保证接口兑换铸造的物品不会被存入折算钩子变回 EMC）
[BeyondEMC] OK   抽取上下文：无上下文时【保守判为模拟】（不扣费）——宁可功能不生效，也绝不错扣玩家的 EMC
[BeyondEMC] OK   抽取 Mixin 与 simulate 真值表：extract() 进入上下文 2 次；
                 模拟抽取 → 钩子看到 simulate=true（不扣费），真实抽取 → simulate=false（可扣费）
[BeyondEMC] OK   配置：allowInterfaceWithdraw=true（网络接口可兑换）。
                 注意接口必须显式配置过滤器才会抽取，故不存在"把网络抽干"的隐患
[BeyondEMC] SKIP 接口兑换的完整链路（扣费 + 铸造 + 取出）需要 EMC 表就绪，须在游戏内用网络接口验证
[BeyondEMC] ---- 网络接口兑换自检结果：4/4 项通过 ----
```

**第 3 项是这一组里最有价值的断言**：它同时证明了"抽取 Mixin 真的应用了"
（否则 `enterCount` 为 0）与"simulate 标志能正确到达钩子"。
没有它，功能失效与扣错钱这两种相反的故障都会被同一句"没反应"掩盖。

累计自动检查：**27 项**（阶段 2 `8/8` + 阶段 3 `8/8` + 阶段 4 `3+2` + 阶段 6 `4/4` +
网络接口兑换 `4/4`）。

---

## 6. 过程中踩到的一个致命错误（值得记住）

第一次实现给 Mixin 写的是 `method = "extract"`，结果**服务器直接启动崩溃**：

```
InvalidInjectionException: Invalid descriptor on UnifiedStorageExtractMixin
  @Inject::beyondemc$enterExtract(Lcom/...IStackKey;JZZ...)V!
  Expected (IJZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V
  but found (Lcom/...IStackKey;JZZL...)V
...
MixinTransformerError: An unexpected critical error was encountered
```

原因：`UnifiedStorage` 有**三个同名 `extract` 重载**
（`(int,long,boolean)`、`(IStackKey,long,boolean,boolean)`、`(TagKey,long,boolean)`）。
只写方法名时 Mixin 匹配到了 3 参数的那个，于是认为我们的 handler 签名不合法。

修法：**写完整描述符**（名字 + 参数 + 返回类型）。

### 教训：`require = 0` 的覆盖范围被高估了

本项目的架构文档一直把 `require = 0` 描述成"优雅降级"。**这不完整**：

> `require = 0` 只覆盖「**注入点找不到**」这一种情况。
> handler 的**签名/描述符错误**是硬错误 —— 无论 `require` 设成什么，
> 都会抛 `InvalidInjectionException` 并中断启动。

这条已补进 `docs/design/architecture.md` 与风险 R8 的描述。

---

## 7. 需要的实机验证

- [ ] 网络**没有**某物品库存，但已学会它（先存过一次）→ 在网络接口里配置该物品为过滤器
      → 接口应产出该物品，且网络 EMC 相应减少（按购买价）
- [ ] 网络 EMC 不足时 → 接口**不产出**，EMC 不变（日志：`余额 … 不足以兑换 …，本次抽取被拒绝`）
- [ ] 接口**不配置过滤器** → 什么都抽不出来（BD 原生行为，不应有任何 EMC 变化）
- [ ] 网络**有**真实库存时 → 走原生抽取，**不扣 EMC**（需求 R6 的一致性）
- [ ] **模拟抽取不扣费**：用漏斗/管道对着接口做"只看不拿"的查询（或反复开关红石），
      EMC 不应变化
- [ ] `allowInterfaceWithdraw=false` → 接口只输出真实库存，EMC 完全不变
- [ ] 长时间运行：接口配在过滤器上跑几分钟，EMC 的下降速度应与产出速度**严格成比例**
      （单价 × 数量），不多扣
- [ ] 日志里 `接口兑换：<物品> ×N → 扣除 <M> EMC（单价 P，网络 id）` 的数字应满足 `M = N × P`

---

## 8. ⚠️ 刷物品漏洞及其修复（用户实测发现）

### 8.1 反馈

> "有个重大隐患，就是有组件的物品会消耗 EMC 从网络接口取出，这样会导致刷物品。"

**这是本项目发现的最严重问题** —— 不是数据丢失，而是**价值凭空产生**。

### 8.2 根因：定价的身份 ≠ 铸造的对象

兑换的判定链是：

```
① 归一化：info = getPersistentInfo(fromStack(请求的物品))
② 用 info 查"网络是否已学会"
③ 用 info 查购买价
④ 扣费，然后铸造出……【请求的物品】      ← 错在这里
```

`getPersistentInfo` 会**剥离不影响价值的组件**。所以请求"附魔钻石剑"时：

| 步骤 | 实际发生 |
|---|---|
| ① | info = **普通钻石剑**（附魔默认不参与计价） |
| ②③ | 按**普通钻石剑**判定已学习、按 8192 计价 |
| ④ | 铸造出**附魔钻石剑** —— 价值远超 8192 |

净效果：**按普通物品的价格买出高价物品**，也就是刷物品。附魔等级越高，凭空产生的价值越大。

自检日志给出了这个机理的直接证据：

```
OK   身份一致性：…（归一化身份=minecraft:diamond_sword，与请求对象不同）
```

一个被改名的钻石剑，其归一化身份就是 `minecraft:diamond_sword` —— 确认组件确实被剥离了。

### 8.3 两条路径都中招，GUI 那条更严重

| 路径 | 触发方式 |
|---|---|
| **网络接口** | 过滤器里放附魔物品即可（用户实测发现） |
| **GUI** | `ExchangeRequestPacket.template` 是**客户端可控**的，改包就能按普通物品价格拿到带组件物品 |

GUI 那条**更严重**：不需要任何游戏内配置，且可以脚本化批量刷。

> 这也说明了一个方法论问题：我最初只按"用户报告的现象"去修接口路径。
> **如果没有顺藤摸瓜检查对称路径，就等于把同一个洞留在了一个更容易被利用的地方。**

### 8.4 修复：让一致性由构造保证

**核心改动**：不再用请求方传来的 `ItemStack` 去铸造，而是用
`info.createStack()` —— **归一化身份重建出的那个堆叠**。
这样"用什么身份定价"与"铸造出什么"在结构上不可能不同。

新增 `exchange/CanonicalExchange` 作为统一收口：

```java
public static @Nullable Resolved resolve(ItemStack requested) {
    ItemInfo info = IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(requested));
    ItemStack canonical = info.createStack();
    // 往返校验：归一化 → 重建 → 再归一化 必须回到同一身份
    if (!info.equals(IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(canonical)))) {
        return null;   // 不稳定就保守拒绝，而不是铸造一个身份不明的对象
    }
    return new Resolved(info, canonical);
}
```

| 路径 | 改法 |
|---|---|
| `ExchangeService.exchange` | `info` 与 `canonical` 都来自 `resolve(template)`；背包预检与发放一律用 `canonical`，**不再碰 `template`** |
| `InterfaceWithdrawService` | ① 结构性：要求过滤器的 key **等于**归一化身份重建的 key，不等就拒绝铸造；② 策略对称：复用 `EmcDepositHandler.skipReason(stack)` |

**第二道闸（策略对称）值得单独说**：它让"能铸造出来的"与"存入时会被折算的"由**同一个函数**判定：

> 既然带组件的物品在存入时不会被折算（也就不会被学习），
> 那就绝不能通过兑换铸造出来。

早先的漏洞正是因为两条路径**各自判断、标准不一致**。复用同一个策略函数后，
两侧永远同源 —— 这也是本项目反复出现的同一条教训：**同一件事不要有两份实现**。

### 8.5 自检（新增第 5 项，决定性断言）

```
OK   身份一致性：普通钻石剑可正常归一化兑换；
     改名/带组件物品【绝不会】被铸造出来（归一化身份=minecraft:diamond_sword，与请求对象不同）
```

这一条同时守住两个方向：

- **不能因为修漏洞把正常路径也拒了**（普通钻石剑仍可兑换）；
- **带组件的请求对象绝不能被原样铸造**（要么归一化失败，要么重建结果与请求对象不同）。

累计自动检查：**28 项**。

### 8.6 教训

> **安全校验用的"身份"与最终执行用的"对象"必须是同一个东西。**
> 当中间存在任何形式的**归一化/转换**（这里是 `getPersistentInfo` 剥离组件），
> 就存在"按 A 校验、执行 B"的缝隙 —— 而这类缝隙正是最典型的提权/复制漏洞来源。

这条与 §8.3 提到的方法论问题合起来是两句话：

1. **校验对象必须就是执行对象**（不留归一化缝隙）；
2. **修一处漏洞时要检查所有对称路径**（否则等于把洞留在更容易利用的地方）。

### 8.7 新增的实机验证项

- [ ] 网络接口过滤器放**附魔钻石剑** → 应**什么都抽不出来**，EMC 不变
      （日志：`过滤器里的物品与网络学会的身份不一致，已拒绝铸造`）
- [ ] 网络接口过滤器放**改名物品** → 同上
- [ ] 网络接口过滤器放**普通钻石剑**（网络已学会）→ 正常产出并扣费
- [ ] GUI：确认普通物品的兑换仍正常（**回归**，防止修复误伤）
- [ ] 网络里有某附魔物品的**真实库存**时，接口仍能把它作为真实库存输出（走原生抽取，不涉及铸造）
