#!/usr/bin/env python3
"""Live scenarios for the Java side of a raid battle: real clients, a real Cobblemon, a real Showdown.

Why this exists
---------------
validation/showdown_fuzz.js covers the Showdown half of a raid battle exhaustively, but it cannot see
what Cobblemon's Java does with Showdown's answers: the AI actor's re-invoke, the invalid-choice
handling, the request/response bookkeeping, or our own RaidReconnectService. Every freeze so far has
lived on the boundary between the two. This drives that boundary with real connected players.

Each scenario boots a server, connects mineflayer bots (raidbot.js answers real battle prompts with
real battle_select_actions packets), runs a raid, and asserts that the battle keeps going -- the only
property that matters when the failure mode is a silent freeze.

Scenarios
---------
  duo-baseline  Two players, no disconnect. The control: if it fails, the harness or the rig is broken.
  solo-baseline The same with one player.
  solo-resume   The only player drops and reconnects inside the grace window. They must be asked for a
                move again and be able to finish the fight. (A lone player is not held on the Showdown
                side, so this passes on the 0.8.148 patch too; it guards the Java resume path.)
  hold-before-choice / hold-between-turns
                Two players; one drops (before choosing / between turns). The other must keep getting
                turns. Fails on any build where a held player's Java actor blocks the shared turn
                (everything before 0.8.151).
  held-player-returns-first
                Both drop; the player held on Showdown returns first and must be asked for a move for
                the turn in progress. Written to FAIL on the 0.8.148 patch (--jar <that build>) and pass
                on 0.8.149+. A scenario that cannot fail proves nothing, so run that comparison when
                changing one.

A held player's own Pokemon fainting (a forced switch owed to someone offline) is covered by the fuzz
harness only: the boss cannot target a held player, so no live scenario can force it.

Usage
-----
    python validation/smoke/java_layer_test.py --server-dir <rig> --java <jdk21 java> [--scenario NAME]
                                               [--jar <CobbleRaids jar>]

The server directory is edited: dynamic level scaling is switched off in config/cobbleraids/server.json
so the boss stays a known strength. Use a throwaway rig. Ports come from its server.properties.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from rcon import Rcon  # noqa: E402
from smoke_test import (  # noqa: E402
    CONTAINED_FAULT,
    MIXIN_TROUBLE,
    ROOT,
    SUSPECT_EXCEPTION,
    Result,
    Server,
    install_jar,
    project_version,
    rcon_port,
    read_password,
    server_port,
)

HERE = Path(__file__).resolve().parent
SOLO_PARTY = ("glaceon", 100)
SETTLE_SECONDS = 4.0
OP_BOTS = False
BOSS = "decidueye"
BOSS_AT = (0, 132, 0)


class BotProcess:
    """One raidbot.js process: commands in on stdin, timestamped events out, kept for assertions."""

    def __init__(self, name: str, port: int):
        self.name = name
        self.started = time.time()
        self.events: list[tuple[float, str]] = []
        self.lock = threading.Lock()
        self.process = subprocess.Popen(
            ["node", str(HERE / "raidbot.js"), name, "127.0.0.1", str(port)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
        )
        threading.Thread(target=self._drain, daemon=True).start()

    def _drain(self) -> None:
        # Drained continuously: a full pipe blocks node, stops its keep-alives, and the server drops it.
        for line in self.process.stdout:
            line = line.strip()
            if not line.startswith(f"[{self.name}] "):
                continue
            with self.lock:
                self.events.append((time.time(), line[len(self.name) + 3:]))

    def send(self, command: str) -> None:
        try:
            self.process.stdin.write(command + "\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    def since(self, moment: float, needle: str) -> list[str]:
        with self.lock:
            return [text for when, text in self.events if when >= moment and needle in text]

    def count(self, needle: str, since: float = 0.0) -> int:
        return len(self.since(since, needle))

    def wait_for(self, needle: str, timeout: float, since: float | None = None) -> bool:
        start = self.started if since is None else since
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.count(needle, start):
                return True
            time.sleep(0.5)
        return False

    def alive(self) -> bool:
        return self.process.poll() is None

    def stop(self) -> None:
        self.send("QUIT")
        try:
            self.process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            self.process.kill()


class Rig:
    """The running server plus the helpers every scenario needs."""

    def __init__(self, server_dir: Path, java: Path):
        self.dir = server_dir
        self.server = Server(server_dir, java)
        self.port = server_port(server_dir)
        self.rcon_port = rcon_port(server_dir)
        self.password = read_password(server_dir)
        self.bots: list[BotProcess] = []
        self.tag = str(int(time.time()) % 100000)

    def rcon(self) -> Rcon:
        return Rcon("127.0.0.1", self.rcon_port, self.password, timeout=60)

    def bot(self, role: str) -> BotProcess:
        # A fresh name per run: parties and raid history persist in the world by name.
        bot = BotProcess(f"{role}{self.tag}", self.port)
        self.bots.append(bot)
        return bot

    def command(self, text: str) -> str:
        with self.rcon() as rcon:
            return rcon.command(text)

    def give(self, bot: BotProcess, species: str, level: int) -> None:
        reply = self.command(f"pokegiveother {bot.name} {species} level={level}")
        # Checked, not assumed: a plain `pokegive` from the console answers "A player is required" and
        # gives nothing, and an empty party looks exactly like "raids do not start".
        assert reply.startswith("Gave"), f"could not give {species} to {bot.name}: {reply.strip()[:120]}"

    def settle(self, bots: list[BotProcess]) -> None:
        """Let freshly joined players finish loading before anything is done to them."""
        for bot in bots:
            assert bot.wait_for("READY", 90), f"{bot.name} never spawned"
        if OP_BOTS:
            for bot in bots:
                self.command(f"op {bot.name}")
        time.sleep(SETTLE_SECONDS)

    def start_raid(self, players: list[BotProcess]) -> None:
        x, y, z = BOSS_AT
        self.command("cobbleraids despawn all")
        self.command(f"cobbleraids spawn {BOSS} {x} {y} {z}")
        for bot in players:
            self.command(f"tp {bot.name} {x + 2} {y} {z + 2}")
        time.sleep(2)
        for bot in players:
            self.command(f"cobbleraids debug join {bot.name}")

    def save_events(self, scenario: str) -> None:
        """Every bot's timeline, kept next to the server log: a failed run is read from this."""
        path = self.dir / "logs" / f"java_layer_{scenario}.events"
        started = min((bot.started for bot in self.bots), default=time.time())
        lines = []
        for bot in self.bots:
            with bot.lock:
                lines += [(when, f"{when - started:7.1f}s [{bot.name}] {text}") for when, text in bot.events]
        path.write_text("\n".join(line for _, line in sorted(lines)), encoding="utf-8")
        print(f"  events: {path}")

    def shutdown(self) -> None:
        for bot in self.bots:
            bot.stop()
        self.server.stop()


def prepare_config(server_dir: Path) -> None:
    """A boss of known strength: no level scaling, so what a bot does to it is predictable."""
    path = server_dir / "config" / "cobbleraids" / "server.json"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    patched = re.sub(r'("dynamic_level"\s*:\s*\{\s*"enabled"\s*:\s*)true', r"\1false", text, count=1)
    if patched != text:
        path.write_text(patched, encoding="utf-8")


# ---------------------------------------------------------------------------------------------
# Scenarios. Each returns a list of Result; none of them assume the others ran.
# ---------------------------------------------------------------------------------------------

def scenario_held_player_returns_first(rig: Rig) -> list[Result]:
    """Both players drop; the one who was HELD on Showdown comes back first. The case the fuzz found.

    The first to drop is held on the Showdown side. The second is the last one out, so nothing is sent
    to Showdown for them and the turn simply waits on their choice. When the held player returns, the
    turn in progress was requested while they were held, so their request is the synthetic "wait" and
    nothing re-issues it: before 0.8.149 they came back to a battle screen that never asked them for
    anything. They must be prompted for the turn already in progress.
    """
    results: list[Result] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append(Result(name, ok, detail))

    first, second = rig.bot("First"), rig.bot("Second")
    rig.settle([first, second])
    for bot in (first, second):
        rig.give(bot, "glaceon", 100)
        bot.send("MOVE lastresort")      # harmless: the fight must outlast the whole scenario
        bot.send("FIGHT")
    rig.start_raid([first, second])
    started = all(bot.wait_for("BATTLE_INIT", 120) for bot in (first, second))
    check("both players are in a started battle", started, "the lobby never became a battle")
    if not started:
        return results

    time.sleep(1)
    first.send("DROP")
    check("the first player drops and is held", first.wait_for("END", 15))
    time.sleep(10)
    check("the raid keeps going for the player still connected", second.count("REQUEST") >= 2,
          "the remaining player is not getting turns: " + rig.command("cobbleraids debug battle").strip())

    second.send("DROP")
    check("the second player drops as the last one out", second.wait_for("END", 15))
    time.sleep(6)

    back = rig.bot("First")              # same role and run tag, so the same name: the same player
    assert back.name == first.name
    back.send("MOVE blizzard")
    back.send("FIGHT")
    returned = back.wait_for("READY", 90)
    check("the held player reconnects inside the grace window", returned)
    if not returned:
        return results
    rejoined = time.time()

    prompted = back.wait_for("PROMPT", 40, rejoined)
    check("the returning held player is asked for a move", prompted,
          "no prompt in 40s -- they came back to a turn that never asked them: "
          + rig.command("cobbleraids debug battle").strip())
    return results


def _hold_keeps_moving(rig: Rig, drop_after_turns: int) -> list[Result]:
    """Two players; one drops; the other must keep getting turns. drop_after_turns=0 drops the player
    before they have chosen anything; N>0 drops them once the other player has seen N turns."""
    results: list[Result] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append(Result(name, ok, detail))

    leaver, stayer = rig.bot("Leaver"), rig.bot("Stayer")
    rig.settle([leaver, stayer])
    for bot in (leaver, stayer):
        rig.give(bot, "glaceon", 100)
        bot.send("MOVE lastresort")     # harmless: the fight has to last long enough to measure
        bot.send("FIGHT")
    rig.start_raid([leaver, stayer])
    started = all(bot.wait_for("BATTLE_INIT", 120) for bot in (leaver, stayer))
    check("both players are in a started battle", started, "the lobby never became a battle")
    if not started:
        return results

    if drop_after_turns == 0:
        time.sleep(1)
    else:
        deadline = time.time() + 60
        while stayer.count("REQUEST") < drop_after_turns and time.time() < deadline:
            time.sleep(0.5)
        check(f"the battle reaches turn {drop_after_turns} before the drop", stayer.count("REQUEST") >= drop_after_turns)
    leaver.send("DROP")
    check("one player's connection drops", leaver.wait_for("END", 15))
    dropped_at = time.time()

    time.sleep(40)
    turns = stayer.count("REQUEST", dropped_at)
    check("the remaining player keeps getting turns", turns >= 3,
          f"only {turns} turn(s) in 40s after the drop -- the raid is waiting on the player who left: "
          + rig.command("cobbleraids debug battle").strip())
    return results


def scenario_hold_before_choice(rig: Rig) -> list[Result]:
    return _hold_keeps_moving(rig, 0)


def scenario_hold_between_turns(rig: Rig) -> list[Result]:
    return _hold_keeps_moving(rig, 2)


def scenario_solo_resume(rig: Rig) -> list[Result]:
    results: list[Result] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append(Result(name, ok, detail))

    solo = rig.bot("Solo")
    rig.settle([solo])
    rig.give(solo, "glaceon", 100)
    solo.send("MOVE lastresort")   # harmless, so the fight is still going when the player returns
    solo.send("FIGHT")
    rig.start_raid([solo])
    started = solo.wait_for("BATTLE_INIT", 120)
    check("the player is in a started battle", started, "the lobby never became a battle")
    if not started:
        return results

    time.sleep(4)
    solo.send("DROP")
    check("the player's connection drops", solo.wait_for("END", 15))
    time.sleep(8)  # well inside the 300s grace window

    back = rig.bot("Solo")           # same role and run tag, so the same name: the same player returning
    assert back.name == solo.name
    back.send("MOVE blizzard")
    back.send("FIGHT")
    returned = back.wait_for("READY", 90)
    check("the player reconnects inside the grace window", returned)
    if not returned:
        return results
    rejoined = time.time()

    # The property: they are asked for a move again, and then they can finish. Without a request for
    # the turn already in progress, they come back to a battle screen that waits on them forever.
    prompted = back.wait_for("PROMPT", 60, rejoined)
    check("the returning player is asked for a move again", prompted,
          "no battle prompt reached the returning player in 60s -- the turn waits on someone who was never asked")
    if not prompted:
        return results
    ended = back.wait_for("BATTLE_END", 120, rejoined)
    check("the returning player can finish the raid", ended, "the battle never ended")
    return results


def scenario_solo_baseline(rig: Rig) -> list[Result]:
    """The control for solo-resume: the same raid with no disconnect. If this fails, the harness (or the
    rig) is the problem, and nothing a disconnect scenario reports can be trusted."""
    results: list[Result] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append(Result(name, ok, detail))

    solo = rig.bot("Base")
    rig.settle([solo])
    rig.give(solo, *SOLO_PARTY)
    solo.send("MOVE blizzard")
    solo.send("FIGHT")
    rig.start_raid([solo])
    started = solo.wait_for("BATTLE_INIT", 120)
    check("the player is in a started battle", started, "the lobby never became a battle")
    if not started:
        return results
    prompted = solo.wait_for("PROMPT", 30)
    check("the player is asked for a move", prompted,
          "no battle prompt in 30s with nobody disconnected: " + rig.command("cobbleraids debug battle").strip())
    check("the player can finish the raid", solo.wait_for("BATTLE_END", 120), "the battle never ended")
    return results


def scenario_solo_run_button(rig: Rig) -> list[Result]:
    """What the 1.8 client's Run button does in a raid.

    In a singles battle against a wild actor the 1.8 client sends FLEE_ATTEMPT directly, where the 1.7.3
    client opened a forfeit confirmation first. A raid must not be leavable that way: the only exits are
    the ones RaidBattleSelectActionsMixin governs (forfeit/leave), so a flee attempt has to leave the
    player in the battle and be asked for a move again.
    """
    results: list[Result] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append(Result(name, ok, detail))

    solo = rig.bot("Run")
    rig.settle([solo])
    rig.give(solo, *SOLO_PARTY)
    solo.send("MOVE blizzard")
    rig.start_raid([solo])
    started = solo.wait_for("BATTLE_INIT", 120)
    check("the player is in a started battle", started, "the lobby never became a battle")
    if not started:
        return results
    check("the player is asked for a move", solo.wait_for("PROMPT", 30), "no battle prompt in 30s")
    # Autofight goes on first so the re-prompt the flee attempt provokes is answered; one that arrives
    # while autofight is off is simply never answered, and the fight would look stalled.
    solo.send("FIGHT")
    before = time.time()
    solo.send("FLEE")
    check("the flee attempt is sent", solo.wait_for("SENT FLEE_ATTEMPT", 10, since=before), "bot never sent it")
    check("the player is told where the exit is", solo.wait_for("/cobbleraids leave", 10, since=before),
          f"no pointer to the leave command: {solo.since(before, 'MSG')}")
    check("the player is asked for a move again", solo.wait_for("PROMPT", 20, since=before),
          "no new prompt: " + rig.command("cobbleraids debug battle").strip())
    check("the battle has not ended", solo.count("BATTLE_END", before) == 0, "BATTLE_END after a flee attempt")
    status = rig.command("cobbleraids debug battle").strip()
    check("the raid battle is still registered", "no active" not in status.lower() and bool(status), status)
    print(f"  after the flee attempt: {status[:200]}")
    print(f"  bot saw: {solo.since(before, '')[:12]}")
    check("the player can still finish the raid", solo.wait_for("BATTLE_END", 120), "the battle never ended")
    return results


def scenario_admin_queue_commands(rig: Rig) -> list[Result]:
    """The operator commands for a queue a player is holding: list who holds something, clear one player.

    Unclaimed rewards and capture sessions share PendingHolderCommands for both, so every message is
    asserted word for word against what each had before they were merged. A real connected player is
    needed because the target is an online-player argument.

    The rig world is not empty: earlier runs leave offline holders behind. So the roster is judged by
    how it changes (one more holder after a grant, back to where it was after a clear) and by whether
    this run's player is in it, never by it being empty.
    """
    results: list[Result] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append(Result(name, ok, detail))

    def holders(listing: str, noun: str, empty: str) -> int:
        if empty in listing:
            return 0
        match = re.search(rf"Players with {noun} \((\d+)\)", listing)
        return int(match.group(1)) if match else -1

    bot = rig.bot("Queue")
    rig.settle([bot])
    name = bot.name

    # --- reward queue
    empty_rewards = "Nobody has an unclaimed raid reward."
    before = holders(rig.command("cobbleraids reward list"), "unclaimed rewards", empty_rewards)
    check("the reward roster is readable", before >= 0, "neither the empty line nor a header was printed")

    granted = rig.command(f"cobbleraids reward grant {name} cobbleraids:garchomp")
    check("a reward can be queued for the player", "Queued" in granted, granted.strip())
    roster = rig.command("cobbleraids reward list")
    check("the roster counts one more holder and names the player",
          holders(roster, "unclaimed rewards", empty_rewards) == before + 1 and name in roster,
          f"was {before}: {roster[:160].strip()}")

    cleared = rig.command(f"cobbleraids reward clear {name}")
    check("clearing reports what it removed",
          f"Cleared 1 unclaimed raid reward(s) from {name}." in cleared, cleared.strip())
    again = rig.command(f"cobbleraids reward clear {name}")
    check("clearing an empty queue says so", f"{name} had no unclaimed raid rewards." in again, again.strip())
    after = rig.command("cobbleraids reward list")
    check("the roster is back to where it was and no longer lists the player",
          holders(after, "unclaimed rewards", empty_rewards) == before and name not in after,
          f"expected {before}: {after[:160].strip()}")

    # --- capture sessions: none can be created without winning a raid, so only the empty paths.
    empty_capture = "Nobody has an active raid capture session."
    status = rig.command("cobbleraids capture status")
    check("the capture roster is readable, and does not list this player",
          holders(status, "active capture sessions", empty_capture) >= 0 and name not in status, status[:160].strip())
    nothing = rig.command(f"cobbleraids capture clear {name}")
    check("clearing a player with no capture session says so",
          f"{name} had no active raid capture session." in nothing, nothing.strip())
    return results


def scenario_duo_baseline(rig: Rig) -> list[Result]:
    """Two players, no disconnect: the control for every multiplayer scenario."""
    results: list[Result] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append(Result(name, ok, detail))

    first, second = rig.bot("DuoA"), rig.bot("DuoB")
    rig.settle([first, second])
    for bot in (first, second):
        rig.give(bot, "glaceon", 100)
        bot.send("MOVE blizzard")
        bot.send("FIGHT")
    rig.start_raid([first, second])
    started = all(bot.wait_for("BATTLE_INIT", 120) for bot in (first, second))
    check("both players are in a started battle", started, "the lobby never became a battle")
    if not started:
        return results
    # What each player is told when they join. The joiner is standing beside the boss, so a broadcast
    # to everyone in range reached them too: "Joined raid: 1/4" immediately followed by
    # "<own name> joined the raid (1/4)", which read as the same line printed twice.
    for bot, other in ((first, second), (second, first)):
        check(f"{bot.name} gets exactly one personal join line", bot.count("Joined raid:") == 1,
              f"saw {bot.count('Joined raid:')}: {bot.since(0.0, 'oined')}")
        check(f"{bot.name} is not also told about their own join", bot.count(f"{bot.name} joined the raid") == 0,
              str(bot.since(0.0, "oined")))
        check(f"{bot.name} still hears when {other.name} joins", bot.count(f"{other.name} joined the raid") >= 1,
              "the other player's join was not announced to this one")
    prompted = all(bot.wait_for("PROMPT", 30) for bot in (first, second))
    check("both players are asked for a move", prompted,
          "no battle prompt in 30s: " + rig.command("cobbleraids debug battle").strip())
    check("the raid can be finished", first.wait_for("BATTLE_END", 120), "the battle never ended")
    return results


SCENARIOS = {
    "hold-before-choice": scenario_hold_before_choice,
    "hold-between-turns": scenario_hold_between_turns,
    "duo-baseline": scenario_duo_baseline,
    "solo-baseline": scenario_solo_baseline,
    "solo-run-button": scenario_solo_run_button,
    "admin-queue-commands": scenario_admin_queue_commands,
    "held-player-returns-first": scenario_held_player_returns_first,
    "solo-resume": scenario_solo_resume,
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("SMOKE_JAVA", "java")))
    parser.add_argument("--jar", type=Path, help="CobbleRaids jar to install (default: this build)")
    parser.add_argument("--scenario", choices=sorted(SCENARIOS) + ["all"], default="all")
    parser.add_argument("--solo-party", default="glaceon:100", help="species:level for the solo scenarios")
    parser.add_argument("--settle", type=float, default=4.0, help="seconds to wait after players join")
    parser.add_argument("--op", action="store_true", help="op the bots before the scenario")
    parser.add_argument("--keep-config", action="store_true", help="do not switch dynamic level scaling off")
    args = parser.parse_args()

    global SOLO_PARTY, SETTLE_SECONDS, OP_BOTS
    SETTLE_SECONDS, OP_BOTS = args.settle, args.op
    species, level = args.solo_party.split(":")
    SOLO_PARTY = (species, int(level))
    server_dir = args.server_dir.resolve()
    jar = (args.jar or ROOT / "build" / "libs" / f"CobbleRaids-{project_version()}.jar").resolve()
    install_jar(server_dir, jar)
    if not args.keep_config:
        prepare_config(server_dir)

    names = sorted(SCENARIOS) if args.scenario == "all" else [args.scenario]
    results: list[Result] = []
    for name in names:
        print(f"== {name}  ({jar.name})")
        rig = Rig(server_dir, args.java)
        rig.server.start()
        try:
            rig.server.wait_until_ready()
            before = len(rig.server.read_log().splitlines())
            for result in SCENARIOS[name](rig):
                results.append(Result(f"{name}: {result.name}", result.passed, result.detail))
            time.sleep(2)
            log = "\n".join(rig.server.read_log().splitlines()[before:])
            results.append(Result(f"{name}: no fault was contained by a barrier",
                                  not CONTAINED_FAULT.search(log),
                                  "\n      ".join(l for l in log.splitlines() if CONTAINED_FAULT.search(l))[:300]))
            results.append(Result(f"{name}: no exception with CobbleRaids in the stack",
                                  not SUSPECT_EXCEPTION.search(log), ""))
        except Exception as exc:  # noqa: BLE001
            results.append(Result(f"{name}: scenario ran to completion", False, repr(exc)))
        finally:
            rig.save_events(name)
            rig.shutdown()

    print()
    width = max(len(result.name) for result in results)
    failed = [result for result in results if not result.passed]
    for result in results:
        print(f"  [{'PASS' if result.passed else 'FAIL'}] {result.name:<{width}}")
        if not result.passed and result.detail:
            print(f"      {result.detail}")
    print()
    if failed:
        print(f"Java-layer live scenarios: FAIL -- {len(failed)} of {len(results)} checks")
        raise SystemExit(1)
    print(f"Java-layer live scenarios: PASS -- {len(results)} checks")


if __name__ == "__main__":
    main()
