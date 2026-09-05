# CobbleRaids Phase 18 — Admin/Test Commands

## Added commands

All commands below except the existing `reward` branch require Minecraft permission level 2.

- `/cobbleraids list`
- `/cobbleraids spawn <raid_id>`
- `/cobbleraids spawn <raid_id> <x> <y> <z>` (Minecraft coordinate syntax, including relative coordinates)
- `/cobbleraids despawn`
- `/cobbleraids despawn all`
- `/cobbleraids debug status`
- `/cobbleraids debug raids`

Unqualified raid IDs such as `example_garchomp` resolve to the `cobbleraids` namespace.

## Safety behavior

- Forced spawns use the existing `RaidBossSpawner.spawnAt` path and create the same real Cobblemon `PokemonEntity` raid boss used by natural spawning.
- Forced spawns are not marked as natural scheduler spawns.
- Administrative despawn of an active battle calls `RaidLifecycleCoordinator.abort` so Cobblemon battle cleanup is allowed to complete normally.
- Administrative despawn of a recruiting boss cancels/removes its lobby before discarding the entity.
- Debug output reads canonical RaidSession HP, active/total participants, combat timer, flee policy, and normalized contribution percentages.

## Validation

`validation/validate_phase18.sh` passed all 7 gates and reran the entire Phase 17 -> Phase 16 regression chain.

A real Java 21 / Fabric Loom 1.13 build was then run in GitHub Actions against Minecraft 1.21.1, Cobblemon 1.7.3, Fabric API, and SkiesGUIs 1.8.1. `Compile and remap` and artifact upload both succeeded.

This validates compilation/remapping. A second live-server boot and command execution test remains the next runtime gate.
