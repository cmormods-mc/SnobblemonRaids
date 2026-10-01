// A mineflayer player that can actually FIGHT in a Cobblemon raid battle, for the live scenarios in
// java_layer_test.py. bot.js only connects and idles; this one answers battle prompts.
//
// Commands arrive one per line on stdin; events leave one per line on stdout, prefixed "[name] ".
//   FIGHT            answer every battle_make_choice prompt from now on (arm this BEFORE the battle starts:
//                    the bot answers a prompt only if it arrives after FIGHT, and never re-prompts itself)
//   STOP             stop answering
//   MOVE a,b,c       move ids to use, cycled one per prompt, when the Pokemon knows them (else its first
//                    known move; a move the server rejects is never sent again)
//   DROP             tear the TCP connection down without a goodbye, like a crash or a cable pull
//   QUIT             a clean disconnect
//   SAY <text>       chat / run a command as this player
//
// Events: READY, KICKED <why>, END <why>, MSG <chat>, BATTLE_INIT, BATTLE_END, REQUEST moves=.., PROMPT,
//         SENT <move>, PAYLOAD <channel>
//
// The wire format was read out of Cobblemon 1.7.3's bytecode (see the project memory on live testing):
//   cobblemon:battle_select_actions = battleId UUID (16 bytes) + u8 count + per response:
//     u8 type ordinal (SWITCH=0, MOVE=1, DEFAULT=2) then, for MOVE, MC-string moveName, a nullable
//     string targetPnx and a nullable string gimmickID (each: bool present [+ string]).
//   The battle id is the first 16 bytes of the clientbound cobblemon:battle_initialize payload.
// DEFAULT (2) was seen to be silently ignored by the server, so this only ever sends MOVE.

const mineflayer = require('mineflayer');
const readline = require('readline');

const [, , username, host, port] = process.argv;
if (!username) {
  console.error('usage: node raidbot.js <username> [host] [port]');
  process.exit(1);
}

const tag = `[${username}]`;
const emit = (text) => console.log(`${tag} ${text}`);

const bot = mineflayer.createBot({
  host: host || '127.0.0.1',
  port: Number(port || 25565),
  username,
  version: '1.21.1',
  auth: 'offline',
  checkTimeoutInterval: 120 * 1000,
});

let battleId = null;
let fighting = false;
let preferred = [];          // move ids to use, in order, whenever the bot actually knows them
let preferredCursor = 0;
let known = [];              // the move ids the active Pokemon really has, from the last battle_queue_request
const refused = new Set();   // moves the server rejected this battle; never picked again

const varint = (value) => {
  const out = [];
  let n = value >>> 0;
  while (n > 0x7f) { out.push((n & 0x7f) | 0x80); n >>>= 7; }
  out.push(n);
  return Buffer.from(out);
};
const mcString = (text) => {
  const body = Buffer.from(text, 'utf8');
  return Buffer.concat([varint(body.length), body]);
};

function sendMove(moveName) {
  if (!battleId) { emit('NO_BATTLE_ID'); return; }
  const payload = Buffer.concat([
    battleId,
    Buffer.from([1]),            // one response
    Buffer.from([1]),            // type ordinal: MOVE
    mcString(moveName),
    Buffer.from([0]),            // targetPnx: absent
    Buffer.from([0]),            // gimmickID: absent
  ]);
  bot._client.write('custom_payload', {channel: 'cobblemon:battle_select_actions', data: payload});
  emit(`SENT ${moveName}`);
}

// battle_queue_request is binary, not JSON: 00, active count, move count, then per move an id string,
// a display-name string and 7 fixed bytes (pp, max pp, flags). Read from a live 1.7.3 server.
function parseMoves(buf) {
  let at = 3;
  const count = buf[2];
  const readString = () => {
    const length = buf[at++];
    if (!(length >= 1 && length <= 40) || at + length > buf.length) throw new Error('bad string');
    const text = buf.toString('latin1', at, at + length);
    at += length;
    return text;
  };
  const ids = [];
  try {
    for (let i = 0; i < count; i++) {
      const id = readString();
      readString();
      at += 7;
      if (!/^[a-z0-9]+$/.test(id)) throw new Error('bad id');
      ids.push(id);
    }
  } catch (err) {
    return [];
  }
  return ids;
}

/** The next move to send: the first preferred move the bot knows, else any known move that works. */
function chooseMove() {
  const usable = known.filter((id) => !refused.has(id));
  for (let tries = 0; tries < preferred.length; tries++) {
    const wanted = preferred[(preferredCursor + tries) % preferred.length];
    if (usable.includes(wanted)) {
      preferredCursor = (preferredCursor + tries + 1) % preferred.length;
      return wanted;
    }
  }
  return usable.find((id) => id !== 'lastresort') || usable[0] || known[0] || 'tackle';
}

bot.once('spawn', () => emit('READY'));
bot.on('message', (message) => {
  const text = message.toString().split('\n').join(' ');
  emit(`MSG ${text}`);
  // "Invalid action choice for X's Y: MoveActionResponse(moveName=foo, ..." -- stop re-sending it.
  const rejected = /Invalid action choice.*moveName=([a-z0-9]+)/.exec(text);
  if (rejected) refused.add(rejected[1]);
});
bot.on('kicked', (reason) => emit(`KICKED ${typeof reason === 'string' ? reason : JSON.stringify(reason)}`));
bot.on('end', (reason) => { emit(`END ${reason}`); process.exit(0); });
bot.on('error', (err) => emit(`ERROR ${err.message}`));
// Cobblemon's custom entity metadata makes the vanilla protocol parser noisy; it is not fatal here.
process.on('uncaughtException', (err) => emit(`UNCAUGHT ${String(err.message).split('\n')[0]}`));

bot._client.on('custom_payload', (packet) => {
  const channel = packet.channel;
  if (!channel || !channel.startsWith('cobblemon:battle')) return;
  const data = Buffer.from(packet.data || []);
  emit(`PAYLOAD ${channel} ${data.length}`);
  if (channel === 'cobblemon:battle_message') {
    emit(`BMSG ${data.toString('utf8').replace(/[^ -~]+/g, ' ').trim().slice(0, 240)}`);
  }
  if (channel === 'cobblemon:battle_initialize' && data.length >= 16) {
    battleId = Buffer.from(data.subarray(0, 16));
    emit('BATTLE_INIT');
  } else if (channel === 'cobblemon:battle_queue_request') {
    const parsed = parseMoves(data);
    if (parsed.length) known = parsed;
    emit(`REQUEST moves=${known.join(',')}`);
  } else if (channel === 'cobblemon:battle_make_choice') {
    emit('PROMPT');
    if (fighting) sendMove(chooseMove());
  } else if (channel === 'cobblemon:battle_end') {
    emit('BATTLE_END');
    battleId = null;
    refused.clear();
  }
});

// Is the packet stream still alive? A throw while parsing one of Cobblemon's custom entities can
// leave the client deaf, which looks exactly like "the server never sent the prompt".
let packetCount = 0;
let lastPacket = '';
bot._client.on('packet', (data, meta) => { packetCount++; lastPacket = meta.name; });
bot._client.on('error', (err) => emit(`CLIENT_ERROR ${String(err.message).split('\n')[0].slice(0, 160)}`));
setInterval(() => emit(`HEARTBEAT packets=${packetCount} last=${lastPacket}`), 5000).unref();

readline.createInterface({input: process.stdin}).on('line', (line) => {
  const [command, ...rest] = line.trim().split(' ');
  const argument = rest.join(' ');
  switch (command.toUpperCase()) {
    case 'FIGHT': fighting = true; emit('AUTOFIGHT on'); break;
    case 'STOP': fighting = false; emit('AUTOFIGHT off'); break;
    case 'MOVE': preferred = argument.split(',').map((m) => m.trim()).filter(Boolean); preferredCursor = 0; emit(`MOVES ${preferred.join(',')}`); break;
    case 'DROP': emit('DROPPING'); bot._client.socket.destroy(); break;
    case 'QUIT': bot.quit(); break;
    case 'SAY': bot.chat(argument); break;
    default: emit(`UNKNOWN_COMMAND ${command}`);
  }
});
