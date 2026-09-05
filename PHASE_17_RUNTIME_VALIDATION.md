# Phase 17 — Live Runtime Compatibility Correction

## Trigger

The first live dedicated-server boot with `cobbleraids 0.8.0-phase16-ci` reached Cobblemon's Showdown thread but failed in CobbleRaids' fail-fast `index.js` bootstrap patch:

`IllegalStateException: Cobblemon Showdown index.js bootstrap signature changed; refusing to patch blindly`

The same server log showed Cobblemon 1.7.3, Fabric API 0.116.14+1.21.1, SkiesGUIs 1.8.1, and Mega Showdown 1.9.5 loaded alongside CobbleRaids.

## Root-cause correction

The 1.7.3 bundled `index.js` contains the original exact `const BS = require('./sim/battle-stream');` signature, so the reference input itself was not wrong. A live modpack may, however, reuse or edit the extracted Showdown `index.js` (including through other Showdown-integrating addons). The Phase 16 hook was unnecessarily coupled to one exact import line.

Phase 17 changes the bootstrap installation to:

1. Remain idempotent if `require('./raid-patch');` is already present.
2. Validate that the target still contains the Cobblemon Graal bootstrap structure:
   - `./sim/battle-stream`
   - `function startBattle(`
   - `function sendBattleMessage(`
3. Append `require('./raid-patch');` at EOF instead of rewriting one exact import statement.
4. Continue to fail closed if the structural checks fail.

This preserves the no-blind-patching rule while allowing harmless formatting/import edits by other addons.

## Validation

`validation/validate_phase17.sh` passes five gates:

- exact Cobblemon 1.7.3 index structure
- append-only/idempotent/fail-closed bootstrap semantics
- exact bundled Showdown runtime bootstrap regression
- complete Phase 16 validation suite
- compiler-discovered Loom/settings/MutableComponent corrections baked into source

Result: `PHASE 17 RUNTIME-COMPAT VALIDATION PASSED`

## Build settings folded into source

- Fabric Loom: `1.13-SNAPSHOT`
- repository mode: `PREFER_PROJECT`
- `BattleType#getDisplayName()`: `MutableComponent`
- version: `0.8.1-phase17-runtime`

## Remaining gate

A fresh dedicated-server boot with the Phase 17 CI JAR is required. The target result is the Cobblemon Showdown service starting without the previous CobbleRaids bootstrap exception, followed by actual raid spawn/recruitment/battle testing.
