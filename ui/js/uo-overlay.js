/* URL Overlay step 1, Overlay: a named overlay's pages placed over a live preview of a stream. Edits stay in a draft
   until Save. Placement is in source pixels: screen pixels / (frame width / source width), clamped to the frame. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var F = U.fields;
  var OVERLAY_KEYS = ['refreshRate', 'threadDrawing', 'maxDrawingTime', 'even_coords', 'opacity', 'overlayIndex'];
  // the page settings a capture depends on; a page whose draft differs here is captured from the draft
  var CAPTURE_KEYS = ['url', 'viewportWidth', 'viewportHeight', 'keyColor', 'keyTolerance', 'paintBackground', 'pageDelay', 'cropX', 'cropY', 'cropW', 'cropH'];
  var el = {};
  var S = { name: null, saved: null, draft: null, layer: null, tab: 'page', preview: null, previewInfo: null, status: null,
    captures: {}, raw: {}, rawAt: {}, busy: {}, seq: 0, shown: {}, capStatus: {}, drafted: {}, saving: false, player: null, timer: null, statusTimer: null,
    undo: [], redo: [], lastSnap: null, known: null, conflict: false, theirs: null, justSaved: false, soon: {}, guides: false, setOpen: false };

  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  function objects() { return (S.draft && S.draft.objects) || []; }
  function byId(id) { return objects().filter(function (o) { return o.id === id; })[0] || null; }
  function savedById(id) { return ((S.saved && S.saved.objects) || []).filter(function (o) { return o.id === id; })[0] || null; }
  function sorted() { return objects().slice().sort(function (a, b) { return (b.zOrder || 0) - (a.zOrder || 0) || String(a.id).localeCompare(String(b.id)); }); }
  function host(u) { try { return new URL(u).host; } catch (e) { return u || ''; } }
  function ovPath(name) { return U.vhostPath() + '/applications/' + U.enc(U.app) + '/overlays/' + U.enc(name || S.name); }
  function even(n) { return S.draft && S.draft.even_coords ? Math.round(n / 2) * 2 : Math.round(n); }
  function src() {
    var t = S.status && S.status.transcoder;
    if (t && t.decoderWidth) return { w: t.decoderWidth, h: t.decoderHeight, known: true };
    var v = el.stage.querySelector('video');
    if (v && v.videoWidth) return { w: v.videoWidth, h: v.videoHeight, known: true };
    return { w: 1920, h: 1080, known: false };
  }
  function size(o) { return { w: o.w > 0 ? o.w : (o.viewportWidth || 640), h: o.h > 0 ? o.h : (o.viewportHeight || 360) }; }
  function entry() { return (U.overlays || []).filter(function (o) { return o.name === S.name; })[0] || null; }

  /* ---------- draft bookkeeping ---------- */
  // number of things that differ from the saved overlay: each page added, removed or changed, and the overlay settings
  function changes() {
    if (!S.draft || !S.saved) return 0;
    var n = 0;
    objects().forEach(function (o) { var s = savedById(o.id); if (!s || JSON.stringify(s) !== JSON.stringify(o)) n++; });
    S.saved.objects.forEach(function (s) { if (!byId(s.id)) n++; });
    if (OVERLAY_KEYS.some(function (k) { return JSON.stringify(S.saved[k]) !== JSON.stringify(S.draft[k]); })) n++;
    return n;
  }
  function edited(id) { var o = byId(id), s = savedById(id); return !s || JSON.stringify(s) !== JSON.stringify(o); }
  function isDraftPage(o) {
    var s = savedById(o.id);
    return !s || CAPTURE_KEYS.some(function (k) { return JSON.stringify(s[k]) !== JSON.stringify(o[k]); });
  }
  // one undo step per action; repeated nudges or typing in one field within a second are one step
  function snapshot(tag) {
    var now = Date.now();
    if (tag && S.lastSnap && S.lastSnap.tag === tag && now - S.lastSnap.at < 1000) { S.lastSnap.at = now; return; }
    S.undo.push(JSON.stringify(S.draft));
    if (S.undo.length > 60) S.undo.shift();
    S.redo = [];
    S.lastSnap = { tag: tag, at: now };
  }
  function restore(json) {
    S.draft = JSON.parse(json);
    if (S.layer && !byId(S.layer)) { var top = sorted()[0]; S.layer = top ? top.id : null; }
    S.lastSnap = null;
    renderEditor(); captureSoonAll();
  }
  function renderEditor() { renderLayers(); renderInspector(); placeBoxes(); renderBar(); }
  function undo() { if (!S.undo.length) return; S.redo.push(JSON.stringify(S.draft)); restore(S.undo.pop()); }
  function redo() { if (!S.redo.length) return; S.undo.push(JSON.stringify(S.draft)); restore(S.redo.pop()); }

  function normalize(o) {
    var n = {};
    OVERLAY_KEYS.forEach(function (k) { if (o && o[k] !== undefined) n[k] = o[k]; });
    if (!n.refreshRate) n.refreshRate = F.refreshRate.def;
    if (n.threadDrawing === undefined) n.threadDrawing = true;
    n.objects = ((o && o.objects) || []).map(function (x) {
      x = clone(x);
      if (!(x.viewportWidth > 0)) delete x.viewportWidth;
      if (!(x.viewportHeight > 0)) delete x.viewportHeight;
      if (x.type === 'webPage' && !(x.reloadRate > 0)) x.reloadRate = 0;
      return x;
    });
    return n;
  }

  /* ---------- leaving with unsaved changes ---------- */
  function leave() {
    if (!changes()) return Promise.resolve(true);
    return U.choose('Unsaved changes', '<p>Overlay <strong>' + U.esc(S.name) + '</strong> has ' + changes() + ' unsaved change(s).</p>',
      [{ label: 'Stay', value: 'stay' }, { label: 'Discard', value: 'discard' }, { label: 'Save', value: 'save', kind: 'primary' }]).then(function (v) {
        if (v === 'discard') { reset(); return true; }
        if (v === 'save') return save();
        return false;
      });
  }

  /* ---------- loading ---------- */
  function renderList(pick) {
    var list = U.overlays || [];
    var want = pick || S.name || U.store.get('overlay.' + U.app);
    if (!list.some(function (o) { return o.name === want; })) want = list.length ? list[0].name : null;
    el.ov.innerHTML = list.map(function (o) { return '<option' + (o.name === want ? ' selected' : '') + '>' + U.esc(o.name) + '</option>'; }).join('');
    el.empty.hidden = !!list.length;
    el.editor.hidden = !list.length;
    if (!want) { S.name = null; S.saved = S.draft = null; return Promise.resolve(); }
    if (want !== S.name) return open(want);
    renderChips();
    return Promise.resolve();
  }

  function open(name) {
    S.name = name; S.layer = null; S.captures = {}; S.raw = {}; S.capStatus = {}; S.drafted = {};
    S.undo = []; S.redo = []; S.conflict = false; S.theirs = null;
    U.store.set('overlay.' + U.app, name);
    var e = entry(); S.known = e ? e.modified : null;
    el.inspector.innerHTML = '<div class="skel"></div><div class="skel"></div><div class="skel"></div>';
    return U.api.get(ovPath(name)).then(function (o) {
      if (S.name !== name) return;
      S.saved = normalize(o); S.draft = clone(S.saved);
      var top = sorted()[0]; S.layer = top ? top.id : null;
      renderAll();
      startCaptures();
    }, function (err) { U.banner('ov', 'bad', 'Overlay ' + name + ' could not be read.', err.message); });
  }

  /* ---------- live state from the server ---------- */
  function onState() {
    if (!U.app || !el.ov) return;
    var e = entry();
    if (S.name && !e) {
      // deleted in another session
      if (changes()) U.toast('Overlay ' + S.name + ' was deleted in another session. Save puts your version back.', { level: 'bad' });
      else { U.toast('Overlay ' + S.name + ' was deleted in another session.'); S.name = null; }
    }
    if (S.name && e && S.known != null && e.modified !== S.known && !S.saving) {
      if (S.justSaved) { S.known = e.modified; S.justSaved = false; }
      else if (changes()) { S.conflict = true; S.theirs = e.modified; renderBar(); }
      else { S.known = e.modified; U.toast('Overlay ' + S.name + ' was changed in another session. Showing the new version.'); open(S.name); return; }
    } else if (S.name && e && S.known == null) S.known = e.modified;
    var names = (U.overlays || []).map(function (o) { return o.name; }).join('|');
    if (!S.name || !e || el.ov.getAttribute('data-sig') !== names) { el.ov.setAttribute('data-sig', names); renderList(); }
    else renderChips();
    loadPreviewChoices();
    renderBar();
  }

  function renderChips() {
    var e = entry(), live = U.liveShowing(S.name);
    var h = (e && e.templates && e.templates.length ? e.templates.map(function (t) { return '<span class="tag">template ' + U.esc(t) + '</span>'; }).join('') : '<span class="tag">in no template yet</span>') +
      (live.length ? '<span class="tag live">live on ' + U.esc(live.join(', ')) + '</span>' : '');
    el.chips.innerHTML = h;
  }

  /* ---------- preview stream ---------- */
  function loadPreviewChoices() {
    var streams = (U.appStreams || []).filter(function (s) { return s.publishing; });
    var want = S.preview || U.store.get('preview.' + U.app);
    if (!streams.some(function (s) { return s.name === want; })) want = streams.length ? streams[0].name : '';
    var sig = streams.map(function (s) { return s.name; }).join('|');
    if (el.preview.getAttribute('data-sig') !== sig) {
      el.preview.innerHTML = streams.length ? streams.map(function (s) { return '<option value="' + U.esc(s.name) + '"' + (s.name === want ? ' selected' : '') + '>' + U.esc(s.name) + '</option>'; }).join('')
                                            : '<option value="">no stream publishing</option>';
      el.preview.setAttribute('data-sig', sig);
    }
    el.liveBadge.hidden = !want;
    if (want !== (S.preview || '')) setPreview(want);
  }

  function setPreview(name) {
    S.preview = name || null; S.status = null; S.previewInfo = null;
    U.store.set('preview.' + U.app, S.preview || '');
    if (S.statusTimer) S.statusTimer.stop();
    renderStage();
    if (!S.preview) return;
    var p = U.streamPath(U.app, S.preview), mine = S.preview;
    U.api.get(p + '/preview').then(function (pv) {
      if (S.preview !== mine) return;
      S.previewInfo = pv; renderStage();
    }, function () {});
    // only for the source size: the decoded frame size is what placement is measured in
    S.statusTimer = U.every(5000, function () {
      if (S.preview !== mine || U.current !== 'uo-overlay' || document.visibilityState === 'hidden') return null;
      return U.api.get(p + '/status').then(function (st) {
        var had = S.status && S.status.transcoder && S.status.transcoder.decoderWidth;
        S.status = st;
        if (!had && st.transcoder && st.transcoder.decoderWidth) placeBoxes();
      }, function () {});
    });
  }

  /* ---------- captures: saved pages as the streams see them, changed pages from the draft ---------- */
  // the width the page is shown at, in device pixels; the preview capture is sent at that size
  function shownWidth(o) {
    var box = el.stage.querySelector('.layerbox[data-id="' + CSS.escape(o.id) + '"]');
    var css = Math.max(box ? box.clientWidth : 0, 160);
    return Math.min(2048, Math.ceil(css * (window.devicePixelRatio || 1) / 64) * 64);
  }
  // the whole page, for picking the key color and the crop, is fetched on those tabs only and at most every 3 s
  function wantRaw(o) {
    return S.layer === o.id && (S.tab === 'transparency' || S.tab === 'placement') && (!S.raw[o.id] || Date.now() - (S.rawAt[o.id] || 0) > 3000);
  }
  // answers can arrive out of order when two are out; one older than the picture shown is dropped
  function current(o, seq) {
    if (seq < (S.shown[o.id] || 0)) return false;
    S.shown[o.id] = seq;
    return true;
  }
  // a page's own capture error shows on the page; connection errors (0, 401, 403, 502) are the banners'
  function capError(o, e) {
    if ([0, 401, 403, 502].indexOf(e.status) >= 0) return;
    S.capStatus[o.id] = { state: 'error', lastError: e.message };
  }
  function capturePage(o, mine) {
    var raw = wantRaw(o), width = shownWidth(o), seq = ++S.seq;
    if (isDraftPage(o)) {
      if (urlProblem(o.url)) return Promise.resolve();
      return U.api.post(ovPath(mine) + '/preview', { page: o, width: width, raw: raw, refreshRate: S.draft.refreshRate }).then(function (c) {
        if (S.name !== mine || !current(o, seq)) return;
        S.capStatus[o.id] = c.capture || {};
        if (c.png) S.captures[o.id] = 'data:image/png;base64,' + c.png;
        if (c.rawPng) { S.raw[o.id] = 'data:image/png;base64,' + c.rawPng; S.rawAt[o.id] = Date.now(); }
        S.drafted[o.id] = true;
      }, function (e) { capError(o, e); });
    }
    var base = ovPath(mine) + '/objects/' + U.enc(o.id) + '/capture';
    var j = U.api.get(base + '?width=' + width).then(function (c) {
      if (S.name !== mine || !current(o, seq)) return;
      S.capStatus[o.id] = c.capture || {};
      if (c.png) S.captures[o.id] = 'data:image/png;base64,' + c.png;
      S.drafted[o.id] = false;
    }, function (e) { capError(o, e); });
    if (!raw) return j;
    return Promise.all([j, U.api.get(base + '?raw=true').then(function (c) { if (c.png) { S.raw[o.id] = 'data:image/png;base64,' + c.png; S.rawAt[o.id] = Date.now(); } }, function () {})]);
  }

  // one capture per page per interval, at most two a second, and only one request out per page
  function startCaptures() {
    if (S.timer) S.timer.stop();
    var mine = S.name;
    S.busy = {};
    S.timer = U.every(function () { return Math.max(500, 1000 * ((S.draft && S.draft.refreshRate) || 0.5)); }, function () {
      if (S.name !== mine || U.current !== 'uo-overlay' || !S.draft || document.visibilityState === 'hidden') return null;
      objects().forEach(function (o) {
        if (o.type !== 'webPage' || (S.busy[o.id] || 0) >= 1) return;
        S.busy[o.id] = (S.busy[o.id] || 0) + 1;
        capturePage(o, mine).then(function () { S.busy[o.id]--; if (S.name === mine) showCaptures(); });
      });
      return null;
    });
  }

  // a changed page is captured about 400 ms after the last keystroke, not at the next tick
  function captureSoon(id) {
    clearTimeout(S.soon[id]);
    var box = el.stage.querySelector('.layerbox[data-id="' + CSS.escape(id) + '"]');
    if (box) box.classList.add('updating');
    var mine = S.name;
    S.soon[id] = setTimeout(function () {
      var o = byId(id);
      if (!o || S.name !== mine || o.type !== 'webPage') return;
      capturePage(o, mine).then(function () { if (S.name === mine) { showCaptures(); var b = el.stage.querySelector('.layerbox[data-id="' + CSS.escape(id) + '"]'); if (b) b.classList.remove('updating'); } });
    }, 400);
  }
  function captureSoonAll() { objects().forEach(function (o) { if (o.type === 'webPage') captureSoon(o.id); }); }

  function showCaptures() {
    el.stage.querySelectorAll('.layerbox').forEach(function (b) {
      var id = b.getAttribute('data-id'), img = b.querySelector('img');
      if (S.captures[id]) { img.src = S.captures[id]; img.hidden = false; }
      var o = byId(id);
      b.classList.toggle('draft', !!(o && isDraftPage(o)));
      var nm = b.querySelector('.name');
      if (nm && o) nm.textContent = o.id + (isDraftPage(o) ? ', draft' : '');
    });
    el.layers.querySelectorAll('.page').forEach(function (l) {
      var id = l.getAttribute('data-id'), img = l.querySelector('.thumb img');
      if (S.captures[id]) { img.src = S.captures[id]; img.hidden = false; }
      var tag = l.querySelector('[data-tag]');
      if (tag) tag.outerHTML = pageTag(id);
    });
    var raw = el.inspector.querySelector('[data-raw]'), keyed = el.inspector.querySelector('[data-keyed]');
    if (raw && S.raw[S.layer]) { raw.src = S.raw[S.layer]; raw.hidden = false; }
    var craw = el.inspector.querySelector('[data-craw]');
    if (craw && S.raw[S.layer] && craw.getAttribute('src') !== S.raw[S.layer]) { craw.src = S.raw[S.layer]; craw.hidden = false; var w = el.inspector.querySelector('[data-cwait]'); if (w) w.hidden = true; }
    if (keyed && S.captures[S.layer]) { keyed.src = S.captures[S.layer]; keyed.hidden = false; }
    var line = el.inspector.querySelector('[data-capline]');
    if (line) line.innerHTML = capLine(S.layer);
  }

  function pageTag(id) {
    var c = S.capStatus[id], o = byId(id);
    if (c && c.state === 'service not running') return '<span class="tag bad" data-tag>no capture service</span>';
    if (c && (c.state === 'blocked' || c.state === 'error')) return '<span class="tag bad" data-tag>' + U.esc(c.state) + '</span>';
    if (o && isDraftPage(o)) return '<span class="tag warn" data-tag>draft</span>';
    if (!c) return '<span class="tag" data-tag>opening</span>';
    return '<span class="tag" data-tag hidden></span>';
  }

  function capLine(id) {
    var c = S.capStatus[id], o = byId(id);
    if (!c) return o && urlProblem(o.url) ? 'Enter the page address to see it.' : 'The capture service is opening the page.';
    if (c.state === 'blocked') return '<span class="err">Blocked: ' + U.esc(c.lastError || '') + '. An administrator can allow it with UrlOverlayAllowHosts (Server view).</span>';
    // the banner says how to start it; the page line only points there
    if (c.state === 'service not running') return '<span class="err">The capture service is not running, so this page is not captured. The banner above says how to start it.</span>';
    if (c.state === 'error') return '<span class="err">' + U.esc(c.lastError || 'Capture failed') + '</span>';
    var ch = c.chrome || {};
    return (S.drafted[id] ? 'Draft captured ' : 'Captured ') + U.ago(c.lastCaptureAt) + (c.lastCaptureMs != null ? ' in ' + c.lastCaptureMs + ' ms' : '') + (ch.pid ? ', Chrome ' + U.mb(ch.rssBytes) : '') + '.';
  }

  /* ---------- stage ---------- */
  function renderStage() {
    if (S.player) { S.player.stop(); S.player = null; }
    el.stage.innerHTML = '<div class="frame" data-frame="source" tabindex="0" aria-label="Preview: drag pages to place them"><video muted playsinline></video><div class="waiting" data-wait></div>' +
      '<div class="safe" data-safe' + (S.guides ? '' : ' hidden') + '></div>' +
      '<div class="lbl">' + (S.preview ? U.esc(S.preview) + ' with the overlay as drafted' : 'No stream: a blank frame of the assumed size') + '</div></div>';
    var v = el.stage.querySelector('video'), wait = el.stage.querySelector('[data-wait]');
    var url = U.hls(S.previewInfo && S.previewInfo.source && S.previewInfo.source.hls);
    if (S.preview && url) {
      S.player = U.player(v, function (state, delay) {
        wait.hidden = state === 'playing';
        if (state === 'waiting') wait.textContent = S.preview + ' is not reachable. Retrying in ' + Math.round(delay / 1000) + ' s.';
        else if (state === 'loading') wait.textContent = 'Connecting...';
        else if (state === 'unsupported') wait.textContent = 'This browser cannot play HLS.';
        if (state === 'playing') placeBoxes();
      });
      S.player.load(url);
      v.addEventListener('loadedmetadata', placeBoxes);
    } else {
      wait.textContent = S.preview ? 'Connecting...' : 'No stream is publishing in this application. Pages can still be placed on a frame of the assumed size.';
    }
    placeBoxes();
  }

  function placeBoxes() {
    var frame = el.stage.querySelector('[data-frame]');
    if (!frame) return;
    var s = src();
    frame.style.aspectRatio = s.w + ' / ' + s.h;
    frame.querySelectorAll('.layerbox').forEach(function (b) { b.remove(); });
    objects().slice().sort(function (a, b) { return (a.zOrder || 0) - (b.zOrder || 0); }).forEach(function (o) {
      var b = document.createElement('div');
      var draft = isDraftPage(o);
      b.className = 'layerbox' + (o.id === S.layer ? ' sel' : '') + (draft ? ' draft' : '');
      b.setAttribute('data-id', o.id);
      b.innerHTML = '<img alt="" hidden><div class="name">' + U.esc(o.id) + (draft ? ', draft' : '') + '</div>' +
        (o.id === S.layer ? '<div class="grip nw" data-g="nw"></div><div class="grip ne" data-g="ne"></div><div class="grip sw" data-g="sw"></div><div class="grip se" data-g="se"></div>' : '');
      if (S.captures[o.id]) { b.querySelector('img').src = S.captures[o.id]; b.querySelector('img').hidden = false; }
      if (o.opacity != null && o.opacity >= 0) b.querySelector('img').style.opacity = String(o.opacity / 100);
      frame.appendChild(b);
      position(b, o, s);
      b.addEventListener('pointerdown', function (e) { startDrag(e, o.id, e.target.getAttribute('data-g')); });
    });
    renderCoords();
  }

  function position(b, o, s) {
    var z = size(o);
    b.style.left = (100 * (o.x || 0) / s.w) + '%';
    b.style.top = (100 * (o.y || 0) / s.h) + '%';
    b.style.width = (100 * z.w / s.w) + '%';
    b.style.height = (100 * z.h / s.h) + '%';
  }

  function renderCoords() {
    var s = src(), o = S.layer && byId(S.layer), z = o ? size(o) : null;
    var outside = o && ((o.x || 0) >= s.w || (o.y || 0) >= s.h);
    el.coords.textContent = 'source ' + s.w + ' x ' + s.h + (s.known ? '' : ' (assumed)') +
      (o ? '   ' + o.id + ': x ' + (o.x || 0) + '  y ' + (o.y || 0) + '  ' + z.w + ' x ' + z.h : '');
    if (outside && s.known) U.banner('outside', 'slow', 'Page ' + o.id + ' is outside the picture.', 'It starts at ' + (o.x || 0) + ', ' + (o.y || 0) +
      ' but ' + S.preview + ' is ' + s.w + ' x ' + s.h + '. Move it on the Placement tab.');
    else U.clearBanner('outside');
  }

  function startDrag(e, id, grip) {
    if (e.button !== 0) return;
    e.preventDefault();
    // a press on another page selects it and drags it at once; its corner grips appear on release
    var fresh = S.layer !== id;
    if (fresh) {
      S.layer = id; grip = null; renderLayers(); renderInspector();
      el.stage.querySelectorAll('.layerbox').forEach(function (b) { b.classList.toggle('sel', b.getAttribute('data-id') === id); });
    }
    var frame = el.stage.querySelector('[data-frame]');
    var box = frame.querySelector('.layerbox[data-id="' + CSS.escape(id) + '"]');
    var o = byId(id), s = src(), scale = frame.clientWidth / s.w, z = size(o);
    var st = { x: e.clientX, y: e.clientY, ox: o.x || 0, oy: o.y || 0, ow: z.w, oh: z.h }, moved = false;
    box.setPointerCapture(e.pointerId);
    function move(ev) {
      if (!moved) { snapshot(); moved = true; }
      var dx = (ev.clientX - st.x) / scale, dy = (ev.clientY - st.y) / scale;
      var x = st.ox, y = st.oy, w = st.ow, h = st.oh;
      if (!grip) { x += dx; y += dy; }
      else {
        if (grip.indexOf('w') >= 0) { x += dx; w -= dx; }
        if (grip.indexOf('e') >= 0) w += dx;
        if (grip.indexOf('n') >= 0) { y += dy; h -= dy; }
        if (grip.indexOf('s') >= 0) h += dy;
      }
      w = Math.max(2, Math.min(w, s.w)); h = Math.max(2, Math.min(h, s.h));
      x = Math.max(0, Math.min(x, s.w - w)); y = Math.max(0, Math.min(y, s.h - h));
      o.x = even(x); o.y = even(y); o.w = even(w); o.h = even(h);
      position(box, o, s); renderCoords(); syncPlacement(); renderBar();
    }
    function up() {
      box.removeEventListener('pointermove', move); box.removeEventListener('pointerup', up); box.removeEventListener('pointercancel', up);
      if (moved) renderLayers();
      if (fresh) placeBoxes();
    }
    box.addEventListener('pointermove', move); box.addEventListener('pointerup', up); box.addEventListener('pointercancel', up);
  }

  function nudge(dx, dy) {
    var o = S.layer && byId(S.layer);
    if (!o) return;
    snapshot('nudge');
    var s = src(), z = size(o);
    o.x = Math.max(0, Math.min((o.x || 0) + dx, s.w - z.w));
    o.y = Math.max(0, Math.min((o.y || 0) + dy, s.h - z.h));
    var box = el.stage.querySelector('.layerbox[data-id="' + CSS.escape(o.id) + '"]');
    if (box) position(box, o, s);
    renderCoords(); syncPlacement(); renderBar(); markEdited();
  }

  /* ---------- pages ---------- */
  function renderLayers() {
    var list = sorted();
    if (!list.length) { el.layers.innerHTML = '<div class="empty sm"><p>No pages yet. Add the scoreboard page.</p><button class="btn primary sm" type="button" data-act="add-first">Add page</button></div>';
      el.layers.querySelector('[data-act=add-first]').onclick = addPage; return; }
    el.layers.innerHTML = list.map(function (o) {
      return '<div class="page' + (o.id === S.layer ? ' sel' : '') + '" draggable="true" data-id="' + U.esc(o.id) + '" tabindex="0" role="button" aria-pressed="' + (o.id === S.layer) + '">' +
        '<span class="grip-h" aria-hidden="true">&#8942;&#8942;</span>' +
        '<div class="thumb"><img alt="" hidden></div><div class="min0"><div class="nm"><span class="ellipsis">' + U.esc(o.id) + '</span>' +
        (edited(o.id) ? '<span class="edited" title="Unsaved changes"></span>' : '') + pageTag(o.id) + '</div>' +
        '<div class="sub">' + U.esc(o.type === 'webPage' ? host(o.url) : o.type) + '</div></div>' +
        '<span class="acts"><button class="btn icon" type="button" data-mv="-1" aria-label="Move page ' + U.esc(o.id) + ' up" title="Move up">&#x25B2;</button>' +
        '<button class="btn icon" type="button" data-mv="1" aria-label="Move page ' + U.esc(o.id) + ' down" title="Move down">&#x25BC;</button>' +
        '<button class="btn icon danger" type="button" data-rm="' + U.esc(o.id) + '" aria-label="Remove page ' + U.esc(o.id) + '" title="Remove page (Delete)">' +
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/></svg></button></span></div>';
    }).join('');
    var dragId = null;
    el.layers.querySelectorAll('.page').forEach(function (l) {
      var id = l.getAttribute('data-id');
      l.onclick = function (e) { if (e.target.closest('[data-rm],[data-mv]')) return; S.layer = id; renderLayers(); renderInspector(); placeBoxes(); };
      l.onkeydown = function (e) { if (e.target === l && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); l.onclick(e); } };
      l.addEventListener('dragstart', function (e) { dragId = id; e.dataTransfer.effectAllowed = 'move'; try { e.dataTransfer.setData('text/plain', id); } catch (x) { /* old browsers */ } });
      l.addEventListener('dragover', function (e) { e.preventDefault(); l.classList.add('drop'); });
      l.addEventListener('dragleave', function () { l.classList.remove('drop'); });
      l.addEventListener('drop', function (e) {
        e.preventDefault(); l.classList.remove('drop');
        if (!dragId || dragId === id) return;
        var order = sorted().map(function (o) { return o.id; }).filter(function (x) { return x !== dragId; });
        order.splice(order.indexOf(id), 0, dragId);
        reorder(order);
      });
      l.querySelectorAll('[data-mv]').forEach(function (b) {
        b.onclick = function (e) {
          e.stopPropagation();
          var order = sorted().map(function (o) { return o.id; }), i = order.indexOf(id), j = i + (+b.getAttribute('data-mv'));
          if (j < 0 || j >= order.length) return;
          order[i] = order[j]; order[j] = id;
          reorder(order);
          var again = el.layers.querySelector('.page[data-id="' + CSS.escape(id) + '"] [data-mv="' + b.getAttribute('data-mv') + '"]');
          if (again) again.focus();
        };
      });
    });
    el.layers.querySelectorAll('[data-rm]').forEach(function (b) { b.onclick = function (e) { e.stopPropagation(); removePage(b.getAttribute('data-rm')); }; });
    showCaptures();
  }
  // order: page ids, top first
  function reorder(order) {
    snapshot();
    order.forEach(function (x, i) { byId(x).zOrder = order.length - i; });
    renderLayers(); placeBoxes(); renderBar();
  }
  // the edited marks and tags follow the draft without rebuilding the list
  function markEdited() {
    el.layers.querySelectorAll('.page').forEach(function (l) {
      var id = l.getAttribute('data-id'), nm = l.querySelector('.nm'), dot = nm.querySelector('.edited');
      if (edited(id) && !dot) { var d = document.createElement('span'); d.className = 'edited'; d.title = 'Unsaved changes'; nm.insertBefore(d, nm.querySelector('[data-tag]')); }
      if (!edited(id) && dot) dot.remove();
    });
  }

  function removePage(id) {
    var o = byId(id);
    if (!o) return;
    snapshot();
    var before = S.undo[S.undo.length - 1];
    S.draft.objects = objects().filter(function (x) { return x.id !== id; });
    if (S.layer === id) { var top = sorted()[0]; S.layer = top ? top.id : null; }
    renderEditor();
    U.toast('Removed page ' + id + ' from the draft. Viewers keep it until Save.', { undo: function () { S.redo = []; restore(before); S.layer = id; renderLayers(); renderInspector(); placeBoxes(); } });
  }

  function urlProblem(url) {
    var u;
    try { u = new URL(url); } catch (e) { return 'Enter a full address starting with http:// or https://.'; }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return 'Only http and https pages can be captured.';
    if (u.username || u.password) return 'Remove user:password from the address; credentials are never stored.';
    return null;
  }

  function addPage() {
    var used = objects().map(function (o) { return o.id; }), n = 1;
    while (used.indexOf('page' + n) >= 0) n++;
    U.prompt('Add a page to ' + S.name, '<div class="fld"><label class="f" for="uo-pid">Name</label><input id="uo-pid" type="text" value="' + (used.length ? 'page' + n : 'score') + '" autocomplete="off">' +
      '<div class="hint">For you only, for example "score" or "clock".</div></div>' +
      '<div class="fld"><label class="f" for="uo-purl">' + F.url.label + '</label><input id="uo-purl" type="url" placeholder="https://" autocomplete="off">' +
      '<div class="hint">' + U.esc(F.url.help) + ' Use the vendor\'s transparent or "bug" view if they have one.</div></div>' +
      '<div class="grid2"><div class="fld"><label class="f" for="uo-pw">Page width (px)</label><input id="uo-pw" type="number" value="1280"></div>' +
      '<div class="fld"><label class="f" for="uo-ph">Page height (px)</label><input id="uo-ph" type="number" value="720"></div></div>' +
      '<div class="hint">The size the vendor designed the page for.</div>', 'Add', function (m) {
        var id = m.querySelector('#uo-pid').value.trim(), url = m.querySelector('#uo-purl').value.trim();
        var vw = parseInt(m.querySelector('#uo-pw').value, 10), vh = parseInt(m.querySelector('#uo-ph').value, 10);
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(id)) return 'Name: letters, digits, dot, dash and underscore.';
        if (used.indexOf(id) >= 0) return 'A page with this name exists.';
        var err = urlProblem(url); if (err) return err;
        if (!(vw >= 16 && vh >= 16)) return 'Enter the page size in pixels.';
        return { id: id, url: url, vw: vw, vh: vh };
      }).then(function (r) {
        if (!r) return;
        snapshot();
        var s = src(), top = sorted()[0];
        var w = even(Math.min(r.vw, s.w * 0.35)), h = even(w * r.vh / r.vw);
        S.draft.objects.push({ id: r.id, type: 'webPage', url: r.url, x: even(s.w - w - s.w * 0.03), y: even(s.h * 0.04), w: w, h: h,
          zOrder: top ? (top.zOrder || 0) + 1 : 1, opacity: 100, viewportWidth: r.vw, viewportHeight: r.vh, pageDelay: 1000, reloadRate: 0,
          keyColor: F.keyColor.def, keyTolerance: 0, paintBackground: true });
        S.layer = r.id; S.tab = 'page'; renderEditor(); captureSoon(r.id);
      });
  }

  /* ---------- inspector ---------- */
  function fieldHtml(name, value, opts) {
    var f = F[name], id = 'uo-f-' + name, v = value === undefined || value === null ? '' : value;
    opts = opts || {};
    var label = U.esc(f.label) + (f.unit ? ' (' + U.esc(f.unit) + ')' : '');
    var key = '<span class="key">' + U.esc(f.key) + '</span>', title = ' title="WSE setting ' + U.esc(f.key) + '"';
    if (f.type === 'bool') {
      return '<div class="fld"><label class="check"' + title + '><input type="checkbox" id="' + id + '" data-f="' + name + '"' + (value ? ' checked' : '') + '>' + label + '</label>' +
        '<div class="hint">' + (f.help ? U.esc(f.help) + ' ' : '') + key + '</div></div>';
    }
    var type = f.type === 'int' || f.type === 'num' ? 'number' : 'text';
    var attrs = (f.min !== undefined ? ' min="' + f.min + '"' : '') + (f.max !== undefined ? ' max="' + f.max + '"' : '') + (f.type === 'num' ? ' step="any"' : '');
    var sw = f.type === 'color' ? '<span class="swatch" data-swatch style="background:' + U.esc(v) + '"></span>' : '';
    return '<div class="fld"><label class="f" for="' + id + '">' + label + sw + '</label><input type="' + type + '" id="' + id + '" data-f="' + name + '" value="' + U.esc(v) + '"' + attrs + title +
      (opts.placeholder ? ' placeholder="' + U.esc(opts.placeholder) + '"' : '') + (type === 'number' ? ' class="num"' : '') + '><div class="err" data-err-for="' + name + '"></div><div class="hint">' + (f.help ? U.esc(f.help) + ' ' : '') + key + '</div></div>';
  }

  // settings that apply to every page of the overlay, under the page list
  function renderSettings() {
    var d = S.draft;
    if (!d) { el.settings.innerHTML = ''; return; }
    el.settings.innerHTML = '<details class="ov-set"' + (S.setOpen ? ' open' : '') + '><summary>Overlay settings <span class="muted small">for every page</span></summary>' +
      fieldHtml('refreshRate', d.refreshRate) + fieldHtml('evenCoords', !!d.even_coords) +
      fieldHtml('threadDrawing', d.threadDrawing !== false) + fieldHtml('maxDrawingTime', d.maxDrawingTime || F.maxDrawingTime.def) + '</details>';
    var det = el.settings.querySelector('details');
    det.addEventListener('toggle', function () { S.setOpen = det.open; });
    el.settings.querySelectorAll('[data-f]').forEach(function (i) { i.addEventListener('input', onField); i.addEventListener('change', onField); });
  }

  function renderInspector() {
    var o = S.layer && byId(S.layer), d = S.draft;
    renderSettings();
    if (!d) { el.inspector.innerHTML = ''; return; }
    if (!o) {
      el.inspector.innerHTML = '<div class="empty sm"><h3>No page selected</h3><p>Select a page in the list or on the preview, or add the page with the clock and score.</p>' +
        '<button type="button" class="btn primary" data-act="add2">Add page</button></div>';
      el.inspector.querySelector('[data-act=add2]').onclick = addPage;
      return;
    }
    var web = o.type === 'webPage';
    if (!web) S.tab = 'placement';
    var tabs = web ? [['page', 'Page'], ['transparency', 'Transparency'], ['placement', 'Placement'], ['timing', 'Timing']] : [['placement', 'Placement']];
    var h = '<div class="pane-h"><h3 class="wrap">' + U.esc(o.id) + '</h3></div>' +
      '<div class="tabs" role="tablist">' + tabs.map(function (t) { return '<button type="button" role="tab" data-tab="' + t[0] + '" aria-selected="' + (S.tab === t[0]) + '">' + t[1] + '</button>'; }).join('') + '</div>';
    if (S.tab === 'page') {
      h += fieldHtml('url', o.url) + '<p class="hint" data-capline>' + capLine(o.id) + '</p>' +
        '<div class="grid2">' + fieldHtml('viewportWidth', o.viewportWidth, { placeholder: String(size(o).w) }) + fieldHtml('viewportHeight', o.viewportHeight, { placeholder: String(size(o).h) }) + '</div>';
    } else if (S.tab === 'transparency') {
      h += '<div class="capture-pair"><div class="cap raw">' + (S.raw[o.id] ? '' : 'Loading the capture...') + '<img data-raw alt="As captured"' + (S.raw[o.id] ? ' src="' + S.raw[o.id] + '"' : ' hidden') + '><span>As captured</span></div>' +
        '<div class="cap keyed"><img data-keyed alt="After keying"' + (S.captures[o.id] ? ' src="' + S.captures[o.id] + '"' : ' hidden') + '><span>After keying</span></div></div>' +
        '<p class="hint">Click the left image to pick the color to make transparent. The result shows within about a second.</p>' +
        '<div class="grid2 mt-s">' + fieldHtml('keyColor', o.keyColor || F.keyColor.def) + fieldHtml('keyTolerance', o.keyTolerance || 0) + '</div>' +
        fieldHtml('paintBackground', o.paintBackground !== false);
    } else if (S.tab === 'placement') {
      var z = size(o);
      h += '<div class="grid2">' + fieldHtml('x', o.x || 0) + fieldHtml('y', o.y || 0) + '</div><div class="grid2">' + fieldHtml('w', z.w) + fieldHtml('h', z.h) + '</div>' +
        fieldHtml('opacity', o.opacity == null || o.opacity < 0 ? 100 : o.opacity) +
        '<p class="hint">Or drag the page on the preview; its corners resize it, arrow keys nudge it. Positions are on the source picture (' + src().w + ' x ' + src().h + ').</p>';
      if (web) h += cropHtml(o);
    } else {
      h += '<div class="grid2">' + fieldHtml('pageDelay', o.pageDelay) + fieldHtml('reloadRate', o.reloadRate) + '</div>';
    }
    el.inspector.innerHTML = h;
    el.inspector.querySelectorAll('[data-tab]').forEach(function (b) { b.onclick = function () { S.tab = b.getAttribute('data-tab'); renderInspector(); if (S.tab === 'transparency') captureSoon(o.id); }; });
    el.inspector.querySelectorAll('[data-f]').forEach(function (i) { i.addEventListener('input', onField); i.addEventListener('change', onField); });
    var raw = el.inspector.querySelector('[data-raw]');
    if (raw) raw.addEventListener('click', pickColor);
    bindCropper(o);
  }

  /* ---------- crop: the part of the captured page that is drawn ---------- */
  function hasCrop(o) { return o.cropW > 0 && o.cropH > 0; }
  function cropHtml(o) {
    return '<h4>Crop the page</h4>' +
      '<div class="cropper" data-cropper><img data-craw alt="The whole page as captured; drag to choose the part to show"' + (S.raw[o.id] ? ' src="' + S.raw[o.id] + '"' : ' hidden') + '>' +
      '<div class="cap" data-cwait' + (S.raw[o.id] ? ' hidden' : '') + '>Loading the page...</div><div class="crop-box" data-cbox hidden></div></div>' +
      '<p class="hint">Drag across the page to show only that part, for example just the clock. A dragged crop also gives the page that shape on the picture.</p>' +
      '<div class="grid2">' + fieldHtml('cropX', o.cropX || 0) + fieldHtml('cropY', o.cropY || 0) + '</div><div class="grid2">' + fieldHtml('cropW', o.cropW || 0) + fieldHtml('cropH', o.cropH || 0) + '</div>' +
      '<div class="row"><button type="button" class="btn sm" data-act="crop-reset"' + (hasCrop(o) ? '' : ' disabled') + '>Show the whole page</button></div>';
  }
  function pageSize(o) { var img = el.inspector.querySelector('[data-craw]'); return img && img.naturalWidth ? { w: img.naturalWidth, h: img.naturalHeight } : { w: o.viewportWidth || size(o).w, h: o.viewportHeight || size(o).h }; }
  function drawCropBox(o) {
    var box = el.inspector.querySelector('[data-cbox]'), img = el.inspector.querySelector('[data-craw]');
    if (!box || !img || img.hidden) return;
    if (!hasCrop(o)) { box.hidden = true; return; }
    var p = pageSize(o);
    box.hidden = false;
    box.style.left = (100 * o.cropX / p.w) + '%'; box.style.top = (100 * o.cropY / p.h) + '%';
    box.style.width = (100 * Math.min(o.cropW, p.w - o.cropX) / p.w) + '%'; box.style.height = (100 * Math.min(o.cropH, p.h - o.cropY) / p.h) + '%';
  }
  // a new crop keeps the page's width on the picture and gives it the crop's shape
  function setCrop(o, c) {
    o.cropX = c.x; o.cropY = c.y; o.cropW = c.w; o.cropH = c.h;
    var whole = !(c.w > 0 && c.h > 0), p = pageSize(o), z = size(o);
    o.h = even(z.w * (whole ? p.h / p.w : c.h / c.w));
    ['cropX', 'cropY', 'cropW', 'cropH', 'h'].forEach(function (k) { var i = el.inspector.querySelector('[data-f=' + k + ']'); if (i) i.value = o[k]; });
    var reset = el.inspector.querySelector('[data-act=crop-reset]'); if (reset) reset.disabled = whole;
    drawCropBox(o); placeBoxes(); markEdited(); renderBar(); captureSoon(o.id);
  }
  function bindCropper(o) {
    var wrap = el.inspector.querySelector('[data-cropper]');
    if (!wrap) return;
    var img = wrap.querySelector('[data-craw]');
    img.addEventListener('load', function () { drawCropBox(o); });
    drawCropBox(o);
    el.inspector.querySelector('[data-act=crop-reset]').onclick = function () { snapshot(); setCrop(o, { x: 0, y: 0, w: 0, h: 0 }); };
    wrap.addEventListener('pointerdown', function (e) {
      if (e.button !== 0 || img.hidden || !img.naturalWidth) return;
      e.preventDefault();
      var r = img.getBoundingClientRect(), k = img.naturalWidth / r.width;
      function at(ev) { return { x: Math.max(0, Math.min(img.naturalWidth, Math.round((ev.clientX - r.left) * k))), y: Math.max(0, Math.min(img.naturalHeight, Math.round((ev.clientY - r.top) * k))) }; }
      var a = at(e), moved = false;
      wrap.setPointerCapture(e.pointerId);
      function move(ev) {
        var b = at(ev);
        if (!moved) { if (Math.abs(b.x - a.x) + Math.abs(b.y - a.y) < 4) return; snapshot(); moved = true; }
        var c = { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), w: Math.abs(b.x - a.x), h: Math.abs(b.y - a.y) };
        o.cropX = c.x; o.cropY = c.y; o.cropW = c.w; o.cropH = c.h;
        drawCropBox(o);
      }
      function up() {
        wrap.removeEventListener('pointermove', move); wrap.removeEventListener('pointerup', up); wrap.removeEventListener('pointercancel', up);
        if (moved && o.cropW >= 4 && o.cropH >= 4) setCrop(o, { x: o.cropX, y: o.cropY, w: o.cropW, h: o.cropH });
      }
      wrap.addEventListener('pointermove', move); wrap.addEventListener('pointerup', up); wrap.addEventListener('pointercancel', up);
    });
  }

  function onField(e) {
    var name = e.target.getAttribute('data-f'), f = F[name], o = byId(S.layer), v;
    var fld = e.target.closest('.fld'), err = fld && fld.querySelector('[data-err-for]');
    if (f.scope !== 'overlay' && !o) return;
    if (f.type === 'bool') v = e.target.checked;
    else if (f.type === 'int' || f.type === 'num') {
      v = e.target.value === '' ? null : (f.type === 'int' ? parseInt(e.target.value, 10) : parseFloat(e.target.value));
      var bad = v !== null && (isNaN(v) || (f.min !== undefined && v < f.min) || (f.max !== undefined && v > f.max));
      e.target.classList.toggle('invalid', !!bad);
      if (err) err.textContent = bad ? 'From ' + f.min + ' to ' + f.max + '.' : '';
      if (bad) return;
    } else v = e.target.value;
    if (name === 'url') { var p = urlProblem(v); e.target.classList.toggle('invalid', !!p); if (err) err.textContent = p || ''; }
    if (name === 'keyColor') { var sw = el.inspector.querySelector('[data-swatch]'); if (sw) sw.style.background = v; }
    snapshot('field:' + name);
    if (f.scope === 'overlay') { S.draft[f.key] = v; if (name === 'evenCoords') renderCoords(); }
    else {
      if (v === null) delete o[f.key]; else o[f.key] = v;
      if (['x', 'y', 'w', 'h', 'opacity'].indexOf(name) >= 0) placeBoxes();
      if (name === 'url') { var sub = el.layers.querySelector('.page[data-id="' + CSS.escape(o.id) + '"] .sub'); if (sub) sub.textContent = host(v); }
      if (CAPTURE_KEYS.indexOf(f.key) >= 0 && o.type === 'webPage') captureSoon(o.id);
      if (['cropX', 'cropY', 'cropW', 'cropH'].indexOf(name) >= 0) {
        if (!(o.cropW > 0) || !(o.cropH > 0)) { delete o.cropX; delete o.cropY; if (!(o.cropW > 0)) delete o.cropW; if (!(o.cropH > 0)) delete o.cropH; }
        drawCropBox(o);
        var reset = el.inspector.querySelector('[data-act=crop-reset]'); if (reset) reset.disabled = !hasCrop(o);
      }
    }
    markEdited(); renderBar();
  }
  function syncPlacement() {
    var o = byId(S.layer);
    ['x', 'y', 'w', 'h'].forEach(function (k) { var i = el.inspector.querySelector('[data-f=' + k + ']'); if (i) i.value = o[k]; });
  }
  // the raw capture is a data URL, so the canvas is not tainted
  function pickColor(e) {
    var img = e.target, r = img.getBoundingClientRect(), nw = img.naturalWidth, nh = img.naturalHeight;
    var sc = Math.min(r.width / nw, r.height / nh), dw = nw * sc, dh = nh * sc;
    var px = Math.floor((e.clientX - r.left - (r.width - dw) / 2) / sc), py = Math.floor((e.clientY - r.top - (r.height - dh) / 2) / sc);
    if (px < 0 || py < 0 || px >= nw || py >= nh) return;
    var c = document.createElement('canvas'); c.width = nw; c.height = nh;
    var g = c.getContext('2d'); g.drawImage(img, 0, 0);
    var p = g.getImageData(px, py, 1, 1).data, inp = el.inspector.querySelector('[data-f=keyColor]');
    inp.value = 'rgb(' + p[0] + ',' + p[1] + ',' + p[2] + ')';
    inp.dispatchEvent(new Event('input'));
  }

  /* ---------- action bar, save, discard ---------- */
  function renderBar() {
    if (!el.bar || !S.draft) return;
    U.actionBar(el.bar, { changes: changes(), conflict: S.conflict, saving: S.saving, saveLabel: U.saveLabel(U.liveShowing(S.name)),
      note: 'All changes saved. Next: step 2 puts the overlay in a transcoder template.',
      onSave: save, onDiscard: discard,
      onTheirs: function () { S.conflict = false; S.known = S.theirs; open(S.name); },
      onMine: function () { S.conflict = false; S.known = S.theirs; renderBar(); U.toast('Keeping your version. Save replaces the one saved in the other session.'); } });
  }

  function save() {
    if (!S.draft || !changes() || S.saving) return Promise.resolve(!changes());
    var bad = objects().filter(function (o) { return o.type === 'webPage' && urlProblem(o.url); })[0];
    if (bad) { S.layer = bad.id; S.tab = 'page'; renderLayers(); renderInspector(); U.toast('Page ' + bad.id + ' has no usable address: ' + urlProblem(bad.url), { level: 'bad' }); return Promise.resolve(false); }
    var body = {};
    OVERLAY_KEYS.forEach(function (k) { if (S.draft[k] !== undefined) body[k] = S.draft[k]; });
    body.objects = S.draft.objects;
    S.saving = true; renderBar();
    var name = S.name;
    return U.api.post(ovPath(name), body).then(function (r) {
      S.saving = false;
      if (S.name !== name) return true;
      S.saved = normalize(r); S.draft = clone(S.saved); S.conflict = false; S.justSaved = true;
      var up = r.updatedStreams || [];
      U.toast('Saved ' + name + '. ' + (up.length ? 'Updated on ' + up.join(', ') + ' now.' : 'No stream shows it yet; step 2 puts it in a template.'));
      renderEditor();
      U.refreshState(true);
      return true;
    }, function (e) {
      S.saving = false; renderBar();
      U.banner('ov', 'bad', 'Not saved.', e.message);
      return false;
    });
  }

  function discard() {
    if (!changes()) return;
    var before = JSON.stringify(S.draft);
    reset();
    U.toast('Discarded the unsaved changes.', { undo: function () { restore(before); } });
  }

  // back to the saved overlay, shown at once
  function reset() {
    S.draft = clone(S.saved); S.undo = []; S.redo = []; S.conflict = false;
    if (S.layer && !byId(S.layer)) { var top = sorted()[0]; S.layer = top ? top.id : null; }
    renderEditor(); captureSoonAll();
  }

  /* ---------- new, duplicate, delete ---------- */
  function namePrompt(title, value, ok) {
    return U.prompt(title, '<div class="fld"><label class="f" for="uo-on">Name</label><input id="uo-on" type="text" value="' + U.esc(value) + '" autocomplete="off">' +
      '<div class="hint">For example Scoreboard or Lower-third. Letters, digits, dot, dash and underscore.</div></div>', ok, function (m) {
        var n = m.querySelector('#uo-on').value.trim();
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(n) || n.indexOf('..') >= 0) return 'Letters, digits, dot, dash and underscore.';
        if ((U.overlays || []).some(function (o) { return o.name === n; })) return 'An overlay with this name exists.';
        return { name: n };
      });
  }

  function newOverlay() {
    (S.name ? leave() : Promise.resolve(true)).then(function (ok) {
      if (!ok) return;
      namePrompt('New overlay in ' + U.app, 'Scoreboard', 'Create').then(function (r) {
        if (!r) return;
        U.api.post(ovPath(r.name), { refreshRate: F.refreshRate.def, threadDrawing: true, maxDrawingTime: F.maxDrawingTime.def, objects: [] }).then(function () {
          S.name = null;
          return U.refreshState(true).then(function () { return renderList(r.name); }).then(addPage);
        }, function (e) { U.banner('ov', 'bad', 'Could not create the overlay.', e.message); });
      });
    });
  }

  function duplicateOverlay() {
    if (!S.saved) return;
    leave().then(function (ok) {
      if (!ok) return;
      var from = S.name, body = clone(S.saved);
      namePrompt('Duplicate ' + from, from + '-copy', 'Duplicate').then(function (r) {
        if (!r) return;
        U.api.post(ovPath(r.name), body).then(function () {
          U.toast('Duplicated ' + from + ' as ' + r.name + '.');
          S.name = null;
          return U.refreshState(true).then(function () { return renderList(r.name); });
        }, function (e) { U.banner('ov', 'bad', 'Not duplicated.', e.message); });
      });
    });
  }

  function deleteOverlay() {
    var o = entry() || {}, live = U.liveShowing(S.name), tpls = o.templates || [];
    if (tpls.length) {
      U.choose('Overlay ' + S.name + ' is in use', '<p>Templates carry it: <strong>' + U.esc(tpls.join(', ')) + '</strong>' +
        (live.length ? ', and ' + U.esc(live.join(', ')) + ' ' + (live.length > 1 ? 'are' : 'is') + ' publishing with it' : '') +
        '.</p><p>Take it off those templates in step 2 first, so no stream is left naming an overlay that does not exist.</p>',
        [{ label: 'Close', value: null }, { label: 'Go to step 2', value: 'go', kind: 'primary' }]).then(function (v) { if (v === 'go') U.go('uo-template'); });
      return;
    }
    U.confirm('Delete overlay ' + S.name + '?', '<p>Its pages and placement are deleted. No template carries it, so no stream changes. This cannot be undone.</p>', 'Delete overlay', true).then(function (ok) {
      if (!ok) return;
      var name = S.name;
      U.api.del(ovPath(name)).then(function () {
        U.toast('Deleted overlay ' + name + '.');
        S.name = null; S.saved = S.draft = null;
        return U.refreshState(true).then(function () { return renderList(); });
      }, function (e) { U.banner('ov', 'bad', 'Not deleted.', e.message); });
    });
  }

  function renderAll() { renderStage(); renderLayers(); renderInspector(); renderChips(); renderBar(); }

  U.views['uo-overlay'] = {
    init: function (section) {
      el.ov = section.querySelector('[data-ov]');
      el.empty = section.querySelector('[data-ov-empty]');
      el.editor = section.querySelector('[data-ov-editor]');
      el.stage = section.querySelector('[data-stage]');
      el.coords = section.querySelector('[data-coords]');
      el.preview = section.querySelector('[data-preview]');
      el.layers = section.querySelector('[data-layers]');
      el.inspector = section.querySelector('[data-inspector]');
      el.settings = section.querySelector('[data-ov-settings]');
      el.chips = section.querySelector('[data-ov-chips]');
      el.bar = section.querySelector('[data-ov-bar]');
      el.liveBadge = section.querySelector('[data-live-badge]');
      S.name = null; S.draft = S.saved = null;
      el.ov.onchange = function () {
        var want = el.ov.value;
        leave().then(function (ok) { if (ok) open(want); else el.ov.value = S.name; });
      };
      el.preview.onchange = function () { setPreview(el.preview.value); };
      section.querySelectorAll('[data-act=new-overlay]').forEach(function (b) { b.onclick = newOverlay; });
      section.querySelector('[data-act=delete-overlay]').onclick = deleteOverlay;
      section.querySelector('[data-act=duplicate-overlay]').onclick = duplicateOverlay;
      section.querySelector('[data-act=add-layer]').onclick = addPage;
      section.querySelector('[data-guides]').onchange = function (e) {
        S.guides = e.target.checked;
        var g = el.stage.querySelector('[data-safe]'); if (g) g.hidden = !S.guides;
      };
      window.addEventListener('resize', placeBoxes);
      U.onShutdown(function () { window.removeEventListener('resize', placeBoxes); if (S.player) S.player.stop(); });
    },
    app: function () { S.name = null; S.preview = null; S.draft = S.saved = null; S.conflict = false; },
    show: function () { if (U.app) { renderList(); loadPreviewChoices(); if (S.draft && S.preview && !S.player) renderStage(); } },
    // the preview player stops while another step is shown
    hide: function () { if (S.player) { S.player.stop(); S.player = null; } },
    state: function () { if (U.current === 'uo-overlay' || S.name) onState(); },
    leave: function () { return S.draft ? leave() : Promise.resolve(true); },
    save: function () { if (S.draft) save(); },
    key: function (e, typing) {
      if (!S.draft) return;
      var k = e.key, mod = e.ctrlKey || e.metaKey;
      if (mod && !typing && k.toLowerCase() === 'z') { e.preventDefault(); if (e.shiftKey) redo(); else undo(); return; }
      if (mod && !typing && k.toLowerCase() === 'y') { e.preventDefault(); redo(); return; }
      if (typing || mod) return;
      if (k === 'Delete' && S.layer) { e.preventDefault(); removePage(S.layer); return; }
      if (k === 'Escape' && S.layer) { S.layer = null; renderLayers(); renderInspector(); placeBoxes(); return; }
      var step = e.shiftKey ? 10 : 1;
      var d = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[k];
      if (d && S.layer) { e.preventDefault(); nudge(d[0], d[1]); }
    }
  };
})();
