#!/usr/bin/env python3
import hashlib
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path

from generate_phase29 import BIOMES, DIFFICULTY, VANILLA_IDS, dedupe


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    if len(sys.argv) != 6:
        raise SystemExit("usage: validate_phase29.py PHASE28 PHASE29 BOSS_PACK COBBLEMON_JAR BYG_JAR")
    base_path, final_path, pack_path, cobblemon_path, byg_path = map(Path, sys.argv[1:])

    with zipfile.ZipFile(base_path) as base, zipfile.ZipFile(final_path) as final:
        base_files = {name for name in base.namelist() if not name.endswith("/")}
        final_files = {name for name in final.namelist() if not name.endswith("/")}
        inherited = {
            name for name in base_files
            if name != "fabric.mod.json" and not name.startswith("data/cobbleraids/raids/")
        }
        missing_inherited = sorted(inherited - final_files)
        changed_inherited = sorted(name for name in inherited if base.read(name) != final.read(name))
        unexpected = sorted(
            name for name in final_files
            if name not in inherited
            and name != "fabric.mod.json"
            and not name.startswith("data/cobbleraids/raids/")
        )
        assert not missing_inherited, missing_inherited
        assert not changed_inherited, changed_inherited
        assert not unexpected, unexpected

        manifest = json.loads(final.read("fabric.mod.json"))
        assert manifest["version"] == "0.8.13-phase29-bosspool"
        assert manifest["depends"]["cobblemon"] == "1.7.3"
        assert manifest["depends"]["biomeswevegone"] == ">=2.6.0"

        raid_names = sorted(
            name for name in final_files
            if name.startswith("data/cobbleraids/raids/") and name.endswith(".json")
        )
        assert len(raid_names) == 130, len(raid_names)
        definitions = {Path(name).stem: json.loads(final.read(name)) for name in raid_names}

    with zipfile.ZipFile(pack_path) as pack:
        source_names = sorted(
            name for name in pack.namelist()
            if name.startswith("output_v2/") and name.endswith("_boss.json")
        )
        source = {
            item["species_id"].split(":", 1)[1]: item
            for item in (json.loads(pack.read(name)) for name in source_names)
        }
        index = json.loads(pack.read("output_v2/curated_list.json"))
    assert sorted(set(index["all"]) - source.keys()) == ["melmetal"]
    assert set(definitions) == set(source)

    with zipfile.ZipFile(cobblemon_path) as cobblemon:
        species = {}
        for name in cobblemon.namelist():
            if name.startswith("data/cobblemon/species/") and name.endswith(".json"):
                species[Path(name).stem] = json.loads(cobblemon.read(name))

    with zipfile.ZipFile(byg_path) as byg:
        byg_manifest = json.loads(byg.read("fabric.mod.json"))
        assert byg_manifest["id"] == "biomeswevegone"
        assert byg_manifest["version"] == "2.6.0"
        valid_byg = {
            Path(name).stem for name in byg.namelist()
            if name.startswith("data/biomeswevegone/worldgen/biome/") and name.endswith(".json")
        }

    assignments = Counter()
    tiers = Counter()
    all_species = set()
    for slug, definition in definitions.items():
        all_species.add(definition["species"])
        assert definition["species"] == source[slug]["species_id"]
        assert slug in species
        pokemon_types = dedupe([species[slug]["primaryType"], species[slug].get("secondaryType")])
        pokemon_types = [pokemon_type for pokemon_type in pokemon_types if pokemon_type]
        expected_vanilla = dedupe(biome for pokemon_type in pokemon_types for biome in BIOMES[pokemon_type][0])
        expected_byg = dedupe(biome for pokemon_type in pokemon_types for biome in BIOMES[pokemon_type][1])
        expected_biomes = [*(f"minecraft:{biome}" for biome in expected_vanilla), *(f"biomeswevegone:{biome}" for biome in expected_byg)]
        spawn = definition["spawn"]
        assert spawn["biomes"] == expected_biomes
        assert spawn["dimensions"] == ["minecraft:overworld"]
        assert spawn["times"] == ["all_day"]
        assert spawn["enabled"] is True
        assert any(value.startswith("minecraft:") for value in spawn["biomes"])
        assert any(value.startswith("biomeswevegone:") for value in spawn["biomes"])
        assert all(value.split(":", 1)[1] in VANILLA_IDS for value in spawn["biomes"] if value.startswith("minecraft:"))
        assert all(value.split(":", 1)[1] in valid_byg for value in spawn["biomes"] if value.startswith("biomeswevegone:"))
        assignments["vanilla"] += len(expected_vanilla)
        assignments["byg"] += len(expected_byg)

        layers = source[slug]["random_configs"][0]["shield_layers"]
        tiers[layers] += 1
        assert definition["level"] == DIFFICULTY[layers]["level"]
        assert definition["base_health"] == DIFFICULTY[layers]["base_health"]
        assert spawn["weight"] == source[slug]["spawn_rule"]["spawn_weight"]
        assert definition["recruitment"] == {"duration_seconds": 45, "radius": 10.0, "max_players": 4}
        assert definition["scaling"] == {"health_per_extra_player": 0.65}
        assert definition["time_limit_seconds"] == 900
        assert definition["allow_flee"] is False
        assert set(definition["rewards"]["choices"]) == {"candy", "balls", "gamble"}

    assert len(all_species) == 130
    assert tiers == Counter({4: 93, 2: 27, 3: 10})
    final_bytes = final_path.read_bytes()
    report = {
        "passed": True,
        "packaged_bosses": len(definitions),
        "unique_species": len(all_species),
        "difficulty_counts": {"2_layer": tiers[2], "3_layer": tiers[3], "4_layer": tiers[4]},
        "biome_assignments": dict(assignments),
        "verified_byg_biome_ids": len({value for pair in BIOMES.values() for value in pair[1]}),
        "phase28_inherited_files_unchanged": len(inherited),
        "phase29_size_bytes": len(final_bytes),
        "phase29_sha256": sha256(final_bytes),
        "source_index_without_file": ["melmetal"],
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
