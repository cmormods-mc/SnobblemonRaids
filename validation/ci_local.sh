#!/usr/bin/env bash
#
# The whole of .github/workflows/build.yml, runnable on a developer machine.
#
# It exists because GitHub Actions is disabled account-wide (see the repo README / session notes),
# so nothing has verified a push since 2026-09-10. Until Actions comes back, this script IS the
# gate: .git/hooks/pre-push runs it, and `bash validation/ci_local.sh` runs it by hand.
#
# Keep it in step with build.yml. If a step is added there and not here, the hook stops being a
# faithful stand-in and starts being a false green.
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

# Git Bash on Windows ships `python`; Linux/macOS ship `python3`. CI has python3.
if command -v python3 >/dev/null 2>&1; then
  py=python3
elif command -v python >/dev/null 2>&1; then
  py=python
else
  echo "ci_local: no python interpreter on PATH" >&2
  exit 1
fi

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

step "Validate core source and resources"
bash validation/validate_phase31.sh

for n in 32 36 37 38 39 40; do
  step "Validate phase $n (sources)"
  "$py" "validation/validate_phase$n.py"
done

step "Validate logging discipline"
"$py" validation/validate_logging.py

step "Validate callback fault barriers"
"$py" validation/validate_callback_guards.py

step "Validate reward-economy manifest"
"$py" validation/economy/validate_economy_manifest.py

step "Validate generated reward tables"
"$py" validation/economy/build_tables.py --check

step "Validate exact reward probabilities"
"$py" validation/economy/validate_economy_probabilities.py

step "Validate the shop test catalogue"
"$py" validation/shop/build_test_catalog.py --check

step "Compile, test and remap"
./gradlew clean build --stacktrace --warning-mode all

# After the build on purpose: this reads compiled bytecode, so there is nothing to inspect until
# the classes exist. `clean` above also wipes anything a previous run left behind.
step "Validate mixin fault barriers (bytecode)"
"$py" validation/validate_mixin_guards.py

# Derived from build.gradle so a version bump cannot silently skip JAR validation.
version="$(sed -n "s/^version = '\(.*\)'$/\1/p" build.gradle)"
jar="build/libs/CobbleRaids-${version}.jar"
if [ ! -f "$jar" ]; then
  echo "ci_local: missing $jar" >&2
  ls -la build/libs >&2 || true
  exit 1
fi

for n in 31 32 36 37 38 39 40; do
  step "Validate phase $n (jar)"
  "$py" "validation/validate_phase$n.py" "$jar"
done

step "Validate mixin fault barriers (jar)"
"$py" validation/validate_mixin_guards.py "$jar"

printf '\n\033[32mci_local: all checks passed for %s\033[0m\n' "$version"
