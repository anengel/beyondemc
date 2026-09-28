package com.zhuyuhang.beyondemc.diag;

import com.zhuyuhang.beyondemc.exchange.ExchangeService;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 0.2.0-A 自检：**兑换物品吸附到鼠标**的容量规则。
 *
 * <h2>为什么这一组必须存在</h2>
 * 吸附功能最容易出错的地方不是界面，而是**数量的裁剪规则**：
 * 鼠标空着、鼠标上有同类可合并、鼠标上有异类、堆叠已满、物品不可堆叠……
 * 每一条都对应一种"多给了/少给了/扣了钱没给"的可能。
 *
 * <p>所以容量规则被抽成纯函数 {@link ExchangeService#cursorCapacity}，
 * 在无头环境里就能被**决定性**验证，不必依赖图形界面。
 *
 * <p>最关键的断言是最后一条：**同一个输入永远得到同一个结果**（无状态）。
 * 因为服务端的顺序是"先算容量 → 再按这个数量扣费 → 再吸附"，
 * 只要容量是纯函数、且扣费与吸附都用同一个返回值，
 * 就结构性地保证了不变式「扣费数量 == 吸附数量」。
 */
public final class CursorPickupSelfTest {

    private CursorPickupSelfTest() {
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        return new ItemStack(item, count);
    }

    public static List<String> run() {
        List<String> out = new ArrayList<>();
        int ok = 0;

        final ItemStack empty = ItemStack.EMPTY;

        // ---- 1. 空鼠标：按请求量取，且不超过原版堆叠上限 ----
        try {
            ItemStack diamond = stack(Items.DIAMOND, 1);
            int a = ExchangeService.cursorCapacity(empty, diamond, 64);
            int b = ExchangeService.cursorCapacity(empty, diamond, 999);
            int c = ExchangeService.cursorCapacity(empty, diamond, 1);
            if (a == 64 && b == 64 && c == 1) {
                out.add("OK   吸附容量·空鼠标：请求 64→64、请求 999→按上限 64、请求 1→1");
                ok++;
            } else {
                out.add("FAIL 吸附容量·空鼠标：a=" + a + " b=" + b + " c=" + c + "（期望 64/64/1）");
            }
        } catch (Throwable t) {
            out.add("FAIL 吸附容量·空鼠标：" + t);
        }

        // ---- 2. 鼠标上已有同类：合并到上限，且不超发 ----
        try {
            ItemStack diamond = stack(Items.DIAMOND, 1);
            int a = ExchangeService.cursorCapacity(stack(Items.DIAMOND, 10), diamond, 64);
            int b = ExchangeService.cursorCapacity(stack(Items.DIAMOND, 64), diamond, 64);
            int c = ExchangeService.cursorCapacity(stack(Items.DIAMOND, 63), diamond, 64);
            if (a == 54 && b == ExchangeService.CURSOR_FULL && c == 1) {
                out.add("OK   吸附容量·同类合并：已有 10 → 再给 54；已有 63 → 再给 1；已有 64（满）→ 拒绝");
                ok++;
            } else {
                out.add("FAIL 吸附容量·同类合并：a=" + a + " b=" + b + " c=" + c + "（期望 54/0/1）");
            }
        } catch (Throwable t) {
            out.add("FAIL 吸附容量·同类合并：" + t);
        }

        // ---- 3. 鼠标上是别的物品：拒绝（而不是把东西丢掉或吞掉）----
        try {
            int a = ExchangeService.cursorCapacity(stack(Items.EMERALD, 1), stack(Items.DIAMOND, 1), 64);
            if (a == ExchangeService.CURSOR_OCCUPIED) {
                out.add("OK   吸附容量·异类拒绝：鼠标拿着绿宝石时兑换钻石 → 拒绝（绝不做交换或丢弃）");
                ok++;
            } else {
                out.add("FAIL 吸附容量·异类拒绝：返回 " + a + "（期望 " + ExchangeService.CURSOR_OCCUPIED + "）");
            }
        } catch (Throwable t) {
            out.add("FAIL 吸附容量·异类拒绝：" + t);
        }

        // ---- 4. 不可堆叠物品：上限就是 1 ----
        try {
            ItemStack sword = stack(Items.DIAMOND_SWORD, 1);
            int a = ExchangeService.cursorCapacity(empty, sword, 64);
            int b = ExchangeService.cursorCapacity(stack(Items.DIAMOND_SWORD, 1), sword, 64);
            if (a == 1 && b == ExchangeService.CURSOR_FULL) {
                out.add("OK   吸附容量·不可堆叠：钻石剑请求 64 → 只给 1；鼠标已有一把 → 拒绝");
                ok++;
            } else {
                out.add("FAIL 吸附容量·不可堆叠：a=" + a + " b=" + b + "（期望 1/0）");
            }
        } catch (Throwable t) {
            out.add("FAIL 吸附容量·不可堆叠：" + t);
        }

        // ---- 5. ★ 无状态：同一输入永远同一结果（「扣费数量 == 吸附数量」的结构性保证）----
        try {
            ItemStack diamond = stack(Items.DIAMOND, 1);
            ItemStack carried = stack(Items.DIAMOND, 5);
            int first = ExchangeService.cursorCapacity(carried, diamond, 64);
            int second = ExchangeService.cursorCapacity(carried, diamond, 64);
            int third = ExchangeService.cursorCapacity(carried, diamond, 64);
            // 非法输入必须安全回落，不能抛异常（网络线程里抛异常代价很大）
            int bad1 = ExchangeService.cursorCapacity(empty, diamond, 0);
            int bad2 = ExchangeService.cursorCapacity(empty, ItemStack.EMPTY, 64);
            boolean stable = first == second && second == third && first == 59;
            boolean safe = bad1 == ExchangeService.CURSOR_FULL && bad2 == ExchangeService.CURSOR_FULL;
            if (stable && safe) {
                out.add("OK   吸附容量·纯函数性（扣费==吸附的结构保证）：重复调用恒为 " + first
                        + "；请求 0 / 空物品均安全返回 0 而不抛异常");
                ok++;
            } else {
                out.add("FAIL 吸附容量·纯函数性：first=" + first + " second=" + second
                        + " third=" + third + " bad1=" + bad1 + " bad2=" + bad2
                        + "（期望稳定 59，bad 均为 0）");
            }
        } catch (Throwable t) {
            out.add("FAIL 吸附容量·纯函数性：" + t);
        }

        out.add("---- 鼠标吸附拾取自检结果：" + ok + "/5 项通过 ----");
        return out;
    }
}
