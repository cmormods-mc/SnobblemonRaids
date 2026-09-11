// A single offline-mode player for the multiplayer load test.
//
// Deliberately minimal: it connects, reports readiness on stdout, and stays alive. Everything the
// test actually drives -- parties, teleports, joins -- goes through RCON as the console, because a
// mineflayer client's view of a Cobblemon entity is not dependable enough to build assertions on.
// See validation/smoke/README.md.
const mineflayer = require('mineflayer');

const [, , username, host, port] = process.argv;
if (!username) {
  console.error('usage: node bot.js <username> [host] [port]');
  process.exit(1);
}

const bot = mineflayer.createBot({
  host: host || '127.0.0.1',
  port: Number(port || 25565),
  username,
  version: '1.21.1',
  auth: 'offline',
  // Cobblemon's custom entity metadata makes vanilla-protocol parsing noisy; the test does not read
  // the world model, so the errors below are logged and ignored rather than treated as failures.
  checkTimeoutInterval: 120 * 1000,
});

const tag = `[${username}]`;
bot.on('spawn', () => console.log(`${tag} READY`));
bot.on('kicked', (reason) => console.log(`${tag} KICKED ${reason}`));
bot.on('end', (reason) => console.log(`${tag} END ${reason}`));
bot.on('error', (err) => console.log(`${tag} ERROR ${err.message}`));
// PartialReadError from Cobblemon metadata is expected and not fatal to this test.
process.on('uncaughtException', (err) => console.log(`${tag} UNCAUGHT ${err.message}`));
