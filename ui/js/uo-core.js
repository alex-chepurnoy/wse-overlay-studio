/* URL Overlay core: REST through the Manager bridge, polling, live state, banners, toasts, dialogs, menus, keyboard and
   helpers. The page never reads the Manager's password: the bridge forwards each call with the signed-in user's
   credentials, server-side. */
(function () {
  'use strict';
  var U = window.UrlOverlay = window.UrlOverlay || {};
  var MODULE = '/v1/server/plugin/urloverlay';
  var V2 = '/v2/servers/_defaultServer_/vhosts/_defaultVHost_';
  U.VHOST = '_defaultVHost_';
  U.INSTANCE = '_definst_';

  /* ---------- the Manager bridge: /enginemanager/urloverlay-api, same origin as this page ---------- */
  var API = '/enginemanager/urloverlay-api';
  // There is no way around the bridge, on purpose: the page never calls WSE REST itself. An SSH tunnel to the
  // Manager's port (8088) already reaches the bridge, and the Manager reaches REST locally, so a fallback that sends
  // REST credentials from the browser would add exposure and no reach.
  var bridge = null, token = null, discovering = null;
  var NO_BRIDGE = 'The URL Overlay bridge is not loaded in WSE Manager.';
  // whether the bridge answers, and its version; null when it is missing (404, or the Manager's sign-in page)
  U.discover = function () { return discovering || (discovering = discover()); };
  function discover() {
    return fetch(API + '/status', { cache: 'no-store', credentials: 'same-origin' }).then(function (r) {
      return r.ok ? r.json().catch(function () { return null; }) : null;
    }, function () { return null; }).then(function (st) {
      bridge = st && st.bridge === 'urloverlay' ? st : null;
      U.via = 'the Manager';
      if (!bridge) {
        U.banner('nobridge', 'bad', NO_BRIDGE,
          'Run the URL Overlay installer again, then restart WSE Manager. If the installer said this WSE Manager version is not supported, this page cannot be used with it.');
        return;
      }
      var mine = pageVersion();
      if (mine && bridge.version && bridge.version !== mine) {
        U.banner('bridgeversion', 'slow', 'The Manager bridge is version ' + bridge.version + ' and this page is ' + mine + '.',
          'Run the URL Overlay installer again, then restart WowzaStreamingEngineManager.');
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
  function send(method, path, body, fresh) {
    var opts = { method: method === 'GET' ? 'GET' : 'POST', headers: { 'Accept': 'application/json' }, cache: 'no-store', credentials: 'same-origin' };
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
        if (!r.ok) {
          var msg = (data && typeof data === 'object' && data.message) ? data.message : (r.statusText || ('HTTP ' + r.status));
          if (r.status === 401) U.banner('auth', 'bad', 'The Manager session has ended or has no WSE credentials (401).', 'Sign in to WSE Manager again.');
          throw new ApiError(r.status, msg, data);
        }
        if (data && typeof data === 'object' && data.success === false) throw new ApiError(r.status, data.message || 'Request failed', data);
        // WSE answered, so an earlier "could not be reached" no longer holds (for example while WSE was restarting)
        U.clearBanner('network');
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
  /* ---------- restarting a stream: overlay changes show at once, output changes wait for a restart ---------- */
  // what a restart does to viewers, for every dialog that offers one
  U.RESTART_GAP = 'Restarting disconnects a pushed stream\'s encoder, which reconnects by itself if set to, and resets a pulled one. Viewers see a short gap.';
  U.restartStream = function (stream, confirmed) {
    var ask = confirmed ? Promise.resolve(true) : U.confirm('Restart ' + stream + '?',
      '<p>' + esc(stream) + ' is rebuilt from its template.</p><p>' + U.RESTART_GAP + '</p>', 'Restart ' + stream);
    return ask.then(function (ok) {
      if (!ok) return null;
      return U.api.post(U.streamPath(U.app, stream) + '/restart', {}).then(function (r) {
        U.toast(r.how === 'reset' ? 'Reset ' + stream + '. Its new outputs start in a few seconds.' : 'Disconnected ' + stream + '\'s encoder. Its new outputs start when it reconnects.');
        return U.refreshState(true);
      }, function (e) { U.banner('restart', 'bad', stream + ' was not restarted.', e.message); });
    });
  };
  // after a template write: say which live streams show it now and offer to restart those whose outputs wait
  U.afterTemplateWrite = function (live) {
    live = (live || []).filter(function (x) { return x.application === U.app; });
    if (!live.length) return Promise.resolve();
    var now = live.map(function (x) { return x.stream; }), later = live.filter(function (x) { return x.outputsChanged; }).map(function (x) { return x.stream; });
    U.toast('Shown now on ' + now.join(', ') + (later.length ? '; outputs change after a restart.' : '.'));
    if (!later.length) return Promise.resolve();
    return U.choose('Restart ' + later.join(', ') + ' to change the outputs?', '<p>' + esc(now.join(', ')) + (now.length > 1 ? ' show' : ' shows') + ' the template\'s overlay already. New sizes, bitrates or outputs start only when ' +
      (later.length > 1 ? 'these streams restart' : 'the stream restarts') + ': Wowza builds a stream\'s transcoder when it starts.</p>' +
      '<p>' + U.RESTART_GAP + '</p>',
      [{ label: 'Later', value: null }, { label: 'Restart now', value: 'go', kind: 'primary' }]).then(function (v) {
        if (v !== 'go') return null;
        return later.reduce(function (p, s) { return p.then(function () { return U.restartStream(s, true); }); }, Promise.resolve());
      });
  };
  U.streamPath = function (app, stream) {
    return '/vhosts/' + enc(U.VHOST) + '/applications/' + enc(app) + '/instances/' + enc(U.INSTANCE) + '/streams/' + enc(stream);
  };
  U.vhostPath = function () { return '/vhosts/' + enc(U.VHOST); };
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

  /* ---------- lifecycle: stop timers and players once the Manager replaces the fragment ---------- */
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
    t.stop = function () { t.stopped = true; clearTimeout(t.id); };
    timers.push(t);
    tick();
    return t;
  };
  U.onShutdown = function (fn) { cleanups.push(fn); };
  U.shutdown = function () {
    timers.forEach(function (t) { t.stop(); });
    timers = [];
    cleanups.forEach(function (fn) { try { fn(); } catch (e) { /* page is gone */ } });
    cleanups = [];
  };

  /* ---------- banners ---------- */
  U.banner = function (id, level, title, detail) {
    if (!U.root) return;
    var box = U.root.querySelector('.banners');
    if (!box) return;
    var el = box.querySelector('[data-banner="' + id + '"]');
    if (!el) { el = document.createElement('div'); el.setAttribute('data-banner', id); box.appendChild(el); }
    el.className = 'notice ' + level;
    el.innerHTML = '<div><strong>' + esc(title) + '</strong>' + (detail ? ' ' + esc(detail) : '') + '</div>' +
      '<button type="button" class="x" aria-label="Dismiss">&times;</button>';
    el.querySelector('.x').onclick = function () { el.remove(); };
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
    while (box.children.length > 4) box.firstChild.remove();
    // a message stays while the pointer is on it, so it never closes under a click
    var ms = opts.undo ? 10000 : 5000, timer = setTimeout(function () { t.remove(); }, ms);
    t.addEventListener('mouseenter', function () { clearTimeout(timer); });
    t.addEventListener('mouseleave', function () { timer = setTimeout(function () { t.remove(); }, 3000); });
  };

  /* ---------- dialogs ---------- */
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
      m.innerHTML = '<div class="box" role="dialog" aria-modal="true" aria-label="' + esc(title) + '"><h3>' + esc(title) + '</h3><div>' + bodyHtml + '</div>' +
        '<div class="row end mt-l">' + buttons.map(function (b, i) {
          return '<button type="button" class="btn' + (b.kind ? ' ' + b.kind : '') + '" data-i="' + i + '">' + esc(b.label) + '</button>'; }).join('') + '</div></div>';
      U.root.appendChild(m);
      function done(v) { m.remove(); document.removeEventListener('keydown', key, true); if (back && back.focus) back.focus(); resolve(v); }
      function key(e) { if (e.key === 'Escape') { e.stopPropagation(); done(null); } else trapTab(m, e); }
      document.addEventListener('keydown', key, true);
      m.querySelectorAll('[data-i]').forEach(function (b) { b.onclick = function () { done(buttons[+b.getAttribute('data-i')].value); }; });
      m.addEventListener('click', function (e) { if (e.target === m) done(null); });
      var focus = m.querySelector('.btn.primary') || m.querySelector('[data-i]');
      if (focus) focus.focus();
    });
  };
  U.confirm = function (title, bodyHtml, okLabel, danger) {
    return U.choose(title, bodyHtml, [{ label: 'Cancel', value: false }, { label: okLabel || 'OK', value: true, kind: danger ? 'danger' : 'primary' }])
      .then(function (v) { return !!v; });
  };
  // read(modal) returns a text to show as the error and keep the dialog open, or any other value to close it with
  U.prompt = function (title, fieldsHtml, okLabel, read) {
    return new Promise(function (resolve) {
      var back = document.activeElement;
      var m = document.createElement('div');
      m.className = 'modal';
      m.innerHTML = '<div class="box" role="dialog" aria-modal="true" aria-label="' + esc(title) + '"><h3>' + esc(title) + '</h3>' + fieldsHtml +
        '<div class="err" data-err role="alert"></div>' +
        '<div class="row end mt-l"><button type="button" class="btn" data-a="no">Cancel</button>' +
        '<button type="button" class="btn primary" data-a="yes">' + esc(okLabel || 'OK') + '</button></div></div>';
      U.root.appendChild(m);
      var first = m.querySelector('input,select');
      if (first) first.focus();
      function close(v) { m.remove(); document.removeEventListener('keydown', key, true); if (back && back.focus) back.focus(); resolve(v); }
      function ok() {
        var v = read(m);
        if (typeof v === 'string') { m.querySelector('[data-err]').textContent = v; return; }
        close(v);
      }
      function key(e) {
        if (e.key === 'Escape') { e.stopPropagation(); close(null); }
        else if (e.key === 'Enter' && e.target.tagName === 'INPUT') { e.preventDefault(); e.stopPropagation(); ok(); }
        else trapTab(m, e);
      }
      document.addEventListener('keydown', key, true);
      m.querySelector('[data-a=no]').onclick = function () { close(null); };
      m.querySelector('[data-a=yes]').onclick = ok;
    });
  };

  /* ---------- menus: a [data-menu=x] button opens its [data-menu-list=x]; outside clicks and Escape close it ---------- */
  function closeMenus(except) {
    if (!U.root) return;
    U.root.querySelectorAll('[data-menu-list]').forEach(function (l) {
      if (l === except) return;
      l.hidden = true;
      var b = U.root.querySelector('[data-menu="' + l.getAttribute('data-menu-list') + '"]');
      if (b) b.setAttribute('aria-expanded', 'false');
    });
  }
  function bindMenus(root) {
    root.querySelectorAll('[data-menu]').forEach(function (b) {
      b.onclick = function (e) {
        e.stopPropagation();
        var l = root.querySelector('[data-menu-list="' + b.getAttribute('data-menu') + '"]');
        var open = l.hidden;
        closeMenus(l);
        l.hidden = !open;
        b.setAttribute('aria-expanded', String(open));
        if (open) { var first = l.querySelector('button'); if (first) first.focus(); }
      };
    });
    root.querySelectorAll('[data-menu-list] button').forEach(function (i) { i.addEventListener('click', function () { closeMenus(); }); });
    document.addEventListener('click', onDocClick);
    U.onShutdown(function () { document.removeEventListener('click', onDocClick); });
  }
  function onDocClick(e) { if (!e.target.closest || !e.target.closest('.menu')) closeMenus(); }

  /* ---------- the editors' action bar: status on the left, Discard and Save on the right ---------- */
  // o: { changes, conflict, saving, saveLabel, onSave, onDiscard, onTheirs, onMine, note }
  U.actionBar = function (bar, o) {
    var msg, cls = 'msg';
    if (o.conflict) {
      cls += ' conflict';
      msg = 'Changed in another session. <button type="button" class="btn sm" data-a="theirs">Load theirs</button> <button type="button" class="btn sm" data-a="mine">Keep mine</button>';
    } else if (o.saving) {
      msg = 'Saving...';
    } else if (o.changes) {
      cls += ' dirty';
      msg = o.changes + ' unsaved change' + (o.changes > 1 ? 's' : '');
    } else {
      msg = o.note || 'All changes saved';
    }
    bar.innerHTML = '<span class="' + cls + '">' + msg + '</span><span class="spacer"></span>' +
      '<span class="hint m0"><span class="kbd">Ctrl</span>+<span class="kbd">S</span></span>' +
      '<button type="button" class="btn" data-a="discard"' + (o.changes && !o.saving ? '' : ' disabled') + '>Discard</button>' +
      '<button type="button" class="btn primary" data-a="save"' + (o.changes && !o.saving && !U.readOnly ? '' : ' disabled') + (U.readOnly ? ' title="' + esc(U.readOnly) + '"' : '') + '>' + esc(o.changes ? o.saveLabel || 'Save' : 'Save') + '</button>';
    bar.querySelector('[data-a=save]').onclick = o.onSave;
    bar.querySelector('[data-a=discard]').onclick = o.onDiscard;
    var t = bar.querySelector('[data-a=theirs]'), m = bar.querySelector('[data-a=mine]');
    if (t) t.onclick = o.onTheirs;
    if (m) m.onclick = o.onMine;
  };
  // "Save", or what Save does to streams on air
  U.saveLabel = function (live) {
    return live && live.length ? 'Save and update ' + live.length + ' live stream' + (live.length > 1 ? 's' : '') : 'Save';
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
  U.store = {
    get: function (k) { try { return window.localStorage.getItem('urloverlay.' + k); } catch (e) { return null; } },
    set: function (k, v) { try { window.localStorage.setItem('urloverlay.' + k, v); } catch (e) { /* storage unavailable */ } }
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
    var p = {
      load: function (u) { if (u === url && (hls || video.src)) return; stop(); url = u; delay = 5000; if (u) start(); else onState('none'); },
      stop: function () { url = null; stop(); }
    };
    U.onShutdown(p.stop);
    return p;
  };

  /* ---------- views and theme ---------- */
  U.init = function (root) {
    U.shutdown();
    U.root = root;
    // the operating system's theme until the producer picks one
    var stored = U.store.get('theme');
    var theme = stored === 'dark' || stored === 'light' ? stored :
      (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    root.setAttribute('data-theme', theme);
    var tb = root.querySelector('[data-act=theme]');
    tb.textContent = theme === 'dark' ? 'Light' : 'Dark';
    tb.onclick = function () {
      var dark = root.getAttribute('data-theme') !== 'dark';
      root.setAttribute('data-theme', dark ? 'dark' : 'light');
      tb.textContent = dark ? 'Light' : 'Dark';
      U.store.set('theme', dark ? 'dark' : 'light');
    };
    // WSE setting names under the fields, off unless the producer turns them on in the ? dialog
    if (U.store.get('keys') === 'on') root.setAttribute('data-keys', 'on');
    var nav = root.querySelectorAll('[data-view]');
    function show(id) {
      nav.forEach(function (b) {
        var on = b.getAttribute('data-view') === id;
        if (b.classList.contains('step')) b.setAttribute('aria-current', String(on));
      });
      root.querySelectorAll('.view').forEach(function (v) { v.classList.toggle('on', v.id === id); });
      if (id !== 'uo-server') U.store.set('view', id);
      var was = U.views[U.current];
      if (U.current !== id && was && was.hide) was.hide();
      U.current = id;
      // banners belong to the view that raised them; REST reachability and the application's module state stay
      Array.prototype.forEach.call(root.querySelectorAll('.banners [data-banner]'), function (b) {
        var k = b.getAttribute('data-banner');
        if (['auth', 'network', 'notadded', 'notrecorded', 'stale', 'ipblocked', 'signedout'].indexOf(k) < 0) b.remove();
      });
      if (U.views[id]) U.views[id].show();
    }
    // leaving a view with unsaved changes asks first (each editor's leave() resolves true to go on)
    function go(id) {
      var cur = U.views[U.current];
      if (!cur || !cur.leave || id === U.current) { show(id); return; }
      cur.leave().then(function (ok) { if (ok) show(id); });
    }
    nav.forEach(function (b) { b.onclick = function () { go(b.getAttribute('data-view')); }; });
    root.querySelector('[data-back]').onclick = function () { show(U.store.get('view') || 'uo-overlay'); };
    U.go = go;
    root.querySelector('[data-act=shortcuts]').onclick = shortcuts;
    bindMenus(root);
    document.addEventListener('keydown', onKey);
    U.onShutdown(function () { document.removeEventListener('keydown', onKey); });
    Object.keys(U.views).forEach(function (k) { U.views[k].init(root.querySelector('#' + k)); });
    U.discover().then(function () { startServerPill(root); });
    pageCheck();

    // one application for every step; the steps are about its streams
    var appSel = root.querySelector('[data-app]');
    appSel.onchange = function () {
      var want = appSel.value, cur = U.views[U.current];
      (cur && cur.leave ? cur.leave() : Promise.resolve(true)).then(function (ok) { if (ok) setApp(want); else appSel.value = U.app; });
    };
    function setApp(name) {
      U.app = name || null;
      U.store.set('app', U.app || '');
      U.state = null; U.overlays = []; U.appStreams = []; U.templates = [];
      Object.keys(U.views).forEach(function (k) { if (U.views[k].app) U.views[k].app(U.app); });
      U.refreshState(true).then(function () { if (U.views[U.current]) U.views[U.current].show(); });
      startStatePoll();
      moduleNotice();
    }
    // live applications, those with the module first; the others can have it added from here
    function loadApps(want) {
      return U.api.get(U.vhostPath() + '/applications').then(function (r) {
        U.apps = ((r && r.applications) || []).filter(function (a) { return !a.error && /^live/.test(a.streamType || ''); });
        if (!U.apps.length) {
          appSel.innerHTML = '<option value="">none</option>';
          U.banner('noapp', 'slow', 'This server has no live application.', 'Make one in WSE Manager, then reload this page.');
          return null;
        }
        var with_ = U.apps.filter(function (a) { return a.installed; }), without = U.apps.filter(function (a) { return !a.installed; });
        if (!U.apps.some(function (a) { return a.name === want; })) want = (with_[0] || U.apps[0]).name;
        function opts(list) { return list.map(function (a) { return '<option value="' + U.esc(a.name) + '"' + (a.name === want ? ' selected' : '') + '>' + U.esc(a.name) + '</option>'; }).join(''); }
        appSel.innerHTML = (with_.length ? '<optgroup label="With URL Overlay">' + opts(with_) + '</optgroup>' : '') +
          (without.length ? '<optgroup label="Without it, can be added">' + opts(without) + '</optgroup>' : '');
        return want;
      });
    }

    // an application without the module: everything can be prepared, but nothing draws until it is added and restarted
    function moduleNotice() {
      U.clearBanner('notadded');
      var a = (U.apps || []).filter(function (x) { return x.name === U.app; })[0];
      if (!a || a.installed) return;
      var box = root.querySelector('.banners'), n = document.createElement('div');
      n.setAttribute('data-banner', 'notadded');
      n.className = 'notice slow';
      n.innerHTML = '<div><strong>URL Overlay is not added to ' + U.esc(a.name) + ' yet.</strong> Overlays and templates can be prepared here, ' +
        'but streams in ' + U.esc(a.name) + ' show nothing until the module is added and the application restarts.</div>' +
        '<button type="button" class="btn primary sm" data-act="add-module">Add to ' + U.esc(a.name) + '...</button>';
      box.insertBefore(n, box.firstChild);
      n.querySelector('[data-act=add-module]').onclick = function () { addModule(a.name); };
    }
    function addModule(app) {
      var live = (U.appStreams || []).filter(function (s) { return s.publishing; }).map(function (s) { return s.name; });
      U.choose('Add URL Overlay to ' + app + '?',
        '<p>The URL Overlay module is added to <code>conf/' + U.esc(app) + '/Application.xml</code>, which is backed up first. Uninstalling URL Overlay takes it out again.</p>' +
        '<p>WSE loads modules when an application starts, so ' + U.esc(app) + ' has to restart. ' +
        (live.length ? '<strong>' + live.length + ' stream' + (live.length > 1 ? 's are' : ' is') + ' publishing to it now (' + U.esc(live.join(', ')) + ').</strong> A restart disconnects ' +
          (live.length > 1 ? 'them' : 'it') + ' for a few seconds; encoders reconnect if they are set to.' : 'Nothing is publishing to it now, so a restart affects no stream.') + '</p>',
        [{ label: 'Cancel', value: null }, { label: 'Add, restart later', value: 'later' }, { label: 'Add and restart now', value: 'now', kind: 'primary' }]).then(function (v) {
          if (!v) return;
          U.api.post(U.vhostPath() + '/applications/' + U.enc(app) + '/module', { restart: v === 'now' }).then(function (r) {
            U.toast(r.added ? 'Added URL Overlay to ' + app + (v === 'now' ? ', restarting it now.' : '. Restart ' + app + ' to load it.') : 'URL Overlay was already in ' + app + '.');
            if (r.added && r.recorded === false) U.banner('notrecorded', 'slow', 'The change to ' + app + ' is not in an install record.', 'URL Overlay was installed without the installer, so uninstalling will not take it out of ' + app + '.');
            return loadApps(app).then(function () { moduleNotice(); U.refreshState(true); });
          }, function (e) { U.banner('notadded-err', 'bad', 'URL Overlay was not added to ' + app + '.', e.message); });
        });
    }

    function start() {
      return U.discover().then(function () {
        // who may change things decides the page's state before anything is drawn
        return U.api.get('/info').then(function (i) { U.serverInfo = i; U.applyAccess(i); }, function () { /* the server pill retries */ });
      }).then(function () { return loadApps(U.store.get('app')); }).then(function (want) {
        if (!want) return;
        var last = U.store.get('view');
        U.current = U.views[last] && last !== 'uo-server' ? last : 'uo-overlay';
        show(U.current);
        setApp(want);
      }, function () { /* banner shown by the REST layer */ });
    }
    start();
  };

  /* ---------- live state: one request per poll, every view renders from it ---------- */
  var statePoll = null, pending = null;
  // force: ask for everything, not "unchanged since"; resolves after every view has seen a change
  U.refreshState = function (force) {
    if (!U.app || !U.root) return Promise.resolve();
    if (pending && !force) return pending;
    var app = U.app;
    var q = !force && U.state && U.state.revision ? '?rev=' + U.enc(U.state.revision) : '';
    pending = U.api.get(U.vhostPath() + '/applications/' + U.enc(app) + '/state' + q).then(function (st) {
      pending = null;
      if (U.app !== app || !st || st.unchanged) return;
      var before = U.state;
      U.state = st;
      U.overlays = st.overlays || [];
      U.appStreams = st.streams || [];
      U.templates = st.templates || [];
      notes();
      Object.keys(U.views).forEach(function (k) {
        if (U.views[k].state) { try { U.views[k].state(st, before); } catch (e) { if (window.console) console.error(e); } }
      });
    }, function () { pending = null; });
    return pending;
  };
  function startStatePoll() {
    if (statePoll) statePoll.stop();
    statePoll = U.every(function () { return U.current === 'uo-live' ? 6000 : 2000; }, function () {
      // a hidden tab asks nothing; it catches up on the first poll after it is shown again
      if (document.visibilityState === 'hidden' || U.current === 'uo-server') return null;
      return U.refreshState(false);
    });
  }

  function notes() {
    if (!U.root) return;
    var ovs = U.overlays, streams = U.appStreams;
    var withTpl = ovs.filter(function (o) { return (o.templates || []).length; });
    var onStreams = streams.filter(function (s) { return s.overlay; });
    var live = onStreams.filter(function (s) { return s.publishing; });
    note('uo-overlay', ovs.length, ovs.length ? ovs.map(function (o) { return o.name; }).join(', ') : 'No overlay yet');
    note('uo-template', withTpl.length, withTpl.length ? withTpl.length + ' overlay(s) in a template' : 'No template carries an overlay yet');
    note('uo-streams', onStreams.length, onStreams.length ? onStreams.map(function (s) { return s.name; }).join(', ') + ' use one' : 'Not used by any stream yet');
    note('uo-live', live.length, live.length ? live.length + ' publishing with an overlay' : 'Nothing publishing with an overlay');
  }
  function note(view, done, text) {
    var el = U.root.querySelector('[data-step-note="' + view + '"]');
    if (el) el.textContent = text;
    var b = U.root.querySelector('.step[data-view="' + view + '"]');
    if (b) b.classList.toggle('done', !!done);
  }
  // streams publishing now that show an overlay; what Save changes on air
  U.liveShowing = function (overlay) {
    return (U.appStreams || []).filter(function (s) { return s.publishing && s.overlay === overlay; }).map(function (s) { return s.name; });
  };

  /* ---------- keyboard: Ctrl+S saves the editor in view, ? lists shortcuts, the rest goes to the view ---------- */
  function onKey(e) {
    if (!U.alive() || !U.root.querySelector('.view.on') || U.root.querySelector('.modal')) return;
    var typing = /^(INPUT|SELECT|TEXTAREA)$/.test((document.activeElement || {}).tagName || '');
    var view = U.views[U.current];
    if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 's') {
      if (view && view.save) { e.preventDefault(); view.save(); }
      return;
    }
    if (e.key === 'Escape') closeMenus();
    if (!typing && e.key === '?') { e.preventDefault(); shortcuts(); return; }
    if (view && view.key) view.key(e, typing);
  }
  function shortcuts() {
    var rows = [['Ctrl+S', 'Save the overlay or template in view'], ['Ctrl+Z', 'Undo an unsaved edit'], ['Ctrl+Shift+Z', 'Redo'],
      ['Delete', 'Remove the selected page (Undo in the message)'], ['Arrow keys', 'Nudge the selected page by 1 px'],
      ['Shift+Arrow keys', 'Nudge by 10 px'], ['Escape', 'Deselect, close a menu or dialog'], ['?', 'This list']];
    U.choose('How URL Overlay works', '<p>A camera publishes a stream to a Wowza application. The application\'s transcoder loads a ' +
      '<strong>transcoder template</strong> for the stream, which decodes it, draws the overlay and makes the outputs viewers watch, ' +
      'for example <code>stream_720p</code>. So: make an overlay (1), put it in a template (2), use the template on streams (3), ' +
      'and check what viewers get (4).</p><h4>Keyboard shortcuts</h4><div class="shortcuts">' + rows.map(function (r) {
      return '<span>' + r[0].split('+').map(function (k) { return '<span class="kbd">' + esc(k) + '</span>'; }).join('+') + '</span><span>' + esc(r[1]) + '</span>';
    }).join('') + '</div><p class="hint">Shortcuts other than Ctrl+S work when the cursor is not in a text field.</p>' +
      '<label class="check"><input type="checkbox" data-keys-toggle' + (U.root.getAttribute('data-keys') === 'on' ? ' checked' : '') + '>Show WSE setting names under the fields</label>',
      [{ label: 'Close', value: true, kind: 'primary' }]);
    var t = U.root.querySelector('[data-keys-toggle]');
    if (t) t.onchange = function () {
      if (t.checked) U.root.setAttribute('data-keys', 'on'); else U.root.removeAttribute('data-keys');
      U.store.set('keys', t.checked ? 'on' : 'off');
    };
  }

  /* ---------- a page left open across an upgrade runs the old scripts until it is reloaded ---------- */
  function pageVersion() {
    var s = document.querySelector('script[src*="url-overlay/js/uo-core.js"]');
    var m = s && /[?&]v=([^&]+)/.exec(s.getAttribute('src'));
    return m ? m[1] : null;
  }
  function staleCheck(serverVersion) {
    var mine = pageVersion();
    if (!serverVersion || !mine || mine === serverVersion || U.root.querySelector('[data-banner=stale]')) return;
    var n = document.createElement('div');
    n.setAttribute('data-banner', 'stale');
    n.className = 'notice slow';
    n.innerHTML = '<div><strong>URL Overlay was updated on the server to ' + esc(serverVersion) + '.</strong> This page is still ' + esc(mine) + '; reload it to use the new version.</div>' +
      '<button type="button" class="btn primary sm" data-act="reload">Reload</button>';
    U.root.querySelector('.banners').prepend(n);
    n.querySelector('[data-act=reload]').onclick = function () { window.location.reload(); };
  }
  // the page's own index.html from the Manager: a new script version means the page is stale, a sign-in page that the session ended
  function pageCheck() {
    U.every(30000, function () {
      if (document.visibilityState === 'hidden') return null;
      return fetch('wse-plugins/server/url-overlay/index.html', { cache: 'no-store' }).then(function (r) {
        return r.text().then(function (html) {
          var m = /uo-core\.js\?v=([^"'&]+)/.exec(html);
          if (m) { U.clearBanner('signedout'); staleCheck(m[1]); return; }
          if (U.root.querySelector('[data-banner=signedout]')) return;
          var n = document.createElement('div');
          n.setAttribute('data-banner', 'signedout');
          n.className = 'notice bad';
          n.innerHTML = '<div><strong>The Manager session ended.</strong> WSE Manager was restarted or signed you out, so this page cannot reach WSE. Sign in again, then open URL Overlay.</div>' +
            '<button type="button" class="btn primary sm" data-act="relogin">Sign in</button>';
          U.root.querySelector('.banners').prepend(n);
          n.querySelector('[data-act=relogin]').onclick = function () { window.location.reload(); };
        });
      }, function () { /* the Manager itself is down; the REST banners say so */ });
    });
  }

  /* ---------- read only: the module accepts changes from WSE administrators only ---------- */
  U.readOnly = null;
  U.applyAccess = function (info) {
    var w = info && info.write;
    var ro = w && w.allowed === false ? (w.reason || 'Read only.') : null;
    if (ro === U.readOnly) return;
    U.readOnly = ro;
    if (!U.root) return;
    if (ro) {
      U.root.setAttribute('data-readonly', '');
      U.banner('readonly', 'slow', 'Read only.', ro.replace(/^Read only: /, '') + ' You can look around; Save and other changes are off.');
    } else {
      U.root.removeAttribute('data-readonly');
      U.clearBanner('readonly');
    }
    Object.keys(U.views).forEach(function (k) { if (U.views[k].access) { try { U.views[k].access(ro); } catch (e) { /* view not ready */ } } });
  };

  /* ---------- server status in the header, from /info ---------- */
  function startServerPill(root) {
    var dot = root.querySelector('[data-server-dot]'), text = root.querySelector('[data-server-text]');
    var pill = root.querySelector('[data-server-pill]');
    if (pill) pill.title = 'Server and Chrome. WSE REST reached through ' + (U.via || 'the Manager');
    U.every(15000, function () {
      if (document.visibilityState === 'hidden') return null;
      return U.api.get('/info').then(function (i) {
        U.serverInfo = i;
        U.applyAccess(i);
        // one banner on every view while pages cannot be captured; streams keep their last picture
        if (i.service && i.service.running === false) U.banner('captureservice', 'bad', 'The capture service is not running.', i.service.message || '');
        else U.clearBanner('captureservice');
        var c = i.chrome || {}, d = i.chromedriver || {}, cap = i.captures || {};
        var ready = i.service && i.service.running && c.found && d.found && d.matchesChrome !== false;
        dot.className = 'dot ' + (ready ? 'ok' : 'bad');
        staleCheck(i.version);
        text.textContent = ready ? 'Chrome ' + String(c.version || '').split('.')[0] + ' ready, ' + (cap.active || 0) + ' of ' + (cap.limit || 0) + ' pages open'
                                 : i.service && i.service.running === false ? 'Capture service not running' : 'Chrome not ready';
      }, function () { dot.className = 'dot bad'; text.textContent = 'Server not reachable'; });
    });
  }
  U.views = U.views || {};
})();
