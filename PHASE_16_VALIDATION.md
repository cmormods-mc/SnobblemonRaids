# Phase 16 Validation — Combat Rules, Withdrawal, and Time Limits

Target runtime: Minecraft 1.21.1/Fabric, Cobblemon 1.7.3, SkiesGUIs 1.8.1.
CobbleBoss 6.0.0 and Raid Dens 0.11.4 remain reference-only validation inputs.

## Validated findings

1. **Explicit player flee is intercepted at the correct Cobblemon boundary.** `BattleSelectActionsHandler.handle(...)` receives `BattleSelectActionsPacket` and calls `BattleActor.setActionResponses(...)`. `ForfeitActionResponse` is a real response type and serializes to `forfeit`. The raid mixin detects that response at handler HEAD, before stock Cobblemon can mutate the shared battle through normal forfeit handling.
2. **Native distance/entity flee is separate from explicit forfeit.** `PokemonBattle.checkFlee()` exists independently of the private `checkForfeit()` path. CobbleRaids cancels `checkFlee()` only when the battle is registered as a raid, leaving all normal Cobblemon battles unchanged.
3. **Cobblemon disconnects normally stop the whole battle.** `BattleRegistry.onPlayerDisconnect(ServerPlayer)` resolves the player's battle and calls `PokemonBattle.stop()`. CobbleRaids intercepts that method only for registered raids and converts the disconnect to a single-player withdrawal instead.
4. **Withdrawal preserves legitimate Pokemon battle state.** The raid-only `>raidleave pN` Showdown command marks that side inactive, stops it from blocking later turns, removes it from boss targeting, and does not zero HP or mark its Pokemon fainted.
5. **A withdrawn player is no longer reward eligible.** Their UUID is removed from `RaidSession.activeParticipants`; winner construction and reward contribution normalization both use the active snapshot.
6. **Withdrawn-player UI is isolated.** A `BattleEndPacket` closes their battle UI immediately, and subsequent `PlayerBattleActor.sendUpdate(...)` calls are suppressed for withdrawn raid participants while the actor remains owned by the shared battle until final cleanup.
7. **Time limit is deterministic.** `RaidCombatClock` is pure Java and validated at an exact 20 ticks = 1 second boundary. `0` means unlimited; expiry is edge-triggered once.
8. **Timeout is a raid defeat, not a victory.** The server-tick combat-rule service changes the raid to `FAILED`, ends the shared battle through normal Cobblemon cleanup, and never creates `RaidRewardEligibility.victory(...)`.
9. **Combat defaults are configurable.** `config/cobbleraids/server.json` now contains `combat_defaults.time_limit_seconds` and `combat_defaults.allow_flee`; individual raid JSONs may override either value.
10. **All prior regressions still pass.** The complete Phase 15 suite (SkiesGUIs rewards + contribution bonuses), Phase 14 natural spawning, and Phase 13 shared-combat/victory tests pass after these changes.

## Runtime semantics

### `allow_flee: false`

A player pressing the battle UI's flee/forfeit option is denied. CobbleRaids cancels the action before Cobblemon's stock forfeit path and resends the current action request so the player can choose normally.

### `allow_flee: true`

A player may withdraw, but this is not treated as a normal single-battle forfeit because that would end the shared battle. Instead:

```text
player chooses flee
  -> remove UUID from active raid participants
  -> close only that player's battle UI
  -> >raidleave pN
  -> side becomes inert in Showdown
  -> boss no longer targets that side
  -> player forfeits raid rewards
  -> shared raid continues for remaining participants
```

The withdrawn player's Cobblemon battle actor deliberately stays owned by the raid until the shared battle ends. This prevents the same party state from participating in two battles concurrently.

### Disconnect

A disconnect cannot be refused. For a raid it is treated like an allowed withdrawal regardless of `allow_flee`: the participant becomes inactive and reward-ineligible, while remaining participants continue. If nobody remains, the raid fails.

### Time limit

The timer begins when the `RaidSession` becomes ACTIVE. A limit of `0` disables the timer. At expiry, the raid fails and no reward GUI is queued.

## Validation command

Run:

```bash
validation/validate_phase16.sh
```

It executes ten gates, including exact Cobblemon 1.7.3 bytecode/API checks, a pure-Java timer test, the raid-withdrawal Showdown simulation against Cobblemon's exact bundled Showdown archive, metadata/config checks, and the entire Phase 15 regression suite.

## Remaining live-runtime gate

This source has not yet completed a full Fabric Loom build or live dedicated-server integration test. The next production gate is to compile the mod against the exact dependency set and run a real 2–4 client server test covering:

- SkiesGUIs opening after victory;
- simultaneous player choices;
- denied flee (`allow_flee=false`);
- allowed withdrawal (`allow_flee=true`);
- disconnect during move selection;
- timer expiry during a turn;
- clean battle/entity registry teardown;
- reward claim exactly once after a real victory.
