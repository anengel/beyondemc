package com.zhuyuhang.beyondemc.knowledge;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.BeyondEmc;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 网络级"已学习物品"集合（产品决策：口径是<b>网络共享</b>，不是玩家个人 ProjectE 知识）。
 *
 * <p><b>为什么不放进 {@code UnifiedStorage}</b>：知识集合是"物品身份"元数据，
 * 条目数可达上千且大部分条目数量恒为 0/1。塞进 `key → long` 模型会污染真实库存视图，
 * 而且 {@code UnifiedStorage} 是"到 0 即删"策略，0 数量的学习标记会被直接清掉。
 * 详见 `docs/design/decisions.md` ADR-002。
 *
 * <p><b>存储位置</b>：本类只持有内存态，持久化由 {@code DimensionsNetMixin} 写进网络自己的
 * 存档 NBT 键 {@value #NBT_KEY}。这样网络销毁 = 文件删除 = 集合消失，零泄漏、零清理代码。
 *
 * <p><b>为什么用 {@link WeakHashMap} 而不是加字段到 {@code DimensionsNet}</b>：
 * 不用注入接口/字段，实现和测试都更简单。注意 BD 的 {@code DimensionsNet} 构造时会把自己
 * 注册到事件总线，所以它本来就不会被 GC —— 弱引用在这里不提供额外收益，也不带来问题；
 * 真正的生命周期由网络存档保证。
 */
public final class NetKnowledgeStore {

    /** 写进 {@code BDNet_<id>.dat} 的 NBT 键。 */
    public static final String NBT_KEY = "beyondemc:knowledge";

    private static final WeakHashMap<DimensionsNet, Set<ItemInfo>> STORE = new WeakHashMap<>();

    private NetKnowledgeStore() {
    }

    /** 取（必要时创建）某个网络的学习集合。返回的是活引用，调用方可直接修改。 */
    public static @NotNull Set<ItemInfo> get(@Nullable DimensionsNet net) {
        if (net == null) {
            return Collections.emptySet();
        }
        synchronized (STORE) {
            return STORE.computeIfAbsent(net, k -> new HashSet<>());
        }
    }

    /** 只读快照，供界面/命令使用（避免调用方误改内部集合）。 */
    public static @NotNull Set<ItemInfo> snapshot(@Nullable DimensionsNet net) {
        if (net == null) {
            return Collections.emptySet();
        }
        synchronized (STORE) {
            Set<ItemInfo> set = STORE.get(net);
            return set == null ? Collections.emptySet() : Set.copyOf(set);
        }
    }

    /**
     * 把一个物品并入网络的学习集合。
     *
     * @param info 必须是 {@code IEMCProxy.getPersistentInfo(...)} 归一化过的，
     *             这样"同一种物品"的判定才与 ProjectE 转换桌一致
     * @return 集合是否因此发生了变化（true = 本次是新学会的）
     */
    public static boolean learn(@Nullable DimensionsNet net, @NotNull ItemInfo info) {
        if (net == null) {
            return false;
        }
        synchronized (STORE) {
            return STORE.computeIfAbsent(net, k -> new HashSet<>()).add(info);
        }
    }

    public static boolean knows(@Nullable DimensionsNet net, @NotNull ItemInfo info) {
        if (net == null) {
            return false;
        }
        synchronized (STORE) {
            Set<ItemInfo> set = STORE.get(net);
            return set != null && set.contains(info);
        }
    }

    public static void clear(@Nullable DimensionsNet net) {
        if (net == null) {
            return;
        }
        synchronized (STORE) {
            Set<ItemInfo> set = STORE.get(net);
            if (set != null) {
                set.clear();
            }
        }
    }

    /** 把 {@code src} 的学习集合并入 {@code dst}（网络合并时用）。 */
    public static void merge(@Nullable DimensionsNet dst, @Nullable DimensionsNet src) {
        if (dst == null || src == null) {
            return;
        }
        Set<ItemInfo> from = snapshot(src);
        if (from.isEmpty()) {
            return;
        }
        synchronized (STORE) {
            STORE.computeIfAbsent(dst, k -> new HashSet<>()).addAll(from);
        }
        BeyondEmc.LOGGER.info("[BeyondEMC] 网络合并：并入 {} 项已学习物品", from.size());
    }

    // ------------------------------------------------------------------
    // 持久化：由 DimensionsNetMixin 在 save/load 时调用
    // ------------------------------------------------------------------

    /** 把学习集合写进网络的存档 CompoundTag。空集合时移除键，避免留垃圾。 */
    public static void writeTo(@NotNull CompoundTag tag, @Nullable DimensionsNet net) {
        Set<ItemInfo> set = snapshot(net);
        if (set.isEmpty()) {
            tag.remove(NBT_KEY);
            return;
        }
        ListTag list = new ListTag();
        int failed = 0;
        for (ItemInfo info : set) {
            var encoded = ItemInfo.CODEC.encodeStart(NbtOps.INSTANCE, info).result();
            if (encoded.isPresent()) {
                list.add(encoded.get());
            } else {
                failed++;
            }
        }
        tag.put(NBT_KEY, list);
        if (failed > 0) {
            BeyondEmc.LOGGER.warn("[BeyondEMC] 有 {} 项已学习物品无法序列化，已被跳过", failed);
        }
    }

    /** 从网络的存档 CompoundTag 读回学习集合。 */
    public static void readInto(@Nullable DimensionsNet net, @NotNull CompoundTag tag) {
        if (net == null || !tag.contains(NBT_KEY)) {
            return;
        }
        ListTag list = tag.getList(NBT_KEY, Tag.TAG_COMPOUND);
        Set<ItemInfo> set = new HashSet<>();
        int failed = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            var decoded = ItemInfo.CODEC.parse(NbtOps.INSTANCE, entry).result();
            if (decoded.isPresent()) {
                set.add(decoded.get());
            } else {
                failed++;
            }
        }
        synchronized (STORE) {
            STORE.put(net, set);
        }
        BeyondEmc.LOGGER.info("[BeyondEMC] 读回网络 {} 的已学习物品 {} 项{}",
                net.getId(), set.size(), failed > 0 ? "（跳过 " + failed + " 项无法解析的）" : "");
    }
}
