package com.zhuyuhang.beyondemc.diag;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeInsertHandler;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.core.LoadingGuard;
import com.zhuyuhang.beyondemc.emc.EmcStackKey;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 3 的诊断自检：折算钩子的各个安全门 + 网络学习集合。
 *
 * <p><b>为什么用 {@code UnifiedStorageBeforeInsertHandler.onBeforeInsert} 而不是真的 insert</b>：
 * 那是 BD 的公开静态入口，会按真实顺序链式调用全部已注册 handler（我们就是其中之一），
 * 因此能端到端验证钩子的路由与门禁，而不需要一个真实世界里的网络。
 *
 * <p><b>关于 EMC 表就绪</b>：阶段 1 已实测 —— 专用服务器上无玩家时 EMC 表**永远为空**
 * （{@code EMCRemapEvent} 不触发）。所以本自检对"是否折算"的断言是<b>自适应</b>的：
 * 未就绪时断言"原样通过"，就绪时断言"折算成 EMC"。
 * 也就是说在无头服务器上它验证门禁，在玩家已登录的环境里它验证完整折算。
 */
public final class Phase3SelfTest {

    private Phase3SelfTest() {
    }

    public static List<String> run(net.minecraft.core.RegistryAccess registryAccess) {
        List<String> out = new ArrayList<>();
        int ok = 0;

        // 每个测试都用独立的临时网络，避免互相污染
        // （DimensionsNet(true) 的 temporary 标志让 onServerTick 直接 return，无副作用）
        DimensionsNet net = new DimensionsNet(true);
        ItemStackKey diamondKey = new ItemStackKey(new ItemStack(Items.DIAMOND));
        KeyAmount diamond64 = new KeyAmount(diamondKey, 64L);

        // ---- 1. 钩子路由：这些输入必须原样通过（与 EMC 表是否就绪无关）----
        try {
            boolean good = true;
            StringBuilder detail = new StringBuilder();

            // (a) net == null（UnifiedStorage.getEmpty() 的壳）
            var rNull = UnifiedStorageBeforeInsertHandler.onBeforeInsert(diamond64, null);
            boolean nullOk = rNull.cancel() || same(rNull.beforeInsert(), diamond64);
            good &= nullOk;
            detail.append("net=null:").append(nullOk ? "OK" : "FAIL(" + rNull + ")");

            // (b) 非物品资源（EMC 自己送进来）
            KeyAmount emcInput = new KeyAmount(EmcStackKey.INSTANCE, 5000L);
            var rEmc = UnifiedStorageBeforeInsertHandler.onBeforeInsert(emcInput, net);
            boolean emcOk = !rEmc.cancel() && same(rEmc.beforeInsert(), emcInput);
            good &= emcOk;
            detail.append(" 非物品资源:").append(emcOk ? "OK" : "FAIL(" + rEmc + ")");

            // (c) 空堆叠
            var rEmpty = UnifiedStorageBeforeInsertHandler.onBeforeInsert(
                    new KeyAmount(ItemStackKey.EMPTY, 0L), net);
            boolean emptyOk = rEmpty.cancel() || rEmpty.beforeInsert().isEmpty();
            good &= emptyOk;
            detail.append(" 空堆叠:").append(emptyOk ? "OK" : "FAIL");

            if (good) {
                out.add("OK   钩子路由：net=null / 非物品资源 / 空堆叠 三类输入均原样通过（" + detail + "）");
                ok++;
            } else {
                out.add("FAIL 钩子路由：" + detail);
            }
        } catch (Throwable t) {
            out.add("FAIL 钩子路由：" + t);
        }

        // ---- 2. 读档守卫：LoadingGuard 打开时不折算 ----
        try {
            LoadingGuard.enter();
            KeyAmount result;
            try {
                result = UnifiedStorageBeforeInsertHandler.onBeforeInsert(diamond64, net).beforeInsert();
            } finally {
                LoadingGuard.exit();
            }
            if (same(result, diamond64) && !LoadingGuard.isLoading()) {
                out.add("OK   读档守卫：加载中不折算（返回仍是物品），且退出后标志已复位");
                ok++;
            } else {
                out.add("FAIL 读档守卫：加载中返回 " + result + "，退出后 isLoading=" + LoadingGuard.isLoading());
            }
        } catch (Throwable t) {
            out.add("FAIL 读档守卫：" + t);
        }

        // ---- 3. 折算门禁 / 完整折算（按 EMC 表当前状态自适应断言）----
        try {
            var r = UnifiedStorageBeforeInsertHandler.onBeforeInsert(diamond64, net);
            KeyAmount after = r.beforeInsert();

            if (!EmcAvailability.isReady()) {
                // 未就绪：必须原样通过（这是无头服务器上的常态）
                if (same(after, diamond64)) {
                    out.add("OK   折算门禁：EMC 表未就绪（remap 次数=" + EmcAvailability.remapCount()
                            + "）→ 不折算，物品原样入库");
                    ok++;
                } else {
                    out.add("FAIL 折算门禁：EMC 表未就绪却改写了堆叠 -> " + after);
                }
            } else {
                // 已就绪：必须折算成 EMC，且数量 = 回收价 × 64
                long sell = IEMCProxy.INSTANCE.getSellValue(ItemInfo.fromItem(Items.DIAMOND));
                long expected = NetEmcAccessor.saturatingMultiply(sell, 64L);
                boolean converted = !r.cancel()
                        && after.key() == EmcStackKey.INSTANCE
                        && after.amount() == expected;
                boolean learned = NetKnowledgeStore.knows(net,
                        IEMCProxy.INSTANCE.getPersistentInfo(ItemInfo.fromItem(Items.DIAMOND)));
                if (converted && learned) {
                    out.add("OK   完整折算：钻石 ×64 → " + after.amount() + " EMC（回收单价 " + sell
                            + "），且已并入网络学习集合");
                    ok++;
                } else {
                    out.add("FAIL 完整折算：converted=" + converted + " learned=" + learned
                            + " 实际=" + after + " 期望=" + expected);
                }
            }
        } catch (Throwable t) {
            out.add("FAIL 折算门禁/完整折算：" + t);
        }

        // ---- 4. 学习集合的增删查 ----
        try {
            DimensionsNet kNet = new DimensionsNet(true);
            ItemInfo info = ItemInfo.fromItem(Items.DIAMOND);

            boolean before = NetKnowledgeStore.knows(kNet, info);
            boolean learned1 = NetKnowledgeStore.learn(kNet, info);
            boolean learned2 = NetKnowledgeStore.learn(kNet, info); // 重复学习应为 false
            boolean after = NetKnowledgeStore.knows(kNet, info);
            int size = NetKnowledgeStore.snapshot(kNet).size();

            NetKnowledgeStore.clear(kNet);
            boolean clearedOk = !NetKnowledgeStore.knows(kNet, info);

            if (!before && learned1 && !learned2 && after && size == 1 && clearedOk) {
                out.add("OK   学习集合：未学→学会(true)→重复学(false)→可查→size=1→清空生效");
                ok++;
            } else {
                out.add("FAIL 学习集合：before=" + before + " learn1=" + learned1 + " learn2=" + learned2
                        + " after=" + after + " size=" + size + " cleared=" + clearedOk);
            }
        } catch (Throwable t) {
            out.add("FAIL 学习集合：" + t);
        }

        // ---- 5. 学习集合的 NBT 往返（决定重进存档后列表还在不在）----
        try {
            DimensionsNet src = new DimensionsNet(true);
            NetKnowledgeStore.learn(src, ItemInfo.fromItem(Items.DIAMOND));
            NetKnowledgeStore.learn(src, ItemInfo.fromItem(Items.EMERALD));
            NetKnowledgeStore.learn(src, ItemInfo.fromItem(Items.NETHERITE_INGOT));

            CompoundTag tag = new CompoundTag();
            NetKnowledgeStore.writeTo(tag, src);

            DimensionsNet dst = new DimensionsNet(true);
            NetKnowledgeStore.readInto(dst, tag);

            var restored = NetKnowledgeStore.snapshot(dst);
            boolean good = restored.size() == 3
                    && restored.contains(ItemInfo.fromItem(Items.DIAMOND))
                    && restored.contains(ItemInfo.fromItem(Items.EMERALD))
                    && restored.contains(ItemInfo.fromItem(Items.NETHERITE_INGOT));
            if (good) {
                out.add("OK   学习集合 NBT 往返：3 项完整还原，NBT 键=" + NetKnowledgeStore.NBT_KEY);
                ok++;
            } else {
                out.add("FAIL 学习集合 NBT 往返：还原出 " + restored.size() + " 项: " + restored);
            }
        } catch (Throwable t) {
            out.add("FAIL 学习集合 NBT 往返：" + t);
        }

        // ---- 6. 网络合并时学习集合并 ----
        try {
            DimensionsNet a = new DimensionsNet(true);
            DimensionsNet b = new DimensionsNet(true);
            NetKnowledgeStore.learn(a, ItemInfo.fromItem(Items.DIAMOND));
            NetKnowledgeStore.learn(b, ItemInfo.fromItem(Items.EMERALD));
            NetKnowledgeStore.merge(a, b);

            var merged = NetKnowledgeStore.snapshot(a);
            if (merged.size() == 2 && merged.contains(ItemInfo.fromItem(Items.DIAMOND))
                    && merged.contains(ItemInfo.fromItem(Items.EMERALD))) {
                out.add("OK   学习集合并：两个网络各 1 项，合并后 2 项");
                ok++;
            } else {
                out.add("FAIL 学习集合并：合并后 " + merged);
            }
        } catch (Throwable t) {
            out.add("FAIL 学习集合并：" + t);
        }

        // ---- 7. LoadingGuard 的嵌套与异常复位语义 ----
        try {
            boolean initially = !LoadingGuard.isLoading();
            LoadingGuard.enter();
            LoadingGuard.enter();
            boolean nested = LoadingGuard.isLoading();
            LoadingGuard.exit();
            boolean stillNested = LoadingGuard.isLoading(); // 还有一层，必须仍为 true
            LoadingGuard.exit();
            boolean reset = !LoadingGuard.isLoading();

            boolean good = initially && nested && stillNested && reset;
            if (good) {
                out.add("OK   LoadingGuard：嵌套计数正确（enter→true, 内层 exit 后仍 true, 外层 exit 后复位）");
                ok++;
            } else {
                out.add("FAIL LoadingGuard：initially=" + initially + " nested=" + nested
                        + " stillNested=" + stillNested + " reset=" + reset);
            }
        } catch (Throwable t) {
            out.add("FAIL LoadingGuard：" + t);
        }

        // ---- 8. 读档守卫的 Mixin 是否真的挂上了（最关键的一项）----
        //
        // 第 2 项只证明了 LoadingGuard 自身的语义，无法证明 Mixin 生效。
        // 若 AbstractUnorderedStorageLoadMixin 注入静默失效，标志永远不会被置起，
        // 于是"读档重写存档"这个数据损坏问题会回来，而所有自检依然是绿的。
        // 这里真的做一次存储反序列化，用 enterCount 的增量来证明 Mixin 被调用过。
        try {
            DimensionsNet src = new DimensionsNet(true);
            NetEmcAccessor.addEmc(src, 4242L);
            CompoundTag tag = src.getUnifiedStorage().serializeNBT(registryAccess);

            long before = LoadingGuard.enterCount();
            DimensionsNet dst = new DimensionsNet(true);
            dst.getUnifiedStorage().deserializeNBT(registryAccess, tag);
            long after = LoadingGuard.enterCount();

            boolean firedAndReset = after > before && !LoadingGuard.isLoading();
            boolean valueOk = NetEmcAccessor.getEmc(dst) == 4242L;

            if (firedAndReset && valueOk) {
                out.add("OK   读档守卫 Mixin 已生效：反序列化期间 enter() 被调用 " + (after - before)
                        + " 次，退出后标志复位，且数据完整（EMC=" + NetEmcAccessor.getEmc(dst) + "）");
                ok++;
            } else {
                out.add("FAIL 读档守卫 Mixin：enter 次数 " + before + " -> " + after
                        + "，退出后 isLoading=" + LoadingGuard.isLoading() + "，数据正确=" + valueOk
                        + "（若 enter 没增加，说明注入没生效 —— 这会导致读档重写存档）");
            }
        } catch (Throwable t) {
            out.add("FAIL 读档守卫 Mixin：" + t);
        }

        out.add("---- 阶段 3 自检结果：" + ok + "/8 项通过 ----");
        return out;
    }

    private static boolean same(KeyAmount a, KeyAmount b) {
        return a.key() == b.key() && a.amount() == b.amount();
    }
}
