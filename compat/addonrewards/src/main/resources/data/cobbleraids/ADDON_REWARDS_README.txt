CobbleRaids Add-on Rewards
==========================

Optional, data only. Adds loot tables that hand out items from common Cobblemon add-ons, so a raid
can reward them without every definition restating item ids.

Reference them from a raid definition's rewards block:

    "rewards": {
      "loot_tables": ["cobbleraids:tier/legendary"],
      "choices": {
        "charms": { "loot_tables": ["cobbleraids:addons/charms"] },
        "training": { "loot_tables": ["cobbleraids:addons/training_caps"] }
      }
    }

Preview any of them in game, without granting anything:

    /cobbleraids debug loot cobbleraids:tier/legendary

Tables
------
tier/starter, tier/powerhouse, tier/legendary, tier/mythical
    Ready-made bundles weighted for each rarity. They draw from the per-add-on tables below, so a
    pack missing an add-on simply loses that slice rather than the whole bundle.

addons/charms          cobblemoncharms   type charms, catch/exp charm, gold bottle cap, shiny charm
addons/vitamins        cobblemoncharms   super vitamins and stat candies
addons/training_caps   cobblemon_utility silver, obsidian, golden and void bottle caps
addons/utility         cobblemon_utility candies, poketreat, ball synchroniser, devolution relic
addons/tms             simpletms         one random TM out of 632
addons/cards           cobblemon-cards   booster packs, by type and by generation
addons/bonds           companion_bonds   friendship bracelet, contest journal, shiny leaf/crown
addons/riding          ridetraining      stamina berries, training reader, riding upgrade
addons/breeding        daycareplus       incubators, daycare sparks, fertility candy, shiny booster
addons/capsules        cobblecapsule     corelite, capsule cores, pokemon capsules
addons/safari          cobblesafari      bait, balm, auspicious and union room balls

Missing add-ons
---------------
Each add-on has its own table on purpose. A table naming an item from a mod that is not installed
fails to load and is skipped by the game, which costs you that one table and nothing else; the tier
bundles keep working with whatever remains. Nothing here is a hard dependency.
