package com.zhuyuhang.beyondemc.mixin;

import com.zhuyuhang.beyondemc.BeyondEmc;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * JEI 相关 Mixin 的**门控**。
 *
 * <h2>为什么必须有它</h2>
 * {@code TransferHelperMixin} 的目标类
 * {@code com.wintercogs.beyonddimensions.integration.module.jei.transfer.TransferHelper}
 * 在**方法签名与字段类型上都引用了 JEI 的类**。玩家没装 JEI 时：
 * <ul>
 *   <li>目标类本身不会被加载（BD 的 JEI 集成模块没启用）；</li>
 *   <li>但 Mixin 在**准备阶段**就会尝试加载我们的 mixin 类来读取注解，
 *       而该类的 handler 签名含 JEI 类型 → {@code NoClassDefFoundError} → <b>可能直接崩游戏</b>。</li>
 * </ul>
 * 所以必须让 Mixin **在 JEI 缺席时整体跳过**这个配置。
 *
 * <h2>⚠️ 门控判据为什么用"类路径"而不是 ModList</h2>
 * 最初写的是 {@code ModList.get().isLoaded("jei")}，实测**失效**：
 * 目标方法确实存在（启动核查报"已核对 1 个"），但 {@code JEI 转移钩子被调用} 一次都没打 ——
 * 也就是这个配置被整体跳过了。
 *
 * <p>原因：{@link #shouldApplyMixin} 会在 Mixin **配置准备阶段**被调用，
 * 那时 {@code ModList} 未必已经就绪；一旦那时判为 false 并被缓存，
 * 这个 Mixin 就**永远不会应用**（而 {@code required = false} 让整件事毫无报错）。
 *
 * <p>改用 {@code Class.forName} 探测 JEI 的入口类：它只看类路径，
 * 与模组加载阶段无关，因此不受调用时机影响。
 *
 * <p>本类刻意**不引用任何 JEI 类型**（只用字符串类名），因此任何环境下都能安全加载。
 */
public final class BeyondEmcJeiMixinPlugin implements IMixinConfigPlugin {

    /** JEI 的入口接口。用字符串写，避免编译期依赖。 */
    private static final String JEI_ENTRY_CLASS = "mezz.jei.api.IModPlugin";

    private static Boolean jeiPresent;

    private static boolean gateLogged;

    /** 只在第一次真正需要判断时探测一次，并在日志里留痕。 */
    private static boolean detectJei() {
        Boolean cached = jeiPresent;
        if (cached != null) {
            return cached;
        }
        boolean present;
        try {
            Class.forName(JEI_ENTRY_CLASS, false, BeyondEmcJeiMixinPlugin.class.getClassLoader());
            present = true;
        } catch (Throwable t) {
            present = false; // 探测不到就当没装：宁可功能不生效，也不要崩
        }
        jeiPresent = present;
        return present;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // 这一行本身就是判据：它出现 = NeoForge 确实加载了这个 mixin 配置。
        // （若 mods.toml 的第二个 [[mixins]] 块不被支持，这里就不会有任何输出。）
        BeyondEmc.LOGGER.info("[BeyondEMC] JEI Mixin 配置已加载（package={}）", mixinPackage);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean present = detectJei();
        if (!gateLogged) {
            gateLogged = true;
            BeyondEmc.LOGGER.info("[BeyondEMC] JEI Mixin 门控判定：jei 在类路径上={}（判据={}），目标={}",
                    present, JEI_ENTRY_CLASS, targetClassName);
        }
        return present;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // 无跨配置目标
    }

    @Override
    public List<String> getMixins() {
        return null; // 用配置文件里声明的清单
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
        // 无前置处理
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        BeyondEmc.LOGGER.info("[BeyondEMC] JEI Mixin 已应用到 {}（{}）", targetClassName, mixinClassName);
    }
}
