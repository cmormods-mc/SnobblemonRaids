#!/usr/bin/env python3
"""CobbleRaids writes to the server log in exactly one way.

This mod runs unattended, so the log is the only account of what happened. Three habits ruin it and
all three had taken hold before this check existed:

  - `System.out.println` / `System.err.println`, which Minecraft's log4j re-emits as [STDOUT] and
    [STDERR]. A genuine error arrives looking like console noise, at no useful level, and no appender
    or log-level setting can tell it from a debug line.
  - `printStackTrace()`, which writes to the process's stderr on whatever thread threw, so the trace
    interleaves with unrelated output and lands away from the message that explains it.
  - Ad-hoc `LoggerFactory.getLogger(...)` fields, which drifted: some prefixed "[CobbleRaids]" and
    some did not, and Minecraft's log pattern does not print the logger name, so the unprefixed ones
    were unfindable by grep.

So: no stdout/stderr printing and no printStackTrace anywhere in main, and RaidLog is the only class
allowed to hold a logger. Client-side code is included -- a crash report from a player's client is
just as much a diagnostic.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "src/main/java/com/cobbleraids"
LOG_FACADE = MAIN / "RaidLog.java"

BANNED = {
    "System.out.print": "use RaidLog.info / RaidLog.warn",
    "System.err.print": "use RaidLog.error",
    ".printStackTrace(": "pass the throwable as the last RaidLog.error argument instead",
}

LOGGER_FACTORY = re.compile(r"LoggerFactory\s*\.\s*getLogger")
# Strip block comments and line comments before scanning, so documentation that names these habits
# (this file's own subject matter, and RaidLog's javadoc) does not trip the check.
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def code_only(text: str) -> str:
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def main() -> None:
    assert LOG_FACADE.exists(), "src/main/java/com/cobbleraids/RaidLog.java is missing"

    problems: list[str] = []
    scanned = 0

    for source in sorted(MAIN.rglob("*.java")):
        scanned += 1
        relative = source.relative_to(ROOT).as_posix()
        body = code_only(source.read_text(encoding="utf-8"))

        for needle, advice in BANNED.items():
            if needle in body:
                line = next(
                    (n for n, text in enumerate(body.splitlines(), 1) if needle in text), 0
                )
                problems.append(f"{relative}:{line} uses {needle} -- {advice}")

        if source != LOG_FACADE and LOGGER_FACTORY.search(body):
            problems.append(
                f"{relative} creates its own Logger -- call RaidLog instead, so every line carries"
                f" the [CobbleRaids] prefix that makes this mod greppable"
            )

    # The prefix is the whole reason RaidLog exists; losing it silently would be worse than any of
    # the habits above, because the log would still look fine.
    facade = LOG_FACADE.read_text(encoding="utf-8")
    if '"[CobbleRaids] "' not in facade:
        problems.append(
            "RaidLog no longer applies the [CobbleRaids] prefix. Minecraft's log pattern does not"
            " print the logger name, so without it an operator cannot isolate this mod's output."
        )

    if problems:
        print("Logging discipline violations:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        raise SystemExit(1)

    print(f"Logging discipline validation: PASS -- {scanned} source files, one logging facade")


if __name__ == "__main__":
    main()
