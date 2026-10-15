'use strict';
// The raid hub. Shows a figure from each room and sends one thing: which room to open.
const CHANNEL = 'cobbleraids.hub';
const $ = (id) => document.getElementById(id);

function send(message) {
  if (window.webgui && window.webgui.postToGame) window.webgui.postToGame({ channel: CHANNEL, ...message });
}
function scale() {
  const s = Math.max(1, Math.min(5, Math.floor(Math.min(innerWidth / 544, innerHeight / 314))));
  const stage = $('stage');
  document.documentElement.style.setProperty('--s', s);
  stage.style.left = Math.round((innerWidth - 520 * s) / 2) + 'px';
  stage.style.top = Math.round((innerHeight - 290 * s) / 2) + 'px';
}
addEventListener('resize', scale); scale();

document.querySelectorAll('.tile').forEach((tile) =>
  tile.addEventListener('click', () => send({ action: 'open', target: tile.dataset.open })));
$('back').addEventListener('click', () => { if (window.webgui && window.webgui.closeGui) window.webgui.closeGui(); });

const n = (v) => Number(v).toLocaleString('en-US');
addEventListener('webgui:hub_state', (e) => {
  const s = e.detail;
  $('who').textContent = s.name;
  $('shop').textContent = n(s.balance) + ' RP';
  $('trophy').textContent = n(s.trophies) + (s.trophies === 1 ? ' trophy' : ' trophies');
  $('legends').textContent = n(s.legends) + (s.legends === 1 ? ' legend' : ' legends');
  $('leaders').textContent = n(s.raidsWon) + (s.raidsWon === 1 ? ' raid won' : ' raids won');
});

send({ action: 'ready' });
