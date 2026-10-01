package com.zhuyuhang.beyondemc.emc;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeInsertHandler;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.config.EmcItemFilter;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.core.LoadingGuard;
import com.zhuyuhang.beyondemc.core.MintingGuard;
import com.zhuyuhang.beyondemc.exchange.KnowledgeSyncNotifier;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import com.zhuyuhang.beyondemc.materialize.ItemMaterializer;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 存入折算钩子：把"有 EMC 价值的物品"换成"等值 EMC"存进维度网络。
 *
 * <p>挂在 BD 的官方扩展点 {@code UnifiedStorageBeforeInsertHandler} 上。选它的理由：
 * 它是**全仓库唯一的存入收口**（`UnifiedStorage.java:91`），且 BD 自己从未注册过任何 handler
 * —— 零竞争。所有进网路径（界面点击、背包 shift、批量转移、网络接口、漏斗、AE2/RS…）
 * 都收敛到这里，我们不需要写任何 Mixin。
 *
 * <h2>⚠️ 本方法必须是纯函数</h2>
 * BD 没把 {@code simulate} 参数传进钩子，所以"先模拟后提交"的调用方（如
 * `NetHopperBlockEntity.java:117/121`）会让我们按同样条件算两次；而且
 * {@code unzipMatterBall} 内部会**递归**调用 {@code insert}（`AbstractUnorderedStackHandler.java:624`）。
 * 因此本类**不持有任何按调用变化的状态**，只做一个确定性的输入→输出映射。
 *
 * <p>唯一的例外是"学习"这个副作用（写 {@link NetKnowledgeStore}）。它是幂等的 {@code Set.add}，
 * 代价见架构文档风险 R2：模拟调用也可能把一个物品记进学习集合。
 * 考虑到玩家手里本来就有该物品、而 ProjectE 的学习本就只需持有物品，这个偏差无实质套利空间，
 * 因此接受，不做额外的"真实提交确认"机制（那需要订阅 delta 事件 + 待定队列，复杂度不值当）。
 */
public final class EmcDepositHandler implements UnifiedStorageBeforeInsertHandler.BeforeInsertHandler {

    private static final AtomicLong CONVERTED_ITEMS = new AtomicLong();

    private static final AtomicLong CONVERTED_EMC = new AtomicLong();

    private static final int LOG_FIRST_N = 3;

    private static final AtomicLong LOGGED = new AtomicLong();

    @Override
    public @NotNull UnifiedStorageBeforeInsertHandler.BeforeInsertHandlerReturnInfo beforeInsert(
            @NotNull KeyAmount originalInsert,
            @NotNull KeyAmount tryInsert,
            @NotNull DimensionsNet net) {

        // ---- 1. 网络为空（UnifiedStorage.getEmpty() 的壳）→ 原样放行 ----
        if (net == null) {
            return pass(tryInsert);
        }

        // ---- 2. 空堆叠 ---- 
        if (tryInsert.isEmpty()) {
            return pass(tryInsert);
        }

        // ---- 3. 总开关 ----
        if (!BeyondEmcConfig.enableEmcDeposit()) {
            return pass(tryInsert);
        }

        // ---- 4. 读档中不折算（否则每次读档都会不可逆地重写存档，风险 R1）----
        if (LoadingGuard.isLoading()) {
            return pass(tryInsert);
        }

        // ---- 4b. 铸造中不折算 ----
        // 网络接口兑换的实现是"先把物品写进存储、再让原生抽取取走"（见 InterfaceWithdrawService）。
        // 若这里不拦，那件物品会被当场按回收价折算回 EMC，
        // 而兑换已经按购买价扣过费 → 兑换自我抵消。
        if (MintingGuard.isMinting()) {
            return pass(tryInsert);
        }

        // ---- 5. 只处理物品，其它资源（流体/能量/EMC 自身）原样放行 ----
        if (!(tryInsert.key() instanceof ItemStackKey itemKey)) {
            return pass(tryInsert);
        }

        // IStackKey 契约：getReadOnlyStack() 返回的堆叠数量恒为 1，数量在 KeyAmount 里
        ItemStack stack = itemKey.getReadOnlyStack();
        if (stack.isEmpty()) {
            return pass(tryInsert);
        }

        // ---- 6/7. 策略判断：物品筛选 + 组件策略 ----
        // 抽成独立方法，既能在无头环境里被自检决定性验证，也便于日志带上原因
        String skip = skipReason(stack);
        if (skip != null) {
            return pass(tryInsert);
        }
        ItemInfo info = ItemInfo.fromStack(stack);

        // ---- 8. EMC 表未就绪不折算 ----
        // 阶段 1 实测：EMC 表要等有玩家登录或 /reload 才由 ProjectE 算出。
        // 未就绪时 getSellValue 本来就会返回 0，这里显式判一次是为了语义清晰、并省一次查表。
        if (!EmcAvailability.isReady()) {
            return pass(tryInsert);
        }

        // ---- 9. 定价：用【回收价】(getSellValue)，与 ProjectE 转换桌烧物品/凝聚器完全一致 ----
        // （证据：SlotConsume.java:29、CondenserBlockEntity.java:150 等 5 处）
        // 若误用 getValue（购买价），在 covalenceLoss < 1 的服务器上会形成无风险套利。
        long sell;
        try {
            sell = IEMCProxy.INSTANCE.getSellValue(info);
        } catch (Throwable t) {
            BeyondEmc.LOGGER.warn("[BeyondEMC] 查询回收价失败，本次按原样入库: {}", info, t);
            return pass(tryInsert);
        }
        if (sell <= 0L) {
            return pass(tryInsert);
        }

        // ---- 10. 换算（饱和乘法，风险 R5）----
        long emc = NetEmcAccessor.saturatingMultiply(sell, tryInsert.amount());
        if (emc <= 0L) {
            return pass(tryInsert);
        }

        // ---- 11. 学习：用 getPersistentInfo 归一化，保证"同一种物品"的判定与 ProjectE 一致 ----
        // 注意这一步有副作用，且无法区分 simulate —— 见类注释
        try {
            ItemInfo persistent = IEMCProxy.INSTANCE.getPersistentInfo(info);
            if (NetKnowledgeStore.learn(net, persistent)) {
                BeyondEmc.LOGGER.debug("[BeyondEMC] 网络 {} 学会新物品: {}", net.getId(), persistent);
                // 增量推给正在看这个网络的玩家：这样"存入新物品后不用关界面重开就能直接取出"
                // （只在新学会时为 true，重复存入不会重复发）
                KnowledgeSyncNotifier.notifyLearned(net, persistent);
            }
        } catch (Throwable t) {
            BeyondEmc.LOGGER.warn("[BeyondEMC] 记入学习集合失败（折算仍继续）: {}", info, t);
        }

        long total = CONVERTED_ITEMS.addAndGet(tryInsert.amount());
        long totalEmc = CONVERTED_EMC.addAndGet(emc);
        if (LOGGED.getAndIncrement() < LOG_FIRST_N) {
            BeyondEmc.LOGGER.info("[BeyondEMC] 折算：{} ×{} → {} EMC（回收单价 {}）",
                    info, tryInsert.amount(), emc, sell);
        }

        // ---- 12. 触发点 ①：EMC 增加后重算物化条目（0.3.0）----
        // ⚠️ 必须"延后"而不是就地刷新：此刻 EMC 【还没落库】—— 本钩子只能改插入内容，
        // 真正的 insert 在返回之后才发生。scheduleRefresh 会把重算排到本 tick 结束之后，
        // 那时 EMC 已经是终态（同一网络只排一次，连续存入不会放大成 N 次全量重算）。
        ItemMaterializer.scheduleRefresh(net);

        // 返回 cancel=false + 换算后的 EMC 堆叠，让 BD 原生写入（自动持久化 + delta 广播）
        return new UnifiedStorageBeforeInsertHandler.BeforeInsertHandlerReturnInfo(
                new KeyAmount(EmcStackKey.INSTANCE, emc), false);
    }

    /**
     * 只做**策略判断**（不查价格）：返回 {@code null} 表示"允许继续走价格流程"，
     * 否则返回人类可读的跳过原因。
     *
     * <p>抽成独立方法的两个理由：
     * <ol>
     *   <li><b>可测</b>：在无头环境里 EMC 表永远为空，价格那一步走不到，
     *       光看"钩子原样返回"无法区分是被哪一道门挡的。有了这个方法，
     *       "组件物品不折算""黑名单不折算"就能被自检**决定性**验证；</li>
     *   <li><b>可观测</b>：日志/命令里可以直接带上原因，而不是只有一个布尔结果。</li>
     * </ol>
     */
    public static @org.jetbrains.annotations.Nullable String skipReason(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "空堆叠";
        }
        if (!EmcItemFilter.allows(stack)) {
            return "被配置的黑名单/白名单排除";
        }
        ItemInfo info = ItemInfo.fromStack(stack);
        if (!BeyondEmcConfig.convertComponentItems() && info.hasModifiedComponents()) {
            return "带非默认数据组件（附魔/耐久/自定义名/容器内容物等），且 convertComponentItems=false";
        }
        return null;
    }

    /**
     * "什么都不做"的正确写法。     *
     * <p>⚠️ 不能返回空的 KeyAmount 来表示"不处理"：`UnifiedStorage.java:98-99` 会把空结果
     * 直接返回给调用方，界面槽位会认为插入失败而把物品留在原地。
     * 必须原样返回当前堆叠，并显式 {@code cancel = false}。
     */
    private static UnifiedStorageBeforeInsertHandler.BeforeInsertHandlerReturnInfo pass(KeyAmount current) {
        return new UnifiedStorageBeforeInsertHandler.BeforeInsertHandlerReturnInfo(current, false);
    }

    /** 累计折算过的物品数量（诊断用）。 */
    public static long convertedItemCount() {
        return CONVERTED_ITEMS.get();
    }

    /** 累计折算出的 EMC 总量（诊断用）。 */
    public static long convertedEmcTotal() {
        return CONVERTED_EMC.get();
    }
}
