package com.cobbleraids.network;

import net.minecraft.resources.ResourceLocation;

/**
 * One cell of the shop, as the web page needs to draw it.
 *
 * <p>Display data only, and deliberately so: the id is the only field the server will accept back.
 * The price travels because it has to be shown, not because it will be believed -- a purchase is
 * settled against the catalogue, so a client that edits this number pays the real one.
 *
 * <p>{@code ivPercent}/{@code evPercent}/{@code personal}/{@code canReroll}/{@code rerollCost} exist
 * for the personal boss shop's entries only -- a snapshot of a boss the player actually defeated,
 * not a catalogue listing. {@code personal} is what tells the client this cell came from that
 * section at all, since its id shape ({@code "personal:"}-prefixed) is otherwise the only signal.
 */
public record ShopEntryPayload(
        String id,
        int cost,
        boolean pokemon,
        ResourceLocation itemId,
        int count,
        String species,
        int level,
        boolean shiny,
        int remaining,
        int limit,
        int ivPercent,
        int evPercent,
        boolean personal,
        boolean canReroll,
        int rerollCost
) {
    /** Stands in for the item field on a Pokemon entry, which has no item to name. */
    private static final ResourceLocation NONE = ResourceLocation.withDefaultNamespace("air");

    public static ShopEntryPayload item(String id, int cost, ResourceLocation itemId, int count,
                                        int remaining, int limit) {
        return new ShopEntryPayload(id, cost, false, itemId, count, "", 0, false, remaining, limit,
                0, 0, false, false, 0);
    }

    public static ShopEntryPayload pokemon(String id, int cost, String species, int level,
                                           boolean shiny, int remaining, int limit) {
        return new ShopEntryPayload(id, cost, true, NONE, 1, species, level, shiny, remaining, limit,
                0, 0, false, false, 0);
    }

    /** A snapshot of a boss the player actually defeated, buyable back with its real, exact stats. */
    public static ShopEntryPayload personalPokemon(String id, int cost, String species, int level,
                                                   boolean shiny, int ivPercent, int evPercent,
                                                   boolean canReroll, int rerollCost) {
        // Unlimited on purpose: a personal slot is not counted against the daily/lifetime purchase
        // tally a catalogue entry uses -- it is consumed by being bought, not by a window resetting.
        return new ShopEntryPayload(id, cost, true, NONE, 1, species, level, shiny,
                Integer.MAX_VALUE, 0, ivPercent, evPercent, true, canReroll, rerollCost);
    }

    /** A limit of zero means the entry is unlimited, and the cell shows no counter at all. */
    public boolean isLimited() {
        return limit > 0;
    }

    /** True once the player has used the entry up for this window. */
    public boolean soldOut() {
        return isLimited() && remaining <= 0;
    }
}
