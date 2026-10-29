/* URL Overlay step 4, Live: for one stream, what viewers get and a check of every link from page to output. Status
   updates redraw only the checks, so playback is not interrupted. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var el = {};
  var S = { stream: null, status: null, preview: null, player: null, output: null, timer: null, laidOut: null };

  function row(ok, label, detail) {
    var cls = ok === true ? 'ok' : ok === false ? 'bad' : '';
    return '<div class="check-row"><span class="dot ' + cls + '"></span><div><strong>' + U.esc(label) + '</strong><div class="small muted">' + detail + '</div></div></div>';
  }

  function carrying() {
    var st = S.status || {}, outs = ((st.transcoder && st.transcoder.encodes) || []).filter(function (e) { return e.overlay; });
    return ((S.preview && S.preview.outputs) || []).filter(function (o) { return outs.some(function (e) { return e.name === o.encode; }); });
  }

  /* one line: how long each page's screenshot takes and how often it is taken; a slow screenshot stretches the interval */
  function shots(caps) {
    var parts = caps.filter(function (o) { return o.capture.timing && o.capture.timing.screenshotMs != null; }).map(function (o) {
      var t = o.capture.timing, every = Math.max(t.intervalMs || 0, t.screenshotMs);
      return (caps.length > 1 ? U.esc(o.id) + ': ' : '') + Math.round(t.screenshotMs) + ' ms per screenshot, one every ' +
        (every >= 1000 ? (every / 1000).toFixed(1) + ' s' : Math.round(every) + ' ms');
    });
    return parts.length ? row(null, 'Page screenshot', parts.join('; ') + '; the stream keeps the last picture meanwhile') : '';
  }

  /* the frame: stream picker result, output picker and player; rebuilt only when what plays changes */
  function layout() {
    if (S.player) { S.player.stop(); S.player = null; }
    if (!S.stream) {
      el.box.innerHTML = '<div class="empty"><h3>No stream uses an overlay yet</h3><p>Step 3 puts an overlay template on a stream.</p></div>';
      S.laidOut = null;
      return;
    }
    var list = carrying();
    if (!S.output || !list.some(function (o) { return o.stream === S.output; })) S.output = list.length ? list[0].stream : null;
    el.box.innerHTML = '<div data-notice></div><div class="two"><div><div class="row between mb-s">' +
      '<span class="small muted">What viewers see, about 10 s behind</span>' +
      (list.length > 1 ? '<select class="w-auto" data-out aria-label="Output">' + list.map(function (o) { return '<option' + (o.stream === S.output ? ' selected' : '') + '>' + U.esc(o.stream) + '</option>'; }).join('') + '</select>' : '') +
      '</div><div class="stage-wrap"><div class="frame"><video muted playsinline></video><div class="waiting" data-wait>' +
      (S.output ? 'Connecting...' : 'No output with the overlay to play yet.') + '</div><div class="lbl">' + U.esc(S.output || 'no output') + '</div></div></div></div>' +
      '<div class="checks" data-checks></div></div>';
    var sel = el.box.querySelector('[data-out]');
    if (sel) sel.onchange = function () { S.output = sel.value; layout(); update(); };
    var v = el.box.querySelector('video'), wait = el.box.querySelector('[data-wait]');
    var o = list.filter(function (x) { return x.stream === S.output; })[0];
    if (o) {
      S.player = U.player(v, function (state, delay) {
        wait.hidden = state === 'playing';
        if (state === 'waiting') wait.textContent = 'Waiting for ' + S.output + '. Retrying in ' + Math.round(delay / 1000) + ' s.';
      });
      S.player.load(U.hls(o.hls));
    }
    S.laidOut = S.stream + '|' + list.map(function (x) { return x.stream; }).join(',');
  }

  /* the notice and checks, from the latest status */
  function update() {
    var st = S.status, checks = el.box.querySelector('[data-checks]'), notice = el.box.querySelector('[data-notice]');
    if (!checks) return;
    if (!st) { checks.innerHTML = '<p class="muted small">Loading ' + U.esc(S.stream) + '...</p>'; return; }
    var t = st.transcoder || {}, tpl = st.template || {}, from = st.overlayFrom || {}, d = st.draw;
    var outs = (t.encodes || []).filter(function (e) { return e.overlay; });
    var caps = (st.objects || []).filter(function (o) { return o.capture; });
    var capsOk = caps.length > 0 && caps.every(function (o) { return o.capture.state === 'running' && o.capture.lastCaptureAt; });
    var running = t.state === 'running';
    checks.innerHTML = [
      row(running, 'Transcoder', running ? 'running, decoding ' + t.decoderWidth + ' x ' + t.decoderHeight :
        t.state === 'vif-template' ? 'runs a VIF template, so no overlay is drawn' : U.esc(S.stream) + ' is not publishing, or its transcoder is off'),
      row(running ? !!tpl.loaded : null, 'Template', tpl.loaded ? U.esc(tpl.loaded) + (tpl.own ? ' (its own copy)' : ' (application default)') : 'known when the transcoder starts'),
      row(running ? (from.source === 'template' && !from.missing) : null, 'Overlay', from.source === 'template' ? (from.missing ? U.esc(from.name) + ' is named by the template but does not exist' : U.esc(from.name) + ', from the template') :
        from.source === 'stream' ? 'saved for this stream directly (older setup)' : 'the template names no overlay'),
      row(running ? capsOk : null, 'Pages', caps.length ? caps.map(function (o) {
        var c = o.capture;
        return U.esc(o.id) + ': ' + U.esc(c.state) + (c.lastCaptureAt ? ', ' + U.ago(c.lastCaptureAt) : '') + (c.sharedBy > 1 ? ', browser shared by ' + c.sharedBy + ' streams' : '') +
          (c.lastError && c.state !== 'running' ? ' (' + U.esc(c.lastError) + ')' : '');
      }).join('<br>') : 'no page captured'),
      row(running && d ? !d.slowCount : null, 'Burn-in cost', d ? Math.round(d.avgMs || 0) + ' ms per update of ' + (d.maxDrawingTimeMs || 100) + ' allowed' + (d.slowCount ? ', ' + d.slowCount + ' slow' : '') : 'not drawing yet'),
      // Chrome takes screenshots off the transcoder, which draws the last one it has meanwhile
      shots(caps),
      row(running ? outs.length > 0 : null, 'Outputs with the overlay', outs.length ? outs.map(function (e) { return U.esc(e.outputStream || e.name); }).join(', ') : 'none: no output viewers watch carries it')
    ].join('');
    var good = running && from.source && !from.missing && capsOk && outs.length > 0;
    notice.innerHTML = '<div class="notice ' + (good ? 'ok' : 'slow') + '"><div><strong>' + (good ? 'Viewers of ' + U.esc(outs.map(function (e) { return e.outputStream || e.name; }).join(', ')) + ' see the overlay.'
      : 'Viewers do not see the overlay on ' + U.esc(S.stream) + ' yet.') + '</strong></div></div>';
  }

  function choices() {
    var rows = (U.appStreams || []).filter(function (s) { return s.overlay; }).sort(function (a, b) { return (b.publishing - a.publishing) || a.name.localeCompare(b.name); });
    var want = S.stream || U.store.get('live.' + U.app);
    if (!rows.some(function (r) { return r.name === want; })) want = rows.length ? rows[0].name : null;
    el.sel.innerHTML = rows.length ? rows.map(function (r) { return '<option value="' + U.esc(r.name) + '"' + (r.name === want ? ' selected' : '') + '>' + U.esc(r.name) + (r.publishing ? ' (publishing)' : '') + '</option>'; }).join('')
                                   : '<option value="">none</option>';
    if (want !== S.stream || !S.timer) select(want);
  }

  function select(name) {
    S.stream = name; S.status = null; S.preview = null; S.output = null;
    U.store.set('live.' + U.app, name || '');
    if (S.timer) { S.timer.stop(); S.timer = null; }
    layout(); update();
    if (!name) return;
    var p = U.streamPath(U.app, name), mine = name, lastEnc = null;
    S.timer = U.every(2000, function () {
      if (S.stream !== mine) return null;
      if (U.current !== 'uo-live') { if (S.player) { S.player.stop(); S.player = null; S.laidOut = null; } return null; }
      return U.api.get(p + '/status').then(function (st) {
        if (S.stream !== mine) return null;
        S.status = st;
        var enc = ((st.transcoder && st.transcoder.encodes) || []).map(function (e) { return e.name; }).join('|');
        var get = enc !== lastEnc ? U.api.get(p + '/preview').then(function (pv) { S.preview = pv; }, function () {}) : Promise.resolve();
        lastEnc = enc;
        return get.then(function () {
          if (S.stream !== mine) return;
          var want = S.stream + '|' + carrying().map(function (x) { return x.stream; }).join(',');
          if (want !== S.laidOut) layout();
          update();
        });
      }, function (e) {
        // the status could not be read: say so instead of loading forever
        var c = S.stream === mine && el.box.querySelector('[data-checks]');
        if (c) c.innerHTML = '<p class="err">The status of ' + U.esc(mine) + ' could not be read: ' + U.esc(e.message) + '</p>';
      });
    });
  }

  U.views['uo-live'] = {
    init: function (section) {
      el.sel = section.querySelector('[data-live-stream]');
      el.box = section.querySelector('[data-live]');
      el.sel.onchange = function () { select(el.sel.value || null); };
      S.stream = null; S.timer = null; S.laidOut = null;
      U.onShutdown(function () { if (S.player) S.player.stop(); });
    },
    app: function () { S.stream = null; },
    show: function () { if (U.app) choices(); },
    state: function () { if (U.current === 'uo-live') choices(); }
  };
})();
