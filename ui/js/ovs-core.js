/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio core: REST through the Manager bridge, timers and shutdown, banners, toasts, dialogs, menus, the save
   bar, players, the page version and session checks, and read-only. The page never reads the Manager's password: the
   bridge forwards each call with the signed-in user's credentials, server-side. */
(function () {
  'use strict';
  var U = window.OverlayStudio = window.OverlayStudio || {};
  var MODULE = '/v1/server/plugin/overlaystudio';
  var V2 = '/v2/servers/_defaultServer_/vhosts/_defaultVHost_';
  U.VHOST = '_defaultVHost_';
  U.INSTANCE = '_definst_';
  U.screens = U.screens || {};

  /* ---------- the Manager bridge: /enginemanager/overlaystudio-api, same origin as this page ---------- */
  var API = '/enginemanager/overlaystudio-api';
  // There is no way around the bridge, on purpose: the page never calls WSE REST itself. An SSH tunnel to the
  // Manager's port (8088) already reaches the bridge, and the Manager reaches REST locally, so a fallback that sends
  // REST credentials from the browser would add exposure and no reach.
  var bridge = null, token = null, discovering = null, out = false;
  var NO_BRIDGE = 'The Overlay Studio bridge is not loaded in WSE Manager.';
  // once the Manager's session ends (it restarted, or timed out) it answers every call with its sign-in page, as a 200
  function isSignIn(text) { return /<!--\s*Redirect to Login\s*-->/i.test(String(text || '').slice(0, 200)); }
  // one banner whichever check notices first: a call answered by the sign-in page, or the page-file poll below
  function signedOut() {
    U.banner('signedout', 'bad', 'You are signed out of WSE Manager.', 'WSE Manager was restarted or signed you out, so nothing here can be read or saved. Unsaved changes stay on this page while you sign in from another tab.',
      { label: 'Sign in again', run: function () { window.open('/enginemanager/login.htm', '_blank'); } });
  }
  U.signedOut = signedOut;
  // whether the bridge answers, and its version; null when it is missing (404, or the Manager's sign-in page)
  U.discover = function () { return discovering || (discovering = discover()); };
  function discover() {
    out = false;
    return fetch(API + '/status', { cache: 'no-store', credentials: 'same-origin' }).then(function (r) {
      return !r.ok ? null : r.text().then(function (t) { if (isSignIn(t)) { out = true; return null; } try { return JSON.parse(t); } catch (e) { return null; } });
    }, function () { return null; }).then(function (st) {
      bridge = st && st.bridge === 'overlaystudio' ? st : null;
      U.via = 'the Manager';
      // signed out is not a missing bridge: ask again once the user has signed in
      if (out) { discovering = null; signedOut(); return; }
      if (!bridge) {
        U.banner('nobridge', 'bad', NO_BRIDGE,
          'Run the Overlay Studio installer again, then restart WSE Manager. If the installer said this WSE Manager version is not supported, this page cannot be used with it.');
        return;
      }
      var mine = pageVersion();
      if (mine && bridge.version && bridge.version !== mine) {
        U.banner('bridgeversion', 'slow', 'The Manager bridge is version ' + bridge.version + ' and this page is ' + mine + '.',
          'Run the Overlay Studio installer again, then restart WowzaStreamingEngineManager.');
      }
    });
  }
  // the session's security token, which the Manager requires on every POST; fetched again after it was refused
  function securityToken(fresh) {
    if (token && !fresh) return Promise.resolve(token);
    return fetch(API + '/token', { cache: 'no-store', credentials: 'same-origin' }).then(function (r) {
      return r.ok ? r.json() : null;
    }).then(function (j) { token = j && j.token ? j.token : null; return token; }, function () { token = null; return null; });
  }
  function withQuery(path, params) {
    var q = Object.keys(params).filter(function (k) { return params[k] != null; })
      .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); }).join('&');
    return q ? path + (path.indexOf('?') >= 0 ? '&' : '?') + q : path;
  }

  /* ---------- REST ---------- */
  function ApiError(status, message, body) { this.status = status; this.message = message; this.body = body; }
  ApiError.prototype = Object.create(Error.prototype);

  // GET as GET; POST, PUT and DELETE as a POST carrying the session token, PUT and DELETE named in ?method=
  // longer than the bridge's own 60 s read timeout, so a slow capture still answers; a request that hangs past it
  // ends, and the poll waiting on it starts again
  var REQUEST_TIMEOUT_MS = 65000;
  function send(method, path, body, fresh) {
    var opts = { method: method === 'GET' ? 'GET' : 'POST', headers: { 'Accept': 'application/json' }, cache: 'no-store', credentials: 'same-origin' };
    if (window.AbortSignal && AbortSignal.timeout) opts.signal = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    if (!bridge) return Promise.reject(new ApiError(503, NO_BRIDGE, null));
    if (method === 'GET') return fetch(API + '/rest' + path, opts);
    return securityToken(fresh).then(function (t) {
      return fetch(API + '/rest' + withQuery(path, { wowzaSecurityToken: t, method: method === 'POST' ? null : method }), opts);
    });
  }

  function request(method, path, body) {
    if (method !== 'GET' && U.readOnly) {
      U.toast(U.readOnly, { level: 'bad' });
      return Promise.reject(new ApiError(403, U.readOnly, null));
    }
    return U.discover().then(function () {
      if (out) throw new ApiError(401, 'You are signed out of WSE Manager.', null);
      return send(method, path, body, false).then(function (r) {
        // the Manager renews its token with the session: fetch it again and try once more
        if (bridge && method !== 'GET' && r.status === 403) {
          return r.clone().text().then(function (t) {
            return /CSRF/i.test(t) ? send(method, path, body, true) : r;
          });
        }
        return r;
      });
    }).then(function (r) {
      return r.text().then(function (text) {
        var data = null;
        if (text) { try { data = JSON.parse(text); } catch (e) { data = text; } }
        // only an answer that is not JSON can be the sign-in page; data that happens to hold its marker is data
        if (typeof data === 'string' && isSignIn(text)) { signedOut(); throw new ApiError(401, 'You are signed out of WSE Manager.', null); }
        // anything else that is not JSON is not an answer from the module
        if (r.ok && typeof data === 'string') throw new ApiError(502, 'WSE Manager answered with a page instead of data.', null);
        if (!r.ok) {
          var msg = (data && typeof data === 'object' && data.message) ? data.message : (r.statusText || ('HTTP ' + r.status));
          if (r.status === 401) U.banner('auth', 'bad', 'The Manager session has ended or has no WSE credentials (401).', 'Sign in to WSE Manager again.');
          throw new ApiError(r.status, msg, data);
        }
        if (data && typeof data === 'object' && data.success === false) throw new ApiError(r.status, data.message || 'Request failed', data);
        // WSE answered, so an earlier "could not be reached" or "signed out" no longer holds
        U.clearBanner('network'); U.clearBanner('auth'); U.clearBanner('signedout');
        return data;
      });
    }, function (e) {
      if (e instanceof ApiError) throw e;
      U.banner('network', 'bad', 'WSE could not be reached from this browser.', 'Check that WSE Manager and WSE are running.');
      throw new ApiError(0, 'WSE REST could not be reached', null);
    });
  }

  U.api = {
    get: function (p) { return request('GET', MODULE + p); },
    post: function (p, b) { return request('POST', MODULE + p, b); },
    put: function (p, b) { return request('PUT', MODULE + p, b); },
    del: function (p) { return request('DELETE', MODULE + p); },
    v2del: function (p) { return request('DELETE', V2 + p); }
  };
  U.streamPath = function (app, stream) {
    return '/vhosts/' + enc(U.VHOST) + '/applications/' + enc(app) + '/instances/' + enc(U.INSTANCE) + '/streams/' + enc(stream);
  };
  U.vhostPath = function () { return '/vhosts/' + enc(U.VHOST); };
  U.appPath = function (app) { return U.vhostPath() + '/applications/' + enc(app || U.app); };

  /* ---------- restarting a stream: overlay changes show at once, output changes wait for a restart ---------- */
  // viewers come from WSE's per-stream counts and only count this server; null when the module does not send them
  U.viewers = function (row) { return row && typeof row.viewers === 'number' ? row.viewers : null; };
  U.viewersText = function (n) { return n + ' viewer' + (n === 1 ? '' : 's') + ' on this server'; };
  // what a restart does to one stream's viewers, for every dialog that disconnects them
  U.restartLines = function (row) {
    var n = U.viewers(row), s = esc(row.name);
    return '<p>' + (n != null ? '<b>' + U.viewersText(n) + '</b> ' + (n === 1 ? 'sees' : 'see') + ' a gap of a few seconds.' : 'Viewers see a gap of a few seconds.') + '</p>' +
      '<p>' + (row.pulled ? s + ' is pulled by WSE, so it resets and is pulled again.' : row.arrives === 'server' ? s + ' is published inside WSE and restarts with its application.' : s + ' is pushed by an encoder, which reconnects by itself within seconds if it is set to.') + '</p>';
  };
  U.arrivalText = function (r) { return r.pulled ? 'pulled: resets' : r.arrives === 'server' ? 'published inside WSE: restarts with the application' : 'pushed: the encoder reconnects within seconds'; };
  U.FINAL = '<p class="dlg-final">This cannot be undone once viewers are disconnected.</p>';
  // a stream published inside WSE, by a server listener for example, has no publisher to disconnect: only restarting
  // its application starts it again, and that reconnects every stream in the application
  U.restartApp = function (why, confirmed) {
    var live = U.live(), app = U.app;
    return (confirmed ? Promise.resolve(true) : U.confirm('Restart the application ' + app + '?',
      (why ? '<p>' + why + '</p>' : '') + '<div class="dlg-streams">' + live.map(function (r) {
        var n = U.viewers(r);
        return '<span class="chip"><span class="dot live"></span>' + esc(r.name) + (n != null ? '<span class="faint">' + U.icon('eye') + n + '</span>' : '') + '</span>';
      }).join('') + '</div><p class="small muted">Each stops for a few seconds. Streams published inside WSE come back with the application; encoders reconnect if set to.</p>',
      'Restart ' + app, true)).then(function (ok) {
        if (!ok) return null;
        return request('PUT', V2 + '/applications/' + enc(app) + '/actions/restart').then(function () {
          U.toast('Restarted ' + app + '. Its streams start again in a few seconds.');
          return U.refreshState(true).then(function () { return true; });
        }, function (e) { U.banner('restart', 'bad', app + ' was not restarted.', e.message); });
      });
  };
  U.restartStream = function (stream, confirmed, why) {
    var row = U.streamRow(stream) || { name: stream };
    if (row.arrives === 'server') {
      return U.restartApp('<b>' + esc(stream) + '</b> is published inside WSE and restarts only with ' + esc(U.app) + '.', confirmed);
    }
    var ask = confirmed ? Promise.resolve(true) : U.confirm('Restart ' + stream + '?',
      U.restartLines(row) + '<p>' + (why || esc(stream) + ' is rebuilt from its template, so pending output changes take effect.') + '</p>' + U.FINAL, 'Restart ' + stream);
    return ask.then(function (ok) {
      if (!ok) return null;
      return U.api.post(U.streamPath(U.app, stream) + '/restart', {}).then(function (r) {
        U.toast(r.how === 'reset' ? 'Reset ' + stream + '. Its new outputs start in a few seconds.' : 'Disconnected ' + stream + '\'s encoder. Its new outputs start when it reconnects.');
        return U.refreshState(true).then(function () { return true; });
      }, function (e) { U.banner('restart', 'bad', stream + ' was not restarted.', e.message); });
    });
  };
  // after a template write: say which live streams show it now and offer to restart those whose outputs wait
  U.afterTemplateWrite = function (live) {
    live = (live || []).filter(function (x) { return x.application === U.app; });
    if (!live.length) return Promise.resolve();
    var now = live.map(function (x) { return x.stream; }), later = live.filter(function (x) { return x.outputsChanged; }).map(function (x) { return x.stream; });
    U.toast('Live now on ' + now.join(', ') + (later.length ? '; outputs change after a restart.' : '.'));
    if (!later.length) return Promise.resolve();
    var rows = later.map(function (s) { return U.streamRow(s) || { name: s }; });
    return U.choose('Restart ' + later.join(', ') + ' to change the outputs?',
      '<p>The overlay is live already. New sizes, bitrates or outputs start only when ' + (later.length > 1 ? 'these streams restart' : 'the stream restarts') +
      ': WSE builds a stream\'s transcoder when it starts.</p><ul>' + rows.map(function (r) {
        var n = U.viewers(r);
        return '<li><b>' + esc(r.name) + '</b>' + (n != null ? ', ' + U.viewersText(n) : '') + ', ' + U.arrivalText(r) + '</li>';
      }).join('') + '</ul>' + U.FINAL,
      [{ label: 'Later', value: null }, { label: 'Restart now', value: 'go', kind: 'primary' }]).then(function (v) {
        if (v !== 'go') return null;
        return later.reduce(function (p, s) { return p.then(function () { return U.restartStream(s, true); }); }, Promise.resolve());
      });
  };
  // HLS straight from WSE's streaming port at the host name this page was loaded from; an https page needs an SSL port
  U.hls = function (url) {
    if (!url) return null;
    var page = window.location, u = url.replace('{host}', page.hostname);
    if (page.protocol === 'https:' && /^http:/.test(u)) {
      var ssl = ((U.serverInfo && U.serverInfo.streamingPorts) || []).filter(function (x) { return x.ssl; })[0];
      if (ssl) u = u.replace(/^http:\/\/([^/:]+):\d+/, 'https://$1:' + ssl.port);
    }
    return u;
  };
  // a manifest the browser could not read at all: the port is closed to it, or the answer had no CORS headers
  function hlsUnreachable(url) {
    var origin = '';
    try { origin = new URL(url).origin; } catch (e) { origin = url; }
    U.banner('hlscors', 'slow', 'The preview cannot load from ' + origin + '.',
      'This page plays HLS straight from WSE\'s streaming port. Check that the port is open to this browser' +
      (window.location.protocol === 'https:' ? ' and serves SSL, since an https page cannot load http video' : '') +
      '. WSE sends Access-Control-Allow-Origin: * on HLS by default; if the application\'s HTTPStreamer properties set ' +
      'cupertinoCORSHeadersEnabled or httpCORSHeadersEnabled to false, the browser refuses the playlist. Set them to true or remove them.');
  }
  function enc(s) { return encodeURIComponent(s); }
  U.enc = enc;
  // a stream's own copy of a template names its base in its description
  var COPY = /copy of ([A-Za-z0-9._-]+) for stream /;
  U.copyBase = function (x) { var m = COPY.exec((x && x.description) || ''); return m ? m[1] : null; };

  /* ---------- lifecycle: timers and listeners stop once the Manager replaces the fragment ---------- */
  var timers = [];
  var cleanups = [];
  U.alive = function () { return !!(U.root && document.body.contains(U.root)); };
  U.every = function (ms, fn) {
    var t = { stopped: false, id: null };
    function tick() {
      if (t.stopped) return;
      if (!U.alive()) { U.shutdown(); return; }
      var next = function () { if (!t.stopped) t.id = setTimeout(tick, typeof ms === 'function' ? ms() : ms); };
      // a hidden page asks nothing; the first tick after it is shown again catches up
      if (document.visibilityState === 'hidden') { next(); return; }
      try {
        var p = fn();
        if (p && typeof p.then === 'function') p.then(next, next); else next();
      } catch (e) { next(); }
    }
    // a stopped timer leaves the list, so screens opened and closed all day do not pile them up
    t.stop = function () { t.stopped = true; clearTimeout(t.id); var i = timers.indexOf(t); if (i >= 0) timers.splice(i, 1); };
    timers.push(t);
    tick();
    return t;
  };
  // @return a function that takes fn back off the list, for something stopped before the page goes
  U.onShutdown = function (fn) {
    cleanups.push(fn);
    return function () { var i = cleanups.indexOf(fn); if (i >= 0) cleanups.splice(i, 1); };
  };
  // a listener on document or window, removed at shutdown so a remount never stacks a second one
  U.listen = function (target, type, fn, opts) {
    target.addEventListener(type, fn, opts);
    U.onShutdown(function () { target.removeEventListener(type, fn, opts); });
  };
  U.shutdown = function () {
    timers.slice().forEach(function (t) { t.stop(); });
    timers = [];
    var list = cleanups;
    cleanups = [];
    list.forEach(function (fn) { try { fn(); } catch (e) { /* page is gone */ } });
  };

  /* ---------- banners: problems with the page or the server; results go to toasts ---------- */
  U.banner = function (id, level, title, detail, action) {
    if (!U.root) return;
    var box = U.root.querySelector('.banners');
    if (!box) return;
    var el = box.querySelector('[data-banner="' + id + '"]');
    if (!el) { el = document.createElement('div'); el.setAttribute('data-banner', id); box.appendChild(el); }
    el.className = 'banner ' + level;
    el.innerHTML = '<div><strong>' + esc(title) + '</strong>' + (detail ? ' ' + esc(detail) : '') + '</div>' +
      (action ? '<button type="button" class="btn primary sm" data-a="act">' + esc(action.label) + '</button>' : '') +
      '<button type="button" class="x" aria-label="Dismiss">&times;</button>';
    el.querySelector('.x').onclick = function () { el.remove(); };
    if (action) el.querySelector('[data-a=act]').onclick = action.run;
  };
  U.clearBanner = function (id) {
    var el = U.root && U.root.querySelector('.banners [data-banner="' + id + '"]');
    if (el) el.remove();
  };

  /* ---------- toasts: results, with Undo where the action can be undone ---------- */
  U.toast = function (text, opts) {
    opts = opts || {};
    var box = U.root && U.root.querySelector('[data-toasts]');
    if (!box) return;
    var t = document.createElement('div');
    t.className = 'toast' + (opts.level === 'bad' ? ' bad' : '');
    t.setAttribute('role', 'status');
    t.innerHTML = '<span>' + esc(text) + '</span>';
    if (opts.undo) {
      var b = document.createElement('button');
      b.type = 'button'; b.textContent = 'Undo';
      b.onclick = function () { t.remove(); opts.undo(); };
      t.appendChild(b);
    }
    var x = document.createElement('button');
    x.type = 'button'; x.setAttribute('aria-label', 'Dismiss'); x.innerHTML = '&times;';
    x.onclick = function () { t.remove(); };
    t.appendChild(x);
    box.appendChild(t);
    while (box.children.length > 3) box.firstChild.remove();
    // a message stays while the pointer is on it, so it never closes under a click
    var ms = opts.undo ? 10000 : 5000, timer = setTimeout(function () { t.remove(); }, ms);
    t.addEventListener('mouseenter', function () { clearTimeout(timer); });
    t.addEventListener('mouseleave', function () { timer = setTimeout(function () { t.remove(); }, 3000); });
  };

  /* ---------- dialogs: only the confirms the design names; Cancel has the first focus ---------- */
  U.dialogOpen = function () { return !!(U.root && U.root.querySelector('.modal')); };
  // keeps Tab inside the dialog
  function trapTab(m, e) {
    if (e.key !== 'Tab') return;
    var f = Array.prototype.filter.call(m.querySelectorAll('button,input,select,textarea,[tabindex]'), function (x) { return !x.disabled && x.offsetParent !== null; });
    if (!f.length) return;
    var first = f[0], last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
  // buttons: [{ label, value, kind: 'primary' | 'danger' | undefined }]; Escape and the backdrop answer null
  U.choose = function (title, bodyHtml, buttons) {
    return new Promise(function (resolve) {
      var back = document.activeElement;
      var m = document.createElement('div');
      m.className = 'modal';
      m.innerHTML = '<div class="box" role="alertdialog" aria-modal="true" aria-labelledby="ovs-dlg-h"><div class="pnl-h"><h3 id="ovs-dlg-h">' + esc(title) + '</h3></div>' +
        '<div class="pnl-b">' + bodyHtml + '</div><div class="foot">' + buttons.map(function (b, i) {
          return '<button type="button" class="btn' + (b.kind ? ' ' + b.kind : '') + '" data-i="' + i + '">' + esc(b.label) + '</button>'; }).join('') + '</div></div>';
      U.root.appendChild(m);
      var off = null;
      function done(v) { m.remove(); document.removeEventListener('keydown', key, true); if (off) off(); if (back && back.focus && document.body.contains(back)) back.focus(); resolve(v); }
      function key(e) { if (e.key === 'Escape') { e.stopPropagation(); done(null); } else trapTab(m, e); }
      document.addEventListener('keydown', key, true);
      off = U.onShutdown(function () { document.removeEventListener('keydown', key, true); });
      m.querySelectorAll('[data-i]').forEach(function (b) { b.onclick = function () { done(buttons[+b.getAttribute('data-i')].value); }; });
      m.addEventListener('click', function (e) { if (e.target === m) done(null); });
      var cancel = buttons.map(function (b, i) { return b.value == null || b.value === false ? i : -1; }).filter(function (i) { return i >= 0; })[0];
      var focus = m.querySelector('[data-i="' + (cancel != null ? cancel : 0) + '"]');
      if (focus) focus.focus();
    });
  };
  U.confirm = function (title, bodyHtml, okLabel, danger) {
    return U.choose(title, bodyHtml, [{ label: 'Cancel', value: false }, { label: okLabel || 'OK', value: true, kind: danger ? 'danger' : 'primary' }])
      .then(function (v) { return !!v; });
  };

  /* ---------- menus: a [data-menu=x] button opens its [data-menu-list=x]; outside clicks and Escape close it ---------- */
  U.closeMenus = function (except) {
    if (!U.root) return;
    U.root.querySelectorAll('[data-menu-list]').forEach(function (l) {
      if (l === except) return;
      l.hidden = true;
      U.root.querySelectorAll('[data-menu="' + l.getAttribute('data-menu-list') + '"]').forEach(function (b) { b.setAttribute('aria-expanded', 'false'); });
    });
  };
  // binds the menus inside box; call again after the box is re-rendered
  U.bindMenus = function (box) {
    box.querySelectorAll('[data-menu]:not([data-rowfor])').forEach(function (b) {
      b.onclick = function (e) {
        e.stopPropagation();
        var l = box.querySelector('[data-menu-list="' + b.getAttribute('data-menu') + '"]');
        var open = l.hidden;
        U.closeMenus(l);
        l.hidden = !open;
        b.setAttribute('aria-expanded', String(open));
        if (open) { var first = l.querySelector('button:not(:disabled)'); if (first) first.focus(); }
      };
    });
    box.querySelectorAll('[data-menu-list] button').forEach(function (i) { i.addEventListener('click', function () { U.closeMenus(); }); });
  };
  // items: [[act, label, danger, disabled], '-', ...]; for: what the actions are on, as "kind:id" (U.rowActions[kind])
  // opts.html: the button's own content instead of the "more" icon, with opts.cls (the Add menus)
  U.rowMenuHtml = function (target, label, items, opts) {
    opts = opts || {};
    return '<button type="button" class="btn sm ' + (opts.html ? '' : 'icon ') + 'ghost rowmenu' + (opts.cls ? ' ' + opts.cls : '') + '" data-write aria-haspopup="menu" aria-expanded="false" aria-label="' + esc(label) + '" title="' + esc(label) + '" data-menu="rowfloat" data-rowfor="' + esc(target) + '" data-rowitems="' +
      esc(JSON.stringify(items)) + '">' + (opts.html || U.icon('more')) + '</button>';
  };
  U.rowActions = {};
  U.openRowMenu = function (b) {
    var l = U.root.querySelector('[data-menu-list="rowfloat"]');
    if (!l) { l = document.createElement('div'); l.className = 'menu-list rowfloat'; l.setAttribute('role', 'menu'); l.setAttribute('data-menu-list', 'rowfloat'); l.hidden = true; U.root.appendChild(l); }
    var was = !l.hidden && l.getAttribute('data-for') === b.getAttribute('data-rowfor');
    U.closeMenus();
    if (was) return;
    var items = JSON.parse(b.getAttribute('data-rowitems') || '[]'), target = b.getAttribute('data-rowfor');
    l.setAttribute('data-for', target);
    l.innerHTML = items.map(function (i) {
      return i === '-' ? '<hr>' : '<button type="button" role="menuitem" data-rowact="' + esc(i[0]) + '"' + (i[2] ? ' class="danger"' : '') + (i[3] ? ' disabled' : '') + '>' + esc(i[1]) + '</button>';
    }).join('');
    l.hidden = false;
    b.setAttribute('aria-expanded', 'true');
    var r = b.getBoundingClientRect(), w = l.offsetWidth, h = l.offsetHeight;
    l.style.left = Math.max(8, Math.min(window.innerWidth - w - 8, r.left)) + 'px';
    l.style.top = (r.bottom + 4 + h > window.innerHeight - 8 ? Math.max(8, r.top - h - 4) : r.bottom + 4) + 'px';
    l.querySelectorAll('[data-rowact]').forEach(function (x) {
      x.onclick = function (e) {
        e.stopPropagation();
        U.closeMenus();
        var i = target.indexOf(':'), kind = target.slice(0, i), id = target.slice(i + 1);
        if (U.rowActions[kind]) U.rowActions[kind](id, x.getAttribute('data-rowact'));
      };
    });
    var first = l.querySelector('button:not(:disabled)'); if (first) first.focus();
  };
  U.menuHtml = function (id, label, items) {
    return '<div class="menu" data-write><button class="btn sm icon" type="button" aria-haspopup="menu" aria-expanded="false" aria-label="' + esc(label) + '" data-menu="' + id + '">' + U.icon('more') + '</button>' +
      '<div class="menu-list" role="menu" data-menu-list="' + id + '" hidden>' + items.map(function (i) {
        return i === '-' ? '<hr>' : '<button type="button" role="menuitem" data-mi="' + i[0] + '"' + (i[2] ? ' class="danger"' : '') + (i[3] ? ' disabled' : '') + '>' + esc(i[1]) + '</button>';
      }).join('') + '</div></div>';
  };

  /* ---------- the save bar: one per screen, covering only the object on that screen ---------- */
  // o: { changes: [text], impact, saving, problem, conflict, saveLabel, saved, onSave, onDiscard, onTheirs, onMine }
  // The bar is rewritten only when what it shows changes. A field's change event fires on the mousedown that clicks
  // Save or Discard, and the render it causes draws the same bar; replacing the buttons then would lose that click.
  function barHtml(bar, html) {
    if (bar.ovsHtml === html) return;
    bar.ovsHtml = html;
    bar.innerHTML = html;
  }
  U.saveBar = function (bar, o) {
    if (!bar) return;
    var n = (o.changes || []).length;
    if (U.readOnly || (!n && !o.saved && !o.conflict)) { bar.hidden = true; barHtml(bar, ''); return; }
    bar.hidden = false;
    bar.className = 'savebar' + (n || o.conflict ? '' : ' saved');
    bar.setAttribute('role', 'region');
    bar.setAttribute('aria-label', n ? 'Unsaved changes' : 'Last save');
    if (!n && !o.conflict) {
      barHtml(bar, '<span class="dot on"></span><div class="what"><b>' + esc(o.saved.title) + '</b><span>' + esc(o.saved.detail || '') + '</span></div>');
      return;
    }
    var what = o.changes.join(', ');
    var sub = o.conflict ? '<span class="err">Changed in another session since you started. Load theirs, or keep yours and Save over it.</span>'
      : o.problem ? '<span class="err">' + esc(o.problem) + '</span>' : '<span title="' + esc(o.impact || '') + '">' + esc(o.impact || '') + '</span>';
    barHtml(bar, '<span class="chip-draft">Unsaved</span><div class="what"><b title="' + esc(what) + '">' + esc(what || 'No changes') + '</b>' + sub + '</div><div class="acts">' +
      (o.conflict ? '<button type="button" class="btn" data-a="theirs">Load theirs</button><button type="button" class="btn" data-a="mine">Keep mine</button>' : '') +
      '<span class="hint"><span class="kbd">Ctrl</span>+<span class="kbd">S</span></span>' +
      '<button type="button" class="btn" data-a="discard"' + (o.saving || !n ? ' disabled' : '') + '>Discard</button>' +
      '<button type="button" class="btn primary" data-a="save"' + (o.saving || o.problem || o.conflict || !n ? ' disabled' : '') + '>' + esc(o.saving ? 'Saving...' : o.saveLabel || 'Save') + '</button></div>');
    // the handlers are set on every call, kept bar or not, so they always run this render's callbacks
    bar.querySelector('[data-a=save]').onclick = o.onSave;
    bar.querySelector('[data-a=discard]').onclick = o.onDiscard;
    var t = bar.querySelector('[data-a=theirs]'), m = bar.querySelector('[data-a=mine]');
    if (t) t.onclick = o.onTheirs;
    if (m) m.onclick = o.onMine;
  };
  U.hhmm = function (d) { d = d || new Date(); return (d.getHours() < 10 ? '0' : '') + d.getHours() + ':' + (d.getMinutes() < 10 ? '0' : '') + d.getMinutes(); };
  // one line pointing at another screen's unsaved work: shown and linked, never saved or discarded from here
  U.scopeLine = function (text, label, go) {
    return '<div class="scope-line"><span class="chip-draft">Unsaved</span><span>' + text + ' Not part of this Save.</span><span class="spacer"></span>' +
      '<button type="button" class="btn sm" data-go="' + esc(go) + '">' + esc(label) + '</button></div>';
  };

  /* ---------- helpers ---------- */
  function esc(s) {
    return String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  U.esc = esc;
  U.ago = function (iso) {
    if (!iso) return 'never';
    var s = (Date.now() - new Date(iso).getTime()) / 1000;
    if (s < 0) s = 0;
    return s < 60 ? s.toFixed(1) + ' s ago' : Math.round(s / 60) + ' min ago';
  };
  U.mb = function (bytes) { return bytes == null ? 'n/a' : Math.round(bytes / 1048576) + ' MB'; };
  U.names = function (list) { return list.join(', '); };
  U.store = {
    get: function (k) { try { return window.localStorage.getItem('overlaystudio.' + k); } catch (e) { return null; } },
    set: function (k, v) { try { window.localStorage.setItem('overlaystudio.' + k, v); } catch (e) { /* storage unavailable */ } }
  };
  var ICONS = {
    plus: '<path d="M12 5v14M5 12h14"/>',
    close: '<path d="M6 6l12 12M18 6L6 18"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    overlay: '<rect x="3" y="5" width="18" height="14" rx="2"/><rect x="12" y="8" width="6" height="4" rx="1"/>',
    play: '<path d="M7 5l12 7-12 7z"/>',
    grid: '<rect x="4" y="4" width="7" height="7"/><rect x="13" y="4" width="7" height="7"/><rect x="4" y="13" width="7" height="7"/><rect x="13" y="13" width="7" height="7"/>',
    up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
    down: '<path d="M12 5v14M6 13l6 6 6-6"/>',
    chev: '<path d="M6 9l6 6 6-6"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/>',
    panel: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M15 4v16"/>',
    eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1"/><path d="M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/>',
    audio: '<path d="M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2"/>',
    input: '<rect x="2" y="6" width="14" height="12" rx="2"/><path d="M16 10l6-3v10l-6-3"/>',
    outputs: '<path d="M4 6h16M4 12h11M4 18h7"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
    sub: '<path d="M6 4v9a3 3 0 0 0 3 3h9M14 12l4 4-4 4"/>',
    web: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
    text: '<path d="M5 6V4h14v2M12 4v16M9 20h6"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    shape: '<rect x="3" y="11" width="9" height="9" rx="1"/><circle cx="16" cy="8" r="5"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><path d="M21 17l-5-5-9 9"/>',
    chip: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>'
  };
  U.debounce = function (fn, ms) { var t = null; return function () { var a = arguments, me = this; clearTimeout(t); t = setTimeout(function () { fn.apply(me, a); }, ms); }; };
  // how often an overlay's pages are captured, as three named speeds plus the exact value. What a page costs depends
  // more on how often it changes than on the speed (docs/USAGE.md, "Pages at once")
  U.SPEEDS = [[0.5, 'Standard', 'Scoreboards and lower thirds'], [0.25, 'Fast', 'Clocks and counters'], [0.1, 'Fastest', 'Tickers; costs most on pages that animate all the time']];
  // field: the exact seconds, shown on the same line, so the named speeds and the number are one control
  U.speedHtml = function (value, attr, disabled, field) {
    var known = U.SPEEDS.some(function (s) { return s[0] === value; });
    return '<div class="speed"><span class="f">Capture speed' + U.tip('How often each page is captured and redrawn on the video, for every page of this overlay. Faster shows changes sooner and costs the capture service CPU; the transcoder cost hardly changes.') + '</span>' +
      '<div class="speed-row"><div class="seg" role="radiogroup" aria-label="Capture speed">' + U.SPEEDS.map(function (s) {
        return '<button type="button" role="radio" ' + attr + '="' + s[0] + '" aria-checked="' + (s[0] === value) + '" title="' + esc(s[2]) + '"' + (disabled ? ' disabled' : '') + '>' + s[1] + (field ? '' : '<small>' + s[0] + ' s</small>') + '</button>';
      }).join('') + (known || field ? '' : '<button type="button" role="radio" aria-checked="true" disabled>Custom<small>' + esc(value) + ' s</small></button>') + '</div>' + (field || '') + '</div>' +
      '<span class="small muted" data-speednote>' + esc((U.SPEEDS.filter(function (s) { return s[0] === value; })[0] || [0, '', 'Custom'])[2]) + '</span></div>';
  };
  // the named speed that matches the exact value is lit, or none for a custom one
  U.syncSpeed = function (root, attr, value) {
    root.querySelectorAll('[' + attr + ']').forEach(function (x) { x.setAttribute('aria-checked', String(parseFloat(x.getAttribute(attr)) === value)); });
    var s = U.SPEEDS.filter(function (x) { return x[0] === value; })[0], note = root.querySelector('[data-speednote]');
    if (note) note.textContent = s ? s[2] : 'Custom';
  };
  U.bindSpeed = function (root, attr, onPick) {
    root.querySelectorAll('[' + attr + ']').forEach(function (b) {
      b.onclick = function () {
        var v = parseFloat(b.getAttribute(attr));
        root.querySelectorAll('[' + attr + ']').forEach(function (x) { x.setAttribute('aria-checked', String(x === b)); });
        var s = U.SPEEDS.filter(function (x) { return x[0] === v; })[0], note = b.closest('.speed').querySelector('.small');
        if (s && note) note.textContent = s[2];
        onPick(v);
      };
    });
  };
  // help that the eye can skip: a small "?" that shows the text on hover or focus, and reads it to a screen reader
  U.tip = function (text) { return '<button type="button" class="tip" aria-label="' + esc(text) + '" data-tip="' + esc(text) + '">?</button>'; };
  U.icon = function (name) {
    if (name === 'more') return '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>';
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (ICONS[name] || '') + '</svg>';
  };

  /* ---------- players ---------- */
  // One hls.js player per video element; reattaches with backoff while the stream is missing.
  U.player = function (video, onState) {
    var hls = null, url = null, retry = null, delay = 5000;
    function stop() {
      clearTimeout(retry);
      if (hls) { hls.destroy(); hls = null; }
      video.removeAttribute('src');
      try { video.load(); } catch (e) { /* detached */ }
    }
    function fail() {
      stop();
      onState('waiting', delay);
      retry = setTimeout(function () { if (U.alive() && url) start(); }, delay);
      delay = Math.min(delay * 2, 30000);
    }
    function start() {
      if (!url) return;
      onState('loading');
      video.muted = true;
      video.playsInline = true;
      if (window.Hls && window.Hls.isSupported()) {
        hls = new window.Hls({ lowLatencyMode: false });
        hls.on(window.Hls.Events.MANIFEST_PARSED, function () { delay = 5000; U.clearBanner('hlscors'); onState('playing'); video.play().catch(function () {}); });
        hls.on(window.Hls.Events.ERROR, function (e, d) {
          if (!d || !d.fatal) return;
          // status 0: no answer the page may read, as opposed to a stream that is not publishing (404)
          if (d.details === window.Hls.ErrorDetails.MANIFEST_LOAD_ERROR && d.response && d.response.code === 0) hlsUnreachable(url);
          fail();
        });
        hls.loadSource(url);
        hls.attachMedia(video);
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = url;
        video.onloadedmetadata = function () { onState('playing'); video.play().catch(function () {}); };
        video.onerror = fail;
      } else {
        onState('unsupported');
      }
    }
    var off = null;
    var p = {
      load: function (u) {
        if (u === url && (hls || video.src)) return;
        stop(); url = u; delay = 5000;
        if (u) { if (!off) off = U.onShutdown(p.stop); playing.add(p); watchVisibility(); start(); } else onState('none');
      },
      stop: function () { url = null; stop(); playing.delete(p); if (off) { off(); off = null; } },
      // a hidden tab stops fetching segments and decoding; shown again, it goes back to live
      suspend: function () { if (hls) hls.stopLoad(); try { video.pause(); } catch (e) { /* detached */ } },
      resume: function () { if (hls) hls.startLoad(-1); if (url) video.play().catch(function () {}); }
    };
    return p;
  };
  var playing = new Set(), watching = false;
  function watchVisibility() {
    if (watching) return;
    watching = true;
    U.listen(document, 'visibilitychange', function () {
      playing.forEach(function (p) { if (document.visibilityState === 'hidden') p.suspend(); else p.resume(); });
    });
    U.onShutdown(function () { watching = false; playing.clear(); });
  }

  /* ---------- a page left open across an upgrade runs the old scripts until it is reloaded ---------- */
  function pageVersion() {
    var s = document.querySelector('script[src*="overlay-studio/js/ovs-core.js"]');
    var m = s && /[?&]v=([^&]+)/.exec(s.getAttribute('src'));
    return m ? m[1] : null;
  }
  U.staleCheck = function (serverVersion) {
    var mine = pageVersion();
    if (!serverVersion || !mine || mine === serverVersion || U.root.querySelector('[data-banner=stale]')) return;
    U.banner('stale', 'slow', 'Overlay Studio was updated on the server to ' + serverVersion + '.', 'This page is still ' + mine + '; reload it to use the new version.',
      { label: 'Reload', run: function () { window.location.reload(); } });
  };
  // the page's own index.html from the Manager: a new script version means the page is stale, a sign-in page that the session ended
  U.pageCheck = function () {
    U.every(30000, function () {
      return fetch('wse-plugins/server/overlay-studio/index.html', { cache: 'no-store' }).then(function (r) {
        return r.text().then(function (html) {
          var m = /ovs-core\.js\?v=([^"'&]+)/.exec(html);
          if (m) { U.clearBanner('signedout'); U.staleCheck(m[1]); return; }
          U.signedOut();
        });
      }, function () { /* the Manager itself is down; the REST banners say so */ });
    });
  };

  /* ---------- read only: the module accepts changes from WSE administrators only ---------- */
  U.readOnly = null;
  U.applyAccess = function (info) {
    var w = info && info.write;
    var ro = w && w.allowed === false ? (w.reason || 'Read only.') : null;
    if (ro === U.readOnly) return;
    U.readOnly = ro;
    if (!U.root) return;
    if (ro) U.root.setAttribute('data-readonly', ''); else U.root.removeAttribute('data-readonly');
    var chip = U.root.querySelector('[data-ro-chip]');
    if (chip) { chip.hidden = !ro; chip.title = ro || ''; }
    Object.keys(U.screens).forEach(function (k) { if (U.screens[k].access) { try { U.screens[k].access(ro); } catch (e) { /* screen not ready */ } } });
    if (U.shell) U.shell.refresh();
  };
  // one line per section of a read-only screen
  U.roNote = function () { return U.readOnly ? '<p class="ro-note">Changes need a WSE administrator.</p>' : ''; };
})();
