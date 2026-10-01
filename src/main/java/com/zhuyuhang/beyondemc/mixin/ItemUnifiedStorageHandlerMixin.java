package com.zhuyuhang.beyondemc.mixin;

import com.wintercogs.beyonddimensions.api.capability.helper.unordered.ItemUnifiedStorageHandler;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.emc.EmcItemKey;
import com.zhuyuhang.beyondemc.materialize.MaterializeQuote;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 BD 的**通用物品能力桥（无序版）**也能「看见」物化条目。
 *
 * <h2>为什么天生看不见</h2>
 * BD 的存储按 key 的**类型 id 分桶**。{@link ItemUnifiedStorageHandler} 只枚举
 * {@code ItemStackKey.ID}（{@code beyonddimension:stack_type/item}）那一个桶：
 * <pre>
 *   getSlots()       → storage.getBucket(ItemStackKey.ID).map(size …)
 *   getStackInSlot() → storage.getBucket(ItemStackKey.ID) 的第 slot 个 key
 *   extractItem()    → 同上取 key，再 storage.extract(key, …)
 * </pre>
 * 而物化条目在 {@code beyondemc:stack_type/emc_item} 桶（见 {@link EmcItemKey#ID}）——
 * 结构性跳过，跟"这个物品值多少 EMC"毫无关系。
 *
 * <h2>注入设计：在原生可视槽之后追一段「只读物化区」</h2>
 * 记原生 {@code getSlots()} 的返回值为 {@code base}，则我们的区段是
 * {@code [base, base + emc 桶槽位数)}。三个方法一致处理：
 * <ul>
 *   <li>{@code getSlots()}：返回值 {@code + emcCount}；</li>
 *   <li>{@code getStackInSlot(slot)}：原生返回空 <b>且</b> slot 落在区段内时，回填物化条目；</li>
 *   <li>{@code extractItem(slot, count, simulate)}：同理。</li>
 * </ul>
 *
 * <p><b>原生槽位逐位不变</b>：区段严格从 {@code base} 之后开始，包括 BD 那个
 * 「容量预留槽」（非满时 {@code getSlots()} 会 {@code +1}，那是给第三方模组插物品用的）
 * —— 它属于 {@code base} 之内，本 Mixin 绝不触碰。
 *
 * <h2>为什么 {@code insertItem} / {@code setStackInSlot} 不用注入</h2>
 * 逐个核对过：{@code setStackInSlot} 对 {@code slot > 桶大小} 的分支会直接 {@code return}
 * （拒绝写），{@code insertItem} 本就忽略 slot 参数（BD 的存储是无槽位的，插哪儿都进网络）。
 * 所以我们的区段在这两个方法上**已经**是安全行为，多注入一处只会多一份出错面。
 *
 * <h2>⚠️ 红线：真抽取必须导回 {@code UnifiedStorage.extract}</h2>
 * 非 simulate 的抽取一律调
 * {@code storage.extract(new ItemStackKey(stack), count, false, false)}
 * （{@code UnifiedStorage.java:117-133}），从而命中
 * {@link com.zhuyuhang.beyondemc.exchange.InterfaceWithdrawService} 的
 * 「扣 EMC + 铸造」链。<b>绝不直接改桶</b> —— 那会重开 {@code S-0.3-7} 的零扣费缺口。
 *
 * <p>模拟抽取只读存储、只算数量（{@link MaterializeQuote}），不产生任何副作用，
 * 与 0.2 立下的「模拟绝不扣费」红线不冲突。
 *
 * <h2>⚠️ 为什么只动这一个类，不动它的「有序版」兄弟</h2>
 * BD 有两套能力体系，名字很像但完全不同：
 * <ul>
 *   <li>{@code CapabilityHelper.USHandlerMap} —— 包 <b>{@code UnifiedStorage}</b>（本类）。
 *       这是**维度网络**对外的物品视图，本 Mixin 改造的就是它；</li>
 *   <li>{@code CapabilityHelper.CommonHandlerMap} —— 包 <b>{@code StackHandler}</b>
 *       （{@code ItemStackTypedHandler}）。它包的是**机器自己**的固定槽位：
 *       熔炉的输入 / 燃料 / 输出槽、Create 装置搬运时的本地存储。</li>
 * </ul>
 * 后者的字段类型是 {@code StackHandler}，而 {@code UnifiedStorage} 与 {@code StackHandler}
 * 在 BD 里是<b>互不相交</b>的两个类（各自 {@code implements IStackHandler}，没有继承关系 ——
 * 这一点由 javac 直接判定 {@code instanceof} 不可能成立而确认）。
 * 也就是说：<b>那个视图底下永远不可能是维度网络</b>，拿不到 {@code DimensionsNet}、
 * 也就拿不到 EMC 余额 ⇒ 无法收费。往那里塞物化条目等于开一个零扣费送物品的口子
 * （正是 {@code S-0.3-7} 要堵的东西）。故<b>刻意不动</b>，保持它原生的"看不见"。
 * 详见 {@code docs/plan/CREATE-INTEGRATION.md} §3。
 */
@Mixin(ItemUnifiedStorageHandler.class)
public abstract class ItemUnifiedStorageHandlerMixin {

    @Shadow
    @Final
    private UnifiedStorage storage;

    // ---------------------------------------------------------------
    // getSlots: 原生值 + 物化区段长度
    // ---------------------------------------------------------------

    /** 在原生返回值之后追加物化区段。原生为 0 时（无 item 桶）也照样追加。 */
    @Inject(method = "getSlots", at = @At("RETURN"), cancellable = true)
    private void beyondemc$appendMaterializedSlots(CallbackInfoReturnable<Integer> cir) {
        int emc = this.beyondemc$emcCount();
        if (emc <= 0) {
            return;
        }
        cir.setReturnValue(cir.getReturnValueI() + emc);
    }

    // ---------------------------------------------------------------
    // getStackInSlot: 仅当外来 slot 落在物化区段内
    // ---------------------------------------------------------------

    @Inject(method = "getStackInSlot", at = @At("RETURN"), cancellable = true)
    private void beyondemc$exposeMaterialized(int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (!cir.getReturnValue().isEmpty()) {
            return; // 原生已有真实库存 → 一律不动
        }
        int raw = this.beyondemc$materializedIndex(slot);
        if (raw < 0) {
            return;
        }
        ItemStack shown = this.beyondemc$materializedDisplay(raw);
        if (!shown.isEmpty()) {
            cir.setReturnValue(shown);
        }
    }

    // ---------------------------------------------------------------
    // extractItem: 模拟报量 / 真抽取导回收费链
    // ---------------------------------------------------------------

    @Inject(method = "extractItem", at = @At("RETURN"), cancellable = true)
    private void beyondemc$extractMaterialized(int slot, int count, boolean sim,
                                              CallbackInfoReturnable<ItemStack> cir) {
        if (!cir.getReturnValue().isEmpty()) {
            return; // 原生已有真实库存 → 一律不动
        }
        int raw = this.beyondemc$materializedIndex(slot);
        if (raw < 0) {
            return;
        }
        ItemStack taken = this.beyondemc$takeMaterialized(raw, count, sim);
        if (!taken.isEmpty()) {
            cir.setReturnValue(taken);
        }
    }

    // ---------------------------------------------------------------
    // 共用的私有逻辑（@Unique：不会与目标类成员冲突）
    // ---------------------------------------------------------------

    /** 物化桶当前的槽位数量。 */
    @Unique
    private int beyondemc$emcCount() {
        try {
            if (this.storage == null) {
                return 0;
            }
            return this.storage.getBucket(EmcItemKey.ID)
                    .map(bucket -> bucket.size())
                    .orElse(0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 把外部 slot 映射到「物化区段内的索引」；不在区段内返回 -1。
     *
     * <p>{@code base} 用 {@code getSlots() - emcCount} 反推 —— 而 {@code getSlots()}
     * 已被本 Mixin 注入（返回值 = 原生 base + emcCount）。这样 {@code base} 的唯一来源
     * 就是 <b>BD 自己的算式</b>，我们不去复制它，BD 改了也不会错位。
     */
    @Unique
    private int beyondemc$materializedIndex(int slot) {
        int emc = this.beyondemc$emcCount();
        if (emc <= 0) {
            return -1;
        }
        int all = ((IItemHandler) (Object) this).getSlots();
        int idx = slot - (all - emc);
        return (idx >= 0 && idx < emc) ? idx : -1;
    }

    /** 物化区段第 {@code raw} 个槽对应的键；不是本模组的键则返回 null。 */
    @Unique
    private EmcItemKey beyondemc$emcKeyAt(int raw) {
        try {
            if (this.storage == null) {
                return null;
            }
            return this.storage.getBucket(EmcItemKey.ID)
                    .map(bucket -> (raw >= 0 && raw < bucket.size()) ? bucket.get(raw) : null)
                    .filter(key -> key instanceof EmcItemKey)
                    .map(key -> (EmcItemKey) key)
                    .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 某个物化槽的展示栈（数量夹在 [1, 原版堆叠数]）；不可交付则为空栈。 */
    @Unique
    private ItemStack beyondemc$materializedDisplay(int raw) {
        try {
            EmcItemKey key = this.beyondemc$emcKeyAt(raw);
            if (key == null) {
                return ItemStack.EMPTY;
            }
            DimensionsNet net = this.beyondemc$net();
            if (net == null) {
                return ItemStack.EMPTY;
            }
            ItemInfo info = key.info();
            // 与「能不能真取出来」用同一套判据：不可交付就不显示，避免"看得见取不到"的假象
            if (MaterializeQuote.policy(net, info) == null) {
                return ItemStack.EMPTY;
            }
            return MaterializeQuote.displayStack(info, MaterializeQuote.materializedAmount(net, info));
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 抽取物化槽。
     *
     * <p>模拟：只算量（{@link MaterializeQuote.Policy#deliverable}，与真实扣费同一算式）。
     * <br>真实：导回 {@code UnifiedStorage.extract(ItemStackKey, …)} → 命中扣费铸造链。
     */
    @Unique
    private ItemStack beyondemc$takeMaterialized(int raw, int count, boolean sim) {
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        try {
            EmcItemKey key = this.beyondemc$emcKeyAt(raw);
            if (key == null) {
                return ItemStack.EMPTY;
            }
            DimensionsNet net = this.beyondemc$net();
            if (net == null) {
                return ItemStack.EMPTY;
            }
            UnifiedStorage unified = net.getUnifiedStorage();
            if (unified == null) {
                return ItemStack.EMPTY;
            }
            ItemInfo info = key.info();

            if (sim) {
                MaterializeQuote.Policy policy = MaterializeQuote.policy(net, info);
                if (policy == null) {
                    return ItemStack.EMPTY;
                }
                long give = policy.deliverable(count, MaterializeQuote.materializedAmount(net, info));
                return MaterializeQuote.displayStack(info, give);
            }

            // 真正的交付：必须走 UnifiedStorage.extract（见类注释红线）
            ItemStack request = info.createStack();
            if (request.isEmpty()) {
                return ItemStack.EMPTY;
            }
            KeyAmount taken = unified.extract(new ItemStackKey(request), count, false, false);
            if (taken == null || taken.isEmpty()) {
                return ItemStack.EMPTY;
            }
            Object out = taken.toStack();
            return (out instanceof ItemStack is && !is.isEmpty()) ? is : ItemStack.EMPTY;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** 该存储所属的维度网络；取不到就一律不暴露（宁可没有，也不给零扣费的口子）。 */
    @Unique
    private DimensionsNet beyondemc$net() {
        try {
            return this.storage == null ? null : this.storage.getNet();
        } catch (Throwable t) {
            return null;
        }
    }
}
