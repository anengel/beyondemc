package com.zhuyuhang.beyondemc.materialize;

/**
 * "当前正在执行物化层自身的存储维护"的标志（ThreadLocal 深度计数）。对应关系：
 * {@code MintingGuard} : 存入折算钩子 ↦ {@code MaterializingGuard} : 抽取收费护栏。
 *
 * <h2>为什么必须有这个守卫</h2>
 * 0.3 让物化条目落进真实存储之后，BD 的<b>三条抽取入口</b>（按槽位 / 按标签 / 按键）都会汇聚到
 * 同一个抽取钩子（{@code UnifiedStorage.java:105-143}）。为了让"抽物化条目"也必然扣 EMC，
 * 钩子对 {@code EmcItemKey} 一律<b>改道收费</b>，而不是放行。
 *
 * <p>但 {@link ItemMaterializer} 收缩/清空物化条目时用的正是 {@code storage.extract(EmcItemKey,…)} ——
 * 那是"物化层在维护自己的派生数据"，不是外部取用。若不加以区分，它会被自己的护栏当成外部抽取：
 * 要么改道去收费（凭空扣钱），要么因余额不足被 cancel（<b>条目永远无法收缩</b>的自锁）。
 *
 * <p>所以物化层的写回全部包在本守卫内，钩子对 {@code EmcItemKey} 只在
 * {@link #isActive()} 为真时放行。
 *
 * <h2>用深度计数而不是布尔</h2>
 * 与 {@code MintingGuard} / {@code LoadingGuard} 同一套模式：BD 内部的分发/合并逻辑可能触发嵌套
 * 插入或抽取，布尔量会在内层退出时过早复位。只有最外层退出才真正清标志。
 *
 * <h2>安全性</h2>
 * 本守卫只放宽"物化键是否走收费路径"这一项。即使它因异常泄漏，最坏后果也只是
 * "一次外部抽取物化条目没有被扣费" —— 而物化条目本身是<b>可丢弃重建的纯派生</b>，
 * 不构成任何"凭空印物品"的通道：真实 EMC 池与学习集合不受影响，
 * 下一次 {@link ItemMaterializer#refresh} 会把条目重算回正确数量。
 */
public final class MaterializingGuard {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private MaterializingGuard() {
    }

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int d = DEPTH.get() - 1;
        DEPTH.set(Math.max(0, d));
    }

    /** 当前线程是否正在做物化层自身的存储维护。任何异常都保守返回 false（即照常改道收费）。 */
    public static boolean isActive() {
        try {
            return DEPTH.get() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 诊断用。 */
    public static int depth() {
        try {
            return DEPTH.get();
        } catch (Throwable t) {
            return -1;
        }
    }
}
