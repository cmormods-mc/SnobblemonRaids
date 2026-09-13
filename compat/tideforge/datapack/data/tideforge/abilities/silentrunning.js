{
  name: "Silent Running",

  /* Enters battle Submerged. Status moves keep it; the first damaging hit it
     takes, or the first damaging move it uses, surfaces it.

     Block comments, not line comments: Cobblemon flattens this file to a single
     line before handing it to Showdown, so a // comment would swallow the rest
     of the script. That is a server-boot crash, not a warning. */
  onStart(pokemon) {
    pokemon.tideforgeSubmerged = true;
    this.add("-message", pokemon.name + " slipped beneath the surface!");
  },

  /* Switching out resets the ability; onStart submerges it again on the way back in. */
  onSwitchOut(pokemon) {
    pokemon.tideforgeSubmerged = false;
  },
  onEnd(pokemon) {
    pokemon.tideforgeSubmerged = false;
  },

  /* Absorb the hit: the first damaging hit taken while Submerged lands 25%
     lighter. The handler lives on the defender, the same way Multiscale's does. */
  onSourceModifyDamage(damage, source, target, move) {
    if (target.tideforgeSubmerged) {
      this.debug("Silent Running weaken");
      return this.chainModify(0.75);
    }
  },
  onDamagingHit(damage, target, source, move) {
    if (!target.tideforgeSubmerged) return;
    target.tideforgeSubmerged = false;
    this.add("-message", target.name + " was forced to the surface!");
  },

  /* Or launch the torpedo: strike first and the Water move hits 30% harder.
     Surfacing happens here rather than in a later hook so the boost below is
     still applied to the very move that spends it. 5325/4096 is 1.3x, the
     ratio Sheer Force uses. */
  onBasePower(basePower, attacker, defender, move) {
    if (!attacker.tideforgeSubmerged) return;
    var torpedo = move.type === "Water";
    attacker.tideforgeSubmerged = false;
    this.add("-message", attacker.name + " broke the surface!");
    if (torpedo) return this.chainModify([5325, 4096]);
  },

  /* Fixed-damage moves (Seismic Toss, Night Shade) never reach the base power
     chain, so they surface it here instead. */
  onModifyMove(move, pokemon) {
    if (pokemon.tideforgeSubmerged && move.category !== "Status" && !move.basePower) {
      pokemon.tideforgeSubmerged = false;
      this.add("-message", pokemon.name + " broke the surface!");
    }
  },

  flags: { breakable: 1 },
  rating: 3,
  num: -7301
}
