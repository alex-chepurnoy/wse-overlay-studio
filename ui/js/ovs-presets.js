/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio presets (plan 4.3): ready layers to start from, each a single layer so it moves, times and deletes as
   one thing, landing at a set place on the frame; and text styles, settings applied to a selected text. Shared by the
   editor's "From a preset..." and New overlay's "A preset" start. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var P = U.presets = {};

  // sizes below are for a 1080p frame and scale with the frame's height, as new layers do
  var TEXT_STYLES = {
    title: { name: 'Title', font: 'Fira Sans', fontStyle: 'bold', fontSize: 72, color: 'white' },
    name: { name: 'Lower third name', font: 'Fira Sans Condensed', fontStyle: 'bold', fontSize: 48, color: 'white', background: 'rgb(0,0,0,180)', padding: 12 },
    role: { name: 'Lower third role', font: 'Fira Sans Condensed', fontStyle: 'plain', fontSize: 32, color: 'rgb(220,220,220)', background: 'rgb(0,0,0,180)', padding: 10 },
    caption: { name: 'Caption', font: 'Fira Sans', fontStyle: 'plain', fontSize: 36, color: 'white', background: 'rgb(0,0,0,150)', padding: 8, radius: 4 },
    ticker: { name: 'Ticker', font: 'Fira Sans Condensed', fontStyle: 'plain', fontSize: 40, color: 'white', background: 'rgb(20,20,20,220)', padding: 10 }
  };
  P.STYLES = Object.keys(TEXT_STYLES).map(function (k) { return { id: k, name: TEXT_STYLES[k].name }; });
  /** The fields a text style sets, for a frame h pixels tall. */
  P.style = function (id, h) {
    var s = TEXT_STYLES[id], k = (h || 1080) / 1080, out = {};
    if (!s) return null;
    Object.keys(s).forEach(function (f) { if (f !== 'name') out[f] = f === 'fontSize' || f === 'padding' || f === 'radius' ? Math.max(1, Math.round(s[f] * k)) : s[f]; });
    return out;
  };

  // shapes: the natural aspect of each kind, centered, 20% of the frame's width wide
  var SHAPES = [['rectangle', 'Rectangle', 320 / 120], ['rounded', 'Rounded rectangle', 320 / 120], ['square', 'Square', 1], ['ellipse', 'Ellipse', 240 / 140],
    ['circle', 'Circle', 1], ['line', 'Line', 0], ['arrow', 'Arrow', 0], ['triangle', 'Triangle', 200 / 170]];
  var DATES = [['HH:mm:ss', 'time'], ['h:mm:ss a', 'time'], ['HH:mm', 'time'], ['yyyy-MM-dd', 'time'], ['EEE, MMM d', 'time'], ['MMMM d, yyyy', 'time'],
    ['yyyy-MM-dd HH:mm:ss', 'time', 'for recordings'], ['yyyy-MM-dd HH:mm:ss z', 'time', 'for recordings'], ['H:mm:ss', 'countdown'],
    ["d'd' HH:mm:ss", 'countdown', 'for events days away'], ['mm:ss', 'countdown'], ['H:mm:ss', 'streamtime']];
  // under the "Date and time" heading a plain date or time is named by its pattern alone
  var KIND_WORD = { time: '', countdown: 'Countdown ', streamtime: 'Stream time ' };

  /** Every preset, grouped: {group, items: [{id, name, note, icon}]}. Text styles are not among them. */
  P.groups = function () {
    return [
      { group: 'Shapes', items: SHAPES.map(function (s) { return { id: 'shape:' + s[0], name: s[1], icon: s[0] === 'rectangle' ? 'rect' : s[0] }; }) },
      { group: 'Badges and bars', items: [
        { id: 'live', name: 'Live badge', note: 'white LIVE on a red box', icon: 'text' },
        { id: 'live-blink', name: 'Live badge, blinking', note: 'on a second, off a second', icon: 'text' },
        { id: 'lower', name: 'Lower third', note: 'name over role on a dark bar', icon: 'text' },
        { id: 'title', name: 'Title card', note: 'centered', icon: 'text' },
        { id: 'ticker', name: 'Ticker strip', note: 'along the bottom', icon: 'text' }] },
      { group: 'Corner bug', items: [{ id: 'bug', name: 'Corner bug', note: 'an image in the top right, waiting for its file', icon: 'image' }] },
      { group: 'Date and time', items: DATES.map(function (d, i) {
        return { id: 'date:' + i, name: KIND_WORD[d[1]] + d[0], note: d[2] || '', icon: 'clock', token: { kind: d[1], pattern: d[0] } };
      }) }
    ];
  };
  /** A short name for an overlay made from a preset: its name, or for a date or time what it is (Clock, Date, ...). */
  P.baseName = function (id) {
    var p = P.find(id);
    if (!p) return 'overlay';
    if (p.token) {
      if (p.token.kind === 'countdown') return 'Countdown';
      if (p.token.kind === 'streamtime') return 'Stream-time';
      return /[HhKkmsSa]/.test(p.token.pattern.replace(/'[^']*'/g, '')) ? 'Clock' : 'Date';
    }
    return p.name.replace(/[^A-Za-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  };
  /** The preset in words, for a toast: "the Live badge preset", "the countdown d'd' HH:mm:ss preset". */
  P.words = function (id) {
    var p = P.find(id);
    if (!p) return 'a preset';
    return 'the ' + (p.token && p.token.kind === 'time' ? 'date and time ' + p.name : p.name) + ' preset';
  };
  P.find = function (id) {
    var all = [].concat.apply([], P.groups().map(function (g) { return g.items; }));
    return all.filter(function (x) { return x.id === id; })[0] || null;
  };

  /**
   * The layer a preset makes on a frame {w, h}, with an id free in taken(id). Positions are fractions of the frame
   * (plan 4.3); text presets are boxes, so their position is their top-left corner.
   */
  P.layer = function (id, f, taken) {
    var p = P.find(id), k = f.h / 1080, r = Math.round;
    if (!p) return null;
    var free = function (prefix) { var n = 1; while (taken(prefix + '-' + n)) n++; return prefix + '-' + n; };
    var box = function (x, y, w, h) { return { x: r(x * f.w), y: r(y * f.h), w: r(w * f.w), h: r(h * f.h) }; };
    var text = function (o, b, style) { var s = P.style(style, f.h); Object.keys(s).forEach(function (key) { o[key] = s[key]; }); o.type = 'string'; o.box = true; o.opacity = 100; o.x = b.x; o.y = b.y; o.w = b.w; o.h = b.h; return o; };
    if (id.indexOf('shape:') === 0) {
      var kind = id.slice(6), s = SHAPES.filter(function (x) { return x[0] === kind; })[0], w = r(0.2 * f.w), h = s[2] ? r(w / s[2]) : 0;
      var x = r((f.w - w) / 2), y = r((f.h - h) / 2), o = { id: free('shape'), opacity: 100, color: 'white', stroke: s[2] ? Math.max(1, r(2 * k)) : Math.max(1, r(4 * k)) };
      if (kind === 'line' || kind === 'arrow') { o.type = 'line'; o.x = x; o.y = y; o.x2 = x + w; o.y2 = y; if (kind === 'arrow') o.arrowHead = 'end'; return o; }
      if (kind === 'triangle') { o.type = 'triangle'; o.fillColor = 'rgb(200,0,0)'; o.x = x; o.y = y + h; o.x2 = x + r(w / 2); o.y2 = y; o.x3 = x + w; o.y3 = y + h; return o; }
      o.type = kind === 'ellipse' || kind === 'circle' ? 'ellipse' : 'rectangle'; o.fillColor = 'rgb(30,90,200)';
      o.x = x; o.y = y; o.w = w; o.h = h;
      if (kind === 'rounded') { o.arcWidth = r(24 * k); o.arcHeight = o.arcWidth; }
      return o;
    }
    if (id === 'live' || id === 'live-blink') {
      var b = box(0.04, 0.05, 0.10, 0.06), live = text({ id: free('live'), text: 'LIVE', align: 'center', autoFit: true }, b, 'caption');
      live.fontStyle = 'bold'; live.fontSize = r(b.h * 0.6); live.background = 'rgb(200,0,0)'; live.radius = r(8 * k); live.padding = r(4 * k);
      if (id === 'live-blink') live.blinkSeconds = 1;
      return live;
    }
    if (id === 'lower') {
      var lb = box(0.05, 0.75, 0.50, 0.14), lo = text({ id: free('lower-third'), text: 'Name\nRole', align: 'left', lineHeight: 1.2, autoFit: true }, lb, 'name');
      lo.fontSize = r(lb.h * 0.34); lo.padding = r(14 * k);
      return lo;
    }
    if (id === 'title') {
      var tb = box(0.15, 0.40, 0.70, 0.20), ti = text({ id: free('title'), text: 'Title', align: 'center', autoFit: true }, tb, 'title');
      ti.fontSize = r(tb.h * 0.45); ti.background = 'rgb(0,0,0,150)'; ti.padding = r(16 * k); ti.radius = r(8 * k);
      return ti;
    }
    if (id === 'ticker') {
      var kb = box(0, 0.92, 1, 0.08), tk = text({ id: free('ticker'), text: 'First headline  ·  Second headline  ·  Third headline', align: 'left' }, kb, 'ticker');
      tk.fontSize = r(kb.h * 0.5); tk.padding = r(14 * k);
      return tk;
    }
    if (id === 'bug') {
      var bb = box(0.86, 0.05, 0.10, 0.10);
      return { id: free('bug'), type: 'image', opacity: 100, x: bb.x, y: bb.y, w: bb.w, h: bb.h };
    }
    if (p.token) {
      // a right-aligned box in the top right, in the Caption style, in the font clocks use
      var db = box(0.65, 0.05, 0.30, 0.07), dt = text({ id: free('clock'), text: '{' + p.token.kind + ':' + p.token.pattern + '}', align: 'right' }, db, 'caption');
      dt.font = 'Fira Mono'; dt.fontSize = r(db.h * 0.55);
      return dt;
    }
    return null;
  };

  /** The grouped list, each preset a button; a date and time preset shows its sample (filled by fillSamples). */
  P.listHtml = function (selected) {
    return '<div class="prelist" role="radiogroup" aria-label="Presets">' + P.groups().map(function (g) {
      return '<div class="pre-g"><div class="sec-h">' + U.esc(g.group) + '</div><div class="pre-items">' + g.items.map(function (x) {
        return '<button type="button" class="pre" role="radio" aria-checked="' + (x.id === selected) + '" data-preset="' + U.esc(x.id) + '">' + U.icon(x.icon) +
          '<span class="pre-t"><span class="pre-n">' + U.esc(x.name) + '</span>' + (x.note ? '<span class="faint">' + U.esc(x.note) + '</span>' : '') +
          (x.token ? '<span class="pre-s mono" data-presample="' + U.esc(x.token.kind + '|' + x.token.pattern) + '"></span>' : '') + '</span></button>';
      }).join('') + '</div></div>';
    }).join('') + '</div>';
  };
  /** Puts the server's sample beside each date and time preset in box; called again as the clock ticks. */
  P.fillSamples = function (box) {
    if (!box || !U.format) return;
    box.querySelectorAll('[data-presample]').forEach(function (x) {
      var a = x.getAttribute('data-presample').split('|'), v = U.format.sample({ kind: a[0], pattern: a.slice(1).join('|') }, {}, U.format.now());
      var t = v == null ? '' : v;
      if (x.textContent !== t) x.textContent = t;
    });
  };
})();
