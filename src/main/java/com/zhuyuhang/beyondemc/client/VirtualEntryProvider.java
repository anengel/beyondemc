package com.zhuyuhang.beyondemc.client;

import com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.wintercogs.beyonddimensions.common.menu.widget.ClientNetStorage;
import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.emc.EmcStackKey;
import com.zhuyuhang.beyondemc.mixin.ClientNetStorageAccessor;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 把"已学习但无库存"的物品注入客户端的网络视图，让它们以虚拟条目出现。
 *
 * <h2>核心不变式（也是识别虚拟条目的唯一判据）</h2>
 * <pre>
 *   真实库存  = 同时存在于 sourceStorage 与 view
 *   虚拟条目  = 只存在于 view，sourceStorage 里没有
 * </pre>
 * 因此 {@link #isVirtual} 是<b>无状态</b>的：现场问一次 {@code sourceStorage} 就知道。
 *
 * <p><b>为什么刻意不用"自己维护一个虚拟集合"</b>：早先两个版本都用
 * {@code Set<IStackKey> VIRTUAL} 来记录注入了什么，结果连踩两次同一个坑 ——
 * 每次 {@code inject} 都先 {@code VIRTUAL.clear()}，一旦这一轮因为某种原因一条都没注入
 * （第一次是把自己上一轮的注入当成"真实库存"，第二次是 {@code view.hasStack} 把自己刚写进视图的
 * 条目当成"网络已有"），虚拟集合就被清空且不再填回。而界面上那些条目**还在显示**，
 * 于是表现为「条目看得见、点击毫无反应」—— 因为点击拦截正是靠这个集合识别的。
 *
 * <p>根因是<b>用可变状态描述一个本可现场推导的事实</b>。现在改成无状态判据后，
 * 这类失同步在结构上不可能再发生。
 *
 * <p>本类<b>刻意不引用任何 Minecraft 客户端类</b>（只用 ItemStack / ItemInfo），
 * 因为 {@code inject} 会被注入到**双端都存在**的 {@code DimensionsNetMenu} 上；
 * 服务端靠 {@code clientNetStorage == null} 提前返回。
 */
public final class VirtualEntryProvider {

    private static long injectCount = 0;

    private static long lastInjectedCount = 0;

    private static final int LOG_FIRST_N = 8;

    private VirtualEntryProvider() {
    }

    /**
     * 该资源键是否是虚拟条目（点击拦截要用）。
     *
     * <p>判据：是物品条目，且 {@code sourceStorage}（客户端侧"绝对真实"的存储镜像）里没有它。
     * 真实库存行两边都有 → 返回 false → 走 BD 原生逻辑；我们注入的条目只在视图里 → 返回 true。
     */
    public static boolean isVirtual(@Nullable ClientNetStorage view, @Nullable IStackKey<?> key) {
        if (view == null || !(key instanceof ItemStackKey)) {
            return false; // 非物品条目（EMC/流体/能量）一律不拦，交给 BD 原生处理
        }
        try {
            ClientNetStorageAccessor accessor = (ClientNetStorageAccessor) (Object) view;
            return !accessor.beyondemc$getSourceStorage().hasStack(key);
        } catch (Throwable t) {
            // 拿不到真实存储就保守地"不拦截"：宁可这次点不动，也不要误拦真实库存的操作
            BeyondEmc.LOGGER.warn("[BeyondEMC] 判定虚拟条目失败，本次不拦截: {}", key, t);
            return false;
        }
    }

    /** 上一次注入的条目数（仅用于诊断日志）。 */
    public static long lastInjectedCount() {
        return lastInjectedCount;
    }

    /**
     * 重新计算并注入虚拟条目。每次调用都会重算（幂等）。
     *
     * @param view 客户端的网络视图；服务端为 null（此时直接返回）
     */
    public static void inject(@Nullable ClientNetStorage view) {
        lastInjectedCount = 0;
        if (view == null) {
            return; // 服务端，或界面尚未就绪
        }

        if (!com.zhuyuhang.beyondemc.config.BeyondEmcConfig.showVirtualEntries()) {
            return; // 配置关闭了虚拟条目显示（BD 原生列表照常工作）
        }

        ClientKnowledgeCache.Entry entry = ClientKnowledgeCache.get();
        if (entry == null || entry.learned().isEmpty()) {
            // 这条日志很关键：区分"注入没生效"和"注入生效了但没数据"
            logThrottled("注入跳过：客户端还没有学习集合数据（知识同步包未到或为空）");
            return;
        }

        // ---- 0.3.0：服务端已开启物化时，客户端不再注入 ----
        // 因为那些物品已经是服务端真实的 EmcItemKey 存储条目（会随 BD 的 delta 同步下来），
        // 再注入一遍 ItemStackKey 虚拟行就会【同一个物品出两行】。
        // 判据来自服务端下发的权威标志（远程客户端读不到服务端配置）。
        if (entry.serverMaterialized()) {
            logThrottled("注入跳过：服务端已开启物化，条目由服务端真实持有（客户端不再注入虚拟条目）");
            return;
        }

        ClientNetStorageAccessor accessor = (ClientNetStorageAccessor) (Object) view;

        // ⚠️⚠️ 本方法里【所有】"库存/余额"查询都必须读 real（sourceStorage），
        // 绝不能读 view（clientNetStorage）—— 视图里混着我们自己注入的虚拟条目。
        // 这里连续踩了两次同一个坑：
        //   1) 用 view.getStorage() 算"已有库存" → 把自己上一轮的注入当成真实库存（日志：有库存 2）；
        //   2) 改成 real 后，view.hasStack(key) 那道门仍读视图 → 把自己刚写进视图的条目
        //      当成"网络已有此物"（日志：精确命中 2）。
        AbstractUnorderedStackHandler real = accessor.beyondemc$getSourceStorage();

        // 网络 EMC 余额（真实资源，BD 从服务端同步而来）
        long emc = real.getStackByKey(EmcStackKey.INSTANCE).amount();

        int injected = 0;
        int skipStocked = 0;
        int skipFiltered = 0;
        int skipNoPrice = 0;
        int skipUnaffordable = 0;

        for (Map.Entry<ItemInfo, Long> e : entry.learned().entrySet()) {
            ItemInfo info = e.getKey();
            long unitPrice = e.getValue() == null ? 0L : e.getValue();

            ItemStack stack = info.createStack();
            if (stack.isEmpty()) {
                continue;
            }

            // 需求 R6：网络里已有【完全相同】的物品（Item + 完整组件）时才让给 BD 原生行。
            //
            // ⚠️ 这里必须是**精确到组件**的匹配，不能用"只看 Item"的粒度。
            // 早期版本为了"避免重复行"用过 Item 粒度，结果是：
            // 网络里有一把【附魔】钻石剑（附魔物品默认不折算，作为真实库存留着），
            // 于是【未附魔】钻石剑的虚拟条目被误判为"已有库存"而跳过 —— 两者被混为一谈。
            // 附魔剑和未附魔剑是两种不同的东西，必须各占一行。
            //
            // 之所以不再担心重复行：默认配置下 convertComponentItems=false，
            // **能通过学习集合的物品一定是无组件的**（带组件的过不了那道门），
            // 所以精确匹配不会把它和任何组件变体混淆。
            ItemStackKey key = new ItemStackKey(stack);
            if (real.hasStack(key)) {
                skipStocked++;
                continue;
            }
            if (!accessor.beyondemc$matchFilter(key)) {
                skipFiltered++; // 尊重搜索框
                continue;
            }
            if (unitPrice <= 0L) {
                skipNoPrice++; // 服务端没给价（该物品当前没有 EMC 价值）
                continue;
            }

            long affordable = emc / unitPrice;
            if (affordable <= 0L) {
                skipUnaffordable++; // 买不起 → 不显示（0 数量条目会被列表丢弃）
                continue;
            }

            view.setAmountByKey(key, affordable);
            injected++;
        }

        lastInjectedCount = injected;

        // ⚠️ 必须置空排序缓存，否则 buildSortedIndex 直接返回旧索引，注入不生效
        // （ClientNetStorage.java:161-165）
        accessor.beyondemc$setCacheIndexes(null);

        // 前几次用 INFO 打出来，方便实机验证时不用开调试日志就能确认注入生效，
        // 且每个"跳过"的原因都分开计数 —— 出问题时一眼能看出是被哪一道门挡的。
        long seq = ++injectCount;
        String summary = String.format(
                "虚拟条目注入（第 %d 次）：注入 %d 条 | 跳过：已有同种库存 %d、被搜索过滤 %d、无价格 %d、买不起 %d"
                        + " | 余额 %d，已学习 %d 项",
                seq, injected, skipStocked, skipFiltered, skipNoPrice, skipUnaffordable,
                emc, entry.learned().size());
        if (seq <= LOG_FIRST_N) {
            BeyondEmc.LOGGER.info("[BeyondEMC] {}", summary);
        } else {
            BeyondEmc.LOGGER.debug("[BeyondEMC] {}", summary);
        }
    }

    private static void logThrottled(String message) {
        long seq = ++injectCount;
        if (seq <= LOG_FIRST_N) {
            BeyondEmc.LOGGER.info("[BeyondEMC] {}", message);
        }
    }
}
