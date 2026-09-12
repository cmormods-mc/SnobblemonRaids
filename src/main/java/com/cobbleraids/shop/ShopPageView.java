package com.cobbleraids.shop;

import java.util.List;

/**
 * One page as the arrows step through it: which section it belongs to, and what sits in each cell.
 *
 * <p>Produced by {@link ShopCatalog#pages()} and used by both ends. The screen draws these entries;
 * the purchase handler resolves a click against the same list. Neither works out a page for itself,
 * because two implementations of "which entry is in slot four" is one more than the number that can
 * be right.
 */
public record ShopPageView(
        String sectionId,
        String title,
        int indexInSection,
        int pagesInSection,
        List<ShopEntry> entries
) {
    public ShopPageView {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    /** The entry in {@code slot}, or null when the cell is empty. */
    public ShopEntry entryAt(int slot) {
        return slot >= 0 && slot < entries.size() ? entries.get(slot) : null;
    }

    /** "Held Items" or "Held Items (2/3)" -- the section only numbers itself when it has to. */
    public String heading() {
        return pagesInSection > 1
                ? title + " (" + (indexInSection + 1) + "/" + pagesInSection + ")"
                : title;
    }
}
