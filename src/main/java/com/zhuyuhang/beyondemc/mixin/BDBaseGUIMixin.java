package com.zhuyuhang.beyondemc.mixin;

import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.wintercogs.beyonddimensions.client.gui.BDBaseGUI;
import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import com.wintercogs.beyonddimensions.common.menu.widget.ClientNetStorage;
import com.wintercogs.beyonddimensions.common.menu.widget.slot.AbstractStackTypedSlot;
import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.client.ClientKnowledgeCache;
import com.zhuyuhang.beyondemc.client.VirtualEntryProvider;
import com.zhuyuhang.beyondemc.exchange.ExchangeRequestPacket;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截对"虚拟条目"的点击，改发兑换请求。
 *
 * <p><b>为什么必须拦截</b>：虚拟条目只存在于客户端视图里，服务端根本不知道它。
 * BD 原本的点击链路会发 {@code CallSeverClickPacket}，服务端拿真实的 {@code UnifiedStorage}
 * 去做 {@code extractByKey}，因为余额里没有这个 key 而<b>静默失败</b>
 * （`AbstractUnorderedStackHandler.java:640-644`）。
 *
 * <p>识别方式：{@code getTypedStackFromUnifiedStorage()} 拿到被点条目的 KeyAmount，
 * 再问 {@link VirtualEntryProvider#isVirtual} —— 后者是**无状态判据**：
 * "只存在于视图、而 {@code sourceStorage} 里没有" 即虚拟条目。
 * 刻意不再用"自己维护的虚拟集合"，那会失同步（详见 VirtualEntryProvider 的类注释）。
 *
 * <p>数量规则：Shift+点击 = 界面上显示的可兑换数量（尽可能多）；其余情况 = 1。
 * 客户端给的数量只是**建议**，服务端会重新校验并按余额/背包空间裁剪。
 *
 * <p>本类在 mixins.json 里放在 {@code client} 段 —— 目标是客户端专有类。
 */
@Mixin(value = BDBaseGUI.class, remap = false)
public abstract class BDBaseGUIMixin {

    private static long clickCount = 0;

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void beyondemc$interceptVirtualEntryClick(Slot slot, int slotIndex, int mouseButton,
                                                      ClickType type, CallbackInfo ci) {
        if (!(slot instanceof AbstractStackTypedSlot typedSlot)) {
            return;
        }

        // 从当前打开的菜单拿客户端视图。
        // 注意必须经由 Object 中转：Mixin 类自身与 AbstractContainerScreen 没有继承关系，
        // 直接写 `this instanceof AbstractContainerScreen<?>` 编译器会报"不可转换的类型"。
        Object self = this;
        if (!(self instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!(screen.getMenu() instanceof DimensionsNetMenu menu)) {
            return;
        }
        ClientNetStorage view = menu.clientNetStorage;
        if (view == null) {
            return;
        }

        // ⚠️ 这里刻意用 getTypedStackFromUnifiedStorage() 而不是 getVanillaActualStack()：
        // 后者会把数量【钳到原版堆叠上限】（AbstractStackTypedSlot.java:159-174 里的
        // Math.min(amount, key.getVanillaMaxStackSize())，ItemStackKey 就是 64），
        // 那样 Shift+点击"尽可能多"最多只能换 64 个。
        // 前者（AbstractStackTypedSlot.java:136）不做钳制，拿到的才是界面上显示的真实可兑换数量。
        KeyAmount clicked = typedSlot.getTypedStackFromUnifiedStorage();
        if (clicked == null || clicked.isEmpty()) {
            return;
        }
        if (!VirtualEntryProvider.isVirtual(view, clicked.key())) {
            return; // 真实库存行（或非物品条目）→ 交给 BD 原生逻辑
        }
        if (!(clicked.key() instanceof ItemStackKey itemKey)) {
            return;
        }

        ClientKnowledgeCache.Entry entry = ClientKnowledgeCache.get();
        if (entry == null) {
            // 还没收到同步包（理论上不会：打开界面时就发了）—— 宁可不动作，也不要发一个假 netId
            BeyondEmc.LOGGER.warn("[BeyondEMC] 点击了虚拟条目但客户端没有网络同步数据，已忽略");
            ci.cancel();
            return;
        }

        int count = 1;
        if (Screen.hasShiftDown()) {
            // Shift+左键 = 取出一"组"（该物品的原版最大堆叠数，绝大多数物品就是 64），
            // 但不超过界面上显示的可兑换数量。
            // 早期版本用的是 clicked.amount()（可兑换总量，可能是几百个），
            // 实测反馈要的是"一组"，所以改成按最大堆叠数取。
            long group = Math.max(1L, itemKey.getVanillaMaxStackSize());
            count = (int) Math.min(Math.min(group, clicked.amount()), Integer.MAX_VALUE);
            count = Math.max(1, count);
        }

        // getReadOnlyStack() 按 IStackKey 契约返回数量恒为 1 的堆叠，数量由 count 单独传
        ItemStack template = itemKey.getReadOnlyStack();
        PacketDistributor.sendToServer(new ExchangeRequestPacket(entry.netId(), template, count));

        if (++clickCount <= 8) {
            BeyondEmc.LOGGER.info("[BeyondEMC] 拦截虚拟条目点击：{} ×{}（网络 {}）",
                    template.getItem(), count, entry.netId());
        }

        ci.cancel();
    }
}
