// N mineflayer bots duel one World Boss on a throwaway server; samples /tick query before and during.
//   CONSOLE=<file the server reads commands from> LOG=<server log> node worldboss_duel_load.js N [maxSeconds]
// Needs mineflayer on NODE_PATH, the server in offline mode on port 25565 with Polymer (the bots answer its
// resource pack), world_boss.json enabled, and a boss that can be started from the console. Bots get
// fire resistance (an arena marker in the Nether can sit near lava) and one Garchomp each, are healed in
// each breather, and use fresh names every run so old parties do not force a switch prompt they cannot answer.
// BOTJS=<other bot script> keeps a per-bot log (bot_<name>.log) for debugging.
const {spawn, execSync} = require('child_process');
const fs = require('fs');
const CON = process.env.CONSOLE, LIVE = process.env.LOG;
if (!CON || !LIVE) { console.error('set CONSOLE and LOG'); process.exit(1); }
const N = +process.argv[2], MAXS = +(process.argv[3] || 420);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const cmd = (c) => fs.appendFileSync(CON, c + '\n');
const logLen = () => fs.statSync(LIVE).size;
function since(off) { const b = fs.readFileSync(LIVE); return b.subarray(off).toString('utf8'); }
async function tick() {
  const off = logLen(); cmd('tick query'); await sleep(1500);
  const t = since(off); const m = t.match(/Average time per tick: ([\d.]+)ms.*?P50: ([\d.]+)ms.*?P95: ([\d.]+)ms.*?P99: ([\d.]+)ms/s);
  return m ? {avg: +m[1], p95: +m[3], p99: +m[4]} : null;
}
function rss() { try { const pid = execSync("pgrep -f KnotServer | head -1").toString().trim(); return Math.round(+execSync(`ps -o rss= -p ${pid}`).toString() / 1024); } catch (e) { return null; } }
const load1 = () => +fs.readFileSync('/proc/loadavg', 'utf8').split(' ')[0];
const TAG = process.env.TAG || ('R' + Math.random().toString(36).slice(2, 5));
const bots = [];
const names = [...Array(N)].map((_, i) => `${TAG}${String(i).padStart(2, '0')}`);
function mk(name) {
  const p = spawn('node', [process.env.BOTJS || 'worldboss_duelbot.js', name, '127.0.0.1', '25565'], {cwd: __dirname, stdio: ['pipe', 'pipe', 'ignore']});
  const b = {name, p, ready: false, ended: false, duels: 0, wins: 0};
  p.stdout.on('data', (d) => d.toString().split('\n').forEach((l) => {
    if (process.env.BOTJS) fs.appendFileSync('bot_' + name + '.log', l + '\n');
    if (l.includes('READY')) b.ready = true;
    if (l.includes('BATTLE_INIT')) b.duels++;
    if (l.includes('BATTLE_END')) b.ends = (b.ends || 0) + 1;
    if (l.includes('Next duel in') && Date.now() - (b.healed || 0) > 20000) { b.healed = Date.now(); b.heals = (b.heals || 0) + 1; cmd(`pokeheal ${b.name}`); }
    if (/END |KICKED/.test(l) && !/BATTLE_END/.test(l)) { b.ended = true; b.why = l.trim().slice(0, 100); }
  }));
  p.on('exit', () => { b.ended = true; });
  return b;
}
const say = (b, t) => { try { b.p.stdin.write(`SAY ${t}\n`); } catch (e) {} };
(async () => {
  const out = {N};
  cmd('cobbleraids worldboss stop'); await sleep(1000);
  const ioff = logLen();
  for (const n of names) { const b = mk(n); bots.push(b); await sleep(700); }
  for (let i = 0; i < 90 && !bots.every((b) => b.ready || b.ended); i++) await sleep(1000);
  out.joined = bots.filter((b) => b.ready && !b.ended).length;
  await sleep(10000);
  const idle = []; for (let i = 0; i < 4; i++) { const t = await tick(); if (t) idle.push(t); await sleep(4000); }
  out.idle = {avg: mean(idle, 'avg'), p95: mean(idle, 'p95'), load1: load1(), rssMb: rss()};
  // arm and set up
  for (const b of bots) { b.p.stdin.write('FIGHT\n'); cmd(`execute as ${b.name} run givepokemon ${b.name} garchomp level=100`); cmd(`effect give ${b.name} minecraft:fire_resistance infinite 0 true`); }
  await sleep(2000);
  const boff = logLen();
  cmd('cobbleraids worldboss start'); await sleep(2500);
  let t0;
  names.forEach((n, i) => cmd(`execute as ${n} in minecraft:the_nether run tp ${n} ${20 + ((i % 8) - 4) * 4} 100 ${30 + Math.floor(i / 8) * 4}`));
  await sleep(2500);
  t0 = Date.now();
  for (const b of bots) { say(b, '/cr worldboss join'); await sleep(250); }
  const duel = []; let defeated = false, peakDuel = 0;
  while ((Date.now() - t0) / 1000 < MAXS) {
    await sleep(6000);
    const t = await tick();
    const off = logLen();
    cmd('cobbleraids worldboss status'); await sleep(800);
    const s = since(off); const pool = (s.match(/Pool (\d+) \/ (\d+)/) || []);
    const dn = +((s.match(/duelling now: (\d+)/) || [])[1] || 0); peakDuel = Math.max(peakDuel, dn);
    if (t) duel.push({...t, duelling: dn, pool: pool[1], at: Math.round((Date.now() - t0) / 1000)});
    // chain mode pauses when parties faint: heal everyone periodically (stands in for a Pokemon Center)
    if (/not running|No World Boss|nothing active/i.test(s) || !pool[1]) { defeated = true; break; }
  }
  out.secondsToEnd = Math.round((Date.now() - t0) / 1000);
  out.ended = defeated ? 'boss gone (defeated or expired)' : 'time cap';
  out.peakDuelsAtOnce = peakDuel;
  out.duelSamples = duel.length;
  out.duelAvgMs = mean(duel, 'avg'); out.duelP95Ms = mean(duel, 'p95'); out.duelWorstAvgMs = Math.max(...duel.map((d) => d.avg));
  out.duelWorstP99Ms = Math.max(...duel.map((d) => d.p99));
  out.load1 = load1(); out.rssMb = rss();
  out.timeline = duel.map((d) => `${d.at}s avg=${d.avg} p95=${d.p95} duelling=${d.duelling} pool=${d.pool}`);
  const log = since(boff);
  out.defeatedLines = (log.match(/defeated[^\n]*/gi) || []).slice(0, 3);
  out.errors = (log.match(/[^\n]*(ERROR|Exception|contained)[^\n]*cobbleraids[^\n]*|[^\n]*com\.cobbleraids[^\n]*Exception[^\n]*/gi) || []).slice(0, 8);
  out.paidLines = (log.match(/[^\n]*(paid|reward)[^\n]*/gi) || []).length;
  out.botsLost = bots.filter((b) => b.ended).map((b) => `${b.name}: ${b.why}`).slice(0, 10);
  out.totalDuelsStarted = bots.reduce((a, b) => a + b.duels, 0); out.totalDuelsEnded = bots.reduce((a, b) => a + (b.ends || 0), 0);
  console.log(JSON.stringify(out, null, 1));
  bots.forEach((b) => { try { b.p.stdin.write('QUIT\n'); } catch (e) {} });
  await sleep(3000); process.exit(0);
})();
function mean(a, k) { return a.length ? +(a.reduce((x, y) => x + y[k], 0) / a.length).toFixed(1) : null; }
