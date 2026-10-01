package com.zhuyuhang.beyondemc.emc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.IStackRender;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.LongStackKey;
import com.zhuyuhang.beyondemc.BeyondEmc;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

/**
 * 物化物品条目的资源键：维度网络里"某物品的 EMC 可兑换份"的身份。
 *
 * <h2>它解决什么问题</h2>
 * 0.2 里"已学习但无库存"的物品只是客户端 {@code VirtualEntryProvider} 现场算出来的显示行，
 * 服务端不认识（架构文档风险 R10「幽灵物品」）。0.3 把它们变成<b>真实写进
 * {@code UnifiedStorage} 的条目</b>，就需要一个键类型来标识"是哪个物品"。
 *
 * <h2>为什么不用 BD 原生的 {@code ItemStackKey}</h2>
 * BD 的存储是 {@code key → long} 的无序映射（{@code AbstractUnorderedStackHandler.java:53-71}）。
 * 若复用 {@code ItemStackKey}，物化出来的钻石会和玩家<b>真实存入的钻石合并成同一个数量</b>，
 * 既无法区分，也无法判断抽取时该不该扣 EMC；回退到 0.2 后更会直接变成<b>免费真实库存</b>。
 * 用本模组自定义的键类型则：落进独立 type bucket，且回退/卸载时因类型未注册而被 BD 静默丢弃
 * （{@code IStackKey.CODEC} 的 dispatch 找不到类型 → {@code deserializeNBT} 的
 * {@code catch(Throwable)} 吞掉，{@code AbstractUnorderedStackHandler.java:889-898}）——不超发、不坏档。
 *
 * <p><b>⚠️ 0.3.2 起「独立 bucket ⇒ 第三方管道看不见」这条已被主动放宽</b>：
 * {@code ItemUnifiedStorageHandlerMixin} 在该能力桥的<b>原生可视槽之后</b>追加了一段<b>只读物化区</b>，
 * 让第三方管道（Create 蓝图大炮、AE2/RS 等）也能"看见"并取用物化物品。
 * 但<b>收费红线不变</b>：真抽取一律导回 {@code UnifiedStorage.extract(IStackKey,long,boolean,boolean)}，
 * 从而命中 {@code InterfaceWithdrawService} 的扣费铸造钩子；模拟抽取（{@code simulate=true}）只读不算钱。
 * 任何绕过该出口、直接改写桶的"优化"都会重开 0.3.0 已修的零扣费缺口（{@code S-0.3-7}）。
 * 取证、设计、风险，以及"为什么有序版 {@code ItemStackTypedHandler} 刻意不动"，见
 * {@code docs/plan/CREATE-INTEGRATION.md}。
 *
 * <h2>⚠️ 三条必须守住的不变式</h2>
 * <ol>
 *   <li><b>必须覆写 {@code equals} / {@code hashCode}，按 {@link ItemInfo} 比较。</b>
 *       基类 {@code LongStackKey.equals} 比较的是 <b>{@code getTypeId()}</b>
 *       （{@code LongStackKey.java:98-115}），而所有 {@code EmcItemKey} 实例的 typeId 相同 ⇒
 *       不覆写会让<b>钻石与铁的物化条目塌缩成同一个键</b>，被 {@code HashMap} 合并成一条数。
 *       这是本类最严重的一处风险。</li>
 *   <li><b>{@code codec()} 必须返回"有字段"的 MapCodec</b>，且必须用 {@code ItemInfo.MAP_CODEC}
 *       （不能用 {@code ItemInfo.CODEC} —— {@code Codec} 放不进 {@code dispatch} 要求的 MapCodec 位），
 *       否则读档时解不出身份。</li>
 *   <li><b>{@code getTags()} 委托物品自身标签</b>（界面"按标签搜索"依赖它），
 *       代价是物化条目会进入 BD 的 {@code tag2stackMap}、从而可被 {@code extract(TagKey,…)} 命中 ——
 *       必须配合 {@code InterfaceWithdrawService} 的"改道收费"护栏，否则会零扣费被抽走。</li>
 * </ol>
 *
 * <p><b>注册</b>：必须通过 {@link EmcRegistration} 在 {@code FMLCommonSetupEvent} 注册，且早于任何
 * 网络反序列化 —— 否则读档时该类型的条目会被静默丢弃。
 */
public class EmcItemKey extends LongStackKey<EmcItemType> {

    /** 类型的唯一标识。与 {@link EmcStackKey#ID} 区分开，决定 type bucket 与 NBT 的 {@code type} 字段。 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(BeyondEmc.MOD_ID, "stack_type/emc_item");

    /**
     * 原型单例，仅用于注册。
     *
     * <p>BD 的 {@code STREAM_CODEC} / {@code CODEC} 都是"先读 typeId、再让注册的原型去解负载"
     * （{@code IStackKey.java:22-58}），所以注册的实例本身就是反序列化工厂。
     * 它携带的物品身份是空气，没有任何语义 —— 真正的实例由 {@link #deserialize} 等构造。
     */
    public static final EmcItemKey INSTANCE = new EmcItemKey(ItemInfo.fromItem(Items.AIR));

    /**
     * 有字段编解码器：直接复用 ProjectE {@link ItemInfo} 自带的 {@code MAP_CODEC}。
     *
     * <p>不手写的原因：{@code ItemInfo.MAP_CODEC} 已经正确处理了 {@code DataComponentPatch}
     * （组件差异），手写既容易漏、又会与 ProjectE 的身份归一规则不一致。
     */
    public static final MapCodec<EmcItemKey> TYPE_CODEC =
            ItemInfo.MAP_CODEC.xmap(EmcItemKey::new, EmcItemKey::info);

    /** 本类型的独立 Codec（{@code serializeNBT} / {@code deserializeNBT} 用）。 */
    public static final Codec<EmcItemKey> CODEC = TYPE_CODEC.codec();

    private final ItemInfo info;

    /** {@link #getVanillaMaxStackSize()} 的缓存（-1 = 未计算）。 */
    private transient int vanillaMaxCache = -1;

    /** 渲染用的物品栈缓存。 */
    private transient ItemStack renderStackCache;

    public EmcItemKey(ItemInfo info) {
        this.info = info;
        // LongStackKey 要求 stack 非空（getSourceClass()/getReadOnlyStack() 会直接用它）
        this.stack = new EmcItemType(info, 1L);
    }

    /** 本条目代表的物品身份（已归一化）。 */
    public ItemInfo info() {
        return info;
    }

    @Override
    public MapCodec<EmcItemKey> codec() {
        return TYPE_CODEC;
    }

    @Override
    public ResourceLocation getTypeID() {
        return ID;
    }

    /**
     * 按模组 id 排序的分组依据。
     *
     * <p><b>必须返回物品自身的命名空间</b>，不是本模组的 id：BD 的 {@code SORT_MODID} 与
     * "按模组搜索"直接用这个值（{@code ClientNetStorage.java:190-300}），返回 {@code beyondemc}
     * 会让所有物化条目挤进同一个分组，搜索也搜不到真正的模组。
     */
    @Override
    public String getModId() {
        try {
            return BuiltInRegistries.ITEM.getKey(info.getItem().value()).getNamespace();
        } catch (Throwable t) {
            return BeyondEmc.MOD_ID;
        }
    }

    @Override
    public EmcItemKey getEmpty() {
        return INSTANCE;
    }

    /**
     * 覆写为物品自身的原版最大堆叠数（与 {@code ItemStackKey} 对齐）。
     *
     * <p>{@link EmcStackKey} 刻意<b>不</b>覆写（要让 EMC 池不被单次操作截断，架构风险 R6），
     * 但那条理由<b>不适用于物品型键</b>：本值在 BD 里是"单次点击/单个接口槽位的取量"
     * （{@code DisorderedStackTypedSlot}、{@code NetInterfaceAccess.java:125}、
     * {@code AbstractStackTypedSlot.java:165} 等）。若保持 {@code Long.MAX_VALUE}，
     * 界面"一次拿一组"会变成"一次拿全部"，接口一周期也会尝试抽干整条目化条目。
     */
    @Override
    public long getVanillaMaxStackSize() {
        int cached = vanillaMaxCache;
        if (cached > 0) {
            return cached;
        }
        int size;
        try {
            size = Math.max(1, info.createStack().getMaxStackSize());
        } catch (Throwable t) {
            size = 64;
        }
        vanillaMaxCache = size;
        return size;
    }

    /**
     * 覆写为"同一种物品"的判定：{@link ItemInfo} 相等。
     *
     * <p>基类实现比的是 {@code getTypeId()}，对本类型而言等于"所有物化条目都相同"，
     * 会让 BD 的模糊抽取（{@code UnifiedStorage.extract(IStackKey,…,fuzzy=true)} 用
     * {@code slotIndex.stream().filter(x -> x.isSame(fuzzyKey))}）命中错误的物品。
     */
    @Override
    public boolean isSame(IStackKey<?> other) {
        return other instanceof EmcItemKey k && info.equals(k.info);
    }

    /** 本类型没有"类型级 vs 组件级"之分：{@link ItemInfo} 本身就含组件。 */
    @Override
    public boolean isSameTypeSameComponents(IStackKey<?> other) {
        return isSame(other);
    }

    /**
     * ⚠️ 必须覆写。基类 {@code LongStackKey.equals} 比 {@code getTypeId()}，
     * 不覆写则所有物品塌缩成同一个键（见类注释不变式 1）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EmcItemKey other)) {
            return false;
        }
        return info.equals(other.info);
    }

    @Override
    public int hashCode() {
        return 31 + info.hashCode();
    }

    @Override
    public @Nullable KeyAmount fromStackObject(Object stack) {
        if (stack instanceof EmcItemType emcItemType) {
            return new KeyAmount(new EmcItemKey(emcItemType.getInfo()), emcItemType.getStackCount());
        }
        return null;
    }

    @Override
    public @Nullable EmcItemKey fromSourceObject(Object key, DataComponentPatch ignored) {
        if (key instanceof EmcItemType emcItemType) {
            return new EmcItemKey(emcItemType.getInfo());
        }
        if (key instanceof ItemInfo itemInfo) {
            return new EmcItemKey(itemInfo);
        }
        return null;
    }

    /** 与 {@link EmcStackKey} 同构：返回自身的 {@code stack}（{@link EmcItemType}）。 */
    @Override
    public @NotNull Object getSource() {
        return this.stack;
    }

    @Override
    public EmcItemType getEmptyStack() {
        return new EmcItemType(info, 0L);
    }

    @Override
    public boolean hasTag(TagKey<?> tagKey) {
        if (tagKey == null) {
            return false;
        }
        if (!tagKey.isFor(Registries.ITEM)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        TagKey<Item> itemTag = (TagKey<Item>) tagKey;
        try {
            return info.getItem().is(itemTag);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 委托物品自身的标签。
     *
     * <p>界面"按标签搜索"依赖它（{@code ClientNetStorageSearchHelper}）。
     * ⚠️ 连带后果：BD 存储会把 {@code key.getTags()} 全部登记进 {@code tag2stackMap}
     * （{@code AbstractUnorderedStackHandler.java:736-738}），使本键可被
     * {@code extract(TagKey,…)} 命中 —— 因此 {@code InterfaceWithdrawService} 对
     * {@code EmcItemKey} 必须"改道收费"而不是放行。
     */
    @Override
    public Stream<? extends TagKey<?>> getTags() {
        try {
            return info.getItem().tags();
        } catch (Throwable t) {
            return Stream.empty();
        }
    }

    // ------- 网络序列化：只写 ItemInfo 负载（typeId 由 IStackKey.STREAM_CODEC 写） -------

    @Override
    public void serialize(net.minecraft.network.RegistryFriendlyByteBuf buf) {
        ItemInfo.STREAM_CODEC.encode(buf, info);
    }

    @Override
    public @NotNull EmcItemKey deserialize(net.minecraft.network.RegistryFriendlyByteBuf buf) {
        return new EmcItemKey(ItemInfo.STREAM_CODEC.decode(buf));
    }

    // ------- NBT：写/读 ItemInfo（与 ItemStackKey.serializeNBT 同构） -------

    @Override
    public @NotNull CompoundTag serializeNBT(HolderLookup.Provider levelRegistryAccess) {
        try {
            var ops = levelRegistryAccess.createSerializationContext(NbtOps.INSTANCE);
            return CODEC.encodeStart(ops, this)
                    .resultOrPartial(err -> BeyondEmc.LOGGER.warn(
                            "[BeyondEMC] EmcItemKey 序列化失败: {}", err))
                    .map(nbt -> nbt instanceof CompoundTag ct ? ct : new CompoundTag())
                    .orElseGet(CompoundTag::new);
        } catch (Throwable t) {
            BeyondEmc.LOGGER.error("[BeyondEMC] EmcItemKey 序列化异常", t);
            return new CompoundTag();
        }
    }

    /**
     * ⚠️ 与接口声明的 {@code @NotNull} 有出入：解析失败时返回 {@code null}，
     * 表示"这份 NBT 不构成本键"。
     *
     * <p>这样做的理由：物品身份无法用"空键"表达（{@link LongStackKey#isEmpty()} 恒为 false），
     * 若退回某个占位实例就等于把损坏数据静默改写成另一个物品 —— 那是数据损坏，比返回 null 更糟。
     *
     * <p>安全性：BD 自身的存储读写走的是 {@code IStackKey.CODEC}（{@code AbstractUnorderedStackHandler.java:840}），
     * <b>不会</b>调用本方法；BD 全仓库对 {@code key.deserializeNBT} 没有任何调用点（已 grep 核实）。
     * 因此返回 null 不会在既有链路上被解引用。
     */
    @Override
    public @Nullable EmcItemKey deserializeNBT(CompoundTag nbt, HolderLookup.Provider levelRegistryAccess) {
        try {
            var ops = levelRegistryAccess.createSerializationContext(NbtOps.INSTANCE);
            return CODEC.parse(ops, nbt)
                    .resultOrPartial(err -> BeyondEmc.LOGGER.warn(
                            "[BeyondEMC] EmcItemKey 反序列化失败: {} | keys={}", err, nbt.getAllKeys()))
                    .orElse(null);
        } catch (Throwable t) {
            BeyondEmc.LOGGER.error("[BeyondEMC] EmcItemKey 反序列化异常 | keys={}", nbt.getAllKeys(), t);
            return null;
        }
    }

    // ------- 渲染 -------

    @Override
    public @NotNull IStackRender getRender() {
        return EmcItemKeyRender.INSTANCE;
    }

    /**
     * 供渲染器使用的物品栈（数量恒为 1）。
     *
     * <p><b>刻意不复用基类的 {@code getRenderStack()}</b>：{@code LongStackKey.getRenderStack()}
     * 返回的是本键自己的 {@code stack}（即 {@link EmcItemType}）并把它的数量置 1
     * （{@code LongStackKey.java:26-38}），那<b>不是</b> {@link ItemStack}，
     * 渲染器无法拿它画图标。所以另开一个带缓存的入口。
     */
    public @NotNull ItemStack createRenderStack() {
        ItemStack cached = renderStackCache;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        ItemStack built;
        try {
            built = info.createStack();
            built.setCount(1);
        } catch (Throwable t) {
            built = ItemStack.EMPTY;
        }
        if (!built.isEmpty()) {
            renderStackCache = built;
        }
        return built;
    }
}
