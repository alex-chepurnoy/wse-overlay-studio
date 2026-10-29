/* URL Overlay step 2, Template: every transcoder template on the server. One made here opens in the builder; any other is
   read-only, with "Use with an overlay" to make a copy or switch overlays on in place. Streams' own copies are not listed. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var el = {};
  var S = { sel: null, draft: null, base: null, known: null, theirs: null, conflict: false, saving: false, open: {} };
  var IN_PLACE = 'overlays switched on in place';
  // heights and default bitrates: 720, 360, 234 and 160 from WSE's transcode.xml; 1080 and 540 are URL Overlay's choice
  var SIZES = [[1080, '1080p', 5000000, 'high'], [720, '720p', 3000000, 'high'], [540, '540p', 1500000, 'high'],
    [360, '360p', 365000, 'high'], [234, '240p', 145000, 'baseline'], [160, '160p', 105000, 'baseline']];

  function vh() { return U.vhostPath(); }
  function inPlace(t) { return (t.description || '').indexOf(IN_PLACE) >= 0; }
  function all() { return (U.templates || []).filter(function (t) { return t && !t.error && !U.copyBase(t); }); }
  function find(name) { return all().filter(function (t) { return t.name === name; })[0]; }
  function group(t) { return t.vif ? 'na' : (t.overlay || t.managed) ? 'mine' : 'other'; }
  function size(h) { return SIZES.filter(function (s) { return s[0] === h; })[0]; }
  function streamsUsing(name) {
    return (U.appStreams || []).filter(function (s) { return s.template === name + '.xml' || (s.own && U.copyBase(s) === name); });
  }
  function carriers(t) { return (t.encodes || []).filter(function (e) { return e.canCarryOverlay; }); }
  function verdict(t) {
    if (!carriers(t).length) return { cls: '', text: 'Cannot carry an overlay', long: 'It has no enabled output that encodes video, so there is nothing to draw an overlay on.' };
    if (!t.dynamicOverlays) return { cls: 'warn', text: 'Needs overlays switched on', long: 'Dynamic overlays are off in this template, so a URL overlay cannot show on its streams yet.' };
    return { cls: 'ok', text: 'Ready for an overlay', long: 'Dynamic overlays are on, and it has outputs an overlay can be drawn on.' };
  }
  function num(v, d) { var n = parseInt(v, 10); return isNaN(n) ? d : n; }

  /* ---------- the builder's model ---------- */
  // the whole template's hardware
  var HW = [['cpu', 'CPU', 'Decode, scale and encode on the server\'s CPU.'],
    ['nvidia', 'NVIDIA', 'NVIDIA GPU. Needs one on the server. With an overlay, frames are copied off the card to draw it, which costs more.'],
    ['intel', 'Intel', 'Intel Quick Sync. Needs it on the server. With an overlay, frames are copied off the chip to draw it, which costs more.'],
    ['netint', 'NETINT', 'NETINT Quadra card. Needs one on the server. With an overlay, frames are copied off the card to draw it, which costs more.']];
  var FPS = 30; // frames per second assumed for keyframes in seconds when the source's is not known
  function base() {
    return { name: '', kind: 'video', enabled: true, overlay: true, height: 720, width: 1280, fit: 'fit-height', region: null, skip: 0,
      codec: 'H.264', profile: 'high', videoBitrate: 3000000, rateControl: 'default', peakBitrate: 0, speed: 'default', keyFollow: false, keyInterval: 60,
      hdrToSdr: false, audio: 'aac', audioBitrate: 96000, channels: 0, sampleRate: 0, captions: true, params: [] };
  }
  function out(h, overlay) {
    var s = size(h) || [h, h + 'p', 3000000, 'high'], o = base();
    o.name = s[1]; o.height = h; o.width = Math.round(h * 16 / 9 / 2) * 2; o.videoBitrate = s[2]; o.profile = s[3]; o.overlay = overlay !== false;
    return o;
  }
  function original() { var o = base(); o.name = 'source'; o.kind = 'passthrough'; o.overlay = false; o.audio = 'passthru'; o.audioBitrate = 0; return o; }
  function audioOnly() { var o = base(); o.name = 'audio'; o.kind = 'audio'; o.overlay = false; return o; }
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
  function blank(overlay) {
    var outs = PRESETS[2][1]();
    return { isNew: true, name: '', overlay: overlay || '', hardware: 'cpu', deinterlace: false, gpuId: -1, minFrameRate: 0, outputs: outs, groups: defaultGroups(outs), images: [], preset: 2 };
  }
  // what the Template step shows for an existing template, from the validator's reading in the live state
  function fromTemplate(t) {
    var listed = t.overlayEncodes || [];
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
      o.overlay = !!t.overlay && e.canCarryOverlay && (!listed.length || listed.indexOf(e.name) >= 0);
      return o;
    });
    return { isNew: false, name: t.name, overlay: t.overlay || '', hardware: t.hardware || 'cpu', deinterlace: !!t.deinterlace, gpuId: num(t.gpuId, -1),
      minFrameRate: parseFloat(t.minFrameRate) || 0,
      images: (t.images || []).map(function (l) {
        return { file: l.file, where: l.where === 'outputs' ? 'outputs' : 'all', outputs: (l.outputs || []).slice(), x: num(l.x, 0), y: num(l.y, 0), width: num(l.width, 0),
          height: num(l.height, 0), align: l.align || 'left,top', opacity: num(l.opacity, 100), refresh: !!l.refresh };
      }),
      outputs: outs, preset: null, groups: (t.groups || []).map(function (g) {
        return { name: g.name, members: (g.members || []).map(function (m) { return { output: m.output, only: m.only || '' }; }) };
      }) };
  }
  function spec(d) {
    var vids = d.outputs.filter(function (o) { return o.kind === 'video' && o.enabled; }).map(function (o) { return o.name; });
    return { name: d.name, hardware: d.hardware, overlay: d.overlay || null, deinterlace: d.deinterlace, gpuId: d.gpuId, minFrameRate: d.minFrameRate || 0,
      images: (d.images || []).map(function (l) {
        return { file: l.file, where: l.where, outputs: l.where === 'outputs' ? l.outputs.filter(function (n) { return vids.indexOf(n) >= 0; }) : [], x: l.x, y: l.y,
          width: l.width, height: l.height, align: l.align, opacity: l.opacity, refresh: l.refresh };
      }),
      groups: d.groups.map(function (g) { return { name: g.name, members: g.members.filter(function (m) { return d.outputs.some(function (o) { return o.name === m.output && o.enabled; }); }) }; })
        .filter(function (g) { return g.members.length; }),
      outputs: d.outputs.map(function (o) {
        var v = o.kind === 'video';
        return { name: o.name, kind: o.kind, enabled: o.enabled, overlay: !!d.overlay && v && o.overlay, height: o.height, width: o.width, fit: o.fit, region: v ? o.region : null,
          skip: v ? o.skip : 0, codec: o.codec, profile: o.profile, videoBitrate: o.videoBitrate, rateControl: v && o.codec === 'H.264' ? o.rateControl : 'default',
          peakBitrate: o.rateControl === 'vbr' ? (o.peakBitrate || Math.round(o.videoBitrate * 1.5)) : 0, speed: v && o.codec === 'H.264' ? o.speed : 'default',
          keyFollow: o.keyFollow, keyInterval: o.keyInterval, hdrToSdr: v && o.hdrToSdr, audio: o.audio, audioBitrate: o.audioBitrate, channels: o.channels,
          sampleRate: o.sampleRate, captions: o.captions, params: v ? o.params.filter(function (p) { return p.name; }) : [] };
      }) };
  }

  /* ---------- the list ---------- */
  function load() {
    var list = all();
    if (S.sel && !S.draft && !find(S.sel)) { if (changes()) U.toast('Template ' + S.sel + ' was deleted in another session.', { level: 'bad' }); S.sel = null; S.known = null; S.base = null; }
    if (!S.sel && !S.draft) {
      var mine = list.filter(function (t) { return group(t) === 'mine'; });
      var first = mine.filter(function (t) { return t.overlay; })[0] || mine[0] || list.filter(function (t) { return group(t) === 'other'; })[0];
      if (first) { select(first.name); return; }
      renderList(); renderEdit(); renderBar(); return;
    }
    var t = S.sel && find(S.sel);
    if (t && S.known != null && t.modified !== S.known && !S.saving) {
      if (changes()) { S.conflict = true; S.theirs = t.modified; }
      else { S.known = t.modified; open(t); renderEdit(); }
    }
    renderList(); renderBar();
  }
  function select(name) {
    var t = name && find(name);
    S.sel = t ? name : null; S.conflict = false; S.draft = null; S.known = t ? t.modified : null;
    open(t);
    renderList(); renderEdit(); renderBar();
  }
  // a template made here, not changed in place, opens in the builder
  function open(t) {
    S.draft = t && group(t) === 'mine' && !inPlace(t) ? fromTemplate(t) : null;
    S.base = S.draft ? JSON.stringify(spec(S.draft)) : null;
    if (t && inPlace(t)) S.base = JSON.stringify(savedForm(t));
  }
  function startNew(draft) {
    S.sel = null; S.known = null; S.conflict = false;
    S.draft = draft || blank(((U.overlays || [])[0] || {}).name);
    S.base = null;
    renderList(); renderEdit(); renderBar();
    var n = el.edit.querySelector('[data-d=name]');
    if (n) n.focus();
  }

  function renderList() {
    var list = all(), mine = list.filter(function (t) { return group(t) === 'mine'; }), other = list.filter(function (t) { return group(t) === 'other'; });
    var na = list.filter(function (t) { return group(t) === 'na'; });
    var h = '<div class="tpl-grp">With URL Overlay</div>' +
      (S.draft && S.draft.isNew ? '<div class="tpl-row sel"><strong>' + U.esc(S.draft.name || 'New template') + '</strong><div class="sub">New, not saved yet</div></div>' : '') +
      (mine.length ? mine.map(function (t) {
        var users = streamsUsing(t.name).map(function (s) { return s.name + (s.publishing ? ' (live)' : ''); });
        var on = carriers(t).filter(function (e) { return !(t.overlayEncodes || []).length || t.overlayEncodes.indexOf(e.name) >= 0; }).length;
        return row(t, t.overlay ? U.esc(t.overlay) + ' on ' + on + ' of ' + (t.encodes || []).length + ' outputs' : 'No overlay yet',
          users.length ? 'Used by ' + U.esc(users.join(', ')) : 'Not used by a stream yet');
      }).join('') : (S.draft && S.draft.isNew ? '' : '<p class="hint tpl-none">None yet. Make one with New template, or give one of the templates below an overlay.</p>')) +
      '<div class="tpl-grp">Other templates on this server</div>' +
      (other.length ? other.map(function (t) { var v = verdict(t); return row(t, '<span class="tag ' + v.cls + '">' + v.text + '</span>', t.shipped ? 'Ships with WSE' : ''); }).join('')
        : '<p class="hint tpl-none">None.</p>') +
      (na.length ? '<details class="tpl-na"><summary>Not available here (' + na.length + ')</summary>' + na.map(function (t) {
        return '<div class="tpl-row off"><strong>' + U.esc(t.name) + '</strong><div class="sub">A Video Intelligence template; a stream runs one or the other.</div></div>'; }).join('') + '</details>' : '');
    el.list.innerHTML = h;
    el.list.querySelectorAll('[data-t]').forEach(function (r) {
      r.onclick = function () { var n = r.getAttribute('data-t'); if (n === S.sel && !(S.draft && S.draft.isNew)) return; leave().then(function (ok) { if (ok) select(n); }); };
      r.onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); r.onclick(); } };
    });
  }
  function row(t, sub1, sub2) {
    var sel = t.name === S.sel && !(S.draft && S.draft.isNew);
    return '<div class="tpl-row' + (sel ? ' sel' : '') + '" data-t="' + U.esc(t.name) + '" tabindex="0" role="button" aria-pressed="' + sel + '"><strong>' + U.esc(t.name) + '</strong>' +
      '<div class="sub">' + sub1 + '</div>' + (sub2 ? '<div class="sub">' + sub2 + '</div>' : '') + '</div>';
  }

  /* ---------- the editor ---------- */
  function renderEdit() {
    var t = S.sel && find(S.sel);
    el.del.disabled = !(t && t.managed && !(S.draft && S.draft.isNew));
    if (S.draft) { renderBuilder(); return; }
    if (!t) {
      el.edit.innerHTML = '<h3>Make a template</h3><p class="muted">Build one from outputs, or pick a template on the left to give it an overlay.</p>' +
        '<button class="btn primary" type="button" data-act="new3">New template</button>';
      el.edit.querySelector('[data-act=new3]').onclick = function () { startNew(); };
      return;
    }
    if (inPlace(t)) { renderOverlayOnly(t); return; }
    renderOther(t);
  }

  function overlayOptions(cur, none) {
    var names = (U.overlays || []).map(function (o) { return o.name; });
    if (cur && names.indexOf(cur) < 0) names.unshift(cur);
    return (none ? '<option value="">' + none + '</option>' : '') + names.map(function (n) { return '<option' + (n === cur ? ' selected' : '') + '>' + U.esc(n) + '</option>'; }).join('');
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
  function card(o, i, d) {
    var v = o.kind === 'video', lit = d.overlay && v && o.overlay && o.enabled;
    var head = '<div class="oc-top"><input type="text" value="' + U.esc(o.name) + '" data-o="name" aria-label="Output name" spellcheck="false">' +
      '<label class="oc-on" title="Output on"><input type="checkbox" data-o="enabled"' + (o.enabled ? ' checked' : '') + '><span class="sr">Output on</span></label>' +
      '<button class="btn icon" type="button" data-rm title="Remove this output" aria-label="Remove output ' + U.esc(o.name) + '">' +
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<div class="oc-sum"><code>&lt;stream&gt;_' + U.esc(o.name) + '</code>' + (v && d.overlay ? '<label class="oc-ovl"><input type="checkbox" data-o="overlay"' + (o.overlay ? ' checked' : '') + '>' + U.esc(d.overlay) + '</label>' : '') + '</div>';
    var cls = 'oc' + (o.enabled ? '' : ' off') + (lit ? ' ov' : '');
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
        '<input type="text" data-param="' + pi + '" data-k="value" value="' + U.esc(p.value) + '" placeholder="value"><button class="btn icon" type="button" data-prm="' + pi + '" aria-label="Remove parameter">&#x2715;</button></div>'; }).join('') +
      '<button class="btn sm" type="button" data-addparam>+ Parameter</button>';
    var videoSum = o.codec + ' ' + kbps(o.videoBitrate) + (o.rateControl === 'cbr' ? ', constant' : o.rateControl === 'vbr' ? ', variable' : '');
    return '<div class="' + cls + '" data-i="' + i + '">' + head +
      '<div class="oc-thumb"><span>' + U.esc(sizeLabel(o)) + (o.region ? ', region' : '') + '</span>' + (lit ? '<i class="oc-ov">overlay</i>' : '') + '</div>' +
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
    return '<div class="b-play"><h4>Playback <button class="btn sm ghost" type="button" data-addgroup>+ Adaptive group</button></h4>' +
      (d.groups.length ? '<table class="list b-groups"><thead><tr><th>Output</th>' + d.groups.map(function (g, gi) {
        return '<th><input type="text" data-gname="' + gi + '" value="' + U.esc(g.name) + '" spellcheck="false" aria-label="Group name"><button class="btn icon" type="button" data-grm="' + gi + '" aria-label="Remove group ' + U.esc(g.name) + '">&#x2715;</button></th>'; }).join('') + '</tr></thead><tbody>' +
        outs.map(function (o) { return '<tr><td><code>_' + U.esc(o.name) + '</code></td>' + d.groups.map(function (g, gi) { return cell(g, gi, o); }).join('') + '</tr>'; }).join('') +
        '<tr><td class="small muted">Players open</td>' + d.groups.map(function (g) { return '<td class="small"><code>ngrp:&lt;stream&gt;_' + U.esc(g.name) + '</code></td>'; }).join('') + '</tr></tbody></table>'
        : '<p class="hint">No adaptive group. Players open each output by its own name.</p>') +
      '<p class="hint">An adaptive group is one stream name a player opens; it switches between the group\'s outputs as bandwidth allows. "Audio only" adds an output\'s audio as a separate track, for example a second language. <span class="key">StreamNameGroups</span></p></div>';
  }

  function summary(d) {
    var on = d.outputs.filter(function (o) { return o.enabled; });
    var ov = on.filter(function (o) { return d.overlay && o.kind === 'video' && o.overlay; });
    var warn = d.overlay && !ov.length ? ' <span class="warn-text">No output shows the overlay yet: turn it on for at least one.</span>' : '';
    return '<strong>' + on.length + (on.length === 1 ? ' output' : ' outputs') + (d.overlay ? ', ' + ov.length + ' with ' + U.esc(d.overlay) : '') + '.</strong> ' +
      (d.groups.length ? 'Players get ' + d.groups.map(function (g) { return '<code>ngrp:&lt;stream&gt;_' + U.esc(g.name) + '</code>'; }).join(', ') + ' and each output by name.' : 'Players open each output by name.') + warn;
  }

  /* ---------- layers: the URL overlay and the transcoder's own image overlays ---------- */
  var ALIGNS = [['left,top', 'Top left'], ['hcenter,top', 'Top center'], ['right,top', 'Top right'], ['left,vcenter', 'Middle left'], ['hcenter,vcenter', 'Center'],
    ['right,vcenter', 'Middle right'], ['left,bottom', 'Bottom left'], ['hcenter,bottom', 'Bottom center'], ['right,bottom', 'Bottom right']];
  var MAX_IMAGES = 8;
  // the image library (GET images), with previews; read once per page and after an upload
  function library(force) {
    if (S.lib && !force) return Promise.resolve(S.lib);
    return U.api.get(vh() + '/images').then(function (r) { S.lib = (r && r.images) || []; return S.lib; });
  }
  function libEntry(file) { return (S.lib || []).filter(function (x) { return x.file === file; })[0] || null; }
  function videoOutputs(d) { return d.outputs.filter(function (o) { return o.kind === 'video' && o.enabled; }); }

  function layersHtml(d) {
    var vids = videoOutputs(d), every = d.overlay && vids.length && !d.urlSome && vids.every(function (o) { return o.overlay; });
    var url = '<div class="ly-row"><div class="ly-thumb ly-page" aria-hidden="true">www</div><div class="ly-main"><strong>' + (d.overlay ? U.esc(d.overlay) : 'No URL overlay') + '</strong>' +
      '<div class="small muted">Live web pages from step 1. Choose the overlay at the top.</div></div>' +
      (d.overlay ? '<div><span class="f">Drawn on</span><select data-urlwhere>' + opts([['all', 'Every output, once before scaling'], ['some', 'Chosen outputs']], every ? 'all' : 'some') + '</select>' +
        (every ? '' : '<div class="hint">Tick it on each output card.</div>') + '</div>' : '') + '</div>';
    var imgs = (d.images || []).map(function (l, i) {
      var e = libEntry(l.file), pv = e && e.preview;
      var where = l.where === 'outputs' ? '<div class="ly-outs">' + vids.map(function (o) {
        return '<label class="check"><input type="checkbox" data-lyout="' + i + '" value="' + U.esc(o.name) + '"' + (l.outputs.indexOf(o.name) >= 0 ? ' checked' : '') + '>' + U.esc(o.name) + '</label>'; }).join('') + '</div>' : '';
      return '<div class="ly-row" data-ly="' + i + '"><div class="ly-thumb">' + (pv ? '<img src="' + U.esc(pv) + '" alt="">' : '<span>?</span>') + '</div>' +
        '<div class="ly-main"><strong>' + U.esc(l.file) + '</strong><div class="small muted">Image overlay, index ' + (i + 1) + (e ? '' : '. <span class="warn-text">Not found in the content folder.</span>') + '</div>' +
        '<div class="oc-f ly-f"><label>Drawn on<select data-ly-k="where">' + opts([['all', 'Every output, before scaling'], ['outputs', 'Chosen outputs']], l.where) + '</select></label>' +
        '<label>Position<select data-ly-k="align">' + opts(ALIGNS, l.align) + '</select></label>' +
        '<label>Offset x<input type="number" data-ly-k="x" min="0" max="8192" value="' + l.x + '"></label><label>Offset y<input type="number" data-ly-k="y" min="0" max="8192" value="' + l.y + '"></label>' +
        '<label>Width (0: its own)<input type="number" data-ly-k="width" min="0" max="8192" value="' + l.width + '"></label><label>Height (0: its own)<input type="number" data-ly-k="height" min="0" max="8192" value="' + l.height + '"></label>' +
        '<label>Opacity %<input type="number" data-ly-k="opacity" min="0" max="100" value="' + l.opacity + '"></label></div>' + where +
        '<label class="check"><input type="checkbox" data-ly-k="refresh"' + (l.refresh ? ' checked' : '') + '>Reload when the file changes</label></div>' +
        '<div class="ly-acts">' + (i ? '<button class="btn icon" type="button" data-lyup="' + i + '" aria-label="Move up">&#x25B2;</button>' : '') +
        '<button class="btn icon" type="button" data-lyrm="' + i + '" aria-label="Remove image layer">&#x2715;</button></div></div>';
    }).join('');
    return '<div class="b-layers"><h4>Layers <button class="btn sm ghost" type="button" data-addimg' + ((d.images || []).length >= MAX_IMAGES ? ' disabled' : '') + '>+ Image</button></h4>' + url + imgs +
      '<p class="hint">An image layer is a PNG, JPEG, GIF or BMP in the server\'s content folder. Offsets are pixels from the chosen corner, ' +
      'in source pixels when drawn before scaling and output pixels on a chosen output.</p></div>';
  }

  function bindLayers(d) {
    var uw = el.edit.querySelector('[data-urlwhere]');
    if (uw) uw.onchange = function () {
      d.urlSome = uw.value !== 'all';
      if (!d.urlSome) videoOutputs(d).forEach(function (o) { o.overlay = true; });
      rerender();
    };
    el.edit.querySelectorAll('[data-ly]').forEach(function (row) {
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
    el.edit.querySelectorAll('[data-lyout]').forEach(function (c) {
      c.onchange = function () { var l = d.images[+c.getAttribute('data-lyout')]; l.outputs = l.outputs.filter(function (n) { return n !== c.value; }); if (c.checked) l.outputs.push(c.value); rerender(); };
    });
    el.edit.querySelectorAll('[data-lyrm]').forEach(function (b) { b.onclick = function () { d.images.splice(+b.getAttribute('data-lyrm'), 1); rerender(); }; });
    el.edit.querySelectorAll('[data-lyup]').forEach(function (b) { b.onclick = function () { var i = +b.getAttribute('data-lyup'), t = d.images[i]; d.images[i] = d.images[i - 1]; d.images[i - 1] = t; rerender(); }; });
    var add = el.edit.querySelector('[data-addimg]');
    if (add) add.onclick = function () { pickImage(d); };
  }

  // the picker: images already on the server, an upload, and removing an upload
  function pickImage(d) {
    library(true).then(function (lib) {
      var grid = lib.length ? '<div class="ly-grid">' + lib.map(function (x, i) {
        return '<label class="ly-pick"><input type="radio" name="uo-img" value="' + U.esc(x.file) + '"' + (i ? '' : ' checked') + '>' + (x.preview ? '<img src="' + U.esc(x.preview) + '" alt="">' : '<span class="ly-noimg">?</span>') + '<span>' + U.esc(x.file) +
          '<small>' + U.esc(x.width) + ' x ' + U.esc(x.height) + '</small></span>' + (x.uploaded ? '<button class="btn icon" type="button" data-imgdel="' + U.esc(x.file) + '" aria-label="Delete ' + U.esc(x.file) + '">&#x2715;</button>' : '') + '</label>'; }).join('') + '</div>'
        : '<p class="muted">No image in the content folder yet. Upload one.</p>';
      U.prompt('Add an image layer', grid + '<div class="fld mt-m"><label class="f" for="uo-img-up">Upload an image (PNG, JPEG, GIF or BMP, up to 2 MB)</label><input id="uo-img-up" type="file" accept=".png,.jpg,.jpeg,.gif,.bmp"></div>' +
        '<p class="hint">Uploads go to the server\'s <code>content/urloverlay/</code> folder.</p>', 'Add layer', function (m) {
          // U.prompt shows a returned string as the error, so the choice goes back in an object
          var r = m.querySelector('input[name=uo-img]:checked');
          return r ? { file: r.value } : 'Pick an image, or upload one.';
        }).then(function (v) {
          if (!v) return;
          var file = v.file;
          d.images = d.images || [];
          d.images.push({ file: file, where: 'all', outputs: [], x: 16, y: 16, width: 0, height: 0, align: 'right,top', opacity: 100, refresh: false });
          rerender();
        });
      var m = U.root.querySelector('.modal');
      if (!m) return;
      m.querySelectorAll('[data-imgdel]').forEach(function (b) {
        b.onclick = function (e) {
          e.preventDefault();
          var f = b.getAttribute('data-imgdel');
          if ((d.images || []).some(function (l) { return l.file === f; })) { U.toast(f + ' is a layer in this template; remove the layer first.', { level: 'bad' }); return; }
          U.api.post(vh() + '/images', { 'delete': f }).then(function () { U.toast('Deleted ' + f + '.'); b.closest('.ly-pick').remove(); S.lib = null; },
            function (er) { U.toast(er.message, { level: 'bad' }); });
        };
      });
      var up = m.querySelector('#uo-img-up');
      up.onchange = function () {
        var f = up.files && up.files[0];
        if (!f) return;
        if (f.size > 2 * 1024 * 1024) { U.toast('An image is at most 2 MB.', { level: 'bad' }); return; }
        var rd = new FileReader();
        rd.onload = function () {
          var name = f.name.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^[^A-Za-z0-9]+/, '');
          U.api.post(vh() + '/images', { name: name, data: rd.result }).then(function (r) {
            m.querySelector('[data-a=no]').click(); // closes the dialog the way Cancel does
            library(true).then(function () {
              d.images = d.images || [];
              d.images.push({ file: r.file, where: 'all', outputs: [], x: 16, y: 16, width: 0, height: 0, align: 'right,top', opacity: 100, refresh: false });
              U.toast('Uploaded ' + r.file + ' and added it as a layer.');
              rerender();
            });
          }, function (er) { U.toast('Not uploaded: ' + er.message, { level: 'bad' }); });
        };
        rd.readAsDataURL(f);
      };
    }, function (e) { U.toast('The image list could not be read: ' + e.message, { level: 'bad' }); });
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
    if (!n) return 'No output encodes video, so the transcoder only handles audio and copies.';
    var unit = 1280 * 720 * 30, cpus = U.serverInfo && U.serverInfo.cpus;
    return '<strong>Estimated load:</strong> about ' + Math.round(px / 1e6) + ' megapixels a second to encode, ' + (Math.round(px / unit * 10) / 10) + ' times one 720p 30 fps output' +
      ' (for a 1080p 30 fps source, plus decoding it once' + (d.hardware === 'cpu' ? '' : ', on the ' + ({ nvidia: 'NVIDIA GPU', intel: 'Intel Quick Sync', netint: 'NETINT VPU' })[d.hardware]) + ').' +
      (cpus ? ' This server has ' + cpus + ' CPU core' + (cpus > 1 ? 's' : '') + '.' : '') +
      (d.hardware !== 'cpu' && d.overlay ? ' With the overlay on, frames are copied off the card to draw it, so this costs more than the same template without it.' : '') +
      ' An estimate: what a core holds depends on the codec, the speed setting and the picture.';
  }

  function renderBuilder() {
    var d = S.draft, t = S.sel && find(S.sel), hw = HW.filter(function (x) { return x[0] === d.hardware; })[0] || HW[0];
    var h = '<div class="b-head"><div class="b-name">' +
      (d.isNew ? '<label class="f" for="uo-b-name">Template name</label><input id="uo-b-name" type="text" data-d="name" value="' + U.esc(d.name) + '" placeholder="for example scoreboard-ladder" spellcheck="false" autocomplete="off">' +
        '<div class="hint">Saved as <code>transcoder/templates/' + U.esc(d.name || '<name>') + '.xml</code>. Not a stream name: step 3 makes each stream\'s own copy.</div>'
        : '<h3>' + U.esc(d.name) + '</h3><div class="hint"><code>transcoder/templates/' + U.esc(d.name) + '.xml</code>' + (t && (t.listedBy || []).length ? ', listed by ' + t.listedBy.map(U.esc).join(', ') : '') + '</div>') + '</div>' +
      '<div><label class="f" for="uo-b-ov">Overlay</label><select id="uo-b-ov" data-d="overlay">' + overlayOptions(d.overlay, 'No overlay') + '</select></div>' +
      '<div><span class="f">Hardware for the whole template</span><div class="seg" role="group" aria-label="Hardware">' + HW.map(function (x) {
        return '<button type="button" data-hw="' + x[0] + '" aria-pressed="' + (d.hardware === x[0]) + '">' + x[1] + '</button>'; }).join('') + '</div><div class="hint">' + hw[2] + '</div></div></div>' +
      (t && t.overlay && !(U.overlays || []).some(function (o) { return o.name === t.overlay; }) ? '<div class="notice bad"><div>It names overlay <strong>' + U.esc(t.overlay) + '</strong>, which does not exist in ' + U.esc(U.app) + '. Streams using it show nothing.</div></div>' : '') +
      layersHtml(d) +
      '<div class="b-bar"><div><span class="f">Start from</span><div class="presets">' + PRESETS.map(function (p, i) {
        return '<button type="button" class="pill" data-preset="' + i + '" aria-pressed="' + (d.preset === i) + '">' + p[0] + '</button>'; }).join('') + '</div></div></div>' +
      '<div class="oc-grid">' + d.outputs.map(function (o, i) { return card(o, i, d); }).join('') +
      '<div class="oc-add"><button class="btn" type="button" data-add="video">+ Add output</button>' +
      '<button class="btn sm ghost" type="button" data-add="passthrough">+ The original, untouched</button><button class="btn sm ghost" type="button" data-add="audio">+ Audio only</button></div></div>' +
      '<div class="b-sum">' + summary(d) + '</div><div class="b-load hint">' + loadText(d) + '</div>' + playback(d) +
      '<details class="b-adv"' + (S.advOpen ? ' open' : '') + '><summary>Decode</summary><div class="b-advgrid">' +
      '<label class="check"><input type="checkbox" data-d="deinterlace"' + (d.deinterlace ? ' checked' : '') + '>Deinterlace the source</label>' +
      '<label>Never below (fps)<input type="number" data-d="minFrameRate" min="0" max="120" value="' + (d.minFrameRate || 0) + '"><span class="hint">For sources that drop frames: WSE fills up to this rate. 0 leaves it out.</span></label>' +
      (d.hardware !== 'cpu' ? '<label>GPU or device<input type="number" data-d="gpuId" min="-1" max="15" value="' + d.gpuId + '"><span class="hint">-1 lets WSE choose.</span></label>' : '') +
      '</div></details>';
    el.edit.innerHTML = h;
    bindBuilder();
    if (!S.lib && !S.libLoading && (d.images || []).length) { S.libLoading = true; library().then(function () { S.libLoading = false; if (S.draft === d) rerender(); }, function () { S.libLoading = false; }); }
  }

  function bindBuilder() {
    var d = S.draft;
    el.edit.querySelectorAll('[data-d]').forEach(function (f) {
      var k = f.getAttribute('data-d');
      f.addEventListener(f.type === 'text' ? 'input' : 'change', function () {
        if (k === 'name') { d.name = f.value.trim().replace(/\.xml$/i, ''); var r = el.list.querySelector('.tpl-row.sel strong'); if (r && d.isNew) r.textContent = d.name || 'New template'; renderBar(); return; }
        if (f.type === 'checkbox') d[k] = f.checked;
        else if (f.type === 'number') d[k] = num(f.value, d[k]);
        else d[k] = f.value;
        if (k === 'overlay' && d.overlay && !d.outputs.some(function (o) { return o.kind === 'video' && o.overlay; })) d.outputs.forEach(function (o) { if (o.kind === 'video') o.overlay = true; });
        rerender();
      });
    });
    var adv = el.edit.querySelector('.b-adv');
    if (adv) adv.addEventListener('toggle', function () { S.advOpen = adv.open; });
    el.edit.querySelectorAll('[data-preset]').forEach(function (b) {
      b.onclick = function () {
        var i = +b.getAttribute('data-preset'), before = JSON.stringify({ outputs: d.outputs, groups: d.groups, preset: d.preset });
        d.outputs = PRESETS[i][1](); d.preset = i; d.groups = defaultGroups(d.outputs); d.urlSome = false;
        if (!d.overlay) d.outputs.forEach(function (o) { o.overlay = false; });
        rerender();
        U.toast('Outputs replaced with ' + PRESETS[i][0] + '.', { undo: function () {
          if (S.draft !== d) return;
          var b0 = JSON.parse(before);
          d.outputs = b0.outputs; d.groups = b0.groups; d.preset = b0.preset;
          rerender();
        } });
      };
    });
    el.edit.querySelectorAll('[data-hw]').forEach(function (b) {
      b.onclick = function () {
        d.hardware = b.getAttribute('data-hw');
        if (d.hardware !== 'cpu') d.outputs.forEach(function (o) { if (o.codec === 'VP8' || o.codec === 'VP9') o.codec = 'H.264'; });
        rerender();
      };
    });
    el.edit.querySelectorAll('[data-sec]').forEach(function (b) { b.onclick = function () { var k = b.getAttribute('data-sec'); S.open[k] = !S.open[k]; rerender(); }; });
    el.edit.querySelectorAll('[data-add]').forEach(function (b) { b.onclick = function () { addOutput(b.getAttribute('data-add')); }; });
    el.edit.querySelectorAll('.oc').forEach(function (c) {
      var i = +c.getAttribute('data-i'), o = d.outputs[i];
      c.querySelector('[data-rm]').onclick = function () {
        if (d.outputs.length === 1) { U.toast('A template needs at least one output.', { level: 'bad' }); return; }
        d.outputs.splice(i, 1); d.preset = null;
        d.groups.forEach(function (g) { g.members = g.members.filter(function (m) { return m.output !== o.name; }); });
        (d.images || []).forEach(function (l) { l.outputs = l.outputs.filter(function (n) { return n !== o.name; }); });
        rerender();
      };
      var ap = c.querySelector('[data-addparam]');
      if (ap) ap.onclick = function () { o.params.push({ name: '', value: '' }); rerender(); };
      c.querySelectorAll('[data-prm]').forEach(function (b) { b.onclick = function () { o.params.splice(+b.getAttribute('data-prm'), 1); rerender(); }; });
      c.querySelectorAll('[data-param]').forEach(function (f) { f.oninput = function () { o.params[+f.getAttribute('data-param')][f.getAttribute('data-k')] = f.value.trim(); renderBar(); }; });
      c.querySelectorAll('[data-region]').forEach(function (f) { f.onchange = function () { o.region[f.getAttribute('data-region')] = Math.max(0, num(f.value, 0)); rerender(); }; });
      c.querySelectorAll('[data-o]').forEach(function (f) {
        var k = f.getAttribute('data-o');
        f.addEventListener(k === 'name' ? 'input' : 'change', function () {
          d.preset = null;
          if (k === 'name') {
            var old = o.name; o.name = f.value.trim();
            d.groups.forEach(function (g) { g.members.forEach(function (m) { if (m.output === old) m.output = o.name; }); });
            (d.images || []).forEach(function (l) { l.outputs = l.outputs.map(function (n) { return n === old ? o.name : n; }); });
            renderSummary(); renderBar(); return;
          }
          if (k === 'enabled' || k === 'overlay' || k === 'keyFollow' || k === 'hdrToSdr' || k === 'captions') o[k] = f.checked;
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
              var old = size(o.height), h = +f.value, s = size(h);
              if (old && o.name === old[1]) { var nn = s ? s[1] : 'full'; d.groups.forEach(function (g) { g.members.forEach(function (m) { if (m.output === o.name) m.output = nn; }); }); o.name = nn; }
              if (s && (!old || o.videoBitrate === old[2])) { o.videoBitrate = s[2]; o.profile = o.codec === 'H.264' ? s[3] : o.profile; }
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
    el.edit.querySelectorAll('[data-gm]').forEach(function (s) {
      s.onchange = function () {
        var g = d.groups[+s.getAttribute('data-gm')], name = s.getAttribute('data-out');
        g.members = g.members.filter(function (m) { return m.output !== name; });
        if (s.value) g.members.push({ output: name, only: s.value === 'all' ? '' : s.value });
        rerender();
      };
    });
    el.edit.querySelectorAll('[data-gname]').forEach(function (f) { f.onchange = function () { d.groups[+f.getAttribute('data-gname')].name = f.value.trim(); rerender(); }; });
    el.edit.querySelectorAll('[data-grm]').forEach(function (b) { b.onclick = function () { d.groups.splice(+b.getAttribute('data-grm'), 1); rerender(); }; });
    bindLayers(d);
    var ag = el.edit.querySelector('[data-addgroup]');
    if (ag) ag.onclick = function () {
      var n = 'group' + (d.groups.length + 1);
      var smallest = d.outputs.filter(function (o) { return o.enabled && o.kind === 'video'; }).sort(function (a, b) { return (a.height || 9999) - (b.height || 9999); }).slice(0, 2);
      d.groups.push({ name: d.groups.some(function (g) { return g.name === 'mobile'; }) ? n : 'mobile', members: smallest.map(function (o) { return { output: o.name, only: '' }; }) });
      rerender();
    };
  }
  // a selector for the focused builder control, so a re-render can put focus back on it
  function focusKey() {
    var a = document.activeElement;
    if (!a || a === el.edit || !el.edit.contains(a)) return null;
    if (a.id) return '#' + CSS.escape(a.id);
    var attrs = Array.prototype.filter.call(a.attributes, function (x) { return /^data-/.test(x.name) || (x.name === 'value' && a.type === 'checkbox'); });
    if (!attrs.length) return null;
    var card = a.closest('.oc[data-i]'), ly = a.closest('[data-ly]');
    return (card ? '.oc[data-i="' + card.getAttribute('data-i') + '"] ' : ly ? '[data-ly="' + ly.getAttribute('data-ly') + '"] ' : '') +
      a.tagName.toLowerCase() + attrs.map(function (x) { return '[' + x.name + '="' + CSS.escape(x.value) + '"]'; }).join('');
  }
  function rerender() {
    var y = el.edit.scrollTop, f = focusKey();
    renderBuilder(); renderBar();
    el.edit.scrollTop = y;
    var n = f && el.edit.querySelector(f);
    if (n) n.focus();
  }
  function renderSummary() { var s = el.edit.querySelector('.b-sum'); if (s) s.innerHTML = summary(S.draft); }

  function addOutput(kind) {
    var d = S.draft;
    d.preset = null;
    if (kind === 'passthrough') { var p = original(); p.name = unique(d, 'source'); d.outputs.unshift(p); rerender(); return; }
    if (kind === 'audio') { var a = audioOnly(); a.name = unique(d, 'audio'); d.outputs.push(a); rerender(); return; }
    // the next standard size below the smallest video output there is, else the largest not used yet
    var have = d.outputs.filter(function (o) { return o.kind === 'video'; }).map(function (o) { return o.height; });
    var low = have.filter(function (h) { return h > 0; }).reduce(function (m, h) { return Math.min(m, h); }, Infinity);
    var next = SIZES.filter(function (s) { return have.indexOf(s[0]) < 0 && s[0] < low; })[0] || SIZES.filter(function (s) { return have.indexOf(s[0]) < 0; })[0];
    if (!next) { U.toast('Every standard size is there already. Change a size on a card, or pick Other height.'); return; }
    var o = out(next[0], !!d.overlay);
    o.name = unique(d, o.name);
    var at = d.outputs.length;
    while (at > 0 && d.outputs[at - 1].kind === 'audio') at--;
    d.outputs.splice(at, 0, o);
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
    if (d.overlay && !d.outputs.some(function (o) { return o.enabled && o.kind === 'video' && o.overlay; })) return 'No output shows the overlay.';
    var vids = videoOutputs(d).map(function (o) { return o.name; });
    for (var l = 0; l < (d.images || []).length; l++) {
      var im = d.images[l];
      if (im.where === 'outputs' && !im.outputs.some(function (n) { return vids.indexOf(n) >= 0; })) return 'Image ' + im.file + ' is on chosen outputs, and none is chosen.';
      if (im.opacity > 100) return 'Image ' + im.file + ': opacity is 0 to 100.';
    }
    return null;
  }

  /* ---------- another template: read-only, with "Use with an overlay" ---------- */
  function renderOther(t) {
    var v = verdict(t), can = carriers(t).length > 0;
    var users = (t.listedBy || []);
    el.edit.innerHTML = '<div class="b-head"><div class="b-name"><h3>' + U.esc(t.name) + '</h3><div class="hint"><code>transcoder/templates/' + U.esc(t.template) + '</code>' +
      (t.shipped ? ', ships with WSE' : '') + '</div></div>' +
      '<div class="row">' + (can ? '<button class="btn primary" type="button" data-act="use">Use with an overlay...</button>' : '') +
      '<button class="btn" type="button" data-act="from">New template from this one</button></div></div>' +
      '<div class="notice ' + (v.cls === 'ok' ? 'ok' : v.cls === 'warn' ? 'slow' : '') + '"><div><strong>' + v.text + '.</strong> ' + v.long + '</div></div>' +
      '<table class="list"><thead><tr><th>Output</th><th>Size</th><th>Video</th><th>Audio</th><th>On</th></tr></thead><tbody>' +
      (t.encodes || []).map(function (e) {
        var o = fromTemplate(t).outputs.filter(function (x) { return x.name === e.name; })[0];
        var video = /^passthru$/i.test(e.codec || '') ? 'passes through' : /^disable/i.test(e.codec || '') ? 'none' : U.esc(e.codec || '') + (o && o.videoBitrate ? ', ' + Math.round(o.videoBitrate / 1000).toLocaleString() + ' kbps' : '');
        return '<tr><td><code>' + U.esc(e.name) + '</code></td><td>' + U.esc(o ? sizeLabel(o) : '') + '</td><td>' + video + '</td><td>' + U.esc(e.audioCodec || '') + '</td><td>' + (e.enabled ? 'yes' : '<span class="muted">off</span>') + '</td></tr>';
      }).join('') + '</tbody></table>' +
      '<p class="hint">Read-only here. ' + (users.length ? 'Applications that list it: ' + users.map(U.esc).join(', ') + '. ' : '') + 'Step 3 can still use it on a stream as it is.</p>';
    var use = el.edit.querySelector('[data-act=use]');
    if (use) use.onclick = function () { useDialog(t); };
    el.edit.querySelector('[data-act=from]').onclick = function () { startNew(copyOf(t)); };
  }
  // a new template built from t; one without an overlay gets the first overlay on every video output
  function copyOf(t) {
    var d = fromTemplate(t);
    d.isNew = true; d.name = freeName(t.name + '-overlay');
    if (!d.overlay) {
      d.overlay = ((U.overlays || [])[0] || {}).name || '';
      d.outputs.forEach(function (o) { o.overlay = !!d.overlay && o.kind === 'video'; });
    }
    return d;
  }
  function freeName(b) { return unique({ outputs: all().map(function (x) { return { name: x.name }; }) }, b); }

  function useDialog(t) {
    if (!(U.overlays || []).length) { U.toast('Make an overlay in step 1 first.', { level: 'bad' }); return; }
    var c = carriers(t), apps = t.listedBy || [];
    var copyName = freeName(t.name + '-overlay');
    U.prompt('Use ' + t.name + ' with an overlay',
      '<label class="opt"><input type="radio" name="uo-use" value="copy" checked><span><strong>Make a copy with the overlay</strong>' +
        '<span class="hint">Writes <code>' + U.esc(copyName) + '.xml</code>, which you can then change here. <code>' + U.esc(t.template) + '</code> stays as it is.</span></span></label>' +
      (t.shipped ? '' : '<label class="opt"><input type="radio" name="uo-use" value="inplace"><span><strong>Change ' + U.esc(t.name) + ' itself</strong>' +
        '<span class="hint">Switches on dynamic overlays in <code>' + U.esc(t.template) + '</code> after a backup; uninstall puts it back. Templates are shared by every application on the server' +
        (apps.length ? ': ' + apps.map(U.esc).join(', ') + ' list it.' : '; no application lists it now.') + '</span></span></label>') +
      '<div class="two mt-m"><div class="fld"><label class="f" for="uo-use-ov">Overlay</label><select id="uo-use-ov">' + overlayOptions(((U.overlays || [])[0] || {}).name) + '</select></div>' +
      '<div class="fld" data-copyname><label class="f" for="uo-use-n">Name of the copy</label><input id="uo-use-n" type="text" value="' + U.esc(copyName) + '" spellcheck="false"></div></div>' +
      '<div class="fld"><span class="f">Outputs that show it</span>' + c.map(function (e) {
        return '<label class="check"><input type="checkbox" data-use-enc="' + U.esc(e.name) + '" checked>' + U.esc(e.name) + '</label>'; }).join('') +
      '<div class="hint">Outputs that pass video through cannot show an overlay and are not listed.</div></div>',
      'Use it', function (m) {
        var how = m.querySelector('input[name=uo-use]:checked').value;
        var picked = Array.prototype.map.call(m.querySelectorAll('[data-use-enc]:checked'), function (b) { return b.getAttribute('data-use-enc'); });
        if (!picked.length) return 'Pick at least one output.';
        var n = m.querySelector('#uo-use-n').value.trim().replace(/\.xml$/i, '');
        if (how === 'copy' && (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(n) || n.indexOf('..') >= 0)) return 'Name: letters, digits, dot, dash and underscore.';
        if (how === 'copy' && (U.templates || []).some(function (x) { return x.name === n; })) return 'A template named ' + n + ' exists.';
        return { how: how, name: n, overlay: m.querySelector('#uo-use-ov').value, encodes: picked.length === c.length ? [] : picked };
      }).then(function (v) {
        if (!v) return;
        U.api.post(vh() + '/templates/' + U.enc(t.name) + '/use', v).then(function (r) {
          var made = String(r.template || '').replace(/\.xml$/, '');
          U.toast(v.how === 'copy' ? 'Made ' + made + ', a copy of ' + t.name + ' carrying ' + v.overlay + '. Next: step 3 uses it on streams.'
            : 'Switched on overlays in ' + t.name + '; it carries ' + v.overlay + '. The original is backed up.');
          return U.refreshState(true).then(function () { select(made); });
        }, function (e) { U.banner('tpl', 'bad', 'Not done.', e.message); });
      });
    var m = U.root.querySelector('.modal');
    if (m) m.querySelectorAll('input[name=uo-use]').forEach(function (r) {
      r.onchange = function () { m.querySelector('[data-copyname]').hidden = r.value !== 'copy' || !r.checked; };
    });
  }

  /* ---------- a customer's template changed in place: only its overlay and outputs ---------- */
  function savedForm(t) { return { overlay: t.overlay || '', encodes: (t.overlayEncodes || []).slice().sort() }; }
  function overlayForm(t) {
    var f = el.edit && el.edit.querySelector('[data-io-ov]');
    if (!f || el.edit.getAttribute('data-for') !== t.name) return savedForm(t);
    var picked = Array.prototype.map.call(el.edit.querySelectorAll('[data-io-enc]:checked'), function (b) { return b.getAttribute('data-io-enc'); }).sort();
    return { overlay: f.value, encodes: picked.length === carriers(t).length ? [] : picked };
  }
  function renderOverlayOnly(t) {
    var c = carriers(t), listed = t.overlayEncodes || [];
    el.edit.setAttribute('data-for', t.name);
    el.edit.innerHTML = '<div class="b-head"><div class="b-name"><h3>' + U.esc(t.name) + '</h3><div class="hint"><code>transcoder/templates/' + U.esc(t.template) + '</code>, your own template with overlays switched on</div></div></div>' +
      '<div class="fld"><label class="f" for="uo-io-ov">Overlay</label><select id="uo-io-ov" data-io-ov>' + overlayOptions(t.overlay, 'No overlay') + '</select></div>' +
      '<div class="fld"><span class="f">Outputs that show it</span>' + c.map(function (e) {
        return '<label class="check"><input type="checkbox" data-io-enc="' + U.esc(e.name) + '"' + (!listed.length || listed.indexOf(e.name) >= 0 ? ' checked' : '') + '>' + U.esc(e.name) + '</label>'; }).join('') + '</div>' +
      '<p class="hint">Its outputs are changed where it was written, for example in WSE Manager. To build on it here, use New template from this one.</p>' +
      '<button class="btn" type="button" data-act="from">New template from this one</button>';
    el.edit.querySelector('[data-act=from]').onclick = function () { startNew(copyOf(t)); };
  }

  /* ---------- changes, the action bar, saving ---------- */
  function current() {
    if (S.draft) return JSON.stringify(spec(S.draft));
    var t = S.sel && find(S.sel);
    return t && inPlace(t) ? JSON.stringify(overlayForm(t)) : null;
  }
  function changes() {
    if (S.draft && S.draft.isNew) return 1;
    var c = current();
    return c != null && S.base != null && c !== S.base ? 1 : 0;
  }
  function onEdit() { if (S.sel && !S.draft) renderBar(); }
  function renderBar() {
    if (!el.bar) return;
    var t = S.sel && find(S.sel);
    if (!S.draft && (!t || !inPlace(t))) {
      el.bar.innerHTML = '<span class="msg">' + (t ? 'Use it with an overlay, or start a new template from it.' : 'Pick a template, or make one with New template.') + '</span>';
      return;
    }
    var why = S.draft && changes() ? problem(S.draft) : null;
    U.actionBar(el.bar, { changes: changes(), conflict: S.conflict, saving: S.saving, saveLabel: S.draft && S.draft.isNew ? 'Create template' : 'Save',
      note: why || 'All changes saved. Next: step 3 uses the template on streams.',
      onSave: save, onDiscard: discard,
      onTheirs: function () { S.conflict = false; S.known = S.theirs; open(find(S.sel)); renderEdit(); renderBar(); },
      onMine: function () { S.conflict = false; S.known = S.theirs; renderBar(); U.toast('Keeping your version. Save replaces the one saved in the other session.'); } });
    if (why) { var msg = el.bar.querySelector('.msg'); if (msg) { msg.textContent = why; msg.classList.add('warn-text'); } }
  }
  function discard() {
    S.conflict = false;
    if (S.draft && S.draft.isNew) { S.draft = null; S.base = null; load(); if (!S.sel) { renderList(); renderEdit(); renderBar(); } U.toast('Discarded the new template.'); return; }
    open(find(S.sel)); renderEdit(); renderBar(); U.toast('Discarded the unsaved changes.');
  }
  function leave() {
    if (!changes()) return Promise.resolve(true);
    var name = S.draft && S.draft.isNew ? 'The new template' : 'Template ' + S.sel;
    return U.choose('Unsaved changes', '<p>' + U.esc(name) + ' has unsaved changes.</p>',
      [{ label: 'Stay', value: 'stay' }, { label: 'Discard', value: 'discard' }, { label: 'Save', value: 'save', kind: 'primary' }]).then(function (v) {
        if (v === 'discard') {
          S.conflict = false;
          if (S.draft && S.draft.isNew) { S.draft = null; S.base = null; } else open(find(S.sel));
          renderList(); renderEdit(); renderBar();
          return true;
        }
        if (v === 'save') return save();
        return false;
      });
  }

  function save() {
    if (!changes() || S.saving) return Promise.resolve(!changes());
    var t = S.sel && find(S.sel), req, label;
    if (S.draft) {
      var why = problem(S.draft);
      if (why) { U.toast(why, { level: 'bad' }); return Promise.resolve(false); }
      var body = spec(S.draft);
      label = S.draft.name;
      req = S.draft.isNew ? U.api.post(vh() + '/templates', body) : U.api.post(vh() + '/templates/' + U.enc(label) + '/spec', body);
    } else {
      var f = overlayForm(t);
      if (f.overlay && !Array.prototype.some.call(el.edit.querySelectorAll('[data-io-enc]'), function (b) { return b.checked; })) { U.toast('No output picked: tick at least one.', { level: 'bad' }); return Promise.resolve(false); }
      label = t.name;
      req = U.api.post(vh() + '/templates/' + U.enc(label) + '/overlay', { overlay: f.overlay || null, encodes: f.encodes });
    }
    var isNew = S.draft && S.draft.isNew;
    S.saving = true; renderBar();
    return req.then(function (res) {
      U.clearBanner('tpl');
      U.toast((isNew ? 'Created template ' : 'Saved template ') + label + '.' + (isNew ? ' Next: step 3 uses it on streams.' : ''));
      // still saving until the new state is in, so the poll does not read this save as another session's
      return U.refreshState(true).then(function () {
        S.saving = false; S.conflict = false;
        select(label);
        // running streams that loaded this file show the overlay now; outputs wait for a restart
        return U.afterTemplateWrite(res && res.live).then(function () { return isNew ? null : offerCopies(label); });
      }).then(function () { return true; });
    }, function (e) { S.saving = false; renderBar(); U.banner('tpl', 'bad', 'Not saved.', e.message); return false; });
  }

  // streams with their own copy keep the old version until asked
  function offerCopies(name) {
    return U.api.get(vh() + '/templates/' + U.enc(name) + '/copies').then(function (r) {
      var copies = (r && r.copies) || [];
      if (!copies.length) return null;
      var live = (U.appStreams || []).filter(function (s) { return s.publishing; }).map(function (s) { return s.name; });
      var rows = copies.map(function (c) {
        var on = live.indexOf(c.stream) >= 0;
        return '<label class="check"><input type="checkbox" data-copy="' + U.esc(c.stream) + '" checked>' + U.esc(c.stream) + (on ? ' <span class="tag warn">publishing</span>' : '') + '</label>';
      }).join('');
      return U.prompt('Update the streams that use ' + name + '?', '<p>These streams have their own copy of ' + U.esc(name) + ' and keep the old version until updated.</p>' + rows +
        '<p class="hint">A publishing stream shows the overlay change at once; new outputs need it restarted, which is offered next.</p>', 'Update', function (m) {
          return Array.prototype.map.call(m.querySelectorAll('[data-copy]:checked'), function (b) { return b.getAttribute('data-copy'); });
        }).then(function (streams) {
          if (!streams || !streams.length) return null;
          return U.api.post(vh() + '/templates/' + U.enc(name) + '/copies', { streams: streams }).then(function (r2) {
            if (!(r2.live || []).length) U.toast('Updated ' + (r2.updated || []).join(', ') + '. Each picks it up when it next starts publishing.');
            return U.afterTemplateWrite(r2.live).then(function () { return U.refreshState(true); });
          });
        });
    });
  }

  function deleteTemplate() {
    var t = S.sel && find(S.sel);
    if (!t || !t.managed) { U.toast(t ? t.name + ' was not made here, so it is not deleted from here.' : 'Pick a template made here first.'); return; }
    var users = streamsUsing(t.name), direct = users.filter(function (s) { return !s.own && s.publishing; });
    if (direct.length) {
      U.choose('Template ' + t.name + ' is in use', '<p>' + U.esc(direct.map(function (s) { return s.name; }).join(', ')) + ' ' + (direct.length > 1 ? 'are' : 'is') +
        ' publishing with it as the application\'s default.</p><p>Change the application\'s default or the template of those streams first (step 3).</p>',
        [{ label: 'Close', value: null }, { label: 'Go to step 3', value: 'go', kind: 'primary' }]).then(function (v) { if (v === 'go') U.go('uo-streams'); });
      return;
    }
    U.confirm('Delete template ' + t.name + '?', '<p>The file <code>transcoder/templates/' + U.esc(t.name) + '.xml</code> is deleted. This cannot be undone.</p>' +
      (inPlace(t) ? '<p>It is your own template with overlays switched on; its backup stays beside it.</p>' : '') +
      (users.length ? '<p>Streams with their own copy keep running their copy: ' + U.esc(users.map(function (s) { return s.name; }).join(', ')) + '.</p>' : ''), 'Delete template', true).then(function (ok) {
        if (!ok) return;
        U.api.v2del('/transcoder/templates/' + U.enc(t.name)).then(function () {
          S.sel = null; S.known = null; S.draft = null; S.base = null;
          U.toast('Deleted template ' + t.name + '.');
          return U.refreshState(true).then(load);
        }, function (e) { U.banner('tpl', 'bad', 'Not deleted.', e.message); });
      });
  }

  U.views['uo-template'] = {
    init: function (section) {
      el.list = section.querySelector('[data-tpl-list]');
      el.edit = section.querySelector('[data-tpl-edit]');
      el.bar = section.querySelector('[data-tpl-bar]');
      el.del = section.querySelector('[data-act=delete-template]');
      el.edit.addEventListener('input', onEdit);
      el.edit.addEventListener('change', onEdit);
      section.querySelector('[data-act=new-template]').onclick = function () { leave().then(function (ok) { if (ok) startNew(); }); };
      section.querySelector('[data-act=delete-template]').onclick = deleteTemplate;
      S.sel = null;
    },
    app: function () { S.sel = null; S.known = null; S.draft = null; S.base = null; S.conflict = false; },
    show: function () { if (U.app) load(); },
    state: function () { if (U.current === 'uo-template') load(); },
    leave: function () { return leave(); },
    save: function () { save(); }
  };
})();
