#!/usr/bin/env python3
"""Phase 32 invariants: raid boss lifecycle integrity and raid boss healing.

Both fixes are easy to silently undo with a small edit, so the properties that make them
work are asserted here rather than left to review.
"""
import re
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
    """Boss-tracking wiring only. The rules themselves are tested properly now.

    This block used to assert the despawn logic by matching exact source text -- the shape of the
    maintenance loop, the idle-timer expression, the name of the tracking field. It went stale twice
    (once when ActiveSpawn became a class, once when it moved into ActiveRaidSpawnTracker) and both
    times it failed CI on a change that broke nothing, which is the worst thing a check can do: it
    trains people to edit the check rather than believe it.

    Those rules are now covered by ActiveRaidSpawnTrackerTest, which exercises them directly and
    fails when the behaviour is wrong rather than when the spelling changes -- including the
    re-entrant discard that caused f261da0. What is left here is what a unit test cannot see: that
    the pieces are actually connected to the game, and that the tracking key is not an entity
    reference.
    """
    entry = read(JAVA / "spawn/TrackedRaidSpawn.java")
    initializer = read(JAVA / "CobbleRaids.java")

    # The one structural rule worth pinning in the source. An entity unloaded with its chunk reports
    # isRemoved() == true while still existing, so a cached PokemonEntity reference cannot be the
    # tracking key -- a regression here would look correct in every unit test, because the test
    # supplies its own boss handle.
    assert "final class TrackedRaidSpawn {" in entry
    fields = entry.split("final class TrackedRaidSpawn {", 1)[1].split("TrackedRaidSpawn(", 1)[0]
    assert "PokemonEntity" not in fields, fields
    assert "BlockPos position" in fields, fields

    # Wiring. Nothing below is reachable from a unit test: these are the callbacks that connect the
    # tracker to the running server, and losing one is silent.
    #
    # Matched as "this event is registered, and this handler is named in the registration" rather
    # than as one exact expression. The exact form broke the day after it was written, when the
    # registrations were wrapped in fault barriers and a method reference became a lambda -- a
    # correct change, failing CI, which is precisely the trap validation/README.md warns about.
    # Whether the handler is wrapped is validate_callback_guards.py's job, not this one's.
    for event, handler in (
        ("ServerEntityEvents.ENTITY_LOAD", "onNaturalBossLoaded"),
        ("ServerEntityEvents.ENTITY_UNLOAD", "onEntityUnloaded"),
        ("ServerWorldEvents.UNLOAD", "onLevelUnloaded"),
    ):
        registration = re.search(
            re.escape(event) + r"\.register\((?:[^;]*?)" + re.escape(handler), initializer, re.S)
        assert registration, f"lost the boss-lifecycle hook: {event} -> {handler}"

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
