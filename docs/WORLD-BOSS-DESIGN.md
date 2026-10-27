# World Boss (event system): design

Status: **proposal, 2026-10-25, nothing built.** Branch `world-boss`. First use: the Halloween event, 2026-10-31 to 2026-11-02 (see AscensionLib `docs/HALLOWEEN-EVENT-DESIGN.md`).

## 1. What it is

A **World Boss** is a server-wide event boss with **one shared health pool**. Every player fights their own **solo duel** (a 1-player raid against a private copy of the boss), and the damage
each duel deals is subtracted from the world pool as it happens. The pool empties, the World Boss is defeated, and everyone who contributed is rewarded.

It lives inside CobbleRaids but is a **separate system** from ordinary raids. Ordinary raids (natural spawns, lobby, 1-4 players, `RaidRewardService`) are unchanged and stay the default.
World Boss is off unless an operator or an event definition turns it on.

## 2. Language (to add to `CONTEXT.md` when built)

- **World Boss**: the event boss and its shared pool. Not a raid.
- **World Pool**: the shared health pool. Owned by the World Boss, not by any one battle.
- **Duel**: one player's solo battle against a private copy of the boss. A duel is an ordinary owned raid session (`RaidSession` with an `Ownership`), so the Showdown patches, boss AI and
  finalization guard are all reused unchanged.
- **Duel boss**: the private copy. Has its own small pool (the **duel pool**), separate from the World Pool.
- **Strike**: damage a duel dealt to its duel boss, as reported live; a Strike is what moves the World Pool.

## 3. How it fits what exists

| Existing piece | Use |
|---|---|
| `EncounterService.start(...)` | Starts every duel (1 player, caller-owned, no raid side effects). Already refuses a player already in a raid. |
| `RaidSession` / `RaidProgress` | The duel's own pool, contribution and terminal states. Unchanged. |
| `RaidDamageInstruction.applyToPool` | The **hook point (option B)**: right after `raid.damage(...)` the applied amount is reported once. This is the only edit to the battle path. |
| `EncounterListener` | Gets a new `default void onDamage(UUID encounterId, UUID playerId, float applied)` (no-op by default), so the public API stays version 1 and other owners are unaffected. |
| `RaidSpawnPositionFinder`, `RaidSpawnAnnouncementService` | Pick and announce the World Boss location, as for raids. |
| `PendingRewardStore` / `RaidRewardGrantEngine` | Persist and pay Raid Points and loot. The World Boss reward is a pending reward, so it survives a restart and a claim reuses the existing screen. |
| `SavedData` + `SavedDataFlush` | Persist the World Pool and contributions. |
| `RaidStats` | Optional: count World Boss wins separately, so ordinary leaderboards are not distorted. |

## 4. New pieces (package `com.cobbleraids.worldboss`)

1. **`WorldBossPool`** (pure, no Minecraft types, like `RaidProgress`): `max`, `current`, `contributions: Map<UUID, Float>`, `strike(player, amount) -> applied` (clamped to what is left),
   `isDefeated()`, and a one-way `defeated` flag. This is the unit-tested core.
2. **`WorldBossDefinition`** (datapack JSON in `data/cobbleraids/world_bosses/`): the raid definition it is built from (so `darkrai.json`, or a new `gengar.json`), event window
   (start and end instants, one configured time zone), pool formula, duel settings, reward, spawn rules, announcement text.
3. **`WorldBossState`** (`SavedData`, file `cobbleraids_world_boss`): active boss id, pool, contributions, status, the reward-paid flag.
4. **`WorldBossService`**: lifecycle (`start`, `end`, `tick` for the window), `joinDuel(player)`, and the `EncounterListener` that turns `onDamage` into `pool.strike` and `onEnded` into
   "duel finished, player may start another". It is the only caller of `EncounterService` for World Boss.
5. **`WorldBossPresentation`**: one shared boss bar (vanilla `ServerBossEvent`) for everyone who has joined or is within range, showing the World Pool and the top contributors.
6. **Commands** (under the existing `/cr` tree, operator-only for control): `worldboss start <definition>`, `stop`, `status`, `reset`; player: `worldboss join`.
7. **Public event for other mods**: `WorldBossEvents.DEFEATED(bossId, Map<UUID, Float> contributions)`, fired once, so AscensionLib can pay `spooky_essence` without Raids knowing about it.
   Same style as the existing encounter API: only `java.*`, `net.minecraft.*` and the API package in signatures.

## 5. Flow

1. **Start** (command, or scheduler when the window opens). The service picks a location, spawns a **showpiece** boss (an unbattled, unrecruitable, invulnerable entity marked as a world
   boss) and announces coordinates. The World Pool is created.
2. **Join.** A player right-clicks the showpiece or runs `worldboss join`. The service checks: event active, pool not empty, player not already in a duel or raid, per-duel cooldown elapsed.
3. **Duel.** `EncounterService.start` with one player, the world boss definition, `catchable=false`, `raidRewards=false`, `raidHistory=false`, a **duel pool** and a **time limit** from the
   definition. The duel plays as a normal raid (one player, one boss).
4. **Strike.** Each time the duel boss takes damage the new `onDamage` callback fires with the applied amount; the service calls `pool.strike(player, applied)` and updates the bar.
5. **Duel ends** (win, lose, time out, leave). The player may join again after the cooldown while the World Pool is not empty. Win or lose, damage already dealt stays counted.
6. **Pool reaches zero.** `defeated` flips once. In-flight duels are allowed to finish; their remaining Strikes are ignored (the pool is already zero) but still credited for rewards up
   to that instant. The showpiece is removed with an announcement.
7. **Rewards.** For each player at or above the contribution threshold: a pending reward (Raid Points, loot from the definition) plus the `DEFEATED` event for other mods. The
   `reward-paid` flag is saved before rewards are queued, and each player's pending reward is keyed by `(worldBossId, playerId)` so a retry cannot pay twice.
8. **Expiry.** If the window closes first, the boss leaves, nobody is paid the defeat reward, and a smaller consolation tier (optional) is paid by contribution.

## 6. Tuning knobs (all in the definition, none hard-coded)

- **World Pool size**: `base + per_active_player * players_in_event` where players_in_event is a count of distinct players who joined a duel in the last 15 minutes, re-evaluated when
  a duel starts and only ever allowed to move up (a pool never un-damages itself).
- **Duel pool and time limit**: small and short, so a duel is easy and fun.
- **Max Strike per duel**: `damage a duel can add to the World Pool` is capped by the duel pool itself (a duel boss at 0 HP ends the duel), so no cap logic is needed beyond the duel pool.
- **Cooldown between duels** (seconds), **contribution threshold** for the main reward (percent of the pool), **window start/end**, **spawn interval**.

## 7. Failure and restart behaviour

- **Server stops mid-event:** the pool is flushed on stop and periodically (every 5 seconds while dirty), so a restart resumes at most a few seconds of damage behind. Active duels do not
  survive a restart: the existing orphan-boss sweep removes their bosses (`EncounterService.onEntityLoaded`), and `onEnded` is not called, so the service starts clean from saved state.
- **Listener exceptions** cannot stop a duel (the API already contains them), and `WorldBossService` guards its own handlers with `RaidFaultBarrier`.
- **A player disconnects mid-duel:** handled by the existing reconnect grace; if they leave, the duel ends and the Strikes already made stay.
- **Time zone and clock:** one server clock, one configured zone.

## 8. Testing

- **Unit** (`src/test`, no Minecraft): `WorldBossPool` (clamp, ordering of strikes, defeated flag once, contributions sum to damage), window logic, reward eligibility threshold.
- **Showdown fuzz** (`validation/showdown_fuzz.js`): unaffected, because the battle path is unchanged except for a call after a pool change.
- **Bot test** (`validation/smoke/raidbot.js`): N bots (start with 12, then 30) all duelling, to measure server tick cost and confirm the pool reaches zero exactly once; then a restart mid-event.
- **Source validators** (`validation/ci_local.sh`): the new package must pass the API-boundary, callback-guard and logging checks that already run.

## 9. What the change to existing code is (so the review is small)

1. `EncounterListener`: one new default method.
2. `RaidDamageInstruction.applyToPool`: one call to report the applied Strike to the owner, when the session is owned.
3. `RaidSession.Ownership`: nothing new (the listener is already there).
4. Registration in `CobbleRaids.java` (commands, tick, `SERVER_STARTED` and `SERVER_STOPPED` hooks, as the other services do).
5. Everything else is new files.

## 10. Owner decisions, 2026-10-25

1. Spawn: **random announced location**, like raids.
2. Pool: **scales** with distinct active players.
3. Rewards: **flat above a small contribution threshold**.
4. **One World Boss at a time.** Joining and continuing duels works from anywhere **within a large radius** of the boss (radius is a definition setting; proposed 128 blocks, so a
   player can wander off to heal and come back).
5. Duels: damage counts whether the duel is won or lost. Re-entry: see section 11.

## 11. Re-entry: continuing to duel until the pool is empty

A player does not have to run a command per duel. Proposed behaviour, **Chain mode (on by default)**:

- After a duel ends, a short **breather** starts (default 10 seconds, so they can heal, swap Pokemon or drink a potion). A chat/HUD line shows the countdown and the pool.
- When it ends, the next duel starts **automatically** if the pool is not empty, the player is still within the radius, online, not in another battle or raid, and their party has a
  Pokemon able to fight. Otherwise the chain pauses with a message, and `worldboss join` resumes it.
- The player can stop chaining at any time (`worldboss leave`, or walking out of the radius). Leaving never loses damage already dealt.
- Chain mode ends for everybody when the pool hits zero or the event window closes.

Why a breather and not instant re-queue: it keeps a duel from feeling like one endless forced battle, gives a safe moment to switch Pokemon, and it bounds how fast one player can
hit the pool, which keeps tuning predictable. Provisional alternatives: manual re-join every time (least intrusive), or no breather (fastest, risks locking out players whose
Pokemon are fainted).

## 12. Final owner answers and implementation status (2026-10-25)

Answers: chain mode on with a 30 second breather, 45 seconds when every Pokemon has fainted; radius 128; pool lasts about one minute per player involved, capped at 20 minutes.

**Built on branch `world-boss` (uncommitted), all tests green (660, 40 of them new), all CI source validators pass:**

- `worldboss/WorldBossPool`, `WorldBossScaling`, `WorldBossRules`, `WorldBossConfig` (pure, tested).
- `worldboss/WorldBossState` (saved to `cobbleraids_world_boss.dat`), `WorldBossConfigManager`, `WorldBossService`, `WorldBossCommand`.
- `api/worldboss/CobbleRaidsWorldBoss` + `WorldBossListener` (public API for other mods).
- Edits to existing code: `EncounterListener.onDamage` (default method), `EncounterService.notifyDamage`, one call in `RaidDamageInstruction`, the right-click branch in `RaidBossInteractionListener`, registration in `CobbleRaids.java` and `RaidCommands`, and the command-gating test (four new player commands, operator commands stay hidden).

**Where the build differs from the sections above:**

- Settings are `config/cobbleraids/world_boss.json` (created on first run, **`enabled: false`**), not a datapack folder. `/cr worldboss reload` and `/reload` re-read it.
- Added `spawn.interval_minutes` (gap between bosses in the window) and `spawn.lifetime_minutes` (how long one boss stays), so the event can recur through the weekend.
- Duels keep health and PP (`withCarryover(true, true)`): fainted Pokemon stay fainted, which is what makes the 45 second breather meaningful. The duel time limit is enforced by the service.
- Rewards: Raid Points are paid at most once (flag saved first); `WorldBossListener.onDefeated` is at least once (flag saved after), so other mods key on the boss id.
- After a defeat, duels already running are allowed to finish; their hits no longer count (the pool is empty).

**Not done yet:**

1. A live run: nothing has been started on a real server. The 12-then-30 bot test, a restart mid-event, and a look at the boss bar and chat are all still to do.
2. ~~Ghost raid definitions~~ Done 2026-10-25: `gengar.json` and `chandelure.json` added (event-only: `spawn.enabled=false`, so they never appear as natural raids); Marshadow, Giratina and Darkrai already existed. More Ghosts (for example Mimikyu) are one JSON file each.
3. AscensionLib side: `spooky_essence`, the Haunting rolls, and a listener that pays essence from `onDefeated`.
4. Measuring "damage per player-minute" with bots and setting `pool.damage_per_player_minute`; the shipped 6000 is a guess.

## 12b. Starting a boss from another mod (0.8.191)

`CobbleRaidsWorldBoss.start(MinecraftServer, ServerPlayer near)` (server thread) starts a World Boss exactly like `/cr worldboss start`: the window and
night-only are not asked, the system must be `enabled`, and only one boss runs at a time. It returns `null` on success or a short reason.
`CobbleRaidsWorldBoss.isEnabled()` says whether the system is on. Both are compatible additions to API version 1.

`spawn.auto_start` (default `true`, unchanged behaviour) turns the schedule off when `false`: the boss then appears only by command or from another
mod. The Spooky Surge uses this: its top tier summons the boss around its top contributor, and a server that wants the boss only through the
surge sets `"auto_start": false`. Verified live with the Spooky Surge: tier 4 started a boss; with the system disabled the call was refused with
the reason; the boss bar shows only to players within `radius` of the boss (or in a duel), as before.

## 13. Decisions still needed

1. Which Ghost bosses to add as raid definitions (Gengar, Mimikyu, Gholdengo, others)?
2. Event window: the shipped example is empty (command-only). For Halloween set `window.start` to `2026-10-31T00:00:00Z` and `window.end` to `2026-11-03T00:00:00Z` (or your server time zone), plus `enabled: true`.

## Live bot test (2026-10-25, throwaway server on 25601, Cobblemon 1.8.2, never the owner's server)

Rig: `L:\claude-cobbleraids-work\testserver-integration` with the new CobbleRaids jar (0.8.189 + World Boss) and AscensionLib jar, natural raids off, `world_boss.json` enabled with test-scale numbers (pool scaled to 0.25 minute per player, duel boss 1500 HP, breathers 8/12 s). Bots are `raidbot.js` players, each with one level-100 Glaceon, healed by `pokeheal` every 10 s to stand in for a Pokemon Center. The stock bot never answers the server's Polymer resource pack, so it hangs in the configuration phase; a patched copy answers it (kept outside the repo).

| Run | Result |
|---|---|
| **12 bots** | All 12 in. 12 duels ran at once. Boss (Gengar, then Darkrai on a later start) **defeated once**, in 460 s with 50 battles and 1132 moves sent. 12 players paid 50 Raid Points (config value) and **2 Spooky Essence** each, once. Tick time 19.6 ms average (18.5 ms with the same 12 bots idle, 0.4 ms with nobody). |
| **20 bots** (30 requested; the test server's `max-players` is 20, the rest were refused) | 20 duels at once, 183 battles, defeated in 762 s, 20 players paid, **no errors**. Tick time 23.5 ms average, 39.8 ms worst. Every bot had a Haunting rolled first; the Showdown module logged 248 "Applied ascension effects" lines (50 for two Pokemon) in the duels, three Rare announcements fired, no errors. |
| **Restart mid-event** | 6 bots, boss at 2148/10800 with 6 duels running, graceful `stop` with only bots online. On restart the log said `Resuming World Boss ... pool 2148/10800`, `/cr worldboss status` showed the **same pool and the same 6 damage records**, the showpiece boss was recreated (1 entity), and 4 bots finished it: 6 players paid, 4 online told. |

**What the first runs showed.** With one Pokemon each, the bots lost most duels (12 of 15 in the first run) and fainted Pokemon stay fainted (health carries over), so the chain correctly paused with "none of your Pokemon can fight" and the pool stalled at 66% until the test healed them. That is the designed behaviour; it means players need a second Pokemon or a heal between duels. The breather messages were not checked from the player's side.

**Noise in the log, not from this code.** Cobblemon's own `BattleSelectActionsHandler` throws a `NullPointerException` when a bot answers a battle prompt just after its duel ended (12 of them in the 12-bot run). The stack has no CobbleRaids or AscensionLib frames. A real client would not answer a finished battle.

**Still not checked.** Numbers at event scale (a real player's damage per minute, so `pool.damage_per_player_minute` is still a guess; the 6000 default was not used in these runs), more than 20 bots (raise `max-players`), a real client (right-click on the showpiece, the boss bar, the action-bar countdown, chat text), the Spooky Essence item in an inventory, and whether the Haunting effects feel right to a player.

## Zone wall (2026-10-25, uncommitted)

The zone a duel needs (`radius`, default 128 blocks round the boss) is drawn as a **wall of soul particles** so players can see where it starts and walk in. Only a player within `visual.view_distance` blocks (default 40, 16 to 96) of the wall is sent it, and only the stretch within that distance: soul fire flames and rising souls on alternating passes every 8 ticks, one packet per column, columns 4 blocks apart on a fixed angle grid (so the wall does not shimmer as someone walks along it), at most 48 columns per player per pass. The height follows the player (about 5 blocks below to 13 above), so it is visible on hills and in caves. Crossing the wall shows an action-bar line once, "You entered the World Boss zone" or "You left the World Boss zone". `visual.border_particles: false` turns it off. The wall is exactly the duel range test, so it is walking in that makes a duel possible.

Pure geometry in `WorldBossBorder` (11 tests); the drawing is `WorldBossService.drawBorder`. **Not yet seen in a client**: the particle choice, density and height are a first guess to be judged by eye. A live check can count the `world_particles` packets a bot receives at the wall (many) against one at the centre (none).
