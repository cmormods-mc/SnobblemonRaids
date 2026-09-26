#!/usr/bin/env python3
"""The Showdown patch files are JavaScript, so nothing here catches a syntax error before runtime.

`raid-patch.js` is loaded into a GraalJS context by `ShowdownIntegrationInstaller`, not compiled by
Gradle, so `gradlew build` reporting SUCCESS proves nothing about whether it parses. This has already
happened once: a syntax error surfaced only as a buried `GraalShowdownService.boot`
PolyglotException, discovered live, with the build green the whole time.

So: every `.js` file this mod ships gets `node --check` run over it. That is a parse check only --
Node never executes the file, and it does not need the `sim/`, `data/` etc. modules the file
`require()`s at runtime -- so this needs nothing beyond a working `node` on PATH, which CI already
has.
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHOWDOWN = ROOT / "src/main/resources/assets/cobbleraids/showdown"


def main() -> None:
    assert SHOWDOWN.is_dir(), f"{SHOWDOWN} is missing -- has the Showdown patch tree moved?"

    scripts = sorted(SHOWDOWN.rglob("*.js"))
    assert scripts, f"no .js files found under {SHOWDOWN} -- has the patch tree moved or emptied?"

    problems: list[str] = []
    for script in scripts:
        relative = script.relative_to(ROOT).as_posix()
        result = subprocess.run(
            ["node", "--check", str(script)], capture_output=True, text=True
        )
        if result.returncode != 0:
            problems.append(f"{relative}:\n{result.stderr.strip()}")

    if problems:
        print("Showdown JS syntax errors:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        raise SystemExit(1)

    print(f"Showdown JS syntax validation: PASS -- {len(scripts)} file(s) parsed")


if __name__ == "__main__":
    main()
