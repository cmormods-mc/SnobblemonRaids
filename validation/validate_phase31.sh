#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

python3 validation/validate_phase31.py "$@"

test_output="validation/tiertest/out"
mkdir -p "$test_output"
javac -d "$test_output" \
  src/main/java/com/cobbleraids/config/RaidRarityTier.java \
  src/main/java/com/cobbleraids/config/RaidTierWeights.java \
  src/main/java/com/cobbleraids/spawn/RaidTierSelector.java \
  validation/tiertest/RaidTierSelectorTest.java
java -cp "$test_output" RaidTierSelectorTest
