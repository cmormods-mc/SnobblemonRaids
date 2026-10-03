package com.cobbleraids.api.showdown;

import com.cobbleraids.showdown.ShowdownExtensionRegistry;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lets another mod add behaviour to Cobblemon's Showdown simulator without editing its files itself.
 *
 * <p>Two mods patching the same unbundled {@code showdown/} directory race each other, and which one wins is
 * mod-load-order luck (CobbleRaids has already lost that race to {@code mega_showdown}). So CobbleRaids does the
 * installing, for everyone, at the two moments it already repairs its own patch, and offers two small things:
 *
 * <ul>
 *   <li>{@link #registerModule}: a JavaScript module written beside {@code raid-patch.js} as {@code ext-<id>.js}
 *       and loaded by it. Each module loads inside its own try/catch: one that throws is logged and skipped, and
 *       can never take the simulator, or another extension, down with it.</li>
 *   <li>{@link #registerFormatFields}: values added to the {@code format} object of the {@code >start} payload of
 *       every battle, which Showdown copies onto {@code battle.format}, where an extension reads them. This is the
 *       same road CobbleRaids' own field conditions travel.</li>
 * </ul>
 *
 * <p><b>Register from a Fabric {@code preLaunch} entrypoint, not from a mod initializer.</b> Cobblemon starts its
 * Showdown service on its own thread while mods are still initializing, unbundles the simulator and builds its
 * JavaScript context, and CobbleRaids writes the extension files and the list {@code raid-patch.js} loads from at
 * that moment. A module registered by a mod initializer can arrive after that thread has already read an empty list,
 * so it is installed but not loaded until the next boot (found live: CobbleTowers did exactly this). This package is
 * plain Java with no Minecraft types, so it is safe to call that early. A module registered even later is installed
 * on the next boot.
 *
 * <p>Signatures use only {@code java.*}, which a build-time check enforces, the same rule as the other API
 * packages. <b>Experimental</b>: {@link #API_VERSION} 1 is shaped by its first consumer, CobbleTowers.
 */
public final class ShowdownExtensions {

    /** Incremented on any incompatible change to this package. */
    public static final int API_VERSION = 1;

    private ShowdownExtensions() {}

    /**
     * Supplies fields for one battle's format object.
     *
     * <p>Called on the server thread for every battle, just before it is handed to Showdown. Return an empty map
     * for a battle that is not yours. A provider that throws is logged and skipped; the battle still starts.
     */
    @FunctionalInterface
    public interface FormatFieldProvider {

        /**
         * @param battleId  the battle's id
         * @param playerIds every player taking part, so a provider can tell whose battle this is
         * @return field name to a <b>raw JSON value</b> (a string holding {@code [..]}, {@code {..}}, a number...).
         *         Names must be a letter followed by up to 31 letters or digits, must not already be present
         *         (the first provider to set a name wins), and must not be one Cobblemon or CobbleRaids uses.
         *         A value that is not valid JSON, or is over 8192 characters, is dropped.
         */
        Map<String, String> fieldsFor(UUID battleId, List<UUID> playerIds);
    }

    /**
     * Registers a JavaScript module to be installed as {@code showdown/ext-<id>.js}. The module is
     * {@code require}d by {@code raid-patch.js}, so a relative {@code require('./sim/battle')} resolves to the
     * simulator. Registering the same id again replaces the earlier source.
     *
     * @param id     lowercase letters, digits, {@code _} or {@code -}, 1 to 48 characters
     * @param source opens the module's source each time it is installed
     * @throws IllegalArgumentException if the id is not valid
     */
    public static void registerModule(String id, Supplier<InputStream> source) {
        ShowdownExtensionRegistry.registerModule(id, source);
    }

    /** Adds a provider; see {@link FormatFieldProvider}. */
    public static void registerFormatFields(FormatFieldProvider provider) {
        ShowdownExtensionRegistry.registerProvider(provider);
    }
}
