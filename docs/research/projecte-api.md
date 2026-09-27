# ProjectE（等价交换）API 逆向调研报告

> 面向目标：为 **Beyond Dimensions（超越维度）** 开发附属模组
> —— 存入物品时按 EMC 折算进"维度网络"并自动学习；维度网络界面展示"已学习"物品；无库存条目按 EMC 兑换取出。

---

## 目录

1. [环境与版本基线](#1-环境与版本基线)
2. [EMC 查询（`IEMCProxy`）](#2-emc-查询iemcproxy)
   - 2.1 [INSTANCE 与 ServiceLoader 机制](#21-instance-与-serviceloader-机制)
   - 2.2 [getValue / getSellValue / getPersistentInfo 的语义差异](#22-getvalue--getsellvalue--getpersistentinfo-的语义差异)
   - 2.3 [关键结论：物品被"烧掉"时游戏实际给多少 EMC](#23-关键结论物品被烧掉时游戏实际给多少-emc)
   - 2.4 [getValue 对 NBT / 附魔 / 储能物品的加成计算](#24-getvalue-对-nbt--附魔--储能物品的加成计算)
3. [知识能力（`PECapabilities.KNOWLEDGE`）](#3-知识能力pecapabilitiesknowledge)
4. [知识集合的语义（`Set<ItemInfo>`）](#4-知识集合的语义setiteminfo)
5. [事件（`api/event/`）](#5-事件apievent)
6. [同步到客户端](#6-同步到客户端)
7. [玩家 EMC 池](#7-玩家-emc-池)
8. [ProjectE 依赖坐标](#8-projecte-依赖坐标)
9. [转换桌 / 凝聚器的服务端逻辑参考](#9-转换桌--凝聚器的服务端逻辑参考)
10. [对附属模组的实现建议](#10-对附属模组的实现建议)
11. [未确认事项](#11-未确认事项)

---

## 1. 环境与版本基线

| 项 | 值 | 证据 |
| --- | --- | --- |
| 分支 | `mc1.21.1` | `git rev-parse --abbrev-ref HEAD` |
| 最后一次提交 | `f432b0c66837759fb0731c9144dc53176b949c5d`（2026-07-23，`Merge pull request #2440 from DonovanDMC/transmutation-tablet`） | `git log -1` |
| Minecraft | `1.21.1` | `gradle.properties:7`（`minecraft_version=1.21.1`） |
| ProjectE | `1.1.0` | `gradle.properties:6`（`projecte_version=1.1.0`） |
| NeoForge（开发用） | `21.1.148` | `gradle.properties:12`（`neo_version=21.1.148`） |
| NeoForge 最低要求 | `[21.1.119,)` | `gradle.properties:13-14`（注释："This determines the minimum version of NeoForge required to use ProjectE"）+ `gradle.properties:15`（`neo_version_range=[21.1.119,)`） |
| Loader 版本范围 | `[4,)` | `gradle.properties:11`（`loader_version_range=[4,)`） |
| mod id | `projecte` | `src/main/resources/META-INF/neoforge.mods.toml:7`；`src/api/java/moze_intel/projecte/api/ProjectEAPI.java:5` |
| Java | 21 | `build.gradle:127` |

源码布局（与任务描述一致）：

- API 源码：`src/api/java/moze_intel/projecte/api/`
- 实现源码：`src/main/java/moze_intel/projecte/`
- API 是**独立 sourceSet**，并单独打成 `classifier=api` 的 jar：`build.gradle:36-39`、`build.gradle:331-336`。同时主 jar/shadowJar 也会包含 api 输出：`build.gradle:316-321`、`build.gradle:344`。

---

## 2. EMC 查询（`IEMCProxy`）

### 2.1 INSTANCE 与 ServiceLoader 机制

`src/api/java/moze_intel/projecte/api/proxy/IEMCProxy.java:16-22`

```java
public interface IEMCProxy extends ToLongFunction<ItemInfo> {

	/**
	 * The proxy for EMC-based API queries.
	 */
	IEMCProxy INSTANCE = ServiceLoader.load(IEMCProxy.class).findFirst()
			.orElseThrow(() -> new IllegalStateException("No valid ServiceImpl for IEMCProxy found, ProjectE may be absent, damaged, or outdated"));
```

- 机制：Java `ServiceLoader`，通过 `META-INF/services/<接口全限定名>` 查找第一个实现。
- 注册文件（本仓库内确认存在）：
  - `src/main/resources/META-INF/services/moze_intel.projecte.api.proxy.IEMCProxy` → `moze_intel.projecte.impl.EMCProxyImpl`
  - 同目录还有 `...api.codec.IPECodecHelper` → `moze_intel.projecte.impl.codec.PECodecHelper`
  - `...api.components.IComponentProcessorHelper` → `moze_intel.projecte.emc.components.ComponentProcessorHelper`
  - `...api.proxy.ITransmutationProxy` → `moze_intel.projecte.impl.TransmutationProxyImpl`
- **对附属模组的意义**：`IEMCProxy.INSTANCE` 是接口静态字段，ProjectE 不在场时会抛 `IllegalStateException`。**不要把这个字段放在会被强制加载的类里做静态初始化**（例如你的物品注册类），否则缺 ProjectE 时你的 mod 直接崩。正确做法：把 ProjectE 依赖声明为 `required`，或在使用点做懒加载 / 用 `ModList.get().isLoaded("projecte")` 守卫。
- 实现（`src/main/java/moze_intel/projecte/impl/EMCProxyImpl.java:11-24`）：

```java
public class EMCProxyImpl implements IEMCProxy {

	@Override
	public long getValue(@NotNull ItemInfo info) {
		return DataComponentManager.getEmcValue(Objects.requireNonNull(info));
	}

	@Override
	public long getSellValue(@NotNull ItemInfo info) {
		return EMCHelper.getEmcSellValue(getValue(info));
	}
}
```

### 2.2 getValue / getSellValue / getPersistentInfo 的语义差异

| 方法 | 声明位置 | 语义 | 是否含 DataComponent 加成 |
| --- | --- | --- | --- |
| `long getValue(ItemInfo)` | `IEMCProxy.java:152`（抽象） | 物品的 **EMC 值**（= 转换桌里的"售价/购买价"）。文档注释明确写"takes into account bonuses such as stored emc in power items and enchantments"（`IEMCProxy.java:145-146`） | **是** |
| `long getSellValue(ItemInfo)` | `IEMCProxy.java:188`（抽象） | "EMC the stack should yield **when burned by transmutation, condensers, or relays**"（`IEMCProxy.java:185`）——即**回收价** | 是（先算 `getValue` 再打折） |
| `ItemInfo getPersistentInfo(ItemInfo)` | `IEMCProxy.java:198-201`（default） | 把 `DataComponentPatch` 裁剪成"会被存进知识 / 用于凝聚"的那部分；委托给 `IComponentProcessorHelper.INSTANCE.getPersistentInfo(info)` | 不适用（数据归一化） |

重载便捷方法：
- `getValue(ItemLike)` `IEMCProxy.java:97-101`（AIR 返回 0）
- `getValue(Holder<Item>)` `IEMCProxy.java:114-118`
- `getValue(ItemStack)` `IEMCProxy.java:133-136`（空栈返回 0，**不计 stack size**）
- `getSellValue(ItemStack)` `IEMCProxy.java:171-174`
- `hasValue(...)` 系列 `IEMCProxy.java:35-84`，实现为 `getValue(...) > 0`
- `applyAsLong(ItemInfo)` `IEMCProxy.java:154-158` → `getValue`，所以 `IEMCProxy.INSTANCE` 本身可直接当 `ToLongFunction<ItemInfo>` 用（`EMCHelper.java:107` 就传给了 `DataComponentManager.updateCachedValues`）

**关键差异的量级**：`getSellValue = floor(getValue * covalenceLoss)`，见 `src/main/java/moze_intel/projecte/utils/EMCHelper.java:153-167`：

```java
@Range(from = 0, to = Long.MAX_VALUE)
public static long getEmcSellValue(@Range(from = 0, to = Long.MAX_VALUE) long originalValue) {
	if (originalValue == 0) {
		return 0;
	}
	long emc = Mth.lfloor(originalValue * ProjectEConfig.server.difficulty.covalenceLoss.get());
	if (emc < 1) {
		if (ProjectEConfig.server.difficulty.covalenceLossRounding.get()) {
			emc = 1;
		} else {
			emc = 0;
		}
	}
	return emc;
}
```

配置默认值（`src/main/java/moze_intel/projecte/config/ServerConfig.java:146-149`）：

```java
covalenceLoss = CachedDoubleValue.wrap(config, PEConfigTranslations.SERVER_DIFFICULTY_COVALENCE_LOSS.applyToBuilder(builder)
		.defineInRange("covalenceLoss", 1.0, 0.1, 1.0));
covalenceLossRounding = CachedBooleanValue.wrap(config, PEConfigTranslations.SERVER_DIFFICULTY_COVALENCE_LOSS_ROUNDING.applyToBuilder(builder)
		.define("covalenceLossRounding", true));
```

> **重要**：`covalenceLoss` 默认 **1.0**（范围 `[0.1, 1.0]`），此时 `getSellValue(item) == getValue(item)`（除了取整边界）。只有在服主把"共价损失"调低时两者才不同。
> 另外 `getEmcTextComponent`（`EMCHelper.java:169-198`）在 `covalenceLoss == 1.0` 时只显示一个数值，否则显示 "EMC / Sell" 两个数值——即 **ProjectE 自己的 UI 就是把 `getValue` 当"购买价"、`getSellValue` 当"回收价"**。

`getPersistentInfo` 的真实实现（`src/main/java/moze_intel/projecte/emc/components/DataComponentManager.java:40-54`）：

```java
@NotNull
static ItemInfo getPersistentInfo(@NotNull ItemInfo info) {
	if (!info.hasModifiedComponents() || info.getItem().is(PETags.Items.DATA_COMPONENT_WHITELIST) || EMCMappingHandler.hasEmcValue(info)) {
		//If we have no custom Data Components, we want to allow data components to be kept, or we have an exact match to a stored value just go with it
		return info;
	}
	//Cleans up the tag in item to reduce it as much as possible
	DataComponentPatch.Builder builder = DataComponentPatch.builder();
	for (IDataComponentProcessor processor : processors) {
		if (MappingConfig.isEnabled(processor) && processor.hasPersistentComponents() && MappingConfig.hasPersistent(processor)) {
			processor.collectPersistentComponents(info, builder);
		}
	}
	return ItemInfo.fromItem(info.getItem(), builder.build());
}
```

三个短路条件（**任一成立就直接原样返回**）：
1. `!info.hasModifiedComponents()` —— 没有改动任何 DataComponent；
2. `info.getItem().is(PETags.Items.DATA_COMPONENT_WHITELIST)` —— 物品在 `data_component_whitelist` 标签里。该标签在当前 datagen 输出中为**空**（`src/datagen/generated/data/projecte/tags/item/data_component_whitelist.json:1-3` → `{"values": []}`）；
3. `EMCMappingHandler.hasEmcValue(info)` —— 该 `ItemInfo`（含完整组件）**在 EMC 映射表里有精确条目**。

否则就用"有持久化意义的 processor"重建一个最小 patch。

### 2.3 关键结论：物品被"烧掉"时游戏实际给多少 EMC

**结论：烧掉给的是 `getSellValue`，不是 `getValue`。** 三处实现证据：

**(a) 转换桌"学习/消耗槽"（`SlotConsume`）** —— `src/main/java/moze_intel/projecte/gameObjs/container/slots/transmutation/SlotConsume.java:25-37`

```java
@Override
public void set(@NotNull ItemStack stack) {
	if (inv.isServer() && !stack.isEmpty()) {
		inv.handleKnowledge(stack);
		inv.addEmc(BigInteger.valueOf(IEMCProxy.INSTANCE.getSellValue(stack)).multiply(BigInteger.valueOf(stack.getCount())));
		this.setChanged();
	}
}

@Override
public boolean mayPlace(@NotNull ItemStack stack) {
	return IEMCProxy.INSTANCE.hasValue(stack) || stack.is(PEItems.TOME_OF_KNOWLEDGE);
}
```

**(b) 转换桌 shift-点击烧物品（`TransmutationContainer.quickMoveStack`）** —— `src/main/java/moze_intel/projecte/gameObjs/container/TransmutationContainer.java:179-188`

```java
//Else if we failed to do that also, transfer to the learn slot if the item has EMC
long emc = IEMCProxy.INSTANCE.getSellValue(stackToInsert);
if (emc > 0 || stackToInsert.getItem() instanceof Tome) {
	if (transmutationInventory.isServer()) {
		BigInteger emcBigInt = BigInteger.valueOf(emc);
		transmutationInventory.handleKnowledge(stackToInsert);
		transmutationInventory.addEmc(emcBigInt.multiply(BigInteger.valueOf(stackToInsert.getCount())));
	}
	currentSlot.set(ItemStack.EMPTY);
}
```

**(c) 凝聚器（Condenser / CondenserMK2）与继电器（Relay）** —— 全部用 `getSellValue`：

| 位置 | 代码 |
| --- | --- |
| `src/main/java/moze_intel/projecte/gameObjs/block_entities/CondenserBlockEntity.java:145-153` | `forceInsertEmc(IEMCProxy.INSTANCE.getSellValue(stack), EmcAction.EXECUTE);`（第 150 行） |
| `src/main/java/moze_intel/projecte/gameObjs/block_entities/CondenserMK2BlockEntity.java:54-70` | `forceInsertEmc(IEMCProxy.INSTANCE.getSellValue(stack) * stack.getCount(), EmcAction.EXECUTE);`（第 64 行） |
| `src/main/java/moze_intel/projecte/gameObjs/block_entities/RelayMK1BlockEntity.java:118-138` | `long emcVal = IEMCProxy.INSTANCE.getSellValue(stack);`（第 131 行） |

**反例（不是"烧物品"）**：`EMCHelper.consumePlayerFuel` 在把燃料当"燃料"消耗时用的是 `getValue` —— `src/main/java/moze_intel/projecte/utils/EMCHelper.java:105-115`

```java
} else if (!metRequirement && FuelMapper.isStackFuel(stack)) {
	long emc = IEMCProxy.INSTANCE.getValue(stack);
	int toRemove = Mth.ceil((double) (minFuel - emcConsumed) / emc);
	...
}
```
这是"以物易燃料"的语境，不是"回收成 EMC"，所以用购买价。**结论不变：把物品折算成 EMC 池，语义对应的是 `getSellValue`。**

### 2.4 getValue 对 NBT / 附魔 / 储能物品的加成计算

入口：`EMCProxyImpl.getValue` → `DataComponentManager.getEmcValue`（`src/main/java/moze_intel/projecte/emc/components/DataComponentManager.java:56-89`）：

```java
@Range(from = 0, to = Long.MAX_VALUE)
public static long getEmcValue(@NotNull ItemInfo info) {
	//TODO: Fix this, as it does not catch the edge case that we have an exact match and then there are random added Data Components on top of it
	long emcValue = EMCMappingHandler.getStoredEmcValue(info);
	if (!info.hasModifiedComponents()) {
		return emcValue;
	} else if (emcValue == 0) {
		//Try getting a base emc value from the Data Component less variant if we don't have one matching our Data Components
		emcValue = EMCMappingHandler.getStoredEmcValue(info.itemOnly());
		if (emcValue == 0) {
			return 0;
		}
	}
	//Note: We continue to use our initial ItemInfo so that we are calculating based on the Data Components
	for (IDataComponentProcessor processor : processors) {
		if (MappingConfig.isEnabled(processor)) {
			try {
				emcValue = processor.recalculateEMC(info, emcValue);
			} catch (ArithmeticException e) {
				//Exit with it not having an EMC value, as it most likely overflowed, and we don't want to allow wasting EMC
				return 0;
			}
			if (emcValue <= 0) {
				return 0;
			}
		}
	}
	return emcValue;
}
```

三条关键规则：
1. **精确命中优先**：若 `ItemInfo`（含组件）本身在映射表里，直接返回该值，**不再跑 processor**。
2. **回退到"无组件版本"**：否则用 `info.itemOnly()` 的基值作为起点。
3. **逐个 processor 累加/调整**，顺序由 `@DataComponentProcessor(priority=...)` 决定（`src/api/java/moze_intel/projecte/api/components/DataComponentProcessor.java:15-20`，priority 越大越先跑；`DataComponentManager.loadProcessors()` 通过 `AnnotationHelper.getDataComponentProcessors()` 收集，`DataComponentManager.java:22-27`）。任何一步抛 `ArithmeticException`（溢出）或结果 `<= 0`，**整个物品直接判为无 EMC 值（0）**。

主要 processor（`src/main/java/moze_intel/projecte/emc/components/processor/`）：

| Processor | 作用 | 默认是否启用 | 证据 |
| --- | --- | --- | --- |
| `DamageProcessor` | **耐久**：按剩余耐久比例折算。`recalculateEMC` 里 `currentEMC = currentEMC * remainingDurability / maxDamage`；剩余 0 → 返回 0 | 启用（`isAvailable()` 未重写 → 默认 `true`，`IConfigurableElement.java:47-49`） | `DamageProcessor.java:32-53`；`@DataComponentProcessor(priority = Integer.MAX_VALUE)` 在 `DamageProcessor.java:11` |
| `StoredEMCProcessor` | **储能**：`Math.addExact(currentEMC, emcHolder.getStoredEmc(stack))`，即 Klein Star 等储存的 EMC 直接加到物品价值上 | 启用 | `StoredEMCProcessor.java:31-40` |
| `EnchantmentProcessor` | **附魔**：每级附魔加 `enchantmentEmcBonus / rarityWeight`，默认 `enchantmentEmcBonus = 5_000`，配置项 `enchantment_emc_bonus`（可 0..Long.MAX） | **默认禁用**（`isAvailable()` 返回 `false`） | `EnchantmentProcessor.java:25-27`（`DEFAULT_ENCHANT_EMC_BONUS = 5_000`）、`:44-54`（`isAvailable`/`usePersistentComponents` 均 `return false`）、`:62-73` |
| `ContainerProcessor` / `BundleProcessor` / `SimpleContainerProcessor` | **容器内容物**：把容器内物品的 EMC 加进去（`SimpleContainerProcessor` 的 `recalculateEMC`） | 启用 | `ContainerProcessor.java:12-44`；`SimpleContainerProcessor.java:9` |
| `BannerProcessor` / `ArmorTrimProcessor` / `FireworkProcessor` / `FireworkStarProcessor` / `DecoratedPotProcessor` / `MapScaleProcessor` / `WrittenBookProcessor` / `WritableBookProcessor` / `DecoratedShieldProcessor` / `MercurialEyeProcessor` | 各自图案/纹饰/烟花/地图缩放/成书等的加成 | 启用 | 见 `src/main/java/moze_intel/projecte/emc/components/processor/` 全目录 |

启用状态由 `MappingConfig` 控制：
- `MappingConfig.isEnabled(IDataComponentProcessor)` —— `src/main/java/moze_intel/projecte/config/MappingConfig.java:106-120`；**默认值就是 `processor.isAvailable()`**（`MappingConfig.java:174`）。
- `MappingConfig.hasPersistent(IDataComponentProcessor)` —— `MappingConfig.java:122-142`；默认值 `processor.hasPersistentComponents() && processor.usePersistentComponents()`（`MappingConfig.java:127`），配置文件里对应 `persistent` 选项（`MappingConfig.java:178-182`）。

**对附属模组的直接影响**：
- 储能物品（Klein Star、各种电容器）`getValue` 会**把里面的 EMC 也折成物品价值**。如果你"存入物品 → 折算 EMC"，务必自己决定是否希望把储能计入（计入 = 玩家可以把一个满的 Klein Star 存进去换出等量 EMC + 星星本体价值，可能不亏；不计入 = 需要剥离）。
- 附魔**默认不计价**（`EnchantmentProcessor.isAvailable()==false`），所以默认情况下"锋利 V 钻石剑"和"普通钻石剑"EMC 相同（但**知识条目不同**，见第 4 节）。
- 容器内容物**会计价**，所以塞满东西的潜影盒 EMC 很高。
- 耐久损耗**会降价**。
- 一切计算在 `getValue` 内部完成，**你不需要自己处理这些**——但要注意 `getValue` 可能返回 0（溢出保护），此时应视为"无 EMC 值"。

---

## 3. 知识能力（`PECapabilities.KNOWLEDGE`）

### 3.1 能力类型

`src/api/java/moze_intel/projecte/api/capabilities/PECapabilities.java:39-42`

```java
/**
 * The capability object for IKnowledgeProvider
 */
public static final EntityCapability<IKnowledgeProvider, Void> KNOWLEDGE_CAPABILITY = EntityCapability.createVoid(rl("knowledge"), IKnowledgeProvider.class);
```

- **是 `EntityCapability`（NeoForge 21.1 的 `net.neoforged.neoforge.capabilities.EntityCapability`），不是旧版 `Capability`。**（同文件顶部 import：`net.neoforged.neoforge.capabilities.EntityCapability`，`PECapabilities.java:16`）
- 注册名 = `ResourceLocation.fromNamespaceAndPath("projecte", "knowledge")`（`PECapabilities.java:25-27` 的 `rl()` + `PECapabilities.java:42`）。
- 第二个泛型参数是 `Void`（`createVoid`），即**不区分上下文**——所以 `getCapability` 调用时**不要传 context 参数**。

### 3.2 从 `ServerPlayer` 获取 `IKnowledgeProvider`

注册处（`src/main/java/moze_intel/projecte/PECore.java:182-185`）：

```java
public void registerCapabilities(RegisterCapabilitiesEvent event) {
	event.registerEntity(PECapabilities.ALCH_BAG_CAPABILITY, EntityType.PLAYER, (player, context) -> new AlchBagImpl(player));
	event.registerEntity(PECapabilities.KNOWLEDGE_CAPABILITY, EntityType.PLAYER, (player, context) -> new KnowledgeImpl(player));
}
```

NeoForge 21.1 的标准用法（**两端都可用**，因为注册目标 `EntityType.PLAYER` 不限端）：

```java
IKnowledgeProvider provider = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
// 或显式：player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY, null)
if (provider == null) { /* 理论上 PLAYER 一定有，但请仍然判空 */ }
```

**实现代码里的真实调用点（可直接照抄的写法）**：

| 位置 | 代码 |
| --- | --- |
| `src/main/java/moze_intel/projecte/gameObjs/container/inventory/TransmutationInventory.java:53-57` | `super((IItemHandlerModifiable) Objects.requireNonNull(player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)).getInputAndLocks(), ...)`；`this.provider = Objects.requireNonNull(player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY));` |
| `src/main/java/moze_intel/projecte/impl/TransmutationProxyImpl.java:24-32` | 服务端：`player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)`；客户端：`Minecraft.getInstance().player.getCapability(...)` |
| `src/main/java/moze_intel/projecte/network/commands/EMCCMD.java:75-76` | `ServerPlayer player = ...; IKnowledgeProvider provider = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);` |
| `src/main/java/moze_intel/projecte/network/packets/to_client/knowledge/KnowledgeSyncChangePKT.java:34-36` | 客户端：`Player player = context.player(); IKnowledgeProvider knowledge = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);` |

**注意**：`KnowledgeImpl` 是**每次 `getCapability` 都新建一个轻量视图**（`PECore.java:184` 的 lambda `(player, context) -> new KnowledgeImpl(player)`），不缓存状态；它通过 `player.getData(PEAttachmentTypes.KNOWLEDGE)` 每次取真实数据（`KnowledgeImpl.java:64-68`）。
→ **不要长期缓存 `IKnowledgeProvider` 实例**（尤其是跨 `ServerPlayer` 重生/换维度后），每次用就重新 `getCapability`。

### 3.3 `IKnowledgeProvider` 全部方法及语义

`src/api/java/moze_intel/projecte/api/capabilities/IKnowledgeProvider.java`（共 176 行）

| # | 签名 | 行号 | 语义 |
| --- | --- | --- | --- |
| 1 | `boolean hasFullKnowledge()` | `:29` | 是否拥有"全书"标记（学习之书 Tome of Knowledge）。为 `true` 时所有知识查询基本都返回 `true` |
| 2 | `void setFullKnowledge(boolean)` | `:34` | 设置"全书"标记；值变化时触发 `PlayerKnowledgeChangeEvent`（`KnowledgeImpl.java:82-88`） |
| 3 | `void clearKnowledge()` | `:39` | 清空全部知识并清除"全书"标记；若原本有知识则触发事件（`KnowledgeImpl.java:90-100`） |
| 4 | `default boolean hasKnowledge(ItemStack)` | `:49-51` | 空栈返回 `false`，否则 `hasKnowledge(ItemInfo.fromStack(stack))` |
| 5 | `boolean hasKnowledge(ItemInfo)` | `:58` | 是否已学习。实现里会先归一化：`attachment.knowledge.contains(IEMCProxy.INSTANCE.getPersistentInfo(info))`（`KnowledgeImpl.java:118-128`）；`fullKnowledge` 时另有分支（见第 4 节） |
| 6 | `default boolean addKnowledge(ItemStack)` | `:68-70` | 空栈返回 `false`，否则委托 |
| 7 | `boolean addKnowledge(ItemInfo)` | `:77` | **添加知识，返回是否真的新增**（已存在 → `false`）；会归一化、会触发事件、**不会自动同步**。详见第 4 节 |
| 8 | `default boolean removeKnowledge(ItemStack)` | `:87-89` | 同上，用于移除 |
| 9 | `boolean removeKnowledge(ItemInfo)` | `:96` | 移除知识，返回是否真的移除 |
| 10 | `Set<ItemInfo> getKnowledge()` | `:101-102` | **不可修改的实时视图**（`Collections.unmodifiableSet`）。`fullKnowledge` 时返回"全部映射物品 ∪ 额外学习条目" |
| 11 | `IItemHandler getInputAndLocks()` | `:107-108` | 转换桌的 9 个输入/锁定槽（`LOCK_SLOTS = 9`，`KnowledgeImpl.java:301`） |
| 12 | `BigInteger getEmc()` | `:113` | **玩家个人转换台网络**里的 EMC（BigInteger） |
| 13 | `void setEmc(BigInteger)` | `:118` | 直接写入，无校验、无同步（`KnowledgeImpl.java:218-221`） |
| 14 | `void sync(ServerPlayer)` | `:125` | 全量同步（知识 + 输入槽 + EMC）→ `KnowledgeSyncPKT`（`KnowledgeImpl.java:223-226`） |
| 15 | `void syncEmc(ServerPlayer)` | `:132` | 只同步 EMC → `KnowledgeSyncEmcPKT`（`KnowledgeImpl.java:228-231`） |
| 16 | `void syncKnowledgeChange(ServerPlayer, ItemInfo change, boolean learned)` | `:141` | 同步单条知识的增删 → `KnowledgeSyncChangePKT`（`KnowledgeImpl.java:233-236`）。javadoc 明确要求 `change` **应该是持久化变体（persistent variant）**（`IKnowledgeProvider.java:138`） |
| 17 | `void syncInputAndLocks(ServerPlayer, IntList slotsChanged, TargetUpdateType)` | `:150` | 同步输入/锁定槽（`KnowledgeImpl.java:238-255`）；`slotsChanged` 为空则什么都不做 |
| 18 | `void receiveInputsAndLocks(Int2ObjectMap<ItemStack>)` | `:157` | 客户端侧接收（`KnowledgeImpl.java:257-269`） |
| 19 | `enum TargetUpdateType { NONE, IF_NEEDED, ALL }` | `:159-175` | 客户端目标列表更新策略；带 `BY_ID` / `STREAM_CODEC` |

`KnowledgeImpl` 额外公开的非接口方法（**不属于 API，附属模组不应依赖**）：
- `KnowledgeImpl.wrapAttachment(KnowledgeAttachment)` —— `KnowledgeImpl.java:49-56`
- `KnowledgeImpl.pruneStaleKnowledge()` —— `KnowledgeImpl.java:273-297`（EMC 重算后清理失效知识）
- `KnowledgeImpl.KnowledgeAttachment`（内部数据类）—— `KnowledgeImpl.java:299-341`

---

## 4. 知识集合的语义（`Set<ItemInfo>`）

### 4.1 `ItemInfo` 的 equals 包含 DataComponentPatch

`src/api/java/moze_intel/projecte/api/ItemInfo.java:201-220`

```java
@Override
public int hashCode() {
	if (!hasCachedHash) {
		hasCachedHash = true;
		ResourceKey<Item> resourceKey = item.getKey();
		int code = resourceKey == null ? 0 : resourceKey.hashCode();
		cachedHashCode = 31 * code + componentsPatch.hashCode();
	}
	return cachedHashCode;
}

@Override
public boolean equals(Object o) {
	if (o == this) {
		return true;
	} else if (o instanceof ItemInfo other) {
		return item.is(other.item) && componentsPatch.equals(other.componentsPatch);
	}
	return false;
}
```

类注释（`ItemInfo.java:23-29`）也点明："Unlike `ItemStack` this class does not keep track of count, and overrides `equals`/`hashCode` so that it can be used properly in a `Set`"；且"若传入的 `DataComponentPatch` 为空，则转换为 null"。`fromStack` 直接取 `stack.getComponentsPatch()`（`ItemInfo.java:120-122`）。

**所以：同一个物品但 NBT/组件不同 → `ItemInfo` 不相等 → 理论上算两条知识。但是否真的算两条，取决于 `addKnowledge` 的归一化（下一节），以及该组件是否被判定为"持久"（persistent）。**

### 4.2 `addKnowledge` 的完整实现

`src/main/java/moze_intel/projecte/impl/capability/KnowledgeImpl.java:130-163`

```java
@Override
public boolean addKnowledge(@NotNull ItemInfo info) {
	KnowledgeAttachment attachment = attachment();
	if (attachment.fullKnowledge) {
		ItemInfo persistentInfo = getIfPersistent(info);
		if (persistentInfo == null) {
			//If the item doesn't have extra data, and we have all knowledge, don't actually add any
			return false;
		}
		//If it does have extra data, pretend we don't have full knowledge and try adding it as what we have is persistent.
		// Note: We ignore the tome here being a separate entity because it should not have any persistent item
		return tryAdd(attachment, persistentInfo);
	}
	if (info.getItem().is(PEItems.TOME_OF_KNOWLEDGE.getKey())) {
		//Make sure we don't have any data components as it doesn't have any effect for the tome
		info = info.itemOnly();
		attachment.knowledge.add(info);
		attachment.fullKnowledge = true;
		fireChangedEvent();
		return true;
	}
	return tryAdd(attachment, IEMCProxy.INSTANCE.getPersistentInfo(info));
}

private boolean tryAdd(@NotNull KnowledgeAttachment attachment, @NotNull ItemInfo cleanedInfo) {
	if (attachment.knowledge.add(cleanedInfo)) {
		fireChangedEvent();
		return true;
	}
	return false;
}
```

`getIfPersistent`（`KnowledgeImpl.java:102-116`）：

```java
@Nullable
private ItemInfo getIfPersistent(@NotNull ItemInfo info) {
	if (!info.hasModifiedComponents() || EMCMappingHandler.hasEmcValue(info)) {
		return null;
	}
	ItemInfo cleanedInfo = IEMCProxy.INSTANCE.getPersistentInfo(info);
	if (cleanedInfo.hasModifiedComponents() && !EMCMappingHandler.hasEmcValue(cleanedInfo)) {
		return cleanedInfo;
	}
	return null;
}
```

事件（`KnowledgeImpl.java:70-74`）：

```java
protected void fireChangedEvent() {
	if (player != null && !player.level().isClientSide) {
		NeoForge.EVENT_BUS.post(new PlayerKnowledgeChangeEvent(player));
	}
}
```

**三个问题的明确回答：**

| 问题 | 答案 | 证据 |
| --- | --- | --- |
| 会自动 `getPersistentInfo` 归一化吗？ | **会。** 走到 `tryAdd` 之前先调 `IEMCProxy.INSTANCE.getPersistentInfo(info)`（`KnowledgeImpl.java:154`）。所以"未附魔钻石剑"和"附魔钻石剑"（在 `EnchantmentProcessor` 默认禁用 → 附魔不是持久组件的情况下）会被归一化成同一条 `ItemInfo`，**只占一条知识**。反过来，若某组件被判定为持久（如启用了附魔持久化），则不同附魔 = 不同知识条目 | `KnowledgeImpl.java:154`、`DataComponentManager.java:40-54`、`MappingConfig.java:122-142` |
| 会触发事件吗？ | **会，且只在服务端。** `tryAdd` 成功时 `fireChangedEvent()` → `NeoForge.EVENT_BUS.post(new PlayerKnowledgeChangeEvent(player))`。事件类**不可取消**（只 `extends Event`，`PlayerKnowledgeChangeEvent.java:13`）。另注意 `fullKnowledge` 分支下若条目已存在，`tryAdd` 返回 `false`，**不触发事件** | `KnowledgeImpl.java:157-163`、`:70-74`、`src/api/.../event/PlayerKnowledgeChangeEvent.java:13` |
| 会自动同步给客户端吗？ | **不会！** `addKnowledge` 内部完全没有任何发包代码。调用方必须**自己**显式调用 `syncKnowledgeChange(player, info, true)`。ProjectE 自己在转换桌流程里就是这么做的 | `KnowledgeImpl.java:157-163`（无 `PacketDistributor` 调用） vs `TransmutationInventory.java:88-97`：<br>`if (provider.addKnowledge(cleanedInfo)) { provider.syncKnowledgeChange((ServerPlayer) player, cleanedInfo, true); }` |

补充：`getKnowledge()` 的实现（`KnowledgeImpl.java:194-205`）

```java
@NotNull
@Override
public Set<ItemInfo> getKnowledge() {
	KnowledgeAttachment attachment = attachment();
	if (attachment.fullKnowledge) {
		Set<ItemInfo> allKnowledge = EMCMappingHandler.getMappedItems();
		//Make sure we include any extra items they have learned such as various enchanted items.
		allKnowledge.addAll(attachment.knowledge);
		return Collections.unmodifiableSet(allKnowledge);
	}
	return Collections.unmodifiableSet(attachment.knowledge);
}
```

`EMCMappingHandler.getMappedItems()` 返回**新拷贝**（`src/main/java/moze_intel/projecte/emc/EMCMappingHandler.java:188-196`，`return new HashSet<>(emc.keySet());`），所以不会污染内部表。
> ⚠️ **性能提示**：若玩家有"全书"（`fullKnowledge`），`getKnowledge()` **每次调用都会构造一个包含全部 EMC 映射物品的新 `HashSet`**（可能上万个条目）。**不要在渲染帧里调用**；应缓存 + 在 `PlayerKnowledgeChangeEvent` / `EMCRemapEvent` 时失效。

数据结构定义（`KnowledgeImpl.java:299-341`）：`KnowledgeAttachment` 持有 `Set<ItemInfo> knowledge`（`HashSet`）、`ItemStackHandler inputLocks`（9 槽）、`BigInteger emc`、`boolean fullKnowledge`；序列化 codec 在 `:303-318`，落盘随玩家 attachment 走（`PEAttachmentTypes.java:31-37`）。

---

## 5. 事件（`api/event/`）

四个事件全部注册在 **`NeoForge.EVENT_BUS`**（游戏事件总线），不是 mod event bus。

| 事件 | 类定义 | 可取消？ | 触发点（实现代码 `file:line`） | 语义 |
| --- | --- | --- | --- | --- |
| `PlayerKnowledgeChangeEvent` | `src/api/.../event/PlayerKnowledgeChangeEvent.java:13` | **否**（只 `extends Event`） | `src/main/java/moze_intel/projecte/impl/capability/KnowledgeImpl.java:72` | 服务端，玩家知识**已改变之后**触发。只带 `UUID playerUUID`（`:28-31`），玩家可能已离线。`setFullKnowledge`/`clearKnowledge`/增删知识成功时都会触发 |
| `PlayerAttemptLearnEvent` | `src/api/.../event/PlayerAttemptLearnEvent.java:14` | **是**（`implements ICancellableEvent`） | `src/main/java/moze_intel/projecte/gameObjs/container/inventory/TransmutationInventory.java:91` | 服务端，玩家**尝试学习**某物品时触发。带 `getPlayer()`、`getSourceInfo()`（原始栈）、`getReducedInfo()`（归一化后）。取消 → 不学习 |
| `PlayerAttemptCondenserSetEvent` | `src/api/.../event/PlayerAttemptCondenserSetEvent.java:14` | **是** | `src/main/java/moze_intel/projecte/gameObjs/block_entities/CondenserBlockEntity.java:206` | 服务端，玩家**尝试在凝聚器里放置物品（设为锁定目标）**时触发。同样带 source/reduced info |
| `EMCRemapEvent` | `src/api/.../event/EMCRemapEvent.java:10` | **否** | `src/main/java/moze_intel/projecte/emc/EMCMappingHandler.java:145` | 服务端，**所有 EMC 值重算完成之后**触发。无字段 |

`PlayerAttemptLearnEvent` 的唯一触发点（`TransmutationInventory.java:85-97`）——**这段就是 ProjectE 官方推荐的"检查已学习 → 触发尝试学习事件 → 添加 → 同步"标准流程**：

```java
/**
 * @apiNote Call on server only
 */
public void handleKnowledge(ItemInfo info) {
	ItemInfo cleanedInfo = IEMCProxy.INSTANCE.getPersistentInfo(info);
	//Pass both stacks to the Attempt Learn Event in case a mod cares about the data component/damage difference when comparing
	if (!provider.hasKnowledge(cleanedInfo) && !NeoForge.EVENT_BUS.post(new PlayerAttemptLearnEvent(player, info, cleanedInfo)).isCanceled()) {
		if (provider.addKnowledge(cleanedInfo)) {
			//Only sync the knowledge changed if the provider successfully added it
			provider.syncKnowledgeChange((ServerPlayer) player, cleanedInfo, true);
		}
	}
}
```

`PlayerAttemptCondenserSetEvent` 触发点（`CondenserBlockEntity.java:197-213`）：

```java
private boolean attemptCondenserSet(@NotNull Level level, @NotNull BlockPos pos, Player player) {
	if (level.isClientSide) {
		return false;
	}
	if (getLockInfo() == null) {
		ItemStack stack = player.containerMenu.getCarried();
		if (!stack.isEmpty()) {
			ItemInfo sourceInfo = ItemInfo.fromStack(stack);
			ItemInfo reducedInfo = IEMCProxy.INSTANCE.getPersistentInfo(sourceInfo);
			if (!NeoForge.EVENT_BUS.post(new PlayerAttemptCondenserSetEvent(player, sourceInfo, reducedInfo)).isCanceled()) {
				lockInfo = reducedInfo;
				checkLockAndUpdate(true);
				markDirty(level, pos, false);
				return true;
			}
			return false;
		}
		...
```

`EMCRemapEvent` 触发点（`EMCMappingHandler.java:125-146`）——**注意它之前先做了 prune + 全量重同步**：

```java
private static void fireEmcRemapEvent() {
	//Start by doing our implementations
	FuelMapper.loadMap();
	loadIndex++;
	MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
	if (server != null) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			IKnowledgeProvider knowledge = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
			if (knowledge != null) {
				if (knowledge instanceof KnowledgeImpl impl && impl.pruneStaleKnowledge()) {
					knowledge.sync(player);
				} else if (player.containerMenu instanceof TransmutationContainer) {
					PECore.packetHandler().updateTransmutationTargets(player);
				}
			}
		}
	}
	NeoForge.EVENT_BUS.post(new EMCRemapEvent());
}
```

> **对附属模组的意义**：`/reload`、数据包变更、服务器启动后 EMC 重算会**清掉**玩家已学但已无 EMC 值 / 持久化形态改变的知识（`KnowledgeImpl.java:273-297`）。如果你的"维度网络"里缓存了"已学习物品 → EMC"的映射，**必须监听 `EMCRemapEvent` 失效重建**，否则会拿着过期 EMC 值兑换。

**关于"非转换桌路径不触发 AttemptLearn"的证据**：`KnowledgeCMD`（管理员 `/projecte knowledge learn`）在 `src/main/java/moze_intel/projecte/network/commands/KnowledgeCMD.java:111-134` **直接调用** `provider.addKnowledge(itemInfo)` / `provider.removeKnowledge(itemInfo)`，然后 `provider.syncKnowledgeChange(...)`，**没有** fire `PlayerAttemptLearnEvent`。说明"不触发该事件"在 ProjectE 内部也是被接受的模式。

---

## 6. 同步到客户端

### 6.1 全量同步：登录 / 重生 / 换维度

**知识 attachment 不是自动同步的**——`PEAttachmentTypes.KNOWLEDGE` 只声明了 `builder` / `serialize` / `copyHandler` / `copyOnDeath`，**没有 `.sync()`**（`src/main/java/moze_intel/projecte/gameObjs/registries/PEAttachmentTypes.java:31-37`）。同步完全靠手写包。

`src/main/java/moze_intel/projecte/events/PlayerEvents.java:72-87`（登录）：

```java
@SubscribeEvent
public static void playerConnect(PlayerEvent.PlayerLoggedInEvent event) {
	ServerPlayer player = (ServerPlayer) event.getEntity();
	IKnowledgeProvider knowledge = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
	if (knowledge != null) {
		knowledge.sync(player);
		PlayerHelper.updateScore(player, PlayerHelper.SCOREBOARD_EMC, knowledge.getEmc());
	}
	IAlchBagProvider alchBagProvider = player.getCapability(PECapabilities.ALCH_BAG_CAPABILITY);
	if (alchBagProvider != null) {
		alchBagProvider.syncAllBags(player);
	}
	PECore.debugLog("Sent knowledge and bag data to {}", player.getName());
}
```

- 重生：`PlayerEvents.java:42-54`（`knowledge.sync(player)` 在 `:47`）
- 换维度：`PlayerEvents.java:56-70`（`knowledge.sync(serverPlayer)` 在 `:63`）
- EMC 重算 prune 后：`EMCMappingHandler.java:134-135`

`sync` 的实现（`KnowledgeImpl.java:223-231`）：

```java
@Override
public void sync(@NotNull ServerPlayer player) {
	PacketDistributor.sendToPlayer(player, new KnowledgeSyncPKT(attachment()));
}

@Override
public void syncEmc(@NotNull ServerPlayer player) {
	PacketDistributor.sendToPlayer(player, new KnowledgeSyncEmcPKT(getEmc()));
}
```

### 6.2 网络包类名与注册

`src/main/java/moze_intel/projecte/network/PacketHandler.java:87-90`：

```java
registrar.play(KnowledgeSyncPKT.TYPE, KnowledgeSyncPKT.STREAM_CODEC);
registrar.play(KnowledgeSyncEmcPKT.TYPE, KnowledgeSyncEmcPKT.STREAM_CODEC);
registrar.play(KnowledgeSyncInputsAndLocksPKT.TYPE, KnowledgeSyncInputsAndLocksPKT.STREAM_CODEC);
registrar.play(KnowledgeSyncChangePKT.TYPE, KnowledgeSyncChangePKT.STREAM_CODEC);
```

| 包类 | 文件 | 载荷 | 方向 |
| --- | --- | --- | --- |
| `KnowledgeSyncPKT` | `network/packets/to_client/knowledge/KnowledgeSyncPKT.java` | 整个 `KnowledgeAttachment`（知识集合 + 输入槽 + EMC + fullKnowledge） | S→C |
| `KnowledgeSyncEmcPKT` | `.../knowledge/KnowledgeSyncEmcPKT.java` | `BigInteger emc` | S→C |
| `KnowledgeSyncChangePKT` | `.../knowledge/KnowledgeSyncChangePKT.java` | `ItemInfo change` + `boolean learned` | S→C |
| `KnowledgeSyncInputsAndLocksPKT` | `.../knowledge/KnowledgeSyncInputsAndLocksPKT.java` | 槽位→`ItemStack` 映射 + `TargetUpdateType` | S→C |

客户端接收端（`KnowledgeSyncPKT.java:29-42`）——**注意它直接写进客户端玩家的 attachment**：

```java
@Override
public void handle(IPayloadContext context) {
	//We have to use the client's player instance rather than context#player as the first usage of this packet is sent during player login
	// which is before the player exists on the client so the context does not contain it.
	//Note: This must stay LocalPlayer to not cause classloading issues
	LocalPlayer player = Minecraft.getInstance().player;
	if (player != null) {
		player.setData(PEAttachmentTypes.KNOWLEDGE, data);
		if (player.containerMenu instanceof TransmutationContainer container) {
			container.transmutationInventory.updateClientTargets(false);
		}
	}
	PECore.debugLog("** RECEIVED TRANSMUTATION DATA CLIENTSIDE **");
}
```

增量包（`KnowledgeSyncChangePKT.java:32-46`）——**客户端会真的在本地调用 `addKnowledge`/`removeKnowledge` 来维护镜像**：

```java
@Override
public void handle(IPayloadContext context) {
	Player player = context.player();
	IKnowledgeProvider knowledge = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
	if (knowledge != null) {
		if (learned) {
			if (!knowledge.hasKnowledge(change) && knowledge.addKnowledge(change) && player.containerMenu instanceof TransmutationContainer container) {
				container.transmutationInventory.itemLearned(change);
			}
		} else if (knowledge.hasKnowledge(change) && knowledge.removeKnowledge(change) && player.containerMenu instanceof TransmutationContainer container) {
			container.transmutationInventory.itemUnlearned(change);
		}
	}
	PECore.debugLog("** RECEIVED TRANSMUTATION KNOWLEDGE CHANGE DATA CLIENTSIDE **");
}
```

### 6.3 客户端能直接拿到这份数据吗？

**能，但只限"本地玩家自己的知识"。**

- 客户端玩家的 `IKnowledgeProvider` 是**真实存在的**：能力注册只针对 `EntityType.PLAYER`、不限端（`PECore.java:184`），且客户端代码确实在调 `getCapability`（`KnowledgeSyncChangePKT.java:35`、`TransmutationProxyImpl.java:29-32`）。
- 客户端的数据来源有两路：
  1. **登录 / 重生 / 换维度时的 `KnowledgeSyncPKT` 全量**；
  2. **平时 `KnowledgeSyncChangePKT` 增量**（每条学习/遗忘）。
- **不存在 `ClientKnowledge` / `KnowledgeClient` 之类的类**（在 `src/` 全量 grep 无匹配）。客户端就是普通的 attachment + capability。

**但是：**
- ⚠️ **拿不到其他玩家的知识**。没有任何包会把别的玩家的知识发给客户端。
- ⚠️ **客户端的 `addKnowledge`/`removeKnowledge` 是"真的会改本地镜像"的**，而且**不会回传服务端**（`KnowledgeSyncChangePKT.java:38,41` 就是靠这个机制工作的）。**你的维度网络 UI 在客户端绝对不要调用 `addKnowledge`/`removeKnowledge`/`setEmc`/`clearKnowledge`**，否则本地状态会和服务端漂移，且ProjectE 也不会纠正你（除了下次全量同步/EMCRemap）。
- ⚠️ **跨玩家/离线玩家的知识必须在服务端取**：
  - 在线：`server.getPlayerList().getPlayer(uuid).getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)`
  - 在线或离线统一入口：`ITransmutationProxy.INSTANCE.getKnowledgeProviderFor(uuid)`（`src/api/java/moze_intel/projecte/api/proxy/ITransmutationProxy.java:13-30`），实现在 `src/main/java/moze_intel/projecte/impl/TransmutationProxyImpl.java:18-34`；离线走 `TransmutationOffline`（`src/main/java/moze_intel/projecte/impl/TransmutationOffline.java:53-89`，直接从 `<world>/playerdata/<uuid>.dat` 读 attachment NBT，见 `:67-77`），返回的是**不可变的只读视图**（`TransmutationOffline.java:91-170`，`addKnowledge` 恒返回 `false`）。
    - ⚠️ 客户端调用 `getKnowledgeProviderFor` 时 **UUID 参数被忽略**，返回的是本地玩家的 provider（`ITransmutationProxy.java:19-20` 的 javadoc + `TransmutationProxyImpl.java:29-32`）。

**结论**：如果你在"维度网络界面"里展示的是**当前打开界面的玩家自己的**已学习列表，客户端可以直接读，**零额外包**；如果要做"网络聚合/别的玩家的列表"，**必须自己写包**。

---

## 7. 玩家 EMC 池

### 7.1 现状：`BigInteger`，无上限

- API：`BigInteger getEmc()` / `void setEmc(BigInteger)`（`IKnowledgeProvider.java:110-118`）
- 存储字段：`KnowledgeAttachment.emc`（`KnowledgeImpl.java:323`），构造默认 `BigInteger.ZERO`（`KnowledgeImpl.java:325-327`）
- 序列化：`IPECodecHelper.INSTANCE.nonNegativeBigInt().optionalFieldOf("emc", BigInteger.ZERO)`（`KnowledgeImpl.java:309`；实现 `src/main/java/moze_intel/projecte/impl/codec/PECodecHelper.java:119`），网络用 `PEStreamCodecs.EMC_VALUE`（`KnowledgeImpl.java:315`）

**是否存在上限？—— 玩家 EMC 本身没有任何上限。** 证据（ProjectE 源码内两处明确注释）：

`src/main/java/moze_intel/projecte/gameObjs/container/inventory/TransmutationInventory.java:457-460`

```java
syncChangedSlots(inputLocksChanged, TargetUpdateType.NONE);
//Note: We act as if there is no "max" EMC for the player given we use a BigInteger
// This means we don't have to try to put the overflow into the lock slot if there is an EMC storage item there
updateEmcAndSync(provider.getEmc().add(value));
```

`TransmutationInventory.java:475-477`

```java
BigInteger currentEmc = provider.getEmc();
//Note: We act as if there is no "max" EMC for the player given we use a BigInteger
// This means we don't need to first try removing it from the lock slot as it will auto drain from the lock slot
```

写入路径 `updateEmcAndSync`（`TransmutationInventory.java:522-533`）**只钳制负数**：

```java
private void updateEmcAndSync(BigInteger emc) {
	if (emc.signum() == -1) {//emc < 0
		//Clamp the emc, should never be less than zero but just in case make sure to fix it
		emc = BigInteger.ZERO;
	}
	provider.setEmc(emc);
	provider.syncEmc((ServerPlayer) player);
	PlayerHelper.updateScore((ServerPlayer) player, PlayerHelper.SCOREBOARD_EMC, emc);
}
```

`KnowledgeImpl.setEmc` 本身**完全无校验**（`KnowledgeImpl.java:218-221`）。管理员命令 `EMCCMD` 也是任意 `BigInteger`（`src/main/java/moze_intel/projecte/network/commands/EMCCMD.java:87-88, 105-139`）。

**"上限"其实是隐性的、来自别处：**

| 位置 | 限制 | 证据 |
| --- | --- | --- |
| 长整型 API | `getAvailableEmcAsLong()` 把 BigInteger 钳到 `Long.MAX_VALUE`，超出部分**不可用**（不会丢，但转换桌用不到） | `TransmutationInventory.java:548-575`；`MathUtils.clampToLong`（`src/main/java/moze_intel/projecte/utils/MathUtils.java:57-60`） |
| 单次操作 | `SlotOutput.remove` 用 `BigInteger` 比较（`SlotOutput.java:34-46`）；`quickMoveStack` 用 BigInteger 乘除（`TransmutationContainer.java:142-155`）；但 `addEmc` 内部对单个储能物品用 `MathUtils.clampToLong`（`TransmutationInventory.java:444, 492`） | 同左 |
| 记分板镜像 | 写记分板时用 `MathUtils.clampToInt`（**int 截断**，仅显示/查询用，不影响真实数据） | `src/main/java/moze_intel/projecte/utils/PlayerHelper.java:44, 185-186`；`MathUtils.java:53-55` |

### 7.2 附属模组如何避免与玩家个人 EMC 冲突

**核心原则：绝不把"维度网络 EMC"写进 `IKnowledgeProvider.setEmc`。** 那是玩家**个人转换台**的池子，ProjectE 的转换桌 GUI（`TransmutationInventory` / `GUITransmutation`）、管理员命令（`EMCCMD`）、离线数据缓存（`TransmutationOffline`）、记分板镜像全都读写它。你写进去 = 玩家的转换桌凭空多/少 EMC。

推荐做法：

1. **独立存储**：用你自己的 `AttachmentType<BigInteger>`（或你自己的 capability / SavedData）挂在玩家或"维度网络"实体上，`AttachmentType` 的注册名用自己的命名空间（例如 `beyonddimensions:network_emc`）。
   - ProjectE 自己的玩家数据就存在玩家 attachment NBT 里（`TransmutationOffline.java:71-75` 读的是 `AttachmentHolder.ATTACHMENTS_NBT_KEY` 下的 attachment id），**不同 id 天然隔离**。
2. **只在"显式兑换"时才碰 ProjectE 的池子**：如果要做"用个人 EMC 换网络 EMC"，走服务端 + 显式读写 + **一定要同步**：
   ```java
   // 服务端
   provider.setEmc(provider.getEmc().subtract(cost));
   provider.syncEmc(serverPlayer);   // 否则客户端 UI 不刷新
   ```
   参考实现：`TransmutationInventory.updateEmcAndSync`（`TransmutationInventory.java:525-533`）、`EMCCMD.java:138-139`。
3. **不要复用 `getInputAndLocks()`** 来当你的库存：它是转换桌的 9 槽（`KnowledgeImpl.java:301` 的 `LOCK_SLOTS = 9`），索引语义绑死在 `TransmutationContainer`（`TransmutationContainer.java:59-85`，第 8 号是 lock 槽）。
4. **容量/溢出**：你自己的池子建议也用 `BigInteger`（和 ProjectE 习惯一致）；若必须转 `long`，用 `MathUtils.clampToLong` 同款语义并在 UI 上提示，避免静默溢出。
5. **`EMCRemapEvent`**：该事件触发时玩家的 EMC 数值不会变，但**物品的 EMC 值会变**。如果你缓存了"某物品 = 多少 EMC"，必须在这里失效。

---

## 8. ProjectE 依赖坐标

### 8.1 是否发布 Maven 构件？

**本仓库的构建脚本里没有配置任何 Maven 发布目标（repository target），因此无法从这个仓库直接得到可用的 Maven 坐标。**

证据：

- `build.gradle:12` 有 `id('maven-publish')` 插件；
- `build.gradle:378-405` 有一个 `publishing { publications { shadow(MavenPublication) { ... } } }`：
  - `groupId = project.group` → `moze_intel.projecte`（`build.gradle:122`）
  - `artifactId = 'ProjectE'`（`build.gradle:384`）
  - `version = project.version` → `1.1.0`（`build.gradle:121` + `gradle.properties:6`）
  - `publication.artifacts = [apiJar, shadowJar, sourcesJar]`（`build.gradle:385`）
- **但整个 `publishing` 块内没有 `repositories { ... }`** —— 也就是没有 `mavenLocal()`、`maven { url ... }` 之类的上传目标（`build.gradle:378-405` 全文）。
- **本仓库克隆里不存在 `.github` 目录**（`Get-ChildItem -Force` 只列出 `.git` / `buildSrc` / `gradle` / `previews` / `src`），因此**没有可参考的 CI 发布工作流**。
- `README.md:13-14` 的下载入口只有 CurseForge：
  ```
  # Downloads
  https://www.curseforge.com/minecraft/mc-mods/projecte/files
  ```
- `update.json` 的 `homepage` 也指向 CurseForge：`https://minecraft.curseforge.com/projects/projecte/`。

**→ 结论：请使用 CurseMaven / Modrinth Maven / 本地 jar 之一。**

### 8.2 推荐的依赖写法

**(A) CurseMaven（ProjectE 自己就在用它拉 Jade，仓库地址有据可查）**

`build.gradle:234`：`exclusiveRepo(handler, 'https://www.cursemaven.com', 'curse.maven')`

```groovy
repositories {
    maven { url = "https://www.cursemaven.com" }
    // ProjectE 自身 build.gradle:224-235 采用 exclusiveContent，但普通 maven{} 也够用
}

dependencies {
    // 格式：curse.maven:<slug>-<projectId>:<fileId>
    // ProjectE 的 CurseForge 数值 projectId：未确认（见第 11 节）
    // 请在 https://www.curseforge.com/minecraft/mc-mods/projecte/files 打开目标文件，
    // 从页面/URL 确认 projectId 与 fileId 后填入：
    compileOnly "curse.maven:projecte-<projectId>:<fileId>"
}
```
> CurseMaven 的 `fileId` 与 `projectId` 必须从 CurseForge 文件页实际确认，**不要凭记忆填写**。

**(B) Modrinth Maven**（需 ProjectE 在 Modrinth 上有对应版本）

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { url = "https://api.modrinth.com/maven" } }
        filter { includeGroup "maven.modrinth" }
    }
}
dependencies {
    compileOnly "maven.modrinth:projecte:<modrinth-version-id>"
}
```

**(C) 本地 jar（最稳，离线可用）**

```groovy
dependencies {
    compileOnly files("libs/ProjectE-1.21.1-PE1.1.0.jar")
    // 若要跑 runClient/runServer 测试，需要放到 run/mods 或改用 localRuntime
}
```
注意两点：
- 主 jar 是 shadowJar，**已经包含 api 输出**（`build.gradle:338-353`，`from([sourceSets.api.output, sourceSets.main.output])`），所以**一个 jar 就够编译**；
- 也有独立的 `classifier=api` 的瘦 jar（`build.gradle:331-336`），只含 `src/api` 的类，适合只想暴露 API 的场景。

**(D) 硬依赖声明（必须写，因为 `IEMCProxy.INSTANCE` 缺失会抛异常）**

在你的 `neoforge.mods.toml` 里（参考 ProjectE 自己的写法 `src/main/resources/META-INF/neoforge.mods.toml:17-32`）：

```toml
[[dependencies.yourmodid]]
  modId="projecte"
  type="required"
  versionRange="[1.1.0,)"
  ordering="AFTER"
  side="BOTH"
```

### 8.3 ProjectE 的 mod id / NeoForge 最低版本（汇总）

| 项 | 值 | 证据 |
| --- | --- | --- |
| mod id | `projecte` | `neoforge.mods.toml:7` |
| 展示名 | `ProjectE` | `neoforge.mods.toml:9` |
| 版本 | `1.1.0`（`${version}` 由 `projecte_version` 展开） | `neoforge.mods.toml:8`；`build.gradle:94`（`replaceResources` 的 `expand`） |
| Minecraft 要求 | `[1.21.1]` | `gradle.properties:16`（`minecraft_version_range=[1.21.1]`）；`neoforge.mods.toml:20` |
| **NeoForge 最低版本** | **`21.1.119`**（范围 `[21.1.119,)`） | `gradle.properties:13-15`（注释 "This determines the minimum version of NeoForge required to use ProjectE" + `neo_version_range=[21.1.119,)`）；`neoforge.mods.toml:25`（`versionRange="${neo_version}"`） |
| Loader 要求 | `[4,)` | `gradle.properties:11`；`neoforge.mods.toml:2`（`loaderVersion="${loader_version}"`） |
| Maven group / artifact 名（若自行发布） | `moze_intel.projecte` / `ProjectE`，`archivesName = projecte` | `build.gradle:122-123`、`build.gradle:382-384` |
| 不兼容 | Mekanism `[,10.7.8]` | `neoforge.mods.toml:28-32` |

---

## 9. 转换桌 / 凝聚器的服务端逻辑参考

### 9.1 "检查已学习 → 扣 EMC → 给出物品" 的语义所在

ProjectE 把这个语义**拆成三处**，其中**真正的"检查已学习"在生成输出槽时做，扣 EMC 在取出时做**（服务端真实扣减，客户端只做显示与预检）。

#### (1) 生成输出槽（已学习 + EMC 足够 → 放进输出槽）

`src/main/java/moze_intel/projecte/gameObjs/container/inventory/TransmutationInventory.java:305-402`，关键在 `:308-361`：

```java
/**
 * @apiNote Call on client only
 */
private void updateClientTargets(long availableEMC) {
	lastAvailableEmc = availableEMC;
	for (int i = 0, slots = outputs.getSlots(); i < slots; i++) {
		outputs.setStackInSlot(i, ItemStack.EMPTY);
	}
	...
	if (lockStack.isEmpty()) {
		filterPredicate = data -> data.emc() > 0 && data.emc() <= availableEMC;
	} else {
		...
	}

	List<ItemInfo> knowledge = provider.getKnowledge().stream()
			.map(info -> new EmcData(info, IEMCProxy.INSTANCE.getValue(info)))
			.filter(filterPredicate)
			.sorted(Comparator.comparingLong(EmcData::emc).reversed())
			.map(EmcData::info)
			.toList();
	...
}
```

注意注释：**这是客户端逻辑**（因为输出槽内容由 `KnowledgeSyncPKT` 同步过来的知识在客户端本地重建）。**列表构成 = `provider.getKnowledge()` ∩ `getValue(info) <= availableEMC`，按 EMC 降序**，且 12 个 matter 槽 + 4 个 fuel 槽分页（`TransmutationInventory.java:41-42` 的 `MAX_MATTER_DISPLAY = 12`、`MAX_FUEL_DISPLAY = 4`）。

服务端也有一个"写输出槽"的校验版本（`TransmutationInventory.java:411-421`），**这就是最凝练的"检查已学习 + EMC 足够"判断**：

```java
/**
 * @apiNote Call on server only
 */
public void writeIntoOutputSlot(int slot, ItemStack item) {
	long emcValue = IEMCProxy.INSTANCE.getValue(item);
	if (emcValue > 0 && emcValue <= getAvailableEmcAsLong() && provider.hasKnowledge(item)) {
		outputs.setStackInSlot(slot, item);
	} else {
		outputs.setStackInSlot(slot, ItemStack.EMPTY);
	}
}
```

#### (2) 扣 EMC 并给出物品（真正的服务端扣减）

`src/main/java/moze_intel/projecte/gameObjs/container/slots/transmutation/SlotOutput.java:26-51`：

```java
@NotNull
@Override
public ItemStack remove(int amount) {
	if (amount == 0) {
		return ItemStack.EMPTY;
	}
	ItemStack stack = ItemHelper.size(getItem(), amount);
	long emcValue = IEMCProxy.INSTANCE.getValue(stack);
	BigInteger bigEmcValue = BigInteger.valueOf(emcValue);
	if (amount > 1) {
		bigEmcValue = bigEmcValue.multiply(BigInteger.valueOf(amount));
		if (bigEmcValue.compareTo(inv.getAvailableEmc()) > 0) {
			//Requesting more emc than available
			//Container expects stacksize=0-Itemstack for 'nothing'
			return ItemStack.EMPTY;
		}
	} else if (emcValue > inv.getAvailableEmcAsLong()) {
		//Requesting more emc than available
		return ItemStack.EMPTY;
	}
	if (inv.isServer()) {
		inv.removeEmc(bigEmcValue);
	}
	return stack;
}
```

配套：
- `SlotOutput.mayPickup` 也做了一道 EMC 判断（`SlotOutput.java:66-69`）
- shift-点击批量兑换：`src/main/java/moze_intel/projecte/gameObjs/container/TransmutationContainer.java:119-163`（`itemEmc = IEMCProxy.INSTANCE.getValue(stack)` → 计算能塞进背包的数量 → `transmutationInventory.removeEmc(totalEmc)`）
- EMC 加减核心：`TransmutationInventory.addEmc`（`:426-461`）、`removeEmc`（`:466-513`）、`updateEmcAndSync`（`:525-533`）
- 可用 EMC（provider + 输入槽里的 Klein Star）：`getAvailableEmcAsLong`（`:551-575`）、`getAvailableEmc`（`:580-595`）

> ⚠️ 注意 `SlotOutput.remove` **本身不再检查 `hasKnowledge`** —— "已学习" 这个前提是在输出槽被填充时保证的（见 `writeIntoOutputSlot` / `updateClientTargets`）。**你的附属模组如果自己做"兑换取出"，必须显式补上 `provider.hasKnowledge(info)` 这一条**，不能只判断 EMC 够不够。

#### (3) 触发入口

- 转换桌物品/方块：`TransmutationTablet`（`src/main/java/moze_intel/projecte/gameObjs/items/TransmutationTablet.java:24-45`，`openContainer` → `TransmutationContainer`）
- 转换石/台：`TransmutationStone`（`src/main/java/moze_intel/projecte/gameObjs/blocks/TransmutationStone.java`）+ `PhilosStoneContainer`（`src/main/java/moze_intel/projecte/gameObjs/container/PhilosStoneContainer.java`）
- 容器与槽位装配：`TransmutationContainer.initSlots`（`TransmutationContainer.java:57-87`）——第 9 号槽是 `SlotConsume`（学习+烧），第 10 号是 `SlotUnlearn`，第 11-26 号是 `SlotOutput`

### 9.2 凝聚器（Condenser）

`src/main/java/moze_intel/projecte/gameObjs/block_entities/CondenserBlockEntity.java`

| 方法 | 行号 | 语义 |
| --- | --- | --- |
| `tickServer` | `:113-120` | 每 tick：检查锁定目标 → `displayEmc = getStoredEmc()` → 若有锁定目标则 `condense()` |
| `checkLockAndUpdate(boolean force)` | `:122-143` | 用 `IEMCProxy.INSTANCE.getValue(lockInfo)` 求出 `requiredEmc`（**产出价 = `getValue`**），并设置 `isAcceptingEmc`；若锁定物品没有 EMC 值则 `requiredEmc = 0` 且不再接受 EMC |
| `condense()` | `:145-158` | **烧输入物品**：`forceInsertEmc(IEMCProxy.INSTANCE.getSellValue(stack), ...)`（第 150 行，**`getSellValue`**）；然后若 `getStoredEmc() >= requiredEmc` 且有空间 → `forceExtractEmc(requiredEmc)` + `pushStack()` |
| `pushStack()` | `:160-165` | `ItemHandlerHelper.insertItemStacked(outputInventory, lockInfo.createStack(), false)` —— 用 `ItemInfo.createStack()` 还原带组件物品（`ItemInfo.java:194-199`） |
| `isStackEqualToLock(ItemStack)` | `:177-187` | 用 `lockInfo.equals(IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(stack)))` 比较（**归一化后比较**） |
| `attemptCondenserSet(Player)` | `:193-224` | 设置锁定目标：`getPersistentInfo` → fire `PlayerAttemptCondenserSetEvent` → 未取消则 `lockInfo = reducedInfo` |

**凝聚器完整语义链**：`getSellValue(输入)` 累积 EMC → 达到 `getValue(锁定目标)` → 消耗掉 EMC → 产出 `1 × 锁定目标`。**这是"物品 ↔ EMC"双向兑换的官方参考实现，也是你的"按 EMC 兑换取出"最贴近的语义。**

`CondenserMK2BlockEntity.condense()`（`:54-70`）同一套语义，只是循环批量：`forceInsertEmc(IEMCProxy.INSTANCE.getSellValue(stack) * stack.getCount(), ...)`（`:64`）。

### 9.3 建议复用的"安全子集"

| 你想做的事 | 应调用的 API | 备注 |
| --- | --- | --- |
| 物品归一化成"知识形态" | `IEMCProxy.INSTANCE.getPersistentInfo(info)` | 必须先做，再 `hasKnowledge` / `addKnowledge` |
| 物品是否有 EMC | `IEMCProxy.INSTANCE.hasValue(info)` 或 `getValue(info) > 0` | |
| 折算成 EMC（存入网络） | `IEMCProxy.INSTANCE.getSellValue(info) * count` | 与"烧掉"一致，见 2.3 |
| 从网络兑换出物品 | `IEMCProxy.INSTANCE.getValue(info) * count` + `hasKnowledge` + 你的池子余额 | 与凝聚器"产出价"一致，见 9.2 |
| 已学习判断 | `provider.hasKnowledge(persistentInfo)` | |
| 学习 | `provider.addKnowledge(persistentInfo)` + `provider.syncKnowledgeChange(player, persistentInfo, true)` | 见 4.2 / 10(b) |
| 玩家个人 EMC 读写 | `provider.getEmc()` / `setEmc` / `syncEmc` | **仅在你确实要操作个人转换台时**，见 7.2 |

---

## 10. 对附属模组的实现建议

### (a) 折算用 `getValue` 还是 `getSellValue`？

**用 `getSellValue`。**

理由（三条，都有源码证据）：

1. **语义正确**：`getSellValue` 的 javadoc 是"EMC the stack **should yield when burned by transmutation, condensers, or relays**"（`IEMCProxy.java:185`）。"存进网络换 EMC"和"烧掉换 EMC"是同一件事，应该给同一个数。
2. **有 ProjectE 的三处实现背书**：`SlotConsume.java:29`、`TransmutationContainer.java:180`、`CondenserBlockEntity.java:150`、`CondenserMK2BlockEntity.java:64`、`RelayMK1BlockEntity.java:131` —— **全部用 `getSellValue`**。
3. **防套利**：`getSellValue = floor(getValue * covalenceLoss)`（`EMCHelper.java:153-167`）。默认 `covalenceLoss = 1.0`（`ServerConfig.java:146-147`），两者相等；但服主可以调到 0.1。若你用 `getValue` 记账，在 `covalenceLoss < 1` 的服务器上就产生了**无风险套利**：存入拿 `getValue`，再用凝聚器/转换桌以 `getValue` 取出——等于凭空造 EMC。用 `getSellValue` 则与全服经济一致。

**实现要点**：
```java
ItemInfo info = ItemInfo.fromStack(stack);                       // 含组件的真实形态
long unitSell = IEMCProxy.INSTANCE.getSellValue(info);           // 回收价（单件）
if (unitSell <= 0) { /* 无 EMC 值，按普通物品处理（或直接拒收） */ }
BigInteger gain = BigInteger.valueOf(unitSell).multiply(BigInteger.valueOf(stack.getCount()));

ItemInfo persistent = IEMCProxy.INSTANCE.getPersistentInfo(info); // 归一化，用于学习/记录
```
注意 `getValue`/`getSellValue` 的 `ItemInfo` 重载**不含数量**（`IEMCProxy.java:129` 注释 "Does not take into account stack size"；`getSellValue(ItemStack)` 同理），**数量必须自己乘**——`SlotConsume.java:29` 和 `TransmutationContainer.java:185` 都是显式 `.multiply(BigInteger.valueOf(stack.getCount()))`。

### (b) 自动学习应该调哪个方法、在哪个线程/侧调用才安全？

**方法**：`provider.addKnowledge(ItemInfo)`，并且**先归一化**。

**标准流程（照抄 ProjectE 自己的 `TransmutationInventory.handleKnowledge`，`TransmutationInventory.java:88-97`）**：

```java
// === 必须在服务端（ServerPlayer / ServerLevel 侧）执行 ===
IKnowledgeProvider provider = serverPlayer.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
if (provider == null) return;                                        // 永远判空

ItemInfo info = ItemInfo.fromStack(stack);
ItemInfo persistent = IEMCProxy.INSTANCE.getPersistentInfo(info);     // ① 归一化

if (provider.hasKnowledge(persistent)) {
    // 已学过：跳过学习，但仍可正常折算 EMC
} else {
    // ②（可选）给其他模组一个取消的机会，与转换桌行为一致
    if (!NeoForge.EVENT_BUS.post(new PlayerAttemptLearnEvent(serverPlayer, info, persistent)).isCanceled()) {
        // ③ addKnowledge 会归一化 + 触发 PlayerKnowledgeChangeEvent，但【不会同步客户端】
        if (provider.addKnowledge(persistent)) {
            // ④ 必须显式同步
            provider.syncKnowledgeChange(serverPlayer, persistent, true);
        }
    }
}
```

**关于第 ② 步（是否 fire `PlayerAttemptLearnEvent`）**：
- **fire 它更"礼貌"**：其他 mod 可能监听此事件来禁止学习（例如禁学某些物品）。ProjectE 的转换桌路径会 fire（`TransmutationInventory.java:91`）。
- **不 fire 也是 ProjectE 内部接受的模式**：管理员命令路径 `KnowledgeCMD.java:111-134` 就直接 `addKnowledge`，没 fire。
- **建议**：fire。成本极低，且能让"禁止学习某物品"类的 mod 对你的网络同样生效。注意它**只能取消"学习"这一步，不能取消你的 EMC 折算**——两者在你的代码里应分开处理。

**关于线程/侧（安全性）**：
- **只在服务端调**。`addKnowledge` 在客户端也是"有效"的（会改客户端镜像，见 `KnowledgeSyncChangePKT.java:38`），但**不会被同步回服务端**，会造成永久性客户端/服务端不一致。
- **线程**：调"服务端逻辑线程"——即 `ServerLevel` 的 tick、BlockEntity 的 `tickServer`、`ServerPlayer` 的交互回调、或 NeoForge 的 `IPayloadHandler`（`PayloadRegistrar`/`PacketDistributor` 注册的 handler，NeoForge 已保证在主线程执行）。**不要**在异步线程 / `CompletableFuture` 里调。
- **不要缓存 `IKnowledgeProvider`**：`KnowledgeImpl` 是每次 `getCapability` 新建的轻量视图（`PECore.java:184`），且 `ServerPlayer` 在重生后会换实例。需要时重新 `getCapability`。
- **`PlayerKnowledgeChangeEvent` 监听者注意**：该事件也可能来自离线相关路径，事件只带 `UUID`（`PlayerKnowledgeChangeEvent.java:15-31`）。你的监听器里如果需要 `ServerPlayer`，要用 `server.getPlayerList().getPlayer(uuid)` 查找并判空。
- **`EMCRemapEvent`**：必须监听并失效你自己缓存的"物品→EMC"表，同时可能要把你网络里"已学习"的判定重新对齐（ProjectE 会在此时 prune 掉失效知识，`EMCMappingHandler.java:134`）。

### (c) 客户端显示"已学习列表"的推荐数据流

**方案 A（推荐，零额外包）：客户端直接读本地玩家的 `IKnowledgeProvider`。**

适用条件：界面展示的是**当前玩家自己的**已学习列表（最常见）。

```java
// 客户端
LocalPlayer player = Minecraft.getInstance().player;
IKnowledgeProvider provider = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY);
Set<ItemInfo> learned = provider.getKnowledge();     // 已由 PE 同步好
long emc = IEMCProxy.INSTANCE.getValue(info);        // EMC 值两端一致，客户端可直接查
```

优点：ProjectE 已经保证同步（登录 `PlayerEvents.java:77` / 重生 `:47` / 换维度 `:63` / 增量 `KnowledgeSyncChangePKT`），你什么都不用做。

**必须注意的三点**：
1. **不要每帧调用 `getKnowledge()`**：`fullKnowledge` 玩家会每次新建一个含全部映射物品的 `HashSet`（`KnowledgeImpl.java:199` + `EMCMappingHandler.java:195`）。请在打开界面时取一次快照缓存，并在 `PlayerKnowledgeChangeEvent`（服务端侧广播给你自己的包，或在客户端用 `KnowledgeSyncChangePKT` 到达时）失效。
2. **客户端只读**：绝不调用 `addKnowledge` / `removeKnowledge` / `clearKnowledge` / `setEmc`。
3. **返回值是 `Collections.unmodifiableSet`**（`KnowledgeImpl.java:202,204`），要改就自己 `new HashSet<>(...)`。

**方案 B：需要"网络聚合"或"展示其他玩家的知识"时——必须自己发包。**

服务端侧（数据源）：

```java
// 在线玩家：直接用能力；离线玩家：用 ITransmutationProxy（内部会读 playerdata NBT 并返回只读视图）
IKnowledgeProvider provider = ITransmutationProxy.INSTANCE.getKnowledgeProviderFor(uuid);
// 注意：离线时返回的 provider 是 immutable 的（addKnowledge 恒 false），见 TransmutationOffline.java:116-118
Set<ItemInfo> learned = new HashSet<>(provider.getKnowledge());   // 复制，别持有引用
```

如果只关心在线玩家，直接：`server.getPlayerList().getPlayer(uuid).getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)`。

发包（NeoForge 21.1 风格，参考 `PacketHandler.java:55-59, 87-90`）：

```java
// 载荷：用 ProjectE 现成的 ItemInfo.STREAM_CODEC（ItemInfo.java:48-52）
// public static final StreamCodec<RegistryFriendlyByteBuf, ItemInfo> STREAM_CODEC = ...
public record NetworkKnowledgePKT(List<ItemInfo> items) implements CustomPacketPayload { /* ... */ }

// 注册（mod event bus）
modEventBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
    PayloadRegistrar registrar = event.registrar("1");
    registrar.playToClient(NetworkKnowledgePKT.TYPE, NetworkKnowledgePKT.STREAM_CODEC, NetworkKnowledgePKT::handle);
});

// 发送
PacketDistributor.sendToPlayer(serverPlayer, new NetworkKnowledgePKT(items));
```

**增量更新建议**：在服务端监听 `PlayerKnowledgeChangeEvent`（`NeoForge.EVENT_BUS`，`KnowledgeImpl.java:72` 触发），用 `event.getPlayerUUID()` 定位玩家，只在**知识发生增删**时推送增量包，而不是每次全量。

**推荐组合**：

| 场景 | 数据流 |
| --- | --- |
| 展示"我的已学习物品" | 客户端直读本地 provider（方案 A），配 `PlayerKnowledgeChangeEvent` → 你自己发一个轻量"刷新"包（或干脆在 `KnowledgeSyncChangePKT` 到达后刷新） |
| 展示"网络内所有玩家已学习的并集" | 服务端聚合 → 自定义 `CustomPacketPayload`（用 `ItemInfo.STREAM_CODEC`）→ `PacketDistributor.sendToPlayer` |
| 展示"某离线玩家的知识" | 服务端 `ITransmutationProxy.INSTANCE.getKnowledgeProviderFor(uuid)` → 打包 → 发送 |
| 兑换取出（点一个条目） | **客户端只发请求包**（携带 `ItemInfo` + 数量）→ **服务端**校验 `hasKnowledge` + 你的池子余额 → 扣池子 → `player.getInventory().placeItemBackInInventory` 或直接产出 → 回包更新 UI |

**兑换的服务端校验模板**（对齐凝聚器与转换桌语义，`CondenserBlockEntity.java:145-158` + `SlotOutput.java:26-51` + `TransmutationInventory.java:414-421`）：

```java
// 服务端处理请求
ItemInfo info = packet.info();
long unitCost = IEMCProxy.INSTANCE.getValue(info);              // 取出价 = getValue（凝聚器同款）
if (unitCost <= 0) return;                                     // 无 EMC 值，拒绝
if (!provider.hasKnowledge(IEMCProxy.INSTANCE.getPersistentInfo(info))) return;  // ← 关键：必须自己补这条检查
BigInteger total = BigInteger.valueOf(unitCost).multiply(BigInteger.valueOf(count));
if (myNetworkEmc.compareTo(total) < 0) return;                  // 你的池子余额
myNetworkEmc = myNetworkEmc.subtract(total);
// 产出物品（用 persistent.createStack()，参考 CondenserBlockEntity.pushStack():160-165）
```

---

## 11. 未确认事项

1. **ProjectE 在 CurseForge 的数值 projectId 与具体 fileId**：本仓库内**没有任何**地方记录（grep `226410`、`curse.maven:projecte` 均无匹配）。`build.gradle:234` 只证明了它使用 `https://www.cursemaven.com` 仓库。**请在 [CurseForge 文件页](https://www.curseforge.com/minecraft/mc-mods/projecte/files) 打开目标文件后，从 URL / 页面确认 `projectId` 和 `fileId`，不要凭记忆填。**
2. **是否存在官方 CI 自动发布到某个 Maven**：本克隆**不含 `.github` 目录**（`Get-ChildItem -Force` 结果：`.git`、`buildSrc`、`gradle`、`previews`、`src`），也没有其它 CI 配置，**无法确认**。若上游 CI 有发布，其坐标不可从本仓库推断。
3. **ProjectE 是否在 Modrinth 上有 1.21.1 / 1.1.0 版本**：`README.md:13-14` 只给了 CurseForge 下载入口，`update.json` 的 homepage 也只有 CurseForge，**未确认**。若要用 Modrinth Maven，需自行核对。
4. **`MappingConfig` 生成的最终配置文件里各 processor 的 `enabled` / `persistent` 实际值**：由运行时生成的 `config/projecte/mapping.toml`（注意 `MappingConfig.getConfigType()` 是 `Type.SERVER`，`MappingConfig.java:159-162`）决定。源码层面只能确认**代码默认值**（`EnchantmentProcessor` 默认禁用，其余默认启用）。**建议在目标整合包里实际打开该配置文件核对。**
5. **`data_component_whitelist` 标签由整合包/其他 mod 扩展后的实际内容**：当前 datagen 输出为空（`src/datagen/generated/data/projecte/tags/item/data_component_whitelist.json:1-3`），但运行时可被数据包覆盖。**未确认**在特定整合包中的最终值。
6. **`getValue` 的 processor 完整执行顺序**：由 `AnnotationHelper.getDataComponentProcessors()` 按 `@DataComponentProcessor(priority=...)` 排序（`DataComponentManager.java:22-27`、`DataComponentProcessor.java:15-20`）。本报告只确认了 `DamageProcessor` 显式声明 `priority = Integer.MAX_VALUE`（最先跑，`DamageProcessor.java:11`），其余 processor 的具体相对顺序**未逐一展开确认**；如需精确复现，请读 `src/main/java/moze_intel/projecte/utils/AnnotationHelper.java` 的排序逻辑。

---

*本报告全部结论均来自本地源码 `C:\mc\mcwj\mod-learn\reference\ProjectE`（分支 `mc1.21.1`，commit `f432b0c`），未修改 `reference/` 下任何文件。*
