# Phase 15 Validation — SkiesGUIs Rewards + Contribution Bonuses

Target: Minecraft 1.21.1/Fabric, Cobblemon 1.7.3, SkiesGUIs 1.8.1.
Reference-only: supplied CobbleBoss 6.0.0 and Raid Dens 0.11.4.

## What was validated

1. The supplied SkiesGUIs ZIP contains mod id `skiesguis`, version `1.8.1`.
2. `SkiesGUIsAPI.INSTANCE.attemptGUIOpen(ServerPlayer, String)` is a real public API.
3. SkiesGUIs loads GUI JSONs from `config/skiesguis/guis`, and the GUI id is the filename without `.json`.
4. `SkiesGUIs.reload()` exists and is used only to load the installed default CobbleRaids GUI when necessary.
5. The adapted reward GUI contains no public alias command and no `GIVE_ITEM` action. Buttons submit only a choice id to CobbleRaids.
6. CobbleRaids consumes the pending claim token before granting the chosen reward, preventing repeated GUI clicks/commands from duplicating a claim.
7. Contribution percentages are normalized from eligible winner damage share. A pure Java test validates 500/300/200 damage -> 50%/30%/20% and highest matching bonus tier selection.
8. Fled/ineligible players are excluded from both the forced raid winner list and the reward eligibility snapshot.
9. The example reward item ids have matching item model assets in the supplied Cobblemon 1.7.3 JAR.
10. The complete Phase 14 validator (including Phase 13 shared-battle regressions) still passes.

## Security correction from the supplied GUI

The uploaded reward GUI directly used `GIVE_ITEM` actions and exposed alias command `bossreward`. That is safe only if another system prevents arbitrary/repeated openings. CobbleRaids instead preserves the three-choice layout while making SkiesGUIs presentation-only:

```text
button click -> /cobbleraids reward claim <choice>
             -> pending claim lookup
             -> choice validation
             -> claim token consumed once
             -> server-side item grant
             -> contribution bonus rolls
```

A closed but unclaimed GUI can be reopened with `/cobbleraids reward`; that command does not create a claim.

## Contribution definition

For reward purposes, contribution percentage is:

```text
player applied raid damage / total applied raid damage by reward-eligible winners * 100
```

This intentionally normalizes to 100% even when boss healing causes aggregate damage to exceed the boss's nominal max HP. Fled players are removed from the eligible set.

The highest configured threshold at or below the player's percentage supplies the number of weighted bonus rolls.

## Remaining runtime gate

The API/bytecode/config path and pure reward logic are validated, but a full Loom build and live Fabric server test with SkiesGUIs opening a chest GUI after a real raid still remain required before calling this production-ready.
