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
unzip -q "$SKIES_ZIP" -d "$TMP"
SKIES_JAR="$TMP/SkiesGUIs-fabric-1.21.1-1.8.1.jar"

echo '[1/10] Validate SkiesGUIs 1.8.1 identity and public open API'
unzip -p "$SKIES_JAR" fabric.mod.json > "$TMP/skies-meta.json"
python - "$TMP/skies-meta.json" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]))
assert m['id']=='skiesguis'
assert m['version']=='1.8.1'
PY
javap -classpath "$SKIES_JAR" -p com.pokeskies.skiesguis.api.SkiesGUIsAPI | grep -F 'attemptGUIOpen(net.minecraft.class_3222, java.lang.String)' >/dev/null

echo '[2/10] Validate SkiesGUIs GUI id/config path/reload behavior from bytecode'
CM="$TMP/configmanager.txt"; SG="$TMP/skies.txt"
javap -classpath "$SKIES_JAR" -c -p com.pokeskies.skiesguis.config.ConfigManager > "$CM"
javap -classpath "$SKIES_JAR" -c -p com.pokeskies.skiesguis.SkiesGUIs > "$SG"
grep -F 'String guis' "$CM" >/dev/null
grep -F 'Method com/pokeskies/skiesguis/config/GuiConfig.setId' "$CM" >/dev/null
grep -F 'String skiesguis' "$SG" >/dev/null
javap -classpath "$SKIES_JAR" -p com.pokeskies.skiesguis.SkiesGUIs | grep -F 'public final void reload();' >/dev/null

echo '[3/10] Validate safe GUI adaptation (no direct item grants / public alias)'
python - "$ROOT" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1])
gui=json.loads((r/'src/main/resources/assets/cobbleraids/skiesguis/cobbleraids_reward.json').read_text())
assert 'alias_commands' not in gui
s=(r/'src/main/resources/assets/cobbleraids/skiesguis/cobbleraids_reward.json').read_text()
assert 'GIVE_ITEM' not in s
for choice in ('candy','balls','gamble'):
    assert f'cobbleraids reward claim {choice}' in s
PY

echo '[4/10] Validate reward JSON choices + contribution tiers'
python - "$ROOT" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1])
raid=json.loads((r/'src/main/resources/data/cobbleraids/raids/example_garchomp.json').read_text())
rw=raid['rewards']
assert rw['gui_id']=='cobbleraids_reward'
assert set(rw['choices'])=={'candy','balls','gamble'}
assert rw['choices']['candy']['items'][0]=={'item':'cobblemon:rare_candy','amount':5}
assert rw['choices']['balls']['items'][0]=={'item':'cobblemon:ultra_ball','amount':8}
assert rw['choices']['gamble']['chance_items'][0]['chance']==0.05
tiers=rw['contribution_bonus']['tiers']
assert [t['min_percentage'] for t in tiers]==[20.0,35.0,50.0]
assert [t['bonus_rolls'] for t in tiers]==[1,2,3]
assert rw['contribution_bonus']['pool']
PY

echo '[5/10] Compile/run contribution normalization + tier selection test'
OUT="$TMP/rewardtest"; mkdir -p "$OUT"
javac -d "$OUT" "$ROOT/src/main/java/com/cobbleraids/reward/ContributionMath.java" "$ROOT/validation/rewardtest/ContributionMathTest.java"
java -cp "$OUT" ContributionMathTest >/dev/null

echo '[6/10] Validate server-authoritative single-use claim boundary'
R="$ROOT/src/main/java/com/cobbleraids/lifecycle/RaidRewardService.java"
grep -F 'queue.removeFirst();' "$R" >/dev/null
grep -F 'RaidRewardGrantEngine.grantChoice' "$R" >/dev/null
grep -F 'SkiesGUIsAPI.INSTANCE.attemptGUIOpen' "$R" >/dev/null
# Skies is presentation only: the bundled GUI can only send a choice id.
grep -F 'COMMAND_PLAYER' "$ROOT/src/main/resources/assets/cobbleraids/skiesguis/cobbleraids_reward.json" >/dev/null

echo '[7/10] Validate active-winner eligibility (fled players excluded)'
grep -F 'raid.getActiveParticipants()' "$ROOT/src/main/java/com/cobbleraids/lifecycle/RaidRewardEligibility.java" >/dev/null
grep -F 'raid.getActiveParticipants().stream()' "$ROOT/src/main/java/com/cobbleraids/lifecycle/RaidLifecycleCoordinator.java" >/dev/null

echo '[8/10] Validate required SkiesGUIs dependency and no reference-mod dependency'
python - "$ROOT" <<'PY'
import json,pathlib,sys
r=pathlib.Path(sys.argv[1])
m=json.loads((r/'src/main/resources/fabric.mod.json').read_text())
assert m['depends']['cobblemon']=='1.7.3'
assert m['depends']['skiesguis']=='1.8.1'
assert 'cobbleboss' not in m['depends'] and 'cobblemonraiddens' not in m['depends']
PY
if grep -R -n -E 'import com\.cobbleboss|import com\.necro\.raid' "$ROOT/src"; then exit 1; fi

echo '[9/10] Validate example reward item assets exist in supplied Cobblemon 1.7.3'
for item in rare_candy ultra_ball great_ball master_ball exp_candy_l; do
  jar tf "$COBBLEMON_JAR" | grep -q "assets/cobblemon/models/item/${item}.json" || { echo "Missing Cobblemon item asset ${item}" >&2; exit 1; }
done

echo '[10/10] Re-run Phase 14 natural-spawn + complete shared-combat regressions'
COBBLEMON_JAR="$COBBLEMON_JAR" COBBLEBOSS_JAR="$COBBLEBOSS_JAR" RAIDDENS_JAR="$RAIDDENS_JAR" bash "$ROOT/validation/validate_phase14.sh" >/dev/null

echo 'PHASE 15 VALIDATION PASSED'
