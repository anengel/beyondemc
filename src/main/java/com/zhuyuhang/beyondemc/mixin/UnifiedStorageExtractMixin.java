package com.zhuyuhang.beyondemc.mixin;

import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.zhuyuhang.beyondemc.core.ExtractContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记录"这次抽取是模拟还是真做"，供抽取钩子读取。
 *
 * <h2>为什么需要</h2>
 * `UnifiedStorageBeforeExtractHandler.BeforeExtractHandler#beforeExtract` 的签名
 * （`UnifiedStorageBeforeExtractHandler.java:32-36`）**没有 simulate 参数**，
 * 而它的调用点 `UnifiedStorage.extract` 有（`UnifiedStorage.java:118-133`）。
 *
 * <p>这个区分是安全关键：外部自动化会大量发起**模拟抽取**（能力查询、漏斗预热等），
 * 若在模拟时也扣 EMC，一次查询就会真扣钱。详见 {@link ExtractContext}。
 *
 * <h2>为什么 require = 0 是安全的（与其他 Mixin 不同）</h2>
 * 若本 Mixin 未能应用，{@code ExtractContext} 的深度始终为 0，
 * 而 {@code isSimulate()} 在深度为 0 时**保守返回 true** →
 * 抽取钩子一律放行 → **网络接口兑换功能整体不生效，但绝不会错扣玩家的 EMC**。
 *
 * <p>也就是说这个 Mixin 的失败模式是"功能静默关闭"，而不是"扣错钱" ——
 * 前者安全，后者不可接受。为了不让它<b>无声</b>地关闭，自检里对
 * {@link ExtractContext#enterCount()} 设了探针（见 {@code InterfaceWithdrawSelfTest}）。
 */
@Mixin(value = UnifiedStorage.class, remap = false)
public abstract class UnifiedStorageExtractMixin {

    /**
     * ⚠️ **必须写完整描述符**：{@code UnifiedStorage} 有三个同名重载
     * （`extract(int,long,boolean)`、`extract(IStackKey,long,boolean,boolean)`、
     * `extract(TagKey,long,boolean)`）。只写 {@code method = "extract"} 时 Mixin 会匹配到
     * 3 参数的那个，于是认为 handler 签名不合法并抛出 <b>InvalidInjectionException</b> ——
     * 那是**致命错误**，会直接中断启动。
     *
     * <p>另需记住：{@code require = 0} 只覆盖"注入点找不到"这一种情况，
     * <b>不覆盖 handler 签名错误</b>。所以这里的描述符不能简化。
     */
    private static final String EXTRACT_BY_KEY =
            "extract(Lcom/wintercogs/beyonddimensions/api/storage/key/IStackKey;JZZ)"
                    + "Lcom/wintercogs/beyonddimensions/api/storage/key/KeyAmount;";

    @Inject(method = EXTRACT_BY_KEY, at = @At("HEAD"), require = 0)
    private void beyondemc$enterExtract(IStackKey<?> key, long amount, boolean simulate, boolean fuzzy,
                                       CallbackInfoReturnable<KeyAmount> cir) {
        ExtractContext.enter(simulate);
    }

    @Inject(method = EXTRACT_BY_KEY, at = @At("RETURN"), require = 0)
    private void beyondemc$exitExtract(IStackKey<?> key, long amount, boolean simulate, boolean fuzzy,
                                       CallbackInfoReturnable<KeyAmount> cir) {
        ExtractContext.exit();
    }
}
