package com.zhuyuhang.beyondemc.exchange;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 点击虚拟条目时的**意图**。
 *
 * <p>0.2.0 起，"用 EMC 兑换物品"分成两种投放方式，它们在服务端是**不同的行为**，
 * 所以必须由客户端明确告知意图，而不是让服务端猜：
 *
 * <ul>
 *   <li>{@link #PICKUP_TO_CURSOR} —— 像原版拾取一样，物品**吸附到鼠标**（carried stack），
 *       由玩家自己决定放哪。左键 = 一组，右键 = 一半。</li>
 *   <li>{@link #QUICK_MOVE_TO_INVENTORY} —— 直接放进背包（0.1.0 的旧行为，
 *       保留为 Shift+左键的快捷方式；放不下时按能放下的数量裁剪）。</li>
 * </ul>
 *
 * <p><b>客户端传来的数量只是建议</b>：两种意图下服务端都会重新裁剪
 * （鼠标容量 / 余额 / 背包空间），并以**实际投放的数量**收费。
 */
public enum ExchangeIntent {

    /** 吸附到鼠标。 */
    PICKUP_TO_CURSOR,

    /** 直接放进背包。 */
    QUICK_MOVE_TO_INVENTORY;

    /**
     * 显式的单字节编解码。
     *
     * <p>刻意不用 {@code ByteBufCodecs.idMapper} 之类的通用映射：
     * 这个枚举会随网络包一起序列化，**序号即协议**。
     * 手写编解码让"序号不能重排"这件事在代码里看得见 ——
     * 以后往中间插入新枚举值时，一眼就知道会破坏兼容。
     */
    public static final StreamCodec<ByteBuf, ExchangeIntent> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> buf.writeByte(value.ordinal()),
            buf -> {
                int ordinal = buf.readByte() & 0xFF;
                ExchangeIntent[] values = values();
                // 越界时回落到"直接进背包"这个最保守的行为，绝不抛异常中断网络线程
                return ordinal >= 0 && ordinal < values.length
                        ? values[ordinal]
                        : QUICK_MOVE_TO_INVENTORY;
            });
}
