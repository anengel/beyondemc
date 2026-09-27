package com.zhuyuhang.beyondemc.diag;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.exchange.ExchangeService;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.world.item.Items;

/**
 * 阶段 4 的诊断自检：兑换服务的校验门。
 *
 * <p>断言是<b>自适应</b>的（与阶段 3 同思路）：EMC 表是否就绪决定了 `getValue` 是否非零，
 * 因此无头服务器上只能验证"拒绝路径"，玩家登录后才走得到"完整报价"路径。
 *
 * <p><b>无头测不到的部分</b>（必须在游戏内确认）：
 * <ul>
 *   <li>{@code ExchangeService.canAccess} 需要 {@code ServerPlayer}；</li>
 *   <li>背包容量预检与发放（需要真实玩家背包）；</li>
 *   <li>端到端的"扣费 + 给物品"。</li>
 * </ul>
 * 这三项列在 `docs/testing/phase4-report.md` 的实机清单里。
 */
public final class Phase4SelfTest {

    private Phase4SelfTest() {
    }

    public static java.util.List<String> run() {
        java.util.List<String> out = new java.util.ArrayList<>();
        int ok = 0;

        ItemInfo diamond = ItemInfo.fromItem(Items.DIAMOND);

        // ---- 1. 非法输入：net / count ----
        try {
            DimensionsNet net = new DimensionsNet(true);
            NetKnowledgeStore.learn(net, diamond);

            boolean netNull = !ExchangeService.validate(null, diamond, 1).ok();
            boolean zero = !ExchangeService.validate(net, diamond, 0).ok();
            boolean negative = !ExchangeService.validate(net, diamond, -5).ok();

            if (netNull && zero && negative) {
                out.add("OK   兑换校验：net=null / count=0 / count<0 全部被拒");
                ok++;
            } else {
                out.add("FAIL 兑换校验：netNull=" + netNull + " zero=" + zero + " negative=" + negative);
            }
        } catch (Throwable t) {
            out.add("FAIL 兑换校验（非法输入）：" + t);
        }

        // ---- 2. 只允许兑换"已学习"的物品（核心规则）----
        try {
            DimensionsNet learned = new DimensionsNet(true);
            DimensionsNet notLearned = new DimensionsNet(true);
            NetKnowledgeStore.learn(learned, diamond);
            NetEmcAccessor.addEmc(learned, Long.MAX_VALUE / 4);
            NetEmcAccessor.addEmc(notLearned, Long.MAX_VALUE / 4);

            var rLearned = ExchangeService.validate(learned, diamond, 1);
            var rNotLearned = ExchangeService.validate(notLearned, diamond, 1);

            // 未学习必须被拒，且理由要明确指向"未学会"
            boolean rejectedForKnowledge = !rNotLearned.ok()
                    && rNotLearned.reason().contains("学会");
            // 已学习：就绪时应当通过；未就绪时应因"没有 EMC 价值"被拒（这是预期）
            boolean learnedBehaves = EmcAvailability.isReady()
                    ? rLearned.ok()
                    : (!rLearned.ok() && rLearned.reason().contains("EMC 价值"));

            if (rejectedForKnowledge && learnedBehaves) {
                out.add("OK   兑换门禁：未学习物品被拒（理由=\"" + rNotLearned.reason()
                        + "\"）；已学习物品 " + (EmcAvailability.isReady()
                        ? "通过校验（单价 " + rLearned.unitPrice() + "）"
                        : "在 EMC 表未就绪时被拒（理由=\"" + rLearned.reason() + "\"，属预期）"));
                ok++;
            } else {
                out.add("FAIL 兑换门禁：未学习被拒=" + rejectedForKnowledge
                        + "，已学习行为异常=" + rLearned + "（已就绪=" + EmcAvailability.isReady() + "）");
            }
        } catch (Throwable t) {
            out.add("FAIL 兑换门禁：" + t);
        }

        // ---- 3. 完整报价与扣费（仅 EMC 表就绪时可测）----
        if (EmcAvailability.isReady()) {
            try {
                DimensionsNet net = new DimensionsNet(true);
                NetKnowledgeStore.learn(net, diamond);
                long unit = IEMCProxy.INSTANCE.getValue(diamond);

                // 余额不足
                var poor = ExchangeService.validate(net, diamond, 64);
                boolean poorRejected = !poor.ok() && poor.reason().contains("不足");

                // 补足余额后应通过，且总价 = 单价 × 64
                long need = NetEmcAccessor.saturatingMultiply(unit, 64);
                NetEmcAccessor.addEmc(net, need);
                var rich = ExchangeService.validate(net, diamond, 64);
                boolean priceOk = rich.ok() && rich.unitPrice() == unit && rich.totalCost() == need;

                // 真正扣费
                long spent = NetEmcAccessor.spendEmc(net, rich.totalCost());
                long left = NetEmcAccessor.getEmc(net);

                if (poorRejected && priceOk && spent == need && left == 0L) {
                    out.add("OK   兑换报价：若余额不足则拒绝；补足后 64 个钻石 = " + need
                            + " EMC（单价 " + unit + "，即【购买价】），扣费后余额归零");
                    ok++;
                } else {
                    out.add("FAIL 兑换报价：poorRejected=" + poorRejected + " priceOk=" + priceOk
                            + " spent=" + spent + " left=" + left + " need=" + need);
                }
            } catch (Throwable t) {
                out.add("FAIL 兑换报价：" + t);
            }
        } else {
            out.add("SKIP 兑换报价：EMC 表未就绪（无玩家登录），完整报价与扣费需在游戏内确认");
        }

        // ---- 4. 防刷：购买价必须 >= 回收价，否则存在"低买高卖"的净收益路径 ----
        //
        // 注意这里断言的是 >=，不是 >：
        // ProjectE 的 covalenceLoss 默认就是 1.0（ServerConfig.java:147，范围 0.1~1.0），
        // 默认配置下买卖【同价】，存入→取出循环是【中性】的（不亏不赚）。
        // 防刷的实质是"不存在净收益路径"，而不是"必然亏损"。
        if (EmcAvailability.isReady()) {
            try {
                long buy = IEMCProxy.INSTANCE.getValue(diamond);
                long sell = IEMCProxy.INSTANCE.getSellValue(diamond);
                if (buy >= sell) {
                    String note = (buy == sell)
                            ? "默认 covalenceLoss=1.0 → 买卖同价，存入→取出循环【中性】，无净收益路径"
                            : "存在差价 " + (buy - sell) + "（covalenceLoss<1）→ 循环【亏损】";
                    out.add("OK   防刷断言：钻石 购买价 " + buy + " >= 回收价 " + sell + "；" + note);
                    ok++;
                } else {
                    out.add("FAIL 防刷断言：购买价 " + buy + " < 回收价 " + sell
                            + " → 存在低买高卖套利！请检查 ProjectE 的 covalenceLoss 配置");
                }
            } catch (Throwable t) {
                out.add("FAIL 防刷断言：" + t);
            }
        } else {
            out.add("SKIP 防刷断言：EMC 表未就绪，买卖价关系需在游戏内确认");
        }

        // ---- 5. 数量边界：极大 count 必须被安全处理（不溢出、不崩）----
        try {
            DimensionsNet net = new DimensionsNet(true);
            NetKnowledgeStore.learn(net, diamond);
            NetEmcAccessor.addEmc(net, Long.MAX_VALUE / 4);

            // count 是 int，用最大值试；结果无论通过与否，都不能抛异常、不能算出负代价
            var huge = ExchangeService.validate(net, diamond, Integer.MAX_VALUE);
            boolean noNegative = huge.totalCost() >= 0L;
            boolean handled = !huge.ok() || huge.totalCost() > 0L;

            if (noNegative && handled) {
                out.add("OK   数量边界：count=" + Integer.MAX_VALUE + " 被安全处理（"
                        + (huge.ok() ? "通过，代价 " + huge.totalCost() : "拒绝：" + huge.reason()) + "）");
                ok++;
            } else {
                out.add("FAIL 数量边界：totalCost=" + huge.totalCost() + " ok=" + huge.ok());
            }
        } catch (Throwable t) {
            out.add("FAIL 数量边界：" + t);
        }

        out.add("---- 阶段 4 自检结果：" + ok + " 项通过"
                + (EmcAvailability.isReady() ? "" : "（EMC 表未就绪，2 项跳过）") + " ----");
        return out;
    }
}
