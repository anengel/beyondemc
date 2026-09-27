package com.zhuyuhang.beyondemc.mixin;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把"网络级已学习物品集合"持久化进网络自己的存档。
 *
 * <p>BD 的 {@code save/load} 只有 9 个固定 NBT 键，**没有自定义数据槽**（`DimensionsNet.java:394`）。
 * DataAttachment 在 NeoForge 21.1 也不支持普通 {@code SavedData}。所以用 Mixin 往
 * {@code BDNet_<id>.dat} 里塞一个额外键 {@link NetKnowledgeStore#NBT_KEY}。
 *
 * <p>好处：生命周期完全跟随网络文件 —— 网络销毁 = 文件删除 = 集合消失，零泄漏、零清理代码；
 * 而且卸载本模组时只留一个无人读取的 NBT 键（比留一个孤儿 {@code .dat} 文件干净）。
 * 详见 `docs/design/decisions.md` ADR-002。
 *
 * <p>三条注入都用 {@code require = 0} 优雅降级：失配的后果是"学习列表丢失"，
 * 属于功能退化而非数据损坏，与 {@code AbstractUnorderedStorageLoadMixin} 的取舍不同。
 */
@Mixin(value = DimensionsNet.class, remap = false)
public abstract class DimensionsNetMixin {

    /** 从存档读回学习集合。{@code load} 是静态工厂方法。 */
    @Inject(method = "load", at = @At("RETURN"), require = 0)
    private static void beyondemc$readKnowledge(CompoundTag tag, HolderLookup.Provider registryAccess,
                                               CallbackInfoReturnable<DimensionsNet> cir) {
        NetKnowledgeStore.readInto(cir.getReturnValue(), tag);
    }

    /** 把学习集合写进存档。 */
    @Inject(method = "save", at = @At("RETURN"), require = 0)
    private void beyondemc$writeKnowledge(CompoundTag tag, HolderLookup.Provider registryAccess,
                                         CallbackInfoReturnable<CompoundTag> cir) {
        NetKnowledgeStore.writeTo(cir.getReturnValue(), (DimensionsNet) (Object) this);
    }

    /**
     * 网络合并时把源网络的学习集合并进来。
     * 用 HEAD 而不是 RETURN —— {@code mergeOtherNet} 结尾会 {@code otherNet.destroySelf()}
     * （`DimensionsNet.java:743`），到 RETURN 时源网络已经销毁了。
     */
    @Inject(method = "mergeOtherNet", at = @At("HEAD"), require = 0)
    private void beyondemc$mergeKnowledge(DimensionsNet otherNet, CallbackInfo ci) {
        NetKnowledgeStore.merge((DimensionsNet) (Object) this, otherNet);
    }
}
