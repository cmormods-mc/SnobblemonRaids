package com.cobbleraids.reward;

import com.cobbleraids.RaidLog;
import java.lang.reflect.Method;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * The 777 Unique's loot bonus: asks AscensionLib how many of an item a player's reward should be, so an item reward is 20% larger while
 * a Pokemon holding 777 is in their party.
 *
 * <p>Reached by reflection, for the reason {@code AscensionShopHook} is: the library is optional. The contract is the single static
 * method {@code com.ascensionlib.AscensionRewards.scaleItemQuantity(UUID, int, String)}. When the mod is absent, the contract does not
 * match, or anything throws, the original quantity is used: a bonus is never worth a lost or failed reward. Item rewards only; currency
 * and Raid Points are not scaled.
 */
public final class AscensionItemBonus {
    private static final String MOD_ID = "ascensionlib";
    private static final String CLASS = "com.ascensionlib.AscensionRewards";

    private static boolean resolved;
    private static Method scale;

    private AscensionItemBonus() {}

    /** @param key identifies the reward, so the library's rounding of a fraction is stable for it */
    public static int scale(ServerPlayer player, int count, String key) {
        Method method = resolve();
        if (method == null || count < 1) return count;
        try {
            Object result = method.invoke(null, player.getUUID(), count, key);
            return result instanceof Integer scaled && scaled >= count ? scaled : count;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            RaidLog.error("AscensionLib could not scale an item reward; using the original quantity", ex);
            return count;
        }
    }

    private static Method resolve() {
        if (resolved) return scale;
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;
        try {
            scale = Class.forName(CLASS).getMethod("scaleItemQuantity", UUID.class, int.class, String.class);
        } catch (ReflectiveOperationException | LinkageError ex) {
            // An older library without the 777 bonus is not an error: its rewards are simply unscaled.
        }
        return scale;
    }
}
