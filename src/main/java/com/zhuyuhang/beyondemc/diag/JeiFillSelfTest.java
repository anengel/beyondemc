package com.zhuyuhang.beyondemc.diag;

import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.client.ClientKnowledgeCache;
import com.zhuyuhang.beyondemc.emc.EmcStackKey;
import com.zhuyuhang.beyondemc.exchange.EmcAvailabilityInjector;
import com.zhuyuhang.beyondemc.exchange.KnowledgeSyncPacket;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 0.2.0-B 自检：**JEI 配方填充用的"可兑换可用量"注入**。
 *
 * <h2>为什么这一组能无头验证</h2>
 * JEI 的类在没装 JEI 时不存在，所以引用 JEI 的 Mixin 无法在无头环境验证。
 * 这正是把逻辑抽到 {@link EmcAvailabilityInjector}（**不引用 JEI**）的原因：
 * 它只依赖 BD 的 {@code KeyAmount} 与我们自己的知识缓存，因此可以在专用服务器上直接断言。
 *
 * <h2>这组自检守住什么</h2>
 * <ul>
 *   <li>虚拟可用量 = {@code floor(网络EMC ÷ 服务端单价)}，与服务端铸造用的是同一个公式；</li>
 *   <li>**有真实库存的物品不重复注入**（否则 JEI 会用虚拟量去填本该由真实库存填的槽位）；</li>
 *   <li>余额为 0 / 没学会 / 无价格 → **原样返回入参**，绝不凭空造出可用量；</li>
 *   <li>**不修改入参列表**（入参是 BD 客户端存储的真实镜像，改了会污染它）。</li>
 * </ul>
 */
public final class JeiFillSelfTest {

    private JeiFillSelfTest() {
    }

    /** 造一个假的"客户端网络存储列表"：EMC 余额 + 若干真实物品。 */
    private static List<KeyAmount> storageWith(long emc, ItemStack... items) {
        List<KeyAmount> list = new ArrayList<>();
        if (emc > 0) {
            list.add(new KeyAmount(EmcStackKey.INSTANCE, emc));
        }
        for (ItemStack s : items) {
            list.add(new KeyAmount(new ItemStackKey(s), s.getCount()));
        }
        return list;
    }

    private static void learn(ItemInfo info, long unitPrice) {
        // 第三个参数 serverMaterialized=false：本自检模拟的是"客户端自己注入可用量"的场景，
        // 与服务端物化条目无关（物化条目走的是另一条路径，见 VirtualEntryProvider.inject 的让位分支）。
        ClientKnowledgeCache.accept(0, List.of(new KnowledgeSyncPacket.LearnedEntry(info, unitPrice)), false);
    }

    public static List<String> run() {
        List<String> out = new ArrayList<>();
        int ok = 0;

        // ⚠️ 自检期间必须静默注入器的日志：否则自检的合成数据（余额 819200 等）
        // 会打出与真实 JEI 交互一模一样的"JEI 可用池注入"行，
        // 让"功能到底有没有生效"无法判断（0.2.0-B 首轮实测就栽在这里）。
        EmcAvailabilityInjector.setQuiet(true);

        final ItemInfo diamond = ItemInfo.fromStack(new ItemStack(Items.DIAMOND));
        final ItemInfo emerald = ItemInfo.fromStack(new ItemStack(Items.EMERALD));

        // ---- 1. 余额足够：按 floor(余额 ÷ 单价) 追加虚拟可用量 ----
        try {
            ClientKnowledgeCache.clear();
            learn(diamond, 8192L);           // 记 1 项：钻石 @8192
            ClientKnowledgeCache.add(0, new KnowledgeSyncPacket.LearnedEntry(emerald, 16384L));

            List<KeyAmount> base = storageWith(1572864L);   // 只有 EMC，没有真实物品
            List<KeyAmount> injected = EmcAvailabilityInjector.withEmcAvailability(base);

            long diamondAvail = find(injected, new ItemStack(Items.DIAMOND));
            long emeraldAvail = find(injected, new ItemStack(Items.EMERALD));

            // 1572864 / 8192 = 192 ；1572864 / 16384 = 96
            if (diamondAvail == 192L && emeraldAvail == 96L) {
                out.add("OK   JEI 可用量注入：余额 1572864 → 钻石可用 192（÷8192）、绿宝石可用 96（÷16384）");
                ok++;
            } else {
                out.add("FAIL JEI 可用量注入：钻石=" + diamondAvail + "（期望 192） 绿宝石="
                        + emeraldAvail + "（期望 96）");
            }
        } catch (Throwable t) {
            out.add("FAIL JEI 可用量注入：" + t);
        }

        // ---- 2. 有真实库存的物品不重复注入 ----
        try {
            ClientKnowledgeCache.clear();
            learn(diamond, 8192L);
            List<KeyAmount> base = storageWith(819200L, new ItemStack(Items.DIAMOND, 5));
            List<KeyAmount> injected = EmcAvailabilityInjector.withEmcAvailability(base);

            long count = countOf(injected, new ItemStack(Items.DIAMOND));
            long avail = find(injected, new ItemStack(Items.DIAMOND));
            // 真实条目只有 1 条（数量 5），不应再多出一条虚拟条目（100 = 819200/8192）
            if (count == 1L && avail == 5L) {
                out.add("OK   有真实库存不重复注入：钻石只保留真实条目 1 条（数量 5），未追加虚拟条目");
                ok++;
            } else {
                out.add("FAIL 有真实库存不重复注入：条目数=" + count + "（期望 1） 数量=" + avail + "（期望 5）");
            }
        } catch (Throwable t) {
            out.add("FAIL 有真实库存不重复注入：" + t);
        }

        // ---- 3. 余额不足 / 无价格 → 不注入（绝不凭空造可用量）----
        try {
            ClientKnowledgeCache.clear();
            learn(diamond, 8192L);
            ClientKnowledgeCache.add(0, new KnowledgeSyncPacket.LearnedEntry(emerald, 0L)); // 无价格

            List<KeyAmount> noEmc = storageWith(1000L);      // 1000/8192 = 0
            List<KeyAmount> zero = storageWith(0L);          // 没有 EMC 条目

            long a = find(EmcAvailabilityInjector.withEmcAvailability(noEmc), new ItemStack(Items.DIAMOND));
            long b = find(EmcAvailabilityInjector.withEmcAvailability(zero), new ItemStack(Items.DIAMOND));
            long c = find(EmcAvailabilityInjector.withEmcAvailability(storageWith(999999999L)),
                    new ItemStack(Items.EMERALD));

            if (a == 0L && b == 0L && c == 0L) {
                out.add("OK   不凭空造可用量：买不起（1000<8192）/ 无 EMC / 无价格 三种情况都不注入");
                ok++;
            } else {
                out.add("FAIL 不凭空造可用量：a=" + a + " b=" + b + " c=" + c + "（期望全为 0）");
            }
        } catch (Throwable t) {
            out.add("FAIL 不凭空造可用量：" + t);
        }

        // ---- 4. ★ 不修改入参（入参是 BD 客户端存储的真实镜像）----
        try {
            ClientKnowledgeCache.clear();
            learn(diamond, 8192L);
            List<KeyAmount> base = storageWith(819200L);
            int before = base.size();
            List<KeyAmount> injected = EmcAvailabilityInjector.withEmcAvailability(base);
            int after = base.size();

            if (before == after && injected != base && injected.size() == before + 1) {
                out.add("OK   不改入参：原列表仍为 " + before + " 条（未被污染），"
                        + "返回的是新列表（" + injected.size() + " 条）");
                ok++;
            } else {
                out.add("FAIL 不改入参：原列表 " + before + " → " + after
                        + "（必须不变），返回列表大小=" + injected.size());
            }
        } catch (Throwable t) {
            out.add("FAIL 不改入参：" + t);
        }

        // ---- 5. 未收到知识同步时安全放行 ----
        try {
            ClientKnowledgeCache.clear();
            List<KeyAmount> base = storageWith(819200L);
            List<KeyAmount> same = EmcAvailabilityInjector.withEmcAvailability(base);
            if (same == base) {
                out.add("OK   无知识数据时原样返回：界面/配方转移照常工作，不受本功能影响");
                ok++;
            } else {
                out.add("FAIL 无知识数据时原样返回：返回了不同的列表");
            }
        } catch (Throwable t) {
            out.add("FAIL 无知识数据时原样返回：" + t);
        }

        ClientKnowledgeCache.clear(); // 自检不留状态给后续逻辑
        EmcAvailabilityInjector.setQuiet(false); // 恢复生产日志
        out.add("---- JEI 配方填充可用量自检结果：" + ok + "/5 项通过 ----");
        return out;
    }

    /** 该物品在列表里的可用总数（0 表示没有条目）。 */
    private static long find(List<KeyAmount> list, ItemStack item) {
        ItemStackKey key = new ItemStackKey(item);
        long sum = 0L;
        for (KeyAmount ka : list) {
            if (ka != null && ka.key() instanceof ItemStackKey isk && isk.equals(key)) {
                sum += ka.amount();
            }
        }
        return sum;
    }

    private static long countOf(List<KeyAmount> list, ItemStack item) {
        ItemStackKey key = new ItemStackKey(item);
        long n = 0L;
        for (KeyAmount ka : list) {
            if (ka != null && ka.key() instanceof ItemStackKey isk && isk.equals(key)) {
                n++;
            }
        }
        return n;
    }
}
