# CobbleRaids

Cooperative wild raid bosses for **Cobblemon 1.7.3** on **Minecraft 1.21.1 / Fabric**.

Right-click a wild boss to open a recruitment window, up to four players join, and
one shared Showdown battle runs against a single boss HP pool with per-player
contribution tracking. Winning opens a reward screen; losing costs the party and
wears the boss down.

## Requirements

| | |
|---|---|
| Java | 21 |
| Fabric Loader | 0.17.2+ |
| Fabric API | 0.116.6+1.21.1 |
| Cobblemon | exactly 1.7.3 |

**Optional.** `SkiesGUIs` 1.8.1 gives reward claiming a chest GUI; without it the same
rewards are claimed from chat with `/cobbleraids reward claim`, and a SkiesGUIs that
fails to load degrades to that fallback rather than aborting server start.
`CobbleRaids-BiomeCompat` and `CobbleRaids-AddonRewards` are optional data-only JARs.

## Content

130 boss definitions: 27 starter, 10 powerhouse, 71 legendary, 22 mythical. Each is
tagged with one or two element-type biome tags, and each carries a fixed competitive
moveset.

The core ships vanilla biome mappings only. Installing `CobbleRaids-BiomeCompat` maps
the same 18 tags onto Fabric convention tags (Fire → `#c:is_hot/overworld`,
`#minecraft:is_badlands`, …), so any biome mod populating those conventions —
Terralith, Oh The Biomes We've Gone — extends the spawn pool automatically. Removing
the JAR returns spawning to vanilla biomes.

## Spawning

A rarity tier is chosen first, then a species within it.

- `tier_weights` is a **mix** selector, renormalised across tiers that currently have
  eligible species. Lowering one tier converts its spawns into other tiers; it does
  not produce fewer raids.
- `tier_spawn_chance` is the **rate** dial, rolled after the tier is chosen. A failed
  roll means no raid that attempt, never a re-roll into another tier, so tiers stay
  independent.

`/cobbleraids spawninfo` reports the effective per-attempt odds at your location,
including the chance of no spawn at all.

## Rewards and cost

Raids are fought on copies of the party, so the two directions are configured
separately.

- **Reward** — experience and EVs from a won raid are granted to the Pokémon that
  faced the boss and survived, using Cobblemon's own calculators. Withdrawing or
  disconnecting forfeits them.
- **Cost** — `battle_carryover` copies health and PP back to the real party (both on
  by default; `status` off). Faints carry with health and recover on Cobblemon's own
  faint timer. Applied on wins and losses alike, and not waived by disconnecting.
- **Catching** — off by default. `catching.enabled` plus a per-tier chance
  (`starter`/`powerhouse`/`legendary`/`mythical`, each `0.0`–`1.0`) rolls once per victor
  for a copy of the boss. Leaving every tier at `0.0` skips the roll entirely.
- **Attempts** — a defeated party leaves the boss standing and healed. After
  `combat_defaults.max_failed_attempts` defeats (default 3, `0` = unlimited) it
  departs. A surviving boss keeps its raid slot and despawn timers.

## Rewards from other mods

Reward items are plain registry ids, so any installed mod's items work with no code or bridge:

```json
"items": [{ "item": "create:brass_ingot", "amount": 4 }]
```

An item whose mod is not installed is skipped with a server-log warning and the rest of the reward
is still granted, so dropping a mod from the pack does not break existing raids.

For anything larger, name a **loot table** instead of listing items. That borrows whatever the other
mod already balanced, and keeps working when it changes its own drops:

```json
"loot_tables": ["minecraft:chests/buried_treasure"]
```

Loot tables can sit on the rewards block (rolled for every victor) or on a single choice (rolled
only if the player picks it). Preview any table the server has loaded, without granting it, with:

```text
/cobbleraids debug loot <loot_table>
```

That command tab-completes over every loot table on the server, including those from other mods,
which is the quickest way to find an id worth using.

`docs/reward-template.json` is a complete, copy-pasteable definition showing every reward form. It
is checked by CI against the parser, so it cannot drift out of date.

The optional `CobbleRaids-AddonRewards` JAR ships ready-made tables for common Cobblemon add-ons —
`cobbleraids:tier/{starter,powerhouse,legendary,mythical}` bundles, and one table per add-on
(`cobbleraids:addons/charms`, `addons/tms`, `addons/cards`, …) covering CobblemonCharms,
SimpleTMs, Cobblemon Cards, Perfect Partners, Mount Mastery, Daycare+, Cobble Capsule and
CobbleSafari. Each add-on has its own table, so a pack missing one loses that table and
nothing else.

## Commands

`/cobbleraids reward claim` is player-facing; `info` is unrestricted. Everything else
requires permission level 2.

```text
/cobbleraids list | info <species> | spawninfo
/cobbleraids spawn <species> [x y z] | testwild <species>
/cobbleraids despawn [all] | reload
/cobbleraids cooldown list | cooldown reset <definition>
/cobbleraids reward claim | reward grant <player> <definition> | reward list [player] | reward clear <player>
/cobbleraids debug status | raids | history | config | audit
/cobbleraids debug definition <species> | loot <loot_table> | record <player> | join <player>
```

`testwild` bypasses the spawn roll and species cooldown while keeping placement,
biome checks, tracking, announcements and active caps.

## Configuration

`config/cobbleraids/server.json` is created on first run and migrated forward on
later starts, so new settings appear with their defaults instead of silently
missing. `/cobbleraids debug config` prints the active values.

## Operations

The mod audits its own state every five minutes and on demand with
`/cobbleraids debug audit`, reporting stranded sessions, leaked raid slots, orphaned
scoreboard entries, lobbies without a boss and rewards naming an unknown definition. It
reports and never repairs, so a bug surfaces instead of being papered over. A clean server
prints nothing.

Every tick subsystem, Fabric callback and mixin injection runs inside a fault barrier, so a
failure is contained to the raid that caused it and logged (rate-limited) rather than
taking the server down. Because of that, **a broken build can look healthy from outside** —
grep the log for `[CobbleRaids]` before declaring a run good.

## Build

```text
gradle --no-daemon clean build
```

Outputs the mod JAR, a sources JAR and the two optional data JARs to `build/libs`, and runs
the 196-test unit suite over the Minecraft-free core: spawn-rate and contribution maths,
config round-trip, lobby recruitment and raid progress rules, fault containment, the
consistency audit's reporting rules, and the Showdown file patcher.

## Validation

```text
validation/validate_phase31.sh
python3 validation/validate_phase{32,36,37,38,39,40,41}.py [jar]
python3 validation/validate_{logging,callback_guards}.py
python3 validation/validate_mixin_guards.py [jar]      # after a build: reads bytecode
```

Structural checks over the 130 definitions, tier membership, biome-compat separation,
optional-mod manifests, the mixin registry, the shared-HP packet path, and the tier loot
table each definition rolls — plus three property checks that re-derive their subject from
the tree each run: every Fabric callback and every mixin injection is wrapped in a fault
barrier, and all logging goes through `RaidLog`. They run against both the source tree and
the built JAR.

The whole sequence — validators, `gradlew clean build` with the unit suite, then the
validators again over the produced JAR — is one script, which is exactly what
`.github/workflows/build.yml` does:

```text
bash validation/ci_local.sh          # ~40s
bash validation/hooks/install.sh     # once per clone: gate `git push` on it
```

The pre-push hook is the real gate right now: GitHub Actions is disabled account-wide for
this repo's owner, so no push has been checked remotely since 2026-09-10. Bypass a single
push with `git push --no-verify` or `SKIP_LOCAL_CI=1 git push`.

A live server harness covers what static checks cannot:

```text
python validation/smoke/smoke_test.py --server-dir <rig> --java <jdk21>/bin/java.exe
python validation/smoke/prove_audit_detects.py --server-dir <rig> --java <jdk21>/bin/java.exe
python validation/smoke/load_test.py --server-dir <rig> --java <jdk21>/bin/java.exe
```

`smoke_test.py` boots a server, drives server-authoritative checks over RCON and fails on
any contained fault in the log; `prove_audit_detects.py` injects a real inconsistency and
asserts the audit names it; `load_test.py` drives concurrent raids to exercise contention.
See `validation/README.md` for what belongs in a validator versus a unit test.

## License

MIT — see [LICENSE](LICENSE).
