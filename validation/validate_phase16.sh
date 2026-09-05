#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COBBLEMON_JAR="${COBBLEMON_JAR:-/mnt/data/Cobblemon-fabric-1.7.3+1.21.1.jar}"
COBBLEBOSS_JAR="${COBBLEBOSS_JAR:-/mnt/data/cobbleboss-6.0.0-fabric.jar}"
RAIDDENS_JAR="${RAIDDENS_JAR:-/mnt/data/cobblemonraiddens-neoforge-0.11.4+1.21.1.jar}"
SKIES_ZIP="${SKIESGUIS_ZIP:-/mnt/data/SkiesGUIs-fabric-1.21.1-1.8.1.zip}"
for f in "$COBBLEMON_JAR" "$COBBLEBOSS_JAR" "$RAIDDENS_JAR" "$SKIES_ZIP"; do
  test -f "$f" || { echo "Missing validation input: $f" >&2; exit 1; }
done
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

echo '[1/10] Validate Cobblemon explicit-forfeit request boundary'
BS="$TMP/battle-select.txt"
javap -classpath "$COBBLEMON_JAR" -p -c com.cobblemon.mod.common.net.serverhandling.battle.BattleSelectActionsHandler > "$BS"
grep -F 'BattleActor.setActionResponses' "$BS" >/dev/null
javap -classpath "$COBBLEMON_JAR" -p -c com.cobblemon.mod.common.battles.ForfeitActionResponse | grep -F 'String forfeit' >/dev/null
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.net.messages.server.battle.BattleSelectActionsPacket | grep -F 'getShowdownActionResponses()' >/dev/null

echo '[2/10] Validate native PvE flee and disconnect behavior being overridden only for raids'
PB="$TMP/pokemonbattle.txt"; BR="$TMP/registry.txt"
javap -classpath "$COBBLEMON_JAR" -p -c com.cobblemon.mod.common.api.battles.model.PokemonBattle > "$PB"
javap -classpath "$COBBLEMON_JAR" -p -c com.cobblemon.mod.common.battles.BattleRegistry > "$BR"
grep -F 'public void checkFlee();' <(javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.api.battles.model.PokemonBattle) >/dev/null
grep -F 'checkForfeit:()Z' "$PB" >/dev/null
grep -F 'PokemonBattle.stop:()V' "$BR" >/dev/null
grep -F '@Mixin(PokemonBattle.class)' "$ROOT/src/main/java/com/cobbleraids/mixin/battle/RaidPokemonBattleMixin.java" >/dev/null
grep -F '@Inject(method = "onPlayerDisconnect"' "$ROOT/src/main/java/com/cobbleraids/mixin/battle/RaidBattleRegistryMixin.java" >/dev/null

echo '[3/10] Validate withdrawn-player UI isolation APIs'
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.battles.actor.PlayerBattleActor | grep -F 'sendUpdate(com.cobblemon.mod.common.api.net.NetworkPacket<?>)' >/dev/null
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket | grep -F 'BattleEndPacket()' >/dev/null
grep -F 'new BattleEndPacket().sendToPlayer(player);' "$ROOT/src/main/java/com/cobbleraids/lifecycle/RaidLifecycleCoordinator.java" >/dev/null
grep -F '!raid.isActiveParticipant(self.getUuid())' "$ROOT/src/main/java/com/cobbleraids/mixin/battle/RaidPlayerBattleActorMixin.java" >/dev/null

echo '[4/10] Compile/run exact combat timer boundary test'
OUT="$TMP/combatclock"; mkdir -p "$OUT"
javac -d "$OUT" "$ROOT/src/main/java/com/cobbleraids/lifecycle/RaidCombatClock.java" "$ROOT/validation/combatclocktest/RaidCombatClockTest.java"
java -cp "$OUT" RaidCombatClockTest >/dev/null

echo '[5/10] Validate configurable combat defaults + per-raid overrides'
python - "$ROOT" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1])
server=json.loads((r/'examples/server.json').read_text())
assert server['combat_defaults']=={'time_limit_seconds':900,'allow_flee':False}
raid=json.loads((r/'src/main/resources/data/cobbleraids/raids/example_garchomp.json').read_text())
assert raid['time_limit_seconds']==900 and raid['allow_flee'] is False
src=(r/'src/main/java/com/cobbleraids/config/RaidDefinition.java').read_text()
assert 'global.combatDefaults()' in src
assert 'cd.timeLimitSeconds()' in src and 'cd.allowFlee()' in src
PY

echo '[6/10] Validate server-tick timeout enforcement and no victory rewards on timeout'
grep -F 'RaidCombatRuleService.tick(server);' "$ROOT/src/main/java/com/cobbleraids/CobbleRaids.java" >/dev/null
grep -F 'RaidLifecycleCoordinator.timeout(raid, server);' "$ROOT/src/main/java/com/cobbleraids/lifecycle/RaidCombatRuleService.java" >/dev/null
grep -F 'if (raid == null || raid.getStatus() != RaidSession.Status.ACTIVE || !raid.fail()) return;' "$ROOT/src/main/java/com/cobbleraids/lifecycle/RaidLifecycleCoordinator.java" >/dev/null
# Only finalizeVictory creates the reward snapshot.
python - "$ROOT" <<'PY'
from pathlib import Path
import sys
s=(Path(sys.argv[1])/'src/main/java/com/cobbleraids/lifecycle/RaidLifecycleCoordinator.java').read_text()
assert s.count('RaidRewardEligibility.victory(raid)') == 1
assert 'public static void timeout' in s
PY

echo '[7/10] Validate allow_flee policy interception occurs before stock setActionResponses'
F="$ROOT/src/main/java/com/cobbleraids/mixin/battle/RaidBattleSelectActionsMixin.java"
grep -F 'response instanceof ForfeitActionResponse' "$F" >/dev/null
grep -F 'ci.cancel();' "$F" >/dev/null
grep -F 'if (!raid.isFleeAllowed())' "$F" >/dev/null
grep -F 'RaidLifecycleCoordinator.withdrawPlayer' "$F" >/dev/null

echo '[8/10] Validate dynamic Showdown withdrawal semantics on exact Cobblemon 1.7.3 bundle'
unzip -p "$COBBLEMON_JAR" data/cobblemon/showdown.zip > "$TMP/showdown.zip"
unzip -q "$TMP/showdown.zip" -d "$TMP/showdown"
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
assert './sim/battle-stream' in s
assert 'function startBattle(' in s
assert 'function sendBattleMessage(' in s
idx.write_text(s + ('' if s.endswith(('\n','\r')) else '\n') + hook + '\n')
PY
cp "$ROOT/validation/raid-withdrawal-test.js" "$TMP/raid-withdrawal-test.js"
node --check "$TMP/showdown/raid-patch.js"
(cd "$TMP" && node raid-withdrawal-test.js >/dev/null)

echo '[9/10] Validate dependency/mixin metadata includes reward + combat integrations'
python - "$ROOT" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1])
meta=json.loads((r/'src/main/resources/fabric.mod.json').read_text())
assert meta['depends']['cobblemon']=='1.7.3'
assert meta['depends']['skiesguis']=='1.8.1'
mix=json.loads((r/'src/main/resources/mixins/cobbleraids.mixins.json').read_text())
for x in ['battle.RaidBattleSelectActionsMixin','battle.RaidPokemonBattleMixin','battle.RaidPlayerBattleActorMixin']:
    assert x in mix['mixins']
assert 'cobbleboss' not in meta['depends'] and 'cobblemonraiddens' not in meta['depends']
PY

echo '[10/10] Re-run complete Phase 15 reward + spawn + shared-combat regressions'
COBBLEMON_JAR="$COBBLEMON_JAR" COBBLEBOSS_JAR="$COBBLEBOSS_JAR" RAIDDENS_JAR="$RAIDDENS_JAR" SKIESGUIS_ZIP="$SKIES_ZIP" bash "$ROOT/validation/validate_phase15.sh" >/dev/null

echo 'PHASE 16 VALIDATION PASSED'
