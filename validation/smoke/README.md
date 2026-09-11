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

## What is deliberately not covered

Anything needing a connected client. Raid recruitment, the shared battle, contribution and the reward
screen all require real players, and driving those with mineflayer is the flaky part of this rig —
its world model of a Cobblemon entity is unreliable enough that a failure does not distinguish "the
mod is broken" from "the bot lost the entity". Those stay manual for now. Everything here is
server-authoritative state, which RCON reaches directly and repeatably.
