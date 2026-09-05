// Phase 32: raid boss healing must reach the Java pool through -raidheal.
//
// Requires an unbundled Cobblemon Showdown tree at validation/showdown, patched the way
// ShowdownResourceLoaderMixin patches it at runtime:
//   1. unzip Cobblemon's data/cobblemon/showdown.zip into validation/showdown
//   2. copy src/main/resources/assets/cobbleraids/showdown/raid-patch.js to validation/showdown/
//   3. copy .../showdown/mods/conditions.js to validation/showdown/data/mods/cobblemon/conditions.js
//   4. in validation/showdown/sim/dex-formats.js, replace the playerCount assignment with the
//      raid-aware form (see PLAYER_COUNT_RAID in ShowdownResourceLoaderMixin)
// Then: node validation/raid-healing-test.js
//
// Before Phase 32 the boss is pinned at full simulated HP, so Showdown's "already at full HP"
// guards refuse every recovery move with `-fail <boss> heal`, which Cobblemon renders as
// "<boss>'s HP is full!". Moves carrying a `heal:` property never even reached the TryHeal
// event, because BattleActions#runMoveEffects calls Pokemon#heal directly.

const path = require('path');
const SD = path.join(__dirname, 'showdown');
require(path.join(SD, 'raid-patch.js'));
const {BattleStream} = require(path.join(SD, 'sim/battle-stream.js'));

// Cobblemon's packed-team dialect:
// name|species|uuid|currentHealth|status|statusDuration|item|ability|moves|movesInfo|
// nature|evs|gender|ivs|shiny|level|misc
const pack = set => [
  set.species, set.species.toLowerCase(), set.uuid, set.currentHealth, '', '-1',
  set.item || '', set.ability, set.moves.join(','), set.moves.map(() => '16/16').join(','),
  '', '', '', '', '', String(set.level), ''
].join('|');

function runRaid({bossMoves, bossItem = '', bossHealth = 304, turns = 2}) {
  const stream = new BattleStream();
  const log = [];
  (async () => { for await (const chunk of stream) log.push(chunk); })();

  const format = JSON.stringify({
    mod: 'cobblemon', gameType: 'raid', gen: 9, ruleset: [], effectType: 'Format', playerCount: 2
  });
  stream.write(`>start { "format": ${format} }`);
  stream.write(`>player p1 {"name":"P1","team":"${pack({
    species: 'Snorlax', uuid: 'h0', currentHealth: 393, ability: 'thickfat',
    moves: ['bodyslam', 'crunch', 'tackle', 'recover'], level: 85
  })}"}`);
  stream.write(`>player p2 {"name":"RaidBoss","team":"${pack({
    species: 'Garchomp', uuid: 'boss', currentHealth: bossHealth, ability: 'sandveil',
    item: bossItem, moves: bossMoves, level: 85
  })}"}`);
  // The boss is AI-driven and auto-chooses its first move; players drive the turn clock.
  for (let turn = 0; turn < turns; turn++) stream.write('>p1 move 1');

  return new Promise(resolve => setImmediate(() => setTimeout(() => resolve(log.join('\n')), 250)));
}

const bossOf = line => line.startsWith('|-heal|p2a:') || line.startsWith('|-fail|p2a: boss|heal');

async function expectHeals(label, options) {
  const output = await runRaid(options);
  const lines = output.split('\n');

  const heals = lines.filter(line => line.startsWith('|-raidheal|p2a: boss|'));
  if (!heals.length) throw new Error(`${label}: expected at least one -raidheal, got none`);

  const failed = lines.filter(line => line === '|-fail|p2a: boss|heal');
  if (failed.length) throw new Error(`${label}: boss healing still fails with "HP is full" (${failed.length}x)`);

  const leaked = lines.filter(bossOf);
  if (leaked.length) throw new Error(`${label}: vanilla boss health line leaked: ${leaked[0]}`);

  const amount = Number(heals[0].split('|')[3]);
  if (!Number.isFinite(amount) || amount <= 0) throw new Error(`${label}: bad -raidheal amount in ${heals[0]}`);
  console.log(`${label}: PASS (${heals.length} x -raidheal, first ${amount})`);
}

async function expectPlayerHealingUntouched() {
  const output = await runRaid({bossMoves: ['tackle', 'tackle', 'tackle', 'tackle'], turns: 3});
  if (output.includes('|-raidheal|p1a:')) throw new Error('player healing was routed through -raidheal');
  console.log('player healing untouched: PASS');
}

(async () => {
  // heal: property, straight through BattleActions#runMoveEffects
  await expectHeals('recover', {bossMoves: ['recover', 'tackle', 'tackle', 'tackle']});
  await expectHeals('roost', {bossMoves: ['roost', 'tackle', 'tackle', 'tackle']});
  // onHit guards that test hp === maxhp or the return value of Battle#heal
  await expectHeals('rest', {bossMoves: ['rest', 'tackle', 'tackle', 'tackle']});
  await expectHeals('synthesis', {bossMoves: ['synthesis', 'tackle', 'tackle', 'tackle']});
  // drain and residual healing, which route through Battle#heal outside runMove
  await expectHeals('gigadrain', {bossMoves: ['gigadrain', 'tackle', 'tackle', 'tackle']});
  await expectHeals('leftovers', {bossMoves: ['tackle', 'tackle', 'tackle', 'tackle'], bossItem: 'leftovers', turns: 3});
  // a boss entering below full simulated HP used to silently emit a vanilla -heal
  await expectHeals('stale entity health', {bossMoves: ['recover', 'tackle', 'tackle', 'tackle'], bossHealth: 200});
  await expectPlayerHealingUntouched();
  console.log('Phase 32 raid healing validation: PASS');
})().catch(error => { console.error(error.message); process.exit(1); });
