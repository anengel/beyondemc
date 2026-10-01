package com.zhuyuhang.beyondemc.emc;

import com.wintercogs.beyonddimensions.api.longtype.LongType;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.network.chat.Component;

/**
 * 物化物品条目的"数值型堆叠"包装，对应 {@link EmcType} 与 BD 的 {@code ManaType} / {@code EnergyType}
 * 的角色 —— 区别只在于它额外携带"是哪个物品"（{@link ItemInfo}）。
 *
 * <p>与 {@link EmcType} 一样，{@link LongType#equals} / {@link LongType#hashCode} 只比较类、不比较数值，
 * 这是 BD 的设计（数值放在外面的 {@code KeyAmount.amount} 里）。<b>不要</b>在这里做物品身份的比较 ——
 * 物品身份由 {@link EmcItemKey#equals} 负责（那是塌缩风险真正所在的地方）。
 *
 * <p>本类<b>不</b>提供 {@code createCodec(...)}：{@link EmcItemKey} 的编解码整体走
 * {@link ItemInfo} 自带的 {@code MAP_CODEC} / {@code STREAM_CODEC}，不需要数值型 Codec。
 */
public class EmcItemType extends LongType<EmcItemType> {

    private final ItemInfo info;

    public EmcItemType(ItemInfo info, long amount) {
        this.info = info;
        this.stackCount = Math.max(0L, amount);
    }

    public EmcItemType(ItemInfo info) {
        this(info, 0L);
    }

    /** 本堆叠代表的物品身份。 */
    public ItemInfo getInfo() {
        return info;
    }

    /**
     * 显示名 = 物品自身的名称。
     *
     * <p>与 {@code EmcStackKeyRender.getDisplayName} 不同，这里<b>不</b>要求"双端安全"：
     * 本方法只在渲染/工具提示链路被调用（全部在客户端），且 {@code ItemStack#getHoverName}
     * 本身在服务端也能正常工作，所以顺带也是双端安全的。
     */
    @Override
    public Component getName() {
        try {
            return info.createStack().getHoverName();
        } catch (Throwable t) {
            // 物品被移除 / 注册表异常时不要炸掉渲染
            return Component.literal(String.valueOf(info));
        }
    }

    @Override
    public EmcItemType getEmpty() {
        return new EmcItemType(info, 0L);
    }

    @Override
    public EmcItemType copy() {
        return new EmcItemType(info, stackCount);
    }

    @Override
    public EmcItemType copyWithAmount(long amount) {
        return new EmcItemType(info, amount);
    }
}
