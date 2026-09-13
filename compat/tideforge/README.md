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

Server-side datapacks are the right way to add Pokemon, and they work: Cobblemon syncs
species, features and abilities to clients on join. Forms are still the better shape for
a regional variant, for the same reason Cobblemon uses them itself -- Alolan Raichu is
`raichu` with the `alolan` aspect, defined by a `flag` species feature exactly like
`tideforge.json` here. One species id, one Pokedex entry, and evolutions that carry the
aspect along.

Trade-off: like Alolan Raichu, these display under the base species name. A Tideforge
Metang reads as "Metang" in the party and PC; the form name appears in the Pokedex.

## What one client mod does to all of this

On this modpack the sync arrives and is then thrown away. Proved from the client log:

    16:48:17  [EMI] Starting EMI reload...                      [Thread-21]
    16:48:17  Loaded 23 fossils / 307 marks                     (the server's sync, applied)
    16:48:18  CMor3zombies joined the server!
    16:48:19  [EMI] Loading plugin from cobbleemi               [Thread-21]
    16:48:19  Reloading world spawn pool for EMI spawn info...  [Thread-21]
    16:48:19  Found new data pack cobblemon, loading it automatically ...
    16:48:22  Loaded 390 abilities                              (the server has 321)
    16:48:23  Loaded 1025 Pokemon species

**`cobbleemi`** builds its own pack repository on the client to read Cobblemon's spawn
pool for EMI, and that re-runs *every* Cobblemon data registry against the client's local
files -- overwriting the species, forms, features and abilities the server had just sent.
Anything that exists only on the server is gone one second after joining.

That is what crashed `/pc` on the old `tideforge_*` species, and what kicked a player
mid-evolution with `DecoderException: Failed to decode packet 'clientbound/minecraft:custom_payload'`
once the form and the custom ability were server-only.

**Fix it on the client, not in the datapack:** remove `cobbleemi` (1.1.4 has no config
switch -- the reload is unconditional), or have its author scope the reload to
`spawn_pool_world` instead of the whole registry set. Dropping this datapack into a
client's own `datapacks/` folder also works as a per-player workaround; that is how the
ability count above went from 389 to 390.

## What is in the two packs

**Datapack** (server) — `world/datapacks/` or `global_packs/required_data/`:

- `species_features/tideforge.json` — the aspect, a `flag` feature, default off
- `species_feature_assignments/regional_tideforge.json` — grants it to the three species
- `species_additions/tideforge/{beldum,metang,metagross}.json` — adds the form to each,
  merging into Cobblemon's species rather than replacing the file
- `abilities/silentrunning.js` — the ability, shipped into Showdown

**Resource pack** — **not** `global_packs/required_resources/`. That folder feeds the
local game's pack repository, and a dedicated server has no client-facing one, so nothing
there ever reaches a player. (globalpacks' `[datapacks]` section *is* server-side; its
`[resourcepacks]` section is not.)

What clients actually receive is `polymer/resource_pack.zip`, generated at boot and served
by polymer-autohost. Add this pack to that build, either way:

- list it in `include_zips` in the server's `config/polymer/resource-pack.json`, or
- unzip its `assets/` into `polymer/source_assets/` (or `polymer/override_assets/`)

then restart so Polymer regenerates. It contains:

- a language file: the three Pokedex entries plus the ability's name and description.
  Without it the summary screen shows raw keys (`cobblemon.ability.silentrunning`).
- three reframed posers, so the party portrait shows a head (see below).

It lives at `assets/**tideforge**/lang/en_us.json`, not under `assets/cobblemon/`, even
though every key is a `cobblemon.*` one. `ClientLanguage.loadFrom` walks **every**
namespace in the resource manager and merges all of their `lang/en_us.json` files into a
single map, so the keys resolve either way — but our own namespace cannot collide with the
remodel pack's `assets/cobblemon/lang/en_us.json` when Polymer merges the packs in
`required_resources/` into one zip.

Restart the server; Cobblemon does not reload species (*"data registries are only loaded
once per server instance"*).

## The remodel pack now covers the art

As of its 17:19 build the server's remodel pack ships its own `tideforge` aspect
resolvers for all three species -- including a Beldum, which it spells `tideforge_beldom`
-- and it has renamed `0376_tideforge_metagross/metagross.animation.json` to
`tideforge_metagross.animation.json`. That fixes the animation-key collision at source and
registers the group its own poser asks for, so **this pack no longer ships resolvers or an
animation fix**. Delete any local `Animation-Key-Fix.zip`.

One collision survives in that pack, dormant because no poser asks for the group:
`0502_dewott/dewott_hisui_bias.animation.json` keys as `dewott_hisui_bias` but contains
`animation.dewott.*`, shadowing Cobblemon's real one. Worth passing to the pack author.

## The portrait framing fix

The remodel pack's posers frame the portrait too far out and never shift it sideways, so
the party widget draws a small centred body instead of a head. Cobblemon's own numbers for
the same body plan are the right target:

| poser | remodel pack | Cobblemon's own |
|---|---|---|
| beldum | 1.9, `[0, 0.25, 0]` | **2.3, `[-0.3, -1.2, 0]`** |
| metang | 1.25, `[0, -0.05, 0]` | **1.8, `[-0.35, 0, 0]`** |
| metagross | 0.78, `[0, 0.1, 0]` | **1.1, `[-0.45, 0.279, 0]`** |

`build.py` takes each Tideforge poser out of the server pack, swaps in those two values and
re-files the copy under `posers/zz_tideforge_portrait_fix/`. Posers are keyed by bare
filename with directories discarded, and that directory sorts after `posers/t...`, so the
copy wins. Simulated against the real mod folder and the real server pack before shipping.

**This is a stopgap and it carries a stale-copy risk**: it is a whole copy of their poser,
so if they change one -- new poses, new animations -- our copy keeps winning with the old
content until `build.py` is re-run. The right fix is for the pack author to set those six
values in their own files, after which delete `posers/zz_tideforge_portrait_fix/` from
this pack.

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

**Never put a `//` comment in an ability script.** Cobblemon flattens the file to a
single line before handing it to Showdown, so a line comment swallows the rest of the
script and the server dies during data load:

    PolyglotException: SyntaxError: <eval>:1:2058 Expected ident but found eof

That is a failed boot, not a warning — it took this server down on 2026-09-13. Use
`/* ... */`, which survives flattening. `build.py` now rejects `//` outright and, when
`node` is on PATH, actually parses the flattened script the way Showdown will see it;
both checks were verified by reintroducing the comment.

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
