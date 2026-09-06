CobbleRaids Biome Compatibility 1.0.0

Drop this JAR in the server mods folder alongside CobbleRaids.
It has no hard dependency on Terralith or Oh The Biomes We've Gone.
It extends CobbleRaids' 18 type-based biome tags with Fabric conventional
and vanilla category tags (e.g. #c:is_hot/overworld, #minecraft:is_forest)
instead of hardcoding individual biome IDs from one specific mod.

Terralith 2.6.2 and BWG 2.5.5 already tag their biomes with those
conventions, so both work automatically -- as will any other biome mod
that follows the same Fabric biome tag conventions.

This add-on contains no boss definitions and therefore cannot duplicate
the boss pool. Removing this JAR safely returns every boss to its
vanilla biome rules.
