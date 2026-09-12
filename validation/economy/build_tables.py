#!/usr/bin/env python3
"""Generates the reward-economy loot tables, and the exact probability report for them.

Every table under `compat/addonrewards/.../loot_table/{base,general,specialty}` is emitted from
the matrix below. They are generated rather than hand-written for the reason the manifest is:
sixty-seven files whose weights have to add up cannot be maintained by hand, and a drifting
weight is invisible in a diff. Edit the matrix, re-run, commit what changes.

    python validation/economy/build_tables.py            # emit tables + probability report
    python validation/economy/build_tables.py --check    # emit nothing, fail if the tree differs

`--check` is what runs in CI: it regenerates in memory and compares, so a hand-edited table is
caught in the same breath as a stale one.

These tables are live: every bundled definition is policy-driven and rolls them.

One structural rule, learned from the rig rather than from the documentation. An item from an
optional mod is NEVER named directly in a selection pool, because Minecraft rejects an entire
loot table when any entry names an unregistered id -- with seven provider mods absent, that took
out all four specialty/<tier> tables and all 21 boss tables at once, and every claim silently lost
its specialty selection. Optional items sit behind their own table, so a missing mod costs only
its own rows. validate_economy_probabilities enforces it.

Weights are in units of 0.01 percentage point, so a specialty pool totals 10000. Sourced from the
economy prompt's sections 7 and 8; its own stated fallback weights (7345 / 7920 / 6894 / 6020)
reproduce exactly from this matrix, which is the check that the transcription is faithful.
"""

import argparse
import io
import json
import os
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST_PATH = os.path.join(REPO_ROOT, "validation", "economy", "manifest.json")
TABLE_ROOT = os.path.join(REPO_ROOT, "compat", "addonrewards", "src", "main", "resources",
                          "data", "cobbleraids", "loot_table")
REPORT_PATH = os.path.join(REPO_ROOT, "validation", "economy", "probability_report.md")

TIERS = ["starter", "powerhouse", "legendary", "mythical"]
POOL_TOTAL = 10000

# --- section 7: the curated base catalog -------------------------------------------------------
# item, weight, then the quantity for each tier in TIERS order.
BASE_CATALOG = [
    ("cobblemon:poke_ball", 24, [4, 4, 4, 4]),
    ("cobblemon:great_ball", 20, [2, 3, 3, 4]),
    ("cobblemon:ultra_ball", 6, [1, 1, 2, 2]),
    ("cobblemon:potion", 16, [2, 2, 2, 2]),
    ("cobblemon:super_potion", 10, [1, 2, 2, 2]),
    ("cobblemon:hyper_potion", 2, [1, 1, 1, 1]),
    ("cobblemon:revive", 4, [1, 1, 1, 1]),
    ("cobblemon:full_heal", 4, [1, 1, 1, 1]),
    ("cobblemon:exp_candy_s", 10, [2, 2, 3, 3]),
    ("cobblemon:exp_candy_m", 4, [1, 1, 2, 2]),
]

# --- section 7: general-roll categories, per tier, totalling 100 -------------------------------
GENERAL_CATEGORIES = [
    ("cobbleraids:base/{tier}", [70, 60, 60, 55]),
    ("cobbleraids:general/leaf/cards", [10, 12, 10, 10]),
    ("cobbleraids:general/leaf/capsule_materials", [8, 12, 15, 20]),
    ("cobbleraids:general/leaf/safari_consumables", [7, 10, 10, 10]),
    ("cobbleraids:general/leaf/riding_consumables", [5, 6, 5, 5]),
]

CARD_PACKS = ["booster_pack"] + [
    "booster_pack_" + t for t in ["bug", "dark", "dragon", "electric", "fairy", "fighting", "fire",
                                  "flying", "ghost", "grass", "ground", "ice", "normal", "poison",
                                  "psychic", "rock", "steel", "water"]
] + ["booster_pack_gen%d" % n for n in range(1, 10)]

TYPES = ["normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
         "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"]

# --- section 8: the specialty matrix -----------------------------------------------------------
# (row name, what it grants, weight per tier). A str target is an item id; a dict is a child table.
SPECIALTY_ROWS = [
    # 26.60% plus the stellar row's 0.20% is 26.80%: one shard per four hours of raiding, given
    # starter is 70% of spawns and the only tier that carries them.
    ("standard tera shard", {"table": "cobbleraids:specialty/leaf/tera_standard"}, [2660, 0, 0, 0]),
    ("stellar tera shard", "mega_showdown:stellar_tera_shard", [20, 0, 0, 0]),
    ("copper incubator", "daycareplus:copper_incubator", [150, 100, 0, 0]),
    ("iron incubator", "daycareplus:iron_incubator", [60, 100, 0, 0]),
    ("gold incubator", "daycareplus:gold_incubator", [20, 40, 0, 0]),
    ("diamond incubator", "daycareplus:diamond_incubator", [0, 0, 30, 0]),
    ("netherite incubator", "daycareplus:netherite_incubator", [0, 0, 0, 15]),
    ("daycare spark", "daycareplus:daycare_spark", [0, 0, 100, 150]),
    # Resolved 2026-09-11: the matrix's "Sparkling Booster" does not exist. daycareplus:shiny_booster
    # does, and is the same idea; taken at the Daycare Spark rate rather than inheriting the rate of
    # a row that named a non-existent item.
    ("shiny booster", "daycareplus:shiny_booster", [0, 0, 100, 150]),
    # Resolved 2026-09-11: the matrix filed "Shiny Capsule" under daycare items. It is cobblecapsule's,
    # and is the shiny sibling of the pokemon capsule row below.
    ("shiny capsule", "cobblecapsule:pokemon_capsule_shiny", [0, 0, 10, 20]),
    # The matrix's "Dizygotic Booster" and "Fertility Card" rows name items that do not exist; they are
    # aliases of the two rows below. Their weight is not reassigned to anything -- it falls to base,
    # which is what the prompt's own unavailable-row rule requires.
    ("daycare booster", "daycareplus:daycare_booster", [0, 0, 75, 100]),
    ("fertility candy", "daycareplus:fertility_candy", [0, 0, 50, 75]),
    ("tm / tr", {"table": "cobbleraids:specialty/leaf/tm_tr"}, [200, 300, 400, 500]),
    ("basic capsule", {"table": "cobbleraids:specialty/leaf/capsule_basic"}, [50, 100, 150, 200]),
    ("rare capsule", {"table": "cobbleraids:specialty/leaf/capsule_rare"}, [0, 60, 100, 150]),
    ("epic capsule core", "cobblecapsule:capsule_core_epic", [0, 0, 10, 20]),
    ("pokemon capsule", "cobblecapsule:pokemon_capsule", [0, 0, 30, 50]),
    ("bracelet / journal", {"table": "cobbleraids:specialty/leaf/bonds_pair"}, [20, 40, 60, 80]),
    ("shiny leaf", "companion_bonds:shiny_leaf", [0, 0, 20, 30]),
    ("shiny crown", "companion_bonds:shiny_crown", [0, 0, 5, 10]),
    ("training reader", "ridetraining:training_reader", [10, 20, 30, 40]),
    ("riding upgrade", "ridetraining:riding_upgrade", [5, 10, 20, 30]),
    ("type charm", {"table": "cobbleraids:specialty/leaf/type_charms"}, [0, 50, 150, 200]),
    ("catch / exp charm", {"table": "cobbleraids:specialty/leaf/catch_exp_charms"}, [0, 20, 50, 75]),
    ("multi charm", "cobblemoncharms:multi_charm", [0, 0, 10, 20]),
    ("gold bottle cap", "cobblemoncharms:gold_bottle_cap", [0, 0, 10, 20]),
    ("shiny charm", "cobblemoncharms:shiny_charm", [0, 0, 2, 5]),
    ("auspicious ball", "cobblesafari:auspicious_pokeball", [20, 30, 40, 50]),
    ("union room ball", "cobblesafari:union_room_pokeball", [0, 10, 20, 30]),
    ("redchain random ball", "cobblesafari:redchain_random_ball", [0, 0, 2, 5]),
    ("golden auspicious ball", "cobblesafari:auspiciouspokeball_gold", [0, 0, 2, 5]),
    ("rare candy", "cobblemon:rare_candy", [100, 200, 300, 400]),
    ("large exp candy", "cobblemon:exp_candy_l", [0, 500, 800, 1000]),
]

# 45.25%, and only on a boss that has a stone. Set from an acquisition target rather than a feel:
# at a 45-minute spawn only 0.55 mega-capable raids happen an hour, so a player averaging one stone
# per four hours needs roughly every other capable raid to yield one. The extra weight comes out of
# the base fallback, so no other premium row changes.
MEGA_WEIGHT = 4525

# Namespaces guaranteed present. Everything else is an optional mod, and an item from one can
# never be named directly in a pool -- see optional_item_table().
ALWAYS_PRESENT = ("minecraft", "cobblemon")


# --- loot table construction -------------------------------------------------------------------

def item_entry(item_id, weight=None, count=None):
    entry = {"type": "minecraft:item", "name": item_id}
    if weight is not None:
        entry["weight"] = weight
    if count is not None and count != 1:
        if isinstance(count, tuple):
            value = {"type": "minecraft:uniform", "min": count[0], "max": count[1]}
        else:
            value = count
        entry["functions"] = [{"function": "minecraft:set_count", "count": value}]
    return entry


def table_entry(table_id, weight=None):
    entry = {"type": "minecraft:loot_table", "value": table_id}
    if weight is not None:
        entry["weight"] = weight
    return entry


def tag_entry(tag_id):
    # expand:true makes the tag a uniform choice among its members. Referenced from a parent at a
    # fixed weight through its own table, so tag size cannot turn an 80/20 split into a
    # 630-versus-630 item count race -- which is the failure the prompt warns about.
    return {"type": "minecraft:tag", "name": tag_id, "expand": True}


def single_pool(entries):
    return {"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": entries}]}


def optional_item_table(tables, item_id, count=None):
    """Puts one optional mod's item behind its own table, and returns that table's id.

    This is not tidiness. Minecraft rejects an ENTIRE loot table when any minecraft:item entry
    names an unregistered id -- it does not skip the entry -- so a single missing mod took out all
    four specialty/<tier> tables and all 21 boss tables at once, proven on the live rig: 41 tables
    failed to parse and every claim silently lost its specialty selection. Behind its own table, a
    missing mod costs only its own rows: the parent still parses, and picking a dead child yields
    nothing for that one selection, which RaidLootRoller reports by name.

    That is still not the "weight falls back to base" the source matrix assumed. Data cannot
    express "use this item if its mod is installed", so the weight is lost rather than reassigned.
    Losing one row beats losing the tier.
    """
    namespace, path = item_id.split(":", 1)
    relative = "specialty/opt/%s/%s.json" % (namespace, path)
    tables[relative] = single_pool([item_entry(item_id, None, count)])
    return "cobbleraids:" + relative[:-len(".json")]


def build_tables(manifest):
    """Every generated table, keyed by path relative to the loot_table root."""
    tables = {}

    # base/<tier> -- the curated catalog, same weights everywhere, quantities by tier
    for index, tier in enumerate(TIERS):
        entries = [item_entry(item, weight, quantities[index])
                   for item, weight, quantities in BASE_CATALOG]
        tables["base/%s.json" % tier] = single_pool(entries)

    # general leaves -- one result each
    tables["general/leaf/cards.json"] = single_pool(
        [item_entry("cobblemon-cards:" + pack, 12 if pack == "booster_pack" else (4 if not pack.startswith("booster_pack_gen") else 3))
         for pack in CARD_PACKS])
    tables["general/leaf/capsule_materials.json"] = single_pool([
        item_entry("cobblecapsule:corelite_shard", 3, (1, 2)),
        item_entry("cobblecapsule:corelite_ingot", 1, 1),
    ])
    tables["general/leaf/safari_consumables.json"] = single_pool([
        item_entry("cobblesafari:bait", 7, (1, 2)),
        item_entry("cobblesafari:balm", 5, 1),
    ])
    tables["general/leaf/riding_consumables.json"] = single_pool([
        item_entry("ridetraining:stamina_berry", 1, (1, 2)),
    ])

    # general/<tier> -- category weights over those leaves
    for index, tier in enumerate(TIERS):
        entries = [table_entry(target.format(tier=tier), weights[index])
                   for target, weights in GENERAL_CATEGORIES if weights[index] > 0]
        tables["general/%s.json" % tier] = single_pool(entries)

    # specialty leaves -- grouped rows, one result each
    tables["specialty/leaf/tera_standard.json"] = single_pool(
        [item_entry("mega_showdown:%s_tera_shard" % t, 1) for t in TYPES])
    tables["specialty/leaf/tr.json"] = single_pool([tag_entry("simpletms:tr_items")])
    tables["specialty/leaf/tm.json"] = single_pool([tag_entry("simpletms:tm_items")])
    tables["specialty/leaf/tm_tr.json"] = single_pool([
        table_entry("cobbleraids:specialty/leaf/tr", 80),
        table_entry("cobbleraids:specialty/leaf/tm", 20),
    ])
    tables["specialty/leaf/capsule_basic.json"] = single_pool([
        item_entry("cobblecapsule:capsule_core_basic", 4),
        item_entry("cobblecapsule:healing_capsule_basic", 4),
        item_entry("cobblecapsule:resource_capsule_basic", 3),
        item_entry("cobblecapsule:combat_capsule_basic", 3),
    ])
    tables["specialty/leaf/capsule_rare.json"] = single_pool([
        item_entry("cobblecapsule:capsule_core_rare", 4),
        item_entry("cobblecapsule:combat_capsule_rare", 3),
    ])
    tables["specialty/leaf/bonds_pair.json"] = single_pool([
        item_entry("companion_bonds:friendship_bracelet", 5),
        item_entry("companion_bonds:contest_journal", 4),
    ])
    tables["specialty/leaf/type_charms.json"] = single_pool(
        [item_entry("cobblemoncharms:%s_charm" % t, 1) for t in TYPES])
    tables["specialty/leaf/catch_exp_charms.json"] = single_pool([
        item_entry("cobblemoncharms:catch_charm", 1),
        item_entry("cobblemoncharms:exp_charm", 1),
    ])

    # specialty/mega/<species> -- the stones one boss can drop, split evenly inside the mega row
    for boss, entry in sorted(manifest["mega"].items()):
        tables["specialty/mega/%s.json" % boss] = single_pool(
            [item_entry(stone["item"], 1) for stone in entry["stones"]])

    # specialty/<tier> and specialty/boss/<boss>
    for index, tier in enumerate(TIERS):
        tables["specialty/%s.json" % tier] = single_pool(specialty_entries(tables, index, tier, None))
    for boss, entry in sorted(manifest["mega"].items()):
        tier_index = TIERS.index(entry["tier"])
        tables["specialty/boss/%s.json" % boss] = single_pool(
            specialty_entries(tables, tier_index, entry["tier"], boss))
    return tables


def specialty_entries(tables, index, tier, boss):
    """One specialty pool: the premium rows, then whatever is left over falls to base."""
    entries = []
    spent = 0
    if boss is not None:
        entries.append(table_entry("cobbleraids:specialty/mega/" + boss, MEGA_WEIGHT))
        spent += MEGA_WEIGHT
    for _name, target, weights in SPECIALTY_ROWS:
        weight = weights[index]
        if weight <= 0:
            continue
        spent += weight
        if isinstance(target, dict):
            entries.append(table_entry(target["table"], weight))
        elif target.split(":", 1)[0] in ALWAYS_PRESENT:
            entries.append(item_entry(target, weight, 1))
        else:
            entries.append(table_entry(optional_item_table(tables, target), weight))
    fallback = POOL_TOTAL - spent
    if fallback <= 0:
        raise SystemExit("specialty pool for %s overflows 10000 (spent %d)" % (tier, spent))
    # The fallback is the tier's base table, never another specialty roll, so a bundle can never
    # contain two premium items from one selection.
    entries.append(table_entry("cobbleraids:base/" + tier, fallback))
    return entries


# --- probability report ------------------------------------------------------------------------

def specialty_breakdown(index, boss_matched):
    rows = []
    spent = MEGA_WEIGHT if boss_matched else 0
    if boss_matched:
        rows.append(("mega stone", MEGA_WEIGHT))
    for name, _target, weights in SPECIALTY_ROWS:
        if weights[index] > 0:
            rows.append((name, weights[index]))
            spent += weights[index]
    rows.append(("base fallback", POOL_TOTAL - spent))
    return rows


def write_report(manifest):
    lines = []
    lines.append("# Reward-economy probability report")
    lines.append("")
    lines.append("Generated by `validation/economy/build_tables.py`. Every figure is exact --"
                 " computed from the table weights, not sampled.")
    lines.append("")
    lines.append("Each claim is **2 general selections + 1 specialty selection**, plus B further"
                 " general selections earned by contribution (B = 0..3), so 3 to 6 in total. The"
                 " specialty selection happens exactly once whatever B is, which is what keeps"
                 " premium drop rates independent of how hard a player fought.")
    lines.append("")

    lines.append("## Specialty pool, by tier")
    lines.append("")
    lines.append("Weights are hundredths of a percent; each pool totals 10000. The *matched*"
                 " column is a boss with a Mega Stone, where 500 weight is the stone and comes"
                 " out of the fallback.")
    lines.append("")
    header = "| Row | " + " | ".join("%s matched | %s unmatched" % (t, t) for t in TIERS) + " |"
    lines.append(header)
    lines.append("|---|" + "---:|" * (len(TIERS) * 2))
    names = ["mega stone"] + [name for name, _t, _w in SPECIALTY_ROWS] + ["base fallback"]
    seen = []
    for name in names:
        if name in seen:
            continue
        seen.append(name)
        cells = []
        for index in range(len(TIERS)):
            for matched in (True, False):
                weight = dict(specialty_breakdown(index, matched)).get(name, 0)
                cells.append("%.2f%%" % (weight / 100.0) if weight else "--")
        lines.append("| " + name + " | " + " | ".join(cells) + " |")
    lines.append("")

    lines.append("## Mega Stones")
    lines.append("")
    lines.append("The 5.00%% row splits evenly between a boss's stones, so a single-stone boss is"
                 " 5.00%% and an X/Y boss is 2.50%% each. %d of 130 bosses have one."
                 % len(manifest["mega"]))
    lines.append("")
    lines.append("| Boss | Tier | Stones | Each |")
    lines.append("|---|---|---|---:|")
    for boss, entry in sorted(manifest["mega"].items()):
        stones = ", ".join(s["item"].split(":")[1] for s in entry["stones"])
        lines.append("| %s | %s | %s | %.2f%% |"
                     % (boss, entry["tier"], stones, MEGA_WEIGHT / 100.0 / len(entry["stones"])))
    lines.append("")

    lines.append("## General rolls")
    lines.append("")
    lines.append("Category weights total 100 per tier and apply to every general selection,"
                 " standard or contribution-earned.")
    lines.append("")
    lines.append("| Category | " + " | ".join(TIERS) + " |")
    lines.append("|---|" + "---:|" * len(TIERS))
    for target, weights in GENERAL_CATEGORIES:
        label = target.replace("cobbleraids:", "").replace("/{tier}", "/<tier>")
        lines.append("| " + label + " | " + " | ".join("%d%%" % w for w in weights) + " |")
    lines.append("")
    lines.append("A category at weight *w* appears at least once in a bundle with probability"
                 " `1 - (1 - w/100)^(2+B)`. For the 10%% card category: 19.00%% at B=0, 27.10%% at"
                 " B=1, 34.39%% at B=2, 40.95%% at B=3.")
    lines.append("")

    lines.append("## Base catalog")
    lines.append("")
    lines.append("The fallback of both the general and specialty pools. Weights total 100 and are"
                 " identical across tiers; only the quantities differ.")
    lines.append("")
    lines.append("| Item | Weight | " + " | ".join(TIERS) + " |")
    lines.append("|---|---:|" + "---:|" * len(TIERS))
    for item, weight, quantities in BASE_CATALOG:
        lines.append("| %s | %d | %s |" % (item, weight, " | ".join(str(q) for q in quantities)))
    lines.append("")
    lines.append("## Resolved against the pack")
    lines.append("")
    lines.append("Three rows of the source matrix named items that do not exist in pack `%s`:"
                 % manifest["pack"]["source"])
    lines.append("")
    lines.append("- **Dizygotic Booster** and **Fertility Card** are aliases of `daycare_booster`"
                 " and `fertility_candy`, which have their own rows. The duplicate weight falls to"
                 " base rather than being reassigned.")
    lines.append("- **Sparkling Booster** does not exist; `daycareplus:shiny_booster` does, taken"
                 " at the Daycare Spark rate.")
    lines.append("- **Shiny Capsule** is `cobblecapsule:pokemon_capsule_shiny`, not a daycare item.")
    return "\n".join(lines) + "\n"


# --- emit / check --------------------------------------------------------------------------------

def serialise(table):
    return json.dumps(table, indent=2, sort_keys=False, ensure_ascii=False) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="compare against the tree instead of writing; non-zero if they differ")
    args = parser.parse_args()

    with io.open(MANIFEST_PATH, encoding="utf-8") as handle:
        manifest = json.load(handle)

    tables = build_tables(manifest)
    report = write_report(manifest)

    if args.check:
        differences = []
        for relative, table in sorted(tables.items()):
            path = os.path.join(TABLE_ROOT, relative.replace("/", os.sep))
            if not os.path.exists(path):
                differences.append(relative + " is missing")
                continue
            with io.open(path, encoding="utf-8") as handle:
                if handle.read().replace("\r\n", "\n") != serialise(table):
                    differences.append(relative + " differs from what the matrix generates")
        if os.path.exists(REPORT_PATH):
            with io.open(REPORT_PATH, encoding="utf-8") as handle:
                if handle.read().replace("\r\n", "\n") != report:
                    differences.append("probability_report.md differs")
        else:
            differences.append("probability_report.md is missing")

        if differences:
            print("Economy table validation: FAIL", file=sys.stderr)
            for difference in differences:
                print("  - " + difference, file=sys.stderr)
            print("  Run: python validation/economy/build_tables.py", file=sys.stderr)
            return 1
        print("Economy table validation: PASS -- %d generated tables match the matrix" % len(tables))
        return 0

    for relative, table in sorted(tables.items()):
        path = os.path.join(TABLE_ROOT, relative.replace("/", os.sep))
        directory = os.path.dirname(path)
        if not os.path.isdir(directory):
            os.makedirs(directory)
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(serialise(table))
    with io.open(REPORT_PATH, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(report)

    print("build_tables: wrote %d loot tables and the probability report" % len(tables))
    for index, tier in enumerate(TIERS):
        matched = dict(specialty_breakdown(index, True))["base fallback"]
        unmatched = dict(specialty_breakdown(index, False))["base fallback"]
        print("  %-11s fallback %d matched / %d unmatched" % (tier, matched, unmatched))
    return 0


if __name__ == "__main__":
    sys.exit(main())
