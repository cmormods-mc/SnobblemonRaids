#!/usr/bin/env python3
"""RewardGuiDefaults.SUPERSEDED is a hand-maintained list of every hash the bundled reward GUI
resource has ever had, minus its current one -- its own doc comment says outright: "When the bundled
file changes, add the outgoing version's hash here, or servers that already have it keep the old copy
forever." Nothing enforced that instruction. Edit the resource and forget the second half of that
edit, and the mistake ships silently: gradlew build passes, every existing test passes, and the only
symptom is that RaidRewardGuiInstaller.ensureInstalledAndLoaded quietly stops upgrading any server
that already had the previous default, forever, with nothing in any log to say so.

So: walk the resource's real git history -- not a hand-copied list of what someone remembers it used
to say -- hash every past revision with the exact normalization RewardGuiDefaults.normalizedSha256
uses (strip \\r, then SHA-256), and assert that every one of them except the current revision appears
in SUPERSEDED. Also flags the opposite mistake: a SUPERSEDED entry that does not match any real past
revision, which is either a typo or a hash for a file this never actually shipped.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESOURCE = "src/main/resources/assets/cobbleraids/skiesguis/cobbleraids_reward.json"
DEFAULTS_SOURCE = ROOT / "src/main/java/com/cobbleraids/reward/RewardGuiDefaults.java"


def normalized_sha256(content: bytes) -> str:
    """Same normalization as RewardGuiDefaults.normalizedSha256: strip carriage returns, then hash,
    so this matches regardless of which line endings this checkout has for the resource."""
    import hashlib
    return hashlib.sha256(content.replace(b"\r", b"")).hexdigest()


def revisions() -> list[tuple[str, str]]:
    """Every commit that touched the resource, oldest first, paired with its normalized hash."""
    log = subprocess.run(
        ["git", "log", "--follow", "--format=%H", "--", RESOURCE],
        cwd=ROOT, capture_output=True, text=True, check=True,
    )
    commits = [c for c in log.stdout.splitlines() if c]
    assert commits, f"{RESOURCE} has no git history -- has it moved, or is --follow broken here?"
    result = []
    for commit in reversed(commits):  # oldest first
        content = subprocess.run(
            ["git", "show", f"{commit}:{RESOURCE}"],
            cwd=ROOT, capture_output=True, check=True,
        ).stdout
        result.append((commit, normalized_sha256(content)))
    return result


def current_hash() -> str:
    return normalized_sha256((ROOT / RESOURCE).read_bytes())


def declared_superseded() -> set[str]:
    text = DEFAULTS_SOURCE.read_text(encoding="utf-8")
    match = re.search(r"SUPERSEDED = Set\.of\((.*?)\);", text, re.S)
    assert match, f"{DEFAULTS_SOURCE} no longer declares SUPERSEDED = Set.of(...) -- has it moved?"
    return {m.strip() for m in re.findall(r'"([0-9a-f]{64})"', match.group(1))}


def main() -> None:
    history = revisions()
    current = current_hash()
    superseded = declared_superseded()

    # A shallow clone (CI's default) truncates `git log` to one commit, which would otherwise make
    # this pass trivially -- one revision, zero history to check anything against.
    assert len(history) > len(superseded), (
        f"only found {len(history)} revision(s) in git history but RewardGuiDefaults declares"
        f" {len(superseded)} superseded hash(es) -- this needs the full history"
        f" (a shallow clone/fetch-depth: 1 checkout would look exactly like this)")

    past_hashes = {h for _, h in history} - {current}
    missing = past_hashes - superseded
    stale = superseded - past_hashes - {current}

    if missing or stale:
        print("Reward GUI defaults validation: FAIL", file=sys.stderr)
        if missing:
            print("  Past revisions of the bundled reward GUI missing from RewardGuiDefaults."
                  "SUPERSEDED -- a server that already has one of these installed will never be"
                  " upgraded to the current default:", file=sys.stderr)
            for commit, h in history:
                if h in missing:
                    print(f"    {h}  ({commit[:12]})", file=sys.stderr)
        if stale:
            print("  Hashes in SUPERSEDED that do not match any real past revision of the resource"
                  " (a typo, or a hash for a file this never actually shipped):", file=sys.stderr)
            for h in sorted(stale):
                print(f"    {h}", file=sys.stderr)
        raise SystemExit(1)

    print(f"Reward GUI defaults validation: PASS -- {len(history)} revision(s) in history, "
          f"{len(superseded)} superseded hash(es) all accounted for")


if __name__ == "__main__":
    main()
