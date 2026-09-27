package com.zhuyuhang.beyondemc.exchange;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.BeyondEmc;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 兑换请求（C2S）。
 *
 * <p><b>包里刻意只带"意图"</b>：哪个网络、哪个物品、要几个。
 * 价格、余额、权限一律由服务端 {@link ExchangeService} 重算 ——
 * 篡改这个包最多只能换到"你本来就有权换、且付得起"的东西。
 *
 * <p>{@code netId} 由客户端提供而不是用 {@code getNetFromPlayer}：
 * 因为玩家可能通过 {@code NetTerminalItem} 打开一个**非主网络**的界面
 * （{@code OpenNetGuiPacket.java:83-125} 的 NET_CRAFT_TERMINAL 分支）。
 * 服务端会用 {@link ExchangeService#canAccess} 校验成员身份，所以指定 netId 本身不构成提权。
 *
 * <p>阶段 5 的界面注入会发这个包；阶段 4 先用 {@code /beyondemc exchange} 命令验证同一套服务端逻辑。
 */
public record ExchangeRequestPacket(int netId, ItemStack template, int count) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ExchangeRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(BeyondEmc.MOD_ID, "exchange_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeRequestPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ExchangeRequestPacket::netId,
                    ItemStack.OPTIONAL_STREAM_CODEC, ExchangeRequestPacket::template,
                    ByteBufCodecs.VAR_INT, ExchangeRequestPacket::count,
                    ExchangeRequestPacket::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ExchangeRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            DimensionsNet net = DimensionsNet.getNetFromId(packet.netId());
            ExchangeService.Result result =
                    ExchangeService.exchange(player, net, packet.template(), packet.count());

            // 失败始终走聊天栏（重要，否则玩家只会看到"点了没反应"）。
            // 成功默认【完全不提示】（实测反馈：不要取出时有字幕弹窗在屏幕中间），
            // 想开提示可以用配置 exchangeFeedback 改成 CHAT / ACTION_BAR。
            if (!result.success()) {
                player.displayClientMessage(Component.literal("[BeyondEMC] " + result.message()), false);
            } else {
                switch (com.zhuyuhang.beyondemc.config.BeyondEmcConfig.exchangeFeedback()) {
                    case NONE -> {
                        // 什么都不做
                    }
                    case CHAT -> player.displayClientMessage(
                            Component.literal("[BeyondEMC] " + result.message()), false);
                    case ACTION_BAR -> player.displayClientMessage(
                            Component.literal("[BeyondEMC] " + result.message()), true);
                }
            }
        });
    }
}
