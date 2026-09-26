#!/usr/bin/env bash
#
# The whole of .github/workflows/build.yml, runnable on a developer machine.
#
# It was written because GitHub Actions was disabled account-wide, which left nothing verifying a
# push between 2026-09-10 and 2026-09-17. Actions is back, so this is now the pre-push gate rather
# than the only gate: .git/hooks/pre-push runs it, and `bash validation/ci_local.sh` runs it by
# hand. Keep running it -- it is faster than a push, and the first step below is one the workflow
# structurally cannot perform, because CI builds a clone where an ignored file does not exist.
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

step "Check git can see every source file"
# A .gitignore pattern without a leading slash matches a directory of that name at ANY depth. In
# CobbleTowers the entry for the dev server's run/ directory also matched a java package named run
# and hid it completely: the build stayed green, because Gradle compiles what is on disk, and a
# fresh clone would simply not have had the files. This repo has the same unanchored patterns, so
# it has the same trap waiting for a package named run, out or build.
#
# Scoped to src/: validation/ deliberately ignores the vendored showdown tree and node_modules.
hidden="$(git ls-files --others --ignored --exclude-standard -- src/ || true)"
if [ -n "$hidden" ]; then
  echo "ci_local: git ignores these source files, so a commit would silently leave them behind:" >&2
  echo "$hidden" | sed 's/^/  /' >&2
  echo "ci_local: check .gitignore for an unanchored pattern; anchor it with a leading slash." >&2
  exit 1
fi

step "Validate core source and resources"
bash validation/validate_phase31.sh

for n in 32 36 37 38 39 40; do
  step "Validate phase $n (sources)"
  "$py" "validation/validate_phase$n.py"
done

step "Validate logging discipline"
"$py" validation/validate_logging.py

step "Validate Showdown JS syntax"
"$py" validation/validate_showdown_js_syntax.py

step "Validate raid banned-move list against Cobblemon's real Showdown fork"
"$py" validation/validate_raid_banned_moves.py

step "Validate callback fault barriers"
"$py" validation/validate_callback_guards.py

step "Validate shop species icons"
# No cobblemon-cards jar in CI, so this checks the committed manifest against the raid roster:
# adding a boss whose species has no icon is the regression worth catching, and it is silent.
"$py" validation/sprites/build_icon_manifest.py --check

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

# After the build on purpose: these read compiled bytecode, so there is nothing to inspect until
# the classes exist. `clean` above also wipes anything a previous run left behind.
step "Validate mixin fault barriers (bytecode)"
"$py" validation/validate_mixin_guards.py

step "Validate mixin targets are not shadowed"
"$py" validation/validate_mixin_target_shadowing.py

step "Validate raid-patch.js behaviour against Cobblemon's real Showdown fork"
"$py" validation/validate_showdown_raid_patch_behavior.py

step "Validate public API boundary (bytecode)"
"$py" validation/validate_api_boundary.py

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

step "Validate public API boundary (jar)"
"$py" validation/validate_api_boundary.py "$jar"

printf '\n\033[32mci_local: all checks passed for %s\033[0m\n' "$version"
