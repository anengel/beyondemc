package com.zhuyuhang.beyondemc.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 折算的物品筛选（对应配置里的黑名单 / 白名单）。
 *
 * <p>语义：
 * <ul>
 *   <li>白名单**非空**时 → 只折算命中的物品（黑名单被忽略）；</li>
 *   <li>白名单为空时 → 折算除黑名单命中之外的所有物品。</li>
 * </ul>
 *
 * <p>每一项支持两种写法：物品 id（{@code minecraft:diamond}）与标签（{@code #minecraft:swords}）。
 * 标签写法在整合包里特别实用 —— 一句 {@code #c:ingots} 就能覆盖所有锭。
 *
 * <p>非法条目（拼错的 id、不存在的标签）**只记一次日志并跳过**，绝不让它把折算路径炸掉 ——
 * 玩家配错配置不该导致存档读不进去。
 */
public final class EmcItemFilter {

    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private EmcItemFilter() {
    }

    /** 该物品是否**允许折算**。 */
    public static boolean allows(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        List<String> whitelist = BeyondEmcConfig.emcDepositWhitelist();
        if (!whitelist.isEmpty()) {
            return matches(stack, whitelist, "emcDepositWhitelist");
        }
        List<String> blacklist = BeyondEmcConfig.emcDepositBlacklist();
        if (!blacklist.isEmpty()) {
            return !matches(stack, blacklist, "emcDepositBlacklist");
        }
        return true;
    }

    private static boolean matches(ItemStack stack, List<String> entries, String which) {
        for (String raw : entries) {
            if (raw == null) {
                continue;
            }
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            try {
                if (entry.startsWith("#")) {
                    TagKey<Item> tag = TagKey.create(Registries.ITEM,
                            ResourceLocation.parse(entry.substring(1)));
                    if (stack.is(tag)) {
                        return true;
                    }
                } else {
                    ResourceLocation id = ResourceLocation.parse(entry);
                    if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) {
                        return true;
                    }
                }
            } catch (Throwable t) {
                String key = which + ":" + entry;
                if (WARNED.add(key)) {
                    com.zhuyuhang.beyondemc.BeyondEmc.LOGGER.warn(
                            "[BeyondEMC] 配置 {} 里的条目无法解析，已忽略：{}", which, entry);
                }
            }
        }
        return false;
    }
}
