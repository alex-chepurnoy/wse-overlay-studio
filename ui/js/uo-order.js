/* URL Overlay template order: the application's Transcoder/Templates list. WSE gives each stream the first template in
   the list that exists, so this decides which template every stream in the application gets. A change is written to
   Application.xml on Save and applies after the application restarts. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var MATCH = '${SourceStreamName}.xml';
  var box = null, el = {};
  var S = { order: null, base: null, exists: {}, loading: false, saving: false, error: null, seen: null, conflict: false, savedNote: null };

  function path() { return U.appPath() + '/templates/order'; }
  function dirty() { return !!S.order && S.order.join(',') !== S.base; }
  function load(keep) {
    S.loading = true;
    return U.api.get(path()).then(function (r) {
      S.loading = false; S.error = null;
      var entries = (r.entries || []).map(function (e) { return e.entry; });
      S.exists = {};
      (r.entries || []).forEach(function (e) { S.exists[e.entry] = e.exists; });
      S.base = entries.join(',');
      if (!keep || !S.order) S.order = entries;
      S.seen = U.state && U.state.appTemplates;
      render();
    }, function (e) { S.loading = false; S.error = e.message; render(); });
  }
  // the file an entry names for one stream, or null when the entry does not apply to it
  function fileFor(entry, s) {
    var f = entry.indexOf('${SourceStreamName}') >= 0 ? entry.split('${SourceStreamName}').join(s.name) : entry;
    var t = U.templateEntry(f.replace(/\.xml$/i, ''));
    return t ? f : null;
  }
  // which entry, and so which file, a stream loads with a given order
  function resolve(order, s) {
    for (var i = 0; i < order.length; i++) { var f = fileFor(order[i], s); if (f) return { i: i, file: f }; }
    return { i: -1, file: null };
  }

  function render() {
    if (!box || U.current() !== 'order') return;
    if (!S.order) {
      el.body.innerHTML = S.error ? '<div class="note bad"><span class="dot bad"></span><div><b>The template order could not be read.</b> ' + U.esc(S.error) + '</div></div>' : '<div class="skel"></div><div class="skel"></div>';
      renderBar(); return;
    }
    var order = ro() ? S.base.split(',').filter(Boolean) : S.order, live = U.live(), streams = U.streams();
    var viewers = live.reduce(function (n, s) { var v = U.viewers(s); return v == null ? n : n + v; }, 0), counted = live.some(function (s) { return U.viewers(s) != null; });
    var names = (U.templates || []).filter(function (t) { return t && !t.error && !U.copyBase(t); }).map(function (t) { return t.template; });
    var base = S.base.split(',').filter(Boolean);
    var addable = (order.indexOf(MATCH) < 0 ? [MATCH] : []).concat(names.filter(function (n) { return order.indexOf(n) < 0; }));
    el.title.textContent = 'Template order for ' + U.app;
    el.count.textContent = order.length + ' entr' + (order.length === 1 ? 'y' : 'ies');
    var h = '<div class="note"><span class="dot"></span><div><b>This order decides which template every stream in ' + U.esc(U.app) + ' gets.</b> Each stream loads the first file that exists. ' +
      'A change applies only after the application ' + U.esc(U.app) + ' restarts, and every stream in it reconnects then: ' + live.length + ' on air' + (counted ? ', ' + U.viewersText(viewers) : '') + '.</div></div>';
    h += '<div class="pnl" style="border-radius:6px">' + (order.length ? order.map(function (e, i) {
      var reached = streams.filter(function (s) { return resolve(order, s).i === i; });
      var missing = e !== MATCH && e.indexOf('${') < 0 && (S.exists[e] === false || names.indexOf(e) < 0);
      return '<div class="ordrow' + (!ro() && base[i] !== e ? ' moved' : '') + '"><span class="n">' + (i + 1) + '</span><span><span class="mono">' + U.esc(e) + '</span>' +
        (e === MATCH ? '<br><span class="small muted">The stream\'s own copy, if it has one</span>' : missing ? '<br><span class="small muted">No such file; skipped</span>' : '') + '</span>' +
        '<span class="hrow">' + (reached.length ? reached.map(function (s) { return '<span class="chip"><span class="dot ' + (s.publishing ? 'live' : '') + '"></span>' + U.esc(s.name) + '</span>'; }).join('') : '<span class="small faint">No stream reaches this entry</span>') + '</span>' +
        '<span class="acts" data-write><button type="button" class="btn sm icon" data-ord="up:' + i + '" aria-label="Move ' + U.esc(e) + ' up"' + (i === 0 ? ' disabled' : '') + '>' + U.icon('up') + '</button>' +
        '<button type="button" class="btn sm icon" data-ord="down:' + i + '" aria-label="Move ' + U.esc(e) + ' down"' + (i === order.length - 1 ? ' disabled' : '') + '>' + U.icon('down') + '</button>' +
        '<button type="button" class="btn sm icon" data-ord="rm:' + i + '" aria-label="Remove ' + U.esc(e) + '">' + U.icon('close') + '</button></span></div>';
    }).join('') : '<p class="empty-sm">No entry: WSE transcodes nothing in ' + U.esc(U.app) + '.</p>') + '</div>';
    if (!ro() && addable.length) h += '<div class="hrow" data-write><select data-ordadd style="width:280px" aria-label="Add an entry"><option value="">Add an entry</option>' + addable.map(function (n) { return '<option value="' + U.esc(n) + '">' + U.esc(n) + '</option>'; }).join('') + '</select></div>';
    if (dirty()) {
      h += '<div><div class="sec-h">What changes after the application restarts</div><table class="tbl"><thead><tr><th>Stream</th><th>Loads now</th><th>After restart</th></tr></thead><tbody>' + streams.map(function (s) {
        var a = s.template || resolve(base, s).file || 'none', b = resolve(order, s).file || 'none';
        return '<tr><td><span class="hrow"><span class="dot ' + (s.publishing ? 'live' : '') + '"></span>' + U.esc(s.name) + '</span></td><td class="mono">' + U.esc(a) + '</td><td class="mono">' + (a === b ? '<span class="faint">same</span>' : '<b>' + U.esc(b) + '</b>') + '</td></tr>';
      }).join('') + '</tbody></table></div>';
    }
    h += '<p class="small faint">Saved to <span class="mono">conf/' + U.esc(U.app) + '/Application.xml</span>.</p>' + U.roNote();
    el.body.innerHTML = h;
    el.body.querySelectorAll('[data-ord]').forEach(function (b) {
      b.onclick = function () {
        var a = b.getAttribute('data-ord').split(':'), i = +a[1], o = S.order;
        if (a[0] === 'up') { var x = o[i - 1]; o[i - 1] = o[i]; o[i] = x; }
        if (a[0] === 'down') { var y = o[i + 1]; o[i + 1] = o[i]; o[i] = y; }
        if (a[0] === 'rm') o.splice(i, 1);
        S.savedNote = null; render(); U.changed();
        var again = el.body.querySelector('[data-ord="' + a[0] + ':' + (a[0] === 'up' ? i - 1 : a[0] === 'down' ? i + 1 : i) + '"]');
        if (again && !again.disabled) again.focus();
      };
    });
    var add = el.body.querySelector('[data-ordadd]');
    if (add) add.onchange = function () { if (add.value) { S.order.push(add.value); S.savedNote = null; render(); U.changed(); } };
    renderBar();
  }
  function ro() { return !!U.readOnly; }
  function renderBar() {
    if (U.current() !== 'order') return;
    U.saveBar(el.bar, { changes: dirty() ? ['Template order: ' + S.order.join(', ')] : [], impact: 'Saved now, applies after the application ' + U.app + ' restarts. Changed streams are listed above.',
      saving: S.saving, conflict: S.conflict, problem: dirty() && !S.order.length ? 'Keep at least one template: without one WSE transcodes nothing.' : null, saved: S.savedNote,
      onSave: save, onDiscard: function () { S.order = S.base.split(',').filter(Boolean); S.conflict = false; render(); U.changed(); },
      onTheirs: function () { S.conflict = false; load(false).then(U.changed); },
      onMine: function () { S.conflict = false; S.seen = U.state && U.state.appTemplates; renderBar(); } });
  }
  function save() {
    if (!dirty() || S.saving || S.conflict || ro() || !S.order.length) return;
    S.saving = true; renderBar();
    return U.api.post(path(), { templates: S.order }).then(function () {
      // still saving until the order is read back, so the poll does not read this save as another session's
      S.savedNote = { title: 'Saved at ' + U.hhmm() + ': the template order for ' + U.app + '.', detail: 'It applies when ' + U.app + ' restarts, for example from WSE Manager\'s Applications page. Every stream in it reconnects then.' };
      U.toast('Saved the template order. It applies after ' + U.app + ' restarts.');
      return U.refreshState(true).then(function () { return load(false); }).then(function () { S.saving = false; S.conflict = false; renderBar(); U.changed(); });
    }, function (e) { S.saving = false; renderBar(); U.banner('order', 'bad', 'The template order was not saved.', e.message); });
  }

  U.screens.order = {
    init: function (b) {
      box = b;
      box.innerHTML = '<div class="pnl"><div class="ed-h"><h2 data-title></h2><span class="small faint" data-count></span></div><div class="pnl-b stack-4" data-body></div></div><div data-bar hidden></div>';
      el = { title: box.querySelector('[data-title]'), count: box.querySelector('[data-count]'), body: box.querySelector('[data-body]'), bar: box.querySelector('[data-bar]') };
    },
    show: function () { render(); if (!dirty()) load(false); },
    state: function (st) {
      // the application file changed elsewhere: reload a clean order, hold a changed one until the producer picks
      if (S.seen != null && st && st.appTemplates !== S.seen && !S.saving) {
        if (dirty()) S.conflict = true; else if (U.current() === 'order') { load(false); return; }
      }
      render();
    },
    app: function () { S.order = null; S.base = null; S.conflict = false; S.seen = null; S.savedNote = null; },
    access: function () { render(); },
    save: save,
    unsaved: function () { return !ro() && dirty() ? [{ label: 'Template order', detail: S.order.join(', '), go: 'order:' }] : []; }
  };
})();
