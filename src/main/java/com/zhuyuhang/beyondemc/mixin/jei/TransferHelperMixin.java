package com.zhuyuhang.beyondemc.mixin.jei;

import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.integration.module.jei.transfer.TransferHelper;
import com.zhuyuhang.beyondemc.exchange.EmcAvailabilityInjector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * JEI 配方转移的接入点：把「已学会但无库存」的物品追加进 BD 的可用池。
 *
 * <h2>为什么是这里</h2>
 * BD 的四个 JEI 转移处理器（`BDjeiPlugin.java:32-37`）全部汇入
 * {@code TransferHelper.transferRecipe(...)}（`TransferHelper.java:29`）——**唯一漏斗**。
 * 它的第 2 个参数 {@code storage} 就是客户端网络存储列表，可用池正是由它参与构建的
 * （`TransferHelper.java:42-49`）。
 *
 * <h2>为什么用 ModifyVariable 而不是 Inject + 修改入参</h2>
 * {@code storage} 来自 {@code container.storage.getStorage()}（`CraftMenuRecipeTransferHandler.java:49`），
 * 是 BD 客户端存储的**真实镜像列表**。往里 {@code add} 会污染它 ——
 * 下一次 delta 同步会冲掉，而且可能被别处当成"真实库存"。
 * 所以用 {@code @ModifyVariable(argsOnly = true)} **换成一个新列表**，
 * 原列表一个字节都不动（{@code EmcAvailabilityInjector} 返回新列表）。
 *
 * <h2>门控</h2>
 * 本类在**方法签名与类型引用**上都依赖 JEI。玩家没装 JEI 时，
 * 加载本类会抛 {@code NoClassDefFoundError} 并可能直接崩游戏。
 * 因此它被放在**独立的 mixin 配置** {@code beyondemc.jei.mixins.json} 里，
 * 由 {@code BeyondEmcJeiMixinPlugin} 在 JEI 缺席时整体跳过。
 *
 * <p>逻辑本身（不引用 JEI）在 {@code EmcAvailabilityInjector}，
 * 可在无头环境被 {@code JeiFillSelfTest} 决定性验证。
 */
@Mixin(value = TransferHelper.class, remap = false)
public class TransferHelperMixin {

    /**
     * 替换第 2 个参数 {@code storage}（静态方法的实参索引 1）：
     * 返回“原列表 + 虚拟可用条目”的新列表。
     */
    @ModifyVariable(method = "transferRecipe", at = @At("HEAD"), argsOnly = true, index = 1)
    private static List<KeyAmount> beyondemc$injectEmcAvailability(List<KeyAmount> storage) {
        return EmcAvailabilityInjector.withEmcAvailability(storage);
    }
}
