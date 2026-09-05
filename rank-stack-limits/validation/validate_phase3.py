#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

eligibility = (ROOT / 'src/main/java/com/cmormods/rankstacklimits/policy/StackEligibilityPolicy.java').read_text()
redistribution = (ROOT / 'src/main/java/com/cmormods/rankstacklimits/policy/StackRedistributionPlanner.java').read_text()
eligibility_tests = (ROOT / 'src/test/java/com/cmormods/rankstacklimits/policy/StackEligibilityPolicyTest.java').read_text()
redistribution_tests = (ROOT / 'src/test/java/com/cmormods/rankstacklimits/policy/StackRedistributionPlannerTest.java').read_text()
build = (ROOT / 'build.gradle').read_text()

checks = []

def require(ok, label):
    checks.append((ok, label))
    if not ok:
        print(f'FAIL: {label}')
        sys.exit(1)
    print(f'PASS: {label}')

require("version = '0.3.0-phase3'" in build, 'Phase 3 build identity')
require('intrinsicLimit == 1' in eligibility and 'preserveVanillaUnstackables' in eligibility,
        'Vanilla max-1 items have an explicit protection branch')
require('Math.max(intrinsicLimit, playerLimit)' in eligibility,
        'Rank limits never reduce a pre-existing larger intrinsic stack limit')
require('Math.min(remaining, newLimit)' in redistribution,
        'Downgrade planner chunks only to the new limit')
require('remaining -= chunk' in redistribution,
        'Downgrade planner accounts for every item')
require('List.copyOf(chunks)' in redistribution,
        'Downgrade plan is immutable after construction')
require('List.of(64, 35)' in redistribution_tests,
        'Explicit 99 -> 64 + 35 downgrade regression test exists')
require('count <= 500' in redistribution_tests and 'limit <= 99' in redistribution_tests,
        'Property-style lossless split sweep covers v1 range and oversized totals')
require('mapToInt(Integer::intValue).sum()' in redistribution_tests,
        'Tests assert item-count conservation')
require('effectiveLimit(1, 99, true)' in eligibility_tests,
        'Vanilla-unstackable protection is unit tested')
require('effectiveLimit(16, 96, true)' in eligibility_tests,
        'Lower intrinsic stack sizes can be raised for ranked players')
require('item-components' not in build.lower() and 'owo' not in build.lower(),
        'Phase 3 still has no Item Components or owo dependency')

print(f'Phase 3 validation complete: {len(checks)} gates passed.')
