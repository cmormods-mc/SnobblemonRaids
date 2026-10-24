'use strict';
// The raid shop page. A view only: it shows what the server last said, and a click sends an id.
// The server re-reads everything else, so nothing here is trusted.
const CHANNEL = 'cobbleraids.shop';
const $ = RC.$;
const grid = $('grid');
const tip = RC.tooltip();
const icons = new Map();   // icon key -> data URL, delivered by webgui:shop_icons
let page = null;
let deadline = 0;          // performance.now() at which the rotating page restocks; 0 = this page does not
let lastRefresh = 0;       // when we last asked for a fresh page because the clock ran out

const send = (m) => RC.send(CHANNEL, m);
const art = (key) => { const u = icons.get(key); return u ? 'url(' + u + ')' : 'none'; };

function render() {
  if (!page) return;
  const pokemon = page.entries.length > 0 && page.entries.every((e) => e.pokemon);
  grid.className = pokemon ? 'pokemon' : 'items';
  document.querySelector('.stage').classList.toggle('single', page.pageCount <= 1);
  $('crumb').textContent = '/ ' + page.heading;
  $('balance').textContent = RC.num(page.balance) + ' RP';
  $('where').textContent = 'PAGE ' + (page.pageIndex + 1) + ' OF ' + page.pageCount;

  grid.textContent = '';
  if (page.entries.length === 0) {
    const e = RC.el('div', 'empty', 'Nothing for sale right now.');
    e.style.gridColumn = '1 / -1';
    e.style.padding = '8cqw 0';
    grid.appendChild(e);
  }
  for (const entry of page.entries) grid.appendChild(cell(entry, pokemon));
}

// Counts down locally from the server's "time left", so a wrong client clock cannot skew it and the
// server is not polled. At zero it asks for the page again, at most every few seconds: the server
// rolls the new stock at the boundary, but a request that lands a moment early would just get the
// old stock back and must not turn into a tight loop.
function tick() {
  const chip = $('timer');
  if (!deadline) { chip.hidden = true; return; }
  const left = Math.max(0, deadline - performance.now());
  chip.hidden = false;
  if (left > 0) {
    const s = Math.ceil(left / 1000);
    const hms = [Math.floor(s / 3600), Math.floor(s / 60) % 60, s % 60]
      .map((n, i) => (i === 0 ? String(n) : String(n).padStart(2, '0'))).join(':');
    chip.textContent = 'NEW STOCK IN ' + hms;
  } else {
    chip.textContent = 'RESTOCKING…';
    if (page && performance.now() - lastRefresh > 3000) {
      lastRefresh = performance.now();
      send({ action: 'turn', page: page.pageIndex });
    }
  }
}
setInterval(tick, 1000);

function cell(entry, pokemon) {
  const affordable = page.balance >= entry.cost && !entry.soldOut;
  const el = RC.el('div', 'panel cell' + (affordable ? '' : ' dim') + (entry.soldOut ? ' soldout' : ''));
  el.dataset.icon = entry.icon || '';
  const image = RC.el('div', 'art');
  image.style.backgroundImage = art(entry.icon);
  el.appendChild(image);

  const price = entry.soldOut ? 'SOLD OUT' : RC.num(entry.cost) + ' RP';
  if (pokemon) {
    const body = RC.el('div', 'body');
    const name = RC.el('div', 'name' + (entry.shiny ? ' shiny' : ''), (entry.shiny ? '★ ' : '') + RC.title(entry.species));
    body.append(name, RC.el('div', 'meta', 'Level ' + entry.level));
    if (entry.personal) body.appendChild(RC.el('div', 'meta', 'IV ' + entry.ivPercent + '%  EV ' + entry.evPercent + '%'));
    // The price and the reroll button share a row, so a long price can never run under the button.
    const priceRow = RC.el('div', 'pricerow');
    priceRow.appendChild(RC.el('div', 'price', price));
    if (entry.personal && entry.canReroll) priceRow.appendChild(rerollButton(entry));
    body.appendChild(priceRow);
    el.appendChild(body);
  } else {
    if (entry.count > 1) el.appendChild(RC.el('span', 'count', '×' + entry.count));
    el.appendChild(RC.el('span', 'price', price));
  }
  if (entry.limit > 0 && !entry.soldOut) el.appendChild(RC.el('span', 'tag', Math.max(0, entry.remaining) + ' left'));

  el.addEventListener('click', () => send({ action: 'buy', page: page.pageIndex, id: entry.id }));
  tip.bind(el, () => tooltipFor(entry));
  return el;
}

function rerollButton(entry) {
  const r = RC.el('button', 'reroll', 'REROLL');
  r.addEventListener('click', (ev) => {
    ev.stopPropagation();
    send({ action: 'buy', page: page.pageIndex, id: 'reroll:' + entry.id.substring('personal:'.length) });
  });
  return r;
}

function tooltipFor(entry) {
  const out = [];
  if (entry.pokemon) {
    out.push([(entry.shiny ? 'Shiny ' : '') + RC.title(entry.species), '']);
    out.push(['Level ' + entry.level, 'mu']);
  } else {
    out.push([entry.name, '']);
    out.push(['×' + entry.count, 'mu']);
  }
  if (entry.personal) {
    out.push(['IV ' + entry.ivPercent + '%   EV ' + entry.evPercent + '%', 'hi']);
    if (entry.canReroll) out.push(['Reroll (risky): ' + entry.rerollCost + ' RP', 'hi']);
  }
  if (entry.soldOut) {
    out.push([entry.limit === 1 ? 'Already purchased' : 'All ' + entry.limit + ' bought', 'ok']);
    out.push([page.rotatesInMillis > 0 ? 'Back with the next restock' : 'Resets daily', 'mu']);
  } else {
    const short = entry.cost - page.balance;
    out.push([RC.num(entry.cost) + ' RP', short > 0 ? 'bad' : 'hi']);
    if (short > 0) out.push([RC.num(short) + ' RP short', 'bad']);
    if (entry.limit > 0) out.push([entry.remaining + ' of ' + entry.limit + ' left', 'mu']);
  }
  return out;
}

function turn(direction) {
  if (!page || page.pageCount <= 1) return;
  send({ action: 'turn', page: (page.pageIndex + direction + page.pageCount) % page.pageCount });
}
$('prev').addEventListener('click', () => turn(-1));
$('next').addEventListener('click', () => turn(1));
$('close').addEventListener('click', RC.close);

addEventListener('webgui:shop_icons', (e) => {
  for (const [key, url] of Object.entries(e.detail || {})) icons.set(key, url);
  grid.querySelectorAll('.cell').forEach((el) => {
    const url = icons.get(el.dataset.icon);
    if (url) el.querySelector('.art').style.backgroundImage = 'url(' + url + ')';
  });
});
addEventListener('webgui:shop_page', (e) => {
  page = e.detail;
  deadline = page.rotatesInMillis > 0 ? performance.now() + page.rotatesInMillis : 0;
  tip.hide();
  render();
  tick();
});

// The server answers with the first page; asking after load means it cannot arrive before we listen.
send({ action: 'ready' });
