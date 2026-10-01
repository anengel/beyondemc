package com.zhuyuhang.beyondemc.exchange;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeExtractHandler;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.ExtractContext;
import com.zhuyuhang.beyondemc.core.MintingGuard;
import com.zhuyuhang.beyondemc.emc.EmcDepositHandler;
import com.zhuyuhang.beyondemc.emc.EmcItemKey;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.materialize.ItemMaterializer;
import com.zhuyuhang.beyondemc.materialize.MaterializeQuote;
import com.zhuyuhang.beyondemc.materialize.MaterializingGuard;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 网络接口（以及所有其它抽取路径）的兑换支持：
 * 让"已学习但无库存"的物品也能被抽取出来，并正常扣除网络 EMC。
 *
 * <h2>为什么挂在抽取钩子上</h2>
 * {@code UnifiedStorageBeforeExtractHandler} 是 BD 全部抽取路径的**唯一收口**：
 * {@code extract(int slot, ...)}、{@code extract(TagKey, ...)}、{@code extractByKey(...)}
 * 最终都会转发到 {@code extract(IStackKey, ...)}，并在那里调用钩子
 * （`UnifiedStorage.java:105-133`）。所以挂在这里能一次覆盖：
 * <ul>
 *   <li>网络接口的自动输出 —— {@code NetInterfaceAccess.transferFromNet} 调
 *       {@code net.getUnifiedStorage().extract(flag.key(), missing, false, fuzzy)}
 *       （`NetInterfaceAccess.java:127`）</li>
 *   <li>其它任何通过 UnifiedStorage 的抽取路径</li>
 * </ul>
 *
 * <h2>钩子的致命限制，以及本类的绕法</h2>
 * 钩子返回的 {@code KeyAmount} 会交给 {@code super.extract(adjusted.key(), ...)}
 * <b>真实抽取</b>（`UnifiedStorage.java:132`）—— 也就是说钩子**只能改抽取什么，
 * 不能凭空造出库存里没有的东西**。
 *
 * <p>所以这里采用"**先铸造、再让原生抽取取走**"：
 * <ol>
 *   <li>确认该物品已学习、价格可得、余额够；</li>
 *   <li>扣掉 EMC；</li>
 *   <li>用 {@link MintingGuard} 包着把物品写进存储 —— 这个守卫让**存入折算钩子放行**，
 *       否则物品会被当场按回收价折算回 EMC，兑换自我抵消；</li>
 *   <li>返回调整后的 {@code KeyAmount}，随后的 {@code super.extract} 把它取走。</li>
 * </ol>
 * 净效果：存储不变，EMC 减少，物品交给了调用方。
 *
 * <h2>安全性质</h2>
 * <ul>
 *   <li><b>模拟抽取绝不扣费</b>：{@link ExtractContext#isSimulate()} 为真时直接放行。
 *       这是最重要的一条 —— 外部模组的能力查询是模拟抽取。</li>
 *   <li><b>必须已学习</b>（除非配置显式关闭 `exchangeRequiresKnowledge`）。</li>
 *   <li><b>只能按购买价买、按余额买**：数量 = min(请求量, 余额 ÷ 购买价)。
 *       因 ProjectE 恒有 `购买价 ≥ 回收价`，本路径<b>不存在无风险套利</b>。</li>
 *   <li><b>绝不先扣钱不交货</b>：铸造不满额时按未交付部分原价退回；扣费失败立即回滚。</li>
 *   <li><b>不会把网络抽干</b>：接口只有在**显式配置了过滤器**时才会抽取
 *       （`NetInterfaceAccess.java:120` 对空过滤器直接 `continue`），
 *       且每个槽位每周期最多取一整堆（`getVanillaMaxStackSize()`）。</li>
 * </ul>
 *
 * <h2>0.3.0：对物化条目（{@code EmcItemKey}）的"改道收费"</h2>
 * 物化条目落进真实存储后，BD 的三条抽取入口（按槽位 / 按标签 / 按键）都会汇聚到本钩子
 * （{@code UnifiedStorage.java:105-143}），而存储会把 {@code key.getTags()} 全登记进
 * {@code tag2stackMap}（{@code AbstractUnorderedStackHandler.java:736-738}）——
 * 于是物化条目可被 {@code extract(TagKey,…)} 命中。
 *
 * <p>本钩子原先对非 {@code ItemStackKey} 一律 {@code pass}，那会让"按标签 / 按槽位抽物化条目"
 * 变成<b>零扣费交付</b>（真实刷物品入口）。现在把 {@code EmcItemKey} <b>归一成"该物品"</b>，
 * 复用下面同一条收费铸造链路：有 EMC 价值就扣费；余额不足或无法定价一律 {@code cancel}。
 *
 * <p>唯一的例外是 {@link MaterializingGuard} 激活时（物化层正在维护自己的派生数据）—— 必须放行，
 * 否则条目无法收缩（自锁）。
 */
public final class InterfaceWithdrawService {

    private static long logCount = 0;
    private static final int LOG_FIRST_N = 8;

    // ---- 自检探针（诊断用；正常运行时 disableProbe 状态，不产生任何影响）----
    private static volatile boolean probeEnabled = false;
    private static volatile boolean probeSawSimulate = false;

    /** 打开探针：下一次 beforeExtract 会记录它看到的 simulate 标志。 */
    public static void enableProbe() {
        probeSawSimulate = false;
        probeEnabled = true;
    }

    public static void disableProbe() {
        probeEnabled = false;
    }

    public static boolean probeSawSimulate() {
        return probeSawSimulate;
    }

    private InterfaceWithdrawService() {
    }

    public static UnifiedStorageBeforeExtractHandler.BeforeExtractHandler handler() {
        return InterfaceWithdrawService::beforeExtract;
    }

    @NotNull
    private static UnifiedStorageBeforeExtractHandler.BeforeExtractHandlerReturnInfo beforeExtract(
            @NotNull KeyAmount originalExtract,
            @NotNull KeyAmount tryExtract,
            @org.jetbrains.annotations.Nullable DimensionsNet net) {

        // 探针必须放在最前面：它是"抽取上下文里的 simulate 标志是否正确"的唯一直接证据
        if (probeEnabled) {
            probeSawSimulate = ExtractContext.isSimulate();
        }

        // ---- 0. 物化层自身的维护：必须放行 ----
        // ItemMaterializer 收缩/清空物化条目用的就是 storage.extract(EmcItemKey,…)，
        // 那是"派生层在维护自己"，不是外部取用。若不放行会被下面的改道逻辑当成外部抽取：
        // 要么凭空扣钱，要么因余额不足被 cancel —— 条目永远无法收缩（自锁）。
        IStackKey<?> rawKey = tryExtract.key();
        boolean materialized = rawKey instanceof EmcItemKey;
        if (materialized && MaterializingGuard.isActive()) {
            return pass(tryExtract);
        }

        // ---- 1. 前置与守卫 ----
        if (net == null) {
            return deny(materialized, tryExtract);
        }
        if (!BeyondEmcConfig.allowInterfaceWithdraw()) {
            return deny(materialized, tryExtract);
        }
        // ⚠️ 模拟抽取绝不扣费（外部模组的能力查询会大量走这里）。
        // 必须排在"改道"之前 —— 这是 0.2 立下的红线，0.3 不得破坏。
        if (ExtractContext.isSimulate()) {
            return pass(tryExtract);
        }

        // ---- 1b. 键归一（0.3.0 的"改道收费"）----
        // 物化条目落进真实存储后，BD 的三条抽取入口（按槽位 / 按标签 / 按键）都会汇聚到这里
        // （UnifiedStorage.java:105-143），而存储又把 key.getTags() 全登记进 tag2stackMap
        // （AbstractUnorderedStackHandler.java:736-738）⇒ 物化条目可被 extract(TagKey,…) 命中。
        // 若这里对 EmcItemKey 直接放行，就是【零扣费交付】—— 真实刷物品入口。
        // 所以把 EmcItemKey 归一成"该物品"，走下面同一条收费铸造链路：
        // "抽物化条目"与"抽该物品"是同一个语义，只是入口键类型不同。
        ItemStackKey itemKey;
        if (rawKey instanceof ItemStackKey k) {
            itemKey = k;
        } else if (rawKey instanceof EmcItemKey emcItemKey) {
            try {
                ItemStack raw = emcItemKey.info().createStack();
                if (raw.isEmpty()) {
                    return cancel(tryExtract); // 身份都取不出来 ⇒ 什么都不交付
                }
                itemKey = new ItemStackKey(raw);
            } catch (Throwable t) {
                BeyondEmc.LOGGER.error("[BeyondEMC] 物化条目身份归一失败，已拒绝此次抽取", t);
                return cancel(tryExtract);
            }
        } else {
            return pass(tryExtract); // 流体/能量/EMC 自身等，交给 BD 原生
        }

        ItemStack stack = itemKey.getReadOnlyStack();
        if (stack.isEmpty()) {
            return deny(materialized, tryExtract);
        }

        UnifiedStorage storage = net.getUnifiedStorage();
        if (storage == null) {
            return deny(materialized, tryExtract);
        }
        // 有真实库存 → 原生抽取（需求 R6 的同一原则）。
        // 物化路径不能走这条：放行等于把 EmcItemKey 交给 super.extract 零扣费取走。
        if (!materialized && storage.hasStack(itemKey)) {
            return pass(tryExtract);
        }

        // ---- 2. 与存入侧完全同一套策略 ----
        // ⚠️ 堵刷物品漏洞的第二道闸：既然"带组件的物品 / 被黑白名单排除的物品"
        // 在存入时不会被折算（也就不会被学习），那就绝不能通过兑换铸造出来。
        // 这里直接复用 EmcDepositHandler.skipReason，保证两侧**永远同源** ——
        // 早先的漏洞正是因为两条路径各自判断、标准不一致。
        String skip = EmcDepositHandler.skipReason(stack);
        if (skip != null) {
            return deny(materialized, tryExtract);
        }

        // ---- 3. 身份归一 + 一致性 ----
        // ⚠️ 堵刷物品漏洞的第一道闸（结构性）：**铸造出来的必须就是定价用的那个身份**。
        // 若过滤器里放的是附魔钻石剑，它的归一化身份是普通钻石剑 —— 两者不同，直接拒绝铸造；
        // 否则就会"按普通剑的价格铸出附魔剑"，也就是刷物品。
        CanonicalExchange.Resolved resolved = CanonicalExchange.resolve(stack);
        if (resolved == null) {
            return deny(materialized, tryExtract);
        }
        ItemInfo info = resolved.info();
        ItemStackKey canonicalKey = new ItemStackKey(resolved.stack());
        if (!canonicalKey.equals(itemKey)) {
            logThrottled("过滤器里的物品与网络学会的身份不一致，已拒绝铸造（防止按低价换出高价物品）");
            return deny(materialized, tryExtract);
        }
        // 有真实库存 → 交给原生抽取（需求 R6 的同一原则）；物化路径同样不能放行
        if (!materialized && storage.hasStack(canonicalKey)) {
            return pass(tryExtract);
        }

        // ---- 4. 定价与余额 ----
        // ⚠️ 这一段必须与两条「把物化条目暴露给第三方」的路径（Create 蓝图接口、
        // BD 通用物品能力桥）共用同一个实现 —— 即 MaterializeQuote。
        // 本类在 0.3.0 曾经因为"两条路径各自判断、标准不一致"出过一次刷物品漏洞，
        // 新增暴露路径时绝不能再各写一份判断链。
        //
        // policy 覆盖了原先散在这里的几项：EMC 表就绪 / 已学习 / 可达购买价 / 余额。
        // 其中"EMC 表未就绪"原先在更早处单独判过，行为等价：两者都归入 deny 分支。
        MaterializeQuote.Policy policy = MaterializeQuote.policy(net, info);
        if (policy == null) {
            return deny(materialized, tryExtract);
        }
        long unitPrice = policy.unitPrice();
        // 真实库存路径不带物化量约束（Long.MAX_VALUE = 不设上限），
        // 于是这里的结果恰好等于原先的 min(tryExtract.amount(), affordable)。
        long want = policy.deliverable(tryExtract.amount(), Long.MAX_VALUE);
        if (want <= 0L) {
            // 买不起：拒绝这次抽取（不要交半份），接口本周期就输出不了东西
            logThrottled("网络 {} 余额 {} 不足以兑换 {}（单价 {}），本次抽取被拒绝",
                    net.getId(), policy.balance(), info, unitPrice);
            return cancel(tryExtract);
        }

        // ---- 5. 扣费 + 铸造 ----
        long cost = NetEmcAccessor.saturatingMultiply(unitPrice, want);
        MintingGuard.enter();
        try {
            long spent = NetEmcAccessor.spendEmc(net, cost);
            if (spent < cost) {
                // 正常不该发生（上面刚查过余额）；并发情况下回滚
                if (spent > 0L) {
                    NetEmcAccessor.addEmc(net, spent);
                }
                return cancel(tryExtract);
            }

            KeyAmount remainder = storage.insert(canonicalKey, want, false);
            long minted = want - remainder.amount();
            if (minted < want) {
                // 没全铸进去 → 按未交付部分原价退回，绝不多扣
                NetEmcAccessor.addEmc(net,
                        NetEmcAccessor.saturatingMultiply(unitPrice, want - minted));
                BeyondEmc.LOGGER.warn("[BeyondEMC] 接口兑换只铸造了 {}/{}，已退回多扣的 EMC",
                        minted, want);
            }
            if (minted <= 0L) {
                return cancel(tryExtract);
            }

            long seq = ++logCount;
            if (seq <= LOG_FIRST_N) {
                BeyondEmc.LOGGER.info("[BeyondEMC] 接口兑换：{} ×{} → 扣除 {} EMC（单价 {}，网络 {}{}）",
                        info, minted, NetEmcAccessor.saturatingMultiply(unitPrice, minted),
                        unitPrice, net.getId(), materialized ? "，来源=物化条目" : "");
            }

            // 触发点 ③（0.3.0）：余额已减少 ⇒ 物化条目要跟着收缩。
            // 延后到 tick 之后：此刻刚铸造的物品还没被下面的 super.extract 取走。
            ItemMaterializer.scheduleRefresh(net);

            // 交给随后的 super.extract(...) 取走刚铸造出来的物品
            return new UnifiedStorageBeforeExtractHandler.BeforeExtractHandlerReturnInfo(
                    new KeyAmount(canonicalKey, minted), false);
        } catch (Throwable t) {
            BeyondEmc.LOGGER.error("[BeyondEMC] 接口兑换铸造阶段出错", t);
            return cancel(tryExtract);
        } finally {
            MintingGuard.exit();
        }
    }

    private static UnifiedStorageBeforeExtractHandler.BeforeExtractHandlerReturnInfo pass(KeyAmount current) {
        return new UnifiedStorageBeforeExtractHandler.BeforeExtractHandlerReturnInfo(current, false);
    }

    private static UnifiedStorageBeforeExtractHandler.BeforeExtractHandlerReturnInfo cancel(KeyAmount current) {
        return new UnifiedStorageBeforeExtractHandler.BeforeExtractHandlerReturnInfo(current, true);
    }

    /**
     * "这条路径无法交付"时的返回。
     *
     * <p>两种键<b>必须区别对待</b>，这是 INV-1′ 的关键：
     * <ul>
     *   <li>{@code ItemStackKey}（原生路径）：{@code pass} —— 让 BD 原生去抽，抽不到自然是 0，
     *       行为与 0.2 完全一致；</li>
     *   <li>{@code EmcItemKey}（物化条目）：{@code cancel} —— 因为 {@code pass} 会把
     *       EmcItemKey 原样交给 {@code super.extract} <b>零扣费取走</b>。
     *       按用户拍板的口径：<b>有 EMC 价值就扣费，余额不足或无法定价就一律拒绝（不交半份）</b>。</li>
     * </ul>
     */
    private static UnifiedStorageBeforeExtractHandler.BeforeExtractHandlerReturnInfo deny(boolean materialized,
                                                                                         KeyAmount current) {
        return materialized ? cancel(current) : pass(current);
    }

    private static void logThrottled(String format, Object... args) {
        long seq = ++logCount;
        if (seq <= LOG_FIRST_N) {
            BeyondEmc.LOGGER.info("[BeyondEMC] " + format, args);
        }
    }
}
