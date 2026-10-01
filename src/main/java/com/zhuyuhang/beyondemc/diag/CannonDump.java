package com.zhuyuhang.beyondemc.diag;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.common.block.entity.NetedBlockEntity;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /beyondemc cannon <x> <y> <z>} —— 无头复现"蓝图大炮材料清单"的完整判定链。
 *
 * <h2>为什么需要它（0.3.2 第二轮实测缺陷的直接产物）</h2>
 * 用户报告「清单里圆石满足条件、传动杆不满足」，但两个物品在网络里的权威数据
 * （已学习 / 有价格 / 有物化条目）完全对称。在实机上这两种状态长得一模一样，
 * 而判定链散在 <b>三个模组</b> 里：
 * <ol>
 *   <li><b>Create</b>：{@code updateChecklist} 用 {@code extractItem(i,1,true)}
 *       当门槛、用 {@code getStackInSlot(i).getCount()} 累加 gathered，
 *       清单（{@code createWrittenBook}）按 {@code required - gathered} 分"满足/还缺"；</li>
 *   <li><b>BeyondDimensions</b>：{@code NetedSchematicannonItemHandler} 按 checklist
 *       键集合建槽位快照（每物品一个槽），对网络做精确键查询；</li>
 *   <li><b>本模组</b>：两条 mixin 在原生查询为空时补报物化可交付量。</li>
 * </ol>
 * 三层任何一层对某个物品失手，表现都相同。本命令把三层全部打出来，
 * 一次调用就能看到"每个物品的 required / gathered / 门槛是否通过"。
 *
 * <h2>为什么全走反射</h2>
 * Create 在本工程只是 {@code runtimeOnly}（编译期不可依赖，见 build.gradle 的
 * withCreate 注释）。所以本类不 import 任何 Create 类型，全部经 {@link Class#forName}
 * + 反射访问；Create 缺席时给出一行说明并退出，不炸命令树。
 *
 * <h2>无副作用声明</h2>
 * 唯一有"动作"的一步是反射调用 {@code updateChecklist()} —— 它本身是 Create 在
 * 打印清单前的正常路径（{@code tickPaperPrinter}），且 {@code findInventories()}
 * 里对清单的扫库全部是模拟抽取（本模组的模拟路径只读，见
 * {@code NetedSchematicannonItemHandlerMixin} 红线 1）。
 */
public final class CannonDump {

    private static final String CANNON_CLASS =
            "com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity";
    private static final String CHECKLIST_CLASS =
            "com.simibubi.create.content.schematics.cannon.MaterialChecklist";

    private CannonDump() {
    }

    public static int dump(CommandSourceStack source, BlockPos pos) {
        // ---- 0. Create 是否在场 ----
        Class<?> cannonClass;
        try {
            cannonClass = Class.forName(CANNON_CLASS);
        } catch (Throwable t) {
            source.sendFailure(Component.literal("[BeyondEMC] 场上没有 Create（找不到 "
                    + CANNON_CLASS + "），无法复现蓝图大炮的判定链"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[BeyondEMC] 环境：EMC 表就绪="
                + EmcAvailability.isReady() + "，remap 次数=" + EmcAvailability.remapCount()
                + (EmcAvailability.isReady() ? "" : "   ← 未就绪：先执行 /reload，否则一切判据都判 0")), false);

        // ---- 1. 在已加载维度里找到坐标上的大炮 ----
        for (ServerLevel level : source.getServer().getAllLevels()) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                source.sendSuccess(() -> Component.literal("[BeyondEMC] " + level.dimension()
                        + " 的 " + pos.toShortString() + " 没有方块实体"), false);
                continue;
            }
            if (!cannonClass.isInstance(be)) {
                source.sendFailure(Component.literal("[BeyondEMC] " + level.dimension() + " 的 "
                        + pos.toShortString() + " 是 " + be.getClass().getName() + "，不是蓝图大炮"));
                return 0;
            }
            return dumpCannon(source, level, pos, be, cannonClass);
        }
        source.sendFailure(Component.literal("[BeyondEMC] 任何已加载维度里 " + pos.toShortString()
                + " 都没有加载的区块（先用 /forceload add 或等玩家靠近）"));
        return 0;
    }

    private static int dumpCannon(CommandSourceStack source, ServerLevel level, BlockPos pos,
                                  BlockEntity cannon, Class<?> cannonClass) {
        try {
            // ---- 2. 打印机状态（required 是否会被填充的前提） ----
            Object printer = cannonClass.getField("printer").get(cannon);
            boolean printerLoaded = printer != null
                    && (Boolean) printer.getClass().getMethod("isLoaded").invoke(printer);
            boolean printerErrored = printer != null
                    && (Boolean) printer.getClass().getMethod("isErrored").invoke(printer);
            Object state = cannonClass.getField("state").get(cannon);
            Object statusMsg = cannonClass.getField("statusMsg").get(cannon);
            int placed = cannonClass.getField("blocksPlaced").getInt(cannon);
            int toPlace = cannonClass.getField("blocksToPlace").getInt(cannon);
            source.sendSuccess(() -> Component.literal("[BeyondEMC] 大炮 " + pos.toShortString()
                    + "：state=" + state + "，status=" + statusMsg
                    + "，已放 " + placed + "/" + toPlace
                    + "，蓝图加载=" + printerLoaded + (printerErrored ? "（且报错）" : "")), false);

            // ---- 3. 走一遍生产路径：updateChecklist()（见类注释的无副作用声明） ----
            Method updateChecklist = cannonClass.getMethod("updateChecklist");
            updateChecklist.invoke(cannon);

            Object checklist = cannonClass.getField("checklist").get(cannon);
            if (checklist == null) {
                source.sendFailure(Component.literal("[BeyondEMC] 大炮 checklist 为 null"));
                return 0;
            }
            Class<?> mcClass = Class.forName(CHECKLIST_CLASS);
            boolean blocksNotLoaded = mcClass.getField("blocksNotLoaded").getBoolean(checklist);
            @SuppressWarnings("unchecked")
            Object2IntMap<Item> required = (Object2IntMap<Item>) mcClass.getField("required").get(checklist);
            @SuppressWarnings("unchecked")
            Object2IntMap<Item> damageRequired = (Object2IntMap<Item>) mcClass.getField("damageRequired").get(checklist);
            @SuppressWarnings("unchecked")
            Object2IntMap<Item> gathered = (Object2IntMap<Item>) mcClass.getField("gathered").get(checklist);
            Method getRequiredAmount = mcClass.getMethod("getRequiredAmount", Item.class);

            source.sendSuccess(() -> Component.literal("[BeyondEMC] updateChecklist 已执行：required="
                    + required.size() + " 种，damageRequired=" + damageRequired.size()
                    + " 种，gathered=" + gathered.size() + " 种"
                    + (blocksNotLoaded ? "，⚠ 有区块未加载（缺失模组的方块会被记进来）" : "")), false);

            // ---- 4. 按 createWrittenBook 的同一套口径算"满足/还缺" ----
            List<Item> items = new ArrayList<>();
            for (Item item : required.keySet()) {
                if (!items.contains(item)) items.add(item);
            }
            for (Item item : damageRequired.keySet()) {
                if (!items.contains(item)) items.add(item);
            }
            items.sort(Comparator.comparing(i -> String.valueOf(BuiltInRegistries.ITEM.getKey(i))));

            List<String> satisfied = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (Item item : items) {
                int need = (Integer) getRequiredAmount.invoke(checklist, item);
                int have = gathered.getInt(item);
                String name = String.valueOf(BuiltInRegistries.ITEM.getKey(item));
                if (need - have <= 0) {
                    satisfied.add(name + "(" + have + "/" + need + ")");
                } else {
                    missing.add(name + " 还缺 " + (need - have) + "（gathered=" + have + " / required=" + need + "）");
                }
            }
            source.sendSuccess(() -> Component.literal("[BeyondEMC] 清单·满足条件（" + satisfied.size() + " 种）："
                    + String.join("，", satisfied)), false);
            for (String line : missing) {
                source.sendSuccess(() -> Component.literal("[BeyondEMC] 清单·还缺：" + line), false);
            }
        } catch (Throwable t) {
            source.sendFailure(Component.literal("[BeyondEMC] 反射读取大炮失败：" + t));
            return 0;
        }

        // ---- 5. 六向扫描蓝图接口（BD 的 pathway），打印每个槽位的两条判据 ----
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(neighbor);
            if (be == null) {
                continue;
            }
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, neighbor, dir.getOpposite());
            if (handler == null) {
                continue;
            }
            dumpPathway(source, level, neighbor, dir, be, handler);
        }
        return 1;
    }

    private static void dumpPathway(CommandSourceStack source, ServerLevel level, BlockPos pos,
                                    Direction fromCannon, BlockEntity pathway, IItemHandler handler) {
        // 网络（权威数据）
        if (pathway instanceof NetedBlockEntity neted) {
            try {
                DimensionsNet net = neted.getNet();
                if (net != null) {
                    source.sendSuccess(() -> Component.literal("[BeyondEMC] 蓝图接口 " + pos.toShortString()
                            + "（大炮在 " + fromCannon.getName() + " 向）：网络 " + net.getId()
                            + "，EMC=" + NetEmcAccessor.getEmc(net)
                            + "，已学习 " + NetKnowledgeStore.snapshot(net).size() + " 种"), false);
                } else {
                    source.sendSuccess(() -> Component.literal("[BeyondEMC] 蓝图接口 " + pos.toShortString()
                            + "：⚠ 未绑定网络（netId=" + neted.getNetId() + "）"), false);
                }
            } catch (Throwable t) {
                source.sendSuccess(() -> Component.literal("[BeyondEMC] 蓝图接口 " + pos.toShortString()
                        + "：读网络失败 " + t), false);
            }
        }

        // 槽位快照（反射读 stacksSnapshot，读不到就靠 getStackInSlot 的返回物推断）
        List<ItemStack> snapshot = readSnapshot(handler);
        int slots = handler.getSlots();
        source.sendSuccess(() -> Component.literal("[BeyondEMC] 槽位数=" + slots
                + (snapshot == null ? "（快照读不到，槽内物品以判据输出为准）"
                : "，快照物品=" + snapshot.stream().map(s ->
                String.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem()))).toList())), false);

        for (int slot = 0; slot < slots; slot++) {
            final int i = slot;
            String snapName = snapshot != null && i < snapshot.size()
                    ? String.valueOf(BuiltInRegistries.ITEM.getKey(snapshot.get(i).getItem()))
                    : "?";
            ItemStack display = handler.getStackInSlot(i);          // 判据 A：显示（累加进 gathered 的数量来源）
            ItemStack gate = handler.extractItem(i, 1, true);      // 判据 B：模拟抽取（Create 的门槛）
            source.sendSuccess(() -> Component.literal("[BeyondEMC] 槽 " + i + " [" + snapName + "]："
                    + "getStackInSlot=" + (display.isEmpty() ? "空"
                    : BuiltInRegistries.ITEM.getKey(display.getItem()) + "×" + display.getCount())
                    + "；extractItem(1,true)=" + (gate.isEmpty() ? "空（→Create 判定无库存）"
                    : BuiltInRegistries.ITEM.getKey(gate.getItem()) + "×" + gate.getCount())), false);
        }
    }

    /** 反射读 BD handler 的 stacksSnapshot（包私有字段）；任何失败返回 null。 */
    private static List<ItemStack> readSnapshot(IItemHandler handler) {
        try {
            Field f = handler.getClass().getDeclaredField("stacksSnapshot");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ItemStack> list = (List<ItemStack>) f.get(handler);
            return list;
        } catch (Throwable t) {
            return null;
        }
    }
}
