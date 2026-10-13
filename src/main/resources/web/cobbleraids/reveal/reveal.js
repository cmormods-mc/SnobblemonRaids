'use strict';
// The raid reward reveal. Choose, wait for the server's roll, watch the reveal, see what was granted.
// A view only: this sends a choice id and shows what the server reports; it decides nothing.
const CHANNEL = 'cobbleraids.reveal';
const $ = (id) => document.getElementById(id);
const CHAMBER = { x: 420, y: 112, w: 1172, h: 675 };
const FOOTER = { x: 430, y: 787, w: 1150, h: 78 };
const BUTTON = { x: 760, y: 792, w: 432, h: 68 };
const OPENING_MS = 600;
const PAD = 24;
// The native screen drew at a 640-wide panel, 0.3828 of the 1672 art; its font scales are in that space.
const NATIVE = 640 / 1672;
const icons = new Map();
let offer = null, result = null, state = 'WAITING_FOR_OFFER', fontsReady = false;

const canvas = document.createElement('canvas').getContext('2d');
function ratio(text) {            // text width in ems of the page font
  canvas.font = '100px Monocraft';
  return Math.max(0.01, canvas.measureText(text).width / 100);
}
function fit(text, wanted, available) { return Math.min(wanted, available / ratio(text)); }

function send(message) {
  if (window.webgui && window.webgui.postToGame) window.webgui.postToGame({ channel: CHANNEL, ...message });
}
function scale() {
  const s = Math.max(0.2, Math.min((innerWidth - 24) / 1672, (innerHeight - 24) / 941));
  const stage = $('stage');
  document.documentElement.style.setProperty('--s', s);
  stage.style.left = Math.round((innerWidth - 1672 * s) / 2) + 'px';
  stage.style.top = Math.round((innerHeight - 941 * s) / 2) + 'px';
}
addEventListener('resize', scale); scale();

function el(tag, css, text, cls) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (css) e.style.cssText = css;
  if (text !== undefined) e.textContent = text;
  return e;
}
const art = (key) => { const u = icons.get(key); return u ? 'url(' + u + ')' : 'none'; };
const grouped = (n) => Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');

// ---------------------------------------------------------------- sidebar

function label(text, y, scaleOf, color) {
  const size = fit(text, 8 * scaleOf, 313 - 2 * PAD);
  return el('div', 'top:' + y + 'px;font-size:' + size + 'px;line-height:' + size * 9 / 8 + 'px;color:' + color, text, 'lab');
}

function drawSidebar() {
  const side = $('side');
  side.textContent = '';
  if (!offer) return;
  const HEAD = '#28f7ff', SUB = '#d0fbff';
  const secs = Math.floor(offer.elapsedTicks / 20);
  const time = String(Math.floor(secs / 60)).padStart(2, '0') + ':' + String(secs % 60).padStart(2, '0');
  const rows = [
    [offer.species ? 'RAID SUMMARY' : '', 20, 2.2, HEAD],
    ['Boss Defeated', 67, 2.2, SUB], ['Raid Tier', 154, 2.2, SUB], ['Completion Time', 233, 2.2, SUB],
    ['YOUR CONTRIBUTION', 325, 2.2, HEAD], ['Damage Dealt', 373, 1.9, SUB], ['Raid Participants', 451, 1.9, SUB],
    [offer.species, 106, 2.5, offer.tierColor], [offer.tierName, 189, 2.5, offer.tierColor], [time, 271, 2.5, '#fff'],
    [offer.contribution.toFixed(1) + '%', 404, 2.5, '#fff'], [String(offer.participants), 478, 2.5, '#fff'],
  ];
  for (const [text, y, sc, color] of rows) if (text) side.appendChild(label(text, y, sc, color));
}

// ---------------------------------------------------------------- chamber

function drawBanner() {
  const banner = $('banner');
  banner.hidden = true;
  banner.textContent = '';
  if (!offer || !offer.banner) return;
  const parts = [['☠ ', '#ff5555'], [offer.banner.name, '#ffaa00']];
  if (offer.banner.epithet) parts.push([', ' + offer.banner.epithet, '#ff5555']);
  parts.push([' ☠', '#ff5555']);
  const full = parts.map((p) => p[0]).join('');
  const size = fit(full, 2.0 / NATIVE * 8, CHAMBER.w * 0.8);
  const lineH = size * 9 / 8, pad = size * 3 / 8;
  const icon = offer.boonIcon ? lineH : 0, gap = offer.boonIcon ? pad : 0;
  const textW = ratio(full) * size;
  const total = textW + icon + gap;
  const left = CHAMBER.w / 2 - total / 2, top = 30;
  banner.appendChild(el('div', 'left:' + (left - pad) + 'px;top:' + (top - pad) + 'px;width:' + (total + 2 * pad) + 'px;height:' + (lineH + pad) + 'px', undefined, 'plate'));
  if (offer.boonIcon) {
    const boon = el('img', 'left:' + left + 'px;top:' + top + 'px;width:' + icon + 'px;height:' + icon + 'px');
    boon.src = 'boon_' + offer.boonIcon + '.png';
    boon.id = 'boonImg';
    banner.appendChild(boon);
  }
  const text = el('div', 'left:' + (left + icon + gap) + 'px;top:' + top + 'px;font-size:' + size + 'px;line-height:' + lineH + 'px;white-space:nowrap;font-weight:bold;text-shadow:2px 2px 0 #3e3e3e');
  text.style.position = 'absolute';
  for (const [t, c] of parts) { const s = el('span', 'color:' + c, t); text.appendChild(s); }
  banner.appendChild(text);
  banner.hidden = false;
}

// A port of RewardSlotLayout: a row of slots on the chamber floor, shrunk by stepping down to fit.
function slotLayout(count) {
  if (count <= 0) return null;
  let cell = Math.max(5, Math.round(CHAMBER.h * 0.16));
  let gap = Math.max(1, Math.round(cell * 0.28));
  while (count * cell + (count - 1) * gap > CHAMBER.w && cell > 5) { cell--; gap = Math.max(1, Math.round(cell * 0.28)); }
  const total = count * cell + (count - 1) * gap;
  return { cell, gap, x: (CHAMBER.w - total) / 2, y: CHAMBER.h - Math.round(CHAMBER.h * 0.17) - cell };
}

function drawResult() {
  const row = $('row'), center = $('center');
  row.textContent = '';
  center.hidden = true;
  if (state !== 'RESULT' || !result) return;
  if (result.success) {
    const first = result.granted.length ? result.granted[0].icon : 'i:cobblemon:poke_ball';
    center.style.backgroundImage = art(first);
    center.hidden = false;
  }
  if (!result.success) {
    row.appendChild(el('div', 'left:0;top:' + CHAMBER.h * 0.4 + 'px;width:' + CHAMBER.w + 'px;text-align:center;color:#ff5555;font-size:21px',
      'Something went wrong. Check chat for details.'));
    return;
  }
  const chips = [];
  if (result.points > 0) chips.push({ text: '+' + grouped(result.points) + ' RP', fill: '#062630d2', edge: '#4fd6e0e6', color: '#8fe8f2', tip: grouped(result.points) + ' Raid Points', tipColor: '#55ffff' });
  if (result.currency > 0) chips.push({ text: '+' + grouped(result.currency), fill: '#2e2206d2', edge: '#f0be46e6', color: '#ffd666', tip: grouped(result.currency) + ' CobbleDollars', tipColor: '#ffaa00' });
  const count = result.granted.length + chips.length;
  const lay = slotLayout(count);
  if (!lay) return;
  const border = Math.max(2, Math.round(lay.cell / 40));
  const place = (i, fill, edge) => {
    const slot = el('div', 'left:' + (lay.x + i * (lay.cell + lay.gap)) + 'px;top:' + lay.y + 'px;width:' + lay.cell + 'px;height:' + lay.cell +
      'px;background:' + fill + ';box-shadow:inset 0 0 0 ' + border + 'px ' + edge, undefined, 'slot');
    row.appendChild(slot);
    return slot;
  };
  result.granted.forEach((g, i) => {
    const slot = place(i, '#081828be', '#5ed6ffdc');
    const size = lay.cell * 0.74, inset = (lay.cell - size) / 2;
    slot.appendChild(el('div', 'left:' + inset + 'px;top:' + inset + 'px;width:' + size + 'px;height:' + size + 'px;background-image:' + art(g.icon), undefined, 'ico'));
    if (g.amount > 1) {
      const f = 8 * size / 16;
      slot.appendChild(el('div', 'right:' + inset + 'px;bottom:' + inset + 'px;font-size:' + f + 'px;line-height:' + f + 'px', String(g.amount), 'cnt'));
    }
    slot.addEventListener('mouseenter', (ev) => showTip([[g.name, '#fff'], g.amount > 1 ? ['x' + g.amount, '#aaa'] : null], ev));
    slot.addEventListener('mousemove', moveTip);
    slot.addEventListener('mouseleave', hideTip);
  });
  chips.forEach((c, k) => {
    const slot = place(result.granted.length + k, c.fill, c.edge);
    const size = fit(c.text, 21, lay.cell - 10);
    slot.appendChild(el('div', 'top:' + (lay.cell - size * 9 / 8) / 2 + 'px;font-size:' + size + 'px;line-height:' + size * 9 / 8 + 'px;color:' + c.color, c.text, 'chip'));
    slot.addEventListener('mouseenter', (ev) => showTip([[c.tip, c.tipColor]], ev));
    slot.addEventListener('mousemove', moveTip);
    slot.addEventListener('mouseleave', hideTip);
  });
}

// ---------------------------------------------------------------- footer, buttons, status

function drawButtons() {
  const holder = $('buttons');
  holder.textContent = '';
  if (state === 'CHOOSING' && offer) {
    const n = offer.choices.length;
    const g = Math.round(12 / NATIVE);     // the native 12-pixel gap, in stage pixels
    const bw = Math.min(BUTTON.w, (FOOTER.w - g * (n - 1)) / Math.max(1, n));
    const bh = Math.max(1, Math.round(bw * BUTTON.h / BUTTON.w));
    const rowW = bw * n + g * Math.max(0, n - 1);
    const centerX = n === 1 ? BUTTON.x + BUTTON.w / 2 : CHAMBER.x + CHAMBER.w / 2;
    const startX = centerX - rowW / 2;
    const y = Math.round(FOOTER.y + (FOOTER.h - bh) / 2);
    offer.choices.forEach((id, i) => holder.appendChild(button(startX + i * (bw + g), y, bw, bh, n === 1 ? 'Claim Rewards' : id, () => choose(id))));
  } else if (state === 'RESULT') {
    holder.appendChild(button(BUTTON.x, BUTTON.y, BUTTON.w, BUTTON.h, 'Close', () => { if (window.webgui && window.webgui.closeGui) window.webgui.closeGui(); }));
  }
}

function button(x, y, w, h, text, onClick) {
  const b = el('button', 'left:' + x + 'px;top:' + y + 'px;width:' + w + 'px;height:' + h + 'px', undefined, 'btn');
  const size = Math.min(h * 4.2 / 83 * 8, fit(text, 1e9, Math.max(1, w - 12 / NATIVE)));
  b.appendChild(el('span', 'font-size:' + size + 'px;line-height:' + h + 'px', text));
  b.addEventListener('click', onClick);
  return b;
}

function drawStatus() {
  const s = $('status');
  s.hidden = !(state === 'WAITING' || state === 'OPENING');
  s.textContent = state === 'WAITING' ? 'Revealing...' : '...';
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
  for (let i = 0; i < 16; i++) {
    const angle = 2 * Math.PI * i / 16, speed = (40 + (i % 3) * 15) / NATIVE;
    const s = el('div', '--dx:' + Math.cos(angle) * speed + 'px;--dy:' + Math.sin(angle) * speed + 'px', undefined, 'spark');
    sparks.appendChild(s);
    void s.offsetWidth; s.classList.add('go');
  }
  setTimeout(() => { sparks.textContent = ''; $('flash').classList.remove('go'); $('flash').hidden = true; }, 700);
}

function render() {
  drawSidebar();
  drawBanner();
  drawResult();
  drawButtons();
  drawStatus();
}

const tip = $('tip');
function showTip(lines, ev) {
  tip.textContent = '';
  for (const l of lines) if (l) tip.appendChild(el('div', 'color:' + l[1], l[0]));
  tip.hidden = false; moveTip(ev);
}
function hideTip() { tip.hidden = true; }
function moveTip(ev) {
  const w = tip.offsetWidth, h = tip.offsetHeight;
  let x = ev.clientX + 14, y = ev.clientY - 14;
  if (x + w > innerWidth) x = ev.clientX - 14 - w;
  if (y + h > innerHeight) y = innerHeight - h;
  tip.style.left = Math.max(0, x) + 'px'; tip.style.top = Math.max(0, y) + 'px';
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

// Text is measured in the page font, so lay out again once it has loaded.
if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { fontsReady = true; render(); });
send({ action: 'ready' });
