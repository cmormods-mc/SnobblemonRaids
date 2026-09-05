#!/usr/bin/env python3
"""Phase 32 invariants: raid boss lifecycle integrity and raid boss healing.

Both fixes are easy to silently undo with a small edit, so the properties that make them
work are asserted here rather than left to review.
"""
import json
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/com/cobbleraids"
SHOWDOWN = ROOT / "src/main/resources/assets/cobbleraids/showdown"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def validate_despawn_integrity() -> None:
    scheduler = read(JAVA / "spawn/RaidSpawnScheduler.java")

    # An entity unloaded with its chunk reports isRemoved() == true while still existing, so a
    # cached PokemonEntity reference cannot be the tracking key.
    assert "private record ActiveSpawn(" in scheduler
    record = scheduler.split("private record ActiveSpawn(", 1)[1].split(")", 1)[0]
    assert "PokemonEntity" not in record, record
    assert "BlockPos position" in record, record

    # Bosses are resolved on demand, and "does not resolve" must never be read as "is gone".
    assert "private static PokemonEntity resolveBoss(" in scheduler
    assert "level.getEntity(bossId)" in scheduler
    assert "boss != null && boss.isRemoved()" in scheduler

    # Idle time has to keep accruing while the boss is unloaded, which is precisely when no
    # player can be near it.
    assert "if (boss != null) {" in scheduler
    assert "idleTicks < active.despawnSeconds() * 20L" in scheduler

    # The orphan sweep, and the guard that keeps it from eating a boss we are mid-spawn.
    assert "public static void onNaturalBossLoaded(" in scheduler
    assert "if (spawningTrackedBoss) return;" in scheduler
    assert "spawningTrackedBoss = true;" in scheduler
    assert "ACTIVE.containsKey(pokemon.getUUID())" in scheduler

    initializer = read(JAVA / "CobbleRaids.java")
    assert "ServerEntityEvents.ENTITY_LOAD.register(RaidSpawnScheduler::onNaturalBossLoaded)" in initializer

    config = read(JAVA / "config/CobbleRaidsConfig.java")
    assert "despawnPlayerRadius >= maxDistanceFromPlayer" in config, "missing keep-alive radius warning"


def validate_raid_healing() -> None:
    patch = read(SHOWDOWN / "raid-patch.js")
    conditions = read(SHOWDOWN / "mods/conditions.js")

    # Healing must reach the Java pool through -raidheal on every route Showdown can take:
    # Battle#heal (residual, drain), Pokemon#heal (moves with a `heal:` property), and the
    # `hp === maxhp` guards that run inside a move.
    assert "Battle.prototype.heal = function" in patch
    assert "Pokemon.prototype.heal = function" in patch
    assert "BattleActions.prototype.runMove = function" in patch
    assert "'-raidheal'" in patch
    assert "lendRaidBossHealHeadroom" in patch and "repinRaidBoss" in patch

    # Vanilla -heal lines carry the pinned simulator health string and would overwrite the
    # authoritative raid percentage on every client.
    assert "if (parts[0] === '-heal') return;" in patch

    # onTryHeal returning 0 made Battle#heal report failure to its callers; boss healing now
    # lives in raid-patch.js so the TryHeal event stays vanilla.
    assert "onTryHeal(" not in conditions, "raidboss.onTryHeal must not intercept healing"
    assert "onDamage(" in conditions


def validate_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        patch = archive.read("assets/cobbleraids/showdown/raid-patch.js").decode("utf-8")
        conditions = archive.read("assets/cobbleraids/showdown/mods/conditions.js").decode("utf-8")
        assert "Battle.prototype.heal = function" in patch
        assert "onTryHeal(" not in conditions
        manifest = json.loads(archive.read("fabric.mod.json"))
        assert manifest["id"] == "cobbleraids"


def main() -> None:
    validate_despawn_integrity()
    validate_raid_healing()
    for argument in sys.argv[1:]:
        validate_jar(Path(argument))
    print("Phase 32 raid integrity validation: PASS")


if __name__ == "__main__":
    main()
