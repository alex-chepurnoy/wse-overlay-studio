/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio overlay editor: a named overlay's pages placed over a live preview of a stream (the canvas), the page
   list under the overlay in the list column, and the inspector for the selected page. Edits stay in a draft until Save; a draft is kept in memory per
   overlay while the page is open, so leaving for another screen and coming back loses nothing. Placement is in
   source pixels: screen pixels / (frame width / source width), clamped to the frame.
   Also the New overlay screen, with a test capture before anything is written. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var F = U.fields;
  var OVERLAY_KEYS = ['refreshRate', 'threadDrawing', 'maxDrawingTime', 'even_coords', 'opacity', 'overlayIndex'];
  // the page settings a capture depends on; a page whose draft differs here is captured from the draft
  var CAPTURE_KEYS = ['url', 'viewportWidth', 'viewportHeight', 'keyColor', 'keyTolerance', 'paintBackground', 'pageDelay', 'cropX', 'cropY', 'cropW', 'cropH'];
  var el = {};
  // drafts of overlays not in view, by name: { saved, draft, undo, redo, known, layer }
  var mem = {};
  var S = { name: null, saved: null, draft: null, layer: null, preview: null, previewInfo: null, status: null,
    captures: {}, raw: {}, rawAt: {}, busy: {}, thumbAt: {}, seq: 0, shown: {}, capStatus: {}, drafted: {}, saving: false, player: null, timer: null, statusTimer: null,
    undo: [], redo: [], lastSnap: null, known: null, conflict: false, theirs: null, justSaved: false, soon: {}, guides: false, inspTab: null, adding: false, savedNote: null };

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
  function size(o) { return { w: o.w > 0 ? o.w : (o.viewportWidth || 640), h: o.h > 0 ? o.h : (o.viewportHeight || 360) }; }
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
      if (!s) { out.push('add page ' + o.id); return; }
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
    so.forEach(function (s) { if (!((draft && draft.objects) || []).some(function (o) { return o.id === s.id; })) out.push('remove page ' + s.id); });
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
    S.conflict = false; S.theirs = null; S.adding = false; S.savedNote = null;
    var e = entry(), kept = mem[name];
    if (kept) {
      S.saved = kept.saved; S.draft = kept.draft; S.undo = kept.undo; S.redo = kept.redo; S.known = kept.known; S.layer = kept.layer;
      // the file changed while the draft waited: the draft stays, and Save is held until the producer picks
      if (e && kept.known != null && e.modified !== kept.known) { S.conflict = true; S.theirs = e.modified; }
      renderAll(); startCaptures();
      return Promise.resolve();
    }
    S.undo = []; S.redo = []; S.layer = null; S.saved = S.draft = null;
    S.known = e ? e.modified : null;
    el.layers.innerHTML = '<div class="skel" style="margin:16px"></div>';
    el.insp.innerHTML = '<section><div class="skel"></div><div class="skel"></div></section>';
    return U.api.get(ovPath(name)).then(function (o) {
      if (S.name !== name) return;
      S.saved = normalize(o); S.draft = clone(S.saved);
      var top = sorted()[0]; S.layer = top ? top.id : null;
      renderAll();
      startCaptures();
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
          placeBoxes();
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
    el.stage.innerHTML = '<div class="frame" data-frame aria-label="Canvas: pages over a background stream"><video muted playsinline></video><div class="waiting" data-wait></div>' +
      '<div class="safe" data-safe' + (S.guides ? '' : ' hidden') + '></div><span class="lbl top" data-airlbl hidden></span></div>';
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
      wait.textContent = S.preview ? 'Connecting...' : 'No stream is publishing in ' + (U.app || 'this application') + '. Pages can still be placed on a frame of the assumed size.';
    }
    placeBoxes(); renderCaption();
  }
  // the stream behind the pages is only a background: whether viewers see this overlay on it is the template's doing
  function onAirHere() { var r = S.preview && U.streamRow(S.preview); return !!(r && r.publishing && r.overlay === S.name); }
  function renderCaption() {
    var dirty = !ro() && changes() > 0, air = onAirHere();
    el.caption.innerHTML = !S.preview ? '<span class="bdg">Blank frame</span>' :
      (air ? '<span class="bdg on">On air on ' + U.esc(S.preview) + '</span>' : '<span class="bdg warn">Background only</span>' +
        U.tip(S.preview + ' does not show ' + S.name + '. It is here to place the pages on. Viewers see an overlay only when the template a stream runs names it.')) +
      (dirty ? '<span class="chip-draft">Unsaved</span>' + U.tip('Shown here only. Nothing is written until Save.') : '');
    var lbl = el.stage.querySelector('[data-airlbl]');
    if (lbl) { lbl.hidden = !S.preview || air; lbl.textContent = 'Background: ' + (S.preview || '') + '. Not on air with ' + S.name; }
  }

  function placeBoxes() {
    var frame = el.stage.querySelector('[data-frame]');
    if (!frame) return;
    var a = document.activeElement, focused = a && frame.contains(a) && a.getAttribute ? a.getAttribute('data-id') : null;
    var s = src();
    frame.style.aspectRatio = s.w + ' / ' + s.h; frame.style.setProperty('--ar', String(s.w / s.h));
    frame.querySelectorAll('.pg').forEach(function (b) { b.remove(); });
    objects().slice().sort(function (x, y) { return (x.zOrder || 0) - (y.zOrder || 0); }).forEach(function (o) {
      var b = document.createElement('div'), draft = isDraftPage(o), sel = o.id === S.layer && !ro();
      b.className = 'pg' + (sel ? ' sel' : '') + (draft || (!ro() && edited(o.id)) ? ' draft' : '');
      b.setAttribute('data-id', o.id);
      if (!ro()) { b.tabIndex = 0; b.setAttribute('role', 'button'); }
      b.setAttribute('aria-label', o.id + ', X ' + (o.x || 0) + ', Y ' + (o.y || 0) + (ro() ? '' : '. Arrow keys move 1 px, Shift 10 px.'));
      b.innerHTML = '<img alt="" hidden><span class="pg-name">' + U.esc(o.id) + (draft || edited(o.id) ? ', unsaved' : '') + '</span>' +
        (sel ? '<i class="h nw" data-g="nw"></i><i class="h ne" data-g="ne"></i><i class="h sw" data-g="sw"></i><i class="h se" data-g="se"></i>' : '');
      if (S.captures[o.id]) { b.querySelector('img').src = S.captures[o.id]; b.querySelector('img').hidden = false; }
      if (o.opacity != null && o.opacity >= 0) b.querySelector('img').style.opacity = String(o.opacity / 100);
      frame.appendChild(b);
      position(b, o, s);
      b.addEventListener('pointerdown', function (e) { startDrag(e, o.id, e.target.getAttribute('data-g')); });
      b.addEventListener('focus', function () { if (S.layer !== o.id && !ro()) select(o.id, true); });
      if (focused === o.id) b.focus({ preventScroll: true });
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
    el.coords.innerHTML = '<span class="bdg" title="Source picture">' + s.w + ' x ' + s.h + (s.known ? '' : ', assumed') + '</span>' +
      (o ? '<span class="bdg" title="' + U.esc(o.id) + ': position and size on the source">' + U.esc(o.id) + ' at ' + (o.x || 0) + ', ' + (o.y || 0) + ' &middot; ' + z.w + ' x ' + z.h + '</span>' : '');
    var outside = o && s.known && ((o.x || 0) >= s.w || (o.y || 0) >= s.h);
    el.outside.hidden = !outside;
    if (outside) el.outside.textContent = 'Page ' + o.id + ' starts at ' + (o.x || 0) + ', ' + (o.y || 0) + ', outside ' + S.preview + ' (' + s.w + ' x ' + s.h + '). Move it under Place.';
  }

  function select(id, keepFocus) {
    S.layer = id; S.adding = false;
    renderLayers(); renderInspector();
    el.stage.querySelectorAll('.pg').forEach(function (b) { b.classList.toggle('sel', b.getAttribute('data-id') === id); });
    if (!keepFocus) placeBoxes(); else { placeBoxes(); var b = el.stage.querySelector('.pg[data-id="' + CSS.escape(id) + '"]'); if (b) b.focus({ preventScroll: true }); }
  }

  function startDrag(e, id, grip) {
    if (e.button !== 0 || ro()) return;
    e.preventDefault();
    // a press on another page selects it and drags it at once; its corner grips appear on release
    var fresh = S.layer !== id;
    if (fresh) {
      S.layer = id; S.adding = false; grip = null; renderLayers(); renderInspector();
      el.stage.querySelectorAll('.pg').forEach(function (b) { b.classList.toggle('sel', b.getAttribute('data-id') === id); });
    }
    var frame = el.stage.querySelector('[data-frame]');
    var box = frame.querySelector('.pg[data-id="' + CSS.escape(id) + '"]');
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
      position(box, o, s); afterMove();
    }
    function up() {
      box.removeEventListener('pointermove', move); box.removeEventListener('pointerup', up); box.removeEventListener('pointercancel', up);
      if (moved) { renderLayers(); renderCaption(); U.changed(); }
      if (fresh || moved) placeBoxes();
      var b = el.stage.querySelector('.pg[data-id="' + CSS.escape(id) + '"]');
      if (b) b.focus({ preventScroll: true });
    }
    box.addEventListener('pointermove', move); box.addEventListener('pointerup', up); box.addEventListener('pointercancel', up);
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
    var s = src(), z = size(o);
    o.x = Math.max(0, Math.min((o.x || 0) + dx, s.w - z.w));
    o.y = Math.max(0, Math.min((o.y || 0) + dy, s.h - z.h));
    var box = el.stage.querySelector('.pg[data-id="' + CSS.escape(o.id) + '"]');
    if (box) { position(box, o, s); box.classList.add('draft'); }
    renderCoords(); syncPlacement(); renderBar(); markEdited(); renderCaption(); U.changed();
  }

  /* ---------- pages ---------- */
  function renderLayers() {
    var list = sorted();
    el.pagesAdd.hidden = ro() || !S.draft;
    if (!list.length) {
      el.layers.innerHTML = '<p class="small faint lpages-empty">No pages yet.</p>';
      return;
    }
    el.layers.innerHTML = list.map(function (o, i) {
      return '<div class="prow" data-id="' + U.esc(o.id) + '"' + (ro() ? '' : ' draggable="true"') + ' tabindex="0" role="button" aria-selected="' + (o.id === S.layer) + '" title="' + (ro() ? '' : 'Drag to reorder; the top page draws on top') + '">' +
        '<div class="thumb"><img alt="" hidden></div><div style="min-width:0"><div class="nm"><span class="dot ' + pageDot(o.id) + '" data-pdot></span>' + U.esc(o.id) +
        (!ro() && edited(o.id) && savedById(o.id) ? '<span class="chip-draft" data-edited>Unsaved</span>' : '') + pageTag(o.id) + '</div>' +
        '<div class="sub">' + U.esc(o.type === 'webPage' ? host(o.url) : o.type) + '</div></div>' +
        U.rowMenuHtml('page:' + o.id, 'Actions for page ' + o.id, [['up', 'Move up', false, !i], ['down', 'Move down', false, i === list.length - 1], ['reload', 'Reload page', false, o.type !== 'webPage' || !savedById(o.id)], '-', ['rm', 'Remove page', true]]) + '</div>';
    }).join('');
    var dragId = null;
    el.layers.querySelectorAll('.prow').forEach(function (l) {
      var id = l.getAttribute('data-id');
      l.onclick = function (e) { if (e.target.closest('[data-rowfor]')) return; select(id); };
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
    if (!byId(id) || ro()) return;
    if (act === 'up') movePage(id, -1);
    else if (act === 'down') movePage(id, 1);
    else if (act === 'rm') removePage(id);
    else if (act === 'reload') reloadPage(id);
  };
  U.rowActions.overlay = function (name, act) {
    if (act === 'dup') U.newOverlayFrom(name);
    else if (act === 'addpage') {
      if (U.current() === 'overlay' && S.name === name && S.draft) { startAdd(); return; }
      // the flag is set only once this overlay is the one opening, so a navigation that did not happen leaves none
      U.go('overlay:' + name);
      if (U.current() === 'overlay' && S.name === name) { if (S.draft) startAdd(); else S.addOnOpen = name; }
    } else if (act === 'del') deleteOverlay(name);
  };
  // order: page ids, top first
  function reorder(order) {
    snapshot();
    order.forEach(function (x, i) { byId(x).zOrder = order.length - i; });
    renderLayers(); placeBoxes(); renderBar(); renderCaption(); U.changed();
  }
  // the edited marks follow the draft without rebuilding the list
  function markEdited() {
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
    U.toast('Removed page ' + id + ' from the draft. Viewers keep it until Save.', { undo: function () { S.redo = []; restore(before); S.layer = id; renderLayers(); renderInspector(); placeBoxes(); } });
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
    if (ro()) {
      var shown = f.type === 'bool' ? (value ? 'Yes' : 'No') : v === '' ? (opts.placeholder ? opts.placeholder + ', from the size on the picture' : 'Not set') : v;
      return '<div class="fld"><span class="f">' + label + '</span><div class="val">' + U.esc(shown) + '</div></div>';
    }
    var savedV = opts.saved, draftCls = savedV !== undefined && JSON.stringify(savedV) !== JSON.stringify(value) ? ' is-draft' : '';
    var savedLine = draftCls ? '<div class="hint">Saved: ' + U.esc(savedV === undefined || savedV === null || savedV === '' ? 'not set' : f.type === 'bool' ? (savedV ? 'on' : 'off') : savedV) + '</div>' : '';
    if (f.type === 'bool') {
      return '<div class="fld"><label class="check"' + title + '><input type="checkbox" id="' + id + '" data-f="' + name + '"' + (value ? ' checked' : '') + '>' + label + '</label>' +
        (f.help ? U.tip(f.help + ' WSE setting ' + f.key + '.') : '') + savedLine + '</div>';
    }
    var type = f.type === 'int' || f.type === 'num' ? 'number' : 'text';
    var attrs = (f.min !== undefined ? ' min="' + f.min + '"' : '') + (f.max !== undefined ? ' max="' + f.max + '"' : '') + (f.type === 'num' ? ' step="any"' : '');
    var sw = f.type === 'color' ? '<span class="swatch" data-swatch style="background:' + U.esc(v) + '"></span>' : '';
    return '<div class="fld"><label class="f" for="' + id + '">' + label + sw + (f.help && !opts.noHelp ? U.tip(f.help + ' WSE setting ' + f.key + '.') : '') + '</label><input type="' + type + '" id="' + id + '" data-f="' + name + '" value="' + U.esc(v) + '"' + attrs + title +
      (opts.placeholder ? ' placeholder="' + U.esc(opts.placeholder) + '"' : '') + ' class="' + (type === 'number' ? 'num' : '') + draftCls + '"><div class="err" data-err-for="' + name + '"></div>' +
      savedLine + '</div>';
  }

  function renderInspector() {
    if (U.current() !== 'overlay') return;
    var o = S.layer && byId(S.layer), d = S.draft, sv = o ? savedById(o.id) || {} : {};
    if (!d) { el.insp.innerHTML = ''; return; }
    if (S.adding) { renderAddPage(); return; }
    var h = '';
    if (!o) {
      h = '<div class="pnl-h"><div><h3>No page selected</h3><span class="small faint">' + U.esc(S.name) + '</span></div></div>' +
        '<section><p class="small muted">Select a page in the list or on the canvas' + (ro() ? '.' : ', or add the page with the clock and score.') + '</p>' +
        (ro() ? '' : '<p style="margin-top:12px"><button type="button" class="btn sm" data-addpage>' + U.icon('plus') + 'Add page</button></p>') + '</section>';
    } else {
      var web = o.type === 'webPage', z = size(o), s = src(), past = (o.x || 0) + z.w > s.w * 0.95 || (o.y || 0) + z.h > s.h * 0.95;
      h = '<div class="pnl-h"><div><h3>' + U.esc(o.id) + '</h3>' + (web ? '<span class="capchip ' + pageDot(o.id) + '" data-capline title="' + U.esc(capLine(o.id).replace(/<[^>]+>/g, '')) + '"><span class="dot ' + pageDot(o.id) + '"></span>' + U.esc(capShort(o.id)) + '</span>' : '') +
        '</div><span class="spacer"></span>' + (web && savedById(o.id) && !ro() ? '<button type="button" class="btn sm ghost" data-reloadpage title="Load ' + U.esc(host(o.url)) + ' again in the browser on the server, for a capture that shows the wrong thing">' + U.icon('refresh') + 'Reload page</button>' : '') +
        '<button type="button" class="btn sm icon ghost only-1200" data-closeinsp aria-label="Close the inspector">' + U.icon('close') + '</button></div>';
      var tabs = web ? [['page', 'Page'], ['place', 'Place'], ['look', 'Key, crop'], ['time', 'Timing']] : [['place', 'Place']];
      if (!tabs.some(function (t) { return t[0] === S.inspTab; })) S.inspTab = tabs[0][0];
      h += '<div class="itabs" role="tablist" aria-label="Page settings">' + tabs.map(function (t) {
        return '<button type="button" role="tab" data-itab="' + t[0] + '" aria-selected="' + (S.inspTab === t[0]) + '" tabindex="' + (S.inspTab === t[0] ? 0 : -1) + '">' + t[1] + '</button>'; }).join('') + '</div>';
      var body = '';
      if (S.inspTab === 'page') body = fieldHtml('url', o.url, { saved: sv.url }) +
        '<div class="grid2">' + fieldHtml('viewportWidth', o.viewportWidth, { placeholder: String(z.w), saved: sv.viewportWidth }) + fieldHtml('viewportHeight', o.viewportHeight, { placeholder: String(z.h), noHelp: true, saved: sv.viewportHeight }) + '</div>' +
        overlaySettings(d) + U.roNote();
      else if (S.inspTab === 'place') body = '<div class="grid2">' +
        fieldHtml('x', o.x || 0, { noHelp: true, saved: sv.x || 0 }) + fieldHtml('y', o.y || 0, { noHelp: true, saved: sv.y || 0 }) +
        fieldHtml('w', z.w, { noHelp: true, saved: sv.id ? size(sv).w : undefined }) + fieldHtml('h', z.h, { noHelp: true, saved: sv.id ? size(sv).h : undefined }) + '</div>' +
        fieldHtml('opacity', o.opacity == null || o.opacity < 0 ? 100 : o.opacity, { noHelp: true }) +
        '<div class="hrow chips">' + (past ? '<span class="bdg warn">Past title safe</span>' : '<span class="bdg">Inside title safe</span>') +
        '<span class="bdg">Source ' + s.w + ' x ' + s.h + '</span>' + (ro() ? '' : U.tip('Drag the page on the canvas; its corners resize it. Arrow keys move 1 px, Shift 10 px. Positions are in source pixels.')) + '</div>' + (ro() ? U.roNote() : '');
      else if (S.inspTab === 'look') body = keyHtml(o, sv);
      else if (S.inspTab === 'time') body = '<div class="grid2">' + fieldHtml('pageDelay', o.pageDelay, { saved: sv.pageDelay }) + fieldHtml('reloadRate', o.reloadRate, { saved: sv.reloadRate }) + '</div>';
      if (web && capNote(o.id) === 'bad') h += '<section><div class="note bad" data-capnote><span class="dot bad"></span><div>' + capLine(o.id) + '</div></div></section>';
      if (body) h += '<section>' + body + '</section>';
    }
    if (!o || !web) h += '<section>' + (o ? '' : '<div class="sec-h">Every page of ' + U.esc(S.name) + '</div>') + overlaySettings(d) + U.roNote() + '</section>';
    el.insp.innerHTML = h;
    el.insp.querySelectorAll('details[data-ovadv]').forEach(function (x) { x.addEventListener('toggle', function () { S.advOpen = x.open; }); });
    // a named speed fills in the exact value beside it, so the draft, Save and Revert treat it as that field
    U.bindSpeed(el.insp, 'data-ospeed', function (v) { var i = el.insp.querySelector('[data-f=refreshRate]'); if (i) { i.value = v; i.dispatchEvent(new Event('input')); } });
    el.insp.querySelectorAll('[data-itab]').forEach(function (b) {
      b.onclick = function () { S.inspTab = b.getAttribute('data-itab'); renderInspector(); var t = el.insp.querySelector('[data-itab="' + S.inspTab + '"]'); if (t) t.focus(); if (S.inspTab === 'look' && o) captureSoon(o.id); };
      b.onkeydown = function (e) {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
        var all = Array.prototype.slice.call(el.insp.querySelectorAll('[data-itab]')), k = all.indexOf(b) + (e.key === 'ArrowRight' ? 1 : -1);
        all[(k + all.length) % all.length].click();
      };
    });
    el.insp.querySelectorAll('[data-f]').forEach(function (i) { i.addEventListener('input', onField); i.addEventListener('change', onField); });
    var add = el.insp.querySelector('[data-addpage]');
    if (add) add.onclick = startAdd;
    var rp = el.insp.querySelector('[data-reloadpage]');
    if (rp) rp.onclick = function () { reloadPage(o.id); };
    var ci = el.insp.querySelector('[data-closeinsp]');
    if (ci) ci.onclick = U.toggleInsp;
    var raw = el.insp.querySelector('[data-raw]');
    if (raw && !ro()) raw.addEventListener('click', pickColor);
    if (o) bindCropper(o);
  }

  // the overlay's own settings, for every page in it: how often pages are captured, and how they are drawn
  function overlaySettings(d) {
    var sd = S.saved || {}, rr = d.refreshRate, draft = sd.refreshRate !== undefined && sd.refreshRate !== rr;
    var every = ro() ? '<span class="every">every ' + U.esc(rr) + ' s</span>' :
      '<label class="every" title="WSE setting refreshRate">every<input type="number" id="ovs-f-refreshRate" data-f="refreshRate" class="num' + (draft ? ' is-draft' : '') + '" min="' + F.refreshRate.min + '" max="' + F.refreshRate.max + '" step="any" value="' + U.esc(rr) + '" aria-label="Capture every, seconds">s</label>';
    return '<div class="ovset">' + U.speedHtml(+rr, 'data-ospeed', ro(), every) + '<div class="err" data-err-for="refreshRate"></div>' +
      (draft ? '<div class="hint">Saved: every ' + U.esc(sd.refreshRate) + ' s</div>' : '') +
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
    if (name === 'url') { var p = urlProblem(v); e.target.classList.toggle('invalid', !!p); if (err) err.textContent = p || ''; }
    if (name === 'keyColor') { var sw = el.insp.querySelector('[data-swatch]'); if (sw) sw.style.background = v; }
    snapshot('field:' + name);
    if (f.scope === 'overlay') { S.draft[f.key] = v; if (name === 'evenCoords') renderCoords(); if (name === 'refreshRate') U.syncSpeed(el.insp, 'data-ospeed', v); }
    else {
      if (v === null) delete o[f.key]; else o[f.key] = v;
      if (['x', 'y', 'w', 'h', 'opacity'].indexOf(name) >= 0) placeBoxes();
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
    markEdited(); renderBar(); renderCaption(); U.changed();
  }
  function syncPlacement() {
    var o = byId(S.layer);
    ['x', 'y', 'w', 'h'].forEach(function (k) { var i = el.insp.querySelector('[data-f=' + k + ']'); if (i) { i.value = o[k]; i.classList.add('is-draft'); } });
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
      var why = !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(id) ? 'Name: letters, digits, dot, dash and underscore.' : used.indexOf(id) >= 0 ? 'A page with this name exists.' :
        urlProblem(url) || (!(vw >= 16 && vh >= 16) ? 'Enter the page size in pixels.' : null);
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
    }).join('') + '</ul><p>The draft has no pages left.</p>', 'Save', false);
    return ask.then(function (ok) {
      if (!ok) return false;
      var body = {};
      OVERLAY_KEYS.forEach(function (k) { if (S.draft[k] !== undefined) body[k] = S.draft[k]; });
      body.objects = S.draft.objects;
      S.saving = true; renderBar();
      var name = S.name;
      return U.api.post(ovPath(name), body).then(function (r) {
        S.saving = false;
        delete mem[name];
        if (S.name !== name) return true;
        S.saved = normalize(r); S.draft = clone(S.saved); S.conflict = false; S.justSaved = true; S.undo = []; S.redo = [];
        var up = r.updatedStreams || [];
        S.savedNote = { title: 'Saved at ' + U.hhmm() + ': ' + name + '.', detail: up.length ? 'Updated on ' + up.join(', ') + ' now.' : 'No stream shows it yet.' };
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
    U.confirm('Delete overlay ' + name + '?', '<p>Its pages and placement are deleted. No template carries it, so no stream changes. This cannot be undone.</p>', 'Delete overlay', true).then(function (ok) {
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
    // Add page from the list's menu, for an overlay that was not open yet
    if (S.addOnOpen && S.addOnOpen === S.name && S.draft) { S.addOnOpen = null; startAdd(); }
  }

  U.screens.overlay = {
    insp: true,
    init: function (box, insp) {
      box.innerHTML = '<div class="pnl"><div class="ed-h"><button type="button" class="btn sm ghost only-narrow" data-showlist aria-expanded="false"></button><h2 data-title></h2><span data-chips class="hrow"></span>' +
        '<span class="spacer"></span><label class="small muted" for="ovs-pv">Background' + U.tip('A publishing stream to place the pages on. It does not put this overlay on that stream.') + '</label><select id="ovs-pv" data-preview></select>' +
        '<label class="check small"><input type="checkbox" data-guides>Safe area</label>' +
        '<button type="button" class="btn sm only-1200" data-togglei>' + U.icon('panel') + 'Inspector</button></div>' +
        '<div class="canvas"><div data-stage></div><div class="canvas-bar"><span class="coords" data-coords></span><span class="spacer"></span><span class="hrow" data-caption></span></div>' +
        '<p class="small warn-text" data-outside hidden></p></div></div><div data-bar hidden></div>';
      // the page list lives in the list column, under the open overlay; the shell places it there
      var pages = document.createElement('div');
      pages.className = 'lpages';
      pages.innerHTML = '<div data-layers></div><button type="button" class="lsub-add" data-write data-addpage>' + U.icon('plus') + 'Add page</button>';
      U.overlayPagesNode = pages;
      el = { stage: box.querySelector('[data-stage]'), coords: box.querySelector('[data-coords]'), preview: box.querySelector('[data-preview]'), layers: pages.querySelector('[data-layers]'),
        pagesAdd: pages.querySelector('[data-addpage]'), caption: box.querySelector('[data-caption]'), outside: box.querySelector('[data-outside]'), title: box.querySelector('[data-title]'), chips: box.querySelector('[data-chips]'),
        listBtn: box.querySelector('[data-showlist]'), bar: box.querySelector('[data-bar]'), insp: insp, grid: U.root.querySelector('[data-grid]') };
      S.name = null; S.draft = S.saved = null; mem = {};
      el.preview.onchange = function () { setPreview(el.preview.value); };
      el.pagesAdd.onclick = startAdd;
      box.querySelector('[data-togglei]').onclick = U.toggleInsp;
      el.listBtn.onclick = function () { var on = !el.grid.classList.contains('show-left'); U.showList(on); el.listBtn.setAttribute('aria-expanded', String(on)); };
      box.querySelector('[data-guides]').onchange = function (e) {
        S.guides = e.target.checked;
        var g = el.stage.querySelector('[data-safe]'); if (g) g.hidden = !S.guides;
      };
      U.listen(window, 'resize', function () { if (U.current() === 'overlay') placeBoxes(); });
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
      if (!S.draft || ro()) return;
      var k = e.key, mod = e.ctrlKey || e.metaKey;
      if (typing) return;
      if (mod && k.toLowerCase() === 'z') { e.preventDefault(); if (e.shiftKey) redo(); else undo(); return; }
      if (mod && k.toLowerCase() === 'y') { e.preventDefault(); redo(); return; }
      if (mod) return;
      var a = document.activeElement;
      // keys act on the canvas when a page or nothing has the focus, never while a button or the list has it
      var onCanvas = !a || a === document.body || el.stage.contains(a) || (a.classList && a.classList.contains('prow'));
      if (!onCanvas) return;
      if (a && a.classList && a.classList.contains('pg') && (k === 'Enter' || k === ' ')) { e.preventDefault(); select(a.getAttribute('data-id'), true); return; }
      if (k === 'Delete' && S.layer) { e.preventDefault(); removePage(S.layer); return; }
      if (k === 'Escape' && S.layer) { S.layer = null; renderLayers(); renderInspector(); placeBoxes(); return; }
      var step = e.shiftKey ? 10 : 1;
      var d = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[k];
      if (d && S.layer && !(a && a.classList && a.classList.contains('prow'))) { e.preventDefault(); nudge(d[0], d[1]); }
    }
  };

  /* ---------- New overlay: name, a starting point, and a test capture before anything is written ---------- */
  var N = { name: '', start: 'page', url: '', vw: 1280, vh: 720, test: null, testTimer: null, testSeq: 0, creating: false };
  var nbox = null;
  U.newOverlayFrom = function (from) { N.start = 'copy:' + from; N.name = from + '-copy'; U.go('new-overlay:'); };
  function nameProblem(n) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(n) || n.indexOf('..') >= 0) return 'Name: letters, digits, dot, dash and underscore.';
    if ((U.overlays || []).some(function (o) { return o.name === n; })) return 'An overlay with this name exists.';
    return null;
  }
  function renderNew() {
    var starts = [['page', 'One page', 'Start from a single web page, tested before it is created']].concat((U.overlays || []).map(function (o) {
      return ['copy:' + o.name, 'Copy of ' + o.name, (o.pages || 0) + ' page' + (o.pages === 1 ? '' : 's') + ', same positions and keys'];
    })).concat([['empty', 'Empty', 'Add pages in the editor']]);
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
    nbox.querySelector('[data-ncancel]').onclick = function () { resetNew(); U.changed(); U.go((U.overlays || []).length ? 'overlay:' + U.overlays[0].name : 'home:'); };
    nbox.querySelector('[data-ncreate]').onclick = createOverlay;
    renderTest();
  }
  function resetNew() { N.name = ''; N.start = 'page'; N.url = ''; N.vw = 1280; N.vh = 720; N.test = null; if (N.testTimer) { N.testTimer.stop(); N.testTimer = null; } }
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
    show: function () { if (U.readOnly) { U.go('home:'); return; } renderNew(); if (N.start === 'page' && N.url) testSoon(); var n = nbox.querySelector('#ovs-nn'); if (n) n.focus(); },
    hide: function () { if (N.testTimer) { N.testTimer.stop(); N.testTimer = null; } },
    app: function () { resetNew(); },
    save: function () { createOverlay(); },
    unsaved: function () { return N.name || N.url ? [{ label: 'New overlay' + (N.name ? ' ' + N.name : ''), go: 'new-overlay:' }] : []; }
  };
})();
