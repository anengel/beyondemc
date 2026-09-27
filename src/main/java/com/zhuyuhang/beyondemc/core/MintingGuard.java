package com.zhuyuhang.beyondemc.core;

/**
 * "我们正在往网络里铸造兑换物品"的标记（ThreadLocal 深度计数）。
 *
 * <h2>为什么必须有这个守卫</h2>
 * 网络接口兑换的实现方式是：**先把兑换出的物品写进网络存储，再让 BD 原生的抽取把它取走**
 * （因为 {@code UnifiedStorageBeforeExtractHandler} 只能改抽取内容、不能凭空造物品）。
 *
 * <p>但直接写进存储会立刻撞上我们自己的**存入折算钩子** —— 那件物品有 EMC 价值，
 * 会被当场折算回 EMC，于是"扣了购买价的 EMC、又按回收价退回"，整个兑换自我抵消。
 *
 * <p>所以铸造期间必须让折算钩子<b>原样放行</b>。这与读档守卫（{@code LoadingGuard}）
 * 是同一套模式：用一个显式的、只在线程内可见的标记，让钩子知道"这次插入不是玩家存的"。
 *
 * <h2>为什么复用 LoadingGuard 的深度计数语义</h2>
 * 铸造过程内部可能再次触发嵌套插入（例如 BD 内部的分发/合并逻辑），
 * 用深度计数可以保证只有最外层退出时才复位。
 *
 * <h2>安全性</h2>
 * 这个标记<b>只放宽"是否折算"这一项</b>，不影响任何权限、余额或数量校验 ——
 * 也就是说，即使这个标记因为异常泄漏，最坏后果是"接口取出了一件没被折算的物品"，
 * 而不是"可以凭空印物品"：铸造所需的 EMC 是在铸造<b>之前</b>就扣掉的，
 * 且铸造数量由余额决定。
 */
public final class MintingGuard {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private MintingGuard() {
    }

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int d = DEPTH.get() - 1;
        DEPTH.set(Math.max(0, d));
    }

    /** 当前线程是否正在铸造。任何异常都保守返回 false（即照常折算）。 */
    public static boolean isMinting() {
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
