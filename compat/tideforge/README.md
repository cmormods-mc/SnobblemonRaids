# Tideforge forms of the Beldum line

Water/Steel regional forms of Beldum, Metang and Metagross, reached through the aspect
**`tideforge`**, plus the custom ability **Silent Running**.

Build and validate with `python build.py --zip`. Nothing here is hand-written data —
the forms are derived from Cobblemon's own files, so a move name or growth rate cannot
drift from the game's.

| form | types | evolves | moves from | stats from |
|---|---|---|---|---|
| `beldum tideforge` | Water/Steel | lv **16** -> `metang tideforge` (learns Metal Claw) | Piplup | Beldum, inherited |
| `metang tideforge` | Water/Steel | lv **36** -> `metagross tideforge` (learns Aqua Jet) | Prinplup | Metang, inherited |
| `metagross tideforge` | Water/Steel | - | Empoleon | Metagross, inherited |

Evolution levels are Piplup's (16 / 36), not the Beldum line's (20 / 45).

    /pokegive beldum tideforge
    /pokegive metagross tideforge level=50 shiny
    /pokegiveother <player> metang tideforge

## Why forms and not new species

The first cut of this pack defined `tideforge_beldum` / `tideforge_metang` /
`tideforge_metagross` as three new species. That is how the server's existing remodel
content does it too, and it is why `/pc` crashed a client on 2026-09-13:

    PokemonSpecies.getByIdentifier(...) -> null
    Intrinsics.checkNotNull(...)        -> NPE   (PokemonGuiUtilsKt.drawProfilePokemon:99)

A species that lives only in a server datapack has to survive a registry sync to reach
clients. That server loads 1027 species while its clients' own files define 1025, and
the two extras were exactly `tideforge_metang` and `tideforge_metagross` — so anything
rendering one client-side had nothing to look up.

An **aspect** adds no species id. `metang tideforge` is still `cobblemon:metang`, which
every client already has, so there is nothing that can go missing. It is also how
Cobblemon ships its own regional variants: Alolan Raichu is `raichu` with the `alolan`
aspect, defined by a `flag` species feature exactly like `tideforge.json` here.

Trade-off: like Alolan Raichu, these display under the base species name. A Tideforge
Metang reads as "Metang" in the party and PC; the form name appears in the Pokedex.

## What is in the two packs

**Datapack** (server) — `world/datapacks/` or `global_packs/required_data/`:

- `species_features/tideforge.json` — the aspect, a `flag` feature, default off
- `species_feature_assignments/regional_tideforge.json` — grants it to the three species
- `species_additions/tideforge/{beldum,metang,metagross}.json` — adds the form to each,
  merging into Cobblemon's species rather than replacing the file
- `abilities/silentrunning.js` — the ability, shipped into Showdown

**Resource pack** — `global_packs/required_resources/` so every client gets it:

- resolvers binding the `tideforge` aspect to the remodel pack's existing Tideforge art
- the three Pokedex entries and the ability's name and description
- **the animation key fix** (see below)

Restart the server; Cobblemon does not reload species (*"data registries are only loaded
once per server instance"*).

## The animation key fix, folded in

Cobblemon keys animation groups by **bare filename, directories discarded**. The remodel
pack ships

    animations/0376_tideforge_metagross/metagross.animation.json

which registers as `metagross` and evicts Cobblemon's own — that is what crashed clients
rendering a party Metagross on 2026-09-13. It also means the group `tideforge_metagross`,
which the Tideforge Metagross poser asks for, was never registered at all: the art could
not have worked even once.

This resource pack re-files both under `animations/zz_animation_key_fix/`, a directory
that sorts after every `0*` one, so Cobblemon's loader writes them last and they win. It
supersedes the standalone `Animation-Key-Fix.zip` — **delete that local pack once this
one is installed.** (It also restores `dewott_hisui_bias`, shadowed the same way.)

The real fix still belongs upstream: the pack author should rename that one file to
`tideforge_metagross.animation.json`, the way its own sibling `tideforge_metang.animation.json`
already is.

## Silent Running

Cobblemon 1.7 loads custom abilities from `data/<namespace>/abilities/*.js` and ships
them into its Showdown process (`Abilities.reload` -> `ShowdownService.sendRegistryData`),
so this is a real battle ability, not a label. `cobblemondungeon` ships its boss
abilities the same way. Showdown derives the id from the script's `name`, so
**"Silent Running" becomes `silentrunning`**, which the forms reference as
`h:silentrunning`; `build.py` checks those two agree.

| brief | handler |
|---|---|
| Submerged on entering battle | `onStart` |
| first damaging hit taken lands 25% lighter, then surfaces | `onSourceModifyDamage` (the Multiscale pattern) + `onDamagingHit` |
| attack first and the first Water move gains 30% | `onBasePower`, `chainModify([5325, 4096])` — the Sheer Force ratio |
| any damaging move surfaces it; status moves do not | `onBasePower`, plus `onModifyMove` for fixed-damage moves that never reach the base-power chain |
| switching out resets it | `onSwitchOut`, and `onStart` submerges it again on the way back in |

`flags: { breakable: 1 }` so Mold Breaker ignores the damage reduction, matching every
other defensive ability. Two deliberate readings of the brief: a multi-hit Water move is
boosted on its first hit only, and a non-Water damaging move surfaces it without a boost.

Every handler used here appears in Cobblemon's bundled `showdown.zip`. `onAfterMove` —
the obvious hook for "ends when it attacks" — appears there **zero** times, which is why
surfacing happens in `onBasePower` instead.

## Decisions worth knowing

- **Stats, EVs, catch rate, growth, egg group, drops and hitbox are inherited** from the
  base species. Only typing, moves, evolution levels and the hidden ability were
  specified, so the form overrides only those. That does mean Tideforge Metagross pairs
  Metagross's physical stats with Empoleon's special-attack movepool — say the word and
  I'll curate the movepool instead of copying it.
- **Normal ability stays `clearbody`**, the Beldum line's own.
- **Each form states its own `evolutions`.** A form inherits the species' evolutions if
  it does not, which would have evolved a Tideforge Beldum into a vanilla Metang.
  `build.py` fails the build if a form omits them or if a result drops the aspect.
- **Swim behaviour is rewritten.** The Beldum line is `avoidsWater: true`, wrong for a
  Water type; these get `canBreatheUnderwater`. Herd leaders point at the Tideforge line.
- Dex entries are written here and are placeholders — edit `LANG` in `build.py`.

## Before you install

- **Remove the server datapack that defines the `tideforge_metang` and
  `tideforge_metagross` species.** Otherwise both definitions exist and the old, broken
  ones stay reachable.
- **Any Tideforge Pokemon already in a PC or party is of the old species** and will keep
  crashing clients after the switch, because it still references `cobblemon:tideforge_metang`.
  Those have to be removed or rewritten server-side; the aspect change does not migrate
  them.
- The remodel pack's own `resolvers/0375_tideforge_metang/` and `0376_tideforge_metagross/`
  files become inert once those species are gone. Harmless, but they can be deleted.

## Still open

- **There is no Tideforge Beldum art.** The remodel pack ships models, textures and posers
  for Metang and Metagross only. The resolver here points the Beldum form at the *vanilla
  Beldum* art so it renders as something; it is a placeholder, not a recolour.
- **Silent Running has not been tested in a live battle.** Each handler is copied from a
  working ability, but the combination is new.
