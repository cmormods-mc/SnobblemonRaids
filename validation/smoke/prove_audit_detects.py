"""Boot the test server, inject a real inconsistency, and check the audit reports it.

A detector nobody has watched fire is a detector nobody should trust. This creates the exact
condition the scoreboard-leak fix was written for -- a glow team still listing a boss that is not
tracked -- and asserts the audit names it, then cleans up and asserts it goes quiet again.
"""
import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from smoke_test import ROOT, Server, install_jar, project_version, read_password  # noqa: E402
from rcon import Rcon  # noqa: E402

# A plausible-looking entity UUID that is not a tracked raid boss.
GHOST = "8f14e45f-ceea-467a-9575-1b0aabbccdd0"
TEAM = "cobbleraids_glow_legendary"

failures = []


def check(name, condition, detail=""):
    print(f"  [{'PASS' if condition else 'FAIL'}] {name}")
    if not condition:
        failures.append(f"{name}: {detail}")
        if detail:
            print(f"        {detail}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("SMOKE_JAVA", "java")))
    args = parser.parse_args()

    server_dir = args.server_dir.resolve()
    globals()["SERVER_DIR"] = server_dir
    install_jar(server_dir, ROOT / "build" / "libs" / f"CobbleRaids-{project_version()}.jar")
    server = Server(server_dir, args.java)
    print("Booting server")
    server.start()
    try:
        server.wait_until_ready()
        with Rcon("127.0.0.1", 25575, read_password(server_dir)) as rcon:
            baseline = rcon.command("cobbleraids debug audit")
            check("audit is clean before we break anything",
                  "raid state consistent" in baseline, baseline[:200])

            # Leave a boss in a glow team without tracking it -- exactly what the service used to do
            # to scoreboard.dat on every boss that ever glowed.
            rcon.command(f"team add {TEAM}")
            rcon.command(f"team join {TEAM} {GHOST}")

            broken = rcon.command("cobbleraids debug audit")
            check("audit detects the orphaned glow team member",
                  "orphaned-glow-team-member" in broken, broken[:300])
            check("the report names the offending id, so it can be chased",
                  GHOST in broken, broken[:300])
            check("the summary reports it as an inconsistency",
                  "inconsistenc" in broken, broken[:200])

            # Clean up and confirm the detector goes quiet rather than latching.
            rcon.command(f"team leave {GHOST}")
            rcon.command(f"team remove {TEAM}")
            after = rcon.command("cobbleraids debug audit")
            check("audit is clean again once the inconsistency is gone",
                  "raid state consistent" in after, after[:200])
    finally:
        print("Stopping server")
        server.stop()

    print()
    if failures:
        print(f"AUDIT DETECTION: FAIL -- {len(failures)} check(s) failed")
        raise SystemExit(1)
    print("AUDIT DETECTION: PASS -- the audit fires on a real inconsistency and clears afterwards")


if __name__ == "__main__":
    main()
