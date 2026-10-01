'use strict';

// Fuzz harness for the raid battle model: random raid battles on the REAL Showdown sim (the exact
// bundle Cobblemon ships) with this build's raid-patch.js applied, driven the way Cobblemon drives it.
//
// Why it exists: the raid freezes so far (Imprison, then Taunt/Disable) were each a Showdown state
// the boss's choice flow did not expect, found from a player's log and reproduced by hand with one
// hand-picked move. This searches that space instead.
//
// How it drives a battle -- the protocol the live server uses:
//   * a boss side is pre-filled by raid-patch.js at the start of every turn;
//   * Cobblemon's AI then sends its OWN choice for the boss through BattleStream._writeLine -- from
//     a request it may not have seen the latest state of, so the harness sometimes names a disabled
//     move, a stale slot, or sends the choice twice (a re-invoke);
//   * humans send moves, switches, undo and sometimes nonsense, and re-choose after a rejection the
//     way a reopened choice GUI does;
//   * players disconnect (>raidhold), reconnect (>raidresume) and withdraw (>raidleave).
//
// What it asserts after every round (a failure is one of these, never a flaky timing):
//   * STALL    -- a side that must act has no complete choice, or every side has one and the turn did
//                 not commit. This is the freeze. A boss left empty is the case that mattered most.
//   * THROW    -- the sim or the patch threw instead of answering.
//   * NOPROGRESS -- a full round ran and nothing about the battle changed.
// Rejected choices are expected and are only counted, so the summary shows what the sim refuses.
//
// Usage (see validate_showdown_fuzz.py, which builds <fixtureDir> from the Cobblemon jar):
//   node showdown_fuzz.js <fixtureDir> [--battles N] [--seed S] [--seeds A,B,C] [--turns T] [--replay SEED] [--verbose]
//   --seeds adds specific seeds to the run: the regression set of battles that once froze.
//
// Every battle is seeded, so a failure replays exactly: --replay SEED --verbose.

const path = require('path');

const args = process.argv.slice(2);
const fixtureDir = args.find(arg => !arg.startsWith('--') && !/^\d+$/.test(arg));
if (!fixtureDir) {
  console.error('usage: node showdown_fuzz.js <extracted-showdown-dir> [--battles N] [--seed S] [--turns T] [--replay SEED] [--verbose]');
  process.exit(2);
}
const option = (name, fallback) => {
  const at = args.indexOf(`--${name}`);
  return at >= 0 && args[at + 1] !== undefined ? Number(args[at + 1]) : fallback;
};
const listOption = name => {
  const at = args.indexOf(`--${name}`);
  return at >= 0 && args[at + 1] ? args[at + 1].split(',').filter(Boolean).map(Number) : [];
};
const OPTIONS = {
  extraSeeds: listOption('seeds'),
  battles: option('battles', 200),
  seed: option('seed', 1),
  turns: option('turns', 14),
  replay: args.includes('--replay') ? option('replay', 1) : null,
  verbose: args.includes('--verbose'),
};

// Requiring raid-patch.js applies every monkeypatch; it must come before anything touches the sim.
const patch = require(path.join(fixtureDir, 'raid-patch.js'));
const {Battle} = require(path.join(fixtureDir, 'sim/battle'));
const {BattleStream} = require(path.join(fixtureDir, 'sim/battle-stream'));
const {Dex} = require(path.join(fixtureDir, 'sim/dex'));

// ---------------------------------------------------------------------------------------------
// Deterministic randomness
// ---------------------------------------------------------------------------------------------
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const pick = (rng, list) => list[Math.floor(rng() * list.length)];
const chance = (rng, p) => rng() < p;
function shuffle(rng, list) {
  const copy = list.slice();
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

// ---------------------------------------------------------------------------------------------
// What a team can be made of
// ---------------------------------------------------------------------------------------------
const USABLE = entry => entry.exists && entry.gen > 0 && entry.gen <= 9 &&
  (!entry.isNonstandard || entry.isNonstandard === 'Past');
const MOVES = Dex.moves.all().filter(move => USABLE(move) && !move.isZ && !move.isMax &&
  !['struggle', 'recharge'].includes(move.id));
const SPECIES = Dex.species.all().filter(species => USABLE(species) && !species.battleOnly &&
  !species.isMega && !species.isPrimal && !species.name.includes('-Gmax') && species.baseStats.hp > 0);
const ITEMS = ['', '', '', 'leftovers', 'choiceband', 'choicescarf', 'choicespecs', 'assaultvest', 'focussash',
  'lifeorb', 'sitrusberry', 'lumberry', 'rockyhelmet', 'toxicorb', 'flameorb', 'ironball', 'blacksludge',
  'shellbell', 'scopelens', 'eviolite', 'safetygoggles', 'covertcloak', 'throatspray', 'loadeddice',
  'protectivepads', 'heavydutyboots', 'redcard', 'ejectbutton', 'ejectpack', 'mentalherb', 'whiteherb'];

function makeSet(rng, {boss}) {
  const species = pick(rng, SPECIES);
  const moveCount = 1 + Math.floor(rng() * 4);
  const moves = shuffle(rng, MOVES).slice(0, moveCount).map(move => move.id);
  const abilities = Object.values(species.abilities);
  // Low PP now and then, so a side runs out of every move and Showdown has to hand it Struggle.
  const lowPp = chance(rng, 0.12);
  return {
    name: species.name, species: species.name, gender: 'M', nature: 'Hardy',
    level: boss ? 100 : 20 + Math.floor(rng() * 81),
    moves, movesInfo: moves.map(() => {
      const pp = lowPp ? 1 + Math.floor(rng() * 2) : 16;
      return {pp, maxPp: pp};
    }),
    ability: pick(rng, abilities),
    item: boss ? '' : pick(rng, ITEMS),
    evs: {hp: 85, atk: 85, def: 85, spa: 85, spd: 85, spe: 85},
    ivs: {hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31},
  };
}

function describeSet(set) {
  return `${set.species} L${set.level} [${set.moves.join(',')}]${set.item ? ' @' + set.item : ''}`;
}

// ---------------------------------------------------------------------------------------------
// Reading a side's request the way a client / Cobblemon's AI does
// ---------------------------------------------------------------------------------------------
const fainted = mon => /\bfnt$/.test(mon.condition || '');

/** Every choice the request legally offers, as the strings Cobblemon writes after ">pN ". */
function legalChoices(side) {
  const req = side.activeRequest;
  if (!req || req.wait) return {moves: [], switches: [], other: []};
  if (req.teamPreview) return {moves: [], switches: [], other: ['team 1']};
  const bench = (req.side?.pokemon || [])
    .map((mon, index) => ({mon, slot: index + 1}))
    .filter(entry => !entry.mon.active && !fainted(entry.mon))
    .map(entry => `switch ${entry.slot}`);
  if (req.forceSwitch) {
    return {moves: [], switches: bench, other: bench.length ? ['default'] : ['pass']};
  }
  if (req.active) {
    const active = req.active[0] || {};
    const moves = [];
    (active.moves || []).forEach((move, index) => {
      if (!move.disabled) moves.push(`move ${index + 1}`);
    });
    if (!moves.length) moves.push('move 1');
    const trapped = active.trapped || active.maybeTrapped;
    return {moves, switches: trapped ? [] : bench, other: []};
  }
  return {moves: [], switches: [], other: []};
}

const NONSENSE = ['move 99', 'switch 99', 'move 0', 'team 3', 'shift', 'move', 'switch', 'pass', 'garbage 1'];

// ---------------------------------------------------------------------------------------------
// One battle
// ---------------------------------------------------------------------------------------------
class Failure extends Error {
  constructor(kind, signature, detail) {
    super(`${kind}: ${signature}`);
    this.kind = kind;
    this.signature = signature;
    this.detail = detail;
  }
}

function runBattle(seed, stats) {
  const rng = mulberry32(seed);
  const playerCount = 1 + Math.floor(rng() * 4);
  const sets = [];
  for (let i = 0; i < playerCount; i++) {
    const team = [];
    const size = 1 + Math.floor(rng() * 3);
    for (let j = 0; j < size; j++) team.push(makeSet(rng, {boss: false}));
    sets.push(team);
  }
  const bossSet = makeSet(rng, {boss: true});
  sets.push([bossSet]);

  const sim = new Battle({formatid: 'gen9customgame', seed: [seed & 0xffff, 1, 2, 3]});
  sim.sides.length = playerCount + 1;
  sim.sides.fill(null);
  sim.gameType = 'raid';
  sets.forEach((team, index) => sim.setPlayer(`p${index + 1}`, {name: index === playerCount ? 'Boss' : `P${index + 1}`, team}));

  const stream = new BattleStream();
  stream.battle = sim;

  const log = [];
  const say = line => { log.push(line); if (OPTIONS.verbose || OPTIONS.replay !== null) console.log(`  ${line}`); };
  say(`players=${playerCount}`);
  sets.forEach((team, index) => say(`${index === playerCount ? 'boss' : 'p' + (index + 1)}: ${team.map(describeSet).join(' | ')}`));

  // Rejections arrive as sideupdate "|error|" lines addressed to one side.
  let rejections = [];
  const send = sim.send.bind(sim);
  sim.send = (type, data) => {
    const text = String(data);
    const match = /^(p\d+)\n\|error\|(.*)$/s.exec(text);
    if (match) rejections.push({side: match[1], text: match[2].trim()});
    return send(type, data);
  };

  const boss = () => sim.sides[sim.sides.length - 1];
  const sideById = id => sim.sides.find(side => side && side.id === id);
  const signature = () => `${sim.turn}|${sim.requestState}|${sim.sides.map(side => side.requestState || '').join(',')}|${sim.ended}`;

  /** Writes a line the way Cobblemon does, recording what the sim answered. */
  function write(side, text) {
    rejections = [];
    let thrown = null;
    try {
      stream._writeLine(side.id, text);
    } catch (err) {
      thrown = err;
    }
    const rejected = rejections.slice();
    say(`>${side.id} ${text}${thrown ? `  THROWS ${thrown.message}` : ''}${rejected.length ? `  -> ${rejected.map(r => r.text).join(' / ')}` : ''}`);
    if (thrown) {
      const where = (thrown.stack || '').split('\n').find(line => /raid-patch|sim\//.test(line)) || '';
      throw new Failure('THROW', `${text.split(' ')[0]}: ${String(thrown.message).split('\n')[0].slice(0, 120)}`, where.trim());
    }
    for (const rejection of rejected) {
      const key = rejection.text.replace(/[A-Z][\w'-]*('s)?\s/g, 'X ').slice(0, 90);
      const bucket = rejection.side === boss().id ? stats.bossRejections : stats.humanRejections;
      bucket.set(key, (bucket.get(key) || 0) + 1);
    }
    return rejected;
  }

  // Cobblemon's AI names a move from a request it may not have the latest state of.
  let previousBossMoves = null;
  function aiBossChoice() {
    const side = boss();
    const req = side.activeRequest;
    const choices = legalChoices(side);
    if (req && req.forceSwitch) return choices.switches.length ? pick(rng, choices.switches) : 'default';
    const moves = req && req.active && req.active[0] && req.active[0].moves;
    if (!moves) return pick(rng, choices.moves.concat(choices.other, ['move 1']));
    const mode = rng();
    let view = moves;
    if (mode < 0.3) view = moves.map(() => ({disabled: false}));            // unaware of any disable
    else if (mode < 0.5 && previousBossMoves) view = previousBossMoves;     // a request one turn old
    const open = view.map((move, index) => (move.disabled ? -1 : index)).filter(index => index >= 0);
    return `move ${(open.length ? pick(rng, open) : 0) + 1}`;
  }

  /** The boss's late choice, plus the re-invoke Cobblemon performs after a rejection. */
  function bossLateChoice() {
    const side = boss();
    for (let attempt = 0; attempt < 3; attempt++) {
      const rejected = write(side, aiBossChoice());
      if (!rejected.length || sim.ended) return;
    }
  }

  function humanChoice(side) {
    if (chance(rng, 0.08)) write(side, 'undo');
    if (chance(rng, 0.05)) write(side, pick(rng, NONSENSE));
    for (let attempt = 0; attempt < 6; attempt++) {
      if (sim.ended || side.isChoiceDone() || !needsChoice(side)) return;
      const options = legalChoices(side);
      const pool = attempt >= 3 ? options.moves.concat(options.switches, options.other)
        : (options.switches.length && chance(rng, 0.15) ? options.switches
          : options.moves.length ? options.moves : options.switches.concat(options.other));
      write(side, pool.length ? pick(rng, pool) : 'default');
    }
  }

  const isPlayer = side => patch.isPlayerSide(side);
  const needsChoice = side => !!side.requestState && !(side.activeRequest && side.activeRequest.wait) && !side.isChoiceDone();

  /** One line per side, for a failure report: who is waiting, withdrawn, or owes a switch. */
  const sideStates = () => sim.sides.map(side => {
    const req = side.activeRequest;
    const flags = [side.requestState || 'none', req && req.wait ? 'wait' : '', req && req.forceSwitch ? 'forceSwitch' : '',
      side.raidWithdrawn ? 'WITHDRAWN' : '', side.isChoiceDone() ? 'done' : 'NOT-done',
      `${side.pokemonLeft} left`].filter(Boolean);
    return `${side.n === sim.sides.length - 1 ? 'boss' : side.id}[${flags.join(' ')}]`;
  }).join(' ');

  /**
   * When a battle is stuck with a withdrawn player, try the two ways it can be unstuck on a live
   * server and report whether either worked. Runs on the dying battle, after the failure is certain.
   */
  function recoveryProbe() {
    const withdrawn = sim.sides.filter(side => patch.isPlayerSide(side) && side.raidWithdrawn);
    if (!withdrawn.length) return '';
    const turn = sim.turn;
    try {
      for (const side of withdrawn) stream._writeLine('raidresume', side.id);
      const resumed = `resume: turn ${sim.turn === turn ? 'unchanged' : 'advanced'}, needs=[${sim.sides.filter(needsChoice).map(side => side.id)}]`;
      for (const side of withdrawn) stream._writeLine('raidleave', side.id);
      return `  | ${resumed}; then leave: turn ${sim.turn === turn ? 'unchanged' : 'advanced'}, ended=${sim.ended}`;
    } catch (err) {
      return `  | recovery probe threw ${String(err.message).split('\n')[0]}`;
    }
  }

  const bossSummary = () => {
    const side = boss();
    const active = side.active[0];
    const req = side.activeRequest && side.activeRequest.active && side.activeRequest.active[0];
    const slots = active ? active.moveSlots.map(slot => `${slot.id}${slot.disabled ? '(' + slot.disabled + ')' : ''}`).join(',') : '-';
    const asked = req ? req.moves.map(move => `${move.id}${move.disabled ? '(off)' : ''}`).join(',') : 'no-active-request';
    const volatiles = active ? Object.keys(active.volatiles).join(',') : '';
    return `${active ? active.name + ' ' + active.status : 'none'} slots[${slots}] request[${asked}] chosen[${side.choice.actions.map(a => a.moveid || a.choice).join(',')}] done=${side.isChoiceDone()} volatiles[${volatiles}]`;
  };

  say('--- start');
  for (const side of sim.sides) if (sim.requestState === 'teampreview') sim.choose(side.id, 'team 1');

  for (let round = 1; round <= OPTIONS.turns && !sim.ended; round++) {
    const before = signature();
    const turnAtStart = sim.turn, phaseAtStart = sim.requestState;
    const sameTurn = () => !sim.ended && sim.turn === turnAtStart && sim.requestState === phaseAtStart;
    say(`--- round ${round}: turn ${sim.turn}, request=${sim.requestState}  boss: ${bossSummary()}`);
    const bossMoves = boss().activeRequest && boss().activeRequest.active && boss().activeRequest.active[0] &&
      boss().activeRequest.active[0].moves;

    const humans = sim.sides.filter(side => isPlayer(side));
    // Connection churn happens between choices, as it does when a player drops mid-turn.
    const events = [];
    for (const side of humans) {
      if (!side.raidWithdrawn && chance(rng, 0.03)) events.push(() => churn(side, 'raidhold'));
      else if (side.raidWithdrawn && chance(rng, 0.3)) events.push(() => churn(side, 'raidresume'));
      else if (!side.raidWithdrawn && chance(rng, 0.01)) events.push(() => churn(side, 'raidleave'));
    }
    function churn(side, verb) {
      rejections = [];
      let ok;
      try { ok = stream._writeLine(verb, side.id); } catch (err) {
        throw new Failure('THROW', `${verb}: ${String(err.message).split('\n')[0].slice(0, 120)}`, '');
      }
      say(`>${verb} ${side.id} -> ${ok}`);
    }

    // The boss's AI answers the request immediately; humans take longer. Rarely the order differs.
    const actions = [];
    if (chance(rng, 0.9)) actions.push(() => bossLateChoice());
    for (const side of shuffle(rng, humans)) actions.push(() => humanChoice(side));
    for (const event of events) actions.splice(Math.floor(rng() * (actions.length + 1)), 0, event);
    if (chance(rng, 0.1)) actions.splice(Math.floor(rng() * (actions.length + 1)), 0, () => bossLateChoice()); // arrives late
    if (chance(rng, 0.15)) actions.push(() => bossLateChoice()); // a duplicate re-invoke

    for (const action of actions) {
      if (sim.ended) break;
      action();
    }
    previousBossMoves = bossMoves || previousBossMoves;

    // Stragglers: a human whose choice was rejected re-chooses until it takes. Only while the turn
    // is still the one this round started in -- once it commits, the next request is the next round's.
    for (const side of humans) if (sameTurn()) humanChoice(side);

    stats.rounds++;
    if (sim.ended) break;

    // ---- invariants -------------------------------------------------------------------------
    // If the turn committed, the battle made progress and the next round deals with its request. If it
    // did not, every side that must act has to have a complete choice -- anything else is a freeze.
    if (sameTurn() && sim.requestState) {
      // Every remaining player held is the deliberate "wait for the party" state, not a freeze -- the
      // raid pauses until somebody comes back (RaidReconnectService#hasActivePresence). Model the
      // return, and require what actually matters: the resumed player owes a choice again.
      const remaining = humans.filter(side => !patch.isEliminatedPlayerSide(side));
      if (sim.requestState === 'move' && remaining.length && remaining.every(side => side.raidWithdrawn)) {
        const returning = pick(rng, remaining);
        churn(returning, 'raidresume');
        if (!needsChoice(returning)) {
          throw new Failure('STALL', 'resumed-player-owes-no-choice (move)', `${returning.id} resumed into a turn that waits on them`);
        }
        continue;
      }
      const lacking = sim.sides.filter(side => side.requestState && !side.isChoiceDone());
      if (lacking.length) {
        const who = lacking.map(side => (side.n === sim.sides.length - 1 ? 'BOSS' : side.id)).join(',');
        const kind = lacking.some(side => side.n === sim.sides.length - 1) ? 'boss-has-no-choice' : 'human-cannot-choose';
        throw new Failure('STALL', `${kind} (${sim.requestState})`, `no complete choice: ${who}`);
      }
      if (sim.allChoicesDone()) {
        throw new Failure('STALL', `all-choices-done-but-turn-not-committed (${sim.requestState})`,
          `${sideStates()}${recoveryProbe()}`);
      }
      throw new Failure('NOPROGRESS', `round changed nothing (${sim.requestState})`, '');
    }
  }
  stats.completed += sim.ended ? 1 : 0;
  return log;
}

// ---------------------------------------------------------------------------------------------
// Run and report
// ---------------------------------------------------------------------------------------------
const stats = {rounds: 0, completed: 0, bossRejections: new Map(), humanRejections: new Map()};
const failures = new Map(); // signature -> {first seed, count, detail, log}
const seeds = OPTIONS.replay !== null ? [OPTIONS.replay]
  : [...new Set([...OPTIONS.extraSeeds, ...Array.from({length: OPTIONS.battles}, (_, i) => OPTIONS.seed + i)])];

for (const seed of seeds) {
  if (OPTIONS.replay !== null) console.log(`battle seed ${seed}`);
  try {
    runBattle(seed, stats);
  } catch (err) {
    const failure = err instanceof Failure ? err : new Failure('THROW', `harness/sim: ${String(err && err.message).split('\n')[0].slice(0, 120)}`, (err && err.stack || '').split('\n').slice(1, 3).join(' | '));
    const key = `${failure.kind} ${failure.signature}`;
    const known = failures.get(key);
    if (known) known.count++;
    else failures.set(key, {seed, count: 1, detail: failure.detail});
    if (OPTIONS.replay !== null) console.log(`FAILED: ${key} ${failure.detail}`);
  }
}

const top = (map, n) => [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, n);
console.log(`showdown fuzz: ${seeds.length} battle(s), ${stats.rounds} round(s), ${stats.completed} played to a result`);
for (const [label, map] of [['boss rejections (recovered, informational)', stats.bossRejections],
  ['human rejections (recovered, informational)', stats.humanRejections]]) {
  if (!map.size) continue;
  console.log(`  ${label}:`);
  for (const [text, count] of top(map, 6)) console.log(`    ${String(count).padStart(5)}  ${text}`);
}
if (failures.size) {
  console.log(`FAIL: ${failures.size} distinct failure(s)`);
  for (const [key, info] of failures) {
    console.log(`  ${key}  x${info.count}  first seed ${info.seed}${info.detail ? `  (${info.detail})` : ''}`);
    console.log(`    replay: node showdown_fuzz.js <dir> --replay ${info.seed}`);
  }
  process.exit(1);
}
console.log('showdown fuzz: PASS');
