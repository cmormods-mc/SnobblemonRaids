'use strict';
// The raid shop page. A view only, like the native screen it replaces: it shows what the server last
// said, and a click sends an id. The server re-reads everything else, so nothing here is trusted.
const CHANNEL = 'cobbleraids.shop';
const root = document.documentElement;
const stage = document.getElementById('stage');
const grid = document.getElementById('grid');
const tip = document.getElementById('tip');
const icons = new Map();   // icon key -> data URL, delivered by webgui:shop_icons
let page = null;

function send(message) {
  if (window.webgui && window.webgui.postToGame) window.webgui.postToGame({ channel: CHANNEL, ...message });
}

function fit() {
  const s = Math.max(1, Math.min(5, Math.floor(Math.min(innerWidth / 251, innerHeight / 271))));
  root.style.setProperty('--s', s);
}
addEventListener('resize', fit);
fit();

const capitalise = (v) => v ? v.charAt(0).toUpperCase() + v.slice(1) : v;

function artFor(entry, pokemon) {
  const url = icons.get(entry.icon);
  return url ? 'url(' + url + ')' : 'none';
}

function render() {
  if (!page) return;
  const pokemon = page.entries.length > 0 && page.entries.every((e) => e.pokemon);
  const columns = pokemon ? 4 : 8;
  const cell = pokemon ? 45 : 22;
  stage.classList.toggle('pokemon', pokemon);
  stage.classList.toggle('items', !pokemon);
  stage.classList.toggle('single', page.pageCount <= 1);
  const suffix = page.pageCount > 1 ? ' ' + (page.pageIndex + 1) + '/' + page.pageCount : '';
  document.getElementById('heading').textContent = page.heading + suffix;
  document.getElementById('balance').textContent = page.balance + ' RP';

  grid.textContent = '';
  page.entries.forEach((entry, slot) => {
    const el = document.createElement('div');
    const affordable = page.balance >= entry.cost && !entry.soldOut;
    el.className = 'cell' + (affordable ? '' : ' unaffordable') + (entry.soldOut ? ' soldout' : '');
    el.style.cssText = 'width:' + cell + 'px;height:' + cell + 'px;left:' + (slot % columns) * (cell + 1) +
      'px;top:' + Math.floor(slot / columns) * (cell + 1) + 'px';
    el.dataset.icon = entry.icon || '';
    el.innerHTML = '<div class="art"></div><div class="dim"></div>';
    el.firstChild.style.backgroundImage = artFor(entry, pokemon);
    if (!pokemon && entry.count > 1) el.insertAdjacentHTML('beforeend', '<span class="count">' + entry.count + '</span>');
    if (entry.limit > 0) {
      el.insertAdjacentHTML('beforeend', '<span class="stock">' + Math.max(0, entry.remaining) + '</span>');
    }
    if (entry.personal && entry.canReroll) {
      const r = document.createElement('span');
      r.className = 'reroll';
      r.textContent = 'R';
      r.style.cssText = 'width:11px;height:11px;right:0;bottom:0;text-align:right;padding:2px 1px 0 0;box-sizing:border-box';
      r.addEventListener('click', (ev) => {
        ev.stopPropagation();
        send({ action: 'buy', page: page.pageIndex, id: 'reroll:' + entry.id.substring('personal:'.length) });
      });
      el.appendChild(r);
    }
    el.addEventListener('click', () => send({ action: 'buy', page: page.pageIndex, id: entry.id }));
    el.addEventListener('mouseenter', (ev) => showTip(entry, ev));
    el.addEventListener('mousemove', moveTip);
    el.addEventListener('mouseleave', hideTip);
    grid.appendChild(el);
  });
}

function line(cls, text) { return '<div class="c-' + cls + '">' + text.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])) + '</div>'; }

function tooltipFor(entry) {
  const out = [];
  if (entry.pokemon) {
    out.push(line('white', (entry.shiny ? 'Shiny ' : '') + capitalise(entry.species)));
    out.push(line('gray', 'Level ' + entry.level));
  } else {
    out.push(line('white', entry.name));
    out.push(line('gray', 'x' + entry.count));
  }
  if (entry.personal) {
    out.push(line('yellow', 'IV: ' + entry.ivPercent + '%'));
    out.push(line('yellow', 'EV: ' + entry.evPercent + '%'));
    if (entry.canReroll) out.push(line('purple', 'Reroll (risky): ' + entry.rerollCost + ' RP'));
  }
  if (entry.soldOut) {
    out.push(line('green', entry.limit === 1 ? 'Already purchased' : 'All ' + entry.limit + ' bought'));
    out.push(line('dgray', 'Resets daily'));
  } else {
    const short = entry.cost - page.balance;
    out.push(line(short > 0 ? 'red' : 'aqua', entry.cost + ' RP'));
    if (short > 0) out.push(line('dred', short + ' RP short'));
    if (entry.limit > 0) out.push(line('gray', entry.remaining + ' of ' + entry.limit + ' left'));
  }
  return out.join('');
}

function showTip(entry, ev) { tip.innerHTML = tooltipFor(entry); tip.hidden = false; moveTip(ev); }
function hideTip() { tip.hidden = true; }
function moveTip(ev) {
  const s = parseFloat(getComputedStyle(root).getPropertyValue('--s')) || 1;
  const w = tip.offsetWidth * s, h = tip.offsetHeight * s;
  let x = ev.clientX + 12 * s, y = ev.clientY - 12 * s;
  if (x + w > innerWidth) x = ev.clientX - 12 * s - w;
  if (y + h > innerHeight) y = innerHeight - h;
  tip.style.left = Math.max(0, x) + 'px';
  tip.style.top = Math.max(0, y) + 'px';
}

document.getElementById('prev').addEventListener('click', () => turn(-1));
document.getElementById('next').addEventListener('click', () => turn(1));
function turn(direction) {
  if (!page || page.pageCount <= 1) return;
  send({ action: 'turn', page: (page.pageIndex + direction + page.pageCount) % page.pageCount });
}

addEventListener('webgui:shop_icons', (e) => {
  for (const [key, url] of Object.entries(e.detail || {})) icons.set(key, url);
  if (page) grid.querySelectorAll('.cell').forEach((el) => {
    const url = icons.get(el.dataset.icon);
    if (url) el.firstChild.style.backgroundImage = 'url(' + url + ')';
  });
});
addEventListener('webgui:shop_page', (e) => { page = e.detail; hideTip(); render(); });

// The server answers with the first page; asking after load means it cannot arrive before we listen.
send({ action: 'ready' });
