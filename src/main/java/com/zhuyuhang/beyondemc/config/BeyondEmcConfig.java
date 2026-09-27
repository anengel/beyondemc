package com.zhuyuhang.beyondemc.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 模组配置（服务端）。
 *
 * <p><b>读取一律走本类的静态方法</b>，它们在配置尚未加载时返回<b>默认值</b>而不是抛异常。
 * 原因：折算钩子挂在 BD 的存储插入路径上，理论上可能在配置加载完成前被碰到；
 * {@code ModConfigSpec.ConfigValue#get()} 在未加载时会抛 {@code IllegalStateException}，
 * 那会让整个插入路径炸掉 —— 而"按默认策略继续"是安全得多的失败模式。
 */
public final class BeyondEmcConfig {

    /** 兑换成功后的提示方式。 */
    public enum FeedbackMode {
        /** 什么都不提示（默认）—— 实测反馈不要屏幕上有弹窗。 */
        NONE,
        /** 聊天栏。 */
        CHAT,
        /** 动作栏（快捷栏上方）。 */
        ACTION_BAR
    }

    public static final ModConfigSpec SPEC;

    private static final Server SERVER;

    static {
        // 刻意不用 ModConfigSpec.Builder#configure(...)：它的返回类型（Pair）在不同
        // NeoForge 版本里位置/名字有过变动，显式 builder 写法最稳。
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SERVER = new Server(builder);
        SPEC = builder.build();
    }

    private BeyondEmcConfig() {
    }

    // ------------------------------------------------------------------
    // 折算
    // ------------------------------------------------------------------

    /** 折算总开关。关闭后所有物品按 BD 原逻辑入库。 */
    public static boolean enableEmcDeposit() {
        return get(() -> SERVER.enableEmcDeposit.get(), true);
    }

    /**
     * 是否折算"带非默认数据组件"的物品（附魔、耐久、储能、自定义名、容器内容物…）。
     *
     * <p><b>默认为 false（不折算，原样入库）</b>，因为折算会<b>不可逆地销毁组件信息</b>：
     * 一把锋利 V 的钻石剑会被按"钻石剑 + 组件加成"计价，但拿不回来。
     * 详见架构文档风险 R4。
     */
    public static boolean convertComponentItems() {
        return get(() -> SERVER.convertComponentItems.get(), false);
    }

    /** 黑名单：命中则**不折算**。支持物品 id 与 {@code #命名空间:标签}。 */
    public static List<String> emcDepositBlacklist() {
        return get(() -> List.copyOf(SERVER.emcDepositBlacklist.get()), List.of());
    }

    /**
     * 白名单：**非空时只折算命中的物品**（优先级高于黑名单）。
     * 支持物品 id 与 {@code #命名空间:标签}。
     */
    public static List<String> emcDepositWhitelist() {
        return get(() -> List.copyOf(SERVER.emcDepositWhitelist.get()), List.of());
    }

    // ------------------------------------------------------------------
    // 兑换
    // ------------------------------------------------------------------

    /** 是否在界面里注入"无库存的已学习物品"虚拟条目。 */
    public static boolean showVirtualEntries() {
        return get(() -> SERVER.showVirtualEntries.get(), true);
    }

    /**
     * 兑换是否必须命中网络的学习集合。
     *
     * <p><b>默认 true</b>。改成 false 就等于"任何有 EMC 价值的物品都能直接换出来"，
     * 会绕开本模组最核心的规则（存入才能取出），不建议关闭。
     */
    public static boolean exchangeRequiresKnowledge() {
        return get(() -> SERVER.exchangeRequiresKnowledge.get(), true);
    }

    /** 单次点击兑换的数量上限（防止误操作/界面异常导致的巨额请求）。 */
    public static long maxExchangePerClick() {
        return get(() -> SERVER.maxExchangePerClick.get(), Long.MAX_VALUE);
    }

    /** 是否允许网络接口用 EMC 兑换物品（自动化向）。 */
    public static boolean allowInterfaceWithdraw() {
        return get(() -> SERVER.allowInterfaceWithdraw.get(), true);
    }

    /** 兑换成功后的提示方式。 */
    public static FeedbackMode exchangeFeedback() {
        return get(() -> SERVER.exchangeFeedback.get(), FeedbackMode.NONE);
    }

    // ------------------------------------------------------------------

    /** 配置未加载时回落到默认值，绝不让配置读取把调用方炸掉。 */
    private static <T> T get(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static final class Server {

        public final ModConfigSpec.BooleanValue enableEmcDeposit;
        public final ModConfigSpec.BooleanValue convertComponentItems;
        public final ModConfigSpec.ConfigValue<List<? extends String>> emcDepositBlacklist;
        public final ModConfigSpec.ConfigValue<List<? extends String>> emcDepositWhitelist;
        public final ModConfigSpec.BooleanValue showVirtualEntries;
        public final ModConfigSpec.BooleanValue exchangeRequiresKnowledge;
        public final ModConfigSpec.LongValue maxExchangePerClick;
        public final ModConfigSpec.BooleanValue allowInterfaceWithdraw;
        public final ModConfigSpec.EnumValue<FeedbackMode> exchangeFeedback;

        @SuppressWarnings("unchecked")
        Server(ModConfigSpec.Builder builder) {
            builder.comment("Beyond EMC —— 服务端配置").push("deposit");
            enableEmcDeposit = builder
                    .comment("存入物品时，把有 EMC 价值的物品折算成网络 EMC。关闭则全部按原逻辑入库。")
                    .define("enableEmcDeposit", true);
            convertComponentItems = builder
                    .comment("是否折算带非默认数据组件的物品（附魔/耐久/储能/自定义名/容器内容物）。",
                            "false（默认）：这类物品原样入库，组件信息不会被销毁。",
                            "true：按 ProjectE 的组件加成规则计价后折算，组件信息会丢失。")
                    .define("convertComponentItems", false);
            emcDepositBlacklist = builder
                    .comment("黑名单：命中的物品【不折算】，原样入库。",
                            "每项可以是物品 id（minecraft:diamond）或标签（#minecraft:swords）。")
                    .defineListAllowEmpty("emcDepositBlacklist", List.of(), () -> "", o -> o instanceof String);
            emcDepositWhitelist = builder
                    .comment("白名单：**非空时只折算命中的物品**，优先级高于黑名单。",
                            "每项可以是物品 id（minecraft:diamond）或标签（#minecraft:swords）。")
                    .defineListAllowEmpty("emcDepositWhitelist", List.of(), () -> "", o -> o instanceof String);
            builder.pop();

            builder.comment("兑换（用网络 EMC 换出物品）").push("exchange");
            showVirtualEntries = builder
                    .comment("是否在维度网络界面里显示\"已学习但无库存\"的物品（可直接用 EMC 兑换）。")
                    .define("showVirtualEntries", true);
            exchangeRequiresKnowledge = builder
                    .comment("兑换是否必须命中网络的学习集合。",
                            "true（默认，强烈建议）：只有存入过的物品才能换出。",
                            "false：任何有 EMC 价值的物品都能换出 —— 会绕开本模组最核心的规则。")
                    .define("exchangeRequiresKnowledge", true);
            maxExchangePerClick = builder
                    .comment("单次点击兑换的数量上限（防止误操作或界面异常导致的巨额请求）。")
                    .defineInRange("maxExchangePerClick", Long.MAX_VALUE, 1L, Long.MAX_VALUE);
            allowInterfaceWithdraw = builder
                    .comment("是否允许【超越维度的网络接口】用网络 EMC 兑换物品（自动化向）。",
                            "true（默认）：接口里配置了某物品的过滤器后，若网络已学会该物品但无库存，",
                            "  接口会按购买价扣网络 EMC 并产出该物品 —— 相当于把界面兑换开放给自动化。",
                            "  注意：接口【必须显式配置过滤器】才会抽取（BD 对空过滤器直接跳过），",
                            "  且每个槽位每周期最多取一整堆，因此不会出现\"接口把网络抽干\"的情况。",
                            "false：接口只输出网络真实库存里的东西（完全回到原版 BD 行为）。")
                    .define("allowInterfaceWithdraw", true);
            exchangeFeedback = builder
                    .comment("兑换成功后的提示方式：NONE（默认，不提示）/ CHAT（聊天栏）/ ACTION_BAR（快捷栏上方）。",
                            "失败提示始终走聊天栏，不受此项影响。")
                    .defineEnum("exchangeFeedback", FeedbackMode.NONE);
            builder.pop();
        }
    }
}
