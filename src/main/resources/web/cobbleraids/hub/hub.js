'use strict';
// The raid hub. Shows a figure from each room and sends one thing: which room to open.
const CHANNEL = 'cobbleraids.hub';
const $ = RC.$;

document.querySelectorAll('.tile').forEach((tile) =>
  tile.addEventListener('click', () => RC.send(CHANNEL, { action: 'open', target: tile.dataset.open })));
$('close').addEventListener('click', RC.close);

addEventListener('webgui:hub_state', (e) => {
  const s = e.detail;
  $('who').textContent = '/ ' + s.name;
  $('rp').textContent = RC.num(s.balance) + ' RP';
  $('shop').textContent = RC.num(s.balance) + ' RP';
  $('trophy').textContent = RC.num(s.trophies) + (s.trophies === 1 ? ' trophy' : ' trophies');
  $('legends').textContent = RC.num(s.legends) + (s.legends === 1 ? ' legend' : ' legends');
  $('leaders').textContent = RC.num(s.raidsWon) + (s.raidsWon === 1 ? ' raid won' : ' raids won');
});

RC.send(CHANNEL, { action: 'ready' });
