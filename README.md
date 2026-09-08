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
| SkiesGUIs | exactly 1.8.1 |

`CobbleRaids-BiomeCompat` is an optional data-only JAR. CobbleBoss and Raid Dens are
reference implementations, not dependencies.

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
- **Attempts** — a defeated party leaves the boss standing and healed. After
  `combat_defaults.max_failed_attempts` defeats (default 3, `0` = unlimited) it
  departs. A surviving boss keeps its raid slot and despawn timers.

## Commands

`/cobbleraids reward claim` is player-facing; `info` is unrestricted. Everything else
requires permission level 2.

```text
/cobbleraids list | info <species> | spawninfo
/cobbleraids spawn <species> [x y z] | testwild <species>
/cobbleraids despawn [all] | reload
/cobbleraids cooldown list | cooldown reset <definition>
/cobbleraids reward claim | reward grant <player> <definition> | reward list [player] | reward clear <player>
/cobbleraids debug status | raids | history | config | definition <species>
```

`testwild` bypasses the spawn roll and species cooldown while keeping placement,
biome checks, tracking, announcements and active caps.

## Configuration

`config/cobbleraids/server.json` is created on first run and migrated forward on
later starts, so new settings appear with their defaults instead of silently
missing. `/cobbleraids debug config` prints the active values.

## Build

```text
gradle --no-daemon clean build
```

Outputs both JARs to `build/libs`, and runs the unit suite covering the spawn-rate
maths, contribution maths, config round-trip and the Showdown file patcher.

## Validation

```text
validation/validate_phase31.sh
python3 validation/validate_phase{32,36,37,38,39,40}.py [jar]
```

Structural checks over the 130 definitions, tier membership, biome-compat separation,
optional-mod manifests, the mixin registry and the shared-HP packet path. They run in
CI against both the source tree and the built JAR.
