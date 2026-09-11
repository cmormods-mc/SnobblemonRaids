#!/usr/bin/env python3
"""Every callback CobbleRaids registers with Fabric must contain its own faults.

A registered listener does not run on our stack. It runs inside Minecraft's packet handling, chunk
loading or server lifecycle, and a Fabric event invoker walks its listeners with no try/catch of its
own. So an escaping exception does two separate kinds of damage:

  - it reaches the server thread and stops the server, exactly as an unguarded tick would;
  - it prevents the listeners registered *after* ours from running at all. That second effect is the
    quieter one and nearly cost more: three of this mod's four SERVER_STARTED listeners restore
    player data, so a throw in the first would have skipped them in silence.

This was missed once already. The tick loop, the Cobblemon battle events and all 17 mixin injection
points were guarded while fifteen ordinary `EVENT.register(...)` calls were not -- including
ENTITY_UNLOAD, which fires for every entity leaving every chunk, and UseEntityCallback, which fires
on every right-click on any entity and calls straight into lobby recruitment. Guarding by mechanism
rather than by enumerating entry points is what let that happen, so this enumerates.

The rule: every registration's argument must mention RaidFaultBarrier -- either a wrapper such as
`RaidFaultBarrier.entityUnload(...)` / `RaidFaultBarrier.guard(...)`, or an inline try/catch that
reports through `RaidFaultBarrier.report(...)` where the safe fallback is domain-specific (the
interaction listener needs PASS before it knows the entity is a raid boss, and SUCCESS after).

Command registration is exempt: Brigadier already wraps command execution, and the registration
callback only builds the dispatcher tree.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "src/main/java/com/cobbleraids"

# Registration calls this mod makes against someone else's event system.
REGISTRATION = re.compile(
    r"(?P<event>[\w.]*(?:EVENT|[A-Z]\w*Events\.[A-Z_]+))\.register\s*\(|"
    r"(?P<net>registerGlobalReceiver)\s*\(",
)

# Building a Brigadier tree; command bodies are wrapped by Minecraft itself.
EXEMPT_EVENTS = ("CommandRegistrationCallback.EVENT",)

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def code_only(text: str) -> str:
    """Blank out comments while preserving offsets, so line numbers stay right."""
    def blank(match: re.Match) -> str:
        return "".join("\n" if c == "\n" else " " for c in match.group(0))

    return LINE_COMMENT.sub(blank, BLOCK_COMMENT.sub(blank, text))


def argument_span(text: str, open_paren: int) -> str:
    """The text between the '(' at `open_paren` and its matching ')'."""
    depth, index, in_string, in_char, escaped = 0, open_paren, False, False, False
    while index < len(text):
        c = text[index]
        if in_string or in_char:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif in_string and c == '"':
                in_string = False
            elif in_char and c == "'":
                in_char = False
        elif c == '"':
            in_string = True
        elif c == "'":
            in_char = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:index]
        index += 1
    raise AssertionError("unbalanced parentheses after offset %d" % open_paren)


def main() -> None:
    unguarded: list[str] = []
    checked = 0

    for source in sorted(MAIN.rglob("*.java")):
        raw = source.read_text(encoding="utf-8")
        body = code_only(raw)
        relative = source.relative_to(ROOT).as_posix()

        for match in REGISTRATION.finditer(body):
            event = (match.group("event") or "registerGlobalReceiver").strip()
            if any(event.endswith(exempt) or exempt in event for exempt in EXEMPT_EVENTS):
                continue

            open_paren = body.index("(", match.start())
            argument = argument_span(body, open_paren)
            checked += 1

            if "RaidFaultBarrier" in argument:
                continue
            line = body.count("\n", 0, match.start()) + 1
            unguarded.append(f"{relative}:{line}  {event}.register(...)")

    assert checked, "no callback registrations found at all -- has the initializer moved?"

    if unguarded:
        print("Callback registrations with no fault barrier:", file=sys.stderr)
        for entry in unguarded:
            print(f"  {entry}", file=sys.stderr)
        print(
            "\nWrap the listener so a fault cannot escape into Minecraft's own call stack:\n"
            "  - hot paths (per entity, per tick): a typed wrapper such as\n"
            "    RaidFaultBarrier.entityUnload(name, Target::method), which allocates once at\n"
            "    registration rather than once per event;\n"
            "  - cold paths (startup, shutdown, a player joining): RaidFaultBarrier.guard(name, body);\n"
            "  - anywhere the safe fallback value is domain-specific: an inline try/catch that\n"
            "    reports through RaidFaultBarrier.report(name, ex) and returns the right default.",
            file=sys.stderr,
        )
        raise SystemExit(1)

    print(f"Callback fault-barrier validation: PASS -- {checked} registrations guarded")


if __name__ == "__main__":
    main()
