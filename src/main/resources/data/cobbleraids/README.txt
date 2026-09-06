CobbleRaids raid definitions — Spawn Director

The core JAR contains 130 unique raid definitions under data/cobbleraids/raids.
Each definition references one or two type-based biome tags under:

  data/cobbleraids/tags/worldgen/biome/raid_types/

The core versions of those tags contain only vanilla Minecraft biomes. Optional
compatibility mods can safely extend the same tags with "replace": false.

The separate CobbleRaids Biome Compatibility JAR maps the same tags to Fabric
convention/vanilla biome-category tags, so any installed biome mod that
populates those conventions (Terralith, Oh The Biomes We've Gone, etc.) extends
the eligible biome pool automatically. CobbleRaids Core does not depend on it
and runs normally when that compatibility JAR is absent.

The spawn director selects a rarity tier before selecting a species. Default aggregate
natural odds are 70% starter, 20% powerhouse, 8% legendary, and 2% mythical.
Odds renormalize when a tier has no eligible species; spawn.weight only chooses
between eligible species inside the selected tier.

Operator diagnostics:
  /cobbleraids spawninfo
  /cobbleraids testwild <species>
