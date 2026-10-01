package com.zhuyuhang.beyondemc.materialize;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.core.LoadingGuard;
import com.zhuyuhang.beyondemc.emc.EmcItemKey;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/**
 * 物化的**唯一收口**：把"已学习但无库存"的物品真实写进网络的 {@code UnifiedStorage}。
 *
 * <h2>权威与派生的边界（不可越）</h2>
 * 唯一权威是 <b>EMC 池 + 学习集合</b>（+ 物品价格表、真实库存）。物化条目
 * = {@code f(EMC, 学习集合, 单价, 真实库存)}，是<b>可随时丢弃重建的纯函数派生</b>。
 * 因此本类<b>只做</b> 权威 → 派生的单向推导：它从不把物化条目的数量回写到 EMC 或学习集合。
 * 物化层彻底损坏也不会丢任何数据（重建即可）。
 *
 * <h2>两个必须守住的性质</h2>
 * <ol>
 *   <li><b>幂等</b>：对同一网络连调两次 {@link #refresh} 结果相同（数学是纯函数、写回是差量）。</li>
 *   <li><b>绝不挂在"存储变化事件"上</b>：写存储会触发 {@code UnifiedStorage.onChange} →
 *       {@code net.setDirty()} + 广播 delta。若把 {@code refresh} 挂在那里会自激。
 *       它只由 5 个服务层触发点显式调用（EMC 增减 / 新学习 / 价格重映射 / 读档修复）。</li>
 * </ol>
 *
 * <h2>写回必须包在 {@link MaterializingGuard} 内</h2>
 * 见 {@link MaterializingGuard} 的类注释：否则物化层维护自己的派生数据时会被自己的抽取护栏
 * 改道收费或拦截，形成自锁。
 */
public final class ItemMaterializer {

    /** 已观测到的网络（弱引用集合）。 */
    private static final Set<DimensionsNet> OBSERVED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** 已排入 tick 队列、等待刷新的网络 → 排队时刻（毫秒）。用于合并同一 tick 内的多次触发。 */
    private static final Map<DimensionsNet, Long> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 排队多久还没跑，就认定那个队列任务已被丢弃，允许重新排队（毫秒）。
     *
     * <h2>为什么必须有这个超时（0.3.2 实测缺陷的直接教训）</h2>
     * 原实现是"用 {@code Set} 去重，由队列任务负责清除标记"。这条链路有个可以证实的缺口：
     * 若 {@code server.execute(...)} 提交的任务<b>没能被执行</b>（服务端正在停止、执行器已关等），
     * 标记就<b>永久留在集合里</b>，此后<b>每一次</b> {@code scheduleRefresh} 都会在
     * {@code if (!PENDING.add(net)) return;} 处静默返回 —— 物化层从此冻结，且没有任何日志。
     *
     * <p>实测现象与之吻合：存档里 15 项已学习、只有 14 条物化条目，缺的
     * {@code minecraft:gunpowder} 恰好是在最后一次成功重算<b>之后</b>才学会的
     * （日志最后一次重算 EMC=3066234 → 14 条，此后到退出再无任何重算）。
     *
     * <p>一个 tick 是 50 ms，正常任务在 1 个 tick 内就会跑掉；取 2 秒作为"任务已丢失"的判据
     * —— 远大于正常延迟，又能让被冻结的物化层在<b>下一次触发时</b>自愈，并留下 WARN 证据。
     */
    private static final long PENDING_STALE_MS = 2_000L;

    private static final AtomicLong REFRESH_COUNT = new AtomicLong();
    private static final AtomicLong LOGGED = new AtomicLong();
    private static final int LOG_FIRST_N = 8;

    private static volatile boolean ledgerWarned = false;

    private ItemMaterializer() {
    }

    // ------------------------------------------------------------------
    // 观测注册（用于 refreshAll）
    // ------------------------------------------------------------------

    /**
     * 登记一个网络，供 {@link #refreshAll()} 遍历。
     *
     * <p>存在的理由：BD 的 {@code NetRegistryIndex} 是包私有类
     * （{@code NetRegistryIndex.java:26}，且 {@code get}/{@code getActiveNetIds} 都是包私有），
     * 我们没有合法的"枚举全部网络"入口（Spike S-0.3-5）。退路就是本集合 +
     * 在线玩家所属网络（{@link #refreshAll()} 两条来源取并集）。
     *
     * <p>覆盖面论证：任何网络只有当玩家能访问时才会被 EMC/学习集合改动，而每一次改动都会经过
     * 我们的触发点（都会调 {@link #refresh} → 都会 {@link #observe}）；此外每个网络在被 BD
     * 反序列化时也会经 {@code DimensionsNetMixin} 登记一次。因此"会变化的网络"必然在集合里。
     */
    public static void observe(@Nullable DimensionsNet net) {
        if (net == null) {
            return;
        }
        try {
            OBSERVED.add(net);
        } catch (Throwable ignored) {
            // 观测失败只影响"读档后自动修复"的覆盖面，不影响任何正确性
        }
    }

    // ------------------------------------------------------------------
    // 主入口
    // ------------------------------------------------------------------

    /**
     * 把一个网络的物化刷新<b>排到当前 tick 结束之后</b>执行（同一网络只排一次）。
     *
     * <h2>为什么需要"延后"而不是就地刷新</h2>
     * 触发点大多发生在"存储正处于一次变更的中间"：
     * <ul>
     *   <li>{@code EmcDepositHandler.beforeInsert} 里 EMC <b>还没落库</b>（钩子只能改插入内容，
     *       真正的 insert 在它返回之后才发生）；</li>
     *   <li>{@code InterfaceWithdrawService.beforeExtract} 里铸造出的物品<b>还没被原生抽取取走</b>。</li>
     * </ul>
     * 就地刷新会读到"半成品状态"，算出来的数量当场就是错的。排到 tick 之后则所有变更都已落定。
     *
     * <h2>为什么不用"订阅存储变更"</h2>
     * BD 提供 {@code subscribeAnyWeak}，但它会在 {@code onContentChanged} 内部回调 ——
     * 那是在存储自身的写操作<b>尚未完全退出</b>时重入并再次写存储（例如 {@code unzipMatterBall}
     * 会在插入过程中递归插入）。本方案刻意避开这种重入：只从 5 个服务层触发点排任务。
     *
     * <h2>合并</h2>
     * 用 {@link #PENDING} 去重：连续存入一背包物品只会排一个任务，不会放大成 N 次全量重算。
     */
    public static void scheduleRefresh(@Nullable DimensionsNet net) {
        if (net == null) {
            return;
        }
        observe(net);
        long now = System.currentTimeMillis();
        Long queuedAt = PENDING.get(net);
        if (queuedAt != null && now - queuedAt < PENDING_STALE_MS) {
            return; // 已经排过，等它跑（同一 tick 内的多次触发合并成一次）
        }
        if (queuedAt != null) {
            // 只有"任务被丢弃"才会走到这里。留痕，否则这种失效是完全无声的（见 PENDING_STALE_MS 注释）
            BeyondEmc.LOGGER.warn("[BeyondEMC] 网络 {} 的上一次物化刷新排了 {} ms 仍未执行，"
                            + "判定队列任务已丢失，重新排队（此前物化层是陈旧的）",
                    net.getId(), now - queuedAt);
        }
        PENDING.put(net, now);
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                runPending(net);
                return;
            }
            server.execute(() -> runPending(net));
        } catch (Throwable t) {
            runPending(net);
        }
    }

    /** 队列任务体：先清标记再重算（顺序不能反，否则重算期间的新触发会被吞掉）。 */
    private static void runPending(DimensionsNet net) {
        PENDING.remove(net);
        refresh(net);
    }

    /**
     * 撤销某个网络"已排队但还没跑"的刷新。
     *
     * <p>只给诊断命令 {@code /beyondemc materialize clear} 用：否则刚清完条目，
     * 队列里那个已排的任务会立刻把它们按权威数据重建回来，命令看起来"没生效"。
     */
    public static void cancelPending(@Nullable DimensionsNet net) {
        if (net == null) {
            return;
        }
        try {
            PENDING.remove(net);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 重算并写回某个网络的物化条目。**幂等、可重入、全程 try/catch**（异常只记日志，
     * 折算/兑换/抽取照常）。
     */
    public static void refresh(@Nullable DimensionsNet net) {
        if (net == null) {
            return;
        }
        observe(net);
        try {
            UnifiedStorage storage = net.getUnifiedStorage();
            if (storage == null) {
                return;
            }

            // L1 熔断：必须【真清空】——条目是真实存储条目，只"忽略"的话 BD 原生列表照样渲染它们
            if (!BeyondEmcConfig.materializeItems()) {
                clear(net);
                return;
            }

            switch (BeyondEmcConfig.materializeMode()) {
                case OFF -> {
                    clear(net);
                    return;
                }
                case LEDGER -> {
                    if (!ledgerWarned) {
                        ledgerWarned = true;
                        BeyondEmc.LOGGER.warn("[BeyondEMC] materializeMode=LEDGER 尚未实现"
                                + "（需自实现持久化/同步/合并/销毁），当前按 OFF 处理：清空物化条目");
                    }
                    clear(net);
                    return;
                }
                case STORAGE -> {
                    // 继续
                }
            }

            // 读档期不动存储（读档后的修复由触发点 ⑤ EMCRemapEvent 负责）
            if (LoadingGuard.isLoading()) {
                return;
            }
            // EMC 表未就绪时价格全是 0，算出来的 want 必然是空表 —— 若此时 applyWant
            // 就会把已有条目全清掉。必须提前返回（实测：EMC 表要等玩家登录或 /reload）。
            if (!EmcAvailability.isReady()) {
                return;
            }

            long emc = NetEmcAccessor.getEmc(net);
            ToLongFunction<ItemInfo> price = ItemMaterializer::buyPrice;
            ToLongFunction<ItemInfo> stock = info -> realStock(storage, info);

            Map<ItemInfo, Long> want = MaterializeMath.solve(
                    emc,
                    NetKnowledgeStore.snapshot(net),
                    price,
                    stock,
                    BeyondEmcConfig.maxMaterializedItems());

            applyWant(net, want);

            long seq = REFRESH_COUNT.incrementAndGet();
            if (LOGGED.getAndIncrement() < LOG_FIRST_N) {
                BeyondEmc.LOGGER.info("[BeyondEMC] 物化（第 {} 次）：网络 {} EMC={} → {} 条物化条目",
                        seq, net.getId(), emc, want.size());
            }
        } catch (Throwable t) {
            BeyondEmc.LOGGER.error("[BeyondEMC] 物化失败（折算/兑换/抽取不受影响）", t);
        }
    }

    /**
     * 遍历所有已知网络各刷新一次。
     *
     * <p>由 {@code EmcAvailability.onRemap}（{@code EMCRemapEvent}）调用 —— 它同时覆盖
     * "价格重映射"与"读档后修复"两件事，因为 EMC 表只有到那时才就绪。
     */
    public static void refreshAll() {
        try {
            Set<DimensionsNet> nets = Collections.newSetFromMap(new IdentityHashMap<>());
            nets.addAll(snapshotObserved());

            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    try {
                        nets.addAll(DimensionsNet.getAllNetFromPlayer(player));
                    } catch (Throwable ignored) {
                        // 单个玩家查询失败不影响其它网络
                    }
                }
            }

            for (DimensionsNet net : nets) {
                refresh(net);
            }
        } catch (Throwable t) {
            BeyondEmc.LOGGER.error("[BeyondEMC] 批量物化失败", t);
        }
    }

    // ------------------------------------------------------------------
    // 存储写回（差量）
    // ------------------------------------------------------------------

    /**
     * 把网络的物化条目同步到 {@code want} 描述的状态（差量写回，不整表重建 ——
     * 避免每次 refresh 刷出上百条 delta 广播）。
     *
     * <p>包在 {@link MaterializingGuard} 内：本方法对 {@link EmcItemKey} 的 insert/extract
     * 属于"物化层自身的维护"，必须能与外部抽取区分开。
     *
     * <p>无副作用的读法见 {@link #currentEntries}；两者一起构成可无头验证的接口。
     */
    public static void applyWant(DimensionsNet net, Map<ItemInfo, Long> want) {
        if (net == null) {
            return;
        }
        UnifiedStorage storage = net.getUnifiedStorage();
        if (storage == null) {
            return;
        }
        Map<ItemInfo, Long> cur = currentEntries(net);
        Map<ItemInfo, Long> target = want == null ? Map.of() : want;

        MaterializingGuard.enter();
        try {
            // 1) 清掉不再需要的（含"want 为空 ⇒ 全清"）
            for (Map.Entry<ItemInfo, Long> e : cur.entrySet()) {
                if (!target.containsKey(e.getKey())) {
                    storage.extract(new EmcItemKey(e.getKey()), e.getValue(), false, false);
                }
            }
            // 2) 差量调整
            long unmet = 0L;
            for (Map.Entry<ItemInfo, Long> e : target.entrySet()) {
                long goal = Math.max(0L, e.getValue());
                long have = cur.getOrDefault(e.getKey(), 0L);
                if (goal > have) {
                    KeyAmount remainder = storage.insert(new EmcItemKey(e.getKey()), goal - have, false);
                    unmet += Math.max(0L, remainder.amount());
                } else if (goal < have) {
                    storage.extract(new EmcItemKey(e.getKey()), have - goal, false, false);
                }
            }
            if (unmet > 0L) {
                // 只可能是网络槽位已满（slotMaxSize）。不是错误，但值得知道。
                BeyondEmc.LOGGER.warn("[BeyondEMC] 网络 {} 槽位不足，有 {} 份物化条目未能写入", net.getId(), unmet);
            }
        } finally {
            MaterializingGuard.exit();
        }
    }

    /**
     * 清空该网络的<b>全部</b>物化条目。
     *
     * <p>这是 L1 熔断（{@code materializeItems=false}）的真正动作 —— 只"忽略"条目是不够的。
     *
     * @return 被清掉的条目种类数
     */
    public static int clear(@Nullable DimensionsNet net) {
        if (net == null) {
            return 0;
        }
        UnifiedStorage storage = net.getUnifiedStorage();
        if (storage == null) {
            return 0;
        }
        Map<ItemInfo, Long> cur = currentEntries(net);
        if (cur.isEmpty()) {
            return 0;
        }

        MaterializingGuard.enter();
        try {
            for (Map.Entry<ItemInfo, Long> e : cur.entrySet()) {
                storage.extract(new EmcItemKey(e.getKey()), e.getValue(), false, false);
            }
        } catch (Throwable t) {
            BeyondEmc.LOGGER.error("[BeyondEMC] 清空物化条目失败", t);
        } finally {
            MaterializingGuard.exit();
        }
        return cur.size();
    }

    // ------------------------------------------------------------------
    // 只读视图
    // ------------------------------------------------------------------

    /**
     * 读出当前网络里<b>真实存在</b>的物化条目（{@code 物品 → 数量}）。
     *
     * <p>这是"物化条目真实存在于维度网络中"的直接证据，也是命令
     * {@code /beyondemc materialize list} 与自检的数据源。
     */
    public static Map<ItemInfo, Long> currentEntries(@Nullable DimensionsNet net) {
        Map<ItemInfo, Long> out = new LinkedHashMap<>();
        if (net == null) {
            return out;
        }
        UnifiedStorage storage = net.getUnifiedStorage();
        if (storage == null) {
            return out;
        }
        // getStorage() 返回的是内部 slotIndex 的活视图，先取再改，避免边迭代边改
        for (KeyAmount ka : new ArrayList<>(storage.getStorage())) {
            if (ka.key() instanceof EmcItemKey itemKey && ka.amount() > 0L) {
                out.merge(itemKey.info(), ka.amount(), Long::sum);
            }
        }
        return out;
    }

    /** 按权威数据现场算出"应该有多少"（命令与自检用）。 */
    public static Map<ItemInfo, Long> desiredEntries(@Nullable DimensionsNet net) {
        if (net == null) {
            return Map.of();
        }
        UnifiedStorage storage = net.getUnifiedStorage();
        if (storage == null) {
            return Map.of();
        }
        return MaterializeMath.solve(
                NetEmcAccessor.getEmc(net),
                NetKnowledgeStore.snapshot(net),
                ItemMaterializer::buyPrice,
                info -> realStock(storage, info),
                BeyondEmcConfig.maxMaterializedItems());
    }

    /** 观测量：累计 refresh 次数（诊断用）。 */
    public static long refreshCount() {
        return REFRESH_COUNT.get();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 购买价。任何异常都当作"无价格"（= 不物化），绝不把异常抛给调用链。 */
    private static long buyPrice(ItemInfo info) {
        try {
            return IEMCProxy.INSTANCE.getValue(info);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 该物品在网络里的真实库存量（用与 BD 相同的 ItemStackKey 语义查询）。 */
    private static long realStock(UnifiedStorage storage, ItemInfo info) {
        try {
            ItemStackKey key = new ItemStackKey(info.createStack());
            return storage.getStackByKey(key).amount();
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 诊断隔离（自检不得留下全局副作用）
    // ------------------------------------------------------------------

    /**
     * 诊断用：取当前「已观测网络」集合的快照（身份语义，与 {@link #OBSERVED} 一致）。
     *
     * <h2>为什么需要它（0.3.2 排查现场的直接教训）</h2>
     * 自检会用 {@code new DimensionsNet(true)} 造一批<b>临时网络</b>去验证物化数学与写回。
     * 这些临时网络只要经过 {@link #refresh} / {@link #scheduleRefresh}，就会被 {@link #observe}
     * 登记进全局的 {@link #OBSERVED}，<b>此后一直留在里面</b>（直到被 GC）。
     *
     * <p>后果不是算错，而是<b>看不见</b>：实测一次 {@code /reload} 触发的 {@link #refreshAll}
     * 连打 8 条「物化（第 N 次）」日志，全部是 {@code EMC=0} 的临时网络
     * （{@code getId()} 也都是 0，与真实网络混在一起无法分辨），把<b>真实网络那一条挤到了
     * {@link #LOG_FIRST_N} 的上限之外</b>。排查"为什么大炮看不到某材料"时，唯一想看的那行
     * 「网络 0 EMC=3066234 → 15 条物化条目」恰恰不在日志里 —— 这类"静默"是本缺陷最难查的部分。
     *
     * <p>用法：自检开始前 {@code Set<DimensionsNet> before = observedSnapshot();}，
     * 结束的 {@code finally} 里 {@link #restoreObserved}(before)。
     */
    public static Set<DimensionsNet> observedSnapshot() {
        Set<DimensionsNet> copy = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            synchronized (OBSERVED) {
                copy.addAll(OBSERVED);
            }
        } catch (Throwable ignored) {
            // 取不到快照只影响自检的"零副作用"，不影响任何正确性
        }
        return copy;
    }

    /**
     * 诊断用：把已观测集合还原成 {@code snapshot}（移除自检期间新登记的临时网络），
     * 一并撤掉它们的排队标记。只做"删除",不添加 —— 快照之前就存在的网络一律保留。
     */
    public static void restoreObserved(@Nullable Set<DimensionsNet> snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            List<DimensionsNet> drop = new ArrayList<>();
            synchronized (OBSERVED) {
                for (DimensionsNet net : OBSERVED) {
                    if (!snapshot.contains(net)) { // snapshot 是身份集合 ⇒ contains 按身份判
                        drop.add(net);
                    }
                }
                for (DimensionsNet net : drop) {
                    OBSERVED.remove(net);
                    PENDING.remove(net);
                }
            }
        } catch (Throwable ignored) {
            // 还原失败只影响日志洁净度，不影响任何正确性
        }
    }

    private static List<DimensionsNet> snapshotObserved() {
        try {
            synchronized (OBSERVED) {
                return new ArrayList<>(OBSERVED);
            }
        } catch (Throwable t) {
            return List.of();
        }
    }
}
