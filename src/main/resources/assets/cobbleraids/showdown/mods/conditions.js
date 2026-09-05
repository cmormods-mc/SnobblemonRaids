"use strict";
const Conditions = {};
Conditions.raidboss = {
  name: 'raidboss',
  onDamage(damage, target, source, effect) {
    if (target?.side?.battle?.gameType !== 'raid') return damage;
    // Showdown still emits its ordinary -damage line when a Damage event returns 0.
    // Because the simulator intentionally keeps the boss at full HP, that follow-up
    // line says 100/100 and overwrites the authoritative shared raid percentage.
    // raid-patch.js consumes exactly that one redundant boss-health instruction.
    target.__cobbleRaidsSuppressVanillaDamageLog = true;
    if (source) this.add('-raiddamage', target, damage, source);
    else this.add('-raiddamage', target, damage);
    return 0;
  },
  onTryHeal(damage, target, source, effect) {
    if (target?.side?.battle?.gameType !== 'raid') return damage;
    if (damage) this.add('-raidheal', target, damage);
    return 0;
  }
};
module.exports = {Conditions};
