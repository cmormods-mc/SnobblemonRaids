#!/usr/bin/env python3
"""Generates a shop catalogue that exercises every feature the shop has.

Not the shipped defaults -- those are deliberately small, safe, and free of optional-mod items.
This one is the opposite: it is meant to be dropped onto the test rig to make every code path
visible at once, including the ones that only show up at the edges.

    python validation/shop/build_test_catalog.py            # writes test_catalog.json
    python validation/shop/build_test_catalog.py --check     # fails if the committed file drifted

What it covers, and why each is here:

  * a full top row of nine Pokemon, so the model rendering is exercised at the width it will
    actually be drawn at rather than one cell at a time
  * shiny variants, which take a different aspect set and therefore a different cached state
  * a mix of purchase limits -- unlimited, five a day, one a day, and one for ever -- which is
    the only way to see the stock counter, the sold-out cell, and each of the three different
    refusal sentences a limit produces
  * items from an optional mod (mega_showdown), which the shipped defaults must never use and a
    test catalogue absolutely should, because that is the path where an id that does not resolve
    turns into a player paying for nothing
  * a section of 80 entries, which is the only way to see a section paginate and its heading
    number itself
  * a section of one entry, which must NOT number itself
  * prices well above any sane balance, so the unaffordable dim and the red price are visible

Every id is checked against the pack manifest before it is written, so this file cannot ship a
listing that does not resolve.
"""

import argparse
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(ROOT, "validation", "economy", "manifest.json")
RAIDS = os.path.join(ROOT, "src", "main", "resources", "data", "cobbleraids", "raids")
OUTPUT = os.path.join(ROOT, "validation", "shop", "test_catalog.json")

# Nine, because that is one full row of the grid.
SHOWCASE = [
    "charizard", "blastoise", "greninja",
    "garchomp", "metagross", "tyranitar",
    "dragonite", "salamence", "hydreigon",
]
SHINY_VAULT = ["charizard", "garchomp", "metagross", "dragonite"]

CONSUMABLES = [
    ("poke_ball", 5, 16), ("great_ball", 12, 16), ("ultra_ball", 25, 16),
    ("heal_ball", 10, 16), ("revive", 20, 8), ("max_revive", 60, 4),
    ("max_potion", 30, 8), ("full_restore", 45, 4), ("rare_candy", 75, 1),
    ("exp_candy_l", 40, 8), ("exp_candy_xl", 90, 4), ("pp_up", 100, 1),
]
HELD_ITEMS = [
    ("leftovers", 150), ("choice_band", 175), ("choice_specs", 175),
    ("choice_scarf", 175), ("life_orb", 200), ("focus_sash", 200),
    ("assault_vest", 175), ("rocky_helmet", 150), ("lucky_egg", 250),
    ("exp_share", 200), ("metal_coat", 90), ("kings_rock", 90),
]


def load_manifest():
    with io.open(MANIFEST, encoding="utf-8") as handle:
        return json.load(handle)


def known_species():
    names = set()
    for name in os.listdir(RAIDS):
        if not name.endswith(".json"):
            continue
        with io.open(os.path.join(RAIDS, name), encoding="utf-8") as handle:
            names.add(json.load(handle)["species"].split(":")[1])
    return names


def item_entry(entry_id, cost, item, count=1, limit=None, reset=None):
    row = {"id": entry_id, "cost": cost, "item": item, "count": count}
    if limit is not None:
        row["limit"] = limit
    if reset is not None:
        row["reset"] = reset
    return row


def pokemon_entry(entry_id, cost, species, level, shiny=False, limit=None, reset=None, traits=None):
    row = {"id": entry_id, "cost": cost, "species": species, "level": level}
    if shiny:
        row["shiny"] = True
    if limit is not None:
        row["limit"] = limit
    if reset is not None:
        row["reset"] = reset
    if traits:
        row["traits"] = traits
    return row


def build(manifest, species):
    mega = manifest["mega"]
    tera = manifest["tera_shards"]
    sections = []

    # One full row of models, at a price a tester can actually reach with /cobbleraids points give.
    sections.append({
        "id": "showcase", "title": "Pokemon",
        "entries": [pokemon_entry("mon_" + name, 200 + index * 50, name, 25)
                    for index, name in enumerate(SHOWCASE)],
    })

    # Shiny aspects, limits that never refill, and one fully pinned example so the traits block
    # is proven end to end rather than only in a unit test.
    vault = [pokemon_entry("shiny_" + name, 2500, name, 50, shiny=True,
                           limit=1, reset="never")
             for name in SHINY_VAULT]
    vault.append(pokemon_entry("perfect_garchomp", 5000, "garchomp", 100,
                               limit=1, reset="never", traits={
        "nature": "jolly",
        "ability": "rough-skin",
        "held_item": "cobblemon:life_orb",
        "ivs": {"attack": 31, "hp": 31, "speed": 31,
                "defence": 31, "special_attack": 31, "special_defence": 31},
        "evs": {"attack": 252, "speed": 252, "hp": 4},
    }))
    sections.append({"id": "vault", "title": "Shiny Vault", "entries": vault})

    sections.append({
        "id": "consumables", "title": "Consumables",
        "entries": [item_entry(name, cost, "cobblemon:" + name, count)
                    for name, cost, count in CONSUMABLES],
    })
    sections.append({
        "id": "held", "title": "Held Items",
        "entries": [item_entry(name, cost, "cobblemon:" + name, 1)
                    for name, cost in HELD_ITEMS],
    })

    # Optional-mod items. The shipped defaults must never do this; a test catalogue must.
    stones = []
    for boss in sorted(mega):
        for stone in mega[boss]["stones"]:
            path = stone["item"].split(":")[1]
            # One a day, so the counter reads 1/1 and the rollover is easy to watch.
            stones.append(item_entry(path, 1200, stone["item"], 1, limit=1))
    sections.append({"id": "mega", "title": "Mega Stones", "entries": stones})
    sections.append({
        "id": "tera", "title": "Tera Shards",
        "entries": [item_entry(item.split(":")[1], 400, item, 1, limit=3)
                    for item in sorted(tera)],
    })

    # 80 entries in one section, so the section paginates and its heading numbers itself.
    bulk = []
    for index in range(80):
        name, cost, count = CONSUMABLES[index % len(CONSUMABLES)]
        # Unlimited on purpose: the cell must show no counter at all, which is the only way
        # to tell that a limited cell's counter means something.
        bulk.append(item_entry("bulk_%02d" % index, 5 + index, "cobblemon:" + name,
                               1 + index % 16, limit=0))
    sections.append({"id": "bulk", "title": "Overflow Test", "entries": bulk})

    # Deliberately unreachable prices, so the dim state and the red price are visible at a glance.
    sections.append({
        "id": "unaffordable", "title": "Too Expensive",
        "entries": [item_entry("priced_out_" + name, 999999, "cobblemon:" + name, 1)
                    for name, _ in HELD_ITEMS[:4]],
    })

    # Exactly one entry: its heading must not gain a "(1/1)".
    sections.append({
        "id": "single", "title": "One Of A Kind",
        "entries": [pokemon_entry("the_only_one", 10000, "arceus", 100, shiny=True,
                                  limit=1, reset="never")],
    })

    return {
        "version": 2,
        "per_page": 72,
        # Stated explicitly rather than left to the defaults, so the file documents the rule it is
        # testing: one Pokemon a day, five of an item a day, both resetting at 00:00 UTC.
        "limits": {"pokemon": 1, "item": 5, "reset": "daily"},
        "sections": sections,
    }


def verify(catalog, manifest, species):
    """Nothing ships that cannot resolve, and no id is used twice."""
    problems = []
    seen = set()
    registries = {ns: set(items) for ns, items in manifest["items"].items()}

    for section in catalog["sections"]:
        for entry in section["entries"]:
            if entry["id"] in seen:
                problems.append("duplicate entry id: " + entry["id"])
            seen.add(entry["id"])
            if "item" in entry:
                namespace, _, path = entry["item"].partition(":")
                if namespace == "minecraft":
                    continue
                if namespace not in registries:
                    problems.append(entry["id"] + ": unknown namespace " + namespace)
                elif path not in registries[namespace]:
                    problems.append(entry["id"] + ": " + entry["item"] + " is not in the pack")
            else:
                if entry["species"] not in species:
                    problems.append(entry["id"] + ": unknown species " + entry["species"])
    return problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="fail if the committed file differs from what this would generate")
    args = parser.parse_args()

    manifest = load_manifest()
    species = known_species()
    catalog = build(manifest, species)

    problems = verify(catalog, manifest, species)
    if problems:
        print("test catalogue is not valid:")
        for problem in problems:
            print("  " + problem)
        return 1

    rendered = json.dumps(catalog, indent=2) + "\n"
    if args.check:
        with io.open(OUTPUT, encoding="utf-8") as handle:
            current = handle.read().replace("\r\n", "\n")
        if current != rendered:
            print("test_catalog.json is out of date; re-run without --check")
            return 1
        print("test catalogue: PASS -- matches the generator")
        return 0

    with io.open(OUTPUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(rendered)

    entries = sum(len(section["entries"]) for section in catalog["sections"])
    pages = sum(max(1, -(-len(section["entries"]) // catalog["per_page"]))
                for section in catalog["sections"])
    print("wrote %s" % OUTPUT)
    print("  %d sections, %d entries, %d pages" % (len(catalog["sections"]), entries, pages))
    return 0


if __name__ == "__main__":
    sys.exit(main())
