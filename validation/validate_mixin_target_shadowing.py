#!/usr/bin/env python3
"""Every mixin here has one silent failure mode none of the other checks catch: the target method
never runs for a real game object, because the concrete class actually used at runtime overrides it
without calling super. `validate_mixin_guards.py` proves an injected handler cannot crash the server;
it says nothing about whether the handler is ever reached.

This happened twice, live, before it was understood. `RaidBossNameplateIconMixin` targeted vanilla
`EntityRenderer.renderNameTag` in two earlier versions and never fired once: `PokemonRenderer`
(Cobblemon's own subclass) hardcodes `shouldShowName` to false, so vanilla's own nameplate path never
runs for a Pokemon at all, and `PokemonRenderer` draws through a completely different, private
override of the same method name. Nothing threw either time -- the mixin compiled, Mixin applied it
without complaint, and it simply never ran. The fix was decompiling `PokemonRenderer` by hand to find
where the real path lived, and the reasoning is recorded on the mixin itself and in
docs/adr/0004-mixin-target-concrete-render-path.md so it does not have to be rediscovered a third
time.

So: for every `@Mixin` target and injected method name in this mod, find every class in the actual
Cobblemon + Minecraft jars on this build's classpath that is a real subclass of the target and
declares a method by that same name -- a candidate shadow. For each candidate, disassemble that one
method with `javap -c` (the same tool `validate_mixin_guards.py` already trusts) and check whether it
calls up through the chain to the target with `invokespecial`. If it does, the override is a
well-behaved wrapper and the mixin still runs; report only the ones that do not call up, which is a
mixin very possibly injected into dead code for every real game object.

This is advisory, not a hard gate: a class that overrides the method for a genuinely unrelated reason
and does not need to call up would show as a finding here without being a bug in that class. What it
guarantees is that a shadow candidate is never silent again -- someone has to look and say so, instead
of a boss going quiet in production being the first anyone hears about it.

Finding the two jars needs nothing beyond what `gradlew build` already resolved: the Cobblemon
coordinate is read out of build.gradle rather than hardcoded, and both jars are found under this
project's own `.gradle/loom-cache`, where Loom keeps its remapped, official-names copy of every mod
dependency and of Minecraft itself. Both must already exist, so this runs after the build, alongside
the other bytecode checks.
"""

import os
import re
import subprocess
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD_GRADLE = ROOT / "build.gradle"
MIXIN_SOURCE = ROOT / "src/main/java/com/cobbleraids/mixin"

ANNOTATION = re.compile(r"@(Inject|Redirect|ModifyArg)\b")
METHOD_ATTR = re.compile(r'method\s*=\s*"([^"$][^"]*)"')
MIXIN_CLASS_TARGET = re.compile(r"@Mixin\(\s*([A-Za-z0-9_]+)\.class\s*\)")
MIXIN_STRING_TARGET = re.compile(r'@Mixin\(\s*targets\s*=\s*"([^"]+)"')


LOOM_CACHE = ROOT / ".gradle/loom-cache"


def find_cobblemon_jar() -> Path | None:
    """The project-local, official-mappings copy of the mod jar.

    The Modrinth coordinate is read out of build.gradle rather than hardcoded, so a version bump does
    not go stale here. Loom keeps two copies of a mod dependency: the jar Gradle downloads as-is
    (intermediary-mapped, in the global module cache) and this project's remapped copy, whose class
    references resolve to the same official names our own source compiles against. Only the remapped
    copy is any use to this check -- the raw one reports Minecraft superclasses as opaque names like
    `class_927`, which is what happens if this ever starts resolving the wrong jar again.
    """
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    line = next((l for l in text.splitlines() if "Cobblemon" in l and "modImplementation" in l), None)
    assert line, "build.gradle no longer has a modImplementation line commented '// Cobblemon ...'"
    match = re.search(r"'([^:']+):([^:']+):([^']+)'", line)
    assert match, f"could not parse a group:artifact:version coordinate out of: {line.strip()}"
    group, artifact, version = match.groups()
    group_path = group.replace(".", "/")
    pattern = f"remapped_mods/remapped/{group_path}/{artifact}-*/{version}/*.jar"
    matches = list(LOOM_CACHE.glob(pattern))
    return matches[0] if matches else None


def find_minecraft_jar() -> Path | None:
    pattern = "minecraftMaven/net/minecraft/minecraft-merged-*/*/minecraft-merged-*.jar"
    matches = [p for p in LOOM_CACHE.glob(pattern) if "intermediary" not in p.name]
    return matches[0] if matches else None


# --- Minimal classfile structural reader: superclass + directly-declared method names only. No
# bytecode is interpreted here, which is why this part can be trusted without a full disassembler. ---

class ClassInfo:
    __slots__ = ("super_name", "method_names")

    def __init__(self, super_name: str | None, method_names: set[str]):
        self.super_name = super_name
        self.method_names = method_names


def _read_constant_pool(buf: bytes, pos: int, count: int):
    """Returns (utf8-or-class-ref table, new pos). Table maps index -> ('Utf8', str) or ('Class', ref)."""
    cp: dict[int, tuple] = {}
    i = 1
    while i < count:
        tag = buf[pos]
        pos += 1
        if tag == 1:  # Utf8
            (length,) = struct.unpack_from(">H", buf, pos)
            pos += 2
            cp[i] = ("Utf8", buf[pos:pos + length].decode("utf-8", errors="replace"))
            pos += length
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            cp[i] = ("Ref", struct.unpack_from(">H", buf, pos)[0])
            pos += 2
        elif tag in (15,):  # MethodHandle: u1 + u2
            pos += 3
        elif tag in (3, 4):  # Integer, Float
            pos += 4
        elif tag in (5, 6):  # Long, Double: 8 bytes, takes two constant-pool slots
            pos += 8
            i += 1
        elif tag in (9, 10, 11, 12, 17, 18):  # *ref, NameAndType, Dynamic, InvokeDynamic
            pos += 4
        else:
            raise ValueError(f"unrecognised constant pool tag {tag} at index {i}")
        i += 1
    return cp, pos


def _resolve_class_name(cp: dict, idx: int) -> str | None:
    if idx == 0:
        return None
    kind, ref = cp[idx]
    assert kind == "Ref"
    kind2, name = cp[ref]
    assert kind2 == "Utf8"
    return name


def _skip_attributes(buf: bytes, pos: int, count: int) -> int:
    for _ in range(count):
        pos += 2  # name index
        (length,) = struct.unpack_from(">I", buf, pos)
        pos += 4 + length
    return pos


def read_class_info(data: bytes) -> ClassInfo | None:
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return None
    pos = 8  # magic + minor + major
    (cp_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    cp, pos = _read_constant_pool(data, pos, cp_count)
    pos += 2  # access_flags
    pos += 2  # this_class (unused: caller already knows the class's own name from the zip entry)
    (super_idx,) = struct.unpack_from(">H", data, pos)
    pos += 2
    super_name = _resolve_class_name(cp, super_idx)
    (iface_count,) = struct.unpack_from(">H", data, pos)
    pos += 2 + 2 * iface_count
    (field_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    for _ in range(field_count):
        pos += 6  # access_flags, name_index, descriptor_index
        (attr_count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        pos = _skip_attributes(data, pos, attr_count)
    (method_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    method_names: set[str] = set()
    for _ in range(method_count):
        pos += 2  # access_flags
        (name_idx,) = struct.unpack_from(">H", data, pos)
        pos += 2
        pos += 2  # descriptor_index
        method_names.add(cp[name_idx][1])
        (attr_count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        pos = _skip_attributes(data, pos, attr_count)
    return ClassInfo(super_name, method_names)


def load_universe(jar_path: Path) -> dict[str, ClassInfo]:
    universe: dict[str, ClassInfo] = {}
    with zipfile.ZipFile(jar_path) as zf:
        for name in zf.namelist():
            if not name.endswith(".class") or name.endswith("module-info.class"):
                continue
            info = read_class_info(zf.read(name))
            if info is not None:
                universe[name[:-6]] = info  # internal name, slashes, no ".class"
    return universe


def is_cobblemon_class(class_name: str) -> bool:
    """Whether a candidate override is Cobblemon's own code rather than an unrelated vanilla class.

    A raid boss is always constructed as a Cobblemon entity (PokemonEntity today, always something
    under com.cobblemon), never as an arbitrary vanilla entity like Warden or ArmorStand -- so a
    vanilla class overriding a vanilla method it inherits from Entity is real, but irrelevant to
    whether *this mod's* mixin still runs: no code path here ever hands the mixin a Warden. Limiting
    findings to Cobblemon's own classes is what keeps this check pointed at the one thing that broke
    twice (Cobblemon quietly shadowing a target we mixed into) instead of flooding on Mojang's own,
    unrelated entity hierarchy every time Minecraft ships a new one.
    """
    return class_name.startswith("com/cobblemon/")


# --- Our own mixin sources: which (target internal name, method name) pairs need checking. ---

def mixin_targets() -> list[tuple[str, str, Path]]:
    targets: list[tuple[str, str, Path]] = []
    for source in sorted(MIXIN_SOURCE.rglob("*.java")):
        text = source.read_text(encoding="utf-8")

        class_match = MIXIN_CLASS_TARGET.search(text)
        if class_match:
            simple = class_match.group(1)
            import_match = re.search(rf"^import\s+([\w.]+\.{simple});", text, re.M)
            if not import_match:
                continue  # same-package target with no import; none of ours today
            target_internal = import_match.group(1).replace(".", "/")
        else:
            string_match = MIXIN_STRING_TARGET.search(text)
            if not string_match:
                continue
            target_internal = string_match.group(1).replace(".", "/")

        for ann in ANNOTATION.finditer(text):
            # Look at the annotation's own arguments, up to the next ')' at depth 0 from '('.
            start = text.index("(", ann.end() - 1)
            depth = 0
            end = start
            for j in range(start, len(text)):
                if text[j] == "(":
                    depth += 1
                elif text[j] == ")":
                    depth -= 1
                    if depth == 0:
                        end = j
                        break
            args = text[start:end]
            method_match = METHOD_ATTR.search(args)
            if method_match:
                # Some targets disambiguate an overload with a full method descriptor
                # ("name(Ldesc;)Ldesc;") rather than a bare name. Everywhere downstream compares
                # against ClassInfo.method_names, which the classfile reader (read_class_info) fills
                # with bare names only -- so a descriptor left intact here can never match, silently
                # skipping every candidate for that target while checked_pairs still counts it as
                # checked. Strip to the bare name before it leaves this function.
                method_name = method_match.group(1).split("(", 1)[0]
                targets.append((target_internal, method_name, source))
    return targets


def ancestors_up_to(universe: dict[str, ClassInfo], start: str, stop: str) -> list[str] | None:
    """Ancestor chain from start's superclass up to and including stop, or None if stop is not reached."""
    chain: list[str] = []
    current = universe.get(start)
    name = current.super_name if current else None
    while name is not None:
        chain.append(name)
        if name == stop:
            return chain
        info = universe.get(name)
        name = info.super_name if info else None
    return None


def calls_up_to(jar_classpath: str, external_name: str, method_name: str, allowed_owners: set[str]) -> bool:
    result = subprocess.run(
        ["javap", "-p", "-c", "-cp", jar_classpath, external_name],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        # Cannot disassemble (e.g. synthetic bridge with an unusual name) -- do not claim certainty.
        return True
    blocks = result.stdout.split("\n\n")
    owner_pattern = "|".join(re.escape(o) for o in allowed_owners)
    invoke_super = re.compile(rf"invokespecial\s+#\d+\s*//\s*Method ({owner_pattern})\.{re.escape(method_name)}:")
    for block in blocks:
        first_line = block.strip().splitlines()[0] if block.strip() else ""
        if f" {method_name}(" not in first_line:
            continue
        if invoke_super.search(block):
            return True
    return False


def main() -> None:
    cobblemon_jar = find_cobblemon_jar()
    minecraft_jar = find_minecraft_jar()
    if cobblemon_jar is None or minecraft_jar is None:
        print("Mixin target shadowing validation: SKIPPED -- dependency jars not resolved yet"
              " (run gradlew build first)", file=sys.stderr)
        raise SystemExit(0)

    universe: dict[str, ClassInfo] = {}
    universe.update(load_universe(minecraft_jar))
    universe.update(load_universe(cobblemon_jar))  # Cobblemon's own classes take precedence on clash

    targets = mixin_targets()
    assert targets, "no mixin @Inject/@Redirect/@ModifyArg targets found -- has the mixin package moved?"

    classpath = f"{cobblemon_jar}{os.pathsep}{minecraft_jar}"
    findings: list[str] = []
    checked_pairs = 0

    for target_internal, method_name, source in targets:
        if target_internal not in universe:
            continue  # a Cobblemon-internal or unresolved type; nothing more this check can do
        checked_pairs += 1
        for candidate, info in universe.items():
            if candidate == target_internal or method_name not in info.method_names:
                continue
            if not is_cobblemon_class(candidate):
                continue
            chain = ancestors_up_to(universe, candidate, target_internal)
            if chain is None:
                continue  # not a descendant of the target at all
            allowed_owners = {target_internal, *chain}
            external_name = candidate.replace("/", ".")
            if not calls_up_to(classpath, external_name, method_name, allowed_owners):
                findings.append(
                    f"{source.relative_to(ROOT).as_posix()}: mixin targets"
                    f" {target_internal.replace('/', '.')}.{method_name}, but"
                    f" {external_name} overrides {method_name} without calling up to it -- for any"
                    f" real object of that concrete type, this mixin's injection may never run"
                )

    if findings:
        print("Possible mixin target shadowing (needs a human look, not necessarily a bug):",
              file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        raise SystemExit(1)

    print(f"Mixin target shadowing validation: PASS -- {checked_pairs} target(s) checked against"
          f" {len(universe)} classes, no unguarded override found")


if __name__ == "__main__":
    main()
