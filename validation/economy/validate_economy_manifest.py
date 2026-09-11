#!/usr/bin/env python3
"""Checks the committed reward-economy manifest against the tree, without needing the modpack.

`build_manifest.py` needs a 658 MB zip and runs by hand. This needs neither, which is what lets
it sit in the ordinary validator sequence and fail a push the moment the manifest and the reward
data stop agreeing.

What it asserts, all re-derived from the tree rather than restated:

  * the manifest is canonical, so a hand edit that breaks its ordering is caught rather than
    quietly surviving until the next regeneration produces a huge spurious diff
  * every boss it claims exists, with the species and tier the definition actually has, and the
    boss and tier counts match the raids directory
  * every Mega Stone and Tera Shard id it names resolves to an item the manifest recorded
  * every item id the reward data references resolves too -- this is the check that would have
    caught a mod's namespace being guessed from its jar filename
  * nothing reachable from a reward is a banned item

Run: python validation/economy/validate_economy_manifest.py
"""

import io
import json
import os
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST_PATH = os.path.join(REPO_ROOT, "validation", "economy", "manifest.json")
RAIDS_DIR = os.path.join(REPO_ROOT, "src", "main", "resources", "data", "cobbleraids", "raids")
COMPAT_DIR = os.path.join(REPO_ROOT, "compat")

# Vanilla is always present; it needs no manifest entry to be a legitimate reward.
ALWAYS_AVAILABLE = ("minecraft",)

TIERS = ("starter", "powerhouse", "legendary", "mythical")

failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def load_json(path):
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def canonical_bytes(manifest):
    buffer = io.StringIO()
    json.dump(manifest, buffer, indent=2, sort_keys=True, ensure_ascii=False)
    buffer.write("\n")
    return buffer.getvalue().encode("utf-8")


def referenced_item_ids():
    """Every item id the reward data names, with the file that names it.

    Walks both shapes the repo uses: loot-table entries (`{"type": "minecraft:item", "name": ...}`)
    and raid-definition reward lines (`{"item": ...}`).
    """
    found = {}
    tag_refs = {}
    table_refs = {}

    def walk(node, source):
        if isinstance(node, dict):
            if node.get("type") == "minecraft:item" and isinstance(node.get("name"), str):
                found.setdefault(node["name"], set()).add(source)
            if isinstance(node.get("item"), str):
                found.setdefault(node["item"], set()).add(source)
            if node.get("type") == "minecraft:tag" and isinstance(node.get("name"), str):
                tag_refs.setdefault(node["name"], set()).add(source)
            if node.get("type") == "minecraft:loot_table" and isinstance(node.get("value"), str):
                table_refs.setdefault(node["value"], set()).add(source)
            for value in node.values():
                walk(value, source)
        elif isinstance(node, list):
            for value in node:
                walk(value, source)

    for root in (COMPAT_DIR, RAIDS_DIR):
        for directory, _unused, filenames in os.walk(root):
            for filename in filenames:
                if not filename.endswith(".json"):
                    continue
                path = os.path.join(directory, filename)
                walk(load_json(path), os.path.relpath(path, REPO_ROOT).replace(os.sep, "/"))
    return found, tag_refs, table_refs


def main():
    if not os.path.exists(MANIFEST_PATH):
        print("Economy manifest validation: FAIL -- " + MANIFEST_PATH + " does not exist."
              " Run validation/economy/build_manifest.py --pack <modpack>.", file=sys.stderr)
        return 1

    with io.open(MANIFEST_PATH, "rb") as handle:
        raw = handle.read()
    try:
        manifest = json.loads(raw.decode("utf-8"))
    except ValueError as error:
        # A truncated or half-merged manifest is a plausible state, and a traceback out of a
        # pre-push hook tells an operator far less than a sentence naming the file and the fix.
        print("Economy manifest validation: FAIL -- manifest.json is not valid JSON (" + str(error)
              + "). Regenerate it with validation/economy/build_manifest.py --pack <modpack>.",
              file=sys.stderr)
        return 1
    if not isinstance(manifest, dict) or "items" not in manifest or "bosses" not in manifest:
        print("Economy manifest validation: FAIL -- manifest.json is missing its top-level"
              " sections. Regenerate it with validation/economy/build_manifest.py.", file=sys.stderr)
        return 1

    # Line endings are normalised out before comparing. core.autocrlf is on for this repo, so a
    # fresh Windows clone hands this file back with CRLF even though it is stored with LF -- and a
    # byte comparison would then fail on a manifest nobody had touched, refusing every push from
    # that clone. What this check is for is a hand edit that breaks the ordering or spacing, and
    # that survives the normalisation intact.
    check(raw.replace(b"\r\n", b"\n") == canonical_bytes(manifest),
          "manifest.json is not in canonical form; regenerate it with build_manifest.py rather"
          " than editing it by hand")

    items = manifest.get("items", {})
    resolved = set()
    for namespace, paths in items.items():
        check(paths, "manifest records namespace '" + namespace + "' with no items")
        check(paths == sorted(paths), "items for '" + namespace + "' are not sorted")
        for path in paths:
            resolved.add(namespace + ":" + path)

    banned = set()
    for namespace, paths in manifest.get("banned", {}).items():
        for path in paths:
            banned.add(namespace + ":" + path)
    check(banned, "manifest records no banned items, so the reachability check asserts nothing")

    # --- bosses -------------------------------------------------------------------------------
    definitions = {}
    for filename in sorted(os.listdir(RAIDS_DIR)):
        if filename.endswith(".json"):
            definition = load_json(os.path.join(RAIDS_DIR, filename))
            definitions[filename[:-5]] = definition

    check(manifest["bosses"]["total"] == len(definitions),
          "manifest claims %d bosses, the tree has %d" % (manifest["bosses"]["total"], len(definitions)))

    counts = {}
    for definition in definitions.values():
        tier = definition.get("rarity_tier")
        counts[tier] = counts.get(tier, 0) + 1
    check(manifest["bosses"]["by_tier"] == counts,
          "manifest tier counts %s do not match the tree's %s" % (manifest["bosses"]["by_tier"], counts))

    # --- mega stones --------------------------------------------------------------------------
    stone_ids = set()
    for boss, entry in manifest.get("mega", {}).items():
        check(boss in definitions, "manifest maps a mega stone to '" + boss + "', which is not a raid definition")
        if boss in definitions:
            species = definitions[boss].get("species", "").split(":")[-1].lower()
            check(entry["species"] == species,
                  "manifest says " + boss + " is " + entry["species"] + "; the definition says " + species)
            check(entry["tier"] == definitions[boss].get("rarity_tier"),
                  "manifest says " + boss + " is " + str(entry["tier"]) + " tier; the definition disagrees")
        check(entry["stones"], "manifest lists " + boss + " under mega with no stones")
        for stone in entry["stones"]:
            stone_ids.add(stone["item"])
            check(stone["item"] in resolved,
                  "mega stone " + stone["item"] + " (from showdown id " + stone["showdown_id"]
                  + ") is not a registered item")

    for shard in manifest.get("tera_shards", []):
        check(shard in resolved, "tera shard " + shard + " is not a registered item")

    # --- what the reward data actually names ---------------------------------------------------
    references, tag_references, table_references = referenced_item_ids()
    for item_id, sources in sorted(references.items()):
        namespace = item_id.split(":")[0]
        if namespace in ALWAYS_AVAILABLE:
            continue
        where = ", ".join(sorted(sources)[:3])
        # Banned first: a banned item is also, by construction, from a namespace the manifest does
        # not stock, and "you are handing out a mod we removed" is the useful half of that.
        if item_id in banned:
            failures.append("reward data reaches banned item " + item_id + " -- seen in " + where)
            continue
        check(namespace in items,
              "reward data references namespace '" + namespace + "' which the manifest does not"
              " cover (add it to PROVIDER_NAMESPACES and regenerate) -- seen in " + where)
        if namespace in items:
            check(item_id in resolved,
                  "reward item " + item_id + " does not exist in the pack -- seen in " + where)

    # --- every definition is on the policy path -------------------------------------------------
    # This replaces validate_phase41's data assertions, which said the opposite: that every
    # definition names its tier bundle. That was the legacy wiring, and it is gone.
    legacy = []
    for boss, definition in sorted(definitions.items()):
        rewards = definition.get("rewards", {})
        own = list(rewards.get("loot_tables", []))
        for choice in rewards.get("choices", {}).values():
            own.extend(choice.get("items", []))
            own.extend(choice.get("chance_items", []))
            own.extend(choice.get("loot_tables", []))
        bonus = rewards.get("contribution_bonus", {})
        if bonus.get("enabled") and bonus.get("pool"):
            own.append("contribution_bonus.pool")
        if own:
            legacy.append(boss)
    check(not legacy,
          "these shipped definitions still name rewards of their own, so they stay on the legacy"
          " path and ignore the reward policy: " + ", ".join(legacy[:8])
          + (" (+%d more)" % (len(legacy) - 8) if len(legacy) > 8 else ""))

    # A boss-specific specialty table must exist for exactly the bosses that have a Mega Stone.
    # One missing means that boss silently drops its stone; one spare means a table nothing reaches.
    boss_table_dir = os.path.join(COMPAT_DIR, "addonrewards", "src", "main", "resources", "data",
                                  "cobbleraids", "loot_table", "specialty", "boss")
    present = set()
    if os.path.isdir(boss_table_dir):
        present = {name[:-5] for name in os.listdir(boss_table_dir) if name.endswith(".json")}
    expected = set(manifest.get("mega", {}))
    for boss in sorted(expected - present):
        failures.append("boss " + boss + " has a mega stone but no specialty/boss table, so it can"
                        " never drop one")
    for boss in sorted(present - expected):
        failures.append("specialty/boss/" + boss + " exists but that boss has no mega stone, so"
                        " nothing reaches it")

    # --- tags and table references --------------------------------------------------------------
    known_tags = manifest.get("item_tags", {})
    for tag_id, sources in sorted(tag_references.items()):
        where = ", ".join(sorted(sources)[:3])
        check(tag_id in known_tags,
              "reward data selects through item tag " + tag_id + " which the pack does not"
              " define -- seen in " + where)
        if known_tags.get(tag_id) == 0:
            failures.append("item tag " + tag_id + " is empty in the pack, so that row would grant"
                            " nothing -- seen in " + where)

    # A cobbleraids: table reference that resolves to no file is a reward that silently rolls
    # nothing. RaidLootRoller warns and returns empty rather than throwing, so this would never
    # surface as an error at runtime -- only as a player wondering where their drop went.
    available = set()
    for root in (COMPAT_DIR, os.path.join(REPO_ROOT, "src", "main", "resources")):
        for directory, _unused, filenames in os.walk(root):
            marker = os.sep + "data" + os.sep + "cobbleraids" + os.sep + "loot_table" + os.sep
            if marker not in directory + os.sep:
                continue
            for filename in filenames:
                if not filename.endswith(".json"):
                    continue
                full = os.path.join(directory, filename)
                relative = full.split(marker, 1)[1].replace(os.sep, "/")[:-len(".json")]
                available.add("cobbleraids:" + relative)
    for table_id, sources in sorted(table_references.items()):
        if not table_id.startswith("cobbleraids:"):
            continue
        check(table_id in available,
              "reward data references loot table " + table_id + " which does not exist -- seen in "
              + ", ".join(sorted(sources)[:3]))

    # --- specialty pools must total exactly 10000 ------------------------------------------------
    specialty_root = os.path.join(COMPAT_DIR, "addonrewards", "src", "main", "resources", "data",
                                  "cobbleraids", "loot_table", "specialty")
    # Only the pools that ARE a specialty selection: the four tier tables and the 21 boss ones.
    # Everything below them -- grouped leaves, mega splits, the per-item wrappers that keep a
    # missing mod from taking a whole table down -- is a child whose own weights are relative.
    selection_tables = []
    for tier in TIERS:
        selection_tables.append(os.path.join(specialty_root, tier + ".json"))
    boss_dir = os.path.join(specialty_root, "boss")
    if os.path.isdir(boss_dir):
        selection_tables.extend(os.path.join(boss_dir, name) for name in sorted(os.listdir(boss_dir))
                                if name.endswith(".json"))
    for path in selection_tables:
        if not os.path.exists(path):
            failures.append("missing specialty selection table " + os.path.basename(path))
            continue
        table = load_json(path)
        for pool in table.get("pools", []):
            total = sum(entry.get("weight", 1) for entry in pool.get("entries", []))
            check(total == 10000, "specialty pool in " + os.path.basename(path)
                  + " totals " + str(total) + ", not 10000")

    if failures:
        print("Economy manifest validation: FAIL", file=sys.stderr)
        for failure in failures:
            print("  - " + failure, file=sys.stderr)
        return 1

    print("Economy manifest validation: PASS -- %d bosses, %d mega stones over %d bosses,"
          " %d tera shards, %d reward item ids, all resolved against pack %s"
          % (manifest["bosses"]["total"], len(stone_ids), len(manifest.get("mega", {})),
             len(manifest.get("tera_shards", [])), len(references), manifest["pack"]["source"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
