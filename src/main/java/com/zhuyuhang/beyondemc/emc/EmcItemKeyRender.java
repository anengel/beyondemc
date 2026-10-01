package com.zhuyuhang.beyondemc.emc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.IStackRender;
import com.wintercogs.beyonddimensions.util.StringFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.TooltipFlag.Default;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.ClientTooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 物化物品条目在界面里的渲染器：画真实物品图标 + 数量，并给出物品自身的工具提示。
 *
 * <h2>⚠️ 身份只能从传入的 {@code key} 取</h2>
 * <b>绝不能</b>读 {@code key.getRenderStack()} —— {@code LongStackKey.getRenderStack()} 返回的是
 * <b>原型实例自己的堆叠</b>（{@code LongStackKey.java:26-38}）：对 {@link EmcItemKey} 而言那是个
 * {@link EmcItemType}，而且对 {@link EmcItemKey#INSTANCE} 而言它代表的是<b>空气</b>。
 * 所有渲染/提示都必须经 {@link #stackOf(IStackKey)} 从当前 key 的 {@code info()} 现场构造。
 *
 * <h2>⚠️ 本类只能在客户端被加载</h2>
 * 实现引用了 {@code Minecraft} 等客户端专有类，在专用服务器上会被 NeoForge 的运行时
 * dist 清理器直接抛异常。这在 BD 的设计下是安全的：<b>BD 从不在服务端调用 {@code getRender()}</b>
 * （全部调用点已由 {@code EmcStackKeyRender} 的类注释逐一列出并核对）。
 * 该约束由 {@code EmcStorageSelfTest} 的第 8 项做哨兵监控 —— 那条断言同样覆盖本渲染器。
 *
 * <p>数量格式化复用 BD 的 {@code StringFormat.formatCount}，与 BD 原生条目（64 → "64"、
 * 10000 → "1万"）保持一致的观感。
 */
public class EmcItemKeyRender implements IStackRender {

    public static final EmcItemKeyRender INSTANCE = new EmcItemKeyRender();

    private EmcItemKeyRender() {
    }

    /** 从 key 现场取物品栈（数量恒为 1）。非本类型一律返回空栈。 */
    private static ItemStack stackOf(IStackKey<?> key) {
        if (key instanceof EmcItemKey itemKey) {
            try {
                return itemKey.createRenderStack();
            } catch (Throwable t) {
                return ItemStack.EMPTY;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void render(GuiGraphics gui, IStackKey<?> key, int x, int y) {
        ItemStack stack = stackOf(key);
        if (stack.isEmpty()) {
            return;
        }
        var pose = gui.pose();
        pose.pushPose();
        gui.renderFakeItem(stack, x, y);
        gui.renderItemDecorations(Minecraft.getInstance().font, stack, x, y, "");
        pose.popPose();
    }

    @Override
    public void renderAmount(GuiGraphics gui, long amount, int x, int y) {
        String text = getCountText(amount);
        if (text.isEmpty()) {
            return;
        }

        // 与 EmcStackKeyRender / ItemStackKeyRender 完全一致的缩放与定位
        float scale = 0.666f;
        var pose = gui.pose();
        pose.pushPose();
        pose.translate(0, 0, 200);
        pose.scale(scale, scale, scale);
        RenderSystem.disableBlend();

        int w = Minecraft.getInstance().font.width(text);
        final int textX = (int) ((x - 1 + 16.0f + 2.0f - w * 0.666f) / 0.666f);
        final int textY = (int) ((y - 1 + 16.0f - 5.0f * 0.666f) / 0.666f);
        gui.drawString(Minecraft.getInstance().font, text, textX, textY, 0xFFFFFF);

        pose.popPose();
    }

    @Override
    public String getCountText(long count) {
        if (count < 0) {
            return "";
        }
        return StringFormat.formatCount(count);
    }

    /**
     * 必须返回<b>物品名</b>：BD 的 {@code SORT_NAME} 排序与"按名称搜索"都用它
     * （{@code ClientNetStorage.java:190-300}、{@code ClientNetStorageSearchHelper.java:299-325}）。
     */
    @Override
    public Component getDisplayName(IStackKey<?> key) {
        ItemStack stack = stackOf(key);
        if (stack.isEmpty()) {
            return Component.empty();
        }
        try {
            return stack.getHoverName();
        } catch (Throwable t) {
            return Component.empty();
        }
    }

    @Override
    public List<Component> getTooltipLines(IStackKey<?> key, long amount, Item.TooltipContext tooltipContext,
                                          @Nullable Player player, TooltipFlag tooltipFlag) {
        ItemStack stack = stackOf(key);
        if (stack.isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>(stack.getTooltipLines(tooltipContext, player, tooltipFlag));
        lines.add(Component.translatable("types.beyondemc.emc_item_amount", amount));
        lines.add(Component.translatable("types.beyondemc.emc_item_hint").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(IStackKey<?> key) {
        ItemStack stack = stackOf(key);
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        try {
            return stack.getTooltipImage();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void renderTooltip(GuiGraphics gui, Font font, IStackKey<?> key, long amount, int mouseX, int mouseY) {
        var mc = Minecraft.getInstance();
        var ctx = mc.level != null ? Item.TooltipContext.of(mc.level) : Item.TooltipContext.EMPTY;
        gui.renderTooltip(
                mc.font,
                getTooltipLines(key, amount, ctx, mc.player,
                        ClientTooltipFlag.of(mc.options.advancedItemTooltips ? Default.ADVANCED : Default.NORMAL)),
                getTooltipImage(key),
                ItemStack.EMPTY,
                mouseX, mouseY
        );
    }
}
