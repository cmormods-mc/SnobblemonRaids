package com.cobbleraids.shop;

import com.cobbleraids.RaidLog;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Gives a shop Pokemon its operator-set AscensionLib rarity.
 *
 * <p>Reached by reflection rather than a compile-time dependency, for the reason
 * {@code CobbleDollarsCurrencyBackend} is: AscensionLib is optional, and adding it to the build would make it a
 * required artifact for anyone building CobbleRaids (CI included). The contract is the single static method
 * {@code com.ascensionlib.AscensionGrants.grantWithRarity(Pokemon, String)}, which returns a result enum whose
 * {@code GRANTED} constant means the Pokemon now has its profile. When the mod is not installed a rarity on a
 * shop entry is ignored, said once. A failure of any kind is logged and never undoes the purchase.
 */
final class AscensionShopHook {

    private static final String MOD_ID = "ascensionlib";
    private static final String GRANTS = "com.ascensionlib.AscensionGrants";
    private static final String GRANT = "grantWithRarity";

    private static boolean resolved;
    private static Method grant;
    private static boolean saidMissing;

    private AscensionShopHook() {}

    /**
     * Gives the just-added Pokemon its rarity, if the entry has one. The points are already taken and the Pokemon
     * already delivered, so a failure here is a line in the log naming the Pokemon and the rarity it should have
     * had, for an operator to settle by hand.
     */
    static void apply(Pokemon pokemon, ShopPokemonGift gift, String entryId) {
        if (gift.rarity() == null) return;
        Method method = resolve();
        if (method == null) {
            if (!saidMissing) {
                saidMissing = true;
                RaidLog.warn("Shop entry " + entryId + " sets a rarity but AscensionLib is not usable; rarities are ignored.");
            }
            return;
        }
        try {
            Object result = method.invoke(null, pokemon, gift.rarity());
            if (!"GRANTED".equals(String.valueOf(result))) {
                RaidLog.error("Shop entry " + entryId + ": Pokemon " + pokemon.getUuid() + " was delivered without its "
                        + gift.rarity() + " ascension profile (" + result + ").");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            RaidLog.error("Shop entry " + entryId + ": Pokemon " + pokemon.getUuid() + " was delivered without its "
                    + gift.rarity() + " ascension profile.", ex);
        }
    }

    /** Resolved once; null when the mod is absent or its contract is not what this expects (said in the log). */
    private static Method resolve() {
        if (resolved) return grant;
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;
        try {
            grant = Class.forName(GRANTS).getMethod(GRANT, Pokemon.class, String.class);
        } catch (ReflectiveOperationException | LinkageError ex) {
            RaidLog.error("AscensionLib is installed but " + GRANTS + "." + GRANT + " could not be resolved", ex);
        }
        return grant;
    }
}
