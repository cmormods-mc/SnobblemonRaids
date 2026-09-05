# CobbleRaids / SnobblemonRaids

CobbleRaids is a cooperative wild-boss raid framework for **Cobblemon 1.7.3** on
**Minecraft 1.21.1 / Fabric**. This repository now contains the complete buildable
Phase 31 source instead of only the historical CI reconstruction bundle.

## Current release

- Core: `CobbleRaids-0.8.15-phase31-spawn-director.jar`
- Optional compatibility: `CobbleRaids-BWG-Compat-1.0.0.jar`
- Java: 21
- Fabric Loader: 0.17.2 or newer
- Fabric API: 0.116.6+1.21.1 or newer
- Cobblemon: exactly 1.7.3
- SkiesGUIs: exactly 1.8.1

CobbleBoss and Raid Dens are reference implementations only and are not runtime
dependencies.

## Included raid content

The core contains 130 unique boss definitions derived from
`cobblemon_boss_pack_v2.zip`:

- 27 starter bosses
- 10 powerhouse bosses
- 71 legendary bosses
- 22 mythical bosses

Every boss uses one or two element-type biome tags. The core supplies only vanilla
Minecraft biomes. Installing the optional BWG compatibility JAR extends those same
18 tags with validated **Oh The Biomes We've Gone 2.6.0** biomes. Removing the
compatibility JAR safely returns the pool to vanilla-only spawning.

## Spawn director

Natural spawning selects a rarity tier before selecting an eligible species. The
default aggregate tier weights are:

```json
"tier_weights": {
  "starter": 70,
  "powerhouse": 20,
  "legendary": 8,
  "mythical": 2
}
```

Weights automatically renormalize when no species from a tier can spawn in the
current biome, dimension, or time. A definition's `spawn.weight` only determines
the species selected inside its chosen tier.

When a natural boss appears, all online players receive its translated species
name, rarity tier, biome, dimension, and a coordinate hint rounded to the nearest
100 blocks.

## Encounter behavior

- Right-click a wild boss to begin the recruitment window.
- Nearby players explicitly join within the configured radius and duration.
- One shared Cobblemon/Showdown battle starts for up to four participants.
- The boss uses one canonical shared HP pool with contribution tracking.
- Fully eliminated or disconnected players withdraw without freezing the raid.
- Boss attacks, player attacks, boss healing, and every participant's health bar
  remain synchronized.
- Victory opens a one-time, server-authoritative SkiesGUIs reward choice with
  contribution bonus rolls.

## Commands

All administrative commands require permission level 2.

```text
/cobbleraids list
/cobbleraids spawn <species> [x y z]
/cobbleraids spawninfo
/cobbleraids testwild <species>
/cobbleraids despawn
/cobbleraids despawn all
/cobbleraids debug status
/cobbleraids debug raids
```

`spawninfo` reports eligible bosses and renormalized tier odds at the player's
current location. `testwild` bypasses the random chance and existing species
cooldown while retaining natural placement, biome checks, tracking, announcements,
and active caps.

## Build

```text
gradle --no-daemon clean build
```

Build outputs are written to `build/libs`. The normal `build` task produces both
the remapped core JAR and the data-only BWG compatibility JAR.

## Validation

```text
validation/validate_phase31.sh
```

The validator checks all 130 definitions, exact tier membership, vanilla/BWG tag
separation, optional-mod manifests, the raid mixin registry, the shared-HP packet
path, and the tier-selection math. GitHub Actions additionally performs the real
Java 21 Fabric Loom build.
