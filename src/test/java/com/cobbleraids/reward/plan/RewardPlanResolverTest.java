package com.cobbleraids.reward.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbleraids.config.CobbleRaidsConfig;
import com.cobbleraids.config.RaidDefinition;
import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.config.RaidRewardPolicy;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The resolver is the single owner of "what does this claim grant", so these are the tests that
 * matter most in the reward path: which of the two modes a definition is in, and exactly which
 * tables each mode produces.
 */
class RewardPlanResolverTest {

    private static final RaidRewardPolicy POLICY = RaidRewardPolicy.defaults();
    private static final CobbleRaidsConfig.Currency NO_CURRENCY = CobbleRaidsConfig.Currency.disabled();
    private static final Predicate<String> NO_BOSS_TABLES = table -> false;

    private static RaidDefinition.RewardItem item(String id, int amount) {
        return new RaidDefinition.RewardItem(ResourceLocation.parse(id), amount, 1.0, 1);
    }

    private static RaidDefinition.RewardChoice choice(List<RaidDefinition.RewardItem> items,
                                                      List<RaidDefinition.RewardItem> chance,
                                                      List<ResourceLocation> tables) {
        return new RaidDefinition.RewardChoice("all", items, chance, tables);
    }

    private static RaidDefinition.RewardChoice emptyChoice() {
        return choice(List.of(), List.of(), List.of());
    }

    private static RaidDefinition.Rewards rewards(List<ResourceLocation> tables,
                                                  RaidDefinition.ContributionBonus bonus) {
        return new RaidDefinition.Rewards("cobbleraids_reward",
                java.util.Map.of("all", emptyChoice()), bonus, tables);
    }

    private static RaidDefinition.ContributionBonus noBonus() {
        return new RaidDefinition.ContributionBonus(false, List.of(), List.of());
    }

    private static RewardPlan resolve(RaidDefinition.Rewards rewards, RaidDefinition.RewardChoice choice,
                                      RaidRarityTier tier, String species, int bonusRolls,
                                      Predicate<String> tableExists) {
        return RewardPlanResolver.resolve(rewards, choice, tier, species, 100.0, bonusRolls,
                POLICY, NO_CURRENCY, tableExists);
    }

    // --- classification --------------------------------------------------------------------------

    @Test
    @DisplayName("a definition naming nothing of its own is policy-driven")
    void emptyDefinitionIsPolicyDriven() {
        assertFalse(RewardPlanResolver.isSelfDescribing(rewards(List.of(), noBonus()), emptyChoice()));
    }

    @Test
    @DisplayName("anything a hand-written definition can name makes it self-describing")
    void anyOwnRewardMakesItLegacy() {
        RaidDefinition.Rewards plain = rewards(List.of(), noBonus());

        assertTrue(RewardPlanResolver.isSelfDescribing(plain,
                choice(List.of(item("cobblemon:rare_candy", 5)), List.of(), List.of())), "items");
        assertTrue(RewardPlanResolver.isSelfDescribing(plain,
                choice(List.of(), List.of(item("cobblemon:master_ball", 1)), List.of())), "chance items");
        assertTrue(RewardPlanResolver.isSelfDescribing(plain,
                choice(List.of(), List.of(), List.of(ResourceLocation.parse("cobbleraids:tier/starter")))),
                "choice loot tables");
        assertTrue(RewardPlanResolver.isSelfDescribing(
                rewards(List.of(ResourceLocation.parse("cobbleraids:tier/starter")), noBonus()), emptyChoice()),
                "rewards-level loot tables");
        assertTrue(RewardPlanResolver.isSelfDescribing(
                rewards(List.of(), new RaidDefinition.ContributionBonus(true,
                        List.of(new RaidDefinition.ContributionTier(20.0, 1)),
                        List.of(item("cobblemon:rare_candy", 1)))), emptyChoice()),
                "inline contribution pool");
    }

    @Test
    @DisplayName("classification works without a choice, which is how the registry asks at load")
    void classificationToleratesNoChoice() {
        assertFalse(RewardPlanResolver.isSelfDescribing(rewards(List.of(), noBonus()), null));
        assertTrue(RewardPlanResolver.isSelfDescribing(
                rewards(List.of(ResourceLocation.parse("cobbleraids:tier/starter")), noBonus()), null));
        assertFalse(RewardPlanResolver.isSelfDescribing(null, null));
    }

    @Test
    @DisplayName("a contribution block that is present but switched off does not make it legacy")
    void disabledBonusDoesNotCount() {
        RaidDefinition.ContributionBonus off = new RaidDefinition.ContributionBonus(
                false, List.of(new RaidDefinition.ContributionTier(20.0, 1)), List.of(item("cobblemon:rare_candy", 1)));

        assertFalse(RewardPlanResolver.isSelfDescribing(rewards(List.of(), off), emptyChoice()));
    }

    // --- policy path -----------------------------------------------------------------------------

    @Test
    @DisplayName("a claim is one specialty selection plus two general ones, before contribution")
    void policyPlanIsThreeSelections() {
        RewardPlan plan = resolve(rewards(List.of(), noBonus()), emptyChoice(),
                RaidRarityTier.STARTER, "charizard", 0, NO_BOSS_TABLES);

        RewardPlan.Policy policy = assertInstanceOf(RewardPlan.Policy.class, plan);
        assertEquals(List.of("cobbleraids:specialty/starter",
                        "cobbleraids:general/starter",
                        "cobbleraids:general/starter"),
                policy.lootTables());
    }

    @Test
    @DisplayName("each earned bonus roll adds exactly one general selection, and never a specialty one")
    void bonusRollsAddGeneralSelectionsOnly() {
        for (int bonus = 0; bonus <= 3; bonus++) {
            RewardPlan.Policy plan = assertInstanceOf(RewardPlan.Policy.class,
                    resolve(rewards(List.of(), noBonus()), emptyChoice(),
                            RaidRarityTier.LEGENDARY, "mewtwo", bonus, NO_BOSS_TABLES));

            assertEquals(3 + bonus, plan.lootTables().size(), "selection count at B=" + bonus);
            assertEquals(1, plan.lootTables().stream().filter(t -> t.contains("specialty")).count(),
                    "specialty selections at B=" + bonus);
            assertEquals(2 + bonus, plan.lootTables().stream().filter(t -> t.contains("general")).count(),
                    "general selections at B=" + bonus);
            assertEquals("cobbleraids:specialty/legendary", plan.lootTables().get(0),
                    "the specialty selection is always first at B=" + bonus);
        }
    }

    @Test
    @DisplayName("a boss with its own specialty table gets it; the other 109 fall back to their tier")
    void bossTableIsUsedWhenItExists() {
        Predicate<String> onlyCharizard = Set.of("cobbleraids:specialty/boss/charizard")::contains;

        RewardPlan.Policy matched = assertInstanceOf(RewardPlan.Policy.class,
                resolve(rewards(List.of(), noBonus()), emptyChoice(), RaidRarityTier.STARTER, "charizard", 0, onlyCharizard));
        RewardPlan.Policy unmatched = assertInstanceOf(RewardPlan.Policy.class,
                resolve(rewards(List.of(), noBonus()), emptyChoice(), RaidRarityTier.STARTER, "pidgeot", 0, onlyCharizard));

        assertEquals("cobbleraids:specialty/boss/charizard", matched.lootTables().get(0));
        assertEquals("cobbleraids:specialty/starter", unmatched.lootTables().get(0));
    }

    @Test
    @DisplayName("species case does not decide whether a boss table is found")
    void speciesIsLowercasedBeforeLookup() {
        Predicate<String> onlyCharizard = Set.of("cobbleraids:specialty/boss/charizard")::contains;

        RewardPlan.Policy plan = assertInstanceOf(RewardPlan.Policy.class,
                resolve(rewards(List.of(), noBonus()), emptyChoice(), RaidRarityTier.STARTER, "Charizard", 0, onlyCharizard));

        assertEquals("cobbleraids:specialty/boss/charizard", plan.lootTables().get(0));
    }

    @Test
    @DisplayName("every tier draws from its own tables")
    void tiersUseTheirOwnTables() {
        for (RaidRarityTier tier : RaidRarityTier.values()) {
            RewardPlan.Policy plan = assertInstanceOf(RewardPlan.Policy.class,
                    resolve(rewards(List.of(), noBonus()), emptyChoice(), tier, null, 0, NO_BOSS_TABLES));

            assertEquals("cobbleraids:specialty/" + tier.serializedName(), plan.lootTables().get(0));
            assertEquals("cobbleraids:general/" + tier.serializedName(), plan.lootTables().get(1));
        }
    }

    // --- legacy path -----------------------------------------------------------------------------

    @Test
    @DisplayName("a legacy definition still rolls its choice tables and its rewards tables together")
    void legacyPlanKeepsBothTableLevels() {
        RaidDefinition.RewardChoice legacyChoice = choice(List.of(item("cobblemon:rare_candy", 5)), List.of(),
                List.of(ResourceLocation.parse("cobbleraids:addons/cards")));
        RaidDefinition.Rewards legacyRewards = rewards(
                List.of(ResourceLocation.parse("cobbleraids:tier/starter")), noBonus());

        RewardPlan.Legacy plan = assertInstanceOf(RewardPlan.Legacy.class,
                resolve(legacyRewards, legacyChoice, RaidRarityTier.STARTER, "charizard", 0, NO_BOSS_TABLES));

        assertEquals(List.of("cobbleraids:addons/cards", "cobbleraids:tier/starter"), plan.lootTables());
        assertEquals(1, plan.items().size());
    }

    @Test
    @DisplayName("a legacy definition's bonus rolls draw from its own pool, not the policy's tables")
    void legacyBonusUsesItsOwnPool() {
        RaidDefinition.ContributionBonus bonus = new RaidDefinition.ContributionBonus(true,
                List.of(new RaidDefinition.ContributionTier(20.0, 1)),
                List.of(item("cobblemon:rare_candy", 1)));

        RewardPlan.Legacy plan = assertInstanceOf(RewardPlan.Legacy.class,
                resolve(rewards(List.of(), bonus), emptyChoice(), RaidRarityTier.STARTER, "charizard", 2, NO_BOSS_TABLES));

        assertEquals(2, plan.bonusRolls());
        assertEquals(1, plan.bonusPool().size());
        assertTrue(plan.lootTables().isEmpty(), "a legacy plan must not gain policy tables");
    }

    // --- how many bonus rolls a share earns -------------------------------------------------------

    @Test
    @DisplayName("a policy-driven definition earns its bonus rolls from the server policy")
    void policyDefinitionUsesPolicyThresholds() {
        // The regression this pins: when the bundled definitions were migrated, their inline
        // contribution blocks went with them. A victory site that only read inline blocks awarded
        // every player zero bonus rolls, so every claim was three selections and the 20/35/50
        // thresholds did nothing -- while the config, the tables and the logs all looked right.
        RaidDefinition.Rewards policyDriven = rewards(List.of(), noBonus());

        assertEquals(0, RewardPlanResolver.bonusRollsFor(policyDriven, emptyChoice(), POLICY, 19.99));
        assertEquals(1, RewardPlanResolver.bonusRollsFor(policyDriven, emptyChoice(), POLICY, 20.0));
        assertEquals(2, RewardPlanResolver.bonusRollsFor(policyDriven, emptyChoice(), POLICY, 35.0));
        assertEquals(3, RewardPlanResolver.bonusRollsFor(policyDriven, emptyChoice(), POLICY, 100.0));
    }

    @Test
    @DisplayName("a legacy definition earns its bonus rolls from its own block, not the policy")
    void legacyDefinitionUsesItsOwnThresholds() {
        RaidDefinition.Rewards legacy = rewards(List.of(), new RaidDefinition.ContributionBonus(true,
                List.of(new RaidDefinition.ContributionTier(80.0, 1)),
                List.of(item("cobblemon:rare_candy", 1))));

        // The policy would award 3 at this share; this definition awards nothing until 80%.
        assertEquals(0, RewardPlanResolver.bonusRollsFor(legacy, emptyChoice(), POLICY, 60.0));
        assertEquals(1, RewardPlanResolver.bonusRollsFor(legacy, emptyChoice(), POLICY, 80.0));
    }

    @Test
    @DisplayName("a legacy definition with contribution switched off earns nothing")
    void legacyWithBonusOffEarnsNothing() {
        RaidDefinition.Rewards legacy = rewards(
                List.of(ResourceLocation.parse("cobbleraids:tier/starter")), noBonus());

        assertEquals(0, RewardPlanResolver.bonusRollsFor(legacy, emptyChoice(), POLICY, 100.0));
    }

    // --- currency ---------------------------------------------------------------------------------

    @Test
    @DisplayName("currency is decided once, here, and reaches both kinds of plan")
    void currencyReachesBothPlans() {
        CobbleRaidsConfig.Currency paying = new CobbleRaidsConfig.Currency(
                true, 100L, 100L, 100L, 100L, false, 0.0);

        RewardPlan policyPlan = RewardPlanResolver.resolve(rewards(List.of(), noBonus()), emptyChoice(),
                RaidRarityTier.STARTER, "charizard", 100.0, 0, POLICY, paying, NO_BOSS_TABLES);
        RewardPlan legacyPlan = RewardPlanResolver.resolve(rewards(List.of(), noBonus()),
                choice(List.of(item("cobblemon:rare_candy", 1)), List.of(), List.of()),
                RaidRarityTier.STARTER, "charizard", 100.0, 0, POLICY, paying, NO_BOSS_TABLES);

        assertEquals(BigInteger.valueOf(100L), policyPlan.currency());
        assertEquals(BigInteger.valueOf(100L), legacyPlan.currency());
        assertEquals("policy", policyPlan.mode());
        assertEquals("legacy", legacyPlan.mode());
    }
}
