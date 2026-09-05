#!/usr/bin/env python3
import hashlib
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path

from generate_phase29 import BIOMES, VANILLA_IDS, dedupe


RAID_PREFIX = "data/cobbleraids/raids/"
TAG_PREFIX = "data/cobbleraids/tags/worldgen/biome/raid_types/"


def files(zf: zipfile.ZipFile) -> set[str]:
    return {name for name in zf.namelist() if not name.endswith("/")}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    if len(sys.argv) != 6:
        raise SystemExit(
            "usage: validate_phase30_split.py PHASE29 CORE COMPAT COBBLEMON_JAR BWG_JAR"
        )

    phase29_path, core_path, compat_path, cobblemon_path, bwg_path = map(Path, sys.argv[1:])
    with zipfile.ZipFile(cobblemon_path) as cobblemon:
        species = {
            Path(name).stem: json.loads(cobblemon.read(name))
            for name in cobblemon.namelist()
            if name.startswith("data/cobblemon/species/") and name.endswith(".json")
        }
    with zipfile.ZipFile(bwg_path) as bwg:
        valid_bwg_biomes = {
            Path(name).stem
            for name in bwg.namelist()
            if name.startswith("data/biomeswevegone/worldgen/biome/") and name.endswith(".json")
        }

    with (
        zipfile.ZipFile(phase29_path) as phase29,
        zipfile.ZipFile(core_path) as core,
        zipfile.ZipFile(compat_path) as compat,
    ):
        p29_files, core_files, compat_files = files(phase29), files(core), files(compat)
        core_manifest = json.loads(core.read("fabric.mod.json"))
        compat_manifest = json.loads(compat.read("fabric.mod.json"))

        assert core_manifest["id"] == "cobbleraids"
        assert core_manifest["version"] == "0.8.14-phase30-core"
        assert "biomeswevegone" not in core_manifest["depends"]
        assert core_manifest["depends"]["cobblemon"] == "1.7.3"
        assert compat_manifest["id"] == "cobbleraids_bwg_compat"
        assert compat_manifest["version"] == "1.0.0"
        assert compat_manifest["depends"]["cobbleraids"] == ">=0.8.14-phase30-core"
        assert compat_manifest["depends"]["biomeswevegone"] == ">=2.6.0"

        p29_classes = sorted(name for name in p29_files if name.endswith(".class"))
        core_classes = sorted(name for name in core_files if name.endswith(".class"))
        assert core_classes == p29_classes and len(core_classes) == 63
        assert all(core.read(name) == phase29.read(name) for name in core_classes)
        assert not any(name.endswith(".class") for name in compat_files)
        assert b"biome_tags" in core.read("com/cobbleraids/config/RaidDefinition.class")
        assert b"biomeTags" in core.read("com/cobbleraids/spawn/RaidSpawnContext.class")

        p29_raids = sorted(name for name in p29_files if name.startswith(RAID_PREFIX) and name.endswith(".json"))
        core_raids = sorted(name for name in core_files if name.startswith(RAID_PREFIX) and name.endswith(".json"))
        compat_raids = sorted(name for name in compat_files if name.startswith(RAID_PREFIX) and name.endswith(".json"))
        assert len(p29_raids) == len(core_raids) == 130
        assert p29_raids == core_raids
        assert compat_raids == []

        type_usage = Counter()
        for name in core_raids:
            before = json.loads(phase29.read(name))
            after = json.loads(core.read(name))
            slug = after["species"].split(":", 1)[1]
            assert slug == Path(name).stem and slug in species
            pokemon_types = dedupe([species[slug]["primaryType"], species[slug].get("secondaryType")])
            pokemon_types = [pokemon_type for pokemon_type in pokemon_types if pokemon_type]
            assert "biomes" not in after["spawn"]
            assert after["spawn"]["biome_tags"] == [
                f"cobbleraids:raid_types/{pokemon_type}" for pokemon_type in pokemon_types
            ]
            type_usage.update(pokemon_types)

            normalized_before = json.loads(json.dumps(before))
            del normalized_before["spawn"]["biomes"]
            normalized_after = json.loads(json.dumps(after))
            del normalized_after["spawn"]["biome_tags"]
            assert normalized_before == normalized_after

        core_tags = sorted(name for name in core_files if name.startswith(TAG_PREFIX) and name.endswith(".json"))
        compat_tags = sorted(name for name in compat_files if name.startswith(TAG_PREFIX) and name.endswith(".json"))
        assert len(core_tags) == len(compat_tags) == len(BIOMES) == 18
        assert [Path(name).name for name in core_tags] == [Path(name).name for name in compat_tags]

        merged_entry_count = 0
        for pokemon_type, (vanilla_names, bwg_names) in BIOMES.items():
            tag_path = f"{TAG_PREFIX}{pokemon_type}.json"
            vanilla_tag = json.loads(core.read(tag_path))
            bwg_tag = json.loads(compat.read(tag_path))
            assert vanilla_tag["replace"] is False and bwg_tag["replace"] is False
            assert vanilla_tag["values"] == [f"minecraft:{value}" for value in vanilla_names]
            assert bwg_tag["values"] == [f"biomeswevegone:{value}" for value in bwg_names]
            assert all(value in VANILLA_IDS for value in vanilla_names)
            assert all(value in valid_bwg_biomes for value in bwg_names)
            merged = dedupe([*vanilla_tag["values"], *bwg_tag["values"]])
            assert len(merged) >= 2 and any(value.startswith("minecraft:") for value in merged)
            assert any(value.startswith("biomeswevegone:") for value in merged)
            merged_entry_count += len(merged)

        allowed_core_changes = {
            "fabric.mod.json",
            "data/cobbleraids/README.txt",
            *p29_raids,
            *core_tags,
        }
        inherited = sorted(name for name in p29_files if name not in allowed_core_changes)
        assert all(name in core_files and core.read(name) == phase29.read(name) for name in inherited)

        allowed_compat_files = {
            "fabric.mod.json",
            "data/cobbleraids/BWG_COMPAT_README.txt",
            *compat_tags,
        }
        assert compat_files == allowed_compat_files
        assert b"biomeswevegone:" not in b"".join(
            core.read(name) for name in core_files if name.endswith((".json", ".txt"))
        )

    report = {
        "passed": True,
        "core_bosses": 130,
        "compat_boss_definitions": 0,
        "shared_type_tags": 18,
        "merged_tag_entries": merged_entry_count,
        "types_used": dict(sorted(type_usage.items())),
        "phase29_classes_byte_identical": 63,
        "core_has_bwg_dependency": False,
        "core_sha256": sha256(core_path),
        "compat_sha256": sha256(compat_path),
        "core_size_bytes": core_path.stat().st_size,
        "compat_size_bytes": compat_path.stat().st_size,
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
