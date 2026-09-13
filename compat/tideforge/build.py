#!/usr/bin/env python3
"""Build and validate the Tideforge datapack + resource pack.

Everything is derived from Cobblemon's own data rather than retyped, so no move
name, stat or growth rate can drift from the game's:

  chassis (stats, EVs, catch rate, growth, drops, hitbox)  <- the Beldum line
  typing, moves, evolution levels                          <- the Piplup line

Usage:  python build.py [--jar <Cobblemon jar>] [--zip]
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).parent
DP, RP = HERE / "datapack", HERE / "resourcepack"

DEFAULT_JAR = Path("L:/Modrinth Instances/profiles/Snobblemon Regions Season 1"
                   "/mods/Cobblemon-fabric-1.7.3+1.21.1.jar")

DATA_PACK_FORMAT, RESOURCE_PACK_FORMAT = 48, 34      # Minecraft 1.21.1 version.json

# our id                base         moves from  dex     evolves to (id, level, moves learnt)
CHAIN = [
    ("tideforge_beldum",    "beldum",    "piplup",   10374, ("tideforge_metang",    16, ["metalclaw"])),
    ("tideforge_metang",    "metang",    "prinplup", 10375, ("tideforge_metagross", 36, ["aquajet"])),
    ("tideforge_metagross", "metagross", "empoleon", 10376, None),
]
DISPLAY = {"tideforge_beldum": "Tideforge Beldum",
           "tideforge_metang": "Tideforge Metang",
           "tideforge_metagross": "Tideforge Metagross"}
PRE_EVOLUTION = {"tideforge_metang": "tideforge_beldum",
                 "tideforge_metagross": "tideforge_metang"}
HERD = [{"pokemon": "tideforge_beldum", "tier": 1},
        {"pokemon": "tideforge_metang", "tier": 2},
        {"pokemon": "tideforge_metagross", "tier": 3}]


def cobblemon_species(jar: Path) -> dict:
    """Every species Cobblemon ships, keyed the way Cobblemon keys them: by bare
    filename, directories discarded (JsonDataRegistry.reload)."""
    out = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith("data/cobblemon/species/") and name.endswith(".json"):
                out[Path(name).stem] = json.loads(z.read(name).decode("utf-8-sig"))
    return out


def aquatic(behaviour: dict) -> dict:
    """The Beldum line avoids water. A Water type must not."""
    b = json.loads(json.dumps(behaviour))
    b.setdefault("moving", {})["swim"] = {
        "avoidsWater": False, "canBreatheUnderwater": True, "swimSpeed": "0.2",
    }
    if "toleratedLeaders" in b.get("herd", {}):
        b["herd"]["toleratedLeaders"] = json.loads(json.dumps(HERD))
    return b


def build_species(vanilla: dict) -> dict:
    built = {}
    for sid, base_name, donor_name, dex, evo in CHAIN:
        base, donor = vanilla[base_name], vanilla[donor_name]
        s = json.loads(json.dumps(base))

        s["name"] = DISPLAY[sid]
        s["nationalPokedexNumber"] = dex
        s["primaryType"], s["secondaryType"] = "water", "steel"
        s["abilities"] = ["clearbody", "h:silentrunning"]
        s["labels"] = [l for l in base.get("labels", []) if l != "gen3"] + ["gen3", "tideforge"]
        s["pokedex"] = ["cobblemon.species.%s.desc" % sid]
        s["moves"] = list(donor["moves"])
        s["behaviour"] = aquatic(base["behaviour"])
        s.pop("forms", None)          # no Mega Tideforge Metagross
        s.pop("features", None)

        if sid in PRE_EVOLUTION:
            s["preEvolution"] = PRE_EVOLUTION[sid]
        else:
            s.pop("preEvolution", None)

        if evo:
            result, level, learnable = evo
            s["evolutions"] = [{
                "id": "%s_%s" % (sid, result), "variant": "level_up", "result": result,
                "consumeHeldItem": False, "learnableMoves": learnable,
                "requirements": [{"variant": "level", "minLevel": level}],
            }]
        else:
            s["evolutions"] = []
        built[sid] = s
    return built


SILENT_RUNNING = r'''{
  name: "Silent Running",

  // Enters battle Submerged. Status moves keep it; the first damaging hit it
  // takes, or the first damaging move it uses, surfaces it.
  onStart(pokemon) {
    pokemon.tideforgeSubmerged = true;
    this.add("-message", pokemon.name + " slipped beneath the surface!");
  },

  // Switching out resets the ability; onStart submerges it again on the way back in.
  onSwitchOut(pokemon) {
    pokemon.tideforgeSubmerged = false;
  },
  onEnd(pokemon) {
    pokemon.tideforgeSubmerged = false;
  },

  // Absorb the hit: the first damaging hit taken while Submerged lands 25% lighter.
  // The handler lives on the defender, the same way Multiscale's does.
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

  // Or launch the torpedo: strike first and the Water move hits 30% harder.
  // Surfacing happens here rather than in a later hook so the boost above is
  // still applied to the very move that spends it.
  onBasePower(basePower, attacker, defender, move) {
    if (!attacker.tideforgeSubmerged) return;
    var torpedo = move.type === "Water";
    attacker.tideforgeSubmerged = false;
    this.add("-message", attacker.name + " broke the surface!");
    if (torpedo) return this.chainModify([5325, 4096]);   // 1.3x, the Sheer Force ratio
  },
  // Fixed-damage moves (Seismic Toss, Night Shade) never reach the base power
  // chain, so they surface it here instead.
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
    "cobblemon.species.tideforge_beldum.name": "Tideforge Beldum",
    "cobblemon.species.tideforge_beldum.desc":
        "Ballast floods its shell so it can sink out of sight. It hangs motionless on the "
        "seabed for days, tracking the magnetic wake of anything that swims overhead.",
    "cobblemon.species.tideforge_metang.name": "Tideforge Metang",
    "cobblemon.species.tideforge_metang.desc":
        "Two Tideforge Beldum fused under pressure. The seam between them vents jets of "
        "water, letting it turn in place without disturbing the silt.",
    "cobblemon.species.tideforge_metagross.name": "Tideforge Metagross",
    "cobblemon.species.tideforge_metagross.desc":
        "Its four hulls close into a single ram. Sailors read the sudden calm above it as a "
        "warning, because the water only goes still once it has chosen a target.",
    "cobblemon.ability.silentrunning": "Silent Running",
    "cobblemon.ability.silentrunning.desc":
        "It slips beneath the surface, concealing its next attack.",
}

# Placeholder art: the server's remodel pack has no Tideforge Beldum model, and a
# species with no resolver renders as the substitute doll. Point it at the vanilla
# Beldum art until real art exists.
BELDUM_RESOLVER = {
    "species": "cobblemon:tideforge_beldum",
    "order": 0,
    "variations": [
        {"aspects": [], "poser": "cobblemon:beldum", "model": "cobblemon:beldum.geo",
         "texture": "cobblemon:textures/pokemon/0374_beldum/beldum.png", "layers": []},
        {"aspects": ["shiny"],
         "texture": "cobblemon:textures/pokemon/0374_beldum/beldum_shiny.png"},
    ],
}


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def dump(path: Path, obj) -> None:
    write(path, json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def validate(built: dict, vanilla: dict) -> list:
    problems = []
    legal_moves = {m for s in vanilla.values() for m in s["moves"]}
    used_dex = {s["nationalPokedexNumber"] for s in vanilla.values()}

    for sid, s in built.items():
        if sid in vanilla:
            problems.append("%s: id collides with a Cobblemon species -- Cobblemon keys species "
                            "by bare filename, so this would shadow it" % sid)
        if s["nationalPokedexNumber"] in used_dex:
            problems.append("%s: dex %d is already taken; speciesByDex holds one species per "
                            "(namespace, number)" % (sid, s["nationalPokedexNumber"]))
        for m in s["moves"]:
            if m not in legal_moves:
                problems.append("%s: move %r appears in no Cobblemon species" % (sid, m))
        for e in s["evolutions"]:
            if e["result"] not in built:
                problems.append("%s: evolves into %r, which this pack does not define"
                                % (sid, e["result"]))
        for a in s["abilities"]:
            bare = a[2:] if a.startswith("h:") else a
            if bare == "silentrunning":
                continue
            if not any(bare in v["abilities"] or ("h:" + bare) in v["abilities"]
                       for v in vanilla.values()):
                problems.append("%s: ability %r is not used by any Cobblemon species" % (sid, bare))
        if s.get("secondaryType") == s.get("primaryType"):
            problems.append("%s: primary and secondary type are the same" % sid)

    # the id Showdown computes from the script's name must match what the species ask for
    name = re.search(r'name:\s*"([^"]+)"', SILENT_RUNNING).group(1)
    showdown_id = re.sub(r"[^a-z0-9]", "", name.lower())
    if showdown_id != "silentrunning":
        problems.append("ability name %r gives Showdown id %r, but the species reference "
                        "'h:silentrunning'" % (name, showdown_id))
    for key in ("cobblemon.ability." + showdown_id, "cobblemon.ability." + showdown_id + ".desc"):
        if key not in LANG:
            problems.append("lang is missing " + key)
    for sid in built:
        for key in ("cobblemon.species.%s.name" % sid, "cobblemon.species.%s.desc" % sid):
            if key not in LANG:
                problems.append("lang is missing " + key)
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    ap.add_argument("--zip", action="store_true", help="also write the two distributable zips")
    args = ap.parse_args()

    if not args.jar.exists():
        print("Cobblemon jar not found: %s" % args.jar, file=sys.stderr)
        return 2

    vanilla = cobblemon_species(args.jar)
    built = build_species(vanilla)

    problems = validate(built, vanilla)
    if problems:
        print("VALIDATION FAILED")
        for p in problems:
            print("  -", p)
        return 1

    for d in (DP, RP):
        if d.exists():
            shutil.rmtree(d)

    dump(DP / "pack.mcmeta", {"pack": {
        "pack_format": DATA_PACK_FORMAT,
        "description": "Tideforge Beldum line - Water/Steel regional forms"}})
    for sid, s in built.items():
        dump(DP / "data/cobblemon/species/tideforge" / (sid + ".json"), s)
    write(DP / "data/tideforge/abilities/silentrunning.js", SILENT_RUNNING)

    dump(RP / "pack.mcmeta", {"pack": {
        "pack_format": RESOURCE_PACK_FORMAT,
        "description": "Tideforge Beldum line - names, dex entries, placeholder Beldum art"}})
    dump(RP / "assets/cobblemon/lang/en_us.json", LANG)
    dump(RP / "assets/cobblemon/bedrock/pokemon/resolvers/0374_tideforge_beldum"
         / "0_tideforge_beldum_base.json", BELDUM_RESOLVER)

    for sid, s in built.items():
        levels = [m for m in s["moves"] if m[0].isdigit()]
        if s["evolutions"]:
            evo = "%s @ %d" % (s["evolutions"][0]["result"],
                               s["evolutions"][0]["requirements"][0]["minLevel"])
        else:
            evo = "-"
        print("%-22s dex %-6d %s/%s  %3d moves (%d level-up)  -> %s"
              % (sid, s["nationalPokedexNumber"], s["primaryType"], s["secondaryType"],
                 len(s["moves"]), len(levels), evo))

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
