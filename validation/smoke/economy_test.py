#!/usr/bin/env python3
"""Drives a real reward claim end to end on a live server.

Everything else about the reward economy is checked statically: the manifest proves the item ids
exist, the probability analyzer proves the tables produce the advertised rates, the unit tests
prove the resolver builds the right plan. None of that runs the game. This does: it queues a claim
through the same RaidRewardService.grant the victory path calls, claims it as a real player, and
reads back what the server actually said.

Two things only this can catch.

The first is a loot table that does not load. Minecraft rejects an entire table when any entry
names an unregistered item, so on a server missing a provider mod all four specialty/<tier> tables
and all 21 boss tables silently failed to parse -- every claim losing its specialty selection with
no symptom beyond one boot-time ERROR. That is why the selection tables are checked by name here,
against the log, rather than trusted.

The second is the contribution thresholds firing at all. They live in the reward policy, the
claim is built at victory, and the two were briefly wired to different things: every player got
zero bonus rolls while the config, the tables and the logs all looked correct. A solo victor has a
100% share, so this claim must say three bonus rolls. Anything else means they are dead again.

    python validation/smoke/economy_test.py --server-dir <rig> --java <jdk21>/bin/java.exe
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from load_test import Bot  # noqa: E402
from rcon import Rcon  # noqa: E402
from smoke_test import Result, Server, project_version, read_password, server_port  # noqa: E402

BOT = "EconomyBot"

# The claim message the server sends the player. The bonus-roll count is the load-bearing part.
CLAIMED = re.compile(r"Raid reward claimed\. Contribution ([\d.]+)% awarded (\d+) bonus roll")

# A selection table failing to parse is the catastrophic case: one missing mod taking out a whole
# tier. Per-provider leaves failing is expected on a rig that has seven of the ten providers absent.
SELECTION_TABLE_FAILED = re.compile(
    r"Couldn't parse element minecraft:loot_table/cobbleraids:specialty/(?:boss/)?[a-z_]+ ")


def check(results: list[Result], name: str, passed: bool, detail: str = "") -> None:
    results.append(Result(name, passed, detail))


class ChattyBot(Bot):
    """A bot that also keeps what the server said to it.

    The claim result is sent to the player, not written to the server log, so reading the log
    cannot tell a claim that granted six selections from one that granted none. This is the only
    vantage point that sees what the player sees.
    """

    def __init__(self, name: str, port: int):
        self.messages: list[str] = []
        super().__init__(name, port)

    def _drain(self) -> None:
        for line in self.process.stdout:
            if "READY" in line:
                self.ready.set()
            elif "KICKED" in line or line.strip().endswith("END null"):
                self.lost = line.strip()
                self.ready.set()
            elif " MSG " in line:
                self.messages.append(line.split(" MSG ", 1)[1].strip())


def run_checks(rcon, server, bot, results: list[Result]) -> None:
    """Everything that needs a live server, with the socket already open."""
    # --- the tables the economy actually rolls ----------------------------------------------
    for table in ("cobbleraids:specialty/legendary", "cobbleraids:general/legendary",
                  "cobbleraids:base/legendary", "cobbleraids:specialty/boss/charizard"):
        response = rcon.command("cobbleraids debug loot " + table)
        check(results, "loot preview resolves " + table,
              "Unknown or incomplete command" not in response and "does not exist" not in response,
              response.strip()[:120])

    # --- a claim, start to finish ------------------------------------------------------------
    granted = rcon.command(f"cobbleraids reward grant {BOT} cobbleraids:charizard")
    check(results, "an admin can queue a reward through the victory path",
          "Queued" in granted, granted.strip()[:160])

    listed = rcon.command(f"cobbleraids reward list {BOT}")
    check(results, "the queued reward is waiting for the player",
          "no unclaimed" not in listed.lower(), listed.strip()[:160])

    # execute as gives the command a player source without the bot having to type anything.
    rcon.command(f"execute as {BOT} run cobbleraids reward claim all")
    time.sleep(3)
    log = server.read_log()

    match = None
    for line in bot.messages:
        found = CLAIMED.search(line)
        if found:
            match = found
    check(results, "the claim completes and reports itself to the player", match is not None,
          "no claim message reached the client; saw: " + " | ".join(bot.messages[-3:])[:200])

    if match:
        # Printed because the interesting part of this test is what a player actually receives,
        # and a PASS line does not show it.
        print("  claim: " + match.string.strip()[:220])
        share, bonus = float(match.group(1)), int(match.group(2))
        check(results, "a solo victor is credited the whole raid", abs(share - 100.0) < 0.05,
              f"share was {share}%")
        # The regression: thresholds living in the policy while the victory site read the
        # inline block, which migrated definitions no longer have.
        check(results, "contribution thresholds award a solo victor three bonus rolls",
              bonus == 3, f"awarded {bonus}, expected 3")

    after = rcon.command(f"cobbleraids reward list {BOT}")
    check(results, "claiming consumes the claim", "no unclaimed" in after.lower(),
          after.strip()[:160])

    audit = rcon.command("cobbleraids debug audit")
    check(results, "the consistency audit finds nothing wrong after a claim",
          "reward-policy-legacy" not in audit and "stranded" not in audit,
          audit.strip()[:200])

    # --- what only the log knows --------------------------------------------------------------
    # (the matches are not listed: the check is binary and the log names them)
    check(results, "no specialty selection table failed to load",
          not SELECTION_TABLE_FAILED.search(log),
          "selection tables failed to parse; one missing mod is taking out a whole tier")
    check(results, "every definition is governed by the reward policy",
          "all governed by the reward policy" in log,
          "a definition still names rewards of its own")
    check(results, "no reward grant failed", "Raid reward grant failed" not in log)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=None)
    args = parser.parse_args()

    server_dir = args.server_dir.resolve()
    java = args.java or Path(os.environ.get("SMOKE_JAVA", "java"))
    results: list[Result] = []

    server = Server(server_dir, java)
    print(f"Booting server ({server.log})")
    server.start()
    bot: ChattyBot | None = None
    try:
        server.wait_until_ready()
        port = server_port(server_dir)
        bot = ChattyBot(BOT, port)
        check(results, "a client connects and stays connected", bot.wait_ready(),
              bot.lost or "")
        if bot.lost:
            raise RuntimeError("bot never connected: " + str(bot.lost))
        time.sleep(2)

        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            run_checks(rcon, server, bot, results)

    except Exception as exc:  # noqa: BLE001
        check(results, "economy end-to-end run", False, str(exc))
    finally:
        if bot is not None:
            bot.stop()
        print("Stopping server")
        server.stop()

    print()
    width = max(len(result.name) for result in results)
    failed = 0
    for result in results:
        mark = "PASS" if result.passed else "FAIL"
        failed += 0 if result.passed else 1
        print(f"  [{mark}] {result.name:<{width}}  {result.detail if not result.passed else ''}")
    print(f"\n{len(results) - failed}/{len(results)} checks passed"
          f" against CobbleRaids {project_version()}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
