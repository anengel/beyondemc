package com.zhuyuhang.beyondemc.materialize;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.emc.EmcItemKey;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 「这个物品能不能用网络 EMC 换出来、最多能换几个」的**唯一真相源**。
 *
 * <h2>为什么必须收口成一个类</h2>
 * 0.3.0 曾经出现过一次真实的刷物品漏洞，根因是两个入口（存入侧折算 / 兑换侧铸造）**各自**
 * 判断"这个物品该不该被折算"，标准不一致。修复时在
 * {@code InterfaceWithdrawService} 的注释里立下规矩：两侧必须**永远同源**。
 *
 * <p>本期新增了两条把物化条目暴露给第三方的路径（Create 蓝图接口、BD 通用物品能力桥）。
 * 它们要做的是**同一套**判断（已学习？能定价？余额够？），若各写一份，就是同一个坑再踩一次。
 * 所以把判断链整体搬进本类，所有调用方共用。
 *
 * <h2>前置链（全部命中才谈得上"能交付"）</h2>
 * <ol>
 *   <li>{@code net != null}；</li>
 *   <li>{@code BeyondEmcConfig.allowInterfaceWithdraw()} —— 关掉它时这里必须也返回不可交付，
 *       否则模拟抽取会**谎报有料**，调用方（如蓝图大炮）会一直等一个永远不来的物品；</li>
 *   <li>{@code EmcAvailability.isReady()} —— EMC 表未就绪时一律不兑换；</li>
 *   <li>{@code exchangeRequiresKnowledge()} 时要求 {@code NetKnowledgeStore.knows(net, info)}；</li>
 *   <li>购买价 {@code IEMCProxy.getValue(info) > 0}（取价抛异常同样视为不可交付）。</li>
 * </ol>
 *
 * <h2>为什么不在这里判 {@code EmcDepositHandler.skipReason}</h2>
 * 那一项判的是"这个 {@link ItemStack} 本身"（黑/白名单、带组件的物品），必须由调用方
 * 用**请求方给的那个 stack**去判，而不是用归一化重建出来的 stack —— 否则两者可能在
 * "带组件物品"的边界上分叉。所以那一项留在调用方（{@code InterfaceWithdrawService}）。
 *
 * <h2>安全性质</h2>
 * <ul>
 *   <li>本类**不做任何写操作**：不扣 EMC、不改存储。它只读余额与价格。</li>
 *   <li>因此"模拟抽取"可以安全地用它 —— 与 0.2 立下的"模拟绝不扣费"红线不冲突。</li>
 *   <li>数量上限取 {@code min(物化条目量, 余额 ÷ 购买价)}，与
 *       {@code InterfaceWithdrawService} 的真实扣费段完全同一套算式 ⇒ 模拟报量与
 *       真实交付量在数学上一致，不会出现"报得比给得多"。</li>
 * </ul>
 */
public final class MaterializeQuote {

    /**
     * 报价单。
     *
     * @param info       归一化后的物品身份（定价用的那个身份）
     * @param unitPrice  购买价（{@code IEMCProxy.getValue}）
     * @param balance    网络当前 EMC 余额
     * @param affordable 余额可负担的份数（{@code balance / unitPrice}）
     */
    public record Policy(ItemInfo info, long unitPrice, long balance, long affordable) {

        /**
         * 本次最多能交付几份。
         *
         * @param want             调用方希望的数量（{@code <= 0} 视为不要）
         * @param materializedAmount 该物品当前的物化条目量；真实库存路径请传 {@link Long#MAX_VALUE}
         *                           （真实库存不受物化量约束 —— 但那种情况不会走到本类）
         */
        public long deliverable(long want, long materializedAmount) {
            if (want <= 0L || affordable <= 0L || materializedAmount <= 0L) {
                return 0L;
            }
            return Math.max(0L, Math.min(want, Math.min(materializedAmount, affordable)));
        }
    }

    private MaterializeQuote() {
    }

    /**
     * 求出该身份的报价；任何一条前置不成立都返回 {@code null}（调用方据此判"不可交付"）。
     *
     * <p>返回 {@code null} 与"报价单里 {@code affordable == 0}"是两件事：
     * 前者是"这条路根本不通"（策略层拒绝），后者是"路通但这次买不起"。
     * {@code InterfaceWithdrawService} 对两者的收尾动作不同，必须区分。
     */
    public static @Nullable Policy policy(@Nullable DimensionsNet net, @Nullable ItemInfo info) {
        if (net == null || info == null) {
            return null;
        }
        // 与"真实抽取时钩子会放行"保持一致：关掉配置就必须一律报"不可交付"，
        // 不能在这里报有料、真抽取却交付 0（会把调用方卡在永远等料的状态）。
        if (!BeyondEmcConfig.allowInterfaceWithdraw()) {
            return null;
        }
        if (!EmcAvailability.isReady()) {
            return null;
        }
        if (BeyondEmcConfig.exchangeRequiresKnowledge() && !NetKnowledgeStore.knows(net, info)) {
            return null;
        }

        long unitPrice;
        try {
            unitPrice = IEMCProxy.INSTANCE.getValue(info); // 购买价
        } catch (Throwable t) {
            return null;
        }
        if (unitPrice <= 0L) {
            return null;
        }

        long balance = NetEmcAccessor.getEmc(net);
        long affordable = balance / unitPrice; // unitPrice > 0，不会除零
        return new Policy(info, unitPrice, balance, affordable);
    }

    /**
     * 「展示栈」的构造收口：把物化条目的身份 + 数量变成可以交给第三方容器的 {@link ItemStack}。
     *
     * <p><b>数量必须夹在 {@code [1, 原版堆叠数]}</b>：物化条目量可能有上百万，
     * 而 {@code IItemHandler} 的调用方（Create 的判定循环、各种自动化模组）会把它当
     * 真实堆叠数使用 —— 直接塞一个七位数进去会引发显示错乱与越界。
     *
     * @return 失败（身份取不出物品）时返回 {@link ItemStack#EMPTY}
     */
    public static @NotNull ItemStack displayStack(@NotNull ItemInfo info, long amount) {
        if (amount <= 0L) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack out = info.createStack();
            if (out.isEmpty()) {
                return ItemStack.EMPTY;
            }
            out.setCount((int) Math.max(1L, Math.min(amount, (long) out.getMaxStackSize())));
            return out;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 「物化量」的取法收口：给定身份，返回当前物化条目里还剩多少。
     *
     * <p>直接按 {@code EmcItemKey} 查存储（HashMap 精确查表，{@code getStackByKey}），
     * 而不是遍历 {@code ItemMaterializer.currentEntries()} 建整张表 —— 后者是每帧被高频调用的路径。
     *
     * @return 物化条目量；不存在或查询失败返回 0
     */
    public static long materializedAmount(@Nullable DimensionsNet net, @NotNull ItemInfo info) {
        if (net == null) {
            return 0L;
        }
        try {
            var storage = net.getUnifiedStorage();
            if (storage == null) {
                return 0L;
            }
            var ka = storage.getStackByKey(new EmcItemKey(info));
            if (ka == null || ka.isEmpty()) {
                return 0L;
            }
            // 只认物化条目自己的键：若某天存储返回了别的键，说明身份对不上，宁可报 0
            return (ka.key() instanceof EmcItemKey) ? Math.max(0L, ka.amount()) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
