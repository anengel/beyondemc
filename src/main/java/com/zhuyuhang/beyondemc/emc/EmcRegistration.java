package com.zhuyuhang.beyondemc.emc;

import com.wintercogs.beyonddimensions.api.storage.key.StackKeyRegistry;
import com.zhuyuhang.beyondemc.BeyondEmc;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * EMC 资源类型的注册。
 *
 * <p><b>时机很关键</b>：必须早于任何网络反序列化。未注册的类型在
 * {@code AbstractUnorderedStackHandler.deserializeNBT} 里会被 {@code catch(Throwable)} 吞掉并
 * 静默丢弃条目（`AbstractUnorderedStackHandler.java:889-898`）—— 也就是"存档里的 EMC 全部消失，
 * 而且没有任何报错"。
 *
 * <p>用 {@code event.enqueueWork} 而不是直接在监听器里注册：{@code FMLCommonSetupEvent} 的监听器
 * 默认并行执行，而 {@code StackKeyRegistry} 内部是一个普通 {@code HashMap}，
 * Beyond Dimensions 自己也在同一阶段注册它内建的类型，并发写会有风险。
 */
public final class EmcRegistration {

    private EmcRegistration() {
    }

    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            StackKeyRegistry.registerType(EmcStackKey.INSTANCE);
            BeyondEmc.LOGGER.info("[BeyondEMC] 已注册 EMC 资源类型: {}", EmcStackKey.ID);

            // 0.3.0：物化物品条目的资源类型。
            // 与 EMC 池一样必须在这里（FMLCommonSetupEvent）注册 —— 晚了的话读档时
            // StackKeyRegistry.getType(id) 找不到类型，BD 会把该类型的条目静默丢弃。
            StackKeyRegistry.registerType(EmcItemKey.INSTANCE);
            BeyondEmc.LOGGER.info("[BeyondEMC] 已注册物化物品资源类型: {}", EmcItemKey.ID);
        });
    }
}
