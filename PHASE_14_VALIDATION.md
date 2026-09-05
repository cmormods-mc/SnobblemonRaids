# Phase 14 Validation — Configurable Natural Wild Raid Spawning

Target: Cobblemon 1.7.3 + Minecraft 1.21.1/Fabric.
Reference JARs: supplied Cobblemon 1.7.3, CobbleBoss 6.0.0, Raid Dens 0.11.4.

## Result

`validation/validate_phase14.sh` completed successfully with all 11 gates passing.

## Gate 1 — Cobblemon physical spawn/entity API

Validated directly against the supplied Cobblemon JAR:
- `Pokemon.sendOut(...)`
- `PokemonEntity.setCountsTowardsSpawnCap(boolean)`
- `PokemonEntity.isBattling()`

The automatic scheduler still ends in the same real physical Cobblemon entity path established in Phase 13.

## Gate 2 — reference natural-spawn architecture

`PokemonBossSpawnSystem` in the supplied CobbleBoss 6.0.0 JAR was inspected with `javap` and confirms the broad proven pattern used here:
- periodic server-level spawn checks
- player sampling
- biome/time eligibility filtering
- weighted boss selection
- delegated spawn-position finding
- physical entity spawning

CobbleRaids implements its own scheduler and types; no CobbleBoss classes are imported at runtime.

## Gate 3 — time buckets

CobbleBoss `TimeUtils.getCurrentSpawnTime(...)` bytecode was inspected and the exact boundaries were verified:
- 0–2999 early_morning
- 3000–5999 morning
- 6000–11999 noon
- 12000–14999 afternoon
- 15000–17999 dusk
- 18000–20999 night
- 21000–23999 midnight

CobbleRaids implements those same understood periods plus `all_day`.

## Gate 4 — global config and raid JSON sanity

`examples/server.json` and `example_garchomp.json` are parsed during validation.

Validated defaults include:
- 1200-tick / 60-second global check interval
- 0.25 global attempt chance
- server active cap 3
- dimension active cap 2
- spawn ring 24–64 blocks from sampled player
- 128-block minimum between natural raids
- 600-second default unattended despawn
- 1800-second default definition cooldown
- recruitment default 45 seconds / 10 blocks / max four humans

The values are defaults only and are intended to be changed by the server owner.

## Gate 5 — external config path and reload

Source validation confirms configuration is read from Fabric Loader's config directory at:

```text
config/cobbleraids/server.json
```

`START_DATA_PACK_RELOAD` reloads this config before raid definitions are prepared. Therefore `/reload` applies new global scheduler values and new inherited defaults in the same reload cycle.

## Gate 6 — datapack path correction

A prior checkpoint inherited an inconsistent resource-prefix assumption. Phase 14 corrects the loader to the actual packaged layout:

```text
data/cobbleraids/raids/*.json
```

The resource manager searches prefix `raids` and explicitly restricts the namespace to `cobbleraids`, avoiding accidental parsing of another mod's unrelated `data/<namespace>/raids` directory.

## Gate 7 — scheduler safety

Static source validation requires all of these controls to be wired:
- global active cap
- per-dimension active cap
- minimum distance between raids
- per-definition cooldown
- per-definition concurrent cap
- idle despawn
- lobby protection
- active-battle protection
- startup stale-natural-boss purge
- shutdown cleanup

## Gate 8 — loaded-chunk location discipline

The position finder is source-checked so `ServerLevel.hasChunkAt(...)` occurs before the heightmap query. This prevents the natural raid scheduler from intentionally loading new chunks solely to search for a boss location.

Candidate surface checks also require:
- world-border containment
- solid collision ground
- clear feet and head collision spaces
- no fluid in feet/head spaces

Minecraft 1.21.1 Mojang mappings were separately checked for the used `getHeight`, `getWorldBorder`, block collision/fluid, `getDayTime`, and entity-distance APIs.

## Gate 9 — opt-in natural spawning and standalone boundary

A raid definition only enters automatic selection when `spawn.enabled` is explicitly true. Missing `spawn` blocks therefore remain manual/event-only definitions.

Source scan again confirms no runtime imports from CobbleBoss or Raid Dens.

## Gate 10 — complete Phase 13 regression

The entire Phase 13 validator is rerun unchanged inside Phase 14 validation.

Result remains pass for:
- 1..4 human dynamic side topology
- shared canonical boss HP
- explicit source-side contribution protocol
- real bootstrap loading
- controlled multi-winner terminal protocol

Natural spawning therefore did not regress the already-approved shared battle layer.

## Gate 11 — metadata dependency boundary

`fabric.mod.json` still requires Cobblemon 1.7.3 and does not require CobbleBoss or Raid Dens.

## Additional implementation corrections in Phase 14

1. Added server-owner `config/cobbleraids/server.json` with generated defaults.
2. Added `/reload`-cycle config refresh before datapack definition preparation.
3. Added per-definition natural spawn rules for enablement, weight, dimensions, exact biomes, biome tags, times, cooldown, idle despawn, and concurrent cap.
4. Added server-wide and per-dimension active raid caps.
5. Added a loaded-chunk land/surface position finder.
6. Added weighted selection across only environment-eligible definitions.
7. Added idle despawn that cannot fire during recruitment or battle.
8. Added clean-shutdown natural-boss cleanup.
9. Added startup stale-natural-boss purge for crash leftovers.
10. Corrected the datapack resource prefix to match `data/cobbleraids/raids/*.json`.
11. Preserved all Phase 13 battle regressions.

## What is NOT validated yet

- Full Loom compile against resolved Fabric/Minecraft dependencies.
- Dedicated Fabric server boot with the built mod.
- Real-world spawn density/balance under many concurrent players.
- Water-surface, underwater, cave, sky, or lava spawn-position strategies.
- Persistence of cooldown/active natural-raid state across a server restart.
- Concrete reward delivery.
- Combat time-limit enforcement.
- Hard enforcement of `allow_flee=false`.

Those remain explicit future gates.
