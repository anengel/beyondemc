package com.zhuyuhang.beyondemc.client;

import com.zhuyuhang.beyondemc.exchange.KnowledgeSyncPacket;
import moze_intel.projecte.api.ItemInfo;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端持有的"当前打开的网络"的已学习物品集合。
 *
 * <p>由 S2C 的 {@code KnowledgeSyncPacket} 在**打开网络界面时**填充
 * （见 {@code BeyondEmc#onContainerOpen}）。
 *
 * <p><b>为什么不在客户端算</b>：学习集合是服务端权威数据（`NetKnowledgeStore`），
 * 客户端既不能读存档也不该自己判断。单价也一样 —— 由服务端随包下发，
 * 见 {@code KnowledgeSyncPacket} 的类注释。
 *
 * <p>刻意做成**纯数据类**（不引用任何 Minecraft 客户端类）：
 * 网络包的注册与处理要在双端都能安全加载这个类。
 */
public final class ClientKnowledgeCache {

    /**
     * 一次同步的内容：网络 id + 该网络已学会的物品及其**服务端算好的购买单价**
     * + 服务端是否已开启物化。
     *
     * <p>用 {@link LinkedHashMap} 保持稳定顺序，让界面里虚拟条目的相对顺序可复现。
     *
     * @param serverMaterialized 服务端已开启物化（{@code materializeItems=true} 且模式为 STORAGE）。
     *                           为 true 时客户端<b>不得</b>再注入虚拟条目 —— 那些物品已经是服务端
     *                           真实的 {@code EmcItemKey} 条目，再注入会出两行。
     */
    public record Entry(int netId, Map<ItemInfo, Long> learned, boolean serverMaterialized) {
    }

    private static volatile @Nullable Entry current = null;

    /**
     * "有新数据到达"的标记。
     *
     * <p><b>这个标记是必需的</b>：知识同步包到达时，界面的列表往往**已经构建过了**
     * （BD 的存储基线同步包先到，触发了一轮 {@code buildIndexList}），
     * 而此时缓存还是空的。若不主动触发一次重建，虚拟条目就要等到下一次存储变化才出现
     * —— 实测表现就是"重新打开界面看不到，再存入一个物品才显示"。
     * 由 {@code ClientKnowledgeRefresh} 在客户端 tick 里消费这个标记。
     */
    private static volatile boolean dirty = false;

    private ClientKnowledgeCache() {
    }

    public static void accept(int netId, List<KnowledgeSyncPacket.LearnedEntry> learned,
                             boolean serverMaterialized) {
        Map<ItemInfo, Long> map = new LinkedHashMap<>();
        for (KnowledgeSyncPacket.LearnedEntry e : learned) {
            map.put(e.info(), e.unitPrice());
        }
        current = new Entry(netId, Collections.unmodifiableMap(map), serverMaterialized);
        dirty = true;
    }

    /**
     * 增量加入一条新学会的物品（由 {@code KnowledgeLearnedPacket} 调用）。
     *
     * <p>若这条通知不属于当前打开的网络（例如刚关了界面、或同时开了另一个网络），就忽略它
     * —— 不能把别的网络的学习集合混进当前缓存。
     */
    public static void add(int netId, KnowledgeSyncPacket.LearnedEntry entry) {
        Entry e = current;
        if (e == null || e.netId() != netId) {
            return;
        }
        Map<ItemInfo, Long> map = new LinkedHashMap<>(e.learned());
        map.put(entry.info(), entry.unitPrice());
        // serverMaterialized 沿用当前值：它只由全量同步包设置
        current = new Entry(netId, Collections.unmodifiableMap(map), e.serverMaterialized());
        dirty = true; // 交给 ClientKnowledgeRefresh 触发一次界面重建，新条目立刻出现
    }

    public static @Nullable Entry get() {
        return current;
    }

    /** 关闭界面时清掉，避免把上一个网络的数据用到下一个网络上。 */
    public static void clear() {
        current = null;
        dirty = false;
    }

    public static Map<ItemInfo, Long> learnedOrEmpty() {
        Entry e = current;
        return e == null ? Collections.emptyMap() : e.learned();
    }

    /** 消费脏标记：返回 true 表示"确实有新数据需要重建一次界面"。 */
    public static boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }
}
