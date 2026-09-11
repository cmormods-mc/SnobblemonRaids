#!/usr/bin/env python3
"""Builds the reward-economy manifest from a modpack.

The economy's loot tables name items from a dozen other mods. Nothing in this repo can tell
whether `cobblemoncharms:bug_charm` is real, so the answer has to come from the pack itself --
and it has to come from the pack *as data*, not as a claim in a document that goes stale the
next time a mod updates.

Run this against the pack, commit the result, and every later check reads the manifest instead
of guessing:

    python validation/economy/build_manifest.py --pack <modpack.zip or mods/ dir>

Then `validate_economy_manifest.py` verifies the committed manifest without needing the pack at
all, which is what lets it run in CI on a machine that has never seen a 658 MB zip.

Two things this deliberately does not do. It does not read language files as a registry: the
charms mod ships one `item.cobblemoncharms.type_charm` key covering eighteen separate items, so
a lang-based check reports eighteen phantom failures. Item model paths are the property that
actually tracks the registry. And it does not hardcode the awkward Mega Stone names -- it
derives them by rule and asserts the result exists, so a future mod update that renames more of
them fails loudly here rather than shipping four broken reward entries.
"""

import argparse
import hashlib
import io
import json
import os
import re
import sys
import zipfile

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST_PATH = os.path.join(REPO_ROOT, "validation", "economy", "manifest.json")
RAIDS_DIR = os.path.join(REPO_ROOT, "src", "main", "resources", "data", "cobbleraids", "raids")

# The namespaces the reward economy draws from. Every other mod in the pack is irrelevant here,
# and dumping all thirty-one registries would bury the useful part in Terralith block ids.
PROVIDER_NAMESPACES = [
    "cobblemon",
    "mega_showdown",
    "cobblemoncharms",
    "cobblemon-cards",
    "cobblecapsule",
    "cobblesafari",
    "daycareplus",
    "ridetraining",
    "companion_bonds",
    "simpletms",
]

# Items that must never be reachable from a reward. Recorded rather than assumed so the Phase A
# reachability check has something concrete to assert against.
BANNED_NAMESPACES = ["cobblemon_utility"]

ITEM_MODEL = re.compile(r"^assets/([a-z0-9_.-]+)/models/item/(.+)\.json$")
MEGA_ENTRY = re.compile(r"^data/mega_showdown/mega_showdown/mega/.+\.json$")
TERA_SHARD = re.compile(r"^[a-z]+_tera_shard$")


def fail(message):
    print("build_manifest: " + message, file=sys.stderr)
    sys.exit(1)


class Pack:
    """A modpack, whether it arrived as a zip or as a plain mods directory."""

    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path.rstrip("/\\"))
        self._zip = zipfile.ZipFile(path) if zipfile.is_zipfile(path) else None
        if self._zip is not None:
            self._jars = sorted(n for n in self._zip.namelist()
                                if n.lower().endswith(".jar") and "/" not in n)
        elif os.path.isdir(path):
            self._jars = sorted(n for n in os.listdir(path) if n.lower().endswith(".jar"))
        else:
            fail(path + " is neither a zip nor a directory")

    def jars(self):
        return self._jars

    def read_jar(self, jar):
        """The jar's bytes. Nested zip members are read whole: streaming a zip inside a zip
        trips 'Truncated file header' on seek-heavy access."""
        if self._zip is not None:
            return self._zip.read(jar)
        with open(os.path.join(self.path, jar), "rb") as handle:
            return handle.read()

    def fingerprint(self):
        """Identifies the pack by what is in it, not by hashing 658 MB. Any mod added, removed or
        updated changes a name, a size or a CRC, and so changes this."""
        digest = hashlib.sha256()
        for jar in self._jars:
            if self._zip is not None:
                info = self._zip.getinfo(jar)
                digest.update(("%s:%d:%d\n" % (jar, info.file_size, info.CRC)).encode("utf-8"))
            else:
                full = os.path.join(self.path, jar)
                digest.update(("%s:%d\n" % (jar, os.path.getsize(full))).encode("utf-8"))
        return digest.hexdigest()


def lenient_json(raw):
    """fabric.mod.json is hand-written and some mods ship comments or trailing commas in it --
    mega_showdown's does not parse strictly. Falling back to a regex for the one field that
    matters beats skipping the mod that owns every Mega Stone."""
    text = raw.decode("utf-8", "replace")
    try:
        return json.loads(text)
    except ValueError:
        pass
    stripped = re.sub(r"//[^\n]*", "", text)
    stripped = re.sub(r",(\s*[}\]])", r"\1", stripped)
    try:
        return json.loads(stripped)
    except ValueError:
        pass
    found = {}
    for key in ("id", "version"):
        match = re.search(r'"' + key + r'"\s*:\s*"([^"]+)"', text)
        if match:
            found[key] = match.group(1)
    return found


def read_mods(pack):
    """Mod id -> jar and version. The id is what namespaces items; the jar name is not.
    Cobblemon Mount Mastery registers `ridetraining`, and nothing about its filename says so."""
    mods = {}
    registries = {}
    mega_entries = []
    for jar in pack.jars():
        raw = pack.read_jar(jar)
        try:
            inner = zipfile.ZipFile(io.BytesIO(raw))
        except zipfile.BadZipFile:
            continue
        names = inner.namelist()
        if "fabric.mod.json" in names:
            meta = lenient_json(inner.read("fabric.mod.json"))
            mod_id = meta.get("id")
            if mod_id:
                mods[mod_id] = {"jar": jar, "version": str(meta.get("version", "unknown"))}
        for name in names:
            match = ITEM_MODEL.match(name)
            if match:
                registries.setdefault(match.group(1), set()).add(match.group(2))
            if MEGA_ENTRY.match(name):
                entry = json.loads(inner.read(name).decode("utf-8", "replace"))
                mega_entries.append(entry)
    return mods, registries, mega_entries


def read_bosses():
    """Every raid definition's species and tier, read from the tree rather than restated here."""
    bosses = {}
    for filename in sorted(os.listdir(RAIDS_DIR)):
        if not filename.endswith(".json"):
            continue
        with io.open(os.path.join(RAIDS_DIR, filename), encoding="utf-8") as handle:
            definition = json.load(handle)
        bosses[filename[:-5]] = {
            "species": definition.get("species", "").split(":")[-1].lower(),
            "tier": definition.get("rarity_tier"),
        }
    return bosses


def stone_item_id(showdown_id, registry):
    """A Mega Stone's registry path from its showdown id.

    Usually they are the same. Four are not: charizarditex, charizarditey, mewtwonitex and
    mewtwonitey register as charizardite_x and so on. Rather than listing those four -- which
    would silently miss a fifth when the mod next adds an X/Y pair -- try the rule and let the
    caller reject anything that still does not resolve.
    """
    if showdown_id in registry:
        return showdown_id
    variant = re.match(r"^(.*)([xy])$", showdown_id)
    if variant:
        candidate = variant.group(1) + "_" + variant.group(2)
        if candidate in registry:
            return candidate
    return None


def build_mega(mega_entries, bosses, registry):
    """Boss -> the stones that boss can drop, for the bosses that have one."""
    species_to_stones = {}
    for entry in mega_entries:
        showdown_id = entry.get("showdown_id")
        for species in entry.get("pokemons", []):
            species_to_stones.setdefault(species.lower(), []).append(showdown_id)

    mapped = {}
    unresolved = []
    for boss in sorted(bosses):
        species = bosses[boss]["species"]
        if species not in species_to_stones:
            continue
        stones = []
        for showdown_id in sorted(species_to_stones[species]):
            path = stone_item_id(showdown_id, registry)
            if path is None:
                unresolved.append(boss + ": " + str(showdown_id))
                continue
            stones.append({"showdown_id": showdown_id, "item": "mega_showdown:" + path})
        mapped[boss] = {"species": species, "tier": bosses[boss]["tier"], "stones": stones}
    return mapped, unresolved


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pack", required=True, help="modpack zip, or a mods/ directory")
    parser.add_argument("--out", default=MANIFEST_PATH, help="where to write the manifest")
    args = parser.parse_args()

    pack = Pack(args.pack)
    mods, registries, mega_entries = read_mods(pack)
    print("build_manifest: %d jars, %d mods, %d mega entries" % (len(pack.jars()), len(mods), len(mega_entries)))

    missing = [ns for ns in PROVIDER_NAMESPACES if ns not in mods]
    if missing:
        fail("the pack is missing provider mods the economy needs: " + ", ".join(sorted(missing)))

    items = {}
    for namespace in PROVIDER_NAMESPACES:
        paths = registries.get(namespace)
        if not paths:
            fail("no item models found for namespace '" + namespace + "'")
        items[namespace] = sorted(paths)

    banned = {}
    for namespace in BANNED_NAMESPACES:
        banned[namespace] = sorted(registries.get(namespace, []))

    bosses = read_bosses()
    mega, unresolved = build_mega(mega_entries, bosses, set(registries.get("mega_showdown", [])))
    if unresolved:
        fail("mega stone ids that resolve to no item: " + "; ".join(unresolved)
             + " -- the naming rule in stone_item_id() needs extending")

    tera = sorted(p for p in registries.get("mega_showdown", []) if TERA_SHARD.match(p))

    tier_counts = {}
    for boss in bosses.values():
        tier_counts[boss["tier"]] = tier_counts.get(boss["tier"], 0) + 1

    manifest = {
        "pack": {
            "source": pack.name,
            "fingerprint": pack.fingerprint(),
            "jars": len(pack.jars()),
        },
        "mods": {mod_id: mods[mod_id] for mod_id in sorted(mods) if mod_id in PROVIDER_NAMESPACES},
        "bosses": {"total": len(bosses), "by_tier": dict(sorted(tier_counts.items()))},
        "mega": mega,
        "tera_shards": ["mega_showdown:" + path for path in tera],
        "banned": banned,
        "items": items,
    }

    with io.open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write("\n")

    stones = sorted({stone["item"] for boss in mega.values() for stone in boss["stones"]})
    print("build_manifest: %d bosses (%s)" % (len(bosses), ", ".join(
        "%s %d" % (tier, count) for tier, count in sorted(tier_counts.items()))))
    print("build_manifest: %d bosses carry a mega stone, %d distinct stone ids" % (len(mega), len(stones)))
    print("build_manifest: %d tera shards, %d banned items recorded" % (
        len(tera), sum(len(v) for v in banned.values())))
    print("build_manifest: wrote " + os.path.relpath(args.out, REPO_ROOT))


if __name__ == "__main__":
    main()
