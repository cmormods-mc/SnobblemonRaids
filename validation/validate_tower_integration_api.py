#!/usr/bin/env python3
"""
Validation notes
================
Exact functionality:
- Verifies the dedicated `com.cobbleraids.api` package exists and contains the initial public
  Tower integration contract types.
- Rejects imports from CobbleRaids implementation packages inside those public API files.
- Rejects mutable runtime implementation types such as RaidSession, PokemonBattle, and
  PokemonEntity from the API surface.

Architectural role:
- Enforces the anti-corruption boundary mechanically in CI so addon-facing contracts remain stable
  even while CobbleRaids internals evolve.

Performance impact:
- CI-only filesystem validation; zero runtime server cost.

Assumptions and constraints:
- This validates API shape, not runtime encounter semantics. External encounter reward/cleanup
  behavior is covered by lifecycle tests as that implementation is added.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
API_DIR = ROOT / "src/main/java/com/cobbleraids/api"

REQUIRED = {
    "RaidBossDescriptor.java",
    "RaidEncounterHandle.java",
    "RaidEncounterOutcome.java",
    "RaidEncounterResult.java",
}

FORBIDDEN_IMPORT_PREFIXES = (
    "com.cobbleraids.lifecycle",
    "com.cobbleraids.raid",
    "com.cobbleraids.spawn",
    "com.cobbleraids.reward",
    "com.cobbleraids.mixin",
)

FORBIDDEN_TYPES = (
    "RaidSession",
    "PokemonBattle",
    "PokemonEntity",
)


def fail(message: str) -> None:
    print(f"tower integration API validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


if not API_DIR.is_dir():
    fail(f"missing API package: {API_DIR.relative_to(ROOT)}")

present = {path.name for path in API_DIR.glob("*.java")}
missing = sorted(REQUIRED - present)
if missing:
    fail("missing required API files: " + ", ".join(missing))

for path in sorted(API_DIR.glob("*.java")):
    text = path.read_text(encoding="utf-8")

    package_match = re.search(r"^package\s+([^;]+);", text, re.MULTILINE)
    if not package_match or package_match.group(1) != "com.cobbleraids.api":
        fail(f"{path.name} is not declared in com.cobbleraids.api")

    for prefix in FORBIDDEN_IMPORT_PREFIXES:
        if re.search(rf"^import\s+{re.escape(prefix)}(?:\.|;)", text, re.MULTILINE):
            fail(f"{path.name} imports internal package {prefix}")

    for type_name in FORBIDDEN_TYPES:
        if re.search(rf"\b{re.escape(type_name)}\b", text):
            fail(f"{path.name} exposes forbidden runtime type {type_name}")

print(f"tower integration API boundary OK ({len(present)} public API source files checked)")
