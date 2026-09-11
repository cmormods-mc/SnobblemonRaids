package com.cobbleraids.reward.currency;

import com.cobbleraids.RaidLog;
import java.lang.reflect.Method;
import java.math.BigInteger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Pays raid currency through CobbleDollars.
 *
 * <p>Reached by reflection rather than a compile-time dependency, for two reasons. CobbleDollars is
 * a Kotlin mod whose public surface here is a file facade
 * ({@code PlayerExtensionKt}, the compiled form of top-level extension functions), and adding it to
 * the build would make an optional runtime mod a required compile artifact for anyone building
 * CobbleRaids. The class and method names below are Kotlin, not Minecraft, so they are not subject
 * to remapping; the {@link Player} parameter is, but our class and theirs remap to the same runtime
 * class, so the lookup matches in dev and in production alike.
 *
 * <p>The handle is resolved once. If resolution fails -- a CobbleDollars update that renames or
 * moves the function -- this backend disables itself after saying so once, instead of throwing
 * inside every subsequent claim.
 */
public final class CobbleDollarsCurrencyBackend implements RaidCurrencyBackend {

    private static final String FACADE = "fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt";
    private static final String METHOD = "earnCobbleDollars";

    /**
     * CobbleDollars' own default for this flag is false (see the synthetic {@code $default}
     * wrapper). It is passed straight through to CobbleDollarsEarnedEvent and does not change
     * whether the money arrives, so matching their default keeps our payouts indistinguishable
     * from any other way a player earns.
     */
    private static final boolean EVENT_FLAG = false;

    private volatile Method earn;
    private volatile boolean unavailable;

    /** Resolves the handle. Throws, so RaidCurrencyBackends can fall back at selection time. */
    public void ensureReady() {
        try {
            Class<?> facade = Class.forName(FACADE);
            earn = facade.getMethod(METHOD, Player.class, BigInteger.class, boolean.class);
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new IllegalStateException("CobbleDollars is installed but " + FACADE + "." + METHOD
                    + " could not be resolved", ex);
        }
    }

    @Override
    public boolean grant(ServerPlayer player, BigInteger amount) {
        Method handle = earn;
        if (unavailable || handle == null || amount == null || amount.signum() <= 0) return false;
        try {
            // Their own guard rejects a negative amount and their event is cancelable, so the
            // return value is the only trustworthy answer to "did this player actually get paid".
            return Boolean.TRUE.equals(handle.invoke(null, player, amount, EVENT_FLAG));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            unavailable = true;
            // Throwable last, so the stack trace stays attached rather than being flattened
            // into the message -- this is the one call in the claim path that leaves our code.
            RaidLog.error("CobbleDollars payout failed and is now disabled for this session;"
                    + " raid item rewards are unaffected.", ex);
            return false;
        }
    }

    @Override
    public String name() {
        return unavailable ? "cobbledollars (disabled after failure)" : "cobbledollars";
    }
}
