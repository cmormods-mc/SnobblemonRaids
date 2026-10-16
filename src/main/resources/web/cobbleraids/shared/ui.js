'use strict';
// Small helpers every page shares. A page is a view: it sends ids and choices, never decisions.
const RC = (() => {
  const $ = (id) => document.getElementById(id);
  const send = (channel, message) => {
    if (window.webgui && window.webgui.postToGame) window.webgui.postToGame({ channel, ...message });
  };
  const close = () => { if (window.webgui && window.webgui.closeGui) window.webgui.closeGui(); };
  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  };
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const num = (v) => Number(v).toLocaleString('en-US');
  const title = (s) => s ? s.charAt(0).toUpperCase() + s.slice(1).replace(/_/g, ' ') : s;
  const date = (ms) => new Date(ms).toISOString().slice(0, 10);

  // One floating tooltip per page: an element with id "tip". Lines are [text, className].
  function tooltip() {
    const tip = $('tip');
    const move = (ev) => {
      const w = tip.offsetWidth, h = tip.offsetHeight;
      let x = ev.clientX + 16, y = ev.clientY - 16;
      if (x + w > innerWidth) x = ev.clientX - 16 - w;
      if (y + h > innerHeight) y = innerHeight - h;
      tip.style.left = Math.max(0, x) + 'px';
      tip.style.top = Math.max(0, y) + 'px';
    };
    return {
      show(lines, ev) {
        tip.textContent = '';
        for (const [text, cls] of lines) tip.appendChild(el('div', cls || '', text));
        tip.hidden = false;
        move(ev);
      },
      move,
      hide() { tip.hidden = true; },
      bind(node, linesFor) {
        node.addEventListener('mouseenter', (ev) => this.show(linesFor(), ev));
        node.addEventListener('mousemove', move);
        node.addEventListener('mouseleave', () => this.hide());
      },
    };
  }

  // A small skull for renowned bosses. Drawn, not typed: a glyph depends on fonts the player may not have.
  const skull = (color) => '<svg viewBox="0 0 16 16" width="1em" height="1em" fill="' + (color || 'currentColor') +
    '"><path d="M8 1C4.4 1 2 3.4 2 6.6c0 1.8.8 3 1.9 3.8V13h2v-1.5h1V13h2v-1.5h1V13h2v-2.6c1.1-.8 1.9-2 1.9-3.8C14 3.4 11.6 1 8 1zM5.5 8.6a1.4 1.4 0 110-2.8 1.4 1.4 0 010 2.8zm5 0a1.4 1.4 0 110-2.8 1.4 1.4 0 010 2.8z"/></svg>';

  return { $, send, close, el, esc, num, title, date, tooltip, skull };
})();
