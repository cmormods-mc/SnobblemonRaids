#!/usr/bin/env python3
"""Source-level guardrails for addon-managed encounter semantics."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
LIFECYCLE = ROOT / "src/main/java/com/cobbleraids/lifecycle/RaidLifecycleCoordinator.java"
SESSION = ROOT / "src/main/java/com/cobbleraids/raid/RaidSession.java"
FACTORY = ROOT / "src/main/java/com/cobbleraids/raid/RaidFactory.java"
CALLBACKS = ROOT / "src/main/java/com/cobbleraids/raid/ExternalEncounterCallbacks.java"
IMPL = ROOT / "src/main/java/com/cobbleraids/integration/RaidEncounterApiImpl.java"


def fail(message: str) -> None:
    print(f"external encounter lifecycle validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)

for path in (LIFECYCLE, SESSION, FACTORY, CALLBACKS, IMPL):
    if not path.is_file():
        fail(f"missing {path.relative_to(ROOT)}")

lifecycle = LIFECYCLE.read_text(encoding="utf-8")
session = SESSION.read_text(encoding="utf-8")
factory = FACTORY.read_text(encoding="utf-8")
callbacks = CALLBACKS.read_text(encoding="utf-8")
impl = IMPL.read_text(encoding="utf-8")

required_lifecycle = (
    "if (raid.isExternalEncounter())",
    "RaidBattleStateCarryover.apply(raid);",
    "RaidProgressionTransfer.grant(raid, server);",
    "recordAndOfferCatch(raid, server);",
    "RaidRewardService.grant(RaidRewardEligibility.victory(raid), server);",
    "if (raid.isExternalEncounter() || !countsAsFailedAttempt) cleanupBossEntity(raid);",
    "ExternalEncounterCallbacks.complete(raid);",
)
for token in required_lifecycle:
    if token not in lifecycle:
        fail(f"lifecycle is missing required semantic guard: {token}")

if "RaidCompletionPolicy.STANDARD" not in session:
    fail("RaidSession default constructor no longer preserves STANDARD behavior")
if "RaidCompletionPolicy.STANDARD" not in factory:
    fail("RaidFactory default overload no longer preserves STANDARD behavior")
if "RaidCompletionPolicy.EXTERNAL" not in impl:
    fail("public encounter implementation does not opt into EXTERNAL behavior")
if "CALLBACKS.remove(raid.getId())" not in callbacks:
    fail("completion callback is not removed before invocation")
if "RaidFaultBarrier.report(\"external-encounter-completion\"" not in callbacks:
    fail("external completion callback is not fault-isolated")
if "requireServerThread(\"api:start-external-encounter\")" not in impl:
    fail("external encounter start is not restricted to the server thread")

print("external encounter lifecycle source invariants OK")
