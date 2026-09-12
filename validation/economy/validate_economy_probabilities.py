#!/usr/bin/env python3
"""Computes exact drop probabilities from the emitted loot tables, and checks them.

The probability report is generated from the matrix. This is generated from the *tables*, by
walking the graph the game will actually roll, and then the two are compared. A generator bug that
writes a weight the matrix never asked for is invisible to every other check here -- build_tables
--check only proves the files match what the generator produces, not that what it produces is
what the matrix says.

Exact, not sampled: every probability is a rational number computed by walking the graph, so a
0.02% row is as precisely known as a 70% one. A million-bundle simulation would take far longer to
say less.

Run: python validation/economy/validate_economy_probabilities.py
"""

import io
import json
import os
import sys
from fractions import Fraction

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TABLE_ROOT = os.path.join(REPO_ROOT, "compat", "addonrewards", "src", "main", "resources",
                          "data", "cobbleraids", "loot_table")
MANIFEST_PATH = os.path.join(REPO_ROOT, "validation", "economy", "manifest.json")

TIERS = ["starter", "powerhouse", "legendary", "mythical"]

# The rates the matrix is supposed to produce, in hundredths of a percent. Stated here rather than
# read from the generator on purpose: this file exists to catch the generator disagreeing with what
# the economy was designed to do, and a check that asks the thing it is checking cannot do that.
MEGA_RATE = 1000
TERA_RATE = 1190
TOLERANCE = Fraction(1, 1000000)

failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def load_table(table_id):
    """A cobbleraids: table id as parsed JSON, or None when no such file ships."""
    if not table_id.startswith("cobbleraids:"):
        return None
    path = os.path.join(TABLE_ROOT, *table_id.split(":", 1)[1].split("/")) + ".json"
    if not os.path.exists(path):
        return None
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def distribution(table_id, tags):
    """Exact probability of each outcome from rolling one table.

    Outcomes are item ids, or "tag:<id>" for a tag pick, which is treated as one outcome because
    which member of a 630-item tag comes out does not change any invariant here.
    """
    table = load_table(table_id)
    if table is None:
        return {"missing:" + table_id: Fraction(1)}

    result = {}
    for pool in table.get("pools", []):
        entries = pool.get("entries", [])
        total = sum(entry.get("weight", 1) for entry in entries)
        if total == 0:
            continue
        for entry in entries:
            share = Fraction(entry.get("weight", 1), total)
            kind = entry.get("type")
            if kind == "minecraft:item":
                result[entry["name"]] = result.get(entry["name"], Fraction(0)) + share
            elif kind == "minecraft:tag":
                key = "tag:" + entry["name"]
                result[key] = result.get(key, Fraction(0)) + share
            elif kind == "minecraft:loot_table":
                for outcome, probability in distribution(entry["value"], tags).items():
                    result[outcome] = result.get(outcome, Fraction(0)) + share * probability
            else:
                failures.append("unhandled entry type " + str(kind) + " in " + table_id)
    return result


def total(distribution_map):
    return sum(distribution_map.values(), Fraction(0))


def main():
    with io.open(MANIFEST_PATH, encoding="utf-8") as handle:
        manifest = json.load(handle)
    tags = manifest.get("item_tags", {})

    # --- every pool is a complete probability distribution ---------------------------------------
    for tier in TIERS:
        for family in ("base", "general", "specialty"):
            table_id = "cobbleraids:%s/%s" % (family, tier)
            outcomes = distribution(table_id, tags)
            check(abs(total(outcomes) - 1) < TOLERANCE,
                  table_id + " probabilities sum to " + str(float(total(outcomes))) + ", not 1")
            missing = [key for key in outcomes if key.startswith("missing:")]
            check(not missing, table_id + " reaches tables that do not exist: " + ", ".join(missing))

    # --- the specialty slot is the only route to a premium item ----------------------------------
    # Contribution adds general selections and nothing else, so if a general roll could reach a
    # specialty leaf, contributing more would raise a player's Mega Stone odds. It must not.
    premium_markers = ("mega_showdown:", "cobblemoncharms:", "companion_bonds:", "daycareplus:",
                       "cobblecapsule:", "ridetraining:training_reader", "ridetraining:riding_upgrade",
                       "tag:simpletms:")
    for tier in TIERS:
        general = distribution("cobbleraids:general/%s" % tier, tags)
        leaked = sorted(key for key in general
                        if any(key.startswith(marker) for marker in premium_markers)
                        and not key.startswith("cobblecapsule:corelite"))
        check(not leaked,
              "the general roll for " + tier + " can reach premium items, so contribution would"
              " raise their odds: " + ", ".join(leaked))

    # --- mega stones ------------------------------------------------------------------------------
    for boss, entry in sorted(manifest.get("mega", {}).items()):
        table_id = "cobbleraids:specialty/boss/" + boss
        outcomes = distribution(table_id, tags)
        stones = [stone["item"] for stone in entry["stones"]]
        aggregate = sum((outcomes.get(stone, Fraction(0)) for stone in stones), Fraction(0))
        check(aggregate == Fraction(MEGA_RATE, 10000),
              boss + " drops a mega stone at " + str(float(aggregate) * 100) + "%, not "
              + str(MEGA_RATE / 100.0) + "%")
        for stone in stones:
            expected = Fraction(MEGA_RATE, 10000) / len(stones)
            check(outcomes.get(stone, Fraction(0)) == expected,
                  boss + " drops " + stone + " at " + str(float(outcomes.get(stone, 0)) * 100)
                  + "%, not " + str(float(expected) * 100) + "%")

    # A tier's own specialty table must never drop a stone: those 109 bosses have none.
    for tier in TIERS:
        outcomes = distribution("cobbleraids:specialty/%s" % tier, tags)
        stones = sorted(key for key in outcomes
                        if key.startswith("mega_showdown:") and "tera_shard" not in key)
        check(not stones, "the " + tier + " specialty table drops mega stones: " + ", ".join(stones))

    # --- rows the matrix names explicitly ----------------------------------------------------------
    starter = distribution("cobbleraids:specialty/starter", tags)
    tera = sum((probability for key, probability in starter.items() if key.endswith("_tera_shard")
                and not key.startswith("mega_showdown:stellar")), Fraction(0))
    check(tera == Fraction(TERA_RATE, 10000),
          "standard tera shards total " + str(float(tera) * 100) + "% at starter, not "
          + str(TERA_RATE / 100.0) + "%")
    check(starter.get("mega_showdown:stellar_tera_shard") == Fraction(20, 10000),
          "the stellar tera shard is not at its own 0.20%")

    # The 18 ordinary types must be equally likely; a heavier one would be invisible in the report.
    shard_rates = {key: value for key, value in starter.items()
                   if key.endswith("_tera_shard") and "stellar" not in key}
    check(len(shard_rates) == 18, "expected 18 ordinary tera shards, found " + str(len(shard_rates)))
    check(len(set(shard_rates.values())) <= 1, "the ordinary tera shards are not equally weighted")

    # --- the TM/TR split survives its tag sizes -----------------------------------------------------
    # The trap: referencing an expand:true tag entry straight from the parent weights it by tag
    # size. Both tags hold 630 items, so that mistake would produce a 50/50 split that looks
    # entirely correct while being correct by accident.
    for tier, expected in zip(TIERS, [Fraction(200, 10000), Fraction(300, 10000),
                                      Fraction(400, 10000), Fraction(500, 10000)]):
        outcomes = distribution("cobbleraids:specialty/%s" % tier, tags)
        tr = outcomes.get("tag:simpletms:tr_items", Fraction(0))
        tm = outcomes.get("tag:simpletms:tm_items", Fraction(0))
        check(tr + tm == expected,
              tier + " TM/TR total is " + str(float(tr + tm) * 100) + "%, not "
              + str(float(expected) * 100) + "%")
        if tr + tm > 0:
            check(tr == (tr + tm) * Fraction(80, 100) and tm == (tr + tm) * Fraction(20, 100),
                  tier + " TM/TR split is " + str(float(tr / (tr + tm)) * 100) + "/"
                  + str(float(tm / (tr + tm)) * 100) + ", not 80/20 -- tag size is deciding it")

    # --- no table may name an optional mod's item directly ------------------------------------------
    # Minecraft rejects an entire loot table when any minecraft:item entry names an unregistered
    # id. Proven on the rig: with seven provider mods absent, 41 tables failed to parse, including
    # all four specialty/<tier> and all 21 boss tables, so every claim silently lost its specialty
    # selection. Behind its own table, a missing mod costs only its own rows.
    always_present = ("minecraft", "cobblemon")
    for directory, _unused, filenames in os.walk(TABLE_ROOT):
        for filename in sorted(filenames):
            if not filename.endswith(".json"):
                continue
            path = os.path.join(directory, filename)
            relative = os.path.relpath(path, TABLE_ROOT).replace(os.sep, "/")
            with io.open(path, encoding="utf-8") as handle:
                table = json.load(handle)
            named = set()
            for pool in table.get("pools", []):
                for entry in pool.get("entries", []):
                    if entry.get("type") == "minecraft:item":
                        named.add(entry["name"])
            optional = sorted({item for item in named
                               if item.split(":")[0] not in always_present})
            if not optional:
                continue
            # A table may name optional items only if every one of them is from the same mod: then
            # the table lives or dies with that mod and nothing else goes with it.
            namespaces = {item.split(":")[0] for item in optional}
            check(len(namespaces) == 1 and not (named - set(optional)),
                  relative + " mixes " + ", ".join(sorted(namespaces))
                  + " with other providers or with guaranteed items, so one missing mod would take"
                  " the whole table down: " + ", ".join(optional[:4]))

    # --- nothing banned is reachable from anywhere ---------------------------------------------------
    banned = {namespace + ":" + path
              for namespace, paths in manifest.get("banned", {}).items() for path in paths}
    for tier in TIERS:
        for family in ("base", "general", "specialty"):
            outcomes = distribution("cobbleraids:%s/%s" % (family, tier), tags)
            reachable = sorted(set(outcomes) & banned)
            check(not reachable,
                  "cobbleraids:%s/%s can drop banned items: %s" % (family, tier, ", ".join(reachable)))
        for name in ("master_ball",):
            outcomes = distribution("cobbleraids:specialty/%s" % tier, tags)
            check("cobblemon:" + name not in outcomes,
                  "the " + tier + " specialty table can drop " + name)

    if failures:
        print("Economy probability validation: FAIL", file=sys.stderr)
        for failure in failures:
            print("  - " + failure, file=sys.stderr)
        return 1

    checked = len(TIERS) * 3 + len(manifest.get("mega", {}))
    print("Economy probability validation: PASS -- %d tables walked exactly; mega %.2f%% aggregate"
          " over %d bosses, tera %.2f%%, TM/TR 80/20, no premium item reachable from a general roll"
          % (checked, MEGA_RATE / 100.0, len(manifest.get("mega", {})), TERA_RATE / 100.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
