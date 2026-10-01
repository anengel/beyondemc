package com.zhuyuhang.beyondemc.diag;

import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.emc.EmcItemKey;
import com.zhuyuhang.beyondemc.materialize.MaterializeQuote;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 「把物化条目暴露给第三方（Create 蓝图接口 / BD 通用物品能力桥）」的**纯逻辑**自检。
 *
 * <h2>为什么这些断言值得单独立一项</h2>
 * 这两条暴露路径的安全性完全建立在两句话上：
 * <ol>
 *   <li><b>模拟报量必须与真实交付量用同一套算式</b> —— 否则会出现
 *       「模拟说 64 个、真取只给 1 个」，调用方（蓝图大炮的 {@code ItemHelper.extract}
 *       与 {@code updateChecklist}）会一直以为自己有料，卡在永远等料的状态；</li>
 *   <li><b>报出去的数量必须夹在原版堆叠数以内</b> —— 物化条目量可能有上百万，
 *       直接塞进 {@link ItemStack} 交给第三方容器会越界 / 显示错乱。</li>
 * </ol>
 * 两条都是**纯函数性质**，可以无头决定性地验证，不需要开游戏、不需要 EMC 表就绪。
 *
 * <p>依赖真实 EMC 价格（{@code IEMCProxy.getValue}）与配置开关的路径无法在这里断言，
 * 记在 {@code docs/plan/CREATE-INTEGRATION.md} 的人工验收清单里。
 */
public final class CreatePathwaySelfTest {

    private CreatePathwaySelfTest() {
    }

    public static List<String> run() {
        List<String> out = new ArrayList<>();
        int[] ok = {0};

        bucketSeparation(out, ok);
        exposedAmountInvariant(out, ok);
        deliverableBoundaries(out, ok);
        deliverableMatchesChargeFormula(out, ok);
        displayStackClamping(out, ok);
        nullSafety(out, ok);

        out.add("---- Create/第三方暴露（0.3.2）自检结果：" + ok[0] + " 项通过 ----");
        return out;
    }

    // ------------------------------------------------------------------

    /**
     * 0b. 回归：**对外报量绝不能要求「物化条目存在」**（0.3.2 实测缺陷）。
     *
     * <p>缺陷现场：存档里 15 项已学习、只有 14 条物化条目（缺 {@code minecraft:gunpowder}），
     * 而蓝图大炮恒定需要火药 —— 大炮报"无库存"，其实一取就能取到。
     * 根因是暴露路径当时写成 {@code policy.deliverable(count, 物化条目量)}，
     * 把"派生数据存在"当成了交付前提；而真实交付（扣 EMC + 当场铸造）根本不看条目。
     *
     * <p>这一条把修好的不变式钉死：<b>余额够 + 策略放行 ⇒ 必须报得出量</b>，
     * 与条目有无、条目多少完全无关。
     */
    private static void exposedAmountInvariant(List<String> out, int[] ok) {
        try {
            long[] wants = {-1L, 0L, 1L, 64L, 100L, 101L, Long.MAX_VALUE};
            long[] affordables = {-1L, 0L, 1L, 64L, 100L};
            boolean[] alloweds = {true, false};

            int checked = 0;
            for (long want : wants) {
                for (long affordable : affordables) {
                    for (boolean allowed : alloweds) {
                        long got = MaterializeQuote.exposedAmount(want, affordable, allowed);
                        // 不变式：报得出量 ⟺ 策略放行 ∧ 有需求 ∧ 余额买得起；此时取两者的较小者
                        boolean shouldBePositive = allowed && want > 0L && affordable > 0L;
                        long expected = shouldBePositive ? Math.min(want, affordable) : 0L;
                        if (got != expected) {
                            out.add("FAIL 对外报量不变式：want=" + want + " affordable=" + affordable
                                    + " allowed=" + allowed + " → " + got + "（期望 " + expected + "）");
                            return;
                        }
                        checked++;
                    }
                }
            }
            ok[0]++;
            out.add("OK   对外报量不变式：" + checked + " 组（want × 余额 × 策略）全部满足"
                    + "「报得出量 ⟺ 策略放行 ∧ 有需求 ∧ 余额买得起」—— 算式里**没有**物化条目这一项，"
                    + "即条目缺失也不可能把量压成 0（0.3.2 实测缺陷的回归）");
        } catch (Throwable t) {
            out.add("FAIL 对外报量不变式：" + t);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 0. 根因不变式：物化条目的桶 id 必须与 {@code ItemStackKey} 不同。
     *
     * <p>这一条是整个「第三方看不见」现象的源头（BD 的能力桥按类型 id 分桶枚举）。
     * 若哪天两者意外相同，物化条目就会与玩家真实存入的同类物品<b>合并成一条数</b>，
     * 既无法区分、也无法按 EMC 收费 —— 那是比"看不见"严重得多的数据事故。
     */
    private static void bucketSeparation(List<String> out, int[] ok) {
        try {
            boolean same = EmcItemKey.ID.equals(ItemStackKey.ID);
            if (same) {
                out.add("FAIL 桶分离：EmcItemKey.ID 与 ItemStackKey.ID 相同（" + EmcItemKey.ID
                        + "）—— 物化条目会与真实库存合并成一条数");
            } else {
                ok[0]++;
                out.add("OK   桶分离：物化条目在 " + EmcItemKey.ID + "，真实物品在 "
                        + ItemStackKey.ID + "（不同类型桶，互不塌缩）");
            }
        } catch (Throwable t) {
            out.add("FAIL 桶分离：" + t);
        }
    }

    /**
     * 1. {@link MaterializeQuote#exposedAmount} 的边界（纯算式，不触碰 EMC 表 / 网络 / 配置）。
     */
    private static void deliverableBoundaries(List<String> out, int[] ok) {
        try {
            long affordable = 1000L;

            long a = MaterializeQuote.exposedAmount(0L, affordable, true);      // 不要 → 0
            long c = MaterializeQuote.exposedAmount(64L, affordable, true);     // 取需求
            long d = MaterializeQuote.exposedAmount(64L, 10L, true);            // 受余额限 → 10
            long e = MaterializeQuote.exposedAmount(2000L, affordable, true);   // 需求大于余额 → 余额
            long f = MaterializeQuote.exposedAmount(-5L, affordable, true);     // 负需求 → 0
            long g = MaterializeQuote.exposedAmount(64L, -5L, true);            // 负余额 → 0
            long i = MaterializeQuote.exposedAmount(64L, 0L, true);             // 余额买不起（affordable=0）→ 0
            long j = MaterializeQuote.exposedAmount(Long.MAX_VALUE, Long.MAX_VALUE, true); // 饱和不溢出

            boolean pass = a == 0L && c == 64L && d == 10L && e == 1000L
                    && f == 0L && g == 0L && i == 0L && j == Long.MAX_VALUE;
            if (pass) {
                ok[0]++;
                out.add("OK   报价边界：不要=0 / 取需求=64 / 受余额限=10 / 取余额=1000 / 负数=0 / "
                        + "买不起=0 / MAX 不溢出");
            } else {
                out.add("FAIL 报价边界：a=" + a + " c=" + c + " d=" + d + " e=" + e
                        + " f=" + f + " g=" + g + " i=" + i + " j=" + j
                        + "（期望 0/64/10/1000/0/0/0/MAX）");
            }
        } catch (Throwable t) {
            out.add("FAIL 报价边界：" + t);
        }
    }

    /**
     * 2. 「模拟报量」与「真实扣费」必须同算式。
     *
     * <p>真实扣费段在 {@code InterfaceWithdrawService} 里用的是
     * {@code want = min(请求量, 余额 / 购买价)}。这里把同一算式跑一遍，确认报价器给出相同的数
     * —— 两条暴露路径与网络接口**永远不会分叉**，这正是把判断链收口到 {@code MaterializeQuote} 的目的。
     */
    private static void deliverableMatchesChargeFormula(List<String> out, int[] ok) {
        try {
            long[] unitPrices = {1L, 64L, 8192L, 1_000_000L};
            long[] balances = {0L, 100L, 8192L * 7L + 3L, 1L << 40};
            long[] wants = {1L, 64L, 4096L, Integer.MAX_VALUE};

            for (long price : unitPrices) {
                for (long balance : balances) {
                    for (long want : wants) {
                        long affordable = balance / price;
                        long expected = Math.min(want, affordable);
                        long actual = MaterializeQuote.exposedAmount(want, affordable, true);
                        if (actual != expected) {
                            out.add("FAIL 算式一致：price=" + price + " balance=" + balance
                                    + " want=" + want + " → 报价 " + actual + "，收费段 " + expected);
                            return;
                        }
                    }
                }
            }
            ok[0]++;
            out.add("OK   算式一致：4 单价 × 4 余额 × 4 需求量共 64 组，报价与收费段 min(请求量, 余额/单价) 逐组相同");
        } catch (Throwable t) {
            out.add("FAIL 算式一致：" + t);
        }
    }

    /**
     * 3. 展示栈的数量约定：<b>全量（只按 int 夹取），不按堆叠数夹取</b>。
     *
     * <p>0.3.2 第二轮实测缺陷的回归：夹到堆叠数（64）会让 Create 的材料清单
     * （{@code gathered} 累加 {@code getStackInSlot().getCount()}）对任何需求量
     * 超过一组的材料永远显示"还缺"（蓝图接口每物品只有一个槽）。BD 原生对真实库存
     * 也返回全量，这里必须同口径。
     */
    private static void displayStackClamping(List<String> out, int[] ok) {
        try {
            ItemInfo diamond = ItemInfo.fromItem(Items.DIAMOND);

            ItemStack huge = MaterializeQuote.displayStack(diamond, 1_000_000L);
            ItemStack one = MaterializeQuote.displayStack(diamond, 1L);
            ItemStack none = MaterializeQuote.displayStack(diamond, 0L);
            ItemStack neg = MaterializeQuote.displayStack(diamond, -1L);
            ItemStack overflow = MaterializeQuote.displayStack(diamond, Long.MAX_VALUE);
            ItemStack maxInt = MaterializeQuote.displayStack(diamond, (long) Integer.MAX_VALUE + 5L);

            boolean pass = !huge.isEmpty() && huge.getCount() == 1_000_000
                    && !one.isEmpty() && one.getCount() == 1
                    && !overflow.isEmpty() && overflow.getCount() == Integer.MAX_VALUE
                    && !maxInt.isEmpty() && maxInt.getCount() == Integer.MAX_VALUE
                    && none.isEmpty() && neg.isEmpty();
            if (pass) {
                ok[0]++;
                out.add("OK   展示栈数量约定：1,000,000 → 1,000,000（全量，不按堆叠数夹）；"
                        + "1 → 1；超 int → Integer.MAX_VALUE；0 / 负数 → 空栈");
            } else {
                out.add("FAIL 展示栈数量约定：huge=" + huge.getCount() + "（期望 1,000,000）one="
                        + one.getCount() + " overflow=" + overflow.getCount()
                        + "（期望 " + Integer.MAX_VALUE + "）none.isEmpty=" + none.isEmpty()
                        + " neg.isEmpty=" + neg.isEmpty());
            }
        } catch (Throwable t) {
            out.add("FAIL 展示栈夹取：" + t);
        }
    }

    /**
     * 4. 空入参必须安全退化（能力桥会在任何 tick 被第三方调用，不能抛异常）。
     */
    private static void nullSafety(List<String> out, int[] ok) {
        try {
            boolean netNull = MaterializeQuote.policy(null, ItemInfo.fromItem(Items.DIAMOND)) == null;
            boolean infoNull = MaterializeQuote.policy(null, null) == null;
            long amountNullNet = MaterializeQuote.materializedAmount(null, ItemInfo.fromItem(Items.DIAMOND));
            if (netNull && infoNull && amountNullNet == 0L) {
                ok[0]++;
                out.add("OK   空入参安全：net=null 或 info=null 时报价为 null（不可交付），物化量报 0");
            } else {
                out.add("FAIL 空入参安全：netNull=" + netNull + " infoNull=" + infoNull
                        + " amountNullNet=" + amountNullNet);
            }
        } catch (Throwable t) {
            out.add("FAIL 空入参安全：" + t);
        }
    }
}
