#!/usr/bin/env python3
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate_phase31.py RAID_DIRECTORY BOSS_PACK")

    raid_directory = Path(sys.argv[1])
    boss_pack = Path(sys.argv[2])
    with zipfile.ZipFile(boss_pack) as archive:
        curated = json.loads(archive.read("output_v2/curated_list.json"))

    tier_by_species = {}
    for key, serialized in (
        ("starters", "starter"),
        ("powerhouse", "powerhouse"),
        ("legendary", "legendary"),
        ("mythical", "mythical"),
    ):
        for species in curated[key]:
            tier_by_species[species] = serialized

    files = sorted(raid_directory.glob("*.json"))
    if len(files) != 130:
        raise AssertionError(f"expected 130 definitions, found {len(files)}")

    counts = Counter()
    for path in files:
        definition = json.loads(path.read_text(encoding="utf-8"))
        species = definition["species"].split(":", 1)[1]
        if species != path.stem or species not in tier_by_species:
            raise AssertionError(f"unmapped definition: {path.name}")
        definition["rarity_tier"] = tier_by_species[species]
        path.write_text(json.dumps(definition, indent=2) + "\n", encoding="utf-8")
        counts[definition["rarity_tier"]] += 1

    expected = Counter({"starter": 27, "powerhouse": 10, "legendary": 71, "mythical": 22})
    if counts != expected:
        raise AssertionError({"expected": expected, "actual": counts})
    print(json.dumps({"passed": True, "bosses": len(files), "tiers": counts}, indent=2))


if __name__ == "__main__":
    main()
