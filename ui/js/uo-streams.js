/* URL Overlay step 3, Streams: the template and overlay each stream runs, and using a template on a stream, which writes
   that stream's own copy (<stream>.xml). Also the application's template order. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var el = {};
  var S = { rows: [], added: [], sig: {}, picks: {} };

  function vh() { return U.vhostPath(); }
  function baseTemplates() {
    // templates carrying an overlay, without the streams' own copies
    var own = {};
    S.rows.forEach(function (r) { if (r.own) own[r.name] = true; });
    var out = [];
    (U.overlays || []).forEach(function (o) {
      (o.templates || []).forEach(function (t) { if (!own[t] && out.indexOf(t) < 0) out.push(t); });
    });
    return out.sort();
  }
  var copyBase = U.copyBase;

  function load() {
    var rows = (U.appStreams || []).slice();
    S.added.forEach(function (n) { if (!rows.some(function (r) { return r.name === n; })) rows.push({ name: n, publishing: false, template: null, own: false, added: true }); });
    S.rows = rows.sort(function (a, b) { return a.name.localeCompare(b.name); });
    render();
  }
  function rowSig(r) { return [r.publishing, r.template, r.own, r.overlay, r.description, r.restartNeeded].join('|'); }

  function templateCell(r) {
    if (!r.template) return '<span class="muted">none (not transcoded)</span>';
    var tag = r.own ? (copyBase(r) ? '<span class="tag accent">own copy of ' + U.esc(copyBase(r)) + '</span>' : '<span class="tag accent">own</span>') : '<span class="tag">application default</span>';
    return U.esc(r.template) + ' ' + tag + (r.vif ? ' <span class="tag">VIF</span>' : '');
  }

  function render() {
    el.title.textContent = 'Streams in ' + U.app;
    var bases = baseTemplates();
    if (!S.rows.length) {
      el.table.innerHTML = '<div class="empty"><h3>No streams yet</h3><p>Streams appear here while they publish. A stream that has not started yet can be added by name.</p></div>';
      return;
    }
    el.table.innerHTML = '<table class="list"><thead><tr><th>Stream</th><th>Status</th><th>Template now</th><th>Overlay</th><th>Use a template</th></tr></thead><tbody>' +
      S.rows.map(function (r) {
        // a template picked but not used yet survives a re-render
        var want = S.picks[r.name] || copyBase(r);
        var pick = bases.length ? '<select class="w-auto" data-pick="' + U.esc(r.name) + '" aria-label="Template for ' + U.esc(r.name) + '">' + bases.map(function (b) {
          return '<option' + (want === b ? ' selected' : '') + '>' + U.esc(b) + '</option>'; }).join('') + '</select> ' +
          '<button class="btn primary sm" type="button" data-use="' + U.esc(r.name) + '">Use</button>' : '<span class="muted small">Make one in step 2</span>';
        var remove = r.own && copyBase(r) ? ' <button class="btn sm" type="button" data-remove="' + U.esc(r.name) + '">Stop using</button>' : '';
        // its template changed since it started: the overlay is current, the outputs wait for a restart
        var restart = r.restartNeeded ? '<div class="small"><span class="tag warn">outputs change on restart</span> <button class="btn sm" type="button" data-restart="' + U.esc(r.name) + '">Restart now</button></div>' : '';
        var changed = S.sig[r.name] !== undefined && S.sig[r.name] !== rowSig(r);
        return '<tr' + (changed ? ' class="flash"' : '') + '><td><strong>' + U.esc(r.name) + '</strong></td><td><span class="dot ' + (r.publishing ? 'ok' : '') + '"></span>' + (r.publishing ? 'publishing' : 'not publishing') + '</td>' +
          '<td>' + templateCell(r) + restart + '</td><td>' + (r.overlay ? U.esc(r.overlay) : '<span class="muted">none</span>') + '</td><td>' + pick + remove + '</td></tr>';
      }).join('') + '</tbody></table>';
    S.sig = {};
    S.rows.forEach(function (r) { S.sig[r.name] = rowSig(r); });
    el.table.querySelectorAll('[data-pick]').forEach(function (s) { s.onchange = function () { S.picks[s.getAttribute('data-pick')] = s.value; }; });
    el.table.querySelectorAll('[data-use]').forEach(function (b) {
      b.onclick = function () { var s = b.getAttribute('data-use'); plan(s, el.table.querySelector('[data-pick="' + CSS.escape(s) + '"]').value); };
    });
    el.table.querySelectorAll('[data-remove]').forEach(function (b) { b.onclick = function () { stopUsing(b.getAttribute('data-remove')); }; });
    el.table.querySelectorAll('[data-restart]').forEach(function (b) { b.onclick = function () { U.restartStream(b.getAttribute('data-restart')).then(load); }; });
  }

  function plan(stream, template) {
    el.plan.innerHTML = '<p class="muted small">Checking what changes...</p>';
    U.api.get(vh() + '/applications/' + U.enc(U.app) + '/templates/assign?scope=stream&template=' + U.enc(template) + '&stream=' + U.enc(stream)).then(function (p) {
      var row = S.rows.filter(function (r) { return r.name === stream; })[0] || {};
      var others = (p.publishing || []).filter(function (n) { return n !== stream; });
      var lines = [];
      if (p.writeFile) lines.push('Writes <code>transcoder/templates/' + U.esc(p.writeFile) + '</code>, a copy of ' + U.esc(p.template) + '.' + (row.own ? ' It replaces ' + U.esc(stream) + '\'s current copy.' : ''));
      else lines.push(U.esc(stream) + ' already has this template as its own.');
      if (p.templatesBefore !== p.templatesAfter) lines.push('Application ' + U.esc(U.app) + ' is set to look for a template named after each stream first: <code>' + U.esc(p.templatesAfter) + '</code>.');
      var warn = [];
      if (p.currentIsVif) warn.push('<strong>VIF stops analyzing ' + U.esc(stream) + '.</strong> It now runs ' + U.esc(p.currentTemplate) + '; afterwards it runs ' + U.esc(p.template) + '. Other streams keep VIF.');
      if (p.transcoderTurnsOn) warn.push('<strong>This switches the transcoder on for application ' + U.esc(U.app) + '.</strong> ' +
        (others.length ? others.length + ' other stream(s) publishing now (' + U.esc(others.join(', ')) + ') start transcoding with the application\'s default template when they next start.' : 'Every stream published to it from now on is transcoded.') +
        ' The application needs a restart for this; restarting drops every stream in it.');
      var when = !row.publishing ? U.esc(stream) + ' picks it up when it starts publishing.' :
        '<strong>' + U.esc(stream) + ' is publishing.</strong> Its outputs change when it restarts, now or later from this list. ' + U.esc(U.RESTART_GAP);
      el.plan.innerHTML = '<div class="card plan"><h4>Use ' + U.esc(template) + ' on ' + U.esc(stream) + '</h4>' +
        '<div class="diff">' + lines.join('<br>') + '</div>' +
        (warn.length ? warn.map(function (w) { return '<div class="notice slow mt-s"><div>' + w + '</div></div>'; }).join('') : '') +
        '<p class="small">' + when + '</p>' +
        '<div class="row end"><button class="btn" type="button" data-a="cancel">Cancel</button>' +
        (row.publishing ? '<button class="btn" type="button" data-a="apply-reset">Use and restart ' + U.esc(stream) + ' now</button>' : '') +
        '<button class="btn primary" type="button" data-a="apply">Use on ' + U.esc(stream) + '</button></div></div>';
      el.plan.querySelector('[data-a=cancel]').onclick = function () { el.plan.innerHTML = ''; };
      el.plan.querySelector('[data-a=apply]').onclick = function () { apply(stream, template, false); };
      var ar = el.plan.querySelector('[data-a=apply-reset]');
      if (ar) ar.onclick = function () { apply(stream, template, true); };
      el.plan.scrollIntoView({ block: 'nearest' });
    }, function (e) {
      el.plan.innerHTML = '<div class="notice bad mt-m"><div><strong>' + U.esc(template) + ' cannot be used on ' + U.esc(stream) + '.</strong> ' + U.esc(e.message) + '</div></div>';
    });
  }

  function apply(stream, template, reset) {
    U.api.post(vh() + '/applications/' + U.enc(U.app) + '/templates/assign', { scope: 'stream', template: template, stream: stream }).then(function (r) {
      el.plan.innerHTML = '';
      var msg = r.restartRequired ? 'Restart application ' + U.app + ' for its transcoder setting to take effect.' : '';
      if (reset && r.restartNeeded) {
        U.toast(stream + ' uses ' + template + '. ' + msg);
        return U.restartStream(stream, true).then(load);
      }
      U.toast(stream + ' uses ' + template + '. ' + (msg || (r.restartNeeded ? 'Its outputs change when it restarts: Restart now is in its row.' : 'Nothing else waits for a restart.')));
      return U.refreshState(true).then(load);
    }, function (e) { U.banner('st', 'bad', 'Not changed.', e.message); });
  }


  function stopUsing(stream) {
    var row = S.rows.filter(function (r) { return r.name === stream; })[0];
    U.confirm('Stop using ' + copyBase(row) + ' on ' + stream + '?', '<p>' + U.esc(stream) + '\'s own copy, <code>' + U.esc(stream) + '.xml</code>, is deleted. ' +
      U.esc(stream) + ' then runs the application\'s default template, without this overlay, from its next restart (Restart now in its row).</p>', 'Stop using', true).then(function (ok) {
        if (!ok) return;
        U.api.v2del('/transcoder/templates/' + U.enc(stream)).then(function () {
          U.toast(stream + ' no longer has its own template. It changes when ' + stream + ' restarts: Restart now is in its row.');
          return U.refreshState(true).then(load);
        }, function (e) { U.banner('st', 'bad', 'Not changed.', e.message); });
      });
  }

  function addStream() {
    U.prompt('Add a stream name', '<div class="fld"><label class="f" for="uo-as">Stream name</label><input id="uo-as" type="text" autocomplete="off">' +
      '<div class="hint">Exactly as the camera or encoder will publish it. The template applies when it starts.</div></div>', 'Add', function (m) {
        var v = m.querySelector('#uo-as').value.trim();
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(v)) return 'Letters, digits, dot, dash and underscore.';
        return { name: v };
      }).then(function (r) { if (r && S.added.indexOf(r.name) < 0) { S.added.push(r.name); load(); } });
  }

  /* ---------- the application's template order: WSE uses the first template in the list that exists ---------- */
  var MATCH = '${SourceStreamName}.xml';
  function loadOrder() {
    return U.api.get(vh() + '/applications/' + U.enc(U.app) + '/templates/order').then(function (r) {
      S.order = (r.entries || []).map(function (e) { return e.entry; });
      S.orderBase = S.order.join(',');
      S.orderExists = {};
      (r.entries || []).forEach(function (e) { S.orderExists[e.entry] = e.exists; });
      renderOrder();
    }, function (e) { el.order.innerHTML = '<div class="notice bad"><div>The template order could not be read: ' + U.esc(e.message) + '</div></div>'; });
  }
  function renderOrder() {
    if (!S.order) return;
    var names = (U.templates || []).filter(function (t) { return t && !t.error && !copyBase(t); })
      .map(function (t) { return t.template; });
    var addable = (S.order.indexOf(MATCH) < 0 ? [MATCH] : []).concat(names.filter(function (n) { return S.order.indexOf(n) < 0; }));
    var changed = S.order.join(',') !== S.orderBase;
    el.order.innerHTML = '<details class="st-order"' + (S.orderOpen || changed ? ' open' : '') + '><summary>Which template a stream gets in ' + U.esc(U.app) + (changed ? ' <span class="tag warn">unsaved</span>' : '') + '</summary>' +
      '<p class="hint">For each stream WSE goes down this list and uses the first template that exists.</p>' +
      '<ol>' + S.order.map(function (e, i) {
        var exists = e === MATCH || S.orderExists[e] !== false && names.indexOf(e) >= 0;
        return '<li><span class="st-o-name"><code>' + U.esc(e) + '</code> ' + (e === MATCH ? '<span class="tag accent">the stream\'s own, when it has one</span>' : exists ? '' : '<span class="tag warn">no such file</span>') + '</span>' +
          (i ? '<button class="btn icon" type="button" data-oup="' + i + '" aria-label="Move ' + U.esc(e) + ' up">&#x25B2;</button>' : '<span class="st-o-gap"></span>') +
          (i < S.order.length - 1 ? '<button class="btn icon" type="button" data-odown="' + i + '" aria-label="Move ' + U.esc(e) + ' down">&#x25BC;</button>' : '<span class="st-o-gap"></span>') +
          '<button class="btn icon" type="button" data-orm="' + i + '" aria-label="Remove ' + U.esc(e) + '">&#x2715;</button></li>';
      }).join('') + '</ol>' +
      '<div class="row">' + (addable.length ? '<select data-oadd>' + addable.map(function (n) { return '<option>' + U.esc(n) + '</option>'; }).join('') + '</select><button class="btn sm" type="button" data-oaddb>Add</button>' : '') +
      '<span class="spacer"></span>' + (changed ? '<button class="btn sm" type="button" data-oreset>Discard</button><button class="btn primary sm" type="button" data-osave>Save order</button>' : '<span class="small muted">Saved.</span>') + '</div>' +
      '<p class="hint">Saved to <code>conf/' + U.esc(U.app) + '/Application.xml</code>. A new order applies after ' + U.esc(U.app) + ' restarts.</p></details>';
    var det = el.order.querySelector('details');
    det.addEventListener('toggle', function () { S.orderOpen = det.open; });
    var move = function (i, d) { var t = S.order[i]; S.order[i] = S.order[i + d]; S.order[i + d] = t; renderOrder(); };
    el.order.querySelectorAll('[data-oup]').forEach(function (b) { b.onclick = function () { move(+b.getAttribute('data-oup'), -1); }; });
    el.order.querySelectorAll('[data-odown]').forEach(function (b) { b.onclick = function () { move(+b.getAttribute('data-odown'), 1); }; });
    el.order.querySelectorAll('[data-orm]').forEach(function (b) { b.onclick = function () { S.order.splice(+b.getAttribute('data-orm'), 1); renderOrder(); }; });
    var ab = el.order.querySelector('[data-oaddb]');
    if (ab) ab.onclick = function () { S.order.push(el.order.querySelector('[data-oadd]').value); renderOrder(); };
    var rs = el.order.querySelector('[data-oreset]');
    if (rs) rs.onclick = function () { S.order = S.orderBase ? S.orderBase.split(',') : []; renderOrder(); };
    var sv = el.order.querySelector('[data-osave]');
    if (sv) sv.onclick = saveOrder;
  }
  function saveOrder() {
    if (!S.order.length) { U.toast('Keep at least one template: without one WSE transcodes nothing.', { level: 'bad' }); return Promise.resolve(false); }
    return U.api.post(vh() + '/applications/' + U.enc(U.app) + '/templates/order', { templates: S.order }).then(function () {
      U.toast('Saved the template order. It applies after ' + U.app + ' restarts.');
      return loadOrder().then(function () { return true; });
    }, function (e) { U.banner('order', 'bad', 'The template order was not saved.', e.message); return false; });
  }

  // leaving with an unsaved template order asks first
  function leave() {
    if (!S.order || S.order.join(',') === S.orderBase) return Promise.resolve(true);
    return U.choose('Unsaved template order', '<p>The template order of ' + U.esc(U.app) + ' has unsaved changes.</p>',
      [{ label: 'Stay', value: 'stay' }, { label: 'Discard', value: 'discard' }, { label: 'Save', value: 'save', kind: 'primary' }]).then(function (v) {
        if (v === 'discard') { S.order = S.orderBase ? S.orderBase.split(',') : []; renderOrder(); return true; }
        if (v === 'save') return saveOrder();
        return false;
      });
  }

  U.views['uo-streams'] = {
    init: function (section) {
      el.title = section.querySelector('[data-st-title]');
      el.table = section.querySelector('[data-st-table]');
      el.plan = section.querySelector('[data-st-plan]');
      el.order = section.querySelector('[data-st-order]');
      section.querySelector('[data-act=add-stream]').onclick = addStream;
      S.added = [];
    },
    app: function () { S.added = []; S.sig = {}; S.picks = {}; S.order = null; },
    show: function () { if (U.app) { el.plan.innerHTML = ''; load(); loadOrder(); } },
    state: function () { if (U.current === 'uo-streams') load(); },
    leave: leave
  };
})();
