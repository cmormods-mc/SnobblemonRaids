import com.cobbleraids.reward.ContributionMath;
import java.util.*;
public class ContributionMathTest {
  public static void main(String[] args) {
    var damage = new LinkedHashMap<String, Number>();
    damage.put("p1", 500); damage.put("p2", 300); damage.put("p3", 200); damage.put("fled", 1000);
    var pct = ContributionMath.percentages(damage, List.of("p1","p2","p3"));
    assertNear(pct.get("p1"), 50.0); assertNear(pct.get("p2"), 30.0); assertNear(pct.get("p3"), 20.0);
    var tiers = List.of(new ContributionMath.Threshold(20,1), new ContributionMath.Threshold(35,2), new ContributionMath.Threshold(50,3));
    if (ContributionMath.bonusRolls(pct.get("p1"), tiers) != 3) throw new AssertionError();
    if (ContributionMath.bonusRolls(pct.get("p2"), tiers) != 1) throw new AssertionError();
    if (ContributionMath.bonusRolls(19.999, tiers) != 0) throw new AssertionError();
    var zero = ContributionMath.percentages(Map.of(), List.of("a","b"));
    assertNear(zero.get("a"),0); assertNear(zero.get("b"),0);
    System.out.println("ContributionMathTest PASS " + pct);
  }
  static void assertNear(double a, double b) { if (Math.abs(a-b) > 1e-7) throw new AssertionError(a+" != "+b); }
}
