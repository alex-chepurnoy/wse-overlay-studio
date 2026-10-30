/* URL Overlay home, On air: the application's streams as rows, filtered from the list column. Until the module
   measures what each output draws, rows show what the template is configured to do, labeled "configured", and no
   snapshot (the fallback layout of the design). */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var box = null;
  // the page being changed inline on On air: { stream, overlay, page, url, many, saving, error } or null
  var E = null;
  var TITLES = { all: 'On air in ', attention: 'Needs attention in ', healthy: 'Healthy in ', off: 'Not publishing in ' };

  function outsHtml(s) {
    var outs = U.outputsOf(U.templateOfStream(s));
    if (!s.template) return '<span class="small faint">Not transcoded</span>';
    if (!outs.length) return '<span class="small faint">' + U.esc(s.template) + '</span>';
    return outs.map(function (o) {
      return '<span class="ochip" title="' + U.esc(U.configuredText(o)) + ', configured"><b>' + U.esc(o.name) + '</b>' + (o.carries ? 'overlay' : o.passthrough ? 'passthrough' : o.audio ? 'audio' : 'no overlay') + '</span>';
    }).join('') + '<span class="small faint">configured</span>';
  }
  // one chip per stream, the most important fact; broken beats attention. At most three amber marks on a screen.
  var amber = 0;
  function chip(s) {
    if (s.overlay && !U.overlayEntry(s.overlay)) return '<span class="bdg bad">Overlay missing</span>';
    if (s.restartNeeded) return '<span class="bdg' + (amber++ < 3 ? ' warn' : '') + '">Restart pending</span>';
    if (s.publishing && !s.overlay) return '<span class="bdg">No overlay</span>';
    return '';
  }
  function row(s) {
    var n = U.viewers(s), editing = E && E.stream === s.name;
    return '<div class="srow' + (editing ? ' editing' : '') + (U.view.highlight === s.name ? ' new' : '') + '">' +
      '<span class="dot ' + (s.publishing ? 'live' : '') + '"></span><button type="button" class="nm linkbtn" data-go="stream:' + U.esc(s.name) + '">' + U.esc(s.name) + '</button>' +
      '<span class="ov">' + (s.overlay ? U.esc(s.overlay) : '') + ' ' + chip(s) + '</span>' +
      '<span class="outs">' + outsHtml(s) + '</span>' +
      '<span class="vw" ' + (n != null ? 'title="Viewers on this server"' : '') + '>' + (s.publishing && n != null ? n + ' viewer' + (n === 1 ? '' : 's') : '') + '</span>' +
      '<span class="acts">' + (s.overlay && U.overlayEntry(s.overlay) && !U.readOnly ? '<button type="button" class="btn sm" data-change="' + U.esc(s.name) + '">Change the page</button>' : '') + '</span></div>' +
      (editing ? editHtml() : '');
  }
  // one page: its address edited in place; more pages: the overlay editor
  function editHtml() {
    if (!E.page) return '<div class="srow-edit"><div class="skel"></div></div>';
    if (E.many) return '<div class="srow-edit"><span class="small muted">' + U.esc(E.overlay) + ' has ' + E.many + ' pages.</span><button type="button" class="btn sm" data-go="overlay:' + U.esc(E.overlay) + '">Open the overlay editor</button><button type="button" class="btn sm ghost" data-ecancel>Cancel</button></div>';
    var live = U.live().filter(function (x) { return x.overlay === E.overlay; }).map(function (x) { return x.name; });
    return '<form class="srow-edit" data-eform><label class="f" for="uo-e-url">Page address of ' + U.esc(E.overlay) + '</label><div class="row"><input id="uo-e-url" type="url" data-eurl value="' + U.esc(E.url) + '" spellcheck="false" autocomplete="off">' +
      (live.length ? '<span class="bdg warn">Live on ' + U.esc(live.join(', ')) + ': goes on air when you save</span>' : '') +
      '<button type="button" class="btn" data-ecancel>Cancel</button><button type="submit" class="btn primary"' + (E.saving ? ' disabled' : '') + '>Save</button></div>' + (E.error ? '<p class="err">' + U.esc(E.error) + '</p>' : '') + '</form>';
  }
  function startEdit(name) {
    var s = U.streamRow(name);
    E = { stream: name, overlay: s.overlay, page: null, url: '', many: 0, saving: false, error: null };
    render();
    U.api.get(U.ovPath(s.overlay)).then(function (o) {
      if (!E || E.stream !== name) return;
      var pages = (o.objects || []).filter(function (x) { return x.type === 'webPage'; });
      E.full = o; E.page = pages[0] || {}; E.url = E.page.url || ''; E.many = pages.length > 1 ? pages.length : 0;
      render();
      var i = box.querySelector('[data-eurl]'); if (i) { i.focus(); i.select(); }
    }, function (e) { if (E) { E.page = {}; E.error = e.message; render(); } });
  }
  function saveEdit(url) {
    var p = U.urlProblem(url);
    if (p) { E.error = p; render(); return; }
    E.saving = true; E.url = url; render();
    var body = JSON.parse(JSON.stringify(E.full)), name = E.overlay;
    body.objects.forEach(function (x) { if (x.id === E.page.id) x.url = url; });
    U.api.post(U.ovPath(name), body).then(function () {
      E = null; U.toast('Saved ' + name + ': the new page is on air wherever it shows.');
      return U.refreshState(true).then(render);
    }, function (e) { E.saving = false; E.error = e.message; render(); });
  }

  function render() {
    if (!box) return;
    amber = 0;
    var f = U.view.filter || 'all', live = U.live(), off = U.streams().filter(function (s) { return !s.publishing; });
    var list = f === 'attention' ? live.filter(U.attention) : f === 'healthy' ? live.filter(function (s) { return !U.attention(s); }) : f === 'off' ? off : live;
    var anyViewers = live.some(function (s) { return U.viewers(s) != null; });
    // first visit: nothing to show yet, one way in
    if (!(U.overlays || []).length && !live.length) {
      box.innerHTML = '<div class="pnl firstrun"><h2>Burn a web page into a stream</h2><p class="muted">Four steps: the page, the renditions that carry it, the stream, and a look at what viewers get.</p>' +
        (U.readOnly ? U.roNote() : '<button type="button" class="btn primary" data-task>Put a page on a stream</button>') + '</div>';
      bind(); return;
    }
    var h = '<div class="home-h"><h2>' + TITLES[f] + U.esc(U.app) + '</h2><span class="small faint">What each template is configured to draw' + (anyViewers ? '; viewers on this server' : '') + '</span>' +
      '<span class="spacer"></span><button type="button" class="btn primary" data-write data-task>' + U.icon('plus') + 'Put a page on a stream</button></div>';
    h += '<div class="pnl">' + (list.length ? '<div class="srow-h"><span></span><span>Stream</span><span class="c3">Overlay</span><span class="c4">Outputs</span><span>' + (anyViewers ? 'Viewers' : '') + '</span><span></span></div>' + list.map(row).join('')
      : '<p class="empty-sm">' + (f === 'all' ? 'No stream is publishing to ' + U.esc(U.app) + ' now. Streams show here while they publish.' : 'Nothing here.') + '</p>') + '</div>';
    if (f === 'all' && off.length) h += '<div class="offrow"><span class="small muted">Not publishing:</span>' + off.map(function (s) {
      return '<button type="button" class="chip' + (U.view.highlight === s.name ? ' new' : '') + '" data-go="stream:' + U.esc(s.name) + '"><span class="dot"></span>' + U.esc(s.name) + (s.overlay ? '<span class="k">' + U.esc(s.overlay) + ' when it starts</span>' : '') + '</button>';
    }).join('') + '</div>';
    var blocks = ((U.serverInfo && U.serverInfo.egress && U.serverInfo.egress.recentBlocks) || []).slice(-3).reverse();
    if (blocks.length) h += '<div class="pnl" style="margin-top:16px"><div class="pnl-h"><h3>Recent page blocks</h3><span class="small faint">Since WSE started</span><span class="spacer"></span>' +
      '<button type="button" class="btn sm" data-status-open="network">Network rules</button></div><ul class="loglist">' + blocks.map(function (b) {
        return '<li><span class="dot bad"></span><span class="faint">' + U.esc(U.hhmm(new Date(b.at))) + '</span><span>' + U.esc(b.host) + ' blocked: ' + U.esc(b.rule) + (b.capture ? ' (' + U.esc(b.capture) + ')' : '') + '</span></li>';
      }).join('') + '</ul></div>';
    box.innerHTML = h;
    bind();
  }
  function bind() {
    box.querySelectorAll('[data-task]').forEach(function (b) { b.onclick = function () { U.startTask(); }; });
    box.querySelectorAll('[data-change]').forEach(function (b) { b.onclick = function () { startEdit(b.getAttribute('data-change')); }; });
    box.querySelectorAll('[data-ecancel]').forEach(function (b) { b.onclick = function () { E = null; render(); }; });
    var f = box.querySelector('[data-eform]');
    if (f) f.onsubmit = function (e) { e.preventDefault(); saveEdit(f.querySelector('[data-eurl]').value.trim()); };
  }

  U.screens.home = {
    init: function (b) { box = b; },
    show: function () { render(); },
    // a poll does not redraw a row while its page address is being edited
    state: function () { if (U.current() === 'home' && !(E && box.contains(document.activeElement))) render(); },
    hide: function () { E = null; U.view.highlight = null; }
  };
})();
