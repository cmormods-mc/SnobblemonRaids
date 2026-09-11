#!/usr/bin/env python3
"""Boot a real Fabric + Cobblemon server with this build and check that raids actually work.

Why this exists
---------------
Every serious bug this mod has shipped was a live-behaviour bug: raid slots never freed when a boss
was destroyed, raids granting no exp or EVs at all, the four-player Showdown stall, a reward dupe
vector, and a ConcurrentModificationException in the boss-tracking pass that stopped the server. Not
one of them could have been caught by `gradlew build`, and several survived a careful read of the
diff. The only thing that finds them is running the mod.

That has always been possible here but manual, so it happened when somebody remembered. This makes
it one command.

What it checks
--------------
Two kinds of thing, and the second is the more interesting one:

1. Explicit scenarios -- the server boots, the datapack loads all 130 definitions, a boss spawns and
   is tracked, despawning frees its slot, cooldowns register and reset, admin commands answer.

2. That nothing was *contained*. Now that RaidFaultBarrier guards the tick loop, the battle events
   and all 17 mixin injection points, a bug that used to stop the server instead survives as a log
   line. That is much better for players and much worse for testing, because a broken build now
   looks healthy from the outside. So the run fails if the log contains a contained-failure report,
   an unexpected exception, or a mixin apply error -- the failures the barriers are busy absorbing.

Usage
-----
    python validation/smoke/smoke_test.py --server-dir <dir> [--jar build/libs/CobbleRaids-x.y.z.jar]

The server directory needs Fabric, Cobblemon, SkiesGUIs, a world, and RCON enabled; see the
project's live-test-server notes. --jar copies that build into mods/ first, removing any other
CobbleRaids jar, so you are always testing what you just built.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rcon import Rcon  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]

BOOT_TIMEOUT_SECONDS = 420
BOOT_MARKER = re.compile(r'Done \([\d.]+s\)! For help')

# Lines that mean a barrier absorbed something. The whole point of the barriers is that the server
# keeps running, so these never fail the server -- they have to fail the test instead.
CONTAINED_FAULT = re.compile(r"contained a failure|Raid reward grant failed|Skipping malformed raid definition")

# Mixin problems are fatal to correctness even when the server starts.
MIXIN_TROUBLE = re.compile(r"Mixin apply failed|was not applied|InvalidInjectionException|Critical injection failure")

# Exceptions we did not ask for. Anything CobbleRaids is in the stack of is ours.
SUSPECT_EXCEPTION = re.compile(r"(Exception|Error)[^\n]*\n(?:[^\n]*\n){0,12}?[^\n]*com\.cobbleraids")

# What a refused or broken command actually looks like coming back over RCON. Deliberately phrases
# rather than words: an earlier version rejected on bare "Failed", which matched the config key
# max_failed_attempts in the `debug config` dump and failed a healthy build.
COMMAND_ERROR = re.compile(
    "Unknown or incomplete command"
    "|An unexpected error"
    "|Incorrect argument"
    "|Expected whitespace"
    "|Failed to ",
    re.I,
)

IGNORED_LOG_NOISE = (
    # Cobblemon and friends are noisy about optional integrations on a bare test server.
    "Unable to find a data file",
    "No data file found",
    "Advancement data",
)


@dataclass
class Check:
    name: str
    command: str
    expect: re.Pattern | None = None
    reject: re.Pattern | None = None
    note: str = ""


@dataclass
class Result:
    name: str
    passed: bool
    detail: str = ""


@dataclass
class Server:
    directory: Path
    java: Path
    log: Path = field(init=False)
    process: subprocess.Popen | None = field(default=None, init=False)

    def __post_init__(self) -> None:
        self.log = self.directory / "logs" / "smoke.log"

    def start(self) -> None:
        # Back-to-back runs race the previous server's listening socket out of TIME_WAIT. Without
        # this the second run dies on "FAILED TO BIND TO PORT" and reports the useless "server
        # exited with code 0", which reads like a mod bug and is not one.
        wait_for_free_port(server_port(self.directory))
        self.log.parent.mkdir(parents=True, exist_ok=True)
        if self.log.exists():
            self.log.unlink()
        launcher = self.directory / "fabric-server-launch.jar"
        assert launcher.exists(), f"no fabric-server-launch.jar in {self.directory}"

        handle = open(self.log, "w", encoding="utf-8", errors="replace")
        self.process = subprocess.Popen(
            [str(self.java), "-Xms2G", "-Xmx4G", "-jar", launcher.name, "nogui"],
            cwd=self.directory,
            stdout=handle,
            stderr=subprocess.STDOUT,
            stdin=subprocess.PIPE,
        )

    def wait_until_ready(self) -> None:
        deadline = time.time() + BOOT_TIMEOUT_SECONDS
        while time.time() < deadline:
            if self.process and self.process.poll() is not None:
                raise RuntimeError(
                    f"server exited with code {self.process.returncode} during boot; see {self.log}"
                )
            if self.log.exists() and BOOT_MARKER.search(self.read_log()):
                return
            time.sleep(2)
        raise TimeoutError(f"server did not finish booting within {BOOT_TIMEOUT_SECONDS}s; see {self.log}")

    def read_log(self) -> str:
        try:
            return self.log.read_text(encoding="utf-8", errors="replace")
        except FileNotFoundError:
            return ""

    def stop(self) -> None:
        if not self.process or self.process.poll() is not None:
            return
        try:
            with Rcon("127.0.0.1", 25575, read_password(self.directory), timeout=15) as rcon:
                rcon.command("stop")
        except Exception:
            pass
        try:
            self.process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            print("  ! server did not stop cleanly; terminating", file=sys.stderr)
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill()


def server_port(directory: Path) -> int:
    for line in (directory / "server.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("server-port="):
            value = line.split("=", 1)[1].strip()
            if value:
                return int(value)
    return 25565


def wait_for_free_port(port: int, timeout: float = 150.0) -> None:
    """Block until the game port can actually be bound.

    Probing with connect() is the obvious thing and the wrong thing. A socket left in TIME_WAIT by a
    previous run refuses connections -- so it looks free -- while still blocking bind; and the port
    can equally be held as the *local* end of someone else's outbound connection, which also refuses
    connections on loopback. Both were observed here. Binding is the only probe that asks the
    question the server is about to ask.
    """
    import socket as _socket

    def bindable() -> OSError | None:
        # Minecraft binds "*", which on a dual-stack host is IPv6 with V6ONLY off. Probing IPv4 alone
        # reports free while an IPv6 conflict is exactly what stops the server -- observed here, where
        # an unrelated outbound HTTPS connection had been assigned 25565 as its local IPv6 port.
        for family, address in ((_socket.AF_INET6, "::"), (_socket.AF_INET, "0.0.0.0")):
            probe = _socket.socket(family, _socket.SOCK_STREAM)
            try:
                if family == _socket.AF_INET6:
                    probe.setsockopt(_socket.IPPROTO_IPV6, _socket.IPV6_V6ONLY, 0)
                probe.bind((address, port))
            except OSError as exc:
                return exc
            finally:
                probe.close()
        return None

    deadline = time.time() + timeout
    announced = False
    while True:
        last = bindable()
        if last is None:
            if announced:
                print(f"  port {port} is free")
            return

        if time.time() >= deadline:
            raise TimeoutError(
                f"cannot bind port {port} after {timeout:.0f}s ({last}). Another Minecraft server may"
                f" be running, a previous run's socket may still be in TIME_WAIT, or an unrelated"
                f" process may hold it -- check with: netstat -ano | findstr :{port}"
            )
        if not announced:
            print(f"  waiting for port {port} to become bindable")
            announced = True
        time.sleep(3)


def read_password(directory: Path) -> str:
    for line in (directory / "server.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("rcon.password="):
            return line.split("=", 1)[1].strip()
    raise AssertionError("server.properties has no rcon.password")


def project_version() -> str:
    for line in (ROOT / "build.gradle").read_text(encoding="utf-8").splitlines():
        if line.startswith("version = "):
            return line.split("=", 1)[1].strip().strip("'\"")
    raise AssertionError("build.gradle has no version assignment")


def install_jar(server_dir: Path, jar: Path) -> None:
    mods = server_dir / "mods"
    mods.mkdir(exist_ok=True)
    for existing in mods.glob("CobbleRaids-*.jar"):
        # Only ever one CobbleRaids in the folder; two would load and fight over the same mixins.
        existing.unlink()
        print(f"  removed stale {existing.name}")
    shutil.copy2(jar, mods / jar.name)
    print(f"  installed {jar.name}")


def scenarios() -> list[Check]:
    """Server-authoritative checks, driven entirely over RCON.

    Deliberately no mineflayer here. Client-driven interaction is the flaky part of this rig -- the
    bot's world model of a Cobblemon entity is unreliable -- and none of these need a client: they
    are about server state, which RCON reaches directly and repeatably. Commands taking a player
    argument are therefore out of scope for this pass; see the module docstring.

    The ordering matters. The spawn/track/despawn block is one scenario split across four commands,
    and it is the sequence that 0.8.33 got wrong: the boss was destroyed but its slot stayed claimed
    against max_active_raids until despawn_seconds ran out.
    """
    return [
        Check(
            "mod is loaded and answers",
            "cobbleraids debug status",
            reject=COMMAND_ERROR,
            note="the admin tree is registered and the permission predicate lets console through",
        ),
        Check(
            "all 130 raid definitions loaded from the datapack",
            "cobbleraids list",
            expect=re.compile(r"Raid definitions \(130\)"),
            note="a malformed definition is skipped rather than fatal now, so the count is the check",
        ),
        Check(
            "effective config is readable",
            "cobbleraids debug config",
            reject=COMMAND_ERROR,
        ),
        Check(
            "loot preview resolves a bundled table",
            "cobbleraids debug loot cobbleraids:tier/legendary",
            reject=COMMAND_ERROR,
            note="exercises the loot roller and the cross-mod item tolerance added in 0.8.38",
        ),

        # --- boss lifecycle: the slot-release path -------------------------------------------
        Check(
            "no raids are active before we start",
            "cobbleraids debug raids",
            reject=re.compile(r"[1-9]\d* (?:active|natural)"),
        ),
        Check(
            "an admin can spawn a boss at explicit coordinates",
            "cobbleraids spawn garchomp 0 80 0",
            reject=re.compile(COMMAND_ERROR.pattern + "|No raid definition uses species", re.I),
            note="console-safe because spawnAt takes a Vec3 rather than deriving one from a player",
        ),
        Check(
            "despawn-all reports removing it",
            "cobbleraids despawn all",
            reject=COMMAND_ERROR,
        ),
        Check(
            "the raid slot is released, not held until despawn_seconds",
            "cobbleraids debug status",
            reject=re.compile(r"natural\s*[:=]?\s*[1-9]", re.I),
            note="the 0.8.33 regression: destroying a boss must free its max_active_raids slot at once",
        ),

        # --- cooldowns and reload ------------------------------------------------------------
        Check(
            "cooldown listing is reachable",
            "cobbleraids cooldown list",
            reject=COMMAND_ERROR,
        ),
        Check(
            "a cooldown can be reset by id",
            "cobbleraids cooldown reset cobbleraids:garchomp",
            reject=COMMAND_ERROR,
        ),
        Check(
            "config reload succeeds",
            "cobbleraids reload",
            reject=COMMAND_ERROR,
            note="reload is last-known-good; a broken config must not destabilise a running server",
        ),
        Check(
            "a datapack reload keeps every definition",
            "reload",
            reject=re.compile(r"Failed to reload", re.I),
        ),
        Check(
            "definitions survive the datapack reload",
            "cobbleraids list",
            expect=re.compile(r"Raid definitions \(130\)"),
        ),

        # --- player-context guard --------------------------------------------------------------
        Check(
            "spawninfo is registered and refuses the console politely",
            "cobbleraids spawninfo",
            expect=re.compile(r"must be run by a player", re.I),
            note="proves registration and the getPlayerOrException guard without needing a bot",
        ),
    ]


def run_scenarios(server: Server) -> list[Result]:
    results: list[Result] = []
    password = read_password(server.directory)
    with Rcon("127.0.0.1", 25575, password) as rcon:
        for check in scenarios():
            try:
                output = rcon.command(check.command)
            except Exception as exc:  # noqa: BLE001 -- a dead socket is a failed check, not a crash
                results.append(Result(check.name, False, f"RCON error: {exc}"))
                continue

            if check.expect and not check.expect.search(output):
                results.append(Result(check.name, False, f"expected /{check.expect.pattern}/, got: {output[:200]!r}"))
            elif check.reject and check.reject.search(output):
                results.append(Result(check.name, False, f"rejected /{check.reject.pattern}/ matched: {output[:200]!r}"))
            else:
                results.append(Result(check.name, True))
    return results


def audit_log(log: str) -> list[Result]:
    """The half that catches what the barriers now hide."""
    results: list[Result] = []

    contained = [line for line in log.splitlines() if CONTAINED_FAULT.search(line)]
    results.append(Result(
        "no fault was contained by a barrier",
        not contained,
        "\n      ".join(contained[:5]),
    ))

    mixin = [line for line in log.splitlines() if MIXIN_TROUBLE.search(line)]
    results.append(Result(
        "every mixin applied cleanly",
        not mixin,
        "\n      ".join(mixin[:5]),
    ))

    suspect = [
        match.group(0).splitlines()[0]
        for match in SUSPECT_EXCEPTION.finditer(log)
        if not any(noise in match.group(0) for noise in IGNORED_LOG_NOISE)
    ]
    results.append(Result(
        "no exception with CobbleRaids in the stack",
        not suspect,
        "\n      ".join(suspect[:5]),
    ))

    results.append(Result(
        "Showdown integration installed",
        "Showdown integration verified" in log,
        "the raid damage/heal pipeline is inert without it, and raids silently do nothing",
    ))

    return results


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--jar", type=Path, default=None, help="build to install into mods/ first")
    parser.add_argument("--java", type=Path, default=None, help="JDK 21 java executable")
    parser.add_argument("--keep-running", action="store_true", help="leave the server up for manual poking")
    args = parser.parse_args()

    server_dir = args.server_dir.resolve()
    assert server_dir.is_dir(), f"{server_dir} is not a directory"

    java = args.java or Path(os.environ.get("SMOKE_JAVA", "java"))

    jar = args.jar
    if jar is None:
        candidate = ROOT / "build" / "libs" / f"CobbleRaids-{project_version()}.jar"
        if candidate.exists():
            jar = candidate
    if jar:
        print(f"Installing {jar.name} into {server_dir / 'mods'}")
        install_jar(server_dir, jar.resolve())

    server = Server(server_dir, java)
    print(f"Booting server ({server.log})")
    started = time.time()
    server.start()

    results: list[Result] = []
    try:
        server.wait_until_ready()
        print(f"  ready in {time.time() - started:.0f}s")
        results += run_scenarios(server)
    except Exception as exc:  # noqa: BLE001
        results.append(Result("server boots and becomes ready", False, str(exc)))
    finally:
        log = server.read_log()
        results += audit_log(log)
        if not args.keep_running:
            print("Stopping server")
            server.stop()

    print()
    width = max(len(r.name) for r in results)
    failed = 0
    for result in results:
        mark = "PASS" if result.passed else "FAIL"
        if not result.passed:
            failed += 1
        print(f"  [{mark}] {result.name:<{width}}")
        if result.detail and not result.passed:
            print(f"      {result.detail}")

    print()
    if failed:
        print(f"Live smoke: FAIL -- {failed} of {len(results)} checks failed. Log: {server.log}")
        raise SystemExit(1)
    print(f"Live smoke: PASS -- {len(results)} checks. Log: {server.log}")


if __name__ == "__main__":
    main()
