package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one part of the trophy gallery with real branching logic: search, tier/shiny filters,
 * sorting and pagination, all of it driven off untrusted network input. See the class javadoc for why
 * this is split out of {@code TrophyRoomGateway} in the first place.
 */
class TrophyGalleryQueryTest {

    private static TrophyEntry entry(String species, boolean shiny, int ivPercent, long firstDefeatedAtEpochMs,
                                     RaidRarityTier tier) {
        return new TrophyEntry(ResourceLocation.parse("cobblemon:" + species), 100, shiny, ivPercent, 50,
                tier, firstDefeatedAtEpochMs, 1);
    }

    private static final List<TrophyEntry> ROSTER = List.of(
            entry("arceus", true, 100, 10, RaidRarityTier.MYTHICAL),
            entry("mewtwo", false, 90, 20, RaidRarityTier.LEGENDARY),
            entry("rayquaza", true, 95, 30, RaidRarityTier.LEGENDARY),
            entry("gengar", false, 80, 40, RaidRarityTier.POWERHOUSE));

    @Test
    @DisplayName("a search term and a tier filter both narrow the result, but the header counts stay whole-collection")
    void searchAndTierFilterNarrowResultsWithoutLosingTotals() {
        TrophyGalleryQuery.Page page = TrophyGalleryQuery.select(ROSTER, 0, 3, " RAY ", "legendary",
                TrophyGalleryQuery.Shiny.ALL, TrophyGalleryQuery.Sort.NEWEST);

        assertEquals(1, page.filteredCount());
        assertEquals("rayquaza", page.entries().get(0).species().getPath());
        assertEquals(4, page.total(), "total must count every trophy, not just what matched");
        assertEquals(2, page.shinyTotal(), "shinyTotal must count every trophy, not just what matched");
    }

    @Test
    @DisplayName("a query with spaces matches an underscored species path")
    void spacesInQueryMatchUnderscoredSpecies() {
        TrophyEntry mrMime = entry("mr_mime", false, 50, 0, RaidRarityTier.STARTER);

        TrophyGalleryQuery.Page page = TrophyGalleryQuery.select(List.of(mrMime), 0, 3, "mr mime", "",
                TrophyGalleryQuery.Shiny.ALL, TrophyGalleryQuery.Sort.NEWEST);

        assertEquals(1, page.filteredCount());
    }

    @Test
    @DisplayName("an untrusted page index and column count are both clamped, not trusted")
    void untrustedIndexAndColumnsAreClamped() {
        TrophyGalleryQuery.Page tooFar = TrophyGalleryQuery.select(ROSTER, Integer.MAX_VALUE, 0, "", "",
                TrophyGalleryQuery.Shiny.ALL, TrophyGalleryQuery.Sort.NEWEST);
        assertEquals(3, tooFar.index(), "four entries at the minimum column width of 1 is 4 pages, last index 3");
        assertEquals(4, tooFar.count());
        assertEquals(1, tooFar.entries().size(), "the last page of a 1-column layout holds exactly one entry");

        TrophyGalleryQuery.Page tooManyColumns = TrophyGalleryQuery.select(ROSTER, -50, 999, "", "",
                TrophyGalleryQuery.Shiny.ALL, TrophyGalleryQuery.Sort.NEWEST);
        assertEquals(0, tooManyColumns.index(), "a negative request must not go below page 0");
        assertEquals(2, tooManyColumns.count(), "columns must be clamped to MAX_COLUMNS (3), giving 2 pages of 4");
    }

    @Test
    @DisplayName("shiny-only and normal-only each exclude the other, and best-IV sorts descending")
    void shinyFilterAndBestIvSort() {
        TrophyGalleryQuery.Page normalOnly = TrophyGalleryQuery.select(ROSTER, 0, 3, "", "",
                TrophyGalleryQuery.Shiny.NORMAL_ONLY, TrophyGalleryQuery.Sort.BEST_IV);
        assertEquals(List.of("mewtwo", "gengar"), normalOnly.entries().stream()
                .map(entry -> entry.species().getPath()).toList());

        TrophyGalleryQuery.Page oldestFirst = TrophyGalleryQuery.select(ROSTER, 0, 3, "", "",
                TrophyGalleryQuery.Shiny.ALL, TrophyGalleryQuery.Sort.OLDEST);
        assertEquals("arceus", oldestFirst.entries().get(0).species().getPath(),
                "oldest first means the smallest firstDefeatedAtEpochMs leads");
    }

    @Test
    @DisplayName("no matches is still a valid, single, empty page -- not an error")
    void noMatchesIsAValidEmptyPage() {
        TrophyGalleryQuery.Page page = TrophyGalleryQuery.select(ROSTER, 99, 3, "no-such-species", "",
                TrophyGalleryQuery.Shiny.ALL, TrophyGalleryQuery.Sort.NEWEST);

        assertEquals(1, page.count());
        assertEquals(0, page.index());
        assertTrue(page.entries().isEmpty());
    }

    @Test
    @DisplayName("a page holds up to twice its column count, for the thumbnail strip's next-page preview")
    void pageCarriesNextPagesPreviewToo() {
        TrophyGalleryQuery.Page page = TrophyGalleryQuery.select(ROSTER, 0, 2, "", "",
                TrophyGalleryQuery.Shiny.ALL, TrophyGalleryQuery.Sort.NEWEST);

        assertEquals(4, page.entries().size(), "2 columns * 2 == the whole 4-entry roster in one page");
    }
}
