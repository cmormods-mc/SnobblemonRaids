"""Finds which archive in a folder actually supplies a species' artwork.

Cobblemon 1.7.3's own jar ships no model, texture, poser or resolver for 76 of the 130 raid
species -- Arceus among them -- yet a live server renders them, so something else is providing the
art. This says what.

    python validation/sprites/find_species_art.py <folder> [species ...]

<folder> is a mods/ or resourcepacks/ directory, or anything above them; every .jar and .zip
underneath is searched. Point it at the server and at the client, since a client-side resource
pack supplies artwork the server never sees.
"""
import argparse
import os
import sys
import zipfile

ART_SUFFIXES = (".png", ".geo.json", ".json")
ART_HINTS = ("textures", "models", "posers", "resolvers", "sprites")


def archives(root):
    if os.path.isfile(root):
        yield root
        return
    for directory, _subdirs, files in os.walk(root):
        for name in files:
            if name.lower().endswith((".jar", ".zip")):
                yield os.path.join(directory, name)


def search(path, species):
    """Entries in one archive that look like artwork for any of the given species."""
    try:
        archive = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError):
        return []
    found = []
    for entry in archive.namelist():
        lower = entry.lower()
        if not lower.endswith(ART_SUFFIXES):
            continue
        if not any(hint in lower for hint in ART_HINTS):
            continue
        for name in species:
            if name in lower:
                found.append((name, entry))
                break
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder")
    parser.add_argument("species", nargs="*", default=None)
    parser.add_argument("--limit", type=int, default=6, help="entries shown per archive")
    args = parser.parse_args()

    species = [s.lower().split(":")[-1] for s in (args.species or ["arceus", "groudon", "zacian"])]
    if not os.path.exists(args.folder):
        print("no such path: " + args.folder, file=sys.stderr)
        return 2

    print("looking for: %s" % ", ".join(species))
    total = 0
    scanned = 0
    for path in archives(args.folder):
        scanned += 1
        hits = search(path, species)
        if not hits:
            continue
        total += len(hits)
        print("\n%s  (%d matching entries)" % (os.path.basename(path), len(hits)))
        for name, entry in hits[:args.limit]:
            print("   [%s] %s" % (name, entry))
        if len(hits) > args.limit:
            print("   ... and %d more" % (len(hits) - args.limit))

    print("\nscanned %d archives, %d matching entries" % (scanned, total))
    if not total:
        print("Nothing found. If the game still renders these species, it is drawing Cobblemon's\n"
              "generic substitute model (bedrock/generic/models/substitute.geo.json) rather than\n"
              "the species -- check whether the party slot shows the real Pokemon or a grey doll.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
