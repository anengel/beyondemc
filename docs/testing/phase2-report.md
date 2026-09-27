# 阶段 2 实测报告：EMC 资源类型与存储验证

> 日期：2026-09-27 · 环境：Windows 11 · JDK `21.0.12.1` · NeoForge `21.1.234` · MDG `2.0.116`
> 前置：超越维度 `0.7.30` + ProjectE `1.1.0`（本地 jar）
> 本报告只记录**实际跑出来的结果**。所有日志片段均为原文摘录。

---

## 1. 交付物

| 文件 | 说明 |
|---|---|
| `src/main/java/.../emc/EmcType.java` | EMC 的纯数值堆叠包装（对应 `ManaType`） |
| `src/main/java/.../emc/EmcStackKey.java` | 自定义资源类型单例（照抄 `ManaStackKey` 模板） |
| `src/main/java/.../emc/EmcStackKeyRender.java` | 界面渲染器（**仅客户端**，见 §4） |
| `src/main/java/.../emc/EmcRegistration.java` | `FMLCommonSetupEvent` 上的类型注册 |
| `src/main/java/.../emc/NetEmcAccessor.java` | EMC 池读写 + 饱和运算 |
| `src/main/java/.../core/EmcAvailability.java` | `EMCRemapEvent` 监听（阶段 3 守卫 / 阶段 6 缓存失效） |
| `src/main/java/.../diag/EmcStorageSelfTest.java` | 8 项无头自检 |
| `src/main/resources/assets/beyondemc/lang/{en_us,zh_cn}.json` | 本地化 |
| `src/main/java/.../command/BeyondEmcCommands.java` | `/beyondemc ping\|selftest\|emc query/add/spend` |

**已清理的阶段 1 脚手架**：`diag/SelfCheck`、`diag/MixinProbe`、`diag/EmcAvailability`（搬到 `core`）、
`mixin/BeyondDimensionsMixin`、`mixin/DimensionsNetMixin`；`beyondemc.mixins.json` 的列表已清空
（**必须清空**：`required: true` 下引用不存在的类会导致启动崩溃）。

---

## 2. 自检结果：8/8 通过

服务器启动后自动执行（日志原文）：

```
[BeyondEMC] 已注册 EMC 资源类型: beyondemc:stack_type/emc
[BeyondEMC] EMC 表就绪=false，remap 次数=0
[BeyondEMC] OK   注册：StackKeyRegistry 按 id 取回的是同一单例（beyondemc:stack_type/emc）
[BeyondEMC] OK   NBT 编解码：往返后仍是同一单例，编码结果={type:"beyondemc:stack_type/emc"}
[BeyondEMC] OK   网络编解码：往返后仍是同一单例，写入了 25 字节（typeId + 空负载）
[BeyondEMC] OK   存储读写：0 -> +12345 -> 12345 -> -2345 -> 10000
[BeyondEMC] OK   存储 NBT 往返：987654321 完整还原（存档里的 EMC 不会丢）
[BeyondEMC] OK   溢出保护：MAX*2 饱和为 MAX，100*3=300，MAX+5 饱和为 MAX
[BeyondEMC] OK   余额不足：请求扣 999、实际只扣 100，余额归零且不为负
[BeyondEMC] OK   渲染器端侧约束：服务端调用 getRender() 如期抛出 RuntimeException
[BeyondEMC] ---- 阶段 2 自检结果：8/8 项通过 ----
```

### 2.1 最重要的一项

**「存储 NBT 往返：987654321 完整还原」** —— 这证明自定义 `IStackKey` 能活着穿过
`AbstractUnorderedStackHandler.serializeNBT/deserializeNBT`。这是本阶段最大的风险点：
类型注册若出问题，BD 会在反序列化时 `catch(Throwable)` **静默丢弃条目**
（`AbstractUnorderedStackHandler.java:889-898`），表现是"EMC 全部消失但没有任何报错"。

### 2.2 顺带钉死的一个 API 语义

`NetEmcAccessor` 的注释里写的"BD 两个方法返回值语义相反"得到了实测确认：

| 方法 | 返回的是什么 | 证据 |
|---|---|---|
| `UnifiedStorage.insert(key, amount, simulate)` | **剩余未插入**的数量 | `DisorderedStackTypedSlot.java:163`；实测 `+12345` 得到 `12345` |
| `UnifiedStorage.extract(key, amount, simulate, fuzzy)` | **实际提取出**的数量 | `DisorderedStackTypedSlot.java:456-457`；实测扣 `2345` 后余 `10000` |

`NetEmcAccessor` 把两者都归一成"实际发生了多少"，避免调用方踩坑。

### 2.3 溢出与负余额

- `Long.MAX_VALUE × 2` 饱和为 `Long.MAX_VALUE`（不静默回绕成负数）
- 余额 100 时请求扣 999 → 实际只扣 100，余额归零且**不为负**

---

## 3. Spike S2 裁决（阶段 2 的分水岭）：EMC 行在 BD 界面里的交互风险

### 结论：**风险解除，方案 A（EMC 作为自定义 `IStackKey`）成立。**

### 3.1 点击行为：安全空操作（静态代码证据）

`DisorderedStackTypedSlot.click` 在「槽位有内容 + 玩家手为空」时是这样的：

```java
// common/menu/widget/slot/DisorderedStackTypedSlot.java:181-195
if (carriedItem.isEmpty())
{
    if (clickStack.key() instanceof ItemStackKey clickKey)
    {
        ... 取出物品 ...
    }
    // ← 没有 else 分支
}
```

**非 `ItemStackKey`（含我们的 EMC）走不进任何分支 → 完全空操作**。
不会崩溃，不会产生垃圾物品。

批量移动路径同理：

```java
// DisorderedStackTypedSlot.java:468（快速移动时）
if (key instanceof ItemStackKey trueItemTypedKey) { ... }
else if (key instanceof FluidStackKey trueFluidTypedKey && ...) { ... }
// ← 其他类型无分支
```

**附带的好处**：手里拿着物品右键点击 EMC 行 → 走正常"存入携带物"路径
（`mayPlace` 分支），正好会触发阶段 3 的折算钩子。这是符合预期的。

### 3.2 渲染：渲染器**必须只在客户端加载**

这里我推翻了自己在阶段 1 写下的一个假设。原假设是：

> `TooltipHelper.readAsCache` 在 `DimensionsNetMenu` 的构造路径上执行（双端都会走），
> 所以渲染器的 `getDisplayName`/`getTooltipLines` 会在服务端被调用。

**实测证伪**——主动在服务端调用 `getRender()`：

```
FAIL 渲染器：java.lang.RuntimeException: Attempted to load class
    net/minecraft/client/multiplayer/ClientLevel for invalid dist DEDICATED_SERVER
```

NeoForge 的运行时 dist 清理器**直接抛异常**（不是 `NoClassDefFoundError`），
因为我们的渲染器实现引用了 `Minecraft` / `ClientLevel`。

**真相**：BD **从不在服务端调用 `getRender()`**。已枚举全部调用点，无一位于服务端：

| 调用点 | 端侧 |
|---|---|
| `client/gui/BDBaseGUI.java:56,86,87` | 客户端（`client/` 包） |
| `common/menu/widget/ClientNetStorage.java:200` | 客户端（该类仅在 `player.level().isClientSide()` 时创建，`DimensionsNetMenu.java:84-87`） |
| `common/menu/widget/ClientNetStorageSearchHelper.java:303` | 客户端 |
| `util/TooltipHelper.java:110` | 客户端 —— 唯一入口是 `DimensionsNetMenu.afterLoadChange()`，而 `SlotGroupSync` 契约把 `loadChange/afterLoadChange` 明确标注为**"仅客户端"**（`DisorderedSlotGroupSync.java:314,346`），唯一调用方是 s2c 包处理器 `DisorderedSlotGroupSyncPacket.java:61-62` |

**兜底**：`TooltipHelper.getTooltipLines` 用 `catch(Throwable)` 包住了 `getRender()`
（`TooltipHelper.java:108-120`），所以万一被误调也只会刷一条 ERROR 日志，不会崩服。
但那是兜底，不是许可。

**处置**：把第 8 项自检改成**哨兵**——主动踩一次并断言它确实抛异常。
一旦哪天它不再抛，说明端侧行为变了，需要复核 BD 的调用点集合。

### 3.3 仍未验证的部分（留给阶段 5）

**客户端实机渲染尚未验证**：EMC 行的图标绘制、数量文本、tooltip 排版都没在真实客户端里看过。
`render` / `renderAmount` / `renderTooltip` 需要 `GuiGraphics`，无头环境测不了。
→ 阶段 5 做界面注入时必须先在客户端实机确认这三条路径。
当前的图标是**临时占位**（双色方块，无贴图资源），阶段 6 换成正式贴图。

---

## 4. 一个刻意的设计选择：不覆写 `getVanillaMaxStackSize()`

`LongStackKey` 的默认实现返回 `Long.MAX_VALUE`，我们没有覆写它。
而 BD 自己的 `ManaStackKey`（`:93-96`）与 `EnergyStackKey` 都把它覆写成了 `1000000`。

**为什么这很重要**：BD 内部多处用 `Math.min(..., key.getVanillaMaxStackSize())` 限流
（例如 `DisorderedStackTypedSlot.java:187` 的取出数量）。若照抄那个常量，
单次存取/兑换会被静默截断在 100 万 —— 也就是架构文档的风险 R6。
自检的"存储 NBT 往返 987654321"间接覆盖了大数值场景。

---

## 5. 环境侧：工作区沙箱故障（2026-09-27 已修复）

从阶段 2 中段开始，**所有 `pwsh` 命令都失败**：

```
Error: SetNamedSecurityInfoW failed (Win32 5): grantWrite(C:\mc\mcwj\mod-learn)
```

连 `Get-Location` 这种无副作用命令也失败，说明是**沙箱自身的 ACL 授权步骤**出的问题
（不是命令的错）。当时的绕过方式是每条命令都申请 `danger-full-access`。

**影响**：开发还能继续，但每条命令都需要一次授权确认，体验很差。

**已于 2026-09-27 修复**：根因有两层 —— ① 工作区根目录缺 `WRITE_OWNER`，而沙箱那一次
`SetNamedSecurityInfoW` 要连 SACL 完整性标签一起写（属主只隐式拥有 `READ_CONTROL + WRITE_DAC`）；
② Low 完整性标签没有传播到修复前就存在的子目录，导致沙箱内写子目录仍被 `no-write-up` 拒绝。
修法是「补 `WRITE_OWNER` + `icacls /setintegritylevel (OI)(CI)L /T`」。
修复后 `pwsh` 在 `workspace-write` 下可直接使用（含沙箱内跑 Gradle，`exit 0`），**不再需要**
`danger-full-access`。完整根因、命令与验证表见 `development/environment-notes.md` §7。

---

## 6. 阶段 2 验收标准

| # | 标准 | 结论 |
|---|---|---|
| 1 | EMC 资源类型注册成功，且能在服务端安全加载 | ✅ |
| 2 | 能对网络 EMC 池做增删查，数值正确 | ✅ |
| 3 | **自定义 key 能活着穿过存储 NBT 往返** | ✅ 987654321 完整还原 |
| 4 | 网络编解码（多人同步路径）可用 | ✅ 25 字节往返 |
| 5 | 溢出不会回绕成负数 | ✅ 饱和处理 |
| 6 | 余额不足不会被扣成负数 | ✅ |
| 7 | **Spike S2：EMC 行的交互风险有明确结论** | ✅ 点击安全（静态证据）；渲染仅客户端（实测约束） |
| 8 | 持久化往返：退出重进后 EMC 不变 | ⚠️ **部分** —— 已用临时网络证明存储层序列化正确；真实存档往返留到阶段 3（与折算钩子一起测，因为那才是"读档会不会重写存档"的完整场景） |

---

## 7. 进入阶段 3 前需要注意

1. **`EmcAvailability` 是阶段 3 折算守卫的核心**，已实现并验证过时机（阶段 1）。
2. **`NetEmcAccessor.saturatingMultiply`** 已就位，阶段 3 的 `单价 × 数量` 直接用它。
3. **不要用 `ServerStartedEvent` 做守卫**（阶段 1 结论），也不要用"反序列化中"以外的弱条件。
4. **渲染器不要在任何服务端路径上被引用**，第 8 项哨兵会监控这一点。
5. `EmcStorageSelfTest` 是诊断代码，阶段 3 起可以精简为 GameTest 或删除；
   但第 8 项哨兵建议保留到阶段 5 客户端验证完成之后。
