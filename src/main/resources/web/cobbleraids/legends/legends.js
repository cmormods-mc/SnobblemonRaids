'use strict';
// The Hall of Legends page. A view only: the server filters and paginates, this shows what it sent.
const CHANNEL = 'cobbleraids.legends';
const $ = (id) => document.getElementById(id);
let page = null, tiers = [];
let mine = false, tier = -1, awaiting = false;

function send(message) {
  if (window.webgui && window.webgui.postToGame) window.webgui.postToGame({ channel: CHANNEL, ...message });
}
function fit() {
  const s = Math.max(1, Math.min(5, Math.floor(Math.min(innerWidth / 544, innerHeight / 384))));
  document.documentElement.style.setProperty('--s', s);
}
addEventListener('resize', fit); fit();

function request(index) {
  if (awaiting) return;
  awaiting = true;
  render();
  send({ action: 'query', page: Math.max(0, index), mine, tier: tier < 0 ? '' : tiers[tier].id });
}

const title = (s) => s ? s.charAt(0).toUpperCase() + s.slice(1).replace(/_/g, ' ') : s;
function el(cls, text, css) {
  const d = document.createElement('div');
  d.className = cls;
  if (text !== undefined) d.textContent = text;
  if (css) d.style.cssText = css;
  return d;
}

function render() {
  if (!page) return;
  $('mine').textContent = mine ? 'Mine' : 'All';
  $('tier').textContent = tier < 0 ? 'All Tiers' : tiers[tier].name;
  $('mine').disabled = $('tier').disabled = awaiting;
  $('prev').disabled = awaiting || page.pageIndex <= 0;
  $('next').disabled = awaiting || page.pageIndex + 1 >= page.pageCount;
  $('counts').textContent = (mine ? page.mineCount + ' OF YOURS' : page.total + ' LEGENDS') +
    '  |  ' + (page.pageIndex + 1) + ' / ' + page.pageCount;

  const rows = $('rows');
  rows.textContent = '';
  if (!awaiting && page.entries.length === 0) {
    rows.appendChild(el('empty', page.total === 0 ? 'No legends recorded yet.' : 'No legends match this filter.', 'top:13px'));
  }
  if (awaiting) return;
  page.entries.forEach((entry, i) => {
    const row = el('row', undefined, 'top:' + i * 37 + 'px');
    row.appendChild(el('stripe', undefined, 'background:' + entry.tierColor));
    row.appendChild(el('l1', entry.title + ' -- ' + title(entry.species)));
    const date = new Date(entry.defeatedAt).toISOString().slice(0, 10);
    row.appendChild(el('l2', entry.tierName.toUpperCase() + '  |  ' + entry.boon + '  |  ' + date));
    row.appendChild(el('l3', entry.victors.join(', ')));
    rows.appendChild(row);
  });
}

$('mine').addEventListener('click', () => { mine = !mine; request(0); });
$('tier').addEventListener('click', () => { tier = tier + 1 >= tiers.length ? -1 : tier + 1; request(0); });
$('prev').addEventListener('click', () => request(page.pageIndex - 1));
$('next').addEventListener('click', () => request(page.pageIndex + 1));
$('back').addEventListener('click', () => { if (window.webgui && window.webgui.closeGui) window.webgui.closeGui(); });

addEventListener('webgui:legends_page', (e) => {
  page = e.detail; tiers = page.tiers || tiers; awaiting = false;
  render();
});

send({ action: 'ready' });
