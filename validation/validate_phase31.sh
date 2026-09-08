#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

# Tier-selection logic is covered by src/test (RaidTierSelectorTest), which the Gradle build runs.
# The standalone javac harness that used to run here was deleted: it had drifted out of step with
# normalizedPercentages gaining its tier_spawn_chance argument and was failing every CI run.
python3 validation/validate_phase31.py "$@"
