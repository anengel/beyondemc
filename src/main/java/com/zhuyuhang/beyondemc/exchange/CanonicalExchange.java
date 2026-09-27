package com.zhuyuhang.beyondemc.exchange;

import com.zhuyuhang.beyondemc.BeyondEmc;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 兑换/铸造的**身份一致性收口**。
 *
 * <h2>它防的是什么：一个真实的刷物品漏洞</h2>
 * 兑换的判定链是这样的：
 * <pre>
 *   ① 归一化：info = getPersistentInfo(fromStack(请求的物品))
 *   ② 用 info 查"网络是否已学会"
 *   ③ 用 info 查购买价
 *   ④ 扣费，然后铸造出……【请求的物品】
 * </pre>
 * 问题出在 ① 与 ④ 之间：`getPersistentInfo` 会**剥离不影响价值的组件**。
 * 于是请求"附魔钻石剑"时：
 * <ul>
 *   <li>① 得到 info = **普通钻石剑**（附魔默认不参与计价）</li>
 *   <li>②③ 按**普通钻石剑**判定已学习、按 8192 计价</li>
 *   <li>④ 却铸造出**附魔钻石剑** —— 价值远超 8192</li>
 * </ul>
 * 这就是"按低价买出高价物品"，也就是刷物品。
 *
 * <p>两条路径都中招：
 * <ul>
 *   <li><b>网络接口</b>：过滤器里放附魔物品即可（用户实测发现）；</li>
 *   <li><b>GUI</b>：{@code ExchangeRequestPacket.template} 是<b>客户端可控</b>的，
 *       改包就能按普通物品的价格拿到带组件的物品。这一条更严重，因为它不需要
 *       任何游戏内配置，且可脚本化。</li>
 * </ul>
 *
 * <h2>修法：让"定价的身份"与"铸造的对象"由构造保证一致</h2>
 * 不再使用请求方传来的 {@code ItemStack} 去铸造，而是用
 * {@code info.createStack()} —— 即**归一化身份重建出的那个堆叠**。
 * 这样"用什么身份定价"与"铸造出什么"在结构上不可能不同。
 *
 * <p>另外做一次**往返校验**：要求
 * {@code getPersistentInfo(fromStack(info.createStack())).equals(info)}。
 * 若某天某个组件处理器让这个往返不再稳定，我们就<b>保守拒绝</b>这次兑换，
 * 而不是冒险铸造一个身份不明的对象。
 */
public final class CanonicalExchange {

    /** 归一化身份 + 由它重建的堆叠（两者保证一致）。 */
    public record Resolved(ItemInfo info, ItemStack stack) {
    }

    private CanonicalExchange() {
    }

    /**
     * 把请求的物品归一到"可定价、可铸造"的身份。
     *
     * @return null 表示无法安全处理（此时调用方应拒绝这次兑换）
     */
    public static @Nullable Resolved resolve(@Nullable ItemStack requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        try {
            ItemInfo info = IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(requested));
            if (info == null) {
                return null;
            }
            ItemStack canonical = info.createStack();
            if (canonical.isEmpty()) {
                return null;
            }
            // 往返校验：确认"归一化 → 重建 → 再归一化"回到同一个身份。
            // 不稳定就保守拒绝 —— 兑换是扣钱的操作，宁可拒绝也不要铸造身份不明的对象。
            ItemInfo roundTrip = IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromStack(canonical));
            if (!info.equals(roundTrip)) {
                BeyondEmc.LOGGER.warn("[BeyondEMC] 物品身份往返不稳定，已拒绝兑换: {} → {} → {}",
                        requested, info, roundTrip);
                return null;
            }
            return new Resolved(info, canonical);
        } catch (Throwable t) {
            BeyondEmc.LOGGER.warn("[BeyondEMC] 归一化物品身份失败，已拒绝兑换: {}", requested, t);
            return null;
        }
    }
}
