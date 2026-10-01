package com.zhuyuhang.beyondemc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.diag.CannonDump;
import com.zhuyuhang.beyondemc.diag.EmcStorageSelfTest;
import com.zhuyuhang.beyondemc.diag.MaterializeSelfTest;
import com.zhuyuhang.beyondemc.diag.Phase3SelfTest;
import com.zhuyuhang.beyondemc.diag.Phase4SelfTest;
import com.zhuyuhang.beyondemc.emc.EmcDepositHandler;
import com.zhuyuhang.beyondemc.emc.EmcStackKey;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.exchange.ExchangeService;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import com.zhuyuhang.beyondemc.materialize.ItemMaterializer;
import com.zhuyuhang.beyondemc.materialize.MaterializeQuote;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /beyondemc} 命令树。
 *
 * <p>设计原则：<b>诊断类子命令必须在控制台也能跑</b>（{@code ping} / {@code selftest}），
 * 这样无头环境（CI、{@code runServer}）不需要玩家就能验证；只有真正操作某个网络的
 * 子命令（{@code emc ...} / {@code knowledge ...} / {@code exchange ...}）才要求玩家。
 */
public final class BeyondEmcCommands {

    private BeyondEmcCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("beyondemc")
                .then(Commands.literal("ping").executes(BeyondEmcCommands::ping))
                .then(Commands.literal("selftest").executes(BeyondEmcCommands::selfTest))
                .then(Commands.literal("exchange")
                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(BeyondEmcCommands::exchange))))
                .then(Commands.literal("emc")
                        .then(Commands.literal("query").executes(BeyondEmcCommands::emcQuery))
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                        .executes(ctx -> emcChange(ctx, true))))
                        .then(Commands.literal("spend")
                                .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                        .executes(ctx -> emcChange(ctx, false)))))
                .then(Commands.literal("knowledge")
                        .then(Commands.literal("list").executes(BeyondEmcCommands::knowledgeList))
                        .then(Commands.literal("clear").executes(BeyondEmcCommands::knowledgeClear)))
                .then(Commands.literal("materialize")
                        .then(Commands.literal("list").executes(BeyondEmcCommands::materializeList))
                        .then(Commands.literal("rebuild").executes(BeyondEmcCommands::materializeRebuild))
                        .then(Commands.literal("clear").executes(BeyondEmcCommands::materializeClear)))
                .then(Commands.literal("why")
                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                .executes(BeyondEmcCommands::why)))
                .then(Commands.literal("cannon")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(BeyondEmcCommands::cannon))))));
    }

    // ------------------------------------------------------------------
    // 诊断命令（控制台可用）
    // ------------------------------------------------------------------

    private static int ping(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int ok = 0;

        if (EmcStorageSelfTest.isTypeRegistered()) {
            source.sendSuccess(msg("OK   资源类型已注册：" + EmcStackKey.ID), false);
            ok++;
        } else {
            source.sendFailure(Component.literal("[BeyondEMC] FAIL 资源类型未注册：" + EmcStackKey.ID));
        }

        try {
            long buy = IEMCProxy.INSTANCE.getValue(ItemInfo.fromItem(Items.DIAMOND));
            long sell = IEMCProxy.INSTANCE.getSellValue(ItemInfo.fromItem(Items.DIAMOND));
            boolean ready = EmcAvailability.isReady();
            source.sendSuccess(msg("OK   等价交换：钻石 购买价=" + buy + "，回收价=" + sell
                    + "，EMC表就绪=" + ready + "，remap次数=" + EmcAvailability.remapCount()
                    + (ready ? "" : "（尚无人登录或未执行 /reload —— 此时折算全部按原样入库，属预期）")), false);
            ok++;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("[BeyondEMC] FAIL 等价交换：" + t));
        }

        try {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                source.sendSuccess(msg("SKIP 超越维度：控制台执行，无玩家故跳过网络查询"), false);
            } else {
                DimensionsNet net = DimensionsNet.getPrimaryNetFromPlayer(player);
                if (net == null) {
                    source.sendSuccess(msg("OK   超越维度：已联通，但该玩家不属于任何维度网络"), false);
                } else {
                    source.sendSuccess(msg("OK   超越维度：网络 id=" + net.getId()
                            + "，名称=" + net.getNetworkName().getString()
                            + "，EMC=" + NetEmcAccessor.getEmc(net)
                            + "，已学习=" + NetKnowledgeStore.snapshot(net).size() + " 项"
                            + "，已存资源种类数=" + net.getUnifiedStorage().getStorage().size()), false);
                }
                ok++;
            }
        } catch (Throwable t) {
            source.sendFailure(Component.literal("[BeyondEMC] FAIL 超越维度：" + t));
        }

        source.sendSuccess(msg("折算统计：累计物品 " + EmcDepositHandler.convertedItemCount()
                + " 个，累计 EMC " + EmcDepositHandler.convertedEmcTotal()), false);

        final int passed = ok;
        source.sendSuccess(msg("---- ping：" + passed + "/3 项通过 ----"), false);
        return passed;
    }

    private static int selfTest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<String> lines = new ArrayList<>();
        lines.add("---- 存储层（阶段 2）----");
        lines.addAll(EmcStorageSelfTest.run(source.registryAccess()));
        lines.add("---- 折算与学习集（阶段 3）----");
        lines.addAll(Phase3SelfTest.run(source.registryAccess()));
        lines.add("---- 兑换服务（阶段 4）----");
        lines.addAll(Phase4SelfTest.run());
        lines.add("---- 物化：物品真实存在于网络中（0.3.0）----");
        lines.addAll(MaterializeSelfTest.run(source.registryAccess()));

        boolean anyFailure = false;
        for (String line : lines) {
            if (line.startsWith("FAIL")) {
                anyFailure = true;
                source.sendFailure(Component.literal("[BeyondEMC] " + line));
            } else {
                source.sendSuccess(msg(line), false);
            }
        }
        return anyFailure ? 0 : 1;
    }

    // ------------------------------------------------------------------
    // 操作网络余额的命令（需要玩家）
    // ------------------------------------------------------------------

    private static int emcQuery(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionsNet net = requireNet(source);
        if (net == null) {
            return 0;
        }
        source.sendSuccess(msg("网络 " + net.getId() + " 的 EMC 余额 = " + NetEmcAccessor.getEmc(net)), false);
        return 1;
    }

    private static int emcChange(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSourceStack source = ctx.getSource();
        DimensionsNet net = requireNet(source);
        if (net == null) {
            return 0;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        long changed = add ? NetEmcAccessor.addEmc(net, amount) : NetEmcAccessor.spendEmc(net, amount);
        long after = NetEmcAccessor.getEmc(net);

        if (changed != amount) {
            source.sendSuccess(msg("实际变动与请求不符：请求 " + amount + "，实际 " + changed
                    + "（受单键容量或余额限制）"), false);
        }
        source.sendSuccess(msg("网络 " + net.getId() + " 的 EMC 余额 = " + after
                + "（本次" + (add ? "+" : "-") + changed + "）"), true);
        return 1;
    }

    private static int knowledgeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionsNet net = requireNet(source);
        if (net == null) {
            return 0;
        }
        var known = NetKnowledgeStore.snapshot(net);
        source.sendSuccess(msg("网络 " + net.getId() + " 已学习 " + known.size() + " 项物品"), false);
        int shown = 0;
        for (ItemInfo info : known) {
            if (shown++ >= 30) {
                source.sendSuccess(msg("…（其余 " + (known.size() - 30) + " 项已省略）"), false);
                break;
            }
            source.sendSuccess(msg("  - " + info), false);
        }
        return 1;
    }

    private static int knowledgeClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionsNet net = requireNet(source);
        if (net == null) {
            return 0;
        }
        int before = NetKnowledgeStore.snapshot(net).size();
        NetKnowledgeStore.clear(net);
        source.sendSuccess(msg("已清空网络 " + net.getId() + " 的学习集合（原有 " + before + " 项）"), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // 物化（0.3.0）
    // ------------------------------------------------------------------

    /**
     * {@code /beyondemc materialize list} —— 列出物化条目的**当前真实内容**与**应然内容**。
     *
     * <p>两者都打印，因为"物化条目是否与权威数据一致"是这个功能最容易出问题的地方，
     * 而这条命令是唯一能在实机上直接读出这件事的手段。
     */
    private static int materializeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionsNet net = requireNet(source);
        if (net == null) {
            return 0;
        }

        var current = ItemMaterializer.currentEntries(net);
        var desired = ItemMaterializer.desiredEntries(net);

        source.sendSuccess(msg("网络 " + net.getId() + " EMC=" + NetEmcAccessor.getEmc(net)
                + "，物化条目 " + current.size() + " 类（应然 " + desired.size() + " 类）"), false);

        int shown = 0;
        for (var e : current.entrySet()) {
            if (shown++ >= 30) {
                source.sendSuccess(msg("…（其余 " + (current.size() - 30) + " 类已省略）"), false);
                break;
            }
            long want = desired.getOrDefault(e.getKey(), 0L);
            String mark = want == e.getValue() ? "" : "  ← 与应然不符（应然 " + want + "）";
            source.sendSuccess(msg("  - " + e.getKey() + " ×" + e.getValue() + mark), false);
        }
        if (current.isEmpty()) {
            source.sendSuccess(msg("  （当前没有任何物化条目）"), false);
        }
        return 1;
    }

    /**
     * {@code /beyondemc materialize rebuild} —— 从权威数据（EMC + 学习集合 + 价格 + 真实库存）
     * 重新推导全部物化条目。
     *
     * <p>这就是"物化条目是可随时丢弃重建的纯函数派生"的直接体现：先清空、再重算。
     */
    private static int materializeRebuild(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionsNet net = requireNet(source);
        if (net == null) {
            return 0;
        }
        int cleared = ItemMaterializer.clear(net);
        ItemMaterializer.refresh(net);
        int after = ItemMaterializer.currentEntries(net).size();
        source.sendSuccess(msg("已按权威数据重建网络 " + net.getId() + " 的物化条目："
                + "清掉 " + cleared + " 类，重建 " + after + " 类"), true);
        return 1;
    }

    /** {@code /beyondemc materialize clear} —— 清空物化条目（等价于临时把 L1 熔断的动作用命令执行一次）。 */
    private static int materializeClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DimensionsNet net = requireNet(source);
        if (net == null) {
            return 0;
        }
        // 先撤掉所有已排队的刷新，否则刚清完就会被队列里的任务重建回来
        ItemMaterializer.cancelPending(net);
        int cleared = ItemMaterializer.clear(net);
        source.sendSuccess(msg("已清空网络 " + net.getId() + " 的物化条目（" + cleared + " 类）。"
                + "注意：任何一次 EMC 变化或打开界面都会按权威数据重新生成它们（这是设计如此）"), true);
        return 1;
    }

    /**
     * {@code /beyondemc why <item>} —— 逐条打印"这个物品现在能不能被取用、卡在哪一道闸"。
     *
     * <h2>为什么需要它</h2>
     * 0.3.2 收到过一条实测反馈：「蓝图大炮的清单只有部分材料满足条件，部分显示无库存」。
     * 在实机上看，这两种状态**长得一模一样** —— 大炮只显示"无库存"，不告诉你原因。
     * 而原因至少有六种，且分属完全不同的处理方向：
     * <ul>
     *   <li><b>未学习</b>（网络学习集合里没有）→ 把该物品存进网络即可（存入即折算即学习）；</li>
     *   <li><b>无 EMC 价值</b>（ProjectE 表里是 0）→ 设计如此，永远取不到；</li>
     *   <li><b>余额不足</b>（单价 &gt; 网络 EMC）→ 往网络里存 EMC 即可；</li>
     *   <li><b>被黑白名单 / 组件策略排除</b>→ 改配置；</li>
     *   <li><b>已有真实库存</b>→ 走 BD 原生行，本来就能取；</li>
     *   <li><b>物化条目缺失</b>→ 派生数据暂时陈旧，<b>不影响取用</b>（0.3.2 起已如此）。</li>
     * </ul>
     *
     * <p>本命令把这几条逐条打印，最后给出结论 —— 结论<b>只由
     * {@link MaterializeQuote#externalDeliverable} 给出</b>（与真实扣费同一入口），
     * 中间几行只是解释为什么。这样命令的输出不可能与真实行为分叉。
     */
    private static int why(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        DimensionsNet net = requireNet(source);
        if (net == null) {
            return 0;
        }

        ItemStack template = ItemArgument.getItem(ctx, "item").createItemStack(1, false);
        source.sendSuccess(msg("问：" + template.getItem() + " 现在能不能被取用？"), false);

        // ① 归一化身份
        var resolved = com.zhuyuhang.beyondemc.exchange.CanonicalExchange.resolve(template);
        if (resolved == null) {
            source.sendFailure(Component.literal("[BeyondEMC] 结论：身份归一化失败 —— "
                    + "ProjectE 得不到稳定身份，一律不可取用（防刷物品）"));
            return 0;
        }
        ItemInfo info = resolved.info();
        source.sendSuccess(msg("① 归一化身份：" + info + "（定价与铸造都按这个身份）"), false);

        // ②③ 配置与学习
        boolean ready = EmcAvailability.isReady();
        boolean knows = NetKnowledgeStore.knows(net, info);
        source.sendSuccess(msg("② 配置：允许接口/第三方取用=" + BeyondEmcConfig.allowInterfaceWithdraw()
                + "；EMC 表就绪=" + ready + (ready ? "" : "（尚无人登录或未 /reload，此时一律不可取用）")), false);
        boolean needKnow = BeyondEmcConfig.exchangeRequiresKnowledge();
        source.sendSuccess(msg("③ 要求已学习=" + needKnow + "；本网络已学习=" + knows
                + (needKnow && !knows ? "   ← 未学习：把该物品**存进这个网络**即可学会（存入即折算、即学习）" : "")), false);

        // ④ 价格与余额
        long price;
        try {
            price = IEMCProxy.INSTANCE.getValue(info);
        } catch (Throwable t) {
            price = -1L;
        }
        long balance = NetEmcAccessor.getEmc(net);
        String affordableText = price > 0L ? String.valueOf(balance / price) : "—（无价 ⇒ 永远取不到）";
        source.sendSuccess(msg("④ 购买价=" + price + "；网络 EMC=" + balance
                + "；余额可买 " + affordableText + " 份"), false);

        // ⑤ 存入侧策略（与收费段同源）
        String skip;
        try {
            skip = EmcDepositHandler.skipReason(info.createStack());
        } catch (Throwable t) {
            skip = "身份取不出物品";
        }
        source.sendSuccess(msg("⑤ 存入侧策略：" + (skip == null ? "放行" : "拒绝（" + skip + "）")
                + "   ← 拒绝则兑换侧也绝不铸造，第三方会一直等不到料"), false);

        // ⑥ 现状快照
        long real = -1L;
        try {
            real = net.getUnifiedStorage().getStackByKey(
                    new com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey(info.createStack()))
                    .amount();
        } catch (Throwable ignored) {
            // 查不到就显示 -1，不影响结论
        }
        long entry = MaterializeQuote.materializedAmount(net, info);
        source.sendSuccess(msg("⑥ 现状：真实库存=" + real + "；物化条目=" + entry
                + "（条目只是派生数据，缺失/偏少都不影响取用）"), false);

        // ⑦ 结论 —— 只由 unique 入口给出
        long exposed = MaterializeQuote.externalDeliverable(net, info, 64L);
        if (exposed > 0L) {
            source.sendSuccess(msg("结论：OK   第三方（蓝图大炮 / 管道 / 总线）会看到 " + exposed + " 份"), true);
            return 1;
        }
        source.sendFailure(Component.literal("[BeyondEMC] 结论：第三方会报「无库存」。"
                + "上面 ②③④⑤ 里第一条不成立的就是原因；若全绿，请用 /beyondemc materialize list 对照应然条目"));
        return 0;
    }

    /**
     * {@code /beyondemc cannon <x> <y> <z>} —— 无头复现蓝图大炮材料清单的完整判定链。
     *
     * <p>把 <b>Create / BD / 本模组</b> 三层对同一个大炮的判定全部打出来：
     * required 与 gathered 的逐项对照（即打印清单的"满足/还缺"口径）、
     * 蓝图接口每个槽位的 {@code getStackInSlot} 与 {@code extractItem(1, true)} 两条判据。
     * 详见 {@link CannonDump} 的类注释。
     *
     * <p><b>控制台可用</b>（配合 RCON / forceload 即可在无玩家环境复现实机报告）。
     */
    private static int cannon(CommandContext<CommandSourceStack> ctx) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        return CannonDump.dump(ctx.getSource(), new BlockPos(x, y, z));
    }

    /**
     * {@code /beyondemc exchange <item> <count>} —— 用网络 EMC 换出已学习的物品。
     *
     * <p>走的是与界面请求包**完全相同**的服务端逻辑（{@link ExchangeService#exchange}），
     * 因此这个命令既可以用来验证，也是阶段 5 之前唯一的功能入口。
     *
     * <p>服务端权威性：命令的参数只是"模板物品 + 数量"，价格、余额、权限、背包空间
     * 全部由 {@code ExchangeService} 重算。
     */
    // Brigadier 的 Command.run 本身就声明了 throws CommandSyntaxException，
    // 所以 ItemArgument.getItem 的受检异常可以直接向上抛，不需要 try/catch。
    private static int exchange(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BeyondEMC] exchange 需要由玩家执行"));
            return 0;
        }
        DimensionsNet net = DimensionsNet.getPrimaryNetFromPlayer(player);
        if (net == null) {
            source.sendFailure(Component.literal("[BeyondEMC] 你当前不属于任何维度网络，请先创建/加入一个"));
            return 0;
        }

        ItemStack template = ItemArgument.getItem(ctx, "item").createItemStack(1, false);
        int count = IntegerArgumentType.getInteger(ctx, "count");

        ExchangeService.Result result = ExchangeService.exchange(player, net, template, count);
        if (result.success()) {
            source.sendSuccess(msg(result.message()), false);
            return 1;
        }
        source.sendFailure(Component.literal("[BeyondEMC] " + result.message()));
        return 0;
    }

    private static DimensionsNet requireNet(CommandSourceStack source) {        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BeyondEMC] 该子命令需要由玩家执行（控制台请改用 selftest）"));
            return null;
        }
        DimensionsNet net = DimensionsNet.getPrimaryNetFromPlayer(player);
        if (net == null) {
            source.sendFailure(Component.literal("[BeyondEMC] 你当前不属于任何维度网络，请先创建/加入一个"));
            return null;
        }
        return net;
    }

    private static Supplier<Component> msg(String text) {
        return () -> Component.literal("[BeyondEMC] " + text);
    }
}
