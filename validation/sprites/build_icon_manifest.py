"""Emits the species -> card-icon map the shop uses to draw a Pokemon without a 3D model.

cobblemon-cards ships hand-drawn 48x32 entity icons for 1021 species, which are better than
anything rendered from the bedrock models and are already on every client that has the mod. The
shop references them in place rather than shipping copies, so nothing is redistributed.

The paths are almost formulaic -- `entity_icon/<dex>_<species>/<species>.png` -- but not quite:
the folder is a sanitised name while the file keeps the real one, so Kommo-o lives at
`0784_kommoo/kommo-o.png`. Guessing that at runtime would break on exactly the species nobody
tests. Scanning the jar once and committing the result makes it a lookup instead.

    python validation/sprites/build_icon_manifest.py <cobblemon-cards.jar | server.zip>
    python validation/sprites/build_icon_manifest.py <archive> --check
"""
import argparse
import io
import json
import os
import re
import sys
import zipfile

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST_PATH = os.path.join(REPO_ROOT, "src", "main", "resources", "assets", "cobbleraids",
                             "shop_icons.json")
RAID_DIR = os.path.join(REPO_ROOT, "src", "main", "resources", "data", "cobbleraids", "raids")
ICON_RE = re.compile(
    r"^assets/cobblemon-cards/textures/item/cards/pokemon/entity_icon/"
    r"(?P<folder>\d+_[a-z0-9]+)/(?P<file>[a-z0-9_\-]+)\.png$")


def open_cards(path):
    """The cobblemon-cards jar, whether given directly or inside a packed server folder."""
    archive = zipfile.ZipFile(path)
    if any(n.startswith("assets/cobblemon-cards/") for n in archive.namelist()):
        return archive
    for name in archive.namelist():
        if re.search(r"cobblemon-cards.*\.jar$", name, re.I):
            return zipfile.ZipFile(io.BytesIO(archive.read(name)))
    raise SystemExit("no cobblemon-cards assets in " + path)


# The default form, when a species ships only forms. Ordered, so Enamorus resolves to Incarnate
# rather than to whichever name happens to sort first.
DEFAULT_FORMS = ("incarnate", "altered", "land", "ordinary", "standard", "normal", "midday",
                 "solo", "amped", "average", "plant", "overcast", "spring", "natural",
                 "disguised", "shield", "red_striped", "male", "female")


def _flatten(name):
    """kommo-o and ho_oh both have to match the folder names kommoo and hooh."""
    return name.replace("-", "").replace("_", "")


def build(archive):
    """species -> {"icon": "<folder>/<file>", "shiny": ...}, base form only.

    A species folder holds its forms and its Megas, so the base form has to be chosen rather than
    taken. Three shapes forced this to be a search instead of a formula: Venusaur ships only
    venusaur_male/_female, Ho-Oh's folder is 0250_hooh but its file is ho_oh, and Enamorus ships
    only enamorus_incarnate/_therian. Megas fall out for free because megavenusaur does not start
    with the species name.
    """
    folders = {}
    for entry in archive.namelist():
        match = ICON_RE.match(entry)
        if not match:
            continue
        folders.setdefault(match.group("folder"), set()).add(match.group("file"))

    icons = {}
    for folder, files in folders.items():
        species = folder.split("_", 1)[1]
        plain = {f for f in files if not f.endswith("_shiny")}
        chosen = None
        for candidate in sorted(plain):
            if _flatten(candidate) == species:
                chosen = candidate
                break
        if chosen is None:
            for suffix in DEFAULT_FORMS:
                for candidate in plain:
                    if _flatten(candidate) == _flatten(species + "_" + suffix):
                        chosen = candidate
                        break
                if chosen:
                    break
        if chosen is None:
            starts = sorted((f for f in plain if _flatten(f).startswith(species)), key=len)
            chosen = starts[0] if starts else None
        if chosen is None:
            continue
        icons[species] = {"icon": folder + "/" + chosen}
        if chosen + "_shiny" in files:
            icons[species]["shiny"] = folder + "/" + chosen + "_shiny"
    return dict(sorted(icons.items()))


def raid_species():
    names = set()
    if not os.path.isdir(RAID_DIR):
        return names
    for file in os.listdir(RAID_DIR):
        if not file.endswith(".json"):
            continue
        with io.open(os.path.join(RAID_DIR, file), encoding="utf-8") as handle:
            data = json.load(handle)
        species = data.get("species") or data.get("pokemon") or file[:-5]
        names.add(str(species).lower().split(":")[-1])
    return names


def serialise(icons):
    return json.dumps({"version": 1, "icons": icons}, indent=2, sort_keys=True) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", nargs="?", help="cobblemon-cards jar, or a zipped server folder")
    parser.add_argument("--check", action="store_true",
                        help="verify the committed manifest covers every raid species")
    args = parser.parse_args()

    if args.check and not args.archive:
        # CI has no jar, so it checks the committed manifest against the raid definitions instead:
        # a species added to the roster without an icon is the regression worth catching.
        if not os.path.exists(MANIFEST_PATH):
            print("shop icon validation: FAIL -- manifest is missing", file=sys.stderr)
            return 1
        with io.open(MANIFEST_PATH, encoding="utf-8") as handle:
            icons = json.load(handle).get("icons", {})
        missing = sorted(s for s in raid_species() if s not in icons)
        if missing:
            print("shop icon validation: FAIL", file=sys.stderr)
            for name in missing:
                print("  - raid species '%s' has no card icon" % name, file=sys.stderr)
            print("  Run: python validation/sprites/build_icon_manifest.py <cobblemon-cards.jar>",
                  file=sys.stderr)
            return 1
        shinies = sum(1 for v in icons.values() if "shiny" in v)
        print("shop icon validation: PASS -- %d species (%d with shinies), all %d raid species covered"
              % (len(icons), shinies, len(raid_species())))
        return 0

    if not args.archive:
        parser.error("an archive is required unless --check is used alone")

    icons = build(open_cards(args.archive))
    raids = raid_species()
    missing = sorted(s for s in raids if s not in icons)
    text = serialise(icons)

    if args.check:
        current = ""
        if os.path.exists(MANIFEST_PATH):
            with io.open(MANIFEST_PATH, encoding="utf-8") as handle:
                current = handle.read().replace("\r\n", "\n")
        if current != text:
            print("shop icon validation: FAIL -- manifest differs from the archive", file=sys.stderr)
            return 1
        print("shop icon validation: PASS -- manifest matches the archive")
        return 0

    os.makedirs(os.path.dirname(MANIFEST_PATH), exist_ok=True)
    with io.open(MANIFEST_PATH, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print("wrote %d species (%d with shiny variants)"
          % (len(icons), sum(1 for v in icons.values() if "shiny" in v)))
    print("raid species covered: %d/%d%s"
          % (len(raids) - len(missing), len(raids),
             ("  MISSING: " + ", ".join(missing)) if missing else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
