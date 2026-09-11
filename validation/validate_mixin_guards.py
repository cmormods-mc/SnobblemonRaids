#!/usr/bin/env python3
"""Every mixin injection point must contain its own faults.

A mixin handler does not run on our stack. It runs inside Minecraft's or Cobblemon's, injected into
methods like Entity.push, Entity.shouldBeSaved and BattleSelectActionsHandler.handle -- several of
them on per-tick, per-entity paths, and all of them declared `required: true`, so they are always
active. An exception escaping one of these does not break a raid; it unwinds into somebody else's
call stack and takes the server with it.

So this asserts a property rather than a spelling: every method annotated @Inject / @Redirect /
@ModifyArg under com.cobbleraids.mixin either carries a try/catch in its compiled body, or delegates
to a method that is itself a barrier (ShowdownIntegrationInstaller.installSafely). Unlike the phase
scripts, it cannot go stale when code is renamed or moved -- it re-derives the entry points from the
source every run, so a newly added mixin is covered the day it lands.

Run with no arguments to check the compiled classes under build/, or pass a JAR to check what was
actually shipped (remapping rewrites these classes, so the JAR is the one that counts).
"""

import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIXIN_SOURCE = ROOT / "src/main/java/com/cobbleraids/mixin"
BUILD_CLASSES = ROOT / "build/classes/java/main/com/cobbleraids/mixin"

# A handler may delegate its containment instead of inlining a try/catch, but only to something that
# is genuinely a barrier. Keep this list short and justify every entry.
DELEGATED_BARRIERS = {
    # installSafely() is a documented never-throws wrapper around install().
    "installSafely",
}

ANNOTATION = re.compile(r"@(Inject|Redirect|ModifyArg)\b")
DECLARATION = re.compile(r"^\s+(?:private|public|protected).*?\b(\w+)\s*\(", re.M | re.S)
# javap prints a method declaration at exactly two spaces of indent, ending in ');'
JAVAP_DECL = re.compile(r"^  (?:\S.*?\s)?(\S+)\(.*\);$", re.M)


def entry_points() -> dict[str, list[tuple[str, str]]]:
    """Every annotated injection handler in the mixin sources, as {class: [(method, kind)]}."""
    found: dict[str, list[tuple[str, str]]] = {}
    for source in sorted(MIXIN_SOURCE.rglob("*.java")):
        text = source.read_text(encoding="utf-8")
        for match in ANNOTATION.finditer(text):
            declaration = DECLARATION.search(text[match.start():])
            assert declaration, f"{source.name}: @{match.group(1)} with no method after it"
            found.setdefault(source.stem, []).append((declaration.group(1), match.group(1)))
    assert found, "no mixin injection points found at all -- has the package moved?"
    return found


def method_bodies(class_file: Path) -> dict[str, str]:
    """Disassemble one class and split it into {method name: body}."""
    output = subprocess.run(
        ["javap", "-p", "-c", str(class_file)], capture_output=True, text=True, check=True
    ).stdout
    declarations = [(m.start(), m.group(1)) for m in JAVAP_DECL.finditer(output)]
    bodies: dict[str, str] = {}
    for index, (position, name) in enumerate(declarations):
        end = declarations[index + 1][0] if index + 1 < len(declarations) else len(output)
        bodies.setdefault(name.split(".")[-1], output[position:end])
    return bodies


def validate(class_root: Path) -> int:
    unguarded: list[str] = []
    checked = 0

    for class_name, handlers in entry_points().items():
        matches = list(class_root.rglob(f"{class_name}.class"))
        assert matches, f"{class_name} has no compiled class under {class_root}"
        bodies = method_bodies(matches[0])

        for method, kind in handlers:
            checked += 1
            body = next((b for name, b in bodies.items() if name.endswith(method)), None)
            assert body is not None, f"{class_name}.{method} is not in the compiled class"

            if "Exception table" in body:
                continue
            if any(barrier in body for barrier in DELEGATED_BARRIERS):
                continue
            unguarded.append(f"{class_name}.{method} (@{kind})")

    if unguarded:
        print("Mixin injection points with no fault barrier:", file=sys.stderr)
        for entry in unguarded:
            print(f"  {entry}", file=sys.stderr)
        print(
            "\nWrap the handler body in try/catch and report through RaidFaultBarrier. Fail OPEN:\n"
            "  - a cancellable @Inject should simply return without setting the CallbackInfo, so\n"
            "    vanilla behaviour resumes;\n"
            "  - a @Redirect must still perform the call it replaced, or the original action is\n"
            "    silently skipped.",
            file=sys.stderr,
        )
        raise SystemExit(1)

    return checked


def main() -> None:
    if len(sys.argv) > 1:
        jar = Path(sys.argv[1])
        with tempfile.TemporaryDirectory() as work:
            with zipfile.ZipFile(jar) as archive:
                members = [
                    name for name in archive.namelist()
                    if name.startswith("com/cobbleraids/mixin/") and name.endswith(".class")
                ]
                assert members, f"{jar} contains no mixin classes"
                archive.extractall(work, members)
            checked = validate(Path(work) / "com/cobbleraids/mixin")
        print(f"Mixin fault-barrier validation ({jar.name}): PASS -- {checked} injection points guarded")
    else:
        checked = validate(BUILD_CLASSES)
        print(f"Mixin fault-barrier validation: PASS -- {checked} injection points guarded")


if __name__ == "__main__":
    main()
