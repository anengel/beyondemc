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
        deliverableBoundaries(out, ok);
        deliverableMatchesChargeFormula(out, ok);
        displayStackClamping(out, ok);
        nullSafety(out, ok);

        out.add("---- Create/第三方暴露（0.3.2）自检结果：" + ok[0] + " 项通过 ----");
        return out;
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
     * 1. {@link MaterializeQuote.Policy#deliverable} 的边界。
     *
     * <p>用直接构造 Policy 的方式测纯算式，不触碰 EMC 表 / 网络 / 配置。
     */
    private static void deliverableBoundaries(List<String> out, int[] ok) {
        try {
            MaterializeQuote.Policy rich = new MaterializeQuote.Policy(
                    ItemInfo.fromItem(Items.DIAMOND), 8192L, 8192L * 1000L, 1000L);

            long a = rich.deliverable(0L, 500L);          // 不要 → 0
            long b = rich.deliverable(64L, 0L);           // 没有物化条目 → 0
            long c = rich.deliverable(64L, 500L);         // 物化量够 → 取需求
            long d = rich.deliverable(64L, 10L);          // 物化量不够 → 取物化量
            long e = rich.deliverable(2000L, 500L);       // 需求大于物化量且大于余额份额 → 取小者
            long f = rich.deliverable(-5L, 500L);         // 负需求 → 0
            long g = rich.deliverable(64L, -5L);          // 负物化量 → 0

            boolean pass = a == 0L && b == 0L && c == 64L && d == 10L && e == 500L && f == 0L && g == 0L;
            if (pass) {
                ok[0]++;
                out.add("OK   报价边界：不要=0 / 无条目=0 / 取需求=64 / 受物化量限=10 / 受余额限=500 / 负数=0");
            } else {
                out.add("FAIL 报价边界：a=" + a + " b=" + b + " c=" + c + " d=" + d
                        + " e=" + e + " f=" + f + " g=" + g + "（期望 0/0/64/10/500/0/0）");
            }

            // 余额不足：affordable = 0 ⇒ 一律交付 0（"不交半份"）
            MaterializeQuote.Policy broke = new MaterializeQuote.Policy(
                    ItemInfo.fromItem(Items.DIAMOND), 8192L, 100L, 0L);
            long h = broke.deliverable(64L, 500L);
            if (h == 0L) {
                ok[0]++;
                out.add("OK   余额不足：可负担份数为 0 时交付 0（不交半份）");
            } else {
                out.add("FAIL 余额不足：交付了 " + h + " 份（期望 0）");
            }
        } catch (Throwable t) {
            out.add("FAIL 报价边界：" + t);
        }
    }

    /**
     * 2. 「模拟报量」与「真实扣费」必须同算式。
     *
     * <p>真实扣费段在 {@code InterfaceWithdrawService} 里用的是
     * {@code want = min(请求量, 余额 / 购买价)}（那里物化量不设限，所以传 {@code Long.MAX_VALUE}）。
     * 这里把同一算式跑一遍，确认报价器给出相同的数 —— 两条暴露路径与网络接口
     * **永远不会分叉**，这正是把判断链收口到 {@code MaterializeQuote} 的目的。
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
                        MaterializeQuote.Policy p =
                                new MaterializeQuote.Policy(null, price, balance, affordable);
                        // 传 Long.MAX_VALUE 等价于"不受物化量限制"，与收费段一致
                        long actual = p.deliverable(want, Long.MAX_VALUE);
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
     * 3. 展示栈的数量必须夹在 {@code [1, 原版堆叠数]}。
     */
    private static void displayStackClamping(List<String> out, int[] ok) {
        try {
            ItemInfo diamond = ItemInfo.fromItem(Items.DIAMOND);
            int max = new ItemStack(Items.DIAMOND).getMaxStackSize(); // 64

            ItemStack huge = MaterializeQuote.displayStack(diamond, 1_000_000L);
            ItemStack one = MaterializeQuote.displayStack(diamond, 1L);
            ItemStack none = MaterializeQuote.displayStack(diamond, 0L);
            ItemStack neg = MaterializeQuote.displayStack(diamond, -1L);

            boolean pass = !huge.isEmpty() && huge.getCount() == max
                    && !one.isEmpty() && one.getCount() == 1
                    && none.isEmpty() && neg.isEmpty();
            if (pass) {
                ok[0]++;
                out.add("OK   展示栈夹取：1,000,000 → " + max + "；1 → 1；0 / 负数 → 空栈");
            } else {
                out.add("FAIL 展示栈夹取：huge=" + huge.getCount() + "（期望 " + max + "）one="
                        + one.getCount() + " none.isEmpty=" + none.isEmpty()
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
