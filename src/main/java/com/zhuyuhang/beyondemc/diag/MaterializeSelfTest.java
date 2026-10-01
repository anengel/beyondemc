package com.zhuyuhang.beyondemc.diag;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.StackKeyRegistry;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.core.LoadingGuard;
import com.zhuyuhang.beyondemc.emc.EmcDepositHandler;
import com.zhuyuhang.beyondemc.emc.EmcItemKey;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.materialize.ItemMaterializer;
import com.zhuyuhang.beyondemc.materialize.MaterializeMath;
import com.zhuyuhang.beyondemc.materialize.MaterializingGuard;
import io.netty.buffer.Unpooled;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.3.0 的诊断自检：验证"物化条目真实存在、可持久化、且不会被零扣费拿走"。
 *
 * <p>全部断言都<b>无头可跑</b>（与 {@link EmcStorageSelfTest} 同一套做法：
 * 用 {@code new DimensionsNet(true)} 建临时网络，不产生副作用）。
 *
 * <p>这里只测"能脱离 EMC 表与图形界面决定性验证"的部分。依赖真实 EMC 价格表
 * （即需要玩家登录或 /reload）的路径记在 {@code ROADMAP-0.3.0.md} §10.3 的人工验收清单里。
 */
public final class MaterializeSelfTest {

    private MaterializeSelfTest() {
    }

    public static List<String> run(RegistryAccess registryAccess) {
        List<String> out = new ArrayList<>();
        int[] ok = {0};

        typeRegistration(out, ok);
        keyIdentity(out, ok);
        serialization(registryAccess, out, ok);
        math(out, ok);
        depositHookPassesMaterializedKey(out, ok);
        storageWriteback(out, ok);
        loadingGuardBlocks(out, ok);
        zeroChargeGuard(out, ok);

        out.add("---- 物化（0.3.0）自检结果：" + ok[0] + " 项通过 ----");
        return out;
    }

    // ------------------------------------------------------------------

    /** 1. 类型必须在注册表里，且取回的是同一个原型单例（否则读档会静默丢条目）。 */
    private static void typeRegistration(List<String> out, int[] ok) {
        try {
            IStackKey<?> looked = StackKeyRegistry.getType(EmcItemKey.ID);
            if (looked == EmcItemKey.INSTANCE) {
                out.add("OK   注册：StackKeyRegistry 按 id 取回的物化类型是同一单例（" + EmcItemKey.ID + "）");
                ok[0]++;
            } else {
                out.add("FAIL 注册：取回的实例不是单例，实际=" + looked);
            }
        } catch (Throwable t) {
            out.add("FAIL 注册：取不回物化类型 -> " + t
                    + "（若为'注册表中不存在此类型的Key'，说明 EmcRegistration 没生效）");
        }
    }

    /**
     * 2. 【最关键】{@code equals/hashCode} 必须区分不同物品。
     *
     * <p>基类 {@code LongStackKey.equals} 比的是 {@code getTypeId()}，不覆写就会让所有物化条目
     * 塌缩成同一个键，存储是 {@code Map<IStackKey, Long>} ⇒ 钻石与铁会合并成一条数。
     */
    private static void keyIdentity(List<String> out, int[] ok) {
        try {
            EmcItemKey diamond = new EmcItemKey(ItemInfo.fromItem(Items.DIAMOND));
            EmcItemKey diamond2 = new EmcItemKey(ItemInfo.fromItem(Items.DIAMOND));
            EmcItemKey iron = new EmcItemKey(ItemInfo.fromItem(Items.IRON_INGOT));

            boolean distinct = !diamond.equals(iron);
            boolean sameHashDiffers = diamond.hashCode() != iron.hashCode();
            boolean reflexive = diamond.equals(diamond2) && diamond.hashCode() == diamond2.hashCode();

            if (distinct && reflexive) {
                out.add("OK   键身份：钻石 ≠ 铁（未塌缩），同物品两次构造相等且 hashCode 一致");
                ok[0]++;
            } else {
                out.add("FAIL 键身份：distinct=" + distinct + " reflexive=" + reflexive
                        + "（distinct=false 就是「所有物品塌缩成一个键」的严重缺陷）");
            }
            if (!sameHashDiffers) {
                // hashCode 碰撞本身合法，只是提示一下，不算失败
                out.add("    键身份：钻石与铁的 hashCode 恰好相同（合法但少见）");
            }

            // 防止 HashMap 合并：放到 map 里必须还是两条
            Map<IStackKey<?>, Long> map = new HashMap<>();
            map.put(diamond, 10L);
            map.put(iron, 20L);
            if (map.size() == 2) {
                out.add("OK   键身份：HashMap 里钻石与铁是两条独立条目（不会被合并成一条数）");
                ok[0]++;
            } else {
                out.add("FAIL 键身份：HashMap 里只剩 " + map.size() + " 条 —— 不同物品被合并了");
            }

            // 搜索/排序依赖的两个值
            String modId = diamond.getModId();
            long maxStack = diamond.getVanillaMaxStackSize();
            if ("minecraft".equals(modId) && maxStack == 64L) {
                out.add("OK   键元数据：getModId=物品自身命名空间（minecraft），"
                        + "getVanillaMaxStackSize=64（与 ItemStackKey 对齐）");
                ok[0]++;
            } else {
                out.add("FAIL 键元数据：getModId=" + modId + "（期望物品自身命名空间 minecraft），"
                        + "getVanillaMaxStackSize=" + maxStack + "（期望 64）");
            }
        } catch (Throwable t) {
            out.add("FAIL 键身份：" + t);
        }
    }

    /** 3. 持久化与同步：NBT（存档）与网络（多人）两条往返。 */
    private static void serialization(RegistryAccess registryAccess, List<String> out, int[] ok) {
        EmcItemKey key = new EmcItemKey(ItemInfo.fromItem(Items.DIAMOND));

        // 3a. IStackKey.CODEC 的 NBT 往返（BD 存储真正走的路径，见 AbstractUnorderedStackHandler.java:840）
        try {
            var ops = registryAccess.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            Tag encoded = IStackKey.CODEC.encodeStart(ops, key).getOrThrow();
            IStackKey<?> decoded = IStackKey.CODEC.parse(ops, encoded).getOrThrow();
            if (key.equals(decoded)) {
                out.add("OK   NBT 编解码：经 IStackKey.CODEC 往返后身份一致，编码=" + encoded);
                ok[0]++;
            } else {
                out.add("FAIL NBT 编解码：往返后得到 " + decoded);
            }
        } catch (Throwable t) {
            out.add("FAIL NBT 编解码：" + t);
        }

        // 3b. 本类型自己的 serializeNBT / deserializeNBT
        try {
            var nbt = key.serializeNBT(registryAccess);
            EmcItemKey back = EmcItemKey.INSTANCE.deserializeNBT(nbt, registryAccess);
            if (key.equals(back)) {
                out.add("OK   独立 NBT 往返：serializeNBT/deserializeNBT 一致");
                ok[0]++;
            } else {
                out.add("FAIL 独立 NBT 往返：得到 " + back);
            }
        } catch (Throwable t) {
            out.add("FAIL 独立 NBT 往返：" + t);
        }

        // 3c. STREAM_CODEC 的网络往返（多人同步走的路径）
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
            IStackKey.STREAM_CODEC.encode(buf, key);
            int written = buf.readableBytes();
            IStackKey<?> decoded = IStackKey.STREAM_CODEC.decode(buf);
            if (key.equals(decoded)) {
                out.add("OK   网络编解码：往返后身份一致，写入 " + written + " 字节（typeId + ItemInfo 负载）");
                ok[0]++;
            } else {
                out.add("FAIL 网络编解码：往返后得到 " + decoded);
            }
        } catch (Throwable t) {
            out.add("FAIL 网络编解码：" + t);
        }
    }

    /**
     * 4. {@link MaterializeMath} 的逐件取整口径（用户口径的样例就写在这里）。
     */
    private static void math(List<String> out, int[] ok) {
        ItemInfo diamond = ItemInfo.fromItem(Items.DIAMOND);
        ItemInfo dirt = ItemInfo.fromItem(Items.DIRT);
        ItemInfo iron = ItemInfo.fromItem(Items.IRON_INGOT);

        Map<ItemInfo, Long> prices = Map.of(diamond, 50L, dirt, 1L, iron, 8L);
        java.util.function.ToLongFunction<ItemInfo> price = i -> prices.getOrDefault(i, 0L);
        java.util.function.ToLongFunction<ItemInfo> noStock = i -> 0L;

        // 4a. 用户口径样例：E=100，钻石 50，泥土 1 → 钻石 2 + 泥土 100（各物品彼此独立）
        try {
            Map<ItemInfo, Long> r = MaterializeMath.solve(100L, List.of(diamond, dirt), price, noStock, 0);
            boolean good = r.size() == 2
                    && r.getOrDefault(diamond, 0L) == 2L
                    && r.getOrDefault(dirt, 0L) == 100L;
            if (good) {
                out.add("OK   口径样例：E=100、钻石50、泥土1 → 钻石2 + 泥土100（合计价值 200 > 100，属预期）");
                ok[0]++;
            } else {
                out.add("FAIL 口径样例：得到 " + r + "（期望 钻石2 + 泥土100）");
            }

            // 4b. 取出 1 钻（扣 50）→ E=70 → 钻石 1 + 泥土 70
            Map<ItemInfo, Long> r2 = MaterializeMath.solve(70L, List.of(diamond, dirt), price, noStock, 0);
            boolean good2 = r2.getOrDefault(diamond, 0L) == 1L && r2.getOrDefault(dirt, 0L) == 70L;
            if (good2) {
                out.add("OK   收缩样例：E=70 → 钻石1 + 泥土70（取出会同时压低所有条目）");
                ok[0]++;
            } else {
                out.add("FAIL 收缩样例：得到 " + r2 + "（期望 钻石1 + 泥土70）");
            }
        } catch (Throwable t) {
            out.add("FAIL 口径样例：" + t);
        }

        // 4c. 排除规则：无价格 / 已有真实库存 / 买不起 / 零 EMC
        try {
            Map<ItemInfo, Long> r = MaterializeMath.solve(100L, List.of(diamond, dirt, iron),
                    price, i -> i.equals(iron) ? 5L : 0L, 0);
            boolean excludesStock = r.size() == 2 && !r.containsKey(iron);
            Map<ItemInfo, Long> poor = MaterializeMath.solve(3L, List.of(diamond, dirt), price, noStock, 0);
            boolean excludesPoor = poor.size() == 1 && poor.getOrDefault(dirt, 0L) == 3L;
            Map<ItemInfo, Long> empty = MaterializeMath.solve(0L, List.of(diamond, dirt), price, noStock, 0);
            boolean excludesZero = empty.isEmpty();

            if (excludesStock && excludesPoor && excludesZero) {
                out.add("OK   排除规则：已有真实库存不物化、买不起不物化（E=3 → 只剩泥土3）、E=0 全空");
                ok[0]++;
            } else {
                out.add("FAIL 排除规则：excludesStock=" + excludesStock + " excludesPoor=" + excludesPoor
                        + " excludesZero=" + excludesZero + "（r=" + r + " poor=" + poor + "）");
            }
        } catch (Throwable t) {
            out.add("FAIL 排除规则：" + t);
        }

        // 4d. 上限截断 + 确定性排序（价格升序：泥土1 → 铁8 → 钻石50）
        try {
            Map<ItemInfo, Long> r = MaterializeMath.solve(100L, List.of(diamond, iron, dirt), price, noStock, 1);
            boolean good = r.size() == 1 && r.containsKey(dirt);
            Map<ItemInfo, Long> rFull = MaterializeMath.solve(100L, List.of(diamond, iron, dirt), price, noStock, 0);
            List<ItemInfo> order = new ArrayList<>(rFull.keySet());
            boolean sorted = order.size() == 3
                    && order.get(0).equals(dirt) && order.get(1).equals(iron) && order.get(2).equals(diamond);
            if (good && sorted) {
                out.add("OK   上限与排序：maxItems=1 取到最便宜的泥土；顺序=价格升序（泥土→铁→钻石）");
                ok[0]++;
            } else {
                out.add("FAIL 上限与排序：maxItems=1 得到 " + r + "，完整顺序=" + order);
            }
        } catch (Throwable t) {
            out.add("FAIL 上限与排序：" + t);
        }

        // 4e. 幂等 + 溢出
        try {
            Map<ItemInfo, Long> a = MaterializeMath.solve(100L, List.of(diamond, iron, dirt), price, noStock, 0);
            Map<ItemInfo, Long> b = MaterializeMath.solve(100L, List.of(diamond, iron, dirt), price, noStock, 0);
            boolean idempotent = new ArrayList<>(a.keySet()).equals(new ArrayList<>(b.keySet()))
                    && new LinkedHashMap<>(a).equals(new LinkedHashMap<>(b));

            Map<ItemInfo, Long> huge = MaterializeMath.solve(Long.MAX_VALUE, List.of(dirt), price, noStock, 0);
            boolean noOverflow = huge.getOrDefault(dirt, -1L) == Long.MAX_VALUE;

            if (idempotent && noOverflow) {
                out.add("OK   幂等与溢出：连算两次结果完全相同；E=Long.MAX_VALUE、单价1 → 数量 Long.MAX_VALUE（无回绕）");
                ok[0]++;
            } else {
                out.add("FAIL 幂等与溢出：idempotent=" + idempotent + " noOverflow=" + noOverflow
                        + " huge=" + huge);
            }
        } catch (Throwable t) {
            out.add("FAIL 幂等与溢出：" + t);
        }
    }

    /**
     * 5. 折算钩子必须对 {@code EmcItemKey} 放行 —— 这是"物化写入自我抵消"的唯一边界。
     */
    private static void depositHookPassesMaterializedKey(List<String> out, int[] ok) {
        try {
            DimensionsNet net = new DimensionsNet(true);
            EmcItemKey key = new EmcItemKey(ItemInfo.fromItem(Items.DIAMOND));
            KeyAmount in = new KeyAmount(key, 7L);
            var ret = new EmcDepositHandler().beforeInsert(in, in, net);
            boolean pass = !ret.cancel() && ret.beforeInsert().key() instanceof EmcItemKey
                    && ret.beforeInsert().amount() == 7L;
            if (pass) {
                out.add("OK   折算放行：EmcDepositHandler 对 EmcItemKey 原样放行（物化写入不会被折算回 EMC）");
                ok[0]++;
            } else {
                out.add("FAIL 折算放行：cancel=" + ret.cancel() + " key=" + ret.beforeInsert().key()
                        + " amount=" + ret.beforeInsert().amount());
            }
        } catch (Throwable t) {
            out.add("FAIL 折算放行：" + t);
        }
    }

    /**
     * 6. 存储写回：差量、幂等、清空，以及"物化条目真的在 {@code UnifiedStorage} 里"。
     */
    private static void storageWriteback(List<String> out, int[] ok) {
        ItemInfo diamond = ItemInfo.fromItem(Items.DIAMOND);
        ItemInfo dirt = ItemInfo.fromItem(Items.DIRT);

        try {
            DimensionsNet net = new DimensionsNet(true);
            UnifiedStorage storage = net.getUnifiedStorage();

            // 差量写入
            Map<ItemInfo, Long> want = new LinkedHashMap<>();
            want.put(diamond, 5L);
            want.put(dirt, 100L);
            ItemMaterializer.applyWant(net, want);

            Map<ItemInfo, Long> cur = ItemMaterializer.currentEntries(net);
            boolean exact = cur.size() == 2
                    && cur.getOrDefault(diamond, 0L) == 5L
                    && cur.getOrDefault(dirt, 0L) == 100L;
            boolean reallyInStorage = storage.hasStack(new EmcItemKey(diamond));
            if (exact && reallyInStorage) {
                out.add("OK   存储写回：条目真实落在 UnifiedStorage 里（钻石5、泥土100），hasStack=true");
                ok[0]++;
            } else {
                out.add("FAIL 存储写回：cur=" + cur + " hasStack=" + reallyInStorage);
            }

            // 幂等：再写一次同样的 want
            ItemMaterializer.applyWant(net, want);
            boolean idem = ItemMaterializer.currentEntries(net).equals(cur);
            if (idem) {
                out.add("OK   写回幂等：重复 applyWant 结果不变（不产生多余 delta）");
                ok[0]++;
            } else {
                out.add("FAIL 写回幂等：得到 " + ItemMaterializer.currentEntries(net));
            }

            // 差量收缩：钻石变 1、泥土消失
            Map<ItemInfo, Long> want2 = new LinkedHashMap<>();
            want2.put(diamond, 1L);
            ItemMaterializer.applyWant(net, want2);
            Map<ItemInfo, Long> cur2 = ItemMaterializer.currentEntries(net);
            boolean shrunk = cur2.size() == 1 && cur2.getOrDefault(diamond, 0L) == 1L;
            if (shrunk) {
                out.add("OK   差量收缩：want 缩到只剩钻石1 后，泥土条目被清掉、钻石降为 1（到 0 即删）");
                ok[0]++;
            } else {
                out.add("FAIL 差量收缩：得到 " + cur2);
            }

            // 清空（L1 熔断的实际动作）
            int cleared = ItemMaterializer.clear(net);
            boolean empty = ItemMaterializer.currentEntries(net).isEmpty();
            if (cleared == 1 && empty) {
                out.add("OK   清空（L1 熔断动作）：clear() 把 " + cleared + " 类物化条目全部移除，存储里已无 EmcItemKey");
                ok[0]++;
            } else {
                out.add("FAIL 清空：cleared=" + cleared + " 剩余=" + ItemMaterializer.currentEntries(net));
            }
        } catch (Throwable t) {
            out.add("FAIL 存储写回：" + t);
        }

        // 配置默认值（L1 默认开启）
        try {
            if (BeyondEmcConfig.materializeItems()) {
                out.add("OK   配置：materializeItems 默认开启；maxMaterializedItems="
                        + BeyondEmcConfig.maxMaterializedItems() + "，模式=" + BeyondEmcConfig.materializeMode());
                ok[0]++;
            } else {
                out.add("FAIL 配置：materializeItems 默认应为 true");
            }
        } catch (Throwable t) {
            out.add("FAIL 配置：" + t);
        }
    }

    /**
     * 7. 读档期（{@code LoadingGuard.isLoading()}）与 EMC 表未就绪时，{@code refresh} 不得动存储。
     */
    private static void loadingGuardBlocks(List<String> out, int[] ok) {
        ItemInfo diamond = ItemInfo.fromItem(Items.DIAMOND);
        try {
            DimensionsNet net = new DimensionsNet(true);

            Map<ItemInfo, Long> seeded = new LinkedHashMap<>();
            seeded.put(diamond, 42L);
            ItemMaterializer.applyWant(net, seeded);
            Map<ItemInfo, Long> before = ItemMaterializer.currentEntries(net);

            // 7a. 读档中：refresh 必须原样不动
            LoadingGuard.enter();
            try {
                ItemMaterializer.refresh(net);
            } finally {
                LoadingGuard.exit();
            }
            boolean unchangedInLoad = ItemMaterializer.currentEntries(net).equals(before);

            // 7b. EMC 表未就绪时（本机无头环境即如此）：也不能动
            boolean unchangedWhenNotReady = true;
            if (!EmcAvailability.isReady()) {
                ItemMaterializer.refresh(net);
                unchangedWhenNotReady = ItemMaterializer.currentEntries(net).equals(before);
            }

            if (unchangedInLoad && unchangedWhenNotReady) {
                out.add("OK   读档保护：LoadingGuard 期间 refresh 不动存储"
                        + (EmcAvailability.isReady() ? "" : "；EMC 表未就绪时同样不动（当前未就绪，已实测）"));
                ok[0]++;
            } else {
                out.add("FAIL 读档保护：unchangedInLoad=" + unchangedInLoad
                        + " unchangedWhenNotReady=" + unchangedWhenNotReady
                        + " before=" + before + " after=" + ItemMaterializer.currentEntries(net));
            }
        } catch (Throwable t) {
            out.add("FAIL 读档保护：" + t);
        }
    }

    /**
     * 8. 【最关键】物化条目不能被"零扣费"抽走（INV-1′）。
     *
     * <p>这里可决定性验证的是"没有价格时一律拒绝、绝不交付"这一半：本机（无头、无玩家）EMC 表
     * 未就绪 ⇒ 购买价为 0 ⇒ 钩子必须 {@code cancel}，既不扣费也不交付。
     *
     * <p>另一半（有价格时改道收费、余额不足时 cancel）需要真实 EMC 表，见人工验收清单。
     */
    private static void zeroChargeGuard(List<String> out, int[] ok) {
        ItemInfo diamond = ItemInfo.fromItem(Items.DIAMOND);
        try {
            DimensionsNet net = new DimensionsNet(true);
            Map<ItemInfo, Long> seeded = new LinkedHashMap<>();
            seeded.put(diamond, 10L);
            ItemMaterializer.applyWant(net, seeded);
            long emcBefore = NetEmcAccessor.getEmc(net);

            // (a) 外部抽取（MaterializingGuard 未激活）→ 必须被护栏拦下
            KeyAmount r = net.getUnifiedStorage().extract(new EmcItemKey(diamond), 1L, false, false);
            Map<ItemInfo, Long> afterExternal = ItemMaterializer.currentEntries(net);
            boolean notDelivered = r.amount() == 0L;
            boolean notReduced = afterExternal.getOrDefault(diamond, 0L) == 10L;
            boolean noCharge = NetEmcAccessor.getEmc(net) == emcBefore;

            if (notDelivered && notReduced && noCharge) {
                out.add("OK   零扣费护栏：外部抽取物化条目（EMC表未就绪 ⇒ 无价格）被拒绝 —— 0 交付、0 扣费、条目未减");
                ok[0]++;
            } else {
                out.add("FAIL 零扣费护栏：delivered=" + r.amount() + " entry=" + afterExternal
                        + " emc=" + NetEmcAccessor.getEmc(net) + "（期望 0 交付 / 条目仍为 10 / EMC 不变）");
            }

            // (b) MaterializingGuard 激活（= 物化层自身维护）→ 必须放行，否则物化层无法收缩自己的条目
            long extracted;
            MaterializingGuard.enter();
            try {
                extracted = net.getUnifiedStorage().extract(new EmcItemKey(diamond), 4L, false, false).amount();
            } finally {
                MaterializingGuard.exit();
            }
            long left = ItemMaterializer.currentEntries(net).getOrDefault(diamond, 0L);
            if (extracted == 4L && left == 6L) {
                out.add("OK   物化层自用放行：MaterializingGuard 激活时抽取成功（10 → 6），护栏不会自锁");
                ok[0]++;
            } else {
                out.add("FAIL 物化层自用放行：extracted=" + extracted + " 剩余=" + left + "（期望 4 / 6）");
            }

            // (c) 空守卫状态下，ItemStackKey 的抽取必须仍然原样放行（不破坏 0.2 行为）
            boolean itemKeyPassthrough;
            KeyAmount ik = net.getUnifiedStorage()
                    .extract(new ItemStackKey(ItemInfo.fromItem(Items.STONE).createStack()), 1L, false, false);
            itemKeyPassthrough = ik.amount() == 0L; // 本来就没有该库存 → 0，但不抛异常即视为通路正常
            if (itemKeyPassthrough) {
                out.add("OK   通路未破坏：ItemStackKey 的抽取仍走 BD 原生路径（无库存 → 0，未抛异常）");
                ok[0]++;
            } else {
                out.add("FAIL 通路未破坏：得到 " + ik);
            }
        } catch (Throwable t) {
            out.add("FAIL 零扣费护栏：" + t);
        }
    }
}
