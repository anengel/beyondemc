package com.zhuyuhang.beyondemc.core;

import com.zhuyuhang.beyondemc.BeyondEmc;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.event.EMCRemapEvent;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.world.item.Items;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * EMC 表可用性探针 + 价格缓存失效钩子。
 *
 * <p><b>为什么需要它</b>：ProjectE 的 EMC 值并不是"模组加载好就有"的。
 * {@code EMCMappingHandler.loadMappers()} 在 ProjectE 的 {@code FMLCommonSetupEvent} 里只登记了
 * mapper 列表；真正算出物品→EMC 映射的是 {@code EMCMappingHandler.map(...)}，
 * 而它由 {@code PECore#dataPackSync(OnDatapackSyncEvent)} 触发
 * （`reference/ProjectE/.../PECore.java:269`）。
 *
 * <p><b>阶段 1 实测结论</b>：专用服务器上无玩家时，{@code ServerStartedEvent} 时刻钻石 EMC 仍是 0；
 * 空跑 60 秒 {@code EMCRemapEvent} 从未触发。EMC 表要等<b>有玩家登录或 {@code /reload}</b> 才构建。
 * 因此"服务器已启动"<b>不能</b>作为"EMC 值可用"的判据 ——
 * 阶段 3 的折算守卫以本类的 {@link #isReady()} 为准。
 *
 * <p>本类同时是阶段 6 的"价格缓存失效"钩子：任何缓存的"物品→价格"表都必须在
 * {@code EMCRemapEvent} 时清空（架构文档风险 R13）。
 */
public final class EmcAvailability {

    private static final AtomicInteger REMAP_COUNT = new AtomicInteger();

    private static volatile boolean ready = false;

    private EmcAvailability() {
    }

    /** 由 {@code BeyondEmc} 注册到 game bus 的 {@code EMCRemapEvent} 上。 */
    public static void onRemap(EMCRemapEvent event) {
        int n = REMAP_COUNT.incrementAndGet();
        ready = true;
        BeyondEmc.LOGGER.info("[BeyondEMC] 收到 EMCRemapEvent（第 {} 次），EMC 表已就绪：钻石 购买价={}，回收价={}",
                n,
                IEMCProxy.INSTANCE.getValue(ItemInfo.fromItem(Items.DIAMOND)),
                IEMCProxy.INSTANCE.getSellValue(ItemInfo.fromItem(Items.DIAMOND)));
    }

    /** EMC 表是否已经至少完成过一次映射计算。 */
    public static boolean isReady() {
        return ready;
    }

    public static int remapCount() {
        return REMAP_COUNT.get();
    }
}
