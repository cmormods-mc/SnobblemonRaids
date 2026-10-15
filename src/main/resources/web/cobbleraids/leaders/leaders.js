'use strict';
// The leaderboards page. A view only: the server ranks the board; this shows the rows it sends.
const CHANNEL = 'cobbleraids.leaders';
const $ = (id) => document.getElementById(id);
let page = null, boardIndex = 0, scope = 'alltime', awaiting = false;

function send(message) {
  if (window.webgui && window.webgui.postToGame) window.webgui.postToGame({ channel: CHANNEL, ...message });
}
function scale() {
  const s = Math.max(1, Math.min(5, Math.floor(Math.min(innerWidth / 544, innerHeight / 384))));
  const stage = $('stage');
  document.documentElement.style.setProperty('--s', s);
  stage.style.left = Math.round((innerWidth - 520 * s) / 2) + 'px';
  stage.style.top = Math.round((innerHeight - 360 * s) / 2) + 'px';
}
addEventListener('resize', scale); scale();

function request() {
  if (awaiting || !page) return;
  awaiting = true;
  render();
  send({ action: 'query', stat: page.boards[boardIndex].id, scope });
}

const n = (v) => Number(v).toLocaleString('en-US');
function duration(total) {
  total = Math.max(0, Math.round(total));
  const d = Math.floor(total / 86400), h = Math.floor(total % 86400 / 3600), m = Math.floor(total % 3600 / 60), s = total % 60;
  if (d > 0) return d + 'd ' + h + 'h';
  if (h > 0) return h + 'h ' + m + 'm';
  return m > 0 ? m + 'm ' + s + 's' : s + 's';
}
const value = (v) => page.seconds ? duration(v) : n(v);

function render() {
  if (!page) return;
  $('title').textContent = page.title;
  $('scope').textContent = scope === 'weekly' ? 'This week' : 'All time';
  $('prev').disabled = $('next').disabled = $('scope').disabled = awaiting;
  const rows = $('rows');
  rows.textContent = '';
  if (!awaiting && page.rows.length === 0) {
    const e = document.createElement('div');
    e.className = 'empty';
    e.textContent = 'Nobody is on this board yet.';
    rows.appendChild(e);
  }
  if (!awaiting) page.rows.forEach((row, i) => {
    const r = document.createElement('div');
    r.className = 'row' + (row.you ? ' you' : '') + (row.rank <= 3 ? ' r' + row.rank : '');
    r.style.top = i * 27 + 'px';
    for (const [cls, text] of [['rank', '#' + row.rank], ['name', row.name], ['val', value(row.value)]]) {
      const c = document.createElement('div');
      c.className = cls;
      c.textContent = text;
      r.appendChild(c);
    }
    rows.appendChild(r);
  });
  let line = page.ranked + ' ranked';
  if (page.viewer) line += '  |  You: #' + page.viewer.rank + '  ' + value(page.viewer.value);
  else line += '  |  You are not ranked';
  if (scope === 'weekly' && page.resetsInSeconds != null) line += '  |  resets in ' + duration(page.resetsInSeconds);
  $('counts').textContent = awaiting ? 'Loading...' : line;
}

$('prev').addEventListener('click', () => { boardIndex = (boardIndex - 1 + page.boards.length) % page.boards.length; request(); });
$('next').addEventListener('click', () => { boardIndex = (boardIndex + 1) % page.boards.length; request(); });
$('scope').addEventListener('click', () => { scope = scope === 'weekly' ? 'alltime' : 'weekly'; request(); });
$('back').addEventListener('click', () => { if (window.webgui && window.webgui.closeGui) window.webgui.closeGui(); });

addEventListener('webgui:leaders_page', (e) => {
  page = e.detail; awaiting = false;
  boardIndex = Math.max(0, page.boards.findIndex((b) => b.id === page.stat));
  scope = page.scope;
  render();
});

send({ action: 'ready' });
