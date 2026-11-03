/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio date and time on the page (plan 3.3, 3.3a, 3.7): a text's tokens, the samples the server formats
   for them (GET /format; the browser cannot format java.time patterns or the duration grammar in the server's zone),
   the editor's clock with Freeze, and when a layer shows, as the module times it (ObjectConfig.timingAlpha). */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var T = U.format = {};

  T.KINDS = ['time', 'countdown', 'countup', 'streamtime'];
  var HOUR = 3600000;

  /**
   * A text's literal parts and tokens, as TextTokens.parse: {{ and }} are braces, a token runs from { to the first }
   * outside single quotes. {parts, error}; a text that does not parse has an error and no tokens.
   */
  T.parse = function (text) {
    var parts = [], lit = '', s = String(text == null ? '' : text), i = 0, n = s.length;
    var push = function () { if (lit) { parts.push({ lit: lit }); lit = ''; } };
    while (i < n) {
      var c = s.charAt(i);
      if (c === '{' && s.charAt(i + 1) === '{') { lit += '{'; i += 2; }
      else if (c === '}' && s.charAt(i + 1) === '}') { lit += '}'; i += 2; }
      else if (c === '}') return { parts: [], error: 'A } at character ' + (i + 1) + ' closes nothing; write }} for a brace' };
      else if (c === '{') {
        var end = -1, q = false;
        for (var j = i + 1; j < n; j++) { var d = s.charAt(j); if (d === '\'') q = !q; else if (d === '}' && !q) { end = j; break; } }
        if (end < 0) return { parts: [], error: 'The { at character ' + (i + 1) + ' is never closed; write {{ for a brace' };
        var body = s.slice(i + 1, end), colon = body.indexOf(':'), kind = colon < 0 ? body : body.slice(0, colon), pattern = colon < 0 ? '' : body.slice(colon + 1);
        if (T.KINDS.indexOf(kind) < 0) return { parts: [], error: 'Unknown token {' + kind + '}: use {time:...}, {countdown:...}, {countup:...} or {streamtime:...}' };
        if (!pattern) return { parts: [], error: '{' + kind + '} needs a pattern, as in {' + kind + ':' + (kind === 'time' ? 'HH:mm:ss' : 'H:mm:ss') + '}' };
        push();
        parts.push({ kind: kind, pattern: pattern, at: i, end: end + 1 });
        i = end + 1;
      } else { lit += c; i++; }
    }
    push();
    return { parts: parts, error: null };
  };
  T.tokens = function (text) { return T.parse(text).parts.filter(function (p) { return p.kind; }); };
  /** A token as written in a text. */
  T.token = function (kind, pattern) { return '{' + kind + ':' + pattern + '}'; };

  /* ---------- the editor's clock ---------- */
  // stream time and a missing target count from when the editor opened (plan 3.3, 3.7); Freeze stops time for editing
  var opened = Date.now(), frozenAt = null, listeners = [];
  T.start = function () { opened = Date.now(); };
  T.now = function () { return frozenAt != null ? frozenAt : Date.now(); };
  T.streamStart = function () { return opened; };
  T.frozen = function () { return frozenAt != null; };
  T.freeze = function (on) { frozenAt = on ? Date.now() : null; changed(); };
  T.onChange = function (fn) { listeners.push(fn); };
  function changed() { listeners.forEach(function (fn) { try { fn(); } catch (e) { /* one view's error leaves the others */ } }); }

  /* ---------- samples from the server ---------- */
  var cache = {}, wanted = {}, flushing = null, defaults = null;
  /** The server's default zone and locale, once an answer has come. */
  T.defaults = function () { return defaults; };
  // a countdown or count-up with no target is sampled against one an hour after the editor opened
  function nominal() { return new Date(opened + HOUR).toISOString(); }
  T.needsTarget = function (kind) { return kind === 'countdown' || kind === 'countup'; };
  function params(p, o) {
    return { kind: p.kind, pattern: p.pattern, zone: o.timeZone || '', locale: o.locale || '',
      target: T.needsTarget(p.kind) ? o.target || nominal() : '', endText: T.needsTarget(p.kind) ? o.endText || '' : '' };
  }
  function keyOf(q) { return [q.kind, q.pattern, q.zone, q.locale, q.target, q.endText].join('\u0001'); }
  // samples start on a boundary of their step, from the moment the token counts from, so each one is the value shown
  // for the whole of its step
  function aligned(q, step, t) {
    var base = q.kind === 'time' ? 0 : q.kind === 'streamtime' ? opened : Date.parse(q.target) || 0;
    return base + Math.floor((t - base) / step) * step;
  }
  function ask(q, t) {
    var k = keyOf(q), e = cache[k];
    if (e && e.pending) return;
    wanted[k] = { q: q, t: t };
    if (!flushing) flushing = setTimeout(flush, 150);
  }
  // asked after a pause, so typing a pattern asks once (plan 3.7)
  function flush() {
    flushing = null;
    var w = wanted; wanted = {};
    Object.keys(w).forEach(function (k) {
      var q = w[k].q, now = w[k].t, prev = cache[k];
      cache[k] = { pending: true, from: prev && prev.from, step: prev && prev.step, samples: prev ? prev.samples : [], error: prev ? prev.error : null };
      var qs = Object.keys(q).filter(function (x) { return q[x] !== ''; }).map(function (x) { return x + '=' + encodeURIComponent(q[x]); });
      qs.push('now=' + now, 'streamStart=' + opened, 'count=120');
      U.api.get('/format?' + qs.join('&')).then(function (r) {
        if (r && r.defaultZone) defaults = { zone: r.defaultZone, locale: r.defaultLocale };
        var step = r && r.stepMs > 0 ? r.stepMs : 1000;
        cache[k] = { from: now, step: step, samples: (r && r.samples) || [], error: (r && r.error) || null };
        changed();
      }, function () { cache[k] = { from: now, step: 1000, samples: [], error: null, failed: true }; });
    });
  }
  /** A token's value at t, or null until its samples come; asks again before they run out. */
  T.sample = function (p, o, t) {
    var q = params(p, o), k = keyOf(q), e = cache[k];
    if (!e || (!e.samples.length && !e.error && !e.pending && !e.failed)) { ask(q, aligned(q, 1000, t)); return null; }
    if (e.error || !e.samples.length) return null;
    var i = Math.floor((t - e.from) / e.step);
    if ((i < 0 || i > e.samples.length - 30) && !e.pending && !e.failed) ask(q, aligned(q, e.step, t));
    return e.samples[Math.max(0, Math.min(i, e.samples.length - 1))];
  };
  /** The validation error the server gave for a token, or null. */
  T.errorOf = function (p, o) { var e = cache[keyOf(params(p, o))]; return e ? e.error : null; };
  /** What a text layer shows at t: each token by its sample, or as written until the sample comes. */
  T.render = function (o, t) {
    var r = T.parse(o.text);
    if (r.error || !r.parts.some(function (p) { return p.kind; })) return o.text == null ? '' : String(o.text);
    var when = t == null ? T.now() : t;
    return r.parts.map(function (p) {
      if (!p.kind) return p.lit;
      var v = T.sample(p, o, when);
      return v == null ? T.token(p.kind, p.pattern) : v;
    }).join('');
  };
  /** Why the layer's tokens do not draw, or null: the text's braces, or the server's answer for a pattern. */
  T.problem = function (o) {
    var r = T.parse(o.text);
    if (r.error) return r.error;
    for (var i = 0; i < r.parts.length; i++) if (r.parts[i].kind) { var e = T.errorOf(r.parts[i], o); if (e) return e; }
    return null;
  };
  /** Whether a countdown or count-up in the text has no target, so it is drawn from a sample, faded. */
  T.noTarget = function (o) {
    return o.type === 'string' && !o.target && T.tokens(o.text).some(function (p) { return T.needsTarget(p.kind); });
  };
  /* ---------- when a layer shows (ObjectConfig.timingAlpha) ---------- */
  var STREAM = /^stream\+(\d{1,9}(\.\d{1,3})?)$/;
  // showFrom or hideAt in epoch milliseconds, or absent; stream+N counts from when the editor opened
  function timeOf(spec, absent) {
    if (spec == null || !String(spec).trim()) return absent;
    var m = STREAM.exec(String(spec).trim());
    if (m) return opened + Math.round(parseFloat(m[1]) * 1000);
    var t = Date.parse(String(spec).trim());
    return isNaN(t) ? Infinity : t;
  }
  /**
   * How the layer shows at t: alpha 0 to 1 as the module draws it; state 'before' or 'after' its show window, or
   * 'on'; and the moment it shows or hid at.
   */
  T.timing = function (o, t) {
    var show = timeOf(o.showFrom, -Infinity), hide = timeOf(o.hideAt, Infinity);
    if (t < show) return { alpha: 0, state: 'before', at: show, spec: o.showFrom };
    if (t >= hide) return { alpha: 0, state: 'after', at: hide, spec: o.hideAt };
    var b = o.blinkSeconds > 0 ? Math.round(o.blinkSeconds * 1000) : 0;
    if (b && Math.floor(t / b) % 2 === 1) return { alpha: 0, state: 'on', blinkOff: true };
    var a = 1, fin = o.fadeIn > 0 ? Math.round(o.fadeIn * 1000) : 0, fout = o.fadeOut > 0 ? Math.round(o.fadeOut * 1000) : 0;
    if (fin && show !== -Infinity) a = Math.min(a, (t - show) / fin);
    if (fout && hide !== Infinity) a = Math.min(a, (hide - t) / fout);
    return { alpha: Math.max(0, Math.min(1, a)), state: 'on' };
  };
  /** Whether the layer changes over time on its own: a token, a blink, a fade or a show window. */
  T.moves = function (o) {
    return !!(o.blinkSeconds > 0 || o.fadeIn > 0 || o.fadeOut > 0 || o.showFrom || o.hideAt || (o.type === 'string' && T.tokens(o.text).length));
  };
  /** A moment as the layer's zone shows it, for "Hidden now, shows at ...". */
  T.when = function (o, ms, spec) {
    if (spec && STREAM.test(String(spec).trim())) return String(spec).trim() + ' (stream time is simulated here)';
    var zone = o.timeZone || (defaults && defaults.zone) || undefined;
    try { return new Date(ms).toLocaleString('en-US', { timeZone: zone, dateStyle: 'medium', timeStyle: 'medium' }) + (zone ? ' ' + zone : ''); }
    catch (e) { return new Date(ms).toISOString(); }
  };
})();
