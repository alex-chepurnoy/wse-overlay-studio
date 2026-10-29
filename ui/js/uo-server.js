/* URL Overlay Server view: the capture engine and the network rules for captured pages, read-only, from /info.
   Values come from Server.xml properties; each rule names the property behind it. */
(function () {
  'use strict';
  var U = window.UrlOverlay;
  var el, timer;

  // "today", "yesterday" or "N days ago", from an ISO time
  function daysAgo(iso) {
    var t = Date.parse(iso);
    if (isNaN(t)) return null;
    var start = new Date(); start.setHours(0, 0, 0, 0);
    var d = Math.max(0, Math.ceil((start.getTime() - t) / 86400000));
    return d === 0 ? 'today' : d === 1 ? 'yesterday' : d + ' days ago';
  }

  // the daily Chrome update in one line, and its message below when it failed
  function updateLine(u) {
    if (!u || !u.result) return '';
    var checked = u.checked ? daysAgo(u.checked) : null;
    var state = { current: 'up to date', updated: 'updated', installed: 'up to date', off: 'automatic updates off (autoUpdate=false)',
      own: 'chosen by hand, not updated', failed: 'update failed' }[u.result] || U.esc(u.result);
    var age = u.installed ? daysAgo(u.installed) : null;
    return '<div class="muted"' + (age ? ' title="installed ' + U.esc(age) + '"' : '') + '>' + (checked ? 'checked ' + U.esc(checked) + ', ' : '') + state +
      (u.result === 'current' && u.latest && u.version && u.latest !== u.version ? ' (Stable is ' + U.esc(u.latest) + ')' : '') + '</div>' +
      (u.result === 'failed' && u.message ? '<div class="err">' + U.esc(u.message) + '</div>' : '');
  }

  function list(a) { return a && a.length ? a.map(function (x) { return '<code>' + U.esc(x) + '</code>'; }).join(' ') : 'none'; }

  function render(i) {
    var chrome = i.chrome || {}, drv = i.chromedriver || {}, cap = i.captures || {}, eg = i.egress || {}, svc = i.service || {};
    var ready = svc.running && chrome.found && drv.found && drv.matchesChrome !== false;
    var pct = cap.limit ? Math.min(100, Math.round(100 * cap.active / cap.limit)) : 0;
    var blocks = (eg.recentBlocks || []).map(function (b) {
      return '<tr><td>' + U.esc(new Date(b.at).toLocaleTimeString()) + '</td><td>' + U.esc(b.capture || '') + '</td><td><code>' + U.esc(b.host) +
        '</code></td><td><code>' + U.esc(b.address || '') + '</code></td><td>' + U.esc(b.rule) + '</td></tr>';
    }).join('');
    el.innerHTML =
      '<div class="card"><div class="card-h"><h3>Capture service</h3><span class="tag ' + (ready ? 'ok">ready' : 'bad">not ready') + '</span></div>' +
      (svc.running ? '' : '<div class="notice bad"><div><strong>The capture service is not running.</strong> ' + U.esc(svc.message || '') + '</div></div>') +
      '<div class="kv">' +
      '<div class="muted">Module</div><div>' + U.esc(i.version) + ' on WSE ' + U.esc(i.wseVersion) + ', ' + U.esc(i.os) + '</div>' +
      '<div class="muted">REST access</div><div>' + U.esc(i.accessMode) + ' <span class="key">UrlOverlayServer</span></div>' +
      '<div class="muted">Service</div><div><span class="dot ' + (svc.running ? 'ok' : 'bad') + '"></span>' +
        (svc.running ? U.esc(svc.version || '') + ' as <code>' + U.esc(svc.user || '') + '</code>' : 'not running') +
        ' on <code>' + U.esc(svc.socket || '') + '</code> <span class="key">UrlOverlayCaptureSocket</span></div>' +
      (svc.running ?
      '<div class="muted">Chrome</div><div><span class="dot ' + (chrome.found ? 'ok' : 'bad') + '"></span>' +
        (chrome.found ? U.esc(chrome.version || 'version unknown') : 'not found') + ' <code>' + U.esc(chrome.path || '') + '</code>' + updateLine(i.chromeUpdate) + '</div>' +
      '<div class="muted">chromedriver</div><div><span class="dot ' + (drv.found && drv.matchesChrome !== false ? 'ok' : 'bad') + '"></span>' +
        (drv.found ? U.esc(drv.version || 'version unknown') + (drv.matchesChrome === false ? ', does not match Chrome' : ', matches Chrome') : 'not found') +
        ' <code>' + U.esc(drv.path || '') + '</code></div>' +
      '<div class="muted">Sandbox</div><div><span class="tag ok">always on</span>' + (i.sandboxProblem ? ' <span class="err">' + U.esc(i.sandboxProblem) + '</span>' : '') + '</div>' +
      '<div class="muted">Browser sessions</div><div>' + U.esc(cap.active) + ' of ' + U.esc(cap.limit) +
        '<div class="meter narrow"><i class="' + (pct >= 100 ? 'bad' : pct >= 80 ? 'slow' : '') + '" style="width:' + pct + '%"></i></div></div>' +
      '<div class="muted">Memory per browser</div><div>restarted above ' + U.esc(cap.maxBrowserMemoryMB) + ' MB</div>' : '') +
      '</div><p class="hint">Read-only here. Chrome runs in the URL Overlay capture service under its own account; its limits are in capture.properties, the network rules below in Server.xml.</p></div>' +
      '<div class="card"><div class="card-h"><h3>Network rules for captured pages</h3></div><div class="kv">' +
      '<div class="muted">Private networks</div><div><span class="tag ' + (eg.allowPrivate ? 'warn">allowed' : 'ok">blocked') + '</span> <span class="key">UrlOverlayAllowPrivateNetworks</span></div>' +
      '<div class="muted">Always allowed</div><div>' + list(eg.allowHosts) + ' <span class="key">UrlOverlayAllowHosts</span></div>' +
      '<div class="muted">Always blocked</div><div>' + list(eg.blockHosts) + ' <span class="key">UrlOverlayBlockHosts</span></div>' +
      '<div class="muted">This server</div><div><span class="tag ok">blocked</span> loopback, link-local and this server\'s own addresses, unless allowed by exact address</div>' +
      '<div class="muted">Cloud metadata</div><div><span class="tag ok">blocked</span> unless allowed by exact address</div>' +
      '<div class="muted">Ports</div><div>' + list((eg.ports || []).map(String)) + ' <span class="key">UrlOverlayAllowPorts</span></div>' +
      '<div class="muted">Chrome\'s own services</div><div><span class="tag ' + (eg.blockChromeServices ? 'ok">blocked' : 'warn">allowed') + '</span> ' +
        U.esc(eg.chromeServiceBlocks || 0) + ' requests refused <span class="key">UrlOverlayBlockChromeServices</span></div>' +
      '</div><h3 class="sub-h">Recent blocks</h3>' +
      (blocks ? '<table class="list"><thead><tr><th>Time</th><th>Layer</th><th>Host</th><th>Address</th><th>Rule</th></tr></thead><tbody>' + blocks + '</tbody></table>'
              : '<p class="muted small">No page requests blocked since WSE started.</p>') +
      '</div>';
  }

  function load() {
    return U.api.get('/info').then(render, function (e) {
      el.innerHTML = '<div class="notice bad"><div><strong>Server information is not available.</strong> ' + U.esc(e.message) + '</div></div>';
    });
  }

  U.views['uo-server'] = {
    init: function (section) { el = section.querySelector('[data-server]'); timer = null; },
    show: function () {
      if (timer) timer.stop();
      timer = U.every(5000, function () { return U.root.querySelector('#uo-server.on') ? load() : (timer.stop(), null); });
    }
  };
})();
