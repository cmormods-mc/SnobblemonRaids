#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COBBLEMON_JAR="${COBBLEMON_JAR:-/mnt/data/Cobblemon-fabric-1.7.3+1.21.1.jar}"
COBBLEBOSS_JAR="${COBBLEBOSS_JAR:-/mnt/data/cobbleboss-6.0.0-fabric.jar}"
RAIDDENS_JAR="${RAIDDENS_JAR:-/mnt/data/cobblemonraiddens-neoforge-0.11.4+1.21.1.jar}"
for f in "$COBBLEMON_JAR" "$COBBLEBOSS_JAR" "$RAIDDENS_JAR"; do
  test -f "$f" || { echo "Missing reference JAR: $f" >&2; exit 1; }
done

echo '[1/11] Validate Cobblemon physical spawn/entity APIs'
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.pokemon.Pokemon | grep -F 'sendOut(' >/dev/null
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.entity.pokemon.PokemonEntity | grep -F 'setCountsTowardsSpawnCap(boolean)' >/dev/null
javap -classpath "$COBBLEMON_JAR" -p com.cobblemon.mod.common.entity.pokemon.PokemonEntity | grep -F 'isBattling()' >/dev/null

echo '[2/11] Validate CobbleBoss reference scheduler/location pattern'
CB="$(mktemp)"
javap -classpath "$COBBLEBOSS_JAR:$COBBLEMON_JAR" -p -c com.cobbleboss.spawn.PokemonBossSpawnSystem > "$CB"
grep -F 'executeGlobalSpawnCheck' "$CB" >/dev/null
grep -F 'selectBossByWeight' "$CB" >/dev/null
grep -F 'getAvailableBossesForBiomeAndTime' "$CB" >/dev/null
grep -F 'SpawnLocationFinder.findSpawnPosition' "$CB" >/dev/null
rm -f "$CB"

echo '[3/11] Validate reference time buckets exactly'
TU="$(mktemp)"
javap -classpath "$COBBLEBOSS_JAR" -p -c com.cobbleboss.util.spawn.TimeUtils > "$TU"
for v in 3000 6000 12000 15000 18000 21000 24000; do grep -F "long ${v}l" "$TU" >/dev/null; done
rm -f "$TU"

echo '[4/11] Validate operator config + raid spawn schema'
python "$ROOT/validation/spawn-config-test.py" "$ROOT"

echo '[5/11] Validate config is external and reloadable'
grep -F 'FabricLoader.getInstance().getConfigDir()' "$ROOT/src/main/java/com/cobbleraids/config/CobbleRaidsConfigManager.java" >/dev/null
grep -F 'resolve("cobbleraids").resolve("server.json")' "$ROOT/src/main/java/com/cobbleraids/config/CobbleRaidsConfigManager.java" >/dev/null
grep -F 'START_DATA_PACK_RELOAD.register' "$ROOT/src/main/java/com/cobbleraids/CobbleRaids.java" >/dev/null

echo '[6/11] Validate raid datapack resource path matches data/cobbleraids/raids/*.json'
grep -F 'listResources("raids"' "$ROOT/src/main/java/com/cobbleraids/config/RaidDefinitionRegistry.java" >/dev/null
grep -F 'path.getNamespace().equals("cobbleraids")' "$ROOT/src/main/java/com/cobbleraids/config/RaidDefinitionRegistry.java" >/dev/null
test -f "$ROOT/src/main/resources/data/cobbleraids/raids/example_garchomp.json"

echo '[7/11] Validate scheduler safety/cap/cooldown/despawn wiring'
S="$ROOT/src/main/java/com/cobbleraids/spawn/RaidSpawnScheduler.java"
for needle in 'maxActiveRaids()' 'maxActiveRaidsPerDimension()' 'minDistanceBetweenRaids()' 'cooldownSeconds()' 'despawnSeconds()' 'hasActiveLobby(boss)' 'boss.isBattling()'; do
  grep -F "$needle" "$S" >/dev/null
done
grep -F 'SERVER_STARTED.register(RaidSpawnScheduler::onServerStarted)' "$ROOT/src/main/java/com/cobbleraids/CobbleRaids.java" >/dev/null
grep -F 'SERVER_STOPPING.register(RaidSpawnScheduler::onServerStopping)' "$ROOT/src/main/java/com/cobbleraids/CobbleRaids.java" >/dev/null

echo '[8/11] Validate loaded-chunk surface finder ordering'
python - "$ROOT" <<'PY'
from pathlib import Path
import sys
s=(Path(sys.argv[1])/'src/main/java/com/cobbleraids/spawn/RaidSpawnPositionFinder.java').read_text()
assert s.index('level.hasChunkAt(probe)') < s.index('level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES')
assert 'getWorldBorder().isWithinBounds(candidate)' in s
assert 'getFluidState().isEmpty()' in s
PY

echo '[9/11] Validate natural spawn is opt-in and standalone'
grep -F 'boolean spawnEnabled = spawnObject.has("enabled") && spawnObject.get("enabled").getAsBoolean();' "$ROOT/src/main/java/com/cobbleraids/config/RaidDefinition.java" >/dev/null
if grep -R -n -E 'import com\.cobbleboss|import com\.necro\.raid' "$ROOT/src"; then
  echo 'Reference-mod runtime import found' >&2
  exit 1
fi

echo '[10/11] Re-run Phase 13 shared-combat/lifecycle regressions'
COBBLEMON_JAR="$COBBLEMON_JAR" COBBLEBOSS_JAR="$COBBLEBOSS_JAR" RAIDDENS_JAR="$RAIDDENS_JAR" bash "$ROOT/validation/validate_phase13.sh" >/dev/null

echo '[11/11] Validate metadata remains Cobblemon-only runtime dependency'
python - "$ROOT" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1])
meta=json.loads((root/'src/main/resources/fabric.mod.json').read_text())
assert meta['depends']['cobblemon']=='1.7.3'
assert 'cobbleboss' not in meta.get('depends', {})
assert 'cobblemonraiddens' not in meta.get('depends', {})
PY

echo 'PHASE 14 VALIDATION PASSED'
