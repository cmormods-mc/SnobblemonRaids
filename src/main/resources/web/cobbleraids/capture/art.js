'use strict';
// A port of the native CaptureArt painters. The native screen built this art from rectangles, scan-converted
// polygons and ellipses in whole GUI pixels; this runs the same arithmetic onto a canvas, one texel per GUI
// pixel, and the page scales the canvas up with nearest-neighbour -- the same hard-edged pixel grid.
const Art = (() => {
  const DARK = 0xFF001A30, CYAN = 0xFF00E6F5, LIGHT = 0xFF70F8FF;
  const div = (a, b) => Math.trunc(a / b);                       // Java's integer division
  const css = (argb) => '#' + (argb & 0xFFFFFF).toString(16).padStart(6, '0');

  // The one primitive everything is built from, like the native Pen: a filled rectangle in pixels.
  function pen(ctx) {
    return (x1, y1, x2, y2, color) => {
      const ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2);
      if (bx > ax && by > ay) { ctx.fillStyle = css(color); ctx.fillRect(ax, ay, bx - ax, by - ay); }
    };
  }

  function line(g, x, y, ex, ey, color) {
    const dx = Math.abs(ex - x), sx = x < ex ? 1 : -1;
    const dy = -Math.abs(ey - y), sy = y < ey ? 1 : -1;
    let error = dx + dy;
    for (;;) {
      g(x, y, x + 1, y + 1, color);
      if (x === ex && y === ey) break;
      const e = 2 * error;
      if (e >= dy) { error += dy; x += sx; }
      if (e <= dx) { error += dx; y += sy; }
    }
  }

  function ellipse(g, cx, cy, rx, ry, color) {
    if (rx < 1 || ry < 1) return;
    for (let yy = -ry; yy <= ry; yy++) {
      const xx = Math.round(rx * Math.sqrt(Math.max(0, 1 - yy * yy / (ry * ry))));
      g(cx - xx, cy + yy, cx + xx + 1, cy + yy + 1, color);
    }
  }

  function outlineEllipse(g, cx, cy, rx, ry, color) {
    for (let yy = -ry; yy <= ry; yy++) {
      const outer = Math.round(rx * Math.sqrt(Math.max(0, 1 - yy * yy / (ry * ry))));
      const inner = Math.abs(yy) >= ry - 2 ? 0
        : Math.round((rx - 2) * Math.sqrt(Math.max(0, 1 - yy * yy / ((ry - 2) * (ry - 2)))));
      g(cx - outer, cy + yy, cx - inner + 1, cy + yy + 1, color);
      g(cx + inner, cy + yy, cx + outer + 1, cy + yy + 1, color);
    }
  }

  function polygon(g, xs, ys, color) {
    const min = Math.min(...ys), max = Math.max(...ys);
    for (let y = min; y < max; y++) {
      let left = Infinity, right = -Infinity;
      for (let i = 0, j = xs.length - 1; i < xs.length; j = i++) {
        if ((ys[i] <= y && ys[j] > y) || (ys[j] <= y && ys[i] > y)) {
          const xx = xs[i] + Math.trunc((y - ys[i]) * (xs[j] - xs[i]) / (ys[j] - ys[i]));
          left = Math.min(left, xx); right = Math.max(right, xx);
        }
      }
      if (right > left) g(left, y, right, y + 1, color);
    }
  }

  function bracket(g, x, y, w, h, length, color) {
    for (let side = 0; side < 2; side++) {
      for (let bottom = 0; bottom < 2; bottom++) {
        const px = side === 0 ? x : x + w - 2, py = bottom === 0 ? y : y + h - 2;
        g(side === 0 ? px : px - length + 2, py, side === 0 ? px + length : px + 2, py + 2, color);
        g(px, bottom === 0 ? py : py - length + 2, px + 2, bottom === 0 ? py + length : py + 2, color);
      }
    }
  }

  // The hologram chamber: perspective wall panels and ribs, light rings, beam bands and brackets.
  function chamber(g, x, y, w, h) {
    g(x, y, x + w, y + h, DARK);
    const cx = x + div(w, 2), beam = div(w * 3, 5), left = cx - div(beam, 2), right = left + beam;
    for (let side = 0; side < 2; side++) {
      const outer = side === 0 ? x + 7 : x + w - 8, inner = side === 0 ? left - 5 : right + 5;
      const ox = side === 0 ? outer + 7 : outer - 7, ix = side === 0 ? inner - 4 : inner + 4;
      polygon(g, [ox, ix, ix, ox], [y + 20, y + 44, y + h - 44, y + h - 20], 0xFF054887);
      polygon(g, [ox, ix, ix, ox], [y + div(h, 3), y + div(h, 3) + 12, y + div(h * 2, 3) - 12, y + div(h * 2, 3)], 0xFF0756A5);
      for (let row = 0; row < 4; row++) {
        const yy = y + 9 + div(row * (h - 18), 3), iy = yy + (row < 2 ? 18 : -18);
        polygon(g, [outer, inner, inner, outer], [yy, iy, iy + 5, yy + 5], 0xFF0867BB);
      }
      for (let row = 0; row < 2; row++) {
        const yy = row === 0 ? y + div(h, 5) : y + div(h * 4, 5), slope = row === 0 ? 7 : -7;
        const a = side === 0 ? ox + 3 : ox - 3, b = side === 0 ? ix - 4 : ix + 4;
        polygon(g, [a, b, b, a], [yy - 5, yy + slope - 5, yy + slope + 5, yy + 5], 0xFF0086E8);
        polygon(g, [a, b, b, a], [yy - 2, yy + slope - 2, yy + slope + 2, yy + 2], CYAN);
      }
    }
    const bands = [0xFF075AAA, 0xFF008CE5, 0xFF00B9EF, 0xFF05CDEB, 0xFF1EDCEE, 0xFF05CDEB, 0xFF00B9EF, 0xFF008CE5, 0xFF075AAA];
    bands.forEach((c, i) => g(left + div(beam * i, 9), y, left + div(beam * (i + 1), 9), y + h, c));
    const ringY = Math.max(12, div(h, 10)), ry = Math.max(5, div(h, 17)), rx = div(beam, 2) + 5;
    for (const yy of [y + ringY, y + h - ringY]) {
      ellipse(g, cx, yy + 3, rx, ry, 0xFF008BDF);
      ellipse(g, cx, yy, rx, ry, LIGHT);
      ellipse(g, cx, yy, rx - 7, Math.max(2, ry - 3), 0xFF007FC5);
      ellipse(g, cx, yy, rx - 19, Math.max(1, ry - 4), 0xFF00C5EC);
      g(cx - div(rx, 3), yy - ry + 3, cx + div(rx, 3), yy + ry - 3, 0xFF03C9E9);
    }
    for (const p of [[1, 3], [8, 3], [2, 6], [7, 7], [1, 8], [8, 6]]) {
      const px = left + div(beam * p[0], 10), py = y + div(h * p[1], 10);
      g(px, py, px + 4, py + 5, LIGHT);
    }
    bracket(g, x + 6, y + 6, w - 12, h - 12, Math.max(7, Math.min(18, div(w, 10))), CYAN);
    bracket(g, cx - div(beam, 3), y + div(h, 4), div(beam * 2, 3), div(h, 2), 10, 0xFFE9FFFF);
  }

  // The scanner's unchanging half, in local coordinates: grid, targeting ring and crosshair.
  function scannerStatic(g, w, h) {
    g(0, 0, w, h, DARK);
    for (let xx = 0; xx < w; xx += 12) g(xx, 0, xx + 1, h, 0xFF0A5074);
    for (let yy = 0; yy < h; yy += 12) g(0, yy, w, yy + 1, 0xFF0A5074);
    const radius = Math.min(div(h, 2) - 6, div(w, 4)), cx = div(w, 3), cy = div(h, 2);
    outlineEllipse(g, cx, cy, radius, radius, CYAN);
    g(cx - radius + 2, cy - 3, cx + radius - 1, cy + 3, DARK);
    g(cx - radius + 2, cy - 3, cx + radius - 1, cy - 1, CYAN);
    g(cx - radius + 2, cy + 1, cx + radius - 1, cy + 3, CYAN);
    ellipse(g, cx, cy, 7, 7, CYAN); ellipse(g, cx, cy, 5, 5, DARK); ellipse(g, cx, cy, 3, 3, CYAN);
    bracket(g, cx - radius - 4, cy - radius - 4, radius * 2 + 8, radius * 2 + 8, 6, CYAN);
  }

  // The footer circuitry in the frame's local coordinates.
  function footer(g, width, height) {
    const y = height - 13;
    for (let side = 0; side < 2; side++) {
      const a = side === 0 ? 18 : width - 18, dir = side === 0 ? 1 : -1;
      for (let i = 0; i < 3; i++) g(a + dir * (35 + i * 10) - 2, y - 16, a + dir * (35 + i * 10) + 2, y - 1, DARK);
      line(g, a, y + 7, a, y - 8, CYAN);
      line(g, a, y - 8, a - dir * 12, y - 20, CYAN);
      const start = a + dir * 72, end = a + dir * (div(width, 2) - 115);
      if (Math.abs(end - start) > 10) {
        line(g, start, y - 11, start + dir * 10, y - 11, DARK);
        line(g, start + dir * 10, y - 11, start + dir * 17, y - 4, DARK);
        line(g, start + dir * 17, y - 4, end, y - 4, DARK);
        g(end - 2, y - 6, end + 2, y - 2, CYAN);
      }
    }
  }

  // A flat-shaded Poke Ball silhouette, as drawn natively while a throw is in flight.
  function pokeball(g, s) {
    g(3, 0, s - 3, s, 0xFF001425);
    g(0, 3, s, s - 3, 0xFF001425);
    g(3, 3, s - 3, div(s, 2), 0xFFFF4F59);
    g(3, div(s, 2), s - 3, s - 3, 0xFFEAFBFF);
    g(0, div(s, 2) - 2, s, div(s, 2) + 2, 0xFF001425);
    g(div(s, 2) - 4, div(s, 2) - 4, div(s, 2) + 4, div(s, 2) + 4, 0xFF001425);
    g(div(s, 2) - 2, div(s, 2) - 2, div(s, 2) + 2, div(s, 2) + 2, 0xFFEAFBFF);
  }

  // The RP crystal icon beside the amount at stake.
  function crystal(g) {
    for (let yy = 0; yy < 14; yy++) {
      const half = yy < 7 ? div(yy, 2) : div(13 - yy, 2);
      g(4 - half, yy, 5 + half, yy + 1, 0xFFD947DD);
      g(4, yy, 5, yy + 1, 0xFFFFE9FF);
    }
  }

  /** A canvas of w x h GUI pixels painted by {@code paint(pen, w, h)}, positioned in its parent by the caller. */
  function canvas(w, h, paint) {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    paint(pen(c.getContext('2d')), w, h);
    return c;
  }

  return { canvas, chamber, scannerStatic, footer, pokeball, crystal, DARK, CYAN, LIGHT };
})();
