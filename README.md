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
- **Currency** — *off by default, replaced by Raid Points.* With CobbleDollars installed and
  `currency.enabled: true`, a claimed reward also pays
  `currency.<tier>` (2000 / 5000 / 12000 / 25000 by default), split by damage share
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
`config/cobbleraids/reward_policy.json` and the tables in the optional `CobbleRaids-AddonRewards`
JAR. All 130 bundled definitions work this way. Each claim is one **specialty** selection plus two
**general** ones, and contribution adds up to three more general selections — 3 to 6 in total, with
the specialty selection happening exactly once however hard the player fought.

```text
cobbleraids:specialty/<tier>            one premium roll: mega stones, charms, TMs, capsules…
cobbleraids:specialty/boss/<species>    the same, for the 21 bosses that have a Mega Stone
cobbleraids:general/<tier>              the ordinary roll: base consumables, cards, materials
cobbleraids:base/<tier>                 what both fall back to, and never itself a premium item
```

`validation/economy/probability_report.md` lists the exact drop rate of every row, computed from
the table weights rather than sampled. The tables are generated: edit the matrix in
`validation/economy/build_tables.py` and re-run it rather than editing a table by hand, which CI
will notice.

A definition that names anything of its own — items, chance items, loot tables, a contribution
pool — keeps its own behaviour instead and ignores the policy entirely. That is what the
per-add-on tables are for: `cobbleraids:addons/charms`, `addons/tms`, `addons/cards` and the rest
each grant one item from one mod, so a hand-written definition can reward exactly one add-on.
`/cobbleraids debug audit` names any definition still on that path, in case it is there by
accident rather than on purpose.

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

## Renowned bosses

Each spawn has a per-tier chance (5% / 7% / 10% / 12% by default) to be **renowned**: it
carries a generated title such as **☠ Kaelen, the Relentless Gyarados ☠** on its nameplate,
in the spawn announcement and across the top of the reward screen, and its epithet grants one
moderate boon:

| Boon | Effect | Config |
|---|---|---|
| `hp_pool` | Larger raid health pool, after player count and level | `renown.health_bonus` (0.15, max 0.5) |
| `stat_focus:<stat>` | Max IV plus extra EVs in one stat, within Cobblemon's 252/510 limits | `renown.stat_focus_evs` (128) |

Beating one pays `renown.points_multiplier` Raid Points and `renown.currency_multiplier`
currency (1.25x each, floored). Contribution bonus rolls are unchanged.

Names and epithets are datapack word lists, loadable from any namespace at
`data/<namespace>/renown/*.json`; see `data/cobbleraids/renown/` for the format. An epithet
picks a boon *kind*; the strength always comes from the server config. Typed epithets
(`"types": ["water"]`) join the general pool for bosses of that type, and `"tiers"` restricts
an entry to rarer bosses. Bad entries are skipped with a warning.

`/cobbleraids spawn <species> [x y z] renown <roll|force|none>` forces the outcome for testing.
The reward-screen packet is `pending_reward_reveal_v2`: clients on an older CobbleRaids build
fall back to the chat claim path instead of disconnecting.

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
python3 validation/validate_phase{32,36,37,38,39,40}.py [jar]
python3 validation/validate_{logging,callback_guards}.py
python3 validation/validate_mixin_guards.py [jar]      # after a build: reads bytecode
python3 validation/economy/validate_economy_manifest.py
python3 validation/economy/build_tables.py --check
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
