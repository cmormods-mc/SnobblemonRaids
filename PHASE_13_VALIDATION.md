# Phase 13 Validation — Wild Recruitment Framework

Target: Cobblemon 1.7.3 + Minecraft 1.21.1/Fabric.
Reference JARs: supplied Cobblemon 1.7.3, CobbleBoss 6.0.0, Raid Dens 0.11.4.

## Result

`validation/validate_phase13.sh` completed successfully with all 9 gates passing.

## Gate 1 — Cobblemon entity/battle API
Validated directly with `javap` against the supplied Cobblemon JAR:
- `Pokemon.sendOut(ServerLevel, Vec3, IllusionEffect, Function1<PokemonEntity, Unit>)`
- `PokemonEntity.setCountsTowardsSpawnCap(boolean)`
- `PokemonEntity.setBattleId(UUID)` / battle association
- entity-backed `PokemonBattleActor(UUID, BattlePokemon, float, BattleAI)`
- `BattleRegistry.startBattle(...)`

This supports the physical-world boss -> entity-backed battle actor design.

## Gate 2 — terminal cleanup ordering
`PokemonBattle.end()` bytecode contains:
1. `BattleEndPacket` dispatch
2. `BattleRegistry.closeBattle(this)`

`PokemonBattleActor.sendUpdate(...)` was separately inspected and clears its backing `PokemonEntity` battle ID when receiving `BattleEndPacket`.

Conclusion: CobbleRaids must not discard the wild boss before `PokemonBattle.end()` completes.

## Gate 3 — reference patterns
CobbleBoss `PokemonBossSpawner` bytecode calls Cobblemon `Pokemon.sendOut(...)`, validating normal-world physical boss spawning as an existing proven pattern.

Raid Dens `RaidDamageInstruction` bytecode, when canonical raid HP reaches zero, accesses the battle dispatch deque, clears it, and uses `dispatchToFront(...)`. CobbleRaids mirrors that ordering principle, but emits its own controlled `>raidwin` input rather than copying Raid Dens' battle implementation.

## Gate 4 — standalone boundary
Source scan confirms no runtime imports from:
- `com.cobbleboss...`
- `com.necro.raid...`

The obsolete Phase-10 abstract boss factory was removed. The only supported boss path is now the physical Cobblemon entity.

## Gate 5 — definition/dependency sanity
Example raid definition validates:
- 45-second recruitment
- 10-block eligibility radius
- 1..4 current participant safety cap
- positive base HP

`fabric.mod.json` requires Cobblemon 1.7.3 and does not require CobbleBoss or Raid Dens.

## Gate 6 — exact Showdown sandbox
The validator extracts `data/cobblemon/showdown.zip` directly from the supplied Cobblemon 1.7.3 JAR.

It requires exact single matches for both version-sensitive modifications:

```js
this.playerCount = this.gameType === "multi" || this.gameType === "freeforall" ? 4 : 2;
```

and:

```js
const BS = require('./sim/battle-stream');
```

If either signature changes, validation fails rather than applying a fuzzy patch.

## Gate 7 — real bootstrap path
A separate test loads the patched **Cobblemon `index.js`** first, matching GraalShowdownService's bootstrap path. It then creates a raid battle without manually requiring `raid-patch.js`.

Result:
- raid patch loaded
- 3 sides created for 2 humans + boss
- boss recognized both players as foes
- two `-raiddamage` records emitted
- boss simulator HP remained unchanged

This caught and corrected a real earlier flaw where `raid-patch.js` existed on disk but was never required by the runtime bootstrap.

## Gate 8 — dynamic shared-combat transport
Exact Cobblemon 1.7.3 simulator tests pass for all currently supported human counts:

| Humans | Total sides | Boss foes | Raid-damage events | Boss simulator HP |
|---:|---:|---:|---:|---:|
| 1 | 2 | 1 | 1 | unchanged |
| 2 | 3 | 2 | 2 | unchanged |
| 3 | 4 | 3 | 3 | unchanged |
| 4 | 5 | 4 | 4 | unchanged |

Every damage event also carries an explicit source side (`p1...pN`). This corrected another hidden issue: the Cobblemon split-parser actor identifies the split recipient, so it cannot safely be treated as the damage contributor.

## Gate 9 — controlled multi-winner victory
The exact simulator accepts raid-only input:

```text
>raidwin UUID1&UUID2&UUID3
```

and produces:

```text
|win|UUID1&UUID2&UUID3
```

with `battle.ended == true`.

Cobblemon's supplied `WinInstruction` bytecode was separately inspected: it splits the winner argument on `&`, resolves all actor UUIDs, sets winners/losers, calls `PokemonBattle.end()`, then emits `BATTLE_VICTORY`.

## Additional corrections made during Phase 13

1. Replaced fixed 4-player boss indexing with boss-last dynamic topology.
2. Corrected actor remapping so players are p1..pN and boss is p(N+1).
3. Added explicit `format.playerCount` injection and a version-gated Format constructor patch.
4. Added the missing runtime `index.js -> raid-patch.js` bootstrap hook.
5. Replaced invalid Java `|win|...` input with raid-only `>raidwin` input that emits normal Showdown output.
6. Wired canonical HP completion to `RaidLifecycleCoordinator.requestVictory(...)`; the old completion placeholder is gone.
7. Replaced direct battle registry closure on defeat with `PokemonBattle.end()` first, preserving entity cleanup order.
8. Added post-end physical boss discard.
9. Added explicit damage-source protocol so contribution can resolve the player actor rather than the split recipient.
10. Replaced Java `null` return from the Kotlin `sendOut` callback with `Unit.INSTANCE`.

## What is NOT validated yet

- Full Loom compile against resolved Fabric/Minecraft dependencies.
- Dedicated Fabric server boot with the resulting mod.
- Client battle UI behavior with all four players connected simultaneously.
- Disconnect/reconnect behavior under actual network conditions.
- Automatic natural-spawn scheduler (biome/dimension/rarity/cooldown selection).
- Concrete reward loot generation and inventory delivery.
- Enforcement of configured active-combat time limit and `allow_flee=false`.

Those remain explicit future validation gates; this checkpoint does not claim them as complete.
