package com.zhuyuhang.beyondemc.client;

import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import com.zhuyuhang.beyondemc.BeyondEmc;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 知识同步包到达后，主动触发一次界面重建（**仅客户端**）。
 *
 * <p><b>为什么必须有这个类</b>：打开网络界面时，服务端发出的包有两个来源，顺序是
 * ① BD 的存储基线同步包 → ② 我们的知识同步包。客户端处理 ① 时会走
 * {@code afterLoadChange → updateViewerStorage → buildIndexList}，
 * <b>此时知识缓存还是空的</b>，于是不注入任何虚拟条目；等 ② 到达把缓存填上时，
 * 列表已经建完了，没有任何东西会再重建它。
 *
 * <p>实测症状正是如此：**重新打开界面看不到已学习的物品，再存入一个物品（触发存储 delta）才出现**。
 *
 * <p>实现方式是在客户端 tick 里消费 {@link ClientKnowledgeCache#consumeDirty()} 的脏标记，
 * 而不是在包处理里直接调客户端代码 —— 这样 {@code KnowledgeSyncPacket.handle}
 * 可以保持"纯数据、双端加载安全"，不需要引用任何客户端类。
 *
 * <p>{@code @EventBusSubscriber(value = Dist.CLIENT)} 让 NeoForge 只在客户端注册它
 * （NeoForge 用 ASM 扫描注解，不会在服务端加载本类），因此这里引用 {@code Minecraft}
 * 是安全的。
 */
@EventBusSubscriber(modid = BeyondEmc.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientKnowledgeRefresh {

    private static long refreshCount = 0;

    private ClientKnowledgeRefresh() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ClientKnowledgeCache.consumeDirty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (mc.player.containerMenu instanceof DimensionsNetMenu menu) {
            // false = 完整更新（不只改数量），会把视图从真实存储重建一遍再重建索引，
            // 而我们的注入挂在 buildIndexList 的 HEAD 上，因此这一次就能带上虚拟条目。
            menu.updateViewerStorage(false);
            if (++refreshCount <= 5) {
                BeyondEmc.LOGGER.info("[BeyondEMC] 知识同步到达，已触发界面重建（第 {} 次）", refreshCount);
            }
        }
    }
}
