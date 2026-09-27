"use strict";
const Items = {};

// Guardian Scale: like Focus Sash, but usable from any HP (not only full) and leaves 20% of max HP
// instead of exactly 1 -- a safety net rather than a last stand. See RaidHeldItems.java for the Java
// side (the HeldItemEffectComponent that routes a held cobbleraids:guardian_scale ItemStack to this
// exact key) and its GUARDIAN_SCALE_SHOWDOWN_ID constant, which this key must match exactly.
Items.cobbleraidsguardianscale = {
  name: "Guardian Scale",
  spritenum: 0,
  fling: {
    basePower: 30
  },
  onDamagePriority: -40,
  onDamage(damage, target, source, effect) {
    if (!effect || effect.effectType !== "Move") return;
    const surviveAt = Math.max(1, Math.floor(target.maxhp / 5));
    if (target.hp > surviveAt && damage >= target.hp) {
      if (target.useItem()) {
        return target.hp - surviveAt;
      }
    }
  },
  num: 9002,
  gen: 9
};

module.exports = {Items};
