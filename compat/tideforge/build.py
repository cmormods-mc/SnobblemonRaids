#!/usr/bin/env python3
"""Build and validate the Tideforge datapack + resource pack.

The Tideforge line is a set of regional FORMS of the Beldum line, reached through
the aspect `tideforge` -- the way Cobblemon does Alolan and Hisuian variants -- not
a set of new species. The pack is server-side; Cobblemon syncs species, features and
abilities to clients on join. See the README for the one client mod (cobbleemi) that
throws that sync away a second later, and what to do about it.

Everything is derived from Cobblemon's own data rather than retyped:

  chassis (stats, EVs, catch rate, growth, drops, hitbox)  <- the Beldum line, inherited
  typing, moves, evolution levels                          <- the Piplup line

Usage:  python build.py [--jar <Cobblemon jar>] [--server-pack <zip>] [--zip]
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).parent
DP, RP = HERE / "datapack", HERE / "resourcepack"

DEFAULT_JAR = Path("L:/Modrinth Instances/profiles/Snobblemon Regions Season 1"
                   "/mods/Cobblemon-fabric-1.7.3+1.21.1.jar")
# The server's remodel pack, as the client cached it. It carries the Tideforge art.
DEFAULT_SERVER_PACK = ("L:/Modrinth Instances/profiles/Snobblemon Regions Season 1"
                       "/downloads/**/6cbf469*")

DATA_PACK_FORMAT, RESOURCE_PACK_FORMAT = 48, 34      # Minecraft 1.21.1 version.json

ASPECT = "tideforge"

# base species  moves from   dex dir   evolves to (species, level, moves learnt)
CHAIN = [
    ("beldum",    "piplup",   "0374_beldum",    ("metang",    16, ["metalclaw"])),
    ("metang",    "prinplup", "0375_metang",    ("metagross", 36, ["aquajet"])),
    ("metagross", "empoleon", "0376_metagross", None),
]

# Art the server's remodel pack already ships, keyed by the base species it now
# decorates. Beldum has none, so it falls back to vanilla Beldum's.
ART = {
    "beldum": {"poser": "cobblemon:beldum", "model": "cobblemon:beldum.geo",
               "texture": "cobblemon:textures/pokemon/0374_beldum/beldum.png",
               "shiny": "cobblemon:textures/pokemon/0374_beldum/beldum_shiny.png",
               "placeholder": True},
    "metang": {"poser": "cobblemon:tideforge_metang", "model": "cobblemon:tideforge_metang.geo",
               "texture": "cobblemon:textures/pokemon/0375_tideforge_metang/tideforge_metang.png",
               "shiny": "cobblemon:textures/pokemon/0375_tideforge_metang/tideforge_metang_shiny.png",
               "placeholder": False},
    "metagross": {"poser": "cobblemon:tideforge_metagross",
                  "model": "cobblemon:tideforge_metagross.geo",
                  "texture": "cobblemon:textures/pokemon/0376_tideforge_metagross/tideforge_metagross.png",
                  "shiny": "cobblemon:textures/pokemon/0376_tideforge_metagross/tideforge_metagross_shiny.png",
                  "placeholder": False},
}

# Cobblemon keys animation groups by bare filename, directories discarded, so the
# remodel pack's 0376_tideforge_metagross/metagross.animation.json registers as
# "metagross" and evicts Cobblemon's own. That crashed clients on 2026-09-13, and it
# also means the group tideforge_metagross -- which the Tideforge poser asks for --
# is never registered at all. Re-file both under a directory that sorts last.
ANIMATION_FIX_DIR = "assets/cobblemon/bedrock/pokemon/animations/zz_animation_key_fix"
ANIMATION_FIX_FROM_JAR = {
    "metagross.animation.json":
        "assets/cobblemon/bedrock/pokemon/animations/0376_metagross/metagross.animation.json",
    "dewott_hisui_bias.animation.json":
        "assets/cobblemon/bedrock/pokemon/animations/0502_dewott/dewott_hisui_bias.animation.json",
}
ANIMATION_FIX_FROM_PACK = {
    "tideforge_metagross.animation.json":
        "assets/cobblemon/bedrock/pokemon/animations/0376_tideforge_metagross/metagross.animation.json",
}


def cobblemon_species(jar: Path) -> dict:
    """Every species Cobblemon ships, keyed the way Cobblemon keys them: by bare
    filename, directories discarded (JsonDataRegistry.reload)."""
    out = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith("data/cobblemon/species/") and name.endswith(".json"):
                out[Path(name).stem] = json.loads(z.read(name).decode("utf-8-sig"))
    return out


def aquatic(behaviour: dict, base_name: str) -> dict:
    """The Beldum line avoids water. A Water type must not."""
    b = json.loads(json.dumps(behaviour))
    b.setdefault("moving", {})["swim"] = {
        "avoidsWater": False, "canBreatheUnderwater": True, "swimSpeed": "0.2",
    }
    if "toleratedLeaders" in b.get("herd", {}):
        b["herd"]["toleratedLeaders"] = [
            {"pokemon": "beldum " + ASPECT, "tier": 1},
            {"pokemon": "metang " + ASPECT, "tier": 2},
            {"pokemon": "metagross " + ASPECT, "tier": 3},
        ]
    return b


def build_additions(vanilla: dict) -> dict:
    """One species_addition per base species, each adding the Tideforge form."""
    out = {}
    for base_name, donor_name, _dex_dir, evo in CHAIN:
        base, donor = vanilla[base_name], vanilla[donor_name]

        form = {
            "name": "Tideforge",
            "aspects": [ASPECT],
            "labels": ["gen3", "tideforge_form"],
            "primaryType": "water",
            "secondaryType": "steel",
            "abilities": ["clearbody", "h:silentrunning"],
            "pokedex": ["cobblemon.species.%s-tideforge.desc" % base_name],
            "moves": list(donor["moves"]),
            "behaviour": aquatic(base["behaviour"], base_name),
        }
        # A form inherits the species' evolutions unless it declares its own, so the
        # Tideforge forms must state theirs or they would evolve into vanilla Metang.
        if evo:
            result, level, learnable = evo
            form["evolutions"] = [{
                "id": "%s_%s_tideforge" % (base_name, result),
                "variant": "level_up",
                "result": "%s %s" % (result, ASPECT),
                "consumeHeldItem": False,
                "learnableMoves": learnable,
                "requirements": [{"variant": "level", "minLevel": level}],
            }]
        else:
            form["evolutions"] = []

        out[base_name] = {"target": base_name, "forms": [form]}
    return out


SILENT_RUNNING = r'''{
  name: "Silent Running",

  /* Enters battle Submerged. Status moves keep it; the first damaging hit it
     takes, or the first damaging move it uses, surfaces it.

     Block comments, not line comments: Cobblemon flattens this file to a single
     line before handing it to Showdown, so a // comment would swallow the rest
     of the script. That is a server-boot crash, not a warning. */
  onStart(pokemon) {
    pokemon.tideforgeSubmerged = true;
    this.add("-message", pokemon.name + " slipped beneath the surface!");
  },

  /* Switching out resets the ability; onStart submerges it again on the way back in. */
  onSwitchOut(pokemon) {
    pokemon.tideforgeSubmerged = false;
  },
  onEnd(pokemon) {
    pokemon.tideforgeSubmerged = false;
  },

  /* Absorb the hit: the first damaging hit taken while Submerged lands 25%
     lighter. The handler lives on the defender, the same way Multiscale's does. */
  onSourceModifyDamage(damage, source, target, move) {
    if (target.tideforgeSubmerged) {
      this.debug("Silent Running weaken");
      return this.chainModify(0.75);
    }
  },
  onDamagingHit(damage, target, source, move) {
    if (!target.tideforgeSubmerged) return;
    target.tideforgeSubmerged = false;
    this.add("-message", target.name + " was forced to the surface!");
  },

  /* Or launch the torpedo: strike first and the Water move hits 30% harder.
     Surfacing happens here rather than in a later hook so the boost below is
     still applied to the very move that spends it. 5325/4096 is 1.3x, the
     ratio Sheer Force uses. */
  onBasePower(basePower, attacker, defender, move) {
    if (!attacker.tideforgeSubmerged) return;
    var torpedo = move.type === "Water";
    attacker.tideforgeSubmerged = false;
    this.add("-message", attacker.name + " broke the surface!");
    if (torpedo) return this.chainModify([5325, 4096]);
  },

  /* Fixed-damage moves (Seismic Toss, Night Shade) never reach the base power
     chain, so they surface it here instead. */
  onModifyMove(move, pokemon) {
    if (pokemon.tideforgeSubmerged && move.category !== "Status" && !move.basePower) {
      pokemon.tideforgeSubmerged = false;
      this.add("-message", pokemon.name + " broke the surface!");
    }
  },

  flags: { breakable: 1 },
  rating: 3,
  num: -7301
}
'''

LANG = {
    "cobblemon.species.beldum-tideforge.desc":
        "Ballast floods its shell so it can sink out of sight. It hangs motionless on the "
        "seabed for days, tracking the magnetic wake of anything that swims overhead.",
    "cobblemon.species.metang-tideforge.desc":
        "Two Tideforge Beldum fused under pressure. The seam between them vents jets of "
        "water, letting it turn in place without disturbing the silt.",
    "cobblemon.species.metagross-tideforge.desc":
        "Its four hulls close into a single ram. Sailors read the sudden calm above it as a "
        "warning, because the water only goes still once it has chosen a target.",
    "cobblemon.ability.silentrunning": "Silent Running",
    "cobblemon.ability.silentrunning.desc":
        "It slips beneath the surface, concealing its next attack.",
}


def ability_script_problems(script: str) -> list:
    """Cobblemon hands the ability script to Showdown as a single line, so a `//`
    comment swallows everything after it and the server dies during data load with
    `SyntaxError: Expected ident but found eof`. Check the script the way Cobblemon
    will actually see it."""
    problems = []
    stripped = re.sub(r"/\*.*?\*/", " ", script, flags=re.S)
    stripped = re.sub(r'"(?:[^"\\]|\\.)*"', '""', stripped)
    if "//" in stripped:
        problems.append("ability script uses a // comment; it is flattened to one line "
                        "before Showdown sees it, so that comments out the rest of the file")

    wrapped = '({"silentrunning": %s })' % script.replace("\n", " ")
    node = shutil.which("node")
    if node:
        r = subprocess.run([node, "--check"], input=wrapped, text=True, encoding="utf-8",
                           capture_output=True)
        if r.returncode != 0:
            lines = [l.strip() for l in r.stderr.splitlines() if l.strip()]
            detail = next((l for l in lines if "Error" in l), lines[-1] if lines else "?")
            problems.append("flattened ability script does not parse: " + detail)
    else:
        depth = {"(": 0, "[": 0, "{": 0}
        pairs = {")": "(", "]": "[", "}": "{"}
        body = re.sub(r'"(?:[^"\\]|\\.)*"', '""',
                      re.sub(r"/\*.*?\*/", " ", wrapped, flags=re.S))
        for ch in body:
            if ch in depth:
                depth[ch] += 1
            elif ch in pairs:
                depth[pairs[ch]] -= 1
        if any(v for v in depth.values()):
            problems.append("flattened ability script is unbalanced: %s (no node on PATH, "
                            "so this is a bracket count rather than a real parse)" % depth)
    return problems


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def dump(path: Path, obj) -> None:
    write(path, json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def validate(additions: dict, vanilla: dict, server_pack: zipfile.ZipFile | None) -> list:
    problems = []
    legal_moves = {m for s in vanilla.values() for m in s["moves"]}

    for base_name, addition in additions.items():
        if base_name not in vanilla:
            problems.append("%s: species_addition targets a species Cobblemon does not ship"
                            % base_name)
        for f in addition["forms"]:
            if ASPECT not in f["aspects"]:
                problems.append("%s: form does not carry the %r aspect" % (base_name, ASPECT))
            # a form without its own evolutions inherits the base species', which for
            # Beldum and Metang means evolving out of the Tideforge line entirely
            if "evolutions" not in f:
                problems.append("%s: form does not override evolutions" % base_name)
            for e in f.get("evolutions", []):
                if not e["result"].endswith(" " + ASPECT):
                    problems.append("%s: evolution result %r drops the aspect"
                                    % (base_name, e["result"]))
                target = e["result"].split()[0]
                if target not in vanilla:
                    problems.append("%s: evolves into unknown species %r" % (base_name, target))
            for m in f["moves"]:
                if m not in legal_moves:
                    problems.append("%s: move %r appears in no Cobblemon species"
                                    % (base_name, m))
            for a in f["abilities"]:
                bare = a[2:] if a.startswith("h:") else a
                if bare == "silentrunning":
                    continue
                if not any(bare in v["abilities"] or ("h:" + bare) in v["abilities"]
                           for v in vanilla.values()):
                    problems.append("%s: ability %r is not used by any Cobblemon species"
                                    % (base_name, bare))
            if f.get("primaryType") == f.get("secondaryType"):
                problems.append("%s: primary and secondary type are the same" % base_name)
            for key in f["pokedex"]:
                if key not in LANG:
                    problems.append("lang is missing " + key)

    # the id Showdown computes from the script's name must match what the forms ask for
    name = re.search(r'name:\s*"([^"]+)"', SILENT_RUNNING).group(1)
    showdown_id = re.sub(r"[^a-z0-9]", "", name.lower())
    if showdown_id != "silentrunning":
        problems.append("ability name %r gives Showdown id %r, but the forms reference "
                        "'h:silentrunning'" % (name, showdown_id))
    for key in ("cobblemon.ability." + showdown_id, "cobblemon.ability." + showdown_id + ".desc"):
        if key not in LANG:
            problems.append("lang is missing " + key)
    problems += ability_script_problems(SILENT_RUNNING)

    # the Tideforge posers are useless without their animation group, and the group is
    # only defined by the misnamed file inside the server's pack
    if server_pack is None:
        problems.append("server pack not found: the Tideforge Metagross animations cannot be "
                        "re-filed, and its poser will fail to load on every client")
    else:
        for src in ANIMATION_FIX_FROM_PACK.values():
            if src not in server_pack.namelist():
                problems.append("server pack is missing " + src)
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    ap.add_argument("--server-pack", default=DEFAULT_SERVER_PACK)
    ap.add_argument("--zip", action="store_true", help="also write the two distributable zips")
    args = ap.parse_args()

    if not args.jar.exists():
        print("Cobblemon jar not found: %s" % args.jar, file=sys.stderr)
        return 2

    matches = glob.glob(args.server_pack, recursive=True)
    server_pack = zipfile.ZipFile(matches[0]) if matches else None

    vanilla = cobblemon_species(args.jar)
    additions = build_additions(vanilla)

    problems = validate(additions, vanilla, server_pack)
    if problems:
        print("VALIDATION FAILED")
        for p in problems:
            print("  -", p)
        return 1

    for d in (DP, RP):
        if d.exists():
            shutil.rmtree(d)

    # ---- datapack -------------------------------------------------------------
    dump(DP / "pack.mcmeta", {"pack": {
        "pack_format": DATA_PACK_FORMAT,
        "description": "Tideforge forms of the Beldum line - Water/Steel"}})
    dump(DP / "data/cobblemon/species_features/tideforge.json",
         {"keys": [ASPECT], "type": "flag", "isAspect": True, "default": False})
    dump(DP / "data/cobblemon/species_feature_assignments/regional_tideforge.json",
         {"pokemon": [b for b, _, _, _ in CHAIN], "features": [ASPECT]})
    for base_name, addition in additions.items():
        dump(DP / "data/cobblemon/species_additions/tideforge" / (base_name + ".json"), addition)
    write(DP / "data/tideforge/abilities/silentrunning.js", SILENT_RUNNING)

    # ---- resource pack --------------------------------------------------------
    dump(RP / "pack.mcmeta", {"pack": {
        "pack_format": RESOURCE_PACK_FORMAT,
        "description": "Tideforge forms - art bindings, dex entries, animation key fix"}})
    dump(RP / "assets/cobblemon/lang/en_us.json", LANG)

    for base_name, _donor, dex_dir, _evo in CHAIN:
        art = ART[base_name]
        dump(RP / "assets/cobblemon/bedrock/pokemon/resolvers" / dex_dir
             / ("5_%s_tideforge.json" % base_name), {
            "species": "cobblemon:" + base_name,
            "order": 5,
            "variations": [
                {"aspects": [ASPECT], "poser": art["poser"], "model": art["model"],
                 "texture": art["texture"], "layers": []},
                {"aspects": ["shiny", ASPECT], "texture": art["shiny"]},
            ],
        })

    with zipfile.ZipFile(args.jar) as z:
        for dest, src in ANIMATION_FIX_FROM_JAR.items():
            (RP / ANIMATION_FIX_DIR).mkdir(parents=True, exist_ok=True)
            (RP / ANIMATION_FIX_DIR / dest).write_bytes(z.read(src))
    for dest, src in ANIMATION_FIX_FROM_PACK.items():
        (RP / ANIMATION_FIX_DIR / dest).write_bytes(server_pack.read(src))

    for base_name, donor, _dex_dir, evo in CHAIN:
        form = additions[base_name]["forms"][0]
        evo_txt = form["evolutions"][0]["result"] + " @ " + str(
            form["evolutions"][0]["requirements"][0]["minLevel"]) if form["evolutions"] else "-"
        note = "  (placeholder art)" if ART[base_name]["placeholder"] else ""
        print("%-10s + aspect %-10s %s/%s  %3d moves (from %s)  -> %s%s"
              % (base_name, ASPECT, form["primaryType"], form["secondaryType"],
                 len(form["moves"]), donor, evo_txt, note))

    if args.zip:
        for src, out in ((DP, HERE / "tideforge-datapack.zip"),
                         (RP, HERE / "tideforge-resourcepack.zip")):
            if out.exists():
                out.unlink()
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
                for f in sorted(src.rglob("*")):
                    if f.is_file():
                        z.write(f, f.relative_to(src).as_posix())
            print("wrote %s (%d bytes)" % (out.name, out.stat().st_size))

    print("validation: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
