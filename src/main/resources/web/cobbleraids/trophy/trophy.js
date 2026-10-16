'use strict';
// The trophy room page. A view only: every search, filter, sort and page turn is a request, and the
// server runs the real query and answers with the page it chose.
const CHANNEL = 'cobbleraids.trophy';
const COLUMNS = 3;
const SHINY_LABELS = ['Shiny: all', 'Shiny only', 'Normal only'];
const SORT_LABELS = ['Newest', 'Oldest', 'Species', 'Best IV'];
const $ = RC.$;
const icons = new Map();
let page = null, tiers = [];
let query = '', tier = -1, shiny = 0, sort = 0;
let awaiting = false, debounce = null;

const send = (m) => RC.send(CHANNEL, m);
const art = (key) => { const u = icons.get(key); return u ? 'url(' + u + ')' : 'none'; };

function request(index) {
  if (awaiting) return;
  clearTimeout(debounce);
  awaiting = true;
  render();
  send({ action: 'query', page: Math.max(0, index), query, tier: tier < 0 ? '' : tiers[tier].id, shiny, sort });
}

function statRow(label, percent) {
  const frag = document.createDocumentFragment();
  frag.append(RC.el('span', '', label));
  const bar = RC.el('span', 'bar');
  const fill = document.createElement('i');
  fill.style.width = Math.max(0, Math.min(100, percent)) + '%';
  bar.appendChild(fill);
  const wrap = RC.el('span');
  wrap.style.display = 'flex';
  wrap.style.alignItems = 'center';
  wrap.style.gap = '0.8cqw';
  bar.style.flex = '1';
  wrap.append(bar, Object.assign(RC.el('b', '', percent + '%'), {}));
  frag.append(wrap);
  return frag;
}

function trophy(entry) {
  const el = RC.el('div', 'panel case');
  el.style.setProperty('--tc', entry.tierColor);
  const box = RC.el('div', 'stagebox');
  if (entry.icon) {
    const sprite = RC.el('div', 'sprite');
    sprite.style.backgroundImage = art(entry.icon);
    box.appendChild(sprite);
  } else {
    // No sprite for this species (Cobblemon Cards missing): say so rather than leaving the case blank.
    box.appendChild(RC.el('div', 'noart', '?'));
  }
  box.appendChild(RC.el('span', 'lvl', 'LEVEL ' + entry.level));
  const tags = RC.el('div', 'tags');
  tags.append(RC.el('span', 'tier', entry.tierName.toUpperCase()),
    RC.el('span', 'shine' + (entry.shiny ? ' on' : ''), entry.shiny ? '★ SHINY' : 'NORMAL'));
  const stats = RC.el('div', 'stats');
  stats.append(statRow('IV', entry.iv), statRow('EV', entry.ev));
  const when = RC.el('div', 'when');
  when.append(RC.el('span', '', 'First ' + RC.date(entry.firstDefeatedAt)),
    RC.el('span', '', 'Beaten ×' + entry.times));
  el.append(box, RC.el('div', 'name', RC.title(entry.species)), tags, stats, when);
  return el;
}

function blank(index) {
  const el = RC.el('div', 'panel case blank');
  const none = !awaiting && page.total > 0 && page.entries.length === 0;
  el.append(RC.el('b', '', awaiting ? 'Loading...' : 'Unclaimed'),
    RC.el('span', '', awaiting ? '' : none ? 'No matches' : index === 0 && page.total === 0 ? 'Win a raid' : ''));
  return el;
}

function render() {
  if (!page) return;
  $('tier').textContent = tier < 0 ? 'All tiers' : tiers[tier].name;
  $('shiny').textContent = SHINY_LABELS[shiny];
  $('sort').textContent = SORT_LABELS[sort];
  for (const id of ['tier', 'shiny', 'sort']) $(id).disabled = awaiting;
  $('prev').disabled = awaiting || page.pageIndex <= 0;
  $('next').disabled = awaiting || page.pageIndex + 1 >= page.pageCount;
  $('crumb').textContent = '/ ' + page.total + ' trophies  ·  ' + page.shinyTotal + ' shiny';
  $('where').textContent = 'PAGE ' + (page.pageIndex + 1) + ' OF ' + page.pageCount;

  const cases = $('cases');
  cases.textContent = '';
  for (let i = 0; i < COLUMNS; i++) {
    const entry = !awaiting && i < page.entries.length ? page.entries[i] : null;
    cases.appendChild(entry ? trophy(entry) : blank(i));
  }
}

$('tier').addEventListener('click', () => { tier = tier + 1 >= tiers.length ? -1 : tier + 1; request(0); });
$('shiny').addEventListener('click', () => { shiny = (shiny + 1) % SHINY_LABELS.length; request(0); });
$('sort').addEventListener('click', () => { sort = (sort + 1) % SORT_LABELS.length; request(0); });
$('prev').addEventListener('click', () => request(page.pageIndex - 1));
$('next').addEventListener('click', () => request(page.pageIndex + 1));
$('close').addEventListener('click', RC.close);
$('search').addEventListener('input', (e) => {
  query = e.target.value;
  clearTimeout(debounce);
  // Debounced: a name typed at normal speed would otherwise be a dozen requests for a result nobody reads.
  debounce = setTimeout(() => { if (awaiting) debounce = setTimeout(() => request(0), 250); else request(0); }, 400);
});

addEventListener('webgui:trophy_icons', (e) => {
  for (const [key, url] of Object.entries(e.detail || {})) icons.set(key, url);
  if (page && !awaiting) render();
});
addEventListener('webgui:trophy_page', (e) => {
  page = e.detail; tiers = page.tiers || tiers; awaiting = false;
  const focused = document.activeElement === $('search');
  render();
  if (focused) $('search').focus();
});

send({ action: 'ready' });
