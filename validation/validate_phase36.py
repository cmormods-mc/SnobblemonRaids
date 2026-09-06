#!/usr/bin/env python3
"""Phase 36 invariants: reward-grant visibility and SkiesGUIs becoming an optional dependency.

Both properties are easy to silently undo with a small edit (a reflex "simplify the return type
back to void", a reflex "move skiesguis back to depends"), so the properties that make them work
are asserted here rather than left to review.
"""
import json
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/cobbleraids"
REWARD = JAVA / "reward"
RESOURCES = ROOT / "src/main/resources"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def validate_reward_result() -> None:
    engine = read(REWARD / "RaidRewardGrantEngine.java")
    result = read(REWARD / "RewardGrantResult.java")
    service = read(ROOT / "src/main/java/com/cobbleraids/lifecycle/RaidRewardService.java")

    # grantChoice must report what it granted, not just grant it.
    assert "public static RewardGrantResult grantChoice(" in engine
    assert "public static void grantChoice(" not in engine

    for field in ("baseItems", "chanceItemsGranted", "contributionBonusItems"):
        assert field in result, f"RewardGrantResult is missing {field}"

    # The RNG draw sites must be unchanged -- exactly one nextDouble() per chance item and one
    # nextLong() per weighted() roll, same as before the refactor.
    assert engine.count("ThreadLocalRandom.current().nextDouble() < item.chance()") == 1
    assert engine.count("ThreadLocalRandom.current().nextLong(total)") == 1

    # The one production call site must actually capture the result, not discard it again.
    assert "RewardGrantResult result = RaidRewardGrantEngine.grantChoice(" in service

    # The claim-failure safety net (restore the pending claim so it isn't lost) must survive.
    assert "addFirst(pending);" in service

    # The captured result must actually be used (logged/displayed), not just bound and ignored.
    assert "result.baseItems()" in service
    assert "result.chanceItemsGranted()" in service
    assert "result.contributionBonusItems()" in service


def validate_skiesguis_optional() -> None:
    manifest_text = read(RESOURCES / "fabric.mod.json")
    manifest = json.loads(manifest_text)
    assert "skiesguis" not in manifest.get("depends", {}), "skiesguis must not be a hard dependency"
    assert manifest.get("recommends", {}).get("skiesguis") == "1.8.1"

    initializer = read(JAVA / "CobbleRaids.java")
    assert "RaidRewardGuiInstaller" not in initializer, "the unconditional installer call must be gone"
    assert "RewardGuiBackends.ensureReady()" in initializer

    backends = read(REWARD / "RewardGuiBackends.java")
    assert 'FabricLoader.getInstance().isModLoaded("skiesguis")' in backends
    assert "catch (RuntimeException ex)" in backends, "a broken SkiesGUIs install must not abort server start"
    assert "com.pokeskies.skiesguis" not in backends, "SkiesGUIs classes must stay isolated in the adapter impl"

    skies_backend = read(REWARD / "SkiesGuisRewardGuiBackend.java")
    assert "com.pokeskies.skiesguis" in skies_backend, "isolation should be concentrated here, not spread out"

    service = read(ROOT / "src/main/java/com/cobbleraids/lifecycle/RaidRewardService.java")
    assert "com.pokeskies.skiesguis" not in service, "RaidRewardService must not talk to SkiesGUIs directly"
    assert "/cobbleraids reward claim" in service, "chat fallback must tell players how to claim"

    installer = read(REWARD / "RaidRewardGuiInstaller.java")
    assert "Failed to install/load CobbleRaids reward GUI" in installer, "installer behavior must be unchanged"

    debug_ops = read(ROOT / "src/main/java/com/cobbleraids/command/RaidAdminDebugOps.java")
    assert "rewardGui=" in debug_ops, "the active reward-GUI backend must be surfaced to admins"


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert "skiesguis" not in manifest.get("depends", {})
        assert manifest.get("recommends", {}).get("skiesguis") == "1.8.1"


def main() -> None:
    validate_reward_result()
    validate_skiesguis_optional()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 36 reward visibility / SkiesGUIs optionality validation: PASS")


if __name__ == "__main__":
    main()
