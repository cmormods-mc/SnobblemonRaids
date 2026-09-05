import com.cobbleraids.config.RaidRarityTier;
import com.cobbleraids.config.RaidTierWeights;
import com.cobbleraids.spawn.RaidTierSelector;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class RaidTierSelectorTest {
    private record Candidate(String id, RaidRarityTier tier, int weight) {}

    public static void main(String[] args) {
        RaidTierWeights weights = RaidTierWeights.defaults();
        Map<RaidRarityTier, Integer> allPresent = new EnumMap<>(RaidRarityTier.class);
        for (RaidRarityTier tier : RaidRarityTier.values()) allPresent.put(tier, 1);
        Map<RaidRarityTier, Double> percentages = RaidTierSelector.normalizedPercentages(allPresent, weights);
        check(percentages.get(RaidRarityTier.STARTER) == 70.0, "starter default");
        check(percentages.get(RaidRarityTier.POWERHOUSE) == 20.0, "powerhouse default");
        check(percentages.get(RaidRarityTier.LEGENDARY) == 8.0, "legendary default");
        check(percentages.get(RaidRarityTier.MYTHICAL) == 2.0, "mythical default");

        allPresent.put(RaidRarityTier.MYTHICAL, 0);
        percentages = RaidTierSelector.normalizedPercentages(allPresent, weights);
        check(close(percentages.get(RaidRarityTier.STARTER), 70.0 / 98.0 * 100.0), "renormalized starter");
        check(percentages.get(RaidRarityTier.MYTHICAL) == 0.0, "empty mythical");

        List<Candidate> onlyPowerhouse = List.of(
                new Candidate("one", RaidRarityTier.POWERHOUSE, 1),
                new Candidate("two", RaidRarityTier.POWERHOUSE, 3));
        int second = 0;
        Random random = new Random(31L);
        for (int i = 0; i < 20_000; i++) {
            Candidate selected = RaidTierSelector.select(
                    onlyPowerhouse, Candidate::tier, Candidate::weight, weights, random);
            if (selected.id().equals("two")) second++;
        }
        check(second > 14_500 && second < 15_500, "within-tier definition weights");
        System.out.println("RaidTierSelectorTest: PASS");
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 0.000001;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
