/* URL Overlay shell: the header with its status strip, the tabbed list column, the router between screens, the status
   drawer, the module-not-added state, the help dialog, theme, keyboard and the Manager navbar measure. Routes never
   touch location.hash; the view is kept in localStorage per application. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var root, el = {}, current = null, drawer = null, search = {};
  // which list tab each screen belongs to
  var TAB = { home: 'onair', stream: 'streams', overlay: 'overlays', 'new-overlay': 'overlays', template: 'templates', order: 'templates', nomodule: null, task: null };
  U.view = { screen: 'home', id: null, tab: 'onair', filter: 'all' };

  /* ---------- router ---------- */
  function saveView() { if (U.app) U.store.set('view.v1.' + U.app, JSON.stringify(U.view)); }
  // target: "screen:id", for example "overlay:Scoreboard", "home:" or "template:"
  U.go = function (target, opts) {
    var i = String(target).indexOf(':'), screen = i < 0 ? target : target.slice(0, i), id = i < 0 ? null : target.slice(i + 1) || null;
    opts = opts || {};
    if (!U.screens[screen]) screen = 'home';
    if (!U.appInstalled() && U.app) screen = 'nomodule';
    var was = U.screens[current];
    if (was && was.hide && (current !== screen || U.view.id !== id)) was.hide();
    U.view.screen = screen; U.view.id = id;
    if (opts.filter) U.view.filter = opts.filter;
    var tab = TAB[screen];
    // a publishing stream opened from On air keeps the On air tab
    if (screen === 'stream' && U.view.tab === 'onair' && !opts.tab) tab = 'onair';
    if (opts.tab) tab = opts.tab;
    if (tab) U.view.tab = tab;
    if (tab && tab !== 'onair' && id) { U.view.last = U.view.last || {}; U.view.last[tab] = screen + ':' + id; }
    if (screen === 'home' && !opts.filter && !opts.keepFilter) U.view.filter = 'all';
    current = screen;
    Object.keys(U.screens).forEach(function (k) { var s = el.center.querySelector('[data-screen="' + k + '"]'); if (s) s.hidden = k !== screen; });
    var scr = U.screens[screen];
    el.grid.classList.toggle('has-insp', !!scr.insp);
    // the task takes the full width: its stepper and summary replace the list column
    el.grid.classList.toggle('no-left', screen === 'nomodule' || screen === 'task');
    el.grid.classList.remove('show-left'); el.grid.classList.remove('insp-open');
    el.left.hidden = screen === 'nomodule' || screen === 'task';
    el.insp.hidden = !scr.insp;
    if (!scr.insp) el.insp.innerHTML = '';
    saveView();
    renderTabs(); renderList();
    scr.show(id);
    U.closeMenus();
  };
  U.current = function () { return current; };
  U.showList = function (on) { el.grid.classList.toggle('show-left', on); };
  U.toggleInsp = function () { el.grid.classList.toggle('insp-open'); };

  /* ---------- unsaved work across screens: kept in memory while the page is open ---------- */
  U.unsaved = function () {
    var all = [];
    Object.keys(U.screens).forEach(function (k) { if (U.screens[k].unsaved) all = all.concat(U.screens[k].unsaved()); });
    return U.readOnly ? [] : all;
  };
  U.changed = function () { renderStrip(); renderItems(); if (drawer) renderDrawer(); };

  /* ---------- header ---------- */
  function si(key, k, v, attrs) {
    return '<button type="button" class="si" ' + (attrs || 'data-status-open="' + key + '"') + '><span class="k">' + k + '</span><span class="v">' + v + '</span></button>';
  }
  function renderStrip() {
    if (!el.strip) return;
    var i = U.serverInfo, svc = (i && i.service) || {}, c = (i && i.chrome) || {}, cap = (i && i.captures) || {}, eg = (i && i.egress) || {};
    var nomod = U.app && !U.appInstalled(), faint = '<span class="faint">Not added</span>';
    var capV = !i ? (U.infoError ? '<span class="dot bad"></span>Not reachable' : '<span class="faint">Checking</span>') :
      !svc.running ? '<span class="dot bad"></span>Stopped' : U.serverReady(i) ? '<span class="dot on"></span><span>Running<span class="wide">, Chrome ' + U.esc(String(c.version || '').split('.')[0]) + '</span></span>' :
      '<span class="dot bad"></span>Chrome not ready';
    var blocks = (eg.recentBlocks || []).length;
    var pend = (U.appStreams || []).filter(function (s) { return s.restartNeeded; }).map(function (s) { return s.name; });
    var un = U.unsaved(), live = U.live();
    el.strip.innerHTML = si('capture', 'Capture service', capV) +
      si('browsers', 'Browsers', i ? U.esc(cap.active || 0) + ' of ' + U.esc(cap.limit || 0) : '<span class="faint">-</span>') +
      // pages first, as the live check; blocks only when there are some, and they open the network rules
      si(blocks || cap.pagesFailing ? 'network' : 'browsers', 'Pages', !i || cap.pagesCapturing == null ? '<span class="faint">-</span>' :
        (cap.pagesFailing ? '<span class="dot bad"></span>' : '') + U.esc(cap.pagesCapturing) + ' capturing' + (cap.pagesFailing ? ', ' + U.esc(cap.pagesFailing) + ' not' : '') + (blocks ? ', ' + blocks + ' blocked' : '')) +
      (nomod ? si('restart', 'Stream restart', faint) + si('unsaved', 'Unsaved', faint) :
        si('restart', 'Stream restart', pend.length ? '<span class="dot warn"></span>' + U.esc(pend[0]) + (pend.length > 1 ? ' +' + (pend.length - 1) : '') : 'None') +
        si('unsaved', 'Unsaved', un.length ? '<span class="chip-draft">' + U.esc(un[0].label) + (un.length > 1 ? ' +' + (un.length - 1) : '') + '</span>' : 'None')) +
      si('onair', 'On air', nomod ? live.length + ' streams' : '<span class="dot ' + (live.length ? 'live' : '') + '"></span>' + live.length + ' stream' + (live.length === 1 ? '' : 's'),
        nomod ? 'data-status-open="restart"' : 'data-go="home:"');
  }

  /* ---------- left column: tabs and lists ---------- */
  function renderTabs() {
    el.left.querySelectorAll('[data-tab]').forEach(function (b) {
      var on = b.getAttribute('data-tab') === U.view.tab;
      b.setAttribute('aria-selected', String(on)); b.tabIndex = on ? 0 : -1;
    });
    el.tabSelect.value = U.view.tab;
  }
  // a tab opens what it last showed, else its first item, so the main panel always matches the list beside it
  function setTab(tab) {
    U.view.tab = tab;
    if (tab === 'onair') { U.go('home:'); return; }
    renderTabs(); renderList(); saveView();
    var items = Array.prototype.slice.call(el.list.querySelectorAll('[data-items] [data-go]')).filter(function (b) { return b.getAttribute('data-go').indexOf('order:') !== 0; });
    var last = (U.view.last || {})[tab], pick = items.filter(function (b) { return b.getAttribute('data-go') === last; })[0] || items[0];
    if (pick) U.go(pick.getAttribute('data-go'), { tab: tab });
    // an empty list opens what makes its first item, or On air when there is nothing to make
    else U.go(tab === 'overlays' && !U.readOnly ? 'new-overlay:' : tab === 'templates' && !U.readOnly ? 'template:' : 'home:', { tab: tab });
  }
  // what to open once go (for example "template:alex") is deleted: the row below it in the same list, else the one above
  U.neighborOf = function (go) {
    var all = Array.prototype.slice.call(el.list.querySelectorAll('[data-items] .li[data-go]')).map(function (b) { return b.getAttribute('data-go'); })
      .filter(function (g) { return g !== 'new-overlay:' && g !== 'template:'; });
    var i = all.indexOf(go);
    return i < 0 ? null : all[i + 1] || all[i - 1] || null;
  };
  // after a delete the page stays in the list it was in: near, when it is still there, else the tab's first item
  U.stayIn = function (tab, near) {
    if (near && el.list.querySelector('[data-items] .li[data-go="' + CSS.escape(near) + '"]')) U.go(near, { tab: tab });
    else setTab(tab);
  };
  // the tab's head (search and New) is built once per tab, so typing in the search box keeps its focus
  function renderList() {
    var tab = U.view.tab;
    if (el.list.getAttribute('data-for') !== tab + '|' + U.app + '|' + !!U.readOnly) {
      el.list.setAttribute('data-for', tab + '|' + U.app + '|' + !!U.readOnly);
      var head = '';
      if (tab !== 'onair') {
        head = '<div class="lsearch"><input type="search" data-search placeholder="Search" aria-label="Search this list" value="' + U.esc(search[tab] || '') + '">' +
          (tab === 'overlays' ? '<button type="button" class="btn sm" data-write data-go="new-overlay:">' + U.icon('plus') + 'New</button>' :
           tab === 'templates' ? '<button type="button" class="btn sm" data-write data-go="template:">' + U.icon('plus') + 'New</button>' :
           '<button type="button" class="btn sm" data-write data-addstream>' + U.icon('plus') + 'Add</button>') + '</div>' +
          (tab === 'streams' ? '<div class="ladd" data-addrow hidden><input type="text" data-addname placeholder="Stream name, as the encoder publishes it" aria-label="Stream name" autocomplete="off" spellcheck="false">' +
            '<button type="button" class="btn sm primary" data-addok>Add</button></div><p class="err lfoot" data-adderr hidden></p>' : '');
      }
      el.list.innerHTML = head + '<ul class="llist" role="listbox" aria-label="' + U.esc(tab) + '" data-items></ul><div data-foot></div>';
      var s = el.list.querySelector('[data-search]');
      if (s) s.addEventListener('input', function () { search[tab] = s.value; renderItems(); });
      var add = el.list.querySelector('[data-addstream]');
      if (add) add.onclick = function () { var r = el.list.querySelector('[data-addrow]'); r.hidden = !r.hidden; if (!r.hidden) r.querySelector('input').focus(); };
      var ok = el.list.querySelector('[data-addok]');
      if (ok) {
        var name = el.list.querySelector('[data-addname]'), err = el.list.querySelector('[data-adderr]');
        var addIt = function () {
          var v = name.value.trim();
          if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(v)) { err.textContent = 'Letters, digits, dot, dash and underscore.'; err.hidden = false; return; }
          err.hidden = true;
          if (U.added.indexOf(v) < 0) U.added.push(v);
          name.value = ''; el.list.querySelector('[data-addrow]').hidden = true;
          U.go('stream:' + v, { tab: 'streams' });
        };
        ok.onclick = addIt;
        name.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); addIt(); } if (e.key === 'Escape') { e.stopPropagation(); el.list.querySelector('[data-addrow]').hidden = true; } });
      }
    }
    renderItems();
  }
  function item(go, dot, t, s, meta, sel, attrs, menu, cls) {
    return '<li' + (menu ? ' class="lrow' + (cls ? ' ' + cls : '') + '"' : cls ? ' class="' + cls + '"' : '') + '><button type="button" class="li" role="option" aria-selected="' + !!sel + '" tabindex="-1" ' + (attrs || 'data-go="' + U.esc(go) + '"') + '>' +
      '<span class="dot ' + dot + '"></span><span style="min-width:0"><span class="t">' + t + '</span>' + (s ? '<span class="s">' + s + '</span>' : '') + '</span><span>' + (meta || '') + '</span></button>' + (menu || '') + '</li>';
  }
  function matches(text) { var q = (search[U.view.tab] || '').toLowerCase(); return !q || text.toLowerCase().indexOf(q) >= 0; }
  U.attention = function (s) { return s.publishing && (!!s.restartNeeded || !!(s.overlay && !U.overlayEntry(s.overlay))); };
  function unsavedFor(go) { return U.unsaved().some(function (u) { return u.go === go; }) ? '<span class="chip-draft">Unsaved</span>' : ''; }
  function renderItems() {
    var box = el.list.querySelector('[data-items]'), foot = el.list.querySelector('[data-foot]');
    if (!box) return;
    var tab = U.view.tab, h = [], v = U.view;
    foot.innerHTML = '';
    if (tab === 'onair') {
      var live = U.live(), att = live.filter(U.attention), off = U.streams().filter(function (s) { return !s.publishing; });
      [['all', 'All on air', live.length, 'live'], ['attention', 'Needs attention', att.length, ''], ['healthy', 'Healthy', live.length - att.length, ''], ['off', 'Not publishing', off.length, '']].forEach(function (f) {
        h.push(item(null, f[3], f[1], '', '<span class="small muted num">' + f[2] + '</span>', v.screen === 'home' && v.filter === f[0], 'data-filter="' + f[0] + '"' + (f[0] === 'attention' ? ' title="A restart pending, or a template naming an overlay that does not exist"' : '')));
      });
      foot.innerHTML = '';
    } else if (tab === 'streams') {
      U.streams().forEach(function (s) {
        var sub = (s.publishing ? '' : 'Not publishing, ') + (s.overlay ? s.overlay : 'No overlay') + (s.restartNeeded ? ', restart pending' : '') + (s.added ? ', added here' : '');
        if (!matches(s.name + ' ' + sub)) return;
        h.push(item('stream:' + s.name, s.publishing ? 'live' : '', U.esc(s.name), U.esc(sub), unsavedFor('stream:' + s.name), v.screen === 'stream' && v.id === s.name, null,
          U.rowMenuHtml('stream:' + s.name, 'Actions for ' + s.name, U.streamItems(s.name))));
      });
      if (!U.streams().length) foot.innerHTML = '<p class="small faint lfoot">Streams show here while they publish. Add a name for one that has not started yet.</p>';
    } else if (tab === 'overlays') {
      (U.overlays || []).forEach(function (o) {
        var live = U.liveShowing(o.name), tpl = o.templates || [];
        var sub = o.error ? 'Cannot be read' : live.length ? 'On air on ' + live.join(', ') : tpl.length ? 'In ' + tpl.join(', ') : 'In no template';
        var open = v.screen === 'overlay' && v.id === o.name;
        if (!open && !matches(o.name + ' ' + sub)) return;
        h.push(item('overlay:' + o.name, o.error ? 'bad' : live.length ? 'on' : '', U.esc(o.name), U.esc(sub), unsavedFor('overlay:' + o.name), open, null,
          U.rowMenuHtml('overlay:' + o.name, 'Actions for ' + o.name, [['addpage', 'Add page'], ['dup', 'Duplicate...'], '-', ['del', 'Delete overlay...', true]])));
        // the open overlay's pages sit under it, with Add page last
        if (open) h.push('<li class="lsub-host" data-ovpages></li>');
      });
      var nu = unsavedFor('new-overlay:');
      if (v.screen === 'new-overlay' || nu) h.unshift(item('new-overlay:', '', 'New overlay', 'Not created yet', nu, v.screen === 'new-overlay'));
      if (!(U.overlays || []).length) foot.innerHTML = '<p class="small faint lfoot">An overlay holds the pages to burn in, for example the scoring vendor\'s clock and score.</p>';
    } else if (tab === 'templates') {
      var nt = unsavedFor('template:');
      if ((v.screen === 'template' && !v.id) || nt) h.push(item('template:', '', 'New master template', 'Not created yet', nt, v.screen === 'template' && !v.id));
      var list = (U.templates || []).filter(function (t) { return t && !t.error && !U.copyBase(t) && !t.vif; });
      var mine = list.filter(function (t) { return t.overlay || t.managed; }), other = list.filter(function (t) { return !(t.overlay || t.managed); });
      function copyItem(c) {
        var s = U.streamRow(c.name), sel = v.screen === 'template' && v.id === c.name, behind = c.copyCurrent === false, base = U.copyBase(c);
        return item('template:' + c.name, s && s.publishing ? 'live' : '', U.icon('sub') + U.esc(c.name), behind ? '<span class="warn-text">Differs from ' + U.esc(base) + '</span>' : 'Copy for stream ' + U.esc(c.name) + (s && s.publishing ? ', publishing' : ''), '', sel, null,
          (behind && !U.readOnly ? '<button type="button" class="btn sm rowupd" data-write data-cupd="' + U.esc(c.name) + '" title="Copy ' + U.esc(base) + ' as saved now into ' + U.esc(c.name) + '">Update</button>' : '') +
          U.rowMenuHtml('copy:' + c.name, 'Actions for the copy ' + c.name, [['update', 'Update from ' + base], ['stream', 'Open stream ' + c.name]]), 'lsub' + (behind ? ' behind' : ''));
      }
      function tplItem(t, master) {
        var users = U.streamsUsing(t.name), live = users.filter(function (s) { return s.publishing; });
        var copies = (U.templates || []).filter(function (c) { return c && U.copyBase(c) === t.name; });
        var behind = copies.filter(function (c) { return c.copyCurrent === false; }).length;
        var sub = (t.overlay ? t.overlay : 'No overlay') + (master ? ', ' + copies.length + ' stream cop' + (copies.length === 1 ? 'y' : 'ies') + (behind ? ', ' + behind + ' differ' + (behind === 1 ? 's' : '') : '') : t.shipped ? ', ships with WSE' : '');
        if (!matches(t.name + ' ' + sub + ' ' + copies.map(function (c) { return c.name; }).join(' '))) return '';
        return item('template:' + t.name, live.length ? 'on' : '', U.esc(t.name), U.esc(sub), unsavedFor('template:' + t.name), v.screen === 'template' && v.id === t.name, null,
          U.rowMenuHtml('template:' + t.name, 'Actions for ' + t.name, master ? [['dup', 'Duplicate...'], '-', ['del', 'Delete template...', true, !t.managed]] : [['dup', 'New master from this one']])) +
          copies.map(copyItem).join('');
      }
      var a = mine.map(function (t) { return tplItem(t, true); }).join(''), b = other.map(function (t) { return tplItem(t, false); }).join('');
      if (a) h.push('<li class="lgroup">Master templates' + U.tip('A master is where a template is edited. A stream runs its own copy of a master, made when it is put on the stream, so editing a master changes no stream until its copy is updated from it.') + '</li>' + a);
      if (b) h.push('<li class="lgroup">Other templates on this server</li>' + b);
      // copies whose master is gone: nothing above lists them, and they cannot be updated
      var orphans = (U.templates || []).filter(function (c) { return c && !c.error && U.copyBase(c) && !U.templateEntry(U.copyBase(c)); }).filter(function (c) { return matches(c.name + ' ' + U.copyBase(c)); });
      if (orphans.length) h.push('<li class="lgroup">Copies without a master' + U.tip('Each is the own copy, for one stream, of a master that no longer exists. The stream still runs it; it can no longer be updated. Delete it to have the stream use the template order again.') + '</li>' + orphans.map(function (c) {
        var s = U.streamRow(c.name);
        return item('template:' + c.name, s && s.publishing ? 'live' : '', U.esc(c.name), 'Copy of ' + U.esc(U.copyBase(c)) + ', which no longer exists', '', v.screen === 'template' && v.id === c.name, null,
          U.rowMenuHtml('copy:' + c.name, 'Actions for the copy ' + c.name, [['stream', 'Open stream ' + c.name], '-', ['del', 'Delete this copy...', true, !!(s && s.publishing)]]));
      }).join(''));
    }
    var pages = U.overlayPagesNode, a = document.activeElement, kept = pages && a && pages.contains(a) ? a : null;
    box.innerHTML = h.join('') || '<li class="small faint" style="padding:8px 12px">Nothing matches.</li>';
    var host = box.querySelector('[data-ovpages]');
    if (host && pages) { host.appendChild(pages); if (kept && pages.contains(kept)) kept.focus({ preventScroll: true }); }
    var items = box.querySelectorAll('.li'), stop = box.querySelector('.li[aria-selected="true"]') || items[0];
    if (stop) stop.tabIndex = 0;
  }

  /* ---------- tips: one box placed in the window, so a panel's edge or scroll never cuts it off ---------- */
  function bindTips(r) {
    var box = document.createElement('div'), on = null;
    box.className = 'uo-tipbox'; box.setAttribute('role', 'tooltip'); box.hidden = true;
    r.appendChild(box);
    function show(t) {
      on = t; box.textContent = t.getAttribute('data-tip'); box.hidden = false;
      var a = t.getBoundingClientRect(), w = box.offsetWidth, h = box.offsetHeight;
      var x = Math.max(8, Math.min(window.innerWidth - w - 8, a.left + a.width / 2 - w / 2));
      var y = a.bottom + 6 + h > window.innerHeight - 8 ? a.top - h - 6 : a.bottom + 6;
      box.style.left = x + 'px'; box.style.top = Math.max(8, y) + 'px';
    }
    function hide() { on = null; box.hidden = true; }
    U.listen(r, 'mouseover', function (e) { var t = e.target.closest && e.target.closest('[data-tip]'); if (t && t !== on) show(t); else if (!t && on) hide(); });
    U.listen(r, 'mouseleave', hide);
    U.listen(r, 'focusin', function (e) { var t = e.target.closest && e.target.closest('[data-tip]'); if (t) show(t); else hide(); });
    U.listen(r, 'focusout', hide);
    U.listen(window, 'scroll', hide, { passive: true, capture: true });
  }

  /* ---------- status drawer ---------- */
  function openDrawer(section, opener) {
    drawer = { section: section, back: opener || document.activeElement };
    measure();
    renderDrawer(true);
  }
  function closeDrawer() {
    if (!drawer) return;
    var back = drawer.back;
    drawer = null;
    el.drawer.innerHTML = '';
    if (back && back.focus && document.body.contains(back)) back.focus();
  }
  function renderDrawer(focus) {
    var y = el.drawer.querySelector('.drawer') ? el.drawer.querySelector('.drawer').scrollTop : 0;
    var pend = (U.appStreams || []).filter(function (s) { return s.restartNeeded; });
    var un = U.unsaved();
    var secs = U.serverSections(U.serverInfo).concat([
      ['restart', 'Stream restart', pend.length ? pend.map(function (s) {
        var n = U.viewers(s);
        return '<p><b>' + U.esc(s.name) + '</b>: its template builds other outputs since it started; they take effect when it restarts. The overlay is not affected' +
          (n != null ? '. ' + U.viewersText(n) : '') + '. <button type="button" class="btn sm" data-go="stream:' + U.esc(s.name) + '" data-close-after>Open ' + U.esc(s.name) + '</button></p>';
      }).join('') : '<p class="muted">None.</p>'],
      ['unsaved', 'Unsaved', un.length ? un.map(function (u) {
        return '<p class="hrow"><span class="chip-draft">Unsaved</span><span>' + U.esc(u.label) + (u.detail ? ': ' + U.esc(u.detail) : '') + '</span><span class="spacer"></span>' +
          '<button type="button" class="btn sm" data-go="' + U.esc(u.go) + '" data-close-after>Open</button></p>';
      }).join('') + '<p class="small faint" style="margin-top:8px">Unsaved work is kept while this page stays open. Leaving WSE Manager or reloading drops it.</p>' : '<p class="muted">None.</p>']
    ]);
    el.drawer.innerHTML = '<div class="scrim" data-close></div><aside class="drawer" role="dialog" aria-modal="true" aria-labelledby="uo-dr-h">' +
      '<div class="pnl-h"><h3 id="uo-dr-h" tabindex="-1">Status</h3><span class="small faint">Read-only here</span><span class="spacer"></span>' +
      '<button type="button" class="btn sm icon ghost" data-close aria-label="Close">' + U.icon('close') + '</button></div>' +
      secs.map(function (s) { return '<section id="uo-sec-' + s[0] + '" class="' + (focus && drawer.section === s[0] ? 'focus' : '') + '"><div class="sec-h">' + s[1] + '</div>' + s[2] + '</section>'; }).join('') + '</aside>';
    var d = el.drawer.querySelector('.drawer');
    if (!focus) { d.scrollTop = y; return; }
    var target = el.drawer.querySelector('#uo-sec-' + drawer.section);
    if (target) { target.tabIndex = -1; target.scrollIntoView({ block: 'nearest' }); target.focus({ preventScroll: true }); }
    else el.drawer.querySelector('#uo-dr-h').focus();
  }

  /* ---------- module not added ---------- */
  U.screens.nomodule = {
    init: function (box) { el.nomod = box; },
    show: function () {
      var a = U.appEntry() || { name: U.app }, live = U.live();
      el.nomod.innerHTML = '<div class="pnl empty"><div class="pnl-b stack-4"><h2>URL Overlay is not added to ' + U.esc(a.name) + '</h2>' +
        '<p class="muted">Overlays and streams belong to an application. ' + U.esc(a.name) + ' has no URL Overlay module yet, so its streams cannot show an overlay. Templates on this server are shared and already usable here.</p>' +
        '<table class="tbl kvt"><tbody><tr><td>File written</td><td><span class="mono">conf/' + U.esc(a.name) + '/Application.xml</span>, one module entry, backed up first</td></tr>' +
        '<tr><td>Takes effect</td><td>After the application ' + U.esc(a.name) + ' restarts. ' + (live.length ? live.length + ' stream' + (live.length > 1 ? 's' : '') + ' publishing there reconnect within seconds.' : 'Nothing is publishing there now.') + '</td></tr>' +
        '<tr><td>Undo</td><td>Uninstalling URL Overlay takes it out again</td></tr></tbody></table>' +
        (U.readOnly ? U.roNote() : '<div class="hrow"><button type="button" class="btn primary" data-addmodule>Add URL Overlay to ' + U.esc(a.name) + '</button>' +
          ((U.apps || []).some(function (x) { return x.installed; }) ? '<button type="button" class="btn" data-backapp>Back to ' + U.esc(((U.apps || []).filter(function (x) { return x.installed; })[0] || {}).name) + '</button>' : '') + '</div>') +
        '</div></div>';
      var b = el.nomod.querySelector('[data-addmodule]');
      if (b) b.onclick = function () { addModule(a.name); };
      var back = el.nomod.querySelector('[data-backapp]');
      if (back) back.onclick = function () { var to = (U.apps || []).filter(function (x) { return x.installed; })[0]; if (to) { el.app.value = to.name; setApp(to.name); } };
    },
    state: function () { if (current === 'nomodule') U.screens.nomodule.show(); }
  };
  function addModule(app) {
    var live = U.live();
    U.choose('Add URL Overlay to ' + app + '?',
      '<p>Writes one module entry to <span class="mono">conf/' + U.esc(app) + '/Application.xml</span>, backed up first. Uninstalling URL Overlay takes it out again.</p>' +
      '<p>WSE loads modules when an application starts, so ' + U.esc(app) + ' has to restart. ' + (live.length ? 'These streams drop and reconnect:</p><ul>' + live.map(function (s) {
        var n = U.viewers(s);
        return '<li><b>' + U.esc(s.name) + '</b>' + (n != null ? ', ' + U.viewersText(n) : '') + ', ' + U.arrivalText(s) + '</li>';
      }).join('') + '</ul>' + U.FINAL : 'Nothing is publishing to it now, so a restart affects no stream.</p>'),
      [{ label: 'Cancel', value: null }, { label: 'Add, restart later', value: 'later' }, { label: 'Add and restart now', value: 'now', kind: 'primary' }]).then(function (v) {
        if (!v) return;
        U.api.post(U.appPath(app) + '/module', { restart: v === 'now' }).then(function (r) {
          U.toast(r.added ? 'Added URL Overlay to ' + app + (v === 'now' ? ', restarting it now.' : '. It starts working when ' + app + ' restarts.') : 'URL Overlay was already in ' + app + '.');
          if (r.added && r.recorded === false) U.banner('notrecorded', 'slow', 'The change to ' + app + ' is not in an install record.', 'URL Overlay was installed without the installer, so uninstalling will not take it out of ' + app + '.');
          return loadApps(app).then(function () { setApp(app, true); });
        }, function (e) { U.banner('addmodule', 'bad', 'URL Overlay was not added to ' + app + '.', e.message); });
      });
  }

  /* ---------- applications ---------- */
  // live applications, those with the module first; the others can have it added from here
  function loadApps(want) {
    return U.api.get(U.vhostPath() + '/applications').then(function (r) {
      U.apps = ((r && r.applications) || []).filter(function (a) { return !a.error && /^live/.test(a.streamType || ''); });
      if (!U.apps.length) {
        el.app.innerHTML = '<option value="">none</option>';
        U.banner('noapp', 'slow', 'This server has no live application.', 'Make one in WSE Manager, then reload this page.');
        return null;
      }
      var with_ = U.apps.filter(function (a) { return a.installed; }), without = U.apps.filter(function (a) { return !a.installed; });
      if (!U.apps.some(function (a) { return a.name === want; })) want = (with_[0] || U.apps[0]).name;
      function opts(list) { return list.map(function (a) { return '<option value="' + U.esc(a.name) + '"' + (a.name === want ? ' selected' : '') + '>' + U.esc(a.name) + '</option>'; }).join(''); }
      el.app.innerHTML = (with_.length ? '<optgroup label="With URL Overlay">' + opts(with_) + '</optgroup>' : '') +
        (without.length ? '<optgroup label="Without it, can be added">' + opts(without) + '</optgroup>' : '');
      return want;
    });
  }
  function setApp(name, keepView) {
    U.app = name || null;
    U.store.set('app', U.app || '');
    U.state = null; U.overlays = []; U.appStreams = []; U.templates = []; U.added = [];
    Object.keys(U.screens).forEach(function (k) { if (U.screens[k].app) U.screens[k].app(U.app); });
    var stored = null;
    try { stored = JSON.parse(U.store.get('view.v1.' + U.app) || 'null'); } catch (e) { stored = null; }
    U.view = { screen: 'home', id: null, tab: 'onair', filter: 'all' };
    if (stored && typeof stored === 'object') { U.view.tab = stored.tab || 'onair'; U.view.filter = stored.filter || 'all'; }
    el.list.removeAttribute('data-for');
    renderStrip();
    U.refreshState(true).then(function () {
      // the page always opens on On air: what is live, and the way in to put a page on a stream
      U.go(keepView && current && current !== 'nomodule' ? current + ':' + (U.view.id || '') : 'home:', { tab: keepView ? (U.view.tab === 'onair' ? null : U.view.tab) : 'onair', keepFilter: true });
    });
    U.startStatePoll();
  }

  /* ---------- help ---------- */
  function help() {
    var rows = [['Ctrl+S', 'Save the screen in view'], ['Ctrl+Z', 'Undo an unsaved overlay edit'], ['Ctrl+Shift+Z', 'Redo'],
      ['Delete', 'Remove the selected page from the draft (Undo in the message)'], ['Arrow keys', 'Nudge the selected page by 1 px'],
      ['Shift+Arrow keys', 'Nudge by 10 px'], ['Left, Right', 'Move between the list tabs'], ['Up, Down, Home, End', 'Move in a list'],
      ['Escape', 'Close a drawer, menu or dialog; deselect a page'], ['?', 'This help']];
    U.choose('How URL Overlay works', '<p>A camera publishes a stream to a Wowza application. The application\'s transcoder loads a ' +
      '<strong>transcoder template</strong> for the stream, which decodes it, draws the overlay and makes the outputs viewers watch, ' +
      'for example <code>stream_720p</code>. An <strong>overlay</strong> holds the web pages to draw; a template names the overlay it draws.</p>' +
      '<p>You edit a <strong>master template</strong>. Putting it on a stream gives that stream its own copy, so a stream changes only when its copy is updated from the master.</p>' +
      '<p>Nothing is written while you edit. Each screen has one save bar at the bottom, for that screen\'s object only. Unsaved work stays while this page is open.</p>' +
      '<h4>Keyboard</h4><div class="shortcuts">' + rows.map(function (r) {
        return '<span>' + r[0].split(/(\+|, )/).map(function (k) { return k === '+' || k === ', ' ? k : '<span class="kbd">' + U.esc(k) + '</span>'; }).join('') + '</span><span>' + U.esc(r[1]) + '</span>';
      }).join('') + '</div><p class="hint" style="margin-top:8px">Shortcuts other than Ctrl+S work when the cursor is not in a text field.</p>',
      [{ label: 'Close', value: null, kind: 'primary' }]);
  }

  /* ---------- settings ---------- */
  function settings() {
    var order = U.state && U.state.appTemplates ? String(U.state.appTemplates).split(',').filter(Boolean) : [];
    U.choose('Settings', '<section class="set"><div class="set-h">Capture service<span class="small faint">Every application on this server</span></div>' +
      '<div class="set-row"><div><b>Pages captured at once</b><div class="hint" data-limnote>Reading...</div></div>' +
      '<div class="hrow" data-write><input type="number" class="num" data-lim min="1" max="32" style="width:80px" aria-label="Pages captured at once" disabled><button type="button" class="btn sm" data-limset disabled>Apply</button></div></div>' +
      '<p class="err" data-limerr hidden></p></section>' +
      '<section class="set"><div class="set-h">Template order for ' + U.esc(U.app || '') + '<span class="small faint">This application</span></div>' +
      '<div class="set-row"><div><div class="mono small">' + (order.length ? order.map(U.esc).join(', ') : 'Not set') + '</div><div class="hint">WSE tries these in order for each stream and uses the first that exists. It reads the list when the application starts.</div></div>' +
      '<button type="button" class="btn sm" data-goorder>Edit</button></div></section>' +
      '<section class="set"><div class="set-h">This browser</div><label class="check"><input type="checkbox" data-keys-toggle' + (root.getAttribute('data-keys') === 'on' ? ' checked' : '') + '>Show WSE setting names under the fields</label></section>',
      [{ label: 'Close', value: null, kind: 'primary' }]);
    var all = root.querySelectorAll('.modal'), m = all[all.length - 1];
    if (!m) return;
    var close = function () { var b = m.querySelector('.foot .btn'); if (b) b.click(); };
    m.querySelector('[data-goorder]').onclick = function () { close(); U.go('order:', { tab: 'templates' }); };
    var t = m.querySelector('[data-keys-toggle]');
    t.onchange = function () { if (t.checked) root.setAttribute('data-keys', 'on'); else root.removeAttribute('data-keys'); U.store.set('keys', t.checked ? 'on' : 'off'); };
    var inp = m.querySelector('[data-lim]'), set = m.querySelector('[data-limset]'), note = m.querySelector('[data-limnote]'), err = m.querySelector('[data-limerr]');
    function show(r) {
      if (!r.running) { note.textContent = 'The capture service is not running, so the limit cannot be read or changed now.'; return; }
      err.hidden = !r.refused;
      if (r.refused) err.textContent = 'When the capture service last started it would not take ' + r.set + ' and runs at ' + r.limit + ': ' + r.refused;
      inp.value = r.limit; inp.max = r.ceiling; inp.disabled = !!U.readOnly; set.disabled = true;
      note.textContent = r.active + ' open now. Each is one Chrome of up to ' + (r.maxBrowserMemoryMB || '?') + ' MB.' +
        (r.set != null ? ' Set here; the service starts with ' + r.startLimit + ' from capture.properties and is given this one.' : ' From capture.properties.') + ' 1 to ' + r.ceiling + '.' +
        (r.memoryMaxMB != null ? ' The service is held to ' + Math.round(r.memoryMaxMB / 102.4) / 10 + ' GB' + (r.cpuLimit != null ? ' and ' + r.cpuLimit + ' CPU cores' : '') +
          ' by its systemd unit, which is what caps it at ' + r.ceiling + '; running the installer again with a higher maxCaptures raises that.' : '');
    }
    U.api.get('/capture/limit').then(show, function (e) { note.textContent = 'Not read: ' + e.message; });
    inp.oninput = function () { var n = +inp.value; set.disabled = !(n >= 1 && n <= +inp.max && n === Math.round(n)); };
    set.onclick = function () {
      set.disabled = true; err.hidden = true;
      U.api.post('/capture/limit', { maxCaptures: +inp.value }).then(function (r) { show(r); U.toast('Pages captured at once: ' + r.limit + '. No restart was needed.'); U.loadInfo().then(renderStrip, function () {}); },
        function (e) { err.textContent = e.message; err.hidden = false; set.disabled = false; });
    };
  }

  /* ---------- the Manager page around the plugin takes its theme, the navbar excepted ---------- */
  function hostTheme(theme) {
    var h = document.documentElement;
    h.classList.add('uo-host');
    h.setAttribute('data-uo-theme', theme);
  }

  /* ---------- the Manager navbar: sticky parts sit below it when it is fixed ---------- */
  function measure() {
    var nav = document.querySelector('.navbar-fixed-top') || document.querySelector('nav.navbar, .navbar');
    var top = 0, bottom = 0;
    if (nav) {
      var cs = window.getComputedStyle(nav), r = nav.getBoundingClientRect();
      if (cs.position === 'fixed' || cs.position === 'sticky') top = Math.max(0, Math.round(r.bottom));
      bottom = Math.max(0, Math.round(r.bottom));
    }
    root.style.setProperty('--uo-top', top + 'px');
    root.style.setProperty('--uo-nav', bottom + 'px');
  }

  /* ---------- keyboard: Ctrl+S saves the screen in view, ? opens help, lists and tabs move focus, the rest goes to the screen ---------- */
  function onKey(e) {
    if (!U.alive() || U.dialogOpen()) return;
    var a = document.activeElement || {}, typing = /^(INPUT|SELECT|TEXTAREA)$/.test(a.tagName || '') || a.isContentEditable;
    var scr = U.screens[current];
    if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 's') {
      if (scr && scr.save && !U.readOnly) { e.preventDefault(); scr.save(); }
      return;
    }
    if (e.key === 'Escape') {
      if (drawer) { e.preventDefault(); closeDrawer(); return; }
      if (root.querySelector('[data-menu-list]:not([hidden])')) { U.closeMenus(); return; }
      if (el.grid.classList.contains('insp-open')) { el.grid.classList.remove('insp-open'); return; }
    }
    if (drawer || typing) { if (scr && scr.key && !drawer) scr.key(e, true); return; }
    if (e.key === '?') { e.preventDefault(); help(); return; }
    // list tabs: Left and Right move and select
    if (a.getAttribute && a.getAttribute('role') === 'tab' && el.left.contains(a) && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
      e.preventDefault();
      var tabs = Array.prototype.slice.call(el.left.querySelectorAll('[role=tab]')), j = (tabs.indexOf(a) + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length;
      setTab(tabs[j].getAttribute('data-tab'));
      el.left.querySelector('[data-tab="' + U.view.tab + '"]').focus();
      return;
    }
    // lists: Up, Down, Home and End move focus
    if (a.classList && a.classList.contains('li') && /^(ArrowDown|ArrowUp|Home|End)$/.test(e.key)) {
      e.preventDefault();
      var all = Array.prototype.slice.call(el.list.querySelectorAll('.li')), i = all.indexOf(a);
      i = e.key === 'Home' ? 0 : e.key === 'End' ? all.length - 1 : Math.max(0, Math.min(all.length - 1, i + (e.key === 'ArrowDown' ? 1 : -1)));
      all.forEach(function (x) { x.tabIndex = -1; }); all[i].tabIndex = 0; all[i].focus();
      return;
    }
    if (el.left.contains(a) && !(a.classList && a.classList.contains('prow'))) return;
    if (scr && scr.key) scr.key(e, false);
  }

  function onClick(e) {
    var t = e.target.closest && e.target.closest('button, [data-go], .scrim');
    if (!t || !root.contains(t)) { U.closeMenus(); return; }
    if (t.hasAttribute('data-rowfor')) { e.preventDefault(); e.stopPropagation(); U.openRowMenu(t); return; }
    if (t.hasAttribute('data-cupd')) { e.preventDefault(); U.closeMenus(); U.rowActions.copy(t.getAttribute('data-cupd'), 'update'); return; }
    if (!t.closest('.menu')) U.closeMenus();
    if (t.hasAttribute('data-close-after')) closeDrawer();
    if (t.hasAttribute('data-go')) { e.preventDefault(); U.go(t.getAttribute('data-go')); return; }
    if (t.hasAttribute('data-close')) { closeDrawer(); return; }
    if (t.hasAttribute('data-filter')) { U.go('home:', { filter: t.getAttribute('data-filter') }); return; }
    if (t.hasAttribute('data-status-open')) { openDrawer(t.getAttribute('data-status-open'), t); return; }
    if (t.hasAttribute('data-tab') && el.left.contains(t)) { setTab(t.getAttribute('data-tab')); return; }
  }

  /* ---------- the shell's hooks for the store ---------- */
  U.shell = {
    refresh: function () { renderStrip(); if (drawer) renderDrawer(); if (el.list && el.list.getAttribute('data-for') && el.list.getAttribute('data-for').split('|')[2] !== String(!!U.readOnly)) renderList(); },
    state: function () { renderStrip(); renderItems(); if (drawer) renderDrawer(); },
    openDrawer: openDrawer
  };

  /* ---------- mount ---------- */
  U.init = function (r) {
    U.shutdown();
    root = U.root = r;
    current = null; drawer = null; search = {};
    el = { strip: r.querySelector('[data-strip]'), app: r.querySelector('[data-app]'), grid: r.querySelector('[data-grid]'), left: r.querySelector('[data-left]'),
      list: r.querySelector('[data-list]'), center: r.querySelector('[data-center]'), insp: r.querySelector('[data-insp]'), drawer: r.querySelector('[data-drawer-root]'),
      tabSelect: r.querySelector('[data-tab-select]') };
    r.querySelectorAll('[data-icon]').forEach(function (x) { x.innerHTML = U.icon(x.getAttribute('data-icon')); });
    // the operating system's theme until the producer picks one
    var stored = U.store.get('theme');
    var theme = stored === 'dark' || stored === 'light' ? stored : 'light';
    r.setAttribute('data-theme', theme);
    hostTheme(theme);
    var unhost = function () { document.documentElement.classList.remove('uo-host'); document.documentElement.removeAttribute('data-uo-theme'); };
    U.onShutdown(unhost);
    // the Manager swaps its page out some time after the address changes; the colors go back the moment it does
    var gone = new MutationObserver(function () { if (!U.alive()) { unhost(); gone.disconnect(); } });
    gone.observe(document.body, { childList: true, subtree: true });
    U.onShutdown(function () { gone.disconnect(); });
    var tb = r.querySelector('[data-act=theme]');
    tb.innerHTML = U.icon(theme === 'dark' ? 'sun' : 'moon'); tb.setAttribute('aria-pressed', String(theme === 'dark'));
    tb.onclick = function () {
      var dark = r.getAttribute('data-theme') !== 'dark';
      r.setAttribute('data-theme', dark ? 'dark' : 'light');
      hostTheme(dark ? 'dark' : 'light');
      tb.innerHTML = U.icon(dark ? 'sun' : 'moon'); tb.setAttribute('aria-pressed', String(dark));
      U.store.set('theme', dark ? 'dark' : 'light');
    };
    if (U.store.get('keys') === 'on') r.setAttribute('data-keys', 'on');
    r.querySelector('[data-act=help]').onclick = help;
    var sb = r.querySelector('[data-act=settings]');
    sb.innerHTML = U.icon('gear'); sb.onclick = settings;
    el.tabSelect.onchange = function () { setTab(el.tabSelect.value); };
    el.app.onchange = function () {
      var want = el.app.value, un = U.unsaved();
      if (!un.length) { setApp(want); return; }
      U.choose('Leave the unsaved work in ' + U.app + '?', '<p>Switching the application drops what is not saved:</p><ul>' + un.map(function (u) { return '<li>' + U.esc(u.label) + '</li>'; }).join('') + '</ul>',
        [{ label: 'Stay', value: null }, { label: 'Discard and switch', value: 'go', kind: 'danger' }]).then(function (v) {
          if (v === 'go') setApp(want); else el.app.value = U.app;
        });
    };
    Object.keys(U.screens).forEach(function (k) { var box = el.center.querySelector('[data-screen="' + k + '"]'); if (box) U.screens[k].init(box, el.insp); });
    bindTips(r);
    U.listen(document, 'keydown', onKey);
    U.listen(document, 'click', onClick);
    U.listen(window, 'resize', measure);
    U.listen(window, 'scroll', function () { if (drawer || el.grid.classList.contains('insp-open')) measure(); }, { passive: true });
    measure();
    renderStrip();
    U.pageCheck();
    U.discover().then(function () {
      // who may change things decides the page's state before anything is drawn
      return U.loadInfo().catch(function () { /* the poll retries */ });
    }).then(function () {
      U.startInfoPoll();
      return loadApps(U.store.get('app'));
    }).then(function (want) { if (want) setApp(want); }, function () { /* banner shown by the REST layer */ });
  };
})();
