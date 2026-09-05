#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CMD="$ROOT/src/main/java/com/cobbleraids/command/RaidAdminCommand.java"
SPAWN="$ROOT/src/main/java/com/cobbleraids/command/RaidAdminSpawnOps.java"
BOSS="$ROOT/src/main/java/com/cobbleraids/command/RaidAdminBossOps.java"
DEBUG="$ROOT/src/main/java/com/cobbleraids/command/RaidAdminDebugOps.java"

printf '[1/7] Re-run Phase 17 runtime/bootstrap + Phase 16 combat/reward regressions\n'
bash "$ROOT/validation/validate_phase17.sh" >/dev/null

printf '[2/7] Validate admin command registration and permission gate\n'
grep -F 'RaidAdminCommand.register();' "$ROOT/src/main/java/com/cobbleraids/CobbleRaids.java" >/dev/null
grep -F 'ADMIN_PERMISSION_LEVEL = 2' "$CMD" >/dev/null
grep -F '.requires(source -> source.hasPermission(ADMIN_PERMISSION_LEVEL))' "$CMD" >/dev/null

printf '[3/7] Validate requested command surface\n'
for literal in '"list"' '"spawn"' '"despawn"' '"all"' '"debug"' '"status"' '"raids"'; do
  grep -F "Commands.literal($literal)" "$CMD" >/dev/null
done
grep -F 'Commands.argument("raid_id", StringArgumentType.word())' "$CMD" >/dev/null
grep -F 'Commands.argument("pos", Vec3Argument.vec3())' "$CMD" >/dev/null

printf '[4/7] Validate force spawn uses existing physical Cobblemon boss path\n'
grep -F 'RaidBossSpawner.spawnAt(source.getLevel(), position, definition)' "$SPAWN" >/dev/null
grep -F 'RaidDefinitionRegistry.get(id)' "$SPAWN" >/dev/null
! grep -F 'markNatural(boss)' "$SPAWN" >/dev/null

printf '[5/7] Validate administrative despawn respects raid lifecycle\n'
grep -F 'RaidLifecycleCoordinator.abort(session);' "$BOSS" >/dev/null
grep -F 'RaidLobbyManager.cancelForBoss(boss);' "$BOSS" >/dev/null
grep -F 'public static boolean cancelForBoss(PokemonEntity boss)' "$ROOT/src/main/java/com/cobbleraids/lobby/RaidLobbyManager.java" >/dev/null

printf '[6/7] Validate debug exposes live canonical raid state\n'
grep -F 'session.getCurrentHealth()' "$DEBUG" >/dev/null
grep -F 'session.getMaxHealth()' "$DEBUG" >/dev/null
grep -F 'session.getActiveParticipants()' "$DEBUG" >/dev/null
grep -F 'session.getRemainingCombatTicks()' "$DEBUG" >/dev/null
grep -F 'ContributionMath.percentages' "$DEBUG" >/dev/null
grep -F 'RaidSpawnScheduler.activeCount()' "$DEBUG" >/dev/null

printf '[7/7] Validate Phase 18 build identity and standalone dependency boundary\n'
grep -F "version = '0.8.2-phase18-admin'" "$ROOT/build.gradle" >/dev/null
! grep -R -E 'com\.cobbleboss|raiddens' "$ROOT/src/main/java" "$ROOT/src/main/resources/fabric.mod.json" >/dev/null

echo 'PHASE 18 ADMIN COMMAND VALIDATION PASSED'
