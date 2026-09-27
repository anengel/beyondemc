package com.zhuyuhang.beyondemc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.diag.EmcStorageSelfTest;
import com.zhuyuhang.beyondemc.diag.Phase3SelfTest;
import com.zhuyuhang.beyondemc.diag.Phase4SelfTest;
import com.zhuyuhang.beyondemc.emc.EmcDepositHandler;
import com.zhuyuhang.beyondemc.emc.EmcStackKey;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import com.zhuyuhang.beyondemc.exchange.ExchangeService;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
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
                        .then(Commands.literal("clear").executes(BeyondEmcCommands::knowledgeClear))));
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
