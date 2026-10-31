/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* URL Overlay server status, for the status drawer: the capture service, Chrome, the browser sessions and the network
   rules for captured pages, read-only, from /info. Values come from Server.xml properties; each rule names its
   property, which a WSE administrator edits. */
(function () {
  'use strict';
  var U = window.UrlOverlay;

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
    return '<div class="muted small"' + (age ? ' title="installed ' + U.esc(age) + '"' : '') + '>' + (checked ? 'checked ' + U.esc(checked) + ', ' : '') + state +
      (u.result === 'current' && u.latest && u.version && u.latest !== u.version ? ' (Stable is ' + U.esc(u.latest) + ')' : '') + '</div>' +
      (u.result === 'failed' && u.message ? '<div class="err">' + U.esc(u.message) + '</div>' : '');
  }
  function list(a) { return a && a.length ? a.map(function (x) { return '<code>' + U.esc(x) + '</code>'; }).join(' ') : 'none'; }
  function ready(i) {
    var c = i.chrome || {};
    return !!(i.service && i.service.running && c.found);
  }
  U.serverReady = ready;

  // sections of the status drawer, each with an id the header items open at
  U.serverSections = function (i) {
    if (!i) return [['capture', 'Capture service', U.infoError ? '<div class="note bad"><span class="dot bad"></span><div><b>Server information is not available.</b> ' + U.esc(U.infoError.message) + '</div></div>' : '<div class="skel"></div>']];
    var chrome = i.chrome || {}, cap = i.captures || {}, eg = i.egress || {}, svc = i.service || {};
    var pct = cap.limit ? Math.min(100, Math.round(100 * cap.active / cap.limit)) : 0;
    var blocks = (eg.recentBlocks || []).map(function (b) {
      return '<tr><td>' + U.esc(new Date(b.at).toLocaleTimeString()) + '</td><td><code>' + U.esc(b.host) + '</code>' + (b.address ? '<br><span class="mono faint">' + U.esc(b.address) + '</span>' : '') +
        '</td><td>' + U.esc(b.rule) + (b.capture ? '<br><span class="faint">' + U.esc(b.capture) + '</span>' : '') + '</td></tr>';
    }).join('');
    var capture = (svc.running ? '' : '<div class="note bad"><span class="dot bad"></span><div><b class="err">The capture service is not running.</b> ' + U.esc(svc.message || '') +
        ' Every overlay is frozen on its last picture, not missing. A WSE administrator starts the <span class="mono">WowzaURLOverlayCapture</span> service on the server.</div></div>') +
      '<dl class="kv"' + (svc.running ? '' : ' style="margin-top:12px"') + '>' +
      '<dt>State</dt><dd>' + (ready(i) ? '<span class="bdg on">Running</span>' : svc.running ? '<span class="bdg bad">Chrome not ready</span>' : '<span class="bdg bad">Stopped</span>') + '</dd>' +
      '<dt>Module</dt><dd>' + U.esc(i.version) + ' on WSE ' + U.esc(i.wseVersion) + ', ' + U.esc(i.os) + '</dd>' +
      '<dt>REST access</dt><dd>' + U.esc(i.accessMode) + ' <span class="key">UrlOverlayServer</span></dd>' +
      '<dt>Service</dt><dd>' + (svc.running ? U.esc(svc.version || '') + ' as <code>' + U.esc(svc.user || '') + '</code>' : 'not running') +
        ' on <code>' + U.esc(svc.socket || '') + '</code> <span class="key">UrlOverlayCaptureSocket</span></dd>' +
      (svc.running ?
      '<dt>Chrome</dt><dd><span class="dot ' + (chrome.found ? 'on' : 'bad') + '"></span> ' + (chrome.found ? U.esc(chrome.version || 'version unknown') + (chrome.headlessShell ? ', headless shell' : '') : 'not found') +
        ' <code>' + U.esc(chrome.path || '') + '</code>' + updateLine(i.chromeUpdate) + '</dd>' +
      '<dt>Sandbox</dt><dd>always on' + (i.sandboxProblem ? ' <span class="err">' + U.esc(i.sandboxProblem) + '</span>' : '') + '</dd>' : '') +
      '</dl><p class="small faint" style="margin-top:8px">Chrome runs in the URL Overlay capture service under its own account; its limits are in capture.properties. If it stops, streams keep the last captured picture.</p>';
    var browsers = '<dl class="kv"><dt>Open</dt><dd>' + U.esc(cap.active || 0) + ' of ' + U.esc(cap.limit || 0) + ' pages, in ' + U.esc(cap.browsers || 0) + ' Chrome' + (cap.browsers === 1 ? '' : 's') +
        (cap.pagesPerBrowser ? ' of up to ' + U.esc(cap.pagesPerBrowser) + ' pages each' : '') + '. Identical pages are captured once.' +
        '<div class="meter"><i class="' + (pct >= 100 ? 'bad' : pct >= 80 ? 'warn' : '') + '" style="width:' + pct + '%"></i></div></dd>' +
      '<dt>Memory</dt><dd>a page is restarted when its Chrome uses more than ' + U.esc(cap.maxBrowserMemoryMB) + ' MB for each page it holds</dd></dl>';
    var network = '<table class="tbl kvt"><tbody>' +
      '<tr><td>Private networks</td><td><b>' + (eg.allowPrivate ? 'Allowed' : 'Blocked') + '</b><br><span class="mono faint">UrlOverlayAllowPrivateNetworks</span></td></tr>' +
      '<tr><td>Always allowed</td><td>' + list(eg.allowHosts) + '<br><span class="mono faint">UrlOverlayAllowHosts</span></td></tr>' +
      '<tr><td>Always blocked</td><td>' + list(eg.blockHosts) + '<br><span class="mono faint">UrlOverlayBlockHosts</span></td></tr>' +
      '<tr><td>This server</td><td><b>Blocked</b>: loopback, link-local and this server\'s own addresses, unless allowed by exact address</td></tr>' +
      '<tr><td>Cloud metadata</td><td><b>Blocked</b> unless allowed by exact address</td></tr>' +
      '<tr><td>Ports</td><td>' + list((eg.ports || []).map(String)) + '<br><span class="mono faint">UrlOverlayAllowPorts</span></td></tr>' +
      '<tr><td>Chrome\'s own services</td><td><b>' + (eg.blockChromeServices ? 'Blocked' : 'Allowed') + '</b>, ' + U.esc(eg.chromeServiceBlocks || 0) +
        (eg.chromeServiceBlocks === 1 ? ' request refused' : ' requests refused') + '<br><span class="mono faint">UrlOverlayBlockChromeServices</span></td></tr></tbody></table>' +
      '<div class="sec-h" style="margin-top:16px">Recent blocks</div>' +
      (blocks ? '<table class="tbl"><thead><tr><th>Time</th><th>Host</th><th>Rule</th></tr></thead><tbody>' + blocks + '</tbody></table>'
        : '<p class="small muted">No page requests blocked since WSE started.</p>') +
      '<p class="small" style="margin-top:8px">To allow a blocked page, the narrow fix first: a WSE administrator adds that one host to <span class="mono">UrlOverlayAllowHosts</span> in Server.xml. ' +
      'Broader, if many internal pages are needed: <span class="mono">UrlOverlayAllowPrivateNetworks</span> set to true. Either way WSE must restart, and every stream on this server reconnects.</p>';
    return [['capture', 'Capture service', capture], ['browsers', 'Pages and Chrome', browsers], ['network', 'Network rules for captured pages', network]];
  };
})();
