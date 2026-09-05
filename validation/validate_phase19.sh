#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$ROOT/src/main/resources/mixins/cobbleraids.mixins.json"

echo '[1/5] Re-run Phase 18 functionality/regression validation under Phase 19 identity'
TMP="$ROOT/validation/.validate_phase18_phase19.tmp.sh"; trap 'rm -f "$TMP"' EXIT
sed "s/version = '0.8.2-phase18-admin'/version = '0.8.3-phase19-mixinfix'/" "$ROOT/validation/validate_phase18.sh" > "$TMP"
bash "$TMP" >/dev/null

echo '[2/5] Validate dedicated Mixin package root'
grep -F '"package": "com.cobbleraids.mixin"' "$CFG" >/dev/null
! grep -F '"package": "com.cobbleraids"' "$CFG" >/dev/null

echo '[3/5] Validate every configured Mixin class exists under dedicated namespace'
python - "$ROOT" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1])
cfg=json.loads((root/'src/main/resources/mixins/cobbleraids.mixins.json').read_text())
assert cfg['package']=='com.cobbleraids.mixin'
for rel in cfg['mixins']:
    path=root/'src/main/java'/pathlib.Path(*(cfg['package']+'.'+rel).split('.')).with_suffix('.java')
    assert path.exists(), path
    text=path.read_text()
    pkg='package '+'.'.join((cfg['package']+'.'+rel).split('.')[:-1])+';'
    assert pkg in text, (path,pkg)
PY

echo '[4/5] Validate no Mixin source remains in ordinary CobbleRaids packages'
if find "$ROOT/src/main/java/com/cobbleraids" -path '*/mixin/*' -prune -o -name '*Mixin.java' -print | grep -q .; then
  echo 'Mixin source found outside com.cobbleraids.mixin' >&2
  exit 1
fi
grep -F 'com.cobbleraids.CobbleRaids' "$ROOT/src/main/resources/fabric.mod.json" >/dev/null

echo '[5/5] Validate Phase 19 build identity'
grep -F "version = '0.8.3-phase19-mixinfix'" "$ROOT/build.gradle" >/dev/null

echo 'PHASE 19 MIXIN PACKAGE VALIDATION PASSED'
