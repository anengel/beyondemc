package com.zhuyuhang.beyondemc.emc;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import org.jetbrains.annotations.Nullable;

/**
 * 网络 EMC 池的读写入口。所有对 EMC 的加减都必须经过这里，以保证溢出保护一致。
 *
 * <p><b>BD 的两个返回值语义相反，极易搞错</b>：
 * <ul>
 *   <li>{@code UnifiedStorage.insert(key, amount, simulate)} 返回的是<b>剩余未插入</b>的数量
 *       （证据：`DisorderedStackTypedSlot.java:163` —— {@code actualInsert = changedCount - insert(...).amount()}）；</li>
 *   <li>{@code UnifiedStorage.extract(...)} 返回的是<b>实际提取出来</b>的数量
 *       （证据：`DisorderedStackTypedSlot.java:456-457` —— 拿到后直接 {@code safeInsert(extract)}）。</li>
 * </ul>
 * 本类把两者统一成"实际发生了多少"，避免调用方踩坑。
 */
public final class NetEmcAccessor {

    private NetEmcAccessor() {
    }

    /** 查询网络 EMC 余额。{@code net} 为 null（例如 {@code UnifiedStorage.getEmpty()} 的空壳）时返回 0。 */
    public static long getEmc(@Nullable DimensionsNet net) {
        if (net == null) {
            return 0L;
        }
        KeyAmount ka = net.getUnifiedStorage().getStackByKey(EmcStackKey.INSTANCE);
        return Math.max(0L, ka.amount());
    }

    /**
     * 增加 EMC。
     *
     * @return <b>实际入账</b>的数量（受单键容量限制，可能小于 {@code delta}）
     */
    public static long addEmc(@Nullable DimensionsNet net, long delta) {
        if (net == null || delta <= 0L) {
            return 0L;
        }
        // insert 返回剩余量
        KeyAmount remainder = net.getUnifiedStorage().insert(EmcStackKey.INSTANCE, delta, false);
        long inserted = delta - Math.max(0L, remainder.amount());
        return Math.max(0L, inserted);
    }

    /**
     * 扣减 EMC。
     *
     * @return <b>实际扣除</b>的数量（余额不足时会小于 {@code cost}）
     */
    public static long spendEmc(@Nullable DimensionsNet net, long cost) {
        if (net == null || cost <= 0L) {
            return 0L;
        }
        // extract 返回的就是实际提取量
        KeyAmount extracted = net.getUnifiedStorage().extract(EmcStackKey.INSTANCE, cost, false, false);
        return Math.max(0L, extracted.amount());
    }

    /** 余额是否足够支付 {@code cost}。 */
    public static boolean canAfford(@Nullable DimensionsNet net, long cost) {
        return cost > 0L && getEmc(net) >= cost;
    }

    /**
     * 饱和乘法。任一侧非正 → 0；溢出 → {@code Long.MAX_VALUE}。
     *
     * <p>用于 {@code 单价 × 数量} 这类计算。ECM 是 long，网络单键容量默认也是
     * {@code Long.MAX_VALUE}，不设防的乘法会静默回绕成负数（架构文档风险 R5）。
     */
    public static long saturatingMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    /** 饱和加法。任一侧非正时按 0 处理；溢出 → {@code Long.MAX_VALUE}。 */
    public static long saturatingAdd(long a, long b) {
        long left = Math.max(0L, a);
        long right = Math.max(0L, b);
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
