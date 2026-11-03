/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio stream view: how one stream reaches viewers (input, template, outputs), a preview of one output, the
   addresses players open it at, and the template's actions (apply or change it with a dry run, stop using it) on the
   template's menu. The overlay is the template's: it is changed in the template, not here. The save bar covers only
   the dry run. Unsaved edits to the same overlay or template in their editors are linked, never saved from here. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var box = null, el = {};
  // per stream, in memory: { dry: { template, plan, error } or null }
  var mem = {};
  var S = { name: null, status: null, preview: null, timer: null, out: null, watching: false, player: null, saving: false, lastEnc: null, saved: null, ptype: null };

  function m(name) { return mem[name || S.name] || (mem[name || S.name] = { dry: null }); }
  function row() { return U.streamRow(S.name) || { name: S.name, publishing: false, template: null, own: false, added: true }; }
  function tpl() { return U.templateOfStream(row()); }
  function fileName(r) { return r.template ? String(r.template).replace(/\.xml$/i, '') : null; }
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
      '<span class="spacer"></span>' + (r.publishing && n != null ? '<span class="small muted" data-svw>' + U.viewersText(n) + '</span>' : '') +
      (r.publishing ? '<button type="button" class="btn sm" data-write data-restart>' + U.icon('refresh') + 'Restart ' + U.esc(r.name) + '</button>' : '') +
      U.rowMenuHtml('stream:' + r.name, 'Actions for ' + r.name, streamItems(r));
    var b = el.head.querySelector('[data-restart]');
    if (b) b.onclick = function () { U.restartStream(r.name, false, r.restartNeeded ? 'Its template builds other outputs since it started; they take effect now. The overlay stays as it is.' : null); };
  }
  // what a stream's menu offers, here and in the list
  function streamItems(r) {
    var base = U.baseOfStream(r);
    return [['tpl', r.template ? 'Change template...' : 'Apply a template...'], ['stop', 'Stop using ' + (r.own && U.copyBase(r) ? U.copyBase(r) : 'its template') + '...', false, !r.own],
      '-', ['dup', 'Duplicate...'], ['del', 'Delete...', true, r.publishing || (!r.own && !r.added)]];
  }
  U.streamItems = function (name) { return streamItems(U.streamRow(name) || { name: name, added: true }); };
  function renderPipe() {
    var r = row(), t = tpl(), base = U.baseOfStream(r), ov = r.overlay ? U.overlayEntry(r.overlay) : null, outs = outputs();
    var dec = S.status && S.status.transcoder && S.status.transcoder.decoderWidth ? S.status.transcoder.decoderWidth + ' x ' + S.status.transcoder.decoderHeight : null;
    if (!S.out || !outs.some(function (o) { return o.name === S.out; })) S.out = (outs.filter(function (o) { return o.carries; })[0] || outs[0] || {}).name || null;
    el.pipe.innerHTML = '<div class="pn pn-in"><span class="nk">Input</span><span class="ln"><b>' + (r.publishing ? (r.pulled ? 'Pulled stream' : r.arrives === 'server' ? 'Published inside WSE' : 'Pushed stream') : 'Waiting') + '</b></span>' +
        '<span class="ln">' + (r.publishing ? dec || 'Publishing' : 'No encoder connected') + '</span></div><span class="ar">' + U.icon('arrow') + '</span>' +
      '<div class="pn pn-tpl"><span class="nk">Template' + (r.template ? U.rowMenuHtml('tpl:' + r.name, 'Template actions for ' + r.name, [['tpl', 'Change template...'], ['stop', 'Stop using it...', true, !r.own], '-', ['open', 'Open ' + (base || fileName(r))]]) : '') + '</span>' + (r.template ?
        '<span class="ln"><button type="button" class="linkbtn" data-go="template:' + U.esc(r.own ? fileName(r) : base || fileName(r)) + '"><b>' + U.esc(r.template) + '</b></button><span class="faint">' + (r.own ? 'copy of ' + U.esc(base || '') : 'shared') + (r.restartNeeded ? ', restart pending' : '') + '</span></span>' +
        // the way to give the stream another template is on the card, not only in its menu (said by Alex, 2026-11-03)
        (U.readOnly ? '' : '<button type="button" class="btn sm" data-write data-applytpl>Change template</button>') +
        (r.vif ? '<span class="ln faint">A VIF template; no overlay</span>' : ov ? '<button type="button" class="ovbadge" data-go="overlay:' + U.esc(ov.name) + '">' + U.icon('overlay') + U.esc(ov.name) + ', ' + (ov.pages || 0) + ' layer' + (ov.pages === 1 ? '' : 's') + '</button>' :
          r.overlay ? '<span class="ln"><span class="dot bad"></span>' + U.esc(r.overlay) + ' does not exist</span>' : '<span class="ln faint">' + (t && !t.dynamicOverlays ? 'Cannot carry an overlay yet' : 'No overlay') + '</span>')
        : '<span class="ln"><b>None</b></span><span class="ln faint">Not transcoded</span>' + (U.readOnly ? '' : '<button type="button" class="btn sm" data-applytpl>Apply a template</button>')) + '</div>' +
      '<span class="ar">' + U.icon('arrow') + '</span><div class="pn-outs" role="group" aria-label="Outputs, as configured; pick one to watch and check">' + (outs.length ? outs.map(function (o) {
        return '<button type="button" class="pn" aria-pressed="' + (o.name === S.out) + '" data-out="' + U.esc(o.name) + '"><span class="nk">Output</span><span class="ln"><b>' + U.esc(o.name) + '</b></span>' +
          '<span class="ln"><span class="dot"></span>' + U.esc(U.configuredText(o)) + '</span></button>';
      }).join('') : '<div class="pn"><span class="nk">Outputs</span><span class="ln faint">' + (r.template ? 'Known when the transcoder starts' : 'None') + '</span></div>') + '</div>';
    el.pipe.querySelectorAll('[data-out]').forEach(function (b) { b.onclick = function () { pickOut(b.getAttribute('data-out')); }; });
    var ap = el.pipe.querySelector('[data-applytpl]');
    if (ap) ap.onclick = function () { openDry(); };
  }
  function pickOut(name) {
    if (S.out === name) return;
    S.out = name;
    el.pipe.querySelectorAll('[data-out]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-out') === name)); });
    renderWatchBar(); renderSide();
  }

  // one sentence on what viewers of the picked output get
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

  /* ---------- the side column: other screens' unsaved work, what viewers get, where players open it ---------- */
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
    writeIf(el.scope, scopeHtml());
    var v = oo && (oo.passthrough || oo.audio) ? ['', U.esc(o) + (oo.audio ? ' is audio only' : ' is the stream as it arrives') + ', so no overlay is drawn on it.'] : verdict();
    writeIf(el.why, '<p class="verdict">' + (v[0] ? '<span class="dot ' + v[0] + '"></span>' : '') + '<span>' + v[1] + '</span></p>' +
      (r.restartNeeded ? '<p class="small warn-text" style="margin-top:6px">Its template builds other outputs since it started; they take effect when ' + U.esc(r.name) + ' restarts.</p>' : ''));
    renderPlay();
  }

  /* ---------- playback addresses: each output and adaptive group, for the kinds the application serves ---------- */
  // HLS and MPEG-DASH when the application lists their HTTP streamers; RTMP on the same streaming port
  var KINDS = [['cupertinostreaming', 'HLS', '/playlist.m3u8'], ['mpegdashstreaming', 'MPEG-DASH', '/manifest.mpd'], ['rtmp', 'RTMP', '']];
  function playHtml() {
    var pv = S.preview, r = row();
    if (!pv) return '<p class="small faint">' + (r.publishing || r.template ? 'Reading the playback addresses...' : 'Known once ' + U.esc(r.name) + ' is transcoded.') + '</p>';
    var ports = pv.ports || {}, port = ports.http || ports.https, scheme = ports.http ? 'http' : 'https', host = window.location.hostname, app = pv.application || U.app;
    if (!port) return '<p class="small faint">The application has no streaming port to play from.</p>';
    var kinds = KINDS.filter(function (k) { return k[0] === 'rtmp' || (pv.streamers || []).indexOf(k[0]) >= 0; });
    if (!kinds.some(function (k) { return k[0] === S.ptype; })) S.ptype = kinds[0][0];
    var k = kinds.filter(function (x) { return x[0] === S.ptype; })[0];
    var url = function (name) { return k[0] === 'rtmp' ? 'rtmp://' + host + ':' + port + '/' + app + '/' + name : scheme + '://' + host + ':' + port + '/' + app + '/' + name + k[2]; };
    var outs = outputs().map(function (x) { return [x.name, x.stream, x.name === S.out]; });
    var t = tpl(), groups = (t && t.groups || []).map(function (g) { return [g.name, 'ngrp:' + r.name + '_' + g.name, false, true]; });
    var rows = outs.concat(k[0] === 'rtmp' ? [] : groups);
    if (!rows.length) rows = [[r.name, r.name, true]];
    return '<div class="seg play-k" role="radiogroup" aria-label="Playback type">' + kinds.map(function (x) {
        return '<button type="button" role="radio" data-ptype="' + x[0] + '" aria-checked="' + (x[0] === S.ptype) + '">' + x[1] + '</button>'; }).join('') + '</div>' +
      '<ul class="play-l">' + rows.map(function (x) {
        var u = url(x[1]);
        return '<li' + (x[2] ? ' class="sel"' : '') + '><span class="pl-n">' + U.esc(x[0]) + (x[3] ? '<span class="bdg">adaptive</span>' : '') + '</span><code title="' + U.esc(u) + '">' + U.esc(u) + '</code>' +
          '<button type="button" class="btn sm icon ghost" data-copyurl="' + U.esc(u) + '" aria-label="Copy the ' + U.esc(k[1]) + ' address of ' + U.esc(x[0]) + '" title="Copy">' + U.icon('copy') + '</button></li>';
      }).join('') + '</ul>' +
      '<p class="small faint">Addresses on this server as you reach it' + (k[0] === 'rtmp' ? '' : '; viewers outside your network may need its public name') + '.</p>';
  }
  // writes html into node only when it differs from what was written last, so a poll that changes nothing keeps a
  // selection, the focus and the scroll position
  function writeIf(node, html) { if (node.__uoHtml === html) return false; node.__uoHtml = html; node.innerHTML = html; return true; }
  function renderPlay() {
    if (!writeIf(el.play, '<div class="sec-h">Playback' + U.tip('Where a player opens each output of ' + S.name + '. Adaptive groups let a player switch between outputs as bandwidth allows. Types are the ones ' + U.app + ' serves.') + '</div>' + playHtml())) return;
    el.play.querySelectorAll('[data-ptype]').forEach(function (b) { b.onclick = function () { S.ptype = b.getAttribute('data-ptype'); renderPlay(); }; });
    el.play.querySelectorAll('[data-copyurl]').forEach(function (b) { b.onclick = function () { copyText(b.getAttribute('data-copyurl')); }; });
  }
  // the Manager is often served over plain http, where the clipboard API is not offered
  function copyText(text) {
    var done = function () { U.toast('Copied ' + text); };
    if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done, function () { U.toast('Not copied. Select the address and copy it.', { level: 'bad' }); }); return; }
    var ta = document.createElement('textarea');
    ta.value = text; ta.setAttribute('readonly', ''); ta.style.position = 'fixed'; ta.style.opacity = '0';
    U.root.appendChild(ta); ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    ta.remove();
    if (ok) done(); else U.toast('Not copied. Select the address and copy it.', { level: 'bad' });
  }

  /* ---------- use a template on this stream: a dry run, written only on Save ---------- */
  function candidates() {
    // templates carrying an overlay, without the streams' own copies
    var out = [];
    (U.overlays || []).forEach(function (o) { (o.templates || []).forEach(function (t) { if (!U.copyBase(U.templateEntry(t) || {}) && out.indexOf(t) < 0) out.push(t); }); });
    return out.sort();
  }
  function openDry(pick) {
    var list = candidates(), r = row();
    if (!list.length) { U.toast('No template carries an overlay yet. Make one in Templates first.', { level: 'bad' }); return; }
    var want = pick || (m().dry && m().dry.template) || (r.own ? U.copyBase(r) : null);
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
    el.dry.innerHTML = '<div class="dry"><div class="dry-h"><b>Give ' + U.esc(r.name) + ' its own copy of</b><select data-dtpl aria-label="Template">' + list.map(function (x) {
        return '<option value="' + U.esc(x) + '"' + (x === d.template ? ' selected' : '') + '>' + U.esc(x) + (r.own && U.copyBase(r) === x ? ' (current)' : '') + '</option>'; }).join('') + '</select>' +
      '<span class="small faint">Dry run, nothing is written until you save</span><span class="spacer"></span><button type="button" class="btn sm ghost icon" data-dclose aria-label="Close dry run">' + U.icon('close') + '</button></div>' +
      // a stream runs a template named after it, else the application's; so "using" a master is having a copy of it
      '<p class="small muted">WSE runs a template named after the stream when there is one. The copy stays linked to ' + U.esc(d.template) + ': when ' + U.esc(d.template) + ' changes, update the copy from Templates.</p>' +
      '<table class="tbl kvt"><tbody>' + rows.map(function (x) { return '<tr><td>' + x[0] + '</td><td>' + x[1] + '</td></tr>'; }).join('') + '</tbody></table></div>';
    el.dry.querySelector('[data-dtpl]').onchange = function (e) { d.template = e.target.value; plan(); };
    el.dry.querySelector('[data-dclose]').onclick = function () { m().dry = null; renderDry(); renderBar(); U.changed(); };
  }

  /* ---------- the save bar: the dry run ---------- */
  function changes() {
    var r = row(), d = m().dry, out = [];
    if (d) out.push('Use ' + d.template + ' on ' + r.name);
    return out;
  }
  function impact() {
    var r = row(), d = m().dry, n = U.viewers(r);
    if (d) {
      var t = U.templateEntry(d.template), removes = r.publishing && r.overlay && (!t || t.overlay !== r.overlay);
      return (removes ? r.overlay + ' leaves ' + r.name + ' now. ' : 'Overlay ' + (t && t.overlay !== (r.overlay || null) ? 'changes now' : 'unchanged') + '. ') +
        (r.publishing ? 'Outputs wait for a restart' + (n != null ? ' (' + U.viewersText(n) + ')' : '') + '.' : 'Applies when it starts publishing.') + (removes || r.own ? ' You confirm before it is written.' : '');
    }
    return '';
  }
  function renderBar() {
    var d = m().dry;
    U.saveBar(el.bar, { changes: changes(), impact: impact(), saving: S.saving, problem: d && d.error ? d.error : d && !d.plan ? 'Checking what changes...' : null,
      saveLabel: d ? 'Use on ' + S.name : 'Save', saved: S.saved && S.saved.stream === S.name ? S.saved : null, onSave: save, onDiscard: discard });
  }
  function discard() { var x = m(); x.dry = null; render(); U.changed(); }
  function save() {
    var r = row(), x = m();
    if (S.saving || !x.dry) return;
    return saveUse(r, x.dry);
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

  /* ---------- a stream's menu: its template, a duplicate, deleting it ---------- */
  // a copy of the stream: the new name gets its own copy of the same template, after a dry run says what that changes
  function duplicate(name) {
    var r = U.streamRow(name) || { name: name }, base = U.baseOfStream(r);
    U.choose('Duplicate ' + name, '<div class="fld"><label class="f" for="ovs-dup-n">New stream name</label><input id="ovs-dup-n" type="text" data-dupn value="' + U.esc(name + '-2') + '" spellcheck="false" autocomplete="off">' +
      '<div class="hint">The name an encoder publishes it as. ' + (base ? 'It gets its own copy of ' + U.esc(base) + ', so it shows the same overlay and outputs.' : U.esc(name) + ' has no template, so only the name is added.') + '</div></div><p class="err" data-duperr></p>',
      [{ label: 'Cancel', value: null }, { label: 'Duplicate', value: 'go', kind: 'primary' }]).then(function (v) {
        if (v !== 'go') return;
        var n = (U.dupName || '').trim();
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(n) || n.indexOf('..') >= 0) { U.toast('Stream name: letters, digits, dot, dash and underscore.', { level: 'bad' }); return; }
        if (U.streamRow(n) || U.templateEntry(n)) { U.toast(n + ' is a stream or template name already.', { level: 'bad' }); return; }
        if (!base) { if (U.added.indexOf(n) < 0) U.added.push(n); U.go('stream:' + n, { tab: 'streams' }); return; }
        var path = U.appPath() + '/templates/assign';
        U.api.get(path + '?scope=stream&template=' + U.enc(base) + '&stream=' + U.enc(n)).then(function (p) {
          var app = p.transcoderTurnsOn ? '<p><b>This switches the transcoder on for ' + U.esc(U.app) + '</b>, which needs an application restart.</p>' :
            p.templatesBefore !== p.templatesAfter ? '<p>' + U.esc(U.app) + ' is set to look for a template named after each stream first. That applies after an application restart.</p>' : '';
          var ask = app ? U.confirm('Duplicate ' + name + ' as ' + n + '?', '<p>Writes <span class="mono">' + U.esc(n) + '.xml</span>, a copy of ' + U.esc(base) + '.</p>' + app, 'Duplicate', false) : Promise.resolve(true);
          return ask.then(function (ok) {
            if (!ok) return null;
            return U.api.post(path, { scope: 'stream', template: base, stream: n }).then(function (res) {
              if (U.added.indexOf(n) < 0) U.added.push(n);
              U.toast(n + ' gets its own copy of ' + base + ' when it publishes.' + (res.restartRequired ? ' Restart ' + U.app + ' for the transcoder setting to take effect.' : ''));
              return U.refreshState(true).then(function () { U.go('stream:' + n, { tab: 'streams' }); });
            });
          });
        }).catch(function (e) { U.banner('dup', 'bad', 'Not duplicated.', e.message); });
      });
    var all = U.root.querySelectorAll('.modal'), dlg = all[all.length - 1], inp = dlg && dlg.querySelector('[data-dupn]');
    U.dupName = inp ? inp.value : '';
    if (inp) { inp.oninput = function () { U.dupName = inp.value; }; inp.focus(); inp.select(); }
  }
  // a stream that is not publishing: its own copy is deleted and the name forgotten here
  function deleteStream(name) {
    var r = U.streamRow(name) || { name: name, added: true };
    if (r.publishing) { U.toast(name + ' is publishing. Stop it at its encoder first; its template can be stopped from its menu.', { level: 'bad' }); return; }
    var forget = function () { U.added = (U.added || []).filter(function (x) { return x !== name; }); };
    // a stream's copy is deleted from the template list too; the page stays in the list it was opened from
    var tab = U.current() === 'template' ? 'templates' : 'streams', near = U.neighborOf((tab === 'templates' ? 'template:' : 'stream:') + name);
    var here = (U.current() === 'stream' || U.current() === 'template') && U.view.id === name;
    if (!r.own) { forget(); U.toast('Removed ' + name + ' from this list.'); U.changed(); return U.refreshState(true).then(function () { if (here) U.stayIn(tab, near); }); }
    U.confirm('Delete ' + name + '?', '<p>Deletes <span class="mono">' + U.esc(r.template) + '</span>, ' + U.esc(name) + '\'s own copy' + (U.copyBase(r) ? ' of ' + U.esc(U.copyBase(r)) : '') + '. ' +
      'If it publishes again it gets its template from the application\'s template order.</p><p>This cannot be undone.</p>', 'Delete', true).then(function (ok) {
        if (!ok) return;
        U.api.v2del('/transcoder/templates/' + U.enc(fileName(r))).then(function () {
          forget(); delete mem[name];
          U.toast('Deleted ' + name + '\'s template copy.');
          return U.refreshState(true).then(function () { if (here) U.stayIn(tab, near); });
        }, function (e) { U.banner('delstream', 'bad', 'Not deleted.', e.message); });
      });
  }
  function onStream(name, then) { if (U.current() !== 'stream' || S.name !== name) U.go('stream:' + name, { tab: 'streams' }); then(); }
  // from Templates: give a stream its own copy of a master, starting with the dry run on the stream
  U.useTemplateOn = function (stream, template) { onStream(stream, function () { openDry(template); }); };
  U.rowActions.stream = function (name, act) {
    if (act === 'tpl') onStream(name, openDry);
    else if (act === 'stop') onStream(name, stopUsing);
    else if (act === 'dup') duplicate(name);
    else if (act === 'del') deleteStream(name);
  };
  U.rowActions.tpl = function (name, act) {
    var r = U.streamRow(name) || { name: name };
    if (act === 'tpl') onStream(name, openDry);
    else if (act === 'stop') onStream(name, stopUsing);
    else if (act === 'open') U.go('template:' + (r.own ? fileName(r) : U.baseOfStream(r) || fileName(r)));
  };

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
        if (S.name === name) el.why.innerHTML = '<p class="err">The status of ' + U.esc(name) + ' could not be read: ' + U.esc(e.message) + '</p>';
      });
    });
  }

  U.screens.stream = {
    init: function (b) {
      box = b;
      box.innerHTML = '<div class="pnl"><div class="sh" data-sh></div><div class="pipe" role="group" data-pipe></div><div data-dry></div>' +
        '<div class="mon"><div><div class="mon-h" data-monh></div><div data-watch></div></div><div class="side"><div class="scope-lines" data-scope></div><div data-why></div><div data-play class="play"></div></div></div></div>' +
        '<div data-bar hidden></div>';
      el = { head: box.querySelector('[data-sh]'), pipe: box.querySelector('[data-pipe]'), dry: box.querySelector('[data-dry]'), monh: box.querySelector('[data-monh]'), watch: box.querySelector('[data-watch]'),
        scope: box.querySelector('[data-scope]'), why: box.querySelector('[data-why]'), play: box.querySelector('[data-play]'), bar: box.querySelector('[data-bar]') };
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
    viewers: function () {
      if (U.current() !== 'stream' || !S.name) return;
      var c = el.head.querySelector('[data-svw]'), n = U.viewers(row());
      if (c && n != null) c.textContent = U.viewersText(n);
    },
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
        var x = mem[n];
        if (x.dry) out.push({ label: n + ': use ' + x.dry.template, detail: 'dry run', go: 'stream:' + n });
      });
      return out;
    }
  };
})();
