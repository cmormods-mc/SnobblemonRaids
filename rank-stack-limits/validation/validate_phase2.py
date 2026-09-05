#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

policy = (ROOT / 'src/main/java/com/cmormods/rankstacklimits/policy/StackLimitPolicy.java').read_text()
resolver = (ROOT / 'src/main/java/com/cmormods/rankstacklimits/policy/LuckPermsStackLimitResolver.java').read_text()
config = (ROOT / 'src/main/java/com/cmormods/rankstacklimits/config/RankStackLimitsConfig.java').read_text()
tests = (ROOT / 'src/test/java/com/cmormods/rankstacklimits/policy/StackLimitPolicyTest.java').read_text()
build = (ROOT / 'build.gradle').read_text()

checks = []

def require(ok, label):
    checks.append((ok, label))
    if not ok:
        print(f'FAIL: {label}')
        sys.exit(1)
    print(f'PASS: {label}')

require('LuckPermsStackLimitResolver' in resolver, 'LuckPerms resolver exists')
require('getUser(player.getUUID())' in resolver, 'Resolver uses the online player UUID')
require('getMetaValue(config.luckPermsMetaKey())' in resolver, 'Resolver reads configurable LuckPerms meta')
require('StackLimitPolicy.resolve' in resolver, 'Resolver delegates numeric safety to policy')
require('DEFAULT_LIMIT = 64' in config and 'V1_MAX_LIMIT = 99' in config, 'Config defaults remain 64..99')
require('DEFAULT_META_KEY = "stack-limit"' in config, 'Default meta key remains stack-limit')
require('preserveVanillaUnstackables' in config, 'Vanilla-unstackable protection flag exists')
require('rankstacklimits.json' in config, 'Server config path is rankstacklimits.json')
require('Integer.parseInt' in policy and 'Math.min' in policy, 'Policy parses and clamps meta values')
require('testImplementation' in build and 'junit-jupiter' in build and 'useJUnitPlatform' in build, 'JUnit tests are wired into Gradle build')
require(tests.count('@Test') >= 5, 'Policy has at least five unit-test cases')
require('item-components' not in build.lower() and 'owo' not in build.lower(), 'Phase 2 still has no Item Components or owo dependency')

print(f'Phase 2 validation complete: {len(checks)} gates passed.')
