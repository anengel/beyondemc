package com.zhuyuhang.beyondemc.exchange;

import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.client.ClientKnowledgeCache;
import com.zhuyuhang.beyondemc.emc.EmcStackKey;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「网络已学会但无库存」的物品注入 BD 的 JEI 可用池。
 *
 * <h2>为什么需要它</h2>
 * BD 的 JEI 配方转移（{@code TransferHelper.transferRecipe}，`TransferHelper.java:29`）
 * 会先把「合成栏 + 网络存储 + 玩家背包」汇成一个可用池，再逐槽位找材料。
 * 找不到材料的槽位会让它返回 {@code MissStackError}（`TransferHelper.java:110-113`），
 * 于是 **JEI 直接禁止转移**，连包都不会发。
 *
 * <p>但服务端其实**已经能产出**那些材料 —— `DimensionsCraftMenu.transferRecipe` 的
 * `extractFromStorage`（`DimensionsCraftMenu.java:337-339`）调用的是
 * {@code storage.extract(type, amount, false, false)}，也就是我们 0.1.0 就装好的抽取钩子
 * （→ {@code InterfaceWithdrawService} 铸造 + 扣 EMC）。
 *
 * <p>所以要做的只是**让客户端知道"这些材料其实买得起"**，JEI 就会正常发包。
 *
 * <h2>为什么单独放一个类（不写进 Mixin）</h2>
 * JEI 的类在**没装 JEI 时不存在**，所以 Mixin 里引用 JEI 类型的那部分代码无法在无头环境验证。
 * 把纯逻辑抽到本类（**完全不引用 JEI**）之后：
 * <ul>
 *   <li>它能在无头服务器上被 {@code JeiFillSelfTest} 决定性验证；</li>
 *   <li>Mixin 只剩一层薄转发，出错面积极小。</li>
 * </ul>
 *
 * <h2>数据来源（两处复用，不新增同步）</h2>
 * <ul>
 *   <li><b>已学习物品 + 单价</b>：{@link ClientKnowledgeCache} —— 0.1.0 起
 *       {@code KnowledgeSyncPacket} 携带的**服务端算好的单价**。
 *       刻意不用客户端自己的 EMC 价格表（0.1.0 已踩过那个坑：客户端价格表可能为空/过期）。</li>
 *   <li><b>网络 EMC 余额</b>：BD 已把 EMC 作为一条普通资源同步进这个列表，
 *       直接从入参里读，不额外发包。</li>
 * </ul>
 *
 * <p>可兑换数量 = {@code floor(网络EMC ÷ 购买价)}，与
 * {@code InterfaceWithdrawService}、{@code VirtualEntryProvider} 用的是**同一个公式、同一份单价**。
 */
public final class EmcAvailabilityInjector {

    private EmcAvailabilityInjector() {
    }

    /**
     * 返回一个**新列表** = 原列表 + 「已学会但无库存」物品的虚拟可用条目。
     *
     * <p>刻意不改入参：{@code storage} 是 BD 的客户端存储列表，
     * 往里写会污染真实镜像（下一次 delta 同步就会把它冲掉，而且可能被当成真实库存）。
     *
     * @param storage 客户端网络存储列表（只读；含 EMC 条目）
     * @return 新列表；数据不可用时**原样返回入参**
     */
    public static List<KeyAmount> withEmcAvailability(List<KeyAmount> storage) {
        if (storage == null || storage.isEmpty()) {
            return storage;
        }
        ClientKnowledgeCache.Entry entry = ClientKnowledgeCache.get();
        if (entry == null || entry.learned().isEmpty()) {
            return storage;
        }

        long emc = readNetworkEmc(storage);
        if (emc <= 0L) {
            return storage;
        }

        // 已有的真实物品条目。用**精确到组件**的匹配，理由与 VirtualEntryProvider 相同：
        // 附魔钻石剑与普通钻石剑是两种东西，不能因为前者在库存里就不给后者虚拟可用量。
        Set<ItemStackKey> present = new HashSet<>();
        for (KeyAmount ka : storage) {
            if (ka != null && ka.key() instanceof ItemStackKey key) {
                present.add(key);
            }
        }

        List<KeyAmount> out = null;
        int added = 0;
        for (Map.Entry<ItemInfo, Long> e : entry.learned().entrySet()) {
            ItemStack canonical = e.getKey().createStack();
            if (canonical.isEmpty()) {
                continue;
            }
            ItemStackKey key = new ItemStackKey(canonical);
            if (present.contains(key)) {
                continue; // 有真实库存 → 交给真实条目，不重复注入
            }
            long unitPrice = e.getValue() == null ? 0L : e.getValue();
            if (unitPrice <= 0L) {
                continue; // 服务端没给价（该物品当前没有 EMC 价值）
            }
            long affordable = emc / unitPrice;
            if (affordable <= 0L) {
                continue; // 买不起 → 不注入（0 数量条目没有意义）
            }
            if (out == null) {
                out = new ArrayList<>(storage);
            }
            out.add(new KeyAmount(key, affordable));
            added++;
        }

        if (out == null) {
            return storage;
        }
        if (!quiet) {
            long seq = ++injectCount;
            String summary = String.format(
                    "JEI 可用池注入：追加 %d 条可兑换物品（余额 %d，已学习 %d 项，原列表 %d 条 → %d 条）",
                    added, emc, entry.learned().size(), storage.size(), out.size());
            if (seq <= LOG_FIRST_N) {
                BeyondEmc.LOGGER.info("[BeyondEMC] {}", summary);
            } else {
                BeyondEmc.LOGGER.debug("[BeyondEMC] {}", summary);
            }
        }
        return out;
    }

    private static long injectCount = 0;
    private static final int LOG_FIRST_N = 5;

    /**
     * 自检期间静默。
     *
     * <p>⚠️ **教训（0.2.0-B 首轮实测）**：自检用的合成数据（余额 999999999、819200 等）
     * 也会打出"JEI 可用池注入"日志，与真实 JEI 交互的日志**完全无法区分** ——
     * 诊断信号被自己的测试污染，导致"到底有没有生效"判断不了，白跑一轮实机。
     *
     * <p>所以自检必须把自己的输出与生产输出分开。这与
     * "扣费数量必须等于交付数量"是同一条纪律：**测量手段不能改变被测对象**。
     */
    private static volatile boolean quiet = false;

    public static void setQuiet(boolean value) {
        quiet = value;
    }

    private static long invokedCount = 0;

    /**
     * 由 {@code TransferHelperMixin} 在每次进入注入点时调用。
     *
     * <p>这是区分两种失败模式的**唯一**判据：
     * <ul>
     *   <li>没有这行 → Mixin 根本没生效（注入点没匹配上，而 {@code require = 0} 是静默跳过的）；</li>
     *   <li>有这行但没有后面的"追加 N 条" → Mixin 生效了，只是当时确实没有可注入的物品
     *       （没学会 / 买不起 / 知识清单为空）。</li>
     * </ul>
     */
    public static void noteMixinInvoked() {
        long n = ++invokedCount;
        if (!quiet && n <= LOG_FIRST_N) {
            BeyondEmc.LOGGER.info("[BeyondEMC] JEI 转移钩子被调用（第 {} 次）：Mixin 已生效", n);
        }
    }

    /** 诊断用：注入点被调用的累计次数。 */
    public static long invokedCount() {
        return invokedCount;
    }

    /** 从客户端网络存储列表里读 EMC 余额（BD 已把它作为一条普通资源同步过来）。 */
    private static long readNetworkEmc(List<KeyAmount> storage) {
        for (KeyAmount ka : storage) {
            if (ka == null || ka.isEmpty()) {
                continue;
            }
            if (ka.key() == EmcStackKey.INSTANCE || EmcStackKey.INSTANCE.equals(ka.key())) {
                return ka.amount();
            }
        }
        return 0L;
    }
}
