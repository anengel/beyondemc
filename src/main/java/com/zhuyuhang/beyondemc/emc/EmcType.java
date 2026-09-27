package com.zhuyuhang.beyondemc.emc;

import com.mojang.serialization.Codec;
import com.wintercogs.beyonddimensions.api.longtype.LongType;
import net.minecraft.network.chat.Component;

/**
 * EMC 的"纯数值型堆叠"包装，对应 {@code ManaType} / {@code EnergyType} 的角色。
 *
 * <p>注意 {@link LongType#equals} / {@link LongType#hashCode} 只比较类，不比较数值 ——
 * 这是"整个网络只有一个 EMC 池"这一语义的基础。
 */
public class EmcType extends LongType<EmcType> {

    public static final Codec<EmcType> CODEC = createCodec(EmcType::new);

    public EmcType(long amount) {
        this.stackCount = amount;
    }

    @Override
    public Component getName() {
        return Component.translatable("types.beyondemc.emc_type.name");
    }

    @Override
    public EmcType getEmpty() {
        return new EmcType(0);
    }

    @Override
    public EmcType copy() {
        return new EmcType(stackCount);
    }

    @Override
    public EmcType copyWithAmount(long amount) {
        return new EmcType(amount);
    }
}
