package com.zhuyuhang.beyondemc.core;

/**
 * "当前正处于存储反序列化过程中"的标志。
 *
 * <p><b>为什么必须有它</b>：{@code DimensionsNet.load} 会调用
 * {@code unifiedStorage.deserializeNBT(...)}，而反序列化内部走
 * {@code acceptEntry → insert}（`AbstractUnorderedStackHandler.java:989`）
 * —— 也就是<b>会再次触发我们的折算钩子</b>。
 *
 * <p>若不拦，后果是：每次读档都会把存档里剩余的、有 EMC 价值的物品<b>再折算一遍</b>，
 * 且不可逆（架构文档风险 R1）。
 *
 * <p>阶段 1 已经证明"EMC 表就绪"这个条件在常规读档时天然为假（EMC 表要等玩家登录才构建），
 * 所以常规读档不会出事。但仍有一个漏洞：{@code DimensionsNet.getNetFromId → computeIfAbsent}
 * 会在<b>玩家已登录之后</b>按需加载某个网络，此时 EMC 表已就绪，存档里的物品就会被折叠算。
 * 正常存档不会出现这种情况（存入时已折算掉了），但<b>迁移场景</b>（给已有存档加装本模组）会。
 *
 * <p>实现用 <b>ThreadLocal 深度计数</b>而非布尔量：{@code deserializeNBT} 可能嵌套
 * （BD 自己或其它模组在反序列化里再触发一次），布尔量会在内层退出时过早复位。
 * 由 {@code AbstractUnorderedStorageLoadMixin} 的 {@code @WrapMethod} 保证 try/finally 语义
 * —— 反序列化抛异常时也必须复位，否则线程会永久卡在"加载中"状态、折算彻底失效。
 */
public final class LoadingGuard {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * 历史累计的 {@link #enter()} 次数（跨线程）。
     *
     * <p>存在的唯一目的：让自检能证明 **Mixin 真的挂上了**。
     * 只测 {@code isLoading()} 是测不出这件事的 —— 那只是在本类内部自证。
     * 若注入静默失效，标志永远不会被置起，读档就会重写存档，而自检却全绿。
     */
    private static final java.util.concurrent.atomic.AtomicLong ENTER_COUNT =
            new java.util.concurrent.atomic.AtomicLong();

    private LoadingGuard() {
    }

    public static void enter() {
        ENTER_COUNT.incrementAndGet();
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
        if (depth[0] == 0) {
            // 避免线程池场合下的 ThreadLocal 泄漏
            DEPTH.remove();
        }
    }

    /** 当前线程是否正在反序列化存储。 */
    public static boolean isLoading() {
        return DEPTH.get()[0] > 0;
    }

    /** 累计 {@link #enter()} 次数，供"Mixin 是否真的生效"的探针使用。 */
    public static long enterCount() {
        return ENTER_COUNT.get();
    }
}
