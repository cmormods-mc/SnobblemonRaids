# CobbleRaids: wiki handoff

Everything needed to write the public wiki, in one file. It is organised as the wiki's pages, in the
order I would publish them. Each section says what it is for, gives the facts, and names the source in
the repo so a claim can be re-checked. Written against build **0.8.183** (Cobblemon 1.8.2, Minecraft
1.21.1, Fabric).

Facts marked **(unverified)** were not exercised on a real client or a real host. Do not state them as
guarantees on the wiki until someone has tested them. They are collected in section 22.

## Contents

1. Home and the elevator pitch
2. Requirements and installation
3. Upgrading from a client-mod version
4. How a raid works (player guide)
5. Raid tiers and the 130 bosses
6. Spawning
7. Recruitment, the battle and dynamic level
8. Rewards, Raid Points and currency
9. The Raid Capture Protocol
10. Renowned bosses
11. The Raid Shop
12. Keys, key fragments and held items
13. Titles, trophies and the Hall of Legends
14. Statistics and leaderboards
15. The in-game screens (WebGUI)
16. Commands and permissions
17. Configuration reference
18. Datapack authoring
19. Compatibility with other mods
20. Developer reference (API, architecture, build, tests)
21. Operations and troubleshooting
22. Known gaps and the release checklist
23. Glossary
24. Suggested wiki structure, screenshots and style notes

---

## 1. Home and the elevator pitch

CobbleRaids adds **cooperative wild raid bosses** to a Cobblemon server. A scaled boss Pokémon spawns in
the world. Up to four players walk up, right-click it, and fight one shared battle against a single
health pool. Winning pays Raid Points, items and optionally CobbleDollars, and gives each winner a chance
to catch the boss through a short timing minigame.

What makes it different from a normal wild battle:

- One shared battle: every player has their own side, the boss has one, and damage from all four players
  drains a single pool. Contribution is tracked per player.
- 130 hand-built bosses in four rarity tiers, each with a fixed competitive moveset.
- A reward economy that the mod owns (Raid Points) and a shop that spends it.
- Renowned bosses with titles, boons and bigger payouts.
- Server-side only: players install nothing from this mod. Screens are WebGUI pages.

Short taglines you can reuse:

- "Co-op raid bosses for Cobblemon. Four trainers, one boss, one health bar."
- "Server-side only. No client mod for the raids themselves."

Source: `README.md`, `CONTEXT.md`.

---

## 2. Requirements and installation

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric Loader | 0.18.0 or newer (WebGUI needs it) |
| Fabric API | 0.116.6+1.21.1 |
| Cobblemon | 1.8.2 or newer |
| Polymer | 0.9.19 or newer: `polymer-core`, `polymer-resource-pack`, `polymer-common`, `polymer-autohost` |
| WebGUI | 1.8.1 or newer. Recommended; it shows every screen. |

Optional mods, each detected at runtime and skipped when absent:

- SkiesGUIs 1.8.1: a chest GUI for claiming rewards (chat claiming is the fallback).
- Text Placeholder API 2.4.2+: publishes `%cobbleraids:points%`.
- CobbleDollars: an alternative currency backend and extra payout.
- Reward-item mods, rewarded only when installed: `mega_showdown`, `simpletms`, `cobblemoncharms`,
  `cobblemon-cards`, `cobblecapsule`, `companion_bonds`, `daycareplus`, `cobblesafari`, `ridetraining`.
  `cobblemon-cards` also supplies the sprites the web pages show; without it a sprite is a "?".
- AscensionLib: a shop Pokémon entry can carry a rarity profile.

Install steps (server):

1. Put `CobbleRaids-<version>.jar` in `mods/`. Do not use the `-sources.jar`.
2. Add the four Polymer jars and WebGUI.
3. Start once, then set `"enabled": true` in `config/polymer/auto-host.json` and restart. If this stays
   off, players see plain vanilla items instead of the custom item art.
4. Optional: in `config/webgui/server.json` an operator can make the WebGUI client mod required.

Install steps (players): nothing for the raids themselves. To use the screens (shop, trophy room, Hall of
Legends, reward reveal, capture minigame, hub, leaderboards) they install the WebGUI client mod, which also
downloads a Chromium build of roughly 150 MB on first use.

Data the server creates: `config/cobbleraids/` holds `server.json`, `shop.json`, `reward_policy.json`,
`titles.json`. Saved state lives in the world data (raid history, trophies, Raid Points, stats, defeated
bosses, pending rewards, capture sessions).

Source: `README.md` Requirements, `src/main/resources/fabric.mod.json`.

---

## 3. Upgrading from a client-mod version

Earlier versions had to be installed on every player's machine. This one must not be.

1. Remove the old `CobbleRaids-*.jar` from `mods/` on the server and on every client. A client that still
   has it registers the raid items itself, which conflicts with the server's versions.
2. Add this jar, the Polymer jars and WebGUI on the server. Fabric Loader 0.18.0+.
3. Enable Polymer auto-host and restart. Players get a small resource pack on their next join.
4. Players without WebGUI keep chat commands: `/cobbleraids shop` and `/cobbleraids shop buy <id>`,
   `/cobbleraids reward claim`, `/cobbleraids top`, `/cobbleraids stats`.

Saved data is read exactly as before. Cobblemon 1.7.3 is no longer supported; the repository keeps the tag
`last-cobblemon-1.7.3` for anyone who needs that line.

What went away for players: the T and L keybinds, the old in-world boon icons on nameplates and the battle
overlay, and the native (non-web) screens. The boon is still shown on the reward page and in the Hall of
Legends.

---

## 4. How a raid works (player guide)

1. **Find a boss.** Wild raid bosses spawn naturally. A server-wide announcement names the boss and the
   area (rounded to the nearest hundred blocks). `/cobbleraids notify` mutes the announcement for you.
   Bosses glow within 48 blocks (configurable) so they are easy to spot.
2. **Recruit.** Right-click the boss. A recruitment window opens (20 seconds, 10 block radius, up to four
   players by default). Other players right-click the same boss to join.
3. **Fight.** When recruitment locks, one shared Showdown battle begins. Each player has their own side.
   Everyone attacks the same health pool. The boss is invulnerable to anything but the raid battle and is
   slowed so it cannot run off.
4. **Win or lose.**
   - Win: each participant gets a pending reward and the reward page opens.
   - Lose: the party pays the cost (see below), the boss is healed and stays. After three failed attempts
     (default) it leaves.
5. **Claim.** The reward page lets you claim. If you cannot open it, `/cobbleraids reward claim` works.
6. **Capture (optional).** After claiming, you may attempt the capture minigame for a chance at the boss
   species, or keep the Raid Points. See section 9.

Rules players should know:

- **Raids use copies of your party.** Your real Pokémon are not at risk. Afterwards, health and PP carry
  back to the real party (status does not by default). Fainted Pokémon recover on Cobblemon's own timer.
- **Leaving:** `/cobbleraids leave` withdraws you. A withdrawn or disconnected player forfeits experience
  and EVs and is not given the win, but their damage is still counted.
- **Disconnecting:** there is a 5 minute reconnect grace period. If you return in time you are put back in
  the fight. If a player is held, the others wait.
- **Experience and EVs** are granted to the Pokémon that faced the boss and survived, using Cobblemon's own
  calculators.
- **Mega Evolution** works when `mega_showdown` is installed.
- **Flee** is disabled by default for raids.

Source: `README.md` Rewards and cost; `RaidReconnectService`; `RaidCombatRuleService`.

---

## 5. Raid tiers and the 130 bosses

| Tier | Bosses | Level | Base HP | Natural spawn share |
|---|---|---|---|---|
| Starter | 27 | 25 | 550 | 70% |
| Powerhouse | 10 | 50 | 1100 | 20% |
| Legendary | 71 | 100 | 3500 | 8% |
| Mythical | 22 | 100 | 3500 | 2% |

- Starter is built so a new player can clear it. Legendary is endgame.
- Every boss has a fixed competitive moveset in its definition. A banned-move list keeps moves that would
  break a shared-health fight out of boss movesets.
- Each boss is tagged with one or two element-type biome tags, which decide where it spawns.
- Tier scaling multipliers (health, time limit, reward) exist but are **off** by default.

Colours used in the UI and chat: starter green, powerhouse blue, legendary gold, mythical pink.

To produce the boss list for the wiki, generate it from the data instead of typing it:
`src/main/resources/data/cobbleraids/raids/*.json` (one file per boss: species, level, base health, tier,
moves, biome tags, spawn weight). `/cobbleraids list [tier]` prints the same in game.

Source: `README.md` Content, `data/cobbleraids/raids/`, `RaidRarityTier`.

---

## 6. Spawning

A rarity tier is chosen first, then a species in it.

- `tier_weights` is a **mix** selector (70/20/8/2). It is renormalised across tiers that currently have
  eligible species, so lowering one tier moves its spawns to other tiers rather than reducing the number of
  raids.
- `tier_spawn_chance` is the **rate** dial, rolled after the tier is chosen. A failed roll means no raid
  that attempt. It never re-rolls into another tier.
- A species needs a matching biome (type tags), dimension, time of day, and must be off its own cooldown
  (default 30 minutes) and under its `max_concurrent`.

Defaults (`natural_spawning`): check every 1200 ticks (1 minute), 5% attempt chance, 1 attempt per check,
at most 10 active raids (4 per dimension), spawns 24 to 64 blocks from a player and at least 128 blocks
from another raid, and despawns after 10 minutes unattended or 30 minutes in total (a boss counts as
attended while a player is within 32 blocks).

Biome coverage: each of the 18 type tags carries a vanilla mapping plus Fabric convention tags, so biome
mods such as Terralith extend the pool automatically with no dependency.

Operators: `/cobbleraids spawninfo` shows the effective odds at your location, including the chance of no
spawn. `/cobbleraids testwild <species>` bypasses the roll and cooldown but keeps placement, biome checks,
tracking, announcements and caps.

Source: `README.md` Spawning; `RaidSpawnScheduler`; `config/CobbleRaidsConfig.NaturalSpawning`.

---

## 7. Recruitment, the battle and dynamic level

**Recruitment (lobby).** `recruitment_defaults`: 20 seconds, radius 10, max 4 players. Each definition can
override. Players join by right-clicking the boss.

**Health pool.** One shared pool. It scales with the number of players (`health_per_extra_player` per
definition) and with the applied level.

**Dynamic level.** When recruitment locks, the boss is raised to the average level of the battle-ready
Pokémon the group brought. It only goes **up**: a definition's level is a floor. `level_offset` shifts the
target; `enabled: false` restores fixed levels. The health pool scales with the applied level.

**Traits.** IVs vary by `iv_jitter` (3). `shiny_chance` is 1%. Bosses can carry fixed nature, ability and
gender in their definition.

**Battle rules.**

- Time limit 900 seconds by default. Flee disabled.
- Every active Pokémon is adjacent to every other (see ADR 0003), so players in slots 3 and 4 can target the
  boss.
- A late or invalid boss choice cannot stall the raid (see section 21 for the history).
- `battle_carryover`: health and PP are copied back after the raid, both on win and loss; status is off.

**Failed raids.** The boss stays, healed. After `max_failed_attempts` (3, `0` = unlimited) it departs. A
surviving boss keeps its raid slot and its despawn timers.

Source: `README.md` Rewards and cost; ADR 0003; `RaidProgress`, `RaidLobbyManager`.

---

## 8. Rewards, Raid Points and currency

**Pending reward.** The moment a raid is won, each participant gets an immutable claim token holding the
reward configuration and a seed, not concrete items. A claim regenerates the same bundle from the seed, so
logging off or restarting does not reroll it. Editing a loot table between win and claim changes the
result.

**What a claim grants (policy-driven definitions, which is all 130 bundled bosses).** Three items, flat,
whatever the contribution: one **specialty** roll plus two **general** rolls. Contribution still scales
Raid Points and CobbleDollars. Key fragments are a category of the general table, so a fragment takes one
of the three slots. Rates are tuned so a key takes about 6, 9, 12 and 15 raids (starter, powerhouse,
legendary, mythical).

Tables (all under `data/cobbleraids/loot_table/`):

```text
cobbleraids:specialty/<tier>          one premium roll: mega stones, charms, TMs, capsules...
cobbleraids:specialty/boss/<species>  the same, for the 21 bosses that have a Mega Stone
cobbleraids:general/<tier>            the ordinary roll: consumables, cards, materials
cobbleraids:base/<tier>               what both fall back to; never a premium item
```

The exact drop rate of every row is in `validation/economy/probability_report.md`, computed from table
weights. The tables are generated by `validation/economy/build_tables.py`; edit the matrix there, not the
JSON.

**Mega pity.** Bad-luck protection on Mega Stones (`mega_pity`, threshold 12). It counts only raids that
could have dropped a stone, not raids in general, and guarantees one by the twelfth such raid. Tera Shards
are not stones.

**Raid Points (RP).** A currency this mod owns. A won raid pays RP flat per claim: 25 starter, 50
powerhouse, 75 legendary, 100 mythical, scaled by contribution and by the renowned multiplier. RP can only
be earned from raids and captures and spent in the raid shop (ADR 0001). `/cobbleraids points` shows a
balance. Operators have `points give|take|set`. `%cobbleraids:points%` is a placeholder token.

**CobbleDollars.** With CobbleDollars installed and `currency.enabled`, a claim also pays 200 / 500 / 1200 /
2500 (by tier), split by damage share and withheld below a 10% share. Without CobbleDollars nothing is paid
and items are unaffected.

**Contribution bonus.** `reward_policy.json` can add bonus rolls at contribution thresholds. The shipped
policy has one threshold at 0% giving 0 bonus rolls.

**Rewards from other mods.** A hand-written definition can name any registry item or a loot table. Items
from missing mods are skipped with a warning and the rest of the reward is still granted.

Source: `README.md` Rewards and cost, The reward policy; ADR 0001; `RaidRewardService`,
`RaidRewardGrantEngine`, `docs/reward-template.json`.

---

## 9. The Raid Capture Protocol

On by default (`catching.enabled`). After claiming, each victor personally chooses, per raid:

- **Attempt capture:** a timing minigame, three stabilization pulses then one throw, for a chance at an
  ordinarily generated Pokémon of the boss's species.
- **Keep the Raid Points:** claim that raid's RP outright.

Failing, declining or letting the choice window lapse all pay the same RP a claim would have. An attempt
only ever gives up that one raid's RP, never an existing balance.

How odds work, per tier: `base_chance` is the floor for doing nothing in the minigame; `stabilization_cap`
and `throw_cap` are the most each half can add. Shipped values: base 8 / 6 / 4 / 2%, and each cap +2 / +2 /
+1.5 / +1%, so a flawless attempt reaches about 12 / 10 / 7 / 4%. Higher tiers also have faster markers and
narrower zones (see the `catching` block in section 17).

How the minigame is judged: the **server** scores each press as the gap between its own two receipts, so
network delay cancels out. The page only animates a marker and reports presses. Zones: a wide gold "good"
zone and a narrow cyan "perfect" zone centred on the track. Press Space or click the button.

Timers: 60 seconds to choose, 18 seconds per attempt, caught Pokémon that cannot be delivered (party and PC
full) retry every 30 seconds. Support commands: `/cobbleraids capture status [player]`, `capture clear
<player>` (settles nothing; only for a genuinely stuck session), and `capture grant <player> <definition>`
(testing).

Setting every tier's chance and caps to `0.0` turns the mechanic off. A server that reprices RP should
retune these.

Without WebGUI the player is not shown the minigame; the offer simply lapses to the decline path.

Source: `README.md`; `RaidCaptureSessionService`, `CaptureWebPage`, `CobbleRaidsConfig.Catching`.

---

## 10. Renowned bosses

Each spawn has a per-tier chance (5 / 7 / 10 / 12%) to be **renowned**: it gets a generated name and
epithet, for example "Kaelen, the Relentless". The title shows on its nameplate, the spawn announcement and
the reward page.

Every renowned boss is stronger: it fights 10 levels up (capped at 100) and its health pool is 30% larger,
about 1.8 times the pool of an ordinary starter at the shipped numbers.

The epithet grants one **boon**, of a closed set of kinds:

| Boon | Effect | Strength set by |
|---|---|---|
| `hp_pool` | A further larger shared health pool | `renown.health_bonus` (0.15, max 0.5) |
| `stat_focus:<stat>` | Max IV plus extra EVs in one stat, within Cobblemon's 252/510 limits | `renown.stat_focus_evs` (200) |

Stats: attack, defence, special attack, special defence, speed. A datapack chooses the *kind*, never the
strength.

Beating one pays 1.25 times the Raid Points and CobbleDollars. Defeated renowned bosses are recorded in the
Hall of Legends (section 13), and a defeated boss can be bought back (section 11).

Datapack word lists: `data/<namespace>/renown/*.json` with `names` and `epithets`. Typed epithets
(`"types": ["water"]`) join the pool for bosses of that type, `"tiers"` restricts to rarer bosses.

Operator: `/cobbleraids spawn <species> [x y z] renown <roll|force|none>`.

Source: `README.md` Renowned bosses; `data/cobbleraids/renown/`; `renown/` package.

---

## 11. The Raid Shop

Spends Raid Points. Opened from the hub or `/cobbleraids shop open`. Everything is in
`config/cobbleraids/shop.json`.

**Catalogue sections.** Consumables, and further sections of items, each entry with `id`, `cost`, `item`,
`count`, a per-player `limit` and a `reset` (`daily`, `weekly` (Monday, UTC) or `never`). Exp candies are
priced about 15 RP per level on average. The strongest items (Lucky Egg, Ability Patch, held items) have low
daily or weekly limits. Default `per_page` is 64.

**Wandering Pokémon.** A page of ten Pokémon that rotates every four hours (00:00, 04:00... UTC) and is the
same for every player. Nothing is stored: the ten are a pure function of the four-hour window. Every species
is eligible except those labelled legendary, mythical, ultra beast, paradox or restricted. Each listing has
a level from 25 to 50 and a fixed nature, ability, gender and IV spread, so every buyer gets the identical
Pokémon. Never shiny. Once per player per rotation. Price is the ceiling scaled by rarity and level, between
100 and 500 RP by default. `/cobbleraids debug rotation` prints the current ten.

**Starter lines.** Starter Pokémon lines are priced 250 / 375 / 500 RP (base / middle / final).

**Your Bosses (personal boss shop).** A player can buy back a boss they defeated, at its recorded stats, for
500 / 750 / 1000 / 1500 RP by tier. A risky **reroll** of IV and EV costs 50 RP.

**Card packs.** 19 card packs at 75 RP, two per week (with `cobblemon-cards`).

**Ascension rarity.** With AscensionLib installed, a catalogue entry's optional `"rarity"` gives the bought
Pokémon an ascension profile; modifier slots are rolled per purchase.

Chat access: `/cobbleraids shop` lists, `/cobbleraids shop buy <id>` buys.

Source: `README.md`; `shop/` package; `config/cobbleraids/shop.json`.

---

## 12. Keys, key fragments and held items

Ten custom items, all server-side (Polymer), all named in the wiki with the art from
`assets/cobbleraids/textures/item/`:

| Item | Looks like | Notes |
|---|---|---|
| Starter / Powerhouse / Mythical / Legendary Raid Key | echo shard | Stacks to 16. Opens that tier's crate in a separate crate mod. |
| The four Raid Key Fragments | prismarine shard | Stacks to 6. Right-click combines every complete set of six in your whole inventory into keys. |
| Raid Core | heart of the sea | Held item. +20% Raid Points on a claim while a party Pokémon holds it. |
| Guardian Scale | turtle scute | Held item. Once per battle, survives a hit that would faint its holder, leaving it at 20% HP. |

Crates: a key opens its tier's crate in another mod. The contract is the item tags
`#cobbleraids:raid_keys` and `#cobbleraids:raid_keys/<tier>` (and `raid_key_fragments`), so a crate mod needs
no dependency on this one. Items live here because raids are their only source.

How the items work without a client mod: a client sees a vanilla item with a custom model number, and a
small resource pack that Polymer builds maps that number to our model and texture. Names and tooltips are
plain text because a client without this mod has no language file.

Guardian Scale carries a real Cobblemon held-item component so the battle knows its effect; the effect
itself is in `assets/cobbleraids/showdown/mods/items.js`.

Source: `item/` package; ADR notes in `RaidKeyItems` Javadoc.

---

## 13. Titles, trophies and the Hall of Legends

**Titles.** Cosmetic, unlocked by progress, set with `/cobbleraids title set <id>`; `title list` and
`title clear` also exist. Defined in `config/cobbleraids/titles.json`. Defaults: Novice Raider (1 win),
Veteran Raider (25), Champion Raider (100), Legend Slayer (10 legendary wins), and a captures title (50
bosses caught).

**Trophy room.** A per-player collection of every distinct boss species beaten. Each trophy remembers the
level, shiny state, IV and EV percentages, the first defeat date and the number of times beaten. Searchable
and filterable by tier, shiny and sort order. `/cobbleraids trophies` or the hub.

**Hall of Legends.** A server-wide record of renowned boss victories: the title, species, tier, boon, date
and the players who won. Seven entries per page, filterable to "mine" and by tier. A server-wide message
announces a new legend; `/cobbleraids notify legends` mutes it. `/cobbleraids legends` or the hub.

Source: `title/` package, `catching/TrophyLedger`, `catching/HallOfLegends`.

---

## 14. Statistics and leaderboards

The server keeps a permanent record of every player's raids, all-time and for the current week (Monday to
Sunday, UTC), plus server totals.

| Board | Counts |
|---|---|
| `raids_won` `raids_joined` `raids_lost` `raids_fled` | raids by outcome; leaving is "fled", not "lost" |
| `wins_starter` `wins_powerhouse` `wins_legendary` `wins_mythical` | wins by tier |
| `rp_earned` | Raid Points from raids and captures (never shop refunds or admin grants) |
| `total_damage` `best_raid_damage` | damage dealt, in raid health-pool units, and the most in one raid |
| `bosses_caught` `renown_defeated` | captures and renowned bosses beaten |
| `win_streak` | longest run of consecutive wins |
| `fastest_win` | shortest winning raid in combat seconds (lower is better) |

Commands: `/cobbleraids top [<board> [weekly|alltime] [rows]]`, `/cobbleraids stats [<player>|server]`. The
leaderboards page is opened from the hub with `/cobbleraids menu`.

Counting rules: a withdrawn or disconnected player's damage is counted but they get no win; a raid
everyone left is not a defeat; only ordinary raids count (a mod-owned encounter counts only if its owner
left raid history on). The first start after upgrading seeds wins, tier wins, captures and renowned
victories from existing history; the other stats begin that day. Stored in `cobbleraids_stats` in the world
data.

Other mods can call `com.cobbleraids.stats.Leaderboards.board(...)`.

Source: `README.md`; `stats/` package.

---

## 15. The in-game screens (WebGUI)

All screens are WebGUI pages: HTML, CSS and JavaScript served over the player's own connection. There is no
web server and no open port for them. The pages are installed from the jar into
`config/webgui/web/cobbleraids/` at server start, and edits need a restart.

| Page | Opened by | What it does |
|---|---|---|
| Hub | `/cobbleraids menu`, F6 main-menu link | Four tiles: shop, trophies, legends, leaderboards |
| Raid Shop | hub, `shop open` | Browse and buy; pokemon pages are 4 across, item pages 8 across; hover for details |
| Trophy Room | hub, `trophies` | Three trophies per page, search, tier, shiny and sort filters |
| Hall of Legends | hub, `legends` | Seven rows per page, "mine" and tier filters |
| Leaderboards | hub | Fifteen boards, weekly or all time, your row highlighted |
| Reward reveal | opens automatically after a win | Raid summary, choose, reveal what was granted |
| Capture | opens after a claim | Offer, three pulses, throw, result |

Design: the "Raid Network" look, a dark-blue terminal with thin cyan lines, cut corners and a faint grid.
Headings use Silkscreen, body text IBM Plex Mono (both bundled, SIL OFL). Every page is a 16:9 stage that
scales to the window, so it looks the same at any size. Shared files are in `web/cobbleraids/shared/`
(`theme.css`, `ui.js`).

A page is a **view**: it sends ids and choices, and the server re-validates everything. Nothing the page
shows or sends is trusted.

Sounds are played by the server, not the page: reveal ball sound, capture pulse sounds (perfect, good,
miss), the capture verdict (delayed until the ball stops shaking) and shop purchase success or failure.

Players without WebGUI: opening a page gives a one line chat message. The shop points to its chat commands,
and the trophy room and legends messages include the player's counts.

Technical notes for the wiki's "for administrators" box:

- One server-to-page message is limited to 32 KB, so icons are sent in separate batches.
- WebGUI uses an offscreen Chromium; shader packs make every WebGUI page slower, which is WebGUI-inherent.
- If a mainMenuUrl is not already set in WebGUI's config, CobbleRaids sets the hub as the F6 target about one
  second after join.

Source: `web/` package, `src/main/resources/web/cobbleraids/`.

---

## 16. Commands and permissions

Everything under `/cobbleraids`. Operator commands need permission level 2.

**Players**

| Command | Does |
|---|---|
| `reward claim [choice]` | Claim a pending reward (the fallback if the page cannot open) |
| `menu` | Open the hub |
| `shop` | List the shop in chat. `shop open` opens the page. `shop buy <id>` buys. |
| `trophies`, `legends` | Open those pages |
| `points` | Your Raid Point balance and what each tier pays |
| `top [board [weekly\|alltime] [rows]]` | Leaderboard in chat |
| `stats [player\|server]` | Records |
| `title list \| set <id> \| clear` | Cosmetic titles |
| `leave` | Withdraw from your active raid |
| `notify`, `notify legends` | Toggle spawn announcements and legend announcements for yourself |
| `info <species>` | Details of a boss (unrestricted) |

**Operators**

```text
list [tier]                         spawn <species> [x y z] [renown <roll|force|none>]
spawninfo                           testwild <species>
despawn [all]                       reload
cooldown list | cooldown reset <definition>
reward grant <player> <definition> | reward list [player] | reward clear <player>
capture status [player] | capture grant <player> <definition> | capture clear <player>
points give|take|set <player> <amount>
shop reload
debug status | raids | battle | history | config | audit | rotation
debug definition <species> | loot <loot_table> | record <player> | join <player>
web hello                           (a test page)
```

Permissions are checked on each operator subcommand, not on the root. This matters: Brigadier keeps the first
registered node's requirement when two mods register the same root, which once left operator commands open to
everyone. Do not move the check up to the root.

Source: `command/` package, `reward/RaidRewardCommand`.

---

## 17. Configuration reference

`config/cobbleraids/server.json` is created on first run and **migrated forward** on later starts, so new
settings appear with defaults. `/cobbleraids debug config` prints the active values. Reload what can be
reloaded with `/cobbleraids reload`. Values below are the shipped defaults.

| Block | Key | Default | Meaning |
|---|---|---|---|
| `natural_spawning` | `enabled` | true | Natural spawns on |
| | `check_interval_ticks` | 1200 | Time between spawn checks |
| | `spawn_attempt_chance` | 0.05 | Chance an attempt happens each check |
| | `attempts_per_check` | 1 | |
| | `max_active_raids` / `_per_dimension` | 10 / 4 | Caps |
| | `min_distance_from_player` / `max_...` | 24 / 64 | Placement distance |
| | `min_distance_between_raids` | 128 | |
| | `despawn_player_radius` | 32 | Presence that stops an unattended despawn |
| | `default_despawn_seconds` / `default_max_lifetime_seconds` | 600 / 1800 | |
| | `default_definition_cooldown_seconds` | 1800 | |
| | `tier_weights` | 70/20/8/2 | Mix selector |
| | `tier_spawn_chance` | 1.0 each | Rate dial |
| | `announcement_precision` | `nearest_hundred` | How exactly the announcement names the area |
| `recruitment_defaults` | `duration_seconds`, `radius`, `max_players` | 20, 10, 4 | |
| `combat_defaults` | `time_limit_seconds`, `allow_flee`, `max_failed_attempts` | 900, false, 3 | |
| `battle_carryover` | `health`, `pp`, `status` | true, true, false | Cost carried to the real party |
| `boss_traits` | `iv_jitter`, `shiny_chance` | 3, 0.01 | |
| `catching` | `enabled` | true | The capture protocol |
| | per tier: `base_chance`, `stabilization_cap`, `throw_cap`, `pulse_*` and `throw_*` travel and zone widths (ms) | see section 9 | |
| | `choice_window_seconds`, `sequence_timeout_seconds`, `delivery_retry_interval_seconds`, `session_retention_seconds_after_resolve` | 60, 18, 30, 120 | |
| `currency` | `enabled`, per-tier amounts, `scale_with_contribution`, `minimum_share_percentage` | true, 200/500/1200/2500, true, 10 | CobbleDollars payout |
| `dynamic_level` | `enabled`, `level_offset`, `max_level` | true, 0, 100 | |
| `mega_pity` | `enabled`, `threshold` | true, 12 | |
| `raid_points` | `enabled`, per tier | true, 25/50/75/100 | |
| `personal_boss_shop` | `enabled`, `reroll_cost`, `buy_cost_<tier>` | true, 50, 500/750/1000/1500 | |
| `tier_scaling` | `enabled`, per tier multipliers | **false** | Optional extra health, time and reward scaling |
| `boss_glow` | `enabled`, `radius_blocks` | true, 48 | |
| `boss_movement` | `slowness_enabled`, `slowness_amplifier`, `prevent_knockback` | true, 6, true | |
| `renown` | `enabled`, `chance` per tier, `health_bonus`, `stat_focus_evs`, `points_multiplier`, `currency_multiplier`, `level_bonus`, `base_health_bonus` | true, 5/7/10/12%, 0.15, 200, 1.25, 1.25, 10, 0.30 | |
| `reconnect_grace` | `enabled`, `grace_seconds` | true, 300 | |
| (root) | `debug_logging` | false | |

Other config files: `shop.json` (catalogue, `per_page`, `limits`, `sections`, `rotation`),
`reward_policy.json` (`standard_general_rolls`, `contribution_thresholds`, table names, key fragments by
bonus rolls), `titles.json`.

Note for the wiki: an existing server's untouched `stat_focus_evs` of 128 moves to 200 on load; a value the
operator set is left alone.

---

## 18. Datapack authoring

**Raid definition** (`data/<namespace>/raids/<name>.json`). Minimal real example (`garchomp.json`):

```json
{
  "species": "cobblemon:garchomp", "level": 50, "base_health": 1100,
  "spawn": { "enabled": true, "weight": 3, "dimensions": ["minecraft:overworld"],
             "biome_tags": ["cobbleraids:raid_types/dragon", "cobbleraids:raid_types/ground"],
             "times": ["all_day"], "cooldown_seconds": 1800, "despawn_seconds": 600, "max_concurrent": 1 },
  "recruitment": { "duration_seconds": 20, "radius": 10.0, "max_players": 4 },
  "scaling": { "health_per_extra_player": 0.65 },
  "time_limit_seconds": 900, "allow_flee": false,
  "moves": ["earthquake", "dragonclaw", "swordsdance", "firefang"],
  "rewards": { "gui_id": "cobbleraids_reward", "choices": { "all": {} } },
  "rarity_tier": "powerhouse"
}
```

- Species keys use the full id (`cobblemon:garchomp`); the lookup is namespace-aware.
- An empty `choices.all` makes the definition **policy-driven**: rewards come from `reward_policy.json` and
  the built-in tables. Naming any items, chance items, loot tables or a contribution pool makes it
  hand-written, and it then ignores the policy.
- Reward items are plain registry ids with an `amount`. A missing mod's item is skipped with a warning.
- `docs/reward-template.json` shows every reward form and is checked by CI against the parser.
- A datapack of the same path **shadows** a bundled definition. A leftover datapack in `world/datapacks/`
  is the classic cause of "my change does not appear".

**Loot tables.** Per-mod tables sit under `specialty/opt/<mod>/` and grouped ones under `specialty/leaf/`;
each is skipped when its mod is missing. `/cobbleraids debug loot <table>` previews any table without
granting it and tab-completes over every loaded table.

**Biome tags.** `data/cobbleraids/tags/worldgen/biome/raid_types/<type>.json`. Other mods may extend them
with `"replace": false`.

**Renown word lists.** `data/<namespace>/renown/names.json` and `epithets.json`. An epithet entry is
`{ "text": "the Relentless", "boon": "stat_focus:attack" }`, with optional `types` and `tiers`.

**Titles.** `config/cobbleraids/titles.json`, entries with `id`, `display`, `color`, `unlock_type`
(`raids_won`, `tier_wins`, `bosses_caught`), `threshold` and `tier` for tier wins.

**Optional data packs shipped in the repo.** `compat/tideforge/` builds a datapack and a resource pack for
Water/Steel forms of the Beldum line (aspect `tideforge`) and the ability Silent Running. Build with
`python build.py --zip` there. It is not part of the mod jar.

---

## 19. Compatibility with other mods

| Mod | Interaction |
|---|---|
| `mega_showdown` | Replaces Cobblemon's unbundled Showdown `index.js` wholesale. CobbleRaids patches it **after** every mod has written and **before** Showdown reads it (ADR 0002 and `ShowdownContextBootMixin`). Raid bosses that take no damage while turns progress normally mean the raid patch is not in the running Showdown context. Mega Evolution works. |
| Polymer | Hard requirement now, for the item art. Cobblemon items are unaffected. |
| WebGUI | Soft requirement; needed by players for every screen. |
| SkiesGUIs | Optional chest-GUI claim backend. |
| CobbleDollars | Optional currency backend and extra payout. |
| `cobblemon-cards` | Item rewards and the sprites the web pages use. |
| Text Placeholder API | `%cobbleraids:points%` (compile-only, never required). |
| Multiworld, `safari-dimension` | Dynamic dimension mods: when a level is closed, raid bosses tracked there are dropped at once, not on a timer. |
| `Veilmon` | A gameplay mod that masks Pokémon species at range and can make a raid boss appear as the wrong model. Not a CobbleRaids bug. |
| `cobblemon-battle-extras` | Overlaps heal-instruction and dispatch handling. Lower confidence; worth a raid healing test with it present. |
| Other mods mixing into Cobblemon battle classes | CobbleRaids mixes into `GraalShowdownUnbundler`, `BattleRegistry`, `PokemonBattle`, `MoveActionResponse` and others. Every mixin runs inside a fault barrier. |

The modpack compatibility notes are in the maintainer's risk register; the wiki can state the table above.

---

## 20. Developer reference

**Encounter API** (`com.cobbleraids.api.encounter`, experimental, `API_VERSION` 1; first consumer
CobbleTowers). Lets another mod run a real raid battle it owns (shared health pool, boss AI, same Showdown
integration) with side effects belonging to the caller:

```java
StartResult result = CobbleRaidsEncounters.start(new EncounterRequest(
        ResourceLocation.fromNamespaceAndPath("mymod", "arena"), UUID.randomUUID(), players,
        ResourceLocation.fromNamespaceAndPath("cobbleraids", "decidueye"), level, position,
        bossLevel, OptionalLong.empty(),
        EncounterPolicy.none().withCarryover(true, true)),
    listener);   // onParticipantLeft, onEnded(EncounterResult)
```

Nothing happens unless the policy asks (catching, rewards, history, experience, HP/PP carryover are each off
by default). The boss belongs to the encounter, is removed when it ends, and one left by a crash is purged
at the next boot. Call on the server thread. Signatures use only `java.*`, `net.minecraft.*` and the package
itself, enforced on bytecode by `validate_api_boundary.py`. Publish locally with `./gradlew
publishToMavenLocal`. Also public: `Leaderboards.board(...)`.

**Architecture.** One package per concern under `src/main/java/com/cobbleraids/`:

| Package | Role |
|---|---|
| `api` | The public API |
| `config` | Config records, codec, definition registry, tiers |
| `spawn` | Natural spawning, scheduler, boss spawner, announcements |
| `lobby`, `raid`, `lifecycle`, `battle` | Recruitment, raid progress and session, rewards lifecycle, battle events |
| `showdown`, `mixin` | The Showdown JS patch installer and the Cobblemon mixins |
| `reward`, `catching`, `shop`, `renown`, `title`, `stats` | The reward, capture, shop, renown, title and stats systems |
| `item` | Polymer items and the item resource pack |
| `web` | WebGUI pages. `WebGuiBridge` is the only class that names WebGUI, via MethodHandles bound once |
| `network` | Plain data carriers for the pages (no packets any more) |
| `fault` | `RaidFaultBarrier`, the containment layer |
| `command`, `placeholder`, `presentation`, `pokemon`, `interaction` | Commands, placeholder token, presentation helpers |

Server data is mostly `SavedData` plus per-system stores. Static maps are cleared on `SERVER_STOPPED` because
an integrated client keeps the JVM across worlds.

**Design decisions worth a "Design notes" page** (full text in `docs/adr/`):

- ADR 0001: Raid Points are owned by this mod, not borrowed from CobbleDollars.
- ADR 0002: the Showdown patch is re-installed at server start, and the injection point moved after
  `attemptUnbundle` (see section 19).
- ADR 0003: in a raid every active Pokémon is adjacent to every other, so players in slots 3 and 4 can
  target the boss.
- ADR 0004: mixin the concrete class used at runtime, never a vanilla ancestor on faith. (Its two worked
  examples included client nameplate and overlay mixins that no longer exist.)

**Fault containment.** Every tick subsystem, Fabric callback and mixin injection runs inside a fault
barrier, so a failure is contained to the raid that caused it and logged (rate-limited). A broken build can
therefore **look healthy from outside**: always grep the log for `[CobbleRaids]` before calling a run good.

**Build.**

```text
./gradlew build              # jar, sources jar, and the unit tests
bash validation/ci_local.sh  # the full local CI, about 40 seconds
bash validation/hooks/install.sh   # once per clone: gate git push on it
```

Version lives on one line of `build.gradle`. JDK 21. Loom 1.13.6. Polymer comes from
`maven.nucleoid.xyz`, Cobblemon and SkiesGUIs from the Modrinth maven.

**Tests.** 602 unit tests over the Minecraft-free core (spawn maths, contribution, config round trip, lobby
rules, fault containment, audit rules, the shop and reward economy, the encounter API, the Showdown file
patcher, the web bundle sync), plus Python validators in `validation/` that read source and bytecode: callback
and mixin fault barriers, logging discipline, API boundary, reward visibility, server-only boundary (no client
code, no packet registration), economy manifest and probabilities. Live harnesses in `validation/smoke/`
(`smoke_test.py`, `prove_audit_detects.py`, `load_test.py`, crash durability, Java-layer scenarios) drive a
real server over RCON with bots.

**CI.** `.github/workflows/build.yml` runs the validators, the Gradle build and the validators again over the
built jar. `validation/ci_local.sh` is the same minus GitHub steps; any new workflow step must be added to
both.

**Repository docs.** `CONTEXT.md` is the domain glossary; `docs/adr/` the decisions; `docs/agents/` the
issue-tracker and triage notes; `validation/README.md` what belongs in a validator versus a unit test.

---

## 21. Operations and troubleshooting

**Health checks.** The mod audits its own state every five minutes and on demand with `/cobbleraids debug
audit`: stranded sessions, leaked raid slots, orphaned scoreboard entries, lobbies without a boss, rewards
naming an unknown definition. It reports and never repairs. A clean server prints nothing.

**Symptoms and causes.**

| Symptom | Likely cause |
|---|---|
| Boss takes no damage but turns progress | The raid patch is missing from the running Showdown context. Check the boot log; see `mega_showdown` above. |
| Boss freezes the battle (stall) | Historically an AI choice rejected for Imprison, Taunt or Disable. Fixed in the raid patch and covered by a fuzz harness. Report with the boss moveset if it recurs. |
| Custom items look like vanilla items | Polymer auto-host is off, or the pack was not accepted. |
| A change to a raid definition does nothing | A datapack in `world/datapacks/` shadows it. |
| Pokémon appear as Eevee at range | The `Veilmon` mod, not CobbleRaids. |
| Shop, trophy or legends show only a chat message | The player's client does not have WebGUI. |
| A player cannot open a page after a restart | WebGUI page edits need a server restart; `reload` only reloads config. |
| Log shows `[CobbleRaids]` errors but the server runs | Expected under the fault barrier; investigate the named subsystem. |

**Persistence and durability.** Pending rewards, capture sessions, run state and stats are written to world
data. A raid active at server stop does not persist its boss (a past duplication bug is fixed), and one that
survived a restart is re-tracked on load. `/cobbleraids leave` and the reconnect grace cover player drops.

**Performance.** Spawn checks run once a minute. Reward and stats work is a few in-memory updates per raid
event; a leaderboard is sorted only when asked for. Web pages are static files served by WebGUI. Sounds are
single packets. The shop rotation is a pure function of the clock and stores nothing.

---

## 22. Known gaps and the release checklist

**Not yet verified on a real client or host (do not promise these on the wiki yet):**

1. Joining with **no CobbleRaids installed** (vanilla, and Fabric-API clients), and how the items, names and
   tooltips look. A bot has been used; the maintainer has confirmed it works in their setup.
2. The Polymer resource pack delivery on a real host, and whether auto-host needs an open port there.
3. A full four-player raid on **Cobblemon 1.8.2**, and the mixin-sensitive paths
   (`RaidBossAttributeResetMixin`, `SwitchBroadcastOfflinePlayerMixin`).
4. The capture minigame's timing feel on a real WebGUI client, including input jitter against the 50 to 60 ms
   perfect zones.
5. Held-item display in Cobblemon's own summary screens for Raid Core and Guardian Scale.
6. A shop page with 60 or more real items on a real client.

**Known limitations to state plainly:**

- Players need the WebGUI client mod for the screens.
- Trophy room shows three trophies per page (set by the server query).
- Text is English only.
- Reward choice buttons show the choice id (title-cased) when a datapack defines several; the bundled
  definitions have one, "Claim Rewards".
- WebGUI pages are slower under shader packs (WebGUI-inherent).

**Release checklist.**

- [ ] Items 1 to 6 above verified.
- [ ] Version moved off 0.8.x (for example 1.0.0), changelog written (none exists yet).
- [x] README header, test count and CI notes refreshed to match the current state.
- [ ] Modrinth page: requirements table, the upgrade steps, the WebGUI note.
- [ ] Wiki screenshots taken (section 24).
- [ ] `bash validation/ci_local.sh` green on the tagged commit; GitHub CI green.

---

## 23. Glossary

Use these exact terms (from `CONTEXT.md`).

- **Raid**: one cooperative fight between a group of players and one scaled boss, as one Cobblemon/Showdown
  battle with a side per player and one for the boss.
- **Raid Definition**: the datapack template for one kind of raid (species, spawn rules, recruitment,
  scaling, rewards).
- **Rarity Tier**: Starter, Powerhouse, Legendary or Mythical; chosen before the species.
- **Lobby**: the pre-battle recruitment window.
- **Raid Session / Raid Progress**: the running raid's identity, and its arithmetic (health pool,
  contributions, terminal state).
- **Contribution**: a participant's share of the damage, as a percentage.
- **Encounter**: a request from outside the natural flow (another mod or an admin) to run one raid-shaped
  battle now. Not a synonym for raid.
- **Renown / Renowned Boss**, **Boon**: the named boss and what its epithet grants.
- **Raid Points (RP)**: the mod's own currency.
- **Pending Reward**: the claim token created on a win.
- **Raid Shop**: the storefront that spends RP.
- **Capture Protocol**: the optional timing minigame after a claim.

---

## 24. Suggested wiki structure, screenshots and style notes

**Pages.** One page per numbered section above, with these merged: 1 as the Home page, 2 and 3 as "Install
and upgrade", 7 and 4 as "How raids work". Add a sidebar: Home, Install, Players, Admins, Datapacks, API.

**Screenshots to capture** (the in-game WebGUI pages render the same as these files):
the hub, shop (Pokémon page, items page, Your Bosses page), trophy room, Hall of Legends, leaderboards,
reward reveal (choose and result), capture (offer, pulse, result), a raid boss with the glow, the recruitment
message, a renowned boss nameplate, and the ten item icons. A script that renders every page from the real
files with mock data was used during development (see the redesign preview in the maintainer's notes); the
item and sprite art is in `src/main/resources/assets/cobbleraids/textures/item/`.

**Style.** Plain statements, active voice, no marketing adjectives. Use the glossary terms. Always give the
default value and the config key together. State requirements before features. For anything in section 22's
unverified list, write "should" until it has been tested.

**Source-of-truth map** (where to re-check a claim):

| Topic | File |
|---|---|
| Defaults | `config/CobbleRaidsConfig.java`, and a freshly generated `server.json` |
| Boss data | `src/main/resources/data/cobbleraids/raids/*.json` |
| Drop rates | `validation/economy/probability_report.md` |
| Commands | `command/` and `reward/RaidRewardCommand.java` |
| Pages | `src/main/resources/web/cobbleraids/` |
| Decisions | `docs/adr/` |
| Terms | `CONTEXT.md` |
