/* URL Overlay stream view: how one stream reaches viewers (input, template, outputs), what viewers of an output get,
   the overlay on the stream (a staged swap), using a template on it with a dry run, and the restart. The save bar
   covers only this screen's own changes; unsaved edits to the same overlay or template in their editors are linked,
   never saved or discarded from here. Status per output is what the template is configured to do, labeled so. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var box = null, el = {};
  // per stream, in memory: { swap: overlay name or '' (undefined when unchanged), dry: { template, plan, error } or null }
  var mem = {};
  var S = { name: null, status: null, preview: null, timer: null, out: null, watching: false, player: null, saving: false, lastEnc: null, saved: null };

  function m(name) { return mem[name || S.name] || (mem[name || S.name] = { swap: undefined, dry: null }); }
  function row() { return U.streamRow(S.name) || { name: S.name, publishing: false, template: null, own: false, added: true }; }
  function tpl() { return U.templateOfStream(row()); }
  function fileName(r) { return r.template ? String(r.template).replace(/\.xml$/i, '') : null; }
  function swapOf(r) { var x = m(r.name).swap; return x === undefined || x === (r.overlay || '') ? undefined : x; }
  // the streams that load the same file as this one, so a swap reaches them all
  function reach(r) {
    if (!r.template) return [];
    if (r.own) return [r];
    return (U.appStreams || []).filter(function (s) { return !s.own && s.template === r.template; });
  }
  function outputs() {
    var st = S.status, t = st && st.transcoder;
    if (t && t.state === 'running' && (t.encodes || []).length) {
      return t.encodes.map(function (e) { return { name: e.name, stream: e.outputStream || e.name, passthrough: !!e.passThrough, audio: false, carries: !!e.overlay }; });
    }
    var r = row();
    return U.outputsOf(tpl()).map(function (o) { o.stream = r.name + '_' + o.name; return o; });
  }

  /* ---------- rendering ---------- */
  function render() {
    if (!box || !S.name) return;
    renderHead(); renderPipe(); renderDry(); renderWatchBar(); renderSide(); renderBar();
  }
  function renderHead() {
    var r = row(), n = U.viewers(r), dec = S.status && S.status.transcoder && S.status.transcoder.decoderWidth ? S.status.transcoder.decoderWidth + ' x ' + S.status.transcoder.decoderHeight : null;
    var own = r.own && U.copyBase(r);
    el.head.innerHTML = '<span class="dot ' + (r.publishing ? 'live' : '') + '"></span><h2>' + U.esc(r.name) + '</h2>' +
      (r.publishing ? '<span class="bdg on">On air</span><span class="small muted">' + (r.pulled ? 'Pulled' : r.arrives === 'server' ? 'Inside WSE' : 'Pushed') + (dec ? ', ' + dec : '') + '</span>' : '<span class="bdg">Not publishing</span>') +
      '<span class="spacer"></span>' + (r.publishing && n != null ? '<span class="small muted">' + U.viewersText(n) + '</span>' : '') +
      (r.publishing ? '<button type="button" class="btn sm" data-write data-restart>' + U.icon('refresh') + 'Restart ' + U.esc(r.name) + '</button>' : '') +
      '<button type="button" class="btn sm" data-write data-usetpl>Use template on this stream</button>' +
      (own ? U.menuHtml('st', 'More actions for ' + r.name, [['stopown', 'Stop using ' + own + '...', true]]) : '');
    var b = el.head.querySelector('[data-restart]');
    if (b) b.onclick = function () { U.restartStream(r.name, false, r.restartNeeded ? 'Its template builds other outputs since it started; they take effect now. The overlay stays as it is.' : null); };
    el.head.querySelector('[data-usetpl]').onclick = function () { openDry(); };
    U.bindMenus(el.head);
    var so = el.head.querySelector('[data-mi=stopown]');
    if (so) so.onclick = stopUsing;
  }
  function renderPipe() {
    var r = row(), t = tpl(), base = U.baseOfStream(r), ov = r.overlay ? U.overlayEntry(r.overlay) : null, outs = outputs();
    var dec = S.status && S.status.transcoder && S.status.transcoder.decoderWidth ? S.status.transcoder.decoderWidth + ' x ' + S.status.transcoder.decoderHeight : null;
    if (!S.out || !outs.some(function (o) { return o.name === S.out; })) S.out = (outs.filter(function (o) { return o.carries; })[0] || outs[0] || {}).name || null;
    el.pipe.innerHTML = '<div class="pn pn-in"><span class="nk">Input</span><span class="ln"><b>' + (r.publishing ? (r.pulled ? 'Pulled stream' : r.arrives === 'server' ? 'Published inside WSE' : 'Pushed stream') : 'Waiting') + '</b></span>' +
        '<span class="ln">' + (r.publishing ? dec || 'Publishing' : 'No encoder connected') + '</span></div><span class="ar">' + U.icon('arrow') + '</span>' +
      '<div class="pn pn-tpl"><span class="nk">Template</span>' + (r.template ?
        '<span class="ln"><button type="button" class="linkbtn" data-go="template:' + U.esc(base || fileName(r)) + '"><b>' + U.esc(r.template) + '</b></button><span class="faint">' + (r.own ? 'own copy' : 'shared') + (r.restartNeeded ? ', restart pending' : '') + '</span></span>' +
        (r.vif ? '<span class="ln faint">A VIF template; no overlay</span>' : ov ? '<button type="button" class="ovbadge" data-go="overlay:' + U.esc(ov.name) + '">' + U.icon('overlay') + U.esc(ov.name) + ', ' + (ov.pages || 0) + ' page' + (ov.pages === 1 ? '' : 's') + '</button>' :
          r.overlay ? '<span class="ln"><span class="dot bad"></span>' + U.esc(r.overlay) + ' does not exist</span>' : '<span class="ln faint">' + (t && !t.dynamicOverlays ? 'Cannot carry an overlay yet' : 'No overlay') + '</span>')
        : '<span class="ln"><b>None</b></span><span class="ln faint">Not transcoded</span>') + '</div>' +
      '<span class="ar">' + U.icon('arrow') + '</span><div class="pn-outs" role="group" aria-label="Outputs, as configured; pick one to watch and check">' + (outs.length ? outs.map(function (o) {
        return '<button type="button" class="pn" aria-pressed="' + (o.name === S.out) + '" data-out="' + U.esc(o.name) + '"><span class="nk">Output</span><span class="ln"><b>' + U.esc(o.name) + '</b></span>' +
          '<span class="ln"><span class="dot"></span>' + U.esc(U.configuredText(o)) + '</span></button>';
      }).join('') : '<div class="pn"><span class="nk">Outputs</span><span class="ln faint">' + (r.template ? 'Known when the transcoder starts' : 'None') + '</span></div>') + '</div>';
    el.pipe.querySelectorAll('[data-out]').forEach(function (b) { b.onclick = function () { pickOut(b.getAttribute('data-out')); }; });
  }
  function pickOut(name) {
    if (S.out === name) return;
    S.out = name;
    el.pipe.querySelectorAll('[data-out]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-out') === name)); });
    renderWatchBar(); renderSide();
  }

  /* ---------- what viewers get: the checks from page to output ---------- */
  // one check as a chip: the state at a glance, the sentence behind it in the tip
  function li(dot, label, detail) {
    var t = U.esc(String(detail).replace(/<br>/g, '; ').replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'"));
    return '<li class="ck ' + dot + '" tabindex="0" data-tip="' + t + '" aria-label="' + U.esc(label) + ': ' + t + '"><span class="dot ' + dot + '"></span>' + U.esc(label) + '</li>';
  }
  function whyHtml() {
    var r = row(), st = S.status;
    var h = '';
    if (!r.publishing) {
      h = li('', 'Not publishing', r.overlay ? U.esc(r.overlay) + ' shows on ' + (outputs().filter(function (o) { return o.carries; }).map(function (o) { return o.name; }).join(', ') || 'no output') + ' when it starts.' :
        r.template ? 'No overlay will show: its template names none.' : 'No template applies to it, so it is not transcoded. Use a template on it to give it an overlay.');
    } else if (!st) {
      h = li('', 'Reading', 'Reading the status of ' + U.esc(r.name) + '.');
    } else {
      var t = st.transcoder || {}, tp = st.template || {}, from = st.overlayFrom || {}, d = st.draw, running = t.state === 'running';
      var caps = (st.objects || []).filter(function (o) { return o.capture; });
      var capsOk = caps.length > 0 && caps.every(function (o) { return o.capture.state === 'running' && o.capture.lastCaptureAt; });
      var o = outputs().filter(function (x) { return x.name === S.out; })[0];
      h += li(running ? 'on' : t.state === 'vif-template' ? '' : 'bad', 'Transcoder', running ? 'Running, decoding ' + t.decoderWidth + ' x ' + t.decoderHeight + '.' :
        t.state === 'vif-template' ? 'Runs a VIF template, so no overlay is drawn.' : 'Not running: ' + U.esc(r.name) + ' is not transcoded, or its transcoder is off.');
      if (running) {
        h += li(tp.loaded ? 'on' : '', 'Template', tp.loaded ? U.esc(tp.loaded) + (tp.own ? ', its own copy' : ', from the template order') + '.' : 'Known when the transcoder starts.');
        h += li(from.source === 'template' && !from.missing ? 'on' : from.missing ? 'bad' : '', 'Overlay', from.source === 'template' ? (from.missing ? U.esc(from.name) + ' is named by the template but does not exist in ' + U.esc(U.app) + '.' : U.esc(from.name) + ', from the template.') :
          from.source === 'stream' ? 'Saved for this stream directly (an older setup).' : 'The template names no overlay.');
        if (caps.length) h += li(capsOk ? 'on' : 'bad', 'Pages', caps.map(function (x) {
          var c = x.capture;
          return U.esc(x.id) + ': ' + U.esc(c.state) + (c.lastCaptureAt ? ', ' + U.ago(c.lastCaptureAt) : '') + (c.sharedBy > 1 ? ', browser shared by ' + c.sharedBy + ' streams' : '') +
            (c.lastError && c.state !== 'running' ? ' (' + U.esc(c.lastError) + ')' : '');
        }).join('<br>'));
        if (d) h += li(d.slowCount || (d.avgMs || 0) > (d.maxDrawingTimeMs || 100) / 2 ? 'warn' : 'on', 'Burn-in ' + Math.round(d.avgMs || 0) + ' ms', Math.round(d.avgMs || 0) + ' ms per update of ' + (d.maxDrawingTimeMs || 100) + ' allowed' + (d.slowCount ? ', ' + d.slowCount + ' slow' : '') + '.');
        // Chrome takes screenshots off the transcoder, which draws the last one it has meanwhile
        var shots = caps.filter(function (x) { return x.capture.timing && x.capture.timing.screenshotMs != null; }).map(function (x) {
          var tm = x.capture.timing, every = Math.max(tm.intervalMs || 0, tm.screenshotMs);
          return (caps.length > 1 ? U.esc(x.id) + ': ' : '') + Math.round(tm.screenshotMs) + ' ms per screenshot, one every ' + (every >= 1000 ? (every / 1000).toFixed(1) + ' s' : Math.round(every) + ' ms');
        });
        if (shots.length) h += li('', 'Page screenshot', shots.join('; ') + '; the stream keeps the last picture meanwhile.');
        if (o) h += li('', o.name + ', configured', U.esc(U.configuredText(o)) + '. Configuration only: whether the encoder drew it is not measured yet.');
      }
    }
    if (r.restartNeeded) h += li('warn', 'Restart pending', 'Its template builds other outputs since it started; they take effect when ' + U.esc(r.name) + ' restarts. The overlay is not affected.');
    return h;
  }

  // one sentence that leads the side column; the checks behind it stay under Details
  function verdict() {
    var r = row(), st = S.status, o = outputs().filter(function (x) { return x.name === S.out; })[0];
    if (!r.publishing) return ['', 'Not publishing.'];
    if (!st) return ['', 'Reading the status of ' + U.esc(r.name) + '...'];
    var t = st.transcoder || {}, from = st.overlayFrom || {}, d = st.draw;
    var caps = (st.objects || []).filter(function (x) { return x.capture; });
    var down = caps.filter(function (x) { return !(x.capture.state === 'running' && x.capture.lastCaptureAt); });
    if (t.state === 'vif-template') return ['', 'Runs a VIF template, so no overlay is drawn.'];
    if (t.state !== 'running') return ['bad', U.esc(r.name) + ' is not transcoded, so no overlay is drawn.'];
    if (from.missing) return ['bad', 'No overlay: ' + U.esc(from.name) + ' does not exist in ' + U.esc(U.app) + '.'];
    if (!from.name) return ['', 'The template names no overlay.'];
    if (down.length) return ['bad', U.esc(from.name) + ': ' + down.map(function (x) { return U.esc(x.id); }).join(', ') + ' not capturing, so viewers see the last picture.'];
    if (d && d.slowCount) return ['warn', U.esc(from.name) + ' is drawn, but ' + d.slowCount + (d.slowCount === 1 ? ' burn-in update was' : ' burn-in updates were') + ' slow since ' + U.esc(r.name) + ' started.'];
    if (o && !o.carries) return ['', U.esc(o.name) + ' carries no overlay. ' + U.esc(from.name) + ' is on the outputs that do.'];
    return ['', (o ? U.esc(o.name) + ' is configured to carry ' : 'Configured to carry ') + U.esc(from.name) + (caps.length ? ', and its pages are capturing' : '') + '. Whether the encoder drew it is not measured yet.'];
  }

  /* ---------- watch: today's HLS player, on demand ---------- */
  function renderWatchBar() {
    var outs = outputs(), r = row();
    // the output is picked in the pipeline above; this only names it
    el.monh.innerHTML = '<b>' + U.esc(S.out ? r.name + '_' + S.out : r.name) + '</b>' + (S.watching ? '<span class="bdg on">Playing</span>' + U.tip('Played from this server, about 10 s behind live.') : '') +
      '';
    renderWatch();
  }
  function stopWatch() { if (S.player) { S.player.stop(); S.player = null; } }
  function renderWatch() {
    var r = row(), o = (S.preview && S.preview.outputs || []).filter(function (x) { return x.encode === S.out; })[0];
    var url = o ? U.hls(o.hls) : null;
    if (!S.watching || !r.publishing) {
      stopWatch();
      S.watching = false;
      el.watch.innerHTML = '<div class="frame idle">' + (r.publishing ? '<button type="button" class="playbig" data-watch aria-label="Watch ' + U.esc(S.out || r.name) + ', about 10 s behind live">' + U.icon('play') + '<span>Watch ' + U.esc(S.out || r.name) + '</span></button>' :
        '<div class="waiting">Not publishing</div>') + '<div class="lbl">' + U.esc(S.out ? r.name + '_' + S.out : r.name) + '</div></div>';
      var w = el.watch.querySelector('[data-watch]');
      if (w) w.onclick = function () { S.watching = true; renderWatchBar(); };
      return;
    }
    if (el.watch.getAttribute('data-url') === url && S.player) return;
    stopWatch();
    el.watch.setAttribute('data-url', url || '');
    el.watch.innerHTML = '<div class="frame"><video muted playsinline></video><div class="waiting" data-wait>' + (url ? 'Connecting...' : 'This output cannot be played from here yet.') + '</div>' +
      '<div class="lbl">' + U.esc(o ? o.stream : r.name) + '</div></div><div class="hrow" style="margin-top:8px"><span class="spacer"></span><button type="button" class="btn sm" data-stopwatch>Stop watching</button></div>';
    el.watch.querySelector('[data-stopwatch]').onclick = function () { S.watching = false; el.watch.removeAttribute('data-url'); renderWatchBar(); };
    if (!url) return;
    var v = el.watch.querySelector('video'), wait = el.watch.querySelector('[data-wait]');
    S.player = U.player(v, function (state, delay) {
      wait.hidden = state === 'playing';
      if (state === 'waiting') wait.textContent = 'Waiting for ' + (o ? o.stream : r.name) + '. Retrying in ' + Math.round(delay / 1000) + ' s.';
      else if (state === 'unsupported') wait.textContent = 'This browser cannot play HLS.';
    });
    S.player.load(url);
  }

  /* ---------- the side column: other screens' unsaved work, what viewers get, the overlay on this stream ---------- */
  function scopeHtml() {
    if (U.readOnly) return '';
    var r = row(), h = '', base = U.baseOfStream(r);
    var od = r.overlay && U.screens.overlay.draftInfo ? U.screens.overlay.draftInfo(r.overlay) : null;
    if (od) h += U.scopeLine('<b>' + U.esc(r.overlay) + ' has unsaved edits in the overlay editor</b> (' + U.esc(od.join(', ')) + ').', 'Open overlay editor', 'overlay:' + r.overlay);
    var td = base && U.screens.template.draftInfo ? U.screens.template.draftInfo(base) : null;
    if (td) h += U.scopeLine('<b>' + U.esc(base) + ' has unsaved edits in the template editor</b> (' + U.esc(td.join(', ')) + ').' + (r.own ? ' ' + U.esc(r.template) + ' would not follow them.' : ''), 'Open template editor', 'template:' + base);
    return h;
  }
  function renderSide() {
    var r = row(), o = S.out, oo = outputs().filter(function (x) { return x.name === o; })[0];
    el.scope.innerHTML = scopeHtml();
    // an output that cannot carry an overlay has nothing to set here: say what it is and point at the ones that can
    if (oo && (oo.passthrough || oo.audio)) {
      var can = outputs().filter(function (x) { return x.carries; });
      el.why.innerHTML = '<div class="sec-h">What viewers of ' + U.esc(o) + ' get</div><div class="passnote">' + U.icon(oo.audio ? 'audio' : 'input') +
        '<div><b>' + (oo.audio ? 'Audio only' : 'The stream as it arrives') + '</b><span class="small muted">No overlay can be drawn on ' + (oo.audio ? 'audio' : 'a passthrough output') + '.</span></div></div>' +
        (can.length ? '<div class="hrow chips"><span class="small muted">With the overlay:</span>' + can.map(function (x) { return '<button type="button" class="chip" data-pickout="' + U.esc(x.name) + '">' + U.icon('overlay') + U.esc(x.name) + '</button>'; }).join('') + '</div>' : '');
      el.why.querySelectorAll('[data-pickout]').forEach(function (b) { b.onclick = function () { pickOut(b.getAttribute('data-pickout')); }; });
      el.whyd.innerHTML = ''; el.cue.innerHTML = '';
      return;
    }
    var v = verdict();
    el.why.innerHTML = '<div class="sec-h">What viewers of ' + U.esc(o || r.name) + ' get</div><p class="verdict">' + (v[0] ? '<span class="dot ' + v[0] + '"></span>' : '') + '<span>' + v[1] + '</span></p>' +
      '<ul class="checks" aria-label="Checks from page to output">' + whyHtml() + '</ul>';
    el.whyd.innerHTML = '';
    renderCue();
  }
  // a poll does not redraw the select while it is in use
  function renderCue(force) {
    if (!force && el.cue.contains(document.activeElement) && document.activeElement.tagName === 'SELECT') return;
    var r = row(), t = tpl(), sw = swapOf(r), cur = sw !== undefined ? sw : (r.overlay || '');
    var h = '<div class="sec-h">Overlay' + U.tip('One overlay per stream. It is drawn on every output that carries it; passthrough and audio outputs never do.') + '</div>';
    if (!r.template) h += '<p class="val faint">None. ' + U.esc(r.name) + ' is not transcoded; use a template on it first.</p>';
    else if (r.vif) h += '<p class="val faint">None. It runs a VIF template.</p>';
    else if (U.readOnly) h += '<p class="val">' + (r.overlay ? U.esc(r.overlay) : 'None') + '</p>' + U.roNote();
    else if (!t || !t.managed) h += '<p class="val">' + (r.overlay ? U.esc(r.overlay) : 'None') + '</p><p class="small muted">' + U.esc(r.template) + ' was not written by URL Overlay, so its overlay is set in the template editor. ' +
      '<button type="button" class="linkbtn" data-go="template:' + U.esc(U.baseOfStream(r) || '') + '"><b>Open ' + U.esc(U.baseOfStream(r) || r.template) + '</b></button></p>';
    else {
      var names = (U.overlays || []).map(function (x) { return x.name; });
      if (r.overlay && names.indexOf(r.overlay) < 0) names.unshift(r.overlay);
      var who = reach(r).map(function (s) { return s.name; });
      h += '<select data-swap class="' + (sw !== undefined ? 'is-draft' : '') + '" aria-label="Overlay for ' + U.esc(r.name) + '"><option value="">None</option>' + names.map(function (n) {
        return '<option value="' + U.esc(n) + '"' + (n === cur ? ' selected' : '') + '>' + U.esc(n) + (U.overlayEntry(n) ? '' : ', does not exist') + '</option>'; }).join('') + '</select>' +
        '<div class="hrow chips">' + (sw !== undefined ? '<span class="chip-draft">Saved: ' + U.esc(r.overlay || 'None') + '</span>' : '') +
        '<span class="bdg" title="The file this changes">' + U.icon('copy') + U.esc(r.template) + '</span>' +
        (r.own ? '<span class="bdg">' + U.esc(r.name) + ' only</span>' : '<span class="bdg" title="Streams that load this file">' + U.icon('link') + U.esc(who.join(', ')) + '</span>') + '<span class="bdg on">Live on save</span></div>';
    }
    var caps = S.status && (S.status.objects || []).filter(function (x) { return x.capture; });
    if (caps && caps.length) h += '<div class="sec-h" style="margin-top:12px">Pages' + U.tip('Pages of ' + (r.overlay || '') + ', shared by every stream that shows it.') + '</div><div class="checks">' + caps.map(function (x) {
      var c = x.capture, bad = c.state === 'blocked' || c.state === 'error';
      return '<span class="ck ' + (bad ? 'bad' : '') + '" tabindex="0" data-tip="' + U.esc(bad ? c.state + (c.lastError ? ': ' + c.lastError : '') : 'Captured ' + U.ago(c.lastCaptureAt)) + '"><span class="dot ' + (bad ? 'bad' : 'on') + '"></span>' + U.esc(x.id) + '</span>';
    }).join('') + '</div>';
    el.cue.innerHTML = h;
    var s = el.cue.querySelector('[data-swap]');
    if (s) s.onchange = function () { m(r.name).swap = s.value === (r.overlay || '') ? undefined : s.value; renderCue(true); renderBar(); U.changed(); };
  }

  /* ---------- use a template on this stream: a dry run, written only on Save ---------- */
  function candidates() {
    // templates carrying an overlay, without the streams' own copies
    var out = [];
    (U.overlays || []).forEach(function (o) { (o.templates || []).forEach(function (t) { if (!U.copyBase(U.templateEntry(t) || {}) && out.indexOf(t) < 0) out.push(t); }); });
    return out.sort();
  }
  function openDry() {
    var list = candidates(), r = row();
    if (!list.length) { U.toast('No template carries an overlay yet. Make one in Templates first.', { level: 'bad' }); return; }
    var want = (m().dry && m().dry.template) || (r.own ? U.copyBase(r) : null);
    if (list.indexOf(want) < 0) want = list[0];
    m().dry = { template: want, plan: null, error: null };
    renderDry(); renderBar(); U.changed(); plan();
  }
  function plan() {
    var d = m().dry, name = S.name;
    if (!d) return;
    d.plan = null; d.error = null; renderDry(); renderBar();
    U.api.get(U.appPath() + '/templates/assign?scope=stream&template=' + U.enc(d.template) + '&stream=' + U.enc(name)).then(function (p) {
      if (m(name).dry !== d) return;
      d.plan = p; if (S.name === name) { renderDry(); renderBar(); }
    }, function (e) { if (m(name).dry !== d) return; d.error = e.message; if (S.name === name) { renderDry(); renderBar(); } });
  }
  function renderDry() {
    var d = m().dry, r = row();
    if (!d || U.readOnly) { el.dry.innerHTML = ''; return; }
    var list = candidates(), t = U.templateEntry(d.template), rows = [];
    if (d.error) rows = [['Not possible', '<span class="err">' + U.esc(d.error) + '</span>']];
    else if (!d.plan) rows = [['Checking', '<span class="faint">Reading what would change...</span>']];
    else {
      var p = d.plan, ovNew = t && t.overlay, ovNow = r.overlay || null, n = U.viewers(r);
      var curOuts = U.outputsOf(tpl()).map(function (o) { return o.name; }), newOuts = U.outputsOf(t).map(function (o) { return o.name; });
      var gone = curOuts.filter(function (x) { return newOuts.indexOf(x) < 0; }), added = newOuts.filter(function (x) { return curOuts.indexOf(x) < 0; });
      rows.push(['File written', p.writeFile ? (r.own ? 'Replaces ' : 'Writes ') + '<span class="mono">transcoder/templates/' + U.esc(p.writeFile) + '</span>, an own copy of ' + U.esc(p.template) + '.' +
        (r.own ? ' ' + U.esc(r.name) + '\'s current copy is replaced and not kept.' : ' Later edits to ' + U.esc(p.template) + ' reach it only when you update the copy.') : U.esc(r.name) + ' already has this template as its own.']);
      rows.push(['Overlay, live now', !r.publishing ? (ovNew ? U.esc(ovNew) + ' shows when it starts.' : 'None when it starts.') : ovNew === ovNow ? (ovNow ? U.esc(ovNow) + ' stays.' : 'Still none.') :
        ovNow ? '<b>' + U.esc(ovNow) + ' disappears' + (n != null ? ' for ' + U.viewersText(n) : '') + ' now.</b>' + (ovNew ? ' ' + U.esc(ovNew) + ' replaces it.' : '') : U.esc(ovNew) + ' appears now.']);
      rows.push(['Outputs, after restart', !r.template ? (newOuts.join(', ') || 'None') + ' start.' : (gone.length ? U.esc(gone.join(', ')) + ' stop. ' : '') + (added.length ? U.esc(added.join(', ')) + ' start. ' : '') +
        (newOuts.filter(function (x) { return added.indexOf(x) < 0; }).length ? U.esc(newOuts.filter(function (x) { return added.indexOf(x) < 0; }).join(', ')) + ' remain.' : '')]);
      rows.push(['VIF', p.currentIsVif ? '<b>VIF stops analyzing ' + U.esc(r.name) + '.</b> It now runs ' + U.esc(p.currentTemplate) + '. Other streams keep VIF.' : 'Not used on ' + U.esc(r.name) + '. Nothing lost.']);
      var others = (p.publishing || []).filter(function (x) { return x !== r.name; });
      rows.push(['Application', p.transcoderTurnsOn ? '<b>This switches the transcoder on for ' + U.esc(U.app) + '.</b> ' + (others.length ? U.esc(others.join(', ')) + ' start transcoding with the default template when they next start.' : 'Every stream published to it from now on is transcoded.') +
        ' It needs an application restart, which drops every stream in it.' : p.templatesBefore !== p.templatesAfter ? U.esc(U.app) + ' is set to look for a template named after each stream first: <span class="mono">' + U.esc(p.templatesAfter) + '</span>. Applies after an application restart.' :
        'No application restart needed.']);
    }
    el.dry.innerHTML = '<div class="dry"><div class="dry-h"><b>Use a template on ' + U.esc(r.name) + '</b><select data-dtpl aria-label="Template">' + list.map(function (x) {
        return '<option value="' + U.esc(x) + '"' + (x === d.template ? ' selected' : '') + '>' + U.esc(x) + (r.own && U.copyBase(r) === x ? ' (current)' : '') + '</option>'; }).join('') + '</select>' +
      '<span class="small faint">Dry run, nothing is written until you save</span><span class="spacer"></span><button type="button" class="btn sm ghost icon" data-dclose aria-label="Close dry run">' + U.icon('close') + '</button></div>' +
      '<table class="tbl kvt"><tbody>' + rows.map(function (x) { return '<tr><td>' + x[0] + '</td><td>' + x[1] + '</td></tr>'; }).join('') + '</tbody></table></div>';
    el.dry.querySelector('[data-dtpl]').onchange = function (e) { d.template = e.target.value; plan(); };
    el.dry.querySelector('[data-dclose]').onclick = function () { m().dry = null; renderDry(); renderBar(); U.changed(); };
  }

  /* ---------- the save bar: the dry run, or the overlay swap ---------- */
  function changes() {
    var r = row(), d = m().dry, sw = swapOf(r), out = [];
    if (d) out.push('Use ' + d.template + ' on ' + r.name);
    else if (sw !== undefined) out.push(r.template + ' overlay: ' + (sw || 'none'));
    return out;
  }
  function impact() {
    var r = row(), d = m().dry, n = U.viewers(r);
    if (d) {
      var t = U.templateEntry(d.template), removes = r.publishing && r.overlay && (!t || t.overlay !== r.overlay);
      return (removes ? r.overlay + ' leaves ' + r.name + ' now. ' : 'Overlay ' + (t && t.overlay !== (r.overlay || null) ? 'changes now' : 'unchanged') + '. ') +
        (r.publishing ? 'Outputs wait for a restart' + (n != null ? ' (' + U.viewersText(n) + ')' : '') + '.' : 'Applies when it starts publishing.') + (removes || r.own ? ' You confirm before it is written.' : '');
    }
    var who = reach(r), live = who.filter(function (s) { return s.publishing; }).map(function (s) { return s.name; }), later = who.filter(function (s) { return !s.publishing; }).map(function (s) { return s.name; });
    return (live.length ? 'Live now on ' + live.join(', ') + '.' : 'No stream that loads it is publishing.') + (later.length ? ' ' + later.join(', ') + ' gets it when it publishes.' : '');
  }
  function renderBar() {
    var d = m().dry;
    U.saveBar(el.bar, { changes: changes(), impact: impact(), saving: S.saving, problem: d && d.error ? d.error : d && !d.plan ? 'Checking what changes...' : null,
      saveLabel: d ? 'Use on ' + S.name : 'Save', saved: S.saved && S.saved.stream === S.name ? S.saved : null, onSave: save, onDiscard: discard });
  }
  function discard() { var x = m(); x.dry = null; x.swap = undefined; render(); U.changed(); }
  function save() {
    var r = row(), x = m();
    if (S.saving || !changes().length) return;
    if (x.dry) return saveUse(r, x.dry);
    return saveSwap(r, swapOf(r));
  }
  function saveSwap(r, ov) {
    var t = tpl(), who = reach(r), losing = r.overlay && ov !== r.overlay ? who.filter(function (s) { return s.publishing && s.overlay === r.overlay; }) : [];
    var ask = !losing.length ? Promise.resolve(true) : U.confirm(r.overlay + ' leaves ' + losing.map(function (s) { return s.name; }).join(', ') + '?', '<ul>' + losing.map(function (s) {
      var n = U.viewers(s);
      return '<li><b>' + U.esc(r.overlay) + ' disappears' + (n != null ? ' for ' + U.viewersText(n) : '') + ' now</b> on ' + U.esc(s.name) + '.</li>';
    }).join('') + '</ul><p>' + (ov ? U.esc(ov) + ' replaces it at once.' : 'No overlay replaces it.') + ' ' + U.esc(r.template) + ' is changed.</p>', 'Save', false);
    return ask.then(function (ok) {
      if (!ok) return;
      S.saving = true; renderBar();
      var encodes = ov && t && t.overlay ? (t.overlayEncodes || []) : [];
      return U.api.post(U.vhostPath() + '/templates/' + U.enc(fileName(r)) + '/overlay', { overlay: ov || null, encodes: encodes }).then(function (res) {
        S.saving = false; m(r.name).swap = undefined;
        S.saved = { stream: r.name, title: 'Saved at ' + U.hhmm() + ': ' + r.template + ' overlay ' + (ov || 'none') + '.', detail: (res.live || []).length ? 'Live now on ' + res.live.map(function (x) { return x.stream; }).join(', ') + '.' : 'No stream that loads it is publishing.' };
        U.changed();
        return U.refreshState(true).then(function () { render(); return U.afterTemplateWrite(res.live); });
      }, function (e) { S.saving = false; renderBar(); U.banner('swap', 'bad', 'Not saved.', e.message); });
    });
  }
  function saveUse(r, d) {
    var t = U.templateEntry(d.template), n = U.viewers(r), removes = r.publishing && r.overlay && (!t || t.overlay !== r.overlay);
    var ask = !(removes || r.own) ? Promise.resolve(true) : U.confirm('Use ' + d.template + ' on ' + r.name + '?',
      (removes ? '<p><b>' + U.esc(r.overlay) + ' disappears' + (n != null ? ' for ' + U.viewersText(n) : '') + ' now.</b></p>' : '') +
      (r.own ? '<p>This replaces <span class="mono">' + U.esc(r.template) + '</span>, ' + U.esc(r.name) + '\'s own copy' + (U.copyBase(r) ? ' of ' + U.esc(U.copyBase(r)) : '') + '. The replaced copy is not kept.</p>' : '') +
      '<p>Outputs change at the next restart of ' + U.esc(r.name) + '.</p>', 'Use ' + d.template, false);
    return ask.then(function (ok) {
      if (!ok) return;
      S.saving = true; renderBar();
      return U.api.post(U.appPath() + '/templates/assign', { scope: 'stream', template: d.template, stream: r.name }).then(function (res) {
        S.saving = false; m(r.name).dry = null;
        var msg = res.restartRequired ? ' Restart the application ' + U.app + ' for its transcoder setting to take effect.' : '';
        S.saved = { stream: r.name, title: 'Saved at ' + U.hhmm() + ': ' + r.name + ' uses ' + d.template + '.', detail: (res.restartNeeded ? 'Its outputs change when it restarts.' : 'Nothing else waits for a restart.') + msg };
        U.toast(r.name + ' uses ' + d.template + '.' + msg);
        U.changed();
        return U.refreshState(true).then(function () {
          render();
          if (r.publishing && res.restartNeeded) return U.restartStream(r.name, false, 'Its new outputs start now; until then it keeps its current ones.');
        });
      }, function (e) { S.saving = false; renderBar(); U.banner('use', 'bad', 'Not changed.', e.message); });
    });
  }
  function stopUsing() {
    var r = row(), base = U.copyBase(r);
    U.confirm('Stop using ' + base + ' on ' + r.name + '?', '<p>' + U.esc(r.name) + '\'s own copy, <span class="mono">' + U.esc(r.template) + '</span>, is deleted. ' +
      U.esc(r.name) + ' then gets its template from the application\'s template order, without this copy\'s overlay, from its next restart.</p><p>This cannot be undone.</p>', 'Delete the copy', true).then(function (ok) {
        if (!ok) return;
        U.api.v2del('/transcoder/templates/' + U.enc(fileName(r))).then(function () {
          U.toast(r.name + ' no longer has its own template. It changes when ' + r.name + ' restarts.');
          return U.refreshState(true).then(render);
        }, function (e) { U.banner('stopown', 'bad', 'Not changed.', e.message); });
      });
  }

  /* ---------- status: only for the stream in view, while it is shown ---------- */
  function startStatus() {
    if (S.timer) S.timer.stop();
    var name = S.name;
    S.status = null; S.preview = null; S.lastEnc = null;
    S.timer = U.every(2000, function () {
      if (S.name !== name || U.current() !== 'stream') return null;
      var r = row();
      if (!r.publishing && !r.template) return null;
      var p = U.streamPath(U.app, name);
      return U.api.get(p + '/status').then(function (st) {
        if (S.name !== name) return null;
        var before = S.status;
        S.status = st;
        var enc = ((st.transcoder && st.transcoder.encodes) || []).map(function (e) { return e.name; }).join('|');
        var get = enc !== S.lastEnc ? U.api.get(p + '/preview').then(function (pv) { S.preview = pv; }, function () {}) : Promise.resolve();
        S.lastEnc = enc;
        return get.then(function () {
          if (S.name !== name) return;
          if (!before || enc !== ((before.transcoder && before.transcoder.encodes) || []).map(function (e) { return e.name; }).join('|')) { renderHead(); renderPipe(); renderWatchBar(); }
          renderSide();
        });
      }, function (e) {
        if (S.name === name) el.why.innerHTML = '<div class="sec-h">What viewers get</div><p class="err">The status of ' + U.esc(name) + ' could not be read: ' + U.esc(e.message) + '</p>';
      });
    });
  }

  U.screens.stream = {
    init: function (b) {
      box = b;
      box.innerHTML = '<div class="pnl"><div class="sh" data-sh></div><div class="pipe" role="group" data-pipe></div><div data-dry></div>' +
        '<div class="mon"><div><div class="mon-h" data-monh></div><div data-watch></div></div><div class="side"><div class="scope-lines" data-scope></div><div data-why></div><div data-cue></div><div data-whyd></div></div></div></div>' +
        '<div data-bar hidden></div>';
      el = { head: box.querySelector('[data-sh]'), pipe: box.querySelector('[data-pipe]'), dry: box.querySelector('[data-dry]'), monh: box.querySelector('[data-monh]'), watch: box.querySelector('[data-watch]'),
        scope: box.querySelector('[data-scope]'), why: box.querySelector('[data-why]'), whyd: box.querySelector('[data-whyd]'), cue: box.querySelector('[data-cue]'), bar: box.querySelector('[data-bar]') };
      el.pipe.setAttribute('aria-label', 'How the stream reaches viewers');
    },
    show: function (name) {
      if (!name) { U.go('home:'); return; }
      if (S.name !== name) { stopWatch(); S.watching = false; S.out = null; el.watch.removeAttribute('data-url'); }
      S.name = name;
      render();
      startStatus();
    },
    hide: function () { stopWatch(); S.watching = false; if (S.timer) { S.timer.stop(); S.timer = null; } el.watch.removeAttribute('data-url'); },
    state: function () {
      if (U.current() !== 'stream' || !S.name) return;
      renderHead(); renderPipe(); renderSide(); renderBar();
      if (m().dry && !el.dry.contains(document.activeElement)) renderDry();
    },
    app: function () { mem = {}; S.name = null; S.saved = null; stopWatch(); },
    access: function () { if (U.current() === 'stream') render(); },
    save: save,
    unsaved: function () {
      var out = [];
      Object.keys(mem).forEach(function (n) {
        var x = mem[n], r = U.streamRow(n) || { name: n, overlay: null };
        if (x.dry) out.push({ label: n + ': use ' + x.dry.template, detail: 'dry run', go: 'stream:' + n });
        else if (x.swap !== undefined && x.swap !== (r.overlay || '')) out.push({ label: n + ': overlay ' + (x.swap || 'none'), go: 'stream:' + n });
      });
      return out;
    }
  };
})();
