package com.zhuyuhang.beyondemc.exchange;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.BeyondEmc;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 兑换服务：用网络 EMC 换出已学习的物品。
 *
 * <p><b>这是本模组唯一的权威实现</b>。客户端传来的只有"哪个网络 / 哪个物品 / 要几个"，
 * 价格与余额一律在服务端重算 —— 客户端的显示只是 UI 提示，**不可信**。
 *
 * <h2>取出口径与 BD 原生保持一致（Spike S3，已由源码确认）</h2>
 * BD 原生"从网络取出物品"的门槛只有一条：玩家必须属于该网络。
 * 证据：{@code OpenNetGuiPacket.handleInServer} 的入口是
 * {@code DimensionsNet.getNetFromPlayer(player)}，非 null 才开界面
 * （`OpenNetGuiPacket.java:65-66`）；而槽位侧的 {@code mayPickup} 用的是原版
 * {@code Slot} 的默认实现（恒真，{@code DisorderedStackTypedSlot} 未覆写）。
 * 因此 <b>Member 及以上都能取出</b>，我们不做更严的限制。
 *
 * <h2>定价</h2>
 * 取出用<b>购买价 {@code getValue}</b>，存入用回收价 {@code getSellValue} ——
 * 两者天然有差价，所以"存入再取出"的循环<b>必然亏损</b>，这是防无限刷的核心断言。
 */
public final class ExchangeService {

    private ExchangeService() {
    }

    /** 校验结果。{@code ok=false} 时 {@code reason} 是给玩家看的拒绝原因。 */
    public record Validation(boolean ok, String reason, long unitPrice, long totalCost) {

        static Validation reject(String reason) {
            return new Validation(false, reason, 0L, 0L);
        }

        static Validation accept(long unitPrice, long totalCost) {
            return new Validation(true, "", unitPrice, totalCost);
        }
    }

    /** 执行结果。 */
    public record Result(boolean success, String message, long spent, int given, long refunded) {

        public static Result fail(String message) {
            return new Result(false, message, 0L, 0, 0L);
        }
    }

    // ------------------------------------------------------------------
    // 校验（不碰玩家，便于自检单独测试）
    // ------------------------------------------------------------------

    /**
     * 只做"该不该允许这次兑换"的判断，不产生任何副作用。
     *
     * @param net   目标网络
     * @param info  必须是 {@code getPersistentInfo} 归一化过的物品身份
     * @param count 请求数量
     */
    public static Validation validate(@Nullable DimensionsNet net, @NotNull ItemInfo info, int count) {
        if (net == null) {
            return Validation.reject("找不到该维度网络");
        }
        if (count <= 0) {
            return Validation.reject("数量必须大于 0");
        }

        // 只允许兑换"网络已学会"的物品 —— 这是本模组的核心规则，也是权威判断。
        // 可由配置关闭（不建议）：关掉就等于"任何有 EMC 价值的物品都能直接换出来"。
        if (com.zhuyuhang.beyondemc.config.BeyondEmcConfig.exchangeRequiresKnowledge()
                && !NetKnowledgeStore.knows(net, info)) {
            return Validation.reject("该物品尚未被这个网络学会，无法兑换");
        }

        long unitPrice;
        try {
            // 购买价（与存入用的回收价相对）
            unitPrice = IEMCProxy.INSTANCE.getValue(info);
        } catch (Throwable t) {
            BeyondEmc.LOGGER.warn("[BeyondEMC] 兑换时查询购买价失败: {}", info, t);
            return Validation.reject("查询该物品的 EMC 价值失败");
        }
        if (unitPrice <= 0L) {
            return Validation.reject("该物品没有 EMC 价值");
        }

        // 饱和乘法：count 是 int，但 unitPrice 可能是很大的 long，乘法仍可能溢出（风险 R5）
        long totalCost = NetEmcAccessor.saturatingMultiply(unitPrice, count);
        if (totalCost <= 0L) {
            return Validation.reject("所需 EMC 超出可表示范围");
        }

        long balance = NetEmcAccessor.getEmc(net);
        if (balance < totalCost) {
            return Validation.reject("网络 EMC 不足：需要 " + totalCost + "，当前 " + balance);
        }

        return Validation.accept(unitPrice, totalCost);
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 完整的兑换事务：权限 → 校验 → 背包预检 → 扣费 → 发放。
     *
     * <p>顺序是刻意的：**先把所有可能失败的事检查完，再扣费**。
     * 否则会出现"钱扣了但物品没给出去"。（架构文档 §4.4 的验收标准之一）
     */
    public static Result exchange(@NotNull ServerPlayer player, @Nullable DimensionsNet net,
                                  @NotNull ItemStack template, int count) {
        if (net == null) {
            return Result.fail("找不到该维度网络");
        }
        if (!canAccess(player, net)) {
            return Result.fail("你不属于这个维度网络");
        }
        if (template.isEmpty()) {
            return Result.fail("请求的物品无效");
        }

        // 单次点击的数量上限（防误操作 / 界面异常导致的巨额请求）。
        // 放在最前面：越早裁剪，后续所有计算（含饱和乘法）的量级就越小。
        long cap = com.zhuyuhang.beyondemc.config.BeyondEmcConfig.maxExchangePerClick();
        if (count > cap) {
            count = (int) Math.min(cap, Integer.MAX_VALUE);
        }

        // ⚠️ 安全关键：**不能**拿客户端传来的 template 去铸造。
        // 归一化身份（用于查知识与定价）与铸造对象必须一致，否则就是刷物品漏洞：
        // 请求"附魔钻石剑"时 info 会被归一化成普通钻石剑并按 8192 计价，
        // 若照 template 发放就会铸出附魔剑。详见 CanonicalExchange 的类注释。
        CanonicalExchange.Resolved resolved = CanonicalExchange.resolve(template);
        if (resolved == null) {
            return Result.fail("请求的物品无效");
        }
        ItemInfo info = resolved.info();
        // 后续一切（背包预检、发放）都用这个规范堆叠，而不是 template
        ItemStack canonical = resolved.stack();

        Validation v = validate(net, info, count);
        if (!v.ok()) {
            return Result.fail(v.reason());
        }

        // 背包预检：必须在扣费之前。
        //
        // 装不下时【按能装下的数量裁剪】而不是直接拒绝 —— 尤其配合"Shift+点击取一组"：
        // 背包快满时咔嚓一个"空间不足"会让人莫名其妙。完全装不下（capacity <= 0）才拒绝。
        // 安全性质不变：**只按实际发放的数量收费**，绝不会先扣钱再发现装不下。
        int capacity = simulateCapacity(player, canonical, count);
        if (capacity <= 0) {
            return Result.fail("背包空间不足，放不下这个物品");
        }
        int effective = Math.min(count, capacity);
        if (effective < count) {
            // 裁剪后代价会变，必须重新校验（余额等）
            Validation recheck = validate(net, info, effective);
            if (!recheck.ok()) {
                return Result.fail(recheck.reason());
            }
            v = recheck;
            BeyondEmc.LOGGER.debug("[BeyondEMC] 背包只能放下 {} 个，请求的 {} 已裁剪", effective, count);
        }

        long spent = NetEmcAccessor.spendEmc(net, v.totalCost());
        if (spent < v.totalCost()) {
            // 正常不该发生（前面查过余额）；可能是同 tick 内的并发操作
            if (spent > 0L) {
                NetEmcAccessor.addEmc(net, spent); // 回滚
            }
            return Result.fail("扣除 EMC 失败，请重试");
        }

        int given = 0;
        try {
            given = give(player, canonical, effective);
        } catch (Throwable t) {
            BeyondEmc.LOGGER.error("[BeyondEMC] 发放兑换物品时出错", t);
        }

        long refunded = 0L;
        if (given < effective) {
            // 兜底：没发出去的部分原价退回，绝不让玩家损失
            refunded = NetEmcAccessor.addEmc(net,
                    NetEmcAccessor.saturatingMultiply(v.unitPrice(), effective - given));
            BeyondEmc.LOGGER.warn("[BeyondEMC] 只发放了 {}/{} 个，已退回 {} EMC", given, effective, refunded);
        }

        if (given > 0) {
            BeyondEmc.LOGGER.debug("[BeyondEMC] 兑换：{} ×{} → 花费 {} EMC（单价 {}）",
                    info, given, spent, v.unitPrice());
        }

        if (given < effective) {
            return new Result(false, "只换出了 " + given + " 个（其余已退回 EMC）", spent, given, refunded);
        }
        if (effective < count) {
            return new Result(true, "背包空间有限，只换出了 " + effective + " 个", spent, given, refunded);
        }
        return new Result(true, "换出 " + given + " 个 " + info + "，花费 " + spent + " EMC",
                spent, given, refunded);
    }

    // ------------------------------------------------------------------
    // 吸附到鼠标（0.2.0）
    // ------------------------------------------------------------------

    /** 鼠标上已有不同物品，无法吸附。 */
    public static final int CURSOR_OCCUPIED = -1;

    /** 鼠标上的同类堆叠已满。 */
    public static final int CURSOR_FULL = 0;

    /**
     * **纯计算**：鼠标当前能再吸附多少个 {@code canonical}。
     *
     * <p>抽成独立方法与 {@code EmcDepositHandler.skipReason} 同理：
     * 吸附容量的规则（空鼠标 / 同类可合并 / 异类拒绝 / 上限）是本功能最容易出错的地方，
     * 做成纯函数就能在无头环境里被自检**决定性**验证，而不必依赖图形界面。
     *
     * @param carried   鼠标当前拿着的堆叠（可为空堆叠）
     * @param canonical 归一化身份重建出的堆叠（**不是**客户端传来的原始对象）
     * @param requested 客户端建议的数量（不可信）
     * @return 实际可吸附数量；{@link #CURSOR_OCCUPIED} 表示鼠标被动占用；
     *         {@link #CURSOR_FULL} 表示同类堆叠已满
     */
    public static int cursorCapacity(@NotNull ItemStack carried, @NotNull ItemStack canonical, int requested) {
        if (canonical.isEmpty() || requested <= 0) {
            return CURSOR_FULL;
        }
        int maxStack = Math.max(1, canonical.getMaxStackSize());
        if (carried.isEmpty()) {
            return Math.min(requested, maxStack);
        }
        if (!ItemStack.isSameItemSameComponents(carried, canonical)) {
            return CURSOR_OCCUPIED;
        }
        return Math.min(requested, maxStack - carried.getCount());
    }

    /**
     * 用 EMC 兑换并把物品**吸附到鼠标**（像原版拾取）。
     *
     * <h2>安全关键：先算能吸附多少，再按这个数量扣费</h2>
     * 绝不能"先扣钱、再发现鼠标放不下"。0.1.0 那个数据丢失 bug
     * （信任 `Inventory.add` 的返回值，而它在创造模式下会销毁物品却返回 true）
     * 就是同型错误。这里的不变式是：
     *
     * <pre>扣费数量 == 吸附到鼠标的数量</pre>
     *
     * <p>与那条旧路径的区别在于：{@code menu.setCarried(...)} 只是给字段赋值，
     * **没有会撒谎的中间层**；而且容量在扣费之前就用 {@link #cursorCapacity} 定好了。
     * 即便如此仍加了 try/catch 回滚，避免任何意外路径造成"扣了钱没拿到"。
     */
    public static Result pickupToCursor(@NotNull ServerPlayer player, @Nullable DimensionsNet net,
                                        @NotNull ItemStack template, int requested) {
        if (net == null) {
            return Result.fail("找不到该维度网络");
        }
        if (!canAccess(player, net)) {
            return Result.fail("你不属于这个维度网络");
        }
        if (!(player.containerMenu instanceof net.minecraft.world.inventory.AbstractContainerMenu menu)) {
            return Result.fail("当前没有打开可操作的界面");
        }

        // 身份归一：绝不拿客户端传来的对象去铸造（防刷物品漏洞的同一条防线）
        CanonicalExchange.Resolved resolved = CanonicalExchange.resolve(template);
        if (resolved == null) {
            return Result.fail("请求的物品无效");
        }
        ItemInfo info = resolved.info();
        ItemStack canonical = resolved.stack();

        ItemStack carried = menu.getCarried();
        // 客户端建议的数量先按配置上限裁剪，再交给容量计算
        long cap = com.zhuyuhang.beyondemc.config.BeyondEmcConfig.maxExchangePerClick();
        int requestedCapped = (int) Math.min(requested, Math.min(cap, Integer.MAX_VALUE));
        int capacity = cursorCapacity(carried, canonical, requestedCapped);
        if (capacity == CURSOR_OCCUPIED) {
            return Result.fail("鼠标上已有其他物品，请先放下再兑换");
        }
        if (capacity <= 0) {
            return Result.fail(carried.isEmpty() ? "请求的数量无效" : "鼠标上的堆叠已经满了");
        }

        // 余额校验（会顺带校验"是否已学会"与"是否有 EMC 价值"）
        Validation v = validate(net, info, capacity);
        if (!v.ok()) {
            return Result.fail(v.reason());
        }

        long cost = v.totalCost();
        long spent = 0L;
        try {
            spent = NetEmcAccessor.spendEmc(net, cost);
            if (spent < cost) {
                // 并发情况下余额被抢走：回滚并放弃，绝不半途
                if (spent > 0L) {
                    NetEmcAccessor.addEmc(net, spent);
                }
                return Result.fail("扣除 EMC 失败，请重试");
            }

            // 吸附：与鼠标上已有堆叠合并（上面已确认是同类或为空）
            ItemStack merged = carried.isEmpty()
                    ? canonical.copyWithCount(capacity)
                    : carried.copyWithCount(carried.getCount() + capacity);
            menu.setCarried(merged);
            // 立即推送一次，玩家不必等下一 tick 才看到（broadcastChanges 内会同步 carried）
            menu.broadcastChanges();

            BeyondEmc.LOGGER.debug("[BeyondEMC] 吸附到鼠标：{} ×{} → 花费 {} EMC（单价 {}）",
                    info, capacity, spent, v.unitPrice());
            return new Result(true,
                    "吸附 " + capacity + " 个 " + info + " 到鼠标，花费 " + spent + " EMC",
                    spent, capacity, 0L);
        } catch (Throwable t) {
            // 任何异常都回滚，保证"扣费数量 == 交付数量"
            if (spent > 0L) {
                NetEmcAccessor.addEmc(net, spent);
            }
            BeyondEmc.LOGGER.error("[BeyondEMC] 吸附到鼠标时出错，已回滚 EMC", t);
            return Result.fail("吸附失败：内部错误（EMC 已退回）");
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 玩家是否有权操作该网络。
     *
     * <p>{@code getPlayers()} 按 {@code DimensionsNetEvent.Destroyed} 的契约
     * <b>包含管理者与所有者</b>（`DimensionsNet.java:73-76` 的注释），
     * 但这里仍然把三者都判一遍 —— 零成本，且不依赖那条注释永远成立。
     */
    public static boolean canAccess(@NotNull ServerPlayer player, @NotNull DimensionsNet net) {
        return net.getPlayers().contains(player.getUUID())
                || net.isOwner(player)
                || net.isManager(player);
    }

    /**
     * 往玩家背包里放物品。{@code apply=false} 时只测算能放多少，**不修改任何状态**。
     *
     * <p>只算主背包 36 格 + 副手（与原版 {@code Inventory.add} 的可达范围一致）。
     *
     * <h2>⚠️ 为什么刻意不用 {@code Inventory.add(ItemStack)}</h2>
     * 它在**创造模式**下有一个危险行为：背包满时会把堆叠直接清零并返回 {@code true}
     * —— 物品被凭空销毁，而调用方以为成功了。证据（NeoForge 反编译源码）：
     * <pre>
     * // net/minecraft/world/entity/player/Inventory.java#add(int, ItemStack)
     * if (stack.getCount() == i && this.player.hasInfiniteMaterials()) {
     *     stack.setCount(0);
     *     return true;
     * }
     * </pre>
     * 最初的实现按这个返回值计数，于是"扣了 EMC 但什么都没拿到"
     * （实测 bug：创造模式下背包满时取出钻石/绿宝石）。受伤物品分支里还有同样的
     * {@code else if (this.player.hasInfiniteMaterials()) { stack.setCount(0); return true; }}。
     *
     * <h2>为什么把"测算"和"落地"合成一个方法</h2>
     * 用同一个 {@code apply} 开关走**同一段代码**，结构上就不可能再出现
     * "预检说放得下、实际放不下"的分歧 —— 那正是前一轮虚拟条目 bug 的同一种病：
     * 同一件事维护了两份实现。
     */
    private static int putIntoInventory(@NotNull ServerPlayer player, @NotNull ItemStack template,
                                        int count, boolean apply) {
        var inv = player.getInventory();
        int remaining = count;
        int maxStack = Math.max(1, template.getMaxStackSize());

        // 第一遍：并入已有的同类堆叠
        for (int i = 0; i < inv.items.size() && remaining > 0; i++) {
            ItemStack slot = inv.items.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, template)) {
                continue;
            }
            int space = Math.min(maxStack, slot.getMaxStackSize()) - slot.getCount();
            if (space <= 0) {
                continue;
            }
            int put = Math.min(space, remaining);
            if (apply) {
                slot.grow(put);
            }
            remaining -= put;
        }

        // 第二遍：放进空槽
        for (int i = 0; i < inv.items.size() && remaining > 0; i++) {
            if (!inv.items.get(i).isEmpty()) {
                continue;
            }
            int put = Math.min(maxStack, remaining);
            if (apply) {
                inv.items.set(i, template.copyWithCount(put));
            }
            remaining -= put;
        }

        // 第三遍：副手
        if (remaining > 0) {
            ItemStack offhand = inv.offhand.get(0);
            if (offhand.isEmpty()) {
                int put = Math.min(maxStack, remaining);
                if (apply) {
                    inv.offhand.set(0, template.copyWithCount(put));
                }
                remaining -= put;
            } else if (ItemStack.isSameItemSameComponents(offhand, template)) {
                int space = Math.min(maxStack, offhand.getMaxStackSize()) - offhand.getCount();
                if (space > 0) {
                    int put = Math.min(space, remaining);
                    if (apply) {
                        offhand.grow(put);
                    }
                    remaining -= put;
                }
            }
        }

        return count - Math.max(0, remaining);
    }

    /** 测算"再放入 {@code count} 个该物品"能容纳多少，不修改任何状态。 */
    static int simulateCapacity(@NotNull ServerPlayer player, @NotNull ItemStack template, int count) {
        return putIntoInventory(player, template, count, false);
    }

    /**
     * 实际发放物品。
     *
     * <p>因为调用方已按 {@link #simulateCapacity} 的结果裁剪过数量，
     * 正常情况下能全部放进背包。这里的掉落只是**兜底**：
     * 万一预检与实际不一致，宁可掉在脚下，也绝不让物品凭空消失（那笔钱已经扣过了）。
     *
     * @return 实际交付的数量（进背包的 + 掉落的）
     */
    private static int give(@NotNull ServerPlayer player, @NotNull ItemStack template, int count) {
        int placed = putIntoInventory(player, template, count, true);
        int remaining = count - placed;
        if (remaining <= 0) {
            return placed;
        }

        int maxStack = Math.max(1, template.getMaxStackSize());
        int left = remaining;
        while (left > 0) {
            int n = Math.min(maxStack, left);
            player.drop(template.copyWithCount(n), false);
            left -= n;
        }
        BeyondEmc.LOGGER.warn("[BeyondEMC] 背包放不下 {} 个，已掉落在脚下（预检与实际不一致，不应发生）", remaining);
        return placed + remaining;
    }
}
