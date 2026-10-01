package com.zhuyuhang.beyondemc.mixin;

import com.zhuyuhang.beyondemc.BeyondEmc;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Create（机械动力）相关 Mixin 的**门控**。
 *
 * <h2>门控要挡的是什么</h2>
 * 我们的目标类是 Beyond Dimensions 的
 * {@code ...integration.module.create.block.entity.SchematicannonPathWayBlockEntity$NetedSchematicannonItemHandler}。
 * 它<b>本身</b>的字段与方法只引用 Minecraft / BD 类型（这一点很关键，见下），
 * 但它的<b>外层类</b> {@code SchematicannonPathWayBlockEntity} 的签名引用了 Create 的
 * {@code MaterialChecklist} / {@code SchematicannonBlockEntity}。
 * 而 BD 的 {@code CreateModule} 标了 {@code @BDIntegrationModule(modId = OtherModIds.CREATE)}
 * —— Create 缺席时整个模块不注册。
 *
 * <p>所以：不装 Create 时，这个方块实体没有任何东西会去加载它，Mixin 自然也不会被应用。
 * 门控在这里是**双保险 + 可观测性**：万一哪天 BD 改成无条件加载，
 * 我们也不会把一段依赖「Create 语境」的注入强行贴上去，而且启动日志里能一眼看出裁决结果。
 *
 * <h2>⚠️ 判据为什么用「类路径探测」而不是 {@code ModList}</h2>
 * 与 {@link BeyondEmcJeiMixinPlugin} 同源：{@link #shouldApplyMixin} 在 Mixin
 * <b>配置准备阶段</b>就会被调用，那时 {@code ModList} 未必就绪；一旦那时判为 false 并被缓存，
 * 这个 Mixin 就<b>永远不会应用</b>（而 {@code required = false} 让整件事毫无报错）。
 * 那个坑在本项目的 JEI 集成上已经实测踩过一次。
 *
 * <h2>⚠️ 探测类为什么挑一个「接口」</h2>
 * 用 {@code Class.forName(name, false, loader)} 探测类路径。挑的类越"重"，
 * 加载它需要解析的父子类 / 接口越多 —— 比如探测 {@code SchematicannonBlockEntity}
 * 会牵出 Create 一大串继承链与 flywheel 接口，中间任何一个没就位就抛
 * {@code NoClassDefFoundError}，我们便会把它误判成「Create 不在场」，功能静默消失。
 * 故选 Create 自己的一个<b>接口</b>（{@code MovementBehaviour}）—— 它就是 BD 的 Create 模块
 * 直接 import 的类型之一，存在性与「Create 可用」强相关，且加载时几乎不需要解析别的东西。
 *
 * <p>本类刻意<b>不引用任何 Create 类型</b>（只用字符串类名），因此任何环境下都能安全加载。
 */
public final class BeyondEmcCreateMixinPlugin implements IMixinConfigPlugin {

    /** 判据类：Create 的一个接口，见类注释「探测类为什么挑一个接口」。 */
    private static final String CREATE_PROBE_CLASS =
            "com.simibubi.create.api.behaviour.movement.MovementBehaviour";

    private static Boolean createPresent;

    private static boolean gateLogged;

    /** 只在第一次真正需要判断时探测一次，并在日志里留痕。 */
    private static boolean detectCreate() {
        Boolean cached = createPresent;
        if (cached != null) {
            return cached;
        }
        boolean present;
        try {
            Class.forName(CREATE_PROBE_CLASS, false, BeyondEmcCreateMixinPlugin.class.getClassLoader());
            present = true;
        } catch (Throwable t) {
            present = false; // 探测不到就当没装：宁可功能不生效，也不要崩
        }
        createPresent = present;
        return present;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // 这一行本身就是判据：它出现 = NeoForge 确实加载了这个 mixin 配置。
        BeyondEmc.LOGGER.info("[BeyondEMC] Create Mixin 配置已加载（package={}）", mixinPackage);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean present = detectCreate();
        if (!gateLogged) {
            gateLogged = true;
            BeyondEmc.LOGGER.info("[BeyondEMC] Create Mixin 门控判定：create 在类路径上={}（判据={}），目标={}",
                    present, CREATE_PROBE_CLASS, targetClassName);
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
        BeyondEmc.LOGGER.info("[BeyondEMC] Create Mixin 已应用到 {}（{}）", targetClassName, mixinClassName);
    }
}
