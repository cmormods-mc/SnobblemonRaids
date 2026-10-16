'use strict';
// The raid reward reveal. Choose, wait for the server's roll, watch the reveal, see what was granted.
// A view only: this sends a choice id and shows what the server reports; it decides nothing.
const CHANNEL = 'cobbleraids.reveal';
const $ = RC.$;
const OPENING_MS = 600;
const icons = new Map();
const tip = RC.tooltip();
let offer = null, result = null, state = 'WAITING_FOR_OFFER';

const send = (m) => RC.send(CHANNEL, m);
const art = (key) => { const u = icons.get(key); return u ? 'url(' + u + ')' : 'none'; };
const grouped = (n) => Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');

// ---------------------------------------------------------------- summary

function kv(label, value, color) {
  const row = RC.el('div', 'kv');
  const v = RC.el('b', color ? 'tier' : '', value);
  if (color) v.style.setProperty('--tc', color);
  row.append(RC.el('span', '', label), v);
  return row;
}

function drawSide() {
  const side = $('side');
  side.textContent = '';
  if (!offer) return;
  const secs = Math.floor(offer.elapsedTicks / 20);
  const time = String(Math.floor(secs / 60)).padStart(2, '0') + ':' + String(secs % 60).padStart(2, '0');
  side.append(RC.el('h3', '', 'RAID SUMMARY'),
    kv('Boss defeated', offer.species, offer.tierColor), kv('Raid tier', offer.tierName, offer.tierColor), kv('Completion time', time),
    RC.el('h3', '', 'YOUR CONTRIBUTION'),
    kv('Damage dealt', offer.contribution.toFixed(1) + '%'), kv('Raiders', String(offer.participants)));
}

// ---------------------------------------------------------------- chamber

function drawBanner() {
  const banner = $('banner');
  banner.hidden = true;
  banner.textContent = '';
  if (!offer || !offer.banner) return;
  const plate = RC.el('div', 'panel plate');
  plate.style.setProperty('--cut', '0.9cqw');
  if (offer.boonIcon) {
    const img = RC.el('img');
    img.src = 'boon_' + offer.boonIcon + '.png';
    img.alt = '';
    plate.appendChild(img);
  }
  const skullLeft = RC.el('span'); skullLeft.innerHTML = RC.skull();
  const skullRight = RC.el('span'); skullRight.innerHTML = RC.skull();
  const name = RC.el('span', 'nm', offer.banner.name);
  plate.append(skullLeft, name);
  if (offer.banner.epithet) plate.append(RC.el('span', 'ep', ', ' + offer.banner.epithet));
  plate.append(skullRight);
  banner.appendChild(plate);
  banner.hidden = false;
}

function drawPrompt() {
  const p = $('prompt');
  p.textContent = '';
  if (state === 'CHOOSING') {
    p.append(RC.el('div', 'big', offer.choices.length > 1 ? 'CHOOSE YOUR REWARD' : 'REWARD READY'),
      RC.el('div', '', offer.choices.length > 1 ? 'Pick one to open the chamber' : 'Claim it to open the chamber'));
  } else if (state === 'WAITING' || state === 'OPENING') {
    p.append(RC.el('div', 'big', 'REVEALING...'));
  }
}

function slot(cls, build) {
  const s = RC.el('div', 'panel slot ' + cls);
  build(s);
  return s;
}

function drawResult() {
  const row = $('row');
  row.textContent = '';
  $('chamber').classList.toggle('done', state === 'RESULT');
  if (state !== 'RESULT' || !result) return;
  if (!result.success) {
    row.appendChild(RC.el('div', 'fail', 'Something went wrong. Check chat for details.'));
    return;
  }
  result.granted.forEach((g, i) => {
    const s = slot('', (el) => {
      const ico = RC.el('div', 'ico');
      ico.style.backgroundImage = art(g.icon);
      el.append(ico, RC.el('div', 'cap', g.name));
      if (g.amount > 1) el.appendChild(RC.el('span', 'cnt', '×' + g.amount));
    });
    s.style.animationDelay = i * 90 + 'ms';
    tip.bind(s, () => [[g.name, ''], g.amount > 1 ? ['×' + g.amount, 'mu'] : null].filter(Boolean));
    row.appendChild(s);
  });
  const chips = [];
  if (result.points > 0) chips.push(['rp', '+' + grouped(result.points), 'Raid Points', grouped(result.points) + ' Raid Points']);
  if (result.currency > 0) chips.push(['cd', '+' + grouped(result.currency), 'CobbleDollars', grouped(result.currency) + ' CobbleDollars']);
  chips.forEach(([cls, value, label, tipText], k) => {
    const s = slot('chip-slot ' + cls, (el) => el.append(RC.el('div', 'val', value), RC.el('div', 'cap', label)));
    s.style.animationDelay = (result.granted.length + k) * 90 + 'ms';
    tip.bind(s, () => [[tipText, '']]);
    row.appendChild(s);
  });
}

// ---------------------------------------------------------------- buttons

function drawButtons() {
  const holder = $('buttons');
  holder.textContent = '';
  if (state === 'CHOOSING' && offer) {
    const n = offer.choices.length;
    offer.choices.forEach((id) => {
      const b = RC.el('button', 'btn', n === 1 ? 'Claim Rewards' : id.replace(/[_.-]+/g, ' ').replace(/(^|\s)\w/g, (c) => c.toUpperCase()));
      b.addEventListener('click', () => choose(id));
      holder.appendChild(b);
    });
  } else if (state === 'RESULT') {
    const b = RC.el('button', 'btn', 'Close');
    b.addEventListener('click', RC.close);
    holder.appendChild(b);
  } else if (state === 'WAITING' || state === 'OPENING') {
    holder.appendChild(RC.el('span', '', ''));
  }
}

// ---------------------------------------------------------------- state

function choose(id) {
  if (state !== 'CHOOSING') return;
  state = 'WAITING';
  send({ action: 'choose', choice: id });
  render();
}

function burst() {
  const flash = $('flash');
  flash.hidden = false;
  flash.style.background = offer ? offer.tierColor : '#fff';
  flash.classList.remove('go'); void flash.offsetWidth; flash.classList.add('go');
  const sparks = $('sparks');
  sparks.textContent = '';
  const reach = document.querySelector('.stage').clientWidth * 0.07;
  for (let i = 0; i < 16; i++) {
    const angle = 2 * Math.PI * i / 16, speed = reach * (0.8 + (i % 3) * 0.35);
    const s = RC.el('div', 'spark');
    s.style.setProperty('--dx', Math.cos(angle) * speed + 'px');
    s.style.setProperty('--dy', Math.sin(angle) * speed + 'px');
    sparks.appendChild(s);
    void s.offsetWidth; s.classList.add('go');
  }
  setTimeout(() => { sparks.textContent = ''; flash.classList.remove('go'); flash.hidden = true; }, 700);
}

function render() {
  drawSide();
  drawBanner();
  drawPrompt();
  drawResult();
  drawButtons();
  $('hint').textContent = state === 'CHOOSING' ? 'Choose 1 of ' + offer.choices.length
    : state === 'RESULT' ? (result && result.hasMore ? 'More rewards waiting' : 'Claimed') : '';
  $('hint').hidden = !$('hint').textContent;
  $('crumb').textContent = offer ? '/ ' + offer.species + ' neutralised' : '';
}

addEventListener('webgui:reveal_icons', (e) => {
  for (const [key, url] of Object.entries(e.detail || {})) icons.set(key, url);
  if (state === 'RESULT') drawResult();
});
addEventListener('webgui:reveal_offer', (e) => {
  offer = e.detail; result = null; state = 'CHOOSING';
  render();
});
addEventListener('webgui:reveal_result', (e) => {
  if (!offer || state !== 'WAITING') return;
  result = e.detail; state = 'OPENING';
  render();
  burst();
  setTimeout(() => { state = 'RESULT'; render(); }, OPENING_MS);
});

send({ action: 'ready' });
