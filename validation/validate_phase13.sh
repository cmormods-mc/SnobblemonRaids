#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COBBLEMON_JAR="${COBBLEMON_JAR:-/mnt/data/Cobblemon-fabric-1.7.3+1.21.1.jar}"
COBBLEBOSS_JAR="${COBBLEBOSS_JAR:-/mnt/data/cobbleboss-6.0.0-fabric.jar}"
RAIDDENS_JAR="${RAIDDENS_JAR:-/mnt/data/cobblemonraiddens-neoforge-0.11.4+1.21.1.jar}"

for f in "$COBBLEMON_JAR" "$COBBLEBOSS_JAR" "$RAIDDENS_JAR"; do
  test -f "$f" || { echo "Missing reference JAR: $f" >&2; exit 1; }
done

echo '[1/9] Validate Cobblemon 1.7.3 entity/battle APIs'
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.pokemon.Pokemon | grep -F 'sendOut(' >/dev/null
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.entity.pokemon.PokemonEntity | grep -F 'setCountsTowardsSpawnCap(boolean)' >/dev/null
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.entity.pokemon.PokemonEntity | grep -F 'setBattleId(java.util.UUID)' >/dev/null
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.battles.actor.PokemonBattleActor | grep -F 'PokemonBattleActor(java.util.UUID' >/dev/null
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.battles.BattleRegistry | grep -F 'startBattle(' >/dev/null

echo '[2/9] Validate Cobblemon battle-end cleanup ordering'
PB="$(mktemp)"
javap -classpath "$COBBLEMON_JAR" -c -p com.cobblemon.mod.common.api.battles.model.PokemonBattle > "$PB"
grep -F 'BattleEndPacket' "$PB" >/dev/null
grep -F 'BattleRegistry.closeBattle' "$PB" >/dev/null
rm -f "$PB"

echo '[3/9] Validate reference patterns (CobbleBoss spawn + Raid Dens terminal dispatch)'
javap -classpath "$COBBLEBOSS_JAR:$COBBLEMON_JAR" -c -p com.cobbleboss.util.boss.PokemonBossSpawner | grep -F 'Pokemon.sendOut' >/dev/null
RD="$(mktemp)"
javap -classpath "$RAIDDENS_JAR:$COBBLEMON_JAR" -c -p com.necro.raid.dens.common.showdown.instructions.RaidDamageInstruction > "$RD"
grep -F 'getDispatches' "$RD" >/dev/null
grep -F 'ConcurrentLinkedDeque.clear' "$RD" >/dev/null
grep -F 'dispatchToFront' "$RD" >/dev/null
rm -f "$RD"

echo '[4/9] Validate standalone dependency boundary'
if grep -R -n -E 'import com\.cobbleboss|import com\.necro\.raid' "$ROOT/src"; then
  echo 'Reference-mod runtime import found' >&2
  exit 1
fi
test ! -d "$ROOT/src/main/java/com/cobbleraids/boss" || { echo 'Obsolete standalone boss path still present' >&2; exit 1; }

echo '[5/9] Validate raid definition + dependency metadata'
python - "$ROOT" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1])
defn=json.loads((root/'src/main/resources/data/cobbleraids/raids/example_garchomp.json').read_text())
assert defn['recruitment']['duration_seconds'] == 45
assert defn['recruitment']['radius'] == 10.0
assert 1 <= defn['recruitment']['max_players'] <= 4
assert defn['base_health'] > 0
meta=json.loads((root/'src/main/resources/fabric.mod.json').read_text())
assert meta['depends']['cobblemon'] == '1.7.3'
assert 'cobbleboss' not in meta.get('depends', {})
assert 'cobblemonraiddens' not in meta.get('depends', {})
PY

echo '[6/9] Build exact Showdown 1.7.3 validation sandbox and apply only fail-fast raid edits'
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -p "$COBBLEMON_JAR" data/cobblemon/showdown.zip > "$TMP/showdown.zip"
unzip -q "$TMP/showdown.zip" -d "$TMP/showdown"
mkdir -p "$TMP/showdown/data/mods/cobblemon"
cp "$ROOT/src/main/resources/assets/cobbleraids/showdown/raid-patch.js" "$TMP/showdown/raid-patch.js"
cp "$ROOT/src/main/resources/assets/cobbleraids/showdown/mods/conditions.js" "$TMP/showdown/data/mods/cobblemon/conditions.js"
python - "$TMP/showdown" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
# These exact strings are deliberately version gates, matching the Java loader.
dex=root/'sim/dex-formats.js'
s=dex.read_text()
old='this.playerCount = this.gameType === "multi" || this.gameType === "freeforall" ? 4 : 2;'
new='this.playerCount = this.gameType === "raid" && Number.isInteger(data.playerCount) ? data.playerCount : this.gameType === "multi" || this.gameType === "freeforall" ? 4 : 2;'
assert s.count(old)==1, 'Cobblemon 1.7.3 playerCount signature mismatch'
dex.write_text(s.replace(old,new))
idx=root/'index.js'
s=idx.read_text()
needle="const BS = require('./sim/battle-stream');"
hook="require('./raid-patch');"
assert s.count(needle)==1, 'Cobblemon 1.7.3 index bootstrap signature mismatch'
idx.write_text(s.replace(needle, needle+'\n'+hook))
PY
cp "$ROOT/validation/dynamic-raid-test.js" "$TMP/dynamic-raid-test.js"
cp "$ROOT/validation/raid-victory-test.js" "$TMP/raid-victory-test.js"
cp "$ROOT/validation/runtime-bootstrap-test.js" "$TMP/runtime-bootstrap-test.js"

echo '[7/9] Validate Showdown JavaScript syntax + actual bootstrap'
node --check "$TMP/showdown/raid-patch.js"
node --check "$TMP/showdown/data/mods/cobblemon/conditions.js"
(cd "$TMP" && node runtime-bootstrap-test.js >/dev/null)

echo '[8/9] Validate dynamic 1..4 player shared-combat topology'
(cd "$TMP" && node dynamic-raid-test.js)

echo '[9/9] Validate controlled multi-winner terminal protocol'
(cd "$TMP" && node raid-victory-test.js)

echo 'PHASE 13 VALIDATION PASSED'
