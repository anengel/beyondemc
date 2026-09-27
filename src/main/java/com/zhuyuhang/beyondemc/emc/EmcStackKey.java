package com.zhuyuhang.beyondemc.emc;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.wintercogs.beyonddimensions.api.storage.key.IStackRender;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.LongStackKey;
import com.zhuyuhang.beyondemc.BeyondEmc;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

/**
 * 维度网络里的 EMC 资源类型。
 *
 * <p>实现方式是照抄 Beyond Dimensions 自己的 {@code ManaStackKey}（最干净的样板）：
 * 单例 + 无字段 {@link MapCodec} + 空 NBT/网络负载。
 * 语义上就是"整张网络只有这一个 EMC 池"。
 *
 * <p><b>刻意不覆写 {@code getVanillaMaxStackSize()}</b>：{@link LongStackKey} 的默认实现返回
 * {@code Long.MAX_VALUE}，正合需求。{@code ManaStackKey} / {@code EnergyStackKey} 把它覆写成了
 * {@code 1000000}，会导致 BD 内部多处 {@code Math.min(..., getVanillaMaxStackSize())} 把单次
 * 存取/兑换截断在 100 万（架构文档风险 R6）。
 *
 * <p><b>注册</b>：必须通过 {@link EmcRegistration} 在 {@code FMLCommonSetupEvent} 注册进
 * {@code StackKeyRegistry}，且要早于任何网络反序列化 —— 否则读档时找不到该类型，
 * BD 会 {@code catch(Throwable)} 静默丢弃条目（`AbstractUnorderedStackHandler.java:889-898`）。
 */
public class EmcStackKey extends LongStackKey<EmcType> {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(BeyondEmc.MOD_ID, "stack_type/emc");

    /** 唯一实例。 */
    public static final EmcStackKey INSTANCE = new EmcStackKey();

    /** 无字段编解码器：decode 直接返回单例，encode 不写任何键。 */
    public static final MapCodec<EmcStackKey> TYPE_CODEC = new MapCodec<>() {
        @Override
        public <T> DataResult<EmcStackKey> decode(com.mojang.serialization.DynamicOps<T> ops,
                                                  com.mojang.serialization.MapLike<T> input) {
            return DataResult.success(EmcStackKey.INSTANCE);
        }

        @Override
        public <T> com.mojang.serialization.RecordBuilder<T> encode(EmcStackKey value,
                                                                    com.mojang.serialization.DynamicOps<T> ops,
                                                                    com.mojang.serialization.RecordBuilder<T> prefix) {
            return prefix; // 不写任何字段
        }

        @Override
        public <T> Stream<T> keys(com.mojang.serialization.DynamicOps<T> ops) {
            return Stream.empty();
        }
    };

    private EmcStackKey() {
        this.stack = new EmcType(0);
    }

    @Override
    public MapCodec<EmcStackKey> codec() {
        return TYPE_CODEC;
    }

    @Override
    public ResourceLocation getTypeID() {
        return ID;
    }

    /**
     * 用作"按模组 id 排序"的分组依据。EMC 这个概念来自 ProjectE，但该资源类型由本模组定义，
     * 故返回本模组 id，避免暗示 ProjectE 定义了它。
     */
    @Override
    public String getModId() {
        return BeyondEmc.MOD_ID;
    }

    @Override
    public EmcStackKey getEmpty() {
        return INSTANCE;
    }

    @Override
    public @Nullable KeyAmount fromStackObject(Object stack) {
        if (stack instanceof EmcType emcType) {
            return new KeyAmount(INSTANCE, emcType.getStackCount());
        }
        return null;
    }

    @Override
    public @Nullable EmcStackKey fromSourceObject(Object key, net.minecraft.core.component.DataComponentPatch ignored) {
        // 允许从 EmcType 或任意 Number 映射到同一个 Key 实例
        if (key instanceof EmcType || key instanceof Number) {
            return INSTANCE;
        }
        return null;
    }

    @Override
    public @NotNull EmcType getSource() {
        return this.stack;
    }

    @Override
    public EmcType getEmptyStack() {
        return new EmcType(0);
    }

    @Override
    public boolean hasTag(TagKey<?> tagKey) {
        return false;
    }

    @Override
    public Stream<? extends TagKey<?>> getTags() {
        return Stream.empty();
    }

    // ------- 网络序列化：仅写 typeId；读回单例 -------

    @Override
    public void serialize(RegistryFriendlyByteBuf buf) {
        // 无负载
    }

    @Override
    public @NotNull EmcStackKey deserialize(RegistryFriendlyByteBuf buf) {
        return INSTANCE;
    }

    // ------- NBT：无负载；读取直接返回单例 -------

    @Override
    public @NotNull CompoundTag serializeNBT(HolderLookup.Provider levelRegistryAccess) {
        return new CompoundTag();
    }

    @Override
    public @NotNull EmcStackKey deserializeNBT(CompoundTag nbt, HolderLookup.Provider levelRegistryAccess) {
        return INSTANCE;
    }

    // ------- 渲染 -------

    @Override
    public @NotNull IStackRender getRender() {
        return EmcStackKeyRender.INSTANCE;
    }
}
