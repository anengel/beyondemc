package com.zhuyuhang.beyondemc.mixin;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import com.zhuyuhang.beyondemc.materialize.ItemMaterializer;
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
 * <p>学习集合的三条注入都用 {@code require = 0} 优雅降级：失配的后果是"学习列表丢失"，
 * 属于功能退化而非数据损坏，与 {@code AbstractUnorderedStorageLoadMixin} 的取舍不同。
 *
 * <p>0.3.0 新增的两条物化相关注入取舍<b>不同</b>：清理用 {@code require = 1}（错搬物化条目是
 * 数据正确性问题），重建用 {@code require = 0}（有打开界面的兜底刷新）。
 */
@Mixin(value = DimensionsNet.class, remap = false)
public abstract class DimensionsNetMixin {

    /** 从存档读回学习集合。{@code load} 是静态工厂方法。 */
    @Inject(method = "load", at = @At("RETURN"), require = 0)
    private static void beyondemc$readKnowledge(CompoundTag tag, HolderLookup.Provider registryAccess,
                                               CallbackInfoReturnable<DimensionsNet> cir) {
        NetKnowledgeStore.readInto(cir.getReturnValue(), tag);
        // 0.3.0：顺便登记这个网络，供 EMCRemapEvent 时的批量物化刷新遍历。
        // 这里【只登记、不刷新】：读档时 EMC 表尚未就绪（要等 EMCRemapEvent），
        // 而且此刻仍在存储反序列化的保护区内。
        ItemMaterializer.observe(cir.getReturnValue());
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

    /**
     * 网络合并前清空两边的物化条目（0.3.0）。
     *
     * <p>{@code mergeOtherNet} 会把源网络的<b>整个 {@code UnifiedStorage}</b> 逐条 insert 进目标
     * （{@code DimensionsNet.java:735-739}）。物化条目是 {@code f(EMC, 学习集合, 单价)} 的派生，
     * 被原样搬运到新网络后数量必然错误（EMC 已经合并变大）。所以先全清、合并后按新 EMC 重建。
     *
     * <p>⚠️ 这条注入<b>刻意用 {@code require = 1}</b>（与同类中其它 {@code require = 0} 不同）：
     * 静默失效的后果是"物化条目被错误搬运" —— 那是<b>数据正确性</b>问题，不是功能退化，
     * 必须让它在 BD 升级后立刻暴露（启动失败）而不是悄悄算错。
     */
    @Inject(method = "mergeOtherNet", at = @At("HEAD"), require = 1)
    private void beyondemc$clearMaterializedBeforeMerge(DimensionsNet otherNet, CallbackInfo ci) {
        ItemMaterializer.clear((DimensionsNet) (Object) this);
        ItemMaterializer.clear(otherNet);
    }

    /**
     * 合并完成后按合并后的 EMC 重建目标网络的物化条目。
     *
     * <p>用 RETURN 而不是 HEAD：HEAD 时目标网络的 EMC 还没把源网络的余额并进来。
     * 这里用 {@code require = 0} —— 即便这条静默失效，下一次打开界面
     * （{@code BeyondEmc.onContainerOpen} 会 refresh）也会把条目修好，属功能退化而非数据损坏。
     */
    @Inject(method = "mergeOtherNet", at = @At("RETURN"), require = 0)
    private void beyondemc$rebuildMaterializedAfterMerge(DimensionsNet otherNet, CallbackInfo ci) {
        ItemMaterializer.refresh((DimensionsNet) (Object) this);
    }
}
