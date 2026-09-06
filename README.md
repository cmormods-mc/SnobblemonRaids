# CobbleRaids / SnobblemonRaids

CobbleRaids is a cooperative wild-boss raid framework for **Cobblemon 1.7.3** on
**Minecraft 1.21.1 / Fabric**. This repository now contains the complete buildable
Phase 35 source instead of only the historical CI reconstruction bundle.

## Current release

- Core: `CobbleRaids-0.8.20-phase35-biome-compat.jar`
- Optional compatibility: `CobbleRaids-BiomeCompat-1.0.0.jar`
- Java: 21
- Fabric Loader: 0.17.2 or newer
- Fabric API: 0.116.6+1.21.1 or newer
- Cobblemon: exactly 1.7.3
- SkiesGUIs: exactly 1.8.1

CobbleBoss and Raid Dens are reference implementations only and are not runtime
dependencies.

### Recent changes (Phase 35)

- **4th raid player silently dropped from battle** — fixed a mismatch inside
  Cobblemon itself between its PNX letter generator (supports up to 6 active
  slots per battle side) and its PNX validation regex (only accepted `a`–`c`),
  which only surfaces on a shared raid side with 4+ players. A targeted mixin
  widens the validator to `a`–`f` so a 4th player's Pokemon no longer throws
  `InvalidInstructionException` on every referencing instruction.
- **Boss faints outside the tracked HP pool never finalized the raid** —
  abilities like Perish Body (and other non-`-raiddamage` faint paths) could
  faint the boss while its virtual HP pool was still nonzero, leaving the raid
  session hanging with no reward GUI. Victory/defeat handling now covers those
  previously-silent outcomes.
- **Move blacklist for non-HP-damage faints** — Explosion, Self-Destruct, Misty
  Explosion, Final Gambit, Memento, Healing Wish, Lunar Dance, Destiny Bond, and
  Perish Song are blocked for both players and the boss AI, since raid HP is
  tracked as a virtual pool that only the `-raiddamage`/`-raidheal` Showdown
  instructions update.
- **Biome compatibility generalized** — the former BWG-only compatibility JAR
  (`CobbleRaids-BWG-Compat`) is now `CobbleRaids-BiomeCompat`, driven by Fabric
  convention/vanilla biome-category tags instead of hardcoded biome IDs. Any
  biome mod that populates those tags (Terralith, Oh The Biomes We've Gone,
  etc.) now works without a dedicated compatibility JAR per mod.
- **SkiesGUIs reward-GUI crash fixed** — the reward-choice GUI's open/close
  messages now use `COMMAND_PLAYER` `tellraw` instead of SkiesGUIs' `MESSAGE`
  action type, avoiding a crash triggered by adventure-platform-fabric +
  CobblemonExtras being present together.

## Included raid content

The core contains 130 unique boss definitions derived from
`cobblemon_boss_pack_v2.zip`:

- 27 starter bosses
- 10 powerhouse bosses
- 71 legendary bosses
- 22 mythical bosses

Every boss uses one or two element-type biome tags. The core supplies only vanilla
Minecraft biomes. Installing the optional `CobbleRaids-BiomeCompat` JAR maps those
same 18 tags to Fabric convention/vanilla biome-category tags (e.g. Fire →
`#c:is_hot/overworld`, `#minecraft:is_badlands`, `#minecraft:is_savanna`), so any
installed biome mod that populates those conventions — Terralith, Oh The Biomes
We've Gone, or others — extends the eligible biome pool automatically. Removing
the compatibility JAR safely returns the pool to vanilla-only spawning.

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
the remapped core JAR and the data-only BiomeCompat JAR.

## Validation

```text
validation/validate_phase31.sh
```

The validator checks all 130 definitions, exact tier membership, vanilla/tag-based
biome-compat separation, optional-mod manifests, the raid mixin registry, the
shared-HP packet path, and the tier-selection math. GitHub Actions additionally
performs the real Java 21 Fabric Loom build.
