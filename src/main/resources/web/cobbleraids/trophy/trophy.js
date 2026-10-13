'use strict';
// The trophy room page. A view only: every search, filter, sort and page turn is a request, and the
// server runs the real query and answers with the page it chose.
const CHANNEL = 'cobbleraids.trophy';
const COLUMNS = 3;
const CASE_X = [134, 615, 1070], CASE_W = [469, 444, 469];
const SHINY_LABELS = ['Shiny: All', 'Shiny only', 'Normal only'];
const SORT_LABELS = ['Newest', 'Oldest', 'Species', 'Best IV'];
const $ = (id) => document.getElementById(id);
const icons = new Map();
let page = null, tiers = [];
let query = '', tier = -1, shiny = 0, sort = 0, highlighted = 0;
let awaiting = false, debounce = null;

function send(message) {
  if (window.webgui && window.webgui.postToGame) window.webgui.postToGame({ channel: CHANNEL, ...message });
}
function fit() {
  const s = Math.min((innerWidth - 48) / 1672, (innerHeight - 48) / 941);
  document.documentElement.style.setProperty('--s', Math.max(0.2, s));
}
addEventListener('resize', fit); fit();

function request(index) {
  if (awaiting) return;
  clearTimeout(debounce);
  awaiting = true;
  render();
  send({ action: 'query', page: Math.max(0, index), query, tier: tier < 0 ? '' : tiers[tier].id, shiny, sort });
}

const title = (s) => s ? s.charAt(0).toUpperCase() + s.slice(1).replace(/_/g, ' ') : s;
const art = (key) => { const u = icons.get(key); return u ? 'url(' + u + ')' : 'none'; };
function div(cls, css, text) {
  const d = document.createElement('div');
  if (cls) d.className = cls;
  if (css) d.style.cssText = css;
  if (text !== undefined) d.textContent = text;
  return d;
}

function render() {
  if (!page) return;
  $('tier').textContent = tier < 0 ? 'All Tiers' : tiers[tier].name;
  $('shiny').textContent = SHINY_LABELS[shiny];
  $('sort').textContent = SORT_LABELS[sort];
  for (const id of ['tier', 'shiny', 'sort']) $(id).disabled = awaiting;
  $('prev').disabled = awaiting || page.pageIndex <= 0;
  $('next').disabled = awaiting || page.pageIndex + 1 >= page.pageCount;
  $('counts').textContent = page.total + ' TROPHIES | ' + page.shinyTotal + ' SHINY';
  $('pager').textContent = (page.pageIndex + 1) + ' / ' + page.pageCount;

  const cases = $('cases');
  cases.textContent = '';
  for (let i = 0; i < COLUMNS; i++) {
    const x = CASE_X[i], w = CASE_W[i], h = 501;
    const shell = div('case', 'left:' + x + 'px;width:' + w + 'px');
    const entry = !awaiting && i < page.entries.length ? page.entries[i] : null;
    if (!entry) {
      shell.appendChild(div('empty', 'left:' + w / 5 + 'px;top:' + h * 0.69 + 'px;width:' + w * 0.6 + 'px', awaiting ? 'Loading...' : 'Unclaimed'));
      const bottom = !awaiting && page.total > 0 && page.entries.length === 0 ? 'No matches' : 'Win a raid';
      shell.appendChild(div('empty', 'left:' + w / 5 + 'px;top:' + h * 0.82 + 'px;width:' + w * 0.6 + 'px;color:var(--white)', bottom));
    } else {
      const size = Math.min(w * 0.78, h * 0.52), sh = size * 32 / 48;
      const sprite = div('art', 'left:' + (w - size) / 2 + 'px;top:' + (h * 0.09 + (size - sh) / 2) + 'px;width:' + size + 'px;height:' + sh + 'px');
      sprite.style.backgroundImage = art(entry.icon);
      shell.appendChild(sprite);
      // No sprite for this species (Cobblemon Cards missing): say so rather than leaving the case blank.
      if (!entry.icon) shell.appendChild(div('empty', 'left:0;top:' + h * 0.3 + 'px;width:' + w + 'px;font-size:60px;line-height:60px', '?'));
      const plateX = w * 0.21, plateW = w * 0.57;
      shell.appendChild(div('name', 'left:' + plateX + 'px;top:' + h * 0.685 + 'px;width:' + plateW + 'px', title(entry.species)));
      const tagH = Math.max(20, h * 0.048);
      shell.appendChild(div('bar', 'left:' + plateX + 'px;top:' + h * 0.75 + 'px;width:' + plateW + 'px;height:' + tagH + 'px;background:' + entry.tierColor));
      shell.appendChild(div('tag', 'left:' + plateX + 'px;top:' + (h * 0.75 + (tagH - 17) / 2) + 'px;width:' + plateW + 'px', entry.tierName.toUpperCase()));
      shell.appendChild(div('shine', 'left:' + plateX + 'px;top:' + h * 0.83 + 'px;width:' + plateW + 'px;color:' + (entry.shiny ? '#ffde58' : 'var(--white)'), entry.shiny ? '* SHINY' : 'NORMAL'));
      shell.appendChild(div('ivev', 'left:11px;top:' + h * 0.916 + 'px;width:' + (w - 22) + 'px', 'IV ' + entry.iv + '% | EV ' + entry.ev + '%'));
      shell.addEventListener('mouseenter', (ev) => showTip(entry, ev));
      shell.addEventListener('mousemove', moveTip);
      shell.addEventListener('mouseleave', hideTip);
    }
    cases.appendChild(shell);
  }

  const thumbs = $('thumbs');
  thumbs.textContent = '';
  if (!awaiting) page.entries.forEach((entry, i) => {
    const t = div('thumb' + (i === highlighted ? ' sel' : ''), 'left:' + (226 + 207 * i) + 'px');
    const size = Math.min(173 - 12, 84 - 8), sh = size * 32 / 48;
    const sprite = div('art', 'left:' + (173 - size) / 2 + 'px;top:' + (84 - sh) / 2 + 'px;width:' + size + 'px;height:' + sh + 'px');
    sprite.style.backgroundImage = art(entry.icon);
    t.appendChild(sprite);
    t.addEventListener('click', () => {
      if (i >= COLUMNS) request(page.pageIndex + 1); else { highlighted = i; render(); }
    });
    thumbs.appendChild(t);
  });
}

const tip = $('tip');
function showTip(entry, ev) {
  const d = new Date(entry.firstDefeatedAt).toISOString().slice(0, 10);
  const lines = [title(entry.species) + ' - Level ' + entry.level, 'First defeat: ' + d,
    'Defeated ' + entry.times + ' time' + (entry.times === 1 ? '' : 's')];
  tip.textContent = '';
  for (const l of lines) tip.appendChild(div('', '', l));
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

$('tier').addEventListener('click', () => { tier = tier + 1 >= tiers.length ? -1 : tier + 1; request(0); });
$('shiny').addEventListener('click', () => { shiny = (shiny + 1) % SHINY_LABELS.length; request(0); });
$('sort').addEventListener('click', () => { sort = (sort + 1) % SORT_LABELS.length; request(0); });
$('prev').addEventListener('click', () => request(page.pageIndex - 1));
$('next').addEventListener('click', () => request(page.pageIndex + 1));
$('back').addEventListener('click', () => { if (window.webgui && window.webgui.closeGui) window.webgui.closeGui(); });
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
  page = e.detail; tiers = page.tiers || tiers; awaiting = false; highlighted = 0; hideTip();
  const focused = document.activeElement === $('search');
  render();
  if (focused) $('search').focus();
});

send({ action: 'ready' });
