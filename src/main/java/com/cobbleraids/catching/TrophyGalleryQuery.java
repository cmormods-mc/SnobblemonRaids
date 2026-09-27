package com.cobbleraids.catching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Search, filter, sort and paginate a trophy room. Pure and Minecraft-free, so the whole thing is
 * testable without a server -- the same reason {@link com.cobbleraids.shop.ShopCatalog} stays free
 * of Minecraft types.
 *
 * <p>Split out of {@link TrophyRoomGateway} rather than left inline: the gateway's job is turning a
 * request into a packet, and this is the one part of that with real branching logic worth pinning
 * with its own tests.
 */
public final class TrophyGalleryQuery {
    private TrophyGalleryQuery() {}

    /** How the results are ordered. */
    public enum Sort {
        NEWEST, OLDEST, SPECIES, BEST_IV;

        public Sort next() {
            Sort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /** Whether shininess narrows the results at all. */
    public enum Shiny {
        ALL, SHINY_ONLY, NORMAL_ONLY;

        public Shiny next() {
            Shiny[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /**
     * One page of results, plus the counts the screen's header needs.
     *
     * <p>{@code entries} deliberately holds up to {@code columns * 2} trophies, not just
     * {@code columns}: the screen's thumbnail strip previews this page <em>and</em> the next one, so
     * a player can see what they are paging into before they turn there. The main display only ever
     * reads the first {@code columns} of them.
     */
    public record Page(
            int index,
            int count,
            int total,
            int shinyTotal,
            int filteredCount,
            List<TrophyEntry> entries
    ) {
    }

    /** The largest number of side-by-side display cases {@link #select} will ever page by. */
    public static final int MAX_COLUMNS = 3;

    /**
     * Runs a search/filter/sort over every trophy a player has, and slices out one page of it.
     *
     * @param columns   how many cases the screen has room for (1-3); also the page size. Clamped,
     *                  since it travels over the network from a client whose window the server has
     *                  no reason to trust.
     * @param query     a species-name substring, case-insensitive; spaces are treated as underscores
     *                  so typing "mr mime" matches {@code cobblemon:mr_mime}. Empty matches everything.
     * @param tierName  a {@code RaidRarityTier} serialized name, or empty for every tier.
     * @param shiny     narrows by shininess.
     * @param sort      the order results are returned in.
     */
    public static Page select(Iterable<TrophyEntry> all, int requestedIndex, int columns,
                              String query, String tierName, Shiny shiny, Sort sort) {
        columns = Math.max(1, Math.min(MAX_COLUMNS, columns));
        String needle = normalizeQuery(query);
        String tier = tierName == null ? "" : tierName.toLowerCase(Locale.ROOT);

        int total = 0;
        int shinyTotal = 0;
        List<TrophyEntry> matched = new ArrayList<>();
        for (TrophyEntry entry : all) {
            total++;
            if (entry.shiny()) shinyTotal++;
            if (!entry.species().getPath().contains(needle)) continue;
            if (!tier.isEmpty() && !entry.rarityTier().serializedName().equals(tier)) continue;
            if (shiny == Shiny.SHINY_ONLY && !entry.shiny()) continue;
            if (shiny == Shiny.NORMAL_ONLY && entry.shiny()) continue;
            matched.add(entry);
        }
        matched.sort(comparatorFor(sort).thenComparing(entry -> entry.species().toString()));

        int pageCount = Math.max(1, (matched.size() + columns - 1) / columns);
        int index = Math.max(0, Math.min(requestedIndex, pageCount - 1));
        int from = Math.min(index * columns, matched.size());
        // Up to the next page's worth too, for the thumbnail strip -- see the Page javadoc.
        int to = Math.min(from + columns * 2, matched.size());

        return new Page(index, pageCount, total, shinyTotal, matched.size(),
                List.copyOf(matched.subList(from, to)));
    }

    /** Lowercased, trimmed, and space-to-underscore so "mr mime" reaches {@code mr_mime}. */
    private static String normalizeQuery(String query) {
        return (query == null ? "" : query).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static Comparator<TrophyEntry> comparatorFor(Sort sort) {
        return switch (sort) {
            case OLDEST -> Comparator.comparingLong(TrophyEntry::firstDefeatedAtEpochMs);
            case SPECIES -> Comparator.comparing(entry -> entry.species().getPath());
            case BEST_IV -> Comparator.comparingInt(TrophyEntry::ivPercent).reversed();
            // Newest first is the default: a trophy room reads as a timeline of recent achievements,
            // the same ordering choice TrophyRoomGateway made before this class existed.
            case NEWEST -> Comparator.comparingLong(TrophyEntry::firstDefeatedAtEpochMs).reversed();
        };
    }
}
