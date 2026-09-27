package com.zhuyuhang.beyondemc.exchange;

import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.client.ClientKnowledgeCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 增量通知：网络刚学会了**一个新物品**（S2C）。
 *
 * <p><b>为什么需要它</b>：{@link KnowledgeSyncPacket} 只在**打开界面时**发一次全量。
 * 于是"往网络里存入一个从没见过的物品（比如下界合金斧）"之后，
 * 客户端的知识缓存里没有它 —— 虽然 BD 的存储 delta 会触发一次列表重建，
 * 但那次重建用的还是**旧缓存**，所以新物品要等到关闭界面再打开才出现。
 *
 * <p>实测反馈正是如此："每次放入新物品，我需要关闭维度网络再打开才能显示"。
 *
 * <p>只在新学会（{@code NetKnowledgeStore.learn} 返回 true）时发一条，
 * 且只发给**此刻正开着这个网络界面**的玩家，因此开销可以忽略。
 */
public record KnowledgeLearnedPacket(int netId, KnowledgeSyncPacket.LearnedEntry entry)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<KnowledgeLearnedPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(BeyondEmc.MOD_ID, "knowledge_learned"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KnowledgeLearnedPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, KnowledgeLearnedPacket::netId,
                    KnowledgeSyncPacket.LearnedEntry.STREAM_CODEC, KnowledgeLearnedPacket::entry,
                    KnowledgeLearnedPacket::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(KnowledgeLearnedPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientKnowledgeCache.add(packet.netId(), packet.entry()));
    }
}
