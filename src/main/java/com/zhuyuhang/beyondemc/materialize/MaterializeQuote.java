package com.zhuyuhang.beyondemc.materialize;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.emc.EmcDepositHandler;
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
 * <h2>为什么不把 {@code EmcDepositHandler.skipReason} 放进 {@link #policy}</h2>
 * 那一项判的是"这个 {@link ItemStack} 本身"（黑/白名单、带组件的物品），必须由调用方
 * 用**请求方给的那个 stack**去判，而不是用归一化重建出来的 stack —— 否则两者可能在
 * "带组件物品"的边界上分叉（{@code InterfaceWithdrawService} 的过滤器里就可能放带组件的物品）。
 *
 * <p>但"把物化条目暴露给第三方"这条路**没有请求方 stack** —— 我们合成什么就交付什么，
 * 那就是 {@code info.createStack()}。所以这条路用 {@link #externalDeliverable}，
 * 它把 {@code skipReason} 也判掉，判据与收费段逐条相同。
 *
 * <h2>⚠️ 第三方可见量绝不能要求"物化条目存在"</h2>
 * 这条是 0.3.2 实测缺陷的根因（存档实证：15 项已学习、只有 14 条物化条目，缺
 * {@code minecraft:gunpowder}）。物化条目是<b>派生数据</b>（见 {@code ItemMaterializer} 的
 * "权威与派生"边界），它可以因为"该物品当前有真实库存"（{@code MaterializeMath.solve} 的排除规则）
 * 而被合法地跳过一次，此后若真实库存被取走而没有任何触发点重算，条目就一直缺失。
 *
 * <p>而<b>真实交付根本不依赖条目</b>：{@code InterfaceWithdrawService} 是"扣 EMC + 当场铸造"
 * （见该类注释），它用的算式是 {@code min(请求量, 余额 ÷ 购买价)}
 * —— <b>只受余额约束</b>。因此第三方暴露若额外要求"条目存在"，就会比真实交付更严，
 * 表现为"大炮说没料、其实一取就能取到"。{@link #externalDeliverable} 就是消除这个分叉的唯一入口。
 *
 * <h2>安全性质</h2>
 * <ul>
 *   <li>本类**不做任何写操作**：不扣 EMC、不改存储。它只读余额与价格。</li>
 *   <li>因此"模拟抽取"可以安全地用它 —— 与 0.2 立下的"模拟绝不扣费"红线不冲突。</li>
 *   <li>{@link #externalDeliverable} 与 {@code InterfaceWithdrawService} 的真实扣费段
 *       <b>共用同一个算式</b>（都是 {@code min(请求量, 余额 ÷ 购买价)}，判据链逐条相同）
 *       ⇒ 第三方看到的量就是真实能取走的量，既不会"报得比给得多"（那会让调用方卡在永远等料），
 *       也不会"给得比报得多"（那是零扣费交付）。</li>
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
     *
     * <p><b>刻意不提供"再按物化条目量截断"的方法</b>：0.3.2 的实测缺陷就是把"条目存在"
     * 当成了对外交付的前提（见类注释）。唯一允许的算式是 {@link #exposedAmount}。
     */
    public record Policy(ItemInfo info, long unitPrice, long balance, long affordable) {
    }

    /**
     * 第三方可见量的**唯一算式**（纯函数，可在无头环境里决定性断言）。
     *
     * <p>刻意做成纯函数：0.3.2 的缺陷正是"第三方路径多要求了一个条件（物化条目存在）"，
     * 而那个条件无法在无头环境里被验证。把算式抽出来，"余额够、策略放行 ⇒ 必须报得出量"
     * 就成了可以写死断言的 <b>不变式</b>。
     *
     * @param want        外部请求量
     * @param affordable  余额可负担份数（{@code balance / unitPrice}）
     * @param allowed     上游策略是否放行（已学习 / 可达购买价 / 配置开启 / 未被存入侧筛选排除）
     * @return 本次对外报出的量；任何一条不成立都是 {@code 0}
     */
    public static long exposedAmount(long want, long affordable, boolean allowed) {
        if (!allowed || want <= 0L || affordable <= 0L) {
            return 0L;
        }
        return Math.min(want, affordable);
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
     * **第三方此刻真正能取走的量** —— 大炮的模拟抽取、通用物品能力桥的模拟抽取与显示，
     * 以及任何"对外报量"的地方，都必须走这一个入口。
     *
     * <h2>判据链（与 {@code InterfaceWithdrawService} 的收费段逐条相同）</h2>
     * <ol>
     *   <li>{@link #policy} 全部通过（配置开启 / EMC 表就绪 / 已学习 / 可达购买价）；</li>
     *   <li>{@code EmcDepositHandler.skipReason(info.createStack()) == null}
     *       —— 存入侧不接受的东西（黑白名单排除、带非默认组件且未开启折算），
     *       兑换侧也绝不会铸造。不判这一条会出现"报有料、真抽取却永远交付 0"，
     *       把调用方（蓝图大炮的 {@code updateChecklist}）卡在永远等料的状态；</li>
     *   <li>算式 {@code min(want, balance ÷ unitPrice)} —— <b>只受余额约束，不受物化条目约束</b>。</li>
     * </ol>
     *
     * <p><b>为什么第 3 条不带"条目量"上限</b>：真实交付是"扣 EMC + 当场铸造"
     * （{@code InterfaceWithdrawService} 第 5 步），它完全不看条目；
     * 而条目只是派生数据、允许暂时缺失。要求条目存在就等于对外比真实更严 —— 那正是实测缺陷。
     *
     * <p>本方法<b>只读</b>：不扣 EMC、不改存储、不写日志。
     *
     * @return 对外报出的量；{@code 0} 表示"这条路交付不了"（调用方应当报"没料"，而不是等待）
     */
    public static long externalDeliverable(@Nullable DimensionsNet net,
                                           @Nullable ItemInfo info,
                                           long want) {
        Policy p = policy(net, info);
        if (p == null) {
            return 0L;
        }
        return exposedAmount(want, p.affordable(), passesDepositPolicy(info));
    }

    /**
     * 存入侧策略是否接受这个身份（复用 {@code EmcDepositHandler.skipReason}，保证两侧同源）。
     *
     * <p>用 {@code info.createStack()} 去判 —— 这条路上**我们合成什么就交付什么**，
     * 所以"请求方 stack"就是归一化重建出的那个 stack（无组件），不存在分叉。
     */
    private static boolean passesDepositPolicy(@NotNull ItemInfo info) {
        try {
            ItemStack probe = info.createStack();
            if (probe.isEmpty()) {
                return false;
            }
            return EmcDepositHandler.skipReason(probe) == null;
        } catch (Throwable t) {
            return false; // 判不了就当作不可交付（安全方向）
        }
    }

    /**
     * 「展示栈」的构造收口：把物化条目的身份 + 数量变成可以交给第三方容器的 {@link ItemStack}。
     *
     * <p><b>数量只按 int 范围夹取，不按原版堆叠数夹取</b>（0.3.2 第二轮实测缺陷的教训）。
     * 两个理由：
     * <ol>
     *   <li><b>与 BD 的原生语义对齐</b>：两个暴露路径（蓝图大炮的
     *       {@code NetedSchematicannonItemHandler}、通用物品能力桥的
     *       {@code ItemUnifiedStorageHandler}）里 BD 原生的 {@code getStackInSlot}
     *       都返回<b>全量</b>（{@code copyStackWithCount(ka.amount())} /
     *       {@code clampLongToInt(...)}）——真实库存 300 万就报 300 万，从不夹到 64。
     *       我们若夹到 64，就成了"同样的网络、真实库存和物化条目报的数量口径不同"。</li>
     *   <li><b>Create 的清单会计按数量累加</b>：{@code updateChecklist} 把
     *       {@code getStackInSlot(i).getCount()} 累加进 {@code gathered}，材料清单按
     *       {@code required − gathered} 判"满足/还缺"。蓝图接口<b>每个物品只有一个槽</b>，
     *       夹到 64 就意味着 {@code gathered} 永远 ≤ 64 —— 任何需求量超过一组的材料
     *       （实测：蓝图里 {@code create:shaft} 需要 125 个）都会<b>永远显示"还缺"</b>，
     *       无论网络 EMC 买得起多少。存档实证（2026-10-01）：圆石 64/58 满足、
     *       传动杆 64/125 不满足 —— 尽管两者都有充足的物化条目与余额。</li>
     * </ol>
     *
     * <p>大数量对调用方是安全的：{@code extractItem} 的真实交付仍逐次按请求量走
     * 扣费铸造链（不会一次交出一百万个），Create 的抽取循环也只按
     * {@code getMaxStackSize()} 分批取。
     *
     * @return 失败（身份取不出物品 / 数量 ≤ 0）时返回 {@link ItemStack#EMPTY}
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
            // 与 BD 的 BDMath.clampLongToInt 同语义：只防 int 溢出，不按堆叠数夹
            out.setCount((int) Math.min(amount, (long) Integer.MAX_VALUE));
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
