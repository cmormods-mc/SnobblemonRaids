#!/usr/bin/env python3
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path


BIOMES = {
    "normal": (["plains", "sunflower_plains"], ["prairie", "coconino_meadow"]),
    "fire": (["badlands", "savanna"], ["firecracker_chaparral", "mojave_desert"]),
    "water": (["river", "beach"], ["bayou", "cypress_wetlands"]),
    "electric": (["windswept_hills", "stony_peaks"], ["howling_peaks", "skyris_vale"]),
    "grass": (["forest", "flower_forest"], ["overgrowth_woodlands", "temperate_grove"]),
    "ice": (["snowy_plains", "frozen_peaks"], ["shattered_glacier", "frosted_taiga"]),
    "fighting": (["meadow", "stony_peaks"], ["crag_gardens", "red_rock_peaks"]),
    "poison": (["swamp", "mangrove_swamp"], ["pale_bog", "cypress_swamplands"]),
    "ground": (["desert", "badlands"], ["atacama_outback", "windswept_desert"]),
    "flying": (["windswept_hills", "jagged_peaks"], ["howling_peaks", "lush_stacks"]),
    "psychic": (["cherry_grove", "flower_forest"], ["enchanted_tangle", "sakura_grove"]),
    "bug": (["birch_forest", "jungle"], ["fragment_jungle", "tropical_rainforest"]),
    "rock": (["stony_peaks", "badlands"], ["dacite_ridges", "basalt_barrera"]),
    "ghost": (["dark_forest", "deep_dark"], ["forgotten_forest", "pale_bog"]),
    "dragon": (["jagged_peaks", "stony_peaks"], ["red_rock_peaks", "howling_peaks"]),
    "dark": (["dark_forest", "old_growth_pine_taiga"], ["black_forest", "ebony_woods"]),
    "steel": (["stony_peaks", "windswept_gravelly_hills"], ["ironwood_gour", "canadian_shield"]),
    "fairy": (["flower_forest", "cherry_grove"], ["enchanted_tangle", "rose_fields"]),
}

VANILLA_IDS = {
    "badlands", "beach", "birch_forest", "cherry_grove", "dark_forest", "deep_dark",
    "desert", "flower_forest", "forest", "frozen_peaks", "jagged_peaks", "jungle",
    "mangrove_swamp", "meadow", "old_growth_pine_taiga", "plains", "river", "savanna",
    "snowy_plains", "stony_peaks", "sunflower_plains", "swamp", "windswept_gravelly_hills",
    "windswept_hills",
}

DIFFICULTY = {
    2: {"level": 75, "base_health": 2000},
    3: {"level": 85, "base_health": 2500},
    4: {"level": 100, "base_health": 3500},
}


def dedupe(values):
    return list(dict.fromkeys(values))


def reward_config():
    return {
        "gui_id": "cobbleraids_reward",
        "choices": {
            "candy": {"items": [{"item": "cobblemon:rare_candy", "amount": 5}]},
            "balls": {"items": [{"item": "cobblemon:ultra_ball", "amount": 8}]},
            "gamble": {
                "items": [{"item": "cobblemon:great_ball", "amount": 5}],
                "chance_items": [{"item": "cobblemon:master_ball", "amount": 1, "chance": 0.05}],
            },
        },
        "contribution_bonus": {
            "enabled": True,
            "tiers": [
                {"min_percentage": 20.0, "bonus_rolls": 1},
                {"min_percentage": 35.0, "bonus_rolls": 2},
                {"min_percentage": 50.0, "bonus_rolls": 3},
            ],
            "pool": [
                {"item": "cobblemon:rare_candy", "amount": 1, "weight": 50},
                {"item": "cobblemon:ultra_ball", "amount": 3, "weight": 35},
                {"item": "cobblemon:exp_candy_l", "amount": 1, "weight": 15},
            ],
        },
        "loot_tables": [],
    }


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: generate_phase29.py BOSS_PACK COBBLEMON_JAR BYG_JAR OUTPUT_DIR")

    boss_pack = Path(sys.argv[1])
    cobblemon_jar = Path(sys.argv[2])
    byg_jar = Path(sys.argv[3])
    output_dir = Path(sys.argv[4])
    output_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(boss_pack) as zf:
        boss_paths = sorted(
            name for name in zf.namelist()
            if name.startswith("output_v2/") and name.endswith("_boss.json")
        )
        source_bosses = [json.loads(zf.read(name)) for name in boss_paths]
        curated = json.loads(zf.read("output_v2/curated_list.json"))

    supplied_slugs = {boss["species_id"].split(":", 1)[1] for boss in source_bosses}
    indexed_slugs = set(curated["all"])
    missing_source_files = sorted(indexed_slugs - supplied_slugs)
    if missing_source_files != ["melmetal"]:
        raise AssertionError(f"unexpected index/file difference: {missing_source_files}")
    if len(source_bosses) != 130 or len(supplied_slugs) != 130:
        raise AssertionError("expected 130 unique supplied boss definitions")

    species_data = {}
    with zipfile.ZipFile(cobblemon_jar) as zf:
        for name in zf.namelist():
            if name.startswith("data/cobblemon/species/") and name.endswith(".json"):
                data = json.loads(zf.read(name))
                species_data[Path(name).stem] = data
    missing_species = sorted(supplied_slugs - species_data.keys())
    if missing_species:
        raise AssertionError(f"bosses missing from Cobblemon 1.7.3: {missing_species}")

    with zipfile.ZipFile(byg_jar) as zf:
        byg_ids = {
            Path(name).stem
            for name in zf.namelist()
            if name.startswith("data/biomeswevegone/worldgen/biome/") and name.endswith(".json")
        }

    requested_vanilla = {biome for vanilla, _ in BIOMES.values() for biome in vanilla}
    requested_byg = {biome for _, byg in BIOMES.values() for biome in byg}
    unknown_vanilla = sorted(requested_vanilla - VANILLA_IDS)
    unknown_byg = sorted(requested_byg - byg_ids)
    if unknown_vanilla or unknown_byg:
        raise AssertionError({"unknown_vanilla": unknown_vanilla, "unknown_byg": unknown_byg})

    type_counts = Counter()
    tier_counts = Counter()
    biome_counts = Counter()
    written = []

    for source in sorted(source_bosses, key=lambda item: item["species_id"]):
        species_id = source["species_id"]
        slug = species_id.split(":", 1)[1]
        species = species_data[slug]
        types = dedupe([species["primaryType"], species.get("secondaryType")])
        types = [pokemon_type for pokemon_type in types if pokemon_type]
        unknown_types = [pokemon_type for pokemon_type in types if pokemon_type not in BIOMES]
        if unknown_types:
            raise AssertionError(f"{slug} has unmapped type(s): {unknown_types}")

        vanilla = dedupe(
            biome
            for pokemon_type in types
            for biome in BIOMES[pokemon_type][0]
        )
        byg = dedupe(
            biome
            for pokemon_type in types
            for biome in BIOMES[pokemon_type][1]
        )
        if not vanilla or not byg:
            raise AssertionError(f"{slug} lacks a vanilla or BYG biome")
        biomes = [*(f"minecraft:{biome}" for biome in vanilla), *(f"biomeswevegone:{biome}" for biome in byg)]

        layers = source["random_configs"][0]["shield_layers"]
        if layers not in DIFFICULTY:
            raise AssertionError(f"{slug} has unsupported shield layer count: {layers}")
        difficulty = DIFFICULTY[layers]
        weight = source["spawn_rule"]["spawn_weight"]

        definition = {
            "species": species_id,
            "level": difficulty["level"],
            "base_health": difficulty["base_health"],
            "spawn": {
                "enabled": True,
                "weight": weight,
                "dimensions": ["minecraft:overworld"],
                "biomes": biomes,
                "times": ["all_day"],
                "cooldown_seconds": 1800,
                "despawn_seconds": 600,
                "max_concurrent": 1,
            },
            "recruitment": {"duration_seconds": 45, "radius": 10.0, "max_players": 4},
            "scaling": {"health_per_extra_player": 0.65},
            "time_limit_seconds": 900,
            "allow_flee": False,
            "rewards": reward_config(),
        }
        output_path = output_dir / f"{slug}.json"
        output_path.write_text(json.dumps(definition, indent=2) + "\n", encoding="utf-8")
        written.append(output_path)
        type_counts.update(types)
        tier_counts[layers] += 1
        biome_counts["vanilla_assignments"] += len(vanilla)
        biome_counts["byg_assignments"] += len(byg)

    if len(written) != 130:
        raise AssertionError("did not write exactly 130 raid definitions")

    report = {
        "passed": True,
        "bosses": len(written),
        "unique_species": len(supplied_slugs),
        "difficulty_counts": {
            "starter_2_layer": tier_counts[2],
            "powerhouse_3_layer": tier_counts[3],
            "legendary_mythical_4_layer": tier_counts[4],
        },
        "type_counts": dict(sorted(type_counts.items())),
        "vanilla_biomes_verified": len(requested_vanilla),
        "byg_biomes_verified": len(requested_byg),
        "biome_assignments": dict(biome_counts),
        "missing_source_files": missing_source_files,
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
