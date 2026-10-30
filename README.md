# CobbleRaids

Cooperative wild raid bosses for **Cobblemon 1.8.2** on **Minecraft 1.21.1 / Fabric**.

Right-click a wild boss to open a recruitment window, up to four players join, and
one shared Showdown battle runs against a single boss HP pool with per-player
contribution tracking. Winning opens a reward page; losing costs the party and
wears the boss down.

## Requirements

| | |
|---|---|
| Java | 21 |
| Fabric Loader | 0.18.0+ (WebGUI needs it) |
| Fabric API | 0.116.6+1.21.1 |
| Cobblemon | 1.8.2+ |
| Polymer | 0.9.19+ (core, resource-pack, common, autohost) |
| WebGUI | 1.8.1+ (recommended; shows every screen) |

**CobbleRaids is server-side only.** Players install nothing from this mod. The raid keys, key
fragments, Raid Core and Guardian Scale are Polymer items: a player sees a vanilla item with a custom
model, supplied by a small resource pack that Polymer builds and sends. Set `"enabled": true` in
`config/polymer/auto-host.json` or players will see the plain vanilla items.

Every screen (shop, trophy room, Hall of Legends, reward reveal, capture minigame, hub and
leaderboards) is a WebGUI page, so players who want them need the WebGUI client mod. A player without
it can still claim rewards from chat with `/cobbleraids reward claim`.

### Upgrading from a version that needed a client mod

Earlier versions had to be installed on every player's machine. This one does not, and the old client
jar must be removed: a client that still has an old CobbleRaids jar registers the key, fragment, Raid
Core and Guardian Scale items itself, which conflicts with the server's versions of them.

1. Remove the old `CobbleRaids-*.jar` from `mods/` on the server and on every client.
2. On the server, add this jar plus Polymer (core, resource-pack, common, autohost) and, for the screens,
   WebGUI. Fabric Loader must be 0.18.0 or newer.
3. Set `"enabled": true` in `config/polymer/auto-host.json`, then restart. Players receive a small
   resource pack with the item art on their next join.
4. Tell players who want the shop, trophy room, Hall of Legends, reward reveal and capture minigame to
   install the WebGUI client mod. Players without it still get chat commands: `/cobbleraids shop` lists
   the shop and `/cobbleraids shop buy <id>` buys, `/cobbleraids reward claim` claims, and
   `/cobbleraids top` and `/cobbleraids stats` show leaderboards and records.

Saved data (raids, trophies, Raid Points, stats, defeated bosses) is read exactly as before.

Rewards are claimed on the web reveal page. A player whose client lacks WebGUI claims from chat with
`/cobbleraids reward claim`.
The reward loot tables are built in. Items from these Cobblemon add-ons are rewarded only when the
add-on is installed, and each is skipped silently when it is not: `mega_showdown`, `simpletms`,
`cobblemoncharms`, `cobblemon-cards`, `cobblecapsule`, `companion_bonds`, `daycareplus`,
`cobblesafari`, `ridetraining`.

## Content

132 boss definitions: 27 starter, 12 powerhouse, 71 legendary, 22 mythical. Each is
tagged with one or two element-type biome tags, and each carries a fixed competitive
moveset.

Each of the 18 type-based biome tags carries both a hardcoded vanilla biome mapping and
Fabric convention tags (Fire → `#c:is_hot/overworld`, `#minecraft:is_badlands`, …), so
any biome mod populating those conventions — Terralith, Oh The Biomes We've Gone —
extends the spawn pool automatically, with no hard dependency on either: a convention
tag nobody defines is simply an empty tag, not an error.

## Raid Radar (prototype)

Craft a radar with four Netherite Ingots (corners), two Shulker Shells (top/bottom),
two Cobblemon Upgrades (left/right) and a Compass (centre). Right-click opens a native
status menu with your raid charges, refill timers and an Open/Close Radar button;
sneak + use cycles available raids while the radar is open. Its north-up circular compass combines cyan
surface contours, your facing arrow, an orange raid marker and a dotted direct bearing.
It samples loaded chunks only and adds no client mod or custom shader. The stacked
terrain appearance is illustrated depth on a flat map. See
[controls, limits and validation](docs/RAID-RADAR-PROTOTYPE.md).

An optional [3D client companion](docs/RAID-RADAR-HOLOGRAM.md) renders a physical compass
with raised terrain in first person. Players without it keep the flat map; H toggles
the companion display. Server-side crafting and raid rules remain authoritative.

## Raid charges

Each player UUID has a pool of **3 raid charges**, restoring **1 per hour** until full.
Offline time counts. The first spent charge starts the hourly timer; further entries
keep that deadline, and time at full cannot be banked. One charge is committed when
an ordinary raid battle starts, win or lose. Joining/cancelling a lobby costs nothing;
a failed battle start refunds its reservation. Zero charges prevents joining ordinary
raid lobbies. Charges survive relogs, compass replacement and server restarts in
`<world>/data/cobbleraids_raid_charges.dat`. Externally owned encounters keep their own
entry rules. Spawn rates are unchanged.

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
- **Raid Capture Protocol** — on by default (`catching.enabled`). Each victor personally
  chooses, per raid: attempt a short timing minigame (three stabilization pulses, then a
  throw) for a chance at an ordinarily-generated Pokémon of the boss's species, or claim
  that raid's Raid Points outright. Failing, declining, or letting the choice window lapse
  all pay the same RP a claim would have; the only thing an attempt ever gives up is this
  one raid's RP, never an existing balance. Every tier
  (`starter`/`powerhouse`/`legendary`/`mythical` under `catching`) has its own `base_chance`
  (the floor for doing nothing in the minigame), `stabilization_cap`/`throw_cap` (the most
  each half of the minigame can add), and its own pulse/throw travel durations and zone
  widths — a higher tier can be tuned with a lower ceiling *and* a tighter, faster-moving
  target. The shipped defaults are 8/6/4/2% base chance and +2/+2/+1.5/+1% for each of
  stabilization and throw (starter through mythical), for ceilings of ~12/10/7/4% on a
  flawless attempt — sized against the RP a player forgoes by attempting instead of
  claiming; a server that reprices RP should retune these. Leaving every tier's chance and
  caps at `0.0` skips the whole mechanic. `choice_window_seconds`/`sequence_timeout_seconds`
  bound how long a
  choice or an in-progress attempt can sit unanswered before it resolves on its own (as a
  decline); `delivery_retry_interval_seconds` governs how often a caught Pokémon that
  couldn't be delivered (party and PC both full) retries. Support can inspect or clear a
  stuck session with `/cobbleraids capture status [player]` / `/cobbleraids capture clear
  <player>` — clearing settles nothing, so use it only for a session that is genuinely
  stuck, not as a routine way to skip the choice.
- **Tiers are progression** — starter raids are level 25 with a 550 HP base, powerhouse level 50
  at 1100, and legendary and mythical stay level 100 at 3500. A new player can clear a starter
  raid; a legendary is endgame. Starter is also 70% of natural spawns, so the tier a low-level
  player meets is the one built for them.
- **Dynamic level** — when recruitment locks, a boss is raised to the average level of the
  battle-ready Pokémon the group actually brought. **Upward only**: a definition's level is a
  floor, never a ceiling, because loot is decided by rarity tier and not by level — a boss that
  could scale down would hand a low-level group a mythical's drop rates off a trivial fight.
  `dynamic_level.level_offset` shifts the target either way and still cannot breach the floor;
  `enabled: false` restores fixed levels. The health pool scales with the applied level too, so a
  geared party raising a starter boss to 100 also faces four times the health — without that, a
  low-tier raid would be over in eight turns for anyone strong enough to trivialise it.
- **Durability** — an unclaimed reward is fixed when the raid is won, not when it is claimed:
  the claim stores a seed and regenerates the same bundle, so logging off does not reroll what
  you earned. Nothing serialises an ItemStack. Edit a loot table between the win and the claim
  and the same seed yields something else, which is the trade against freezing contents outright.
- **Raid Points** — a won raid pays RP: 25 starter, 50 powerhouse, 75 legendary, 100 mythical,
  flat per claim. A currency this mod owns and the raid shop will spend, earnable nowhere else,
  so what a raid is worth does not depend on another mod's price list. `/cobbleraids points`
  shows a balance and what each tier pays; operators have `points give|take|set <player>`.
- **Currency** — *on, at a token amount.* With CobbleDollars installed and
  `currency.enabled: true`, a claimed reward also pays
  `currency.<tier>` (200 / 500 / 1200 / 2500 by default), split by damage share
  and withheld below `currency.minimum_share_percentage` (10%). This is on top of the
  income CobbleDollars already pays every winner of a wild battle, which a raid is.
  `currency.enabled: false` switches it off; without CobbleDollars nothing is paid and
  item rewards are unaffected. `/cobbleraids debug status` names the active backend.
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

### The reward policy

A definition that names **no** rewards of its own is *policy-driven*: what it grants comes from
`config/cobbleraids/reward_policy.json` and the tables built into the core JAR. All 132 bundled definitions work this way. Each claim is one **specialty** selection plus two
**general** ones: three items, flat, whatever the player's contribution (contribution still scales
Raid Points and CobbleDollars). Raid keys and key fragments are registered but not currently in any
loot table, so nothing drops them. A claim is about 76 / 72 / 67 / 62% base supplies (starter /
powerhouse / legendary / mythical). Of a general roll, card packs are 12 / 14 / 16 / 18% and "curios"
(evolution items, fossils, and nature mints from powerhouse up) are 14 / 16 / 18 / 20%; the exact
figures are in `validation/economy/probability_report.md`.

```text
cobbleraids:specialty/<tier>            one premium roll: mega stones, charms, TMs, capsules…
cobbleraids:specialty/boss/<species>    the same, for the 21 bosses that have a Mega Stone
cobbleraids:general/<tier>              the ordinary roll: base consumables, cards, curios
cobbleraids:base/<tier>                 what both fall back to, and never itself a premium item
```

`validation/economy/probability_report.md` lists the exact drop rate of every row, computed from
the table weights rather than sampled. The tables are generated: edit the matrix in
`validation/economy/build_tables.py` and re-run it rather than editing a table by hand, which CI
will notice.

A definition that names anything of its own — items, chance items, loot tables, a contribution
pool — keeps its own behaviour instead and ignores the policy entirely. That is what the
per-item tables under `cobbleraids:specialty/opt/<mod>/` and the grouped ones under
`specialty/leaf/` are for: each grants one item (or one group) from one mod, so a hand-written
definition can reward exactly one add-on. Each is skipped when its mod is not installed.
`/cobbleraids debug audit` names any definition still on that path, in case it is there by
accident rather than on purpose.

### The raid shop

Items are priced in Raid Points, with per-player limits that reset daily, weekly (Monday, UTC) or
never; everything lives in `config/cobbleraids/shop.json`. The Consumables page sells capture balls,
Rare and Exp Candies (priced so a level costs about 15 RP on average), the six stat vitamins, Ability
Capsule and Patch, PP Up and Max, Lucky Egg and Exp Share; the strongest of these carry low daily or
weekly limits. A second page sells Cobblemon Cards booster packs when that mod is installed.

**Wandering Pokemon.** A page of ten Pokemon that changes every four hours (00:00, 04:00... UTC), with a
countdown to the next restock in the shop header. Every player gets their own ten, so two players
never see the same page, and each player's page is stable until the next restock. Nothing is stored:
the ten are a pure function of the four-hour window and the player, so a restart reproduces them.
Each listing has a level from 25 to 50 and a fixed nature, ability, gender and IV spread, so the
Pokemon you see is exactly the one you get. Each can be bought once per player per rotation.

Legendary-class species (legendary, mythical, ultra beast, paradox, restricted) are rare but possible:
each listing has a 2% chance to be one, at 4x the normal price, and any listing has a 2% chance to be
shiny, at 3x. Both can happen at once. That works out to roughly one of each per player per day.
Ordinary listings cost 100 to 500 RP: the ceiling scaled by rarity and level, `max_price x rarity x
level / max_level`, where rarity is half catch rate (45 or lower counts as hardest) and half base stat
total (600 counts as full), with a floor of 10%. The `rotation` block in `shop.json` sets the listing
count, level range, price band, excluded labels and, under `rarity`, the chances and price multipliers.

**Ascension rarity.** A Pokemon entry in `shop.json` may carry `"rarity"` (`common`, `uncommon`, `rare`,
`epic`, `legendary` or `mythical`), written beside `species` and `level`. When the optional AscensionLib mod is
installed, the bought Pokemon gets an ascension profile at exactly that rarity; only its modifier slots are
rolled, once per purchase, so two buyers get the same rarity and different modifiers. An entry without `rarity`
gets no profile, as before, and the rotating page never sets one. An unknown rarity drops that entry at load
(logged) rather than selling a Pokemon without the rarity it was priced for. Without AscensionLib the field is
ignored. If the profile cannot be granted the purchase still stands and the log names the Pokemon and rarity.

## Commands

`/cr` is a short alias for `/cobbleraids`; both run the same commands under the same permissions.
(Not `/raid`, which vanilla Minecraft already owns for village raids.)

**Every player:**

```text
/cr                           open the raid hub (shop, trophies, Hall of Legends, leaderboards)
/cr shop                      open the shop; lists it in chat if the client has no WebGUI
/cr shop buy <id>             buy from the chat list
/cr reward                    reopen a pending reward;  /cr reward claim <choice> claims it
/cr points                    your Raid Points
/cr top [board [weekly|alltime] [rows]]    leaderboards
/cr stats [player|server]     raid records
/cr title [list|set <id>|clear]            cosmetic titles
/cr info <species>            where a raid boss appears
/cr leave                     withdraw from your current raid
/cr notify [legends]          mute or unmute spawn / Hall of Legends announcements
```

**Operators only** (permission level 2; each subcommand checks this itself, and a test proves it):

```text
/cr list [tier] | spawninfo
/cr spawn <species> [x y z] [renown <roll|force|none>]
/cr despawn [all] | reload
/cr cooldown list | cooldown reset <definition>
/cr points <player> | give|take|set <player> <amount>
/cr reward grant <player> <definition> | list [player] | clear <player>
/cr capture status [player] | grant <player> <definition> | clear <player>
/cr perf [log]
/cr debug status | raids | battle | history | config | audit
/cr debug loot <loot_table> | record <player> | join <player>
```

`/cr reload` reloads the config, reward policy and shop together.

## Statistics and leaderboards

The server keeps a permanent record of every player's raids, all-time and for the current week
(Monday to Sunday, UTC, the same window as the shop's weekly limits), plus server-wide totals.

| Board | Counts |
|---|---|
| `raids_won` `raids_joined` `raids_lost` `raids_fled` | raids by outcome; leaving a raid is "fled", not "lost" |
| `wins_starter` `wins_powerhouse` `wins_legendary` `wins_mythical` | wins by tier |
| `rp_earned` | Raid Points granted for raids and captures (never shop refunds or admin grants) |
| `total_damage` `best_raid_damage` | damage dealt, in raid health-pool units, and the most in one raid |
| `bosses_caught` `renown_defeated` | captures and renowned bosses beaten |
| `win_streak` | the longest run of consecutive wins; a loss or leaving a raid ends a run |
| `fastest_win` | the shortest winning raid in combat seconds; the one board where lower is better |

`/cobbleraids top fastest_win weekly 5` shows the top five; your own rank is shown beneath the list
if you are not on it. `/cobbleraids stats` is your own record and `stats server` the totals.

- A player who withdrew or lost their connection still has the damage they dealt counted, but is not
  given the win. A raid everyone had left is not counted as a defeat.
- Only ordinary raids count. An encounter owned by another mod counts only if its owner left raid
  history on.
- The first start after upgrading seeds each player from the raid history already on the server:
  wins, wins by tier, captures and renowned bosses beaten. Damage, Raid Points earned, losses,
  streaks and speed start counting from that day, because the old records never held them.
- Everything is saved in `cobbleraids_stats` in the world data. Counting is a few in-memory updates
  per raid event, and a board is sorted only when someone asks for one.

The leaderboards are also a WebGUI page, opened from the hub (`/cr`). Anything building
its own view should call `com.cobbleraids.stats.Leaderboards.board(...)`, which returns the rows, how
many players are ranked and the viewer's own row, rather than parsing the command output.

## Renowned bosses

Each spawn has a per-tier chance (5% / 7% / 10% / 12% by default) to be **renowned**: it
carries a generated title such as **☠ Kaelen, the Relentless Gyarados ☠** on its nameplate,
in the spawn announcement and across the top of the reward page, and its epithet grants one
moderate boon:

| Boon | Effect | Config |
|---|---|---|
| `hp_pool` | A further larger raid health pool, after player count and level | `renown.health_bonus` (0.15, max 0.5) |
| `stat_focus:<stat>` | Max IV plus extra EVs in one stat, within Cobblemon's 252/510 limits | `renown.stat_focus_evs` (200) |

Every renowned boss, whatever its epithet, is also **stronger than the ordinary one**: it fights
`renown.level_bonus` levels up (10, so a level-25 starter spawns at 35, capped at 100) and its raid
health pool carries `renown.base_health_bonus` more (0.30). The raised level is the floor dynamic
level scaling starts from, so the pool grows in proportion to it whether or not dynamic level is on --
about 1.8x the pool of an ordinary starter at the shipped numbers. An existing server's untouched
`stat_focus_evs` of 128 moves to 200 on load; a value you set yourself is left alone.

Beating one pays `renown.points_multiplier` Raid Points and `renown.currency_multiplier`
currency (1.25x each, floored). Contribution bonus rolls are unchanged.

Names and epithets are datapack word lists, loadable from any namespace at
`data/<namespace>/renown/*.json`; see `data/cobbleraids/renown/` for the format. An epithet
picks a boon *kind*; the strength always comes from the server config. Typed epithets
(`"types": ["water"]`) join the general pool for bosses of that type, and `"tiers"` restricts
an entry to rarer bosses. Bad entries are skipped with a warning.

`/cobbleraids spawn <species> [x y z] renown <roll|force|none>` forces the outcome for testing.

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

## Encounter API for other mods

Another mod can start a raid boss battle it owns through `com.cobbleraids.api.encounter`
(experimental, `CobbleRaidsEncounters.API_VERSION` 1; first consumer: CobbleTowers). An owned
encounter is a real raid battle — shared health pool, boss AI, the same Showdown integration —
whose side effects belong to the caller:

```java
StartResult result = CobbleRaidsEncounters.start(new EncounterRequest(
        ResourceLocation.fromNamespaceAndPath("mymod", "arena"), UUID.randomUUID(), players,
        ResourceLocation.fromNamespaceAndPath("cobbleraids", "decidueye"), level, position,
        bossLevel, OptionalLong.empty(),
        EncounterPolicy.none().withCarryover(true, true)),
    listener);   // onParticipantLeft, onEnded(EncounterResult)
```

- **Nothing happens unless the policy asks.** Catching, raid rewards, raid history, experience
  and HP/PP carryover are each off by default, independent of the server config.
- **The boss belongs to the encounter.** Nobody else can recruit into it, it is removed when the
  encounter ends however it ends, and one left behind by a crash is purged at the next boot.
- **Call on the server thread.** Listener exceptions are contained and logged; they never stop
  cleanup.

Signatures in that package use only `java.*`, `net.minecraft.*` and the package itself, which
`validate_api_boundary.py` enforces on the bytecode. Consumers compile against the published
artifact `com.cobbleraids:cobbleraids:<version>`:

```text
./gradlew publishToMavenLocal
```

## Build

```text
gradle --no-daemon clean build
```

Outputs the mod JAR and a sources JAR to `build/libs` (install only the mod JAR), and runs
the 602-test unit suite over the Minecraft-free core: spawn-rate and contribution maths,
config round-trip, lobby recruitment and raid progress rules, fault containment, the
consistency audit's reporting rules, the shop and reward economy, the encounter API's request
and policy rules, and the Showdown file patcher.

## Validation

```text
validation/validate_core_sources.sh
python3 validation/validate_{raid_integrity,reward_visibility,reveal_screen}.py [jar]
python3 validation/validate_{logging,callback_guards}.py
python3 validation/validate_mixin_guards.py [jar]      # after a build: reads bytecode
python3 validation/validate_api_boundary.py [jar]      # after a build: reads bytecode
python3 validation/economy/validate_economy_manifest.py
python3 validation/economy/build_tables.py --check
```

Structural checks over the 132 definitions, tier membership, biome tag composition,
optional-mod manifests, the mixin registry, the shared-HP packet path, and the tier loot
table each definition rolls — plus four property checks that re-derive their subject from
the tree each run: every Fabric callback and every mixin injection is wrapped in a fault
barrier, all logging goes through `RaidLog`, and no public API signature names an internal or
Cobblemon type. They run against both the source tree and the built JAR.

The whole sequence — validators, `gradlew clean build` with the unit suite, then the
validators again over the produced JAR — is one script, which is exactly what
`.github/workflows/build.yml` does:

```text
bash validation/ci_local.sh          # ~40s
bash validation/hooks/install.sh     # once per clone: gate `git push` on it
```

The pre-push hook runs the same sequence locally before every push, and GitHub Actions runs it
again on the pushed commit. Bypass the hook for a single push with `git push --no-verify` or
`SKIP_LOCAL_CI=1 git push`.

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
