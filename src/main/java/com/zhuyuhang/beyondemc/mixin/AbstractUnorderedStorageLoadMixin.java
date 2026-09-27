package com.zhuyuhang.beyondemc.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler;
import com.zhuyuhang.beyondemc.core.LoadingGuard;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 读档守卫：在存储反序列化期间置起 {@link LoadingGuard} 标志，让折算钩子跳过。
 *
 * <p>为什么需要：{@code DimensionsNet.load} → {@code unifiedStorage.deserializeNBT(...)} →
 * {@code acceptEntry} → {@code insert}（`AbstractUnorderedStackHandler.java:989`）
 * 会**再次触发折算钩子**。不拦的话每次读档都会把存档里剩余的、有 EMC 价值的物品再折算一遍，
 * 且不可逆（架构文档风险 R1）。
 *
 * <p>为什么用 MixinExtras 的 {@link WrapMethod} 而不是 {@code @Inject(HEAD)} + {@code @Inject(RETURN)}：
 * 需要 **try/finally** 语义 —— 反序列化中途抛异常时也必须复位标志，
 * 否则该线程会永久处于"加载中"状态，折算彻底失效（这是个很难查的静默故障）。
 * 运行时已确认 NeoForge 21.1.234 自带 MixinExtras 0.5.3（启动日志有 "Initializing MixinExtras"）。
 *
 * <p><b>这一条刻意用 {@code require = 1}（全项目唯一一条）</b>：其余 Mixin 都遵循
 * {@code require = 0} 的优雅降级策略（风险 R8），但**本守卫的静默失效等于数据损坏**
 * —— BD 哪天改了方法名，我们宁可启动时响亮地崩，也不要安静地每次读档重写玩家存档。
 * 失效时的处置见 `docs/plan/ROADMAP.md` §3.4 的升级回归清单。
 */
@org.spongepowered.asm.mixin.Mixin(value = AbstractUnorderedStackHandler.class, remap = false)
public abstract class AbstractUnorderedStorageLoadMixin {

    @WrapMethod(method = "deserializeNBT", require = 1)
    private void beyondemc$guardDeserialize(HolderLookup.Provider provider, CompoundTag tag, Operation<Void> original) {
        LoadingGuard.enter();
        try {
            original.call(provider, tag);
        } finally {
            LoadingGuard.exit();
        }
    }
}
