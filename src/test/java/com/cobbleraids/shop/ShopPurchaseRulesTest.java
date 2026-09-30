package com.cobbleraids.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.catching.RaidPlayerRecord;
import com.cobbleraids.catching.RaidPurchaseTally;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may buy what, how often, and when that refills.
 *
 * <p>The clock is passed in rather than read from the machine, because a daily limit whose rollover
 * cannot be tested is a daily limit nobody can trust -- and the interesting cases all live either
 * side of a midnight nobody is going to sit and wait for.
 */
class ShopPurchaseRulesTest {

    /** Mid-afternoon, so nothing here is accidentally sitting on a boundary. */
    private static final Instant NOON = Instant.parse("2026-09-12T12:00:00Z");
    private static final Instant LATE = Instant.parse("2026-09-12T23:59:59Z");
    private static final Instant NEXT_DAY = Instant.parse("2026-09-13T00:00:01Z");

    private static final ShopEntry BALLS =
            ShopEntry.ofItem("balls", 25, "cobblemon:poke_ball", 8, 5, ShopResetPeriod.DAILY);
    private static final ShopEntry MON = ShopEntry.ofPokemon("starter", 500, gift(), 1, ShopResetPeriod.DAILY);
    private static final ShopEntry UNIQUE = ShopEntry.ofPokemon("unique", 9000, gift(), 1, ShopResetPeriod.NEVER);
    private static final ShopEntry UNLIMITED =
            ShopEntry.ofItem("free_flow", 5, "cobblemon:poke_ball", 1, 0, ShopResetPeriod.DAILY);

    private static ShopPokemonGift gift() {
        return new ShopPokemonGift("dratini", 15, false, null, null, null, null, null, null,
                Map.of(), Map.of());
    }

    private static RaidPurchaseTally boughtToday(int count, Instant when) {
        return new RaidPurchaseTally(count, ShopResetPeriod.DAILY.windowOf(when));
    }

    @Test
    @DisplayName("a fresh player may buy up to the limit and no further")
    void countsDownToTheLimit() {
        RaidPurchaseTally none = RaidPurchaseTally.NONE;

        assertEquals(5, ShopPurchaseRules.remaining(BALLS, none, NOON));
        assertEquals(2, ShopPurchaseRules.remaining(BALLS, boughtToday(3, NOON), NOON));
        assertEquals(0, ShopPurchaseRules.remaining(BALLS, boughtToday(5, NOON), NOON));
        assertNull(ShopPurchaseRules.check(BALLS, 10_000, boughtToday(4, NOON), NOON));
        assertEquals(ShopPurchaseResult.LIMIT_REACHED,
                ShopPurchaseRules.check(BALLS, 10_000, boughtToday(5, NOON), NOON));
    }

    @Test
    @DisplayName("a count beyond the limit still reads as none left, never as a negative")
    void overshootDoesNotGoNegative() {
        // An operator lowering a limit from 10 to 5 leaves players holding counts above it.
        assertEquals(0, ShopPurchaseRules.remaining(BALLS, boughtToday(40, NOON), NOON));
    }

    @Test
    @DisplayName("a daily limit refills at midnight UTC")
    void dailyResets() {
        RaidPurchaseTally spent = boughtToday(5, LATE);

        assertEquals(0, ShopPurchaseRules.remaining(BALLS, spent, LATE));
        assertEquals(5, ShopPurchaseRules.remaining(BALLS, spent, NEXT_DAY));
        assertNull(ShopPurchaseRules.check(BALLS, 10_000, spent, NEXT_DAY));
    }

    @Test
    @DisplayName("one second before midnight is still yesterday")
    void theBoundaryIsExact() {
        // Off by an hour here and a server in the wrong timezone resets at the wrong moment; off by
        // a second and a player loses or gains a purchase at the boundary.
        RaidPurchaseTally spent = boughtToday(5, NOON);

        assertEquals(0, ShopPurchaseRules.remaining(BALLS, spent, LATE));
        assertEquals(0, ShopPurchaseRules.remaining(BALLS, spent,
                LATE.plus(500, ChronoUnit.MILLIS)));
        assertEquals(5, ShopPurchaseRules.remaining(BALLS, spent,
                Instant.parse("2026-09-13T00:00:00Z")));
    }

    @Test
    @DisplayName("a never-resetting limit is not refilled by the calendar")
    void neverMeansNever() {
        // Stored against window 0, and it has to stay spent however far the clock moves.
        RaidPurchaseTally spent = new RaidPurchaseTally(1, ShopResetPeriod.NEVER.windowOf(NOON));

        assertEquals(0, ShopPurchaseRules.remaining(UNIQUE, spent, NOON));
        assertEquals(0, ShopPurchaseRules.remaining(UNIQUE, spent, NEXT_DAY));
        assertEquals(0, ShopPurchaseRules.remaining(UNIQUE, spent,
                NEXT_DAY.plus(3650, ChronoUnit.DAYS)));
    }

    @Test
    @DisplayName("a Pokemon is one a day, which is the shipped intent")
    void pokemonAreOnePerDay() {
        assertEquals(1, ShopPurchaseRules.remaining(MON, RaidPurchaseTally.NONE, NOON));
        assertEquals(ShopPurchaseResult.LIMIT_REACHED,
                ShopPurchaseRules.check(MON, 10_000, boughtToday(1, NOON), NOON));
        assertNull(ShopPurchaseRules.check(MON, 10_000, boughtToday(1, NOON), NEXT_DAY));
    }

    @Test
    @DisplayName("an unlimited entry is never refused for stock and never counted")
    void unlimitedIsUnlimited() {
        assertEquals(Integer.MAX_VALUE,
                ShopPurchaseRules.remaining(UNLIMITED, boughtToday(9999, NOON), NOON));
        assertNull(ShopPurchaseRules.check(UNLIMITED, 10, boughtToday(9999, NOON), NOON));
    }

    @Test
    @DisplayName("the limit is checked before the price")
    void limitIsCheckedBeforeAffordability() {
        // A player with nothing who has already hit their cap must hear about the cap. Telling them
        // they cannot afford it sends them off to earn points for a refusal.
        assertEquals(ShopPurchaseResult.LIMIT_REACHED,
                ShopPurchaseRules.check(BALLS, 0, boughtToday(5, NOON), NOON));
    }

    @Test
    @DisplayName("an unknown id is refused before anything else is considered")
    void unknownEntry() {
        assertEquals(ShopPurchaseResult.UNKNOWN_ENTRY,
                ShopPurchaseRules.check(null, 10_000, RaidPurchaseTally.NONE, NOON));
    }

    @Test
    @DisplayName("exactly enough points is enough")
    void exactBalanceIsEnough() {
        assertNull(ShopPurchaseRules.check(BALLS, BALLS.cost(), RaidPurchaseTally.NONE, NOON));
        assertEquals(ShopPurchaseResult.NOT_ENOUGH_POINTS,
                ShopPurchaseRules.check(BALLS, BALLS.cost() - 1, RaidPurchaseTally.NONE, NOON));
        assertEquals(15, ShopPurchaseRules.shortfall(BALLS, 10));
        assertEquals(0, ShopPurchaseRules.shortfall(BALLS, 25));
    }

    @Test
    @DisplayName("the refusal says something specific to the entry that caused it")
    void refusalsReadWell() {
        assertEquals("You have already bought that.", ShopPurchaseRules.limitMessage(UNIQUE));
        assertEquals("You have already bought that today.", ShopPurchaseRules.limitMessage(MON));
        assertEquals("You have bought all 5 of those today.", ShopPurchaseRules.limitMessage(BALLS));
    }

    @Test
    @DisplayName("a tally counts up within a window and starts over across one")
    void tallyArithmetic() {
        long today = ShopResetPeriod.DAILY.windowOf(NOON);
        long tomorrow = ShopResetPeriod.DAILY.windowOf(NEXT_DAY);

        RaidPurchaseTally tally = RaidPurchaseTally.NONE.increment(today).increment(today);
        assertEquals(2, tally.count());
        assertEquals(1, tally.increment(tomorrow).count(), "a new day starts the count again");
        assertEquals(tomorrow, tally.increment(tomorrow).day());
    }

    @Test
    @DisplayName("the record counts purchases per entry, and keeps them apart")
    void recordCountsPerEntry() {
        long today = ShopResetPeriod.DAILY.windowOf(NOON);
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY
                .withPurchase("balls", today)
                .withPurchase("balls", today)
                .withPurchase("starter", today);

        assertEquals(2, record.purchasesOf("balls").count());
        assertEquals(1, record.purchasesOf("starter").count());
        assertEquals(0, record.purchasesOf("never_bought").count());
    }

    @Test
    @DisplayName("pruning drops yesterday's tallies and keeps the permanent ones")
    void pruningIsHousekeepingOnly() {
        long today = ShopResetPeriod.DAILY.windowOf(NOON);
        long tomorrow = ShopResetPeriod.DAILY.windowOf(NEXT_DAY);
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY
                .withPurchase("balls", today)
                .withPurchase("unique", 0L);

        RaidPlayerRecord pruned = record.prunePurchases(NEXT_DAY);

        assertEquals(0, pruned.purchasesOf("balls").count(), "yesterday's daily tally is gone");
        assertEquals(1, pruned.purchasesOf("unique").count(), "a permanent tally must survive");
        // And it changes nothing about what is allowed, which is the point of calling it safe.
        assertEquals(5, ShopPurchaseRules.remaining(BALLS, record.purchasesOf("balls"), NEXT_DAY));
        assertEquals(5, ShopPurchaseRules.remaining(BALLS, pruned.purchasesOf("balls"), NEXT_DAY));
    }

    /** One purchase, through the same record method RaidPlayerRecords.recordPurchase uses. */
    private static RaidPlayerRecord buy(RaidPlayerRecord record, ShopEntry entry, Instant when) {
        return record.withPurchaseOn(entry.id(), ShopPurchaseRules.windowOf(entry, when), when);
    }

    @Test
    @DisplayName("buying a lifetime entry does not refill a daily limit spent the same day")
    void lifetimePurchaseKeepsTodaysDailyTallies() {
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY;
        for (int i = 0; i < 5; i++) record = buy(record, BALLS, NOON);
        assertEquals(0, ShopPurchaseRules.remaining(BALLS, record.purchasesOf("balls"), NOON));

        record = buy(record, UNIQUE, NOON);

        assertEquals(0, ShopPurchaseRules.remaining(BALLS, record.purchasesOf("balls"), LATE),
                "a lifetime purchase must not hand back today's daily stock");
        assertEquals(0, ShopPurchaseRules.remaining(UNIQUE, record.purchasesOf("unique"), LATE));

        // Midnight refills the daily entry and nothing else.
        record = buy(record, BALLS, NEXT_DAY);
        assertEquals(4, ShopPurchaseRules.remaining(BALLS, record.purchasesOf("balls"), NEXT_DAY));
        assertEquals(0, ShopPurchaseRules.remaining(UNIQUE, record.purchasesOf("unique"), NEXT_DAY),
                "a daily purchase must not forget a lifetime one");
    }

    @Test
    @DisplayName("purchase tallies survive every other update to the record")
    void talliesSurviveOtherWrites() {
        long today = ShopResetPeriod.DAILY.windowOf(NOON);
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY.withPurchase("balls", today);

        assertEquals(1, record.withCatch().purchasesOf("balls").count());
        assertEquals(1, record.withPoints(250).purchasesOf("balls").count());
        assertEquals(1, record.withWin(com.cobbleraids.config.RaidRarityTier.LEGENDARY,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobbleraids", "mewtwo"),
                50.0).purchasesOf("balls").count());
    }

    @Test
    @DisplayName("every refusal reason still has something to say")
    void everyResultSpeaks() {
        for (ShopPurchaseResult result : ShopPurchaseResult.values()) {
            assertTrue(result.message() != null && !result.message().isBlank(),
                    result + " has nothing to say to the player");
        }
        // BOUGHT, BOUGHT_TO_PC, and REROLLED -- the personal shop's reroll gamble succeeding is a
        // third distinct way to leave a player charged and better/worse off, not empty-handed.
        assertEquals(3, java.util.Arrays.stream(ShopPurchaseResult.values())
                .filter(ShopPurchaseResult::success).count());
    }

    private static final ShopEntry WEEKLY_EGG =
            ShopEntry.ofItem("lucky_egg", 600, "cobblemon:lucky_egg", 1, 3, ShopResetPeriod.WEEKLY);

    @Test
    @DisplayName("a weekly limit holds through the week and refills on Monday")
    void weeklyLimitRefillsOnMonday() {
        Instant saturday = Instant.parse("2026-09-12T12:00:00Z");
        Instant sunday = Instant.parse("2026-09-13T23:59:59Z");
        Instant monday = Instant.parse("2026-09-14T00:00:01Z");
        RaidPlayerRecord record = RaidPlayerRecord.EMPTY;
        for (int index = 0; index < 3; index++) record = buy(record, WEEKLY_EGG, saturday);

        assertEquals(0, ShopPurchaseRules.remaining(WEEKLY_EGG, record.purchasesOf("lucky_egg"), sunday));
        assertEquals(3, ShopPurchaseRules.remaining(WEEKLY_EGG, record.purchasesOf("lucky_egg"), monday));
    }

    @Test
    @DisplayName("buying something else the next day does not refill a weekly limit")
    void dailyPurchaseKeepsWeeklyTallies() {
        Instant saturday = Instant.parse("2026-09-12T12:00:00Z");
        Instant sunday = Instant.parse("2026-09-13T12:00:00Z");
        RaidPlayerRecord record = buy(RaidPlayerRecord.EMPTY, WEEKLY_EGG, saturday);
        record = buy(record, WEEKLY_EGG, saturday);
        record = buy(record, WEEKLY_EGG, saturday);

        record = buy(record, BALLS, sunday);

        assertEquals(0, ShopPurchaseRules.remaining(WEEKLY_EGG, record.purchasesOf("lucky_egg"), sunday),
                "pruning to today alone used to delete the weekly tally here");
    }

    @Test
    @DisplayName("the refusal names the week")
    void weeklyRefusalMessage() {
        assertEquals("You have bought all 3 of those this week.", ShopPurchaseRules.limitMessage(WEEKLY_EGG));
    }

    private static final ShopEntry ROTATING =
            ShopEntry.ofPokemon("rotating_3", 400, gift(), 1, ShopResetPeriod.ROTATION);

    @Test
    @DisplayName("a rotating listing can be bought once per four-hour window, then again in the next")
    void rotatingLimitResetsEveryFourHours() {
        Instant first = Instant.parse("2026-09-12T08:10:00Z");
        Instant sameWindow = Instant.parse("2026-09-12T11:59:59Z");
        Instant nextWindow = Instant.parse("2026-09-12T12:00:01Z");

        RaidPlayerRecord record = buy(RaidPlayerRecord.EMPTY, ROTATING, first);

        assertEquals(0, ShopPurchaseRules.remaining(ROTATING, record.purchasesOf("rotating_3"), sameWindow));
        assertEquals(1, ShopPurchaseRules.remaining(ROTATING, record.purchasesOf("rotating_3"), nextWindow));
    }

    @Test
    @DisplayName("buying something else in the same window does not refill a rotating listing")
    void otherPurchasesKeepRotatingTallies() {
        Instant when = Instant.parse("2026-09-12T08:10:00Z");
        RaidPlayerRecord record = buy(RaidPlayerRecord.EMPTY, ROTATING, when);

        record = buy(record, BALLS, when.plusSeconds(600));

        assertEquals(0, ShopPurchaseRules.remaining(ROTATING, record.purchasesOf("rotating_3"), when.plusSeconds(900)),
                "pruning must keep the current rotation's tally");
    }
}
