'use strict';
// The Hall of Legends page. A view only: the server filters and paginates, this shows what it sent.
const CHANNEL = 'cobbleraids.legends';
const $ = RC.$;
let page = null, tiers = [];
let mine = false, tier = -1, awaiting = false;

const send = (m) => RC.send(CHANNEL, m);

function request(index) {
  if (awaiting) return;
  awaiting = true;
  render();
  send({ action: 'query', page: Math.max(0, index), mine, tier: tier < 0 ? '' : tiers[tier].id });
}

function row(entry) {
  const el = RC.el('div', 'panel row');
  el.style.setProperty('--tc', entry.tierColor);
  const l1 = RC.el('div', 'l1');
  l1.append(document.createTextNode(entry.title + '  '), RC.el('em', '', RC.title(entry.species)));
  const l2 = RC.el('div', 'l2');
  l2.append(RC.el('span', 'tier', entry.tierName.toUpperCase()),
    document.createTextNode('   ·   ' + entry.boon + '   ·   ' + RC.date(entry.defeatedAt)));
  el.append(l1, l2, RC.el('div', 'l3', entry.victors.join(', ')));
  return el;
}

function render() {
  if (!page) return;
  $('mine').textContent = mine ? 'Mine' : 'Everyone';
  $('tier').textContent = tier < 0 ? 'All tiers' : tiers[tier].name;
  $('mine').disabled = $('tier').disabled = awaiting;
  $('prev').disabled = awaiting || page.pageIndex <= 0;
  $('next').disabled = awaiting || page.pageIndex + 1 >= page.pageCount;
  $('crumb').textContent = '/ ' + (mine ? page.mineCount + ' of yours' : page.total + ' legends');
  $('counts').textContent = 'PAGE ' + (page.pageIndex + 1) + ' OF ' + page.pageCount;

  const rows = $('rows');
  rows.textContent = '';
  if (awaiting) { rows.appendChild(RC.el('div', 'empty', 'Loading...')); return; }
  if (page.entries.length === 0) {
    rows.appendChild(RC.el('div', 'empty', page.total === 0 ? 'No legends recorded yet.' : 'No legends match this filter.'));
    return;
  }
  page.entries.forEach((entry) => rows.appendChild(row(entry)));
}

$('mine').addEventListener('click', () => { mine = !mine; request(0); });
$('tier').addEventListener('click', () => { tier = tier + 1 >= tiers.length ? -1 : tier + 1; request(0); });
$('prev').addEventListener('click', () => request(page.pageIndex - 1));
$('next').addEventListener('click', () => request(page.pageIndex + 1));
$('close').addEventListener('click', RC.close);

addEventListener('webgui:legends_page', (e) => {
  page = e.detail; tiers = page.tiers || tiers; awaiting = false;
  render();
});

send({ action: 'ready' });
