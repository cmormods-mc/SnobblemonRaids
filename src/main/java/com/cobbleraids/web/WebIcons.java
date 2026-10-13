package com.cobbleraids.web;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Item icons, species sprites and item names for the web pages, read from the installed mods.
 *
 * <p>A dedicated server cannot render anything, but the mods' own jars are on it and still hold their
 * textures and language files. This reads the flat 16x16 item texture an item's model names, and the
 * cobblemon-cards entity icon a species maps to, and hands them to the page as data URLs. Nothing is
 * copied into this mod, so someone else's artwork stays out of our jar, and nothing is written into
 * WebGUI's folder, so the page cap of 2000 files is not touched.
 *
 * <p>Everything is cached after the first read, including a miss, so a missing texture costs one
 * failed lookup rather than one per page view.
 */
public final class WebIcons {
    private WebIcons() {}

    private static final String CARDS_MOD = "cobblemon-cards";
    private static final String CARDS_ROOT = "assets/cobblemon-cards/textures/item/cards/pokemon/entity_icon/";
    private static final String MANIFEST = "/assets/cobbleraids/shop_icons.json";

    /** What a missing item texture is replaced with, matching the native screen's fallback. */
    public static final String FALLBACK_ITEM = "i:cobblemon:poke_ball";

    private static final Map<String, Optional<String>> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> LANG = new ConcurrentHashMap<>();
    private static volatile Map<String, String[]> species;

    /** Key for an item's icon, e.g. {@code i:cobblemon:poke_ball}. */
    public static String itemKey(String namespace, String path) {
        return "i:" + namespace + ":" + path;
    }

    /** Key for a species sprite, e.g. {@code s:pikachu:0}; the species is the bare path. */
    public static String speciesKey(String speciesPath, boolean shiny) {
        return "s:" + speciesPath.toLowerCase(Locale.ROOT) + ":" + (shiny ? "1" : "0");
    }

    /** The data URL for a key, or empty when no installed mod provides it. */
    public static Optional<String> dataUrl(String key) {
        return CACHE.computeIfAbsent(key, WebIcons::load);
    }

    private static Optional<String> load(String key) {
        try {
            byte[] png = key.startsWith("i:") ? readItem(key) : readSpecies(key);
            if (png == null) return Optional.empty();
            return Optional.of("data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        } catch (IOException | RuntimeException e) {
            RaidLog.warn("Could not load web icon {}: {}", key, e.toString());
            return Optional.empty();
        }
    }

    /** Follows an item's model to its {@code layer0} texture. Handles only flat generated items. */
    private static byte[] readItem(String key) throws IOException {
        String[] parts = key.split(":", 3);
        String namespace = parts[1], path = parts[2];
        JsonObject model = readJson(assetPath(namespace, "assets/" + namespace + "/models/item/" + path + ".json"));
        if (model == null || !model.has("textures")) return null;
        JsonElement layer = model.getAsJsonObject("textures").get("layer0");
        if (layer == null) return null;
        String texture = layer.getAsString();
        int colon = texture.indexOf(':');
        String texNamespace = colon < 0 ? "minecraft" : texture.substring(0, colon);
        String texPath = colon < 0 ? texture : texture.substring(colon + 1);
        Path file = assetPath(texNamespace, "assets/" + texNamespace + "/textures/" + texPath + ".png");
        return file == null ? null : Files.readAllBytes(file);
    }

    private static byte[] readSpecies(String key) throws IOException {
        String[] parts = key.split(":", 3);
        String[] paths = speciesManifest().get(parts[1]);
        if (paths == null) return null;
        String file = parts[2].equals("1") && paths[1] != null ? paths[1] : paths[0];
        Optional<ModContainer> cards = FabricLoader.getInstance().getModContainer(CARDS_MOD);
        if (cards.isEmpty()) return null;
        Optional<Path> sprite = cards.get().findPath(CARDS_ROOT + file + ".png");
        return sprite.isPresent() ? Files.readAllBytes(sprite.get()) : null;
    }

    private static Map<String, String[]> speciesManifest() {
        Map<String, String[]> loaded = species;
        if (loaded != null) return loaded;
        Map<String, String[]> map = new HashMap<>();
        try (InputStream stream = WebIcons.class.getResourceAsStream(MANIFEST)) {
            if (stream == null) {
                RaidLog.error("Shop icon manifest {} is missing from the jar", MANIFEST);
            } else {
                JsonObject icons = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("icons");
                for (String name : icons.keySet()) {
                    JsonObject entry = icons.getAsJsonObject(name);
                    if (!entry.has("icon")) continue;
                    map.put(name, new String[] {entry.get("icon").getAsString(),
                            entry.has("shiny") ? entry.get("shiny").getAsString() : null});
                }
            }
        } catch (IOException | RuntimeException e) {
            RaidLog.error("Shop icon manifest could not be read: {}", e.toString());
        }
        species = map;
        return map;
    }

    /** An item's display name from its mod's English language file, else the path in title case. */
    public static String itemName(String namespace, String path) {
        String name = LANG.computeIfAbsent(namespace, WebIcons::loadLang)
                .get("item." + namespace + "." + path.replace('/', '.'));
        if (name != null) return name;
        StringBuilder out = new StringBuilder();
        for (String word : path.substring(path.lastIndexOf('/') + 1).split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static Map<String, String> loadLang(String namespace) {
        Map<String, String> map = new HashMap<>();
        try {
            JsonObject lang = readJson(assetPath(namespace, "assets/" + namespace + "/lang/en_us.json"));
            if (lang != null) {
                lang.entrySet().forEach(e -> {
                    if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
                });
            }
        } catch (IOException | RuntimeException e) {
            RaidLog.warn("Could not read the {} language file: {}", namespace, e.toString());
        }
        return map;
    }

    /** A file inside the mod that owns a namespace; mod id and namespace match for the mods we sell from. */
    private static Path assetPath(String namespace, String path) {
        return FabricLoader.getInstance().getModContainer(namespace).flatMap(mod -> mod.findPath(path)).orElse(null);
    }

    private static JsonObject readJson(Path file) throws IOException {
        if (file == null) return null;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
