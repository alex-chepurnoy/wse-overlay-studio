/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio template editor: every transcoder template on the server. A master template, made here, opens in the
   builder: the hardware on top, then a Decode box (the source, and the overlays drawn on it before scaling) and an
   Encode box (the outputs, the overlays drawn on chosen outputs, and the adaptive groups). A template carries up to
   four overlays (plan 3.9), each on the input or on chosen outputs. One switched on in place gets the overlay form; a
   stream's own copy of a master is shown read-only with Update from its master; any other is shown read-only with its
   verdict, "Use with an overlay", and "New master from this one". Edits stay in a draft kept in memory per template
   until Save. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var el = {};
  // drafts not in view, by template name ('' for a new one): { draft, form, base, known }
  var mem = {};
  var S = { sel: null, kind: null, draft: null, form: null, base: null, known: null, theirs: null, conflict: false, saving: false, open: {}, lib: null, libLoading: false,
    advOpen: false, pick: false, use: null, savedNote: null };
  var IN_PLACE = 'overlays switched on in place';
  // heights and default bitrates: 720, 360, 234 and 160 from WSE's transcode.xml; 1080 and 540 are Overlay Studio's choice
  var SIZES = [[1080, '1080p', 5000000, 'high'], [720, '720p', 3000000, 'high'], [540, '540p', 1500000, 'high'],
    [360, '360p', 365000, 'high'], [234, '240p', 145000, 'baseline'], [160, '160p', 105000, 'baseline']];

  function vh() { return U.vhostPath(); }
  function inPlace(t) { return (t.description || '').indexOf(IN_PLACE) >= 0; }
  function all() { return (U.templates || []).filter(function (t) { return t && !t.error && !U.copyBase(t); }); }
  function find(name) { return all().filter(function (t) { return t.name === name; })[0]; }
  // a master or any other template, or a stream's own copy of one
  function findAny(name) { var t = U.templateEntry(name); return t && !t.error ? t : null; }
  function group(t) { return t.vif ? 'na' : (tplSlots(t).length || t.managed) ? 'mine' : 'other'; }
  // the overlays a template carries, as the validator reads them, in slot order: {name, encodes, slot}, encodes empty
  // for every output (drawn on the input)
  var MAX_OVERLAYS = 4;
  function tplSlots(t) {
    var s = t.overlays && t.overlays.length ? t.overlays : t.overlay ? [{ name: t.overlay, encodes: t.overlayEncodes || [], slot: 1 }] : [];
    return s.slice().sort(function (a, b) { return a.slot - b.slot; });
  }
  // the overlays an output of t carries, by name
  function carriedOn(t, e) {
    if (!e.canCarryOverlay) return [];
    return tplSlots(t).filter(function (x) { return !(x.encodes || []).length || x.encodes.indexOf(e.name) >= 0; }).map(function (x) { return x.name; });
  }
  function size(h) { return SIZES.filter(function (s) { return s[0] === h; })[0]; }
  function carriers(t) { return (t.encodes || []).filter(function (e) { return e.canCarryOverlay; }); }
  function verdict(t) {
    if (!carriers(t).length) return { cls: '', text: 'Cannot carry an overlay', long: 'It has no enabled output that encodes video, so there is nothing to draw an overlay on.' };
    if (!t.dynamicOverlays) return { cls: 'warn', text: 'Needs overlays switched on', long: 'Dynamic overlays are off in this template, so an overlay cannot show on its streams yet.' };
    return { cls: 'on', text: 'Ready for an overlay', long: 'Dynamic overlays are on, and it has outputs an overlay can be drawn on.' };
  }
  function num(v, d) { var n = parseInt(v, 10); return isNaN(n) ? d : n; }
  function ro() { return !!U.readOnly; }

  /* ---------- the builder's model ---------- */
  // the whole template's hardware
  var HW = [['cpu', 'CPU', 'Decode, scale and encode on the server\'s CPU.'],
    ['nvidia', 'NVIDIA', 'NVIDIA GPU. Needs one on the server. With an overlay, frames are copied off the card to draw it, which costs more.'],
    ['intel', 'Intel', 'Intel Quick Sync. Needs it on the server. With an overlay, frames are copied off the chip to draw it, which costs more.'],
    ['netint', 'NETINT', 'NETINT Quadra card. Needs one on the server. With an overlay, frames are copied off the card to draw it, which costs more.']];
  // what each choice runs decode, scale and encode on: TemplateBuilder.Hardware (NVCUVID_EVA, CUDA_EVA, NVENC_EVA for
  // NVIDIA; Intel keeps the default scaler). VP8 and VP9 always encode on the CPU.
  var HWS = { cpu: ['CPU', 'CPU', 'CPU'], nvidia: ['NVIDIA NVDEC', 'NVIDIA CUDA', 'NVIDIA NVENC'], intel: ['Intel Quick Sync', 'CPU', 'Intel Quick Sync'], netint: ['NETINT', 'NETINT', 'NETINT'] };
  function hwOf(d) { return HWS[d.hardware] || HWS.cpu; }
  function outHw(d, o) {
    if (o.kind !== 'video') return '';
    var h = hwOf(d), enc = o.codec === 'VP8' || o.codec === 'VP9' ? 'CPU' : h[2];
    return o.height === 0 && !o.region ? 'Encode ' + enc : h[1] === enc ? 'Scale, encode ' + enc : 'Scale ' + h[1] + ', encode ' + enc;
  }
  var FPS = 30; // frames per second assumed for keyframes in seconds when the source's is not known
  function base() {
    return { name: '', kind: 'video', enabled: true, height: 720, width: 1280, fit: 'fit-height', region: null, skip: 0,
      codec: 'H.264', profile: 'high', videoBitrate: 3000000, rateControl: 'default', peakBitrate: 0, speed: 'default', keyFollow: false, keyInterval: 60,
      hdrToSdr: false, audio: 'aac', audioBitrate: 96000, channels: 0, sampleRate: 0, captions: true, params: [] };
  }
  function out(h) {
    var s = size(h) || [h, h + 'p', 3000000, 'high'], o = base();
    o.name = s[1]; o.height = h; o.width = Math.round(h * 16 / 9 / 2) * 2; o.videoBitrate = s[2]; o.profile = s[3];
    return o;
  }
  function original() { var o = base(); o.name = 'source'; o.kind = 'passthrough'; o.audio = 'passthru'; o.audioBitrate = 0; return o; }
  function audioOnly() { var o = base(); o.name = 'audio'; o.kind = 'audio'; return o; }
  var PRESETS = [
    ['Original + overlay copy', function () { var o = out(0); o.name = 'overlay'; o.height = 0; o.videoBitrate = 4000000; o.profile = 'main'; o.audio = 'passthru'; o.audioBitrate = 0; return [original(), o]; }],
    ['1080p, 720p, 360p', function () { return [out(1080), out(720), out(360)]; }],
    ['720p, 360p', function () { return [out(720), out(360)]; }],
    ['Single 720p', function () { return [out(720)]; }],
    ['Audio only', function () { return [audioOnly()]; }]
  ];
  // WSE's own transcode.xml has "all" and "mobile" (the two smallest)
  function defaultGroups(outs) {
    var vids = outs.filter(function (o) { return o.enabled && o.kind !== 'audio'; });
    return vids.length > 1 ? [{ name: 'abr', members: vids.map(function (o) { return { output: o.name, only: '' }; }) }] : [];
  }
  // a new template starts with no overlay: they are added from the Overlays sections (plan 4.5)
  function blank() {
    var outs = PRESETS[2][1]();
    return { isNew: true, name: '', overlays: [], hardware: 'cpu', deinterlace: false, gpuId: -1, minFrameRate: 0, outputs: outs, groups: defaultGroups(outs), images: [], preset: 2 };
  }
  // for the task's step 2: a preset's outputs, and the spec of a new template carrying one overlay
  U.presetOutputs = function (i) { return PRESETS[i][1](); };
  U.templateSpecFor = function (name, overlay, preset) {
    var d = blank();
    d.name = name; d.outputs = PRESETS[preset][1](); d.groups = defaultGroups(d.outputs); d.preset = preset;
    // its one overlay on the input, so every video output shows it
    if (overlay) d.overlays = [{ name: overlay, stage: 'input', outputs: [] }];
    return spec(d);
  };
  // what the editor shows for an existing template, from the validator's reading in the live state
  function fromTemplate(t) {
    var outs = (t.encodes || []).map(function (e) {
      var codec = String(e.codec || ''), kind = /^passthru$/i.test(codec) ? 'passthrough' : /^disable/i.test(codec) ? 'audio' : 'video';
      var ac = String(e.audioCodec || '').toLowerCase(), o = base();
      var rect = (e.sourceRectangle || '').split(',').map(Number);
      o.name = e.name; o.kind = kind; o.enabled = !!e.enabled;
      o.height = e.fitMode === 'match-source' ? 0 : num(e.height, 0); o.width = num(e.width, 0) || Math.round(o.height * 16 / 9 / 2) * 2;
      o.fit = e.fitMode && e.fitMode !== 'match-source' ? e.fitMode : 'fit-height';
      o.region = rect.length === 4 && rect.every(function (x) { return !isNaN(x); }) ? { x: rect[0], y: rect[1], w: rect[2], h: rect[3] } : null;
      o.skip = num(e.skipFrameCount, 0);
      o.codec = /265/.test(codec) ? 'H.265' : /^vp8$/i.test(codec) ? 'VP8' : /^vp9$/i.test(codec) ? 'VP9' : 'H.264';
      o.profile = (e.profile || 'main').toLowerCase(); o.videoBitrate = num(e.bitrate, 3000000);
      o.rateControl = e.rateControl || 'default'; o.peakBitrate = num(e.peakBitrate, 0); o.speed = e.speed || 'default';
      o.keyFollow = !!e.followSource; o.keyInterval = num(e.keyInterval, 60); o.hdrToSdr = !!e.convert8bit;
      o.audio = ac === 'aac' ? 'aac' : ac === 'opus' ? 'opus' : ac === 'disable' ? 'disable' : 'passthru';
      o.audioBitrate = o.audio === 'aac' || o.audio === 'opus' ? num(e.audioBitrate, 96000) : 0;
      o.channels = num(e.audioChannels, 0); o.sampleRate = num(e.audioSampleRate, 0); o.captions = e.captions !== false;
      // the parameters the plain controls stand for are not listed again as expert ones
      var own = { 'mainconcept.bit_rate_mode': 1, 'mainconcept.max_bit_rate': 1, 'mainconcept.performance_level': 1 };
      o.params = Object.keys(e.parameters || {}).filter(function (k) { return !own[k]; }).map(function (k) { return { name: k, value: e.parameters[k] }; });
      return o;
    });
    var ovs = tplSlots(t).map(function (x) { return { name: x.name, stage: (x.encodes || []).length ? 'outputs' : 'input', outputs: (x.encodes || []).slice() }; });
    return { isNew: false, name: t.name, overlays: inStageOrder(ovs), hardware: t.hardware || 'cpu', deinterlace: !!t.deinterlace, gpuId: num(t.gpuId, -1),
      minFrameRate: parseFloat(t.minFrameRate) || 0,
      images: (t.images || []).map(function (l) {
        return { file: l.file, where: l.where === 'outputs' ? 'outputs' : 'all', outputs: (l.outputs || []).slice(), x: num(l.x, 0), y: num(l.y, 0), width: num(l.width, 0),
          height: num(l.height, 0), align: l.align || 'left,top', opacity: num(l.opacity, 100), refresh: !!l.refresh };
      }),
      outputs: outs, preset: null, groups: (t.groups || []).map(function (g) {
        return { name: g.name, members: (g.members || []).map(function (m) { return { output: m.output, only: m.only || '' }; }) };
      }) };
  }
  // Decode overlays first, then Encode ones, each stage keeping its own order: list order is slot order (plan 3.9)
  function inStageOrder(list) {
    return list.filter(function (x) { return x.stage !== 'outputs'; }).concat(list.filter(function (x) { return x.stage === 'outputs'; }));
  }
  // whether an overlay entry draws on output o (a video output)
  function carries(x, o) { return x.stage !== 'outputs' || x.outputs.indexOf(o.name) >= 0; }
  // the output names an entry is saved with: none for the input, and none when it is on every video output, which
  // the module saves on the input too
  function encodesOf(x, vids) {
    if (x.stage !== 'outputs') return [];
    var on = x.outputs.filter(function (n) { return vids.indexOf(n) >= 0; });
    return on.length === vids.length ? [] : on;
  }
  function spec(d) {
    var vids = d.outputs.filter(function (o) { return o.kind === 'video' && o.enabled; }).map(function (o) { return o.name; });
    // slot 1 is carried by the outputs marked overlay, as the module reads it; slots 2 to 4 name their outputs
    var ovs = (d.overlays || []).filter(function (x) { return x.name; }), first = ovs[0];
    return { name: d.name, hardware: d.hardware, overlay: first ? first.name : null,
      overlays: ovs.slice(1).map(function (x, i) { return { name: x.name, encodes: encodesOf(x, vids), slot: i + 2 }; }), deinterlace: d.deinterlace, gpuId: d.gpuId, minFrameRate: d.minFrameRate || 0,
      images: (d.images || []).map(function (l) {
        return { file: l.file, where: l.where, outputs: l.where === 'outputs' ? l.outputs.filter(function (n) { return vids.indexOf(n) >= 0; }) : [], x: l.x, y: l.y,
          width: l.width, height: l.height, align: l.align, opacity: l.opacity, refresh: l.refresh };
      }),
      groups: d.groups.map(function (g) { return { name: g.name, members: g.members.filter(function (m) { return d.outputs.some(function (o) { return o.name === m.output && o.enabled; }); }) }; })
        .filter(function (g) { return g.members.length; }),
      outputs: d.outputs.map(function (o) {
        var v = o.kind === 'video';
        return { name: o.name, kind: o.kind, enabled: o.enabled, overlay: !!first && v && carries(first, o), height: o.height, width: o.width, fit: o.fit, region: v ? o.region : null,
          skip: v ? o.skip : 0, codec: o.codec, profile: o.profile, videoBitrate: o.videoBitrate, rateControl: v && o.codec === 'H.264' ? o.rateControl : 'default',
          peakBitrate: o.rateControl === 'vbr' ? (o.peakBitrate || Math.round(o.videoBitrate * 1.5)) : 0, speed: v && o.codec === 'H.264' ? o.speed : 'default',
          keyFollow: o.keyFollow, keyInterval: o.keyInterval, hdrToSdr: v && o.hdrToSdr, audio: o.audio, audioBitrate: o.audioBitrate, channels: o.channels,
          sampleRate: o.sampleRate, captions: o.captions, params: v ? o.params.filter(function (p) { return p.name; }) : [] };
      }) };
  }

  /* ---------- opening a template, and keeping a draft when another is shown ---------- */
  function stash() {
    var key = S.kind === 'new' ? '' : S.sel;
    if (key == null || !S.kind) return;
    if (changes()) mem[key] = { draft: S.draft, form: S.form, base: S.base, known: S.known };
    else delete mem[key];
  }
  function select(name) {
    stash();
    var t = name ? find(name) : null;
    S.conflict = false; S.theirs = null; S.pick = false; S.use = null; S.savedNote = null;
    if (!name) {
      S.sel = null; S.kind = 'new'; S.form = null; S.known = null;
      var k = mem[''];
      S.draft = k ? k.draft : blank();
      // a new template counts as unsaved work once it has a name or differs from where it started
      S.base = k ? k.base : JSON.stringify(spec(S.draft));
      return;
    }
    S.sel = name;
    if (!t) t = findAny(name);
    if (!t) { S.kind = null; S.draft = null; S.form = null; return; }
    var kept = mem[name];
    S.kind = U.copyBase(t) ? 'copy' : t.vif ? 'vif' : inPlace(t) ? 'inplace' : group(t) === 'mine' ? 'builder' : 'other';
    if (kept) {
      S.draft = kept.draft; S.form = kept.form; S.base = kept.base; S.known = kept.known;
      if (kept.known != null && t.modified !== kept.known) { S.conflict = true; S.theirs = t.modified; }
      return;
    }
    S.known = t.modified;
    loadFrom(t);
  }
  function loadFrom(t) {
    S.draft = S.kind === 'builder' ? fromTemplate(t) : null;
    S.form = S.kind === 'inplace' ? savedForm(t) : null;
    S.base = S.draft ? JSON.stringify(spec(S.draft)) : S.form ? JSON.stringify(S.form) : null;
  }
  // the editor's draft for another screen's link, or null
  function draftInfo(name) {
    if (ro()) return null;
    if (name === S.sel && S.kind !== 'new' && changes()) return describe();
    var k = mem[name];
    if (!k) return null;
    var cur = k.draft ? JSON.stringify(spec(k.draft)) : k.form ? JSON.stringify(k.form) : null;
    return cur && cur !== k.base ? ['unsaved edits'] : null;
  }

  /* ---------- live state ---------- */
  function onState() {
    if (S.kind === 'new') { renderHead(); renderUsedBy(); renderBar(); return; }
    var t = S.sel && findAny(S.sel);
    if (S.sel && !t) {
      if (changes()) U.toast('Template ' + S.sel + ' was deleted in another session.', { level: 'bad' });
      delete mem[S.sel]; S.sel = null; S.kind = null; U.stayIn('templates'); return;
    }
    if (t && S.known != null && t.modified !== S.known && !S.saving) {
      if (changes()) { S.conflict = true; S.theirs = t.modified; }
      else { S.known = t.modified; loadFrom(t); renderBody(); }
    }
    renderHead(); renderUsedBy(); renderBar();
  }

  /* ---------- head and Used by ---------- */
  function renderHead() {
    var t = S.sel && findAny(S.sel);
    if (S.kind === 'new') {
      el.head.innerHTML = '<h2>New master template</h2><span class="small faint">Shared by every application on this server. Streams run their own copies of it.</span>';
      return;
    }
    if (!t) { el.head.innerHTML = ''; return; }
    var origin = S.kind === 'copy' ? 'Stream copy of ' + U.esc(U.copyBase(t)) : t.shipped ? 'Ships with WSE' : inPlace(t) ? 'Your template, overlays switched on here' : t.managed || tplSlots(t).length ? 'Master template' : t.vif ? 'Video Intelligence' : 'Not made here';
    el.head.innerHTML = '<h2>' + U.esc(t.name) + '</h2><span class="bdg' + (S.kind === 'builder' ? ' on' : '') + '">' + origin + '</span><span class="mono faint">' + U.esc(t.template) + '</span>';
  }
  // who uses a master is in the list column beside it, with its copies and whether they still match
  function renderUsedBy() { el.used.innerHTML = ''; el.reach.innerHTML = ''; }
  // right after a save: streams with their own copy still run the old version. One dialog shows the two steps,
  // update the copy and restart, and does both on one answer; Used by keeps Update for later
  function offerCopies(name, copies) {
    var live = copies.filter(function (s) { return s.publishing; });
    var chips = function (list) { return list.map(function (s) { return '<span class="chip"><span class="dot ' + (s.publishing ? 'live' : '') + '"></span>' + U.esc(s.name) + '</span>'; }).join(''); };
    var app = live.some(function (s) { return s.arrives === 'server'; });
    return U.choose(copies.length > 1 ? 'These streams still run the old ' + name : copies[0].name + ' still runs the old ' + name,
      '<ol class="steps"><li><b>Update the copy</b><div class="dlg-streams">' + chips(copies) + '</div><span class="small muted">Copies ' + U.esc(name) + ' as saved now. The overlay changes at once.</span></li>' +
      (live.length ? '<li><b>Restart</b><div class="dlg-streams">' + chips(live) + '</div><span class="small muted">New outputs, layers and hardware start only then' + (app ? '. Streams published inside WSE restart with the application ' + U.esc(U.app) + '.' : ', a gap of a few seconds for viewers.') + '</span></li>' : '') + '</ol>',
      [{ label: 'Later', value: null }].concat(live.length ? [{ label: 'Update only', value: 'update' }, { label: 'Update and restart', value: 'both', kind: 'primary' }] : [{ label: 'Update', value: 'update', kind: 'primary' }])).then(function (v) {
        if (!v) return null;
        return U.api.post(vh() + '/templates/' + U.enc(name) + '/copies', { streams: copies.map(function (s) { return s.name; }) }).then(function (r2) {
          U.toast('Updated ' + (r2.updated || copies.map(function (s) { return s.name; })).join(', ') + '.');
          return U.refreshState(true).then(function () {
            if (v !== 'both') return null;
            // one application restart covers every stream published inside WSE
            var done = false;
            return live.reduce(function (p, s) {
              return p.then(function () { if (s.arrives === 'server') { if (done) return null; done = true; } return U.restartStream(s.name, true); });
            }, Promise.resolve());
          });
        }, function (e) { U.banner('tpl', 'bad', 'Not updated.', e.message); });
      });
  }
  // streams with their own copy keep the old version until updated; the overlay changes at once, outputs on a restart
  function updateCopies(name, streams) {
    var rows = streams.map(function (s) { return U.streamRow(s) || { name: s }; }), live = rows.filter(function (r) { return r.publishing; });
    U.confirm('Update ' + streams.join(', ') + ' from ' + name + '?', '<p>' + U.esc(streams.map(function (s) { return s + '.xml'; }).join(', ')) + ' take' + (streams.length === 1 ? 's' : '') + ' every output and setting of ' + U.esc(name) +
      ' as saved now. Anything changed in the copy by hand is replaced; the copy is not kept.</p>' +
      (live.length ? '<p>The overlay changes at once. New outputs need a restart, which is offered next:</p>' + live.map(U.restartLines).join('') : '<p>Not publishing now, so viewers see no gap.</p>'), 'Update copy', false).then(function (ok) {
        if (!ok) return;
        U.api.post(vh() + '/templates/' + U.enc(name) + '/copies', { streams: streams }).then(function (r2) {
          if (!(r2.live || []).length) U.toast('Updated ' + (r2.updated || streams).join(', ') + '. Each picks it up when it next starts publishing.');
          return U.refreshState(true).then(function () { return U.afterTemplateWrite(r2.live); });
        }, function (e) { U.banner('tpl', 'bad', 'Not updated.', e.message); });
      });
  }

  /* ---------- the body by kind ---------- */
  function renderBody() {
    var t = S.sel && findAny(S.sel);
    if (S.kind === 'copy') { renderCopy(t); return; }
    if (S.kind === 'new' || S.kind === 'builder') { if (ro() && S.kind === 'builder') renderOther(t); else renderBuilder(); return; }
    if (S.kind === 'inplace') { renderOverlayOnly(t); return; }
    if (S.kind === 'vif') { el.body.innerHTML = '<div class="note"><span class="dot"></span><div><b>A Video Intelligence template.</b> A stream runs a VIF template or a Overlay Studio one, not both, so this one cannot carry an overlay.</div></div>'; return; }
    if (t) { renderOther(t); return; }
    el.body.innerHTML = '<p class="muted">Pick a template in the list.</p>';
  }

  // the application's overlays; with taken, those the template carries already are shown but cannot be picked again
  function overlayOptions(cur, none, taken, own) {
    var names = (U.overlays || []).map(function (o) { return o.name; }), have = (taken || []).map(function (x) { return x.name; });
    if (cur && names.indexOf(cur) < 0) names.unshift(cur);
    return (none ? '<option value="">' + none + '</option>' : '') + names.map(function (n) {
      var used = n !== own && have.indexOf(n) >= 0;
      return '<option value="' + U.esc(n) + '"' + (n === cur ? ' selected' : '') + (used ? ' disabled' : '') + '>' + U.esc(n) + (used ? ' (already in this template)' : '') + '</option>'; }).join('');
  }
  function sizeLabel(o) {
    if (o.kind === 'passthrough') return 'Original';
    if (o.kind === 'audio') return 'Audio only';
    var s = size(o.height);
    return o.height === 0 ? 'Source size' : s ? s[1] : o.height + ' high';
  }
  function opts(list, cur) {
    return list.map(function (x) { return '<option value="' + U.esc(x[0]) + '"' + (String(x[0]) === String(cur) ? ' selected' : '') + (x[2] ? ' disabled' : '') + '>' + U.esc(x[1]) + '</option>'; }).join('');
  }
  var FITS = [['fit-height', 'Fit the height'], ['fit-width', 'Fit the width'], ['letterbox', 'Letterbox'], ['crop', 'Crop to fill'], ['stretch', 'Stretch']];
  function kbps(b) { return Math.round(b / 1000).toLocaleString() + ' kbps'; }
  function fpsLabel(skip) { return skip ? Math.round(FPS / (skip + 1)) + ' fps (1/' + (skip + 1) + ')' : 'same as the source'; }
  function audioText(o) { return o.audio === 'disable' ? 'off' : o.audio === 'passthru' ? 'passes through' : (o.audio === 'opus' ? 'Opus ' : 'AAC ') + kbps(o.audioBitrate) + (o.channels === 1 ? ', mono' : ''); }

  // one collapsible section of an output card; S.open remembers which are open, by output name
  function sec(o, key, title, summaryText, body) {
    var k = o.name + '/' + key, open = !!S.open[k];
    return '<div class="oc-sec' + (open ? ' open' : '') + '"><button type="button" class="oc-sh" data-sec="' + U.esc(k) + '" aria-expanded="' + open + '">' + title +
      '<span>' + summaryText + '</span></button>' + (open ? '<div class="oc-sb">' + body + '</div>' : '') + '</div>';
  }
  function audioBody(o, encodeOnly) {
    var enc = o.audio === 'aac' || o.audio === 'opus';
    return '<div class="oc-f"><label>Codec<select data-o="audio">' + opts([['aac', 'AAC'], ['opus', 'Opus']].concat(encodeOnly ? [] : [['passthru', 'Pass through'], ['disable', 'Off (video only)']]), o.audio) + '</select></label>' +
      (enc ? '<label>Bitrate (kbps)<input type="number" data-o="akbps" min="16" max="512" value="' + Math.round(o.audioBitrate / 1000) + '"></label>' +
        '<label>Channels<select data-o="channels">' + opts([[0, 'As the source'], [2, 'Stereo'], [1, 'Mono']], o.channels) + '</select></label>' +
        '<label>Sample rate<select data-o="sampleRate">' + opts([[0, 'As the source'], [48000, '48 kHz'], [44100, '44.1 kHz']], o.sampleRate) + '</select></label>' : '') + '</div>' +
      (enc && (o.channels || o.sampleRate) ? '<p class="hint key-note">Written as <span class="key">Audio/Resample</span>.</p>' : '');
  }
  function videoSummary(o) { return o.codec + ' ' + kbps(o.videoBitrate) + (o.rateControl === 'cbr' ? ', constant' : o.rateControl === 'vbr' ? ', variable' : ''); }

  // the picture an output encodes, drawn to scale against 1080p: a glance tells the ladder apart
  function resBox(o) {
    if (o.kind === 'audio') return '<span class="res res-a" aria-hidden="true">' + U.icon('audio') + '</span>';
    if (o.kind === 'passthrough') return '<span class="res res-p" aria-hidden="true"><i style="width:64px;height:36px"></i></span>';
    var h = o.height || 1080, k = Math.max(0.22, Math.min(1, h / 1080));
    return '<span class="res" aria-hidden="true"><i style="width:' + Math.round(64 * k) + 'px;height:' + Math.round(36 * k) + 'px"></i></span>';
  }
  // one line per output; Details opens the full card for that output only
  // the overlays drawn on output o
  function onOutput(d, o) { return o.kind === 'video' ? (d.overlays || []).filter(function (x) { return x.name && carries(x, o); }) : []; }
  function row(o, i, d) {
    var v = o.kind === 'video', lit = v && o.enabled && onOutput(d, o).length > 0;
    var chips = v ? [U.esc(videoSummary(o))].concat(o.skip ? [fpsLabel(o.skip)] : []) : [o.kind === 'audio' ? 'Audio only' : 'As it arrives'];
    if (o.kind !== 'audio') chips.push(audioText(o) === 'off' ? 'No audio' : 'Audio ' + audioText(o));
    if (v) chips.push('<i class="hwc-s">' + U.icon('chip') + U.esc(outHw(d, o)) + '</i>');
    return '<div class="oc oc-row' + (o.enabled ? '' : ' off') + (lit ? ' ov' : '') + '" data-i="' + i + '">' + resBox(o) +
      '<div class="oc-rn"><div class="oc-rt"><input type="text" value="' + U.esc(o.name) + '" data-o="name" aria-label="Output name" spellcheck="false">' + (sizeLabel(o) !== o.name ? '<span class="bdg">' + U.esc(sizeLabel(o)) + '</span>' : '') + '</div>' +
      '<div class="oc-chips">' + chips.map(function (c) { return '<span>' + c + '</span>'; }).join('') + '</div></div>' +
      '<span class="oc-ov">' + (v && o.enabled ? layerBadges(d, o) + (layerBadges(d, null) ? '<span class="lbadge from" title="Drawn on the input, so this output shows it too">' + U.icon('input') + 'from input</span>' : '') : '') + '</span>' +
      '<span class="oc-ra"><label class="switch" title="Output on"><input type="checkbox" data-o="enabled"' + (o.enabled ? ' checked' : '') + '><span class="sr">Output on</span></label>' +
      '<button class="btn sm" type="button" data-oexp aria-expanded="false">Edit</button>' +
      '<button class="btn sm icon ghost" type="button" data-rm title="Remove this output" aria-label="Remove output ' + U.esc(o.name) + '">' + U.icon('close') + '</button></span></div>';
  }
  function card(o, i, d) {
    if (!S.openOut || !S.openOut.has(o)) return row(o, i, d);
    var v = o.kind === 'video', lit = v && o.enabled && onOutput(d, o).length > 0;
    var head = '<div class="oc-top"><input type="text" value="' + U.esc(o.name) + '" data-o="name" aria-label="Output name" spellcheck="false">' +
      '<label class="oc-on" title="Output on"><input type="checkbox" data-o="enabled"' + (o.enabled ? ' checked' : '') + '><span class="sr">Output on</span></label>' +
      '<button class="btn sm" type="button" data-oexp aria-expanded="true">Close</button>' +
      '<button class="btn sm icon ghost" type="button" data-rm title="Remove this output" aria-label="Remove output ' + U.esc(o.name) + '">' + U.icon('close') + '</button></div>' +
      '<div class="oc-sum"><code>&lt;stream&gt;_' + U.esc(o.name) + '</code>' + (v ? '<span class="hwc-s">' + U.icon('chip') + U.esc(outHw(d, o)) + '</span>' + layerBadges(d, o) : '') + '</div>';
    var cls = 'oc oc-open' + (o.enabled ? '' : ' off') + (lit ? ' ov' : '');
    if (o.kind === 'passthrough') {
      return '<div class="' + cls + '" data-i="' + i + '">' + head + '<p class="hint oc-pad">The stream as it arrives. An overlay cannot be drawn on it.</p>' +
        sec(o, 'aud', 'Audio', audioText(o), audioBody(o, false)) + '</div>';
    }
    if (o.kind === 'audio') return '<div class="' + cls + '" data-i="' + i + '">' + head + sec(o, 'aud', 'Audio', audioText(o), audioBody(o, true)) + '</div>';
    var custom = o.height !== 0 && !size(o.height), h264 = o.codec === 'H.264', vp = o.codec === 'VP8' || o.codec === 'VP9';
    var keySecs = Math.round(o.keyInterval * (o.skip + 1) / FPS * 10) / 10;
    var picture = '<div class="oc-f"><label>Size<select data-o="height"><option value="0"' + (o.height === 0 ? ' selected' : '') + '>Source size</option>' +
      SIZES.map(function (s) { return '<option value="' + s[0] + '"' + (o.height === s[0] ? ' selected' : '') + '>' + s[1] + (s[0] === 234 ? ' (416 x 234)' : '') + '</option>'; }).join('') +
      '<option value="custom"' + (custom ? ' selected' : '') + '>Other height</option></select></label>' +
      (o.height ? '<label>Fit<select data-o="fit">' + opts(FITS, o.fit) + '</select></label>' : '') +
      (custom ? '<label>Height<input type="number" data-o="customHeight" min="120" max="4320" value="' + o.height + '"></label>' : '') +
      (o.height && o.fit !== 'fit-height' ? '<label>Width<input type="number" data-o="width" min="120" max="7680" step="2" value="' + o.width + '"></label>' : '') + '</div>' +
      '<label class="check"><input type="checkbox" data-o="regionOn"' + (o.region ? ' checked' : '') + '>Only a region of the source</label>' +
      (o.region ? '<div class="oc-f oc-f4">' + ['x', 'y', 'w', 'h'].map(function (k) { return '<label>' + { x: 'Left', y: 'Top', w: 'Width', h: 'Height' }[k] + '<input type="number" data-region="' + k + '" min="0" value="' + o.region[k] + '"></label>'; }).join('') + '</div>' +
        '<p class="hint">In source pixels. <span class="key">FrameSize/SourceRectangle</span></p>' : '');
    var motion = '<div class="oc-f"><label>Frame rate<select data-o="skip">' + opts([[0, 'Same as the source'], [1, 'Half'], [2, 'A third'], [3, 'A quarter']], o.skip) + '</select></label></div>' +
      (o.skip ? '<p class="hint warn-text">Captions are dropped at a reduced frame rate.</p>' : '') + '<p class="hint key-note"><span class="key">SkipFrameCount</span> ' + o.skip + '</p>';
    var video = '<div class="oc-f"><label>Codec<select data-o="codec">' + opts([['H.264', 'H.264'], ['H.265', 'H.265'], ['VP8', 'VP8', d.hardware !== 'cpu'], ['VP9', 'VP9', d.hardware !== 'cpu']], o.codec) + '</select></label>' +
      (h264 ? '<label>Profile<select data-o="profile">' + opts([['baseline', 'baseline'], ['main', 'main'], ['high', 'high']], o.profile) + '</select></label>' : '') +
      '<label>Bitrate (kbps)<input type="number" data-o="kbps" min="50" max="100000" step="50" value="' + Math.round(o.videoBitrate / 1000) + '"></label>' +
      (h264 ? '<label>Rate control<select data-o="rateControl">' + opts([['default', 'Engine default'], ['cbr', 'Constant'], ['vbr', 'Variable, with a peak']], o.rateControl) + '</select></label>' +
        (o.rateControl === 'vbr' ? '<label>Peak (kbps)<input type="number" data-o="peak" min="50" value="' + Math.round((o.peakBitrate || o.videoBitrate * 1.5) / 1000) + '"></label>' : '') +
        '<label>Speed or quality<select data-o="speed">' + opts([['default', 'Engine default'], ['fastest', 'Fastest'], ['balanced', 'Balanced'], ['quality', 'Best quality']], o.speed) + '</select></label>' : '') +
      '<label>Keyframe every (s)<input type="number" data-o="keySecs" min="0.1" step="0.5" value="' + keySecs + '"' + (o.keyFollow ? ' disabled' : '') + '></label></div>' +
      '<label class="check"><input type="checkbox" data-o="keyFollow"' + (o.keyFollow ? ' checked' : '') + '>Keyframes follow the source</label>' +
      (!vp ? '<label class="check"><input type="checkbox" data-o="hdrToSdr"' + (o.hdrToSdr ? ' checked' : '') + '>HDR source to SDR output</label>' : '') +
      (h264 ? '' : '<p class="hint">For ' + o.codec + ', set rate control under Expert by the encoder\'s parameter names.</p>') +
      '<p class="hint key-note">' + (h264 ? '<span class="key">mainconcept.bit_rate_mode</span>, <span class="key">max_bit_rate</span>, <span class="key">performance_level</span>. ' : '') +
      '<span class="key">KeyFrameInterval</span> ' + (o.keyFollow ? 'follows the source' : o.keyInterval + ' frames at ' + FPS + ' fps') + '.</p>';
    var expert = '<p class="hint">Any encoder parameter by name, as <code>prefix.name</code>, for example <code>mainconcept.adaptive_b_frames</code>. WSE drops a name it does not know and says so in its log.</p>' +
      o.params.map(function (p, pi) { return '<div class="oc-f oc-param"><input type="text" data-param="' + pi + '" data-k="name" value="' + U.esc(p.name) + '" placeholder="prefix.name" spellcheck="false">' +
        '<input type="text" data-param="' + pi + '" data-k="value" value="' + U.esc(p.value) + '" placeholder="value"><button class="btn sm icon ghost" type="button" data-prm="' + pi + '" aria-label="Remove parameter">' + U.icon('close') + '</button></div>'; }).join('') +
      '<button class="btn sm" type="button" data-addparam>+ Parameter</button>';
    var videoSum = videoSummary(o);
    return '<div class="' + cls + '" data-i="' + i + '">' + head +
      sec(o, 'pic', 'Picture', U.esc(sizeLabel(o) + (o.height ? ', ' + o.fit.replace('-', ' ') : '')), picture) +
      sec(o, 'mot', 'Frame rate', fpsLabel(o.skip), motion) +
      sec(o, 'vid', 'Video', U.esc(videoSum), video) +
      sec(o, 'aud', 'Audio', audioText(o), audioBody(o, false)) +
      sec(o, 'cap', 'Captions', o.captions && !o.skip ? 'kept' : 'dropped', '<label class="check"><input type="checkbox" data-o="captions"' + (o.captions ? ' checked' : '') + '>Keep CEA-608 captions from the source</label><p class="hint key-note"><span class="key">copyCEA608CaptionsFromSourceStream</span></p>') +
      sec(o, 'exp', 'Expert', o.params.length ? o.params.length + ' parameter' + (o.params.length > 1 ? 's' : '') : 'none', expert) + '</div>';
  }

  function playback(d) {
    var outs = d.outputs.filter(function (o) { return o.enabled; });
    if (!outs.length) return '';
    var cell = function (g, gi, o) {
      var m = g.members.filter(function (x) { return x.output === o.name; })[0], v = m ? (m.only || 'all') : '';
      return '<td><select data-gm="' + gi + '" data-out="' + U.esc(o.name) + '" aria-label="' + U.esc(o.name) + ' in ' + U.esc(g.name) + '">' +
        opts([['', 'not in it'], ['all', 'in it']].concat(o.kind !== 'audio' ? [['video', 'video only']] : []).concat([['audio', 'audio only']]), v) + '</select></td>';
    };
    return '<div class="b-play"><div class="tb-h">' + U.icon('play') + 'Playback' + U.tip('An adaptive group is one stream name a player opens; it switches between the group\'s outputs as bandwidth allows. Audio only adds an output\'s audio as a separate track, for example a second language. WSE setting StreamNameGroups.') +
      '<span class="spacer"></span><button class="btn sm ghost" type="button" data-addgroup>' + U.icon('plus') + 'Adaptive group</button></div>' +
      (d.groups.length ? '<table class="tbl b-groups"><thead><tr><th>Output</th>' + d.groups.map(function (g, gi) {
        return '<th><input type="text" data-gname="' + gi + '" value="' + U.esc(g.name) + '" spellcheck="false" aria-label="Group name"><button class="btn sm icon ghost" type="button" data-grm="' + gi + '" aria-label="Remove group ' + U.esc(g.name) + '">' + U.icon('close') + '</button></th>'; }).join('') + '</tr></thead><tbody>' +
        outs.map(function (o) { return '<tr><td><code>_' + U.esc(o.name) + '</code></td>' + d.groups.map(function (g, gi) { return cell(g, gi, o); }).join('') + '</tr>'; }).join('') +
        '<tr><td class="small muted">Players open</td>' + d.groups.map(function (g) { return '<td class="small"><code>ngrp:&lt;stream&gt;_' + U.esc(g.name) + '</code></td>'; }).join('') + '</tr></tbody></table>'
        : '<p class="faint small">No adaptive group: players open each output by name.</p>') + '</div>';
  }

  function summary(d) {
    var on = d.outputs.filter(function (o) { return o.enabled; });
    return '<span class="bdg">' + on.length + (on.length === 1 ? ' output' : ' outputs') + '</span>' +
      (d.overlays || []).filter(function (x) { return x.name; }).map(function (x) {
        var n = on.filter(function (o) { return o.kind === 'video' && carries(x, o); }).length;
        return '<span class="bdg' + (n ? '' : ' warn') + '">' + (n ? n + ' with ' + U.esc(x.name) : 'No output draws ' + U.esc(x.name)) + '</span>';
      }).join('') +
      (d.groups.length ? '<span class="bdg" title="Adaptive group names players open">' + d.groups.map(function (g) { return 'ngrp:_' + U.esc(g.name); }).join(', ') + '</span>' : '');
  }

  /* ---------- layers: the overlay and the transcoder's own image overlays ---------- */
  var ALIGNS = [['left,top', 'Top left'], ['hcenter,top', 'Top center'], ['right,top', 'Top right'], ['left,vcenter', 'Middle left'], ['hcenter,vcenter', 'Center'],
    ['right,vcenter', 'Middle right'], ['left,bottom', 'Bottom left'], ['hcenter,bottom', 'Bottom center'], ['right,bottom', 'Bottom right']];
  var MAX_IMAGES = 8;
  // the image library (GET images), with previews; read once per page and after an upload
  function library(force) {
    if (S.lib && !force) return Promise.resolve(S.lib);
    return U.images.library(force).then(function (l) { S.lib = l; return l; });
  }
  function libEntry(file) { return (S.lib || []).filter(function (x) { return x.file === file; })[0] || null; }
  function videoOutputs(d) { return d.outputs.filter(function (o) { return o.kind === 'video' && o.enabled; }); }

  // where a layer is drawn. Decode's Overlays: on the input, once and before scaling, so every video output shows it,
  // in source pixels. Encode's Overlays: after scaling, only on the outputs picked, in each output's pixels. A template
  // carries up to four overlays, each with its own place; one in Encode with every output picked is saved on the
  // input, where it draws the same. With no video output there is nothing to draw on, and it shows in Decode.
  function ovStage(d, x) { return videoOutputs(d).length ? x.stage : 'input'; }
  function imgStage(l) { return l.where === 'outputs' ? 'outputs' : 'input'; }
  var IN_TIP = 'Drawn once on the source picture, before scaling, so every video output shows it. Offsets and sizes are in source pixels.';
  var OUT_TIP = 'Drawn after scaling, only on the outputs you pick, so outputs can differ. Offsets and sizes are in each output\'s pixels.';
  var KINDS_TIP = ' An overlay is made in the Overlays tab and holds up to 64 layers: web pages, text, clocks, shapes and images. A template carries up to four, so combine several layers in one overlay. An image here is a PNG, JPEG, GIF or BMP file in the server\'s content folder, drawn under the overlays.';
  function outPicks(attr, i, vids, isOn) {
    return '<div class="ly-outs">' + vids.map(function (o) {
      return '<label class="pilltog' + (isOn(o) ? ' on' : '') + '"><input type="checkbox" ' + attr + '="' + i + '" value="' + U.esc(o.name) + '"' + (isOn(o) ? ' checked' : '') + '>' + U.esc(o.name) + '</label>'; }).join('') + '</div>';
  }
  // one overlay the template carries: which overlay, where it draws, and its place in the stage's stacking order
  function overlayCard(d, x, i, stage, vids) {
    var picked = vids.filter(function (o) { return x.outputs.indexOf(o.name) >= 0; }).length;
    var same = d.overlays.filter(function (y) { return ovStage(d, y) === stage; }), k = same.indexOf(x);
    var other = stage === 'input' ? 'outputs' : 'input', canMove = vids.length > 1 || stage === 'outputs';
    var missing = x.name && !(U.overlays || []).some(function (o) { return o.name === x.name; });
    return '<div class="lyc lyr lyu" data-ov="' + i + '"><div class="lyc-top"><div class="ly-thumb">' + U.icon('overlay') + '</div>' +
      '<div class="ly-main"><select data-ovname="' + i + '" aria-label="Overlay ' + (i + 1) + '">' + overlayOptions(x.name, x.name ? null : 'Pick an overlay', d.overlays, x.name) + '</select>' +
      '<span class="small muted">Overlay' + (same.length > 1 ? ', ' + (k === 0 ? 'drawn under the others here' : k === same.length - 1 ? 'drawn on top here' : 'drawn ' + ordinal(k + 1) + ' here') : '') + '</span></div>' +
      '<div class="ly-acts">' + (k > 0 ? '<button class="btn sm icon ghost" type="button" data-ovup="' + i + '" aria-label="Move ' + U.esc(x.name || 'overlay') + ' down the stack" title="Draw it under the one before">' + U.icon('up') + '</button>' : '') +
      (canMove ? '<button class="btn sm ghost" type="button" data-ovmove="' + i + '" title="' + (other === 'input' ? 'Draw it on the input, so every video output shows it' : 'Draw it on chosen outputs only') + '">' + U.icon(other === 'input' ? 'input' : 'outputs') + (other === 'input' ? 'To Decode' : 'To outputs') + '</button>' : '') +
      '<button class="btn sm icon ghost" type="button" data-ovrm="' + i + '" aria-label="Remove overlay ' + U.esc(x.name || (i + 1)) + '">' + U.icon('close') + '</button></div></div>' +
      (missing ? '<div class="note bad"><span class="dot bad"></span><div>Overlay <strong>' + U.esc(x.name) + '</strong> does not exist in ' + U.esc(U.app) + '. Overlays are saved per application.</div></div>' : '') +
      (stage === 'outputs' ? '<div class="lyc-row"><span class="f">On</span>' + outPicks('data-ovout', i, vids, function (o) { return x.outputs.indexOf(o.name) >= 0; }) + '</div>' +
        (picked === vids.length && vids.length ? '<span class="bdg">Every output picked: saved on the input, in Decode</span>' : !picked ? '<span class="bdg warn">Pick at least one output</span>' : '') : '') + '</div>';
  }
  function ordinal(n) { return ['first', 'second', 'third', 'fourth'][n - 1] || n + 'th'; }
  function imageCard(d, l, i, stage, vids) {
    var e = libEntry(l.file), pv = e && e.preview;
    var same = (d.images || []).filter(function (x) { return imgStage(x) === stage; }), first = same[0] === l;
    return '<div class="lyc lyr" data-ly="' + i + '"><div class="lyc-top"><div class="ly-thumb">' + (pv ? '<img src="' + U.esc(pv) + '" alt="">' : U.icon('grid')) + '</div>' +
      '<div class="ly-main"><strong>' + U.esc(l.file) + '</strong><span class="small ' + (e || !S.lib ? 'muted' : 'warn-text') + '">' + (e || !S.lib ? 'Image' : 'Not in the content folder') + '</span></div>' +
      '<div class="ly-acts">' + (first ? '' : '<button class="btn sm icon ghost" type="button" data-lyup="' + i + '" aria-label="Move up">' + U.icon('up') + '</button>') +
      '<button class="btn sm icon ghost" type="button" data-lyrm="' + i + '" aria-label="Remove image ' + U.esc(l.file) + '">' + U.icon('close') + '</button></div></div>' +
      (stage === 'outputs' ? '<div class="lyc-row"><span class="f">On</span>' + outPicks('data-lyout', i, vids, function (o) { return l.outputs.indexOf(o.name) >= 0; }) + '</div>' : '') +
      '<div class="lyc-row"><span class="f">Corner</span><div class="align9" role="radiogroup" aria-label="Corner">' + ALIGNS.map(function (a) {
        return '<button type="button" role="radio" data-lyalign="' + a[0] + '" aria-checked="' + (l.align === a[0]) + '" aria-label="' + a[1] + '" title="' + a[1] + '"></button>'; }).join('') + '</div></div>' +
      '<details class="lyc-more"><summary>Offset, size, opacity</summary><div class="oc-f ly-f">' +
      '<label>Offset x<input type="number" data-ly-k="x" min="0" max="8192" value="' + l.x + '"></label><label>Offset y<input type="number" data-ly-k="y" min="0" max="8192" value="' + l.y + '"></label>' +
      '<label>Width<input type="number" data-ly-k="width" min="0" max="8192" value="' + l.width + '" placeholder="its own"></label><label>Height<input type="number" data-ly-k="height" min="0" max="8192" value="' + l.height + '" placeholder="its own"></label>' +
      '<label>Opacity %<input type="number" data-ly-k="opacity" min="0" max="100" value="' + l.opacity + '"></label></div>' +
      '<label class="check"><input type="checkbox" data-ly-k="refresh"' + (l.refresh ? ' checked' : '') + '>Reload when the file changes</label></details></div>';
  }
  // one Overlays section: its add buttons, the image picker when opened here, then the overlays and the images drawn
  // here, overlays over images (S1: within a stage the module's slots draw over the template's images)
  function overlaysHtml(d, t, stage) {
    var vids = videoOutputs(d), full = (d.images || []).length >= MAX_IMAGES, ovFull = d.overlays.length >= MAX_OVERLAYS;
    var cards = d.overlays.map(function (x, i) { return ovStage(d, x) === stage ? overlayCard(d, x, i, stage, vids) : ''; }).join('');
    var items = cards + (d.images || []).map(function (l, i) { return imgStage(l) === stage ? imageCard(d, l, i, stage, vids) : ''; }).join('');
    // nothing to draw on: what is there can still be removed
    if (!vids.length) return '<div class="hrow ly-add"><span class="small faint">No video output to draw on</span></div>' + items;
    // an overlay is on outputs only when some of two or more carry it; with one, it is the input
    var add = stage === 'outputs' && vids.length < 2 ? '<span class="small faint">With one video output, overlays go in Decode</span>'
      : !(U.overlays || []).length ? '<button class="btn sm ghost" type="button" data-write data-go="new-overlay:">' + U.icon('plus') + 'Overlay</button>'
      : '<button class="btn sm ghost" type="button" data-addov="' + stage + '"' + (ovFull ? ' disabled title="At most ' + MAX_OVERLAYS + ' overlays in a template. Combine several layers in one overlay in the Overlays tab."' : '') + '>' + U.icon('plus') + 'Overlay</button>';
    var hw = cards && d.hardware !== 'cpu' ? '<p class="hint">On ' + U.esc((HW.filter(function (x) { return x[0] === d.hardware; })[0] || HW[0])[1]) + ', each frame is copied off the card to draw an overlay, which costs more than without one.</p>' : '';
    return '<div class="hrow ly-add">' + add + '<button class="btn sm ghost" type="button" data-addimg="' + stage + '"' + (full ? ' disabled title="At most ' + MAX_IMAGES + ' images in a template"' : '') + ' aria-expanded="' + (S.pick === stage) + '">' + U.icon('plus') + 'Image</button>' +
      (items || S.pick === stage ? '' : '<span class="small faint">None</span>') + '</div>' + (S.pick === stage ? pickerHtml() : '') + items + hw;
  }
  // what is drawn where, as badges: the input row of the ladder, and each output row
  function layerBadges(d, o) {
    var b = [];
    d.overlays.forEach(function (x) { if (x.name && (o ? ovStage(d, x) === 'outputs' && carries(x, o) : ovStage(d, x) === 'input')) b.push(x.name); });
    (d.images || []).forEach(function (l) { if (o ? l.where === 'outputs' && l.outputs.indexOf(o.name) >= 0 : l.where !== 'outputs') b.push(l.file); });
    return b.map(function (n) { return '<span class="lbadge">' + U.icon('overlay') + U.esc(n) + '</span>'; }).join('');
  }
  // the picker (ovs-images.js), inline under the Layers title
  function pickerHtml() { return U.images.pickerHtml({ okLabel: 'Add image' }); }
  function addImage(d, file, stage) {
    d.images = d.images || [];
    var outs = stage === 'outputs';
    d.images.push({ file: file, where: outs ? 'outputs' : 'all', outputs: outs ? videoOutputs(d).slice(0, 1).map(function (o) { return o.name; }) : [], x: 16, y: 16, width: 0, height: 0,
      align: 'right,top', opacity: 100, refresh: false });
  }
  // an overlay on one stage: the input is every video output, outputs starts with the first one; it goes last in
  // that stage, so on top of what is there
  function placeOverlay(d, x, stage) {
    var i = d.overlays.indexOf(x);
    if (i >= 0) d.overlays.splice(i, 1);
    x.stage = stage;
    x.outputs = stage === 'outputs' ? (x.outputs.length ? x.outputs : videoOutputs(d).slice(0, 1).map(function (o) { return o.name; })) : [];
    d.overlays.push(x);
    d.overlays = inStageOrder(d.overlays);
  }
  // the first overlay of the application the template does not carry yet
  function nextOverlay(d) {
    var have = d.overlays.map(function (x) { return x.name; });
    return ((U.overlays || []).filter(function (o) { return have.indexOf(o.name) < 0; })[0] || {}).name || '';
  }
  function bindPicker(d) {
    var stage = S.pick;
    U.images.bind(el.body.querySelector('.ly-picker'), {
      onCancel: function () { S.pick = false; rerender(); },
      onPick: function (file, uploaded) {
        S.lib = U.images.cached();
        addImage(d, file, stage); S.pick = false;
        if (uploaded) U.toast('Uploaded ' + file + ' and added it as a layer.');
        rerender();
      },
      inUse: function (f) { return (d.images || []).some(function (l) { return l.file === f; }) ? f + ' is a layer in this template; remove the layer first.' : null; },
      onChange: function () { S.lib = U.images.cached(); rerender(); }
    });
  }
  function bindLayers(d) {
    el.body.querySelectorAll('[data-addov]').forEach(function (b) {
      b.onclick = function () {
        if (d.overlays.length >= MAX_OVERLAYS) return;
        var x = { name: nextOverlay(d), stage: 'input', outputs: [] };
        placeOverlay(d, x, b.getAttribute('data-addov'));
        rerender();
        var s = el.body.querySelector('[data-ovname="' + d.overlays.indexOf(x) + '"]');
        if (s) s.focus({ preventScroll: true });
      };
    });
    el.body.querySelectorAll('[data-ovname]').forEach(function (s) { s.onchange = function () { d.overlays[+s.getAttribute('data-ovname')].name = s.value; rerender(); }; });
    el.body.querySelectorAll('[data-ovmove]').forEach(function (b) {
      b.onclick = function () { var x = d.overlays[+b.getAttribute('data-ovmove')]; placeOverlay(d, x, ovStage(d, x) === 'input' ? 'outputs' : 'input'); rerender(); };
    });
    el.body.querySelectorAll('[data-ovrm]').forEach(function (b) { b.onclick = function () { d.overlays.splice(+b.getAttribute('data-ovrm'), 1); rerender(); }; });
    el.body.querySelectorAll('[data-ovout]').forEach(function (c) {
      c.onchange = function () { var x = d.overlays[+c.getAttribute('data-ovout')]; x.outputs = x.outputs.filter(function (n) { return n !== c.value; }); if (c.checked) x.outputs.push(c.value); rerender(); };
    });
    // under the overlay before it in the same stage: a lower slot draws first (plan 3.9)
    el.body.querySelectorAll('[data-ovup]').forEach(function (b) {
      b.onclick = function () {
        var i = +b.getAttribute('data-ovup'), st = ovStage(d, d.overlays[i]), j = i - 1;
        while (j >= 0 && ovStage(d, d.overlays[j]) !== st) j--;
        if (j < 0) return;
        var t = d.overlays[i]; d.overlays[i] = d.overlays[j]; d.overlays[j] = t; rerender();
      };
    });
    el.body.querySelectorAll('[data-lyalign]').forEach(function (b) {
      b.onclick = function () { d.images[+b.closest('[data-ly]').getAttribute('data-ly')].align = b.getAttribute('data-lyalign'); rerender(); };
    });
    el.body.querySelectorAll('[data-ly]').forEach(function (row) {
      var l = d.images[+row.getAttribute('data-ly')];
      row.querySelectorAll('[data-ly-k]').forEach(function (f) {
        f.onchange = function () {
          var k = f.getAttribute('data-ly-k');
          if (f.type === 'checkbox') l[k] = f.checked;
          else if (f.type === 'number') l[k] = Math.max(0, num(f.value, 0));
          else l[k] = f.value;
          if (k === 'where' && l.where === 'outputs' && !l.outputs.length) l.outputs = videoOutputs(d).slice(0, 1).map(function (o) { return o.name; });
          rerender();
        };
      });
    });
    el.body.querySelectorAll('[data-lyout]').forEach(function (c) {
      c.onchange = function () { var l = d.images[+c.getAttribute('data-lyout')]; l.outputs = l.outputs.filter(function (n) { return n !== c.value; }); if (c.checked) l.outputs.push(c.value); rerender(); };
    });
    el.body.querySelectorAll('[data-lyrm]').forEach(function (b) { b.onclick = function () { d.images.splice(+b.getAttribute('data-lyrm'), 1); rerender(); }; });
    // up past the image above it in the same section; the other section's images keep their places
    el.body.querySelectorAll('[data-lyup]').forEach(function (b) {
      b.onclick = function () {
        var i = +b.getAttribute('data-lyup'), st = imgStage(d.images[i]), j = i - 1;
        while (j >= 0 && imgStage(d.images[j]) !== st) j--;
        if (j < 0) return;
        var t = d.images[i]; d.images[i] = d.images[j]; d.images[j] = t; rerender();
      };
    });
    el.body.querySelectorAll('[data-addimg]').forEach(function (add) {
      add.onclick = function () {
        var st = add.getAttribute('data-addimg');
        S.pick = S.pick === st ? false : st; rerender();
        if (S.pick) library(true).then(function () { if (S.draft === d) rerender(); }, function (e) { S.pick = false; rerender(); U.toast('The image list could not be read: ' + e.message, { level: 'bad' }); });
      };
    });
    bindPicker(d);
  }

  // pixels encoded per second over the enabled video outputs, for an assumed 1080p 30 fps source, in 720p 30 fps units
  function loadText(d) {
    var px = 0, n = 0;
    d.outputs.forEach(function (o) {
      if (!o.enabled || o.kind !== 'video') return;
      var h = o.height || 1080, w = o.height === 0 ? 1920 : o.fit === 'fit-height' ? Math.round(h * 16 / 9) : o.width;
      if (o.fit === 'fit-width' && o.height) h = Math.round(w * 9 / 16);
      if (o.region && o.height === 0) { w = o.region.w; h = o.region.h; }
      px += w * h * 30 / (o.skip + 1); n++;
    });
    if (!n) return '<span class="bdg">Audio and copies only</span>';
    var unit = 1280 * 720 * 30, cpus = U.serverInfo && U.serverInfo.cpus, x = Math.round(px / unit * 10) / 10;
    var why = 'About ' + Math.round(px / 1e6) + ' megapixels a second to encode, for a 1080p 30 fps source plus decoding it once' + (d.hardware === 'cpu' ? '' : ', on the ' + ({ nvidia: 'NVIDIA GPU', intel: 'Intel Quick Sync', netint: 'NETINT VPU' })[d.hardware]) + '.' +
      (cpus ? ' This server has ' + cpus + ' CPU core' + (cpus > 1 ? 's' : '') + '.' : '') +
      (d.hardware !== 'cpu' && d.overlays.length ? ' With an overlay on, frames are copied off the card to draw it, so this costs more than without it.' : '') +
      ' An estimate: what a core holds depends on the codec, the speed setting and the picture.';
    var pct = Math.min(100, Math.round(x / 6 * 100));
    return '<span class="load" title="Estimated encode load, in 720p 30 fps outputs">Encode load<span class="load-bar"><i style="width:' + pct + '%"></i></span>' + x + ' x 720p30</span>' + U.tip(why);
  }

  function renderBuilder() {
    var d = S.draft, t = S.sel && find(S.sel), hw = HW.filter(function (x) { return x[0] === d.hardware; })[0] || HW[0], hs = hwOf(d);
    var top = '<div class="tb-top">' +
      (d.isNew ? '<div class="fld tb-name"><label class="f" for="ovs-b-name">Master template name' + U.tip('Saved as transcoder/templates/<name>.xml. Not a stream name: putting it on a stream gives that stream its own copy.') + '</label>' +
        '<input id="ovs-b-name" type="text" data-d="name" value="' + U.esc(d.name) + '" placeholder="for example scoreboard-ladder" spellcheck="false" autocomplete="off"></div>' : '') +
      '<div class="tb-hw"><span class="f">Hardware' + U.tip(hw[2] + ' It sets what decodes, scales and encodes for the whole template. Takes effect when a stream restarts with it.') + '</span>' +
      '<div class="hrow"><div class="seg" role="group" aria-label="Hardware">' + HW.map(function (x) {
        return '<button type="button" data-hw="' + x[0] + '" aria-pressed="' + (d.hardware === x[0]) + '">' + x[1] + '</button>'; }).join('') + '</div>' +
      (d.hardware !== 'cpu' ? '<label class="inl">Device<input type="number" class="num" data-d="gpuId" min="-1" max="15" value="' + d.gpuId + '" aria-label="GPU or device">' + U.tip('-1 lets WSE choose.') + '</label>' : '') + '</div></div>' +
      '<ol class="tb-flow" aria-label="Where each stage runs"><li><b>Decode</b>' + U.esc(hs[0]) + '</li><li><b>Scale</b>' + U.esc(hs[1]) + '</li><li><b>Encode</b>' + U.esc(hs[2]) + '</li></ol></div>';
    var decode = '<section class="tb-box"><div class="tb-bh"><span class="tb-ic">' + U.icon('input') + '</span><h3>Decode</h3><span class="hwc">' + U.icon('chip') + U.esc(hs[0]) + '</span>' +
      '<span class="small faint">The incoming stream, and what is drawn on its picture</span></div>' +
      '<div class="tb-line"><span class="f">Source</span><div class="tb-val hrow"><label class="check"><input type="checkbox" data-d="deinterlace"' + (d.deinterlace ? ' checked' : '') + '>Deinterlace</label>' +
      '<label class="inl">Never below<input type="number" class="num" data-d="minFrameRate" min="0" max="120" value="' + (d.minFrameRate || 0) + '" aria-label="Never below, frames per second">fps' + U.tip('For sources that drop frames: WSE fills up to this rate. 0 leaves it out.') + '</label></div></div>' +
      '<div class="tb-line"><span class="f">Overlays' + U.tip(IN_TIP + KINDS_TIP) + '</span><div class="tb-val ly-sec">' + overlaysHtml(d, t, 'input') + '</div></div></section>';
    var encode = '<section class="tb-box"><div class="tb-bh"><span class="tb-ic">' + U.icon('outputs') + '</span><h3>Encode</h3><span class="hwc">' + U.icon('chip') + (hs[1] === hs[2] ? 'Scale, encode ' + U.esc(hs[2]) : 'Scale ' + U.esc(hs[1]) + ', encode ' + U.esc(hs[2])) + '</span>' +
      '<span class="spacer"></span>' + loadText(d) + '</div>' +
      // presets replace every output, so they are offered only while the template is new
      (d.isNew ? '<div class="tb-line"><span class="f">Start from</span><div class="tb-val presets">' + PRESETS.map(function (p, i) {
        return '<button type="button" class="pill" data-preset="' + i + '" aria-pressed="' + (d.preset === i) + '">' + p[0] + '</button>'; }).join('') +
        (d.preset === 0 ? '<span class="hint" data-presethint>Add an overlay for the overlay output.</span>' : '') + '</div></div>' : '') +
      '<div class="b-sum hrow">' + summary(d) + '</div>' +
      '<div class="oc-list">' + d.outputs.map(function (o, i) { return card(o, i, d); }).join('') +
      '<div class="oc-add"><button class="btn sm" type="button" data-add="video">' + U.icon('plus') + 'Output</button>' +
      '<button class="btn sm ghost" type="button" data-add="passthrough">' + U.icon('plus') + 'Original</button><button class="btn sm ghost" type="button" data-add="audio">' + U.icon('plus') + 'Audio only</button></div></div>' +
      '<div class="b-ovl"><div class="tb-h">' + U.icon('overlay') + 'Overlays on outputs' + U.tip(OUT_TIP + KINDS_TIP) + '</div>' + overlaysHtml(d, t, 'outputs') + '</div>' +
      playback(d) + '</section>';
    el.body.innerHTML = '<div class="tb">' + top + decode + encode + '</div>';
    bindBuilder();
    if (!S.lib && !S.libLoading && (d.images || []).length) { S.libLoading = true; library().then(function () { S.libLoading = false; if (S.draft === d) rerender(); }, function () { S.libLoading = false; }); }
  }

  function bindBuilder() {
    var d = S.draft;
    el.body.querySelectorAll('[data-d]').forEach(function (f) {
      var k = f.getAttribute('data-d');
      f.addEventListener(f.type === 'text' ? 'input' : 'change', function () {
        if (k === 'name') { d.name = f.value.trim().replace(/\.xml$/i, ''); renderBar(); U.changed(); return; }
        if (f.type === 'checkbox') d[k] = f.checked;
        else if (f.type === 'number') d[k] = num(f.value, d[k]);
        else d[k] = f.value;
        rerender();
      });
    });
    el.body.querySelectorAll('[data-preset]').forEach(function (b) {
      b.onclick = function () {
        var i = +b.getAttribute('data-preset'), before = JSON.stringify({ outputs: d.outputs, groups: d.groups, preset: d.preset });
        d.outputs = PRESETS[i][1](); d.preset = i; d.groups = defaultGroups(d.outputs);
        // outputs picked by name are gone with the old outputs
        d.overlays.forEach(function (x) { x.outputs = x.outputs.filter(function (n) { return d.outputs.some(function (o) { return o.name === n; }); }); });
        rerender();
        U.toast('Outputs replaced with ' + PRESETS[i][0] + '.', { undo: function () {
          if (S.draft !== d) return;
          var b0 = JSON.parse(before);
          d.outputs = b0.outputs; d.groups = b0.groups; d.preset = b0.preset;
          rerender();
        } });
      };
    });
    el.body.querySelectorAll('[data-hw]').forEach(function (b) {
      b.onclick = function () {
        d.hardware = b.getAttribute('data-hw');
        if (d.hardware !== 'cpu') d.outputs.forEach(function (o) { if (o.codec === 'VP8' || o.codec === 'VP9') o.codec = 'H.264'; });
        rerender();
      };
    });
    el.body.querySelectorAll('[data-sec]').forEach(function (b) { b.onclick = function () { var k = b.getAttribute('data-sec'); S.open[k] = !S.open[k]; rerender(); }; });
    el.body.querySelectorAll('[data-add]').forEach(function (b) { b.onclick = function () { addOutput(b.getAttribute('data-add')); }; });
    el.body.querySelectorAll('[data-oexp]').forEach(function (b) {
      b.onclick = function () {
        var o = d.outputs[+b.closest('.oc').getAttribute('data-i')];
        if (!S.openOut) S.openOut = new WeakSet();
        if (S.openOut.has(o)) S.openOut.delete(o); else S.openOut.add(o);
        rerender();
      };
    });
    el.body.querySelectorAll('.oc').forEach(function (c) {
      var i = +c.getAttribute('data-i'), o = d.outputs[i];
      c.querySelector('[data-rm]').onclick = function () {
        if (d.outputs.length === 1) { U.toast('A template needs at least one output.', { level: 'bad' }); return; }
        d.outputs.splice(i, 1); d.preset = null;
        d.groups.forEach(function (g) { g.members = g.members.filter(function (m) { return m.output !== o.name; }); });
        (d.images || []).forEach(function (l) { l.outputs = l.outputs.filter(function (n) { return n !== o.name; }); });
        d.overlays.forEach(function (x) { x.outputs = x.outputs.filter(function (n) { return n !== o.name; }); });
        rerender();
      };
      var ap = c.querySelector('[data-addparam]');
      if (ap) ap.onclick = function () { o.params.push({ name: '', value: '' }); rerender(); };
      c.querySelectorAll('[data-prm]').forEach(function (b) { b.onclick = function () { o.params.splice(+b.getAttribute('data-prm'), 1); rerender(); }; });
      c.querySelectorAll('[data-param]').forEach(function (f) { f.oninput = function () { o.params[+f.getAttribute('data-param')][f.getAttribute('data-k')] = f.value.trim(); renderBar(); U.changed(); }; });
      c.querySelectorAll('[data-region]').forEach(function (f) { f.onchange = function () { o.region[f.getAttribute('data-region')] = Math.max(0, num(f.value, 0)); rerender(); }; });
      c.querySelectorAll('[data-o]').forEach(function (f) {
        var k = f.getAttribute('data-o');
        // typing a name keeps the field as it is; leaving it redraws what names the output, the pickers on cards
        if (k === 'name') f.addEventListener('change', rerender);
        f.addEventListener(k === 'name' ? 'input' : 'change', function () {
          d.preset = null;
          if (k === 'name') {
            var old = o.name; o.name = f.value.trim();
            renamed(d, old, o.name);
            renderSummary(); renderBar(); U.changed(); return;
          }
          if (k === 'enabled' || k === 'keyFollow' || k === 'hdrToSdr' || k === 'captions') o[k] = f.checked;
          else if (k === 'regionOn') o.region = f.checked ? { x: 0, y: 0, w: 1280, h: 720 } : null;
          else if (k === 'kbps') o.videoBitrate = num(f.value, 0) * 1000;
          else if (k === 'peak') o.peakBitrate = num(f.value, 0) * 1000;
          else if (k === 'akbps') o.audioBitrate = num(f.value, 96) * 1000;
          else if (k === 'keySecs') o.keyInterval = Math.max(1, Math.round(parseFloat(f.value) * FPS / (o.skip + 1)) || 1);
          else if (k === 'skip' || k === 'channels' || k === 'sampleRate' || k === 'width' || k === 'customHeight') {
            var secs = o.keyInterval * (o.skip + 1) / FPS;
            o[k === 'customHeight' ? 'height' : k] = num(f.value, 0);
            if (k === 'skip') o.keyInterval = Math.max(1, Math.round(secs * FPS / (o.skip + 1))); // keep the same keyframe spacing in seconds
          }
          else if (k === 'height') {
            if (f.value === 'custom') o.height = o.height && !size(o.height) ? o.height : 1440;
            else {
              var old2 = size(o.height), h = +f.value, s = size(h);
              if (old2 && o.name === old2[1]) { var nn = s ? s[1] : 'full'; renamed(d, o.name, nn); o.name = nn; }
              if (s && (!old2 || o.videoBitrate === old2[2])) { o.videoBitrate = s[2]; o.profile = o.codec === 'H.264' ? s[3] : o.profile; }
              o.height = h; o.width = Math.round(h * 16 / 9 / 2) * 2;
            }
          }
          else if (k === 'audio') { o.audio = f.value; if ((o.audio === 'aac' || o.audio === 'opus') && !o.audioBitrate) o.audioBitrate = 96000; }
          else if (k === 'codec') { o.codec = f.value; if (o.codec !== 'H.264') { o.rateControl = 'default'; o.speed = 'default'; } }
          else o[k] = f.value;
          rerender();
        });
      });
    });
    el.body.querySelectorAll('[data-gm]').forEach(function (s) {
      s.onchange = function () {
        var g = d.groups[+s.getAttribute('data-gm')], name = s.getAttribute('data-out');
        g.members = g.members.filter(function (m) { return m.output !== name; });
        if (s.value) g.members.push({ output: name, only: s.value === 'all' ? '' : s.value });
        rerender();
      };
    });
    el.body.querySelectorAll('[data-gname]').forEach(function (f) { f.onchange = function () { d.groups[+f.getAttribute('data-gname')].name = f.value.trim(); rerender(); }; });
    el.body.querySelectorAll('[data-grm]').forEach(function (b) { b.onclick = function () { d.groups.splice(+b.getAttribute('data-grm'), 1); rerender(); }; });
    bindLayers(d);
    var ag = el.body.querySelector('[data-addgroup]');
    if (ag) ag.onclick = function () {
      var n = 'group' + (d.groups.length + 1);
      var smallest = d.outputs.filter(function (o) { return o.enabled && o.kind === 'video'; }).sort(function (a, b) { return (a.height || 9999) - (b.height || 9999); }).slice(0, 2);
      d.groups.push({ name: d.groups.some(function (g) { return g.name === 'mobile'; }) ? n : 'mobile', members: smallest.map(function (o) { return { output: o.name, only: '' }; }) });
      rerender();
    };
  }
  // an output renamed: what names it follows
  function renamed(d, old, nu) {
    d.groups.forEach(function (g) { g.members.forEach(function (m) { if (m.output === old) m.output = nu; }); });
    (d.images || []).forEach(function (l) { l.outputs = l.outputs.map(function (n) { return n === old ? nu : n; }); });
    d.overlays.forEach(function (x) { x.outputs = x.outputs.map(function (n) { return n === old ? nu : n; }); });
  }
  // a selector for the focused builder control, so a re-render can put focus back on it
  function focusKey() {
    var a = document.activeElement;
    if (!a || a === el.body || !el.body.contains(a)) return null;
    if (a.id) return '#' + CSS.escape(a.id);
    var attrs = Array.prototype.filter.call(a.attributes, function (x) { return /^data-/.test(x.name) || (x.name === 'value' && a.type === 'checkbox'); });
    if (!attrs.length) return null;
    var c = a.closest('.oc[data-i]'), ly = a.closest('[data-ly]'), ov = a.closest('[data-ov]');
    return (c ? '.oc[data-i="' + c.getAttribute('data-i') + '"] ' : ly ? '[data-ly="' + ly.getAttribute('data-ly') + '"] ' : ov ? '[data-ov="' + ov.getAttribute('data-ov') + '"] ' : '') +
      a.tagName.toLowerCase() + attrs.map(function (x) { return '[' + x.name + '="' + CSS.escape(x.value) + '"]'; }).join('');
  }
  function rerender() {
    var f = focusKey();
    S.savedNote = null;
    renderBuilder(); renderBar(); U.changed();
    var n = f && el.body.querySelector(f);
    if (n) n.focus({ preventScroll: true });
  }
  function renderSummary() { var s = el.body.querySelector('.b-sum'); if (s) s.innerHTML = summary(S.draft); }

  function addOutput(kind) {
    var d = S.draft;
    d.preset = null;
    if (!S.openOut) S.openOut = new WeakSet();
    // a new output opens, so its settings are in view
    if (kind === 'passthrough') { var p = original(); p.name = unique(d, 'source'); d.outputs.unshift(p); S.openOut.add(p); rerender(); return; }
    if (kind === 'audio') { var a = audioOnly(); a.name = unique(d, 'audio'); d.outputs.push(a); S.openOut.add(a); rerender(); return; }
    // the next standard size below the smallest video output there is, else the largest not used yet
    var have = d.outputs.filter(function (o) { return o.kind === 'video'; }).map(function (o) { return o.height; });
    var low = have.filter(function (h) { return h > 0; }).reduce(function (m, h) { return Math.min(m, h); }, Infinity);
    var next = SIZES.filter(function (s) { return have.indexOf(s[0]) < 0 && s[0] < low; })[0] || SIZES.filter(function (s) { return have.indexOf(s[0]) < 0; })[0];
    if (!next) { U.toast('Every standard size is there already. Change a size on a card, or pick Other height.'); return; }
    var o = out(next[0]);
    o.name = unique(d, o.name);
    var at = d.outputs.length;
    while (at > 0 && d.outputs[at - 1].kind === 'audio') at--;
    d.outputs.splice(at, 0, o); S.openOut.add(o);
    // a new rendition joins the first adaptive group, as most ladders want
    if (d.groups[0]) d.groups[0].members.push({ output: o.name, only: '' });
    rerender();
  }
  function unique(d, b) {
    var n = b, i = 2;
    while (d.outputs.some(function (o) { return o.name.toLowerCase() === n.toLowerCase(); })) n = b + '-' + i++;
    return n;
  }

  // the problems a save would be refused for, in words (the module checks them again)
  function problem(d) {
    if (d.isNew) {
      if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(d.name) || d.name.indexOf('..') >= 0) return 'Name the template: letters, digits, dot, dash and underscore.';
      if ((U.templates || []).some(function (t) { return t.name === d.name; })) return 'A template named ' + d.name + ' exists. Choose another name.';
      if ((U.appStreams || []).some(function (s) { return s.name === d.name; })) return d.name + ' is a stream name; that stream would pick the template up directly. Choose another name.';
    }
    if (!d.outputs.some(function (o) { return o.enabled; })) return 'Every output is off.';
    var seen = {};
    for (var i = 0; i < d.outputs.length; i++) {
      var o = d.outputs[i];
      if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/.test(o.name)) return 'Output name "' + o.name + '": letters, digits, dash and underscore.';
      if (seen[o.name.toLowerCase()]) return 'Two outputs are named ' + o.name + '.';
      seen[o.name.toLowerCase()] = 1;
      if (o.kind !== 'video') continue;
      if (o.videoBitrate < 50000 || o.videoBitrate > 100000000) return 'Output ' + o.name + ': video bitrate 50 to 100,000 kbps.';
      if (o.height !== 0 && (o.height < 120 || o.height > 4320)) return 'Output ' + o.name + ': height 120 to 4320.';
      if (o.height && o.fit !== 'fit-height' && o.width < 120) return 'Output ' + o.name + ': width of at least 120 for ' + o.fit + '.';
      if (o.rateControl === 'vbr' && o.peakBitrate && o.peakBitrate < o.videoBitrate) return 'Output ' + o.name + ': the peak is below the bitrate.';
      if (o.region && (o.region.w < 16 || o.region.h < 16)) return 'Output ' + o.name + ': the region is at least 16 x 16.';
      for (var p = 0; p < o.params.length; p++) if (o.params[p].name && !/^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)+$/.test(o.params[p].name)) return 'Output ' + o.name + ': parameter "' + o.params[p].name + '" is prefix.name.';
    }
    var gseen = {};
    for (var g = 0; g < d.groups.length; g++) {
      var gn = d.groups[g].name;
      if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/.test(gn)) return 'Group name "' + gn + '": letters, digits, dash and underscore.';
      if (gseen[gn.toLowerCase()] || seen[gn.toLowerCase()]) return 'Group ' + gn + ' has the name of another group or output.';
      gseen[gn.toLowerCase()] = 1;
    }
    var vids = videoOutputs(d).map(function (o) { return o.name; }), named = {};
    if (d.overlays.length > MAX_OVERLAYS) return 'A template carries at most ' + MAX_OVERLAYS + ' overlays. Combine several layers in one overlay in the Overlays tab.';
    for (var x = 0; x < d.overlays.length; x++) {
      var ov = d.overlays[x];
      if (!ov.name) return 'Pick an overlay for each overlay card, or remove the empty one.';
      if (named[ov.name]) return 'Overlay ' + ov.name + ' is in this template twice; a template carries each overlay once.';
      named[ov.name] = 1;
      if (!vids.length) return 'No video output to draw overlay ' + ov.name + ' on.';
      if (ovStage(d, ov) === 'outputs' && !ov.outputs.some(function (n) { return vids.indexOf(n) >= 0; })) return 'Overlay ' + ov.name + ' is on chosen outputs, and none is chosen.';
    }
    // the "Original + overlay copy" preset makes its second output for an overlay
    var copyOut = d.preset === 0 && d.outputs[1];
    if (copyOut && !onOutput(d, copyOut).length) return 'Add an overlay for the overlay output, or pick another start.';
    for (var l = 0; l < (d.images || []).length; l++) {
      var im = d.images[l];
      if (im.where === 'outputs' && !im.outputs.some(function (n) { return vids.indexOf(n) >= 0; })) return 'Image ' + im.file + ' is on chosen outputs, and none is chosen.';
      if (im.opacity > 100) return 'Image ' + im.file + ': opacity is 0 to 100.';
    }
    return null;
  }

  /* ---------- a stream's own copy of a master: read-only, updated from the master ---------- */
  function renderCopy(t) {
    var base = U.copyBase(t), m = find(base), s = U.streamRow(t.name);
    el.body.innerHTML = '<div class="note"><span class="dot ' + (s && s.publishing ? 'live' : '') + '"></span><div><b>' + U.esc(t.name) + ' runs this copy of ' + U.esc(base) + '.</b> ' +
      (m ? 'It changes only when it is updated from ' + U.esc(base) + ', which replaces anything changed in it. To change it, edit the master and update.' :
        U.esc(base) + ' no longer exists, so this copy cannot be updated. Deleting it makes ' + U.esc(t.name) + ' use the application\'s template order when it next starts.') +
      '<div class="hrow" style="margin-top:8px">' + (m ? '<button type="button" class="btn sm" data-go="template:' + U.esc(base) + '">Open ' + U.esc(base) + '</button>' : '<span class="bdg warn">' + U.esc(base) + ' no longer exists</span>') +
      (m && !ro() ? '<button type="button" class="btn sm" data-write data-cupd>Update from ' + U.esc(base) + '</button>' : '') +
      '<button type="button" class="btn sm ghost" data-go="stream:' + U.esc(t.name) + '">Open stream ' + U.esc(t.name) + '</button></div></div></div>' + outputsTable(t);
    var up = el.body.querySelector('[data-cupd]');
    if (up) up.onclick = function () { updateCopies(base, [t.name]); };
  }

  /* ---------- another template: read-only, with "Use with an overlay" and "New master from this one" ---------- */
  function outputsTable(t) {
    var outs = fromTemplate(t).outputs;
    return outputsHtml(t);
  }
  // a template's outputs, read-only, with the overlays each carries
  function outputsHtml(t) {
    var outs = fromTemplate(t).outputs;
    return '<div><div class="sec-h">Outputs</div><table class="tbl"><thead><tr><th>Output</th><th>Picture</th><th>Video</th><th>Audio</th><th>Carries</th></tr></thead><tbody>' +
      (t.encodes || []).map(function (e) {
        var o = outs.filter(function (x) { return x.name === e.name; })[0], on = carriedOn(t, e);
        var video = /^passthru$/i.test(e.codec || '') ? 'Passes through' : /^disable/i.test(e.codec || '') ? 'None' : U.esc(e.codec || '') + (o && o.videoBitrate ? ', ' + kbps(o.videoBitrate) : '');
        return '<tr data-carries="' + U.esc(e.name) + '"><td><b>' + U.esc(e.name) + '</b>' + (e.enabled ? '' : ' <span class="faint">off</span>') + '</td><td class="muted">' + U.esc(o ? sizeLabel(o) : '') + '</td><td>' + video + '</td><td>' + U.esc(e.audioCodec || '') + '</td>' +
          '<td>' + (/^passthru$/i.test(e.codec || '') ? '<span class="tgl-text">Not encoded</span>' : '<span class="tgl-text">' + (on.length ? U.esc(on.join(', ')) : 'No overlay') + '</span>') + '</td></tr>';
      }).join('') + '</tbody></table>' + U.roNote() + '</div>';
  }
  function renderOther(t) {
    var v = verdict(t), can = carriers(t).length > 0 && !ro() && group(t) === 'other';
    var h = '<div class="note ' + (v.cls === 'on' ? 'on' : v.cls === 'warn' ? 'warn' : '') + '"><span class="dot ' + (v.cls === 'on' ? 'on' : v.cls === 'warn' ? 'warn' : '') + '"></span><div><b>' + v.text + '.</b> ' + v.long + '</div></div>' +
      outputsHtml(t);
    if (can) h += useHtml(t);
    else if (!ro() && group(t) === 'other') h += '<p class="small muted">It can still be used on a stream as it is, from the stream view.</p>';
    el.body.innerHTML = h;
    if (can) bindUse(t);
  }
  // a new template built from t, with the overlays t carries and no others
  function copyOf(t) {
    var d = fromTemplate(t);
    d.isNew = true; d.name = freeName(t.name + '-overlay');
    return d;
  }
  function freeName(b) { return unique({ outputs: all().map(function (x) { return { name: x.name }; }) }, b); }

  // "Use with an overlay": a copy carrying the overlay, or overlays switched on in the template itself
  function useHtml(t) {
    var c = carriers(t), apps = t.listedBy || [];
    var u = S.use || (S.use = { how: 'copy', name: freeName(t.name + '-overlay'), overlay: ((U.overlays || [])[0] || {}).name || '', encodes: c.map(function (e) { return e.name; }), error: null });
    if (!(U.overlays || []).length) return '<div class="note"><span class="dot"></span><div><b>Use it with an overlay.</b> Make an overlay first; then this template can carry it as a copy or in place. <button type="button" class="btn sm" data-go="new-overlay:">New overlay</button></div></div>';
    return '<div><div class="sec-h">Use it with an overlay</div><div class="stack">' +
      '<label class="opt"><input type="radio" name="ovs-use" value="copy"' + (u.how === 'copy' ? ' checked' : '') + '><span><strong>Make a copy with the overlay</strong>' +
        '<span class="hint">Writes <code>' + U.esc(u.name || '<name>') + '.xml</code>, which you can then change in the builder. <code>' + U.esc(t.template) + '</code> stays as it is.</span></span></label>' +
      (t.shipped ? '' : '<label class="opt"><input type="radio" name="ovs-use" value="inplace"' + (u.how === 'inplace' ? ' checked' : '') + '><span><strong>Change ' + U.esc(t.name) + ' itself</strong>' +
        '<span class="hint">Switches on dynamic overlays in <code>' + U.esc(t.template) + '</code> after a backup; uninstall puts it back. Templates are shared by every application on the server' +
        (apps.length ? ': ' + U.esc(apps.join(', ')) + ' list it.' : '; no application lists it now.') + '</span></span></label>') + '</div>' +
      '<div class="grid2" style="margin-top:12px"><div class="fld"><label class="f" for="ovs-use-ov">Overlay</label><select id="ovs-use-ov" data-use-ov>' + overlayOptions(u.overlay) + '</select></div>' +
      (u.how === 'copy' ? '<div class="fld"><label class="f" for="ovs-use-n">Name of the copy</label><input id="ovs-use-n" type="text" data-use-n value="' + U.esc(u.name) + '" spellcheck="false"></div>' : '<div></div>') + '</div>' +
      '<div class="fld"><span class="f">Outputs that carry it</span><div class="hrow">' + c.map(function (e) {
        return '<label class="check"><input type="checkbox" data-use-enc="' + U.esc(e.name) + '"' + (u.encodes.indexOf(e.name) >= 0 ? ' checked' : '') + '>' + U.esc(e.name) + '</label>'; }).join('') + '</div>' +
      '<div class="hint">Outputs that pass video through cannot show an overlay and are not listed.</div></div>' +
      (u.error ? '<p class="err">' + U.esc(u.error) + '</p>' : '') +
      '<div class="hrow"><span class="spacer"></span><button type="button" class="btn primary" data-use-go>' + (u.how === 'copy' ? 'Create the copy' : 'Switch overlays on') + '</button></div></div>';
  }
  function bindUse(t) {
    var u = S.use, c = carriers(t);
    el.body.querySelectorAll('input[name=ovs-use]').forEach(function (r) { r.onchange = function () { u.how = r.value; u.error = null; renderOther(t); }; });
    var ov = el.body.querySelector('[data-use-ov]'); if (ov) ov.onchange = function () { u.overlay = ov.value; };
    var n = el.body.querySelector('[data-use-n]'); if (n) n.oninput = function () { u.name = n.value.trim().replace(/\.xml$/i, ''); };
    el.body.querySelectorAll('[data-use-enc]').forEach(function (b) { b.onchange = function () { var e = b.getAttribute('data-use-enc'); u.encodes = u.encodes.filter(function (x) { return x !== e; }); if (b.checked) u.encodes.push(e); }; });
    var go = el.body.querySelector('[data-use-go]');
    if (go) go.onclick = function () {
      u.error = !u.encodes.length ? 'Pick at least one output.' :
        u.how === 'copy' && (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(u.name) || u.name.indexOf('..') >= 0) ? 'Name: letters, digits, dot, dash and underscore.' :
        u.how === 'copy' && (U.templates || []).some(function (x) { return x.name === u.name; }) ? 'A template named ' + u.name + ' exists.' : null;
      if (u.error) { renderOther(t); return; }
      go.disabled = true;
      var body = { how: u.how, name: u.name, overlay: u.overlay, encodes: u.encodes.length === c.length ? [] : u.encodes };
      U.api.post(vh() + '/templates/' + U.enc(t.name) + '/use', body).then(function (r) {
        var made = String(r.template || '').replace(/\.xml$/, '');
        U.toast(u.how === 'copy' ? 'Made ' + made + ', a copy of ' + t.name + ' carrying ' + u.overlay + '. Use it on a stream from the stream view.'
          : 'Switched on overlays in ' + t.name + '; it carries ' + u.overlay + '. The original is backed up.');
        S.use = null;
        return U.refreshState(true).then(function () { U.go('template:' + (made || t.name)); });
      }, function (e) { go.disabled = false; u.error = e.message; renderOther(t); });
    };
  }

  /* ---------- a customer's template changed in place: only its overlay and outputs ---------- */
  // the overlays it carries, each with the outputs that carry it, by name: every carrying output when it is on all
  function savedForm(t) {
    var c = carriers(t).map(function (e) { return e.name; });
    return { overlays: tplSlots(t).map(function (x) { return { name: x.name, on: ((x.encodes || []).length ? x.encodes.slice() : c.slice()).sort() }; }) };
  }
  function renderOverlayOnly(t) {
    var c = carriers(t), f = S.form || (S.form = savedForm(t)), b = S.base ? JSON.parse(S.base) : savedForm(t);
    var every = function (x) { return x.on.length === c.length; };
    if (ro()) {
      el.body.innerHTML = '<dl class="kv">' + (b.overlays.length ? b.overlays.map(function (x) { return '<dt>' + U.esc(x.name) + '</dt><dd>' + U.esc(every(x) ? 'Every output' : x.on.join(', ')) + '</dd>'; }).join('') : '<dt>Overlays</dt><dd>None</dd>') + '</dl>' + U.roNote();
      return;
    }
    var full = f.overlays.length >= MAX_OVERLAYS;
    el.body.innerHTML = '<div class="sec-h">Overlays drawn by this template' + U.tip('Up to ' + MAX_OVERLAYS + '; a later one draws over an earlier one on the same output. Live on save for streams that load this file.') + '</div>' +
      '<div class="stack io-list">' + f.overlays.map(function (x, i) {
        var was = b.overlays[i], changed = !was || was.name !== x.name;
        return '<div class="lyc lyr lyu" data-io="' + i + '"><div class="lyc-top"><div class="ly-thumb">' + U.icon('overlay') + '</div>' +
          '<div class="ly-main"><select data-io-ov="' + i + '" aria-label="Overlay ' + (i + 1) + '"' + (changed ? ' class="is-draft"' : '') + '>' + overlayOptions(x.name, x.name ? null : 'Pick an overlay', f.overlays, x.name) + '</select>' +
          '<span class="small muted">' + (changed && was ? 'Saved: ' + U.esc(was.name) : 'Overlay') + '</span></div>' +
          '<div class="ly-acts">' + (i > 0 ? '<button class="btn sm icon ghost" type="button" data-io-up="' + i + '" aria-label="Move ' + U.esc(x.name || 'overlay') + ' down the stack">' + U.icon('up') + '</button>' : '') +
          '<button class="btn sm icon ghost" type="button" data-io-rm="' + i + '" aria-label="Remove overlay ' + U.esc(x.name || (i + 1)) + '">' + U.icon('close') + '</button></div></div>' +
          '<div class="lyc-row"><span class="f">On</span><div class="ly-outs">' + c.map(function (e) {
            var on = x.on.indexOf(e.name) >= 0;
            return '<label class="pilltog' + (on ? ' on' : '') + '"><input type="checkbox" data-io-enc="' + i + '" value="' + U.esc(e.name) + '"' + (on ? ' checked' : '') + '>' + U.esc(e.name) + '</label>'; }).join('') + '</div></div>' +
          (!x.on.length ? '<span class="bdg warn">Pick at least one output</span>' : '') + '</div>';
      }).join('') + '</div>' +
      '<div class="hrow ly-add">' + ((U.overlays || []).length ? '<button class="btn sm ghost" type="button" data-io-add' + (full ? ' disabled title="At most ' + MAX_OVERLAYS + ' overlays in a template. Combine several layers in one overlay in the Overlays tab."' : '') + '>' + U.icon('plus') + 'Overlay</button>'
        : '<button class="btn sm ghost" type="button" data-write data-go="new-overlay:">' + U.icon('plus') + 'Overlay</button>') +
      (f.overlays.length ? '' : '<span class="small faint">None</span>') + '</div>' +
      '<p class="small muted">Its outputs are changed where it was written, for example in WSE Manager. To build on it here, use New template from this one in the menu.</p>';
    function changed() { S.savedNote = null; renderOverlayOnly(t); renderBar(); U.changed(); }
    el.body.querySelectorAll('[data-io-ov]').forEach(function (s) { s.onchange = function () { f.overlays[+s.getAttribute('data-io-ov')].name = s.value; changed(); }; });
    el.body.querySelectorAll('[data-io-enc]').forEach(function (x) {
      x.onchange = function () {
        var o = f.overlays[+x.getAttribute('data-io-enc')];
        o.on = o.on.filter(function (n) { return n !== x.value; });
        if (x.checked) o.on.push(x.value);
        o.on.sort(); changed();
      };
    });
    el.body.querySelectorAll('[data-io-rm]').forEach(function (x) { x.onclick = function () { f.overlays.splice(+x.getAttribute('data-io-rm'), 1); changed(); }; });
    el.body.querySelectorAll('[data-io-up]').forEach(function (x) {
      x.onclick = function () { var i = +x.getAttribute('data-io-up'), a = f.overlays[i]; f.overlays[i] = f.overlays[i - 1]; f.overlays[i - 1] = a; changed(); };
    });
    var add = el.body.querySelector('[data-io-add]');
    if (add) add.onclick = function () {
      var have = f.overlays.map(function (x) { return x.name; });
      f.overlays.push({ name: ((U.overlays || []).filter(function (o) { return have.indexOf(o.name) < 0; })[0] || {}).name || '', on: c.map(function (e) { return e.name; }).sort() });
      changed();
    };
  }
  // why the in-place form cannot be saved, or null
  function formProblem(f) {
    var seen = {};
    for (var i = 0; i < f.overlays.length; i++) {
      var x = f.overlays[i];
      if (!x.name) return 'Pick an overlay for each overlay, or remove the empty one.';
      if (seen[x.name]) return 'Overlay ' + x.name + ' is in this template twice; a template carries each overlay once.';
      seen[x.name] = 1;
      if (!x.on.length) return 'Overlay ' + x.name + ': pick at least one output.';
    }
    return f.overlays.length > MAX_OVERLAYS ? 'A template carries at most ' + MAX_OVERLAYS + ' overlays.' : null;
  }

  /* ---------- changes, the save bar, saving ---------- */
  function currentJson() {
    if (S.draft) return JSON.stringify(spec(S.draft));
    if (S.form) return JSON.stringify(S.form);
    return null;
  }
  function changes() {
    if (ro()) return 0;
    if (S.kind === 'new') return newChanged(S.draft, S.base) ? 1 : 0;
    var c = currentJson();
    return c != null && S.base != null && c !== S.base ? 1 : 0;
  }
  function newChanged(d, b) { return !!d && (!!d.name || b == null || JSON.stringify(spec(d)) !== b); }
  var WORDS = { videoBitrate: 'video bitrate', height: 'size', width: 'width', fit: 'fit', codec: 'codec', profile: 'profile', rateControl: 'rate control', peakBitrate: 'peak',
    speed: 'speed', keyFollow: 'keyframes', keyInterval: 'keyframes', skip: 'frame rate', audio: 'audio', audioBitrate: 'audio bitrate', channels: 'channels', sampleRate: 'sample rate',
    captions: 'captions', params: 'expert parameters', overlay: 'carries the overlay', enabled: 'on or off', region: 'region', hdrToSdr: 'HDR to SDR', kind: 'kind' };
  // the changes in words, for the save bar
  function describe() {
    if (S.kind === 'new') return ['new template ' + (S.draft.name || '(no name yet)')];
    var a = S.base ? JSON.parse(S.base) : {}, b = JSON.parse(currentJson() || '{}'), out = [];
    if (S.form) return overlayWords(a.overlays, b.overlays);
    // each overlay with the outputs it is on, none for every output, whichever slot it is saved in
    var place = function (sp) {
      var vids = (sp.outputs || []).filter(function (o) { return o.kind === 'video' && o.enabled; }), on = vids.filter(function (o) { return o.overlay; }).map(function (o) { return o.name; });
      return [{ name: sp.overlay, on: on.length === vids.length ? [] : on }].concat((sp.overlays || []).map(function (x) { return { name: x.name, on: x.encodes }; })).filter(function (x) { return x.name; });
    };
    out = overlayWords(place(a), place(b));
    if (a.hardware !== b.hardware) out.push('hardware ' + b.hardware);
    var ao = a.outputs || [], bo = b.outputs || [];
    bo.forEach(function (o) {
      var s = ao.filter(function (x) { return x.name === o.name; })[0];
      if (!s) { out.push('add ' + o.name); return; }
      var w = {};
      Object.keys(o).forEach(function (k) { if (k !== 'overlay' && JSON.stringify(o[k]) !== JSON.stringify(s[k])) w[WORDS[k] || k] = 1; });
      if (Object.keys(w).length) out.push(o.name + ' ' + Object.keys(w).join(', '));
    });
    ao.forEach(function (s) { if (!bo.some(function (o) { return o.name === s.name; })) out.push('remove ' + s.name); });
    if (JSON.stringify(a.images) !== JSON.stringify(b.images)) out.push('images');
    if (JSON.stringify(a.groups) !== JSON.stringify(b.groups)) out.push('adaptive groups');
    if (a.deinterlace !== b.deinterlace || a.gpuId !== b.gpuId || a.minFrameRate !== b.minFrameRate) out.push('decode');
    return out.length ? out : ['unsaved edits'];
  }
  // overlays added, removed, moved to other outputs or reordered, in words; a and b are [{name, on}]
  function overlayWords(a, b) {
    var out = [], an = a.map(function (x) { return x.name; }), bn = b.map(function (x) { return x.name; });
    b.forEach(function (x) {
      var was = a[an.indexOf(x.name)];
      if (!was) out.push('add overlay ' + x.name);
      else if (JSON.stringify(was.on) !== JSON.stringify(x.on)) out.push('where ' + x.name + ' draws');
    });
    an.forEach(function (n) { if (bn.indexOf(n) < 0) out.push('remove overlay ' + n); });
    var kept = function (l, other) { return l.filter(function (n) { return other.indexOf(n) >= 0; }).join(); };
    if (kept(an, bn) !== kept(bn, an)) out.push('overlay order');
    return out;
  }
  function impact() {
    if (S.kind === 'new') return 'Writes transcoder/templates/' + (S.draft.name || '<name>') + '.xml. No stream uses it until you use it on a stream or add it to the template order.';
    var users = U.streamsUsing(S.sel), ord = users.filter(function (s) { return !s.own; }), copies = users.filter(function (s) { return s.own; });
    var on = ord.filter(function (s) { return s.publishing; }).map(function (s) { return s.name; }), off = ord.filter(function (s) { return !s.publishing; }).map(function (s) { return s.name; });
    return (on.length ? 'Overlay changes live now on ' + on.join(', ') + '; outputs wait for a restart. ' : 'No stream that follows it is publishing. ') +
      (off.length ? off.join(', ') + ' gets it when it publishes. ' : '') + (copies.length ? copies.map(function (s) { return s.template; }).join(', ') + ' will not change.' : '');
  }
  function renderBar() {
    if (!el.bar || U.current() !== 'template') return;
    var n = changes();
    U.saveBar(el.bar, { changes: n ? describe() : [], impact: impact(), conflict: S.conflict, saving: S.saving,
      problem: !n ? null : S.draft ? problem(S.draft) : S.form ? formProblem(S.form) : null, saveLabel: S.kind === 'new' ? 'Create template' : 'Save', saved: S.savedNote,
      onSave: save, onDiscard: discard,
      onTheirs: function () { S.conflict = false; delete mem[S.sel]; var t = find(S.sel); S.known = t.modified; loadFrom(t); renderBody(); renderBar(); U.changed(); },
      onMine: function () { S.conflict = false; S.known = S.theirs; renderBar(); U.toast('Keeping your version. Save replaces the one saved in the other session.'); } });
  }
  function discard() {
    S.conflict = false; S.savedNote = null; S.pick = false;
    if (S.kind === 'new') { delete mem['']; S.draft = null; S.kind = null; U.toast('Discarded the new template.'); U.changed(); U.stayIn('templates'); return; }
    delete mem[S.sel];
    var t = find(S.sel);
    if (t) loadFrom(t);
    renderBody(); renderBar(); U.changed(); U.toast('Discarded the unsaved changes to ' + S.sel + '.');
  }
  function startNew(draft) {
    stash();
    if (draft) mem[''] = { draft: draft, base: null };
    U.go('template:');
  }

  function save() {
    if (!changes() || S.saving || S.conflict || ro()) return Promise.resolve(!changes());
    var t = S.sel && find(S.sel), req, label, isNew = S.kind === 'new';
    if (S.draft) {
      var why = problem(S.draft);
      if (why) { U.toast(why, { level: 'bad' }); return Promise.resolve(false); }
      var body = spec(S.draft);
      label = S.draft.name;
      req = isNew ? U.api.post(vh() + '/templates', body) : U.api.post(vh() + '/templates/' + U.enc(label) + '/spec', body);
    } else {
      var f = S.form, bad = formProblem(f), every = carriers(t).length;
      if (bad) { U.toast(bad, { level: 'bad' }); return Promise.resolve(false); }
      label = t.name;
      req = U.api.post(vh() + '/templates/' + U.enc(label) + '/overlay', { overlays: f.overlays.map(function (x, i) { return { name: x.name, encodes: x.on.length === every ? [] : x.on, slot: i + 1 }; }) });
    }
    S.saving = true; renderBar();
    var copies = isNew ? [] : U.streamsUsing(label).filter(function (s) { return s.own; });
    return req.then(function (res) {
      U.clearBanner('tpl');
      delete mem[isNew ? '' : label];
      // still saving until the new state is in, so the poll does not read this save as another session's
      return U.refreshState(true).then(function () {
        S.saving = false; S.conflict = false;
        if (isNew) { S.draft = null; U.toast('Created template ' + label + '. Use it on a stream from the stream view.'); U.go('template:' + label); }
        else {
          // the draft is saved now: without this, select() would stash it as unsaved work against the old base and
          // bring it back as a conflict with this very save
          S.kind = null;
          select(label); renderHead(); renderBody(); renderUsedBy();
          var live = ((res && res.live) || []).filter(function (x) { return x.application === U.app; });
          S.savedNote = { title: 'Saved at ' + U.hhmm() + ': ' + label + '.', detail: (live.length ? 'Live on ' + live.map(function (x) { return x.stream; }).join(', ') + '. ' : '') +
            (copies.length ? copies.map(function (s) { return s.name; }).join(', ') + (copies.length > 1 ? ' run their own copies' : ' runs its own copy') + ', not this save. Update in Used by.' : '') };
          renderBar();
        }
        U.changed();
        // running streams that loaded this file show the overlay now; outputs wait for a restart
        return U.afterTemplateWrite(res && res.live).then(function () { return isNew || !copies.length ? null : offerCopies(label, copies); });
      }).then(function () { return true; });
    }, function (e) { S.saving = false; renderBar(); U.banner('tpl', 'bad', 'Not saved.', e.message); return false; });
  }

  function deleteTemplate(name) {
    var t = name && find(name);
    if (!t || !t.managed) { U.toast(t ? t.name + ' was not made here, so it is not deleted from here.' : 'Pick a template made here first.'); return; }
    var users = U.streamsUsing(t.name), direct = users.filter(function (s) { return !s.own && s.publishing; });
    if (direct.length) {
      U.toast(direct.map(function (s) { return s.name; }).join(', ') + (direct.length > 1 ? ' are' : ' is') + ' publishing with ' + t.name + ' through the template order. Change the order or use another template on those streams first.', { level: 'bad' });
      return;
    }
    U.confirm('Delete template ' + t.name + '?', '<p>The file <code>transcoder/templates/' + U.esc(t.name) + '.xml</code> is deleted. This cannot be undone.</p>' +
      (inPlace(t) ? '<p>It is your own template with overlays switched on; its backup stays beside it.</p>' : '') +
      (users.length ? '<p>Streams with their own copy keep running their copy: ' + U.esc(users.map(function (s) { return s.name; }).join(', ')) + '.</p>' : ''), 'Delete template', true).then(function (ok) {
        if (!ok) return;
        var near = U.neighborOf('template:' + t.name);
        U.api.v2del('/transcoder/templates/' + U.enc(t.name)).then(function () {
          delete mem[t.name];
          var here = S.sel === t.name;
          if (here) { S.sel = null; S.kind = null; S.draft = null; S.base = null; }
          U.toast('Deleted template ' + t.name + '.');
          return U.refreshState(true).then(function () { if (here) U.stayIn('templates', near); });
        }, function (e) { U.banner('tpl', 'bad', 'Not deleted.', e.message); });
      });
  }

  U.rowActions.template = function (name, act) {
    var t = find(name);
    if (!t) return;
    if (act === 'dup') {
      var d = fromTemplate(t);
      d.isNew = true; d.name = freeName(t.name);
      if (group(t) === 'other') d = copyOf(t);
      startNew(d);
    } else if (act === 'del') deleteTemplate(name);
    else if (act === 'use') useOnStream(name);
  };
  // a master put on a stream: the stream gets its own copy, linked to the master, after the stream's dry run
  function useOnStream(name) {
    var list = U.streams().map(function (s) { return s.name; });
    if (!list.length) { U.toast('There is no stream yet. Streams show under Streams while they publish, or add one there.', { level: 'bad' }); return; }
    var pick = list[0];
    var p = U.choose('Use ' + name + ' on a stream', '<p>The stream gets its own copy of ' + U.esc(name) + ', linked to it. You see what changes on the stream before anything is written.</p>' +
      '<div class="fld"><label class="f" for="ovs-usestream">Stream</label><select id="ovs-usestream" data-usestream>' + list.map(function (n) { return '<option value="' + U.esc(n) + '">' + U.esc(n) + '</option>'; }).join('') + '</select></div>',
      [{ label: 'Cancel', value: false }, { label: 'Continue', value: true, kind: 'primary' }]);
    var sel = U.root.querySelector('[data-usestream]');
    if (sel) sel.onchange = function () { pick = sel.value; };
    p.then(function (ok) { if (ok) U.useTemplateOn(pick, name); });
  }
  U.rowActions.copy = function (name, act) {
    var c = findAny(name), base = c && U.copyBase(c);
    if (act === 'update' && base && find(base)) updateCopies(base, [name]);
    else if (act === 'stream') U.go('stream:' + name);
    else if (act === 'del') U.rowActions.stream(name, 'del');
  };

  U.screens.template = {
    init: function (box) {
      box.innerHTML = '<div class="pnl"><div class="ed-h" data-th></div><div class="pnl-b stack-4"><div data-reach></div><div data-tb></div><div data-used></div></div></div><div data-bar hidden></div>';
      el = { head: box.querySelector('[data-th]'), reach: box.querySelector('[data-reach]'), body: box.querySelector('[data-tb]'), used: box.querySelector('[data-used]'), bar: box.querySelector('[data-bar]') };
      mem = {};
    },
    show: function (name) {
      if (!name && ro()) { U.go('home:'); return; }
      if (name && !findAny(name)) { U.stayIn('templates'); return; }
      if (name !== S.sel || (name == null && S.kind !== 'new') || !S.kind) select(name || null);
      renderHead(); renderBody(); renderUsedBy(); renderBar();
      if (S.kind === 'new') { var n = el.body.querySelector('[data-d=name]'); if (n && !n.value) n.focus(); }
    },
    hide: function () { stash(); S.pick = false; },
    state: function () { if (U.current() === 'template') onState(); },
    app: function () { mem = {}; S.sel = null; S.kind = null; S.draft = null; S.form = null; S.base = null; S.conflict = false; S.lib = null; S.savedNote = null; },
    access: function () { if (U.current() === 'template') { renderHead(); renderBody(); renderUsedBy(); renderBar(); } },
    save: function () { save(); },
    draftInfo: draftInfo,
    unsaved: function () {
      if (ro()) return [];
      var out = [], cur = S.kind === 'new' ? '' : S.sel;
      if (S.kind && changes() && !mem[cur]) out.push({ label: S.kind === 'new' ? 'New template' + (S.draft && S.draft.name ? ' ' + S.draft.name : '') : S.sel, detail: describe().join(', '), go: 'template:' + (cur || '') });
      Object.keys(mem).forEach(function (n) {
        var k = mem[n], c = k.draft ? JSON.stringify(spec(k.draft)) : k.form ? JSON.stringify(k.form) : null;
        if (n === '' ? newChanged(k.draft, k.base) : c && c !== k.base) out.push({ label: n === '' ? 'New template' + (k.draft.name ? ' ' + k.draft.name : '') : n, go: 'template:' + n });
      });
      return out;
    }
  };
})();
