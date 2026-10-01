package com.zhuyuhang.beyondemc.mixin.create;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.exchange.CanonicalExchange;
import com.zhuyuhang.beyondemc.materialize.MaterializeQuote;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 让 Create 的蓝图大炮能「看见」维度网络里**已物化**的物品。
 *
 * <h2>断点在哪（本轮由反汇编 Create 6.0.10 逐条确认）</h2>
 * BD 的 {@code NetedSchematicannonItemHandler} 只暴露大炮物料清单（checklist）需要的物品 +
 * 火药，并对网络做**精确键查询** {@code getStackByKey(new ItemStackKey(snap))}。
 * BD 的 {@code getStackByKey} 是 {@code HashMap.getOrDefault}，而 {@code ItemStackKey.equals}
 * 只认同类 —— 物化条目的键是 {@code EmcItemKey}，两者 hash 与 equals 都对不上，
 * 于是 {@code getStackInSlot} 恒返回 {@code EMPTY}。
 *
 * <p>而 Create 判定「这个槽有料吗」的唯一依据是
 * <b>{@code grabItemsFromAttachedInventories} / {@code updateChecklist} 里对
 * {@code extractItem(slot, …, true)}（模拟抽取）的返回值</b>：
 * <ul>
 *   <li>{@code updateChecklist}：{@code if (extractItem(i,1,true).isEmpty()) continue;}
 *       —— 模拟为空就跳过，连 {@code getStackInSlot} 都不会读；</li>
 *   <li>{@code ItemHelper.extract}：先跑一遍 {@code simulate=true} 估量，够了才跑
 *       {@code simulate=false} 真抽。</li>
 * </ul>
 * 所以模拟抽取说"没料"，真抽取那一遍<b>永远不会被调用</b>。
 *
 * <h2>为什么只补「模拟」与「显示」两处，真抽取一行都不改</h2>
 * 真抽取（{@code simulate=false}）走的已经是通的链路：
 * {@code storage.extract(new ItemStackKey(snap), count, false, false)} →
 * {@code UnifiedStorage.extract} → {@link com.zhuyuhang.beyondemc.exchange.InterfaceWithdrawService}
 * 钩子扣 EMC + 铸造 {@code ItemStackKey} → 返回的键恰好是 {@code ItemStackKey}，
 * 通过 BD 的 {@code instanceof ItemStackKey} 守卫 → 交出真实物品。
 * 它唯一的"毛病"是<b>走不到</b>，因为上一段模拟已经把它筛掉了。
 *
 * <h2>⚠️ 两条红线</h2>
 * <ol>
 *   <li><b>模拟路径绝不产生任何副作用</b>：本类只读存储、只算数量，不扣 EMC、不写存储。
 *       数量上限取自 {@link MaterializeQuote.Policy#deliverable}，与真实扣费段
 *       <b>同一个算式</b>，因此不会"模拟报得比真能给的多"。</li>
 *   <li><b>真抽取不改</b>：绝不在这里直接交付物品 —— 那会绕过收费链，重开
 *       {@code S-0.3-7} 的零扣费缺口。真交付只能发生在 BD 的原生
 *       {@code extractItem} 里，经由 {@code UnifiedStorage.extract}。</li>
 * </ol>
 *
 * <p>另：{@code getSlots()} <b>刻意不注入</b> —— {@code allSchematicannonItems} 快照
 * 本来就包含 checklist 里的全部物品（不看网络有没有），槽位数已经覆盖，无需改动。
 *
 * <p>本类<b>不引用任何 Create 类型</b>，注入点签名全是 Minecraft 类型；
 * Create 缺席时由 {@code BeyondEmcCreateMixinPlugin} 整体跳过。
 */
@Mixin(targets = "com.wintercogs.beyonddimensions.integration.module.create.block.entity."
        + "SchematicannonPathWayBlockEntity$NetedSchematicannonItemHandler")
public abstract class NetedSchematicannonItemHandlerMixin {

    /** BD 的槽位快照（大炮 checklist 需要的 ItemStack 列表，末尾附一份火药）。 */
    @Shadow
    private List<ItemStack> stacksSnapshot;

    /** 该「蓝图接口」所连的维度网络。 */
    @Shadow
    private DimensionsNet net;

    /**
     * 显示值：原生查不到时，用物化条目补上。
     *
     * <p>Create 的 {@code updateChecklist} 会用 {@code getStackInSlot(i)} 把物品收进
     * 「已收集（gathered）」表用于界面显示。不补这一处，玩家会看到大炮认为
     * "有料可抽"但「已收集」却一直是 0。
     */
    @Inject(method = "getStackInSlot", at = @At("RETURN"), cancellable = true)
    private void beyondemc$exposeMaterializedStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (!cir.getReturnValue().isEmpty()) {
            return; // 原生有真实库存（ItemStackKey 桶命中）→ 一律不动
        }
        ItemStack exposed = this.beyondemc$materializedStack(slot);
        if (!exposed.isEmpty()) {
            cir.setReturnValue(exposed);
        }
    }

    /**
     * 模拟报量：原生模拟抽取为空、且本次确实是模拟时，按报价补报可交付量。
     *
     * <p><b>{@code simulate == false} 一律直接返回</b> —— 真抽取交给 BD 原生链路
     * （它会走 {@code UnifiedStorage.extract}，从而命中扣费铸造钩子）。
     */
    @Inject(method = "extractItem", at = @At("RETURN"), cancellable = true)
    private void beyondemc$reportMaterializedForSimulate(int slot, int count, boolean simulate,
                                                        CallbackInfoReturnable<ItemStack> cir) {
        if (!simulate || count <= 0) {
            return; // 真抽取：绝不在这里交付（见类注释红线 2）
        }
        if (!cir.getReturnValue().isEmpty()) {
            return; // 原生模拟已经报出真实库存的量 → 以原生的为准
        }
        try {
            if (this.net == null || this.stacksSnapshot == null) {
                return;
            }
            if (slot < 0 || slot >= this.stacksSnapshot.size()) {
                return;
            }
            ItemStack snap = this.stacksSnapshot.get(slot);
            if (snap == null || snap.isEmpty()) {
                return;
            }
            CanonicalExchange.Resolved resolved = CanonicalExchange.resolve(snap);
            if (resolved == null) {
                return; // 身份归一失败 → 保守报"没料"
            }
            MaterializeQuote.Policy policy = MaterializeQuote.policy(this.net, resolved.info());
            if (policy == null) {
                return; // 策略层不允许（未学习 / 买不到价 / 配置关闭）→ 报"没料"
            }
            long amount = MaterializeQuote.materializedAmount(this.net, resolved.info());
            long give = policy.deliverable(count, amount);
            if (give <= 0L) {
                return;
            }
            // 数量夹在 [1, 原版堆叠数]：物化量可能有上百万，直接塞进 ItemStack 会越界/显示错乱
            ItemStack out = MaterializeQuote.displayStack(resolved.info(), give);
            if (!out.isEmpty()) {
                cir.setReturnValue(out);
            }
        } catch (Throwable t) {
            // 模拟路径宁可不报量，也不要把异常抛进 Create 的判定循环
        }
    }

    /**
     * 取该槽对应的「物化条目」显示栈；没有则返回空栈。
     *
     * <p>数量恒定在 {@code min(物化量, 原版堆叠数)}：物化量可能有上百万，
     * 直接把 {@code int} 数量塞给第三方容器会引发越界与显示错乱。
     */
    @Unique
    private ItemStack beyondemc$materializedStack(int slot) {
        try {
            if (this.net == null || this.stacksSnapshot == null) {
                return ItemStack.EMPTY;
            }
            if (slot < 0 || slot >= this.stacksSnapshot.size()) {
                return ItemStack.EMPTY;
            }
            ItemStack snap = this.stacksSnapshot.get(slot);
            if (snap == null || snap.isEmpty()) {
                return ItemStack.EMPTY;
            }
            CanonicalExchange.Resolved resolved = CanonicalExchange.resolve(snap);
            if (resolved == null) {
                return ItemStack.EMPTY;
            }
            long amount = MaterializeQuote.materializedAmount(this.net, resolved.info());
            if (amount <= 0L) {
                return ItemStack.EMPTY;
            }
            return MaterializeQuote.displayStack(resolved.info(), amount);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }
}
