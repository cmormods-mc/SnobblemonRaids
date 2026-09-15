#!/usr/bin/env python3
"""The public API may only be written in types its consumers can depend on.

com.cobbleraids.api is the contract other mods compile against. If a public signature there names a
CobbleRaids internal type or a Cobblemon type, every consumer is coupled to that type whether it
means to be or not, and the "internals may change freely" promise is broken silently. So this reads
the compiled classes -- not the source -- and fails on any public or protected field, method, super
type or generic signature that references a type outside:

    java/                  the JDK
    net/minecraft/         Minecraft, which every consumer already depends on
    com/cobbleraids/api/   the API itself

Reading bytecode means it cannot be fooled by a fully qualified name, an import alias or a type
reached through a generic argument, and it keeps working when classes are renamed or added.

    python validation/validate_api_boundary.py                         # compiled classes
    python validation/validate_api_boundary.py build/libs/CobbleRaids-x.jar
"""

from __future__ import annotations

import re
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API_PREFIX = "com/cobbleraids/api/"
ALLOWED = ("java/", "net/minecraft/", API_PREFIX)
ACC_PUBLIC = 0x0001
ACC_PROTECTED = 0x0004
# Class names inside descriptors and generic signatures: Lpkg/Name; or Lpkg/Name<...>;
CLASS_NAME = re.compile(r"L([^;<]+)")


def parse_class(data: bytes) -> dict:
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    pos = 8
    (count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    pool: list = [None] * count
    index = 1
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (length,) = struct.unpack_from(">H", data, pos)
            pos += 2
            pool[index] = data[pos:pos + length].decode("utf-8", "replace")
            pos += length
        elif tag == 7:
            pool[index] = ("class", struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        index += 1

    def class_name(i: int) -> str:
        return pool[pool[i][1]]

    access, this_index, super_index, interface_count = struct.unpack_from(">HHHH", data, pos)
    pos += 8
    interfaces = []
    for _ in range(interface_count):
        interfaces.append(class_name(struct.unpack_from(">H", data, pos)[0]))
        pos += 2

    def read_attributes(p: int) -> tuple[int, list[str]]:
        (attribute_count,) = struct.unpack_from(">H", data, p)
        p += 2
        signatures = []
        for _ in range(attribute_count):
            name_index, length = struct.unpack_from(">HI", data, p)
            p += 6
            if pool[name_index] == "Signature":
                signatures.append(pool[struct.unpack_from(">H", data, p)[0]])
            p += length
        return p, signatures

    members = []
    for _kind in ("field", "method"):
        (member_count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        for _ in range(member_count):
            member_access, name_index, descriptor_index = struct.unpack_from(">HHH", data, pos)
            pos += 6
            pos, signatures = read_attributes(pos)
            members.append((member_access, pool[name_index], pool[descriptor_index], signatures))
    _, class_signatures = read_attributes(pos)

    return {
        "access": access,
        "name": class_name(this_index),
        "super": class_name(super_index) if super_index else None,
        "interfaces": interfaces,
        "members": members,
        "signatures": class_signatures,
    }


def api_classes(source: Path | None) -> list[tuple[str, bytes]]:
    if source is None:
        base = ROOT / "build" / "classes" / "java" / "main"
        return [(str(p.relative_to(base)).replace("\\", "/"), p.read_bytes())
                for p in sorted((base / API_PREFIX).rglob("*.class"))]
    with zipfile.ZipFile(source) as jar:
        return [(name, jar.read(name)) for name in sorted(jar.namelist())
                if name.startswith(API_PREFIX) and name.endswith(".class")]


def violations_in(parsed: dict) -> list[str]:
    found = []

    def check(where: str, text: str | None) -> None:
        if not text:
            return
        for referenced in CLASS_NAME.findall(text):
            if not referenced.startswith(ALLOWED):
                found.append(f"{where} references {referenced}")

    owner = parsed["name"]
    for super_type in [parsed["super"], *parsed["interfaces"]]:
        if super_type and not super_type.startswith(ALLOWED):
            found.append(f"{owner} extends or implements {super_type}")
    for signature in parsed["signatures"]:
        check(owner, signature)
    for access, name, descriptor, signatures in parsed["members"]:
        if not access & (ACC_PUBLIC | ACC_PROTECTED):
            continue
        check(f"{owner}.{name}", descriptor)
        for signature in signatures:
            check(f"{owner}.{name}", signature)
    return found


def main() -> None:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    classes = api_classes(source)
    if not classes:
        print(f"API boundary validation: FAIL -- no classes under {API_PREFIX} in "
              f"{source or 'build/classes/java/main'}; nothing was checked")
        sys.exit(1)

    public_types = 0
    violations: list[str] = []
    for path, data in classes:
        parsed = parse_class(data)
        if not parsed["access"] & ACC_PUBLIC:
            continue
        public_types += 1
        violations.extend(violations_in(parsed))

    label = f" ({source.name})" if source else ""
    if violations:
        print(f"API boundary validation{label}: FAIL")
        for violation in violations:
            print("  " + violation)
        sys.exit(1)
    print(f"API boundary validation{label}: PASS -- {public_types} public type(s) under {API_PREFIX},"
          f" every signature within java/, net/minecraft/ and the API itself")


if __name__ == "__main__":
    main()
