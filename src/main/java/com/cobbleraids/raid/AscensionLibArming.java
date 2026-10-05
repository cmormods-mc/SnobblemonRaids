package com.cobbleraids.raid;

import com.cobbleraids.RaidLog;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Lets AscensionLib's battle effects act in an ordinary raid: the boss is declared as an enemy of its own encounter and the
 * players' battle is armed for it just before the battle starts.
 *
 * <p>Reached by reflection, for the reason {@code AscensionShopHook} is: the library is optional and must not become a build
 * dependency. The contract is four static methods on {@code com.ascensionlib.AscensionEncounters} taking only {@code java.*}
 * types. Nothing here can stop a raid: a failure is logged and the raid simply fights natively.
 *
 * <p>Only an ordinary raid is handled. A raid another mod owns (CobbleTowers) arms its own battle, with its own encounter, so
 * arming it again here would replace that.
 */
public final class AscensionLibArming {

    private static final String MOD_ID = "ascensionlib";
    private static final String CLASS = "com.ascensionlib.AscensionEncounters";
    /** A raid boss is the library's top enemy tier; its reveal is shared with the whole party. */
    private static final String BOSS_TIER = "boss";

    private static boolean resolved;
    private static Method declare;
    private static Method arm;
    private static Method disarm;
    private static Method end;
    /** Optional: an older library without raid attunement still arms battles. */
    private static Method settleAttunement;

    private AscensionLibArming() {}

    /** One encounter per boss spawn: the boss Pokemon's UUID is unique and never reused. */
    static String encounterId(UUID bossPokemonId) {
        return "raid:" + bossPokemonId;
    }

    /** Declares the boss and arms the battle for it. Pair with {@link #disarm}. Returns whether the battle is armed. */
    static boolean declareAndArm(Collection<UUID> players, UUID bossPokemonId, String speciesId, int level) {
        if (!resolve()) return false;
        String encounter = encounterId(bossPokemonId);
        try {
            Object status = declare.invoke(null, encounter, players, 0, BOSS_TIER, true, null, speciesId, level);
            if (!"DECLARED".equals(String.valueOf(status))) return false; // DISABLED (no world) or unknown species: fight natively
            arm.invoke(null, players, encounter);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            RaidLog.error("Arming the raid battle of " + speciesId + " for ascension effects failed; it fights natively", ex);
            return false;
        }
    }

    static void disarm(Collection<UUID> players) {
        if (!resolve()) return;
        try {
            disarm.invoke(null, players);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            RaidLog.error("Disarming the raid battle for ascension effects failed", ex);
        }
    }

    /**
     * A won ordinary raid: every victor's party earns attunement toward promotion. The library pays no materials for it (Raids keeps
     * its own rewards) and counts it once per raid and Pokemon, so a repeat changes nothing. Players who are offline miss it.
     */
    public static void settleVictory(UUID bossPokemonId, Collection<UUID> victors) {
        if (!resolve() || settleAttunement == null) return;
        try {
            settleAttunement.invoke(null, encounterId(bossPokemonId), "VICTORY", victors);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            RaidLog.error("Awarding raid attunement through AscensionLib failed", ex);
        }
    }

    /** The raid is over: the library forgets the boss and every reveal. Safe to repeat. */
    static void end(UUID bossPokemonId) {
        if (!resolve()) return;
        try {
            end.invoke(null, encounterId(bossPokemonId));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            RaidLog.error("Ending the raid's ascension encounter failed", ex);
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return false;
            try {
                Class<?> encounters = Class.forName(CLASS);
                declare = encounters.getMethod("declareEnemy", String.class, Collection.class, int.class, String.class,
                        boolean.class, String.class, String.class, int.class);
                arm = encounters.getMethod("armBattle", Collection.class, String.class);
                disarm = encounters.getMethod("disarmBattle", Collection.class);
                end = encounters.getMethod("end", String.class);
                try {
                    settleAttunement = Class.forName("com.ascensionlib.AscensionRewards")
                            .getMethod("settleRaidAttunement", String.class, String.class, Collection.class);
                } catch (ReflectiveOperationException ignored) {
                    settleAttunement = null;
                }
            } catch (ReflectiveOperationException | LinkageError ex) {
                declare = null;
                arm = null;
                disarm = null;
                end = null;
                RaidLog.error("AscensionLib is installed but " + CLASS + " does not match what CobbleRaids expects, "
                        + "so raid bosses fight natively", ex);
            }
        }
        return declare != null;
    }
}
