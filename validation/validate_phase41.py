#!/usr/bin/env python3
"""Phase 41 invariant: every bundled raid rolls its rarity tier's add-on loot table.

The AddonRewards JAR ships cobbleraids:tier/{starter,powerhouse,legendary,mythical} and the
per-add-on tables they draw from, but a raid only rolls a loot table it names in its rewards
block. When the 130 bundled definitions shipped with "loot_tables": [] none of that content
could ever drop. This locks the wiring in place: each definition must name exactly its own
tier bundle, every bundle must exist, and no bundle may point at a missing sub-table.
"""
import json
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CORE_RESOURCES = ROOT / "src/main/resources"
ADDON_RESOURCES = ROOT / "compat/addonrewards/src/main/resources"
RAIDS = CORE_RESOURCES / "data/cobbleraids/raids"
LOOT = ADDON_RESOURCES / "data/cobbleraids/loot_table"
TIERS = {"starter", "powerhouse", "legendary", "mythical"}


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def validate_definitions() -> None:
    raids = sorted(RAIDS.glob("*.json"))
    assert len(raids) == 130, len(raids)
    for path in raids:
        definition = load(path)
        tier = definition["rarity_tier"]
        assert tier in TIERS, (path.name, tier)
        loot_tables = definition["rewards"]["loot_tables"]
        assert loot_tables == [f"cobbleraids:tier/{tier}"], (path.name, loot_tables)


def validate_bundles() -> None:
    addons = {path.stem for path in (LOOT / "addons").glob("*.json")}
    for tier in sorted(TIERS):
        bundle_path = LOOT / "tier" / f"{tier}.json"
        assert bundle_path.exists(), f"missing tier bundle {bundle_path}"
        bundle = load(bundle_path)
        assert bundle["type"] == "minecraft:chest", (tier, bundle.get("type"))
        entries = [entry for pool in bundle["pools"] for entry in pool["entries"]]
        assert entries, f"{tier} bundle has no entries"
        for entry in entries:
            assert entry["type"] == "minecraft:loot_table", (tier, entry)
            ref = entry["value"]
            assert ref.startswith("cobbleraids:addons/"), (tier, ref)
            name = ref.split("/", 1)[1]
            assert name in addons, f"{tier} bundle points at missing sub-table {ref}"

    for path in sorted((LOOT / "addons").glob("*.json")):
        table = load(path)
        assert table["type"] == "minecraft:chest", (path.name, table.get("type"))
        assert [entry for pool in table["pools"] for entry in pool["entries"]], f"{path.name} is empty"


def validate_java_wiring() -> None:
    engine = (ROOT / "src/main/java/com/cobbleraids/reward/RaidRewardGrantEngine.java").read_text(encoding="utf-8")
    assert "RaidLootRoller.rollAll(player, choice.lootTables(), definitionId)" in engine
    assert "rewards().lootTables()" in engine


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        if "fabric.mod.json" in names and json.loads(archive.read("fabric.mod.json")).get("id") == "cobbleraids":
            raids = [n for n in names if n.startswith("data/cobbleraids/raids/") and n.endswith(".json")]
            assert len(raids) == 130, len(raids)
            for name in raids:
                definition = json.loads(archive.read(name))
                tier = definition["rarity_tier"]
                assert definition["rewards"]["loot_tables"] == [f"cobbleraids:tier/{tier}"], name
            return
        bundles = [n for n in names if n.startswith("data/cobbleraids/loot_table/tier/") and n.endswith(".json")]
        assert {Path(n).stem for n in bundles} == TIERS, sorted(bundles)


def main() -> None:
    validate_definitions()
    validate_bundles()
    validate_java_wiring()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 41 tier loot wiring validation: PASS")


if __name__ == "__main__":
    main()
