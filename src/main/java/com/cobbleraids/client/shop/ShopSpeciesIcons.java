package com.cobbleraids.client.shop;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;

/**
 * Where a species' 2D icon lives, for drawing a Pokemon without rendering its model.
 *
 * <p>cobblemon-cards ships hand-drawn 48x32 entity icons for a thousand species, and they read far
 * better at a twenty-pixel cell than a scaled-down 3D model does. They are also already on every
 * client that has the mod, so this references them in place: nothing is copied into this mod, which
 * keeps someone else's artwork out of our jar entirely.
 *
 * <p>The map is generated rather than computed, because the paths are only nearly formulaic. The
 * folder is a sanitised species name while the file keeps the real one, so Ho-Oh is
 * {@code 0250_hooh/ho_oh}; Venusaur ships only gendered files; Enamorus ships only forms. A runtime
 * formula would have been right for about nine hundred species and silently wrong for the rest --
 * see validation/sprites/build_icon_manifest.py, which picks the base form and is re-checked in CI
 * against the raid roster.
 *
 * <p>Absent cobblemon-cards there is nothing to point at, and a missing texture draws as magenta
 * rather than as nothing, so {@link #available()} is checked before any of this is used.
 */
final class ShopSpeciesIcons {
    private ShopSpeciesIcons() {}

    private static final String MANIFEST = "/assets/cobbleraids/shop_icons.json";
    private static final String PROVIDER = "cobblemon-cards";
    /** Every icon in the set is this size; the shop scales to the cell from here. */
    static final int WIDTH = 48, HEIGHT = 32;

    /** species -> "<folder>/<file>", and the same for the shiny variant where one exists. */
    private static final Map<String, String> PLAIN = new HashMap<>();
    private static final Map<String, String> SHINY = new HashMap<>();
    private static final Map<String, ResourceLocation> RESOLVED = new HashMap<>();
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded(PROVIDER);

    static {
        // Reading our own jar, so a failure here means a broken build rather than a bad install --
        // but it still must not take the shop screen with it.
        try (InputStream stream = ShopSpeciesIcons.class.getResourceAsStream(MANIFEST)) {
            if (stream == null) {
                RaidLog.error("Shop icon manifest " + MANIFEST + " is missing from the jar");
            } else {
                JsonObject icons = JsonParser
                        .parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("icons");
                for (String species : icons.keySet()) {
                    JsonObject entry = icons.getAsJsonObject(species);
                    if (entry.has("icon")) PLAIN.put(species, entry.get("icon").getAsString());
                    if (entry.has("shiny")) SHINY.put(species, entry.get("shiny").getAsString());
                }
            }
        } catch (RuntimeException | java.io.IOException ex) {
            RaidLog.error("Shop icon manifest could not be read (" + ex + "); the shop will draw"
                    + " Pokemon models instead");
        }
    }

    /** False when the mod that owns the artwork is not installed, in which case nothing resolves. */
    static boolean available() {
        return PRESENT && !PLAIN.isEmpty();
    }

    /**
     * The icon texture for a species, or null when there is none to point at.
     *
     * <p>A shiny with no shiny icon falls back to the plain one rather than to no icon: the wrong
     * palette still reads as the right Pokemon, and the cell's tooltip says "Shiny" either way.
     */
    static ResourceLocation texture(String species, boolean shiny) {
        if (!available() || species == null) return null;
        String key = species.toLowerCase(java.util.Locale.ROOT);
        String path = shiny ? SHINY.get(key) : null;
        if (path == null) path = PLAIN.get(key);
        if (path == null) return null;
        // Cached because a ResourceLocation per cell per frame is exactly the allocation the shop
        // audit went through this package to remove.
        return RESOLVED.computeIfAbsent(path, value -> ResourceLocation.fromNamespaceAndPath(
                PROVIDER, "textures/item/cards/pokemon/entity_icon/" + value + ".png"));
    }
}
