#!/usr/bin/env python3
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CORE_RESOURCES = ROOT / "src/main/resources"
COMPAT_RESOURCES = ROOT / "compat/biome/src/main/resources"
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
        assert all(value.startswith("#") for value in compat_data["values"])

    core_text = "\n".join(path.read_text(encoding="utf-8") for path in CORE_RESOURCES.rglob("*.json"))
    assert "biomeswevegone:" not in core_text

    compat_manifest = load(COMPAT_RESOURCES / "fabric.mod.json")
    assert compat_manifest["id"] == "cobbleraids_biome_compat"
    assert "biomeswevegone" not in compat_manifest["depends"]
    assert "biomeswevegone" not in load(CORE_RESOURCES / "fabric.mod.json")["depends"]

    mixins = load(CORE_RESOURCES / "mixins/cobbleraids.mixins.json")["mixins"]
    assert "battle.RaidMoveActionResponseMixin" in mixins
    assert "battle.RaidActiveBattlePokemonMixin" in mixins

    scheduler = (ROOT / "src/main/java/com/cobbleraids/spawn/RaidSpawnScheduler.java").read_text()
    damage = (ROOT / "src/main/java/com/cobbleraids/showdown/RaidDamageInstruction.java").read_text()
    # Announcements and the two admin commands moved out of the scheduler; assert they still exist
    # and are still wired, rather than that they live in any one file.
    announcements = (ROOT / "src/main/java/com/cobbleraids/spawn/RaidSpawnAnnouncementService.java").read_text()
    commands = (ROOT / "src/main/java/com/cobbleraids/spawn/RaidSpawnCommands.java").read_text()
    assert "RaidTierSelector.select" in scheduler
    assert "static void naturalSpawn(" in announcements
    assert "RaidSpawnAnnouncementService.naturalSpawn(" in scheduler
    assert "sendSpawnInfo" in commands and "testWild" in commands
    assert "sendSidedUpdate" in damage
    assert "BattleHealthChangePacket(pnx, ratio, null)" in damage


def project_version() -> str:
    """Read the version from build.gradle so a version bump cannot drift from validation."""
    for line in (ROOT / "build.gradle").read_text(encoding="utf-8").splitlines():
        if line.startswith("version = "):
            return line.split("=", 1)[1].strip().strip("'\"")
    raise AssertionError("build.gradle has no version assignment")


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert manifest["version"] == project_version(), (manifest["version"], project_version())
        assert "biomeswevegone" not in manifest["depends"]
        assert "com/cobbleraids/config/RaidRarityTier.class" in names
        assert "com/cobbleraids/config/RaidTierWeights.class" in names
        assert "com/cobbleraids/spawn/RaidTierSelector.class" in names
        assert len([name for name in names if name.startswith("data/cobbleraids/raids/") and name.endswith(".json")]) == 130


def validate_reward_template() -> None:
    """The documented reward template must stay loadable and stay in step with the parser.

    examples/server.json drifted several settings behind the schema and was deleted; this exists so
    the replacement cannot do the same quietly. It checks the shape a datapack author would copy,
    not the values.
    """
    template = ROOT / "docs/reward-template.json"
    assert template.exists(), "docs/reward-template.json is referenced by the README and must exist"
    data = json.loads(template.read_text(encoding="utf-8"))

    for required in ("species", "level", "base_health", "rewards"):
        assert required in data, f"template is missing {required}, so it would not load"

    rewards = data["rewards"]
    # Keys RaidDefinition.parseRewards actually reads. A key here that the parser dropped, or a
    # parser key the template never shows, means the template is teaching something untrue.
    assert set(k for k in rewards if not k.startswith("_")) == {
        "gui_id", "loot_tables", "choices", "contribution_bonus"}, sorted(rewards)

    definition = (ROOT / "src/main/java/com/cobbleraids/config/RaidDefinition.java").read_text(encoding="utf-8")
    for key in ("gui_id", "loot_tables", "choices", "contribution_bonus", "chance_items", "min_percentage"):
        assert f'"{key}"' in definition, f"template documents {key} but the parser never reads it"

    def check_items(items, where):
        for entry in items:
            assert ":" in entry["item"], f"{where}: item ids need a namespace ({entry})"
            assert entry.get("amount", 1) >= 1, f"{where}: amount must be at least 1"

    for table in rewards["loot_tables"]:
        assert ":" in table, f"loot table ids need a namespace ({table})"
    assert rewards["choices"], "the template must show at least one choice"
    for name, choice in rewards["choices"].items():
        body = {k: v for k, v in choice.items() if not k.startswith("_")}
        assert body, f"choice {name} is empty"
        assert set(body) <= {"items", "chance_items", "loot_tables"}, (name, sorted(body))
        check_items(body.get("items", []), name)
        for entry in body.get("chance_items", []):
            assert 0.0 <= entry["chance"] <= 1.0, f"{name}: chance must be 0..1"
        check_items(body.get("chance_items", []), name)
    # One choice must demonstrate loot tables, since that is what the template is for.
    assert any("loot_tables" in c for c in rewards["choices"].values()),         "the template must show a loot-table-only choice"

    bonus = rewards["contribution_bonus"]
    check_items(bonus["pool"], "contribution_bonus.pool")
    for entry in bonus["pool"]:
        assert entry["weight"] >= 1, "contribution bonus weights must be at least 1"


def main() -> None:
    validate_tree()
    validate_reward_template()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 31 source/resource validation: PASS")


if __name__ == "__main__":
    main()
