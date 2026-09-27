package com.zhuyuhang.beyondemc.mixin;

import com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.common.menu.widget.ClientNetStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 访问 {@code ClientNetStorage} 的三个 private 成员。
 *
 * <p>为什么必须用 Mixin 而不是复制逻辑：
 * <ul>
 *   <li>{@code matchFilter} 承载着 BD 的搜索框语义（`@` / `$` / `#` / `*` 前缀、拼音搜索等，
 *       见 {@code ClientNetStorageSearchHelper}）。复制一遍必然随时间漂移，
 *       所以用 {@link Invoker} 调原方法。</li>
 *   <li>{@code cacheIndexes} 是排序结果缓存（`ClientNetStorage.java:45`）。
 *       注入了新条目却不置空它，{@code buildSortedIndex} 会直接返回旧索引，
 *       表现为"注入完全没生效"（`ClientNetStorage.java:161-165`）。</li>
 *   <li><b>{@code sourceStorage} 是判"有没有真实库存"唯一正确的数据源</b>
 *       （`ClientNetStorage.java:28-31` 的原注释：「原有的，对客户端而言绝对真实的存储」）。
 *       <br>⚠️ 这里踩过一个坑：早先版本用视图自身（{@code ClientNetStorage.getStorage()}）去算库存，
 *       而视图里**混着我们自己注入的虚拟条目** —— 于是下一次注入时把自己上一轮注入的东西
 *       当成了"真实库存"而全部跳过，虚拟集合被清空却不再填回，
 *       表现为「条目能看见但点击毫无反应」（点击拦截靠虚拟集合识别）。</li>
 * </ul>
 */
@Mixin(value = ClientNetStorage.class, remap = false)
public interface ClientNetStorageAccessor {

    @Accessor("cacheIndexes")
    void beyondemc$setCacheIndexes(List<Integer> value);

    @Invoker("matchFilter")
    boolean beyondemc$matchFilter(IStackKey<?> key);

    /** 客户端侧"绝对真实"的存储镜像（不含我们注入的虚拟条目）。 */
    @Accessor("sourceStorage")
    AbstractUnorderedStackHandler beyondemc$getSourceStorage();
}
