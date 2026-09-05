# Phase 19 — Mixin Package Runtime Fix

## Live-server finding
Fabric Loader rejected the CobbleRaids entrypoint because `mixins/cobbleraids.mixins.json` declared `com.cobbleraids` as the Mixin package root. Sponge Mixin reserves that whole package tree and therefore treated `com.cobbleraids.CobbleRaids` as part of a defined Mixin package.

## Correction
- Mixin root changed from `com.cobbleraids` to `com.cobbleraids.mixin`.
- The five Mixin classes were moved to:
  - `com.cobbleraids.mixin.showdown.ShowdownResourceLoaderMixin`
  - `com.cobbleraids.mixin.battle.RaidBattleRegistryMixin`
  - `com.cobbleraids.mixin.battle.RaidBattleSelectActionsMixin`
  - `com.cobbleraids.mixin.battle.RaidPokemonBattleMixin`
  - `com.cobbleraids.mixin.battle.RaidPlayerBattleActorMixin`
- Ordinary classes, including `com.cobbleraids.CobbleRaids`, remain outside the reserved Mixin package.
- No Mixin targets or raid behavior were changed.

## Validation
- `validation/validate_phase19.sh`: PASS.
- Re-ran Phase 18 functionality/regression chain under the Phase 19 build identity: PASS.
- Dedicated Mixin namespace structural checks: PASS.
- GitHub Actions Java 21 + Fabric Loom compile/remap: PASS.
- Artifact upload: PASS.
- Runtime JAR archive integrity: PASS.
- Runtime JAR contains `com/cobbleraids/CobbleRaids.class` outside the Mixin tree.
- Runtime JAR contains all five Mixin classes only beneath `com/cobbleraids/mixin/...`.
- No legacy Mixin class paths remain under `com/cobbleraids/battle` or `com/cobbleraids/showdown`.

## Remaining validation gate
A second live dedicated-server boot is required to confirm Fabric Loader now reaches the CobbleRaids entrypoint and applies the five Mixins successfully in the user's full modpack.
