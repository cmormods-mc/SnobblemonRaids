#!/usr/bin/env python3
"""RaidBannedMoves.BANNED is a hand-curated set, justified only by a comment claiming it was checked
against Cobblemon's bundled data/moves.js by hand once. Nothing enforced that the check stays true
after Cobblemon updates -- a new move gaining `selfdestruct: "always"`/`"ifHit"`, or a move gaining a
direct `.faint()` call, would drift the ban list silently. Nothing throws, no test fails: a raid boss
using that move just hangs the battle interface open forever, exactly like Imprison did before that
was understood, and exactly what this whole ban list exists to prevent.

So: extract Cobblemon's actual bundled `data/moves.js` from `data/cobblemon/showdown.zip` inside the
same jar this build depends on -- not a hand-copied fixture that can quietly drift from it -- find
every move that either carries `selfdestruct: "always"`/`"ifHit"`, or whose own move-data block (which
includes any nested `condition: {...}` it defines, exactly like RaidBannedMoves.BANNED's own comment
describes for Destiny Bond/Perish Song) contains a direct `.faint()` call. That union is mechanically
exactly what RaidBannedMoves.BANNED should be. Diff the two; a mismatch in either direction fails.
"""

import io
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD_GRADLE = ROOT / "build.gradle"
BANNED_MOVES_SOURCE = ROOT / "src/main/java/com/cobbleraids/battle/RaidBannedMoves.java"

MOVE_BLOCK_START = re.compile(r"^  ([a-zA-Z0-9]+): \{$")
MOVE_BLOCK_END = re.compile(r"^  \},$")


def find_cobblemon_jar() -> Path | None:
    """Same lookup as validate_showdown_raid_patch_behavior.py: the raw, as-downloaded jar, which is
    the one that actually contains data/cobblemon/showdown.zip as a resource."""
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    line = next((l for l in text.splitlines() if "Cobblemon" in l and "modImplementation" in l), None)
    assert line, "build.gradle no longer has a modImplementation line commented '// Cobblemon ...'"
    match = re.search(r"'([^:']+):([^:']+):([^']+)'", line)
    assert match, f"could not parse a group:artifact:version coordinate out of: {line.strip()}"
    group, artifact, version = match.groups()
    pattern = f"caches/modules-2/files-2.1/{group}/{artifact}/{version}/*/{artifact}-{version}.jar"
    matches = list(Path.home().glob(f".gradle/{pattern}"))
    return matches[0] if matches else None


def read_moves_js(cobblemon_jar: Path) -> str:
    with zipfile.ZipFile(cobblemon_jar) as jar:
        with jar.open("data/cobblemon/showdown.zip") as bundled:
            bundle_bytes = bundled.read()
    with zipfile.ZipFile(io.BytesIO(bundle_bytes)) as bundle:
        return bundle.read("data/moves.js").decode("utf-8")


def move_blocks(moves_js: str) -> dict[str, str]:
    """Every top-level move entry, keyed by its Showdown id, mapped to its own block's full text
    (start line through the matching close, at the same 2-space indent -- any nested object, however
    deep, closes at a deeper indent and is skipped over rather than mistaken for the end)."""
    lines = moves_js.splitlines()
    blocks: dict[str, str] = {}
    i = 0
    while i < len(lines):
        start_match = MOVE_BLOCK_START.match(lines[i])
        if not start_match:
            i += 1
            continue
        move_id = start_match.group(1)
        j = i + 1
        while j < len(lines) and not MOVE_BLOCK_END.match(lines[j]):
            j += 1
        blocks[move_id] = "\n".join(lines[i:j + 1])
        i = j + 1
    return blocks


def expected_bans(blocks: dict[str, str]) -> set[str]:
    result: set[str] = set()
    for move_id, block in blocks.items():
        if re.search(r'selfdestruct: "(always|ifHit)"', block):
            result.add(move_id)
        elif re.search(r"\.faint\(\)", block):
            result.add(move_id)
    return result


def actual_bans() -> set[str]:
    text = BANNED_MOVES_SOURCE.read_text(encoding="utf-8")
    match = re.search(r"BANNED = Set\.of\((.*?)\);", text, re.S)
    assert match, f"{BANNED_MOVES_SOURCE} no longer declares BANNED = Set.of(...) -- has it moved?"
    return {m.strip().strip('"') for m in match.group(1).split(",") if m.strip()}


def main() -> None:
    cobblemon_jar = find_cobblemon_jar()
    if cobblemon_jar is None:
        print("Raid banned-move validation: SKIPPED -- Cobblemon jar not resolved yet"
              " (run gradlew build first)", file=sys.stderr)
        raise SystemExit(0)

    moves_js = read_moves_js(cobblemon_jar)
    blocks = move_blocks(moves_js)
    assert len(blocks) > 500, f"only found {len(blocks)} move blocks -- has moves.js's format changed?"

    expected = expected_bans(blocks)
    actual = actual_bans()

    missing = expected - actual
    stale = actual - expected
    if missing or stale:
        print("Raid banned-move validation: FAIL", file=sys.stderr)
        if missing:
            print("  Moves that bypass the damage pipeline (selfdestruct field or a direct .faint()"
                  " call) but are NOT in RaidBannedMoves.BANNED -- a raid boss or player using one of"
                  " these can hang the battle interface open:", file=sys.stderr)
            for move_id in sorted(missing):
                print(f"    {move_id}", file=sys.stderr)
        if stale:
            print("  Moves in RaidBannedMoves.BANNED that no longer match either check against the"
                  " current Cobblemon version -- harmless to leave, but worth a look:", file=sys.stderr)
            for move_id in sorted(stale):
                print(f"    {move_id}", file=sys.stderr)
        raise SystemExit(1)

    print(f"Raid banned-move validation: PASS -- {len(blocks)} move(s) checked, "
          f"{len(actual)} banned move(s) confirmed exact")


if __name__ == "__main__":
    main()
