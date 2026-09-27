package com.zhuyuhang.beyondemc.core;

/**
 * 抽取上下文：让"抽取是在模拟还是真做"这件事能被<b>抽取钩子</b>读到。
 *
 * <h2>为什么需要它</h2>
 * {@code UnifiedStorageBeforeExtractHandler.BeforeExtractHandler#beforeExtract} 的签名是
 * {@code (originalExtract, tryExtract, net)} —— **没有 simulate 参数**
 * （`UnifiedStorageBeforeExtractHandler.java:32-36`）。但它的调用点
 * `UnifiedStorage.extract` 是拿得到 simulate 的（`UnifiedStorage.java:118-133`）。
 *
 * <p>这个区分至关重要：外部模组的自动化会大量发起**模拟抽取**（能力查询、
 * `IItemHandler.extractItem(..., true)`、漏斗预热等）。如果在模拟时也扣 EMC，
 * 一次查询就会真扣钱 —— 那是灾难性的。
 *
 * <p>所以由 {@code UnifiedStorageExtractMixin} 在进入/离开
 * {@code extract(IStackKey, long, boolean, boolean)} 时维护这里的标志。
 *
 * <h2>为什么用深度计数而不是布尔</h2>
 * `extract(int slot, ...)` 与 `extract(TagKey, ...)` 都会**转发**到
 * `extract(IStackKey, ...)`，存在嵌套。用深度计数才能保证只有最外层退出时才清标志
 * （与 {@code LoadingGuard} 同一套模式）。
 */
public final class ExtractContext {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final ThreadLocal<Boolean> SIMULATE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile long enterCount = 0;

    private ExtractContext() {
    }

    /** 进入一次 {@code extract(IStackKey, ...)}。 */
    public static void enter(boolean simulate) {
        int d = DEPTH.get();
        if (d == 0) {
            SIMULATE.set(simulate);
        }
        DEPTH.set(d + 1);
        enterCount++;
    }

    /** 离开一次 {@code extract(IStackKey, ...)}。 */
    public static void exit() {
        int d = DEPTH.get() - 1;
        if (d <= 0) {
            DEPTH.set(0);
            SIMULATE.set(Boolean.FALSE); // 复位，避免污染后续同线程的调用
        } else {
            DEPTH.set(d);
        }
    }

    /**
     * 当前抽取是否为模拟。
     *
     * <p>**保守默认：拿不到上下文时返回 true（当作模拟）** ——
     * 模拟不会扣费，宁可少做事也不要错扣钱。
     */
    public static boolean isSimulate() {
        try {
            return DEPTH.get() == 0 || Boolean.TRUE.equals(SIMULATE.get());
        } catch (Throwable t) {
            return true;
        }
    }

    /** 诊断用：Mixin 生效性探针（为 0 说明 Mixin 没应用）。 */
    public static long enterCount() {
        return enterCount;
    }

    /** 诊断用：重置探针计数。 */
    public static void resetEnterCount() {
        enterCount = 0;
    }
}
