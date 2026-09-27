package com.zhuyuhang.beyondemc.diag;

import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.config.EmcItemFilter;
import com.zhuyuhang.beyondemc.emc.EmcDepositHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 6 的诊断自检：**策略层**（配置 + 物品筛选 + 组件策略）。
 *
 * <p>这些断言之所以能无头通过，是因为阶段 6 把"策略判断"从折算钩子里抽成了
 * {@link EmcDepositHandler#skipReason(ItemStack)}，并把它排在"EMC 表就绪"之前 ——
 * 否则在无头服务器上（EMC 表永远为空）根本走不到这些分支，
 * 而"钩子原样返回"又无法区分是被哪一道门挡的。
 */
public final class Phase6SelfTest {

    private Phase6SelfTest() {
    }

    public static List<String> run() {
        List<String> out = new ArrayList<>();
        int ok = 0;

        // ---- 1. 配置默认值（这些默认值是安全语义的一部分，不该被无声改掉）----
        try {
            boolean deposit = BeyondEmcConfig.enableEmcDeposit();
            boolean convertComponents = BeyondEmcConfig.convertComponentItems();
            boolean showVirtual = BeyondEmcConfig.showVirtualEntries();
            boolean requireKnowledge = BeyondEmcConfig.exchangeRequiresKnowledge();
            long cap = BeyondEmcConfig.maxExchangePerClick();

            boolean good = deposit && !convertComponents && showVirtual && requireKnowledge && cap > 0;
            if (good) {
                out.add("OK   配置默认值：折算开启=" + deposit + "，折算组件物品=" + convertComponents
                        + "（应为 false，保护附魔/耐久/容器）"
                        + "，显示虚拟条目=" + showVirtual
                        + "，兑换需已学习=" + requireKnowledge + "，单次上限=" + cap);
                ok++;
            } else {
                out.add("FAIL 配置默认值：deposit=" + deposit + " convertComponents=" + convertComponents
                        + " showVirtual=" + showVirtual + " requireKnowledge=" + requireKnowledge + " cap=" + cap);
            }
        } catch (Throwable t) {
            out.add("FAIL 配置默认值：" + t);
        }

        // ---- 2. 组件策略：普通物品放行，带组件物品被挡（风险 R4 的核心断言）----
        try {
            ItemStack plain = new ItemStack(Items.DIAMOND_SWORD);
            String plainReason = EmcDepositHandler.skipReason(plain);

            ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("测试之剑"));
            String namedReason = EmcDepositHandler.skipReason(named);

            ItemStack damaged = new ItemStack(Items.DIAMOND_PICKAXE);
            damaged.setDamageValue(100);
            String damagedReason = EmcDepositHandler.skipReason(damaged);

            boolean good = plainReason == null
                    && namedReason != null && namedReason.contains("组件")
                    && damagedReason != null && damagedReason.contains("组件");
            if (good) {
                out.add("OK   组件策略：普通钻石剑放行；改名物品与耐久物品均被挡"
                        + "（原因=\"" + namedReason + "\"）");
                ok++;
            } else {
                out.add("FAIL 组件策略：plain=" + plainReason + " named=" + namedReason
                        + " damaged=" + damagedReason + "（期望：plain 放行，另两个被挡并含\"组件\"）");
            }
        } catch (Throwable t) {
            out.add("FAIL 组件策略：" + t);
        }

        // ---- 3. 物品筛选在空配置下不误伤、不抛异常 ----
        try {
            boolean stone = EmcItemFilter.allows(new ItemStack(Items.STONE));
            boolean diamond = EmcItemFilter.allows(new ItemStack(Items.DIAMOND));
            boolean empty = EmcItemFilter.allows(ItemStack.EMPTY);

            if (stone && diamond && !empty) {
                out.add("OK   物品筛选：空配置下不误伤任何物品（石头/钻石均放行），空堆叠被拒");
                ok++;
            } else {
                out.add("FAIL 物品筛选：stone=" + stone + " diamond=" + diamond + " empty=" + empty);
            }
        } catch (Throwable t) {
            out.add("FAIL 物品筛选：" + t);
        }

        // ---- 4. 折算钩子的策略段与"价格段"边界：无价格时也应先被策略放行 ----
        // （这里只验证策略段本身；是否真的折算取决于 EMC 表，属于阶段 3/4 的自检范围）
        try {
            String stoneReason = EmcDepositHandler.skipReason(new ItemStack(Items.STONE));
            String netheriteAxeReason = EmcDepositHandler.skipReason(new ItemStack(Items.NETHERITE_AXE));
            if (stoneReason == null && netheriteAxeReason == null) {
                out.add("OK   策略段边界：无 EMC 价值的物品（石头）与普通工具在策略段均放行，"
                        + "由后续\"回收价 > 0\"判定是否折算");
                ok++;
            } else {
                out.add("FAIL 策略段边界：stone=" + stoneReason + " netheriteAxe=" + netheriteAxeReason);
            }
        } catch (Throwable t) {
            out.add("FAIL 策略段边界：" + t);
        }

        out.add("---- 阶段 6 自检结果：" + ok + "/4 项通过 ----");
        return out;
    }
}
