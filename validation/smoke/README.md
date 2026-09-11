# Live smoke test

Boots a real Fabric + Cobblemon server with a CobbleRaids build and checks that raids actually work.

```sh
./gradlew build
python validation/smoke/smoke_test.py \
    --server-dir /path/to/testserver \
    --java "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.1+1/bin/java.exe"
```

With no `--jar`, it installs `build/libs/CobbleRaids-<version from build.gradle>.jar`, removing any
other CobbleRaids jar from `mods/` first. A run takes about a minute, most of it server boot.

## Why it exists

Every serious bug this mod has shipped was a live-behaviour bug — raid slots never freed when a boss
was destroyed, raids granting no exp or EVs at all, the four-player Showdown stall, a reward dupe
vector, a `ConcurrentModificationException` in the boss-tracking pass that stopped the server. None
of them could have been caught by `gradlew build`, and several survived a careful read of the diff.

There is a second reason now. Since `RaidFaultBarrier` began guarding the tick loop, the battle
events and all 17 mixin injection points, a bug that used to stop the server instead survives as a
log line — better for players, worse for testing, because **a broken build now looks healthy from
the outside**. So half the checks read the server log and fail the run on anything a barrier
absorbed: a contained-failure report, a mixin apply error, or any exception with `com.cobbleraids`
in its stack.

## Setting up the server directory

Needs a Fabric server for the Minecraft version in `build.gradle`, plus in `mods/`:

- `fabric-api` matching `build.gradle`
- `Cobblemon-fabric-<version>.jar`
- `SkiesGUIs` — easy to miss; it is a hard dependency and the server refuses to start without it

Pointing it at a directory that also has the live modpack's other mods (mega_showdown, accessories,
architectury, owo-lib) is better than a clean room: that is the combination players actually run, and
mega_showdown patches the same Showdown files CobbleRaids does.

`server.properties` needs:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=<something>
online-mode=false
```

## Checking that the harness still bites

A green run only means something if a broken build goes red. To confirm, drop a truncated definition
into a world datapack and re-run:

```sh
mkdir -p world/datapacks/smoke_broken/data/cobbleraids/raids
echo '{ "pack": { "pack_format": 48, "description": "broken" } }' > world/datapacks/smoke_broken/pack.mcmeta
printf '{ "species": "cobblemon:garchomp", "level": ' > world/datapacks/smoke_broken/data/cobbleraids/raids/zzz_broken.json
```

The run should fail on *no fault was contained by a barrier* and *no exception with CobbleRaids in
the stack*, while the server still boots and the other 130 definitions still load — which is also a
live demonstration that malformed-definition isolation works. Delete the datapack afterwards; a
leftover world datapack silently shadows the mod's own `data/cobbleraids/raids/*.json` and has
produced a false bug report before.

## Proving the audit fires

`smoke_test.py` asserts the mod's own consistency sweep reports nothing wrong. That is only
meaningful if the sweep can report something wrong, so a second script proves it does:

```sh
python validation/smoke/prove_audit_detects.py --server-dir /path/to/testserver --java <jdk21 java>
```

It boots the server, injects a real inconsistency (a glow scoreboard team left listing a boss that
is not tracked -- exactly what leaked into `scoreboard.dat` before 0.8.49), and asserts the audit
names it and the offending id, then removes it and asserts the audit goes quiet again. A detector
nobody has watched fire is a detector nobody should trust.

## What is deliberately not covered

Anything needing a connected client. Raid recruitment, the shared battle, contribution and the reward
screen all require real players, and driving those with mineflayer is the flaky part of this rig —
its world model of a Cobblemon entity is unreliable enough that a failure does not distinguish "the
mod is broken" from "the bot lost the entity". Those stay manual for now. Everything here is
server-authoritative state, which RCON reaches directly and repeatably.

## economy_test.py

Drives a real reward claim end to end: queues one through the same `RaidRewardService.grant` the
victory path calls, claims it as a connected player via `execute as`, and reads the result out of
the player's own chat.

```sh
python validation/smoke/economy_test.py --server-dir <rig> --java <jdk21>/bin/java.exe
```

Two things only this can catch, both of which it has already caught.

**A loot table that does not load.** Minecraft rejects an entire table when any entry names an
unregistered item -- it does not skip the entry. With seven of the ten provider mods absent from
this rig, all four `specialty/<tier>` tables and all 21 boss tables failed to parse, so every
claim silently lost its specialty selection: no crash, no in-game symptom, one boot-time ERROR.
The fix put optional items behind their own tables; the check here asserts by name that no
*selection* table failed, because per-provider leaves failing on this rig is expected and their
failure costs only their own row.

**Contribution thresholds not firing.** They live in the reward policy while the claim is built at
victory, and the two were briefly wired to different things -- every player got zero bonus rolls
while the config, the tables and the logs all looked right. A solo victor has a 100% share, so the
claim must say three bonus rolls.

The claim message is printed, because what a player actually receives is the point:

```text
claim: Raid reward claimed. Contribution 100.0% awarded 3 bonus rolls.
       Granted: Great Ball x2, Potion x2, Potion x2, Great Ball x2, Super Potion x1
```

Five of six selections produced an item there; the sixth hit a leaf whose mod this rig does not
have. On a complete server all six produce.

`bot.js` echoes system messages for this. The claim result is sent to the player, not written to
the server log, so reading the log cannot tell a claim that granted six selections from one that
granted none.
