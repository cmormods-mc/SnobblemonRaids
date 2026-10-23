#!/usr/bin/env python3
"""Reward-grant visibility, and the guarantee that SkiesGUIs stays removed.

Both properties are easy to silently undo with a small edit (a reflex "simplify the return type
back to void", a reflex "move skiesguis back to depends"), so the properties that make them work
are asserted here rather than left to review.
"""
import json
import sys
import zipfile
import re
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

    # The grant path must draw from the claim's own seeded generator, never a global one. This
    # replaced a pair of assertions pinning the exact ThreadLocalRandom call sites, which were
    # asserting the opposite of what is now required: a claim that draws from ThreadLocalRandom
    # cannot be reproduced after a restart, which is the whole point of the per-claim seed. Nothing
    # else would catch that -- the rewards would still arrive, just different ones each time.
    assert "ThreadLocalRandom" not in engine, (
        "RaidRewardGrantEngine draws from ThreadLocalRandom, so an unclaimed reward would reroll"
        " across a restart instead of regenerating")

    # The one production call site must actually capture the result, not discard it again.
    assert "RewardGrantResult result = RaidRewardGrantEngine.grantChoice(" in service

    # The claim-failure safety net (restore the pending claim so it isn't lost) must survive.
    assert "addFirst(pending);" in service

    # The captured result must actually be used (logged/displayed), not just bound and ignored.
    assert "result.baseItems()" in service
    assert "result.chanceItemsGranted()" in service
    assert "result.contributionBonusItems()" in service


def validate_skiesguis_removed() -> None:
    """SkiesGUIs was dropped: the web reveal page and the chat claim are the only reward surfaces."""
    manifest = json.loads(read(RESOURCES / "fabric.mod.json"))
    for section in ("depends", "recommends", "suggests"):
        assert "skiesguis" not in manifest.get(section, {}), f"skiesguis is back in {section}"

    for path in JAVA.rglob("*.java"):
        text = read(path)
        assert "com.pokeskies" not in text, f"{path.name} still imports SkiesGUIs"
    for name in ("SkiesGuisRewardGuiBackend", "RaidRewardGuiInstaller", "RewardGuiBackends"):
        assert not (REWARD / f"{name}.java").exists(), f"{name} should have been deleted"

    service = read(JAVA / "lifecycle/RaidRewardService.java")
    assert "/cobbleraids reward claim" in service, "chat fallback must tell players how to claim"


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read("fabric.mod.json"))
        for section in ("depends", "recommends", "suggests"):
            assert "skiesguis" not in manifest.get(section, {})
        assert not any(name.startswith("assets/cobbleraids/skiesguis/") for name in archive.namelist())


def main() -> None:
    validate_reward_result()
    validate_skiesguis_removed()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Reward visibility / SkiesGUIs removal validation: PASS")


if __name__ == "__main__":
    main()
