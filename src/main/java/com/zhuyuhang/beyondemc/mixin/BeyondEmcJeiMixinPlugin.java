package com.zhuyuhang.beyondemc.mixin;

import net.neoforged.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * JEI 相关 Mixin 的**端侧/依赖门控**。
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
 * 所以必须让 Mixin **在 JEI 缺席时整体跳过**这个配置，而不是指望它自己优雅失败。
 *
 * <h2>为什么懒判断</h2>
 * {@code shouldApplyMixin} 里才去问 {@link ModList}，而不是在 {@code onLoad} 里缓存 ——
 * {@code onLoad} 的调用时机早于我们愿意假设的模组加载阶段，
 * 懒判断只多一次 map 查询，却避免了对时机的假设。
 *
 * <p>本类**刻意不引用任何 JEI 类型**，因此它在任何环境下都能安全加载。
 */
public final class BeyondEmcJeiMixinPlugin implements IMixinConfigPlugin {

    private static final String JEI_MOD_ID = "jei";

    private static Boolean jeiPresent;

    private static boolean isJeiPresent() {
        Boolean cached = jeiPresent;
        if (cached == null) {
            boolean present;
            try {
                present = ModList.get() != null && ModList.get().isLoaded(JEI_MOD_ID);
            } catch (Throwable t) {
                present = false; // 拿不到就当没装：宁可功能不生效，也不要崩
            }
            cached = present;
            jeiPresent = cached;
        }
        return cached;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // 无需预热：见类注释里"为什么懒判断"
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isJeiPresent();
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
        // 无后置处理
    }
}
