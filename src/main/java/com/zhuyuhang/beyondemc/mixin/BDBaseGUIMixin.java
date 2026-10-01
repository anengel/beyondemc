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
import com.zhuyuhang.beyondemc.emc.EmcItemKey;
import com.zhuyuhang.beyondemc.exchange.ExchangeIntent;
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
 * 拦截对"物化条目 / 虚拟条目"的点击，改发兑换请求。
 *
 * <h2>为什么必须拦截</h2>
 * 这两类条目都<b>不是</b>普通库存：物化条目（{@code EmcItemKey}）虽有真实存储条目，但它的语义是
 * "用 EMC 换"，必须走 {@code ExchangeService} 才能保证"先扣费再交付"（INV-2）；
 * 虚拟条目（0.2 的兜底）则只存在于客户端视图里，服务端根本不知道它。
 * 若走 BD 原本的点击链路，服务端会拿真实的 {@code UnifiedStorage} 去做 {@code extractByKey}，
 * 对虚拟条目是<b>静默失败</b>（`AbstractUnorderedStackHandler.java:640-644`），
 * 对物化条目则会绕过"吸附到鼠标"的语义。
 *
 * <h2>判据的演进</h2>
 * 0.2：{@code getTypedStackFromUnifiedStorage()} 拿到 KeyAmount 后问
 * {@link VirtualEntryProvider#isVirtual}（= "只存在于视图、sourceStorage 里没有"）——
 * 那是用本地状态<b>推断</b>服务端事实。
 *
 * <p>0.3：物化条目由服务端真实持有，判据变成 <b>{@code key instanceof EmcItemKey}</b> ——
 * 直接、无状态、不可能失同步。{@code isVirtual} 降级为 {@code materializeItems=false} 时的兜底。
 *
 * <p>数量规则：Shift+点击 = 界面上显示的可兑换数量（尽可能多）；右键 = 一半；其余 = 一组。
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

        // ⚠️ 判据（0.3.0 起）：**key instanceof EmcItemKey** —— 物化条目已经是服务端真实持有的
        // 存储条目，所以判据是"键的类型"这样一个直接、无状态的事实，不再是 0.2 那种
        // "sourceStorage 里没有它"的客户端推断（那条推断依赖本地状态，曾两次失同步）。
        //
        // 保留 ItemStackKey + isVirtual 分支作为 materializeItems=false 时的兜底
        //（那时没有 EmcItemKey，客户端仍会注入虚拟条目）。
        ItemStack template;
        long group;
        if (clicked.key() instanceof EmcItemKey materializedKey) {
            // 发出去的必须是"数量为 1 的模板物品"；用新构造的栈，不要共享渲染缓存实例
            template = materializedKey.info().createStack();
            group = Math.max(1L, materializedKey.getVanillaMaxStackSize());
        } else if (clicked.key() instanceof ItemStackKey stockKey
                && VirtualEntryProvider.isVirtual(view, clicked.key())) {
            template = stockKey.getReadOnlyStack();
            group = Math.max(1L, stockKey.getVanillaMaxStackSize());
        } else {
            return; // 真实库存行（或非物品条目）→ 交给 BD 原生逻辑
        }
        if (template.isEmpty()) {
            return;
        }

        ClientKnowledgeCache.Entry entry = ClientKnowledgeCache.get();
        if (entry == null) {
            // 还没收到同步包（理论上不会：打开界面时就发了）—— 宁可不动作，也不要发一个假 netId
            BeyondEmc.LOGGER.warn("[BeyondEMC] 点击了物化/虚拟条目但客户端没有网络同步数据，已忽略");
            ci.cancel();
            return;
        }

        // 界面上显示的可兑换数量（买不起的物品根本不会被物化/注入，所以这里 >= 1）
        long affordable = Math.max(1L, clicked.amount());
        long whole = Math.min(group, affordable);

        // 0.2.0 的点击口径（见 docs/plan/ROADMAP-0.2.0.md §1.4）：
        //   左键      → 一组【吸附到鼠标】（像原版拾取）
        //   右键      → 一半吸附到鼠标
        //   Shift+左键 → 一组【直接进背包】（0.1.0 的旧行为，保留为快捷方式）
        // 客户端给的只是建议数量，服务端会用 ExchangeService.cursorCapacity 重新裁剪。
        ExchangeIntent intent;
        long wanted;
        if (type == ClickType.QUICK_MOVE) {
            intent = ExchangeIntent.QUICK_MOVE_TO_INVENTORY;
            wanted = whole;
        } else if (mouseButton == 1) {
            intent = ExchangeIntent.PICKUP_TO_CURSOR;
            wanted = Math.max(1L, (whole + 1L) / 2L); // 原版右键取一半（向上取整）
        } else {
            intent = ExchangeIntent.PICKUP_TO_CURSOR;
            wanted = whole;
        }
        int count = (int) Math.min(Math.max(1L, wanted), Integer.MAX_VALUE);

        // getReadOnlyStack()/createStack() 的数量恒为 1，数量由 count 单独传
        PacketDistributor.sendToServer(new ExchangeRequestPacket(entry.netId(), template, count, intent));

        if (++clickCount <= 8) {
            BeyondEmc.LOGGER.info("[BeyondEMC] 拦截{}点击：{} ×{}，意图={}（网络 {}）",
                    clicked.key() instanceof EmcItemKey ? "物化条目" : "虚拟条目",
                    template.getItem(), count, intent, entry.netId());
        }

        ci.cancel();
    }
}
