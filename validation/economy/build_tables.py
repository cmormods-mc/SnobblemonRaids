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

# --- the curated base catalog ------------------------------------------------------------------
# item, weight per tier, then the quantity per tier, both in TIERS order.
#
# Rebuilt 2026-09-12. It used to be ten rows of Poke Balls, Great Balls and Potions at one weight
# shared by every tier, and because base is the fallback of BOTH pools that made 44-55%% of every
# reward a player saw a basic ball or a potion. The categories below are what a raid should pay
# out: balls worth using, EV items, a thin slice of upgraded medicine, and held items that change
# how a Pokemon fights.
#
# Weights are per tier now, not shared, which is what lets a row exist at one tier and not another
# -- Beast and Dream Balls from legendary up, the Master Ball at mythical alone. Each tier totals
# BASE_POOL_TOTAL; build_base_pool asserts it rather than trusting the arithmetic below.
#
# The scale is 1000 rather than 100 so a row can be rarer than one part in a hundred. The Master
# Ball needs that: at weight 2 it is 0.31%% of a mythical bundle, about one per 320 mythical raids.
BASE_POOL_TOTAL = 1000

# Balls: utility over quantity. No Poke Ball, no Great Ball -- those are what made the old pool
# feel like a consolation prize, and the shop sells them for anyone who wants a bulk supply.
BASE_BALLS = [
    ("cobblemon:ultra_ball",   [80, 60, 50, 40], [2, 3, 3, 4]),
    ("cobblemon:dusk_ball",    [50, 50, 40, 30], [1, 2, 2, 3]),
    ("cobblemon:quick_ball",   [50, 50, 40, 30], [1, 2, 2, 3]),
    ("cobblemon:timer_ball",   [40, 40, 30, 20], [1, 2, 2, 3]),
    ("cobblemon:net_ball",     [30, 30, 20, 20], [1, 1, 2, 2]),
    ("cobblemon:repeat_ball",  [30, 30, 20, 20], [1, 1, 2, 2]),
    ("cobblemon:nest_ball",    [30, 10, 10, 10], [1, 1, 2, 2]),
    ("cobblemon:heal_ball",    [30, 20, 20, 10], [1, 2, 2, 2]),
    ("cobblemon:dive_ball",    [20, 20, 20, 10], [1, 1, 2, 2]),
    ("cobblemon:luxury_ball",  [20, 20, 10, 10], [1, 1, 1, 2]),
    ("cobblemon:level_ball",   [10, 10, 10, 10], [1, 1, 1, 2]),
    ("cobblemon:moon_ball",    [10, 10, 10, 10], [1, 1, 1, 2]),
    ("cobblemon:beast_ball",   [0,  0,  12, 20], [1, 1, 1, 1]),
    ("cobblemon:dream_ball",   [0,  0,  8,  18], [1, 1, 1, 1]),
    ("cobblemon:master_ball",  [0,  0,  0,  2],  [1, 1, 1, 1]),
]

# Stat items: the six EV vitamins and the six Power training items. Vitamins are consumed, so they
# are the common half and scale in quantity; a Power item is permanent gear and always lands as one.
BASE_VITAMINS = ["protein", "iron", "calcium", "zinc", "carbos", "hp_up"]
BASE_POWER_ITEMS = ["power_weight", "power_bracer", "power_belt", "power_lens", "power_band",
                    "power_anklet"]
VITAMIN_WEIGHTS = [30, 32, 34, 36]
POWER_WEIGHTS = [10, 14, 16, 18]
VITAMIN_QUANTITIES = [1, 2, 2, 3]

# Medicine: a thin slice, and only the grades worth receiving. Ether and Max Elixir are here
# because PP carries between raids (RaidBattleStateCarryover), so restoring it is a real need and
# nothing else in the reward economy covers it.
BASE_MEDICINE = [
    ("cobblemon:revive",       [40, 32, 24, 16], [1, 1, 2, 2]),
    ("cobblemon:max_potion",   [40, 32, 24, 18], [1, 2, 2, 2]),
    ("cobblemon:ether",        [30, 24, 18, 14], [1, 2, 2, 2]),
    ("cobblemon:max_revive",   [20, 20, 20, 18], [1, 1, 1, 2]),
    ("cobblemon:full_restore", [16, 16, 16, 16], [1, 1, 2, 2]),
    ("cobblemon:max_elixir",   [10, 10, 10, 10], [1, 1, 1, 2]),
]

# Held items: twelve, deliberately the second tier rather than the marquee one. Each is one item
# at one weight, equal within the tier, so the category reads as "a held item" rather than as a
# lottery with an obvious jackpot.
BASE_HELD_ITEMS = ["eviolite", "expert_belt", "weakness_policy", "heavy_duty_boots", "loaded_dice",
                   "covert_cloak", "air_balloon", "scope_lens", "razor_claw", "muscle_band",
                   "wise_glasses", "light_clay"]
HELD_ITEM_WEIGHTS = [17, 20, 24, 27]


def base_catalog():
    """Every base row as (item, weights per tier, quantities per tier)."""
    rows = list(BASE_BALLS)
    rows += [("cobblemon:" + name, list(VITAMIN_WEIGHTS), list(VITAMIN_QUANTITIES))
             for name in BASE_VITAMINS]
    rows += [("cobblemon:" + name, list(POWER_WEIGHTS), [1, 1, 1, 1])
             for name in BASE_POWER_ITEMS]
    rows += list(BASE_MEDICINE)
    rows += [("cobblemon:" + name, list(HELD_ITEM_WEIGHTS), [1, 1, 1, 1])
             for name in BASE_HELD_ITEMS]
    return rows


# --- section 7: general-roll categories, per tier, totalling 100 -------------------------------
# Two categories, because the other three were the filler this whole rebuild is about. The capsule
# materials leaf was corelite shard and ingot; safari was bait and balm; riding was a stamina berry
# and nothing else. A leaf of one or two entries cannot help repeating itself, and between them
# they were 40-54%% of every general selection -- more of a bundle than the base catalog.
#
# What is left is the only two pools with real spread: 45 curated items, and 28 card packs. Base's
# share of a bundle therefore climbs back to 52-64%%, undoing part of the earlier cut, and that is
# the right trade now: the cut existed because base was 76%% balls and potions, which it no longer
# is. A large share of a good pool is not the problem a large share of a bad one was.
GENERAL_CATEGORIES = [
    ("cobbleraids:base/{tier}", [58, 53, 51, 47]),
    ("cobbleraids:general/leaf/cards", [42, 47, 49, 53]),
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
    # 11.90% plus the stellar row's 0.20%: one shard per four hours, given starter is 70% of a
    # 20-minute spawn and the only tier that carries them. It was 26.60% at a 45-minute spawn --
    # the rate did not change, the supply did.
    ("standard tera shard", {"table": "cobbleraids:specialty/leaf/tera_standard"}, [1190, 0, 0, 0]),
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

# 10.00%, and only on a boss that has a stone. Solved from three dials that multiply: a 20-minute
# spawn gives 3 raids an hour, a 3x spawn bias toward the 12 starters that carry a stone makes 1.80
# of those mega-capable, and 10% of those is one stone every four hours. Low because the pity
# counter puts a floor under it -- 10% guaranteed by the twelfth capable raid averages the same as
# 15% with no floor, over a far tighter distribution. See RaidMegaPity.
MEGA_WEIGHT = 1000

# Namespaces guaranteed present. Everything else is an optional mod, and an item from one can
# never be named directly in a pool -- see optional_item_table(). "cobbleraids" is here because the
# key fragments are registered by this mod: if they are missing the mod is missing, and the loot
# tables are not being read at all.
ALWAYS_PRESENT = ("minecraft", "cobblemon", "cobbleraids")


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


def build_base_pool(index, tier):
    """The base entries for one tier, with the rows that tier does not carry left out."""
    entries = []
    total = 0
    for item, weights, quantities in base_catalog():
        weight = weights[index]
        if weight <= 0:
            continue
        total += weight
        entries.append(item_entry(item, weight, quantities[index]))
    if total != BASE_POOL_TOTAL:
        raise SystemExit("base pool for %s totals %d, not %d -- the category budgets no longer add up"
                         % (tier, total, BASE_POOL_TOTAL))
    return entries


def build_tables(manifest):
    """Every generated table, keyed by path relative to the loot_table root."""
    tables = {}

    # base/<tier> -- the curated catalog, weights and quantities both by tier
    for index, tier in enumerate(TIERS):
        tables["base/%s.json" % tier] = single_pool(build_base_pool(index, tier))

    # keys/<tier> -- one outcome, so a selection pointed at it is a guaranteed fragment. The
    # resolver decides how many selections to point here; this table only says what one is worth.
    for tier in TIERS:
        tables["keys/%s.json" % tier] = single_pool(
            [item_entry("cobbleraids:%s_raid_key_fragment" % tier)])

    # general leaves -- one result each
    tables["general/leaf/cards.json"] = single_pool(
        [item_entry("cobblemon-cards:" + pack, 12 if pack == "booster_pack" else (4 if not pack.startswith("booster_pack_gen") else 3))
         for pack in CARD_PACKS])
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
                 " `1 - (1 - w/100)^(2+B)`. For the 42% starter card category: 66.36% at B=0,"
                 " 80.49% at B=1, 88.68% at B=2, 93.43% at B=3.")
    lines.append("")

    lines.append("## Base catalog")
    lines.append("")
    lines.append("The fallback of both the general and specialty pools. Each tier's weights total"
                 " %d; a row at weight 0 is not in that tier's pool at all. Cells read"
                 " *weight (quantity)*." % BASE_POOL_TOTAL)
    lines.append("")
    lines.append("| Item | " + " | ".join(TIERS) + " |")
    lines.append("|---|" + "---:|" * len(TIERS))
    for item, weights, quantities in base_catalog():
        cells = ["%d (x%d)" % (w, q) if w else "--" for w, q in zip(weights, quantities)]
        lines.append("| %s | %s |" % (item, " | ".join(cells)))
    lines.append("")
    lines.append("### Category budgets")
    lines.append("")
    lines.append("| Category | " + " | ".join(TIERS) + " |")
    lines.append("|---|" + "---:|" * len(TIERS))
    groups = [
        ("balls", BASE_BALLS),
        ("stat items (vitamins + power)",
         [(n, VITAMIN_WEIGHTS, None) for n in BASE_VITAMINS]
         + [(n, POWER_WEIGHTS, None) for n in BASE_POWER_ITEMS]),
        ("medicine", BASE_MEDICINE),
        ("held items", [(n, HELD_ITEM_WEIGHTS, None) for n in BASE_HELD_ITEMS]),
    ]
    for label, rows in groups:
        totals = [sum(r[1][i] for r in rows) for i in range(len(TIERS))]
        lines.append("| %s | %s |" % (label, " | ".join(
            "%d (%.1f%%)" % (t, t * 100.0 / BASE_POOL_TOTAL) for t in totals)))
    lines.append("")
    lines.append("### What a bundle actually contains")
    lines.append("")
    lines.append("Base is reached from a general selection at its category weight and from the"
                 " specialty selection through the fallback, so its share of a bundle is higher"
                 " than either figure alone. At B=1 (three general selections plus one specialty),"
                 " on a boss with no Mega Stone:")
    lines.append("")
    lines.append("| Tier | base per bundle | share | Master Ball per bundle |")
    lines.append("|---|---:|---:|---:|")
    general_base = dict(GENERAL_CATEGORIES)["cobbleraids:base/{tier}"]
    for index, tier in enumerate(TIERS):
        fallback = dict(specialty_breakdown(index, False))["base fallback"] / float(POOL_TOTAL)
        selections = 3
        base_hits = selections * general_base[index] / 100.0 + fallback
        master = next((w[index] for item, w, _q in BASE_BALLS
                       if item == "cobblemon:master_ball"), 0) / float(BASE_POOL_TOTAL)
        rate = base_hits * master
        lines.append("| %s | %.2f of %d | %.0f%% | %s |"
                     % (tier, base_hits, selections + 1,
                        base_hits / (selections + 1) * 100.0,
                        ("%.3f%% (1 in %d)" % (rate * 100.0, round(1 / rate))) if rate else "--"))
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


def on_disk():
    """Every table file under TABLE_ROOT, keyed the same way build_tables() keys its output."""
    found = set()
    for directory, _subdirs, files in os.walk(TABLE_ROOT):
        for name in files:
            if not name.endswith(".json"):
                continue
            relative = os.path.relpath(os.path.join(directory, name), TABLE_ROOT)
            found.add(relative.replace(os.sep, "/"))
    return found


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
        # A table the matrix no longer generates is still loaded by the game, so a row deleted
        # from the matrix keeps paying out until its file goes too. Comparing only the tables we
        # generate cannot see that -- it is invisible in exactly the way a stale generated file
        # always is, which this repo has already paid for once.
        for orphan in sorted(on_disk() - set(tables)):
            differences.append(orphan + " is not generated by the matrix any more; delete it")

        if differences:
            print("Economy table validation: FAIL", file=sys.stderr)
            for difference in differences:
                print("  - " + difference, file=sys.stderr)
            print("  Run: python validation/economy/build_tables.py", file=sys.stderr)
            return 1
        print("Economy table validation: PASS -- %d generated tables match the matrix,"
              " and nothing else is in the tree" % len(tables))
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

    for orphan in sorted(on_disk() - set(tables)):
        os.remove(os.path.join(TABLE_ROOT, orphan.replace("/", os.sep)))
        print("build_tables: removed " + orphan + ", which the matrix no longer generates")

    print("build_tables: wrote %d loot tables and the probability report" % len(tables))
    for index, tier in enumerate(TIERS):
        matched = dict(specialty_breakdown(index, True))["base fallback"]
        unmatched = dict(specialty_breakdown(index, False))["base fallback"]
        print("  %-11s fallback %d matched / %d unmatched" % (tier, matched, unmatched))
    return 0


if __name__ == "__main__":
    sys.exit(main())
