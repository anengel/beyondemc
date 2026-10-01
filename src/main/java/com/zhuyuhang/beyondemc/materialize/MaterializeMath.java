package com.zhuyuhang.beyondemc.materialize;

import moze_intel.projecte.api.ItemInfo;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * 物化数量的**纯函数**：给定网络 EMC 与学习集合，算出每个物品应该物化多少个。
 *
 * <h2>口径：逐件独立 {@code floor(EMC ÷ 单价)}（忠实搬运 0.2 的显示口径）</h2>
 * 0.2 的 {@code VirtualEntryProvider.java:151} 就是逐件 {@code affordable = emc / unitPrice}，
 * 且买不起（{@code affordable <= 0}）直接不显示（{@code :152-155}）。0.3 把这同一套数学搬到服务端，
 * <b>不共享预算</b>。
 *
 * <pre>
 *   for info in 学习集合：
 *       p = price(info)                     // IEMCProxy.getValue，购买价
 *       if p &lt;= 0            → 不物化        // 与 0.2 一致：无价格的物品不显示
 *       if realStock(info) &gt; 0 → 不物化      // 与 0.2 的 skipStocked 一致，并消除重复行
 *       n = floor(E / p)                     // ★ 逐件独立，不扣减任何共享预算
 *       if n == 0            → 不物化        // 与 0.2 一致：0 数量条目会被列表丢弃
 *       物化(info) = n
 * </pre>
 *
 * <p><b>关键性质</b>：各物品彼此独立，{@code Σ (count_i × price_i)} <b>允许</b>大于 {@code E}。
 * 例：{@code E=100、钻石单价 50、泥土单价 1} ⇒ 钻石 2、泥土 100（合计价值 200 &gt; 100）。
 * 这不是 bug（详见 {@code docs/plan/ROADMAP-0.3.0.md} §2.3 的论证）：
 * <b>防刷物品靠"交付即扣费"（INV-1′），而不是靠"显示不超额"</b>。
 * 取走 1 颗钻石会让 {@code E} 降到 70，泥土随即从 100 变 70 —— 这正是用户描述的行为。
 *
 * <h2>为什么要做成纯函数</h2>
 * 它是本功能唯一有实质逻辑的部分，也是最容易出错的部分（价格边界、真实库存排除、上限截断、
 * 幂等性）。做成纯函数后可以在<b>无头环境</b>里用假价格表做决定性验证，完全不依赖 EMC 表是否就绪、
 * 不依赖服务端、不依赖任何存储。
 */
public final class MaterializeMath {

    private MaterializeMath() {
    }

    /**
     * 计算物化结果。
     *
     * @param emc        网络 EMC 余额（&lt;=0 时直接返回空表）
     * @param learned    网络学习集合（可为 null/空）
     * @param price      购买价查询（{@code IEMCProxy.getValue}）；异常/负数视为"无价格"
     * @param realStock  真实库存查询（{@code UnifiedStorage.getStackByKey(ItemStackKey)}）；&gt;0 则不物化
     * @param maxItems   条目数上限（&lt;=0 视为不限）
     * @return 有序的 {@code 物品 → 物化数量}（顺序确定：价格升序 → 物品注册名 → toString）
     */
    public static Map<ItemInfo, Long> solve(long emc,
                                            Collection<ItemInfo> learned,
                                            ToLongFunction<ItemInfo> price,
                                            ToLongFunction<ItemInfo> realStock,
                                            int maxItems) {
        Map<ItemInfo, Long> out = new LinkedHashMap<>();
        if (emc <= 0L || learned == null || learned.isEmpty()) {
            return out;
        }

        List<Candidate> candidates = new ArrayList<>();
        for (ItemInfo info : learned) {
            if (info == null) {
                continue;
            }
            long p = safeQuery(price, info);
            if (p <= 0L) {
                continue; // 无 EMC 价值：与 0.2 一致，不显示
            }
            if (safeQuery(realStock, info) > 0L) {
                continue; // 已有真实库存：让给 BD 原生行，避免重复行（勘误 E3）
            }
            long n = emc / p;
            if (n <= 0L) {
                continue; // 买不起：与 0.2 一致，不显示
            }
            candidates.add(new Candidate(info, p, n));
        }

        // 确定性排序：它是"上限截断"与"幂等"成立的前提
        candidates.sort(Comparator
                .comparingLong(Candidate::price)
                .thenComparing(c -> stableId(c.info()))
                .thenComparing(c -> String.valueOf(c.info())));

        int limit = maxItems <= 0 ? Integer.MAX_VALUE : maxItems;
        for (Candidate c : candidates) {
            if (out.size() >= limit) {
                break;
            }
            out.put(c.info(), c.count());
        }
        return out;
    }

    /** 物品的稳定标识（注册名）。拿不到时退回 {@code toString()}，绝不抛异常。 */
    private static String stableId(ItemInfo info) {
        try {
            return BuiltInRegistries.ITEM.getKey(info.getItem().value()).toString();
        } catch (Throwable t) {
            return String.valueOf(info);
        }
    }

    /** 价格/库存查询一律容错：任何异常都当作 0（安全方向：不物化）。 */
    private static long safeQuery(ToLongFunction<ItemInfo> fn, ItemInfo info) {
        try {
            return fn.applyAsLong(info);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private record Candidate(ItemInfo info, long price, long count) {
    }
}
