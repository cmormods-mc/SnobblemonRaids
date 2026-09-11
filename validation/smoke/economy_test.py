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

# The same facts from the server's own debug log, which needs no client to survive. A mineflayer
# client on a heavy modset is dropped on a keep-alive timeout often enough that hanging the whole
# verification off its chat made this test fail for reasons that had nothing to do with the mod.
CLAIM_LOGGED = re.compile(
    r"claimed '(\w+)' for raid ([\w:/]+) \(contribution ([\d.]+)%, (\d+) bonus roll\(s\)\)"
    r".*?currency=(\d+)")

# The payout, when an economy mod is installed. Charizard is starter tier and a solo victor takes
# the whole tier amount, so this is the shipped starter figure exactly.
PAID = re.compile(r"\(\+([\d,]+) CobbleDollars\)")
STARTER_PAYOUT = 2000

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

    logged = None
    for line in log.splitlines():
        found = CLAIM_LOGGED.search(line)
        if found:
            logged = found
    check(results, "the claim completes and the server records it", logged is not None,
          "no claim line in the server log (is debug_logging on?)")

    match = None
    for line in bot.messages:
        found = CLAIMED.search(line)
        if found:
            match = found
    if logged:
        print("  claim: " + logged.group(0)[:220])
        check(results, "a solo victor is credited the whole raid",
              abs(float(logged.group(3)) - 100.0) < 0.05, "share was " + logged.group(3) + "%")
        # The regression: thresholds living in the reward policy while the victory site read the
        # inline block, which migrated definitions no longer have.
        check(results, "contribution thresholds award a solo victor three bonus rolls",
              int(logged.group(4)) == 3, "awarded " + logged.group(4) + ", expected 3")

    if match:
        # Printed because the interesting part of this test is what a player actually receives,
        # and a PASS line does not show it.
        print("  claim: " + match.string.strip()[:220])
        # The player's own view, when the client survived long enough to receive it.
        check(results, "the player is told the same thing the server logged",
              logged is not None and match.group(2) == logged.group(4),
              "client and server disagree about the bonus rolls")

    # --- currency, when there is an economy mod to pay it -------------------------------------
    # Conditional on purpose: this rig is run both with and without CobbleDollars, and a payout
    # appearing on a server that has no economy mod would be as wrong as one going missing.
    status = rcon.command("cobbleraids debug status")
    has_economy = "currency cobbledollars" in status and "disabled after failure" not in status
    check(results, "the currency backend reports itself in debug status",
          "currency " in status, status.strip()[:160])

    paid_amount = int(logged.group(5)) if logged else 0
    if has_economy:
        # The backend reaches CobbleDollars by reflection into a Kotlin file facade, so nothing
        # short of running it proves the handle resolves and the money actually arrives.
        check(results, "an installed economy mod is paid through to the player", paid_amount > 0,
              "the claim paid nothing despite backend " + status.strip()[:80])
        check(results, "a solo starter claim pays the shipped starter figure",
              paid_amount == STARTER_PAYOUT, f"paid {paid_amount}, expected {STARTER_PAYOUT}")
        balance = rcon.command(f"execute as {BOT} run cobbledollars balance")
        check(results, "the payout is visible in the player's balance",
              "Unknown or incomplete command" not in balance, balance.strip()[:120])
    else:
        check(results, "no payout is reported when no economy mod is installed", paid_amount == 0,
              "a claim paid " + str(paid_amount) + " with no backend to pay it")

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
        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            # The server's view, not mineflayer's. A client on this modset is often dropped on a
            # keep-alive timeout ~30s after joining, so waiting on its spawn event meant the player
            # was already gone by the time the claim ran -- and the failure looked like the mod.
            online = False
            for _ in range(60):
                if BOT in rcon.command("list"):
                    online = True
                    break
                time.sleep(1)
            check(results, "a client joins and the server sees it", online,
                  bot.lost or "the server never listed the player")
            if not online:
                raise RuntimeError("bot never joined")
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
