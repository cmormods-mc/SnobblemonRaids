'use strict';
// The Raid Capture Protocol: a choice prompt, three stabilization pulses, a throw, and the result.
// A view only. The server scores every input off its own clock and rolls exactly once; this page animates
// a timing marker, reports presses, and shows what the server answers. It never decides an outcome.
const CHANNEL = 'cobbleraids.capture';
const W = 700, H = 420;
// CaptureUiLayout at its 700x420 maximum.
const L = {
  headerY: 14, contentY: 39, contentH: H - 146,
  infoX: 12, infoW: Math.max(92, Math.trunc(W / 3)),
  trackX: 18, trackY: H - 81, trackW: W - 36, buttonY: H - 38,
};
L.chamberX = L.infoX + L.infoW + 9;
L.chamberW = W - 12 - L.chamberX;
const REVEAL_MS = 1500;
const C = { cyan: '#37eeff', white: '#eafbff', navy: '#03283f', muted: '#9abacb', gold: '#ffb32f', zone: '#00cdeb' };

const $stage = document.getElementById('stage');
const icons = new Map();
const meter = document.createElement('canvas').getContext('2d');
let offer = null, state = 'WAITING_FOR_OFFER';
let pulseIndex = 0, scores = [0, 0, 0], stepStart = 0, pressSentAt = 0, waiting = false;
let result = null, resultAt = 0, attemptSentAt = 0, openedAt = 0, ticker = null, revealTimer = null;
let headingEl = null, countdownEl = null;   // the two texts the once-a-second tick rewrites

function send(message) {
  if (window.webgui && window.webgui.postToGame) window.webgui.postToGame({ channel: CHANNEL, ...message });
}
function scale() {
  const s = Math.max(1, Math.min(5, Math.floor(Math.min(innerWidth / (W + 16), innerHeight / (H + 16)))));
  document.documentElement.style.setProperty('--s', s);
  $stage.style.left = Math.round((innerWidth - W * s) / 2) + 'px';
  $stage.style.top = Math.round((innerHeight - H * s) / 2) + 'px';
}
addEventListener('resize', scale); scale();

// ---------------------------------------------------------------- text helpers (Monocraft, 8px)

const width8 = (s) => { meter.font = '100px Monocraft'; return meter.measureText(s).width * 0.08; };
function fit(s, w) {
  if (width8(s) <= w) return s;
  let cut = s.length;
  while (cut > 0 && width8(s.slice(0, cut) + '...') > w) cut--;
  return s.slice(0, cut) + '...';
}
function wrap(s, w) {
  const lines = []; let line = '';
  for (const word of s.split(' ')) {
    const next = line ? line + ' ' + word : word;
    if (width8(next) <= w || !line) line = next; else { lines.push(line); line = word; }
  }
  if (line) lines.push(line);
  return lines;
}

// ---------------------------------------------------------------- DOM helpers

function box(parent, cls, x, y, w, h, css) {
  const d = document.createElement('div');
  d.className = cls;
  d.style.cssText = 'left:' + x + 'px;top:' + y + 'px;width:' + w + 'px;height:' + h + 'px;' + (css || '');
  parent.appendChild(d);
  return d;
}
function panel(parent, x, y, w, h, fill, edge) {
  return box(parent, 'panel abs', x, y, w, h, '--fill:' + fill + ';--edge:' + edge);
}
function text(parent, s, x, y, w, color, center) {
  const d = document.createElement('div');
  d.className = 't';
  const fitted = fit(s, w);
  d.textContent = fitted;
  d.style.cssText = 'top:' + y + 'px;color:' + color + ';' +
    (center ? 'left:' + (x + (w - width8(fitted)) / 2) + 'px' : 'left:' + x + 'px');
  parent.appendChild(d);
  return d;
}
function wrapped(parent, s, x, y, w, color) {
  const lines = wrap(s, w);
  lines.forEach((l, i) => text(parent, l, x, y + i * 10, w + 4, color));
  return y + lines.length * 10;
}
function button(parent, x, y, w, label, enabled, onClick) {
  const b = document.createElement('button');
  b.className = 'act panel';
  b.style.cssText = 'left:' + x + 'px;top:' + y + 'px;width:' + w + 'px;--fill:' + C.navy + ';--edge:' + C.cyan;
  b.disabled = !enabled;
  const span = document.createElement('span');
  span.textContent = fit(label, w - 10);
  b.appendChild(span);
  b.addEventListener('click', onClick);
  parent.appendChild(b);
  return b;
}

// ---------------------------------------------------------------- static layer (built once)

const stat = document.createElement('div');
stat.style.cssText = 'position:absolute;left:0;top:0;width:' + W + 'px;height:' + H + 'px';
$stage.appendChild(stat);
const dyn = document.createElement('div');
dyn.style.cssText = 'position:absolute;left:0;top:0;width:' + W + 'px;height:' + H + 'px';
$stage.appendChild(dyn);

function buildStatic() {
  panel(stat, 0, 0, W, H, '#064578', '#008fff');
  panel(stat, 6, 6, W - 12, 26, C.navy, C.cyan);
  const footerTop = H - 13 - 21;
  const foot = Art.canvas(W, 30, (g) => Art.footer((x1, y1, x2, y2, c) => g(x1, y1 - footerTop, x2, y2 - footerTop, c), W, H));
  foot.style.cssText = 'left:0;top:' + footerTop + 'px';
  stat.appendChild(foot);
  panel(stat, L.infoX, L.contentY, L.infoW, L.contentH, C.navy, C.cyan);
  panel(stat, L.chamberX, L.contentY, L.chamberW, L.contentH, C.navy, C.cyan);
  const cw = L.chamberW - 10, ch = L.contentH - 10;
  const chamber = Art.canvas(cw, ch, (g, w, h) => Art.chamber(g, 0, 0, w, h));
  chamber.style.cssText = 'left:' + (L.chamberX + 5) + 'px;top:' + (L.contentY + 5) + 'px';
  stat.appendChild(chamber);
}
buildStatic();

// ---------------------------------------------------------------- the dynamic layer

const pct = (f) => (f * 100).toFixed(1) + '%';
const avg = () => (scores[0] + scores[1] + scores[2]) / 3;
const revealing = () => result && performance.now() - resultAt < REVEAL_MS;

function heading() {
  if (state === 'RESULT') return revealing() ? 'CAPTURE CHECK' : result.success ? 'CAPTURED!' : 'THE ECHO ESCAPED';
  const left = offer.details ? Math.max(0, offer.details.sequenceSeconds - Math.floor((performance.now() - attemptSentAt) / 1000)) : null;
  const tail = left === null ? '' : '  |  ' + left + 's';
  if (state === 'THROW') return 'THROW THE BALL' + tail;
  if (state === 'PULSE') return 'STABILIZE ECHO  ' + (pulseIndex + 1) + ' / 3' + tail;
  return 'RAID CAPTURE';
}

function render() {
  dyn.textContent = '';
  if (!offer) return;
  const d = dyn;
  headingEl = text(d, heading(), 17, L.headerY, W - 220, C.white);
  countdownEl = null;

  // Progress lamps, top right.
  const lampLabel = state === 'RESULT' ? 'COMPLETE' : state === 'THROW' ? 'THROW READY' : state === 'PULSE' ? 'STABILIZING' : 'ECHO READY';
  const completed = state === 'OFFER' ? 0 : state === 'PULSE' ? pulseIndex : 3;
  const right = W - 17, lw = width8(lampLabel);
  text(d, lampLabel, right - lw, L.headerY, lw + 4, C.cyan);
  for (let i = 0; i < 3; i++) {
    const x = right - lw - 42 + i * 11;
    box(d, 'abs', x, L.headerY, 7, 7, 'background:' + C.cyan);
    box(d, 'abs', x + 1, L.headerY + 1, 5, 5, 'background:' + (i < completed ? '#70f8ff' : '#001a30'));
  }

  drawChamber(d);
  drawMeter(d);
  drawInfo(d, completed);
  if (state === 'OFFER') drawOfferText(d); else if (state === 'RESULT') drawResult(d); else drawTiming(d);
  drawButtons(d);
}

function drawChamber(d) {
  const x = L.chamberX + 5, y = L.contentY + 5, w = L.chamberW - 10, h = L.contentH - 10;
  const clip = box(d, 'abs', x, y, w, h, 'overflow:hidden');
  const escaped = state === 'RESULT' && !revealing() && !result.success;
  const ball = (state === 'THROW' && waiting) || revealing();
  if (ball) {
    const size = Math.max(14, Math.min(30, Math.trunc(h / 3)));
    const c = Art.canvas(size, size, (g, sw) => Art.pokeball(g, sw));
    c.style.cssText = 'left:' + (Math.trunc(w / 2) - Math.trunc(size / 2)) + 'px;top:' + (Math.trunc(h / 2) - Math.trunc(size / 2)) + 'px';
    if (revealing()) c.className = 'shake';
    clip.appendChild(c);
  } else if (!escaped) {
    const url = offer.details && icons.get(offer.details.icon);
    if (url) {
      const size = Math.max(16, Math.min(Math.trunc(w * 2 / 3), Math.trunc(h * 3 / 4)));
      const sprite = box(clip, 'abs', Math.trunc((w - size) / 2), Math.trunc((h - size) / 2) + Math.trunc((size - size * 32 / 48) / 2), size, Math.round(size * 32 / 48),
        'background:url(' + url + ') no-repeat 0 0 / 100% 100%;image-rendering:pixelated');
      sprite.id = 'sprite';
    } else {
      text(clip, offer.details ? '?' : offer.speciesName, 8, Math.trunc(h / 2), w - 16, C.white, true);
    }
  }
}

function drawMeter(d) {
  const x = L.chamberX + L.chamberW - 20, y = L.contentY + Math.trunc(L.contentH / 5), h = Math.trunc(L.contentH * 3 / 5);
  panel(d, x, y, 10, h, '#001a30', C.cyan);
  const count = 12, filled = Math.round(Math.max(0, Math.min(1, avg())) * count);
  for (let i = 0; i < count; i++) {
    const top = y + 4 + Math.trunc(i * (h - 8) / count), bottom = y + 4 + Math.trunc((i + 1) * (h - 8) / count) - 1;
    if (bottom > top) box(d, 'abs', x + 3, top, 4, bottom - top, 'background:' + (i >= count - filled ? C.cyan : '#075385'));
  }
}

function drawInfo(d, completed) {
  const x = L.infoX + 9, y = L.contentY + 10, w = L.infoW - 18;
  text(d, (offer.shiny ? 'Shiny ' : '') + offer.speciesName, x, y, w, C.white);
  const offerView = state === 'OFFER';
  text(d, offer.tier.toUpperCase(), x, y + (offerView ? 15 : 14), w, C.cyan);
  const rp = offerView ? offer.rp + ' RP earned' : offer.rp + ' RP reserved';
  text(d, rp, x, y + (offerView ? 31 : 29), w, C.white);
  if (!offerView) {
    const cx = x + width8(rp) + 5;
    if (cx + 9 < x + w) { const c = Art.canvas(9, 14, (g) => Art.crystal(g)); c.style.cssText = 'left:' + cx + 'px;top:' + (y + 26) + 'px'; d.appendChild(c); }
  }

  if (offerView) {
    const odds = offer.details ? pct(offer.details.base) + ' - ' + pct(Math.min(1, offer.details.base + offer.details.stabCap + offer.details.throwCap)) : 'Server configured';
    text(d, 'Capture chance', x, y + 51, w, C.muted);
    text(d, odds, x, y + 64, w, C.cyan);
    wrapped(d, 'Three pulses. One throw. Timing improves your odds.', x, y + 89, w, C.white);
    scanner(d, x, y + 125, w, L.contentH - 145, 0);
    return;
  }
  let odds;
  if (result && !revealing()) odds = 'Final chance: ' + result.finalChance.toFixed(1) + '%';
  else if (!offer.details) odds = 'Server scored';
  else odds = pct(Math.min(1, offer.details.base + avg() * offer.details.stabCap)) + ' + throw bonus';
  text(d, odds, x, y + 48, w, C.cyan);
  for (let i = 0; i < 3; i++) {
    const state_ = i >= completed ? '--' : scores[i] >= 1 ? 'PERFECT' : scores[i] >= 0.5 ? 'GOOD' : 'MISSED';
    const color = i >= completed ? C.muted : scores[i] > 0 ? C.cyan : '#ffc46b';
    text(d, (i + 1) + '  ' + state_, x, y + 67 + i * 13, w, color);
  }
  const bottom = wrapped(d, 'Match the white marker to the center zone. Misses keep your base chance.', x, y + 115, w, C.white);
  scanner(d, x, bottom + 5, w, L.contentY + L.contentH - bottom - 14, state === 'PULSE' ? pulseIndex : 3);
}

// The targeting-bracket scanner: static grid and ring, then twelve progress dots and three boxes.
const scannerCache = new Map();
function scanner(d, x, y, w, h, completed) {
  if (h < 40 || w < 90) return;
  const key = w + 'x' + h;
  if (!scannerCache.has(key)) scannerCache.set(key, Art.canvas(w, h, (g, sw, sh) => Art.scannerStatic(g, sw, sh)));
  const c = scannerCache.get(key).cloneNode();
  c.getContext('2d').drawImage(scannerCache.get(key), 0, 0);
  c.style.cssText = 'left:' + x + 'px;top:' + y + 'px';
  d.appendChild(c);
  const gx = x + Math.trunc(w * 4 / 5), gy = y + Math.trunc(h / 3), gr = Math.min(14, Math.trunc(h / 5));
  for (let i = 0; i < 12; i++) {
    const a = (i * 30 - 90) * Math.PI / 180;
    const px = gx + Math.round(Math.cos(a) * gr), py = gy + Math.round(Math.sin(a) * gr);
    box(d, 'abs', px - 2, py - 2, 4, 4, 'background:' + (i < completed * 4 ? C.cyan : '#7294ac'));
  }
  const bw = Math.min(19, Math.trunc((Math.trunc(w / 2) - 7) / 3)), start = x + w - 3 * bw - 4;
  for (let i = 0; i < 3; i++) {
    const bx = start + i * bw, by = y + h - 21;
    panel(d, bx, by, bw - 2, 18, '#001a30', i < completed ? C.cyan : '#537c99');
    text(d, String(i + 1), bx + 2, by + 5, bw - 6, i < completed ? C.cyan : '#abc9dd', true);
  }
}

function drawOfferText(d) {
  wrapped(d, 'Caught: Pokemon replaces this raid\'s RP. Escaped: keep all ' + offer.rp + ' RP.', 16, H - 99, W - 32, C.white);
  countdownEl = text(d, 'Other loot is safe. Choose within ' + offerRemaining() + 's.', 16, H - 55, W - 32, C.cyan);
}

function drawTiming(d) {
  const prompt = waiting ? 'Waiting for server score...' : state === 'THROW' ? 'Time your throw at the center!' : 'Press SPACE or click Stabilize at the center';
  text(d, prompt, 12, H - 99, W - 24, C.white, true);
  const trackBox = panel(d, L.trackX - 3, L.trackY - 3, L.trackW + 6, 24, C.navy, '#129ec6');
  const timing = state === 'THROW' ? offer.throwT : offer.pulse;
  const zone = (ms) => Math.max(1, Math.round(L.trackW * (timing.travel <= 0 ? 0 : Math.max(0, Math.min(1, ms / timing.travel)))));
  const good = zone(timing.good), perfect = zone(timing.perfect);
  for (let i = 1; i < 16; i++) box(d, 'abs', L.trackX + Math.trunc(i * L.trackW / 16), L.trackY + 5, 1, 8, 'background:' + C.cyan);
  text(d, '<', L.trackX + 3, L.trackY + 5, 10, C.cyan);
  text(d, '>', L.trackX + L.trackW - 8, L.trackY + 5, 10, C.cyan);
  const center = L.trackX + Math.trunc(L.trackW / 2);
  box(d, 'abs', center - Math.trunc(good / 2), L.trackY, good, 18, 'background:' + C.gold);
  box(d, 'abs', center - Math.trunc(perfect / 2), L.trackY, perfect, 18, 'background:' + C.zone);
  box(d, 'abs', center - 1, L.trackY + 4, 2, 10, 'background:' + C.white);
  // The marker sweeps with a CSS animation on the compositor, started so it is already `elapsed` into its
  // cycle: no script runs per frame while it moves.
  const holder = box(d, 'abs', L.trackX - 1, L.trackY, 0, 0, '');
  const marker = document.createElement('div');
  marker.id = 'indicator';
  const elapsed = Math.max(0, performance.now() - stepStart);
  marker.style.cssText = '--travel:' + L.trackW + 'px;animation:sweep ' + timing.travel + 'ms linear infinite alternate;animation-delay:-' + (elapsed % (2 * timing.travel)) + 'ms';
  holder.appendChild(marker);
  text(d, 'GOLD: GOOD   |   CYAN CENTER: PERFECT', 12, L.trackY + 27, W - 24, C.muted, true);
}

function drawResult(d) {
  if (revealing()) { text(d, 'Checking the capture...', 12, L.trackY, W - 24, C.cyan, true); return; }
  const line = result.success
    ? (String(result.delivery).toUpperCase() === 'DELIVERED' ? 'Added to your party / PC.' : 'Captured! Delivery waits for party / PC space.')
    : 'You keep all ' + offer.rp + ' RP from this raid.';
  wrapped(d, line, 16, H - 99, W - 32, C.white);
  const detail = result.success
    ? 'Capture replaces this raid\'s ' + offer.rp + ' RP. Other loot is yours.'
    : 'Final chance ' + result.finalChance.toFixed(1) + '%. Other raid loot is yours.';
  wrapped(d, detail, 16, H - 68, W - 32, C.cyan);
}

function drawButtons(d) {
  if (state === 'OFFER') {
    const bw = Math.trunc((W - 34) / 2);
    button(d, 12, L.buttonY, bw, 'Attempt Capture', true, () => choose(true));
    button(d, 22 + bw, L.buttonY, bw, 'Keep ' + offer.rp + ' RP', true, () => choose(false));
    return;
  }
  const label = state === 'RESULT' ? (revealing() ? 'Checking...' : 'Close')
    : waiting ? 'Scoring...' : state === 'THROW' ? 'Throw Ball [SPACE]' : 'Stabilize [SPACE]';
  const enabled = state === 'RESULT' ? !revealing() : !waiting;
  const bw = Math.min(180, W - 32);
  button(d, Math.trunc((W - bw) / 2), L.buttonY, bw, label, enabled, act);
}

// ---------------------------------------------------------------- input and state

function offerRemaining() {
  return Math.max(0, offer.choiceSeconds - Math.floor((performance.now() - openedAt) / 1000));
}
function close() { if (window.webgui && window.webgui.closeGui) window.webgui.closeGui(); }

function choose(attempt) {
  if (state !== 'OFFER') return;
  send({ action: 'choice', attempt });
  if (!attempt) { close(); return; }
  state = 'PULSE'; pulseIndex = 0; waiting = false;
  // The server starts the first pulse when this message reaches it, and scores a press as the gap
  // between its own two receipts. Starting the marker when the message is SENT therefore matches the
  // server's phase exactly: the delay up is the same for both ends of that gap and cancels out.
  attemptSentAt = stepStart = performance.now();
  startTicker();
  render();
}

function act() {
  if (waiting || state === 'OFFER') return;
  if (state === 'RESULT') { if (!revealing()) close(); return; }
  waiting = true;
  pressSentAt = performance.now();
  if (state === 'PULSE') send({ action: 'pulse', index: pulseIndex }); else send({ action: 'throw' });
  render();
}

addEventListener('keydown', (e) => {
  if (e.code === 'Space' && !e.repeat) { e.preventDefault(); act(); }
});

// A once-a-second tick keeps the countdowns honest; nothing else runs while the marker moves.
function startTicker() {
  clearInterval(ticker);
  ticker = setInterval(() => {
    if (state === 'OFFER' && offerRemaining() === 0) { clearInterval(ticker); close(); return; }
    // Only the two countdown texts change; re-rendering would rebuild the marker mid-sweep.
    if (state === 'OFFER' && countdownEl) countdownEl.textContent = fit('Other loot is safe. Choose within ' + offerRemaining() + 's.', W - 32);
    else if ((state === 'PULSE' || state === 'THROW') && headingEl) headingEl.textContent = fit(heading(), W - 220);
  }, 1000);
}

addEventListener('webgui:capture_icons', (e) => {
  for (const [key, url] of Object.entries(e.detail || {})) icons.set(key, url);
  if (offer) render();
});
addEventListener('webgui:capture_offer', (e) => {
  offer = e.detail; state = 'OFFER'; result = null; scores = [0, 0, 0]; pulseIndex = 0; waiting = false;
  openedAt = performance.now();
  startTicker();
  render();
});
addEventListener('webgui:capture_pulse', (e) => {
  const p = e.detail;
  if (state !== 'PULSE' || p.index !== pulseIndex) return;
  scores[pulseIndex] = Math.max(0, Math.min(1, p.score));
  waiting = false;
  // The server started the next step when it received the press just acknowledged, so the next step's
  // marker is already that far along; see choose() for why the send time is the right anchor.
  stepStart = pressSentAt;
  if (pulseIndex === 2) state = 'THROW'; else pulseIndex++;
  render();
});
addEventListener('webgui:capture_result', (e) => {
  if (state === 'RESULT') return;
  result = e.detail; state = 'RESULT'; waiting = false; resultAt = performance.now();
  clearInterval(ticker);
  render();
  clearTimeout(revealTimer);
  revealTimer = setTimeout(render, REVEAL_MS + 20);
});

if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => render());
send({ action: 'ready' });
