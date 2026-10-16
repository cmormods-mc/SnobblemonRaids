'use strict';
// The leaderboards page. A view only: the server ranks the board; this shows the rows it sends.
const CHANNEL = 'cobbleraids.leaders';
const $ = RC.$;
let page = null, boardIndex = 0, scope = 'alltime', awaiting = false;

const send = (m) => RC.send(CHANNEL, m);

function request() {
  if (awaiting || !page) return;
  awaiting = true;
  render();
  send({ action: 'query', stat: page.boards[boardIndex].id, scope });
}

function duration(total) {
  total = Math.max(0, Math.round(total));
  const d = Math.floor(total / 86400), h = Math.floor(total % 86400 / 3600), m = Math.floor(total % 3600 / 60), s = total % 60;
  if (d > 0) return d + 'd ' + h + 'h';
  if (h > 0) return h + 'h ' + m + 'm';
  return m > 0 ? m + 'm ' + s + 's' : s + 's';
}
const value = (v) => page.seconds ? duration(v) : RC.num(v);

function row(r) {
  const el = RC.el('div', 'row' + (r.you ? ' you' : '') + (r.rank <= 3 ? ' r' + r.rank : ''));
  el.append(RC.el('span', 'rank', '#' + r.rank), RC.el('span', 'name', r.name), RC.el('span', 'val', value(r.value)));
  return el;
}

function render() {
  if (!page) return;
  $('crumb').textContent = '/ ' + page.title;
  $('boardTitle').textContent = page.title;
  $('scope').textContent = scope === 'weekly' ? 'This week' : 'All time';
  $('prev').disabled = $('next').disabled = $('scope').disabled = awaiting;
  $('valueHead').textContent = page.seconds ? 'TIME' : 'TOTAL';

  const rows = $('rows');
  rows.textContent = '';
  if (awaiting) rows.appendChild(RC.el('div', 'empty', 'Loading...'));
  else if (page.rows.length === 0) rows.appendChild(RC.el('div', 'empty', 'Nobody is on this board yet.'));
  else page.rows.forEach((r) => rows.appendChild(row(r)));

  let line = page.ranked + ' ranked';
  if (page.viewer) line += '   |   You: #' + page.viewer.rank + '  ' + value(page.viewer.value);
  else line += '   |   You are not ranked';
  if (scope === 'weekly' && page.resetsInSeconds != null) line += '   |   Resets in ' + duration(page.resetsInSeconds);
  $('counts').textContent = awaiting ? '' : line;
}

$('prev').addEventListener('click', () => { boardIndex = (boardIndex - 1 + page.boards.length) % page.boards.length; request(); });
$('next').addEventListener('click', () => { boardIndex = (boardIndex + 1) % page.boards.length; request(); });
$('scope').addEventListener('click', () => { scope = scope === 'weekly' ? 'alltime' : 'weekly'; request(); });
$('close').addEventListener('click', RC.close);

addEventListener('webgui:leaders_page', (e) => {
  page = e.detail; awaiting = false;
  boardIndex = Math.max(0, page.boards.findIndex((b) => b.id === page.stat));
  scope = page.scope;
  render();
});

send({ action: 'ready' });
