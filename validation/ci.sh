#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE=(gradle)
if [[ -x ./gradlew ]]; then GRADLE=(./gradlew); fi

pass() { printf '[PASS] %s\n' "$1"; }
run() {
  local label="$1"; shift
  printf '\n==> %s\n' "$label"
  "$@"
  pass "$label"
}

run "Core source and resources" bash validation/validate_phase31.sh
run "Raid boss lifecycle and healing integrity" python3 validation/validate_phase32.py
run "Reward visibility and SkiesGUIs optionality" python3 validation/validate_phase36.py
run "Native reward reveal screen" python3 validation/validate_phase37.py
run "Cinematic reveal" python3 validation/validate_phase38.py
run "Textured reward panel" python3 validation/validate_phase39.py
run "Real texture art" python3 validation/validate_phase40.py
run "Logging discipline" python3 validation/validate_logging.py
run "Callback fault barriers" python3 validation/validate_callback_guards.py
run "Tower integration API boundary" python3 validation/validate_tower_integration_api.py
run "External encounter lifecycle isolation" python3 validation/validate_external_encounter_lifecycle.py
run "Reward economy manifest" python3 validation/economy/validate_economy_manifest.py
run "Generated reward tables" python3 validation/economy/build_tables.py --check
run "Exact reward probabilities" python3 validation/economy/validate_economy_probabilities.py
run "Shop test catalogue" python3 validation/shop/build_test_catalog.py --check
run "Clean compile, tests, and remap" "${GRADLE[@]}" --no-daemon clean build --stacktrace --warning-mode all
run "Compiled mixin fault barriers" python3 validation/validate_mixin_guards.py

version=$(sed -n "s/^version = '\(.*\)'$/\1/p" build.gradle)
jar="build/libs/CobbleRaids-${version}.jar"
test -f "$jar" || { echo "[FAIL] Missing runtime JAR: $jar" >&2; exit 1; }

run "Runtime JAR core validation" python3 validation/validate_phase31.py "$jar"
run "Runtime JAR lifecycle validation" python3 validation/validate_phase32.py "$jar"
run "Runtime JAR reward visibility" python3 validation/validate_phase36.py "$jar"
run "Runtime JAR native reveal" python3 validation/validate_phase37.py "$jar"
run "Runtime JAR cinematic reveal" python3 validation/validate_phase38.py "$jar"
run "Runtime JAR textured panel" python3 validation/validate_phase39.py "$jar"
run "Runtime JAR real art" python3 validation/validate_phase40.py "$jar"
run "Runtime JAR mixin guards" python3 validation/validate_mixin_guards.py "$jar"

printf '\nCOBBLERAIDS VALIDATION: PASSED\n'
printf 'Runtime JAR: %s\n' "$jar"
