package com.zhuyuhang.beyondemc.diag;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mixin 注入目标的**运行时存活性核查**（阶段 7）。
 *
 * <h2>为什么需要它</h2>
 * 我们的 Mixin 绝大多数用 {@code require = 0} 优雅降级（架构文档风险 R8）：
 * BD 一旦改了方法名/字段名，注入会**静默失效** —— 没有报错、没有日志，
 * 只是"虚拟条目不见了""点击没反应"这类现象。而这类现象又很容易被误当成别的问题。
 *
 * <p>本类用反射按**字符串类名**逐个核查目标是否存在。刻意用字符串而不是直接引用类：
 * 客户端专有类（如 {@code BDBaseGUI}）在专用服务器上用 {@code Class.forName} 加载会被
 * NeoForge 的运行时 dist 清理器直接抛异常，所以必须①按端侧筛选、②把异常吃掉。
 *
 * <p>被核查的清单与各 Mixin 的注入点一一对应，**BD 升级后跑一次
 * {@code /beyondemc selftest} 或看启动日志即可知道有没有失配**，
 * 不必再去逐条读 `docs/plan/ROADMAP.md` §3.4 的回归清单。
 */
public final class MixinTargetCheck {

    /** 一项待核查的目标。{@code params} 为 null 表示查字段。 */
    private record Target(String className, boolean clientOnly, String member, String... params) {
    }

    /** 只在某个可选模组存在时才核对的注入目标。 */
    private record OptionalTarget(String requiredMod, String className, String member, String... params) {
    }

    /**
     * 可选集成（目前是 JEI）的注入目标。
     *
     * <p>为什么需要单独一组：JEI 的类**不该被本模组的任何类引用**（否则 JEI 缺席时会
     * {@code NoClassDefFoundError}），所以这里一律用**字符串类名 + 反射**。
     * 而 {@code JeiFillSelfTest} 只能验证纯逻辑，验证不了"注入点还在不在"——
     * 那正是这次 B 首轮失效时无法判断的那一环。
     */
    private static final List<OptionalTarget> OPTIONAL_TARGETS = List.of(
            new OptionalTarget("jei",
                    "com.wintercogs.beyonddimensions.integration.module.jei.transfer.TransferHelper",
                    "transferRecipe",
                    "java.util.List", "java.util.List", "java.util.List",
                    "mezz.jei.api.gui.ingredient.IRecipeSlotsView",
                    "boolean", "boolean", "boolean")
    );

    private static final List<Target> TARGETS = List.of(
            // 读档守卫（@WrapMethod，唯一 require = 1）
            new Target("com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler",
                    false, "deserializeNBT",
                    "net.minecraft.core.HolderLookup$Provider", "net.minecraft.nbt.CompoundTag"),
            // 知识持久化（save / load / mergeOtherNet）
            new Target("com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet",
                    false, "save",
                    "net.minecraft.nbt.CompoundTag", "net.minecraft.core.HolderLookup$Provider"),
            new Target("com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet",
                    false, "load",
                    "net.minecraft.nbt.CompoundTag", "net.minecraft.core.HolderLookup$Provider"),
            new Target("com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet",
                    false, "mergeOtherNet",
                    "com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet"),
            // 界面列表注入（buildIndexList HEAD + updateViewerStorage RETURN）
            new Target("com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu",
                    false, "buildIndexList"),
            new Target("com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu",
                    false, "updateViewerStorage", "boolean"),
            // 抽取上下文：记录 simulate 标志（网络接口兑换安全性的前提）
            new Target("com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage",
                    false, "extract",
                    "com.wintercogs.beyonddimensions.api.storage.key.IStackKey",
                    "long", "boolean", "boolean"),
            // 客户端视图：排序缓存字段 + 搜索过滤方法 + 真实存储字段（Accessor/Invoker）
            new Target("com.wintercogs.beyonddimensions.common.menu.widget.ClientNetStorage",
                    true, "cacheIndexes", (String[]) null),
            new Target("com.wintercogs.beyonddimensions.common.menu.widget.ClientNetStorage",
                    true, "matchFilter", "com.wintercogs.beyonddimensions.api.storage.key.IStackKey"),
            new Target("com.wintercogs.beyonddimensions.common.menu.widget.ClientNetStorage",
                    true, "sourceStorage", (String[]) null),
            // 点击拦截
            new Target("com.wintercogs.beyonddimensions.client.gui.BDBaseGUI",
                    true, "slotClicked",
                    "net.minecraft.world.inventory.Slot", "int", "int",
                    "net.minecraft.world.inventory.ClickType")
    );

    private MixinTargetCheck() {
    }

    /** @return 每行一条结论，以 OK / FAIL / SKIP 开头 */
    public static List<String> run() {
        List<String> out = new ArrayList<>();
        boolean dedicatedServer = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
        Map<String, String> failures = new LinkedHashMap<>();
        int checked = 0;
        int skipped = 0;

        for (Target t : TARGETS) {
            String label = t.className().substring(t.className().lastIndexOf('.') + 1) + "#" + t.member();
            if (t.clientOnly() && dedicatedServer) {
                skipped++;
                continue; // 端侧不适用：专用服务器上这些类不该被加载
            }
            try {
                Class<?> owner = Class.forName(t.className(), false,
                        MixinTargetCheck.class.getClassLoader());
                if (t.params() == null) {
                    owner.getDeclaredField(t.member());
                } else {
                    owner.getDeclaredMethod(t.member(), resolve(t.params()));
                }
                checked++;
            } catch (Throwable e) {
                String reason = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                failures.put(label, reason);
            }
        }

        // ---- 可选集成（JEI）的注入目标 ----
        // 这是"B 到底有没有接上"唯一不依赖 JEI 交互的判据：客户端启动即核对。
        int optionalChecked = 0;
        int optionalSkipped = 0;
        for (OptionalTarget t : OPTIONAL_TARGETS) {
            if (!isModLoaded(t.requiredMod())) {
                optionalSkipped++;
                continue;
            }
            String label = "[" + t.requiredMod() + "] "
                    + t.className().substring(t.className().lastIndexOf('.') + 1) + "#" + t.member();
            try {
                Class<?> owner = Class.forName(t.className(), false,
                        MixinTargetCheck.class.getClassLoader());
                owner.getDeclaredMethod(t.member(), resolve(t.params()));
                optionalChecked++;
            } catch (Throwable e) {
                String reason = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                failures.put(label, reason);
            }
        }

        if (failures.isEmpty()) {
            out.add("OK   Mixin 目标存活性：核对了 " + checked + " 个目标，全部存在"
                    + (skipped > 0 ? "（" + skipped + " 个客户端专项目标在专用服务器上跳过）" : ""));
            if (optionalChecked > 0 || optionalSkipped > 0) {
                out.add("     （可选集成：已核对 " + optionalChecked + " 个，"
                        + optionalSkipped + " 个因对应模组未加载而跳过）");
            }
        } else {
            out.add("FAIL Mixin 目标存活性：以下目标在当前的 Beyond Dimensions 版本里【已不存在】——"
                    + "对应的 Mixin 会静默失效，功能会无声消失：");
            failures.forEach((k, v) -> out.add("      - " + k + "  →  " + v));
        }
        return out;
    }

    /** 该模组是否已加载。用字符串 modId，避免对可选模组产生编译期依赖。 */
    private static boolean isModLoaded(String modId) {
        try {
            return net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?>[] resolve(String[] names) throws ClassNotFoundException {
        Class<?>[] types = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            types[i] = switch (names[i]) {
                case "boolean" -> boolean.class;
                case "int" -> int.class;
                case "long" -> long.class;                default -> Class.forName(names[i], false, MixinTargetCheck.class.getClassLoader());
            };
        }
        return types;
    }
}
