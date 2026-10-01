package com.zhuyuhang.beyondemc;

import com.mojang.logging.LogUtils;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeExtractHandler;
import com.wintercogs.beyonddimensions.api.dimensionnet.helper.UnifiedStorageBeforeInsertHandler;
import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import com.zhuyuhang.beyondemc.client.ClientKnowledgeCache;
import com.zhuyuhang.beyondemc.command.BeyondEmcCommands;
import com.zhuyuhang.beyondemc.config.BeyondEmcConfig;
import com.zhuyuhang.beyondemc.core.EmcAvailability;
import com.zhuyuhang.beyondemc.diag.EmcStorageSelfTest;
import com.zhuyuhang.beyondemc.diag.CursorPickupSelfTest;
import com.zhuyuhang.beyondemc.diag.JeiFillSelfTest;
import com.zhuyuhang.beyondemc.diag.Phase3SelfTest;
import com.zhuyuhang.beyondemc.diag.InterfaceWithdrawSelfTest;
import com.zhuyuhang.beyondemc.diag.MixinTargetCheck;
import com.zhuyuhang.beyondemc.diag.Phase4SelfTest;
import com.zhuyuhang.beyondemc.diag.Phase6SelfTest;
import com.zhuyuhang.beyondemc.emc.EmcDepositHandler;
import com.zhuyuhang.beyondemc.emc.EmcRegistration;
import com.zhuyuhang.beyondemc.exchange.ExchangeRequestPacket;
import com.zhuyuhang.beyondemc.exchange.KnowledgeLearnedPacket;
import com.zhuyuhang.beyondemc.exchange.KnowledgeSyncPacket;
import com.zhuyuhang.beyondemc.exchange.InterfaceWithdrawService;
import com.zhuyuhang.beyondemc.knowledge.NetKnowledgeStore;
import com.zhuyuhang.beyondemc.materialize.ItemMaterializer;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Beyond EMC 主类。
 *
 * <p>本模组是 Beyond Dimensions（超越维度）与 ProjectE（等价交换）的附属模组，
 * 把 EMC 作为一种资源接进维度网络。
 *
 * <p>进度见 {@code docs/plan/ROADMAP.md}：阶段 1（骨架）✅、阶段 2（EMC 资源类型）✅、
 * 阶段 3（折算 + 学习 + 读档守卫）✅、阶段 4（兑换取出）实现中。
 */
@Mod(BeyondEmc.MOD_ID)
public class BeyondEmc {

    public static final String MOD_ID = "beyondemc";

    public static final Logger LOGGER = LogUtils.getLogger();

    public BeyondEmc(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[BeyondEMC] 开始初始化");

        // 配置
        modContainer.registerConfig(ModConfig.Type.SERVER, BeyondEmcConfig.SPEC);

        // 资源类型注册必须挂在 mod 事件总线的 FMLCommonSetupEvent 上
        modEventBus.addListener(EmcRegistration::onCommonSetup);

        // 网络包（兑换请求 C2S；阶段 5 的界面注入会发它）
        modEventBus.addListener(this::onRegisterPayloads);

        // ---- 核心：注册存入折算钩子 ----
        // BD 的 handlers 是一个非线程安全的 ArrayList，所以在这里（mod 构造期、单线程）
        // 一次性注册完，绝不在运行时动态增删。
        UnifiedStorageBeforeInsertHandler.addHandler(new EmcDepositHandler());
        LOGGER.info("[BeyondEMC] 已注册存入折算钩子");

        // 抽取钩子：让网络接口（以及所有其它抽取路径）也能取出"已学习但无库存"的物品并扣 EMC。
        // UnifiedStorageBeforeExtractHandler 是 BD 全部抽取路径的唯一收口
        // （slot / TagKey / extractByKey 都转发到 extract(IStackKey, ...)，见 UnifiedStorage.java:105-133）
        UnifiedStorageBeforeExtractHandler.addHandler(InterfaceWithdrawService.handler());
        LOGGER.info("[BeyondEMC] 已注册网络接口兑换钩子");

        // 命令走 game 事件总线
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // 打开/关闭网络界面时同步（阶段 5 的界面注入需要客户端的已学习集合）
        NeoForge.EVENT_BUS.addListener(this::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(this::onContainerClose);

        // EMC 表就绪信号（阶段 3 的折算门禁 + 阶段 6 的价格缓存失效）
        NeoForge.EVENT_BUS.addListener(EmcAvailability::onRemap);

        // 诊断：启动后自动跑自检并写进日志，便于无头验证
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);

        LOGGER.info("[BeyondEMC] 初始化完成。");
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(
                ExchangeRequestPacket.TYPE,
                ExchangeRequestPacket.STREAM_CODEC,
                ExchangeRequestPacket::handle);
        registrar.playToClient(
                KnowledgeSyncPacket.TYPE,
                KnowledgeSyncPacket.STREAM_CODEC,
                KnowledgeSyncPacket::handle);
        // 增量：网络刚学会一个新物品时推给正在看该网络的玩家，
        // 这样"存入新物品后不用关界面重开就能直接取出"
        registrar.playToClient(
                KnowledgeLearnedPacket.TYPE,
                KnowledgeLearnedPacket.STREAM_CODEC,
                KnowledgeLearnedPacket::handle);
    }

    /**
     * 打开网络界面时把该网络的"已学习物品"全量同步给客户端 —— 界面要靠它显示虚拟条目。
     *
     * <p>怎么拿到网络对象：{@code DimensionsNetMenu} 自己<b>不存 net id</b>
     * （构造参数只有 storage），但服务端的 storage 就是 {@code UnifiedStorage}，
     * 而它有一个 public 的 {@code getNet()}（`UnifiedStorage.java:69-73`）。
     * 所以直接从 storage 反查网络，不需要改 BD 的菜单。
     */
    private void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getContainer() instanceof DimensionsNetMenu menu)) {
            return;
        }
        if (!(menu.storage instanceof UnifiedStorage storage)) {
            return;
        }
        DimensionsNet net = storage.getNet();
        if (net == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 0.3.0：打开界面前先重算一次物化条目。
        // 这是"读档后修复"的兜底路径（Spike S-0.3-5）：EMC 表就绪后，只要玩家打开界面，
        // 物化条目就一定与当前 EMC 一致 —— 不依赖 EMCRemapEvent 时能否枚举到全部网络。
        com.zhuyuhang.beyondemc.materialize.ItemMaterializer.refresh(net);

        // 单价在服务端算好随包下发：客户端不该依赖自己那份 EMC 价格表
        // （它可能是空的或过期的，而那种失败会表现成"界面里什么都看不到"）
        List<KnowledgeSyncPacket.LearnedEntry> learned = new ArrayList<>();
        int noPrice = 0;
        for (ItemInfo info : NetKnowledgeStore.snapshot(net)) {
            long unitPrice;
            try {
                unitPrice = IEMCProxy.INSTANCE.getValue(info);
            } catch (Throwable t) {
                unitPrice = 0L;
            }
            if (unitPrice <= 0L) {
                noPrice++;
            }
            learned.add(new KnowledgeSyncPacket.LearnedEntry(info, unitPrice));
        }

        PacketDistributor.sendToPlayer(player, new KnowledgeSyncPacket(
                net.getId(), learned, isMaterializeActive()));
        LOGGER.info("[BeyondEMC] 已向 {} 同步网络 {} 的 {} 项已学习物品（其中 {} 项当前无 EMC 价值；服务端物化={}）",
                player.getGameProfile().getName(), net.getId(), learned.size(), noPrice, isMaterializeActive());
    }

    /**
     * 服务端物化是否正在生效。
     *
     * <p>随 {@code KnowledgeSyncPacket} 下发给客户端，让客户端在物化生效时<b>不再注入虚拟条目</b>
     * —— 那些物品已经是服务端真实的 {@code EmcItemKey} 条目，再注入会出两行。
     */
    private static boolean isMaterializeActive() {
        try {
            return BeyondEmcConfig.materializeItems()
                    && BeyondEmcConfig.materializeMode() == BeyondEmcConfig.MaterializeMode.STORAGE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 关闭界面时清掉客户端缓存，避免把上一个网络的学习集合用到下一个网络上。 */
    private void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getContainer() instanceof DimensionsNetMenu) {
            ClientKnowledgeCache.clear();
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        BeyondEmcCommands.register(event.getDispatcher(), event.getBuildContext());
    }

    /**
     * 诊断：把自检结果写进日志，这样无头环境（CI / {@code runServer}）不需要人工敲命令也能验证。
     * 阶段 5 起可以删掉，改为 GameTest。
     */
    private void onServerStarted(ServerStartedEvent event) {
        // 自检用 new DimensionsNet(true) 造临时网络。若不隔离，它们会被登记进 ItemMaterializer
        // 的全局「已观测网络」集合，此后每次 refreshAll 都要为一批死网络空转一整遍，
        // 还会把真实网络那条「物化（第 N 次）」日志挤出 LOG_FIRST_N 的上限 —— 实测排查本缺陷时
        // 唯一想看的那行恰恰因此不可见。这里保证自检零全局副作用（详见 observedSnapshot 的说明）。
        Set<DimensionsNet> observedBefore = ItemMaterializer.observedSnapshot();
        try {
            LOGGER.info("[BeyondEMC] ===== 自检开始 =====");
            LOGGER.info("[BeyondEMC] EMC 表就绪={}，remap 次数={}",
                    EmcAvailability.isReady(), EmcAvailability.remapCount());
            LOGGER.info("[BeyondEMC] ---- 存储层（阶段 2）----");
            for (String line : EmcStorageSelfTest.run(event.getServer().registryAccess())) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- 折算与学习集（阶段 3）----");
            for (String line : Phase3SelfTest.run(event.getServer().registryAccess())) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- 兑换服务（阶段 4）----");
            for (String line : Phase4SelfTest.run()) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- 物化：物品真实存在于网络中（0.3.0）----");
            for (String line : com.zhuyuhang.beyondemc.diag.MaterializeSelfTest.run(event.getServer().registryAccess())) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- 策略与配置（阶段 6）----");
            for (String line : Phase6SelfTest.run()) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- Mixin 目标存活性（阶段 7：防 BD 升级后静默失效）----");
            for (String line : MixinTargetCheck.run()) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- 网络接口兑换（自动化向）----");
            for (String line : InterfaceWithdrawSelfTest.run()) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- 兑换物品吸附到鼠标（0.2.0）----");
            for (String line : CursorPickupSelfTest.run()) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- JEI 配方填充可用量（0.2.0，不依赖 JEI 存在）----");
            for (String line : JeiFillSelfTest.run()) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ---- 第三方暴露：Create 蓝图接口 / 通用物品能力桥（0.3.2）----");
            for (String line : com.zhuyuhang.beyondemc.diag.CreatePathwaySelfTest.run()) {
                LOGGER.info("[BeyondEMC] {}", line);
            }
            LOGGER.info("[BeyondEMC] ===== 自检结束 =====");
        } finally {
            // 只移除自检期间新登记的临时网络；快照之前就存在的（真实网络）一律保留。
            ItemMaterializer.restoreObserved(observedBefore);
        }
    }
}
