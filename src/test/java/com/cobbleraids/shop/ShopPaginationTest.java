package com.cobbleraids.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Page arithmetic, which the screen and the purchase handler have to agree on exactly. */
class ShopPaginationTest {

    private static final int PER_PAGE = 72;

    @Test
    @DisplayName("pages are counted with the remainder included")
    void pageCounts() {
        assertEquals(1, new ShopPagination(1, PER_PAGE).pageCount());
        assertEquals(1, new ShopPagination(72, PER_PAGE).pageCount());
        assertEquals(2, new ShopPagination(73, PER_PAGE).pageCount());
        assertEquals(3, new ShopPagination(145, PER_PAGE).pageCount());
    }

    @Test
    @DisplayName("an empty catalogue still has one page to draw")
    void emptyStillHasAPage() {
        ShopPagination empty = new ShopPagination(0, PER_PAGE);

        assertEquals(1, empty.pageCount());
        assertEquals(0, empty.countOn(0));
        assertEquals(-1, empty.entryIndex(0, 0));
    }

    @Test
    @DisplayName("every entry appears on exactly one page, in exactly one slot")
    void everyEntryIsReachableOnce() {
        // The property that matters: nothing in the catalogue is unbuyable because it fell in a
        // gap between pages, and nothing is offered twice at two different slots.
        for (int total : new int[] {0, 1, 71, 72, 73, 144, 145, 500}) {
            ShopPagination pages = new ShopPagination(total, PER_PAGE);
            Set<Integer> seen = new HashSet<>();
            for (int page = 0; page < pages.pageCount(); page++) {
                for (int slot = 0; slot < PER_PAGE; slot++) {
                    int entry = pages.entryIndex(page, slot);
                    if (entry < 0) continue;
                    assertTrue(seen.add(entry), "entry " + entry + " offered twice for total " + total);
                }
            }
            assertEquals(total, seen.size(), "unreachable entries for total " + total);
        }
    }

    @Test
    @DisplayName("trailing cells on the last page hold nothing")
    void trailingCellsAreEmpty() {
        ShopPagination pages = new ShopPagination(75, PER_PAGE);

        assertEquals(3, pages.countOn(1));
        assertEquals(74, pages.entryIndex(1, 2));
        // A click on the fourth cell of the last page must buy nothing at all.
        assertEquals(-1, pages.entryIndex(1, 3));
        assertEquals(-1, pages.entryIndex(1, PER_PAGE - 1));
    }

    @Test
    @DisplayName("a slot outside the page is never an entry")
    void slotOutOfRange() {
        ShopPagination pages = new ShopPagination(500, PER_PAGE);

        assertEquals(-1, pages.entryIndex(0, -1));
        assertEquals(-1, pages.entryIndex(0, PER_PAGE));
    }

    @Test
    @DisplayName("a stale page number is clamped rather than read off the end")
    void stalePagesClamp() {
        ShopPagination pages = new ShopPagination(75, PER_PAGE);

        assertEquals(1, pages.clampPage(9));
        assertEquals(0, pages.clampPage(-3));
        // A click that arrives after the catalogue shrank resolves inside the new catalogue.
        assertEquals(74, pages.entryIndex(9, 2));
    }

    @Test
    @DisplayName("the arrows wrap around both ends")
    void arrowsWrap() {
        ShopPagination pages = new ShopPagination(145, PER_PAGE);

        assertEquals(1, pages.nextPage(0));
        assertEquals(2, pages.nextPage(1));
        assertEquals(0, pages.nextPage(2));
        assertEquals(2, pages.previousPage(0));
        assertEquals(0, pages.previousPage(1));
    }

    @Test
    @DisplayName("a single page wraps to itself rather than going nowhere")
    void singlePageWraps() {
        ShopPagination one = new ShopPagination(5, PER_PAGE);

        assertEquals(0, one.nextPage(0));
        assertEquals(0, one.previousPage(0));
    }

    @Test
    @DisplayName("a nonsense page size is refused at construction")
    void refusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new ShopPagination(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new ShopPagination(10, -5));
        assertThrows(IllegalArgumentException.class, () -> new ShopPagination(-1, PER_PAGE));
    }
}
