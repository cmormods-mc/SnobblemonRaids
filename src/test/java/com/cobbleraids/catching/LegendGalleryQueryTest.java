package com.cobbleraids.catching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.renown.RenownBoon;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the Hall of Legends' filter/pagination logic, the same reasoning
 * {@code TrophyGalleryQueryTest} pins {@code TrophyGalleryQuery}'s.
 */
class LegendGalleryQueryTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static LegendEntry entry(String species, RaidRarityTier tier, long defeatedAtEpochMs, UUID victor) {
        return new LegendEntry("Someone, the Something", ResourceLocation.parse("cobblemon:" + species),
                tier, RenownBoon.HP_POOL, defeatedAtEpochMs, List.of(victor), List.of("Name"));
    }

    private static final List<LegendEntry> ROSTER = List.of(
            entry("arceus", RaidRarityTier.MYTHICAL, 10, ALICE),
            entry("mewtwo", RaidRarityTier.LEGENDARY, 30, ALICE),
            entry("rayquaza", RaidRarityTier.LEGENDARY, 20, BOB),
            entry("gengar", RaidRarityTier.POWERHOUSE, 40, BOB));

    @Test
    @DisplayName("results come back newest first, always")
    void resultsAreNewestFirst() {
        LegendGalleryQuery.Page page = LegendGalleryQuery.select(ROSTER, 0, 8, null, false, "");

        assertEquals(List.of("gengar", "mewtwo", "rayquaza", "arceus"), page.entries().stream()
                .map(e -> e.species().getPath()).toList());
    }

    @Test
    @DisplayName("a tier filter narrows the result, but the total stays scoped to the filtered set")
    void tierFilterNarrowsResults() {
        LegendGalleryQuery.Page page = LegendGalleryQuery.select(ROSTER, 0, 8, null, false, "legendary");

        assertEquals(2, page.total());
        assertEquals(List.of("mewtwo", "rayquaza"), page.entries().stream()
                .map(e -> e.species().getPath()).toList());
    }

    @Test
    @DisplayName("mineOnly narrows the returned entries, but total still reports the whole (tier-filtered) set")
    void mineOnlyNarrowsEntriesNotTotal() {
        LegendGalleryQuery.Page mine = LegendGalleryQuery.select(ROSTER, 0, 8, ALICE, true, "");

        assertEquals(4, mine.total(), "total is not narrowed by mineOnly -- the screen reads mineCount for that");
        assertEquals(2, mine.mineCount());
        assertEquals(List.of("mewtwo", "arceus"), mine.entries().stream()
                .map(e -> e.species().getPath()).toList());
    }

    @Test
    @DisplayName("mineCount reflects the viewer's legends even while browsing everyone's, unaffected by mineOnly")
    void mineCountIsIndependentOfMineOnly() {
        LegendGalleryQuery.Page browsingAll = LegendGalleryQuery.select(ROSTER, 0, 8, ALICE, false, "");
        assertEquals(4, browsingAll.total(), "mineOnly=false must not narrow the page");
        assertEquals(2, browsingAll.mineCount(), "mineCount still says how many of the visible set are the viewer's");

        LegendGalleryQuery.Page noViewer = LegendGalleryQuery.select(ROSTER, 0, 8, null, false, "");
        assertEquals(0, noViewer.mineCount(), "a null viewer can hold no legends");
    }

    @Test
    @DisplayName("an untrusted page index and row count are both clamped, not trusted")
    void untrustedIndexAndRowsAreClamped() {
        LegendGalleryQuery.Page tooFar = LegendGalleryQuery.select(ROSTER, Integer.MAX_VALUE, 1, null, false, "");
        assertEquals(3, tooFar.index(), "four entries at a page size of 1 is 4 pages, last index 3");
        assertEquals(4, tooFar.count());
        assertEquals(1, tooFar.entries().size());

        LegendGalleryQuery.Page tooManyRows = LegendGalleryQuery.select(ROSTER, -50, 999, null, false, "");
        assertEquals(0, tooManyRows.index(), "a negative request must not go below page 0");
        assertEquals(1, tooManyRows.count(), "rows must be clamped to MAX_COLUMNS, giving one page for 4 entries");
    }

    @Test
    @DisplayName("no matches is still a valid, single, empty page -- not an error")
    void noMatchesIsAValidEmptyPage() {
        LegendGalleryQuery.Page page = LegendGalleryQuery.select(ROSTER, 99, 8, null, false, "starter");

        assertEquals(1, page.count());
        assertEquals(0, page.index());
        assertTrue(page.entries().isEmpty());
    }
}
