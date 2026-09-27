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
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.TooltipFlag.Default;
import net.neoforged.neoforge.client.ClientTooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * EMC 资源在界面里的渲染器。照抄 {@code ManaStackKeyRender} 的结构。
 *
 * <p><b>⚠️ 本类只能在客户端被加载 —— 绝不要在服务端调用 {@code EmcStackKey.getRender()}。</b>
 * 本类的实现引用了 {@code Minecraft} / {@code ClientLevel} 等客户端专有类，
 * 在专用服务器上会被 NeoForge 的运行时 dist 清理器直接抛异常：
 * <pre>
 * java.lang.RuntimeException: Attempted to load class
 *     net/minecraft/client/multiplayer/ClientLevel for invalid dist DEDICATED_SERVER
 * </pre>
 * 这在 BD 的设计下是安全的，因为 <b>BD 从不在服务端调用 {@code getRender()}</b>。
 * 已核对的全部调用点：
 * <ul>
 *   <li>{@code client/gui/BDBaseGUI.java:56,86,87}</li>
 *   <li>{@code common/menu/widget/ClientNetStorage.java:200}（该类仅在 {@code isClientSide} 时创建）</li>
 *   <li>{@code common/menu/widget/ClientNetStorageSearchHelper.java:303}</li>
 *   <li>{@code util/TooltipHelper.java:110} —— 唯一入口是 {@code DimensionsNetMenu.afterLoadChange()}，
 *       而 {@code SlotGroupSync} 的契约明确把 {@code loadChange/afterLoadChange} 标注为"仅客户端"
 *       （{@code DisorderedSlotGroupSync.java:314,346}），唯一调用方是 s2c 包处理器
 *       {@code DisorderedSlotGroupSyncPacket.java:61-62}</li>
 * </ul>
 * 该约束由 {@code EmcStorageSelfTest} 的第 8 项做哨兵监控。
 *
 * <p>另外，{@code TooltipHelper.getTooltipLines} 用 {@code catch(Throwable)} 包住了
 * {@code getRender()}（`TooltipHelper.java:108-120`），所以即使万一被误调也只会刷一条 ERROR 日志，
 * 不会崩服 —— 但那是"兜底"，不是"允许"。
 *
 * <p>图标是<b>临时占位</b>：直接画一个双色方块，不引入任何贴图资源，零风险。
 * 阶段 6 再换成正式的 16×16 贴图。
 */
public class EmcStackKeyRender implements IStackRender {

    public static final EmcStackKeyRender INSTANCE = new EmcStackKeyRender();

    /** 深青底 / 亮青内框。 */
    private static final int COLOR_BASE = 0xFF0B3A3A;
    private static final int COLOR_INNER = 0xFF1FA8A8;

    private EmcStackKeyRender() {
    }

    @Override
    public void render(GuiGraphics gui, IStackKey<?> key, int x, int y) {
        gui.fill(x, y, x + 16, y + 16, COLOR_BASE);
        gui.fill(x + 2, y + 2, x + 14, y + 14, COLOR_INNER);
    }

    @Override
    public void renderAmount(GuiGraphics gui, long amount, int x, int y) {
        String text = getCountText(amount);
        if (text.isEmpty()) {
            return;
        }

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

    @Override
    public Component getDisplayName(IStackKey<?> key) {
        // 双端安全：只用可翻译组件，不碰客户端单例
        return Component.translatable("types.beyondemc.emc_type.name");
    }

    @Override
    public List<Component> getTooltipLines(IStackKey<?> key, long amount, Item.TooltipContext tooltipContext,
                                          @Nullable Player player, TooltipFlag tooltipFlag) {
        // 双端安全（服务端也会走这条路径）
        return List.of(
                getDisplayName(key),
                Component.translatable("types.beyondemc.emc_amount", amount),
                Component.translatable("types.beyondemc.emc_hint").withStyle(ChatFormatting.DARK_GRAY)
        );
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(IStackKey<?> key) {
        return Optional.empty();
    }

    @Override
    public void renderTooltip(GuiGraphics gui, Font font, IStackKey<?> key, long amount, int mouseX, int mouseY) {
        var mc = Minecraft.getInstance();
        var ctx = mc.level != null ? Item.TooltipContext.of(mc.level) : Item.TooltipContext.EMPTY;
        gui.renderTooltip(
                mc.font,
                getTooltipLines(key, amount, ctx, mc.player,
                        ClientTooltipFlag.of(mc.options.advancedItemTooltips ? Default.ADVANCED : Default.NORMAL)),
                getTooltipImage(key),
                net.minecraft.world.item.ItemStack.EMPTY,
                mouseX, mouseY
        );
    }
}
