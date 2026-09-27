package com.zhuyuhang.beyondemc.mixin;

import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import com.zhuyuhang.beyondemc.client.VirtualEntryProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在网络界面的列表重建时注入虚拟条目。
 *
 * <p>两个注入点是配套的，缺一不可：
 *
 * <ol>
 *   <li><b>{@code buildIndexList} 的 HEAD</b> —— 每次重建列表前先注入，然后让 BD 的原逻辑
 *       把我们的条目一起排序/分页。因为我们同时置空了排序缓存，原逻辑会重新计算
 *       （`ClientNetStorage.java:161-165`）。</li>
 *   <li><b>{@code updateViewerStorage} 的 RETURN</b> —— 这条路径是"只更新数量"模式
 *       （按住 Shift 打开界面时 `DimensionsNetMenu.java:99` 传 true）。
 *       它会遍历视图里已有的 key 并从真实存储覆写数量
 *       （`ClientNetStorage.java:134-142`），把我们的虚拟条目刷成 0。
 *       在这一步之后重新注入即可覆盖。</li>
 * </ol>
 *
 * <p>两个方法都是 {@code public}，且 {@code clientNetStorage} 在
 * {@code DimensionsNetMenu} 里本来就是 public 字段（`DimensionsNetMenu.java:42`），
 * 所以不需要额外的 Accessor。
 *
 * <p>服务端也会加载 {@code DimensionsNetMenu}，但那时 {@code clientNetStorage == null}
 * —— {@link VirtualEntryProvider#inject} 首行就返回，且 {@code buildIndexList} 本身
 * 也有 {@code isClientSide()} 早退（`DimensionsNetMenu.java:243-246`）。
 *
 * <p>用 {@code require = 0}（优雅降级，风险 R8）：注入失败只表现为"看不到虚拟条目"，
 * 不影响 BD 原有功能。客户端是否真的注入成功由日志与实机确认。
 */
@Mixin(value = DimensionsNetMenu.class, remap = false)
public abstract class DimensionsNetMenuMixin {

    @Inject(method = "buildIndexList", at = @At("HEAD"), require = 0)
    private void beyondemc$injectBeforeRebuild(CallbackInfo ci) {
        DimensionsNetMenu self = (DimensionsNetMenu) (Object) this;
        VirtualEntryProvider.inject(self.clientNetStorage);
    }

    @Inject(method = "updateViewerStorage", at = @At("RETURN"), require = 0)
    private void beyondemc$reinjectAfterAmountUpdate(boolean onlyAmountUpdate, CallbackInfo ci) {
        DimensionsNetMenu self = (DimensionsNetMenu) (Object) this;
        VirtualEntryProvider.inject(self.clientNetStorage);
    }
}
