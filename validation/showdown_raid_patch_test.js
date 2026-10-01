'use strict';

// Exercises raid-patch.js's exported topology predicates against the REAL Showdown sim modules it
// requires -- not a reimplementation of them. Requiring raid-patch.js also applies every monkeypatch
// in the file (Battle.prototype.add, Side.prototype.chooseSwitch, etc.), so a syntax or load-time
// error anywhere in it fails this test too, not just the two functions asserted below.
//
// This is deliberately narrow: only the exported, pure functions (isRaid, bossIndex, bossSide,
// isBossSide, isPlayerSide, isEliminatedPlayerSide) are tested here, with plain object literals
// standing in for real Battle/Side instances -- the functions are written to accept exactly that
// shape (isRaid does `value?.battle || value`), so a fake is not a guess about their contract, it is
// the contract. The deeper fixes in this file (the >p5 dispatcher, the forced-switch resolver) touch
// real Side/BattleStream internals closely enough that a hand-built fake would risk testing this
// file's own wrong assumptions about those internals rather than the real ones; those stay covered
// only by validation/smoke's live multi-player tests.
//
// Invoked with the extracted-Showdown-plus-raid-patch.js directory as argv[2]; see
// validate_showdown_raid_patch_behavior.py, which builds that directory from the same Cobblemon jar
// the mod ships against.

const path = require('path');

const fixtureDir = process.argv[2];
if (!fixtureDir) {
  console.error('usage: node showdown_raid_patch_test.js <extracted-showdown-dir>');
  process.exit(2);
}

const patch = require(path.join(fixtureDir, 'raid-patch.js'));

let failures = 0;
function check(name, actual, expected) {
  if (actual !== expected) {
    failures++;
    console.error(`FAIL ${name}: expected ${expected}, got ${actual}`);
  }
}

function pokemon({fainted = false, hp = 100} = {}) {
  return {fainted, hp};
}

function battle({gameType, sides}) {
  return {gameType, sides};
}

function side(battleObj, n, extra = {}) {
  return {battle: battleObj, n, pokemon: [], pokemonLeft: 1, raidWithdrawn: false, ...extra};
}

// --- isRaid ---
{
  const raidBattle = battle({gameType: 'raid', sides: []});
  const ordinaryBattle = battle({gameType: 'singles', sides: []});
  check('isRaid(raid battle)', patch.isRaid(raidBattle), true);
  check('isRaid(ordinary battle)', patch.isRaid(ordinaryBattle), false);
  check('isRaid(side of a raid battle)', patch.isRaid({battle: raidBattle}), true);
  check('isRaid(null)', patch.isRaid(null), false);
}

// --- bossIndex / bossSide: last side is always the boss, at any player count ---
for (const playerCount of [1, 2, 3, 4]) {
  const sides = new Array(playerCount + 1).fill(null);
  const raidBattle = battle({gameType: 'raid', sides});
  const bossIdx = patch.bossIndex(raidBattle);
  check(`bossIndex with ${playerCount} player(s)`, bossIdx, playerCount);

  const bossSideObj = side(raidBattle, bossIdx);
  raidBattle.sides[bossIdx] = bossSideObj;
  check(`bossSide resolves the last side (${playerCount} players)`, patch.bossSide(raidBattle), bossSideObj);
  check(`isBossSide true for the last side (${playerCount} players)`, patch.isBossSide(bossSideObj), true);

  for (let n = 0; n < bossIdx; n++) {
    const playerSideObj = side(raidBattle, n);
    raidBattle.sides[n] = playerSideObj;
    check(`isPlayerSide true for slot ${n} of ${playerCount}`, patch.isPlayerSide(playerSideObj), true);
    check(`isBossSide false for slot ${n} of ${playerCount}`, patch.isBossSide(playerSideObj), false);
  }
}

// --- The historically-buggy 4-player (5-side) shape specifically ---
{
  const raidBattle = battle({gameType: 'raid', sides: new Array(5).fill(null)});
  const boss = side(raidBattle, 4);
  raidBattle.sides[4] = boss;
  const players = [0, 1, 2, 3].map(n => {
    const s = side(raidBattle, n);
    raidBattle.sides[n] = s;
    return s;
  });
  check('4-player raid: bossIndex is 4', patch.bossIndex(raidBattle), 4);
  for (const p of players) {
    check(`4-player raid: player slot ${p.n} is a player side`, patch.isPlayerSide(p), true);
  }
  check('4-player raid: boss is not a player side', patch.isPlayerSide(boss), false);
}

// --- isPlayerSide / isBossSide are false outside a raid, regardless of index ---
{
  const ordinaryBattle = battle({gameType: 'singles', sides: new Array(2).fill(null)});
  const s0 = side(ordinaryBattle, 0);
  const s1 = side(ordinaryBattle, 1);
  ordinaryBattle.sides[0] = s0;
  ordinaryBattle.sides[1] = s1;
  check('isPlayerSide false outside a raid', patch.isPlayerSide(s0), false);
  check('isBossSide false outside a raid (last side)', patch.isBossSide(s1), false);
}

// --- isEliminatedPlayerSide ---
{
  const raidBattle = battle({gameType: 'raid', sides: new Array(3).fill(null)});
  raidBattle.sides[2] = side(raidBattle, 2);

  const zeroLeft = side(raidBattle, 0, {pokemonLeft: 0, pokemon: [pokemon()]});
  check('eliminated when pokemonLeft is 0', patch.isEliminatedPlayerSide(zeroLeft), true);

  const allFainted = side(raidBattle, 0, {pokemonLeft: 1, pokemon: [pokemon({fainted: true}), pokemon({hp: 0})]});
  check('eliminated when every listed Pokemon is fainted or at 0 HP', patch.isEliminatedPlayerSide(allFainted), true);

  const oneAlive = side(raidBattle, 0, {pokemonLeft: 1, pokemon: [pokemon({fainted: true}), pokemon({hp: 50})]});
  check('not eliminated with one live Pokemon left', patch.isEliminatedPlayerSide(oneAlive), false);

  const bossNeverEliminated = side(raidBattle, 2, {pokemonLeft: 0, pokemon: [pokemon({fainted: true})]});
  check('the boss side is never "eliminated" (it is not a player side)',
      patch.isEliminatedPlayerSide(bossNeverEliminated), false);
}

// --- bossLateMoveUnusable: a late AI choice for a move the boss cannot use must be dropped ---
// Pure predicate over a side's moveSlots and request, so plain object literals are the contract.
{
  const boss = (slots, requestMoves) => ({
    active: [{fainted: false, moveSlots: slots}],
    activeRequest: requestMoves ? {active: [{moves: requestMoves}]} : null,
  });
  const slots = [{id: 'splash', disabled: true}, {id: 'tackle', disabled: false}, {id: 'ember', disabled: 'hidden'}];
  check('unusable: visibly disabled slot', patch.bossLateMoveUnusable(boss(slots), 'move 1'), true);
  check('usable: enabled slot', patch.bossLateMoveUnusable(boss(slots), 'move 2'), false);
  check('unusable: hidden-disabled slot (Imprison)', patch.bossLateMoveUnusable(boss(slots), 'move 3'), true);
  check('unusable: no such slot', patch.bossLateMoveUnusable(boss(slots), 'move 9'), true);
  check('unusable: disabled in the request only',
      patch.bossLateMoveUnusable(boss(slots, [{}, {disabled: true}, {}]), 'move 2'), true);
  check('usable by name', patch.bossLateMoveUnusable(boss(slots), 'move tackle'), false);
  check('unusable by name', patch.bossLateMoveUnusable(boss(slots), 'move splash'), true);
  check('a target suffix does not change the verdict', patch.bossLateMoveUnusable(boss(slots), 'move 2 1'), false);
  check('a switch is never judged here', patch.bossLateMoveUnusable(boss(slots), 'switch 2'), false);
  check('"default" is never judged here', patch.bossLateMoveUnusable(boss(slots), 'default'), false);
  check('a fainted boss is never judged here',
      patch.bossLateMoveUnusable({active: [{fainted: true, moveSlots: slots}]}, 'move 1'), false);
}

// --- the real dispatcher + the real sim: Taunt must not leave the boss with no choice ---
// Reproduced on 2026-09-30 from a live raid freeze ("[Unavailable choice] Can't move: Blaziken's
// Flare Blitz is disabled"): Showdown's choose() clears the boss's valid pre-fill BEFORE it validates
// the late AI choice, so a choice for a taunted/disabled move left the boss with none and the turn
// waited forever. Asserts presence of the surviving choice, not just the absence of an error.
{
  const {Battle} = require(path.join(fixtureDir, 'sim/index.js'));
  const {BattleStream} = require(path.join(fixtureDir, 'sim/battle-stream'));
  const set = (species, moves) => ({
    name: species, species, level: 100, gender: 'M', moves,
    movesInfo: moves.map(() => ({pp: 40, maxPp: 40})), ability: 'Pressure', item: '', nature: 'Hardy',
    evs: {hp: 85, atk: 85, def: 85, spa: 85, spd: 85, spe: 85},
    ivs: {hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31},
  });
  const sim = new Battle({formatid: 'gen9customgame'});
  sim.gameType = 'raid';
  sim.setPlayer('p1', {team: [set('Regieleki', ['taunt', 'tackle'])]});
  sim.setPlayer('p2', {team: [set('Arceus', ['splash', 'tackle'])]});
  if (sim.requestState === 'teampreview') {
    sim.choose('p1', 'team 1');
    sim.choose('p2', 'team 1');
  }
  const stream = new BattleStream();
  stream.battle = sim;
  const errors = [];
  const send = sim.send.bind(sim);
  sim.send = (type, data) => {
    if (/\|error\|/.test(String(data))) errors.push(String(data));
    return send(type, data);
  };
  const chosen = () => sim.p2.choice.actions.map(action => action.moveid).join(',');

  sim.choose('p1', 'move 1'); // Taunt: splash is now disabled for the boss
  check('precondition: the boss pre-filled a usable move', chosen(), 'tackle');
  stream._writeLine('p2', 'move 1'); // the AI names the taunted move
  check('a late choice for a disabled move leaves the pre-filled choice in place', chosen(), 'tackle');
  check('the boss still has a complete choice', sim.p2.isChoiceDone(), true);
  check('no |error| is emitted for the dropped choice', errors.length, 0);

  stream._writeLine('p2', 'move 2'); // a valid late choice must still be applied as before
  check('a valid late choice is still applied', chosen(), 'tackle');
  const before = sim.turn;
  sim.choose('p1', 'move 2');
  check('the turn advances rather than waiting on the boss', sim.turn, before + 1);
}

if (failures > 0) {
  console.error(`${failures} assertion(s) failed`);
  process.exit(1);
}
console.log('raid-patch.js topology predicates and late boss choices: PASS');
