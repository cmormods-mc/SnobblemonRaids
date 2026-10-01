#!/usr/bin/env python3
"""Fuzz the raid battle model against Cobblemon's real Showdown fork with raid-patch.js applied.

Builds the same fixture validate_showdown_raid_patch_behavior.py does -- the Showdown tree bundled
inside the Cobblemon jar, with this build's real raid-patch.js dropped in next to it -- and hands it to
showdown_fuzz.js, which plays seeded random raid battles (1-4 players plus a boss, the whole movepool,
status effects, disconnects) the way Cobblemon drives them and fails on any freeze. See that file's
header for what it asserts and why.

Default run (what CI does): a fixed block of seeds plus REGRESSION_SEEDS, the battles that each once
froze a raid. It is deterministic, so a red CI run replays exactly with the seed it prints.

A long local sweep looks for the NEXT bug:

    python validation/validate_showdown_fuzz.py --battles 20000 --seed 100000

and a failing seed replays verbosely with:

    python validation/validate_showdown_fuzz.py --replay 1395
"""

import argparse
import io
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_showdown_raid_patch_behavior import RAID_PATCH, ROOT, find_cobblemon_jar  # noqa: E402

FUZZ_SCRIPT = ROOT / "validation/showdown_fuzz.js"

# Each of these froze a raid at some point. Add the seed of every new failure the harness finds, so
# the fix for it can never quietly regress.
REGRESSION_SEEDS = [
    76,     # a held player's forced switch left every side waiting
    1032,   # same, with four players and the last Pokemon gone
    1103,   # forced switch for a held player, committed from outside the go() stack
    1395,   # AI named a slot the request did not list (Struggle) and wiped the boss's pre-fill
    21685,  # the last player standing, held and resumed, was never asked for a move again
    22104,  # as above, with the other three players eliminated
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--battles", type=int, default=250, help="how many seeded battles to play (default 250)")
    parser.add_argument("--seed", type=int, default=1, help="first seed of the block (default 1)")
    parser.add_argument("--turns", type=int, default=14, help="rounds per battle (default 14)")
    parser.add_argument("--replay", type=int, help="replay one seed verbosely instead of running a block")
    opts = parser.parse_args()

    cobblemon_jar = find_cobblemon_jar()
    if cobblemon_jar is None:
        print("Showdown fuzz: SKIPPED -- Cobblemon jar not resolved yet (run gradlew build first)", file=sys.stderr)
        raise SystemExit(0)
    assert RAID_PATCH.exists(), f"{RAID_PATCH} is missing -- has the Showdown patch moved?"

    with zipfile.ZipFile(cobblemon_jar) as jar:
        with jar.open("data/cobblemon/showdown.zip") as bundled:
            bundle_bytes = bundled.read()

    with tempfile.TemporaryDirectory(prefix="cobbleraids-showdown-fuzz-") as tmp:
        tmp_path = Path(tmp)
        with zipfile.ZipFile(io.BytesIO(bundle_bytes)) as bundle:
            bundle.extractall(tmp_path)
        shutil.copy(RAID_PATCH, tmp_path / "raid-patch.js")

        command = ["node", str(FUZZ_SCRIPT), str(tmp_path), "--turns", str(opts.turns)]
        if opts.replay is not None:
            command += ["--replay", str(opts.replay)]
        else:
            command += ["--battles", str(opts.battles), "--seed", str(opts.seed),
                        "--seeds", ",".join(str(seed) for seed in REGRESSION_SEEDS)]
        result = subprocess.run(command, capture_output=True, text=True)

    if opts.replay is not None:
        print(result.stdout)
        print(result.stderr, file=sys.stderr)
        raise SystemExit(result.returncode)
    if result.returncode != 0:
        print("Showdown fuzz validation: FAIL", file=sys.stderr)
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        print("Replay a failure verbosely: python validation/validate_showdown_fuzz.py --replay <seed>",
              file=sys.stderr)
        raise SystemExit(1)
    print(result.stdout.strip().splitlines()[0])
    print("Showdown fuzz validation: PASS")


if __name__ == "__main__":
    main()
