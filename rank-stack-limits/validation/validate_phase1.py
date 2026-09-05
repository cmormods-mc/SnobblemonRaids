#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

build = (ROOT / 'build.gradle').read_text()
mod = json.loads((ROOT / 'src/main/resources/fabric.mod.json').read_text())
main = (ROOT / 'src/main/java/com/cmormods/rankstacklimits/RankStackLimits.java').read_text()
config_path = ROOT / 'src/main/java/com/cmormods/rankstacklimits/config/RankStackLimitsConfig.java'
config = config_path.read_text() if config_path.exists() else main

checks = []

def require(ok, label):
    checks.append((ok, label))
    if not ok:
        print(f'FAIL: {label}')
        sys.exit(1)
    print(f'PASS: {label}')

require("minecraft 'com.mojang:minecraft:1.21.1'" in build, 'Minecraft locked to 1.21.1')
require("net.fabricmc:fabric-loader:0.17.2" in build, 'Fabric Loader dependency present')
require("net.fabricmc.fabric-api:fabric-api:0.116.6+1.21.1" in build, 'Fabric API dependency present')
require("compileOnly 'net.luckperms:api:5.4'" in build, 'LuckPerms API is compileOnly')
require('item-components' not in build.lower() and 'owo' not in build.lower(), 'No Item Components or owo dependency')
require(mod['environment'] == 'server', 'Mod is server-only')
require(mod['depends'].get('luckperms') == '>=5.4', 'LuckPerms declared as runtime dependency')
require(mod['entrypoints']['main'] == ['com.cmormods.rankstacklimits.RankStackLimits'], 'Entrypoint metadata matches source')
require('LuckPermsProvider.get()' in main, 'Entrypoint obtains LuckPerms service')
require('"stack-limit"' in config, 'LuckPerms meta key defaults to stack-limit')
require('99' in config and '64' in config, 'v1 range constants preserve 64..99 policy')
require(not re.search(r'package\s+com\.cmormods\.rankstacklimits\.mixin\s*;', main), 'Entrypoint is outside future mixin package')

print(f'Phase 1 validation complete: {len(checks)} gates passed.')
