package com.cobbleraids.config;

import com.cobbleraids.RaidLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The optional per-boss trait block: what a raid boss <em>is</em>, beyond its species, level and
 * moves.
 *
 * <p>Without it a boss is built from a bare {@code new Pokemon()}, so its IVs, nature, ability and
 * gender are Cobblemon's random defaults -- the same boss is statistically a different fight every
 * time it spawns. Fixed movesets were added for competitive consistency; this is the rest of that
 * idea.
 *
 * <p>Every field is optional and an absent block leaves the boss exactly as it was, so the shipped
 * definitions are unaffected until someone authors values.
 *
 * <p>Deliberately free of Minecraft and Cobblemon types: the values are carried as plain strings
 * and stat-name keys and only resolved against the registries at spawn time. That keeps this class
 * unit-testable, which matters because it owns the held-item policy below.
 *
 * <p><b>Nothing here throws.</b> RaidDefinitionRegistry rethrows any failure while reading a
 * definition, which aborts the whole datapack load -- one typo in one trait block would take all
 * 130 raids off the server. Bad fields are dropped with a warning instead, the same choice
 * RaidBossSpawner.applyFixedMoveset made for move ids and for the same reason.
 */
public record RaidBossTraits(
        String nature,
        String ability,
        Map<String, Integer> ivs,
        Map<String, Integer> evs,
        String heldItem,
        String gender,
        String teraType,
        String form
) {
    public static final RaidBossTraits NONE = new RaidBossTraits(null, null, Map.of(), Map.of(), null, null, null, null);

    /** Cobblemon's six permanent stats, as they are written in a definition. */
    private static final Set<String> STATS =
            Set.of("hp", "attack", "defence", "special_attack", "special_defence", "speed");

    /** American spellings accepted so an author does not have to guess which one this mod chose. */
    private static final Map<String, String> STAT_ALIASES =
            Map.of("defense", "defence", "special_defense", "special_defence");

    /**
     * Held items a raid boss may carry.
     *
     * <p>Narrow on purpose. raid-patch.js pins the boss's simulated HP near maximum because the
     * real health is a separate pool, so anything that reads or writes HP behaves in a way nothing
     * here has tested. This list is therefore only items that multiply damage and touch nothing
     * else, plus Rocky Helmet, whose contact damage lands on the player through the ordinary path.
     *
     * <p>See {@link #heldItemRejection} for what is excluded and why. Widen this once the excluded
     * behaviour has actually been tested on a live raid -- not before.
     */
    private static final Set<String> ALLOWED_HELD_ITEMS = Set.of(
            // Type-boosting items: a flat multiplier on one damage type, no other interaction.
            "minecraft:charcoal",           // Cobblemon reuses vanilla charcoal as the fire booster
            "cobblemon:mystic_water", "cobblemon:miracle_seed", "cobblemon:magnet",
            "cobblemon:twisted_spoon", "cobblemon:never_melt_ice", "cobblemon:sharp_beak",
            "cobblemon:poison_barb", "cobblemon:soft_sand", "cobblemon:hard_stone",
            "cobblemon:silver_powder", "cobblemon:spell_tag", "cobblemon:dragon_fang",
            "cobblemon:black_glasses", "cobblemon:black_belt", "cobblemon:silk_scarf",
            "cobblemon:metal_coat",
            // Category multipliers.
            "cobblemon:expert_belt", "cobblemon:muscle_band", "cobblemon:wise_glasses",
            // Damages the attacker on contact, through the normal player damage path.
            "cobblemon:rocky_helmet");

    /** Why a specific well-known item is refused, so the warning can say something useful. */
    private static final Map<String, String> BLOCKED_HELD_ITEMS = Map.ofEntries(
            Map.entry("cobblemon:focus_sash",
                    "survives on an HP threshold, and the boss's simulated HP is pinned"),
            Map.entry("cobblemon:focus_band",
                    "survives on an HP threshold, and the boss's simulated HP is pinned"),
            Map.entry("cobblemon:power_herb",
                    "skips charge turns, which is what makes Geomancy and similar moves fair here"),
            Map.entry("cobblemon:life_orb",
                    "damages its holder outside the raid HP pool"),
            Map.entry("cobblemon:red_card",
                    "forces a switch, and the boss side has exactly one Pokemon"),
            Map.entry("cobblemon:eject_button",
                    "forces a switch, and the boss side has exactly one Pokemon"),
            Map.entry("cobblemon:leftovers",
                    "heals through the raid HP pool every turn; needs a live test first"),
            Map.entry("cobblemon:shell_bell",
                    "heals through the raid HP pool; needs a live test first"),
            Map.entry("cobblemon:choice_band",
                    "move locking against RaidBossBattleAI is untested"),
            Map.entry("cobblemon:choice_specs",
                    "move locking against RaidBossBattleAI is untested"),
            Map.entry("cobblemon:assault_vest",
                    "blocks status moves, which can leave a fixed moveset with no legal action"));

    public RaidBossTraits {
        ivs = Map.copyOf(ivs);
        evs = Map.copyOf(evs);
    }

    /** True when the boss should be built exactly as it was before traits existed. */
    public boolean isEmpty() {
        return nature == null && ability == null && ivs.isEmpty() && evs.isEmpty()
                && heldItem == null && gender == null && teraType == null && form == null;
    }

    /** Null when the item may be held, otherwise the reason to report. */
    public static String heldItemRejection(String itemId) {
        if (itemId == null || ALLOWED_HELD_ITEMS.contains(itemId)) return null;
        String known = BLOCKED_HELD_ITEMS.get(itemId);
        return known != null ? known : "not on the raid-boss held-item allowlist";
    }

    /** Cobblemon's total EV cap across all six stats, shared by {@link #rollEvs} and its caller. */
    public static final int EV_TOTAL_CAP = 510;

    /**
     * Spreads a pinned IV a little so repeat raids are not byte-identical, without changing the
     * character of the fight. A spread of 0 pins it exactly; the result is always a legal IV.
     */
    public static int jitterIv(int base, int spread, RandomGenerator random) {
        if (spread <= 0) return clamp(base, 31);
        int offset = random.nextInt(spread * 2 + 1) - spread;
        return clamp(base + offset, 31);
    }

    /**
     * A fresh IV roll, uniform across the entire legal 0..31 range -- unlike {@link #jitterIv},
     * not anchored to any prior value at all. This is the personal shop's reroll gamble: the point
     * is that the old value has no bearing on the new one, not a small nudge away from it.
     */
    public static int rollIv(RandomGenerator random) {
        return random.nextInt(32);
    }

    /**
     * Rolls every stat in {@code stats} uniformly across 0..252 independently, then renormalizes
     * down to {@link #EV_TOTAL_CAP} if the independent rolls landed over it -- exactly the cap
     * Cobblemon itself enforces, so a rerolled spread can never be illegal. The personal shop's EV
     * reroll gamble: every stat is a fresh, independent roll, with no relationship to what it was
     * a moment ago.
     *
     * <p>Scaling down proportionally can leave the total a point or two under the cap after
     * rounding; the remainder is trimmed one point at a time off whichever stat is currently
     * largest, which keeps the total exact without ever taking a stat below zero.
     */
    public static Map<String, Integer> rollEvs(Collection<String> stats, RandomGenerator random) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String stat : stats) {
            result.put(stat, random.nextInt(253));
        }
        int total = result.values().stream().mapToInt(Integer::intValue).sum();
        if (total > EV_TOTAL_CAP) {
            double scale = EV_TOTAL_CAP / (double) total;
            result.replaceAll((stat, value) -> (int) Math.floor(value * scale));
            int over = EV_TOTAL_CAP - result.values().stream().mapToInt(Integer::intValue).sum();
            while (over < 0) {
                String largest = result.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                result.merge(largest, -1, Integer::sum);
                over++;
            }
        }
        return result;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    /** Reads the optional "traits" block. Never throws; unusable fields are dropped with a warning. */
    public static RaidBossTraits fromJson(JsonObject root, String definitionId) {
        if (root == null || !root.has("traits") || !root.get("traits").isJsonObject()) return NONE;
        JsonObject traits = root.getAsJsonObject("traits");

        String heldItem = string(traits, "held_item");
        if (heldItem != null) {
            String rejection = heldItemRejection(heldItem);
            if (rejection != null) {
                warn(definitionId, "held_item '" + heldItem + "' is refused: " + rejection);
                heldItem = null;
            }
        }

        return new RaidBossTraits(
                string(traits, "nature"),
                string(traits, "ability"),
                readStats(traits, "ivs", 0, 31, definitionId),
                readStats(traits, "evs", 0, 252, definitionId),
                heldItem,
                string(traits, "gender"),
                string(traits, "tera_type"),
                string(traits, "form"));
    }

    /**
     * Reads an ivs/evs block. Public because the shop catalogue writes stats the same way and the
     * rule for what a stat is called, and what it may be set to, should have one owner.
     */
    public static Map<String, Integer> readStats(JsonObject traits, String key, int min, int max, String definitionId) {
        if (!traits.has(key) || !traits.get(key).isJsonObject()) return Map.of();
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : traits.getAsJsonObject(key).entrySet()) {
            String stat = entry.getKey().trim().toLowerCase(Locale.ROOT);
            stat = STAT_ALIASES.getOrDefault(stat, stat);
            if (!STATS.contains(stat)) {
                warn(definitionId, key + " has unknown stat '" + entry.getKey() + "'; expected one of " + STATS);
                continue;
            }
            int value;
            try {
                value = entry.getValue().getAsInt();
            } catch (RuntimeException ex) {
                warn(definitionId, key + "." + stat + " is not a number; ignoring it");
                continue;
            }
            if (value < min || value > max) {
                warn(definitionId, key + "." + stat + " is " + value + ", outside " + min + ".." + max + "; ignoring it");
                continue;
            }
            values.put(stat, value);
        }
        return values;
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        String value = object.get(key).getAsString().trim();
        return value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
    }

    private static void warn(String definitionId, String message) {
        RaidLog.error("" + definitionId + " traits: " + message);
    }
}
