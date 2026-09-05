'use strict';

const {Battle} = require('./sim/battle');
const {Side} = require('./sim/side');
const {Pokemon} = require('./sim/pokemon');

/**
 * Dynamic cooperative raid topology.
 *
 * The Java bridge supplies format.playerCount = joinedPlayers + 1.
 * The LAST Showdown side is always the raid boss; every earlier side is a player.
 * This keeps 1..N recruitment independent from fixed p1-p5 assumptions.
 */
const isRaid = value => {
  const battle = value?.battle || value;
  return !!battle && battle.gameType === 'raid';
};
const bossIndex = battle => battle.sides.length - 1;
const bossSide = battle => battle.sides[bossIndex(battle)] || null;
const isBossSide = side => isRaid(side?.battle) && side.n === bossIndex(side.battle);
const isPlayerSide = side => isRaid(side?.battle) && side.n >= 0 && side.n < bossIndex(side.battle);
const isEliminatedPlayerSide = side => isPlayerSide(side) && (
  side.pokemonLeft <= 0 ||
  (side.pokemon.length > 0 && side.pokemon.every(pokemon => !pokemon || pokemon.fainted || pokemon.hp <= 0))
);
const isActivePlayerSide = side => isPlayerSide(side) && !side.raidWithdrawn && !isEliminatedPlayerSide(side);

// raidboss.onDamage must return numeric 0 so Showdown keeps the hit successful and
// continues secondary effects and turn resolution. Showdown nevertheless follows a
// zero-damage result with a vanilla `-damage ... 100/100` instruction. Suppress only
// that marked boss instruction; the preceding `-raiddamage` remains authoritative.
const oldAdd = Battle.prototype.add;
Battle.prototype.add = function(...parts) {
  const target = parts[1];
  if (
    isRaid(this) &&
    parts[0] === '-damage' &&
    isBossSide(target?.side) &&
    target.__cobbleRaidsSuppressVanillaDamageLog
  ) {
    delete target.__cobbleRaidsSuppressVanillaDamageLog;
    return;
  }
  return oldAdd.apply(this, parts);
};

function refreshOpponentAnchors(battle) {
  if (!isRaid(battle)) return;
  const boss = bossSide(battle);
  const players = battle.sides.filter(isPlayerSide);
  const activePlayers = players.filter(isActivePlayerSide);
  for (const player of players) {
    player.foe = boss;
    player.allySide = null;
  }
  if (boss) {
    // A few upstream mechanics still dereference side.foe directly instead of calling foes().
    // Keep a live player anchor while the raid-specific foes() override supplies the full group.
    boss.foe = activePlayers[0] || players[0] || null;
    boss.allySide = null;
  }
}

function passivatePlayerSide(side) {
  const request = {wait: true, side: side.getRequestData()};
  if (!side.activeRequest?.wait) side.emitRequest(request);
  else side.activeRequest = request;
  side.clearChoice();
}

function raidNormalizeSide(side) {
  if (isRaid(side)) side.id = `p${side.n + 1}`;
  return side;
}

const oldAllies = Side.prototype.allies;
Side.prototype.allies = function(all) {
  if (!isRaid(this)) return oldAllies.call(this, all);
  if (isBossSide(this)) return this.active.filter(p => p && (all || p.hp));
  const result = [];
  for (const side of this.battle.sides) {
    if (!isActivePlayerSide(side)) continue;
    for (const p of side.active) if (p) result.push(p);
  }
  return all ? result : result.filter(p => p.hp);
};

const oldFoes = Side.prototype.foes;
Side.prototype.foes = function(all) {
  if (!isRaid(this)) return oldFoes.call(this, all);
  if (isBossSide(this)) {
    const result = [];
    for (const side of this.battle.sides) {
      if (!isActivePlayerSide(side)) continue;
      for (const p of side.active) if (p) result.push(p);
    }
    return all ? result : result.filter(p => p.hp);
  }
  const boss = bossSide(this.battle);
  return boss ? boss.active.filter(p => p && (all || p.hp)) : [];
};

const oldHasAlly = Side.prototype.hasAlly;
Side.prototype.hasAlly = function(pokemon) {
  if (!isRaid(this)) return oldHasAlly.call(this, pokemon);
  if (!pokemon) return false;
  if (isBossSide(this)) return isBossSide(pokemon.side);
  return isActivePlayerSide(pokemon.side);
};

const oldFoePokemonLeft = Side.prototype.foePokemonLeft;
Side.prototype.foePokemonLeft = function() {
  if (!isRaid(this)) return oldFoePokemonLeft.call(this);
  if (isBossSide(this)) {
    return this.battle.sides.slice(0, bossIndex(this.battle)).reduce((n, side) => n + (isActivePlayerSide(side) ? side.pokemonLeft : 0), 0);
  }
  return bossSide(this.battle)?.pokemonLeft || 0;
};

const oldActiveTeam = Side.prototype.activeTeam;
Side.prototype.activeTeam = function() {
  if (!isRaid(this)) return oldActiveTeam.call(this);
  return this.allies(true);
};

// Cobblemon's Java battle model keeps all human raid actors on one BattleSide.
// Their active PNX slot letters therefore advance across the human side (p1a, p2b,
// p3c, p4d), while the lone boss on the opposite Java side is always <bossId>a.
// Stock Showdown derives letters from half-field numbering (p1a, p2a, p3b...),
// which is incompatible with Cobblemon's getActorAndActiveSlotFromPNX decoder.
const oldGetSlot = Pokemon.prototype.getSlot;
Pokemon.prototype.getSlot = function() {
  if (!isRaid(this.side)) return oldGetSlot.call(this);
  if (isBossSide(this.side)) return `${this.side.id}a`;
  const letter = 'abcdef'.charAt(this.side.n);
  if (!letter) throw new Error(`CobbleRaids PNX supports at most 6 human raid sides; got side index ${this.side.n}`);
  return `${this.side.id}${letter}`;
};

const oldPokemonIsAlly = Pokemon.prototype.isAlly;
Pokemon.prototype.isAlly = function(pokemon) {
  if (!isRaid(this.side)) return oldPokemonIsAlly.call(this, pokemon);
  return !!pokemon && this.side.hasAlly(pokemon);
};

const oldAdjacentAllies = Pokemon.prototype.adjacentAllies;
Pokemon.prototype.adjacentAllies = function() {
  if (!isRaid(this.side)) return oldAdjacentAllies.call(this);
  return this.allies().filter(p => p !== this && !p.fainted);
};

const oldAdjacentFoes = Pokemon.prototype.adjacentFoes;
Pokemon.prototype.adjacentFoes = function() {
  if (!isRaid(this.side)) return oldAdjacentFoes.call(this);
  return this.foes().filter(p => !p.fainted);
};

// +1 always means the boss from a player side. Negative values identify allied player sides.
const oldGetLocOf = Pokemon.prototype.getLocOf;
Pokemon.prototype.getLocOf = function(target) {
  if (!isRaid(this.side)) return oldGetLocOf.call(this, target);
  if (isBossSide(target.side)) return 1;
  if (target.side.n === this.side.n) return -1;
  return -(target.side.n + 2);
};

const oldGetAtLoc = Pokemon.prototype.getAtLoc;
Pokemon.prototype.getAtLoc = function(targetLoc) {
  if (!isRaid(this.side)) return oldGetAtLoc.call(this, targetLoc);
  if (targetLoc === 1) return bossSide(this.battle)?.active[0] || null;
  if (targetLoc < -1) {
    const sideIndex = -targetLoc - 2;
    const side = this.battle.sides[sideIndex];
    return isActivePlayerSide(side) ? side?.active[0] || null : null;
  }
  return this;
};

const oldValidTargetLoc = Battle.prototype.validTargetLoc;
Battle.prototype.validTargetLoc = function(targetLoc, source, targetType) {
  if (!isRaid(this)) return oldValidTargetLoc.call(this, targetLoc, source, targetType);
  if (!targetLoc) return true;
  const target = source.getAtLoc(targetLoc);
  if (!target || target.fainted) return false;
  if (targetType === 'adjacentAlly' || targetType === 'adjacentAllyOrSelf' || targetType === 'allySide' || targetType === 'allyTeam') {
    return source.isAlly(target);
  }
  if (targetType === 'adjacentFoe' || targetType === 'normal' || targetType === 'randomNormal' || targetType === 'any' || targetType === 'scripted') {
    return !source.isAlly(target) || target === source;
  }
  return true;
};

const oldGetRandomTarget = Battle.prototype.getRandomTarget;
Battle.prototype.getRandomTarget = function(pokemon, move) {
  if (!isRaid(this)) return oldGetRandomTarget.call(this, pokemon, move);
  if (move.target === 'self' || move.target === 'allies' || move.target === 'allySide' || move.target === 'allyTeam') return pokemon;
  const foes = pokemon.foes().filter(p => p && !p.fainted);
  return foes.length ? this.sample(foes) : null;
};

const oldCheckWin = Battle.prototype.checkWin;
Battle.prototype.checkWin = function(faintData) {
  if (!isRaid(this)) return oldCheckWin.call(this, faintData);
  const boss = bossSide(this);
  if (!boss || boss.pokemonLeft <= 0) {
    const survivingPlayer = this.sides.find(isActivePlayerSide);
    return this.win(survivingPlayer || null);
  }
  if (!this.sides.some(isActivePlayerSide)) return this.win(boss);
  return false;
};

const oldStart = Battle.prototype.start;
Battle.prototype.start = function() {
  if (!isRaid(this)) return oldStart.apply(this, arguments);
  if (this.deserialized) return;
  if (!this.sides.every(side => !!side)) throw new Error(`Missing sides: ${this.sides}`);
  if (this.started) throw new Error('Battle already started');
  if (this.sides.length < 2) throw new Error('Raid requires at least one player and one boss side');

  this.started = true;
  this.activePerHalf = 1;
  for (const side of this.sides) raidNormalizeSide(side);
  this.gameType = 'raid';
  refreshOpponentAnchors(this);

  const format = this.format;
  this.add('gametype', 'raid');
  for (const side of this.sides) this.add('teamsize', side.id, side.pokemon.length);
  this.add('gen', this.gen);
  this.add('tier', format.name);
  if (format.onBegin) format.onBegin.call(this);
  for (const rule of this.ruleTable.keys()) {
    if ('+*-!'.includes(rule.charAt(0))) continue;
    const sub = this.dex.formats.get(rule);
    if (sub.onBegin) sub.onBegin.call(this);
  }
  if (this.sides.some(side => !side.pokemon[0])) throw new Error('Battle not started: A raid side has an empty team.');
  if (this.debugMode) this.checkEVBalance();
  if (format.onTeamPreview) format.onTeamPreview.call(this);
  for (const rule of this.ruleTable.keys()) {
    if ('+*-!'.includes(rule.charAt(0))) continue;
    const sub = this.dex.formats.get(rule);
    if (sub.onTeamPreview) sub.onTeamPreview.call(this);
  }

  const boss = bossSide(this)?.pokemon[0];
  if (boss) boss.addVolatile('raidboss');

  this.queue.addChoice({choice: 'start'});
  this.midTurn = true;
  if (!this.requestState) this.go();
};

// The boss is AI-controlled by CobbleRaids/Cobblemon and never waits on a human input stream.
const oldMakeRequest = Battle.prototype.makeRequest;
Battle.prototype.makeRequest = function(type) {
  if (!isRaid(this)) return oldMakeRequest.apply(this, arguments);
  refreshOpponentAnchors(this);
  const result = oldMakeRequest.apply(this, arguments);
  // A withdrawn player remains a Cobblemon BattleActor until the shared battle ends, but their
  // Showdown side must never block later turns waiting for another choice. Keep its real Pokemon
  // state untouched and convert its request to a passive wait state.
  for (const side of this.sides) {
    if (!isPlayerSide(side) || (!side.raidWithdrawn && !isEliminatedPlayerSide(side))) continue;
    if (isEliminatedPlayerSide(side)) side.raidEliminated = true;
    passivatePlayerSide(side);
  }
  refreshOpponentAnchors(this);
  const boss = bossSide(this);
  if (type === 'move' && boss && boss.requestState === 'move' && boss.active[0] && !boss.active[0].fainted) {
    boss.chooseMove('', 0);
  }
  return result;
};

const oldSendUpdates = Battle.prototype.sendUpdates;
Battle.prototype.sendUpdates = function() {
  if (isRaid(this)) {
    this.sides.forEach(raidNormalizeSide);
    refreshOpponentAnchors(this);
  }
  return oldSendUpdates.apply(this, arguments);
};

const oldSetPlayer = Battle.prototype.setPlayer;
Battle.prototype.setPlayer = function(slot, options) {
  const result = oldSetPlayer.call(this, slot, options);
  if (isRaid(this)) {
    const side = this.sides[parseInt(slot.slice(1), 10) - 1];
    if (side) side.id = slot;
    this.activePerHalf = 1;
  }
  return result;
};

module.exports = {isRaid, bossIndex, bossSide, isBossSide, isPlayerSide, isEliminatedPlayerSide};

// Controlled cooperative victory command. Cobblemon writes Showdown INPUT, so emitting a raw
// "|win|..." line from Java is invalid. >raidwin is consumed here and produces the normal
// Showdown OUTPUT line that Cobblemon's WinInstruction already understands.
const {BattleStream} = require('./sim/battle-stream');
const oldWriteLine = BattleStream.prototype._writeLine;
BattleStream.prototype._writeLine = function(type, message) {
  if (!this.battle || !isRaid(this.battle)) {
    return oldWriteLine.call(this, type, message);
  }

  if (type === 'raidleave') {
    const battle = this.battle;
    if (battle.ended) return false;
    const side = battle.getSide(message.trim());
    if (!side || !isPlayerSide(side) || side.raidWithdrawn) return false;
    side.raidWithdrawn = true;
    passivatePlayerSide(side);
    refreshOpponentAnchors(battle);
    battle.inputLog.push(`>raidleave ${side.id}`);
    battle.add('-message', `${side.name} withdrew from the raid.`);
    // Do not change Pokemon HP/fainted state here. Cobblemon will commit the legitimate state
    // accumulated before withdrawal when the shared battle finally ends.
    if (battle.requestState && battle.allChoicesDone()) battle.commitDecisions();
    battle.sendUpdates();
    return true;
  }

  if (type === 'raidwin') {
    const battle = this.battle;
    if (battle.ended) return false;
    battle.inputLog.push(`>raidwin ${message}`);
    battle.winner = message;
    battle.add('');
    battle.add('win', message);
    battle.updatePP();
    battle.ended = true;
    battle.requestState = '';
    for (const side of battle.sides) if (side) side.activeRequest = null;
    battle.sendUpdates();
    return true;
  }

  return oldWriteLine.call(this, type, message);
};
