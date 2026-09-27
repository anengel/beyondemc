package com.zhuyuhang.beyondemc.exchange;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeExtractHandler;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.core.ExtractContext;
import com.zhuyuhang.beyondemc.core.MintingGuard;
import com.zhuyuhang.beyondemc.emc.EmcDepositHandler;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
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

        // ---- 1. 前置与守卫 ----
        if (net == null) {
            return pass(tryExtract);
        }
        if (!BeyondEmcConfig.allowInterfaceWithdraw()) {
            return pass(tryExtract);
        }
        // ⚠️ 模拟抽取绝不扣费（外部模组的能力查询会大量走这里）
        if (ExtractContext.isSimulate()) {
            return pass(tryExtract);
        }
        if (!(tryExtract.key() instanceof ItemStackKey itemKey)) {
            return pass(tryExtract); // 流体/能量/EMC 自身等，交给 BD 原生
        }
        ItemStack stack = itemKey.getReadOnlyStack();
        if (stack.isEmpty()) {
            return pass(tryExtract);
        }

        UnifiedStorage storage = net.getUnifiedStorage();
        if (storage == null) {
            return pass(tryExtract);
        }
        // 有真实库存 → 原生抽取（需求 R6 的同一原则）
        if (storage.hasStack(itemKey)) {
            return pass(tryExtract);
        }

        // ---- 2. 与存入侧完全同一套策略 ----
        // ⚠️ 堵刷物品漏洞的第二道闸：既然"带组件的物品 / 被黑白名单排除的物品"
        // 在存入时不会被折算（也就不会被学习），那就绝不能通过兑换铸造出来。
        // 这里直接复用 EmcDepositHandler.skipReason，保证两侧**永远同源** ——
        // 早先的漏洞正是因为两条路径各自判断、标准不一致。
        String skip = EmcDepositHandler.skipReason(stack);
        if (skip != null) {
            return pass(tryExtract);
        }

        // ---- 3. EMC 表未就绪不兑换 ----
        if (!EmcAvailability.isReady()) {
            return pass(tryExtract);
        }

        // ---- 4. 身份归一 + 一致性 ----
        // ⚠️ 堵刷物品漏洞的第一道闸（结构性）：**铸造出来的必须就是定价用的那个身份**。
        // 若过滤器里放的是附魔钻石剑，它的归一化身份是普通钻石剑 —— 两者不同，直接拒绝铸造；
        // 否则就会"按普通剑的价格铸出附魔剑"，也就是刷物品。
        CanonicalExchange.Resolved resolved = CanonicalExchange.resolve(stack);
        if (resolved == null) {
            return pass(tryExtract);
        }
        ItemInfo info = resolved.info();
        ItemStackKey canonicalKey = new ItemStackKey(resolved.stack());
        if (!canonicalKey.equals(itemKey)) {
            logThrottled("过滤器里的物品与网络学会的身份不一致，已拒绝铸造（防止按低价换出高价物品）");
            return pass(tryExtract);
        }
        // 有真实库存 → 交给原生抽取（需求 R6 的同一原则）
        if (storage.hasStack(canonicalKey)) {
            return pass(tryExtract);
        }

        if (BeyondEmcConfig.exchangeRequiresKnowledge() && !NetKnowledgeStore.knows(net, info)) {
            return pass(tryExtract); // 未学习 → 原样放行，原生抽取自然返回 0
        }

        // ---- 4. 定价与余额 ----
        long unitPrice;
        try {
            unitPrice = IEMCProxy.INSTANCE.getValue(info); // 购买价
        } catch (Throwable t) {
            return pass(tryExtract);
        }
        if (unitPrice <= 0L) {
            return pass(tryExtract); // 该物品当前没有 EMC 价值
        }

        long balance = NetEmcAccessor.getEmc(net);
        long affordable = balance / unitPrice;
        long want = Math.min(tryExtract.amount(), affordable);
        if (want <= 0L) {
            // 买不起：拒绝这次抽取（不要交半份），接口本周期就输出不了东西
            logThrottled("网络 {} 余额 {} 不足以兑换 {}（单价 {}），本次抽取被拒绝",
                    net.getId(), balance, info, unitPrice);
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
                BeyondEmc.LOGGER.info("[BeyondEMC] 接口兑换：{} ×{} → 扣除 {} EMC（单价 {}，网络 {}）",
                        info, minted, NetEmcAccessor.saturatingMultiply(unitPrice, minted),
                        unitPrice, net.getId());
            }
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

    private static void logThrottled(String format, Object... args) {
        long seq = ++logCount;
        if (seq <= LOG_FIRST_N) {
            BeyondEmc.LOGGER.info("[BeyondEMC] " + format, args);
        }
    }
}
