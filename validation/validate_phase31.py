#!/usr/bin/env python3
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CORE_RESOURCES = ROOT / "src/main/resources"
COMPAT_RESOURCES = ROOT / "compat/bwg/src/main/resources"
TIERS = {"starter", "powerhouse", "legendary", "mythical"}
EXPECTED_COUNTS = Counter({"starter": 27, "powerhouse": 10, "legendary": 71, "mythical": 22})


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def validate_tree() -> None:
    raids = sorted((CORE_RESOURCES / "data/cobbleraids/raids").glob("*.json"))
    assert len(raids) == 130, len(raids)

    tiers = Counter()
    species = set()
    for path in raids:
        definition = load(path)
        slug = definition["species"].split(":", 1)[1]
        assert definition["species"].startswith("cobblemon:")
        assert slug == path.stem
        assert definition["species"] not in species
        species.add(definition["species"])
        tier = definition["rarity_tier"]
        assert tier in TIERS
        tiers[tier] += 1

        spawn = definition["spawn"]
        assert "biomes" not in spawn
        assert 1 <= len(spawn["biome_tags"]) <= 2
        assert all(tag.startswith("cobbleraids:raid_types/") for tag in spawn["biome_tags"])
        assert spawn["dimensions"] == ["minecraft:overworld"]
        assert definition["recruitment"]["max_players"] == 4
        assert definition["allow_flee"] is False

    assert tiers == EXPECTED_COUNTS, (tiers, EXPECTED_COUNTS)

    core_tags = sorted((CORE_RESOURCES / "data/cobbleraids/tags/worldgen/biome/raid_types").glob("*.json"))
    compat_tags = sorted((COMPAT_RESOURCES / "data/cobbleraids/tags/worldgen/biome/raid_types").glob("*.json"))
    assert len(core_tags) == len(compat_tags) == 18
    assert [path.name for path in core_tags] == [path.name for path in compat_tags]
    for core, compat in zip(core_tags, compat_tags):
        core_data, compat_data = load(core), load(compat)
        assert core_data["replace"] is False and compat_data["replace"] is False
        assert core_data["values"] and compat_data["values"]
        assert all(value.startswith("minecraft:") for value in core_data["values"])
        assert all(value.startswith("biomeswevegone:") for value in compat_data["values"])

    core_text = "\n".join(path.read_text(encoding="utf-8") for path in CORE_RESOURCES.rglob("*.json"))
    assert "biomeswevegone:" not in core_text

    compat_manifest = load(COMPAT_RESOURCES / "fabric.mod.json")
    assert compat_manifest["id"] == "cobbleraids_bwg_compat"
    assert compat_manifest["depends"]["biomeswevegone"] == ">=2.6.0"
    assert "biomeswevegone" not in load(CORE_RESOURCES / "fabric.mod.json")["depends"]

    mixins = load(CORE_RESOURCES / "mixins/cobbleraids.mixins.json")["mixins"]
    assert "battle.RaidMoveActionResponseMixin" in mixins
    assert "battle.RaidActiveBattlePokemonMixin" in mixins

    scheduler = (ROOT / "src/main/java/com/cobbleraids/spawn/RaidSpawnScheduler.java").read_text()
    damage = (ROOT / "src/main/java/com/cobbleraids/showdown/RaidDamageInstruction.java").read_text()
    assert "RaidTierSelector.select" in scheduler
    assert "announceNaturalSpawn" in scheduler
    assert "sendSpawnInfo" in scheduler and "testWild" in scheduler
    assert "sendSidedUpdate" in damage
    assert "BattleHealthChangePacket(pnx, ratio, null)" in damage


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert manifest["version"] == "0.8.15-phase31-spawn-director"
        assert "biomeswevegone" not in manifest["depends"]
        assert "com/cobbleraids/config/RaidRarityTier.class" in names
        assert "com/cobbleraids/config/RaidTierWeights.class" in names
        assert "com/cobbleraids/spawn/RaidTierSelector.class" in names
        assert len([name for name in names if name.startswith("data/cobbleraids/raids/") and name.endswith(".json")]) == 130


def main() -> None:
    validate_tree()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 31 source/resource validation: PASS")


if __name__ == "__main__":
    main()
