# Tideforge Beldum line

Three Water/Steel regional forms of the Beldum line, plus the custom ability
**Silent Running**, as a Cobblemon datapack and a companion resource pack.

Build and validate with `python build.py --zip`. Nothing here is hand-written data —
the species are derived from Cobblemon's own files, so a move name or growth rate
cannot drift from the game's.

| species id | dex | types | evolves | moves from | stats from |
|---|---|---|---|---|---|
| `tideforge_beldum` | 10374 | Water/Steel | lv **16** -> `tideforge_metang` (learns Metal Claw) | Piplup | Beldum |
| `tideforge_metang` | 10375 | Water/Steel | lv **36** -> `tideforge_metagross` (learns Aqua Jet) | Prinplup | Metang |
| `tideforge_metagross` | 10376 | Water/Steel | - | Empoleon | Metagross |

Evolution levels are Piplup's (16 / 36), not the Beldum line's (20 / 45).

## Installing

**Datapack** -> the server. Either `world/datapacks/` or, to push it with the rest of
the pack, `global_packs/required_data/`. It is server-side only; species sync to
clients at runtime.

**Resource pack** -> `global_packs/required_resources/` on the server so every client
gets it, or drop the zip in a client's own `resourcepacks/` folder. Without it the
three species show as raw lang keys and Tideforge Beldum renders as the substitute
doll.

Restart the server (Cobblemon does not reload species: *"Cobblemon data registries are
only loaded once per server instance as Pokemon species are not safe to reload"*).

Then:

    /pokegive tideforge_beldum
    /pokegiveother <player> tideforge_metagross level=50

## Silent Running

Cobblemon 1.7 loads custom abilities from `data/<namespace>/abilities/*.js` and ships
them into its Showdown process (`Abilities.reload` -> `ShowdownService.sendRegistryData`),
so this is a real battle ability, not a label. `cobblemondungeon` ships its boss
abilities the same way.

Showdown derives the ability id from the script's `name`, so **"Silent Running" becomes
`silentrunning`**, which is what the species reference as `h:silentrunning`. `build.py`
checks that those two agree.

| brief | handler |
|---|---|
| Submerged on entering battle | `onStart` |
| first damaging hit taken lands 25% lighter, then surfaces | `onSourceModifyDamage` (the Multiscale pattern) + `onDamagingHit` |
| attack first and the first Water move gains 30% | `onBasePower`, `chainModify([5325, 4096])` — the Sheer Force ratio |
| any damaging move surfaces it; status moves do not | `onBasePower`, plus `onModifyMove` for fixed-damage moves that never reach the base-power chain |
| switching out resets it | `onSwitchOut`, and `onStart` submerges it again on the way back in |

`flags: { breakable: 1 }` so Mold Breaker ignores the damage reduction, matching every
other defensive ability.

Two deliberate readings of the brief: a multi-hit Water move is boosted on its first
hit only, and a non-Water damaging move surfaces it without any boost.

## Decisions worth knowing

- **Stats, EVs, catch rate, growth, egg group, drops and hitbox are the Beldum line's.**
  Only typing, moves, evolution levels and the hidden ability were specified, so
  everything else is left alone. That does mean Tideforge Metagross has Metagross's
  physical stats with Empoleon's special-attack movepool — say the word and I'll
  curate the movepool instead of copying it.
- **Normal ability stays `clearbody`**, the Beldum line's own. Only the hidden ability
  was specified.
- **Dex numbers are 10374-10376, not 374-376.** Cobblemon keys `speciesByDex` on
  `(namespace, dex number)` and holds exactly one species per pair — all 1025 of its own
  species have distinct numbers. Reusing 374-376 would evict vanilla Beldum, Metang and
  Metagross from that table. Change the three numbers in `build.py` if you would rather
  have Pokedex adjacency and accept the shadowing.
- **Swim behaviour is rewritten.** The Beldum line is `avoidsWater: true`, which is
  wrong for a Water type; these get `canBreatheUnderwater` instead. Herd leaders point
  at this line rather than the vanilla one.
- No Mega form, and no `features` block, are carried over from Metagross.
- Dex entries are written here and are placeholders — edit `LANG` in `build.py`.

## Open items

- **There is no Tideforge Beldum art.** The server's remodel pack ships models,
  textures and posers for `tideforge_metang` and `tideforge_metagross` only. The
  resource pack here points Beldum at the *vanilla Beldum* art so it renders as
  something rather than a substitute doll; it is a placeholder, not a recolour.
- **The server may already define `tideforge_metang` / `tideforge_metagross`.** It loads
  1027 species against the 1025 its mods ship, which is consistent with those two
  already existing in a server-side datapack. If so, two definitions of the same id will
  collide and datapack order decides the winner. Check before installing.
- **Silent Running has not been tested in a live battle.** The handlers are each copied
  from a working ability in Cobblemon's bundled `showdown.zip`, but the combination is
  new.

## The landmine to remember

`JsonDataRegistry.reload` keys every species by **namespace + bare filename, discarding
directories** (`ResourceLocation.fromNamespaceAndPath(ns, File(path).nameWithoutExtension)`).
`data/cobblemon/species/generation3/beldum.json` is therefore `cobblemon:beldum`, and
our `species/tideforge/tideforge_beldum.json` is `cobblemon:tideforge_beldum`. A second
`beldum.json` anywhere under any `species/` folder would silently replace the real one —
which is exactly how a misnamed `metagross.animation.json` in a server pack crashed
clients on 2026-09-13. `build.py` fails the build if any id here collides with one of
Cobblemon's.
