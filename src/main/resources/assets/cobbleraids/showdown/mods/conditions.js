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
  }
  // No onTryHeal here. It used to report -raidheal and return 0, which made
  // Battle#heal answer "healed nothing" to its callers: moves that test the result
  // (Synthesis, Morning Sun, Moonlight, Shore Up) then logged `-fail <boss> heal`
  // even though the pool had just been topped up, and moves carrying a `heal:`
  // property never reached the event at all because BattleActions#runMoveEffects
  // calls Pokemon#heal directly. Boss healing is now owned by raid-patch.js, which
  // overrides Battle#heal and Pokemon#heal, so the TryHeal event stays vanilla and
  // effects that legitimately cancel healing (Heal Block, Liquid Ooze) keep working.
};
module.exports = {Conditions};
