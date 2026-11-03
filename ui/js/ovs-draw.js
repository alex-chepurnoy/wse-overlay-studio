/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio canvas drawing (plan 4.4): text, shape and image layers as one small SVG each, in source pixels,
   following how the module draws them (ObjectConfig.draw, TextDraw.draw), so the editor shows what the video will.
   Web page layers are not drawn here; their captures are. A token such as {time:HH:mm:ss} draws the server's sample
   for now (ovs-format.js), and shows as written until it comes. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var D = U.draw = {};

  // what java.awt.Color names the module accepts: its lowercase fields, so darkGray and lightGray are not among them
  var NAMED = { black: [0, 0, 0], blue: [0, 0, 255], cyan: [0, 255, 255], gray: [128, 128, 128], green: [0, 255, 0], magenta: [255, 0, 255],
    orange: [255, 200, 0], pink: [255, 175, 175], red: [255, 0, 0], white: [255, 255, 255], yellow: [255, 255, 0] };
  D.SHAPES = ['rectangle', 'ellipse', 'line', 'triangle'];
  // the module's arrow head (ObjectConfig.ARROW_SPREAD, arrowSize, lineEnds)
  var ARROW_SPREAD = 28 * Math.PI / 180;
  var esc = U.esc;

  function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : d; }

  /** A layer color as CSS, as awt_color reads it: rgb(r,g,b), rgb(r,g,b,a) or a name; black for anything else. */
  D.css = function (c) {
    if (!c) return null;
    var s = String(c).trim().toLowerCase(), m = /^rgb *\( *(\d+) *, *(\d+) *, *(\d+) *(?:, *(\d+) *)?\)$/.exec(s);
    var v = m ? [+m[1], +m[2], +m[3], m[4] === undefined ? 255 : +m[4]] : NAMED[s] ? NAMED[s].concat([255]) : [0, 0, 0, 255];
    return 'rgba(' + v[0] + ',' + v[1] + ',' + v[2] + ',' + Math.round(v[3] / 255 * 1000) / 1000 + ')';
  };

  /** Whether the module takes c as a color (ObjectConfig.isColor): rgb(r,g,b) or rgb(r,g,b,a), each 0 to 255, or a name. */
  D.isColor = function (c) {
    var s = String(c == null ? '' : c).trim().toLowerCase(), m = /^rgb *\( *(\d{1,3}) *, *(\d{1,3}) *, *(\d{1,3}) *(?:, *(\d{1,3}) *)?\)$/.exec(s);
    if (m) return [m[1], m[2], m[3], m[4] === undefined ? '0' : m[4]].every(function (v) { return +v <= 255; });
    return Object.prototype.hasOwnProperty.call(NAMED, s);
  };

  /** The values a layer draws with: its own, else the overlay's defaults, else the module's (applyDefaults). */
  D.effective = function (o, ov) {
    ov = ov || {};
    var e = {}, k;
    for (k in o) if (Object.prototype.hasOwnProperty.call(o, k)) e[k] = o[k];
    e.x = num(o.x, 0); e.y = num(o.y, 0); e.w = num(o.w, 0); e.h = num(o.h, 0);
    e.x2 = num(o.x2, 0); e.y2 = num(o.y2, 0); e.x3 = num(o.x3, 0); e.y3 = num(o.y3, 0);
    e.stroke = num(o.stroke, -1) >= 0 ? o.stroke : num(ov.stroke, 1);
    e.color = o.color || ov.color || 'black';
    e.fillColor = o.fillColor || ov.fillColor || null;
    e.shadowColor = o.shadowColor || ov.shadowColor || null;
    e.shadowOffset = num(o.shadowOffset, -1) >= 0 ? o.shadowOffset : num(ov.shadowOffset, 2);
    e.opacity = num(o.opacity, -1) >= 0 ? Math.min(100, o.opacity) : 100;
    e.rotation = num(o.rotation, 0);
    if (o.type === 'string') {
      e.font = o.font || ov.font || 'Fira Sans';
      e.fontStyle = o.fontStyle || ov.fontStyle || 'plain';
      e.fontSize = num(o.fontSize, -1) > 0 ? o.fontSize : num(ov.fontSize, 16);
    }
    return e;
  };

  /* ---------- text ---------- */
  var ctx = null;
  function cssFont(e, size) {
    var st = String(e.fontStyle || 'plain').toLowerCase();
    return (st.indexOf('italic') >= 0 ? 'italic ' : '') + (st.indexOf('bold') >= 0 ? '700 ' : '400 ') + size + 'px "' + String(e.font).replace(/"/g, '') + '", "Fira Sans", sans-serif';
  }
  // measuring on a canvas does not make the browser fetch an @font-face font: ask for each one, and redraw once it is in
  var asked = {};
  function want(font) {
    if (asked[font] || !document.fonts || !document.fonts.load) return;
    asked[font] = true;
    document.fonts.load(font).then(function (faces) { if (faces && faces.length) changed(); }, function () { /* the fallback font stays */ });
  }
  // Java's TextLayout draws without kerning, so the canvas measures and draws without it too: with it, "Text" in Fira
  // Sans at 48 px is 88.5 px wide here and 91.6 px in the video (found by the server check, PR 9)
  function measurer(font) {
    if (!ctx) ctx = document.createElement('canvas').getContext('2d');
    want(font);
    ctx.font = font;
    if ('fontKerning' in ctx) ctx.fontKerning = 'none';
    return ctx;
  }
  function width(c, s) { return s ? c.measureText(s).width : 0; }
  // a font's ascent and descent as fractions of its size, read at 1000 px: the canvas rounds them to whole pixels, so
  // at 51 px Fira Sans Condensed reads 48 + 14 where Java's LineMetrics has 47.7 + 13.5, and two lines that fit a box on
  // the server overflowed it here and were shrunk by autoFit (found by the server check, PR 10b). Read again when a
  // font arrives, since until then the fallback's are measured.
  var vmetrics = {};
  function vertical(e) {
    var key = cssFont(e, 1000), v = vmetrics[key];
    if (v) return v;
    var m = measurer(key).measureText('Hgjy');
    v = { asc: m.fontBoundingBoxAscent ? m.fontBoundingBoxAscent / 1000 : 0.935, desc: m.fontBoundingBoxDescent ? m.fontBoundingBoxDescent / 1000 : 0.265 };
    if (document.fonts && document.fonts.check && document.fonts.check(key)) vmetrics[key] = v;
    return v;
  }
  // greedy word wrap at w; a word wider than the line breaks between characters (TextDraw.wrap)
  function wrap(c, para, w, out) {
    if (!para) { out.push(''); return; }
    var line = '';
    para.split(' ').forEach(function (word) {
      var cand = line ? line + ' ' + word : word;
      if (width(c, cand) <= w) { line = cand; return; }
      if (line) { out.push(line); line = ''; }
      Array.from(word).forEach(function (ch) {
        if (line && width(c, line + ch) > w) { out.push(line); line = ''; }
        line += ch;
      });
    });
    out.push(line);
  }
  function layout(e, text, size, wrapW, lh) {
    var v = vertical(e), c = measurer(cssFont(e, size)), lines = [];
    text.split('\n').forEach(function (p) { if (wrapW > 0) wrap(c, p, wrapW, lines); else lines.push(p); });
    var asc = v.asc * size, desc = v.desc * size;
    var widths = lines.map(function (l) { return width(c, l); });
    var maxW = widths.reduce(function (a, b) { return Math.max(a, b); }, 0);
    return { size: size, lines: lines, widths: widths, step: size * lh, ascent: asc, descent: desc, maxWidth: maxW,
      height: lines.length ? (lines.length - 1) * size * lh + asc + desc : 0 };
  }
  function isBox(e) { return e.box === true && e.w > 0 && e.h > 0; }

  /** Where a text layer's lines go (TextDraw.layoutFor and draw): the font size after autoFit, each line's x and baseline, and its area. */
  D.text = function (e, text) {
    var lh = e.lineHeight == null ? 1.2 : e.lineHeight, pad = num(e.padding, 0), box = isBox(e);
    var innerW = box ? e.w - 2 * pad : 0, innerH = box ? e.h - 2 * pad : 0;
    var l = layout(e, text, e.fontSize, innerW, lh);
    if (box && e.autoFit === true) {
      for (var size = e.fontSize; (l.height > innerH || l.maxWidth > innerW) && size > e.fontSize * 0.5;) {
        size = Math.max(e.fontSize * 0.5, size * 0.95);
        l = layout(e, text, size, innerW, lh);
      }
    }
    var area, base, left;
    if (box) { area = { x: e.x, y: e.y, w: e.w, h: e.h }; base = e.y + pad + l.ascent; left = e.x + pad; }
    else { innerW = l.maxWidth; area = { x: e.x - pad, y: e.y - l.ascent - pad, w: innerW + 2 * pad, h: l.height + 2 * pad }; base = e.y; left = e.x; }
    l.box = box; l.area = area;
    // a box's text that does not fit, even at the auto-fit floor of half its size, is cut at the box's edge (plan 4.2a)
    l.cut = box && (l.height > innerH + 0.5 || l.maxWidth > innerW + 0.5);
    l.rows = l.lines.map(function (s, i) {
      var lx = e.align === 'center' ? left + (innerW - l.widths[i]) / 2 : e.align === 'right' ? left + innerW - l.widths[i] : left;
      return { text: s, x: lx, base: base + i * l.step, w: l.widths[i] };
    });
    return l;
  };

  /* ---------- images ---------- */
  var images = {};
  var waiting = [];
  function changed() { vmetrics = {}; waiting.forEach(function (fn) { fn(); }); }
  /** Calls fn whenever an image or font this module asked for arrives, so the canvas can draw with it. */
  D.onLoad = function (fn) { waiting.push(fn); };
  /** The library image an image layer names, or null while it loads; {width, height, data} or {error}. */
  D.image = function (file) {
    if (!file) return null;
    var c = images[file];
    if (c) return c.state === 'loading' ? null : c;
    images[file] = { state: 'loading' };
    U.api.get(U.vhostPath() + '/images?file=' + encodeURIComponent(file)).then(function (r) {
      images[file] = { state: 'ok', width: r.width, height: r.height, data: r.data };
    }, function (e) {
      images[file] = { state: 'error', error: e.message };
    }).then(changed);
    return null;
  };
  // where the picture goes inside the layer's box (ObjectConfig.imageRect): fitted when lockAspect is on, stretched when off
  function imageRect(e, iw, ih) {
    var x = e.x, y = e.y, bw = e.w > 0 ? e.w : 0, bh = e.h > 0 ? e.h : 0, dw, dh;
    if (bw > 0 && bh > 0) {
      if (e.lockAspect !== false) { var k = Math.min(bw / iw, bh / ih); dw = iw * k; dh = ih * k; x += (bw - dw) / 2; y += (bh - dh) / 2; }
      else { dw = bw; dh = bh; }
    } else if (bw > 0) { dw = bw; dh = bw * ih / iw; }
    else if (bh > 0) { dh = bh; dw = bh * iw / ih; }
    else { dw = iw; dh = ih; }
    return { x: Math.round(x), y: Math.round(y), w: Math.round(dw), h: Math.round(dh) };
  }

  /* ---------- the layer's area ---------- */
  function span(xs, ys) {
    var x0 = Math.min.apply(null, xs), y0 = Math.min.apply(null, ys);
    return { x: x0, y: y0, w: Math.max.apply(null, xs) - x0, h: Math.max.apply(null, ys) - y0 };
  }
  function arrowSize(e) { return Math.max(10, e.stroke * 4); }
  D.arrowSize = function (o, ov) { return arrowSize(D.effective(o, ov)); };

  /**
   * The layer's own area in source pixels, unrotated: what the canvas boxes it with and what it turns about. A line
   * gets room for its stroke and heads; an image with no size takes its picture's, once it has loaded.
   */
  D.bounds = function (o, ov) {
    var e = D.effective(o, ov);
    if (o.type === 'rectangle' || o.type === 'ellipse') return { x: e.x, y: e.y, w: Math.max(1, e.w), h: Math.max(1, e.h) };
    if (o.type === 'line') {
      var r = span([e.x, e.x2], [e.y, e.y2]), g = Math.max(e.stroke / 2, 3) + (e.arrowHead && e.arrowHead !== 'none' ? arrowSize(e) / 2 : 0);
      return { x: r.x - g, y: r.y - g, w: r.w + 2 * g, h: r.h + 2 * g };
    }
    if (o.type === 'triangle') { var t = span([e.x, e.x2, e.x3], [e.y, e.y2, e.y3]); return { x: t.x, y: t.y, w: Math.max(1, t.w), h: Math.max(1, t.h) }; }
    if (o.type === 'string') { var a = D.text(e, D.shown(e)).area; return { x: a.x, y: a.y, w: Math.max(1, a.w), h: Math.max(1, a.h) }; }
    if (o.type === 'image') {
      var im = D.image(e.imageFile);
      if (e.w > 0 && e.h > 0) return { x: e.x, y: e.y, w: e.w, h: e.h };
      if (im && im.width) { var d = imageRect(e, im.width, im.height); return { x: e.x, y: e.y, w: d.w, h: d.h }; }
      return { x: e.x, y: e.y, w: e.w > 0 ? e.w : 160, h: e.h > 0 ? e.h : 90 };
    }
    return { x: e.x, y: e.y, w: Math.max(1, e.w), h: Math.max(1, e.h) };
  };

  /**
   * Where a layer draws, unrotated and unpadded, as ObjectConfig.checkBox works it out, for the server check (plan
   * 4.4): {x, y, w, h}, and for text its widest line as advance; null for a page or a picture not here yet. A text
   * draws text, given (a token's sample at the checked moment).
   */
  D.checkBox = function (o, ov, text) {
    var e = D.effective(o, ov);
    if (o.type === 'rectangle' || o.type === 'ellipse') return { x: e.x, y: e.y, w: Math.max(0, e.w), h: Math.max(0, e.h) };
    if (o.type === 'line') return span([e.x, e.x2], [e.y, e.y2]);
    if (o.type === 'triangle') return span([e.x, e.x2, e.x3], [e.y, e.y2, e.y3]);
    if (o.type === 'string') { var l = D.text(e, text), a = l.area; return { x: a.x, y: a.y, w: a.w, h: a.h, advance: l.maxWidth }; }
    if (o.type === 'image') { var im = D.image(e.imageFile); return im && im.width ? imageRect(e, im.width, im.height) : null; }
    return null;
  };

  /** What a text layer shows here now: its tokens by the server's samples (U.format.render), as written until they come. */
  D.shown = function (o) { return U.format ? U.format.render(o) : o.text == null ? '' : String(o.text); };

  /* ---------- SVG ---------- */
  function grad(id, c1, c2, dir, x, y, x2, y2) {
    var p = { up: [x, y2, x, y], down: [x, y, x, y2], left: [x2, y, x, y], right: [x, y, x2, y], downright: [x, y, x2, y2], downleft: [x2, y, x, y2],
      upleft: [x2, y2, x, y], upright: [x, y2, x2, y] }[dir] || [x, y, x, y2];
    return '<linearGradient id="' + id + '" gradientUnits="userSpaceOnUse" x1="' + p[0] + '" y1="' + p[1] + '" x2="' + p[2] + '" y2="' + p[3] + '">' +
      '<stop offset="0" stop-color="' + D.css(c1) + '"/><stop offset="1" stop-color="' + D.css(c2) + '"/></linearGradient>';
  }
  function strokeAttrs(e) {
    var s = Math.max(e.stroke, 1), d = e.dash || 'solid';
    if (d === 'dashed') return ' stroke-width="' + s + '" stroke-dasharray="' + 3 * s + ' ' + 2 * s + '" stroke-linecap="butt" stroke-linejoin="miter"';
    if (d === 'dotted') return ' stroke-width="' + s + '" stroke-dasharray="0.01 ' + 2 * s + '" stroke-linecap="round" stroke-linejoin="round"';
    return ' stroke-width="' + s + '" stroke-linecap="square" stroke-linejoin="miter"';
  }
  function n(v) { return Math.round(v * 100) / 100; }

  // the drawing of one layer in source pixels, as the module paints it, shadow first
  function body(o, e, uid) {
    var defs = '', out = '', gx = function (key, c1, x, y, x2, y2) {
      var id = uid + '-' + key; defs += grad(id, c1, e.gradientColor, e.gradientDirection || 'down', x, y, x2, y2); return 'url(#' + id + ')';
    };
    var paint = function (key, c, x, y, x2, y2) { return e.gradientColor ? gx(key, c, x, y, x2, y2) : D.css(c); };
    var s = e.stroke, so = e.shadowOffset, sa = strokeAttrs(e);
    if (o.type === 'rectangle') {
      // the module fills and outlines w and h less twice the stroke (ObjectConfig.draw)
      var w = Math.max(0, e.w - 2 * s), h = Math.max(0, e.h - 2 * s), arc = num(e.arcWidth, -1) >= 0 && num(e.arcHeight, -1) >= 0;
      var r = arc ? ' rx="' + n(e.arcWidth / 2) + '" ry="' + n(e.arcHeight / 2) + '"' : '';
      var x1 = e.x, y1 = e.y, x2 = e.x + e.w, y2 = e.y + e.h;
      if (e.shadowColor) out += '<rect x="' + (e.x + so) + '" y="' + (e.y + so) + '" width="' + w + '" height="' + h + '"' + r + ' fill="none" stroke="' + paint('sh', e.shadowColor, x1, y1, x2, y2) + '"' + sa + '/>';
      if (e.fillColor) out += '<rect x="' + e.x + '" y="' + e.y + '" width="' + w + '" height="' + h + '"' + r + ' fill="' + paint('fill', e.fillColor, x1, y1, x2, y2) + '"/>';
      out += '<rect x="' + e.x + '" y="' + e.y + '" width="' + w + '" height="' + h + '"' + r + ' fill="none" stroke="' + paint('line', e.color, x1, y1, x2, y2) + '"' + sa + '/>';
    } else if (o.type === 'ellipse') {
      var cx = e.x + e.w / 2, cy = e.y + e.h / 2, ell = function (dx, attrs) { return '<ellipse cx="' + n(cx + dx) + '" cy="' + n(cy + dx) + '" rx="' + n(e.w / 2) + '" ry="' + n(e.h / 2) + '"' + attrs + '/>'; };
      var bx = [e.x, e.y, e.x + e.w, e.y + e.h];
      if (e.shadowColor) out += ell(so, ' fill="' + paint('sh', e.shadowColor, bx[0], bx[1], bx[2], bx[3]) + '"');
      if (e.fillColor) out += ell(0, ' fill="' + paint('fill', e.fillColor, bx[0], bx[1], bx[2], bx[3]) + '"');
      out += ell(0, ' fill="none" stroke="' + paint('line', e.color, bx[0], bx[1], bx[2], bx[3]) + '"' + sa);
    } else if (o.type === 'line') {
      var lp = function (k, c) { return paint(k, c, e.x, e.y, e.x2, e.y2); };
      if (e.shadowColor) out += '<line x1="' + (e.x + so) + '" y1="' + (e.y + so) + '" x2="' + (e.x2 + so) + '" y2="' + (e.y2 + so) + '" stroke="' + lp('sh', e.shadowColor) + '"' + sa + '/>';
      var len = Math.hypot(e.x2 - e.x, e.y2 - e.y), size = arrowSize(e), back = size * Math.cos(ARROW_SPREAD) * 0.9;
      var ux = len ? (e.x2 - e.x) / len : 0, uy = len ? (e.y2 - e.y) / len : 0, end = e.arrowHead === 'end' || e.arrowHead === 'both', start = e.arrowHead === 'both';
      var c = lp('line', e.color);
      out += '<line x1="' + n(start ? e.x + ux * back : e.x) + '" y1="' + n(start ? e.y + uy * back : e.y) + '" x2="' + n(end ? e.x2 - ux * back : e.x2) +
        '" y2="' + n(end ? e.y2 - uy * back : e.y2) + '" stroke="' + c + '"' + sa + '/>';
      var head = function (ax, ay, bx2, by) {
        var a = Math.atan2(by - ay, bx2 - ax);
        return '<polygon points="' + n(bx2) + ',' + n(by) + ' ' + n(bx2 - size * Math.cos(a - ARROW_SPREAD)) + ',' + n(by - size * Math.sin(a - ARROW_SPREAD)) + ' ' +
          n(bx2 - size * Math.cos(a + ARROW_SPREAD)) + ',' + n(by - size * Math.sin(a + ARROW_SPREAD)) + '" fill="' + c + '"/>';
      };
      if (end) out += head(e.x, e.y, e.x2, e.y2);
      if (start) out += head(e.x2, e.y2, e.x, e.y);
    } else if (o.type === 'triangle') {
      var pts = e.x + ',' + e.y + ' ' + e.x2 + ',' + e.y2 + ' ' + e.x3 + ',' + e.y3, tp = function (k, c) { return paint(k, c, e.x, e.y, e.x2, e.y2); };
      // the module fills the shadow in place, with no offset
      if (e.shadowColor) out += '<polygon points="' + pts + '" fill="' + tp('sh', e.shadowColor) + '"/>';
      if (e.fillColor) out += '<polygon points="' + pts + '" fill="' + tp('fill', e.fillColor) + '"/>';
      out += '<polygon points="' + pts + '" fill="none" stroke="' + tp('line', e.color) + '"' + sa + '/>';
    } else if (o.type === 'string') {
      var l = D.text(e, D.shown(e)), a = l.area, font = ' font-family="' + esc(String(e.font)) + ', Fira Sans, sans-serif" font-size="' + n(l.size) + '"';
      var st = String(e.fontStyle).toLowerCase();
      font += (st.indexOf('bold') >= 0 ? ' font-weight="700"' : '') + (st.indexOf('italic') >= 0 ? ' font-style="italic"' : '');
      if (l.box) { defs += '<clipPath id="' + uid + '-clip"><rect x="' + a.x + '" y="' + a.y + '" width="' + a.w + '" height="' + a.h + '"/></clipPath>'; out += '<g clip-path="url(#' + uid + '-clip)">'; }
      if (e.background) out += '<rect x="' + n(a.x) + '" y="' + n(a.y) + '" width="' + n(a.w) + '" height="' + n(a.h) + '" rx="' + num(e.radius, 0) + '" fill="' + D.css(e.background) + '"/>';
      var outline = num(e.outlineWidth, 0);
      l.rows.forEach(function (row, i) {
        if (!row.text) return;
        var t = function (x, y, attrs) { return '<text x="' + n(x) + '" y="' + n(y) + '"' + font + attrs + ' xml:space="preserve" style="white-space:pre;font-kerning:none">' + esc(row.text) + '</text>'; };
        if (e.shadowColor) out += t(row.x + so, row.base + so, ' fill="' + D.css(e.shadowColor) + '"');
        if (outline > 0 && e.outlineColor) out += t(row.x, row.base, ' fill="none" stroke="' + D.css(e.outlineColor) + '" stroke-width="' + 2 * outline + '" stroke-linejoin="round" stroke-linecap="round"');
        var fill = e.gradientColor ? gx('t' + i, e.color, Math.round(row.x), Math.round(row.base - l.ascent), Math.round(row.x + row.w), Math.round(row.base + l.descent)) : D.css(e.color);
        out += t(row.x, row.base, ' fill="' + fill + '"');
      });
      if (l.box) out += '</g>';
    } else if (o.type === 'image') {
      var im = D.image(e.imageFile);
      if (im && im.data) {
        var d = imageRect(e, im.width, im.height);
        out += '<image href="' + im.data + '" x="' + d.x + '" y="' + d.y + '" width="' + d.w + '" height="' + d.h + '" preserveAspectRatio="none"/>';
      } else {
        var bb = D.bounds(o);
        out += '<rect class="img-wait" x="' + bb.x + '" y="' + bb.y + '" width="' + bb.w + '" height="' + bb.h + '" fill="none"/>';
      }
    }
    return { defs: defs, out: out };
  }

  /** What the layer is, in words, for its accessible name and the layer list. */
  D.kind = function (o) {
    if (o.type === 'webPage') return 'web page';
    if (o.type === 'string') return U.draw.hasToken(o.text) ? 'date and time' : 'text';
    if (o.type === 'image') return 'image';
    if (o.type === 'rectangle') return num(o.arcWidth, -1) > 0 ? 'rounded rectangle' : 'rectangle';
    if (o.type === 'line') return o.arrowHead && o.arrowHead !== 'none' ? 'arrow' : 'line';
    return o.type || 'layer';
  };
  /** Whether a text holds a token, roughly as TextTokens.hasTokens: an opening brace not doubled, then a kind and a colon or brace. */
  D.hasToken = function (text) { return /(^|[^{])\{(time|countdown|countup|streamtime)[:}]/.test(String(text || '')); };
  /** The layer's name in the list: its text, the shape it is, its file or its page's host. */
  D.name = function (o) {
    if (o.type === 'string') { var t = D.shown(o).split('\n')[0].trim(); return t ? (t.length > 40 ? t.slice(0, 39) + '…' : t) : 'Empty text'; }
    if (o.type === 'image') return o.imageFile ? String(o.imageFile).split('/').pop() : 'No image chosen';
    if (o.type === 'webPage') { try { return new URL(o.url).host; } catch (e) { return o.url || 'web page'; } }
    var k = D.kind(o);
    return k.charAt(0).toUpperCase() + k.slice(1);
  };
  /** The icon for a layer's kind. */
  D.icon = function (o) { return o.type === 'webPage' ? 'web' : o.type === 'string' ? (D.hasToken(o.text) ? 'clock' : 'text') : o.type === 'image' ? 'image' : 'shape'; };

  /** What rotation turns about, in source pixels: the center of the layer's area, or a free text's start (ObjectConfig.pivot). */
  D.pivot = function (o, ov) {
    var e = D.effective(o, ov), b = D.bounds(o, ov);
    return o.type === 'string' && !isBox(e) ? { x: e.x, y: e.y } : { x: b.x + b.w / 2, y: b.y + b.h / 2 };
  };
  /** The layer's accessible name: its kind and what it shows. */
  D.label = function (o) { return (D.kind(o) + ': ' + D.name(o)).replace(/^./, function (c) { return c.toUpperCase(); }); };

  /**
   * The layer as an SVG over its own area: viewBox in source pixels, so the element is scaled like position(), with
   * its accessible name. Rotation turns about D.pivot, as the module does. opts.unrotated leaves the turn out, for
   * the canvas, which turns the layer's whole box (handles included); opts.hidden leaves the name to that box.
   */
  D.svg = function (o, ov, uid, opts) {
    opts = opts || {};
    var e = D.effective(o, ov), b = D.bounds(o, ov), parts = body(o, e, uid || ('l' + Math.random().toString(36).slice(2, 8))), p = D.pivot(o, ov);
    return '<svg class="lyr-svg" ' + (opts.hidden ? 'aria-hidden="true"' : 'role="img" aria-label="' + esc(D.label(o)) + '"') + ' viewBox="' + n(b.x) + ' ' + n(b.y) + ' ' + n(b.w) + ' ' + n(b.h) + '" preserveAspectRatio="none" overflow="visible">' +
      (parts.defs ? '<defs>' + parts.defs + '</defs>' : '') +
      '<g opacity="' + e.opacity / 100 + '"' + (e.rotation && !opts.unrotated ? ' transform="rotate(' + n(e.rotation) + ' ' + n(p.x) + ' ' + n(p.y) + ')"' : '') + '>' + parts.out + '</g></svg>';
  };
})();
