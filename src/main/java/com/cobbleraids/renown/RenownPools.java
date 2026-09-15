package com.cobbleraids.renown;

import com.cobbleraids.config.RaidRarityTier;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Every name and epithet a renowned boss can draw, indexed by tier once at reload.
 *
 * <p>Read from {@code data/<namespace>/renown/*.json}; a file may carry a {@code "names"} block, an
 * {@code "epithets"} block, or both. See the shipped files for the format.
 *
 * <p><b>Nothing here throws on content.</b> The loader drops a bad entry with a warning and keeps
 * the rest, the same rule raid definitions follow: this namespace is open to any datapack, and one
 * typo in someone else's word list must not take titles off the server, let alone the server
 * itself.
 *
 * <p>Free of Minecraft types so the draw can be tested with a seeded generator.
 */
public final class RenownPools {

    public static final RenownPools EMPTY = new RenownPools(List.of(), List.of());

    /** Cobblemon's eighteen battle types, as its ElementalType showdown ids spell them. */
    public static final Set<String> TYPES = Set.of(
            "normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground",
            "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy");

    public record NameEntry(String name, Set<RaidRarityTier> tiers) {
        public NameEntry {
            tiers = tiers.isEmpty() ? Collections.unmodifiableSet(EnumSet.allOf(RaidRarityTier.class)) : Set.copyOf(tiers);
        }
    }

    public record EpithetEntry(String text, int weight, Set<RaidRarityTier> tiers, Set<String> types, RenownBoon boon) {
        public EpithetEntry {
            tiers = tiers.isEmpty() ? Collections.unmodifiableSet(EnumSet.allOf(RaidRarityTier.class)) : Set.copyOf(tiers);
            types = Set.copyOf(types);
        }

        /** Untyped epithets suit any boss; a typed one needs the boss to share at least one type. */
        boolean suits(Collection<String> bossTypes) {
            if (types.isEmpty()) return true;
            for (String type : bossTypes) if (types.contains(type)) return true;
            return false;
        }
    }

    private final List<NameEntry> names;
    private final List<EpithetEntry> epithets;
    // Built once here, not per draw: a spawn then filters a few dozen epithets by type and nothing more.
    private final Map<RaidRarityTier, List<String>> namesByTier = new EnumMap<>(RaidRarityTier.class);
    private final Map<RaidRarityTier, List<EpithetEntry>> epithetsByTier = new EnumMap<>(RaidRarityTier.class);

    public RenownPools(List<NameEntry> names, List<EpithetEntry> epithets) {
        this.names = List.copyOf(names);
        this.epithets = List.copyOf(epithets);
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            namesByTier.put(tier, this.names.stream().filter(n -> n.tiers().contains(tier)).map(NameEntry::name).toList());
            epithetsByTier.put(tier, this.epithets.stream().filter(e -> e.tiers().contains(tier)).toList());
        }
    }

    public List<NameEntry> names() { return names; }
    public List<EpithetEntry> epithets() { return epithets; }

    /**
     * One title for a boss of this tier and these types, or empty when the lists cannot supply one.
     *
     * <p>A typed epithet joins the pool alongside the general ones rather than replacing them, so a
     * Water boss is more likely to be "the Tidebreaker" without every Water boss being one.
     */
    public Optional<RaidRenown> draw(RaidRarityTier tier, Collection<String> bossTypes, RandomGenerator random) {
        List<String> tierNames = namesByTier.getOrDefault(tier, List.of());
        if (tierNames.isEmpty()) return Optional.empty();

        List<EpithetEntry> eligible = new ArrayList<>();
        long totalWeight = 0;
        for (EpithetEntry entry : epithetsByTier.getOrDefault(tier, List.of())) {
            if (!entry.suits(bossTypes)) continue;
            eligible.add(entry);
            totalWeight += entry.weight();
        }
        if (eligible.isEmpty()) return Optional.empty();

        long roll = random.nextLong(totalWeight);
        EpithetEntry chosen = eligible.getLast();
        for (EpithetEntry entry : eligible) {
            roll -= entry.weight();
            if (roll < 0) { chosen = entry; break; }
        }
        String name = tierNames.get(random.nextInt(tierNames.size()));
        return Optional.of(new RaidRenown(name, chosen.text(), chosen.boon()));
    }

    /**
     * Builds pools from every renown file, in the order given. Duplicates keep the first spelling
     * seen, case-insensitively, so a datapack repeating a shipped name does not double its odds.
     */
    public static RenownPools parse(Map<String, JsonObject> files, Consumer<String> warn) {
        List<NameEntry> names = new ArrayList<>();
        List<EpithetEntry> epithets = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> seenEpithets = new HashSet<>();

        for (Map.Entry<String, JsonObject> file : files.entrySet()) {
            String source = file.getKey();
            JsonObject root = file.getValue();
            if (root.has("names")) readNames(source, root.get("names"), names, seenNames, warn);
            if (root.has("epithets")) readEpithets(source, root.get("epithets"), epithets, seenEpithets, warn);
        }
        return new RenownPools(names, epithets);
    }

    /** {@code "names"} is one group or a list of groups, each {@code {"tiers": [...], "values": [...]}}. */
    private static void readNames(String source, JsonElement block, List<NameEntry> out, Set<String> seen,
                                  Consumer<String> warn) {
        if (block.isJsonArray()) {
            for (JsonElement group : block.getAsJsonArray()) readNameGroup(source, group, out, seen, warn);
        } else {
            readNameGroup(source, block, out, seen, warn);
        }
    }

    private static void readNameGroup(String source, JsonElement block, List<NameEntry> out, Set<String> seen,
                                      Consumer<String> warn) {
        if (!block.isJsonObject()) {
            warn.accept(source + ": a \"names\" group must be an object with a \"values\" list; skipping it");
            return;
        }
        JsonObject object = block.getAsJsonObject();
        Set<RaidRarityTier> tiers = readTiers(source, object, warn);
        if (!object.has("values") || !object.get("values").isJsonArray()) {
            warn.accept(source + ": \"names\" has no \"values\" list; skipping it");
            return;
        }
        for (JsonElement element : object.getAsJsonArray("values")) {
            String name = element.isJsonPrimitive() ? element.getAsString().trim() : null;
            try {
                // Validated through the record, so the loader and the tag reader agree on the rule.
                new RaidRenown(name, "the Test", RenownBoon.NONE);
            } catch (IllegalArgumentException ex) {
                warn.accept(source + ": " + ex.getMessage() + "; skipping it");
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                warn.accept(source + ": name '" + name + "' is already loaded; skipping the repeat");
                continue;
            }
            out.add(new NameEntry(name, tiers));
        }
    }

    private static void readEpithets(String source, JsonElement block, List<EpithetEntry> out, Set<String> seen,
                                     Consumer<String> warn) {
        if (!block.isJsonArray()) {
            warn.accept(source + ": \"epithets\" must be a list; skipping it");
            return;
        }
        JsonArray array = block.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                warn.accept(source + ": an epithet entry is not an object; skipping it");
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String text = entry.has("text") && entry.get("text").isJsonPrimitive() ? entry.get("text").getAsString().trim() : null;
            String label = source + ": epithet '" + text + "'";

            RenownBoon boon = entry.has("boon") && entry.get("boon").isJsonPrimitive()
                    ? RenownBoon.decode(entry.get("boon").getAsString()).orElse(null) : null;
            if (boon == null) {
                warn.accept(label + " needs a boon of none, hp_pool or stat_focus:<stat> (one of "
                        + RenownBoon.FOCUS_STATS + "); skipping it");
                continue;
            }
            try {
                new RaidRenown("Test", text, boon);
            } catch (IllegalArgumentException ex) {
                warn.accept(source + ": " + ex.getMessage() + "; skipping it");
                continue;
            }

            int weight = 10;
            if (entry.has("weight")) {
                try { weight = entry.get("weight").getAsInt(); }
                catch (RuntimeException ex) { weight = -1; }
            }
            if (weight < 1 || weight > 1000) {
                warn.accept(label + " has a weight outside 1..1000; skipping it");
                continue;
            }

            Set<String> types = new HashSet<>();
            boolean badType = false;
            for (JsonElement type : arrayOf(entry, "types")) {
                String value = type.isJsonPrimitive() ? type.getAsString().trim().toLowerCase(Locale.ROOT) : "";
                if (TYPES.contains(value)) types.add(value);
                else { warn.accept(label + " names unknown type '" + value + "'; skipping the epithet"); badType = true; }
            }
            if (badType) continue;

            if (!seen.add(text.toLowerCase(Locale.ROOT))) {
                warn.accept(label + " is already loaded; skipping the repeat");
                continue;
            }
            out.add(new EpithetEntry(text, weight, readTiers(source, entry, warn), types, boon));
        }
    }

    /** Unknown tiers are dropped with a warning; an empty result means every tier. */
    private static Set<RaidRarityTier> readTiers(String source, JsonObject object, Consumer<String> warn) {
        Set<RaidRarityTier> tiers = EnumSet.noneOf(RaidRarityTier.class);
        for (JsonElement element : arrayOf(object, "tiers")) {
            try {
                tiers.add(RaidRarityTier.parse(element.getAsString()));
            } catch (RuntimeException ex) {
                warn.accept(source + ": unknown tier '" + element + "'; ignoring it");
            }
        }
        return tiers;
    }

    private static JsonArray arrayOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }
}
