#!/usr/bin/env python3
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path

from generate_phase29 import BIOMES, VANILLA_IDS, dedupe


TAG_PREFIX = "cobbleraids:raid_types/"


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 5:
        raise SystemExit(
            "usage: generate_phase30_split.py CORE_STAGING COMPAT_STAGING COBBLEMON_JAR BYG_JAR"
        )

    core_staging = Path(sys.argv[1])
    compat_staging = Path(sys.argv[2])
    cobblemon_jar = Path(sys.argv[3])
    byg_jar = Path(sys.argv[4])
    raid_dir = core_staging / "data/cobbleraids/raids"
    raid_files = sorted(raid_dir.glob("*.json"))
    if len(raid_files) != 130:
        raise AssertionError(f"expected 130 Phase 29 raid definitions, found {len(raid_files)}")

    with zipfile.ZipFile(cobblemon_jar) as zf:
        species_data = {
            Path(name).stem: json.loads(zf.read(name))
            for name in zf.namelist()
            if name.startswith("data/cobblemon/species/") and name.endswith(".json")
        }

    with zipfile.ZipFile(byg_jar) as zf:
        byg_manifest = json.loads(zf.read("fabric.mod.json"))
        if byg_manifest.get("id") != "biomeswevegone" or byg_manifest.get("version") != "2.6.0":
            raise AssertionError("the supplied BWG JAR is not biomeswevegone 2.6.0")
        valid_byg = {
            Path(name).stem
            for name in zf.namelist()
            if name.startswith("data/biomeswevegone/worldgen/biome/") and name.endswith(".json")
        }

    used_types = Counter()
    for raid_file in raid_files:
        definition = json.loads(raid_file.read_text(encoding="utf-8"))
        slug = definition["species"].split(":", 1)[1]
        if slug != raid_file.stem or slug not in species_data:
            raise AssertionError(f"invalid or missing Cobblemon species for {raid_file.name}")
        species = species_data[slug]
        types = dedupe([species["primaryType"], species.get("secondaryType")])
        types = [pokemon_type for pokemon_type in types if pokemon_type]
        if any(pokemon_type not in BIOMES for pokemon_type in types):
            raise AssertionError(f"unmapped type for {slug}: {types}")

        expected_phase29_biomes = [
            *(f"minecraft:{biome}" for pokemon_type in types for biome in BIOMES[pokemon_type][0]),
            *(f"biomeswevegone:{biome}" for pokemon_type in types for biome in BIOMES[pokemon_type][1]),
        ]
        expected_phase29_biomes = dedupe(expected_phase29_biomes)
        if definition["spawn"].get("biomes") != expected_phase29_biomes:
            raise AssertionError(f"unexpected Phase 29 biome mapping for {slug}")

        rewritten_spawn = {}
        for key, value in definition["spawn"].items():
            if key == "biomes":
                rewritten_spawn["biome_tags"] = [f"{TAG_PREFIX}{pokemon_type}" for pokemon_type in types]
            else:
                rewritten_spawn[key] = value
        definition["spawn"] = rewritten_spawn
        write_json(raid_file, definition)
        used_types.update(types)

    if set(used_types) != set(BIOMES):
        raise AssertionError(f"boss pool does not exercise every mapped type: {sorted(used_types)}")

    core_tag_dir = core_staging / "data/cobbleraids/tags/worldgen/biome/raid_types"
    compat_tag_dir = compat_staging / "data/cobbleraids/tags/worldgen/biome/raid_types"
    for pokemon_type, (vanilla_biomes, byg_biomes) in BIOMES.items():
        unknown_vanilla = set(vanilla_biomes) - VANILLA_IDS
        unknown_byg = set(byg_biomes) - valid_byg
        if unknown_vanilla or unknown_byg:
            raise AssertionError(
                {"type": pokemon_type, "unknown_vanilla": sorted(unknown_vanilla), "unknown_byg": sorted(unknown_byg)}
            )
        write_json(
            core_tag_dir / f"{pokemon_type}.json",
            {"replace": False, "values": [f"minecraft:{biome}" for biome in vanilla_biomes]},
        )
        write_json(
            compat_tag_dir / f"{pokemon_type}.json",
            {"replace": False, "values": [f"biomeswevegone:{biome}" for biome in byg_biomes]},
        )

    report = {
        "passed": True,
        "bosses": len(raid_files),
        "shared_type_tags": len(BIOMES),
        "core_tag_entries": sum(len(value[0]) for value in BIOMES.values()),
        "compat_tag_entries": sum(len(value[1]) for value in BIOMES.values()),
        "types_used_by_pool": dict(sorted(used_types.items())),
        "core_contains_bwg_biome_ids": False,
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
