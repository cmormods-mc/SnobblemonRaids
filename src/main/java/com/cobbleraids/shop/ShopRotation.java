package com.cobbleraids.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * What the rotating Pokemon page offers in one four-hour window, and what each listing costs.
 *
 * <p>A pure function of the window number and the species pool: nothing is stored, nothing is
 * scheduled, and a restart in the middle of a window reproduces exactly the same ten listings. That
 * is also what makes the page the same for every player, which is the property that lets a price on
 * one of them mean something -- two players paying for "level 23 Skarmory" get the identical
 * Pokemon, nature, IVs and ability included.
 *
 * <p>Free of Minecraft and Cobblemon types. {@link ShopRotationPool} is what turns the species
 * registry into {@link Candidate}s.
 */
public final class ShopRotation {

    private ShopRotation() {}

    /** Catch rate at or below which a species counts as the hardest to acquire (pseudo-legendaries sit at 45). */
    static final int HARDEST_CATCH_RATE = 45;
    static final int EASIEST_CATCH_RATE = 255;
    /** Base stat total at or below which power adds nothing, and at or above which it is full. */
    static final int WEAKEST_BST = 250;
    static final int STRONGEST_BST = 600;

    /** The rarity floor: the commonest, weakest species still costs a tenth of the ceiling rate. */
    private static final double RARITY_FLOOR = 0.1;

    private static final String[] NATURES = {
            "hardy", "lonely", "brave", "adamant", "naughty", "bold", "docile", "relaxed", "impish", "lax",
            "timid", "hasty", "serious", "jolly", "naive", "modest", "mild", "quiet", "bashful", "rash",
            "calm", "gentle", "sassy", "careful", "quirky"};
    private static final String[] STATS = {"hp", "attack", "defence", "special_attack", "special_defence", "speed"};

    /**
     * One species the rotation may offer.
     *
     * @param speciesId what the purchase will ask Cobblemon to build: a bare name for Cobblemon's own
     *                  species, a namespaced id for an add-on's
     * @param abilities the species' ordinary (non-hidden) abilities; hidden ones are never offered
     * @param maleRatio 0..1, or negative for a genderless species
     */
    public record Candidate(String speciesId, int catchRate, int baseStatTotal, List<String> abilities,
                            float maleRatio) {}

    /** One slot of the page. {@code gift} carries every trait pinned, so the purchase is deterministic. */
    public record Listing(int slot, ShopPokemonGift gift, int cost) {
        /** The name the client keys its icons on: the path with no namespace. */
        public String speciesPath() {
            String id = gift.species();
            int colon = id.indexOf(':');
            return colon < 0 ? id : id.substring(colon + 1);
        }

        /** The entry the purchase path settles against: limited to one per rotation, per player. */
        public ShopEntry entry() {
            return ShopEntry.ofPokemon(entryId(slot), cost, gift, 1, ShopResetPeriod.ROTATION);
        }
    }

    /** The tally key. Stable across windows on purpose: the limit resets by the window stored with it. */
    public static String entryId(int slot) {
        return "rotating_" + slot;
    }

    /**
     * The id a click carries. It names the window, so a click on a listing that has since rotated is
     * refused. Dots rather than colons so it is also a legal unquoted word for /cobbleraids shop buy.
     */
    public static String wireId(long window, int slot) {
        return "rotating." + window + "." + slot;
    }

    /** Parses {@link #wireId}; null for anything that is not one. */
    public static long[] parseWireId(String id) {
        if (id == null || !id.startsWith("rotating.")) return null;
        String[] parts = id.split("\\.");
        if (parts.length != 3) return null;
        try {
            return new long[] {Long.parseLong(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The price: the ceiling, scaled by how hard the species is to come by and how high its level is.
     *
     * <p>Rarity is half catch rate and half base stat total. Catch rate is the direct measure of
     * acquisition difficulty; base stats are what the species is worth once you have it, and
     * together they keep a rare-but-weak species from pricing like a common one and a strong-but-
     * common one from pricing like a rare one. The hardest, strongest species at the top level is
     * exactly {@code maxPrice}, and nothing exceeds it.
     */
    public static int price(int catchRate, int baseStatTotal, int level, ShopRotationConfig config) {
        double catchScore = clamp01((double) (EASIEST_CATCH_RATE - catchRate) / (EASIEST_CATCH_RATE - HARDEST_CATCH_RATE));
        double powerScore = clamp01((double) (baseStatTotal - WEAKEST_BST) / (STRONGEST_BST - WEAKEST_BST));
        double rarity = RARITY_FLOOR + (1.0 - RARITY_FLOOR) * (0.5 * catchScore + 0.5 * powerScore);
        double levelShare = clamp01((double) level / config.maxLevel());
        long rounded = Math.round(config.maxPrice() * rarity * levelShare / 5.0) * 5L;
        return (int) Math.max(config.minPrice(), Math.min(config.maxPrice(), rounded));
    }

    /**
     * The listings for {@code window}, in slot order.
     *
     * <p>The pool is sorted before it is shuffled, so the answer depends on the species and not on
     * whatever order the registry happened to enumerate them in. A pool smaller than the configured
     * count yields fewer listings rather than repeating one.
     */
    public static List<Listing> roll(long window, List<Candidate> pool, ShopRotationConfig config) {
        if (pool == null || pool.isEmpty() || !config.enabled()) return List.of();
        List<Candidate> bag = new ArrayList<>(pool);
        bag.sort(Comparator.comparing(Candidate::speciesId));
        SplittableRandom random = new SplittableRandom(window * 0x9E3779B97F4A7C15L ^ 0x5DEECE66DL);

        int count = Math.min(config.listings(), bag.size());
        List<Listing> listings = new ArrayList<>(count);
        for (int slot = 0; slot < count; slot++) {
            int pick = slot + random.nextInt(bag.size() - slot);
            Candidate candidate = bag.get(pick);
            bag.set(pick, bag.get(slot));
            bag.set(slot, candidate);

            int level = config.minLevel() + random.nextInt(config.maxLevel() - config.minLevel() + 1);
            String nature = NATURES[random.nextInt(NATURES.length)];
            Map<String, Integer> ivs = new LinkedHashMap<>();
            for (String stat : STATS) ivs.put(stat, random.nextInt(32));
            String ability = candidate.abilities().isEmpty() ? null
                    : candidate.abilities().get(random.nextInt(candidate.abilities().size()));
            String gender = candidate.maleRatio() < 0f ? "genderless"
                    : random.nextDouble() < candidate.maleRatio() ? "male" : "female";

            ShopPokemonGift gift = new ShopPokemonGift(candidate.speciesId(), level, false, nature, ability,
                    gender, null, null, null, ivs, Map.of());
            // A starter line has a flat price by stage, whatever its level; everything else is priced
            // by how hard it is to come by and how high its level is.
            int cost = config.starterPriceFor(candidate.speciesId())
                    .orElseGet(() -> price(candidate.catchRate(), candidate.baseStatTotal(), level, config));
            listings.add(new Listing(slot, gift, cost));
        }
        return List.copyOf(listings);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
