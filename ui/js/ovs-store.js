/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio store: the application state poll, the server information poll, and the selectors every screen reads
   from them. One state request per poll; the shell and the screens render from it. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  U.state = null; U.overlays = []; U.appStreams = []; U.templates = []; U.added = [];

  /* ---------- application state: one request per poll, with ?rev= so an unchanged state answers short ---------- */
  var statePoll = null, pending = null;
  // force: ask for everything, not "unchanged since"; resolves after the shell and every screen have seen a change
  U.refreshState = function (force) {
    if (!U.app || !U.root) return Promise.resolve();
    if (pending && !force) return pending;
    var app = U.app;
    var q = !force && U.state && U.state.revision ? '?rev=' + U.enc(U.state.revision) : '';
    pending = U.api.get(U.appPath(app) + '/state' + q).then(function (st) {
      pending = null;
      if (U.app !== app || !st) return;
      // nothing else changed: viewers come with the short answer, and only the counts on screen are updated
      if (st.unchanged) { if (st.viewers) applyViewers(st.viewers); return; }
      var before = U.state;
      U.state = st;
      U.overlays = st.overlays || [];
      U.appStreams = st.streams || [];
      U.templates = st.templates || [];
      if (U.shell) U.shell.state(st, before);
      Object.keys(U.screens).forEach(function (k) {
        if (U.screens[k].state) { try { U.screens[k].state(st, before); } catch (e) { if (window.console) console.error(e); } }
      });
    }, function () { pending = null; });
    return pending;
  };
  function applyViewers(counts) {
    var moved = false;
    (U.appStreams || []).forEach(function (r) {
      if (!r.publishing) return;
      var n = counts[r.name] != null ? counts[r.name] : 0;
      if (r.viewers !== n) { r.viewers = n; moved = true; }
    });
    if (!moved) return;
    Object.keys(U.screens).forEach(function (k) {
      if (U.screens[k].viewers) { try { U.screens[k].viewers(); } catch (e) { if (window.console) console.error(e); } }
    });
  }
  U.startStatePoll = function () {
    if (statePoll) statePoll.stop();
    // U.every skips a hidden tab; the first poll after it is shown again catches up
    statePoll = U.every(2000, function () { return U.refreshState(false); });
  };

  /* ---------- server information: header, read-only, capture service ---------- */
  U.loadInfo = function () {
    return U.api.get('/info').then(function (i) {
      U.serverInfo = i;
      U.applyAccess(i);
      // streams keep their last picture while pages cannot be captured; one banner says so on every screen
      if (i.service && i.service.running === false) U.banner('captureservice', 'bad', 'The capture service is not running, so every overlay is frozen on its last picture.', i.service.message || '');
      else U.clearBanner('captureservice');
      U.staleCheck(i.version);
      U.infoError = null;
      if (U.shell) U.shell.refresh();
      return i;
    }, function (e) { U.infoError = e; if (U.shell) U.shell.refresh(); throw e; });
  };
  U.startInfoPoll = function () { U.every(15000, function () { return U.loadInfo().catch(function () {}); }); };

  /* ---------- selectors ---------- */
  U.appEntry = function (name) { return (U.apps || []).filter(function (a) { return a.name === (name || U.app); })[0] || null; };
  U.appInstalled = function () { var a = U.appEntry(); return !!(a && a.installed); };
  // streams publishing or known by an own template, plus names added by hand that have not started yet
  U.streams = function () {
    var rows = (U.appStreams || []).slice();
    (U.added || []).forEach(function (n) { if (!rows.some(function (r) { return r.name === n; })) rows.push({ name: n, publishing: false, template: null, own: false, added: true }); });
    return rows.sort(function (a, b) { return a.name.localeCompare(b.name); });
  };
  U.streamRow = function (name) { return U.streams().filter(function (s) { return s.name === name; })[0] || null; };
  U.live = function () { return (U.appStreams || []).filter(function (s) { return s.publishing; }); };
  U.overlayEntry = function (name) { return (U.overlays || []).filter(function (o) { return o.name === name; })[0] || null; };
  U.templateEntry = function (name) { return (U.templates || []).filter(function (t) { return t && t.name === name; })[0] || null; };
  // the template file a stream loads, as its entry; null when it is not transcoded
  U.templateOfStream = function (row) { return row && row.template ? U.templateEntry(String(row.template).replace(/\.xml$/i, '')) : null; };
  // the template a stream's own copy was made from, or the shared template it loads
  U.baseOfStream = function (row) { return row ? (row.own ? U.copyBase(row) : (row.template ? String(row.template).replace(/\.xml$/i, '') : null)) : null; };
  // streams publishing now that show an overlay; what a Save changes on air
  U.liveShowing = function (overlay) {
    return (U.appStreams || []).filter(function (s) { return s.publishing && s.overlay === overlay; }).map(function (s) { return s.name; });
  };
  U.usersOfOverlay = function (overlay) { return (U.appStreams || []).filter(function (s) { return s.overlay === overlay; }); };
  // streams that load a template: directly through the template order, or through an own copy made from it
  U.streamsUsing = function (name) {
    return (U.appStreams || []).filter(function (s) { return (!s.own && s.template === name + '.xml') || (s.own && U.copyBase(s) === name); });
  };
  // the outputs a template builds, and whether each is configured to carry its overlay
  U.outputsOf = function (t) {
    if (!t) return [];
    var listed = t.overlayEncodes || [];
    return (t.encodes || []).map(function (e) {
      var pass = /^passthru$/i.test(String(e.codec || '')), audio = /^disable/i.test(String(e.codec || ''));
      return { name: e.name, enabled: !!e.enabled, passthrough: pass, audio: audio,
        carries: !!t.overlay && !!e.canCarryOverlay && !!e.enabled && (!listed.length || listed.indexOf(e.name) >= 0) };
    }).filter(function (o) { return o.enabled; });
  };
  // what one output says when nothing is measured yet: configuration only, labeled as such
  U.configuredText = function (o) {
    return o.passthrough ? 'Passthrough, no overlay' : o.audio ? 'Audio only' : o.carries ? 'Carries the overlay' : 'No overlay';
  };
})();
