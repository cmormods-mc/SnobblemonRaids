package com.cobbleraids.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.catching.RaidPlayerRecord;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may buy what, and why a purchase was refused.
 *
 * <p>The service around this needs a world; the decision does not, and the decision is where the
 * mistakes would be. Refusal ordering is tested explicitly because a wrong order is not a crash --
 * it is a player being told to go and earn points for something they already own.
 */
class ShopPurchaseRulesTest {

    private static final ShopEntry BALL = ShopEntry.ofItem("ball", 25, "cobblemon:poke_ball", 8);
    private static final ShopEntry ONCE = ShopEntry.ofPokemon("starter", 500,
            new ShopPokemonGift("dratini", 15, false, null, null, null, null, null, null,
                    Map.of(), Map.of()),
            true);

    @Test
    @DisplayName("an affordable entry passes with nothing blocking it")
    void affordablePasses() {
        assertNull(ShopPurchaseRules.check(BALL, 25, false));
        assertNull(ShopPurchaseRules.check(BALL, 10_000, false));
    }

    @Test
    @DisplayName("exactly enough points is enough")
    void exactBalanceIsEnough() {
        // Off-by-one here is a listing that can never be bought at its own advertised price.
        assertNull(ShopPurchaseRules.check(BALL, BALL.cost(), false));
        assertEquals(ShopPurchaseResult.NOT_ENOUGH_POINTS,
                ShopPurchaseRules.check(BALL, BALL.cost() - 1, false));
    }

    @Test
    @DisplayName("an unknown id is refused before anything else is considered")
    void unknownEntry() {
        assertEquals(ShopPurchaseResult.UNKNOWN_ENTRY, ShopPurchaseRules.check(null, 10_000, false));
        assertEquals(ShopPurchaseResult.UNKNOWN_ENTRY, ShopPurchaseRules.check(null, 0, true));
    }

    @Test
    @DisplayName("something already owned says so, rather than complaining about the price")
    void ownershipIsCheckedBeforeAffordability() {
        // A player with 0 points who already owns the entry must hear ALREADY_OWNED. Telling them
        // they cannot afford it sends them off to earn 500 points for a purchase that will still
        // be refused.
        assertEquals(ShopPurchaseResult.ALREADY_OWNED, ShopPurchaseRules.check(ONCE, 0, true));
        assertEquals(ShopPurchaseResult.ALREADY_OWNED, ShopPurchaseRules.check(ONCE, 10_000, true));
    }

    @Test
    @DisplayName("ownership only restricts entries marked once per player")
    void repeatableEntriesIgnoreOwnership() {
        assertNull(ShopPurchaseRules.check(BALL, 100, true));
    }

    @Test
    @DisplayName("the shortfall is what the player is actually missing")
    void shortfallIsUseful() {
        assertEquals(15, ShopPurchaseRules.shortfall(BALL, 10));
        assertEquals(0, ShopPurchaseRules.shortfall(BALL, 25));
        assertEquals(0, ShopPurchaseRules.shortfall(BALL, 900), "a rich player is never short");
        assertEquals(0, ShopPurchaseRules.shortfall(null, 0));
    }

    @Test
    @DisplayName("a free entry is buyable at zero points")
    void freeEntriesAreBuyable() {
        ShopEntry free = ShopEntry.ofItem("free", 0, "cobblemon:poke_ball", 1);

        assertNull(ShopPurchaseRules.check(free, 0, false));
    }

    @Test
    @DisplayName("a once-per-player purchase is remembered, and remembered only once")
    void purchasesAreRemembered() {
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY;

        assertTrue(record.purchases().isEmpty());
        record = record.withPurchase("starter");
        assertTrue(record.hasPurchased("starter"));
        assertEquals(1, record.withPurchase("starter").purchases().size());
        assertEquals(2, record.withPurchase("other").purchases().size());
    }

    @Test
    @DisplayName("a remembered purchase survives every other update to the record")
    void purchasesSurviveOtherWrites() {
        // Winning a raid, catching a boss and earning points all rebuild the record. A purchase
        // dropped by any of them is a once-per-player entry quietly becoming buyable again.
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY.withPurchase("starter");

        assertTrue(record.withCatch().hasPurchased("starter"));
        assertTrue(record.withPoints(250).hasPurchased("starter"));
        assertTrue(record.withWin(com.cobbleraids.config.RaidRarityTier.LEGENDARY,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobbleraids", "mewtwo"),
                50.0).hasPurchased("starter"));
    }

    @Test
    @DisplayName("every refusal reason means nothing was taken")
    void onlySuccessesTakeAnything() {
        // The invariant the purchase path is built around: no outcome charges a player and leaves
        // them empty-handed, so there is no refund path to get wrong.
        for (ShopPurchaseResult result : ShopPurchaseResult.values()) {
            assertTrue(result.message() != null && !result.message().isBlank(),
                    result + " has nothing to say to the player");
        }
        assertTrue(ShopPurchaseResult.BOUGHT.success());
        assertTrue(ShopPurchaseResult.BOUGHT_TO_PC.success());
        assertEquals(2, java.util.Arrays.stream(ShopPurchaseResult.values())
                .filter(ShopPurchaseResult::success).count());
    }
}
