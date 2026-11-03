/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio overlay editor: a named overlay's layers placed over a live preview of a stream (the canvas), the
   layer list under the overlay in the list column, and the inspector for the selected layer. Web pages show their
   captures; text, shapes and images are drawn in the browser (ovs-draw.js). Edits stay in a draft until Save; a draft is kept in memory per
   overlay while the page is open, so leaving for another screen and coming back loses nothing. Placement is in
   source pixels: screen pixels / (frame width / source width), clamped to the frame.
   Also the New overlay screen, with a test capture before anything is written. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var F = U.fields;
  // every overlay-level field the module keeps (OverlayStore.OVERLAY_KEYS), its style defaults included, so a save keeps them
  var OVERLAY_KEYS = ['refreshRate', 'threadDrawing', 'maxDrawingTime', 'even_coords', 'opacity', 'stroke', 'color', 'fillColor', 'shadowColor', 'shadowOffset',
    'font', 'fontStyle', 'fontSize'];
  // the page settings a capture depends on; a page whose draft differs here is captured from the draft
  var CAPTURE_KEYS = ['url', 'viewportWidth', 'viewportHeight', 'keyColor', 'keyTolerance', 'paintBackground', 'pageDelay', 'cropX', 'cropY', 'cropW', 'cropH'];
  var el = {};
  // drafts of overlays not in view, by name: { saved, draft, undo, redo, known, layer }
  var mem = {};
  var S = { name: null, saved: null, draft: null, layer: null, preview: null, previewInfo: null, status: null,
    captures: {}, raw: {}, rawAt: {}, busy: {}, thumbAt: {}, seq: 0, shown: {}, capStatus: {}, drafted: {}, saving: false, player: null, timer: null, statusTimer: null,
    undo: [], redo: [], lastSnap: null, known: null, conflict: false, theirs: null, justSaved: false, soon: {}, guides: false, inspTab: null, adding: false, savedNote: null,
    // the kind being placed on the canvas (plan 4.1), and layer ids renamed since the last save: first id -> current
    placing: null, renamed: {},
    // the text layer being edited on the canvas (plan 4.1), and when the canvas last took a press
    editing: null, pressedAt: 0 };
  // layers hidden on the canvas, by overlay and id: this browser only, never in a draft or a save
  var hiddenBy = {};
  function hiddenKey(id) { return S.name + '\n' + id; }
  function hidden(id) { return !!hiddenBy[hiddenKey(id)]; }
  // the element id of a layer's box on the canvas, for aria-activedescendant
  function boxId(id) { return 'ovs-lyr-' + id; }
  function rotationOf(o) { return o.type === 'webPage' ? 0 : (typeof o.rotation === 'number' && isFinite(o.rotation) ? o.rotation : 0); }
  // a point turned by deg about c
  function turn(p, c, deg) {
    var a = deg * Math.PI / 180, cs = Math.cos(a), sn = Math.sin(a), dx = p.x - c.x, dy = p.y - c.y;
    return { x: c.x + dx * cs - dy * sn, y: c.y + dx * sn + dy * cs };
  }
  // how far a point is from the segment a-b
  function segDist(p, a, b) {
    var vx = b.x - a.x, vy = b.y - a.y, l = vx * vx + vy * vy, t = l ? Math.max(0, Math.min(1, ((p.x - a.x) * vx + (p.y - a.y) * vy) / l)) : 0;
    return Math.hypot(p.x - a.x - t * vx, p.y - a.y - t * vy);
  }

  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  function objects() { return (S.draft && S.draft.objects) || []; }
  function byId(id) { return objects().filter(function (o) { return o.id === id; })[0] || null; }
  function savedById(id) { return ((S.saved && S.saved.objects) || []).filter(function (o) { return o.id === id; })[0] || null; }
  function sorted() { return objects().slice().sort(function (a, b) { return (b.zOrder || 0) - (a.zOrder || 0) || String(a.id).localeCompare(String(b.id)); }); }
  function host(u) { try { return new URL(u).host; } catch (e) { return u || ''; } }
  function ovPath(name) { return U.appPath() + '/overlays/' + U.enc(name || S.name); }
  U.ovPath = ovPath;
  function even(n) { return S.draft && S.draft.even_coords ? Math.round(n / 2) * 2 : Math.round(n); }
  function src() {
    var t = S.status && S.status.transcoder;
    if (t && t.decoderWidth) return { w: t.decoderWidth, h: t.decoderHeight, known: true };
    var v = el.stage && el.stage.querySelector('video');
    if (v && v.videoWidth) return { w: v.videoWidth, h: v.videoHeight, known: true };
    return { w: 1920, h: 1080, known: false };
  }
  function size(o) { if (o.type !== 'webPage') { var b = area(o); return { w: b.w, h: b.h }; } return { w: o.w > 0 ? o.w : (o.viewportWidth || 640), h: o.h > 0 ? o.h : (o.viewportHeight || 360) }; }
  // where a layer sits on the source, unrotated: a page's box, or what ovs-draw measures for text, shapes and images
  function area(o) { if (o.type === 'webPage') { var z = size(o); return { x: o.x || 0, y: o.y || 0, w: z.w, h: z.h }; } return U.draw.bounds(o, S.draft); }
  // layers whose box is their x, y, w, h, so the corners resize them; a line or triangle moves by its points, free text by its start
  function resizable(o) { return o.type === 'webPage' || o.type === 'rectangle' || o.type === 'ellipse' || o.type === 'image' || (o.type === 'string' && o.box === true && o.w > 0 && o.h > 0); }
  // moves a layer by dx, dy from where it was (from): its start, and a line's or triangle's other points with it
  function shift(o, from, dx, dy) {
    o.x = even((from.x || 0) + dx); o.y = even((from.y || 0) + dy);
    if (o.type === 'line' || o.type === 'triangle') { o.x2 = even((from.x2 || 0) + dx); o.y2 = even((from.y2 || 0) + dy); }
    if (o.type === 'triangle') { o.x3 = even((from.x3 || 0) + dx); o.y3 = even((from.y3 || 0) + dy); }
  }
  function entry() { return U.overlayEntry(S.name); }
  function ro() { return !!U.readOnly; }

  /* ---------- draft bookkeeping ---------- */
  // number of things that differ from the saved overlay: each page added, removed or changed, and the overlay settings
  function changesOf(saved, draft) {
    if (!draft || !saved) return 0;
    var n = 0, so = saved.objects || [], dob = draft.objects || [];
    dob.forEach(function (o) { var s = so.filter(function (x) { return x.id === o.id; })[0]; if (!s || JSON.stringify(s) !== JSON.stringify(o)) n++; });
    so.forEach(function (s) { if (!dob.some(function (o) { return o.id === s.id; })) n++; });
    if (OVERLAY_KEYS.some(function (k) { return JSON.stringify(saved[k]) !== JSON.stringify(draft[k]); })) n++;
    return n;
  }
  function changes() { return changesOf(S.saved, S.draft); }
  // the changes in words, for the save bar and for other screens' links here
  function describe(saved, draft) {
    var out = [], so = (saved && saved.objects) || [];
    ((draft && draft.objects) || []).forEach(function (o) {
      var s = so.filter(function (x) { return x.id === o.id; })[0];
      if (!s) { out.push('add ' + (o.type === 'webPage' ? 'page ' : 'layer ') + o.id); return; }
      if (JSON.stringify(s) === JSON.stringify(o)) return;
      var w = [];
      var dx = (o.x || 0) - (s.x || 0), dy = (o.y || 0) - (s.y || 0);
      if (dx) w.push('moved ' + Math.abs(dx) + ' px ' + (dx > 0 ? 'right' : 'left'));
      if (dy) w.push('moved ' + Math.abs(dy) + ' px ' + (dy > 0 ? 'down' : 'up'));
      if (o.w !== s.w || o.h !== s.h) w.push('resized');
      if (o.zOrder !== s.zOrder) w.push('reordered');
      var keys = {};
      Object.keys(o).concat(Object.keys(s)).forEach(function (k) { if (['x', 'y', 'w', 'h', 'zOrder'].indexOf(k) < 0 && JSON.stringify(o[k]) !== JSON.stringify(s[k])) keys[k] = 1; });
      Object.keys(keys).forEach(function (k) {
        var f = Object.keys(F).filter(function (n) { return F[n].scope === 'object' && F[n].key === k; })[0];
        w.push((f ? F[f].label : k).toLowerCase());
      });
      out.push(o.id + ' ' + w.join(', '));
    });
    so.forEach(function (s) { if (!((draft && draft.objects) || []).some(function (o) { return o.id === s.id; })) out.push('remove ' + (s.type === 'webPage' ? 'page ' : 'layer ') + s.id); });
    if (saved && draft && OVERLAY_KEYS.some(function (k) { return JSON.stringify(saved[k]) !== JSON.stringify(draft[k]); })) out.push('overlay settings');
    return out;
  }
  function edited(id) { var o = byId(id), s = savedById(id); return !s || JSON.stringify(s) !== JSON.stringify(o); }
  function isDraftPage(o) {
    if (ro()) return false;
    var s = savedById(o.id);
    return !s || CAPTURE_KEYS.some(function (k) { return JSON.stringify(s[k]) !== JSON.stringify(o[k]); });
  }
  // one undo step per action; repeated nudges or typing in one field within a second are one step
  function snapshot(tag) {
    var now = Date.now();
    S.savedNote = null;
    if (tag && S.lastSnap && S.lastSnap.tag === tag && now - S.lastSnap.at < 1000) { S.lastSnap.at = now; return; }
    S.undo.push(JSON.stringify(S.draft));
    if (S.undo.length > 60) S.undo.shift();
    S.redo = [];
    S.lastSnap = { tag: tag, at: now };
  }
  function restore(json) {
    endEdit();
    S.draft = JSON.parse(json);
    if (S.layer && !byId(S.layer)) { var top = sorted()[0]; S.layer = top ? top.id : null; }
    S.lastSnap = null;
    renderEditor(); captureSoonAll();
  }
  function renderEditor() { renderLayers(); renderInspector(); placeBoxes(); renderBar(); renderCaption(); U.changed(); }
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

  /* ---------- opening, and keeping a draft when another overlay or screen is shown ---------- */
  function stash() {
    if (!S.name) return;
    if (S.draft && changes()) mem[S.name] = { saved: S.saved, draft: S.draft, undo: S.undo, redo: S.redo, known: S.known, layer: S.layer };
    else delete mem[S.name];
  }
  function open(name) {
    if (S.name && S.name !== name) stash();
    S.name = name; S.captures = {}; S.raw = {}; S.capStatus = {}; S.drafted = {}; S.shown = {}; S.thumbAt = {};
    S.conflict = false; S.theirs = null; S.adding = false; S.savedNote = null; S.warnings = [];
    var e = entry(), kept = mem[name];
    if (kept) {
      S.saved = kept.saved; S.draft = kept.draft; S.undo = kept.undo; S.redo = kept.redo; S.known = kept.known; S.layer = kept.layer;
      // the file changed while the draft waited: the draft stays, and Save is held until the producer picks
      if (e && kept.known != null && e.modified !== kept.known) { S.conflict = true; S.theirs = e.modified; }
      U.format.start(); S.live = {}; ticked = {};
      renderAll(); startCaptures(); loadLive();
      return Promise.resolve();
    }
    S.undo = []; S.redo = []; S.layer = null; S.saved = S.draft = null;
    S.known = e ? e.modified : null;
    el.layers.innerHTML = '<div class="skel" style="margin:16px"></div>';
    el.insp.innerHTML = '<section><div class="skel"></div><div class="skel"></div></section>';
    return U.api.get(ovPath(name)).then(function (o) {
      if (S.name !== name) return;
      S.saved = normalize(o); S.draft = clone(S.saved); S.renamed = {}; S.placing = null;
      var top = sorted()[0]; S.layer = top ? top.id : null;
      U.format.start(); S.live = {}; ticked = {}; loadFonts(); chk.state = null; chk.png = null; chk.show = false; checkSoon();
      renderAll();
      startCaptures();
      loadLive();
    }, function (err) { U.banner('ov', 'bad', 'Overlay ' + name + ' could not be read.', err.message); });
  }
  // the editor's draft for another screen's link, or null; never saved or discarded from there
  function draftInfo(name) {
    if (U.readOnly) return null;
    if (name === S.name && S.draft && changes()) return describe(S.saved, S.draft);
    var k = mem[name];
    return k && changesOf(k.saved, k.draft) ? describe(k.saved, k.draft) : null;
  }

  /* ---------- live state from the server ---------- */
  function onState() {
    if (!S.name) return;
    var e = entry();
    if (!e) {
      // deleted in another session
      if (changes()) { U.toast('Overlay ' + S.name + ' was deleted in another session. Save puts your version back.', { level: 'bad' }); }
      else { U.toast('Overlay ' + S.name + ' was deleted in another session.'); delete mem[S.name]; S.name = null; U.stayIn('overlays'); return; }
    }
    if (e && S.known != null && e.modified !== S.known && !S.saving) {
      if (S.justSaved) { S.known = e.modified; S.justSaved = false; }
      else if (changes()) { S.conflict = true; S.theirs = e.modified; }
      else { S.known = e.modified; U.toast('Overlay ' + S.name + ' was changed in another session. Showing the new version.'); open(S.name); return; }
    } else if (e && S.known == null) S.known = e.modified;
    renderHead(); loadPreviewChoices(); renderBar(); renderCaption();
  }

  /* ---------- the editor head: name, the templates that carry it, preview stream, menu ---------- */
  function renderHead() {
    var e = entry() || {}, tpls = e.templates || [];
    el.title.textContent = S.name || '';
    el.listBtn.innerHTML = U.icon('grid') + 'Overlays (' + (U.overlays || []).length + ')';
    el.chips.innerHTML = tpls.length ? '<span class="ug-h">Used in</span>' + tpls.map(function (t) { return '<button type="button" class="chip" data-go="template:' + U.esc(t) + '">' + U.esc(t) + '</button>'; }).join('') :
      '<span class="bdg">In no template</span>';
  }
  function loadPreviewChoices() {
    var streams = U.live();
    var want = S.preview || U.store.get('preview.' + U.app);
    if (!streams.some(function (s) { return s.name === want; })) {
      // the streams that show this overlay come first
      var mine = streams.filter(function (s) { return s.overlay === S.name; })[0];
      want = mine ? mine.name : streams.length ? streams[0].name : '';
    }
    var sig = streams.map(function (s) { return s.name; }).join('|');
    if (el.preview.getAttribute('data-sig') !== sig) {
      el.preview.innerHTML = streams.length ? streams.map(function (s) { return '<option value="' + U.esc(s.name) + '"' + (s.name === want ? ' selected' : '') + '>' + U.esc(s.name) + '</option>'; }).join('')
                                            : '<option value="">no stream publishing</option>';
      el.preview.setAttribute('data-sig', sig);
    }
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
      if (S.preview !== mine || U.current() !== 'overlay') return null;
      return U.api.get(p + '/status').then(function (st) {
        var had = S.status && S.status.transcoder && S.status.transcoder.decoderWidth;
        S.status = st;
        if (!had && st.transcoder && st.transcoder.decoderWidth) {
          // the rows say which layers fall outside this stream's frame
          placeBoxes(); renderLayers();
          // the inspector quotes the source size and title safe; left alone while someone types in it
          if (!el.insp.contains(document.activeElement)) renderInspector();
        }
      }, function () {});
    });
  }

  /* ---------- captures: saved pages as the streams see them, changed pages from the draft ---------- */
  // the width the page is shown at, in device pixels; the preview capture is sent at that size
  function shownWidth(o) {
    var box = el.stage.querySelector('.pg[data-id="' + CSS.escape(o.id) + '"]');
    var css = Math.max(box ? box.clientWidth : 0, 160);
    return Math.min(2048, Math.ceil(css * (window.devicePixelRatio || 1) / 64) * 64);
  }
  // the whole page, for picking the key color and the crop, is fetched only while that section is open, at most every 3 s
  function wantRaw(o) {
    return S.layer === o.id && S.inspTab === 'look' && (!S.raw[o.id] || Date.now() - (S.rawAt[o.id] || 0) > 3000);
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
      if (S.name !== mine || U.current() !== 'overlay' || !S.draft) return null;
      objects().forEach(function (o) {
        if (o.type !== 'webPage' || (S.busy[o.id] || 0) >= 1) return;
        S.busy[o.id] = (S.busy[o.id] || 0) + 1;
        capturePage(o, mine).then(function () { S.busy[o.id]--; if (S.name === mine) showCaptures(o.id); });
      });
      return null;
    });
  }

  // a changed page is captured about 400 ms after the last keystroke, not at the next tick
  function captureSoon(id) {
    clearTimeout(S.soon[id]);
    var box = el.stage.querySelector('.pg[data-id="' + CSS.escape(id) + '"]');
    if (box) box.classList.add('updating');
    var mine = S.name;
    S.soon[id] = setTimeout(function go() {
      var o = byId(id);
      if (!o || S.name !== mine || o.type !== 'webPage' || !U.alive()) return;
      // one request out per page: wait for the timer's capture to finish rather than send a second
      if ((S.busy[id] || 0) >= 1) { S.soon[id] = setTimeout(go, 150); return; }
      S.busy[id] = (S.busy[id] || 0) + 1;
      capturePage(o, mine).then(function () { S.busy[id]--; if (S.name === mine) { showCaptures(id); var b = el.stage.querySelector('.pg[data-id="' + CSS.escape(id) + '"]'); if (b) b.classList.remove('updating'); } });
    }, 400);
  }
  function captureSoonAll() { objects().forEach(function (o) { if (o.type === 'webPage') captureSoon(o.id); }); }

  // a picture is set only when it changed: each new one is a full-size PNG the browser decodes again
  function setSrc(img, src) { if (src && img.getAttribute('src') !== src) img.src = src; if (src) img.hidden = false; }
  var THUMB_MS = 2000;
  // only: the page whose capture just finished; without it, every page
  function showCaptures(only) {
    var now = Date.now();
    el.stage.querySelectorAll('.pg').forEach(function (b) {
      var id = b.getAttribute('data-id');
      if (only && id !== only) return;
      setSrc(b.querySelector('img'), S.captures[id]);
      var o = byId(id);
      b.classList.toggle('draft', !!(o && isDraftPage(o)));
      var nm = b.querySelector('.pg-name'), t = o ? o.id + (isDraftPage(o) ? ', unsaved' : '') : '';
      if (nm && o && nm.textContent !== t) nm.textContent = t;
    });
    el.layers.querySelectorAll('.prow').forEach(function (l) {
      var id = l.getAttribute('data-id');
      if (only && id !== only) return;
      // a thumbnail a few pixels wide does not need every frame
      if (!only || now - (S.thumbAt[id] || 0) >= THUMB_MS) { S.thumbAt[id] = now; setSrc(l.querySelector('.thumb img'), S.captures[id]); }
      var tag = l.querySelector('[data-tag]'), th = pageTag(id);
      if (tag && tag.outerHTML !== th) tag.outerHTML = th;
      var dot = l.querySelector('[data-pdot]'), dc = 'dot ' + pageDot(id);
      if (dot && dot.className !== dc) dot.className = dc;
    });
    if (only && only !== S.layer) return;
    var raw = el.insp.querySelector('[data-raw]'), keyed = el.insp.querySelector('[data-keyed]');
    if (raw && S.raw[S.layer]) { setSrc(raw, S.raw[S.layer]); var rw = el.insp.querySelector('[data-rawwait]'); if (rw) rw.hidden = true; }
    var craw = el.insp.querySelector('[data-craw]');
    if (craw && S.raw[S.layer] && craw.getAttribute('src') !== S.raw[S.layer]) { craw.src = S.raw[S.layer]; craw.hidden = false; var w = el.insp.querySelector('[data-cwait]'); if (w) w.hidden = true; }
    if (keyed && S.captures[S.layer]) setSrc(keyed, S.captures[S.layer]);
    var chip = el.insp.querySelector('[data-capline]');
    if (chip) { chip.className = 'capchip ' + pageDot(S.layer); chip.title = capLine(S.layer).replace(/<[^>]+>/g, ''); chip.innerHTML = '<span class="dot ' + pageDot(S.layer) + '"></span>' + U.esc(capShort(S.layer)); }
    var note = el.insp.querySelector('[data-capnote]');
    if (note) note.innerHTML = '<span class="dot bad"></span><div>' + capLine(S.layer) + '</div>';
  }
  function pageDot(id) {
    var c = S.capStatus[id];
    if (c && (c.state === 'blocked' || c.state === 'error' || c.state === 'service not running')) return 'bad';
    return c && c.state === 'running' && c.lastCaptureAt ? 'on' : '';
  }
  function pageTag(id) {
    var c = S.capStatus[id], o = byId(id);
    if (c && c.state === 'service not running') return '<span class="bdg bad" data-tag>No capture service</span>';
    if (c && (c.state === 'blocked' || c.state === 'error')) return '<span class="bdg bad" data-tag>' + U.esc(c.state === 'blocked' ? 'Blocked' : 'Error') + '</span>';
    if (o && !savedById(id) && !ro()) return '<span class="chip-draft" data-tag>Unsaved</span>';
    if (!c) return '<span class="bdg" data-tag>Opening</span>';
    return '<span data-tag hidden></span>';
  }
  function capNote(id) { var c = S.capStatus[id]; return c && (c.state === 'blocked' || c.state === 'error' || c.state === 'service not running') ? 'bad' : ''; }
  function capShort(id) {
    var c = S.capStatus[id], o = byId(id);
    if (!c) return o && urlProblem(o.url) ? 'No address' : 'Opening';
    if (c.state === 'blocked') return 'Blocked';
    if (c.state === 'service not running') return 'No capture service';
    if (c.state === 'error') return 'Error';
    return (S.drafted[id] ? 'Draft ' : 'Live ') + U.ago(c.lastCaptureAt) + (c.lastCaptureMs != null ? ', ' + c.lastCaptureMs + ' ms' : '');
  }
  function capLine(id) {
    var c = S.capStatus[id], o = byId(id);
    if (!c) return o && urlProblem(o.url) ? 'Enter the page address to see it.' : 'The capture service is opening the page.';
    if (c.state === 'blocked') return '<b class="err">Blocked. Nothing is captured.</b> ' + U.esc(c.lastError || '') +
      '<br><span class="small">Narrow fix: point the page at a public address, or ask a WSE administrator to add its host to <span class="mono">OverlayStudioAllowHosts</span> in Server.xml. ' +
      'Broader: <span class="mono">OverlayStudioAllowPrivateNetworks</span> set to true. Either needs a WSE restart. <button type="button" class="linkbtn" data-status-open="network"><b>Network rules</b></button></span>';
    if (c.state === 'service not running') return '<b class="err">Not capturing: the capture service is not running.</b> Viewers see the last picture.';
    if (c.state === 'error') return '<b class="err">' + U.esc(c.lastError || 'Capture failed') + '</b>';
    var ch = c.chrome || {};
    return (S.drafted[id] ? 'Draft captured ' : 'Capturing, last picture ') + U.ago(c.lastCaptureAt) + (c.lastCaptureMs != null ? ' in ' + c.lastCaptureMs + ' ms' : '') + (ch.pid ? ', Chrome ' + U.mb(ch.rssBytes) + (ch.pagesInBrowser > 1 ? ' as its share of one Chrome for ' + ch.pagesInBrowser + ' pages' : '') : '') + '.';
  }

  // the browser on the server loads the saved page's address again; the next captures show the result
  function reloadPage(id) {
    var o = byId(id), name = S.name;
    if (!o || !savedById(id) || ro()) return;
    U.api.post(ovPath(name) + '/objects/' + U.enc(id) + '/capture', { reload: true }).then(function () {
      U.toast('Loading ' + host(o.url) + ' again for page ' + id + '. Viewers keep the last picture until it has loaded.');
      captureSoon(id);
    }, function (e) { U.toast('Not reloaded: ' + e.message, { level: 'bad' }); });
  }

  /* ---------- canvas ---------- */
  function renderStage() {
    if (S.player) { S.player.stop(); S.player = null; }
    // one Tab stop for every layer (plan 4.1): the selected layer is its active descendant
    el.stage.innerHTML = '<div class="frame" data-frame tabindex="0" role="application" aria-roledescription="canvas" aria-label="Canvas: layers over a background stream. Page Up and Page Down choose a layer' +
      (ro() ? '' : ', arrow keys move it, Enter edits it') + '."><video muted playsinline></video><div class="waiting" data-wait></div>' +
      '<div class="safe" data-safe' + (S.guides ? '' : ' hidden') + '></div><span class="lbl top" data-airlbl hidden></span></div>';
    var fr = el.stage.querySelector('[data-frame]');
    fr.addEventListener('pointerdown', onPlaceDown, true);
    fr.addEventListener('pointerdown', onCanvasDown);
    fr.addEventListener('dblclick', onCanvasDbl);
    // Tab into the canvas with nothing selected selects the front layer; a click chooses for itself
    fr.addEventListener('focus', function () {
      if (Date.now() - S.pressedAt < 600 || S.layer || S.placing) return;
      var top = visible()[0];
      if (top) select(top.id, true);
    });
    var v = el.stage.querySelector('video'), wait = el.stage.querySelector('[data-wait]');
    var url = U.hls(S.previewInfo && S.previewInfo.source && S.previewInfo.source.hls);
    if (S.preview && url && U.current() === 'overlay') {
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
      wait.textContent = S.preview ? 'Connecting...' : 'No stream is publishing in ' + (U.app || 'this application') + '. Layers can still be placed on a frame of the assumed size.';
    }
    placeBoxes(); renderCaption();
  }
  // the stream behind the layers is only a background: whether viewers see this overlay on it is the template's doing
  function onAirHere() { var r = S.preview && U.streamRow(S.preview); return !!(r && r.publishing && U.overlayNames(r).indexOf(S.name) >= 0); }
  function renderCaption() {
    var dirty = !ro() && changes() > 0, air = onAirHere();
    el.caption.innerHTML = !S.preview ? '<span class="bdg">Blank frame</span>' :
      (air ? '<span class="bdg on">On air on ' + U.esc(S.preview) + '</span>' : '<span class="bdg warn">Background only</span>' +
        U.tip(S.preview + ' does not show ' + S.name + '. It is here to place the layers on. Viewers see an overlay only when the template a stream runs names it.')) +
      (dirty ? '<span class="chip-draft">Unsaved</span>' + U.tip('Shown here only. Nothing is written until Save.') : '');
    var lbl = el.stage.querySelector('[data-airlbl]');
    if (lbl) { lbl.hidden = !S.preview || air; lbl.textContent = 'Background: ' + (S.preview || '') + '. Not on air with ' + S.name; }
  }

  // the layers the canvas shows, front first: every one but those hidden on it
  function visible() { return sorted().filter(function (o) { return !hidden(o.id); }); }
  // A free text is stored by its first line's baseline (ObjectConfig, TextDraw); the page shows and takes its Y as the
  // top of that line, like every other layer's top edge, and turns it back into the baseline the module draws from
  function freeText(o) { return o && o.type === 'string' && !(o.box === true && o.w > 0 && o.h > 0); }
  function ascentOf(o) { var e = U.draw.effective(o, S.draft); return U.draw.text(e, U.draw.shown(e)).ascent; }
  function shownY(o) { return freeText(o) ? Math.round((o.y || 0) - ascentOf(o)) : o.y || 0; }
  function storedY(o, top) { return freeText(o) ? even(top + ascentOf(o)) : top; }
  // what the canvas's active descendant says: the layer, its id and where it is
  function boxLabel(o) {
    var r = rotationOf(o);
    return (o.type === 'webPage' ? 'Web page: ' + host(o.url) : U.draw.label(o)) + '. Id ' + o.id + ', X ' + (o.x || 0) + ', Y ' + shownY(o) +
      (r ? ', turned ' + r + ' degrees' : '') + (!ro() && edited(o.id) ? ', unsaved' : '');
  }
  // the selected layer's grips: corners on a box, a point on each end of a line and each corner of a triangle, and
  // the turn handle on anything the module draws itself
  function handlesHtml(o) {
    var h = '';
    if (resizable(o)) h += '<i class="h nw" data-g="nw"></i><i class="h ne" data-g="ne"></i><i class="h sw" data-g="sw"></i><i class="h se" data-g="se"></i>';
    var pts = o.type === 'line' ? 2 : o.type === 'triangle' ? 3 : 0;
    for (var i = 1; i <= pts; i++) h += '<i class="h pt" data-g="p' + i + '" title="Drag to move this ' + (o.type === 'line' ? 'end' : 'corner') + '"></i>';
    if (o.type !== 'webPage') h += '<i class="h rot" data-g="rot" title="Drag to turn; Shift turns in 15 degree steps"></i>';
    return h;
  }
  function pointOf(o, g) { return g === 'p1' ? { x: o.x || 0, y: o.y || 0 } : g === 'p2' ? { x: o.x2 || 0, y: o.y2 || 0 } : { x: o.x3 || 0, y: o.y3 || 0 }; }
  function markSel() {
    var frame = el.stage.querySelector('[data-frame]');
    if (!frame) return;
    frame.querySelectorAll('.pg').forEach(function (b) { b.classList.toggle('sel', b.getAttribute('data-id') === S.layer); });
    var b = S.layer && frame.querySelector('.pg[data-id="' + CSS.escape(S.layer) + '"]');
    if (b) frame.setAttribute('aria-activedescendant', b.id); else frame.removeAttribute('aria-activedescendant');
  }

  function placeBoxes() {
    var frame = el.stage.querySelector('[data-frame]');
    if (!frame) return;
    var s = src();
    frame.style.aspectRatio = s.w + ' / ' + s.h; frame.style.setProperty('--ar', String(s.w / s.h));
    frame.classList.toggle('placing', !!S.placing);
    frame.querySelectorAll('.pg').forEach(function (b) { b.remove(); });
    var tedit = frame.querySelector('[data-tedit]');
    objects().slice().sort(function (x, y) { return (x.zOrder || 0) - (y.zOrder || 0); }).forEach(function (o) {
      if (hidden(o.id)) return;
      var b = document.createElement('div'), web = o.type === 'webPage', draft = web ? isDraftPage(o) : !ro() && !savedById(o.id), sel = o.id === S.layer;
      b.className = 'pg' + (web ? '' : ' lyr') + (sel ? ' sel' : '') + (draft || (!ro() && edited(o.id)) ? ' draft' : '');
      b.setAttribute('data-id', o.id);
      b.id = boxId(o.id);
      b.setAttribute('role', 'img');
      b.setAttribute('aria-label', boxLabel(o));
      b.innerHTML = (web ? '<img alt="" hidden>' : U.draw.svg(o, S.draft, 'ovs-' + o.id, { unrotated: true, hidden: true })) + '<span class="pg-name" aria-hidden="true">' + U.esc(o.id) + (draft || edited(o.id) ? ', unsaved' : '') + '</span>' +
        (sel && !ro() ? handlesHtml(o) : '');
      if (web && S.captures[o.id]) { b.querySelector('img').src = S.captures[o.id]; b.querySelector('img').hidden = false; }
      if (web) { var dop = drawn(o).opacity; if (dop != null && dop >= 0) b.querySelector('img').style.opacity = String(dop / 100); }
      // the text being edited keeps its box above every layer
      if (tedit) frame.insertBefore(b, tedit); else frame.appendChild(b);
      position(b, o, s);
    });
    markSel();
    renderCoords();
  }

  function position(b, o, s) {
    var d = drawn(o), z = area(d);
    b.style.left = (100 * z.x / s.w) + '%';
    b.style.top = (100 * z.y / s.h) + '%';
    b.style.width = (100 * z.w / s.w) + '%';
    b.style.height = (100 * z.h / s.h) + '%';
    // text, shapes and images draw as they change, a page keeps its capture
    if (o.type === 'webPage') { paintTime(b, o); return; }
    var g = b.querySelector('.lyr-svg'); if (g) g.outerHTML = U.draw.svg(d, S.draft, 'ovs-' + o.id, { unrotated: true, hidden: true });
    // the box turns about the module's pivot, so its outline and grips turn with the drawing
    var r = rotationOf(o);
    if (r) { var p = U.draw.pivot(o, S.draft); b.style.transformOrigin = (100 * (p.x - z.x) / z.w) + '% ' + (100 * (p.y - z.y) / z.h) + '%'; b.style.transform = 'rotate(' + r + 'deg)'; }
    else { b.style.transform = ''; b.style.transformOrigin = ''; }
    b.querySelectorAll('.h.pt').forEach(function (h) {
      var p = pointOf(o, h.getAttribute('data-g'));
      h.style.left = (100 * (p.x - z.x) / z.w) + '%'; h.style.top = (100 * (p.y - z.y) / z.h) + '%';
    });
    paintTime(b, o);
  }

  /* ---------- the live canvas (plan 4.4): samples tick, timing plays, live values show ---------- */
  // the values a feed set on air (3.6), or null
  function liveOf(o) { var v = o && S.live && S.live[savedIdOf(o.id)]; return v && Object.keys(v).length ? v : null; }
  // the id a layer has on the server: a rename waits for the save (plan 3.6), and live values with it
  function savedIdOf(id) {
    var from = S.renamed && Object.keys(S.renamed).filter(function (k) { return S.renamed[k] === id; })[0];
    return from && savedById(from) ? from : id;
  }
  // what the canvas draws for a layer: its live values on top, except while it is selected for editing
  function drawn(o) {
    var v = liveOf(o);
    if (!v || o.id === S.layer) return o;
    var c = {}, k;
    for (k in o) c[k] = o[k];
    ['text', 'color', 'background', 'opacity'].forEach(function (f) { if (v[f] !== undefined && v[f] !== null) c[f] = v[f]; });
    return c;
  }
  // how much of a layer shows now, and the tag that says why it is faint
  function timeState(o) {
    var t = U.format.timing(o, U.format.now()), v = liveOf(o), sel = o.id === S.layer;
    if (t.state !== 'on') return { alpha: 0.25, tag: (t.state === 'before' ? 'Hidden now, shows at ' : 'Hidden since ') + U.format.when(o, t.at, t.spec) };
    if (v && v.hidden === true && !sel) return { alpha: 0.25, tag: 'Hidden by a live value' };
    if (t.blinkOff) return { alpha: sel ? 0.25 : 0, tag: null };
    if (U.format.noTarget(o)) return { alpha: Math.min(t.alpha, 0.5), tag: 'No target set' };
    return { alpha: t.alpha, tag: null };
  }
  function paintTime(b, o) {
    var st = timeState(o), g = b.querySelector(o.type === 'webPage' ? 'img' : '.lyr-svg');
    var op = drawn(o).opacity;
    if (g) g.style.opacity = o.type === 'webPage' ? String((op != null && op >= 0 ? op / 100 : 1) * st.alpha) : st.alpha === 1 ? '' : String(st.alpha);
    var tag = b.querySelector('[data-ttag]');
    if (!st.tag) { if (tag) tag.remove(); return; }
    if (!tag) { tag = document.createElement('span'); tag.className = 'pg-tag'; tag.setAttribute('data-ttag', ''); tag.setAttribute('aria-hidden', 'true'); b.appendChild(tag); }
    if (tag.textContent !== st.tag) tag.textContent = st.tag;
  }
  var ticked = {};
  // four times a second: what changed by the clock is drawn again, layer by layer, without rebuilding the canvas
  function tick() {
    if (U.current() !== 'overlay' || !S.draft || !el.stage) return;
    var frame = el.stage.querySelector('[data-frame]');
    if (!frame) return;
    var s = src();
    objects().forEach(function (o) {
      if (!U.format.moves(drawn(o))) return;
      var b = frame.querySelector('.pg[data-id="' + CSS.escape(o.id) + '"]');
      if (!b) return;
      var st = timeState(o), text = o.type === 'string' ? U.draw.shown(drawn(o)) : '', k = [text, st.alpha.toFixed(2), st.tag];
      var was = ticked[o.id];
      if (was && was[0] === k[0] && was[1] === k[1] && was[2] === k[2]) return;
      ticked[o.id] = k;
      var moved = !was || was[0] !== text;
      if (moved && o.type !== 'webPage') { position(b, o, s); b.setAttribute('aria-label', boxLabel(o)); } else paintTime(b, o);
      // a text's size follows its sample; only the readout's text changes, so a focused Freeze keeps its focus
      var lc = moved && o.id === S.layer && el.coords.querySelector('[data-lcoords]');
      if (lc) lc.textContent = layerCoords(o);
      var nm = moved && el.layers.querySelector('.prow[data-id="' + CSS.escape(o.id) + '"] .lname');
      if (nm) nm.textContent = U.draw.name(drawn(o));
      if (moved) refreshRowProblems(o);
    });
    refreshFormat();
    if (S.presetPick) U.presets.fillSamples(el.insp);
  }
  // the Format tab's samples and its pattern check follow the clock and the server's answers
  function refreshFormat() {
    var o = S.layer && byId(S.layer);
    if (!o || !el.insp || S.inspTab !== 'format') return;
    el.insp.querySelectorAll('[data-fsample]').forEach(function (x) {
      var a = x.getAttribute('data-fsample').split('|'), v = U.format.sample({ kind: a[0], pattern: a.slice(1).join('|') }, o, U.format.now());
      var shown = v == null ? '' : v === '' ? '(blank)' : v;
      if (x.textContent !== shown) x.textContent = shown;
    });
    var err = el.insp.querySelector('[data-ferr]'), why = S.fErr || U.format.problem(o) || '';
    if (err && err.textContent !== why) { err.textContent = why; var pi = el.insp.querySelector('[data-fpattern]'); if (pi) pi.classList.toggle('invalid', !!why); }
  }
  // the layers' live values, read when the overlay opens and every few seconds while it is shown
  var liveAsked = 0, liveCleared = 0;
  function loadLive() {
    var name = S.name, asked = ++liveAsked;
    if (!name || !entry()) return Promise.resolve();
    return U.api.get(ovPath(name) + '/live').then(function (r) {
      if (S.name !== name || asked <= liveCleared) return;
      var was = JSON.stringify(S.live || {}), now = JSON.stringify(r || {});
      S.live = r || {};
      if (was === now) return;
      ticked = {};
      renderLayers(); placeBoxes();
      if (!el.insp.contains(document.activeElement)) renderInspector();
    }, function () { /* the canvas keeps the values it had */ });
  }
  function clearLive(id) {
    if (ro()) return;
    var sid = savedIdOf(id);
    liveCleared = liveAsked;
    U.api.post(ovPath() + '/live', { clear: sid }).then(function (r) {
      if (S.live) delete S.live[sid];
      ticked = {}; renderLayers(); placeBoxes(); renderInspector();
      U.toast(r && r.cleared ? 'Cleared the live value of ' + id + '. The video shows its saved values again.' : id + ' had no live value on the server any more.');
    }, function (er) { U.toast('The live value of ' + id + ' could not be cleared: ' + er.message, { level: 'bad' }); });
  }
  // which fields a feed set, in words: "text and color"
  function liveWords(v) {
    var f = ['text', 'color', 'background', 'opacity', 'hidden'].filter(function (k) { return v[k] !== undefined && v[k] !== null; });
    return f.length > 1 ? f.slice(0, -1).join(', ') + ' and ' + f[f.length - 1] : f[0] || 'value';
  }

  function renderCoords() {
    placeBanner();
    if (S.placing) {
      el.coords.innerHTML = '';
      el.outside.hidden = true;
      return;
    }
    if (el.say) el.say.textContent = '';
    var s = src(), o = S.layer && byId(S.layer), z = o ? size(o) : null;
    // a text's size is measured in its font, so it is shown in whole pixels
    el.coords.innerHTML = '<span class="bdg" title="Source picture">' + s.w + ' x ' + s.h + (s.known ? '' : ', assumed') + '</span>' +
      (o ? '<span class="bdg" data-lcoords title="' + U.esc(o.id) + ': position and size on the source">' + U.esc(layerCoords(o)) + '</span>' : '');
    // Freeze stops time on the canvas for editing a layer that ticks, blinks or fades
    if (objects().some(function (x) { return U.format.moves(drawn(x)); })) el.coords.innerHTML += '<button type="button" class="btn sm ghost" data-freeze aria-pressed="' + U.format.frozen() + '" title="Stop the clock on the canvas while you edit. The video keeps time.">' +
      (U.format.frozen() ? 'Frozen, resume time' : 'Freeze time') + '</button>';
    var outside = o && s.known && frameFit(o, s) === 'outside';
    el.outside.hidden = !outside;
    if (outside) el.outside.textContent = (o.type === 'webPage' ? 'Page ' : 'Layer ') + o.id + ' is outside ' + S.preview + ' (' + s.w + ' x ' + s.h + '): nothing of it shows. Move it under Place.';
  }
  // the preset list in the panel: a choice adds that layer at its place on the frame (the background stream's size,
  // else 1920 x 1080) and selects it
  function renderPresetPick() {
    el.insp.innerHTML = '<div class="pnl-h"><div><h3>Add from a preset</h3><span class="small faint">' + U.esc(S.name) + '</span></div></div>' +
      '<section>' + U.presets.listHtml(null) + '<div class="hrow" style="margin-top:12px"><span class="spacer"></span><button type="button" class="btn" data-precancel>Cancel</button></div></section>';
    U.presets.fillSamples(el.insp);
    el.insp.querySelectorAll('[data-preset]').forEach(function (b) { b.onclick = function () { addPreset(b.getAttribute('data-preset')); }; });
    el.insp.querySelector('[data-precancel]').onclick = function () { S.presetPick = false; renderInspector(); };
  }
  function addPreset(id) {
    if (full()) return;
    var s = src(), o = U.presets.layer(id, { w: s.w, h: s.h }, function (x) { return !!byId(x); }), top = sorted()[0];
    if (!o) return;
    o.zOrder = top ? (top.zOrder || 0) + 1 : 1;
    snapshot();
    S.draft.objects.push(o);
    S.presetPick = false; S.layer = o.id;
    if (o.type === 'string' && U.draw.hasToken(o.text)) S.inspTab = 'format';
    renderEditor();
    focusCanvas();
    U.toast('Added ' + U.presets.words(id) + ' as ' + o.id + '. Viewers see it after Save.');
  }
  // while placing, a banner on the canvas itself says what a click does, with Cancel; it is said once through data-say
  // (the readout under the canvas is not a live region, so a ticking clock's size is not read out every second)
  function placeBanner() {
    var f = frameEl(), b = f && f.querySelector('[data-placehint]');
    if (!S.placing) { if (b) b.remove(); return; }
    var text = 'Click to place the ' + KIND_LABEL[S.placing].toLowerCase() + ', or drag to draw it. Enter puts it in the center, Escape cancels.';
    if (!f) return;
    if (!b) {
      b = document.createElement('div'); b.className = 'placebanner'; b.setAttribute('data-placehint', '');
      b.innerHTML = '<span></span><button type="button" class="btn sm" data-placecancel>Cancel</button>';
      b.addEventListener('pointerdown', function (e) { e.stopPropagation(); });
      b.querySelector('[data-placecancel]').onclick = function (e) { e.stopPropagation(); stopPlacing(); renderCoords(); focusCanvas(); };
      f.appendChild(b);
    }
    if (b.firstChild.textContent !== text) { b.firstChild.textContent = text; if (el.say) el.say.textContent = text; }
  }
  // where the selected layer is and how big it draws, for the readout under the canvas
  function layerCoords(o) { var z = size(drawn(o)); return o.id + ' at ' + (o.x || 0) + ', ' + shownY(o) + ' · ' + Math.round(z.w) + ' x ' + Math.round(z.h); }
  var FIT = { outside: 'Outside the frame', past: 'Past title safe', inside: 'Inside title safe' };
  // the Place tab's chip follows the layer as it moves, without rebuilding the panel
  function updateFit() {
    var c = el.insp && el.insp.querySelector('[data-fit]'), o = S.layer && byId(S.layer);
    if (!c || !o) return;
    var f = frameFit(o, src());
    c.textContent = FIT[f]; c.className = 'bdg' + (f === 'inside' ? '' : ' warn');
  }
  // where a layer's drawn area sits on the frame: wholly outside it, past the title safe area (5% in from each edge), or inside
  function frameFit(o, s) {
    var a = area(o);
    if (a.x >= s.w || a.y >= s.h || a.x + a.w <= 0 || a.y + a.h <= 0) return 'outside';
    return a.x < s.w * 0.05 || a.y < s.h * 0.05 || a.x + a.w > s.w * 0.95 || a.y + a.h > s.h * 0.95 ? 'past' : 'inside';
  }

  /* ---------- problems the page shows (plan 4.2a) ---------- */
  // GET /fonts/check, once per font, style and set of characters: the family Java draws with and what it cannot draw
  var fontChecks = {};
  function fontCheck(o) {
    var e = U.draw.effective(o, S.draft), r = U.format.parse(o.text), lit = r.parts.filter(function (p) { return !p.kind; }).map(function (p) { return p.lit; }).join('');
    // a token draws digits, letters and punctuation of its pattern
    var chars = lit + (r.parts.some(function (p) { return p.kind; }) ? '0123456789:' + r.parts.filter(function (p) { return p.kind; }).map(function (p) { return p.pattern; }).join('') : '');
    var seen = {}, distinct = Array.from(chars).filter(function (c) { if (seen[c] || /\s/.test(c)) return false; seen[c] = true; return true; }).slice(0, 512).join('');
    var key = e.font + '\u0001' + (e.fontStyle || 'plain') + '\u0001' + distinct, c = fontChecks[key];
    if (!c) {
      fontChecks[key] = c = { pending: true };
      U.api.get('/fonts/check?font=' + encodeURIComponent(e.font) + '&style=' + encodeURIComponent(e.fontStyle || 'plain') + '&chars=' + encodeURIComponent(distinct)).then(function (a) {
        fontChecks[key] = a || {};
        if (U.current() === 'overlay' && S.draft) { renderLayers(); showProblems(); }
      }, function () { fontChecks[key] = {}; });
    }
    return { font: e.font, result: c };
  }
  /** What is wrong with a layer, each {text, field, row}: shown on its row (row) and under its field (field). */
  function problemsOf(o) {
    var out = [], s = src();
    if (o.type === 'string') {
      var fc = fontCheck(o), a = fc.result;
      if (a.available && a.uses && a.uses !== fc.font) out.push({ row: 'Font', field: 'font', text: 'Drawn as ' + a.uses + ' on this server: ' + fc.font + ' is not installed here.' });
      if (a.available && a.missing && a.missing.length) out.push({ row: 'Characters', field: 'text', text: 'These characters will not draw in ' + (a.uses || fc.font) + ': ' + a.missing.join(' ') });
      var e = U.draw.effective(drawn(o), S.draft), l = U.draw.text(e, U.draw.shown(e));
      if (l.cut) out.push({ field: 'text', text: 'Text is cut off at the box\'s edge' + (e.autoFit === true ? ', even at half its size' : '') + '. Make the box bigger or the text shorter' + (e.autoFit === true ? '.' : ', or turn on Shrink to fit the box.') });
    }
    if (o.type === 'image' && o.imageFile) {
      var im = U.draw.image(o.imageFile);
      if (im && im.error) out.push({ row: 'File missing', field: 'imageFile', text: 'File missing: ' + o.imageFile + '. The layer draws nothing until another image is chosen.' });
    }
    if (noFill(o) && o.gradientColor) out.push({ field: 'gradientColor', text: 'With no fill, a gradient still fills the shape from clear to this color. Clear it for an empty shape.' });
    (S.warnings || []).forEach(function (w) { if (w.id === o.id) out.push({ row: 'Warning', field: w.field, text: w.message }); });
    if (s.known) {
      var z = area(o);
      if (z.x < -0.5 || z.y < -0.5 || z.x + z.w > s.w + 0.5 || z.y + z.h > s.h + 0.5) out.push({ row: 'Outside the frame', outside: true, text: (frameFit(o, s) === 'outside' ? 'Wholly' : 'Partly') + ' outside the frame of ' + S.preview + ' (' + s.w + ' x ' + s.h + ').' });
    }
    return out;
  }
  // the stream is not 1920 x 1080, so layers placed for 1080p can be scaled to it
  // Fit to this stream is for an overlay made for 1920 x 1080 shown on a smaller stream: its layers reach well past
  // this frame and fit 1920 x 1080. One made on this stream, with a layer just over its edge, is not offered it.
  function canFit() {
    var s = src(), r = 0, b = 0;
    if (!s.known || (s.w === 1920 && s.h === 1080) || ro()) return false;
    objects().forEach(function (o) { var z = area(o); r = Math.max(r, z.x + z.w); b = Math.max(b, z.y + z.h); });
    return (r > s.w * 1.1 || b > s.h * 1.1) && r <= 1920 * 1.05 && b <= 1080 * 1.05;
  }
  // a row's problem chips, and Fit to this stream on the first row outside the frame
  function rowProblemsHtml(o) {
    var first = objects().filter(function (x) { return problemsOf(x).some(function (p) { return p.outside; }); })[0];
    return problemsOf(o).filter(function (p) { return p.row; }).map(function (p) {
      return '<span class="bdg warn" data-rowprob title="' + U.esc(p.text) + '">' + U.esc(p.row) + '</span>' +
        (p.outside && first === o && canFit() ? '<button type="button" class="btn sm ghost" data-fitstream title="Scale every layer from 1920 x 1080 to this stream">Fit to this stream</button>' : '');
    }).join('');
  }
  // the chips follow a text whose width changes (a clock's sample), without drawing the row anew
  function refreshRowProblems(o) {
    var box = el.layers && el.layers.querySelector('.prow[data-id="' + CSS.escape(o.id) + '"] [data-rowprobs]'), h = box && rowProblemsHtml(o);
    if (box && box.innerHTML !== h) box.innerHTML = h;
  }
  /** Fit to this stream (4.2a): every layer's position and size scaled by the stream's size over 1920 x 1080. */
  function fitToStream() {
    var s = src();
    if (ro() || !canFit()) return;
    var kx = s.w / 1920, ky = s.h / 1080, r = function (v) { return even(v); };
    snapshot();
    objects().forEach(function (o) {
      ['x', 'x2', 'x3'].forEach(function (k) { if (typeof o[k] === 'number') o[k] = r(o[k] * kx); });
      ['y', 'y2', 'y3'].forEach(function (k) { if (typeof o[k] === 'number') o[k] = r(o[k] * ky); });
      if (o.w > 0) o.w = Math.max(1, r(o.w * kx));
      if (o.h > 0) o.h = Math.max(1, r(o.h * ky));
      ['fontSize', 'stroke', 'padding', 'radius', 'arcWidth', 'arcHeight', 'shadowOffset', 'outlineWidth'].forEach(function (k) {
        if (typeof o[k] === 'number' && o[k] > 0) o[k] = Math.max(1, Math.round(o[k] * ky));
      });
    });
    ticked = {};
    renderEditor();
    U.toast('Scaled every layer of ' + S.name + ' from 1920 x 1080 to ' + s.w + ' x ' + s.h + '. Undo puts them back.');
  }
  // the notes under the fields of the selected layer; called after the panel is drawn and when a check answers
  function showProblems() {
    if (!el.insp) return;
    el.insp.querySelectorAll('[data-prob]').forEach(function (x) { x.remove(); });
    var o = S.layer && byId(S.layer);
    if (!o || S.presetPick || S.adding) return;
    problemsOf(o).forEach(function (p) {
      var f = p.field && (el.insp.querySelector('#ovs-f-' + p.field) || (p.field === 'imageFile' ? el.insp.querySelector('[data-imgname]') : null));
      var host = f && f.closest('.fld');
      if (!host) return;
      var n = document.createElement('div'); n.className = 'hint warn-text'; n.setAttribute('data-prob', p.field); n.textContent = p.text;
      host.appendChild(n);
    });
  }
  function frameEl() { return el.stage.querySelector('[data-frame]'); }
  function focusCanvas() { var f = frameEl(); if (f && document.activeElement !== f) f.focus({ preventScroll: true }); }
  // keepFocus: the canvas keeps (or takes) the focus, with the layer as its active descendant
  function select(id, keepFocus) {
    endEdit();
    S.layer = id; S.adding = false; S.imgPick = false; S.presetPick = false;
    renderLayers(); renderInspector(); placeBoxes();
    if (keepFocus) focusCanvas();
  }
  function deselect() { endEdit(); S.layer = null; renderLayers(); renderInspector(); placeBoxes(); }

  /* ---------- picking a layer on the canvas ---------- */
  // where a pointer event is, in source pixels
  function toSource(ev) {
    var f = frameEl(), r = f.getBoundingClientRect(), s = src();
    return { x: (ev.clientX - r.left) * s.w / r.width, y: (ev.clientY - r.top) * s.h / r.height };
  }
  // the layers under p, front first; m is the margin around them, in source pixels (6 screen pixels), so a thin line
  // can be hit
  function hits(p, m) {
    return visible().filter(function (o) {
      var q = rotationOf(o) ? turn(p, U.draw.pivot(o, S.draft), -rotationOf(o)) : p, e = U.draw.effective(o, S.draft);
      if (o.type === 'line') return segDist(q, { x: e.x, y: e.y }, { x: e.x2, y: e.y2 }) <= Math.max(e.stroke, 1) / 2 + (e.arrowHead && e.arrowHead !== 'none' ? U.draw.arrowSize(o, S.draft) / 2 : 0) + m;
      if (o.type === 'triangle') {
        var a = { x: e.x, y: e.y }, b = { x: e.x2, y: e.y2 }, c = { x: e.x3, y: e.y3 };
        var side = function (u, v) { return (v.x - u.x) * (q.y - u.y) - (v.y - u.y) * (q.x - u.x); };
        var s1 = side(a, b), s2 = side(b, c), s3 = side(c, a);
        return (s1 >= 0 && s2 >= 0 && s3 >= 0) || (s1 <= 0 && s2 <= 0 && s3 <= 0) || Math.min(segDist(q, a, b), segDist(q, b, c), segDist(q, c, a)) <= m;
      }
      var z = area(o);
      return q.x >= z.x - m && q.x <= z.x + z.w + m && q.y >= z.y - m && q.y <= z.y + z.h + m;
    }).map(function (o) { return o.id; });
  }
  function onCanvasDown(e) {
    if (e.button !== 0 || S.placing) return;
    S.pressedAt = Date.now();
    if (e.target.closest('[data-tedit]')) return;
    var g = e.target.closest('[data-g]');
    if (g && !ro()) { startDrag(e, g.closest('.pg').getAttribute('data-id'), g.getAttribute('data-g')); return; }
    var f = frameEl(), list = hits(toSource(e), 6 * src().w / f.clientWidth);
    if (!list.length) { if (S.layer) deselect(); return; }
    // the selected layer wins while it is under the pointer, so a layer reached by Alt+click can be dragged
    var id = list.indexOf(S.layer) >= 0 ? S.layer : list[0];
    if (e.altKey) {
      // Alt+click: the next layer under the pointer, toward the back, round again
      e.preventDefault();
      var i = list.indexOf(S.layer);
      select(list[i < 0 ? 0 : (i + 1) % list.length], true);
      return;
    }
    if (ro()) { e.preventDefault(); select(id, true); return; }
    startDrag(e, id, null);
  }
  function onCanvasDbl(e) {
    if (ro() || S.placing || e.target.closest('[data-tedit]')) return;
    var list = hits(toSource(e), 6 * src().w / frameEl().clientWidth), id = list.indexOf(S.layer) >= 0 ? S.layer : list[0], o = id && byId(id);
    if (o && o.type === 'string') { e.preventDefault(); startEdit(o.id); }
  }

  /* ---------- guides: snapping to the frame, the title safe area and the other layers ---------- */
  function snapTargets(id) {
    var s = src(), xs = [0, s.w / 2, s.w], ys = [0, s.h / 2, s.h];
    if (S.guides) { xs.push(s.w * 0.05, s.w * 0.95); ys.push(s.h * 0.05, s.h * 0.95); }
    visible().forEach(function (o) {
      if (o.id === id) return;
      var z = area(o);
      xs.push(z.x, z.x + z.w / 2, z.x + z.w); ys.push(z.y, z.y + z.h / 2, z.y + z.h);
    });
    return { xs: xs, ys: ys };
  }
  // the smallest move that puts one of cands on one of targets, within tol, or null
  function snapAxis(cands, targets, tol) {
    var best = null;
    cands.forEach(function (c) { targets.forEach(function (t) { var d = t - c; if (Math.abs(d) <= tol && (!best || Math.abs(d) < Math.abs(best.d))) best = { d: d, at: t }; }); });
    return best;
  }
  function drawGuides(lines) {
    var f = frameEl(), s = src();
    if (!f) return;
    f.querySelectorAll('[data-guide]').forEach(function (g) { g.remove(); });
    (lines || []).forEach(function (l) {
      var g = document.createElement('div');
      g.className = 'guide ' + (l.v !== undefined ? 'v' : 'h'); g.setAttribute('data-guide', l.v !== undefined ? 'x' + Math.round(l.v) : 'y' + Math.round(l.h)); g.setAttribute('aria-hidden', 'true');
      if (l.v !== undefined) g.style.left = (100 * l.v / s.w) + '%'; else g.style.top = (100 * l.h / s.h) + '%';
      f.appendChild(g);
    });
  }

  // a press on a layer: drag moves it; a corner grip resizes it, a point grip moves that point, the turn grip turns
  // it; guides snap unless Ctrl is held
  function startDrag(e, id, grip) {
    if (e.button !== 0 || ro() || S.placing) return;
    e.preventDefault();
    endEdit();
    // a press on another layer selects it and drags it at once; its grips appear on release
    var fresh = S.layer !== id;
    if (fresh) { S.layer = id; S.adding = false; S.imgPick = false; grip = null; renderLayers(); renderInspector(); markSel(); }
    var frame = frameEl();
    var box = frame.querySelector('.pg[data-id="' + CSS.escape(id) + '"]');
    var o = byId(id), s = src(), scale = frame.clientWidth / s.w, z = area(o), rot = rotationOf(o);
    var st = { x: e.clientX, y: e.clientY, ox: z.x, oy: z.y, ow: z.w, oh: z.h, from: clone(o), piv: U.draw.pivot(o, S.draft), t: snapTargets(id) }, moved = false;
    var tol = 6 / scale;
    frame.setPointerCapture(e.pointerId);
    function move(ev) {
      if (!moved) { snapshot(); moved = true; }
      var snap = !(ev.ctrlKey || ev.metaKey), lines = [];
      var dx = (ev.clientX - st.x) / scale, dy = (ev.clientY - st.y) / scale;
      var x = st.ox, y = st.oy, w = st.ow, h = st.oh;
      if (!grip) {
        // the whole layer stays on the frame: its area is clamped, and every point moves by the same amount
        var clampX = function (d) { return Math.max(-st.ox, Math.min(d, s.w - st.ow - st.ox)); }, clampY = function (d) { return Math.max(-st.oy, Math.min(d, s.h - st.oh - st.oy)); };
        dx = clampX(dx); dy = clampY(dy);
        if (snap) {
          var sx = snapAxis([st.ox + dx, st.ox + dx + st.ow / 2, st.ox + dx + st.ow], st.t.xs, tol), sy = snapAxis([st.oy + dy, st.oy + dy + st.oh / 2, st.oy + dy + st.oh], st.t.ys, tol);
          if (sx) { dx = clampX(dx + sx.d); lines.push({ v: sx.at }); }
          if (sy) { dy = clampY(dy + sy.d); lines.push({ h: sy.at }); }
        }
        shift(o, st.from, dx, dy);
      } else if (grip === 'rot') {
        // the turn handle sits above the layer: straight up is no turn
        var p = toSource(ev), deg = Math.atan2(p.y - st.piv.y, p.x - st.piv.x) * 180 / Math.PI + 90;
        if (ev.shiftKey) deg = Math.round(deg / 15) * 15;
        else if (snap && Math.abs(deg - Math.round(deg / 90) * 90) <= 3) deg = Math.round(deg / 90) * 90;
        deg = Math.round(((deg % 360) + 540) % 360 - 180);
        if (deg === -180) deg = 180;
        if (deg) o.rotation = deg; else delete o.rotation;
      } else if (grip.charAt(0) === 'p') {
        // a point is kept unturned, so the pointer is turned back about where the layer turned when the drag began
        var q = rot ? turn(toSource(ev), st.piv, -rot) : toSource(ev);
        q.x = Math.max(0, Math.min(q.x, s.w)); q.y = Math.max(0, Math.min(q.y, s.h));
        if (snap && !rot) {
          var px = snapAxis([q.x], st.t.xs, tol), py = snapAxis([q.y], st.t.ys, tol);
          if (px) { q.x += px.d; lines.push({ v: px.at }); }
          if (py) { q.y += py.d; lines.push({ h: py.at }); }
        }
        var n = grip.charAt(1), kx = n === '1' ? 'x' : 'x' + n, ky = n === '1' ? 'y' : 'y' + n;
        o[kx] = even(q.x); o[ky] = even(q.y);
      } else {
        // a turned box resizes along its own sides
        if (rot) { var lr = turn({ x: dx, y: dy }, { x: 0, y: 0 }, -rot); dx = lr.x; dy = lr.y; }
        if (grip.indexOf('w') >= 0) { x += dx; w -= dx; }
        if (grip.indexOf('e') >= 0) w += dx;
        if (grip.indexOf('n') >= 0) { y += dy; h -= dy; }
        if (grip.indexOf('s') >= 0) h += dy;
        if (snap && !rot) {
          var ex = snapAxis([grip.indexOf('w') >= 0 ? x : x + w], st.t.xs, tol), ey = snapAxis([grip.indexOf('n') >= 0 ? y : y + h], st.t.ys, tol);
          if (ex) { if (grip.indexOf('w') >= 0) { x += ex.d; w -= ex.d; } else w += ex.d; lines.push({ v: ex.at }); }
          if (ey) { if (grip.indexOf('n') >= 0) { y += ey.d; h -= ey.d; } else h += ey.d; lines.push({ h: ey.at }); }
        }
        w = Math.max(2, Math.min(w, s.w)); h = Math.max(2, Math.min(h, s.h));
        x = Math.max(0, Math.min(x, s.w - w)); y = Math.max(0, Math.min(y, s.h - h));
        o.x = even(x); o.y = even(y); o.w = even(w); o.h = even(h);
      }
      position(box, o, s); drawGuides(lines); afterMove();
    }
    function up() {
      frame.removeEventListener('pointermove', move); frame.removeEventListener('pointerup', up); frame.removeEventListener('pointercancel', up);
      drawGuides(null);
      if (moved) { renderLayers(); renderCaption(); U.changed(); }
      if (fresh || moved) placeBoxes();
      if (moved && !fresh) renderInspector();
      focusCanvas();
    }
    frame.addEventListener('pointermove', move); frame.addEventListener('pointerup', up); frame.addEventListener('pointercancel', up);
  }

  /* ---------- editing a text layer on the canvas (double-click, Enter or F2) ---------- */
  function startEdit(id) {
    var o = byId(id), frame = frameEl();
    if (!o || o.type !== 'string' || ro() || !frame) return;
    if (S.layer !== id) select(id, true);
    endEdit();
    var s = src(), k = frame.clientWidth / s.w, e = U.draw.effective(o, S.draft), t = document.createElement('textarea');
    t.className = 'tedit'; t.setAttribute('data-tedit', ''); t.spellcheck = false;
    t.setAttribute('aria-label', 'Text of ' + id + '. Enter finishes, Shift+Enter starts a new line.');
    t.value = o.text == null ? '' : String(o.text);
    t.style.fontFamily = '"' + String(e.font).replace(/"/g, '') + '", "Fira Sans", sans-serif';
    t.style.fontSize = Math.max(10, e.fontSize * k) + 'px';
    if (/bold/i.test(e.fontStyle)) t.style.fontWeight = '700';
    if (/italic/i.test(e.fontStyle)) t.style.fontStyle = 'italic';
    var fit = function () {
      var z = area(o);
      t.style.left = (100 * z.x / s.w) + '%'; t.style.top = (100 * z.y / s.h) + '%';
      t.style.width = (100 * z.w / s.w) + '%'; t.style.height = (100 * z.h / s.h) + '%';
    };
    fit();
    frame.appendChild(t);
    S.editing = id;
    t.focus({ preventScroll: true }); t.select();
    t.addEventListener('input', function () {
      snapshot('text:' + id);
      o.text = t.value;
      var b = frame.querySelector('.pg[data-id="' + CSS.escape(id) + '"]');
      if (b) { position(b, o, s); b.classList.add('draft'); b.setAttribute('aria-label', boxLabel(o)); }
      var nm = el.layers.querySelector('.prow[data-id="' + CSS.escape(id) + '"] .lname'); if (nm) nm.textContent = U.draw.name(o);
      fit(); markEdited(); renderBar(); renderCaption(); U.changed();
    });
    // the canvas's keys and the shell's Escape stay out of the text
    t.addEventListener('keydown', function (ev) {
      ev.stopPropagation();
      if ((ev.key === 'Enter' && !ev.shiftKey) || ev.key === 'Escape') { ev.preventDefault(); endEdit(true); }
    });
    t.addEventListener('blur', function () { endEdit(); });
    renderCoords();
  }
  function endEdit(refocus) {
    if (!S.editing) return;
    S.editing = null;
    var f = frameEl(), t = f && f.querySelector('[data-tedit]');
    if (t) t.remove();
    renderLayers(); renderInspector(); placeBoxes();
    if (refocus) focusCanvas();
  }
  // Enter on a layer that is not text: its first field in the panel
  function focusPanel() {
    if (el.grid) el.grid.classList.add('insp-open');
    var i = el.insp.querySelector('[data-lid], [data-f], select, input');
    if (i) i.focus();
  }
  // Page Up toward the front, Page Down toward the back; nothing selected selects the front layer
  function stepLayer(by) {
    var list = visible(), i = list.map(function (o) { return o.id; }).indexOf(S.layer);
    if (!list.length) return;
    var j = i < 0 ? 0 : Math.max(0, Math.min(list.length - 1, i + by));
    select(list[j].id, true);
  }

  // the coordinates, the inspector fields and the save bar after a move, at most once per animation frame: the bar
  // compares the whole draft with the saved overlay, too much for every pointer event of a drag
  var moveFrame = 0;
  function afterMove() {
    if (moveFrame) return;
    moveFrame = window.requestAnimationFrame(function () { moveFrame = 0; renderCoords(); syncPlacement(); renderBar(); });
  }

  function nudge(dx, dy) {
    var o = S.layer && byId(S.layer);
    if (!o) return;
    snapshot('nudge');
    var s = src(), z = area(o);
    shift(o, clone(o), Math.max(-z.x, Math.min(dx, s.w - z.w - z.x)), Math.max(-z.y, Math.min(dy, s.h - z.h - z.y)));
    var box = el.stage.querySelector('.pg[data-id="' + CSS.escape(o.id) + '"]');
    if (box) { position(box, o, s); box.classList.add('draft'); box.setAttribute('aria-label', boxLabel(o)); }
    renderCoords(); syncPlacement(); renderBar(); markEdited(); renderCaption(); U.changed();
  }

  /* ---------- layers ---------- */
  function renderLayers() {
    var list = sorted();
    el.pagesAdd.hidden = ro() || !S.draft;
    if (!list.length) {
      el.layers.innerHTML = '<p class="small faint lpages-empty">No layers yet.</p>';
      return;
    }
    el.layers.innerHTML = list.map(function (o, i) {
      var web = o.type === 'webPage', kind = U.draw.kind(o);
      // a page by its id and host, as before; any other layer by what it shows, with its kind and id under it
      return '<div class="prow" data-id="' + U.esc(o.id) + '"' + (ro() ? '' : ' draggable="true"') + ' tabindex="0" role="button" aria-selected="' + (o.id === S.layer) + '" title="' + (ro() ? '' : 'Drag to reorder; the top layer draws on top') + '">' +
        (web ? '<div class="thumb"><img alt="" hidden></div>' : '<div class="thumb kind" title="' + U.esc(kind) + '">' + U.icon(U.draw.icon(o)) + '</div>') +
        '<div style="min-width:0"><div class="nm">' + (web ? '<span class="dot ' + pageDot(o.id) + '" data-pdot></span>' + U.esc(o.id) : '<span class="lname">' + U.esc(U.draw.name(o)) + '</span>') +
        (!ro() && edited(o.id) && savedById(o.id) ? '<span class="chip-draft" data-edited>Unsaved</span>' : '') + (hidden(o.id) ? '<span class="bdg" data-hiddenmark title="Hidden on this canvas only. Viewers still see it.">Hidden here</span>' : '') + (web ? pageTag(o.id) : '') +
        (U.format.noTarget(o) ? '<span class="bdg warn" data-notarget>No target set</span>' : '') +
        '<span class="rowprobs" data-rowprobs>' + rowProblemsHtml(o) + '</span>' +
        (liveOf(o) ? '<span class="bdg live" data-livebadge title="A feed set its ' + U.esc(liveWords(liveOf(o))) + ' on air">Live' + (liveOf(o).text != null ? ': ' + U.esc(String(liveOf(o).text).split('\n')[0].slice(0, 24)) : '') + '</span>' +
          (ro() ? '' : '<button type="button" class="btn sm ghost" data-liveclear="' + U.esc(o.id) + '" aria-label="Clear the live value of ' + U.esc(o.id) + '">Clear</button>') : '') + '</div>' +
        '<div class="sub">' + U.esc(web ? host(o.url) : kind.charAt(0).toUpperCase() + kind.slice(1) + ', ' + o.id) + '</div></div>' +
        U.rowMenuHtml('page:' + o.id, 'Actions for ' + (web ? 'page ' : 'layer ') + o.id, [['up', 'Move up', false, !i], ['down', 'Move down', false, i === list.length - 1],
          ['front', 'Bring to front', false, !i], ['back', 'Send to back', false, i === list.length - 1], '-', ['dup', 'Duplicate'], ['hide', hidden(o.id) ? 'Show on canvas' : 'Hide on canvas'],
          ['reload', 'Reload page', false, !web || !savedById(o.id)], '-', ['rm', web ? 'Remove page' : 'Remove layer', true]]) + '</div>';
    }).join('');
    var dragId = null;
    el.layers.querySelectorAll('.prow').forEach(function (l) {
      var id = l.getAttribute('data-id');
      l.onclick = function (e) {
        if (e.target.closest('[data-rowfor]')) return;
        if (e.target.closest('[data-liveclear]')) { clearLive(id); return; }
        if (e.target.closest('[data-fitstream]')) { fitToStream(); return; }
        select(id);
      };
      l.onkeydown = function (e) { if (e.target === l && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); select(id); } };
      if (ro()) return;
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
    });
    showCaptures();
  }
  function movePage(id, by) {
    var order = sorted().map(function (o) { return o.id; }), i = order.indexOf(id), j = i + by;
    if (i < 0 || j < 0 || j >= order.length) return;
    order[i] = order[j]; order[j] = id;
    reorder(order);
  }
  U.rowActions.page = function (id, act) {
    if (!byId(id)) return;
    if (act === 'hide') { toggleHidden(id); return; }
    if (ro()) return;
    if (act === 'up') movePage(id, -1);
    else if (act === 'down') movePage(id, 1);
    else if (act === 'front' || act === 'back') {
      var order = sorted().map(function (o) { return o.id; }).filter(function (x) { return x !== id; });
      if (act === 'front') order.unshift(id); else order.push(id);
      reorder(order);
    }
    else if (act === 'dup') duplicate(id);
    else if (act === 'rm') removePage(id);
    else if (act === 'reload') reloadPage(id);
  };
  // Hide on canvas: in this browser only, so the layer is out of the way while others are placed; never saved
  function toggleHidden(id) {
    var k = hiddenKey(id);
    if (hiddenBy[k]) delete hiddenBy[k]; else hiddenBy[k] = true;
    if (hiddenBy[k] && S.layer === id) { endEdit(); S.layer = null; renderInspector(); }
    renderLayers(); placeBoxes();
    U.toast(hiddenBy[k] ? 'Hid ' + id + ' on this canvas. Viewers still see it, and Save keeps it.' : 'Showing ' + id + ' on the canvas again.');
  }
  // a copy of a layer, a little down and to the right, on top, with an id of its kind
  function duplicate(id) {
    var o = byId(id), s = src(), z = area(o), c = clone(o);
    c.id = freeId(o.type === 'webPage' ? 'page' : o.type === 'string' ? (U.draw.hasToken(o.text) ? 'clock' : 'text') : o.type === 'image' ? 'image' : 'shape');
    shift(c, o, Math.max(-z.x, Math.min(20, s.w - z.w - z.x)), Math.max(-z.y, Math.min(20, s.h - z.h - z.y)));
    c.zOrder = (sorted()[0].zOrder || 0) + 1;
    snapshot();
    S.draft.objects.push(c);
    S.layer = c.id; renderEditor();
    if (c.type === 'webPage') captureSoon(c.id);
    U.toast('Duplicated ' + id + ' as ' + c.id + ' in the draft. Viewers see it after Save.');
  }
  U.rowActions.overlay = function (name, act) {
    if (act === 'dup') U.newOverlayFrom(name);
    else if (act === 'addpage' || act.indexOf('add:') === 0) {
      var kind = act === 'addpage' ? 'page' : act.slice(4);
      if (U.current() === 'overlay' && S.name === name && S.draft) { addOnOpen(kind); return; }
      // the flag is set only once this overlay is the one opening, so a navigation that did not happen leaves none
      U.go('overlay:' + name);
      if (U.current() === 'overlay' && S.name === name) { if (S.draft) addOnOpen(kind); else S.addOnOpen = { name: name, kind: kind }; }
    } else if (act === 'del') deleteOverlay(name);
  };
  // order: page ids, top first
  function reorder(order) {
    snapshot();
    order.forEach(function (x, i) { byId(x).zOrder = order.length - i; });
    renderLayers(); placeBoxes(); renderBar(); renderCaption(); U.changed();
  }
  // the edited marks follow the draft without rebuilding the list
  /* ---------- the server check (plan 4.4) ---------- */
  // 600 ms after the last edit the server draws the draft at one frozen moment, and where each layer draws there is
  // compared with where it draws here: text by its widest line (3%) and its box (8 px), shapes and images by 2 px.
  // Boxes, not pixels: antialiasing differs on every edge even when the text looks the same (spike S4).
  var chk = { timer: null, seq: 0, state: null, png: null, show: false };
  function checkSoon() {
    if (chk.timer) clearTimeout(chk.timer);
    chk.timer = setTimeout(runCheck, 600);
  }
  // the overlay as a save would send it
  function draftBody() {
    var body = {};
    OVERLAY_KEYS.forEach(function (k) { if (S.draft[k] !== undefined) body[k] = S.draft[k]; });
    body.objects = S.draft.objects;
    return body;
  }
  function runCheck() {
    chk.timer = null;
    if (U.current() !== 'overlay' || !S.draft) return;
    var drawnHere = objects().filter(function (o) { return o.type !== 'webPage'; });
    if (!drawnHere.length) { setCheck(null); return; }
    if (ro()) { setCheck({ kind: 'na', text: 'Server check needs write access.' }); return; }
    // stream time and stream+N count from the moment the canvas counts from, so both read the same
    var now = U.format.now(), start = U.format.streamStart(), seq = ++chk.seq, name = S.name;
    setCheck({ kind: 'busy', text: 'Checking against the server\'s drawing...' }, true);
    U.api.post(ovPath() + '/render', { overlay: draftBody(), width: 640, now: now, streamStart: start }).then(function (r) {
      if (seq !== chk.seq || S.name !== name) return;
      chk.png = r && r.png;
      var diffs = compare((r && r.boxes) || [], now);
      setCheck(diffs.length ? { kind: 'bad', text: 'Differs from the server\'s drawing: ' + diffs.slice(0, 3).join('; ') + (diffs.length > 3 ? '; and ' + (diffs.length - 3) + ' more' : '') + '.' } :
        { kind: 'ok', text: 'Matches the server\'s drawing.' });
    }, function (e) {
      if (seq !== chk.seq) return;
      chk.png = null;
      setCheck({ kind: 'na', text: 'Server check unavailable' + (e && e.status === 429 ? ': too many checks a second' : '') + '. It runs again on the next edit.' });
    });
  }
  // what differs, in words, layer by layer; a layer the server did not draw (a missing picture, no fonts) is left out
  function compare(boxes, now) {
    var server = {}, out = [];
    boxes.forEach(function (b) { server[b.id] = b; });
    objects().forEach(function (o) {
      if (o.type === 'webPage' || U.format.timing(o, now).state !== 'on') return;
      var s = server[o.id];
      if (!s) return;
      var text = o.type === 'string' ? U.format.render(o, now) : null;
      // a token whose sample for that moment is not here yet is not compared
      if (text !== null && U.draw.hasToken(text)) return;
      var p = U.draw.checkBox(o, S.draft, text);
      if (!p) return;
      var tol = o.type === 'string' ? 8 : 2, said = [], px = function (v) { return Math.round(Math.abs(v)) + ' px '; };
      if (o.type === 'string' && s.advance > 0 && Math.abs(p.advance - s.advance) > 0.03 * s.advance) said.push('is ' + px(p.advance - s.advance) + (s.advance < p.advance ? 'narrower' : 'wider'));
      var dx = s.x - p.x, dy = s.y - p.y, dr = (s.x + s.w) - (p.x + p.w), db = (s.y + s.h) - (p.y + p.h);
      if (Math.abs(dx) > tol) said.push('starts ' + px(dx) + (dx > 0 ? 'further right' : 'further left'));
      else if (Math.abs(dr) > tol) said.push('ends ' + px(dr) + (dr > 0 ? 'further right' : 'further left'));
      if (Math.abs(dy) > tol) said.push('starts ' + px(dy) + (dy > 0 ? 'lower' : 'higher'));
      else if (Math.abs(db) > tol) said.push('ends ' + px(db) + (db > 0 ? 'lower' : 'higher'));
      if (said.length) out.push(o.id + ' ' + said.join(', ') + ' on the server');
    });
    return out;
  }
  // the check line: what the check found, announced once; Show the server's drawing lays its picture over the canvas
  function setCheck(c, quiet) {
    var was = chk.state && chk.state.text;
    chk.state = c;
    if (!c) chk.show = false;
    paintCheck();
    if (c && !quiet && c.text !== was && el.say && !S.placing) el.say.textContent = c.text;
  }
  function paintCheck() {
    if (!el.checkLine) return;
    var c = chk.state;
    el.checkLine.hidden = !c;
    if (!c) return;
    el.check.className = 'bdg' + (c.kind === 'ok' ? ' on' : c.kind === 'bad' ? ' warn' : '');
    el.check.textContent = c.text;
    var can = !!chk.png && (c.kind === 'ok' || c.kind === 'bad');
    if (!can) chk.show = false;
    el.checkShow.hidden = !can;
    el.checkShow.setAttribute('aria-pressed', String(chk.show));
    el.checkShow.textContent = chk.show ? 'Hide the server\'s drawing' : 'Show the server\'s drawing';
    var f = frameEl(), img = f && f.querySelector('[data-serverimg]');
    if (chk.show && f) {
      if (!img) { img = document.createElement('img'); img.className = 'serverimg'; img.setAttribute('data-serverimg', ''); img.alt = 'The server\'s drawing of the draft'; f.appendChild(img); }
      img.src = 'data:image/png;base64,' + chk.png;
    } else if (img) img.remove();
  }
  function markEdited() {
    checkSoon();
    el.layers.querySelectorAll('.prow').forEach(function (l) {
      var id = l.getAttribute('data-id'), nm = l.querySelector('.nm'), mark = nm.querySelector('[data-edited]');
      var on = edited(id) && !!savedById(id);
      if (on && !mark) { var d = document.createElement('span'); d.className = 'chip-draft'; d.setAttribute('data-edited', ''); d.textContent = 'Unsaved'; nm.insertBefore(d, nm.querySelector('[data-tag]')); }
      if (!on && mark) mark.remove();
    });
  }

  function removePage(id) {
    var o = byId(id);
    if (!o || ro()) return;
    snapshot();
    var before = S.undo[S.undo.length - 1];
    S.draft.objects = objects().filter(function (x) { return x.id !== id; });
    if (S.layer === id) { var top = sorted()[0]; S.layer = top ? top.id : null; }
    renderEditor();
    U.toast('Removed ' + (o.type === 'webPage' ? 'page ' : 'layer ') + id + ' from the draft. Viewers keep it until Save.', { undo: function () { S.redo = []; restore(before); S.layer = id; renderLayers(); renderInspector(); placeBoxes(); } });
  }

  function urlProblem(url) {
    var u;
    try { u = new URL(url); } catch (e) { return 'Enter a full address starting with http:// or https://.'; }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return 'Only http and https pages can be captured.';
    if (u.username || u.password) return 'Remove user:password from the address; credentials are never stored.';
    return null;
  }
  U.urlProblem = urlProblem;
  // a new page sized to about a third of the frame, in the top right, as most score bugs sit
  function newPage(id, url, vw, vh, s, z) {
    var w = even(Math.min(vw, s.w * 0.35)), h = even(w * vh / vw);
    return { id: id, type: 'webPage', url: url, x: even(s.w - w - s.w * 0.03), y: even(s.h * 0.04), w: w, h: h,
      zOrder: z, opacity: 100, viewportWidth: vw, viewportHeight: vh, pageDelay: 1000, reloadRate: 0,
      keyColor: F.keyColor.def, keyTolerance: 0, paintBackground: true };
  }
  U.newPage = function (id, url, vw, vh) { return newPage(id, url, vw, vh, { w: 1920, h: 1080 }, 1); };

  /* ---------- inspector ---------- */
  function fieldHtml(name, value, opts) {
    var f = F[name], id = 'ovs-f-' + name, v = value === undefined || value === null ? '' : value;
    opts = opts || {};
    var label = U.esc(f.label) + (f.unit ? ' (' + U.esc(f.unit) + ')' : '');
    var title = ' title="WSE setting ' + U.esc(f.key) + '"';
    var named = function (x) { var i = f.values ? f.values.indexOf(x) : -1; return i >= 0 && f.names ? f.names[i] : x; };
    if (ro()) {
      var shown = f.type === 'bool' ? (value ? 'Yes' : 'No') : v === '' ? (opts.placeholder ? opts.placeholder + (opts.fromPicture ? ', from the size on the picture' : '') : 'Not set') : f.type === 'enum' ? named(v) : v;
      return '<div class="fld"><span class="f">' + label + '</span><div class="val">' + U.esc(shown) + '</div></div>';
    }
    // a saved value differs from savedV when it was set, or the field shows a default: undefined and the default are alike
    var savedV = opts.saved, same = JSON.stringify(savedV) === JSON.stringify(value) || ((savedV === undefined || savedV === null) && (value === '' || value === f.def || value === opts.placeholder));
    var draftCls = savedV !== undefined && !same || (savedV === undefined && opts.savedKnown && !same) ? ' is-draft' : '';
    var savedLine = draftCls ? '<div class="hint">Saved: ' + U.esc(savedV === undefined || savedV === null || savedV === '' ? 'not set' : f.type === 'bool' ? (savedV ? 'on' : 'off') : named(savedV)) + '</div>' : '';
    if (f.type === 'bool') {
      return '<div class="fld"><label class="check"' + title + '><input type="checkbox" id="' + id + '" data-f="' + name + '"' + (value ? ' checked' : '') + '>' + label + '</label>' +
        (f.help ? U.tip(f.help + ' WSE setting ' + f.key + '.') : '') + savedLine + '</div>';
    }
    var head = '<label class="f" for="' + id + '">' + label + (f.help && !opts.noHelp ? U.tip(f.help + ' WSE setting ' + f.key + '.') : '') + '</label>';
    if (f.type === 'enum' || f.type === 'font') {
      var opt = function (x, text) { return '<option value="' + U.esc(x) + '"' + (x === v ? ' selected' : '') + '>' + U.esc(text) + '</option>'; };
      return '<div class="fld">' + head + '<select id="' + id + '" data-f="' + name + '"' + title + ' class="' + draftCls + '">' +
        (f.type === 'font' ? fontOptions(v, opt) : f.values.map(function (x) { return opt(x, named(x)); }).join('')) + '</select><div class="err" data-err-for="' + name + '"></div>' + savedLine + '</div>';
    }
    if (f.type === 'textarea') {
      return '<div class="fld">' + head + '<textarea id="' + id + '" data-f="' + name + '" rows="3" spellcheck="false"' + title + ' class="' + draftCls + '">' + U.esc(v) + '</textarea>' +
        '<div class="err" data-err-for="' + name + '"></div>' + savedLine + '</div>';
    }
    var type = f.type === 'int' || f.type === 'num' ? 'number' : 'text';
    var attrs = (f.min !== undefined ? ' min="' + f.min + '"' : '') + (f.max !== undefined ? ' max="' + f.max + '"' : '') + (f.type === 'num' ? ' step="any"' : '');
    var input = '<input type="' + type + '" id="' + id + '" data-f="' + name + '" value="' + U.esc(v) + '"' + attrs + title +
      (opts.placeholder ? ' placeholder="' + U.esc(opts.placeholder) + '"' : '') + (opts.list ? ' list="' + opts.list + '"' : '') + ' class="' + (type === 'number' ? 'num' : '') + draftCls + '">';
    // a color has the browser's picker beside it; the text stays, for transparency and for names such as white
    if (f.type === 'color') input = '<span class="cfield"><input type="color" data-cpick="' + name + '" value="' + hexOf(v || opts.placeholder) + '" aria-label="Pick the ' + U.esc(f.label.toLowerCase()) + '" title="Pick a color">' + input + '</span>';
    return '<div class="fld"><label class="f" for="' + id + '">' + label + (f.help && !opts.noHelp ? U.tip(f.help + ' WSE setting ' + f.key + '.') : '') + '</label>' + input +
      '<div class="err" data-err-for="' + name + '"></div>' + savedLine + '</div>';
  }
  // a color as the picker's #rrggbb: what the module would draw, black for anything it would not take
  function hexOf(c) {
    var m = /rgba\((\d+),(\d+),(\d+)/.exec(U.draw.css(c) || '');
    return '#' + (m ? [m[1], m[2], m[3]] : [0, 0, 0]).map(function (x) { return ('0' + (+x).toString(16)).slice(-2); }).join('');
  }
  // a picked color into the field as rgb(r,g,b), keeping the transparency the field had
  function onPick(e) {
    var p = e.target, t = el.insp.querySelector('[data-f="' + p.getAttribute('data-cpick') + '"]');
    if (!t) return;
    var h = p.value, a = /^\s*rgb\s*\([^,]+,[^,]+,[^,]+,\s*(\d{1,3})\s*\)\s*$/i.exec(t.value);
    t.value = 'rgb(' + parseInt(h.slice(1, 3), 16) + ',' + parseInt(h.slice(3, 5), 16) + ',' + parseInt(h.slice(5, 7), 16) + (a ? ',' + a[1] : '') + ')';
    t.dispatchEvent(new Event('input'));
  }

  function renderInspector() {
    if (U.current() !== 'overlay') return;
    var o = S.layer && byId(S.layer), d = S.draft, sv = o ? savedById(o.id) || {} : {};
    if (!d) { el.insp.innerHTML = ''; return; }
    if (S.adding) { renderAddPage(); return; }
    if (S.presetPick && !ro()) { renderPresetPick(); return; }
    var h = '';
    if (!o) {
      h = '<div class="pnl-h"><div><h3>No layer selected</h3><span class="small faint">' + U.esc(S.name) + '</span></div></div>' +
        '<section><p class="small muted">Select a layer in the list or on the canvas' + (ro() ? '.' : ', or add one.') + '</p>' +
        (ro() ? '' : '<p style="margin-top:12px">' + addMenuHtml('insp', 'btn-add') + '</p>') + '</section>';
    } else {
      var web = o.type === 'webPage', z = size(o), s = src(), fit = frameFit(o, s);
      // the id is editable: live values (plan 3.6) address a layer by it
      var idHtml = ro() ? '<h3>' + U.esc(o.id) + '</h3>' : '<input class="lid" data-lid value="' + U.esc(o.id) + '" aria-label="Layer id" title="Layer id: what a live value feed writes to" spellcheck="false" autocomplete="off"><div class="err" data-liderr></div>';
      h = '<div class="pnl-h"><div>' + idHtml + (web ? '<span class="capchip ' + pageDot(o.id) + '" data-capline title="' + U.esc(capLine(o.id).replace(/<[^>]+>/g, '')) + '"><span class="dot ' + pageDot(o.id) + '"></span>' + U.esc(capShort(o.id)) + '</span>' : '') +
        '</div><span class="spacer"></span>' + (web && savedById(o.id) && !ro() ? '<button type="button" class="btn sm ghost" data-reloadpage title="Load ' + U.esc(host(o.url)) + ' again in the browser on the server, for a capture that shows the wrong thing">' + U.icon('refresh') + 'Reload page</button>' : '') +
        '<button type="button" class="btn sm icon ghost only-1200" data-closeinsp aria-label="Close the inspector">' + U.icon('close') + '</button></div>';
      var tabs = TABS[web ? 'webPage' : o.type === 'string' || o.type === 'image' ? o.type : 'shape'];
      if (o.type === 'string' && U.draw.hasToken(o.text)) tabs = [['format', 'Format']].concat(tabs);
      if (!tabs.some(function (t) { return t[0] === S.inspTab; })) S.inspTab = tabs[0][0];
      h += '<div class="itabs" role="tablist" aria-label="' + (web ? 'Page' : 'Layer') + ' settings">' + tabs.map(function (t) {
        return '<button type="button" role="tab" data-itab="' + t[0] + '" aria-selected="' + (S.inspTab === t[0]) + '" tabindex="' + (S.inspTab === t[0] ? 0 : -1) + '">' + t[1] + '</button>'; }).join('') + '</div>';
      var body = '';
      if (S.inspTab === 'page') body = fieldHtml('url', o.url, { saved: sv.url }) +
        '<div class="grid2">' + fieldHtml('viewportWidth', o.viewportWidth, { placeholder: String(z.w), fromPicture: true, saved: sv.viewportWidth }) + fieldHtml('viewportHeight', o.viewportHeight, { placeholder: String(z.h), fromPicture: true, noHelp: true, saved: sv.viewportHeight }) + '</div>' +
        overlaySettings(d, true) + U.roNote();
      else if (S.inspTab === 'format') body = formatTabHtml(o, sv);
      else if (S.inspTab === 'text') body = textTabHtml(o, sv);
      else if (S.inspTab === 'shape') body = shapeTabHtml(o, sv);
      else if (S.inspTab === 'fx') body = '<div class="grid2">' + of(o, sv, 'shadowColor') + of(o, sv, 'shadowOffset') + '</div>' + opacityHtml(o, sv);
      else if (S.inspTab === 'image') body = imageTabHtml(o);
      else if (S.inspTab === 'place') body = '<div class="grid2">' +
        fieldHtml('x', o.x || 0, { noHelp: true, saved: sv.id ? sv.x || 0 : undefined }) + fieldHtml('y', shownY(o), { noHelp: true, saved: sv.id ? shownY(sv) : undefined }) +
        // a line, a triangle and a free text are sized by their points and their font, not a box
        (resizable(o) ? fieldHtml('w', z.w, { noHelp: true, saved: sv.id ? size(sv).w : undefined }) + fieldHtml('h', z.h, { noHelp: true, saved: sv.id ? size(sv).h : undefined }) : '') + '</div>' +
        (web ? '' : of(o, sv, 'rotation')) + (o.type === 'image' ? fieldHtml('lockAspect', o.lockAspect !== false, { saved: sv.id ? sv.lockAspect !== false : undefined }) : '') +
        (TABS.shape === tabs ? '' : opacityHtml(o, sv)) +
        '<div class="hrow chips"><span class="bdg' + (fit === 'inside' ? '' : ' warn') + '" data-fit>' + FIT[fit] + '</span>' +
        '<span class="bdg">Source ' + s.w + ' x ' + s.h + '</span>' + (ro() ? '' : U.tip('Drag the layer on the canvas; its corners resize it and the round handle above turns it. It snaps to the frame, the title safe area and other layers; hold Ctrl to place it freely. Arrow keys move 1 px, Shift 10 px. Positions are in source pixels.')) + '</div>' + (ro() ? U.roNote() : '');
      else if (S.inspTab === 'look') body = keyHtml(o, sv);
      else if (S.inspTab === 'time') body = (web ? '<div class="grid2">' + fieldHtml('pageDelay', o.pageDelay, { saved: sv.pageDelay }) + fieldHtml('reloadRate', o.reloadRate, { saved: sv.reloadRate }) + '</div>' : '') +
        of(o, sv, 'showFrom', { placeholder: 'stream+30' }) + of(o, sv, 'hideAt', { placeholder: '2027-01-01T18:00:00-05:00' }) +
        '<div class="grid2">' + of(o, sv, 'fadeIn') + of(o, sv, 'fadeOut') + '</div>' + of(o, sv, 'blinkSeconds', { placeholder: 'Steady' }) +
        '<p class="hint">The canvas plays this timing: outside its show window a layer is drawn faint, with when it shows. Freeze time, under the canvas, stops the clock while you edit.</p>';
      var lv = liveOf(o);
      if (lv) h += '<section><div class="note" data-livenote><div>Live ' + U.esc(liveWords(lv)) + (/ and /.test(liveWords(lv)) ? ' are' : ' is') + ' on air instead of these.' +
        (lv.text != null ? ' Live text: <b>' + U.esc(String(lv.text)) + '</b>' : '') + '</div>' + (ro() ? '' : '<button type="button" class="btn sm" data-liveclear="' + U.esc(o.id) + '">Clear</button>') + '</div></section>';
      if (web && capNote(o.id) === 'bad') h += '<section><div class="note bad" data-capnote><span class="dot bad"></span><div>' + capLine(o.id) + '</div></div></section>';
      if (body) h += '<section>' + body + '</section>';
    }
    // a text, shape or image shows only Advanced drawing; with nothing selected, the capture speed shows too when there is a page
    if (!o || !web) h += '<section>' + (o ? '' : '<div class="sec-h">Every layer of ' + U.esc(S.name) + '</div>') + overlaySettings(d, !o) + U.roNote() + '</section>';
    el.insp.innerHTML = h;
    el.insp.querySelectorAll('details[data-ovadv]').forEach(function (x) { x.addEventListener('toggle', function () { S.advOpen = x.open; }); });
    // a named speed fills in the exact value beside it, so the draft, Save and Revert treat it as that field
    U.bindSpeed(el.insp, 'data-ospeed', function (v) { var i = el.insp.querySelector('[data-f=refreshRate]'); if (i) { i.value = v; i.dispatchEvent(new Event('input')); } });
    el.insp.querySelectorAll('[data-itab]').forEach(function (b) {
      b.onclick = function () { S.inspTab = b.getAttribute('data-itab'); S.imgPick = false; renderInspector(); var t = el.insp.querySelector('[data-itab="' + S.inspTab + '"]'); if (t) t.focus(); if (S.inspTab === 'look' && o) captureSoon(o.id); };
      b.onkeydown = function (e) {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
        var all = Array.prototype.slice.call(el.insp.querySelectorAll('[data-itab]')), k = all.indexOf(b) + (e.key === 'ArrowRight' ? 1 : -1);
        all[(k + all.length) % all.length].click();
      };
    });
    el.insp.querySelectorAll('[data-f]').forEach(function (i) { i.addEventListener('input', onField); i.addEventListener('change', onField); });
    el.insp.querySelectorAll('[data-cpick]').forEach(function (p) { p.addEventListener('input', onPick); });
    var lid = el.insp.querySelector('[data-lid]');
    if (lid && o) {
      var commit = function () { if (lid.value.trim() !== o.id) renameLayer(o.id, lid.value.trim(), lid); };
      lid.addEventListener('change', commit);
      lid.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); commit(); } else if (e.key === 'Escape') { lid.value = o.id; lid.blur(); } });
    }
    var kd = el.insp.querySelector('[data-kind]');
    if (kd && o) kd.onchange = function () { changeKind(o.id, kd.value); };
    el.insp.querySelectorAll('[data-liveclear]').forEach(function (b) { b.onclick = function () { clearLive(b.getAttribute('data-liveclear')); }; });
    el.insp.querySelectorAll('[data-tstyle]').forEach(function (b) { b.onclick = function () { if (o) applyStyle(o, b.getAttribute('data-tstyle')); }; });
    if (o && S.inspTab === 'format') bindFormat(o);
    var nf = el.insp.querySelector('[data-nofill]');
    if (nf && o) nf.onchange = function () { setNoFill(o, nf.checked); };
    showProblems();
    var bx = el.insp.querySelector('details[data-boxadv]');
    if (bx) bx.addEventListener('toggle', function () { S.boxOpen = bx.open; });
    var ch = el.insp.querySelector('[data-imgchoose]');
    if (ch) ch.onclick = function () {
      S.imgPick = true; renderInspector();
      U.images.library().then(function () { if (S.imgPick) renderInspector(); }, function (er) { S.imgPick = false; renderInspector(); U.toast('The image list could not be read: ' + er.message, { level: 'bad' }); });
    };
    if (S.imgPick && o && o.type === 'image') U.images.bind(el.insp.querySelector('.ly-picker'), {
      onCancel: function () { S.imgPick = false; renderInspector(); },
      onPick: function (file, uploaded) { setImage(o.id, file); if (uploaded) U.toast('Uploaded ' + file + ' and chose it for ' + o.id + '.'); },
      inUse: function (f) { var u = objects().filter(function (x) { return x.type === 'image' && x.imageFile === f; })[0]; return u ? 'Layer ' + u.id + ' in this overlay uses ' + f + '; choose another image for it first.' : null; },
      onChange: renderInspector
    });
    var rp = el.insp.querySelector('[data-reloadpage]');
    if (rp) rp.onclick = function () { reloadPage(o.id); };
    var ci = el.insp.querySelector('[data-closeinsp]');
    if (ci) ci.onclick = U.toggleInsp;
    var raw = el.insp.querySelector('[data-raw]');
    if (raw && !ro()) raw.addEventListener('click', pickColor);
    if (o) bindCropper(o);
  }

  /* ---------- the panel for each kind (plan 4.2) ---------- */
  var TABS = { webPage: [['page', 'Page'], ['place', 'Place'], ['look', 'Key, crop'], ['time', 'Timing']], string: [['text', 'Text'], ['place', 'Place'], ['time', 'Timing']],
    image: [['image', 'Image'], ['place', 'Place'], ['time', 'Timing']], shape: [['shape', 'Shape'], ['fx', 'Effects'], ['place', 'Place'], ['time', 'Timing']] };
  // one layer field: its own value, or blank with what it draws with as the placeholder (the overlay's or the module's default)
  function of(o, sv, name, extra) {
    var f = F[name], k = f.key, v = o[k], eff = U.draw.effective(o, S.draft)[k], opts = { saved: sv.id ? sv[k] : undefined, savedKnown: !!sv.id }, x;
    for (x in extra || {}) opts[x] = extra[x];
    if (f.type === 'enum' || f.type === 'font') v = v != null ? v : eff != null ? eff : f.def;
    else if (f.type === 'bool') v = v == null ? !!f.def : v;
    else if (v == null) {
      v = '';
      var p = eff != null && eff !== '' ? eff : f.def;
      if (opts.placeholder === undefined && p != null) opts.placeholder = String(p);
    }
    return fieldHtml(name, v, opts);
  }
  function opacityHtml(o) { return fieldHtml('opacity', o.opacity == null || o.opacity < 0 ? 100 : o.opacity, { noHelp: true }); }
  // the Format tab (plan 4.2): what the text's first token shows, the formats with samples, its pattern, zone and language
  var KIND_NAMES = { time: 'The date and time now', countdown: 'A countdown to a target', countup: 'A count-up from a target', streamtime: 'Time since the stream started' };
  var PRESETS = { time: ['HH:mm:ss', 'h:mm:ss a', 'HH:mm', 'yyyy-MM-dd', 'EEE, MMM d', 'MMMM d, yyyy', 'yyyy-MM-dd HH:mm:ss', 'yyyy-MM-dd HH:mm:ss z'],
    countdown: ['H:mm:ss', "d'd' HH:mm:ss", 'mm:ss'], countup: ['H:mm:ss', 'mm:ss'], streamtime: ['H:mm:ss', 'mm:ss'] };
  var PATTERN_HELP = {
    time: 'Java date and time letters: yyyy year, MMM or MM month, d day, EEE weekday, HH hours (h with a for AM and PM), mm minutes, ss seconds, z zone. Text in single quotes shows as written.',
    duration: 'd days, H hours, m minutes, s seconds, S tenths. A doubled letter pads to two digits, and the largest unit is a total: 90 minutes in mm:ss is 90:00. Text in single quotes shows as written.' };
  function formatTabHtml(o, sv) {
    var r = U.format.parse(o.text), toks = r.parts.filter(function (x) { return x.kind; }), p = toks[0];
    if (!p) return '<div class="note warn"><div data-ferr>' + U.esc(r.error || 'This text has no date or time token.') + '</div></div>';
    var kind = p.kind, def = U.format.defaults(), dis = ro() ? ' disabled' : '';
    var h = (toks.length > 1 ? '<p class="hint">This text has ' + toks.length + ' tokens; these settings change the first, ' + U.esc(U.format.token(p.kind, p.pattern)) + '. Edit the others in the Text tab.</p>' : '') +
      '<div class="fld"><label class="f" for="ovs-fkind">What it shows</label><select id="ovs-fkind" data-fkind' + dis + '>' +
      U.format.KINDS.map(function (k) { return '<option value="' + k + '"' + (k === kind ? ' selected' : '') + '>' + KIND_NAMES[k] + '</option>'; }).join('') + '</select></div>' +
      '<div class="fld"><span class="f">Formats</span><div class="fpresets" role="group" aria-label="Formats">' +
      PRESETS[kind].map(function (pat) {
        return '<button type="button" class="fpreset" data-fpreset="' + U.esc(pat) + '" aria-pressed="' + (pat === p.pattern) + '"' + dis + '><span class="fs" data-fsample="' + U.esc(kind + '|' + pat) + '"></span><code>' + U.esc(pat) + '</code></button>';
      }).join('') + '</div></div>' +
      '<div class="fld"><label class="f" for="ovs-fpattern">Pattern' + U.tip(PATTERN_HELP[kind === 'time' ? 'time' : 'duration']) + '</label><input type="text" id="ovs-fpattern" data-fpattern value="' + U.esc(p.pattern) + '" spellcheck="false" autocomplete="off"' + (ro() ? ' readonly' : '') + '>' +
      '<div class="err" data-ferr></div><div class="hint">Now: <b data-fnow data-fsample="' + U.esc(kind + '|' + p.pattern) + '"></b>' + (kind === 'streamtime' ? '. Stream time is simulated here, from when you opened this overlay.' : '') + '</div></div>';
    if (U.format.needsTarget(kind)) h += of(o, sv, 'target', { placeholder: '2027-01-01T18:00:00-05:00' }) +
      (o.target ? '' : '<div class="note warn" data-notargetnote><div>No target set. The canvas counts to an hour from when you opened this overlay, faded; the video shows the text at zero.</div></div>') +
      (kind === 'countdown' ? of(o, sv, 'endText', { placeholder: 'Nothing' }) : '');
    return h + '<div class="grid2">' + of(o, sv, 'timeZone', { placeholder: def ? def.zone : 'The server\'s zone', list: 'ovs-zones' }) + of(o, sv, 'locale', { placeholder: def ? def.locale : 'en-US' }) + '</div>' +
      '<p class="hint">Samples come from the server, in its zone ' + (def ? U.esc(def.zone) : '') + ' unless the layer names one.</p>' + zoneList();
  }
  function zoneList() {
    var z = [];
    try { z = Intl.supportedValuesOf('timeZone'); } catch (e) { /* the field still takes a typed zone */ }
    return '<datalist id="ovs-zones">' + ['UTC'].concat(z).map(function (x) { return '<option value="' + U.esc(x) + '">'; }).join('') + '</datalist>';
  }
  // the first token of the text, rewritten; the reason when the text would not parse
  function setToken(o, kind, pattern) {
    var p = U.format.tokens(o.text)[0];
    if (!p) return 'This text has no date or time token.';
    var next = o.text.slice(0, p.at) + U.format.token(kind, pattern) + o.text.slice(p.end), r = U.format.parse(next);
    if (r.error) return r.error;
    snapshot('field:text');
    o.text = next;
    ticked = {};
    renderLayers(); placeBoxes(); markEdited(); renderBar(); renderCaption(); U.changed();
    return null;
  }
  function bindFormat(o) {
    S.fErr = null;
    refreshFormat();
    if (ro()) return;
    var k = el.insp.querySelector('[data-fkind]'), pi = el.insp.querySelector('[data-fpattern]');
    if (k) k.onchange = function () {
      var p = U.format.tokens(o.text)[0], nk = k.value;
      // a pattern moves between the duration kinds; the date and time kind has letters of its own
      setToken(o, nk, (p.kind === 'time') === (nk === 'time') ? p.pattern : PRESETS[nk][0]);
      renderInspector();
      var x = el.insp.querySelector('[data-fkind]'); if (x) x.focus();
    };
    el.insp.querySelectorAll('[data-fpreset]').forEach(function (b) {
      b.onclick = function () {
        var pat = b.getAttribute('data-fpreset');
        setToken(o, U.format.tokens(o.text)[0].kind, pat);
        renderInspector();
        var x = el.insp.querySelector('[data-fpreset="' + CSS.escape(pat) + '"]'); if (x) x.focus();
      };
    });
    if (pi) pi.addEventListener('input', function () {
      var p = U.format.tokens(o.text)[0];
      S.fErr = !pi.value ? 'A pattern is needed, such as ' + PRESETS[p.kind][0] + '.' : setToken(o, p.kind, pi.value);
      if (!S.fErr) {
        var now = el.insp.querySelector('[data-fnow]'); if (now) now.setAttribute('data-fsample', p.kind + '|' + pi.value);
        el.insp.querySelectorAll('[data-fpreset]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-fpreset') === pi.value)); });
        pi.classList.add('is-draft');
      }
      refreshFormat();
    });
  }
  // text styles: settings for the selected text, not starts (plan 4.3)
  function stylesHtml() {
    if (ro()) return '';
    return '<div class="fld"><span class="f">Text style' + U.tip('Sets the font, size, weight, color and background box for this text. Change any of them after.') + '</span>' +
      '<div class="tstyles" role="group" aria-label="Text styles">' + U.presets.STYLES.map(function (s) { return '<button type="button" class="btn sm" data-tstyle="' + s.id + '">' + U.esc(s.name) + '</button>'; }).join('') + '</div></div>';
  }
  // the style's font is loaded first, so a free text's top is kept with the font's own measurements, not a fallback's
  function applyStyle(o, id) {
    var fields = U.presets.style(id, src().h);
    if (!fields) return;
    var st = String(fields.fontStyle || ''), css = (st.indexOf('italic') >= 0 ? 'italic ' : '') + (st.indexOf('bold') >= 0 ? '700 ' : '400 ') + fields.fontSize + 'px "' + fields.font + '"';
    var ready = document.fonts && document.fonts.load ? document.fonts.load(css).catch(function () { return null; }) : Promise.resolve();
    ready.then(function () { if (byId(o.id) === o) setStyle(o, id, fields); });
  }
  function setStyle(o, id, fields) {
    var top = freeText(o) ? shownY(o) : null;
    snapshot();
    // a style replaces the box look as a whole: a background, padding or corners it does not set go
    ['background', 'padding', 'radius'].forEach(function (k) { if (!(k in fields)) delete o[k]; });
    Object.keys(fields).forEach(function (k) { o[k] = fields[k]; });
    if (top !== null) o.y = storedY(o, top);
    ticked = {};
    renderLayers(); renderInspector(); placeBoxes(); markEdited(); renderBar(); renderCaption(); U.changed();
    var b = el.insp.querySelector('[data-tstyle="' + id + '"]'); if (b) b.focus();
    U.toast('Applied the ' + U.presets.STYLES.filter(function (s) { return s.id === id; })[0].name + ' style to ' + o.id + '.');
  }
  function textTabHtml(o, sv) {
    loadFonts();
    return stylesHtml() + of(o, sv, 'text') + '<div class="grid2">' + of(o, sv, 'font') + of(o, sv, 'fontSize') + of(o, sv, 'fontStyle') + of(o, sv, 'align') + '</div>' + of(o, sv, 'textColor') +
      '<details class="adv" data-boxadv' + (S.boxOpen ? ' open' : '') + '><summary>Box and outline</summary>' + of(o, sv, 'box') +
      '<div class="grid2">' + of(o, sv, 'background') + of(o, sv, 'padding') + of(o, sv, 'outlineColor') + of(o, sv, 'outlineWidth') + of(o, sv, 'radius') + of(o, sv, 'lineHeight') + '</div>' +
      of(o, sv, 'autoFit') + '</details>';
  }
  function shapeTabHtml(o, sv) {
    var line = o.type === 'line';
    return kindHtml(o) + '<div class="grid2">' + (line ? '' : noFill(o) ? '<div class="fld"><span class="f">Fill</span><div class="val" data-nofillval>None</div></div>' : of(o, sv, 'fillColor')) +
      of(o, sv, 'lineColor') + of(o, sv, 'stroke') + of(o, sv, 'dash') +
      (line ? of(o, sv, 'arrowHead') : '') + of(o, sv, 'gradientColor') + of(o, sv, 'gradientDirection') + '</div>' + noFillHtml(o) + (o.type === 'rectangle' ? of(o, sv, 'arcWidth') : '');
  }
  // an empty shape: the line only (said by Alex, 2026-11-03). Saved as a clear fill, so an overlay-wide fill default
  // (ObjectConfig falls back to it when fillColor is blank) does not fill it again
  var NO_FILL = 'rgb(0,0,0,0)', lastFill = {};
  function noFill(o) { return o.fillColor === NO_FILL; }
  function noFillHtml(o) {
    if (ro() || ['rectangle', 'ellipse', 'triangle'].indexOf(o.type) < 0) return '';
    var e = U.draw.effective(o, S.draft), lineless = !(e.stroke > 0) || !e.color;
    return '<div class="fld"><label class="check"><input type="checkbox" data-nofill' + (noFill(o) ? ' checked' : '') + (lineless && !noFill(o) ? ' disabled' : '') + '>No fill, the line only</label>' +
      (lineless && !noFill(o) ? '<div class="hint">Give the shape a line width and color first: with no fill and no line it draws nothing.</div>' : '') + '</div>';
  }
  function setNoFill(o, on) {
    snapshot();
    var keep = lastFill[o.id] || {};
    if (on) {
      lastFill[o.id] = { fill: o.fillColor, gradient: o.gradientColor };
      o.fillColor = NO_FILL;
      // a gradient fills the shape from its fill to the gradient's color, so an empty shape has none
      delete o.gradientColor;
    } else {
      o.fillColor = keep.fill && keep.fill !== NO_FILL ? keep.fill : 'rgb(30,90,200)';
      if (keep.gradient) o.gradientColor = keep.gradient;
    }
    ticked = {};
    renderLayers(); renderInspector(); placeBoxes(); markEdited(); renderBar(); renderCaption(); U.changed();
    var c = el.insp.querySelector('[data-nofill]'); if (c) c.focus();
  }
  // the fonts the server can draw (GET /fonts), read once
  var fonts = null, fontsAsked = false;
  function loadFonts() {
    if (fontsAsked) return;
    fontsAsked = true;
    U.api.get('/fonts').then(function (r) {
      fonts = r || {};
      fillFontLists();
    }, function () { /* the field offers the layer's own font until a later page load */ });
  }
  // the font fields fill in where they are once the list comes: a whole new panel would wait for the focus to leave
  // it, and a clock's Text tab, opened from its Format tab, keeps the focus on the tab
  function fillFontLists() {
    if (U.current() !== 'overlay' || !el.insp) return;
    el.insp.querySelectorAll('select[data-f=font]').forEach(function (s) {
      // an open list is not changed under the pointer; it fills when it closes
      if (s === document.activeElement) { s.addEventListener('blur', fillFontLists, { once: true }); return; }
      var v = s.value;
      s.innerHTML = fontOptions(v, function (x, text) { return '<option value="' + U.esc(x) + '"' + (x === v ? ' selected' : '') + '>' + U.esc(text) + '</option>'; });
    });
  }
  function fontOptions(v, opt) {
    var groups = fonts ? [['Bundled with Overlay Studio', (fonts.bundled || []).map(function (b) { return b.family; })], ['Java', fonts.logical || []], ['On this server', fonts.system || []]] : [];
    var all = [].concat.apply([], groups.map(function (g) { return g[1]; }));
    return (all.indexOf(v) < 0 ? opt(v, v + (fonts ? ' (not on this server)' : '')) : '') + groups.filter(function (g) { return g[1].length; }).map(function (g) {
      return '<optgroup label="' + U.esc(g[0]) + '">' + g[1].map(function (n) { return opt(n, n); }).join('') + '</optgroup>'; }).join('');
  }
  // the picture an image layer draws, and the library to choose another (ovs-images.js)
  function imageTabHtml(o) {
    var f = o.imageFile, im = f ? U.draw.image(f) : null;
    var h = '<div class="fld"><span class="f">' + U.esc(F.imageFile.label) + U.tip(F.imageFile.help) + '</span><div class="val" data-imgname>' + (f ? U.esc(f) +
      (im && im.width ? ' <span class="faint small">' + im.width + ' x ' + im.height + '</span>' : im && im.error ? ' <span class="warn-text small">' + U.esc(im.error) + '</span>' : '') : 'No image chosen') + '</div></div>';
    if (ro()) return h;
    return h + (S.imgPick ? U.images.pickerHtml({ selected: f, okLabel: 'Use image' }) :
      '<div class="hrow"><button type="button" class="btn sm" data-imgchoose>' + U.icon('image') + (f ? 'Choose another image' : 'Choose an image') + '</button></div>');
  }
  function setImage(id, file) {
    var o = byId(id);
    if (!o) return;
    snapshot();
    o.imageFile = file; S.imgPick = false;
    renderLayers(); renderInspector(); placeBoxes(); renderBar(); renderCaption(); U.changed();
  }

  /* ---------- a shape's kind, within its geometry (plan 4.1): the box kinds share x, y, w, h; line and arrow their ends ---------- */
  var BOX_KINDS = ['rectangle', 'rounded', 'square', 'ellipse', 'circle'], LINE_KINDS = ['line', 'arrow'];
  function shapeKind(o) {
    if (o.type === 'rectangle') return o.arcWidth > 0 ? 'rounded' : o.w > 0 && o.w === o.h ? 'square' : 'rectangle';
    if (o.type === 'ellipse') return o.w > 0 && o.w === o.h ? 'circle' : 'ellipse';
    if (o.type === 'line') return o.arrowHead && o.arrowHead !== 'none' ? 'arrow' : 'line';
    return null;
  }
  function kindHtml(o) {
    var now = shapeKind(o);
    if (!now) return '';
    if (ro()) return '<div class="fld"><span class="f">Shape</span><div class="val">' + U.esc(KIND_LABEL[now]) + '</div></div>';
    var kinds = o.type === 'line' ? LINE_KINDS : BOX_KINDS;
    return '<div class="fld"><label class="f" for="ovs-kind">Shape</label><select id="ovs-kind" data-kind>' + kinds.map(function (k) {
      return '<option value="' + k + '"' + (k === now ? ' selected' : '') + '>' + U.esc(KIND_LABEL[k]) + '</option>'; }).join('') + '</select>' +
      '<div class="hint">' + (o.type === 'line' ? 'A line and an arrow share their ends.' : 'These shapes share one box. A line, arrow or triangle is a new layer.') + '</div></div>';
  }
  function changeKind(id, kind) {
    var o = byId(id);
    if (!o || ro() || shapeKind(o) === kind) return;
    snapshot();
    if (kind === 'arrow') o.arrowHead = 'end';
    else if (kind === 'line') delete o.arrowHead;
    else {
      o.type = kind === 'ellipse' || kind === 'circle' ? 'ellipse' : 'rectangle';
      if (kind === 'rounded') { if (!(o.arcWidth > 0)) { o.arcWidth = 24; o.arcHeight = 24; } }
      else { delete o.arcWidth; delete o.arcHeight; }
      // a square or circle takes the shorter side, so it stays where the box was
      if (kind === 'square' || kind === 'circle') o.w = o.h = even(Math.min(o.w || 0, o.h || 0)) || 2;
      else if (o.w === o.h && (kind === 'rectangle' || kind === 'ellipse')) o.w = even(Math.min(o.w * 1.5, src().w - (o.x || 0)));
    }
    renderEditor();
    var kd = el.insp.querySelector('[data-kind]'); if (kd) kd.focus();
  }

  // the overlay's own settings, for every page in it: how often pages are captured, and how they are drawn
  function overlaySettings(d, speed) {
    var sd = S.saved || {}, rr = d.refreshRate, draft = sd.refreshRate !== undefined && sd.refreshRate !== rr;
    // the capture speed is how often the module takes a page's new frame (OverlayImage.nextWakeAt); text, shapes,
    // images and clocks redraw on their own schedule, so it is offered only to an overlay with a web page
    // and only where a page is being looked at: its own panel, or the overlay's with nothing selected (said by Alex, 2026-11-03)
    var pages = speed && objects().some(function (x) { return x.type === 'webPage'; });
    var every = ro() ? '<span class="every">every ' + U.esc(rr) + ' s</span>' :
      '<label class="every" title="WSE setting refreshRate">every<input type="number" id="ovs-f-refreshRate" data-f="refreshRate" class="num' + (draft ? ' is-draft' : '') + '" min="' + F.refreshRate.min + '" max="' + F.refreshRate.max + '" step="any" value="' + U.esc(rr) + '" aria-label="Capture every, seconds">s</label>';
    return '<div class="ovset' + (pages ? '' : ' bare') + '">' + (pages ? U.speedHtml(+rr, 'data-ospeed', ro(), every) + '<div class="err" data-err-for="refreshRate"></div>' +
      (draft ? '<div class="hint">Saved: every ' + U.esc(sd.refreshRate) + ' s</div>' : '') : '') +
      '<details class="adv" data-ovadv' + (S.advOpen ? ' open' : '') + '><summary>Advanced drawing</summary>' +
      fieldHtml('evenCoords', !!d.even_coords, { saved: !!sd.even_coords }) +
      fieldHtml('threadDrawing', d.threadDrawing !== false, { saved: sd.threadDrawing !== false }) +
      fieldHtml('maxDrawingTime', d.maxDrawingTime || F.maxDrawingTime.def, { saved: sd.maxDrawingTime || F.maxDrawingTime.def }) + '</details></div>';
  }

  /* ---------- transparency and crop: the part of the captured page that is drawn ---------- */
  function hasCrop(o) { return o.cropW > 0 && o.cropH > 0; }
  function keyHtml(o, sv) {
    return '<div class="capture-pair"><div class="cap raw"><span data-rawwait' + (S.raw[o.id] ? ' hidden' : '') + ' style="position:static;background:none">Loading the capture...</span><img data-raw alt="As captured"' + (S.raw[o.id] ? ' src="' + S.raw[o.id] + '"' : ' hidden') + '><span>Captured page</span></div>' +
      '<div class="cap keyed"><img data-keyed alt="After keying"' + (S.captures[o.id] ? ' src="' + S.captures[o.id] + '"' : ' hidden') + '><span>Keyed, over the video</span></div></div>' +
      (ro() ? '' : '<p class="hint">Click the captured page to pick the color to make transparent. The result shows within about a second.</p>') +
      '<div class="grid2" style="margin-top:8px">' + fieldHtml('keyColor', o.keyColor || F.keyColor.def, { noHelp: true, saved: sv.id ? sv.keyColor || F.keyColor.def : undefined }) + fieldHtml('keyTolerance', o.keyTolerance || 0, { noHelp: true, saved: sv.id ? sv.keyTolerance || 0 : undefined }) + '</div>' +
      fieldHtml('paintBackground', o.paintBackground !== false) +
      '<div class="sec-h" style="margin:12px 0 0">Crop</div>' +
      '<div class="cropper" data-cropper><img data-craw alt="The whole page as captured; drag to choose the part to show"' + (S.raw[o.id] ? ' src="' + S.raw[o.id] + '"' : ' hidden') + '>' +
      '<div class="cap" data-cwait' + (S.raw[o.id] ? ' hidden' : '') + '>Loading the page...</div><div class="crop-box" data-cbox hidden></div></div>' +
      (ro() ? '' : '<p class="hint">Drag across the page to show only that part, for example just the clock. A dragged crop also gives the page that shape on the picture.</p>') +
      '<div class="grid2">' + fieldHtml('cropX', o.cropX || 0, { noHelp: true }) + fieldHtml('cropY', o.cropY || 0, { noHelp: true }) + fieldHtml('cropW', o.cropW || 0, { noHelp: true }) + fieldHtml('cropH', o.cropH || 0, { noHelp: true }) + '</div>' +
      (ro() ? '' : '<div class="hrow"><button type="button" class="btn sm" data-act="crop-reset"' + (hasCrop(o) ? '' : ' disabled') + '>Show the whole page</button></div>');
  }
  function pageSize(o) { var img = el.insp.querySelector('[data-craw]'); return img && img.naturalWidth ? { w: img.naturalWidth, h: img.naturalHeight } : { w: o.viewportWidth || size(o).w, h: o.viewportHeight || size(o).h }; }
  function drawCropBox(o) {
    var box = el.insp.querySelector('[data-cbox]'), img = el.insp.querySelector('[data-craw]');
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
    ['cropX', 'cropY', 'cropW', 'cropH', 'h'].forEach(function (k) { var i = el.insp.querySelector('[data-f=' + k + ']'); if (i) i.value = o[k]; });
    var reset = el.insp.querySelector('[data-act=crop-reset]'); if (reset) reset.disabled = whole;
    drawCropBox(o); placeBoxes(); markEdited(); renderBar(); renderCaption(); captureSoon(o.id); U.changed();
  }
  function bindCropper(o) {
    var wrap = el.insp.querySelector('[data-cropper]');
    if (!wrap) return;
    var img = wrap.querySelector('[data-craw]');
    img.addEventListener('load', function () { drawCropBox(o); });
    drawCropBox(o);
    if (ro()) return;
    el.insp.querySelector('[data-act=crop-reset]').onclick = function () { snapshot(); setCrop(o, { x: 0, y: 0, w: 0, h: 0 }); };
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
    var fld = e.target.closest('.fld, .ovset'), err = fld && fld.querySelector('[data-err-for="' + name + '"]');
    if (f.scope !== 'overlay' && !o) return;
    if (f.type === 'bool') v = e.target.checked;
    else if (f.type === 'int' || f.type === 'num') {
      v = e.target.value === '' ? null : (f.type === 'int' ? parseInt(e.target.value, 10) : parseFloat(e.target.value));
      var bad = v !== null && (isNaN(v) || (f.min !== undefined && v < f.min) || (f.max !== undefined && v > f.max));
      e.target.classList.toggle('invalid', !!bad);
      if (err) err.textContent = bad ? 'From ' + f.min + (f.max !== undefined ? ' to ' + f.max : ' up') + '.' : '';
      if (bad) return;
    } else v = e.target.value;
    // a blank color or time is no value: the layer draws without it
    if ((f.type === 'color' || f.type === 'when' || f.type === 'zone' || f.type === 'target' || name === 'locale' || name === 'endText') && f.scope === 'object' && !String(v).trim()) v = null;
    var why = v === null ? null : f.type === 'color' && f.scope === 'object' && !U.draw.isColor(v) ? 'Use rgb(r,g,b), rgb(r,g,b,a) or a color name such as white.' :
      f.type === 'when' && !WHEN.test(String(v).trim()) ? 'Use stream+N for seconds after the stream starts, or a date and time with its offset such as 2027-01-01T18:00:00-05:00.' :
      name === 'text' && v.length > 2000 ? 'At most 2,000 characters (' + v.length + ' now).' :
      f.type === 'zone' && !zoneOk(String(v).trim()) ? 'Use a zone such as America/New_York or UTC.' :
      f.type === 'target' && !TARGET.test(String(v).trim()) ? 'Use a date and time with its offset, such as 2027-01-01T18:00:00-05:00.' :
      name === 'locale' && !/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/.test(String(v).trim()) ? 'Use a language tag such as en-US or fr-FR.' : null;
    if (f.type === 'color' || f.type === 'when' || f.type === 'zone' || f.type === 'target' || name === 'locale' || name === 'text') {
      e.target.classList.toggle('invalid', !!why);
      if (err) err.textContent = why || '';
      if (why) return;
      if (v !== null && f.type !== 'color' && name !== 'text') v = String(v).trim();
    }
    if (name === 'url') { var p = urlProblem(v); e.target.classList.toggle('invalid', !!p); if (err) err.textContent = p || ''; }
    // the picker follows a color typed in
    if (f.type === 'color') { var cp = el.insp.querySelector('[data-cpick="' + name + '"]'); if (cp && document.activeElement !== cp) cp.value = hexOf(v || e.target.placeholder); }
    snapshot('field:' + name);
    if (f.scope === 'overlay') { S.draft[f.key] = v; if (name === 'evenCoords') renderCoords(); if (name === 'refreshRate') U.syncSpeed(el.insp, 'data-ospeed', v); }
    else if (name === 'box') { setBox(o, v); return; }
    else {
      // a free text keeps its top when its font changes, so it grows down like a box
      var top = freeText(o) && TOP_KEYS.indexOf(name) >= 0 ? shownY(o) : null;
      if (name === 'y' && v !== null) v = storedY(o, v);
      if (v === null) delete o[f.key]; else o[f.key] = v;
      if (top !== null) o.y = storedY(o, top);
      // the curve of a corner is as tall as it is wide
      if (name === 'arcWidth') { if (v === null) delete o.arcHeight; else o.arcHeight = v; }
      // text, shapes and images draw from every field; a page from its place
      if (o.type !== 'webPage' || ['x', 'y', 'w', 'h', 'opacity'].indexOf(name) >= 0) placeBoxes();
      if (o.type !== 'webPage') { var nm = el.layers.querySelector('.prow[data-id="' + CSS.escape(o.id) + '"] .lname'); if (nm) nm.textContent = U.draw.name(o); }
      // the Shape select follows a field that changes which shape it is
      if (name === 'arrowHead' || name === 'arcWidth') { var kd = el.insp.querySelector('[data-kind]'), sk = shapeKind(o); if (kd && sk) kd.value = sk; }
      // a target set or cleared: the row's "No target set" and the Format tab's note follow
      if (name === 'target') { renderLayers(); var nn = el.insp.querySelector('[data-notargetnote]'); if (nn) nn.hidden = v !== null; }
      if (name === 'url') { var sub = el.layers.querySelector('.prow[data-id="' + CSS.escape(o.id) + '"] .sub'); if (sub) sub.textContent = host(v); }
      if (CAPTURE_KEYS.indexOf(f.key) >= 0 && o.type === 'webPage') captureSoon(o.id);
      if (['cropX', 'cropY', 'cropW', 'cropH'].indexOf(name) >= 0) {
        if (!(o.cropW > 0) || !(o.cropH > 0)) { delete o.cropX; delete o.cropY; if (!(o.cropW > 0)) delete o.cropW; if (!(o.cropH > 0)) delete o.cropH; }
        drawCropBox(o);
        var reset = el.insp.querySelector('[data-act=crop-reset]'); if (reset) reset.disabled = !hasCrop(o);
      }
    }
    // the dashed draft mark follows the field without rebuilding the inspector, which would take its focus
    e.target.classList.add('is-draft');
    if (f.scope === 'object') { updateFit(); renderCoords(); problemsSoon(); }
    markEdited(); renderBar(); renderCaption(); U.changed();
  }
  // the problems follow an edit after a pause, so typing asks the server once (fonts/check)
  var probTimer = null;
  function problemsSoon() {
    if (probTimer) clearTimeout(probTimer);
    probTimer = setTimeout(function () { probTimer = null; if (U.current() === 'overlay' && S.draft) { renderLayers(); showProblems(); } }, 300);
  }
  // the text fields that change a free text's ascent
  var TOP_KEYS = ['font', 'fontSize', 'fontStyle'];
  // a countdown's target, as the module takes it (ObjectConfig.target): a date and time with its offset
  var TARGET = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/;
  // a zone as Java's ZoneId.of takes it: an offset such as GMT+5, UTC+01:00 or +05:00, or a region the browser knows
  // spelled exactly (the browser takes america/new_york, Java does not)
  function zoneOk(z) {
    if (/^(Z|UTC|GMT|UT|(UTC|GMT|UT)?[+-]\d{1,2}(:?\d{2})?)$/.test(z)) return true;
    try {
      var r = new Intl.DateTimeFormat('en-US', { timeZone: z }).resolvedOptions().timeZone;
      return !(r !== z && r.toLowerCase() === z.toLowerCase());
    } catch (e) { return false; }
  }
  // a time a layer shows or hides at, as the module takes it (ObjectConfig.timingProblem)
  var WHEN = /^(stream\+\d{1,9}(\.\d{1,3})?|\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2}))$/;
  // a text's box on or off, keeping the text where it is: a box starts at the text's extent, a free text at its first line
  function setBox(o, on) {
    var e = U.draw.effective(o, S.draft), l = U.draw.text(e, U.draw.shown(e)), pad = typeof o.padding === 'number' ? o.padding : 0;
    if (on) { o.box = true; o.x = even(l.area.x); o.y = even(l.area.y); o.w = even(Math.max(l.area.w, 40)); o.h = even(Math.max(l.area.h, 40)); }
    else { o.x = even(l.area.x + pad); o.y = even(l.rows.length ? l.rows[0].base : o.y + pad + l.ascent); delete o.box; delete o.w; delete o.h; }
    renderEditor();
    var c = el.insp.querySelector('[data-f=box]'); if (c) c.focus();
  }
  function syncPlacement() {
    var o = byId(S.layer);
    updateFit();
    ['x', 'y', 'w', 'h'].forEach(function (k) { var i = el.insp.querySelector('[data-f=' + k + ']'); if (i) { i.value = k === 'y' ? shownY(o) : o[k]; i.classList.add('is-draft'); } });
  }
  // the raw capture is a data URL, so the canvas is not tainted
  function pickColor(e) {
    var img = e.target, r = img.getBoundingClientRect(), nw = img.naturalWidth, nh = img.naturalHeight;
    var sc = Math.min(r.width / nw, r.height / nh), dw = nw * sc, dh = nh * sc;
    var px = Math.floor((e.clientX - r.left - (r.width - dw) / 2) / sc), py = Math.floor((e.clientY - r.top - (r.height - dh) / 2) / sc);
    if (px < 0 || py < 0 || px >= nw || py >= nh) return;
    var c = document.createElement('canvas'); c.width = nw; c.height = nh;
    var g = c.getContext('2d'); g.drawImage(img, 0, 0);
    var p = g.getImageData(px, py, 1, 1).data, inp = el.insp.querySelector('[data-f=keyColor]');
    inp.value = 'rgb(' + p[0] + ',' + p[1] + ',' + p[2] + ')';
    inp.dispatchEvent(new Event('input'));
  }

  /* ---------- add a page: a form in the inspector; the page joins the draft and is test-captured there ---------- */
  function startAdd() { if (ro() || !S.draft) return; S.adding = true; renderInspector(); if (el.grid) el.grid.classList.add('insp-open'); }
  function renderAddPage() {
    var used = objects().map(function (o) { return o.id; }), n = 1;
    while (used.indexOf('page' + n) >= 0) n++;
    el.insp.innerHTML = '<div class="pnl-h"><div><h3>Add page</h3><span class="small faint">To ' + U.esc(S.name) + ', unsaved until Save</span></div></div>' +
      '<section><div class="fld"><label class="f" for="ovs-pid">Name</label><input id="ovs-pid" type="text" value="' + (used.length ? 'page' + n : 'score') + '" autocomplete="off" spellcheck="false">' +
      '<div class="hint">For you only, for example "score" or "clock".</div></div>' +
      '<div class="fld"><label class="f" for="ovs-purl">' + U.esc(F.url.label) + '</label><input id="ovs-purl" type="url" placeholder="https://" autocomplete="off" spellcheck="false">' +
      '<div class="hint">' + U.esc(F.url.help) + ' Use the vendor\'s transparent or "bug" view if they have one.</div></div>' +
      '<div class="grid2"><div class="fld"><label class="f" for="ovs-pw">Browser width (px)</label><input id="ovs-pw" type="number" value="1280"></div>' +
      '<div class="fld"><label class="f" for="ovs-ph">Browser height (px)</label><input id="ovs-ph" type="number" value="720"></div></div>' +
      '<p class="hint">The size the vendor designed the page for. The page is captured as soon as it is added, so you see it before Save.</p>' +
      '<p class="err" data-adderr></p><div class="hrow" style="margin-top:12px"><span class="spacer"></span><button type="button" class="btn" data-addcancel>Cancel</button><button type="button" class="btn primary" data-addok>Add to draft</button></div></section>';
    var idI = el.insp.querySelector('#ovs-pid'), urlI = el.insp.querySelector('#ovs-purl');
    urlI.focus();
    function ok() {
      var id = idI.value.trim(), url = urlI.value.trim(), err = el.insp.querySelector('[data-adderr]');
      var vw = parseInt(el.insp.querySelector('#ovs-pw').value, 10), vh = parseInt(el.insp.querySelector('#ovs-ph').value, 10);
      var why = idProblem(id) || urlProblem(url) || (!(vw >= 16 && vh >= 16) ? 'Enter the page size in pixels.' : null);
      if (why) { err.textContent = why; return; }
      snapshot();
      var top = sorted()[0];
      S.draft.objects.push(newPage(id, url, vw, vh, src(), top ? (top.zOrder || 0) + 1 : 1));
      S.adding = false; S.layer = id; renderEditor(); captureSoon(id);
    }
    el.insp.querySelector('[data-addok]').onclick = ok;
    el.insp.querySelector('[data-addcancel]').onclick = function () { S.adding = false; renderInspector(); };
    el.insp.querySelectorAll('input').forEach(function (i) { i.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); ok(); } }); });
  }

  /* ---------- adding layers (plan 4.1): the Add bar and menus, placed on the canvas ---------- */
  var KIND_LABEL = { page: 'Web page', text: 'Text', datetime: 'Date and time', image: 'Image', rectangle: 'Rectangle', rounded: 'Rounded rectangle', square: 'Square',
    ellipse: 'Ellipse', circle: 'Circle', line: 'Line', arrow: 'Arrow', triangle: 'Triangle' };
  var SHAPE_KINDS = ['rectangle', 'rounded', 'square', 'ellipse', 'circle', 'line', 'arrow', 'triangle'];
  var KIND_ICON = { page: 'web', text: 'text', datetime: 'clock', image: 'image', rectangle: 'rect', rounded: 'rounded', square: 'square',
    ellipse: 'ellipse', circle: 'circle', line: 'line', arrow: 'arrow', triangle: 'triangle' };
  function kindItem(k) { return [k, KIND_LABEL[k], false, false, KIND_ICON[k]]; }
  function shapeItems() { return SHAPE_KINDS.map(kindItem); }
  // the one way to add a layer, each kind with its icon: under the layer list and in the empty inspector (said by
  // Alex, 2026-11-03: the bar over the canvas repeated it and did nothing visible when clicked)
  function addMenuHtml(where, cls) {
    return U.rowMenuHtml('add:' + where, 'Add a layer', [kindItem('page'), kindItem('text'), kindItem('datetime'), '-'].concat(shapeItems()).concat(['-', kindItem('image'), '-', ['preset', 'From a preset...', false, false, 'grid']]),
      { html: U.icon('plus') + 'Add layer', cls: cls });
  }
  U.rowActions.add = function (where, kind) { addKind(kind); };
  // from the overlay row's menu: Add shape... opens the Add layer menu, the rest start at once
  function addOnOpen(kind) {
    if (kind !== 'shape') { addKind(kind); return; }
    if (el.pagesAdd) U.openRowMenu(el.pagesAdd);
  }
  // an overlay holds at most 64 layers (OverlayStore.MAX_LAYERS)
  var MAX_LAYERS = 64;
  function full() {
    if (!S.draft || objects().length < MAX_LAYERS) return false;
    U.toast('An overlay holds at most ' + MAX_LAYERS + ' layers. Remove one to add another.', { level: 'bad' });
    return true;
  }
  function addKind(kind) {
    if (full()) return;
    // the preset list opens in the panel (plan 4.3)
    if (kind === 'preset' && !ro() && S.draft) { stopPlacing(); endEdit(); S.adding = false; S.presetPick = true; renderInspector(); var f = el.insp.querySelector('[data-preset]'); if (f) f.focus(); return; }
    if (ro() || !S.draft || !KIND_LABEL[kind]) return;
    // a web page asks for its address first, and appears at the usual spot once added
    if (kind === 'page') { stopPlacing(); startAdd(); return; }
    endEdit();
    S.adding = false; S.placing = kind;
    renderInspector(); placeBoxes();
    focusCanvas();
  }
  function stopPlacing() {
    if (!S.placing) return;
    S.placing = null;
    var f = frameEl(), g = f && f.querySelector('[data-ghost]');
    if (g) g.remove();
    placeBoxes();
  }
  // a press on the canvas while placing: a click places the layer at its usual size, a drag draws it
  function onPlaceDown(e) {
    // the banner's Cancel is not a click on the picture (this listens before the banner can stop it)
    if (!S.placing || e.button !== 0 || e.target.closest('[data-placehint]')) return;
    e.preventDefault(); e.stopPropagation();
    var frame = e.currentTarget, r = frame.getBoundingClientRect(), s = src(), k = s.w / r.width;
    var at = function (ev) { return { x: Math.max(0, Math.min(s.w, (ev.clientX - r.left) * k)), y: Math.max(0, Math.min(s.h, (ev.clientY - r.top) * k)) }; };
    var p0 = at(e), p1 = p0, ghost = document.createElement('div');
    ghost.className = 'ghost'; ghost.setAttribute('data-ghost', ''); frame.appendChild(ghost);
    frame.setPointerCapture(e.pointerId);
    function move(ev) {
      p1 = at(ev);
      var x = Math.min(p0.x, p1.x), y = Math.min(p0.y, p1.y);
      ghost.style.left = 100 * x / s.w + '%'; ghost.style.top = 100 * y / s.h + '%';
      ghost.style.width = 100 * Math.abs(p1.x - p0.x) / s.w + '%'; ghost.style.height = 100 * Math.abs(p1.y - p0.y) / s.h + '%';
    }
    function up() {
      frame.removeEventListener('pointermove', move); frame.removeEventListener('pointerup', up); frame.removeEventListener('pointercancel', up);
      ghost.remove();
      if (!S.placing) return;
      // a few screen pixels of wobble is still a click
      var drag = Math.abs(p1.x - p0.x) / k > 4 || Math.abs(p1.y - p0.y) / k > 4;
      place(S.placing, drag ? { x: p0.x, y: p0.y, x2: p1.x, y2: p1.y } : { x: p0.x, y: p0.y });
    }
    frame.addEventListener('pointermove', move); frame.addEventListener('pointerup', up); frame.addEventListener('pointercancel', up);
  }
  // an id not used yet, text-1, shape-2, ...
  function freeId(prefix) {
    var n = 1;
    while (byId(prefix + '-' + n)) n++;
    return prefix + '-' + n;
  }
  /** Why an id cannot be used for a layer, or null: the module's rule (3.10), unique within the overlay. */
  function idProblem(id, current) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(id) || id.indexOf('..') >= 0) return 'An id is 1 to 64 letters, digits, dots, dashes or underscores, starting with a letter or digit, without "..".';
    if (id !== current && byId(id)) return 'A layer with this id exists.';
    return null;
  }
  // a new layer of this kind; at is the click point {x, y}, a drag {x, y, x2, y2}, or null for the frame's center.
  // Layers are in the stream's own pixels, so the default sizes, set for 1080p, scale with the stream's height
  function layerFor(kind, at, s) {
    var k = s.h / 1080, sz = function (n) { return Math.max(2, Math.round(n * k)); };
    var box = { rectangle: [320, 120], rounded: [320, 120], square: [160, 160], ellipse: [240, 140], circle: [160, 160], line: [300, 0], arrow: [300, 0],
      triangle: [200, 170], image: [320, 180], text: [360, 64], datetime: [300, 64] }[kind];
    var x, y, w = sz(box[0]), h = kind === 'line' || kind === 'arrow' ? 0 : sz(box[1]), drag = at && at.x2 !== undefined;
    if (drag) {
      x = Math.min(at.x, at.x2); y = Math.min(at.y, at.y2); w = Math.abs(at.x2 - at.x); h = Math.abs(at.y2 - at.y);
      // a square or circle keeps its sides equal
      if (kind === 'square' || kind === 'circle') w = h = Math.max(w, h);
    } else if (at) { x = at.x; y = at.y; }
    else { x = (s.w - w) / 2; y = (s.h - h) / 2; }
    if (!(kind === 'line' || kind === 'arrow') || !drag) { x = Math.max(0, Math.min(x, s.w - w)); y = Math.max(0, Math.min(y, s.h - h)); }
    var o = { opacity: 100 };
    if (kind === 'text' || kind === 'datetime') {
      o.id = freeId(kind === 'text' ? 'text' : 'clock'); o.type = 'string';
      o.text = kind === 'text' ? 'Text' : '{time:HH:mm:ss}'; o.font = kind === 'text' ? 'Fira Sans' : 'Fira Mono'; o.fontSize = sz(48); o.color = 'white';
      // a drawn text is a box it wraps in; a placed one has its top left where it was clicked
      if (drag) { o.box = true; o.x = even(x); o.y = even(y); o.w = even(Math.max(w, 40)); o.h = even(Math.max(h, 40)); }
      else { o.x = even(x); o.y = storedY(o, y); }
      return o;
    }
    if (kind === 'image') { o.id = freeId('image'); o.type = 'image'; o.x = even(x); o.y = even(y); o.w = even(Math.max(w, 8)); o.h = even(Math.max(h, 8)); return o; }
    o.id = freeId('shape'); o.color = 'white'; o.stroke = kind === 'line' || kind === 'arrow' ? 4 : 2;
    if (kind === 'line' || kind === 'arrow') {
      o.type = 'line';
      if (drag) { o.x = even(at.x); o.y = even(at.y); o.x2 = even(at.x2); o.y2 = even(at.y2); }
      else { o.x = even(x); o.y = even(y); o.x2 = even(Math.min(s.w, x + w)); o.y2 = even(y); }
      if (kind === 'arrow') o.arrowHead = 'end';
      return o;
    }
    w = Math.max(w, 4); h = Math.max(h, 4);
    if (kind === 'triangle') {
      o.type = 'triangle'; o.fillColor = 'rgb(200,0,0)';
      o.x = even(x); o.y = even(y + h); o.x2 = even(x + w / 2); o.y2 = even(y); o.x3 = even(x + w); o.y3 = even(y + h);
      return o;
    }
    o.type = kind === 'ellipse' || kind === 'circle' ? 'ellipse' : 'rectangle'; o.fillColor = 'rgb(30,90,200)';
    o.x = even(x); o.y = even(y); o.w = even(w); o.h = even(h);
    if (kind === 'rounded') { o.arcWidth = even(sz(24)); o.arcHeight = o.arcWidth; }
    return o;
  }
  function place(kind, at) {
    var o = layerFor(kind, at, src()), top = sorted()[0];
    o.zOrder = top ? (top.zOrder || 0) + 1 : 1;
    snapshot();
    S.draft.objects.push(o);
    S.placing = null;
    S.layer = o.id;
    if (kind === 'datetime') S.inspTab = 'format';
    renderEditor();
    focusCanvas();
    U.toast('Added ' + KIND_LABEL[kind].toLowerCase() + ' ' + o.id + ' to the draft. Viewers see it after Save.');
  }
  // a new id for a layer; a saved layer's rename goes with the save, so its live values move with it (plan 3.6)
  function renameLayer(old, id, input) {
    var why = idProblem(id, old), err = el.insp.querySelector('[data-liderr]');
    if (why) { if (err) err.textContent = why; if (input) { input.setAttribute('aria-invalid', 'true'); input.focus(); } return; }
    var o = byId(old);
    if (!o) return;
    snapshot();
    o.id = id;
    ['captures', 'raw', 'rawAt', 'busy', 'thumbAt', 'shown', 'capStatus', 'drafted', 'soon'].forEach(function (m) {
      if (S[m] && Object.prototype.hasOwnProperty.call(S[m], old)) { S[m][id] = S[m][old]; delete S[m][old]; }
    });
    if (hiddenBy[hiddenKey(old)]) { delete hiddenBy[hiddenKey(old)]; hiddenBy[hiddenKey(id)] = true; }
    var first = Object.keys(S.renamed).filter(function (k) { return S.renamed[k] === old; })[0] || old;
    S.renamed[first] = id;
    S.layer = id;
    renderEditor();
  }
  // the renames a save carries: saved ids now gone from the draft whose layer is there under its new id
  function renames() {
    var out = {};
    Object.keys(S.renamed).forEach(function (from) {
      var to = S.renamed[from];
      if (from !== to && savedById(from) && !byId(from) && byId(to)) out[from] = to;
    });
    return out;
  }

  /* ---------- save bar, save, discard ---------- */
  function impact() {
    var e = entry() || {}, live = U.liveShowing(S.name), later = U.usersOfOverlay(S.name).filter(function (s) { return !s.publishing; }).map(function (s) { return s.name; });
    if (!live.length && !later.length) return (e.templates || []).length ? 'In ' + e.templates.join(', ') + '; no stream uses it yet.' : 'In no template yet. Saving reaches no viewers.';
    return (live.length ? 'Live now on ' + live.join(', ') + '.' : '') + (later.length ? ' ' + later.join(', ') + ' gets it when it publishes.' : '');
  }
  function renderBar() {
    if (!el.bar || !S.draft || U.current() !== 'overlay') return;
    var bad = objects().filter(function (o) { return o.type === 'webPage' && urlProblem(o.url); })[0];
    var live = U.liveShowing(S.name);
    U.saveBar(el.bar, { changes: changes() ? describe(S.saved, S.draft) : [], impact: impact(), conflict: S.conflict, saving: S.saving,
      problem: bad ? 'Page ' + bad.id + ' has no usable address: ' + urlProblem(bad.url) : null,
      saveLabel: live.length ? 'Save and update ' + live.length + ' live stream' + (live.length > 1 ? 's' : '') : 'Save',
      saved: S.savedNote, onSave: save, onDiscard: discard,
      onTheirs: function () { S.conflict = false; delete mem[S.name]; S.known = S.theirs; S.draft = null; open(S.name).then(function () { U.changed(); }); },
      onMine: function () { S.conflict = false; S.known = S.theirs; renderBar(); U.toast('Keeping your version. Save replaces the one saved in the other session.'); } });
  }

  function save() {
    if (!S.draft || !changes() || S.saving || S.conflict || ro()) return Promise.resolve(!changes());
    var bad = objects().filter(function (o) { return o.type === 'webPage' && urlProblem(o.url); })[0];
    if (bad) { select(bad.id); return Promise.resolve(false); }
    // removing every page takes the overlay off the streams that show it
    var losing = !objects().length && (S.saved.objects || []).length ? U.liveShowing(S.name) : [];
    var ask = !losing.length ? Promise.resolve(true) : U.confirm(S.name + ' disappears from ' + losing.join(', ') + '?', '<ul>' + losing.map(function (n) {
      var r = U.streamRow(n), v = U.viewers(r);
      return '<li><b>' + U.esc(S.name) + ' disappears' + (v != null ? ' for ' + U.viewersText(v) : '') + ' now</b> on ' + U.esc(n) + '.</li>';
    }).join('') + '</ul><p>The draft has no layers left.</p>', 'Save', false);
    return ask.then(function (ok) {
      if (!ok) return false;
      var body = {};
      OVERLAY_KEYS.forEach(function (k) { if (S.draft[k] !== undefined) body[k] = S.draft[k]; });
      body.objects = S.draft.objects;
      var ren = renames();
      if (Object.keys(ren).length) body.renamed = ren;
      S.saving = true; renderBar();
      var name = S.name;
      return U.api.post(ovPath(name), body).then(function (r) {
        S.saving = false;
        delete mem[name];
        if (S.name !== name) return true;
        S.saved = normalize(r); S.draft = clone(S.saved); S.conflict = false; S.justSaved = true; S.undo = []; S.redo = []; S.renamed = {};
        // the save still succeeds; what it warned about shows on the layers' rows and fields until the next save
        S.warnings = r.warnings || [];
        var up = r.updatedStreams || [];
        S.savedNote = { title: 'Saved at ' + U.hhmm() + ': ' + name + '.', detail: (up.length ? 'Updated on ' + up.join(', ') + ' now.' : 'No stream shows it yet.') +
          (r.renamed && Object.keys(r.renamed).length ? ' Renamed ' + Object.keys(r.renamed).map(function (k) { return k + ' to ' + r.renamed[k]; }).join(', ') + '; a live value feed writing to the old id now gets "no layer".' : '') };
        U.clearBanner('ov');
        renderEditor();
        U.refreshState(true);
        return true;
      }, function (e) {
        S.saving = false; renderBar();
        U.banner('ov', 'bad', 'Not saved.', e.message);
        return false;
      });
    });
  }

  function discard() {
    if (!changes()) return;
    var before = JSON.stringify(S.draft);
    reset();
    U.toast('Discarded the unsaved changes to ' + S.name + '.', { undo: function () { restore(before); } });
  }
  // back to the saved overlay, shown at once
  function reset() {
    S.draft = clone(S.saved); S.undo = []; S.redo = []; S.conflict = false; S.savedNote = null; S.adding = false;
    delete mem[S.name];
    if (S.layer && !byId(S.layer)) { var top = sorted()[0]; S.layer = top ? top.id : null; }
    renderEditor(); captureSoonAll();
  }

  /* ---------- delete ---------- */
  function deleteOverlay(name) {
    var o = U.overlayEntry(name) || {}, live = U.liveShowing(name), tpls = o.templates || [];
    if (tpls.length) {
      U.toast('Templates carry ' + name + ': ' + tpls.join(', ') + (live.length ? ', and ' + live.join(', ') + ' show it' : '') + '. Take it off those templates first, so no stream names an overlay that does not exist.', { level: 'bad' });
      return;
    }
    U.confirm('Delete overlay ' + name + '?', '<p>Its layers and placement are deleted. No template carries it, so no stream changes. This cannot be undone.</p>', 'Delete overlay', true).then(function (ok) {
      if (!ok) return;
      var near = U.neighborOf('overlay:' + name);
      U.api.del(ovPath(name)).then(function () {
        U.toast('Deleted overlay ' + name + '.');
        delete mem[name];
        var here = S.name === name;
        if (here) { S.name = null; S.saved = S.draft = null; }
        return U.refreshState(true).then(function () { if (here) U.stayIn('overlays', near); });
      }, function (e) { U.banner('ov', 'bad', 'Not deleted.', e.message); });
    });
  }

  function renderAll() {
    renderHead(); renderStage(); renderLayers(); renderInspector(); renderBar(); loadPreviewChoices(); U.changed();
    // an Add from the overlay row's menu, for an overlay that was not open yet
    if (S.addOnOpen && S.addOnOpen.name === S.name && S.draft) { var k = S.addOnOpen.kind; S.addOnOpen = null; addOnOpen(k); }
  }

  U.screens.overlay = {
    insp: true,
    init: function (box, insp) {
      box.innerHTML = '<div class="pnl"><div class="ed-h"><button type="button" class="btn sm ghost only-narrow" data-showlist aria-expanded="false"></button><h2 data-title></h2><span data-chips class="hrow"></span>' +
        '<span class="spacer"></span><label class="small muted" for="ovs-pv">Background' + U.tip('A publishing stream to place the layers on. It does not put this overlay on that stream.') + '</label><select id="ovs-pv" data-preview></select>' +
        '<label class="check small"><input type="checkbox" data-guides>Safe area</label>' +
        '<button type="button" class="btn sm only-1200" data-togglei>' + U.icon('panel') + 'Inspector</button></div>' +
        '<div class="canvas"><div data-stage></div><div class="canvas-bar"><span class="coords" data-coords aria-live="off"></span><span class="vh" data-say aria-live="polite"></span><span class="spacer"></span><span class="hrow" data-caption></span></div>' +
        '<div class="checkline" data-checkline hidden><span data-check></span><button type="button" class="btn sm ghost" data-checkshow aria-pressed="false" hidden>Show the server\'s drawing</button></div>' +
        '<p class="small warn-text" data-outside hidden></p></div></div><div data-bar hidden></div>';
      // the page list lives in the list column, under the open overlay; the shell places it there
      var pages = document.createElement('div');
      pages.className = 'lpages';
      pages.innerHTML = '<div data-layers></div>' + addMenuHtml('list', 'lsub-add');
      U.overlayPagesNode = pages;
      el = { stage: box.querySelector('[data-stage]'), coords: box.querySelector('[data-coords]'), say: box.querySelector('[data-say]'), preview: box.querySelector('[data-preview]'), layers: pages.querySelector('[data-layers]'),
        pagesAdd: pages.querySelector('[data-rowfor="add:list"]'), caption: box.querySelector('[data-caption]'), outside: box.querySelector('[data-outside]'), title: box.querySelector('[data-title]'), chips: box.querySelector('[data-chips]'),
        checkLine: box.querySelector('[data-checkline]'), check: box.querySelector('[data-check]'), checkShow: box.querySelector('[data-checkshow]'),
        listBtn: box.querySelector('[data-showlist]'), bar: box.querySelector('[data-bar]'), insp: insp, grid: U.root.querySelector('[data-grid]') };
      S.name = null; S.draft = S.saved = null; mem = {};
      el.preview.onchange = function () { setPreview(el.preview.value); };
      box.querySelector('[data-togglei]').onclick = U.toggleInsp;
      el.listBtn.onclick = function () { var on = !el.grid.classList.contains('show-left'); U.showList(on); el.listBtn.setAttribute('aria-expanded', String(on)); };
      box.querySelector('[data-guides]').onchange = function (e) {
        S.guides = e.target.checked;
        var g = el.stage.querySelector('[data-safe]'); if (g) g.hidden = !S.guides;
      };
      U.listen(window, 'resize', function () { if (U.current() === 'overlay') placeBoxes(); });
      U.every(250, tick);
      el.checkShow.onclick = function () { chk.show = !chk.show; paintCheck(); };
      U.every(1000, function () { if (U.current() === 'new-overlay' && N.start === 'preset' && nbox) U.presets.fillSamples(nbox); });
      U.every(5000, function () { if (U.current() === 'overlay' && S.draft) return loadLive(); });
      U.format.onChange(function () { if (U.current() === 'overlay' && S.draft) { ticked = {}; tick(); } });
      el.coords.addEventListener('click', function (e) {
        if (!e.target.closest('[data-freeze]')) return;
        U.format.freeze(!U.format.frozen()); renderCoords();
        var b = el.coords.querySelector('[data-freeze]'); if (b) b.focus();
      });
      U.onShutdown(function () { if (S.player) S.player.stop(); });
    },
    show: function (name) {
      if (!name || !U.overlayEntry(name)) { U.go((U.overlays || []).length ? 'overlay:' + U.overlays[0].name : 'new-overlay:'); return; }
      if (name !== S.name || !S.draft) open(name);
      else { renderAll(); startCaptures(); }
    },
    // the preview player stops while another screen is shown; the draft stays in memory
    hide: function () {
      S.addOnOpen = null;
      if (S.player) { S.player.stop(); S.player = null; }
      if (S.statusTimer) S.statusTimer.stop();
      if (S.timer) S.timer.stop();
      // the preview is chosen again on return, which restarts its status reads
      S.preview = null;
      stash();
    },
    state: function () { if (U.current() === 'overlay') onState(); },
    app: function () { stash(); mem = {}; S.name = null; S.preview = null; S.draft = S.saved = null; S.conflict = false; S.savedNote = null; },
    access: function () { if (U.current() === 'overlay' && S.draft) renderAll(); },
    save: function () { if (S.draft) save(); },
    draftInfo: draftInfo,
    unsaved: function () {
      var out = [];
      if (S.name && S.draft && changes() && !mem[S.name]) out.push({ label: S.name, detail: describe(S.saved, S.draft).join(', '), go: 'overlay:' + S.name });
      Object.keys(mem).forEach(function (n) { var k = mem[n]; if (changesOf(k.saved, k.draft)) out.push({ label: n, detail: describe(k.saved, k.draft).join(', '), go: 'overlay:' + n }); });
      return out;
    },
    key: function (e, typing) {
      if (!S.draft) return;
      var k = e.key, mod = e.ctrlKey || e.metaKey;
      if (typing) return;
      if (mod && !ro() && k.toLowerCase() === 'z') { e.preventDefault(); if (e.shiftKey) redo(); else undo(); return; }
      if (mod && !ro() && k.toLowerCase() === 'y') { e.preventDefault(); redo(); return; }
      if (mod) return;
      if (S.placing && k === 'Escape') { e.preventDefault(); stopPlacing(); return; }
      if (S.placing && k === 'Enter') { e.preventDefault(); place(S.placing, null); return; }
      var a = document.activeElement, frame = frameEl(), onFrame = !!frame && a === frame, onList = !!(a && a.classList && a.classList.contains('prow'));
      // keys act on the canvas when it or nothing has the focus, and Delete and Escape from the layer list too
      if (!(onFrame || onList || !a || a === document.body)) return;
      // Page Up and Page Down choose a layer, read-only too, so each can be heard
      if (onFrame && (k === 'PageUp' || k === 'PageDown')) { e.preventDefault(); stepLayer(k === 'PageUp' ? -1 : 1); return; }
      if (k === 'Escape' && S.layer) { e.preventDefault(); deselect(); return; }
      if (ro()) return;
      if (onFrame && (k === 'Enter' || k === 'F2') && S.layer) {
        e.preventDefault();
        var o = byId(S.layer);
        if (o && o.type === 'string') startEdit(o.id); else focusPanel();
        return;
      }
      if (k === 'Delete' && S.layer) { e.preventDefault(); removePage(S.layer); return; }
      var step = e.shiftKey ? 10 : 1;
      var d = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[k];
      if (!d || onList) return;
      e.preventDefault();
      // after Escape the next arrow chooses the front layer, as Page Up and Page Down do
      if (!S.layer) { if (onFrame) stepLayer(0); return; }
      nudge(d[0], d[1]);
    }
  };

  /* ---------- New overlay: name, a starting point, and a test capture before anything is written ---------- */
  var N = { name: '', start: 'page', url: '', vw: 1280, vh: 720, preset: null, test: null, testTimer: null, testSeq: 0, creating: false };
  var nbox = null;
  // a New overlay draft someone has typed into is asked about before another start replaces it (plan 4.1)
  function newDirty() { return !!(N.name || N.url || N.preset); }
  function replaceNew(then) {
    if (!newDirty()) { then(); return; }
    U.confirm('Replace the new overlay you started?', '<p>New overlay holds ' + U.esc(N.name || 'an unnamed overlay') + ', not created yet. Starting another replaces it.</p>', 'Replace it').then(function (ok) { if (ok) then(); });
  }
  U.newOverlayFrom = function (from) { replaceNew(function () { resetNew(); N.start = 'copy:' + from; N.name = from + '-copy'; U.go('new-overlay:'); }); };
  function suggestName(id) {
    var base = U.presets.baseName(id), n = base, i = 2;
    while ((U.overlays || []).some(function (o) { return o.name === n; })) n = base + '-' + i++;
    return n;
  }
  function nameProblem(n) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(n) || n.indexOf('..') >= 0) return 'Name: letters, digits, dot, dash and underscore.';
    if ((U.overlays || []).some(function (o) { return o.name === n; })) return 'An overlay with this name exists.';
    return null;
  }
  function renderNew() {
    var starts = [['page', 'One page', 'Start from a single web page, tested before it is created'], ['preset', 'A preset', 'A ready layer: a badge, a lower third, a clock, a shape']].concat((U.overlays || []).map(function (o) {
      return ['copy:' + o.name, 'Copy of ' + o.name, (o.pages || 0) + ' layer' + (o.pages === 1 ? '' : 's') + ', same positions and keys'];
    })).concat([['empty', 'Empty', 'Add layers in the editor']]);
    if (!starts.some(function (s) { return s[0] === N.start; })) N.start = 'page';
    nbox.innerHTML = '<div class="pnl formp"><div class="ed-h"><h2>New overlay in ' + U.esc(U.app) + '</h2></div><div class="pnl-b stack-4">' +
      '<div class="grid2"><div class="fld"><label class="f" for="ovs-nn">Name</label><input id="ovs-nn" type="text" data-n="name" value="' + U.esc(N.name) + '" placeholder="for example Scoreboard" autocomplete="off" spellcheck="false">' +
      '<div class="err" data-nerr></div></div><div class="fld"><span class="f">File</span><div class="val mono">' + U.esc(N.name || 'name') + '.json, in ' + U.esc(U.app) + '</div></div></div>' +
      '<div><div class="sec-h">Start from</div><div class="startcards" role="radiogroup" aria-label="Start from">' + starts.map(function (s) {
        return '<button type="button" class="startcard" role="radio" aria-checked="' + (N.start === s[0]) + '" data-start="' + U.esc(s[0]) + '"><b>' + U.esc(s[1]) + '</b><span>' + U.esc(s[2]) + '</span></button>';
      }).join('') + '</div></div>' +
      (N.start === 'page' ? '<div><div class="fld"><label class="f" for="ovs-nu">First page URL</label><input id="ovs-nu" type="url" data-n="url" value="' + U.esc(N.url) + '" placeholder="https://" autocomplete="off" spellcheck="false">' +
        '<div class="hint">' + U.esc(F.url.help) + '</div></div><div class="grid2"><div class="fld"><label class="f" for="ovs-nw">Browser width (px)</label><input id="ovs-nw" type="number" data-n="vw" value="' + N.vw + '"></div>' +
        '<div class="fld"><label class="f" for="ovs-nh">Browser height (px)</label><input id="ovs-nh" type="number" data-n="vh" value="' + N.vh + '"></div></div><div data-test></div></div>' : '') +
      (N.start === 'preset' ? '<div>' + U.presets.listHtml(N.preset) + '<div class="err" data-perr></div></div>' : '') +
      '<p class="small muted">A new overlay reaches no viewer until a template names it. After creating it, pick it in a template, or in "Overlay on this stream" on a stream.</p>' +
      '<div class="hrow"><span class="spacer"></span><button type="button" class="btn" data-ncancel>Cancel</button><button type="button" class="btn primary" data-ncreate' + (N.creating ? ' disabled' : '') + '>Create overlay</button></div></div></div>';
    nbox.querySelectorAll('[data-n]').forEach(function (i) {
      i.addEventListener('input', function () {
        var k = i.getAttribute('data-n');
        N[k] = k === 'vw' || k === 'vh' ? parseInt(i.value, 10) : i.value.trim();
        if (k === 'name') { var f = nbox.querySelector('.val.mono'); if (f) f.textContent = (N.name || 'name') + '.json, in ' + U.app; nbox.querySelector('[data-nerr]').textContent = ''; }
        if (k !== 'name') testSoon();
        U.changed();
      });
    });
    nbox.querySelectorAll('[data-start]').forEach(function (b) { b.onclick = function () { N.start = b.getAttribute('data-start'); renderNew(); if (N.start === 'page') testSoon(); }; });
    nbox.querySelectorAll('[data-preset]').forEach(function (b) {
      b.onclick = function () {
        var id = b.getAttribute('data-preset'), was = N.preset && suggestName(N.preset);
        N.preset = id;
        // a name suggested from the last preset follows the new one; a typed name stays
        if (!N.name || N.name === was) N.name = suggestName(id);
        renderNew();
        var x = nbox.querySelector('[data-preset="' + CSS.escape(id) + '"]'); if (x) x.focus();
        U.changed();
      };
    });
    U.presets.fillSamples(nbox);
    nbox.querySelector('[data-ncancel]').onclick = function () { resetNew(); U.changed(); U.go((U.overlays || []).length ? 'overlay:' + U.overlays[0].name : 'home:'); };
    nbox.querySelector('[data-ncreate]').onclick = createOverlay;
    renderTest();
  }
  function resetNew() { N.name = ''; N.start = 'page'; N.url = ''; N.vw = 1280; N.vh = 720; N.preset = null; N.test = null; if (N.testTimer) { N.testTimer.stop(); N.testTimer = null; } }
  function renderTest() {
    var box = nbox && nbox.querySelector('[data-test]');
    if (!box) return;
    var t = N.test, why = N.url ? urlProblem(N.url) : null;
    if (!N.url) { box.innerHTML = ''; return; }
    if (why) { box.innerHTML = '<p class="err">' + U.esc(why) + '</p>'; return; }
    var c = (t && t.capture) || {}, bad = c.state === 'blocked' || c.state === 'error' || c.state === 'service not running' || (t && t.error);
    box.innerHTML = '<div class="note ' + (bad ? 'bad' : t && t.png ? 'on' : '') + '"><span class="dot ' + (bad ? 'bad' : t && t.png ? 'on' : '') + '"></span><div class="testcap"><div class="cap keyed">' +
      (t && t.png ? '<img alt="Test capture" src="data:image/png;base64,' + t.png + '">' : '') + '</div><div>' +
      (t && t.error ? '<b class="err">Test capture failed.</b> ' + U.esc(t.error) : bad ? '<b class="err">Test capture: ' + U.esc(c.state) + '.</b> ' + U.esc(c.lastError || '') +
        (c.state === 'blocked' ? ' A WSE administrator can allow the host with <span class="mono">OverlayStudioAllowHosts</span>.' : '') :
        t && t.png ? '<b>Test capture passed.</b> ' + U.esc(host(N.url)) + ' answered' + (c.lastCaptureMs != null ? ' in ' + c.lastCaptureMs + ' ms' : '') + '. Nothing reaches viewers until a template names the overlay.' :
        'Test capture: opening ' + U.esc(host(N.url)) + ' on the server...') + '</div></div></div>';
  }
  // the preview call only checks the overlay's name, so a page is captured before the overlay exists
  function testSoon() {
    if (N.testTimer) { N.testTimer.stop(); N.testTimer = null; }
    N.test = null; renderTest();
    if (N.start !== 'page' || !N.url || urlProblem(N.url) || !(N.vw >= 16 && N.vh >= 16) || U.readOnly) return;
    var seq = ++N.testSeq, tries = 0, page = U.newPage('page1', N.url, N.vw, N.vh);
    var name = nameProblem(N.name) ? 'new-overlay' : N.name;
    setTimeout(function () {
      if (seq !== N.testSeq || !U.alive()) return;
      N.testTimer = U.every(1500, function () {
        if (seq !== N.testSeq || U.current() !== 'new-overlay' || ++tries > 20 || (N.test && N.test.png)) { if (N.testTimer) N.testTimer.stop(); return null; }
        return U.api.post(ovPath(name) + '/preview', { page: page, width: 320, raw: false, refreshRate: 1 }).then(function (c) {
          if (seq !== N.testSeq) return;
          N.test = c; renderTest();
        }, function (e) { if (seq !== N.testSeq) return; N.test = { error: e.message }; renderTest(); });
      });
    }, 500);
  }
  function createOverlay() {
    // a preset suggests the name, so it is asked for first
    if (N.start === 'preset' && !N.preset) { nbox.querySelector('[data-perr]').textContent = 'Choose a preset, or start Empty.'; return; }
    var why = nameProblem(N.name), err = nbox.querySelector('[data-nerr]');
    if (why) { err.textContent = why; nbox.querySelector('#ovs-nn').focus(); return; }
    if (N.start === 'page' && (urlProblem(N.url) || !(N.vw >= 16 && N.vh >= 16))) { renderTest(); var u = nbox.querySelector('#ovs-nu'); if (u) u.focus(); if (!N.url) nbox.querySelector('[data-test]').innerHTML = '<p class="err">Enter the first page\'s address, or start Empty.</p>'; return; }
    var name = N.name, body;
    var base = { refreshRate: F.refreshRate.def, threadDrawing: true, maxDrawingTime: F.maxDrawingTime.def, objects: [] };
    N.creating = true; renderNew();
    var get = N.start.indexOf('copy:') === 0 ? U.api.get(ovPath(N.start.slice(5))) : Promise.resolve(null);
    get.then(function (from) {
      body = from ? normalize(from) : base;
      if (N.start === 'page') body.objects = [U.newPage('page1', N.url, N.vw, N.vh)];
      // New overlay has no background stream: presets land on 1920 x 1080 (plan 4.3)
      if (N.start === 'preset') { var pl = U.presets.layer(N.preset, { w: 1920, h: 1080 }, function () { return false; }); pl.zOrder = 1; body.objects = [pl]; }
      return U.api.post(ovPath(name), body);
    }).then(function () {
      N.creating = false;
      U.toast('Created overlay ' + name + ' in ' + U.app + '. It is in no template yet.');
      resetNew();
      return U.refreshState(true).then(function () { U.go('overlay:' + name); });
    }, function (e) { N.creating = false; renderNew(); U.banner('ov', 'bad', 'Could not create the overlay.', e.message); });
  }
  U.screens['new-overlay'] = {
    init: function (box) { nbox = box; },
    show: function () { if (U.readOnly) { U.go('home:'); return; } U.format.start(); renderNew(); if (N.start === 'page' && N.url) testSoon(); var n = nbox.querySelector('#ovs-nn'); if (n) n.focus(); },
    hide: function () { if (N.testTimer) { N.testTimer.stop(); N.testTimer = null; } },
    app: function () { resetNew(); },
    save: function () { createOverlay(); },
    unsaved: function () { return N.name || N.url ? [{ label: 'New overlay' + (N.name ? ' ' + N.name : ''), go: 'new-overlay:' }] : []; }
  };
  // an image or a font that has just arrived changes what text, shapes and images look like and how big they are
  // Each box is drawn again where it is, so a grip being dragged or about to be is not swapped for a new one
  U.draw.onLoad(function () {
    if (U.current() !== 'overlay' || !S.draft) return;
    var frame = el.stage && el.stage.querySelector('[data-frame]'), s = src();
    if (frame) objects().forEach(function (o) {
      var b = frame.querySelector('.pg[data-id="' + CSS.escape(o.id) + '"]');
      if (b) { position(b, o, s); b.setAttribute('aria-label', boxLabel(o)); }
    });
    ticked = {}; renderCoords(); renderLayers(); showProblems();
    // a free text's Y is its top, measured in its font: the field follows once the font is here, unless it is being typed in
    var yi = el.insp && el.insp.querySelector('#ovs-f-y'), sel = S.layer && byId(S.layer);
    if (yi && sel && document.activeElement !== yi) yi.value = shownY(sel);
    // the Place tab's size follows a picture that has just arrived, unless someone is typing in the panel
    if (!el.insp.contains(document.activeElement)) renderInspector();
  });
})();
