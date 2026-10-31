/* URL Overlay task: put a page on a stream, in four steps (overlay, template, stream, preview), launched from On air
   and ending there. Every step uses the module's existing endpoints: the overlay test capture and save, a new
   template from a preset or a copy of an existing one, the stream assign with its dry run, and the stream preview. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var box = null, el = {};
  var T;
  function reset() {
    T = { step: 1, ov: { name: '', url: '', vw: 1280, vh: 720, x: 0, y: 0, w: 0, h: 0, speed: 0.5, test: null, seq: 0, timer: null, placed: false, pick: false },
      src: { w: 1920, h: 1080, stream: null, known: false }, tpl: { mode: 'new', name: '', preset: 2, existing: null, nameTouched: false },
      str: { mode: 'live', name: '', live: null, plan: null, planErr: null, planSeq: 0, applied: null }, made: { overlay: null, template: null, stream: null },
      busy: false, out: null, player: null, status: null, preview: null, watching: false };
  }
  reset();
  var NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
  function slug(s) { return String(s || '').toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'overlay'; }
  function host(u) { try { return new URL(u).hostname; } catch (e) { return ''; } }
  function ro() { return !!U.readOnly; }
  function overlayNames() { return (U.overlays || []).map(function (o) { return o.name; }); }
  function templateNames() { return (U.templates || []).filter(function (t) { return t && !t.error; }).map(function (t) { return t.name; }); }
  function unique(base, taken) { var n = base, i = 2; while (taken.indexOf(n) >= 0) n = base + '-' + i++; return n; }

  /* ---------- frame: stepper, body (main and summary), footer ---------- */
  var STEPS = [['Overlay', 'A web page on the picture'], ['Template', 'Which renditions carry it'], ['Stream', 'Where viewers see it'], ['Preview', 'What viewers get']];
  function done(n) {
    return n === 1 ? T.made.overlay : n === 2 ? T.made.template : n === 3 ? T.made.stream : false;
  }
  function stepSummary(n) {
    if (n === 1) return T.made.overlay;
    if (n === 2) return T.made.template;
    if (n === 3) return T.made.stream + (T.str.mode === 'later' ? ', starts later' : ', live');
    return '';
  }
  function renderStepper() {
    el.steps.innerHTML = STEPS.map(function (s, i) {
      var n = i + 1, cur = n === T.step, d = done(n) && !cur;
      var can = n <= T.step || done(n - 1);
      return '<li><button type="button" class="' + (d ? 'done' : cur ? '' : 'future') + '" data-step="' + n + '"' + (cur ? ' aria-current="step"' : '') + (can ? '' : ' disabled') + '>' +
        '<span class="n">' + (d ? '&#10003;' : n) + '</span><span class="x"><span class="t">' + s[0] + '</span><span class="s">' + U.esc(d ? stepSummary(n) : s[1]) + '</span></span>' +
        (d ? '<span class="edit">Edit</span>' : '') + '</button></li>';
    }).join('');
    el.steps.querySelectorAll('[data-step]').forEach(function (b) { b.onclick = function () { go(+b.getAttribute('data-step')); }; });
  }
  function renderSummary() {
    var t = T.made.template && U.templateEntry(T.made.template), outs = t ? U.outputsOf(t) : [], carry = outs.filter(function (o) { return o.carries; }).map(function (o) { return o.name; });
    var other = outs.filter(function (o) { return !o.carries; }).map(function (o) { return o.name; }), s = T.made.stream;
    el.sum.innerHTML = '<div class="ph"><h3>What viewers will get</h3></div><div class="pb"><div class="mini">' + (T.ov.test && T.ov.test.png ? miniPage() : '') + '</div><dl>' +
      '<dt>Overlay</dt><dd>' + (T.made.overlay ? U.esc(T.made.overlay) : '<span class="no">Step 1</span>') + '</dd>' +
      '<dt>Renditions</dt><dd>' + (t ? (carry.length ? U.esc(carry.join(', ')) + ' carry it' + (other.length ? '; ' + U.esc(other.join(', ')) + ' does not' : '') : 'None carries it') : '<span class="no">Step 2</span>') + '</dd>' +
      '<dt>Players open</dt><dd>' + (t ? U.esc(playName(s || '<stream>')) : '<span class="no">Step 2</span>') + '</dd>' +
      '<dt>Stream</dt><dd>' + (s ? U.esc(s) + (T.str.mode === 'later' ? ', when it starts' : '') : '<span class="no">Step 3</span>') + '</dd></dl></div>';
  }
  function miniPage() {
    var o = T.ov, s = T.src;
    return '<img alt="" src="data:image/png;base64,' + o.test.png + '" style="left:' + (100 * o.x / s.w) + '%;top:' + (100 * o.y / s.h) + '%;width:' + (100 * o.w / s.w) + '%;height:' + (100 * o.h / s.h) + '%">';
  }
  // the name players open: the adaptive group a new template has, else the first rendition that carries the overlay
  function playName(stream) {
    var t = T.made.template && U.templateEntry(T.made.template);
    if (t && T.tpl.mode === 'new' && U.outputsOf(t).filter(function (o) { return !o.audio; }).length > 1) return 'ngrp:' + stream + '_abr';
    var c = t ? U.outputsOf(t).filter(function (o) { return o.carries; })[0] : null;
    return c ? stream + '_' + c.name : stream;
  }
  function renderFoot(back, next, nextDisabled, extra) {
    el.foot.innerHTML = (T.step < 4 ? '<button type="button" class="btn ghost" data-close>Close</button>' : '') + (back ? '<button type="button" class="btn" data-back>' + back + '</button>' : '') + (extra || '') + '<span class="spacer"></span>' +
      '<span class="small faint">Step ' + T.step + ' of 4</span>' + (next ? '<button type="button" class="btn primary" data-next' + (nextDisabled || T.busy ? ' disabled' : '') + '>' + next + '</button>' : '');
    var b = el.foot.querySelector('[data-back]'); if (b) b.onclick = function () { go(T.step - 1); };
    // Close leaves what was already saved (an overlay, a template, a stream's copy) and starts the next task fresh
    var cl = el.foot.querySelector('[data-close]'); if (cl) cl.onclick = function () { stopPlayer(); if (T.ov.timer) { T.ov.timer.stop(); T.ov.timer = null; } reset(); U.go('home:'); };
  }
  function go(n) {
    if (n < 1 || n > 4) return;
    stopPlayer();
    T.step = n; render();
  }
  // the task fills the window below where it starts, so the footer and its primary action are always in view
  function fit() {
    var t = box && box.querySelector('.task');
    if (!t || U.current() !== 'task') return;
    var top = t.getBoundingClientRect().top + window.scrollY;
    t.style.height = Math.max(520, window.innerHeight - top - 12) + 'px';
  }
  function render() {
    if (!box || U.current() !== 'task') return;
    var a = document.activeElement, keep = a && a.id && box.contains(a) && /INPUT|SELECT/.test(a.tagName) ? { id: a.id, at: a.selectionStart } : null;
    renderStepper();
    [step1, step2, step3, step4][T.step - 1]();
    renderSummary();
    // steps 1 and 4 have their own side panel; the summary card is for steps 2 and 3
    el.body.classList.toggle('one', T.step === 1 || T.step === 4);
    if (keep) { var f = document.getElementById(keep.id); if (f && !f.disabled) { f.focus(); try { if (keep.at != null) f.setSelectionRange(keep.at, keep.at); } catch (e) { /* not a text field */ } } }
  }

  /* ---------- step 1: the page ---------- */
  function pickSource() {
    var live = U.live();
    if (!T.src.stream && live.length) T.src.stream = live[0].name;
    if (!T.src.stream) return Promise.resolve();
    return U.api.get(U.streamPath(U.app, T.src.stream) + '/status').then(function (st) {
      var t = st && st.transcoder;
      if (t && t.decoderWidth) { T.src.w = t.decoderWidth; T.src.h = t.decoderHeight; T.src.known = true; }
    }, function () {});
  }
  function place() {
    // top right, about a third of the frame wide, as most score bugs sit
    var s = T.src, o = T.ov, w = Math.round(s.w * 0.35 / 2) * 2, h = Math.round(w * o.vh / o.vw / 2) * 2;
    o.w = w; o.h = h; o.x = Math.round((s.w * 0.94 - w) / 2) * 2; o.y = Math.round(s.h * 0.06 / 2) * 2; o.placed = true;
  }
  function testNow() {
    var o = T.ov;
    if (o.timer) { o.timer.stop(); o.timer = null; }
    o.test = null;
    if (!o.url || U.urlProblem(o.url)) { render(); return; }
    var seq = ++o.seq, tries = 0, name = NAME.test(o.name) ? o.name : 'new-overlay';
    if (!o.name) o.name = unique(slug(host(o.url).split('.').slice(-2, -1)[0] || 'page'), overlayNames());
    render();
    o.timer = U.every(1500, function () {
      if (seq === o.seq && ++tries > 20 && !(o.test && o.test.png)) {
        o.test = { error: 'No picture from ' + host(o.url) + ' after 30 s. Check the address, or that the server can reach it, then press Capture again.' };
        if (o.timer) o.timer.stop(); render(); return null;
      }
      if (seq !== o.seq || U.current() !== 'task' || (o.test && o.test.png)) { if (o.timer) o.timer.stop(); return null; }
      return U.api.post(U.ovPath(name) + '/preview', { page: U.newPage('page1', o.url, o.vw, o.vh), width: 640, raw: false, refreshRate: 1 }).then(function (c) {
        if (seq !== o.seq) return;
        o.test = c;
        if (c && c.png && !o.placed) place();
        if (T.step === 1) render();
      }, function (e) { if (seq !== o.seq) return; o.test = { error: e.message }; render(); });
    });
  }
  function testState() {
    var t = T.ov.test, c = (t && t.capture) || {};
    if (!t) return ['', 'Capturing ' + U.esc(host(T.ov.url)) + ' on the server...'];
    if (t.error) return ['bad', U.esc(t.error)];
    if (c.state === 'blocked') return ['bad', 'Blocked: ' + U.esc(c.lastError || host(T.ov.url)) + '. A WSE administrator can allow the host with UrlOverlayAllowHosts.'];
    if (c.state === 'error' || c.state === 'service not running') return ['bad', U.esc(c.state + (c.lastError ? ': ' + c.lastError : ''))];
    return t.png ? ['on', 'Captured' + (c.lastCaptureMs != null ? ' in ' + c.lastCaptureMs + ' ms' : '')] : ['', 'Capturing...'];
  }
  function step1() {
    var o = T.ov, has = !!(o.test && o.test.png), s = T.src, ts = o.url ? testState() : null;
    var live = U.live();
    var stage = '<div class="stagebox"><div class="tstage' + (has ? '' : ' blank') + '" style="aspect-ratio:' + s.w + ' / ' + s.h + '" data-stage>' +
      (has ? '<div class="tpage" data-page tabindex="0" aria-label="The page; drag to move, arrow keys nudge" style="left:' + (100 * o.x / s.w) + '%;top:' + (100 * o.y / s.h) + '%;width:' + (100 * o.w / s.w) + '%;height:' + (100 * o.h / s.h) + '%">' +
        '<img alt="Captured page" src="data:image/png;base64,' + o.test.png + '"><span class="tag">' + U.esc(o.name || 'page') + '</span><span class="grip" data-grip aria-hidden="true"></span></div>'
      : '<div class="cta"><h3>Paste the address of the page to show</h3><p>A scoreboard, clock or lower third the server can reach. A transparent page shows the video through.</p>' +
        '<form class="row" data-urlform><input type="url" data-url placeholder="https://" value="' + U.esc(o.url) + '" aria-label="Page address" autocomplete="off" spellcheck="false"><button type="submit" class="btn primary">Capture</button></form>' +
        (ts ? '<p class="cap-state ' + ts[0] + '">' + ts[1] + '</p>' : '') + '</div>') +
      '<span class="lbl">' + (T.src.stream ? U.esc(T.src.stream) + ', ' : '') + s.w + ' x ' + s.h + (s.known ? '' : ', assumed') + '</span></div></div>';
    var main = '<div class="tpanel"><div class="ph"><h3>' + (has ? 'Drag the page into place' : 'Start with a web page') + '</h3><span class="spacer"></span>' +
      (has ? '<span class="chips"><span class="bdg">x ' + o.x + ', y ' + o.y + '</span><span class="bdg">' + o.w + ' x ' + o.h + '</span>' +
        (o.x + o.w > s.w * 0.95 || o.y + o.h > s.h * 0.95 ? '<span class="bdg warn">Past title safe</span>' : '<span class="bdg on">Inside title safe</span>') + '</span>' : '') + '</div><div class="pb fill">' + stage + '</div></div>';
    var side = '<div class="tpanel"><div class="ph"><h3>Overlay</h3></div><div class="pb">' +
      (has ? '<div><label class="f" for="uo-t-url">Page address</label><form class="row" data-urlform><input id="uo-t-url" type="url" data-url value="' + U.esc(o.url) + '" spellcheck="false"><button type="submit" class="btn">Capture</button></form></div>' +
        '<div class="chips"><span class="bdg ' + (ts[0] === 'bad' ? 'bad' : ts[0]) + '">' + ts[1] + '</span></div>' : '') +
      (has ? '<div><label class="f" for="uo-t-name">Name</label><input id="uo-t-name" type="text" data-oname value="' + U.esc(o.name) + '" placeholder="for example Scoreboard" spellcheck="false" autocomplete="off"><div class="err" data-onameerr></div></div>' : !o.url ? '<p class="muted">The page is captured on the server by a sandboxed browser and drawn into the video.</p>' : '') +
      (has && live.length ? '<div><label class="f" for="uo-t-src">Preview against' + U.tip('The live picture the page is placed on here, to get its size right. Which stream shows the overlay is step 3.') + '</label><select id="uo-t-src" data-src>' + live.map(function (r) { return '<option' + (r.name === T.src.stream ? ' selected' : '') + '>' + U.esc(r.name) + '</option>'; }).join('') + '</select></div>' : '') +
      (!has ? '' : '<details class="adv"' + (T.advOpen ? ' open' : '') + '><summary>Advanced: speed, browser size</summary><div class="stack-3">' + U.speedHtml(o.speed, 'data-tspeed') +
      '<div class="grid2"><div><label class="f" for="uo-t-vw">Browser width</label><input id="uo-t-vw" type="number" data-vw value="' + o.vw + '"></div><div><label class="f" for="uo-t-vh">Browser height</label><input id="uo-t-vh" type="number" data-vh value="' + o.vh + '"></div></div>' +
      '<p class="small muted">Transparency, crop and more pages are in the overlay editor after this.</p></div></details>') +
      ((U.overlays || []).length ? '<div class="pickov">' + (o.pick ? '<label class="f" for="uo-t-pick">Use an overlay you already made</label><div class="row"><select id="uo-t-pick" data-pick>' + overlayNames().map(function (n) { return '<option>' + U.esc(n) + '</option>'; }).join('') +
        '</select><button type="button" class="btn" data-usepick>Use it</button></div>' : '<button type="button" class="linkbtn" data-showpick>Or use an overlay you already made</button>') + '</div>' : '') + '</div></div>';
    el.main.innerHTML = main + side;
    // an overlay picked from the list has no capture here; Continue goes on with it
    renderFoot(null, has ? (T.made.overlay === o.name ? 'Save and continue' : 'Save overlay and continue') : T.made.overlay ? 'Continue with ' + T.made.overlay : 'Save overlay and continue',
      has ? !!nameProblem(o.name) : !T.made.overlay);
    bind1();
  }
  function nameProblem(n) {
    if (!NAME.test(n) || n.indexOf('..') >= 0) return 'Letters, digits, dot, dash and underscore.';
    if (n !== T.made.overlay && overlayNames().indexOf(n) >= 0) return 'An overlay with this name exists. Pick another, or use it below.';
    return null;
  }
  function bind1() {
    var o = T.ov;
    el.main.querySelectorAll('[data-urlform]').forEach(function (f) {
      f.onsubmit = function (e) { e.preventDefault(); var u = f.querySelector('[data-url]').value.trim(); var p = U.urlProblem(u); if (p) { U.toast(p, { level: 'bad' }); return; } o.url = u; o.placed = false; testNow(); };
    });
    var nm = el.main.querySelector('[data-oname]');
    if (nm) nm.oninput = function () { o.name = nm.value.trim(); var p = nameProblem(o.name); el.main.querySelector('[data-onameerr]').textContent = o.name ? p || '' : ''; var n = el.foot.querySelector('[data-next]'); if (n) n.disabled = !!p; };
    var src = el.main.querySelector('[data-src]');
    if (src) src.onchange = function () { T.src.stream = src.value; T.src.known = false; pickSource().then(function () { if (o.placed) place(); render(); }); };
    ['vw', 'vh'].forEach(function (k) { var i = el.main.querySelector('[data-' + k + ']'); if (i) i.onchange = function () { var v = parseInt(i.value, 10); if (v >= 16) { o[k] = v; o.placed = false; testNow(); } }; });
    var adv = el.main.querySelector('details.adv'); if (adv) adv.ontoggle = function () { T.advOpen = adv.open; };
    U.bindSpeed(el.main, 'data-tspeed', function (v) { o.speed = v; });
    var sp = el.main.querySelector('[data-showpick]'); if (sp) sp.onclick = function () { o.pick = true; render(); };
    var up = el.main.querySelector('[data-usepick]');
    if (up) up.onclick = function () { T.made.overlay = el.main.querySelector('[data-pick]').value; T.ov.test = null; suggestTemplate(); go(2); };
    bindDrag();
    bindNext1();
  }
  function bindNext1() {
    var n = el.foot.querySelector('[data-next]');
    if (n) n.onclick = function () { if (!(T.ov.test && T.ov.test.png) && T.made.overlay) go(2); else saveOverlay(); };
  }
  function bindDrag() {
    var pg = el.main.querySelector('[data-page]'), stg = el.main.querySelector('[data-stage]');
    if (!pg || !stg) return;
    var o = T.ov, s = T.src;
    function clamp() { o.x = Math.max(0, Math.min(s.w - 16, o.x)); o.y = Math.max(0, Math.min(s.h - 16, o.y)); o.w = Math.max(32, o.w); o.h = Math.max(16, o.h); }
    function show() { pg.style.left = (100 * o.x / s.w) + '%'; pg.style.top = (100 * o.y / s.h) + '%'; pg.style.width = (100 * o.w / s.w) + '%'; pg.style.height = (100 * o.h / s.h) + '%'; }
    pg.addEventListener('pointerdown', function (e) {
      if (e.button !== 0) return;
      e.preventDefault();
      var r = stg.getBoundingClientRect(), k = s.w / r.width, sx = e.clientX, sy = e.clientY, ox = o.x, oy = o.y, ow = o.w, oh = o.h, resize = e.target.hasAttribute('data-grip');
      pg.setPointerCapture(e.pointerId);
      function mv(ev) {
        var dx = Math.round((ev.clientX - sx) * k / 2) * 2, dy = Math.round((ev.clientY - sy) * k / 2) * 2;
        if (resize) { o.w = ow + dx; o.h = Math.round(o.w * oh / ow / 2) * 2; } else { o.x = ox + dx; o.y = oy + dy; }
        clamp(); show();
      }
      function up() { pg.removeEventListener('pointermove', mv); pg.removeEventListener('pointerup', up); render(); }
      pg.addEventListener('pointermove', mv); pg.addEventListener('pointerup', up);
    });
    pg.addEventListener('keydown', function (e) {
      var st = e.shiftKey ? 10 : 2, d = { ArrowLeft: [-st, 0], ArrowRight: [st, 0], ArrowUp: [0, -st], ArrowDown: [0, st] }[e.key];
      if (!d) return;
      e.preventDefault(); o.x += d[0]; o.y += d[1]; clamp(); show();
    });
  }
  function saveOverlay() {
    var o = T.ov, why = nameProblem(o.name);
    if (why || !(o.test && o.test.png) || T.busy) return;
    var page = U.newPage('page1', o.url, o.vw, o.vh);
    page.x = o.x; page.y = o.y; page.w = o.w; page.h = o.h;
    T.busy = true; render();
    U.api.post(U.ovPath(o.name), { refreshRate: o.speed, threadDrawing: true, maxDrawingTime: U.fields.maxDrawingTime.def, objects: [page] }).then(function () {
      T.busy = false; T.made.overlay = o.name;
      if (o.timer) { o.timer.stop(); o.timer = null; }
      suggestTemplate();
      return U.refreshState(true).then(function () { go(2); });
    }, function (e) { T.busy = false; render(); U.banner('task', 'bad', 'The overlay was not saved.', e.message); });
  }

  /* ---------- step 2: the renditions ---------- */
  var LADDERS = [[1, '1080p, 720p, 360p'], [2, '720p, 360p'], [3, 'Single 720p']];
  // only before this task has made a template: going back to step 1 must not rename it into a second one
  function suggestTemplate() { if (!T.tpl.nameTouched && !T.made.template) T.tpl.name = unique(slug(T.made.overlay) + '-ladder', templateNames()); }
  function candidates() {
    return (U.templates || []).filter(function (t) { return t && !t.error && !t.vif && !U.copyBase(t) && (t.encodes || []).some(function (e) { return e.canCarryOverlay && e.enabled; }); });
  }
  function ladderHtml() {
    var outs = U.presetOutputs(T.tpl.preset);
    var vids = outs.filter(function (o) { return o.kind === 'video'; });
    return '<div class="ladder"><div class="rung in"><span class="res">' + U.icon('input') + '</span><div><b>Input</b><span class="small muted">The source picture, before scaling</span></div><span class="lbadge">' + U.icon('overlay') + U.esc(T.made.overlay) + '</span></div>' +
      vids.map(function (o) { var k = Math.max(0.3, o.height / 1080); return '<div class="rung"><span class="res"><i style="width:' + Math.round(50 * k) + 'px;height:' + Math.round(28 * k) + 'px"></i></span><div><b>' + U.esc(o.name) + '</b><span class="small muted">H.264 ' + Math.round(o.videoBitrate / 1000).toLocaleString() + ' kbps</span></div><span class="lbadge from">from input</span></div>'; }).join('') +
      (vids.length > 1 ? '<div class="rung"><span class="res">' + U.icon('link') + '</span><div><b>Adaptive group</b><span class="small muted">One name players open; it switches between ' + U.esc(vids.map(function (o) { return o.name; }).join(' and ')) + '</span></div><span></span></div>' : '') + '</div>';
  }
  function step2() {
    var p = T.tpl, list = candidates(), isNew = p.mode === 'new';
    if (!isNew && !p.existing && list.length) p.existing = list[0].name;
    var ex = !isNew && p.existing ? U.templateEntry(p.existing) : null, already = ex && ex.overlay === T.made.overlay;
    var copyName = ex && !already ? unique(slug(ex.name) + '-' + slug(T.made.overlay), templateNames()) : null;
    var mine = isNew && p.name === T.made.template, key = p.mode + '|' + (isNew ? p.preset + '|' + p.name : p.existing), same = T.made.template && key === p.madeKey;
    var nameErr = isNew ? (!NAME.test(p.name) ? 'Letters, digits, dot, dash and underscore.' : !mine && templateNames().indexOf(p.name) >= 0 ? 'A template with this name exists.' :
      U.streams().some(function (s) { return s.name === p.name; }) ? 'A stream has this name; a template cannot.' : null) : null;
    el.main.innerHTML = '<div class="tpanel"><div class="ph"><h3>Which renditions carry ' + U.esc(T.made.overlay) + '?</h3></div><div class="pb form">' +
      '<div class="radios two" role="radiogroup" aria-label="Template">' +
      '<label class="opt"><input type="radio" name="uo-t2" value="new"' + (isNew ? ' checked' : '') + '><span><b>A new template</b><span class="sub">A ladder made for this overlay</span></span></label>' +
      '<label class="opt' + (list.length ? '' : ' off') + '"><input type="radio" name="uo-t2" value="existing"' + (!isNew ? ' checked' : '') + (list.length ? '' : ' disabled') + '><span><b>Start from an existing template</b><span class="sub">' + (list.length ? 'Copied; the original is not changed' : 'None can carry an overlay') + '</span></span></label></div>' +
      (isNew ? '<div class="radios three" role="radiogroup" aria-label="Renditions">' + LADDERS.map(function (l) {
            return '<label class="opt"><input type="radio" name="uo-t2l" value="' + l[0] + '"' + (p.preset === l[0] ? ' checked' : '') + '><span><b>' + l[1] + '</b><span class="sub">' + (l[0] === 3 ? 'One rendition' : 'Players switch between them') + '</span></span></label>'; }).join('') + '</div>' + ladderHtml() +
          (nameErr ? '<p class="err">' + U.esc(nameErr) + ' Change the name under Advanced.</p>' : '')
        : '<div class="radios" role="radiogroup" aria-label="Existing template">' + list.map(function (t) {
          var users = U.streamsUsing(t.name).map(function (s) { return s.name; });
          return '<label class="opt"><input type="radio" name="uo-t2x" value="' + U.esc(t.name) + '"' + (t.name === p.existing ? ' checked' : '') + '><span><b>' + U.esc(t.name) + '</b><span class="sub">' +
            U.esc(U.outputsOf(t).map(function (o) { return o.name; }).join(', ')) + (users.length ? '; used by ' + U.esc(users.join(', ')) : '') + '</span></span>' +
            (t.overlay === T.made.overlay ? '<span class="spacer"></span><span class="bdg on">Already carries it</span>' : '') + '</label>'; }).join('') + '</div>' +
          (ex ? '<div class="chips">' + (already ? '<span class="bdg on">Used as it is: it already draws ' + U.esc(T.made.overlay) + '</span>' : '<span class="bdg">New copy: ' + U.esc(copyName) + '</span><span class="bdg on">' + U.esc(ex.name) + ' is not changed</span>') + '</div>' : '')) +
      '<details class="adv"' + (nameErr ? ' open' : '') + '><summary>Advanced: name, hardware, each rendition</summary><div class="stack-3">' +
      (isNew ? '<div><label class="f" for="uo-t-tn">Template name' + U.tip('Templates are shared by every application on this server.') + '</label><input id="uo-t-tn" type="text" data-tname value="' + U.esc(p.name) + '" spellcheck="false" autocomplete="off"></div>' : '') +
      '<p class="small muted">Hardware, codecs, bitrates, and overlays on the input or on chosen outputs are in the template editor, under Templates.</p></div></details></div></div>';
    renderFoot('Back', same || already ? 'Continue' : mine ? 'Save and continue' : isNew ? 'Create template and continue' : 'Make the copy and continue', isNew ? !!nameErr : !ex);
    el.main.querySelectorAll('[name=uo-t2]').forEach(function (r) { r.onchange = function () { p.mode = r.value; render(); }; });
    el.main.querySelectorAll('[name=uo-t2x]').forEach(function (r) { r.onchange = function () { p.existing = r.value; render(); }; });
    var tn = el.main.querySelector('[data-tname]'); if (tn) tn.onchange = function () { p.name = tn.value.trim(); p.nameTouched = true; render(); };
    el.main.querySelectorAll('[name=uo-t2l]').forEach(function (r) { r.onchange = function () { p.preset = +r.value; render(); }; });
    var n = el.foot.querySelector('[data-next]');
    if (n) n.onclick = function () { if (same) { go(3); return; } saveTemplate(isNew, ex, already, copyName, mine, key); };
  }
  function saveTemplate(isNew, ex, already, copyName, mine, key) {
    if (T.busy) return;
    if (already) { T.made.template = ex.name; T.tpl.madeKey = key; go(3); return; }
    T.busy = true; render();
    var body = isNew ? U.templateSpecFor(T.tpl.name, T.made.overlay, T.tpl.preset) : null;
    var req = mine ? U.api.post(U.vhostPath() + '/templates/' + U.enc(T.tpl.name) + '/spec', body) : isNew ? U.api.post(U.vhostPath() + '/templates', body)
      : U.api.post(U.vhostPath() + '/templates/' + U.enc(ex.name) + '/use', { how: 'copy', name: copyName, overlay: T.made.overlay, encodes: [] });
    req.then(function (r) {
      T.busy = false;
      T.made.template = isNew ? T.tpl.name : String((r && r.template) || copyName).replace(/\.xml$/, '');
      T.tpl.madeKey = key;
      T.str.plan = null; T.str.applied = null;
      return U.refreshState(true).then(function () { go(3); });
    }, function (e) { T.busy = false; render(); U.banner('task', 'bad', 'The template was not saved.', e.message); });
  }

  /* ---------- step 3: the stream ---------- */
  function target() { return T.str.mode === 'later' ? T.str.name : T.str.live; }
  function streamProblem(n) {
    if (!n) return null;
    if (!NAME.test(n) || n.indexOf('..') >= 0) return 'Letters, digits, dot, dash and underscore.';
    if (templateNames().indexOf(n) >= 0) return 'A template is already named ' + n + '. Pick another stream name.';
    if (U.live().some(function (s) { return s.name === n; })) return n + ' is live now: pick it under "A stream that is live".';
    return null;
  }
  function planFor() {
    var s = T.str, n = target();
    s.plan = null; s.planErr = null;
    if (!n || (s.mode === 'later' && streamProblem(n))) { render(); return; }
    var seq = ++s.planSeq;
    render();
    U.api.get(U.appPath() + '/templates/assign?scope=stream&template=' + U.enc(T.made.template) + '&stream=' + U.enc(n)).then(function (p) {
      if (seq === s.planSeq) { s.plan = p; if (T.step === 3) render(); }
    }, function (e) { if (seq === s.planSeq) { s.planErr = e.message; if (T.step === 3) render(); } });
  }
  function planRows() {
    var s = T.str, p = s.plan, n = target(), r = U.streamRow(n) || { name: n }, out = [];
    var appRestart = p.transcoderTurnsOn || p.templatesBefore !== p.templatesAfter;
    out.push(['', '<span class="pill" title="Writes ' + U.esc(p.writeFile || n + '.xml') + ', its own copy">' + U.esc(n) + '</span> will use <span class="pill">' + U.esc(T.made.template) + '</span>' + (r.own ? '<span class="small muted">, replacing its current copy</span>' : '')]);
    if (p.currentIsVif) out.push(['warn', '<b>VIF stops analyzing ' + U.esc(n) + '.</b> Other streams keep it.']);
    if (s.mode === 'live') {
      out.push(['', U.esc(T.made.overlay) + ' shows at once on the renditions ' + U.esc(n) + ' already has' + (r.overlay && r.overlay !== T.made.overlay ? '; ' + U.esc(r.overlay) + ' leaves it' : '')]);
      var now = U.outputsOf(U.templateOfStream(r)).map(function (o) { return o.name; }).sort().join(','), next = U.outputsOf(U.templateEntry(T.made.template)).map(function (o) { return o.name; }).sort().join(',');
      if (now !== next || !r.template) out.push(['warn', 'Its renditions change' + (next ? ' to ' + U.esc(next.split(',').join(', ')) : '') + ' when <b>' + U.esc(n) + ' restarts</b>: ' + (r.pulled ? 'it is pulled by WSE and resets' : r.arrives === 'server' ? 'it is published inside WSE and restarts with the application ' + U.esc(U.app) : 'it is pushed by an encoder and stops until the encoder reconnects') + '. You choose when, after Apply']);
      else out.push(['', 'Its renditions stay the same: ' + U.esc(next.split(',').join(', '))]);
    }
    if (appRestart) out.push(['warn', '<b>The application ' + U.esc(U.app) + ' needs one restart</b> ' + (p.transcoderTurnsOn ? 'to switch its transcoder on' : 'to look for a template named after each stream') + '. Every stream in it stops until it reconnects; after that, streams you prepare need none']);
    else if (s.mode === 'later') out.push(['on', '<b>Ready.</b> ' + U.esc(n) + ' uses it when it starts publishing']);
    return out;
  }
  function doneRows() {
    var s = T.str, res = s.applied, n = T.made.stream, out = [['on', '<span class="pill">' + U.esc(n) + '</span> uses <span class="pill">' + U.esc(T.made.template) + '</span>']];
    if (res.restartRequired) out.push(['warn', '<b>Restart the application ' + U.esc(U.app) + ' once</b> so it looks for ' + U.esc(n) + '.xml. Every stream in it stops until it reconnects.', 'app']);
    if (s.mode === 'live' && res.restartNeeded) out.push(['warn', '<b>New renditions start when ' + U.esc(n) + ' restarts.</b>', 'stream']);
    if (s.mode === 'later' && !res.restartRequired) out.push(['on', '<b>Ready.</b> ' + U.esc(n) + ' uses it when it starts publishing']);
    if (s.mode === 'live' && !res.restartNeeded && !res.restartRequired) out.push(['on', U.esc(T.made.overlay) + ' is on ' + U.esc(n) + ' now']);
    return out;
  }
  function planHtml(rows) {
    var k = 0;
    return '<div class="plan">' + rows.map(function (x) {
      return '<div class="' + (x[0] === 'warn' ? 'blk' : '') + '"><span class="ic ' + x[0] + '">' + (x[0] === 'on' ? '&#10003;' : x[0] === 'warn' ? '!' : ++k) + '</span><span>' + x[1] + '</span>' +
        (x[2] === 'app' ? '<button type="button" class="btn warn sm" data-write data-rapp>Restart ' + U.esc(U.app) + '</button>' : x[2] === 'stream' ? '<button type="button" class="btn warn sm" data-write data-rstream>Restart ' + U.esc(T.made.stream) + '</button>' : '<span></span>') + '</div>';
    }).join('') + '</div>';
  }
  function step3() {
    var s = T.str, live = U.live();
    if (!live.length && !s.applied && s.mode === 'live') s.mode = 'later';
    var n = target(), bad = s.mode === 'later' ? streamProblem(n) : null;
    if (s.mode === 'live' && !s.live && live.length) { s.live = live[0].name; planFor(); return; }
    var applied = !!s.applied;
    el.main.innerHTML = '<div class="tpanel"><div class="ph"><h3>Which stream shows it?</h3>' + (applied ? '<span class="spacer"></span><span class="bdg on">Applied to ' + U.esc(T.made.stream) + '</span>' : '') + '</div><div class="pb form">' +
      '<div class="radios two" role="radiogroup" aria-label="Stream">' +
      '<label class="opt' + (live.length ? '' : ' off') + '"><input type="radio" name="uo-t3" value="live"' + (s.mode === 'live' ? ' checked' : '') + (applied || !live.length ? ' disabled' : '') + '><span><b>A stream that is live</b><span class="sub">' + (live.length ? 'Publishing now' : 'None is publishing now') + '</span></span></label>' +
      '<label class="opt"><input type="radio" name="uo-t3" value="later"' + (s.mode === 'later' ? ' checked' : '') + (applied ? ' disabled' : '') + '><span><b>A stream that starts later</b><span class="sub">Name it; it uses this when it starts</span></span></label></div>' +
      (s.mode === 'later' ? '<div><label class="f" for="uo-t-sn">Stream name, as the encoder will publish it</label><input id="uo-t-sn" class="big' + (bad ? ' invalid' : '') + '" type="text" data-sname value="' + U.esc(s.name) + '" spellcheck="false" autocomplete="off"' + (applied ? ' disabled' : '') + '><div class="err">' + U.esc(bad || '') + '</div></div>'
        : live.length ? '<div class="radios" role="radiogroup" aria-label="Live stream">' + live.map(function (r) {
          var v = U.viewers(r), how = r.pulled ? 'Pulled by WSE' : r.arrives === 'server' ? 'Published inside WSE; restarts only with the application' : 'Pushed by an encoder';
          return '<label class="opt"><input type="radio" name="uo-t3l" value="' + U.esc(r.name) + '"' + (r.name === s.live ? ' checked' : '') + (applied ? ' disabled' : '') + '><span class="dot live" style="margin-top:6px"></span><span><b>' + U.esc(r.name) + '</b><span class="sub">' + how +
            (r.template ? '; ' + U.esc(r.template) + ' now' : '; not transcoded now') + (v != null ? '; ' + U.viewersText(v) : '') + '</span></span>' +
            (r.overlay && r.overlay !== T.made.overlay ? '<span class="spacer"></span><span class="bdg warn">' + U.esc(r.overlay) + ' leaves it</span>' : '') + '</label>';
        }).join('') + '</div>' : '<p class="muted">No stream is publishing to ' + U.esc(U.app) + ' now. Name one that starts later.</p>') +
      (n && !bad ? '<div><div class="sec-h">' + (applied ? 'Done' : 'What Apply does') + '</div>' + (applied ? planHtml(doneRows()) : s.planErr ? '<p class="err">' + U.esc(s.planErr) + '</p>' : s.plan ? planHtml(planRows()) : '<div class="skel"></div>') + '</div>' : '') +
      '<details class="adv"><summary>Advanced: template order for ' + U.esc(U.app) + '</summary><p class="small muted">Streams look for a template named after themselves first. The order is in Templates, Template order; changing it restarts the application.</p></details></div></div>';
    var nextLabel = applied ? 'Continue to Preview' : 'Apply to ' + (n || 'the stream');
    renderFoot('Back', nextLabel, !applied && (!n || !!bad || !s.plan || ro()));
    el.main.querySelectorAll('[name=uo-t3]').forEach(function (r) { r.onchange = function () { s.mode = r.value; planFor(); }; });
    el.main.querySelectorAll('[name=uo-t3l]').forEach(function (r) { r.onchange = function () { s.live = r.value; planFor(); }; });
    var sn = el.main.querySelector('[data-sname]');
    if (sn) { sn.oninput = U.debounce(function () { s.name = sn.value.trim(); planFor(); }, 400); }
    var ra = el.main.querySelector('[data-rapp]');
    if (ra) ra.onclick = function () { U.restartApp('<b>' + U.esc(T.made.stream) + '</b> picks up ' + U.esc(T.made.template) + ' once ' + U.esc(U.app) + ' has restarted.').then(function (ok) { if (ok === true) { s.applied.restartRequired = false; render(); } }); };
    var rs = el.main.querySelector('[data-rstream]');
    if (rs) rs.onclick = function () { U.restartStream(T.made.stream, false, 'Its new renditions start now.').then(function (ok) { if (ok === true) { s.applied.restartNeeded = false; render(); } }); };
    var nx = el.foot.querySelector('[data-next]');
    if (nx) nx.onclick = applied ? function () { go(4); } : apply;
  }
  function apply() {
    var s = T.str, n = target();
    if (T.busy || !n || !s.plan) return;
    T.busy = true; render();
    U.api.post(U.appPath() + '/templates/assign', { scope: 'stream', template: T.made.template, stream: n }).then(function (res) {
      T.busy = false; T.made.stream = n; s.applied = res || {};
      if (s.mode === 'later' && U.added.indexOf(n) < 0) U.added.push(n);
      return U.refreshState(true).then(render);
    }, function (e) { T.busy = false; render(); U.banner('task', 'bad', n + ' was not changed.', e.message); });
  }

  /* ---------- step 4: what viewers get ---------- */
  function stopPlayer() { if (T.player) { T.player.stop(); T.player = null; } T.watching = false; }
  // at most one round of reads out at a time: a slow server with the state moving every poll does not pile them up
  var previewOut = false;
  function loadPreview() {
    var n = T.made.stream, r = n && U.streamRow(n);
    if (!r || !r.publishing || previewOut) return;
    previewOut = true;
    var done = function () { previewOut = false; };
    if (T.watching) { U.api.get(U.streamPath(U.app, n) + '/status').then(function (st) { T.status = st; var c = el.main.querySelector('[data-tchips]'); if (c && T.step === 4) c.innerHTML = chipsHtml(U.streamRow(n) || {}); }, function () {}).then(done); return; }
    var p = U.streamPath(U.app, n);
    Promise.all([
      U.api.get(p + '/preview').then(function (pv) { T.preview = pv; if (T.step === 4 && !T.watching) render(); }, function () {}),
      U.api.get(p + '/status').then(function (st) {
        T.status = st;
        if (T.step !== 4) return;
        // while a rendition plays only the chips change, so the video is not replaced
        if (T.watching) { var c = el.main.querySelector('[data-tchips]'); if (c) c.innerHTML = chipsHtml(U.streamRow(n) || {}); } else render();
      }, function () {})
    ]).then(done);
  }
  function chipsHtml(r) {
    var st = T.status || {}, tr = st.transcoder || {}, caps = (st.objects || []).filter(function (x) { return x.capture; }), pend = r.restartNeeded;
    var outs = U.outputsOf(U.templateOfStream(r) || U.templateEntry(T.made.template));
    var eye = U.icon('eye');
    return (tr.state === 'running' ? '<span class="bdg on" title="Measured">' + eye + 'Transcoder running</span>' : '<span class="bdg warn">Transcoder ' + U.esc(tr.state || 'not reported') + '</span>') +
      caps.map(function (x) { var c = x.capture, ok = c.state === 'running' && c.lastCaptureAt; return '<span class="bdg ' + (ok ? 'on' : 'warn') + '" title="Measured">' + eye + 'Page ' + U.esc(x.id) + (ok ? ' captured ' + U.ago(c.lastCaptureAt) : ': ' + c.state) + '</span>'; }).join('') +
      (pend ? '<span class="bdg warn">Restart pending</span>' : '<span class="bdg cfg" title="Configured in the template, not measured on the video">Set for ' + U.esc(outs.filter(function (x) { return x.carries; }).map(function (x) { return x.name; }).join(', ') || 'no rendition') + '</span>');
  }
  function step4() {
    var n = T.made.stream, r = U.streamRow(n) || { name: n }, live = !!r.publishing, t = U.templateOfStream(r) || U.templateEntry(T.made.template);
    var outs = U.outputsOf(t), pend = live && r.restartNeeded;
    if (!T.out || !outs.some(function (o) { return o.name === T.out; })) T.out = (outs.filter(function (o) { return o.carries; })[0] || outs[0] || {}).name || null;
    if (live && !T.preview) loadPreview();
    var o = outs.filter(function (x) { return x.name === T.out; })[0] || {}, pv = T.preview && (T.preview.outputs || []).filter(function (x) { return x.encode === T.out; })[0];
    var inner = !live ? '<div class="wait">' + U.esc(n) + ' is not live yet. When its encoder publishes, it uses ' + U.esc(T.made.template) + '.</div>'
      : o.passthrough ? '<div class="wait">' + U.esc(T.out) + ' is the stream as it arrives: it never carries an overlay.</div>'
      : pend ? '<div class="wait">The new renditions start when ' + U.esc(n) + ' restarts.</div>'
      : T.watching ? '<video muted playsinline></video><div class="wait" data-wait>Connecting...</div><button type="button" class="btn sm stopw" data-stopwatch>Stop watching</button>'
      : '<button type="button" class="playbig" data-watch>' + U.icon('play') + '<span>Watch ' + U.esc(T.out || n) + '</span></button>';
    el.main.innerHTML = '<div class="tpanel"><div class="ph"><h3>' + U.esc(n) + '</h3>' + (live ? '<span class="bdg on">Publishing</span>' : '<span class="bdg">Not live yet</span>') + '<span class="spacer"></span>' +
      (live ? '<div class="outs" role="group" aria-label="Rendition">' + outs.map(function (x) {
        return '<button type="button" class="outbtn" data-out="' + U.esc(x.name) + '" aria-pressed="' + (x.name === T.out) + '"><b>' + U.esc(x.name) + '</b><span class="small faint">' + (x.passthrough ? 'no overlay' : x.carries ? (pend ? 'after restart' : 'configured') : 'no overlay') + '</span></button>'; }).join('') + '</div>' : '') +
      '</div>' + (live ? '<div class="pb fill"><div class="stagebox"><div class="tstage dark" style="aspect-ratio:16 / 9">' + inner + '<span class="lbl">' + U.esc(n + (T.out ? '_' + T.out : '')) + '</span></div></div></div>'
        : '<div class="pb"><div class="waitcard">' + U.icon('play') + '<div><b>Ready when ' + U.esc(n) + ' starts</b><span class="small muted">When its encoder publishes, it uses ' + U.esc(T.made.template) + ' and shows ' + U.esc(T.made.overlay) + '. Come back to watch it here, or from On air.</span></div></div></div>') + '</div>' +
      '<div class="tpanel"><div class="ph"><h3>Status</h3></div><div class="pb">' +
      (live ? '<div class="chips" data-tchips>' + chipsHtml(r) + '</div>' +
        (pend ? '<div class="plan"><div class="blk"><span class="ic warn">!</span><span>New renditions start when ' + U.esc(n) + ' restarts.</span><button type="button" class="btn warn sm" data-write data-rstream>Restart ' + U.esc(n) + '</button></div></div>' : '')
        : '<div class="chips"><span class="bdg on" title="' + U.esc(n) + '.xml written">' + U.icon('eye') + 'Set up</span><span class="bdg cfg">Waiting for ' + U.esc(n) + ' to publish</span></div>') +
      '<div><label class="f">Players open' + U.tip('Players on ' + n + ' itself get the stream as it arrives, without the overlay.') + '</label><div class="copyrow"><span class="mono">' + U.esc(playName(n)) + '</span><span class="spacer"></span><button type="button" class="btn sm" data-copy>Copy</button></div></div>' +
      '<div class="hrow"><button type="button" class="btn sm" data-go="overlay:' + U.esc(T.made.overlay) + '">Edit the overlay</button><button type="button" class="btn sm" data-go="stream:' + U.esc(n) + '">Open the stream</button></div></div></div>';
    renderFoot('Back', 'Done');
    el.main.querySelectorAll('[data-out]').forEach(function (b) { b.onclick = function () { stopPlayer(); T.out = b.getAttribute('data-out'); render(); }; });
    var w = el.main.querySelector('[data-watch]'); if (w) w.onclick = function () { T.watching = true; render(); };
    var sw = el.main.querySelector('[data-stopwatch]'); if (sw) sw.onclick = function () { stopPlayer(); render(); };
    var v = el.main.querySelector('video'), url = pv ? U.hls(pv.hls) : null;
    if (v && !T.player) {
      var wait = el.main.querySelector('[data-wait]');
      if (!url) wait.textContent = 'This rendition cannot be played from here yet.';
      else { T.player = U.player(v, function (state, delay) { wait.hidden = state === 'playing'; if (state === 'waiting') wait.textContent = 'Waiting for the rendition. Retrying in ' + Math.round(delay / 1000) + ' s.'; }); T.player.load(url); }
    }
    var cp = el.main.querySelector('[data-copy]'); if (cp) cp.onclick = function () { try { navigator.clipboard.writeText(playName(n)); U.toast('Copied ' + playName(n) + '.'); } catch (e) { /* no clipboard */ } };
    var rs = el.main.querySelector('[data-rstream]'); if (rs) rs.onclick = function () { U.restartStream(n, false, 'Its new renditions start now.').then(function (ok) { if (ok === true) { T.preview = null; render(); } }); };
    var nx = el.foot.querySelector('[data-next]'); if (nx) nx.onclick = function () { stopPlayer(); var keep = T.made.stream; reset(); U.view.highlight = keep; U.go('home:'); };
  }

  U.startTask = function (opts) {
    if (ro()) return;
    if (!opts || !opts.resume) reset();
    U.go('task:');
  };
  U.screens.task = {
    init: function (b) {
      box = b;
      box.innerHTML = '<div class="task"><ol class="tsteps" data-tsteps aria-label="Create a new overlay and put it on a stream"></ol><div class="tbody" data-tbody><div class="tmain" data-tmain></div><div class="tpanel tsum" data-tsum></div></div>' +
        '<div class="tfoot" data-tfoot></div></div>';
      U.listen(window, 'resize', U.debounce(fit, 150));
      el = { steps: box.querySelector('[data-tsteps]'), body: box.querySelector('[data-tbody]'), main: box.querySelector('[data-tmain]'), sum: box.querySelector('[data-tsum]'), foot: box.querySelector('[data-tfoot]') };
    },
    show: function () {
      if (ro()) { U.go('home:'); return; }
      window.scrollTo(0, 0);
      setTimeout(fit, 0);
      if (T.step === 1) pickSource().then(render);
      render();
    },
    hide: function () { stopPlayer(); if (T.ov.timer) { T.ov.timer.stop(); T.ov.timer = null; } },
    state: function () {
      if (U.current() !== 'task') return;
      // live state changes only redraw steps that show it, and never under a field being typed in
      if (box.contains(document.activeElement) && /INPUT|SELECT/.test(document.activeElement.tagName)) return;
      if (T.step === 3) render();
      if (T.step === 4) { loadPreview(); if (!T.watching) render(); }
    },
    app: function () { stopPlayer(); reset(); },
    unsaved: function () { return T.ov.url && !T.made.overlay ? [{ label: 'New page ' + (T.ov.name || ''), go: 'task:' }] : []; }
  };
})();
