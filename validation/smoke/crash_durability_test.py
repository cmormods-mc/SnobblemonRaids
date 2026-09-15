#!/usr/bin/env python3
"""Proves a won raid's reward survives the server being killed before its next autosave.

RaidRewardService.grant used to mark the pending-reward SavedData dirty and nothing more, so a new
reward reached disk only at the next autosave -- up to five minutes later. A crash in that window
lost a reward the player had already been shown. Only killing a real server shows whether the write
happened: a clean stop saves everything and hides the bug, which is why economy_test's restart check
never caught it.

The kill is a hard one (TerminateProcess on Windows, SIGKILL elsewhere), taken with the player still
connected, so no stop, logout or autosave path gets a chance to write the reward for us.

    python validation/smoke/crash_durability_test.py --server-dir <rig> --java <jdk21 java> [--jar <build>]
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from load_test import Bot  # noqa: E402
from rcon import Rcon  # noqa: E402
from smoke_test import Result, Server, install_jar, project_version, read_password, server_port  # noqa: E402

BOT = "DurabilityBot"


def offline_uuid(name: str) -> str:
    """The UUID an offline-mode server assigns: Java's UUID.nameUUIDFromBytes("OfflinePlayer:" + name)."""
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def wait_online(rcon: Rcon, name: str, seconds: int = 60) -> bool:
    # The server's player list, not mineflayer's spawn event: a client on this modset is often
    # dropped ~30s after joining, and granting needs the player to still be there.
    for _ in range(seconds):
        if name in rcon.command("list"):
            return True
        time.sleep(1)
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=None)
    parser.add_argument("--jar", type=Path, default=None, help="build to install into mods/ first")
    args = parser.parse_args()

    server_dir = args.server_dir.resolve()
    java = args.java or Path(os.environ.get("SMOKE_JAVA", "java"))
    if args.jar:
        install_jar(server_dir, args.jar.resolve())

    results: list[Result] = []
    server = Server(server_dir, java)
    bot: Bot | None = None
    player_id = offline_uuid(BOT)
    try:
        print(f"Booting server ({server.log})")
        server.start()
        server.wait_until_ready()
        bot = Bot(BOT, server_port(server_dir))
        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            if not wait_online(rcon, BOT):
                raise RuntimeError("bot never joined: " + str(bot.lost))
            # Baseline. `reward clear` is itself only marked dirty, so without the flush a reward an
            # earlier run left on disk would survive the kill and pass this test on a broken build.
            rcon.command(f"cobbleraids reward clear {BOT}")
            rcon.command("save-all flush")
            granted = rcon.command(f"cobbleraids reward grant {BOT} cobbleraids:charizard")
            results.append(Result("a reward is queued for the connected player",
                                  "Queued" in granted, granted.strip()[:160]))
            held = rcon.command(f"cobbleraids reward list {BOT}")
            results.append(Result("the reward is held before the kill",
                                  "no unclaimed" not in held.lower(), held.strip()[:160]))

        print("Killing the server with no stop and no save")
        server.process.kill()
        server.process.wait(timeout=60)
        bot.stop()
        bot = None

        print("Restarting")
        server.start()
        server.wait_until_ready()
        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            roster = rcon.command("cobbleraids reward list")
            print("  after restart: " + roster.strip()[:300])
            results.append(Result("a granted reward survives a hard kill before the next autosave",
                                  player_id in roster or BOT in roster,
                                  f"{BOT} ({player_id}) holds nothing after the restart"))
    except Exception as exc:  # noqa: BLE001
        results.append(Result("crash durability run", False, str(exc)))
    finally:
        if bot is not None:
            bot.stop()
        print("Stopping server")
        server.stop()

    print()
    width = max(len(result.name) for result in results)
    failed = sum(1 for result in results if not result.passed)
    for result in results:
        mark = "PASS" if result.passed else "FAIL"
        print(f"  [{mark}] {result.name:<{width}}  {result.detail if not result.passed else ''}")
    print(f"\n{len(results) - failed}/{len(results)} checks passed against CobbleRaids {project_version()}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
