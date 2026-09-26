# CobbleRaids

A Fabric mod adding cooperative multiplayer boss fights against scaled Pokémon to a Cobblemon
server, plus the reward economy, shop and renown systems built around them.

## Language

**Raid**:
One cooperative fight between a group of players and a single scaled boss Pokémon, run as one
Cobblemon/Showdown battle with one side per player plus one side for the boss.
_Avoid_: encounter (see below — a different, narrower concept), battle (a raid is a battle, but not
every battle is a raid)

**Raid Definition**:
The config-authored template for one kind of raid: species, spawn rules, recruitment window,
scaling, and rewards. Loaded from datapack JSON into `RaidDefinitionRegistry`.
_Avoid_: raid config, boss config

**Rarity Tier**:
The aggregate classification a natural spawn rolls before an individual Raid Definition is chosen:
Starter, Powerhouse, Legendary, Mythical.
_Avoid_: rarity, boss tier

**Lobby**:
The pre-battle window where players join a spawned boss before the fight starts, bounded by a
duration, a radius and a player cap. Owned by `RaidLobbyManager`.
_Avoid_: recruitment window (the config field on Raid Definition is spelled `Recruitment` for this
same concept — an existing naming inconsistency, not a second concept)

**Raid Session**:
The server-authoritative identity of one running raid: the Cobblemon battle, the boss entity, and
the definition it was spawned from. Exists only while a server is running.
_Avoid_: raid instance

**Raid Progress**:
The arithmetic and state-machine half of a running raid, split out of Raid Session so it can be
tested without a server: the shared boss health pool, who is still in, each participant's
contribution, and which terminal state (won/lost/abandoned) has been reached.
_Avoid_: raid state

**Contribution**:
One participant's share of the damage dealt to the boss, expressed as a percentage of the total.
Crosses a Raid Definition's configured thresholds to grant bonus reward rolls.
_Avoid_: participation, damage share

**Encounter**:
A request from code outside the normal natural-spawn/lobby flow — another mod, or an admin command
— to run one raid-shaped battle against a specific definition, for a specific set of players, right
now. The seam other mods (e.g. CobbleTowers) use to run a raid boss inside their own content instead
of a wild spawn.
_Avoid_: raid (an encounter becomes a raid once it starts; the word names the request to start one)

**Renown / Renowned Boss**:
A boss that rolled a name and epithet (e.g. "Kaelen, the Relentless") on spawn, on top of its normal
species and tier. Rarer, and pays a reward multiplier.
_Avoid_: named boss, elite boss

**Boon**:
What a Renowned Boss's epithet grants: a larger shared health pool, or maximum IV plus extra EVs in
one stat. A closed set of kinds — the epithet may say what kind of boon and, for a stat boon, which
stat, but never how strong it is; strength is a server config number, not something a datapack or
word list can set.
_Avoid_: epithet effect, boss buff

**Raid Points**:
The currency a raid pays out and the raid shop spends. Owned entirely by this mod rather than
borrowed from a server economy mod — see [ADR 0001](docs/adr/0001-raid-points-owned-currency.md).
_Avoid_: raid currency, points (ambiguous with contribution or reward rolls)

**Pending Reward**:
An immutable, per-player claim token created the moment a raid is won, snapshotting the reward
config and a seed rather than concrete items. A claim regenerates its contents from that seed, so an
unclaimed reward survives a server restart and a `/reload` cannot rewrite something already earned.
_Avoid_: reward claim, loot ticket

**Raid Shop**:
The storefront that spends Raid Points, separate from any general-purpose server shop.
_Avoid_: reward shop
