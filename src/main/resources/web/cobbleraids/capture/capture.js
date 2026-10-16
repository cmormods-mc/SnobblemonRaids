'use strict';
// The Raid Capture Protocol: a choice prompt, three stabilization pulses, a throw, and the result.
// A view only. The server scores every input off its own clock and rolls exactly once; this page animates
// a timing marker, reports presses, and shows what the server answers. It never decides an outcome.
const CHANNEL = 'cobbleraids.capture';
const REVEAL_MS = 1500;

const $stage = document.getElementById('stage');
const icons = new Map();
let offer = null, state = 'WAITING_FOR_OFFER';
let pulseIndex = 0, scores = [0, 0, 0], stepStart = 0, pressSentAt = 0, waiting = false;
let result = null, resultAt = 0, attemptSentAt = 0, openedAt = 0, ticker = null, revealTimer = null;
let headingEl = null, countdownEl = null;   // the two texts the once-a-second tick rewrites

const send = (m) => RC.send(CHANNEL, m);
const el = RC.el;

const pct = (f) => (f * 100).toFixed(1) + '%';
const avg = () => (scores[0] + scores[1] + scores[2]) / 3;
const revealing = () => result && performance.now() - resultAt < REVEAL_MS;

const BALL = '<svg id="ball" viewBox="0 0 16 16"><circle cx="8" cy="8" r="7" fill="#eafbff" stroke="#001a30" stroke-width="1"/>' +
  '<path d="M1 8a7 7 0 0114 0z" fill="#ff5a6a" stroke="#001a30" stroke-width="1"/><path d="M1 8h14" stroke="#001a30" stroke-width="1.4"/>' +
  '<circle cx="8" cy="8" r="2.2" fill="#eafbff" stroke="#001a30" stroke-width="1.2"/></svg>';
const CRYSTAL = '<svg viewBox="0 0 9 14"><path d="M4.5 0L9 4.5 4.5 14 0 4.5z" fill="currentColor"/></svg>';

// ---------------------------------------------------------------- the whole page, rebuilt per step

function heading() {
  if (state === 'RESULT') return revealing() ? 'CAPTURE CHECK' : result.success ? 'CAPTURED!' : 'THE ECHO ESCAPED';
  const left = offer.details ? Math.max(0, offer.details.sequenceSeconds - Math.floor((performance.now() - attemptSentAt) / 1000)) : null;
  const tail = left === null ? '' : '  |  ' + left + 's';
  if (state === 'THROW') return 'THROW THE BALL' + tail;
  if (state === 'PULSE') return 'STABILIZE ECHO  ' + (pulseIndex + 1) + ' / 3' + tail;
  return 'RAID CAPTURE';
}

function render() {
  $stage.textContent = '';
  if (!offer) return;
  const completed = state === 'OFFER' ? 0 : state === 'PULSE' ? pulseIndex : 3;
  countdownEl = null;

  const head = el('header');
  head.id = 'head';
  headingEl = el('b', 'title', heading());
  const lampLabel = state === 'RESULT' ? 'COMPLETE' : state === 'THROW' ? 'THROW READY' : state === 'PULSE' ? 'STABILIZING' : 'ECHO READY';
  const lamps = el('div');
  lamps.id = 'lamps';
  for (let i = 0; i < 3; i++) lamps.appendChild(el('i', i < completed ? 'on' : ''));
  const stateText = el('span', '', lampLabel);
  stateText.id = 'state';
  head.append(headingEl, el('i', 'sp'), stateText, lamps);
  $stage.appendChild(head);

  $stage.appendChild(info(completed));
  $stage.appendChild(chamber());
  $stage.appendChild(meter());
  if (state === 'OFFER') offerText();
  else if (state === 'RESULT') resultText();
  else timing();
  actions();
}

function info(completed) {
  const p = el('aside', 'panel');
  p.id = 'info';
  p.appendChild(el('div', 'sp-name', (offer.shiny ? 'Shiny ' : '') + offer.speciesName));
  p.appendChild(el('div', 'tier', offer.tier.toUpperCase()));
  const offerView = state === 'OFFER';
  const rp = el('div', 'rp');
  rp.append(document.createTextNode(offer.rp + (offerView ? ' RP earned' : ' RP reserved')));
  if (!offerView) { const c = el('span'); c.innerHTML = CRYSTAL; rp.appendChild(c); }
  p.appendChild(rp);

  if (offerView) {
    const odds = offer.details ? pct(offer.details.base) + ' - ' + pct(Math.min(1, offer.details.base + offer.details.stabCap + offer.details.throwCap)) : 'Server configured';
    p.append(el('div', 'lbl', 'Capture chance'), el('div', 'odds', odds),
      el('div', 'note', 'Three pulses. One throw. Timing improves your odds.'));
    return p;
  }
  let odds;
  if (result && !revealing()) odds = 'Final chance: ' + result.finalChance.toFixed(1) + '%';
  else if (!offer.details) odds = 'Server scored';
  else odds = pct(Math.min(1, offer.details.base + avg() * offer.details.stabCap)) + ' + throw bonus';
  p.append(el('div', 'lbl', 'Capture chance'), el('div', 'odds', odds));
  const list = el('div', 'pulses');
  for (let i = 0; i < 3; i++) {
    const done = i < completed;
    const label = !done ? '--' : scores[i] >= 1 ? 'PERFECT' : scores[i] >= 0.5 ? 'GOOD' : 'MISSED';
    const row = el('div', done ? label.toLowerCase() : '');
    row.append(el('span', '', 'Pulse ' + (i + 1)), el('span', '', label));
    list.appendChild(row);
  }
  p.appendChild(list);
  if (state !== 'RESULT') p.appendChild(el('div', 'note', 'Match the white marker to the center zone. Misses keep your base chance.'));
  return p;
}

function chamber() {
  const c = el('section', 'panel');
  c.id = 'chamber';
  const escaped = state === 'RESULT' && !revealing() && !result.success;
  const ball = (state === 'THROW' && waiting) || revealing();
  if (ball) {
    const holder = el('div');
    holder.innerHTML = BALL;
    if (revealing()) holder.firstChild.classList.add('shake');
    c.appendChild(holder.firstChild);
  } else if (!escaped) {
    const url = offer.details && icons.get(offer.details.icon);
    if (url) {
      const sprite = el('div');
      sprite.id = 'sprite';
      sprite.style.backgroundImage = 'url(' + url + ')';
      c.appendChild(sprite);
    } else {
      c.appendChild(el('div', 'q', offer.details ? '?' : offer.speciesName));
    }
  }
  return c;
}

function meter() {
  const frag = document.createDocumentFragment();
  const label = el('div', '', 'ODDS');
  label.id = 'meterlbl';
  const m = el('div', 'panel');
  m.id = 'meter';
  const count = 12, filled = Math.round(Math.max(0, Math.min(1, avg())) * count);
  for (let i = 0; i < count; i++) m.appendChild(el('i', i < filled ? 'on' : ''));
  frag.append(label, m);
  return frag;
}

function offerText() {
  const outro = el('div');
  outro.id = 'outro';
  outro.appendChild(el('div', '', 'Caught: the Pokemon replaces this raid\'s RP. Escaped: keep all ' + offer.rp + ' RP.'));
  countdownEl = el('div', 'detail', 'Other loot is safe. Choose within ' + offerRemaining() + 's.');
  outro.appendChild(countdownEl);
  $stage.appendChild(outro);
}

function resultText() {
  const outro = el('div');
  outro.id = 'outro';
  if (revealing()) {
    outro.appendChild(el('div', 'detail', 'Checking the capture...'));
  } else {
    outro.appendChild(el('div', '', result.success
      ? (String(result.delivery).toUpperCase() === 'DELIVERED' ? 'Added to your party / PC.' : 'Captured! Delivery waits for party / PC space.')
      : 'You keep all ' + offer.rp + ' RP from this raid.'));
    outro.appendChild(el('div', 'detail', result.success
      ? 'Capture replaces this raid\'s ' + offer.rp + ' RP. Other loot is yours.'
      : 'Final chance ' + result.finalChance.toFixed(1) + '%. Other raid loot is yours.'));
  }
  $stage.appendChild(outro);
}

function timing() {
  const prompt = el('div', '', waiting ? 'Waiting for server score...' : state === 'THROW' ? 'Time your throw at the center!' : 'Press SPACE or click Stabilize at the center');
  prompt.id = 'prompt';
  $stage.appendChild(prompt);

  const track = el('div', 'panel');
  track.id = 'track';
  const zones = el('div', 'zones');
  const t = state === 'THROW' ? offer.throwT : offer.pulse;
  const share = (ms) => (t.travel <= 0 ? 0 : Math.max(0, Math.min(1, ms / t.travel))) * 100;
  for (let i = 1; i < 16; i++) { const tick = el('i', 'tick'); tick.style.left = i * 100 / 16 + '%'; zones.appendChild(tick); }
  const good = el('div', 'good'); good.style.left = '50%'; good.style.width = Math.max(0.4, share(t.good)) + '%';
  const perfect = el('div', 'perfect'); perfect.style.left = '50%'; perfect.style.width = Math.max(0.4, share(t.perfect)) + '%';
  zones.append(good, perfect, el('div', 'mid'));
  const marker = el('div');
  marker.id = 'indicator';
  zones.appendChild(marker);
  track.appendChild(zones);
  $stage.appendChild(track);
  $stage.appendChild(Object.assign(el('div', '', 'GOLD: GOOD   |   CYAN CENTER: PERFECT'), { id: 'legend' }));

  // The marker sweeps with a CSS animation on the compositor, started so it is already `elapsed` into its
  // cycle: no script runs per frame while it moves. The track is measured once it is in the page.
  const elapsed = Math.max(0, performance.now() - stepStart);
  marker.style.cssText = '--travel:' + zones.clientWidth + 'px;animation:sweep ' + t.travel + 'ms linear infinite alternate;animation-delay:-' + (elapsed % (2 * t.travel)) + 'ms';
}

function actions() {
  const holder = el('div');
  holder.id = 'actions';
  const add = (label, enabled, onClick, ghost, single) => {
    const b = el('button', 'btn' + (ghost ? ' ghost' : '') + (single ? ' single' : ''), label);
    b.disabled = !enabled;
    b.addEventListener('click', onClick);
    holder.appendChild(b);
  };
  if (state === 'OFFER') {
    add('Attempt Capture', true, () => choose(true));
    add('Keep ' + offer.rp + ' RP', true, () => choose(false), true);
  } else {
    const label = state === 'RESULT' ? (revealing() ? 'Checking...' : 'Close')
      : waiting ? 'Scoring...' : state === 'THROW' ? 'Throw Ball [SPACE]' : 'Stabilize [SPACE]';
    add(label, state === 'RESULT' ? !revealing() : !waiting, act, false, true);
  }
  $stage.appendChild(holder);
}

// ---------------------------------------------------------------- input and state

function offerRemaining() {
  return Math.max(0, offer.choiceSeconds - Math.floor((performance.now() - openedAt) / 1000));
}

function choose(attempt) {
  if (state !== 'OFFER') return;
  send({ action: 'choice', attempt });
  if (!attempt) { RC.close(); return; }
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
  if (state === 'RESULT') { if (!revealing()) RC.close(); return; }
  waiting = true;
  pressSentAt = performance.now();
  if (state === 'PULSE') send({ action: 'pulse', index: pulseIndex }); else send({ action: 'throw' });
  render();
}

addEventListener('keydown', (e) => {
  if (e.code === 'Space' && !e.repeat) { e.preventDefault(); act(); }
});
// The track is measured in pixels for the marker, so a resized window needs the step drawn again; the
// delay is computed from the step's start, so the marker stays in phase.
addEventListener('resize', () => { if (offer) render(); });

// A once-a-second tick keeps the countdowns honest; nothing else runs while the marker moves.
function startTicker() {
  clearInterval(ticker);
  ticker = setInterval(() => {
    if (state === 'OFFER' && offerRemaining() === 0) { clearInterval(ticker); RC.close(); return; }
    // Only the two countdown texts change; re-rendering would rebuild the marker mid-sweep.
    if (state === 'OFFER' && countdownEl) countdownEl.textContent = 'Other loot is safe. Choose within ' + offerRemaining() + 's.';
    else if ((state === 'PULSE' || state === 'THROW') && headingEl) headingEl.textContent = heading();
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

send({ action: 'ready' });
