package com.zhuyuhang.beyondemc.exchange;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 把"刚学会的新物品"增量推送给正在查看该网络的玩家。
 *
 * <p>由 {@code EmcDepositHandler} 在 {@code NetKnowledgeStore.learn(...)} 返回 true 时调用
 * —— 也就是**确实新学会了一个物品**，而不是重复存入。
 *
 * <p>只发给"此刻正开着该网络界面"的玩家：判断方式是看他的 {@code containerMenu}
 * 是不是 {@code DimensionsNetMenu}，且其 storage 反查回来的网络 id 一致
 * （{@code UnifiedStorage.getNet()}，`UnifiedStorage.java:69-73`）。
 * 这样既精确又省 —— 没有开界面的玩家不需要这个通知（他们打开界面时会收到全量）。
 */
public final class KnowledgeSyncNotifier {

    private KnowledgeSyncNotifier() {
    }

    /**
     * @param net  刚刚学会新物品的网络
     * @param info 归一化过的物品身份
     */
    public static void notifyLearned(@Nullable DimensionsNet net, @NotNull ItemInfo info) {
        if (net == null) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        // 客户端要显示"能换出多少"，所以顺带把服务端算好的购买单价发过去
        long unitPrice;
        try {
            unitPrice = IEMCProxy.INSTANCE.getValue(info);
        } catch (Throwable t) {
            unitPrice = 0L;
        }
        KnowledgeSyncPacket.LearnedEntry entry = new KnowledgeSyncPacket.LearnedEntry(info, unitPrice);

        int netId = net.getId();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isViewingNet(player, netId)) {
                PacketDistributor.sendToPlayer(player, new KnowledgeLearnedPacket(netId, entry));
            }
        }
    }

    /** 该玩家此刻是否正开着指定网络 id 的界面。 */
    private static boolean isViewingNet(@NotNull ServerPlayer player, int netId) {
        if (!(player.containerMenu instanceof DimensionsNetMenu menu)) {
            return false;
        }
        if (!(menu.storage instanceof UnifiedStorage storage)) {
            return false;
        }
        DimensionsNet viewing = storage.getNet();
        return viewing != null && viewing.getId() == netId;
    }
}
