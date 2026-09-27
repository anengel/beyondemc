package com.zhuyuhang.beyondemc.diag;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.ExtractContext;
import com.zhuyuhang.beyondemc.core.MintingGuard;
import com.zhuyuhang.beyondemc.exchange.CanonicalExchange;
import com.zhuyuhang.beyondemc.exchange.InterfaceWithdrawService;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络接口兑换（自动化向）的诊断自检。
 *
 * <h2>这一组自检想守住什么</h2>
 * 本功能的实现依赖两件"如果坏了就会静默失效"的东西：
 * <ol>
 *   <li>{@code UnifiedStorageExtractMixin} 必须真的应用，否则 {@link ExtractContext}
 *       的深度恒为 0，{@link ExtractContext#isSimulate()} 保守返回 true，
 *       兑换钩子一律放行 —— <b>功能整体不生效</b>。用 {@code enterCount()} 探针守住；</li>
 *   <li>{@link ExtractContext} 必须能区分模拟与真实抽取，否则<b>外部模组的一次能力查询
 *       就会真扣玩家的 EMC</b> —— 这是最坏的一类 bug。用探针守住这条真值表。</li>
 * </ol>
 */
public final class InterfaceWithdrawSelfTest {

    private InterfaceWithdrawSelfTest() {
    }

    public static List<String> run() {
        List<String> out = new ArrayList<>();
        int ok = 0;

        // ---- 1. MintingGuard：嵌套语义正确，退出后复位 ----
        try {
            boolean before = MintingGuard.isMinting();
            MintingGuard.enter();
            boolean outer = MintingGuard.isMinting();
            MintingGuard.enter();
            MintingGuard.exit();
            boolean stillInside = MintingGuard.isMinting();
            MintingGuard.exit();
            boolean after = MintingGuard.isMinting();

            if (!before && outer && stillInside && !after) {
                out.add("OK   铸造守卫：进入前=false → 外层=true → 内层退出后仍=true → 全部退出=false"
                        + "（保证接口兑换铸造的物品不会被存入折算钩子变回 EMC）");
                ok++;
            } else {
                out.add("FAIL 铸造守卫：before=" + before + " outer=" + outer
                        + " stillInside=" + stillInside + " after=" + after);
            }
        } catch (Throwable t) {
            out.add("FAIL 铸造守卫：" + t);
        }

        // ---- 2. ExtractContext 的保守默认 ----
        try {
            if (ExtractContext.isSimulate()) {
                out.add("OK   抽取上下文：无上下文时【保守判为模拟】（不扣费）——"
                        + "宁可功能不生效，也绝不错扣玩家的 EMC");
                ok++;
            } else {
                out.add("FAIL 抽取上下文：无上下文时应保守返回 true（判为模拟）");
            }
        } catch (Throwable t) {
            out.add("FAIL 抽取上下文默认值：" + t);
        }

        // ---- 3. 抽取 Mixin 真的应用了 + simulate 标志能到达钩子 ----
        try {
            DimensionsNet net = new DimensionsNet(true);
            UnifiedStorage storage = net.getUnifiedStorage();
            ItemStackKey key = new ItemStackKey(new ItemStack(Items.DIAMOND));

            long beforeCount = ExtractContext.enterCount();

            // 3a. 模拟抽取：钩子应看到 simulate=true
            InterfaceWithdrawService.enableProbe();
            storage.extract(key, 1L, true, false);
            boolean sawSimulate = InterfaceWithdrawService.probeSawSimulate();

            // 3b. 真实抽取：钩子应看到 simulate=false
            InterfaceWithdrawService.enableProbe();
            storage.extract(key, 1L, false, false);
            boolean sawReal = InterfaceWithdrawService.probeSawSimulate();
            InterfaceWithdrawService.disableProbe();

            long enters = ExtractContext.enterCount() - beforeCount;

            boolean mixinApplied = enters >= 2;
            boolean flagsCorrect = sawSimulate && !sawReal;

            if (mixinApplied && flagsCorrect) {
                out.add("OK   抽取 Mixin 与 simulate 真值表：extract() 进入上下文 " + enters + " 次；"
                        + "模拟抽取 → 钩子看到 simulate=true（不扣费），真实抽取 → simulate=false（可扣费）");
                ok++;
            } else if (!mixinApplied) {
                out.add("FAIL 抽取 Mixin 未生效：extract() 期间 ExtractContext 进入次数=" + enters
                        + "（应为 2）→ 网络接口兑换会【静默失效】。请核对 UnifiedStorageExtractMixin");
            } else {
                out.add("FAIL simulate 真值表错误：模拟抽取看到=" + sawSimulate
                        + "（应 true）、真实抽取看到=" + sawReal + "（应 false）——"
                        + "这会导致模拟抽取时真扣 EMC");
            }
        } catch (Throwable t) {
            out.add("FAIL 抽取 Mixin 探针：" + t);
        }

        // ---- 4. 配置默认值 ----
        try {
            boolean allowed = BeyondEmcConfig.allowInterfaceWithdraw();
            if (allowed) {
                out.add("OK   配置：allowInterfaceWithdraw=true（网络接口可兑换）。"
                        + "注意接口必须显式配置过滤器才会抽取，故不存在\"把网络抽干\"的隐患");
                ok++;
            } else {
                out.add("FAIL 配置：allowInterfaceWithdraw 默认应为 true（本次用户需求）");
            }
        } catch (Throwable t) {
            out.add("FAIL 配置 allowInterfaceWithdraw：" + t);
        }

        // ---- 5. ★ 刷物品漏洞的核心不变式：绝不铸造"带组件的请求对象" ----
        // 漏洞回顾：判定"已学习/价格"用的是归一化身份（附魔剑 → 普通剑），
        // 而最初铸造的是原始请求对象（附魔剑）→ 按普通剑的价拿到附魔剑。
        // 现在的契约：铸造对象必须由归一化身份重建，绝不等于带组件的请求对象。
        try {
            ItemStack plain = new ItemStack(Items.DIAMOND_SWORD);
            ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("测试之剑"));

            CanonicalExchange.Resolved plainResolved = CanonicalExchange.resolve(plain);
            CanonicalExchange.Resolved namedResolved = CanonicalExchange.resolve(named);

            ItemStackKey plainKey = new ItemStackKey(plain);
            ItemStackKey namedKey = new ItemStackKey(named);

            // 5a. 普通物品仍能正常兑换（不能因为修漏洞把正常路径也拒了）
            boolean plainOk = plainResolved != null
                    && new ItemStackKey(plainResolved.stack()).equals(plainKey);

            // 5b. 带组件的物品绝不能被铸造出来：要么解析失败，要么重建出的堆叠与请求对象不同
            boolean namedBlocked = namedResolved == null
                    || !new ItemStackKey(namedResolved.stack()).equals(namedKey);

            if (plainOk && namedBlocked) {
                out.add("OK   身份一致性：普通钻石剑可正常归一化兑换；"
                        + "改名/带组件物品【绝不会】被铸造出来"
                        + (namedResolved == null ? "（归一化直接失败）"
                                                 : "（归一化身份=" + namedResolved.info() + "，与请求对象不同）"));
                ok++;
            } else if (!plainOk) {
                out.add("FAIL 身份一致性：普通物品也无法归一化（plainResolved=" + plainResolved
                        + "）→ 正常兑换会被误拒");
            } else {
                out.add("FAIL 身份一致性：带组件的请求对象竟然能被原样铸造 → **存在刷物品漏洞**"
                        + "（namedResolved=" + namedResolved.info() + "）");
            }
        } catch (Throwable t) {
            out.add("FAIL 身份一致性：" + t);
        }

        // ---- 6. 买不起时必须拒绝而不是交半份 ----
        // （无法无头覆盖：需要 EMC 表就绪才能得到真实价格。留作实机验证项，此处只声明契约）
        out.add("SKIP 接口兑换的完整链路（扣费 + 铸造 + 取出）需要 EMC 表就绪，"
                + "须在游戏内用网络接口验证");

        out.add("---- 网络接口兑换自检结果：" + ok + "/5 项通过 ----");
        return out;
    }
}
