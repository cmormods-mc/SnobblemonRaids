#!/usr/bin/env python3
"""Behavioural coverage for raid-patch.js, run against the actual Showdown fork Cobblemon ships --
not a hand-copied fixture that can quietly drift from it, and not a generic `pokemon-showdown` from
npm, which is a different fork and would not reflect Cobblemon's own dex-formats/mods changes.

Cobblemon bundles its entire patched Showdown tree as a zip inside its own jar, at
`data/cobblemon/showdown.zip` -- the exact archive `GraalShowdownUnbundler` unpacks at runtime. This
extracts that archive fresh into a temp directory, drops this build's real `raid-patch.js` in next to
it (exactly where `ShowdownIntegrationInstaller` puts it against a live server), and runs
`showdown_raid_patch_test.js` against it with plain Node -- no GraalJS, no Minecraft, no server.

What this catches: a regression in the exported topology predicates (`isRaid`, `bossIndex`,
`isPlayerSide`, ...) that the whole dynamic player-count raid model depends on, and any syntax or
load-time error in raid-patch.js against Cobblemon's *current* Showdown fork -- both previously
findable only by booting a real server and watching a multi-bot raid stall. It deliberately does not
cover deeper Side/BattleStream internals; see the test file's own header for why.
"""

import io
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD_GRADLE = ROOT / "build.gradle"
RAID_PATCH = ROOT / "src/main/resources/assets/cobbleraids/showdown/raid-patch.js"
TEST_SCRIPT = ROOT / "validation/showdown_raid_patch_test.js"


def find_cobblemon_jar() -> Path | None:
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    line = next((l for l in text.splitlines() if "Cobblemon" in l and "modImplementation" in l), None)
    assert line, "build.gradle no longer has a modImplementation line commented '// Cobblemon ...'"
    match = re.search(r"'([^:']+):([^:']+):([^']+)'", line)
    assert match, f"could not parse a group:artifact:version coordinate out of: {line.strip()}"
    group, artifact, version = match.groups()
    pattern = f"caches/modules-2/files-2.1/{group}/{artifact}/{version}/*/{artifact}-{version}.jar"
    matches = list(Path.home().glob(f".gradle/{pattern}"))
    return matches[0] if matches else None


def main() -> None:
    cobblemon_jar = find_cobblemon_jar()
    if cobblemon_jar is None:
        print("Showdown raid-patch behaviour validation: SKIPPED -- Cobblemon jar not resolved yet"
              " (run gradlew build first)", file=sys.stderr)
        raise SystemExit(0)
    assert RAID_PATCH.exists(), f"{RAID_PATCH} is missing -- has the Showdown patch moved?"

    with zipfile.ZipFile(cobblemon_jar) as jar:
        with jar.open("data/cobblemon/showdown.zip") as bundled:
            bundle_bytes = bundled.read()

    with tempfile.TemporaryDirectory(prefix="cobbleraids-showdown-") as tmp:
        tmp_path = Path(tmp)
        with zipfile.ZipFile(io.BytesIO(bundle_bytes)) as bundle:
            bundle.extractall(tmp_path)
        shutil.copy(RAID_PATCH, tmp_path / "raid-patch.js")

        result = subprocess.run(
            ["node", str(TEST_SCRIPT), str(tmp_path)],
            capture_output=True, text=True,
        )

    if result.returncode != 0:
        print("Showdown raid-patch behaviour validation: FAIL", file=sys.stderr)
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        raise SystemExit(1)

    print(result.stdout.strip())


if __name__ == "__main__":
    main()
