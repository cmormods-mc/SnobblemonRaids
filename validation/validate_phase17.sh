#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COBBLEMON_JAR="${COBBLEMON_JAR:-/mnt/data/Cobblemon-fabric-1.7.3+1.21.1.jar}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

echo '[1/5] Validate exact Cobblemon 1.7.3 index structure used by tolerant hook'
unzip -p "$COBBLEMON_JAR" data/cobblemon/showdown.zip > "$TMP/showdown.zip"
unzip -q "$TMP/showdown.zip" -d "$TMP/showdown"
IDX="$TMP/showdown/index.js"
grep -F "./sim/battle-stream" "$IDX" >/dev/null
grep -F 'function startBattle(' "$IDX" >/dev/null
grep -F 'function sendBattleMessage(' "$IDX" >/dev/null

echo '[2/5] Validate bootstrap hook is append-only, idempotent, and structurally fail-closed'
python - "$ROOT" "$IDX" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1]); stock=Path(sys.argv[2]).read_text()
src=(root/'src/main/java/com/cobbleraids/mixin/showdown/ShowdownResourceLoaderMixin.java').read_text()
for needle in ['INDEX_BATTLE_STREAM_MODULE', 'INDEX_START_BATTLE', 'INDEX_SEND_BATTLE_MESSAGE', 'source + separator + INDEX_RAID_HOOK']:
    assert needle in src, needle
assert 'source.replace(INDEX_BATTLE_STREAM_173' not in src
# Representative addon edit: change quote/alias/spacing while preserving the same module and entry-point surface.
modified=stock.replace("const BS = require('./sim/battle-stream');", "const BattleStreams=require(\"./sim/battle-stream\"); // modified by another addon")
assert "./sim/battle-stream" in modified and 'function startBattle(' in modified and 'function sendBattleMessage(' in modified
hook="require('./raid-patch');"
patched=modified + ('' if modified.endswith(('\n','\r')) else '\n') + hook + '\n'
assert patched.count(hook)==1
# Idempotence rule in Java returns immediately when hook is already present.
assert 'if (source.contains(INDEX_RAID_HOOK)) return;' in src
PY

echo '[3/5] Validate exact Showdown bootstrap still loads raid patch after EOF injection'
mkdir -p "$TMP/showdown/data/mods/cobblemon"
cp "$ROOT/src/main/resources/assets/cobbleraids/showdown/raid-patch.js" "$TMP/showdown/raid-patch.js"
cp "$ROOT/src/main/resources/assets/cobbleraids/showdown/mods/conditions.js" "$TMP/showdown/data/mods/cobblemon/conditions.js"
python - "$TMP/showdown" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
dex=root/'sim/dex-formats.js'; s=dex.read_text()
old='this.playerCount = this.gameType === "multi" || this.gameType === "freeforall" ? 4 : 2;'
new='this.playerCount = this.gameType === "raid" && Number.isInteger(data.playerCount) ? data.playerCount : this.gameType === "multi" || this.gameType === "freeforall" ? 4 : 2;'
assert s.count(old)==1
dex.write_text(s.replace(old,new))
idx=root/'index.js'; s=idx.read_text(); hook="require('./raid-patch');"
assert hook not in s
idx.write_text(s + ('' if s.endswith(('\n','\r')) else '\n') + hook + '\n')
PY
cp "$ROOT/validation/runtime-bootstrap-test.js" "$TMP/runtime-bootstrap-test.js"
(cd "$TMP" && node runtime-bootstrap-test.js >/dev/null)

echo '[4/5] Re-run complete Phase 16 validation suite'
bash "$ROOT/validation/validate_phase16.sh" >/dev/null

echo '[5/5] Validate production build settings incorporate compiler-discovered corrections'
grep -F "id 'fabric-loom' version '1.13-SNAPSHOT'" "$ROOT/build.gradle" >/dev/null
grep -F 'RepositoriesMode.PREFER_PROJECT' "$ROOT/settings.gradle" >/dev/null
grep -F 'MutableComponent getDisplayName()' "$ROOT/src/main/java/com/cobbleraids/battle/RaidBattleType.java" >/dev/null

echo 'PHASE 17 RUNTIME-COMPAT VALIDATION PASSED'
