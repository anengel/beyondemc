package com.zhuyuhang.beyondemc.exchange;

import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.client.ClientKnowledgeCache;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 已学习物品集合的同步（S2C）。在玩家**打开网络界面**时发送一次。
 *
 * <h2>为什么连单价一起发（而不是让客户端自己查）</h2>
 * 客户端算"能换出多少"需要物品的**购买价**。虽然 ProjectE 也会把 EMC 数据同步到客户端，
 * 但让客户端依赖"自己那份价格表是否与当前 tick 一致"是一件不必要的风险：
 * 一旦客户端价格为空或过期，表现就是"界面里什么都看不到"，而服务端其实一切正常。
 *
 * <p>所以这里由**服务端算好单价**随包下发，客户端只做除法
 * `可兑换数 = floor(网络EMC ÷ 单价)`，既能随 EMC 变化实时更新，又不依赖客户端价格表。
 *
 * <h2>为什么不做增量</h2>
 * 集合只在"存入新物品"时增长，而存入本身就会触发 BD 的存储 delta 同步、进而重建界面。
 * 打开界面这种低频场景下一次全量完全可接受；增量需要维护"自上次同步以来的变更集合"，
 * 属于典型的过早优化。
 *
 * <h2>0.3.0：为什么要带上 {@code serverMaterialized}</h2>
 * 服务端开着物化时，"已学习但无库存"的物品已经是<b>服务端真实的存储条目</b>
 * （{@code EmcItemKey}），客户端不该再注入一遍虚拟条目 —— 否则同一个物品会出现<b>两行</b>
 * （{@code EmcItemKey} 行 + 客户端注入的 {@code ItemStackKey} 行）。
 *
 * <p>服务端配置没法直接读到客户端（远程客户端上 SERVER 配置不会加载），
 * 所以由服务端随包把这个事实下发给客户端。
 */
public record KnowledgeSyncPacket(int netId, List<LearnedEntry> learned, boolean serverMaterialized)
        implements CustomPacketPayload {

    /** 一条已学习物品：身份 + 服务端算好的购买单价。 */
    public record LearnedEntry(ItemInfo info, long unitPrice) {

        public static final StreamCodec<RegistryFriendlyByteBuf, LearnedEntry> STREAM_CODEC =
                StreamCodec.composite(
                        ItemInfo.STREAM_CODEC, LearnedEntry::info,
                        ByteBufCodecs.VAR_LONG, LearnedEntry::unitPrice,
                        LearnedEntry::new);
    }

    public static final CustomPacketPayload.Type<KnowledgeSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(BeyondEmc.MOD_ID, "knowledge_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KnowledgeSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, KnowledgeSyncPacket::netId,
                    LearnedEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), KnowledgeSyncPacket::learned,
                    ByteBufCodecs.BOOL, KnowledgeSyncPacket::serverMaterialized,
                    KnowledgeSyncPacket::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：只写进纯数据缓存并置脏标记，不碰任何客户端类，双端加载安全。 */
    public static void handle(KnowledgeSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientKnowledgeCache.accept(
                packet.netId(), packet.learned(), packet.serverMaterialized()));
    }
}
